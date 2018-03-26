package eu.slipo.poi

import java.net.URI
import java.io.PrintWriter
import java.util.Calendar

import net.sansa_stack.rdf.spark.io.NTripleReader
import net.sansa_stack.rdf.spark.model.TripleRDD
import org.apache.spark.rdd._
import org.apache.spark.sql._
import org.apache.jena.graph.Triple
import org.apache.spark.SparkContext
import eu.slipo.algorithms.Distances
import eu.slipo.algorithms.PIC
import eu.slipo.datatypes.Cluster
import eu.slipo.datatypes.Clusters
import eu.slipo.datatypes.Coordinate
import eu.slipo.datatypes.Poi
import eu.slipo.datatypes.Categories
import net.liftweb.json._
  import Extraction._

object poiClustering {
  
    val dataSource = "resources/data/tomtom_pois_austria_v0.3.nt"  // there are 312385 pois for tomtom and 350053 for herold
    val termValueUri = "http://slipo.eu/def#termValue"
    val termPrefix = "http://slipo.eu/id/term/" 
    val typePOI = "http://slipo.eu/def#POI"
    val coordinatesPredicate = "http://www.opengis.net/ont/geosparql#asWKT"
    val categoryPOI = "http://slipo.eu/def#category"
    val termPOI = "http://slipo.eu/def#termValue"
    val poiPrefix = "http://slipo.eu/id/poi/"
    val categoriesFile = "resources/results/categories"
    val results = "resources/results/clusters.txt"
    val poiCategoriesFile = "resources/results/poi_categories"
    val runTimeStatics = "resources/results/runtime.txt"
    val now = Calendar.getInstance()
    val fileWriter = new PrintWriter(results)
        
    /*
     * Write (category_id, category_values_set) to file
     * */
    def getCategoryValues(sparkSession: SparkSession, data: RDD[Triple]): RDD[(Int, Categories)] = {
      // get category id(s)
      val categoriesValue = data.filter(x => x.getPredicate.toString().equalsIgnoreCase(termValueUri))
      // get category id and it's corresponding values
      val categoriesIdValues = categoriesValue.map(x => (x.getSubject.toString().replace(termPrefix, "").toInt, x.getObject.toString()))
      // group by id and put all values of category to a set
      categoriesIdValues.groupByKey().sortByKey().map(x => (x._1, Categories(x._2.toSet)))
    }
    
    /*
     * Write clustering results to file
     * */
    def writeClusteringResult(clusters: Map[Int, Array[Long]], pois: RDD[Poi]) = {
      val assignments = clusters.toList.sortBy { case (k, v) => v.length }
      println("assignments")
      val poisKeyPair = pois.keyBy(f => f.id)
      println("number of pois: %i", poisKeyPair.count())
      val clustersPois = Clusters(assignments.map(f => Cluster(f._1, f._2.map(x => poisKeyPair.lookup(x.toInt).head))))
      println("number of clusters: %i", clustersPois.clusters.size)
      implicit val formats = DefaultFormats
      val clustersJson = decompose(clustersPois)
      //val assignmentsStr = assignments.map { case (k, v) => s"$k -> ${v.mkString("[", ",", "]")}, ${v.map(poi => (poiCoordinates.lookup(poi.toInt).head, poiCategories.lookup(poi.toInt).head.mkString("(", "," ,")"))).mkString("[", ",", "]")}, ${v.map(poi => poiCategories.lookup(poi.toInt).head.map(category => categories.lookup(category).mkString(",")).mkString("(", ",", ")")).mkString("[", ",", "]")}"}.mkString("\n")
      //val sizesStr = assignments.map {_._2.length}.sorted.mkString("(", ",", ")")
      //fileWriter.println(s"Cluster Assignments:\n $assignmentsStr\n")
      println("number of clusters: %i", assignments.length)
      fileWriter.println(clustersJson.toString())
    }
    
    def generatePois(sparkSession: SparkSession, poiCoordinates: RDD[(Int, Coordinate)], poiCategoryIds: RDD[(Int, Iterable[Int])], categories: RDD[(Int, Categories)]): RDD[Poi] = {
      //poiCategoryIds.map(f => Poi(f._1, poiCoordinates.lookup(f._1).head, categories.lookup(f._1).head))
      poiCoordinates.map(f => Poi(f._1, f._2, categories.lookup(f._1).head))
    }
    
    
    def main(args: Array[String]){
      
      val spark = SparkSession.builder
      .master("local[*]")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .appName("Triple reader resources/data/tomtom_pois_austria_v0.3.nt")
      .getOrCreate()
      spark.conf.set("spark.executor.memory", "10g")
      spark.conf.set("spark.driver.memory", "10g")
      System.setProperty("hadoop.home.dir", "C:\\winutil\\");
      
      // read NTriple file, get RDD contains triples
      val dataRDD = NTripleReader.load(spark, dataSource)
      
      // get the coordinates of pois
      val pattern = "POINT(.+ .+)".r
      val poiCoordinates = dataRDD.filter(x => x.getPredicate.toString().equalsIgnoreCase(coordinatesPredicate)).map(x => (x.getSubject.toString().replace(poiPrefix, "").replace("/geometry", "").toInt, pattern.findFirstIn(x.getObject.toString()).head.replace("POINT", "").replace("^^http://www.opengis.net/ont/geosparql#wktLiteral", "").replaceAll("^\"|\"$", "")))
      val poiCleanCoordinates = poiCoordinates.mapValues(x => {val coordinates = x.replace("(", "").replace(")", "").split(" ")
                                    Coordinate(coordinates(0).toDouble, coordinates(1).toDouble)})
      
      // find pois in vinna, 72549 in total for herold
      val poiVinna = poiCleanCoordinates.filter(x => (x._2.latitude>=(16.192851) && x._2.latitude<=(16.593533)) && (x._2.longitude>=(48.104194) && x._2.longitude<=(48.316388))).sample(false, 0.001, 0).persist()
      val keys = poiVinna.keys.collect()

      
      // find all the categories of pois, which are in Vinna
      val poiFlatCategories = dataRDD.filter(x => x.getPredicate.toString().equalsIgnoreCase(categoryPOI))
      val poiCategoriesVinna = poiFlatCategories.filter(x => keys.contains(x.getSubject.toString().replace(poiPrefix, "").toInt))
      
      // from 'Node' to (poi_id, category_id) pairs, possible with duplicated keys
      val poiRawCategories = poiCategoriesVinna.map(x => (x.getSubject.toString().replace(poiPrefix, "").toInt, x.getObject.toString().replace(termPrefix, "").toInt))
      
      // get the categories for each poi, sample 1% to reduce the computation costs
      val poiCategories = poiRawCategories.groupByKey().persist() // .sample(false, 0.001, 0)
      
      
      //oneHotEncoding(poiCategories, spark)
      
      println(s"poi Vinna: ${poiRawCategories.count().toInt}")
      // get the number of pois, and save corresponding categories
      val numberPOIs = poiCategories.count().toString().toInt
      fileWriter.println(s"Number of POIs: ${numberPOIs}\n")
      
      // considering PIC https://spark.apache.org/docs/1.5.1/mllib-clustering.html, build ((sid, ()), (did, ())) RDD
      val pairwisePOICategories = poiCategories.cartesian(poiCategories).filter{ case (a, b) => a._1.toInt < b._1.toInt }
      
      // from ((sid, ()), (did, ())) to (sid, did, similarity)
      val pairwisePOISimilarity = pairwisePOICategories.map(x => (x._1._1.toString().toLong, x._2._1.toString().toLong, new Distances().jaccardSimilarity(x._1._2.toSet, x._2._2.toSet)))
      pairwisePOISimilarity.persist()
      
      // get the coordinates 
      
      // distance RDD, from (sid, did, similarity) to (sid, did, distance)
      //val distancePairs = pairwisePOISimilarity.map(x => (x._1, x._2, 1.0 - x._3))
      
      // generate coordindates in 2 dimension
      //val coordinates = multiDimensionScaling(distancePairs, numberPOIs, 2).map(x => (x(0), x(1)))
     
      // kmeans clustering, number of clusters 2
      //println(kmeansClustering(coordinates, spark, 2))
      
      // dbscan clustering, TODO solve scala version flicts with SANSA
      // dbscanClustering(coordinates, spark)
      
      // pic clustering, 20 centroids and 5 iterations
      val clusters = new PIC().picSparkML(pairwisePOISimilarity, 3, 1, spark)
      val categoryIdValues = getCategoryValues(spark, dataRDD)
      val pois = generatePois(spark, poiVinna, poiCategories, categoryIdValues)
      println("number of pois %i", pois.count())
      writeClusteringResult(clusters, pois)
      
      // stop spark session
      fileWriter.close()
      spark.stop()
    }
}
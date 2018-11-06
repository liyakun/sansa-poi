package eu.slipo.evaluation

import eu.slipo.datatypes.Clusters
import org.json4s.DefaultFormats
import org.json4s.native.JsonMethods
import org.scalatest.FunSuite

class PurityTest extends FunSuite{
  val clustersJson = """{
                         "numOfClusters" : 1,
                         "clusterSizes" : [ 2 ],
                         "clusters" : [
                           {
                             "cluster_id" : 1,
                             "poi_in_cluster" : [
                               {
                               "poi_id" : 1,
                               "coordinate" : {
                               "longitude" : 1.0,
                               "latitude" : 1.0
                               },
                               "categories" : {
                               "categories" : [ "test1", "test2"]
                                },
                               "review" : 1.0
                               },
                               {
                               "poi_id" : 2,
                               "coordinate" : {
                               "longitude" : 2.0,
                               "latitude" : 2.0
                               },
                               "categories" : {
                               "categories" : [ "test1", "test2"]
                               },
                               "review" : 1.0
                               }
                             ]
                           }
                         ]
                       }
                       """
  val clusters_f: org.json4s.JValue = JsonMethods.parse(clustersJson)
  implicit val formats: org.json4s.DefaultFormats = DefaultFormats
  val clusters: Clusters = clusters_f.extract[Clusters]

  test("Purity.calPurity"){
    assert(new Purity(clusters).calPurity() === 1)
  }
}
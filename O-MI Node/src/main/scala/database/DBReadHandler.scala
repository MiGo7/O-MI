package database

import java.util.Date

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import akka.actor.{Actor, ActorRef, ActorSystem, ActorLogging}
import analytics.{AddUser, AddRead, AnalyticsStore}

//import scala.collection.JavaConverters._ //JavaConverters provide explicit conversion methods
//import scala.collection.JavaConversions.asJavaIterator
import scala.xml.{NodeSeq, PrettyPrinter}
//import akka.http.StatusCode

import types.OdfTypes._
import types.OmiTypes._
import types.Path
import types.Path._
import http.{ActorSystemContext, Storages}

trait DBReadHandler extends DBHandlerBase{
  /** Method for handling ReadRequest.
    * @param read request
    * @return (xml response, HTTP status code)
    */
  def handleRead(read: ReadRequest): Future[ResponseRequest] = {
     read match{
       case ReadRequest(_,_,begin,end,Some(newest),Some(oldest),_,_,_) =>
         Future.successful(
           ResponseRequest( Vector(
             Results.InvalidRequest(
               Some("Both newest and oldest at the same time not supported!")
             )
           ))
       )
        //Read all
         /*
       case ReadRequest(objs,_,None,None,None,None,_,_,_) if objs.objects.isEmpty=>
         log.info( "Received read all.")
         Future{
           // NOTE: Might go off sync with tree or values if the request is large,
           // but it shouldn't be a big problem
           val metadataTree = (singleStores.hierarchyStore execute GetTree())

           val pathToValue = singleStores.latestStore execute LookupSensorDatas( metadataTree.intersect( objects ).infoItems.map(_.path)) 
           val objectsWithValues = pathToValue.map{
             case ( path: Path, value: OdfValue[Any]) => OdfInfoItem( path, values = Vector( value)).createAncestors
             }.fold(OdfObjects()){
               case ( odf: OdfObjects, iiObjs: OdfObjects) => odf.union( iiObjs)
             }

             //Find nodes from the request that HAVE METADATA OR DESCRIPTION REQUEST
             val nodesWithoutMetadata: Option[OdfObjects] = getOdfNodes(objects).collect {
               case oii@OdfInfoItem(_, _, desc, mData, typeValue,attr)
               if desc.isDefined || mData.isDefined || typeValue.nonEmpty ||attr.nonEmpty=> 
                 createAncestors(oii.copy(values = OdfTreeCollection()))
               case obj@OdfObject(pat, _, _, _, des, _,attr)
               if des.isDefined  || attr.nonEmpty => 
                 createAncestors(obj.copy(infoItems = OdfTreeCollection(), objects = OdfTreeCollection()))
             }.reduceOption(_.union(_))

             def objectsWithMetadata = nodesWithoutMetadata.map( objs => metadataTree.intersect( objs ) )

             //Select requested O-DF from metadataTree and remove MetaDatas and descriptions
             val objectsWithValuesAndAttributes = metadataTree
               .allMetaDatasRemoved
               .intersect( objectsWithValues.valuesRemoved )
               .union( objectsWithValues )

               val metaCombined = objectsWithMetadata.fold(objectsWithValuesAndAttributes){
                 metas => objectsWithValuesAndAttributes.union(metas) 
               }

               ResponseRequest( Vector(
                 Results.Read( metaCombined) )
               ) 
         }
         
       case ReadRequest(_,_,begin,end,newest,Some(oldest),_) =>
         Future.successful(
           xmlFromResults(
             1.0,
             Results.simple(
               "400",
               Some("Oldest not supported with Warp10 integration!")
             )
           )
         )*/
       case default: ReadRequest =>
         log.debug(
           s"DBHandler handling Read(" + 
           default.begin.map{ t => s"begin: $t," }.getOrElse("") + 
           default.end.map{ t => s"end: $t," }.getOrElse("") + 
           default.newest.map{ t => s"newest: $t," }.getOrElse("") + 
           default.oldest.map{ t => s"oldest: $t," }.getOrElse("") + 
           s"ttl: ${default.ttl} )"
          )

         val leafs = getLeafs(read.odf)


         // NOTE: Might go off sync with tree or values if the request is large,
         // but it shouldn't be a big problem
         val metadataTree = (singleStores.hierarchyStore execute GetTree())

         //Find nodes from the request that HAVE METADATA OR DESCRIPTION REQUEST
         def nodesWithoutMetadata: Option[OdfObjects] = getOdfNodes(read.odf).collect {
           case oii@OdfInfoItem(_, _, desc, mData, typeValue,attr)
           if desc.isDefined || mData.isDefined || typeValue.nonEmpty ||attr.nonEmpty=> 
              createAncestors(oii.copy(values = OdfTreeCollection()))
           case obj@OdfObject(pat, _, _, _, des, _,attr)
             if des.isDefined  || attr.nonEmpty => 
               createAncestors(obj.copy(infoItems = OdfTreeCollection(), objects = OdfTreeCollection()))
         }.reduceOption(_.union(_))

         def objectsWithMetadata = 
           nodesWithoutMetadata.map( objs => metadataTree.intersect( objs ) )
          
         //Get values from database
         val objectsWithValuesO: Future[Option[OdfObjects]] = dbConnection.getNBetween(leafs, read.begin, read.end, read.newest, read.oldest)

         val resultF = objectsWithValuesO.map {
           case Some(objectsWithValues) =>
             //Select requested O-DF from metadataTree and remove MetaDatas and descriptions
             val objectsWithValuesAndAttributes = 
              metadataTree.allMetaDatasRemoved.intersect( objectsWithValues.valuesRemoved )
                .union( objectsWithValues )


             val metaCombined = objectsWithMetadata
               .fold(objectsWithValuesAndAttributes){
                 metas => objectsWithValuesAndAttributes.union(metas) 
               }
             val requestsPaths = leafs.map { _.path }
             val foundOdf = getLeafs(objectsWithValuesAndAttributes)
             val foundOdfAsPaths = foundOdf.flatMap { _.path.getParentsAndSelf }.toSet
             //handle analytics
             analyticsStore.foreach{ store =>
               val reqTime: Long = new Date().getTime()
               foundOdf.foreach(n => {
                 store ! AddRead(n.path, reqTime)
                 store ! AddUser(n.path, read.user.remoteAddress.map(_.hashCode()), reqTime)
               })
             }

             val notFound = requestsPaths.filterNot { path => foundOdfAsPaths.contains(path) }.toSet.toSeq
             val notFoundOdf = notFound.flatMap{ 
               path => read.odf.get(path).map{ node => createAncestors(node)}
            }.foldLeft(OdfObjects()){ 
              case (result, nf) => 
                result.union(nf)
            }
             val found = if( metaCombined.objects.nonEmpty ) Some( Results.Read(metaCombined) ) else None
             val nfResults = if (notFound.nonEmpty) Vector(Results.NotFoundPaths(notFoundOdf)) 
             else Vector.empty
             val omiResults = nfResults ++ found.toVector

             ResponseRequest( omiResults )
           case None =>
             ResponseRequest( Vector(Results.NotFoundPaths(read.odf) ) )
         }
         resultF.onSuccess{
           case response: ResponseRequest =>
             log.info( response.asXML.toString)
         }
         resultF
     }
   }
}

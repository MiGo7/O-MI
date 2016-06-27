/*+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
 +    Copyright (c) 2015 Aalto University.                                        +
 +                                                                                +
 +    Licensed under the 4-clause BSD (the "License");                            +
 +    you may not use this file except in compliance with the License.            +
 +    You may obtain a copy of the License at top most directory of project.      +
 +                                                                                +
 +    Unless required by applicable law or agreed to in writing, software         +
 +    distributed under the License is distributed on an "AS IS" BASIS,           +
 +    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.    +
 +    See the License for the specific language governing permissions and         +
 +    limitations under the License.                                              +
 +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++*/

package database

import java.sql.Timestamp
import java.util.Date

import scala.concurrent.Future
import scala.util.Try

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, Materializer}
import spray.json._
import types.OdfTypes._
import types.Path
import Warp10JsonProtocol.Warp10JsonFormat
//serializer and deserializer for warp10 json formats
object Warp10JsonProtocol extends DefaultJsonProtocol {

  implicit object Warp10JsonFormat extends RootJsonFormat[Seq[OdfInfoItem]] {

    private val createOdfValue: PartialFunction[JsArray, OdfValue] = {
      case JsArray(Vector(JsNumber(timestamp), JsNumber(value))) =>
        OdfValue(value.toString(), timestamp = new Timestamp((timestamp / 1000).toLong))
      case JsArray(Vector(JsNumber(timestamp), JsNumber(elev), JsNumber(value))) =>
        OdfValue(s"$elev $value", timestamp = new Timestamp((timestamp / 1000).toLong))
      case JsArray(Vector(JsNumber(timestamp), JsNumber(lat), JsNumber(lon), JsNumber(value))) =>
        OdfValue(s"$lat:$lon $value", timestamp = new Timestamp((timestamp / 1000).toLong))
      case JsArray(Vector(JsNumber(timestamp), JsNumber(lat), JsNumber(lon), JsNumber(elev), JsNumber(value))) =>
        OdfValue(s"$lat:$lon/$elev $value", timestamp = new Timestamp((timestamp / 1000).toLong))
    }


    def write(o: Seq[OdfInfoItem]): JsValue = ??? //no use

    def read(v: JsValue): Seq[OdfInfoItem] = v.convertTo[List[JsObject]] match {

      case jsObjs: List[JsObject] => {
        val idPathValuesTuple = jsObjs.map { jsobj =>
          val path = fromField[Option[String]](jsobj, "c")
          val vals = fromField[JsArray](jsobj,"v")
          val id = fromField[Option[String]](jsobj,"i")

          val values: OdfTreeCollection[OdfValue] = vals match {
            case JsArray(valueVectors: Vector[JsArray]) => valueVectors.collect(createOdfValue)
          }

          (id, path , values)

        }
        val infoIs:Seq[OdfInfoItem] = idPathValuesTuple.groupBy(_._1).collect{
          case (None , ii) => ii.map{
            case (_, Some(_path), c) => OdfInfoItem(Path(_path), c) //sort by timestamp?
            case _ => throw new DeserializationException("No Path found when deserializing")
          }

          case (Some(id), ii) => {
            val _path = ii.collectFirst{ case (_, Some(p),_) => p}
              .getOrElse(throw new DeserializationException("Was not able to match id to path while deserializing"))

            val _values = ii.foldLeft(OdfTreeCollection[OdfValue])((col ,next ) => col ++ next._3)

            Seq(OdfInfoItem(Path(_path), _values.sortBy(_.timestamp.getTime())))
          }
          case _ => throw new DeserializationException("Unknown format")
        }(collection.breakOut).flatten

        infoIs
      }
    }
  }

}

class Warp10Wrapper( settings: Warp10ConfigExtension )(implicit system: ActorSystem = ActorSystem()) extends DB {
 
  import Warp10JsonProtocol._
  type Warp10Token = String
  final class Warp10TokenHeader(token: Warp10Token) extends ModeledCustomHeader[Warp10TokenHeader] {
    override def renderInRequests = false
    override def renderInResponses = false
    override val companion = Warp10TokenHeader
    override def value: String = token
  }
  object Warp10TokenHeader extends ModeledCustomHeaderCompanion[Warp10TokenHeader] {
    override val name = "X-Warp10-Token"
    override def parse(value: String) = Try(new Warp10TokenHeader(value))
  }

 def warpAddress : Uri = settings.warp10Address 
 implicit val readToken : Warp10Token = settings.warp10ReadToken
 implicit val writeToken : Warp10Token = settings.warp10WriteToken
 
 import system.dispatcher // execution context for futures
 val httpExt = Http(system)
 implicit val mat: Materializer = ActorMaterializer()
 def log = system.log
 def getNBetween(
    requests: Iterable[OdfNode],
    begin: Option[Timestamp],
    end: Option[Timestamp],
    newest: Option[Int],
    oldest: Option[Int]
  ): Future[Option[OdfObjects]] = {
    val selector = nodesToReadPathSelector(requests)
    val content : String = (begin, end, newest) match {
      case (None, None, None) =>
        warpReadNBeforeMsg(selector, 1, None)(readToken)
      case (None, None, Some(sticks)) =>
        warpReadNBeforeMsg(selector,sticks, None)(readToken)
      case (None, Some(endTime), Some(sticks)) => 
        warpReadNBeforeMsg(selector,sticks, end)(readToken)
      case (startTime, endTime, None) => 
        warpReadBetweenMsg(selector,begin, end)(readToken)
    } 
    val request = RequestBuilding.Post(warpAddress, content)
    val responseF : Future[HttpResponse] = httpExt.singleRequest(request)//httpHandler(request)
    responseF.flatMap{
      case response @ HttpResponse( status, headers, entity, protocol ) if status.isSuccess =>
        Unmarshal(entity).to[Seq[OdfInfoItem]].map{ 
          case infos if infos.isEmpty=> 
            None
          case infos => 
            Some(infos.map(createAncestors).foldLeft(OdfObjects())( _ union _ ))
        }

        
    }
 }

 def writeMany(data: Seq[(Path, OdfValue)]): Future[Seq[(Path,Int)]/*StatusCode*/] ={
   val content = data.map{
    case (path, odfValue) =>
    toWriteFormat(path,odfValue)
   }.mkString("")
   val request = RequestBuilding.Post(warpAddress, content)
     .withHeaders(Warp10TokenHeader(writeToken))

   val response = httpExt.singleRequest(request)//httpHandler(request)
   response.map{
     case HttpResponse( status, headers, entity, protocol ) if status.isSuccess =>
       status
     case HttpResponse( status, headers, entity, protocol ) if status.isFailure => 
       Unmarshal(entity).to[String].map{ 
        str => 
          log.debug(s"$status with:\n $str")
       }
       status
       //TODO: what to do if failed? Entity has more information.
   }

   ???
 }

 private def toWriteFormat( path: Path, odfValue : OdfValue ) : String ={
   val unixEpochTime = odfValue.timestamp.getTime * 1000
   val pathSelector = path.mkString(".") 
   val labelSelector = "{}"
   val value = odfValue.value
   s"$unixEpochTime// $pathSelector$labelSelector $value\n" 
 }
 
 private def nodesToReadPathSelector( nodes : Iterable[OdfNode] ) = {
   val paths = nodes.map{ 
     case obj : OdfObject => obj.path.mkString(".") + ".*"
     case objs : OdfObjects => objs.path.mkString(".") + ".*" 
     case info : OdfInfoItem => info.path.mkString(".") 
   }
   "(" + paths.mkString("|") + ")"
 }
 
 
 private def warpReadNBeforeMsg(
   pathSelector: String,
   sticks: Int,
   start: Option[Timestamp]
 )(
   implicit readToken: Warp10Token
 ): String = {
   val epoch = start.map{ ts => (ts.getTime * 1000).toString }.getOrElse("NOW")
   s"""[
   '$readToken'
   '$pathSelector'
   {}
   $epoch
   -$sticks
   ] FETCH"""
 }

 def currentEpoch = new Timestamp( new Date().getTime ).getTime *1000
 private def warpReadBetweenMsg(
   pathSelector: String,
   begin: Option[Timestamp],
   end: Option[Timestamp]
   )(
     implicit readToken: Warp10Token
   ): String = {
   val startEpoch=  end.map{
    time => time.getTime * 1000
   }.getOrElse( currentEpoch )
   val timespan =  begin.map{
    time => startEpoch - time.getTime * 1000 
   }.getOrElse( startEpoch )
   s"""[
   '$readToken'
   '$pathSelector'
   {}
   $startEpoch
   $timespan
   ] FETCH"""
 }
}

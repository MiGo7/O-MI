/**
  Copyright (c) 2015 Aalto University.

  Licensed under the 4-clause BSD (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at top most directory of project.

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
**/
package database

import scala.language.postfixOps

import slick.driver.H2Driver.api._
import java.sql.Timestamp

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.collection.JavaConversions.{ asJavaIterable, iterableAsScalaIterable }
import scala.collection.SortedMap

import types._
import types.OdfTypes._
import types.OdfTypes.OdfTreeCollection.seqToOdfTreeCollection

/**
 * Read only restricted interface methods for db tables
 */
trait DBReadOnly extends DBBase with OdfConversions with DBUtility with OmiNodeTables {
  protected[this] def findParentI(childPath: Path): DBIOro[Option[DBNode]] = findParentQ(childPath).result.headOption

  protected[this] def findParentQ(childPath: Path) = (
    if (childPath.length == 0)
      hierarchyNodes filter (_.path === childPath)
    else
      hierarchyNodes filter (_.path === Path(childPath.init))
  )
  


  /**
   * Used to get data from database based on given path.
   * returns Some(OdfInfoItem) if path leads to sensor and if
   * path leads to object returns Some(OdfObject).
   * OdfObject has childs as infoitems and objects.
   * if nothing is found for given path returns None
   *
   * @param path path to search data from
   *
   * @return either Some(OdfInfoItem),Some(OdfObject) or None based on where the path leads to
   */
  def get(path: Path): Option[OdfNode] = runSync(getQ(path))

  //def getQ(single: OdfElement): OdfElement = ???
  def getQ(path: Path): DBIOro[Option[OdfNode]] = for {

    subTreeData <- getSubTreeI(path, depth = Some(1))

    dbInfoItems = toDBInfoItems(subTreeData)

    result = singleObjectConversion(dbInfoItems)

  } yield result

  /**
   * Used to get sensor values with given constrains. first the two optional timestamps, if both are given
   * search is targeted between these two times. If only start is given,all values from start time onwards are
   * targeted. Similiarly if only end is given, values before end time are targeted.
   *    Then the two Int values. Only one of these can be present. fromStart is used to select fromStart number
   * of values from the begining of the targeted area. Similiarly from ends selects fromEnd number of values from
   * the end.
   * All parameters except path are optional, given only path returns all values in the database for that path
   *
   * @param path path as Path object
   * @param start optional start Timestamp
   * @param end optional end Timestamp
   * @param fromStart number of values to be returned from start
   * @param fromEnd number of values to be returned from end
   * @return query for the requested values
   */
  //protected[this] def getNBetweenDBInfoItemQ(
  //  id: Int,
  //  begin: Option[Timestamp],
  //  end: Option[Timestamp],
  //  newest: Option[Int],
  //  oldest: Option[Int]): Query[DBValuesTable, DBValue, Seq] =
  //  nBetweenLogicQ(getValuesQ(id), begin, end, newest, oldest)

  /**
   * Makes a Query which filters, limits and sorts as limited by the parameters.
   * See [[getNBetween]].
   * @param getter Gets DBValue from some ValueType for filtering and sorting
   */
  protected[this] def nBetweenLogicQ(
    values: Query[DBValuesTable, DBValue, Seq],
    begin: Option[Timestamp],
    end: Option[Timestamp],
    newest: Option[Int],
    oldest: Option[Int]): Query[DBValuesTable, DBValue, Seq] = {

    //are these values already sorted?
    val timeFrame = values filter betweenLogicR(begin, end)

    // NOTE: duplicate code: takeLogic
    val query = (begin, end, oldest, newest) match {
      case (_, _, Some(_oldest), _) => timeFrame sortBy (_.timestamp.asc) take (oldest.get)
      case (_, _, _, Some(_newest)) => timeFrame sortBy (_.timestamp.desc) take (_newest) sortBy (_.timestamp.asc)
      case (None, None, _, _)       => timeFrame sortBy (_.timestamp.desc) take 1
      case _                        => timeFrame
    }
    //old
    //    val query =
    //      if (oldest.nonEmpty) {
    //        timeFrame sortBy (_.timestamp.asc) take (oldest.get) //sortBy (_.timestamp.asc)
    //      } else if (newest.nonEmpty) {
    //        timeFrame sortBy (_.timestamp.desc) take (newest.get) sortBy (_.timestamp.asc)
    //      } else if (begin.isEmpty && end.isEmpty) {
    //        timeFrame sortBy (_.timestamp.desc) take 1
    //      } else {
    //        timeFrame
    //      }
    query
  }

  protected[this] def betweenLogicR(
    begin: Option[Timestamp],
    end: Option[Timestamp]): DBValuesTable => Rep[Boolean] =
    (end, begin) match {
      case (None, Some(startTime)) =>
        { value =>
          value.timestamp >= startTime
        }
      case (Some(endTime), None) =>
        { value =>
          value.timestamp <= endTime
        }
      case (Some(endTime), Some(startTime)) =>
        { value =>
          value.timestamp >= startTime &&
            value.timestamp <= endTime
        }
      case (None, None) =>
        { value =>
          true: Rep[Boolean]
        }
    }
  protected[this] def betweenLogic(
    begin: Option[Timestamp],
    end: Option[Timestamp]): DBValue => Boolean =
    (end, begin) match {
      case (None, Some(startTime)) =>
        { _.timestamp.getTime >= startTime.getTime }

      case (Some(endTime), None) =>
        { _.timestamp.getTime <= endTime.getTime }

      case (Some(endTime), Some(startTime)) =>
        { value =>
          value.timestamp.getTime >= startTime.getTime && value.timestamp.getTime <= endTime.getTime
        }
      case (None, None) =>
        { value => true }
    }

  // NOTE: duplicate code: nBetweenLogicQ
  protected[this] def takeLogic(
    newest: Option[Int],
    oldest: Option[Int],
    timeFrameEmpty: Boolean): Seq[DBValue] => Seq[DBValue] = {
    (newest, oldest) match {
      case (_, Some(_oldest))    => _.sortBy(_.timestamp.getTime) take (_oldest)
      case (Some(_newest), _)    => _.sortBy(_.timestamp.getTime)(Ordering.Long.reverse) take (_newest) reverse
      case _ if (timeFrameEmpty) => _.sortBy(_.timestamp.getTime)(Ordering.Long.reverse) take 1
      case _                     => _.sortBy(_.timestamp.getTime)
    }
  }

  /**
   * Used to get result values with given constrains in parallel if possible.
   * first the two optional timestamps, if both are given
   * search is targeted between these two times. If only start is given,all values from start time onwards are
   * targeted. Similiarly if only end is given, values before end time are targeted.
   *    Then the two Int values. Only one of these can be present. fromStart is used to select fromStart number
   * of values from the begining of the targeted area. Similiarly from ends selects fromEnd number of values from
   * the end.
   * All parameters except the first are optional, given only the first returns all requested data
   *
   * @param requests SINGLE requests in a list (leafs in request O-DF); InfoItems, Objects and MetaDatas
   * @param begin optional start Timestamp
   * @param end optional end Timestamp
   * @param newest number of values to be returned from start
   * @param  number of values to be returned from end
   * @return Combined results in a O-DF tree
   */
  def getNBetween(
    requests: Iterable[OdfNode],
    begin: Option[Timestamp],
    end: Option[Timestamp],
    newest: Option[Int],
    oldest: Option[Int]): Option[OdfObjects] = {

    require(!(newest.isDefined && oldest.isDefined),
      "Both newest and oldest at the same time not supported!")

    val requestsSeq = requests.toSeq

    require(requestsSeq.size >= 1,
      "getNBetween should be called with at least one request thing")

    // NOTE: Might go off sync with tree or values if the request is large,
    // but it shouldn't be a big problem
    val metadataTree = SingleStores.hierarchyStore execute GetTree()

    def processObjectI(path: Path): DBIO[Option[OdfObjects]] = {
      getHierarchyNodeI(path) flatMap {
        case Some(rootNode) => for {
          subTreeData <- getSubTreeI(rootNode.path)

          // NOTE: We can only apply "between" logic here because of the subtree query
          // basicly we fetch too much data if "newest" or "oldest" is set

          timeframedTreeData = subTreeData filter {
            case (node, Some(value)) => betweenLogic(begin, end)(value)
            case (node, None)        => true // keep objects for their description etc.
          }

          dbInfoItems: DBInfoItems = toDBInfoItems(timeframedTreeData) mapValues takeLogic(newest, oldest, begin.isEmpty && end.isEmpty)

          results = odfConversion(dbInfoItems)

        } yield results

        case None => // Requested object was not found, TODO: think about error handling
          DBIO.successful(None)
      }

    }

    // returns metadata if metadataQuery is Some
    def getMetaInfoItem(metadataQuery: Option[OdfMetaData], path: Path): OdfInfoItem = {
      metadataQuery flatMap {_ =>            // If metadataQuery.nonEmpty
        metadataTree.get(path) match {   // and InfoItem exists in tree
          case Some(found: OdfInfoItem) => Some(found)
          case _ => None
        }
      } getOrElse OdfInfoItem(path, Iterable(), None, None)
    }


    def getFromDB(): Seq[Option[OdfObjects]] = requestsSeq map { // par

      case obj @ OdfObjects(objects, _) =>
        require(objects.isEmpty,
          s"getNBetween requires leaf OdfElements from the request, given nonEmpty $obj")


        runSync(processObjectI(obj.path))

      case obj @ OdfObject(path, items, objects, _, _) =>
        require(items.isEmpty && objects.isEmpty,
          s"getNBetween requires leaf OdfElements from the request, given nonEmpty $obj")

        runSync(processObjectI(path))

      case OdfInfoItem(path, rvalues, _, metadataQuery) =>

        val odfInfoItemI = getHierarchyNodeI(path) flatMap { nodeO =>

          nodeO match {
            case Some(node @ DBNode(Some(nodeId), _, _, _, _, _, _, true)) => for {

              odfInfoItem <- processObjectI(path)


              metaInfoItem: OdfInfoItem = getMetaInfoItem(metadataQuery, path)
              result = odfInfoItem.map {
                infoItem => fromPath(infoItem) union fromPath(metaInfoItem)
              }

            } yield result

            case n =>
              println(s"Requested '$path' as InfoItem, found '$n'")
              DBIO.successful(None) // Requested object was not found or not infoitem, TODO: think about error handling
          }
        }

        runSync(odfInfoItemI)

      //case odf: OdfElement =>
      //  throw new RuntimeException(s"Non-supported query parameter: $odf")
      //case OdfObjects(_, _) =>
      //case OdfDesctription(_, _) =>
      //case OdfValue(_, _, _) =>
    }

    def getFromCache(): Seq[Option[OdfObjects]] = {
      val objectData: Seq[Option[OdfObjects]] = requestsSeq map {
        case obj @ OdfObjects(objects, _) =>
          require(objects.isEmpty,
            s"getNBetween requires leaf OdfElements from the request, given nonEmpty $obj")

          Some( SingleStores.buildOdfFromValues(
            SingleStores.latestStore execute LookupAllDatas()) )

        case obj @ OdfObject(path, items, objects, desc, _) =>
          require(items.isEmpty && objects.isEmpty,
            s"getNBetween requires leaf OdfElements from the request, given nonEmpty $obj")

          val resultsO = for {
            odfObject <- metadataTree.get(path) collect {  // get all descendants
              case o: OdfObject => o
            }
            paths = getLeafs(odfObject) map (_.path)

            pathValues = SingleStores.latestStore execute LookupSensorDatas(paths) 
            _ = println(s"PATH VALUES $paths: $pathValues")
          } yield SingleStores.buildOdfFromValues(pathValues)

          // O-DF standard is a bit unclear about description field for objects
          // so we decided to put it in only when explicitly asked
          resultsO map (_ union (for {
            results <- resultsO

            _ <- desc  //if desc.nonEmpty
            meta <- metadataTree.get(path) collect {
              case o: OdfObject => o
            }
            
          } yield fromPath(meta)).getOrElse(OdfObjects()))

        case _ => None // noop, infoitems are processed in the next lines
      }

      // And then get all InfoItems with the same call
      val infoItems = requestsSeq collect {case ii: OdfInfoItem => ii}
      val paths = infoItems map (_.path)
      val infoItemData = SingleStores.latestStore execute LookupSensorDatas(paths)
      val resultOdf = SingleStores.buildOdfFromValues(infoItemData)
      objectData :+ Some(
        infoItems.foldLeft(resultOdf){(result, info) =>
          info match {
            case OdfInfoItem(path, _, _, metadataQuery) =>
              result union fromPath(getMetaInfoItem(metadataQuery, path))
          }
        }
      )
    }

    // Optimizing basic read requests
    val allResults = 
      if (newest.isEmpty && oldest.isEmpty && begin.isEmpty && end.isEmpty)  
        getFromCache()
      else
        getFromDB()

    // Combine some Options
    val results = allResults.fold(None){
      case (Some(results), Some(otherResults)) => Some(results union otherResults)
      case (None, Some(results))               => Some(results)
      case (Some(results), None)               => Some(results)
      case (None, None)                        => None
    }

    results

  }

//  def getNBetweenWithHierarchyIds(
//    infoItemIdTuples: Seq[(Int, OdfInfoItem)],
//    begin: Option[Timestamp],
//    end: Option[Timestamp],
//    newest: Option[Int],
//    oldest: Option[Int]): OdfObjects =
//    {
//      val ids = infoItemIdTuples.map { case (id, info) => id }
//      val betweenValues = runSync(
//        nBetweenLogicQ(
//          latestValues.filter { _.hierarchyId.inSet(ids) },
//          begin,
//          end,
//          newest,
//          oldest).result)
//      infoItemIdTuples.map {
//        case (id, info) =>
//          fromPath(info.copy(values = betweenValues.collect { case dbval if dbval.hierarchyId == id => dbval.toOdf }))
//      }.foldLeft(OdfObjects())(_.union(_))
//    }
    

  /**
   * @param root Root of the tree
   * @param depth Maximum traverse depth relative to root
   */
  protected[this] def getSubTreeQ(
    root: DBNode,
    depth: Option[Int] = None): Query[(DBNodesTable, Rep[Option[DBValuesTable]]), (DBNode, Option[DBValue]), Seq] = {

    val depthConstraint: DBNodesTable => Rep[Boolean] = node =>
      depth match {
        case Some(depthLimit) =>
          node.depth <= root.depth + depthLimit
        case None =>
          true
      }
    val nodesQ = hierarchyNodes filter { node =>
      node.leftBoundary >= root.leftBoundary &&
        node.rightBoundary <= root.rightBoundary &&
        depthConstraint(node)
    }

    val nodesWithValuesQ =
      nodesQ joinLeft latestValues on (_.id === _.hierarchyId)

    nodesWithValuesQ sortBy (_._1.leftBoundary.asc)
  }

  protected[this] def getSubTreeI(
    path: Path,
    depth: Option[Int] = None): DBIOro[Seq[(DBNode, Option[DBValue])]] = {

    val subTreeRoot = getHierarchyNodeI(path)

    subTreeRoot flatMap {
      case Some(root) =>

        getSubTreeQ(root, depth).result

      case None => DBIO.successful(Seq()) // TODO: What if not found?
    }
  }

  protected[this] def getInfoItemsI(hNodes: Seq[DBNode]): DBIO[DBInfoItems] =
    dbioDBInfoItemsSum(
      hNodes map { hNode =>
        for {
          subTreeData <- getSubTreeI(hNode.path)

          infoItems: DBInfoItems = toDBInfoItems(subTreeData)

          result: DBInfoItems = infoItems collect {
            case (node, seqVals) if seqVals.nonEmpty =>
              (node, seqVals sortBy (_.timestamp.getTime) take 1)
          }
        } yield result
      })

 /* /**
   * Query to the database for given subscription id.
   * Data removing is done separately
   * @param id id of the subscription to poll
   * @return
   */
  def pollSubData(id: Long): Seq[SubValue] = {
    runSync(pollSubDataI(id))
  }
  private def pollSubDataI(id: Long) = {
    val subData = pollSubs filter (_.subId === id)
    subData.result
  }*/
}

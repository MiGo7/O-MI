// Generated by <a href="http://scalaxb.org/">scalaxb</a>.
package parsing


case class ObjectsType(Object: Seq[ObjectType] = Nil,
  version: Option[String] = None)


case class ObjectType(id: Seq[QlmID] = Nil,
  description: Option[Description] = None,
  InfoItem: Seq[InfoItemType] = Nil,
  Object: Seq[ObjectType] = Nil,
  typeValue: Option[String] = None,
  attributes: Map[String, scalaxb.DataRecord[Any]])


case class MetaData(InfoItem: InfoItemType*)


case class InfoItemType(otherName: Seq[QlmID] = Nil,
  description: Option[Description] = None,
  MetaData: Option[MetaData] = None,
  value: Seq[ValueType] = Nil,
  name: String,
  attributes: Map[String, scalaxb.DataRecord[Any]])


case class Description(value: String,
  lang: Option[String] = None,
  attributes: Map[String, scalaxb.DataRecord[Any]])


case class QlmID(value: String,
  idType: Option[String] = None,
  tagType: Option[String] = None,
  startDate: Option[javax.xml.datatype.XMLGregorianCalendar] = None,
  endDate: Option[javax.xml.datatype.XMLGregorianCalendar] = None,
  attributes: Map[String, scalaxb.DataRecord[Any]])


case class ValueType(value: String,
  typeValue: String,
  dateTime: Option[javax.xml.datatype.XMLGregorianCalendar] = None,
  unixTime: Option[Long] = None,
  attributes: Map[String, scalaxb.DataRecord[Any]])


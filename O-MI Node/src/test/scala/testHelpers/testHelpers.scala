package testHelpers
import org.specs2.mutable._
import org.specs2.specification.{Step, Fragments}
import org.xml.sax.InputSource
import akka.testkit.TestKit
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.specs2.specification.Scope
import akka.actor._
import responses.RemoveSubscription
import scala.xml._
import parsing._

trait BeforeAll extends Specification {
  override def map(fs: =>Fragments) ={
    Step(beforeAll) ^ fs
  }

  protected def beforeAll()
}

trait AfterAll extends Specification {
  override def map(fs: =>Fragments) ={
    fs ^ Step(afterAll)
  }
    
  protected def afterAll()
}

trait BeforeAfterAll extends Specification {
  override def map(fs: => Fragments)={
    Step(beforeAll) ^ fs ^ Step(afterAll)
  }
  protected def beforeAll()
  protected def afterAll()
}

abstract class Actors extends TestKit(ActorSystem("testsystem", ConfigFactory.parseString("""
  akka.loggers = ["akka.testkit.TestEventListener"]
  """))) with After with Scope{
    def after = system.shutdown()
  }

class SubscriptionHandlerTestActor extends Actor {
  def receive = {
    case RemoveSubscription(x) => {
      if (x <= 10) {
        sender() ! true
      } else {
        sender() ! false
      }
    }
    case _ => sender() ! false
  }
}

class HTML5Parser extends NoBindingFactoryAdapter{
  override def loadXML(source: InputSource, parser: SAXParser) = {
    loadXML(source)
  }
  
  def loadXML(source: InputSource) = {
    import nu.validator.htmlparser.{sax,common}
    import sax.HtmlParser
    import common.XmlViolationPolicy
    
    val reader = new HtmlParser
    reader.setXmlPolicy(XmlViolationPolicy.ALLOW)
    reader.setContentHandler(this)
    reader.parse(source)
    rootElem
  }
}
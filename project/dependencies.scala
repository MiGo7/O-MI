import sbt._
import Keys._

object Dependencies{

//Akka 
  val akkaV = "2.3.6"
  val akkaActor    = "com.typesafe.akka"   %%  "akka-actor"    % akkaV //
  
  
//Spray
  val sprayV = "1.3.2"
  val sprayCan     = "io.spray"			%%  "spray-can"     % sprayV //common
  val sprayRouting = "io.spray"			%%  "spray-routing" % sprayV //common
  val sprayClient  = "io.spray"			%%  "spray-client"  % sprayV //common
  val sprayServlet = "io.spray"			%%  "spray-servlet" % sprayV 

//Test dependencies
  val specs2        = "org.specs2"          	%% "specs2-core"	% "2.3.11"	% "test"
  val nuValidator	= "nu.validator.htmlparser" % "htmlparser"		% "1.4" //html5 parser for systemtests
  val akkaTestkit   = "com.typesafe.akka"		%% "akka-testkit"	% akkaV		% "test"
  val sprayTestkit  = "io.spray"				%%  "spray-testkit" % sprayV	% "test"
  
//Slick
  val slickV = "3.0.0"
  val slick  		= "com.typesafe.slick"	%%  "slick" 		% slickV //common
  val slickCodegen 	= "com.typesafe.slick"	%% "slick-codegen" 	% slickV //common
  val slf4jNop 		= "org.slf4j"			% "slf4j-nop" 		% "1.6.4" //common
  val sqliteJdbc 	= "org.xerial"			% "sqlite-jdbc" 	% "3.7.2" //common
  val hikariCP		= "com.zaxxer"			% "HikariCP-java6" 	% "2.3.3" // XXX: manually updated dependency, slick had 2.0.1
  val h2			= "com.h2database"		% "h2" 				% "1.4.187"  //common
  val json4s        = "org.json4s"			%%  "json4s-native"	% "3.2.11" //common
  
//etc
  
  val schwatcher	= "com.beachape.filemanagement"	%% "schwatcher"		% "0.1.8" //common
  val commonsLang	= "commons-lang" 				% "commons-lang"	% "2.6" //common
  
//Scala XML
  val scalaXML = "org.scala-lang.modules" %% "scala-xml" % "1.0.4" //
  
//STM
  val stm = "org.scala-stm" %% "scala-stm" % "0.7"

  val commonDependencies: Seq[ModuleID] = Seq(
    akkaActor,
	sprayCan,
	sprayRouting,
	sprayClient,
	slick,
	slickCodegen,
	slf4jNop,
	sqliteJdbc,
	hikariCP,
	h2,
	json4s,
	scalaXML,
	schwatcher,
	commonsLang
	)
  
  val servletDependencies: Seq[ModuleID] = Seq(
    sprayServlet,
	stm
	)
  
  val testDependencies: Seq[ModuleID] = Seq(
    specs2,
	nuValidator,
	akkaTestkit,
	sprayTestkit
	)
  

}
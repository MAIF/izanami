import play.sbt.PlayRunHook
import sbt.Keys.fullClasspath
import sbt._

object SbtClasspathRunHook {

  def apply(files: Seq[File]): PlayRunHook = {
    object SbtClasspath extends PlayRunHook {
      override def beforeStarted(): Unit = {
        val sbtClasspath: String = files.map(x => x.getAbsolutePath).mkString(":")
        println("Set SBT classpath to 'sbt-classpath' environment variable")
        System.setProperty("sbt-classpath", sbtClasspath)
      }
    }
    SbtClasspath
  }
  
}

import sbt._
import sbt.Keys._

object Release {

  lazy val publishSettings = Seq(
    credentials += Credentials("Nexus Repository Manager", "nexus.europe.intranet", "deployment", "do.deploy"),
    publishTo  <<= version { v: String =>
      val nexus = "http://nexus.europe.intranet:8085/nexus/content/repositories/"
      if (v.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "snapshots")
      else
        Some("releases" at nexus + "releases")
    }
  )

}
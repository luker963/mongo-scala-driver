import com.typesafe.sbt.SbtGit._
import sbt._

object Versioning {

  val snapshotSuffix = "-SNAPSHOT"
  val releasedVersion = """^r?([0-9\.]+)$""".r
  val releasedCandidateVersion = """^r?([0-9\.]+-rc\d+)$""".r
  val snapshotVersion = """^r?[0-9\.]+(.*)$""".r

  def settings(baseVersion: String): Seq[Def.Setting[_]] = Seq(
    git.baseVersion := baseVersion,
    git.uncommittedSignifier := None,
    git.useGitDescribe := true,
    git.gitTagToVersionNumber := {
      case releasedVersion(v) => Some(v)
      case releasedCandidateVersion(rc) => Some(rc)
      case snapshotVersion(v) => Some(s"$baseVersion$v$snapshotSuffix")
      case _ => None
    }
  )

}
package fr.maif.izanami.helpers

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.Timeout
import com.mifmif.common.regex.Generex
import fr.maif.izanami.api.BaseAPISpec.{TestApiKey, TestCondition, TestDateTimePeriod, TestDayPeriod, TestFeature, TestFeatureContext, TestHourPeriod, TestProject, TestSituationBuilder, TestTenant, TestUser, cleanUpDB}
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import play.api.test.Helpers.await

import java.time.{DayOfWeek, LocalDateTime, LocalTime}
import scala.concurrent.{ExecutionContext, Future}

object HighVolumetryLoader {
  def main(args: Array[String]): Unit = {
    cleanUpDB(hard = true)

    val generex = new Generex("[a-zA-Z0-9:_-]+");
    val projectGenerex = new Generex("[a-zA-Z0-9_-]+");

    val featureNames = (for (i <- 0 to 500) yield generex.random(3)).toSet
    val projectNames = (for (i <- 0 to 100) yield projectGenerex.random(3)).toSet


    val situation = TestSituationBuilder().loggedInWithAdminRights()
      .withTenants(TestTenant("load")).build()

    //for(projectName <- projectNames) yield situation.createProject(projectName, "load")

    val projectFeatureTuples = for(
      featureName <- featureNames;
      projectName <- projectNames
    ) yield (projectName, featureName)


    val actorSystem = ActorSystem("foo")
    val mat = Materializer(actorSystem)
    await(Source.fromIterator(() => projectNames.iterator)
      .mapAsync(10)(p => Future{
        println(s"Creating project ${p}")
        situation.createProject(p, "load")
      }(ExecutionContext.global))
      .run()(mat))(Timeout.durationToTimeout(60.seconds))

    println("DONE creatine projects")

    await(Source.fromIterator(() => projectFeatureTuples.iterator)
      .mapAsync(200){ case (project, name) => Future{
        println(s"Creating feature ${name} for project ${project}")
        situation.createFeature(name, project, "load")}(ExecutionContext.global)
      }
      .run()(mat))(Timeout.durationToTimeout(1200.seconds))
    System.exit(0)
  }
}

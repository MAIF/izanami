package domains.abtesting

import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec

abstract class AbstractExperimentServiceTest(name: String) extends PlaySpec with ScalaFutures with IntegrationPatience {

  s"$name ExperimentServiceTest" must {

    "crud"  in {

    }

  }

}

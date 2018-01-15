package libs.streams

import akka.NotUsed
import akka.stream.FlowShape
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, Source, Zip}

object Flows {

  def count[In, Out](aFlow: => Flow[In, Out, NotUsed]): Flow[In, (Out, Int), NotUsed] =
    Flow.fromGraph {
      GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._

        val bcast = b.add(Broadcast[In](2))
        val zip   = b.add(Zip[Out, Int]())
        val count = Flow[In].fold(0) { (acc, _) =>
          acc + 1
        }

        bcast ~> count ~> zip.in1
        bcast ~> aFlow ~> zip.in0

        FlowShape(bcast.in, zip.out)
      }
    }
}

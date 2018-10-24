package domains
import play.api.libs.json.{JsString, Json}
import test.IzanamiSpec

class NodeTest extends IzanamiSpec {

  "node" must {
    "deep merge node" in {

      val nodes = Node.valuesToNodes(
        List(
          (Key("izanami:prod:seg1:seg2:seg3"), JsString("v1")),
          (Key("izanami:prod:seg1"), JsString("v1")),
          (Key("izanami:preprod:seg1:seg2:seg3"), JsString("v1")),
          (Key("izanami:preprod:seg1:seg2:seg4"), JsString("v1")),
          (Key("izanami:preprod:seg1:seg3"), JsString("v1")),
          (Key("otoroshi:prod:seg1:seg2:seg3"), JsString("v1")),
          (Key("otoroshi:preprod:seg1:seg2:seg3"), JsString("v1")),
          (Key("nio:seg1:seg2:seg3"), JsString("v1"))
        )
      )

      val expected = List(
        Node(
          "otoroshi",
          List(
            Node("prod", List(Node("seg1", List(Node("seg2", List(Node("seg3", value = Some(JsString("v1"))))))))),
            Node("preprod",
                 List(
                   Node("seg1",
                        List(
                          Node("seg2",
                               List(
                                 Node("seg3", value = Some(JsString("v1")))
                               ))
                        ))
                 ))
          )
        ),
        Node("nio",
             List(
               Node("seg1",
                    List(
                      Node("seg2",
                           List(
                             Node("seg3", value = Some(JsString("v1")))
                           ))
                    ))
             )),
        Node(
          "izanami",
          List(
            Node(
              "prod",
              List(
                Node("seg1", List(Node("seg2", List(Node("seg3", value = Some(JsString("v1")))))), Some(JsString("v1")))
              )
            ),
            Node(
              "preprod",
              List(
                Node(
                  "seg1",
                  List(
                    Node("seg3", value = Some(JsString("v1"))),
                    Node("seg2",
                         List(
                           Node("seg4", value = Some(JsString("v1"))),
                           Node("seg3", value = Some(JsString("v1")))
                         ))
                  )
                )
              )
            )
          )
        )
      )

      nodes mustEqual expected
    }

    "serialize json" in {
      import Node._
      val nodes = Node.valuesToNodes(
        List(
          (Key("izanami:prod"), JsString("v1")),
          (Key("izanami:prod:seg1"), JsString("v1")),
          (Key("izanami"), JsString("v1")),
          (Key("izanami:preprod:seg1:seg2:seg4"), JsString("v1"))
        )
      )

      val json = Json.toJson(nodes)

      println(json)
    }
  }

}

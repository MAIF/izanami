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
          Key("otoroshi"),
          "otoroshi",
          List(
            Node(
              Key("otoroshi:preprod"),
              "preprod",
              List(
                Node(
                  Key("otoroshi:preprod:seg1"),
                  "seg1",
                  List(
                    Node(Key("otoroshi:preprod:seg1:seg2"),
                         "seg2",
                         List(
                           Node(Key("otoroshi:preprod:seg1:seg2:seg3"), "seg3", value = Some(JsString("v1")))
                         ))
                  )
                )
              )
            ),
            Node(
              Key("otoroshi:prod"),
              "prod",
              List(
                Node(
                  Key("otoroshi:prod:seg1"),
                  "seg1",
                  List(
                    Node(Key("otoroshi:prod:seg1:seg2"),
                         "seg2",
                         List(Node(Key("otoroshi:prod:seg1:seg2:seg3"), "seg3", value = Some(JsString("v1")))))
                  )
                )
              )
            )
          )
        ),
        Node(
          Key("nio"),
          "nio",
          List(
            Node(Key("nio:seg1"),
                 "seg1",
                 List(
                   Node(Key("nio:seg1:seg2"),
                        "seg2",
                        List(
                          Node(Key("nio:seg1:seg2:seg3"), "seg3", value = Some(JsString("v1")))
                        ))
                 ))
          )
        ),
        Node(
          Key("izanami"),
          "izanami",
          List(
            Node(
              Key("izanami:preprod"),
              "preprod",
              List(
                Node(
                  Key("izanami:preprod:seg1"),
                  "seg1",
                  List(
                    Node(
                      Key("izanami:preprod:seg1:seg2"),
                      "seg2",
                      List(
                        Node(Key("izanami:preprod:seg1:seg2:seg4"), "seg4", value = Some(JsString("v1"))),
                        Node(Key("izanami:preprod:seg1:seg2:seg3"), "seg3", value = Some(JsString("v1")))
                      )
                    ),
                    Node(Key("izanami:preprod:seg1:seg3"), "seg3", value = Some(JsString("v1")))
                  )
                )
              )
            ),
            Node(
              Key("izanami:prod"),
              "prod",
              List(
                Node(
                  Key("izanami:prod:seg1"),
                  "seg1",
                  List(
                    Node(Key("izanami:prod:seg1:seg2"),
                         "seg2",
                         List(Node(Key("izanami:prod:seg1:seg2:seg3"), "seg3", value = Some(JsString("v1")))))
                  ),
                  Some(JsString("v1"))
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

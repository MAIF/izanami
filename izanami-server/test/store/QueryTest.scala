package store

import domains.Key
import test.IzanamiSpec

class QueryTest extends IzanamiSpec {

  "Query" must {

    "key match query" in {
      Query.keyMatchQuery(
        Key("test1:ab:scenario:B:8:displayed"),
        Query
          .oneOf("test1:ab:scenario:*", "test1:ab:*")
          .and(Query.oneOf("test2:ab:scenario:*", "test1:ab:*"))
      )  must be(true)
    }

    "key doesn't match query" in {
      Query.keyMatchQuery(
        Key("test1:ab:scenario:B:8:displayed"),
        Query
          .oneOf("test1:abc:scenario:*", "test2:ab:*")
          .and(Query.oneOf("test2:ab:scenario:*", "test2:ab:*"))
      )  must be(false)
    }
  }

}

package libs.mongo

import akka.http.scaladsl.util.FastFuture
import libs.logs.IzanamiLogger
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.bson.collection.BSONSerializationPack
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.api.bson.BSONDocument
import reactivemongo.play.json.collection.JSONCollection

import scala.concurrent.{ExecutionContext, Future}

object MongoUtils {

  def createIndex(key: Seq[(String, IndexType)], unique: Boolean): Index.Default = Index(BSONSerializationPack)(
    key,
    name = None,
    unique,
    background = false,
    dropDups = false,
    sparse = false,
    expireAfterSeconds = None,
    storageEngine = None,
    weights = None,
    defaultLanguage = None,
    languageOverride = None,
    textIndexVersion = None,
    sphereIndexVersion = None,
    bits = None,
    min = None,
    max = None,
    bucketSize = None,
    collation = None,
    wildcardProjection = None,
    version = None,
    partialFilter = None,
    options = BSONDocument.empty
  )

  def initIndexes(collectionName: String, indexesDefinition: Seq[Index.Default])(implicit mongoApi: ReactiveMongoApi,
                                                                                 ec: ExecutionContext): Future[Unit] =
    mongoApi.database.flatMap(_.collectionNames).flatMap { names =>
      mongoApi.database
        .map(_.collection[JSONCollection](collectionName))
        .flatMap { collection =>
          val created = if (names.contains(collectionName)) {
            FastFuture.successful(())
          } else {
            IzanamiLogger.info(s"Creating collection $collectionName")
            collection.create()
          }
          created.flatMap { _ =>
            IzanamiLogger.info(s"Creating indices for $collectionName")
            Future.traverse(indexesDefinition) { index =>
              collection.indexesManager.ensure(index)
            }
          }
        }
        .map { _ =>
          ()
        }
    }
}

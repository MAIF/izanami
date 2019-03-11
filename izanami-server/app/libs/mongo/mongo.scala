package libs.mongo

import akka.http.scaladsl.util.FastFuture
import libs.logs.IzanamiLogger
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.indexes.Index
import reactivemongo.play.json.collection.JSONCollection

import scala.concurrent.{ExecutionContext, Future}

object MongoUtils {

  def initIndexes(collectionName: String, indexesDefinition: Seq[Index])(implicit mongoApi: ReactiveMongoApi,
                                                                         ec: ExecutionContext): Future[Unit] =
    mongoApi.database.flatMap(_.collectionNames).flatMap { names =>
      mongoApi.database
        .map(_.collection[JSONCollection](collectionName))
        .flatMap { collection =>
          val created = if (names.contains(collectionName)) {
            FastFuture.successful(())
          } else {
            IzanamiLogger.info(s"Creating collection $collectionName")
            collection.create(autoIndexId = false)
          }
          created.flatMap { _ =>
            IzanamiLogger.info(s"Creating indices for $collectionName")
            Future.sequence(indexesDefinition.map(collection.indexesManager.ensure))
          }
        }
        .map { _ =>
          ()
        }
    }
}

package domains.abtesting

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import domains.Key
import domains.abtesting.Experiment.ExperimentKey
import domains.events.EventStore
import play.api.libs.json.{Format, Json, Writes}
import store.Result.{AppErrors, ErrorMessage, Result}
import store._

import scala.concurrent.{ExecutionContext, Future}
import store.SourceUtils._
/* ************************************************************************* */
/*                      Variant binding                                      */
/* ************************************************************************* */

case class VariantBindingKey(experimentId: ExperimentKey, clientId: String) {
  def key: Key = Key.Empty / experimentId / clientId
}

object VariantBindingKey {

  implicit val format: Format[VariantBindingKey] = Format(
    Key.format.map { k =>
      VariantBindingKey(k)
    },
    Writes[VariantBindingKey](vk => Key.format.writes(vk.key))
  )

  def apply(key: Key): VariantBindingKey = {
    val last :: rest = key.segments.toList.reverse
    VariantBindingKey(Key(rest.reverse), last)
  }
}

case class VariantBinding(variantBindingKey: VariantBindingKey, variantId: String)

object VariantBinding {
  implicit val format = Json.format[VariantBinding]

  def variantFor(experimentKey: ExperimentKey, clientId: String)(
      implicit ec: ExecutionContext,
      experimentStore: ExperimentStore,
      VariantBindingStore: VariantBindingStore
  ): Future[Result[Variant]] =
    VariantBindingStore
      .getById(VariantBindingKey(experimentKey, clientId))
      .one
      .flatMap {
        case None =>
          createVariantForClient(experimentKey, clientId)
        case Some(v) =>
          experimentStore.getById(experimentKey).one.map {
            case Some(e) =>
              e.variants
                .find(_.id == v.variantId)
                .map(Result.ok)
                .getOrElse(Result.error("error.variant.missing"))
            case None =>
              Result.error("error.experiment.missing")
          }
      }

  def createVariantForClient(experimentKey: ExperimentKey, clientId: String)(
      implicit ec: ExecutionContext,
      experimentStore: ExperimentStore,
      VariantBindingStore: VariantBindingStore
  ): Future[Result[Variant]] = {

    import cats.implicits._
    import libs.functional.EitherTOps._
    import libs.functional.Implicits._

    // Each step is an EitherT[Future, StoreError, ?]. EitherT is a monad transformer where map, flatMap, withFilter is implemented for Future[Either[L, R]]
    // something  |> someOp, aim to transform "something" into EitherT[Future, StoreError, ?]
    (
      for {
        //Get experiment, if empty : StoreError
        experiment <- experimentStore.getById(experimentKey).one |> liftFOption(
                       AppErrors(Seq(ErrorMessage("error.experiment.missing")))
                     )
        // Sum of the distinct client connected
        sum = experiment.variants.map { _.currentPopulation.getOrElse(0) }.sum
        // We find the variant with the less value
        lastChosenSoFar = experiment.variants.maxBy {
          case v if sum == 0 => 0
          case v =>
            val pourcentReached = v.currentPopulation.getOrElse(0).toFloat / sum
            val diff            = v.traffic - pourcentReached
            //println(s"${v.id} => traffic: ${v.traffic} diff: $diff, reached: $pourcentReached, current ${v.currentPopulation.getOrElse(0).toFloat}, sum: $sum")
            v.traffic - v.currentPopulation.getOrElse(0).toFloat / sum
        }
        // Update of the variant
        newLeastChosenVariantSoFar = lastChosenSoFar.incrementPopulation
        // Update of the experiment
        newExperiment = experiment.addOrReplaceVariant(newLeastChosenVariantSoFar)
        // Update OPS on store for experiment
        experimentUpdated <- experimentStore.update(experimentKey, experimentKey, newExperiment) |> liftFEither[
                              AppErrors,
                              Experiment
                            ]
        // Variant binding creation
        variantBindingKey       = VariantBindingKey(experimentKey, clientId)
        binding: VariantBinding = VariantBinding(variantBindingKey, newLeastChosenVariantSoFar.id)
        variantBindingCreated <- VariantBindingStore.create(variantBindingKey, binding) |> liftFEither[AppErrors,
                                                                                                       VariantBinding]
      } yield newLeastChosenVariantSoFar
    ).value
  }

}

trait VariantBindingStore extends DataStore[VariantBindingKey, VariantBinding]

object VariantBindingStore {
  def apply(jsonStore: JsonDataStore, eventStore: EventStore, system: ActorSystem): VariantBindingStore =
    new VariantBindingStoreImpl(jsonStore, eventStore, system)
}

class VariantBindingStoreImpl(jsonStore: JsonDataStore, eventStore: EventStore, system: ActorSystem)
    extends VariantBindingStore {
  import domains.events.Events._
  import system.dispatcher

  private implicit val s  = system
  private implicit val es = eventStore

  import VariantBinding._

  override def create(id: VariantBindingKey, data: VariantBinding): Future[Result[VariantBinding]] =
    jsonStore
      .create(id.key, format.writes(data))
      .to[VariantBinding]
      .andPublishEvent { r =>
        VariantBindingCreated(id, r)
      }

  override def update(oldId: VariantBindingKey,
                      id: VariantBindingKey,
                      data: VariantBinding): Future[Result[VariantBinding]] =
    jsonStore.update(oldId.key, id.key, format.writes(data)).to[VariantBinding]

  override def delete(id: VariantBindingKey): Future[Result[VariantBinding]] =
    jsonStore.delete(id.key).to[VariantBinding]
  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] =
    jsonStore.deleteAll(patterns)

  override def getById(id: VariantBindingKey): FindResult[VariantBinding] =
    JsonFindResult[VariantBinding](jsonStore.getById(id.key))

  override def getByIdLike(patterns: Seq[String],
                           page: Int,
                           nbElementPerPage: Int): Future[PagingResult[VariantBinding]] =
    jsonStore
      .getByIdLike(patterns, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def getByIdLike(patterns: Seq[String]): Source[(VariantBindingKey, VariantBinding), NotUsed] =
    jsonStore.getByIdLike(patterns).readsKV[VariantBinding].map {
      case (k, v) => (VariantBindingKey(k), v)
    }

  override def count(patterns: Seq[String]): Future[Long] =
    jsonStore.count(patterns)

}

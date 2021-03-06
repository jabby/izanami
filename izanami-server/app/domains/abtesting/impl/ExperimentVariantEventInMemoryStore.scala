package domains.abtesting.impl

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorSystem, Props}
import akka.stream.scaladsl.Source
import domains.abtesting._
import env.DbDomainConfig
import store.Result.Result
import store.Result

import scala.concurrent.Future
import ExperimentDataStoreActor._
import akka.http.scaladsl.util.FastFuture

//////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    IN MEMORY     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventInMemoryStore {
  def apply(configdb: DbDomainConfig, actorSystem: ActorSystem): ExperimentVariantEventInMemoryStore =
    new ExperimentVariantEventInMemoryStore(configdb, actorSystem)
}

class ExperimentVariantEventInMemoryStore(configdb: DbDomainConfig, actorSystem: ActorSystem)
    extends ExperimentVariantEventStore {

  import actorSystem.dispatcher
  import akka.pattern._
  import akka.util.Timeout

  import scala.concurrent.duration.DurationInt

  private implicit val timeout = Timeout(1.second)

  private val store = actorSystem.actorOf(Props[ExperimentDataStoreActor](new ExperimentDataStoreActor()),
                                          configdb.conf.namespace + "_try_to_use_this_one")

  override def create(id: ExperimentVariantEventKey,
                      data: ExperimentVariantEvent): Future[Result[ExperimentVariantEvent]] =
    (store ? AddEvent(id.experimentId.key, id.variantId, data))
      .mapTo[ExperimentVariantEvent]
      .map[Result[ExperimentVariantEvent]](res => Result.ok(res))

  override def deleteEventsForExperiment(experiment: Experiment): Future[Result[Done]] =
    (store ? DeleteEvents(experiment.id.key))
      .mapTo[Done]
      .map[Result[Done]](res => Result.ok(res))

  override def findVariantResult(experiment: Experiment): Source[VariantResult, NotUsed] =
    Source
      .fromFuture(Future.sequence {
        experiment.variants
          .map(variant => {

            val fEvents: Future[List[ExperimentVariantEvent]] = (store ? FindEvents(experiment.id.key, variant.id))
              .mapTo[List[ExperimentVariantEvent]]
            val fDisplayed: Future[Long] = (store ? FindCounterDisplayed(experiment.id.key, variant.id))
              .mapTo[Long]
            val fWon: Future[Long] = (store ? FindCounterWon(experiment.id.key, variant.id))
              .mapTo[Long]

            for {
              events    <- fEvents
              displayed <- fDisplayed
              won       <- fWon
            } yield {

              val transformation: Double = if (displayed != 0) {
                won * 100 / displayed
              } else 0.0

              VariantResult(
                variant = Some(variant),
                events = events,
                transformation = transformation,
                displayed = displayed,
                won = won
              )

            }
          })
          .toList
      })
      .mapConcat(identity)

  override def listAll(patterns: Seq[String]) =
    Source
      .fromFuture((store ? GetAll(patterns)).mapTo[Seq[ExperimentVariantEvent]])
      .mapConcat(_.toList)

  override def check(): Future[Unit] = FastFuture.successful(())
}

private[abtesting] class ExperimentDataStoreActor extends Actor {

  private var datas: Map[String, List[ExperimentVariantEvent]] =
    Map.empty[String, List[ExperimentVariantEvent]]
  private var counters: Map[String, Long] = Map.empty[String, Long]

  val experimentseventsdisplayedNamespace: String =
    "experimentseventsdisplayed:count"
  val experimentseventswonNamespace: String = "experimentseventswon:count"
  val experimentseventsNamespace: String    = "experimentsevents"

  def transformation(displayed: Long, won: Long): Double =
    if (displayed != 0) {
      won * 100.0 / displayed
    } else 0.0

  override def receive: Receive = {
    case AddEvent(experimentId, variantId, event) =>
      val eventKey: String =
        s"$experimentseventsNamespace:$experimentId:$variantId"
      val displayedCounterKey: String =
        s"$experimentseventsdisplayedNamespace:$experimentId:$variantId"
      val wonCounterKey: String =
        s"$experimentseventswonNamespace:$experimentId:$variantId"

      val events: List[ExperimentVariantEvent] =
        datas.getOrElse(eventKey, List.empty[ExperimentVariantEvent])
      val displayed: Long = counters.getOrElse(displayedCounterKey, 0)
      val won: Long       = counters.getOrElse(wonCounterKey, 0)

      event match {
        case e: ExperimentVariantDisplayed =>
          val transfo: Double = transformation(displayed + 1, won)
          val eventToSave     = e.copy(transformation = transfo)

          counters = counters + (displayedCounterKey -> (displayed + 1))
          datas = datas + (eventKey                  -> (eventToSave :: events))
          sender() ! eventToSave

        case e: ExperimentVariantWon =>
          val transfo: Double = transformation(displayed, won + 1)
          val eventToSave     = e.copy(transformation = transfo)
          counters = counters + (wonCounterKey -> (won + 1))
          datas = datas + (eventKey            -> (eventToSave :: events))
          sender() ! eventToSave
      }

    case FindEvents(experimentId, variantId) =>
      val eventKey: String =
        s"$experimentseventsNamespace:$experimentId:$variantId"
      sender() ! datas
        .getOrElse(eventKey, List.empty[ExperimentVariantEvent])
        .sortWith((e1, e2) => e1.date.isBefore(e2.date))

    case GetAll(patterns) =>
      sender() ! datas.values.flatten.filter(e => e.id.key.matchPatterns(patterns: _*))

    case DeleteEvents(experimentId) =>
      val eventKey: String = s"$experimentseventsNamespace:$experimentId:"
      val displayedCounterKey: String =
        s"$experimentseventsdisplayedNamespace:$experimentId:"
      val wonCounterKey: String =
        s"$experimentseventswonNamespace:$experimentId:"

      datas.keys
        .filter(key => key.startsWith(eventKey))
        .foreach(key => datas = datas - key)
      counters.keys
        .filter(key => key.startsWith(displayedCounterKey) || key.startsWith(wonCounterKey))
        .foreach(key => datas = datas - key)

      sender() ! Done

    case FindCounterDisplayed(experimentId, variantId) =>
      sender() ! counters.getOrElse(s"$experimentseventsdisplayedNamespace:$experimentId:$variantId", 0)

    case FindCounterWon(experimentId, variantId) =>
      sender() ! counters.getOrElse(s"$experimentseventswonNamespace:$experimentId:$variantId", 0)

    case m =>
      unhandled(m)
  }
}

private[abtesting] object ExperimentDataStoreActor {

  sealed trait ExperimentDataMessages

  case class AddEvent(experimentId: String, variantId: String, event: ExperimentVariantEvent)
      extends ExperimentDataMessages

  case class FindEvents(experimentId: String, variantId: String) extends ExperimentDataMessages

  case class GetAll(patterns: Seq[String]) extends ExperimentDataMessages

  case class DeleteEvents(experimentId: String) extends ExperimentDataMessages

  case class FindCounterDisplayed(experimentId: String, variantId: String) extends ExperimentDataMessages

  case class FindCounterWon(experimentId: String, variantId: String) extends ExperimentDataMessages

}

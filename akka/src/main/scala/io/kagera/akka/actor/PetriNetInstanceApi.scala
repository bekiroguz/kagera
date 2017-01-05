package io.kagera.akka.actor

import akka.NotUsed
import akka.actor.{ Actor, ActorRef, ActorSystem, Props }
import akka.pattern.ask
import akka.stream.scaladsl.{ Sink, Source, SourceQueueWithComplete }
import akka.stream.{ Materializer, OverflowStrategy }
import akka.util.Timeout
import cats.data.Xor
import io.kagera.akka.actor.PetriNetInstanceProtocol._
import io.kagera.api.colored.ExceptionStrategy.RetryWithDelay
import io.kagera.api.colored.{ Transition, _ }

import scala.collection.immutable.Seq
import scala.concurrent.{ Await, Future }

sealed trait ErrorResponse {
  def msg: String
}

object ErrorResponse {
  def unapply(arg: ErrorResponse): Option[String] = Some(arg.msg)
}

case class UnexpectedMessage(msg: String) extends ErrorResponse

case object UnknownProcessId extends ErrorResponse {
  val msg: String = s"Unknown process id"
}

/**
 * An actor that pushes all received messages on a SourceQueueWithComplete.
 *
 * TODO: Guarantee that this actor dies after not receiving messages for some time. Currently, in case messages
 * get lost, this actor will live indefinitely creating the possibility of memory leak.
 */
class QueuePushingActor(queue: SourceQueueWithComplete[TransitionResponse], waitForRetries: Boolean) extends Actor {
  var runningJobs = Set.empty[Long]

  override def receive: Receive = {
    case e: TransitionFired ⇒
      queue.offer(e)

      val newJobIds = e.newJobs.map(_.id)
      runningJobs = runningJobs ++ newJobIds - e.jobId

      stopActorIfDone

    case msg @ TransitionFailed(_, _, _, _, _, RetryWithDelay(_)) if waitForRetries ⇒
      queue.offer(msg)

    case msg @ TransitionFailed(jobId, _, _, _, _, _) ⇒
      runningJobs = runningJobs - jobId
      queue.offer(msg)
      stopActorIfDone

    case m @ _ ⇒
      runningJobs = Set.empty
      stopActorIfDone
  }

  def stopActorIfDone: Unit = {
    if (runningJobs.isEmpty) {
      queue.complete()
      context.stop(self)
    }
  }
}

object PetriNetInstanceApi {
  def askSource(actor: ActorRef, msg: Any, waitForRetries: Boolean)(implicit actorSystem: ActorSystem): Source[TransitionResponse, NotUsed] = {
    Source.queue[TransitionResponse](100, OverflowStrategy.fail).mapMaterializedValue { queue ⇒
      val sender = actorSystem.actorOf(Props(new QueuePushingActor(queue, waitForRetries)))
      actor.tell(msg, sender)
      NotUsed.getInstance()
    }
  }
}

/**
 * Contains some methods to interact with a petri net instance actor.
 */
class PetriNetInstanceApi[S](topology: ExecutablePetriNet[S], actor: ActorRef)(implicit actorSystem: ActorSystem, materializer: Materializer) {

  import PetriNetInstanceApi._
  import actorSystem.dispatcher

  /**
   * Fires a transition and confirms (waits) for the result of that transition firing.
   */
  def askAndConfirmFirst(msg: Any)(implicit timeout: Timeout): Future[Xor[UnexpectedMessage, InstanceState]] = {
    actor.ask(msg).map {
      case e: TransitionFired ⇒ Xor.Right(e.result)
      case msg @ _            ⇒ Xor.Left(UnexpectedMessage(s"Received unexepected message: $msg"))
    }
  }

  def askAndConfirmFirstSync(msg: Any)(implicit timeout: Timeout): Xor[UnexpectedMessage, InstanceState] = {
    Await.result(askAndConfirmFirst(topology, msg), timeout.duration)
  }

  /**
   * Fires a transition and confirms (waits) for all responses of subsequent automated transitions.
   */
  def askAndConfirmAll(msg: Any, waitForRetries: Boolean = false)(implicit timeout: Timeout): Future[Xor[ErrorResponse, InstanceState]] = {

    val futureMessages: Future[Seq[TransitionResponse]] = askAndCollectAll(msg, waitForRetries).runWith(Sink.seq)

    futureMessages.map {
      _.lastOption match {
        case Some(e: TransitionFired) ⇒ Xor.Right(e.result)
        case Some(msg)                ⇒ Xor.Left(UnexpectedMessage(s"Received unexpected message: $msg"))
        case None                     ⇒ Xor.Left(UnknownProcessId)
      }
    }
  }

  /**
   * Synchronously collects all messages in response to a message sent to a PetriNet instance.
   */
  def askAndCollectAllSync(msg: Any, waitForRetries: Boolean = false)(implicit timeout: Timeout): Seq[TransitionResponse] = {
    val futureResult = askAndCollectAll(msg, waitForRetries).runWith(Sink.seq)
    Await.result(futureResult, timeout.duration)
  }

  /**
   * Sends a FireTransition command to the actor and returns a Source of TransitionResponse messages
   */
  def fireTransition(transitionId: Long, input: Any): Source[TransitionResponse, NotUsed] =
    askAndCollectAll(FireTransition(transitionId, input))

  /**
   * Returns a Source of all the messages from a petri net actor in response to a message.
   *
   * If the instance is 'uninitialized' returns an empty source.
   */
  def askAndCollectAll(msg: Any, waitForRetries: Boolean = false): Source[TransitionResponse, NotUsed] = {
    askSource(actor, msg, waitForRetries).map {
      case e: TransitionResponse ⇒ Xor.Right(e)
      case msg @ _               ⇒ Xor.Left(s"Received unexpected message: $msg")
    }.takeWhile(_.isRight).map(_.asInstanceOf[Xor.Right[TransitionResponse]].b)
  }
}

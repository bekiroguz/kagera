package io.kagera.akka.actor

import akka.NotUsed
import akka.actor.{ Actor, ActorRef, ActorSystem, Props }
import akka.stream.scaladsl.{ Sink, Source, SourceQueueWithComplete }
import akka.stream.{ Materializer, OverflowStrategy }
import akka.util.Timeout
import akka.pattern._

import io.kagera.akka.actor.PetriNetInstanceProtocol._
import io.kagera.api.colored.ExceptionStrategy.RetryWithDelay
import io.kagera.api.colored._

import scala.collection.immutable.Seq
import scala.concurrent.{ Await, Future }

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

    case Uninitialized(id) ⇒
      completeQueueAndStop()

    case msg @ TransitionNotEnabled(id, reason) ⇒
      queue.offer(msg)
      completeQueueAndStop()

    case msg @ _ ⇒
      queue.fail(new IllegalStateException(s"Unexpected message: $msg"))
      completeQueueAndStop()
  }

  def completeQueueAndStop() = {
    queue.complete()
    context.stop(self)
  }

  def stopActorIfDone: Unit =
    if (runningJobs.isEmpty)
      completeQueueAndStop()
}

/**
 * Contains some methods to interact with a petri net instance actor.
 */
class PetriNetInstanceApi[S](topology: ExecutablePetriNet[S], actor: ActorRef)(implicit actorSystem: ActorSystem, materializer: Materializer) {

  /**
   * Fires a transition and confirms (waits) for the result of that transition firing.
   */
  def askAndConfirmFirst[S](msg: Any)(implicit timeout: Timeout): Future[TransitionResponse] = actor.ask(msg).mapTo[TransitionResponse]

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
   */
  def askAndCollectAll(msg: Any, waitForRetries: Boolean = false): Source[TransitionResponse, NotUsed] =
    Source.queue[TransitionResponse](100, OverflowStrategy.fail).mapMaterializedValue { queue ⇒
      val sender = actorSystem.actorOf(Props(new QueuePushingActor(queue, waitForRetries)))
      actor.tell(msg, sender)
      NotUsed.getInstance()
    }
}

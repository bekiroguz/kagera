package io.kagera.akka.actor

import akka.persistence.{PersistentActor, RecoveryCompleted}
import io.kagera.api.colored.ExecutablePetriNet
import io.kagera.execution.EventSourcing._
import io.kagera.execution.{EventSourcing, Instance}
import io.kagera.persistence.{Serialization, messages}

trait PetriNetInstanceRecovery[S] {

  this: PersistentActor ⇒

  def topology: ExecutablePetriNet[S]

  implicit val system = context.system
  val serializer = new Serialization(new AkkaObjectSerializer(context.system))

  def onRecoveryCompleted(state: Instance[S])

  def persistEvent[T, E <: Event](instance: Instance[S], e: E)(fn: E => T): Unit = {
    val serializedEvent = serializer.serializeEvent(e)(instance)
    persist(serializedEvent) { persisted => fn(e) }
  }

  private var recoveringState: Instance[S] = Instance.uninitialized[S](topology)

  private def applyToRecoveringState(e: AnyRef) = {
    val deserializedEvent = serializer.deserializeEvent(e)(recoveringState)
    recoveringState = EventSourcing.apply(recoveringState)(deserializedEvent)
  }

  override def receiveRecover: Receive = {
    case e: messages.Initialized      ⇒ applyToRecoveringState(e)
    case e: messages.TransitionFired  ⇒ applyToRecoveringState(e)
    case e: messages.TransitionFailed ⇒ applyToRecoveringState(e)
    case RecoveryCompleted ⇒
      if (recoveringState.sequenceNr > 0)
        onRecoveryCompleted(recoveringState)
  }
}

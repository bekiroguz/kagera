package io.kagera.akka.actor

import io.kagera.api.colored.{ ExceptionStrategy, Marking, Transition }

/**
 * Describes the messages to and from a PetriNetInstance actor.
 */
object PetriNetInstanceProtocol {

  /**
   * A common trait for all commands to a petri net instance.
   */
  sealed trait Command

  /**
   * Command to request the current state of the petri net instance.
   */
  case object GetState extends Command

  object Initialize {

    def apply(marking: Marking): Initialize = Initialize(marking, ())
  }

  /**
   * Command to initialize a petri net instance.
   */
  case class Initialize(marking: Marking, state: Any) extends Command

  object FireTransition {

    def apply[I](t: Transition[I, _, _], input: I): FireTransition = FireTransition(t.id, input, None)

    def apply(t: Transition[Unit, _, _]): FireTransition = FireTransition(t.id, (), None)
  }

  /**
   * Command to fire a specific transition with input.
   */
  case class FireTransition(
    transitionId: Long,
    input: Any,
    correlationId: Option[Long] = None) extends Command

  /**
   * A common trait for all responses coming from a petri net instance.
   */
  sealed trait Response

  /**
   * Response indicating that the command could not be processed because of
   * the current state of the actor.
   *
   * This message is only send in response to Command messages.
   */
  case class IllegalCommand(reason: String) extends Response

  /**
   * A response indicating that the instance has been initialized in a certain state.
   *
   * This message is only send in response to an Initialize message.
   */
  case class Initialized(
    marking: Marking,
    state: Any) extends Response

  /**
   * Any message that is a response to a FireTransition command.
   */
  sealed trait TransitionResponse extends Response {
    val transitionId: Long
  }

  /**
   *  Response indicating that a transition has fired successfully
   */
  case class TransitionFired(
    override val transitionId: Long,
    consumed: Marking,
    produced: Marking,
    result: InstanceState) extends TransitionResponse

  /**
   *  Response indicating that a transition has failed.
   */
  case class TransitionFailed(
    override val transitionId: Long,
    consume: Marking,
    input: Any,
    reason: String,
    strategy: ExceptionStrategy) extends TransitionResponse

  /**
   * Response indicating that the transition could not be fired because it is not enabled.
   */
  case class TransitionNotEnabled(
    override val transitionId: Long,
    reason: String) extends TransitionResponse

  /**
   * The exception state of a transition.
   */
  case class ExceptionState(
    failureCount: Int,
    failureReason: String,
    failureStrategy: ExceptionStrategy)

  /**
   * Response containing the state of the process.
   */
  case class InstanceState(
      sequenceNr: Long,
      marking: Marking,
      state: Any,
      failures: Map[Long, ExceptionState]) {

    def hasFailed(transitionId: Long): Boolean = failures.contains(transitionId)
  }
}

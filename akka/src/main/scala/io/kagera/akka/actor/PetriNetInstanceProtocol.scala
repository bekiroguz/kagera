package io.kagera.akka.actor

import io.kagera.api.colored.ExceptionStrategy.RetryWithDelay
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
   * A response send in case any other command then 'Initialize' is sent to the actor in unitialized state.
   *
   * @param id The identifier of the unitialized actor.
   */
  case class Uninitialized(id: String) extends Response

  /**
   * Returned in case a second Initialize is send after a first is processed
   */
  case object AlreadyInitialized extends Response

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
   * Response indicating that a transition has fired successfully
   */
  case class TransitionFired(
    jobId: Long,
    override val transitionId: Long,
    consumed: Marking,
    produced: Marking,
    result: InstanceState,
    newJobs: Set[JobState]) extends TransitionResponse

  /**
   * Response indicating that a transition has failed.
   */
  case class TransitionFailed(
    jobId: Long,
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

  object ExceptionState {

    def apply(exceptionState: io.kagera.execution.ExceptionState): ExceptionState =
      ExceptionState(exceptionState.failureCount, exceptionState.failureReason, exceptionState.failureStrategy)
  }

  /**
   * Response containing the state of the `Job`.
   */
  case class JobState(
      id: Long,
      transitionId: Long,
      consumedMarking: Marking,
      input: Any,
      exceptionState: Option[ExceptionState]) {

    def isActive: Boolean = exceptionState match {
      case Some(ExceptionState(_, _, RetryWithDelay(_))) ⇒ true
      case None                                          ⇒ true
      case _                                             ⇒ false
    }
  }

  object JobState {
    def apply(job: io.kagera.execution.Job[_, _]): JobState =
      JobState(job.id, job.transition.id, job.consume, job.input, job.failure.map(ExceptionState(_)))
  }

  /**
   * Response containing the state of the process.
   */
  case class InstanceState(
      sequenceNr: Long,
      marking: Marking,
      state: Any,
      jobs: Map[Long, JobState]) {

    @transient
    lazy val reservedMarking: Marking = jobs.map { case (id, job) ⇒ job.consumedMarking }.reduceOption(_ |+| _).getOrElse(Marking.empty)

    @transient
    lazy val availableMarking: Marking = marking |-| reservedMarking
  }

  object InstanceState {
    // Note: extra .map(identity) is a needed to workaround the scala Map serialization bug: https://issues.scala-lang.org/browse/SI-7005
    def apply(instance: io.kagera.execution.Instance[_]): InstanceState =
      InstanceState(instance.sequenceNr, instance.marking, instance.state, instance.jobs.mapValues(JobState(_)).map(identity))
  }

}

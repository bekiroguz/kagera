package io.kagera.akka.actor

import akka.actor._
import akka.event.Logging
import akka.event.Logging.LogLevel
import akka.pattern.pipe
import akka.persistence.PersistentActor
import fs2.Strategy
import io.kagera.akka.actor.PetriNetInstance.Settings
import io.kagera.akka.actor.PetriNetInstanceProtocol._
import io.kagera.api._
import io.kagera.api.colored.ExceptionStrategy.RetryWithDelay
import io.kagera.api.colored._
import io.kagera.execution.EventSourcing._
import io.kagera.execution._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.existentials

object PetriNetInstance {

  case class Settings(
    evaluationStrategy: Strategy,
    idleTTL: Option[FiniteDuration])

  val defaultSettings: Settings = Settings(
    evaluationStrategy = Strategy.fromCachedDaemonPool("Kagera.CachedThreadPool"),
    idleTTL = Some(5 minutes)
  )

  private case class IdleStop(seq: Long)

  def petriNetInstancePersistenceId(processId: String): String = s"process-$processId"

  def props[S](topology: ExecutablePetriNet[S], settings: Settings = defaultSettings): Props =
    Props(new PetriNetInstance[S](topology, settings, new AsyncTransitionExecutor[S](topology)(settings.evaluationStrategy)))
}

/**
 * This actor is responsible for maintaining the state of a single petri net instance.
 */
class PetriNetInstance[S](
  override val topology: ExecutablePetriNet[S],
  val settings: Settings,
  executor: TransitionExecutor[S, Transition]) extends PersistentActor
    with PetriNetInstanceRecovery[S] {

  import PetriNetInstance._

  val processId = context.self.path.name

  val log = Logging.getLogger(this)

  override def persistenceId: String = petriNetInstancePersistenceId(processId)

  import context.dispatcher

  override def receiveCommand = uninitialized

  def logWithMDC(level: LogLevel, msg: String, mdc: Map[String, Any]) = {
    log.setMDC(mdc.asJava)
    log.log(level, msg)
    log.clearMDC()
  }

  def uninitialized: Receive = {
    case msg @ Initialize(marking, state) ⇒
      val uninitialized = Instance.uninitialized[S](topology)
      val event = InitializedEvent(marking, state)
      persistEvent(uninitialized, event) {
        (applyEvent(uninitialized) _)
          .andThen(step)
          .andThen { _ ⇒ sender() ! Initialized(marking, state) }
      }
    case msg: Command ⇒
      sender() ! Uninitialized(processId)
      context.stop(context.self)
  }

  def running(instance: Instance[S]): Receive = {
    case IdleStop(n) if n == instance.sequenceNr && instance.activeJobs.isEmpty ⇒
      log.debug(s"Instance was idle for ${settings.idleTTL}, stopping the actor")
      context.stop(context.self)

    case GetState ⇒
      sender() ! InstanceState(instance)

    case e @ TransitionFiredEvent(jobId, transitionId, timeStarted, timeCompleted, consumed, produced, output) ⇒

      val transition = topology.transitions.getById(transitionId)
      val mdc = Map(
        "kageraEvent" -> "TransitionFired",
        "processId" -> processId,
        "jobId" -> jobId,
        "transitionId" -> transition.id,
        "transitionLabel" -> transition.label,
        "timeStarted" -> timeStarted,
        "timeCompleted" -> timeCompleted,
        "duration" -> (timeCompleted - timeStarted)
      )

      logWithMDC(Logging.DebugLevel, s"Transition '$transition' successfully fired", mdc)

      persistEvent(instance, e)(
        (applyEvent(instance) _)
          .andThen(step)
          .andThen {
            case (updatedInstance, jobs) ⇒
              sender() ! TransitionFired(jobId, transitionId, e.consumed, e.produced, InstanceState(updatedInstance), jobs.map(JobState(_)))
              updatedInstance
          }

      )

    case e @ TransitionFailedEvent(jobId, transitionId, timeStarted, timeFailed, consume, input, reason, strategy) ⇒

      val transition = topology.transitions.getById(transitionId)
      val mdc = Map(
        "kageraEvent" -> "TransitionFailed",
        "processId" -> processId,
        "timeStarted" -> timeStarted,
        "timeFailed" -> timeFailed,
        "duration" -> (timeFailed - timeStarted),
        "transitionId" -> transition.id,
        "transitionLabel" -> transition.label,
        "transitionFailureReason" -> reason
      )

      logWithMDC(Logging.WarningLevel, s"Transition '$transition' failed with: $reason", mdc)

      val updatedInstance = applyEvent(instance)(e)

      def updateAndRespond(instance: Instance[S]) = {
        sender() ! TransitionFailed(jobId, transitionId, consume, input, reason, strategy)
        context become running(instance)
      }

      strategy match {
        case RetryWithDelay(delay) ⇒
          val mdc = Map(
            "kageraEvent" -> "TransitionRetry",
            "processId" -> processId,
            "transitionId" -> transition.id,
            "transitionLabel" -> transition.label)

          logWithMDC(Logging.WarningLevel, s"Scheduling a retry of transition '$transition' in $delay milliseconds", mdc)
          val originalSender = sender()
          system.scheduler.scheduleOnce(delay milliseconds) { executeJob(updatedInstance.jobs(jobId), originalSender) }
          updateAndRespond(applyEvent(instance)(e))
        case _ ⇒
          persistEvent(instance, e)(
            (applyEvent(instance) _).andThen(updateAndRespond _))
      }

    case msg @ FireTransition(transitionId, input, correlationId) ⇒

      val transition = topology.transitions.getById(transitionId)

      fireTransitionById[S](transitionId, input).run(instance).value match {
        case (updatedInstance, Right(job)) ⇒
          executeJob(job, sender())
          context become running(updatedInstance)
        case (_, Left(reason)) ⇒
          val mdc = Map(
            "kageraEvent" -> "FireTransitionRejected",
            "processId" -> processId,
            "transitionId" -> transition.id,
            "transitionLabel" -> transition.label,
            "rejectReason" -> reason)

          logWithMDC(Logging.WarningLevel, s"Not Firing Transition '$transition' because: $reason", mdc)
          sender() ! TransitionNotEnabled(transitionId, reason)
      }
    case msg: Initialize ⇒
      sender() ! AlreadyInitialized
  }

  // TODO remove side effecting here
  def step(instance: Instance[S]): (Instance[S], Set[Job[S, _]]) = {
    fireAllEnabledTransitions.run(instance).value match {
      case (updatedInstance, jobs) ⇒

        if (jobs.isEmpty && updatedInstance.activeJobs.isEmpty)
          settings.idleTTL.foreach { ttl ⇒
            system.scheduler.scheduleOnce(ttl, context.self, IdleStop(updatedInstance.sequenceNr))
          }

        jobs.foreach(job ⇒ executeJob(job, sender()))
        context become running(updatedInstance)
        (updatedInstance, jobs)
    }
  }

  def executeJob[E](job: Job[S, E], originalSender: ActorRef) = {

    val mdc = Map(
      "kageraEvent" -> "FiringTransition",
      "processId" -> processId,
      "jobId" -> job.id,
      "transitionId" -> job.transition.id,
      "transitionLabel" -> job.transition.label,
      "timeStarted" -> System.currentTimeMillis()
    )

    logWithMDC(Logging.DebugLevel, s"Firing transition ${job.transition}", mdc)
    runJobAsync(job, executor).unsafeRunAsyncFuture().pipeTo(context.self)(originalSender)
  }

  override def onRecoveryCompleted(instance: Instance[S]) = step(instance)
}

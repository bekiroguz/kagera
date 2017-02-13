package io.kagera.execution

import io.kagera.api._
import io.kagera.api.colored._

import scala.util.Random

object Instance {
  def uninitialized[S](process: ExecutablePetriNet[S]): Instance[S] = Instance[S](process, 0, Marking.empty, null.asInstanceOf[S], Map.empty)
}

case class Instance[S](
    process: ExecutablePetriNet[S],
    sequenceNr: Long,
    marking: Marking,
    state: S,
    jobs: Map[Long, Job[S, _]]) {

  // The marking that is already used by running jobs
  lazy val reservedMarking: Marking = jobs.map { case (id, job) ⇒ job.consume }.reduceOption(_ |+| _).getOrElse(Marking.empty)

  // The marking that is available for new jobs
  lazy val availableMarking: Marking = marking |-| reservedMarking

  def activeJobs: Iterable[Job[S, _]] = jobs.values.filter(_.isActive)

  def isBlockedReason(transitionId: Long): Option[String] = jobs.values.map {
    case Job(_, _, t, _, _, Some(ExceptionState(_, reason, _))) if t.id == transitionId ⇒
      Some(s"Transition '${process.transitions.getById(transitionId)}' is blocked because it failed previously with: $reason")
    case Job(_, _, t, _, _, Some(ExceptionState(_, reason, ExceptionStrategy.Fatal))) ⇒
      Some(s"Transition '$t' caused a Fatal exception")
    case _ ⇒ None
  }.find(_.isDefined).flatten

  def nextJobId(): Long = Random.nextLong()
}

package io.kagera.api

/**
 * Interface for deciding which (transition, marking) parameters are 'enabled'
 *
 * @tparam P Place
 * @tparam T Transition
 * @tparam M Marking     The type of Marking in the PetriNet
 */
trait TokenGame[P, T, M] {

  this: PetriNet[P, T] ⇒

  def enabledParameters(marking: M): Map[T, Iterable[M]] = {
    // inefficient, fix
    enabledTransitions(marking).view.map(t ⇒ t -> consumableMarkings(marking)(t)).toMap
  }

  def consumableMarkings(marking: M)(t: T): Iterable[M]

  /**
   * Checks whether a transition is 'enabled' in a marking.
   *
   * @param marking The marking.
   * @param t The transition.
   * @return
   */
  def isEnabled(marking: M)(t: T): Boolean = consumableMarkings(marking)(t).nonEmpty

  /**
   * Returns all enabled transitions for a marking.
   *
   * @param marking marking
   * @return
   */
  def enabledTransitions(marking: M): Set[T]
}

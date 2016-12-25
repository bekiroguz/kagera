package io.kagera.execution

import io.kagera.api.colored._

trait TransitionExecutor[State, T[_, _, _]] {

  /**
   * Given a transition returns an input output function
   *
   * @param t
   * @tparam Input
   * @tparam Output
   * @return
   */
  def fireTransition[Input, Output](t: T[Input, Output, State]): TransitionFunction[Input, Output, State]
}
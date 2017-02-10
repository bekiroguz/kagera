package io.kagera.execution

import io.kagera.api.colored._

trait TransitionExecutor[State, T[_, _, _]] {

  /**
   * Given a transition returns an TransitionFunction
   *
   * @param transition
   *
   * @tparam Input  The input type of the transition.
   * @tparam Output The output type of the transition.
   * @return
   */
  def apply[Input, Output](transition: T[Input, Output, State]): TransitionFunction[Input, Output, State]
}
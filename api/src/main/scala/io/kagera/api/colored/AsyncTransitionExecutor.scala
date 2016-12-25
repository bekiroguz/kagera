package io.kagera.api.colored

import fs2.{ Strategy, Task }

class AsyncTransitionExecutor[State](topology: ColoredPetriNet)(implicit strategy: Strategy) extends TransitionExecutor[State] {

  val cachedTransitionFunctions: Map[Transition[_, _, _], _] =
    topology.transitions.map(t ⇒ t -> t.apply(topology.inMarking(t), topology.outMarking(t))).toMap

  def transitionFunction[Input, Output](t: Transition[Input, Output, State]) =
    cachedTransitionFunctions(t).asInstanceOf[TransitionFunction[Input, Output, State]]

  def fireTransition[Input, Output](t: Transition[Input, Output, State]): TransitionFunction[Input, Output, State] = {
    (consume, state, input) ⇒

      def handleFailure: PartialFunction[Throwable, Task[(Marking, Output)]] = {
        case e: Throwable ⇒ Task.fail(e)
      }

      if (consume.multiplicities != topology.inMarking(t)) {
        Task.fail(new IllegalArgumentException(s"Transition $t may not consume $consume"))
      }

      try {
        transitionFunction(t)(consume, state, input).handleWith { handleFailure }.async
      } catch { handleFailure }
  }
}

package io.kagera.akka

import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.testkit.scaladsl.TestSink
import akka.util.Timeout
import io.kagera.akka.actor.PetriNetInstanceApi
import io.kagera.akka.actor.PetriNetInstanceProtocol._
import io.kagera.api.colored.{ Marking, Place, Transition }
import io.kagera.api.colored.dsl._
import org.scalatest.Matchers._

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }

class PetriNetInstanceApiSpec extends AkkaTestBase {

  implicit def materializer = ActorMaterializer()
  implicit def ec: ExecutionContext = system.dispatcher

  "The PetriNetInstanceApi" should {

    "Return a source of events resulting from a TransitionFired command" in new TestSequenceNet {

      override val sequence = Seq(
        transition()(_ ⇒ Added(1)),
        transition(automated = true)(_ ⇒ Added(2)),
        transition(automated = true)(_ ⇒ Added(3))
      )

      val actor = PetriNetInstanceSpec.createPetriNetActor[Set[Int]](petriNet)

      actor ! Initialize(initialMarking, Set.empty)
      expectMsgClass(classOf[Initialized])

      val api = new PetriNetInstanceApi(petriNet, actor)
      val source: Source[TransitionResponse, NotUsed] = api.askAndCollectAll(FireTransition(1, ()))
      source.map(_.transitionId).runWith(TestSink.probe).request(3).expectNext(1, 2, 3)

    }

    "Return an error response when one transition fails but a later one does not (parallel situation)" in new StateTransitionNet[Unit, Unit] {
      implicit val waitTimeout: FiniteDuration = 2 seconds
      implicit val akkaTimeout: akka.util.Timeout = waitTimeout

      override val eventSourcing: Unit ⇒ Unit ⇒ Unit = s ⇒ e ⇒ s

      val p1 = Place[Unit](id = 1)
      val p2 = Place[Unit](id = 2)

      val t1 = nullTransition[Unit](id = 1, automated = false)
      val t2 = transition(id = 2, automated = true)(_ ⇒ throw new RuntimeException("t2 failed!"))
      val t3 = transition(id = 3, automated = true)(_ ⇒ Thread.sleep(200))

      val petriNet = createPetriNet(
        t1 ~> p1,
        t1 ~> p2,
        p1 ~> t2,
        p2 ~> t3
      )

      val actor = PetriNetInstanceSpec.createPetriNetActor[Set[Int]](petriNet)

      actor ! Initialize(Marking.empty, ())
      expectMsgClass(classOf[Initialized])

      val api = new PetriNetInstanceApi(petriNet, actor)

      val results = api.askAndCollectAllSync(FireTransition(1, ()))

      results(1) should matchPattern { case TransitionFailed(_, t2.id, _, _, _, _) ⇒ }
      results(2) should matchPattern { case TransitionFired(_, t3.id, _, _, _, _) ⇒ }
    }

    "Return an empty source when the petri net instance is 'uninitialized'" in new TestSequenceNet {

      override val sequence = Seq(
        transition()(_ ⇒ Added(1))
      )

      val actor = PetriNetInstanceSpec.createPetriNetActor[Set[Int]](petriNet)
      val api = new PetriNetInstanceApi(petriNet, actor)
      val source: Source[TransitionResponse, NotUsed] = api.askAndCollectAll(FireTransition(1, ()))

      source.runWith(TestSink.probe[TransitionResponse]).expectSubscriptionAndComplete()

    }
  }
}
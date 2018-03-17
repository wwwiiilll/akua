package akua

import scala.collection.mutable

import akka.NotUsed
import akka.stream.scaladsl.{Flow, GraphDSL, SubFlow, Source}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FanInShape2, FlowShape, Graph, Inlet, Outlet, SourceShape}

private[akua] final class Exists[A, B](extractKey: A => B) extends GraphStage[FanInShape2[A, B, A]] {

  val in0: Inlet[A] = Inlet("Exists.in0")
  val in1: Inlet[B] = Inlet("Exists.in1")
  val out: Outlet[A] = Outlet("Exists.out")

  override val shape: FanInShape2[A, B, A] = new FanInShape2(in0, in1, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with OutHandler {

    private[this] val keys: mutable.Set[B] = mutable.Set.empty

    override def preStart(): Unit = {
      pull(in1)
    }

    setHandler(in0, new InHandler {

      override def onPush(): Unit = {
        val e = grab(in0)
        val k = extractKey(e)
        if (keys(k)) {
          push(out, e)
        } else {
          pull(in0)
        }
      }

      override def onUpstreamFinish(): Unit = {
        completeStage()
      }

    })

    setHandler(in1, new InHandler {

      override def onPush(): Unit = {
        keys += grab(in1)
        pull(in1)
      }

      override def onUpstreamFinish(): Unit = {
        if (isAvailable(out)) pull(in0)
      }

    })

    override def onPull(): Unit = {
      if (isClosed(in1)) pull(in0)
    }

    setHandler(out, this)

  }

}

object Exists {

  def apply[A, B](f: A => B): Exists[A, B] = new Exists(f)

  def apply[A, B](keys: Source[B, _])(f: A => B): Flow[A, A, NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._
      val e = b.add(apply(f))
      keys ~> e.in1
      FlowShape(e.in0, e.out)
    })

  def apply[A, B, Mat](values: Source[A, Mat], keys: Source[B, _])(f: A => B): Source[A, Mat] =
    values.via(apply(keys)(f))

}

private[akua] trait ExistsOps[Out, Mat] {

  type Repr[O] <: akka.stream.scaladsl.FlowOps[O, Mat] { type Repr[OO] = ExistsOps.this.Repr[OO] }

  protected def self: Repr[Out]

  private[this] def existsGraph[Out2, M](keys: Graph[SourceShape[Out2], M])(f: Out => Out2): Graph[FlowShape[Out, Out], M] =
    GraphDSL.create(keys) { implicit b => k =>
      import GraphDSL.Implicits._
      val e = b.add(Exists(f))
      k ~> e.in1
      FlowShape(e.in0, e.out)
    }

  def exists[Out2, A](keys: Graph[SourceShape[Out2], _])(f: Out => Out2): Repr[Out] =
    self.via(existsGraph(keys)(f))

}

final class SourceExistsOps[Out, Mat](override protected val self: Source[Out, Mat]) extends ExistsOps[Out, Mat] {
  override type Repr[O] = Source[O, Mat]
}

final class FlowExistsOps[In, Out, Mat](override protected val self: Flow[In, Out, Mat]) extends ExistsOps[Out, Mat] {
  override type Repr[O] = Flow[In, O, Mat]
}

final class SubFlowExistsOps[Out, Mat, F[+_], C](override protected val self: SubFlow[Out, Mat, F, C]) extends ExistsOps[Out, Mat] {
  override type Repr[O] = SubFlow[O, Mat, F, C]
}

trait ToExistsOps {
  implicit def toSourceExistsOps[Out, Mat](source: Source[Out, Mat]): SourceExistsOps[Out, Mat] = new SourceExistsOps(source)
  implicit def toFlowExistsOps[In, Out, Mat](flow: Flow[In, Out, Mat]): FlowExistsOps[In, Out, Mat] = new FlowExistsOps(flow)
  implicit def toSubFlowExistsOps[Out, Mat, F[+_], C](sub: SubFlow[Out, Mat, F, C]): SubFlowExistsOps[Out, Mat, F, C] = new SubFlowExistsOps(sub)
}

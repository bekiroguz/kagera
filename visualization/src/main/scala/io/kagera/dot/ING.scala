package io.kagera.dot

import io.kagera.api.MarkingLike
import scalax.collection.edge.WLDiEdge
import scalax.collection.io.dot._
import scalax.collection.io.dot.implicits._

object ING {

  val placeDotAttrList: List[DotAttr] = List(
    DotAttr("color", "\"#FF6200\""),
    DotAttr("style", "filled"),
    DotAttr("fillcolor", "darkorange"),
    DotAttr("fontcolor", "white")
  )

  val transitionDotAttrList: List[DotAttr] = List(
    DotAttr("shape", "rect"),
    DotAttr("margin", 0.5D),
    DotAttr("color", "\"#525199\""),
    DotAttr("style", "rounded, filled"),
    DotAttr("fontcolor", "white"),
    DotAttr("penwidth", 2)
  )

  def ingMarkedTheme[P, T, M](marking: M)(implicit markingLike: MarkingLike[M, P]): GraphTheme[Either[P, T], WLDiEdge] =
    new GraphTheme[Either[P, T], WLDiEdge] {
      override def nodeLabelFn = PetriNet.labelFn
      override def nodeDotAttrFn = {
        case Left(nodeA) ⇒
          markingLike.multiplicity(marking).get(nodeA) match {
            case Some(n) if n > 0 ⇒ DotAttr("shape", "doublecircle") :: placeDotAttrList
            case _ ⇒ DotAttr("shape", "circle") :: placeDotAttrList
          }

        case Right(nodeB) ⇒ transitionDotAttrList
      }
    }

  def ingTheme[P, T] = new GraphTheme[Either[P, T], WLDiEdge] {

    override def nodeLabelFn = PetriNet.labelFn
    override def nodeDotAttrFn = {
      case Left(nodeA) ⇒ DotAttr("shape", "circle") :: placeDotAttrList
      case Right(nodeB) ⇒ transitionDotAttrList
    }
    override val attrStmts = List(
      DotAttrStmt(Elem.node, List(DotAttr("fontname", "ING Me"), DotAttr("fontsize", 22)))
    )

    override val rootAttrs = List(
      DotAttr("pad", 0.2D)
    )
  }
}

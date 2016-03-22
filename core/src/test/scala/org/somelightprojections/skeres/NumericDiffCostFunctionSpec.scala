package org.somelightprojections.skeres

import com.google.ceres.{CostFunction, NumericDiffMethodType}
import org.scalatest.matchers.{BeMatcher, MatchResult}
import org.scalatest.{MustMatchers, WordSpec}
import spire.implicits._

// This is a port to Scala of numeric_diff_cost_function_test.cc and numeric_diff_test_util.cc
// from the ceres-solver source distribution
//
object NumericDiffCostFunctionSpec {
  // Noise factor for randomized cost function.
  val kNoiseFactor = 0.01

  // Default random seed for randomized cost function.
  val kRandomSeed = 1234

  // y1 = x1'x2      -> dy1/dx1 = x2,               dy1/dx2 = x1
  // y2 = (x1'x2)^2  -> dy2/dx1 = 2 * x2 * (x1'x2), dy2/dx2 = 2 * x1 * (x1'x2)
  // y3 = x2'x2      -> dy3/dx1 = 0,                dy3/dx2 = 2 * x2
  class EasyFunctor extends NumericDiffCostFunctor(3, 5, 5) {
    override def apply(x: Array[Double]*): Array[Double] = {
      require(x.length == 2)
      val x1 = x(0)
      val x2 = x(1)
      val y = Array.fill[Double](3)(0.0)
      cforRange(0 until 5) { i =>
        y(0) += x1(i) * x2(i)
        y(2) += x2(i) * x2(i)
      }
      y(1) = y(0) * y(0)
      y
    }
  }

  trait CheckFunctor {
    def nearlyCorrect(method: NumericDiffMethodType): BeMatcher[CostFunction]

    protected def failOrNone(e: => Boolean, failMsg: String, successMsg: String): Option[MatchResult] =
      if (e) None else Some(MatchResult(e, failMsg, successMsg))
  }

  object EasyFunctor extends CheckFunctor {
    override def nearlyCorrect(method: NumericDiffMethodType) = new BeMatcher[CostFunction] {
      import NumericDiffMethodType._

      override def apply(left: CostFunction): MatchResult = {
        import TestUtil._

        // The x1[0] is made deliberately small to test the performance near
        // zero.
        val x1 = Array(1e-64, 2.0, 3.0, 4.0, 5.0)
        val x2 = Array(9.0, 9.0, 5.0, 5.0, 1.0)
        val parameters = RichDoubleMatrix.fromArrays(x1, x2)

        val jacobians = RichDoubleMatrix.ofSize(2, 15)

        val evaluatedResiduals = RichDoubleArray.ofSize(3)
        if (!left.evaluate(parameters, evaluatedResiduals, jacobians)) {
          MatchResult(false, "evaluate failed", "evaluate did not fail")
        } else {
          val residuals = evaluatedResiduals.toArray(3)
          val functor = new EasyFunctor
          val expectedResiduals = functor(x1, x2)
          failOrNone(expectedResiduals.zip(residuals).forall(p => p._1 == p._2),
            "inconsistent residuals", "consistent residuals"
          ).getOrElse {
            val tolerance = method match {
              case FORWARD => 2e-5
              case RIDDERS => 1e-13
              case CENTRAL | _ => 3e-9
            }

            val dydx1 = jacobians.getRow(0).toArray(15) // 3 x 5, row major.
            val dydx2 = jacobians.getRow(1).toArray(15) // 3 x 5, row major.

            MatchResult(
              (0 until 5).forall { i =>
                expectClose(x2(i),                    dydx1(5 * 0 + i), tolerance) &&
                expectClose(x1(i),                    dydx2(5 * 0 + i), tolerance) &&
                expectClose(2 * x2(i) * residuals(0), dydx1(5 * 1 + i), tolerance) &&
                expectClose(2 * x1(i) * residuals(0), dydx2(5 * 1 + i), tolerance) &&
                expectClose(0.0,                      dydx1(5 * 2 + i), tolerance) &&
                expectClose(2 * x2(i),                dydx2(5 * 2 + i), tolerance)
              },
              "inconsistent values", "consistent values"
            )
          }
        }
      }
    }
  }
}

class NumericDiffCostFunctionSpec extends WordSpec with MustMatchers {
  import NumericDiffMethodType._

  "NumericDiffCostFunction" when {
    "in the easy case" should {
      import NumericDiffCostFunctionSpec.EasyFunctor
      import EasyFunctor.nearlyCorrect
      "be nearly correct for FORWARD differences" in {
        val costFunction = new EasyFunctor().toNumericDiffCostFunction(FORWARD)
        costFunction must be(nearlyCorrect(FORWARD))
      }
      "be nearly correct for CENTRAL differences" in {
        val costFunction = new EasyFunctor().toNumericDiffCostFunction(CENTRAL)
        costFunction must be(nearlyCorrect(CENTRAL))
      }
    }
  }
}
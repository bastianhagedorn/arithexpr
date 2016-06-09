package apart
package arithmetic
package simplifier

import scala.language.postfixOps

object SimplifyMod {

  def simplify(dividend: ArithExpr, divisor: ArithExpr): Option[ArithExpr] = (dividend, divisor) match {

    // Simplify operands
    case (x, y) if !x.simplified => Some(ExprSimplifier(x) % y)
    case (x, y) if !y.simplified => Some(x % ExprSimplifier(y))

     // special cases: todo combine the cases which only differ in order
    case (Sum(
              Prod(Cst(c1) :: (a2:ArithExpr) :: Nil) ::
              Prod((a1:ArithExpr) :: (m1: Var) :: Nil) ::
              (e:ArithExpr) ::
              Nil),
          Sum(Cst(c2) :: (m2: Var) :: Nil))
      if m1 == m2 && a1 == a2 && c1 == c2 =>
      Some(Mod(e, Sum(Cst(c1) :: m1 :: Nil)))

    case (Sum(
              (e:ArithExpr) ::
              Prod((m1: Var) :: (a1:ArithExpr) :: Nil) ::
              Prod(Cst(c1) :: (a2:ArithExpr) :: Nil) ::
              Nil),
          Sum(Cst(c2) :: (m2: Var) :: Nil))
      if m1 == m2 && a1 == a2 && c1 == c2 =>
      Some(SimplifyMod(e, Sum(Cst(c1) :: m1 :: Nil)))

    // (((M * j) + (2 * j) + i) % (2 + M))  == (j * (M + 2) + i) % (M + 2) == i % (M + 2)
    case (Sum(Prod((m1: Var) :: (j1:Var) :: Nil) ::
              Prod(Cst(c1) :: (j2:Var) :: Nil) ::
              (i:Var) :: Nil), Sum(Cst(c2) :: (m2: Var) :: Nil)) if m1 == m2 && j1 == j2 && c1 == c2 =>
     Some(SimplifyMod(i, Sum(m1 :: Cst(c1) :: Nil)))

    // Modulo 1
    case (_, Cst(1)) => Some(Cst(0))

    // 0 or 1 modulo anything
    case (Cst(x), _) if x == 0 || x == 1 => Some(dividend)

    // Constant modulo
    case (Cst(x), Cst(y)) => Some(Cst(x % y))

    case (x, y) if x == y => Some(Cst(0))

    case (x, y) if ArithExpr.isSmaller(abs(x), abs(y)).getOrElse(false) => Some(x)

    case (x, y) if ArithExpr.multipleOf(x, y) => Some(Cst(0))
    case (m: Mod, d) if m.divisor == d => Some(m)

    // If the divident is a product, try to find the divisor. Finding the GCD below should make this redundant, but the
    // GCD method does not return fractions, but the divisor could be one.
    case (Prod(factors), x) if factors.contains(x) && !ArithExpr.hasDivision(factors) => Some(Cst(0))

    // If there exists a common denominator, simplify
    case (x, y) if ArithExpr.gcd(x,y) == y => Some(Cst(0))

    // Apply mod to constants
    case (s@Sum(terms), Cst(d))
      if terms.collect({ case Cst(_) => }).nonEmpty &&
        terms.collect({ case Cst(v) => v }).head >= d =>

      val c = terms.collect({ case Cst(v) => v }).head
      Some((s - c + c%d) % d)

    // Isolate the terms which are multiple of the mod and eliminate
    case (s@Sum(terms), d) if !ArithExpr.mightBeNegative(s) =>
      val (multiple, _) = terms.partition(t => (t, d) match {
        case (Prod(factors1), Prod(factors2)) => factors2 forall (factors1 contains)
        case (Prod(factors), x) if factors.contains(x) => true
        case (x, y) if ArithExpr.multipleOf(x, y) => true
        case (x, y) => ArithExpr.gcd(x, y) == y
      })
      val shorterSum = s.withoutTerm(multiple)
      if (multiple.nonEmpty && !ArithExpr.mightBeNegative(shorterSum)) Some(shorterSum % d)
      else None

    case _ => None
  }

  def apply(dividend: ArithExpr, divisor: ArithExpr): ArithExpr = {
    val simplificationResult = if (PerformSimplification()) simplify(dividend, divisor) else None
    simplificationResult match {
      case Some(toReturn) => toReturn
      case None => new Mod(dividend, divisor) with SimplifiedExpr
    }
  }
}

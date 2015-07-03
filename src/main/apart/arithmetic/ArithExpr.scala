package apart
package arithmetic

import arithmetic.simplifier._

import scala.collection.immutable
import scala.language.implicitConversions
import scala.util.Random

import scala.util.control.ControlThrowable

object Devel {
  val SanityCheck = true

  def Assert(p: Boolean, reason: => String = "no reason"): Unit = {
    if(!p) throw new RuntimeException(s"Sanity check failed: ${reason}")
  }

  def AssertNot(p: Boolean, reason: => String = "no reason"): Unit = {
    if(p) throw new RuntimeException(s"Sanity check failed: ${reason}")
  }
}

/**
 * Control flow exception used to abort arithmetic expression evaluation on unknown terms.
 */
final class NotEvaluableException extends ControlThrowable

/**
 * Predicate object. Stores two arithmetic expressions and an operator
 */
case class Predicate(lhs: ArithExpr, rhs: ArithExpr, op: Predicate.Operator.Operator) {
  override lazy val toString: String = s"(${lhs} ${op} ${rhs})"

  sealed class TrueBlock(predicate: Predicate, then: ArithExpr) {
    def !!(els: ArithExpr) = SimplifyIfThenElse(predicate, then, els)
  }

  def ??(then: ArithExpr) = new TrueBlock(this, then)

  val digest: Int =  0x7c6736c0 ^ lhs.digest() ^ rhs.digest() ^ op.hashCode()
}

object Predicate {
  /**
   * List of comparison operators
   */
  object Operator extends Enumeration {
    type Operator = Value;
    val < = Value("<")
    val > = Value(">")
    val <= = Value("<=")
    val >= = Value(">=")
    val != = Value("!=")
    val == = Value("==")
  }
}

/**
 * Class `ArithExpr` is the base class for arithmetic expression trees.
 *
 * An arithmetic expression is a collection of statements representing algebraic operations (+,-,*,/,...), constants
 * and variables. Precedence is taken care of using Scala's operator precedence.
 *
 * These expressions follow mostly natural arithmetic, with a few exceptions:
 * - Modulo is defined for all integers (like the remainder operator `%` in C)
 * - The division operator `/` performs an integer division (the fractional part is discarded)
 * - The operator `/^` is a division operator in the rational set (using ordinal arithmetic)
 */
abstract sealed class ArithExpr {

  /**
   * By default the expression is not simplified
   */
  val simplified: Boolean = false

  /**
   * Lower and upper bounds of the expression.
   */
  lazy val (min,max): (ArithExpr,ArithExpr) = (Var(""), Var(""))

  /**
   * Evaluates an arithmetic expression.
   * @return The Int value of the expression.
   * @throws NotEvaluableException if the expression cannot be fully evaluated.
   */
  lazy val eval: Int = {
    // Evaluating is quite expensive, traverse the tree to check assess evaluability
    if (!isEvaluable) throw ArithExpr.NotEvaluable
    val dblResult = ArithExpr.evalDouble(this)
    if (dblResult.isValidInt)
      dblResult.toInt
    else throw ArithExpr.NotEvaluable
  }

  lazy val isEvaluable: Boolean = {
    !ArithExpr.visitUntil(this, x => {
      x == ? || x.isInstanceOf[ArithExprFunction] || x.isInstanceOf[Var] || x.isInstanceOf[IfThenElse]
    })
  }

  lazy val evalDbl: Double = ArithExpr.evalDouble(this)

  lazy val atMax: ArithExpr = atMax(constantMax = false)

  def atMax(constantMax: Boolean = false): ArithExpr = {
    val vars = varList.filter(_.range.max != ?)
    val exprFunctions = ArithExprFunction.getArithExprFuns(this).filter(_.range.max != ?)
    var maxLens = vars.map(_.range.max) ++ exprFunctions.map(_.range.max)

    if (constantMax && !maxLens.exists(!_.isInstanceOf[Cst]))
      maxLens = maxLens.map(m => Cst(m.eval - 1))

    ArithExpr.substitute(this, (vars ++ exprFunctions, maxLens).zipped.toMap)
  }

  lazy val atMin: ArithExpr = {
    val vars = varList.filter(_.range.min != ?)
    val exprFunctions = ArithExprFunction.getArithExprFuns(this).filter(_.range.min != ?)
    val maxLens = vars.map(_.range.min) ++ exprFunctions.map(_.range.min)
    ArithExpr.substitute(this, (vars ++ exprFunctions, maxLens).zipped.toMap)
  }

  private def getVars(e: ArithExpr = this, l: Set[Var] = Set[Var]()) : Set[Var] = {
    e match {
      case adds: Sum => adds.terms.foldLeft(l)((acc,expr) => getVars(expr, acc))
      case muls: Prod => muls.factors.foldLeft(l)((acc,expr) => getVars(expr, acc))
      case v: Var => if (l contains v) l else  l + v
      case _ => l
    }
  }

  lazy val varList = getVars()

  final def ==(that: ArithExpr): Boolean = {
    if (digest() == that.digest()) {
      // cross check: digest submodes
      /*var thistree = 0
      var thattree = 0
      ArithExpr.visit(this, x => thistree = thistree ^ x.digest())
      ArithExpr.visit(that, x => thattree = thattree ^ x.digest())
      if(thistree != thattree) {
        throw new RuntimeException("digest clash") // TODO: remove this if a collision occurs
        return false
      }
      else*/ true
    } else false
  }

  /* === Arithmetic operators === */

  def pow(that: ArithExpr): ArithExpr = SimplifyPow(this, that)

  /**
   * Multiplication operator.
   * @param that Right-hand side.
   * @return An expression representing the product (not necessarily a Prod object).
   */
  def *(that: ArithExpr): ArithExpr = SimplifyProd(this,that)

  /**
   * Addition operator.
   * @param that Right-hand side.
   * @return An expression representing the sum (not necessarily a Sum object).
   */
  def +(that: ArithExpr): ArithExpr = SimplifySum(this,that)

  /**
   * Division operator in Natural set (ie int div like Scala): `1/2=0`.
   * @param that Right-hand side (divisor).
   * @return An IntDiv object wrapping the operands.
   * @throws ArithmeticException if the right-hand-side is zero.
   */
  def /(that: ArithExpr) = SimplifyIntDiv(this, that)

  /**
   * Ordinal division operator.
   * This prevents integer arithmetic simplification through exponentiation.
   * @param that Right-hand side (divisor).
   * @return The expression multiplied by the divisor exponent -1.
   */
  def /^(that: ArithExpr) = (this,that) match {
    case (x,Cst(1)) => x
    case (Cst(x),Cst(y)) if x % y == 0 => Cst(x/y)
    case (x,y) if x == y => Cst(1)
    case (x,y) if x == y * -1 => Cst(-1)
    case (x,y) => x * (y pow -1)
  }

  /**
   * Transform subtraction into sum of product with -1
   * @param that Right-hand side of the division
   * @return A Sum object
   */
  def -(that: ArithExpr) = this + (that * -1)

  /**
   * The % operator yields the remainder from the division of the first expression by the second.
   * @param that The right-hand side (divisor)
   * @return A Mod expression
   * @throws ArithmeticException if the right-hand-side is zero.
   * @note This operation is defined for negative number since it computes the remainder of the algebraic quotient
   *       without fractional part times the divisor, ie (a/b)*b + a%b is equal to a.
   */
  def %(that: ArithExpr) = SimplifyMod(this, that)

  /* === Comparison operators === */
  /**
   * Lower than comparison operator.
   * @param that Right-hand side of the comparison
   * @return A Predicate object
   */
  def lt(that: ArithExpr) = Predicate(this, that, Predicate.Operator.<)

  /**
   * Greater than comparison operator.
   * @param that Right-hand side of the comparison
   * @return A Predicate object
   */
  def gt(that: ArithExpr) = Predicate(this, that, Predicate.Operator.>)

  /**
   * Lower-or-equal comparison operator.
   * @param that Right-hand side of the comparison
   * @return A Predicate object
   */
  def le(that: ArithExpr) = Predicate(this, that, Predicate.Operator.<=)

  /**
   * Greater-or-equal comparison operator.
   * @param that Right-hand side of the comparison
   * @return A Predicate object
   */
  def ge(that: ArithExpr) = Predicate(this, that, Predicate.Operator.>=)

  /**
   * Equality comparison operator.
   * @note Silently overrides the reference comparison operator `AnyRef.eq`
   * @param that Right-hand side of the comparison
   * @return A Predicate object
   */
  def eq(that: ArithExpr) = Predicate(this, that, Predicate.Operator.==)

  /**
   * Inequality comparison operator.
   * @note Silently overrides the reference comparison operator `AnyRef.ne`
   * @param that Right-hand side of the comparison
   * @return A Predicate object
   */
  def ne(that: ArithExpr) = Predicate(this, that, Predicate.Operator.!=)

  /* == Hash function == */
  /**
   * The hash function creates a 32 bit digest of the expression. Each node type has a unique salt and combines
   * the hashes of the subexpressions using a commutative and associative operator (most likely XOR).
   *
   * The probability of a collision is already fairly low, but in order to guarantee equality one should call
   * visit with a hash comparison function on the sub-tree to guarantee that each node matches. The probability
   * of a collision is then the probability of a collision of a leaf node, which is zero for constant nodes and zero
   * for the first 2,147,483,647 variable instances.
   * @return A 32 bit digest of the expression.
   */
  def digest(): Int
}

//object floor {
 // def apply(exp: ArithExpr) = Floor(exp)
//}

object ArithExpr {

  implicit def IntToCst(i: Int): Cst = Cst(i)

  val NotEvaluable = new NotEvaluableException()

  def max(e1: ArithExpr, e2: ArithExpr) : ArithExpr = minmax(e1, e2)._2

  def min(e1: ArithExpr, e2: ArithExpr) : ArithExpr = minmax(e1, e2)._1

  def minmax(v: Var, c: Cst): (ArithExpr, ArithExpr) = {
    val m1 = v.range.min match { case Cst(min) => if (min >= c.c) Some((c, v)) else None; case _ => ??? }
    if (m1.isDefined) return m1.get

    val m2 = v.range.max match { case Cst(max) => if (max <= c.c) Some((v, c)) else None; case _ => ??? }
    if (m2.isDefined) return m2.get

    throw NotEvaluable
  }

  def minmax(p: Prod, c: Cst): (ArithExpr, ArithExpr) = {
    val lb = lowerBound(p)
    if (lb.isDefined && lb.get >= c.c) return (c, p)

    val ub = upperBound(p)
    if (ub.isDefined && ub.get <= c.c) return (p, c)

    throw NotEvaluable
  }

  /**
   * Find the Greatest Common Divisor in two expressions.
   * @param a The first expression.
   * @param b The second expression.
   * @return The greatest common divisor.
   */
  def gcd(a: ArithExpr, b: ArithExpr): ArithExpr = {
    // Factorize a sum: find a factor common to all terms
    def FactorizeSum(s: Sum): ArithExpr = {
      assert(s.terms.length > 1)
      (for {
        t1 <- s.terms
        t2 <- s.terms
        if t1.digest < t2.digest
      } yield gcd(t1,t2)).map{
        case c@Cst(1) => return c
        case x => x
      }.reduce(_+_)
    }

    val g: ArithExpr = (a,b) match {
      // GCD of constants
      case (Cst(x), Cst(y)) => if(y == 0) scala.math.abs(x) else gcd(scala.math.abs(y), scala.math.abs(x)%y)

      case (i:IntDiv, _) => Cst(1)

      // GCD of two identical things is itself
      case (x, y) if x == y => x

      // GCD of powers, go through bases and find a match, return smallest exp
      // TODO: handle negative exp
      case (Pow(_,Cst(x)), _) if x < 0 => Cst(1)
      case (_, Pow(_,Cst(x))) if x < 0 => Cst(1)
      case (x, Pow(b,e)) if b == x => x // pow 1 (implicit)
      case (Pow(b1,e1), Pow(b2,e2)) if b1 == b2 => b1 pow ArithExpr.min(e1, e2)
      case (Pow(b,e), Prod(factors)) if factors.contains(b) => b // pow 1 (implicit)
      case (Prod(factors), Pow(b,e)) if factors.contains(b) => b // pow 1 (implicit)
      case (Pow(b,e), x) if b == x => x // pow 1 (implicit)
      case (x, Pow(b,e)) if b == x => x // pow 1 (implicit)

      // GCD of products: find GCD in factor pairs
      case (Prod(fs1), Prod(fs2)) => (for { f1 <- fs1; f2 <- fs2 } yield gcd(f1,f2)).reduce(_*_)
      case (Prod(f), c:Cst) => gcd(b,a)
      case (c:Cst, Prod(f)) => f.find(_.isInstanceOf[Cst]) match {
        case Some(x) => gcd(c,x)
        case _ => Cst(1)
      }
      case (Prod(f), x) if f.contains(x) => x
      case (x, Prod(f)) if f.contains(x) => x

      // GCD of sums: find common factor across all terms
      case (s1:Sum, s2:Sum) => {
        // Compute the common factors
        val fac1 = FactorizeSum(s1)
        if (fac1 == Cst(1)) return fac1
        val fac2 = FactorizeSum(s2)
        if (fac1 == Cst(1)) return fac2

        // The GCD could be either the factor or the remainder, so we compute the intersection
        val common = List(fac1, s1 /^ fac1).intersect(List(fac2, s2 /^ fac2))
        if(common.isEmpty) Cst(1)
        else common.head
      }
      case (x, s:Sum) => gcd(b,a)
      case (s:Sum, x) => {
        // compute the common factor
        val factor = FactorizeSum(s)
        // If there is none, there is no possible common factor
        if (factor == Cst(1)) factor
        // otherwise see if there is a common factor with the sum's terms
        else gcd(factor, x) match {
          // If there isn't, we still have a chance with the remainder
          //case Cst(x) if x == 1 => gcd(x, s /^ factor)
          case x => x
        }
      }

      case (x,y) => Cst(1)
    }
    // Never factorize by a fraction
    g match {
      case Pow(x,Cst(-1)) => Cst(1)
      case i:IntDiv => Cst(1)
      case x => x
    }
  }

  private def upperBound(p: Prod): Option[Int] = {
    Some(Prod(p.factors.map({
      case v: Var => v.range.max match {
        case max: Cst => max
        case _ => return None
      }
      case c: Cst => c
      case _ => throw new IllegalArgumentException("upperBound expects a Var or a Cst")
    })).eval)
  }

  private def lowerBound(p: Prod): Option[Int] = {
    Some(Prod(p.factors.map({
      case v: Var => v.range.min match {
        case min: Cst => min
        case _ => return None
      }
      case c: Cst => c
      case _ => throw new IllegalArgumentException("lowerBound expects a Var or a Cst")
    })).eval)
  }

  def minmax(e1: ArithExpr, e2: ArithExpr): (ArithExpr, ArithExpr) = {
    e1 - e2 match {
      case Cst(c) if c < 0 => (e1, e2) /* e1 is smaller than e2 */
      case Cst(c) => (e2, e1) /* e2 is smaller than e1*/
      case _ =>
        (e1, e2) match {
          case (v: Var, c: Cst) => minmax(v, c)
          case (c: Cst, v: Var) => minmax(v, c).swap

          case (p: Prod, c: Cst) => minmax(p, c)
          case (c: Cst, p: Prod) => minmax(p, c).swap

          case _ => throw NotEvaluable
        }
    }
  }

  def max(e: ArithExpr) : ArithExpr = minmax(e)._2

  def min(e: ArithExpr) : ArithExpr = minmax(e)._1

  def minmax(e: ArithExpr): (ArithExpr, ArithExpr) = {
    e match {
      case _: Cst => (e, e)
      case Var(_, range) => ( if (range.min != ?) min(range.min) else e,
                              if (range.max != ?) max(range.max) else e )

      case Sum(sums) => ( Sum(sums.map(min)), Sum(sums.map(max)) )

      // TODO: check if the product is positive or negative
      case Prod (prods) => ( prods.map(min).reduce(_*_), prods.map(max).reduce(_*_) )

      case Pow(b, cst@Cst(c)) => ( if (c>=0) min(b) pow cst else max(b) pow cst,
                                   if (c>=0) max(b) pow cst else min(b) pow cst )

      case _ =>  throw NotEvaluable
    }
  }

  def contains(expr: ArithExpr, elem: ArithExpr) : Boolean = {
    visit(expr, e => if (e==elem) return true)
    false
  }

  /**
   * Find if an expression is possibly a multiple of another.
   * @param expr The expression.
   * @param that A possible multiple.
   * @return True if `that` is a multiple of `expr`, false otherwise
   */
  def multipleOf(expr: ArithExpr, that: ArithExpr) : Boolean = (ExprSimplifier(expr), that) match {

    // Compare two products, look for inclusion of common denominator
    case (Prod(terms), Prod(otherTerms)) => terms.count(isDivision) == otherTerms.count(isDivision) && otherTerms.map({
        case pow: Pow => terms.exists(multipleOf(_, pow))
        case x => terms.contains(x)
      }).reduce(_&&_)

    // A constant is a multiple of a product if it is a multiple of its constant factor
    case (Prod(terms), Cst(c)) =>
      val cst = terms.find(_.isInstanceOf[Cst])
      !cst.isEmpty && cst.get.asInstanceOf[Cst].c % c == 0

    // If it is something else, it is a multiple if it is included in the list of factors and the product does not
    // contain a division
    case (Prod(terms), _) => !terms.exists(isDivision) && terms.contains(that)

    // Check multiple of constants
    case (Cst(c1), Cst(c2)) => c1 % c2 == 0

    // Look for common denominator in fractions
    case (IntDiv(n1, d1), IntDiv(n2, d2)) => multipleOf(d2, d1) && multipleOf(n1, n2)

    // Look for the denominator for two inverses
    case (Pow(b1, Cst(-1)), Pow(b2, Cst(-1))) => multipleOf(b2, b1)

    // Finally, the two expressions are multiple of each other if they are the same
    case (x,y) => x == y
  }

  private[arithmetic] def hasDivision(factors: List[ArithExpr]): Boolean = {
    factors.exists(isDivision)
  }

  private[arithmetic] def isDivision: (ArithExpr) => Boolean = {
    case Pow(_, Cst(-1)) => true
    case e => false
  }

  def isSmaller(ae1: ArithExpr, ae2: ArithExpr): Boolean = {
    //System.out.println(s"${ae1} <?< ${ae2}")
    try {
      // TODO: Assuming range.max is non-inclusive
      val atMax = ae1.atMax

      atMax match {
        case Prod(factors) if hasDivision(factors) =>
          val newProd = ExprSimplifier(factors.filter(!isDivision(_)).reduce(_*_))
          if (newProd == ae2)
            return true
        case _ =>
      }

      if (atMax == ae2 || ae1.atMax(constantMax = true).eval < ae2.eval)
        return true
    } catch {
      case e: NotEvaluableException =>
    }
    false
  }

  def visit(e: ArithExpr, f: (ArithExpr) => Unit) : Unit = {
    f(e)
    e match {
      case Pow(base, exp) =>
        visit(base, f)
        visit(exp, f)
      case IntDiv(n, d) =>
        visit(n, f)
        visit(d, f)
      case Mod(dividend, divisor) =>
        visit(dividend, f)
        visit(divisor, f)
      case Log(b,x) =>
        visit(b, f)
        visit(x, f)
      case Floor(expr) => visit(expr, f)
      case Sum(terms) => terms.foreach(t => visit(t, f))
      case Prod(terms) => terms.foreach(t => visit(t, f))
      case Var(_,_) |  Cst(_) | IfThenElse(_,_,_) | ArithExprFunction(_) | ? =>
      case _ => throw new RuntimeException(s"Cannot visit expression ${e}")
    }
  }

  def visitUntil(e: ArithExpr, f: (ArithExpr) => Boolean) : Boolean = {
    if(f(e)) true
    else {
      e match {
        case Pow(base, exp) =>
          visitUntil(base, f) || visitUntil(exp, f)
        case IntDiv(n, d) =>
          visitUntil(n, f) || visitUntil(d, f)
        case Mod(dividend, divisor) =>
          visitUntil(dividend, f) || visitUntil(divisor, f)
        case Log(b,x) =>
          visitUntil(b, f) || visitUntil(x, f)
        case Floor(expr) => visitUntil(expr, f)
        case Sum(terms) =>
          terms.foreach(t => if (visitUntil(t, f)) return true)
          false
        case Prod(terms) =>
          terms.foreach(t => if (visitUntil(t, f)) return true)
          false
        case Var(_,_) |  Cst(_) | IfThenElse(_,_,_) | ArithExprFunction(_) | ? => false
        case _ => throw new RuntimeException(s"Cannot visit expression ${e}")
      }
    }
  }

  def substitute(e: ArithExpr, substitutions: scala.collection.immutable.Map[ArithExpr,ArithExpr]) : ArithExpr =
    substitutions.getOrElse(e, e) match {
      case Pow(l,r) => substitute(l,substitutions) pow substitute(r,substitutions)
      case IntDiv(n, d) => (substitute(n, substitutions) / substitute(d, substitutions))
      case Mod(dividend, divisor) => (substitute(dividend, substitutions) % substitute(divisor, substitutions))
      case Log(b,x) => Log(substitute(b, substitutions), substitute(x, substitutions))
      case IfThenElse(i, t, e) =>
        val cond = Predicate(substitute(i.lhs, substitutions), substitute(i.rhs, substitutions), i.op)
        cond ?? substitute(t, substitutions) !! substitute(e, substitutions)
      case Floor(expr) => Floor(substitute(expr, substitutions))
      case adds: Sum => (adds.terms.map(t => substitute(t, substitutions))).reduce(_+_)
      case muls: Prod => (muls.factors.map(t => substitute(t, substitutions))).reduce(_*_)
      case x => x
    }

  private def evalDouble(e: ArithExpr) : Double = e match {
    case Cst(c) => c

    case IntDiv(n, d) => scala.math.floor(evalDouble(n) / evalDouble(d))

    case Pow(base,exp) => scala.math.pow(evalDouble(base),evalDouble(exp))
    case Log(b,x) => scala.math.log(evalDouble(x)) / scala.math.log(evalDouble(b))

    case Mod(dividend, divisor) => dividend.eval % divisor.eval

    case Sum(terms) => terms.foldLeft(0.0)((result,expr) => result+evalDouble(expr))
    case Prod(terms) => terms.foldLeft(1.0)((result,expr) => result*evalDouble(expr))

    case Floor(expr) => scala.math.floor(evalDouble(expr))

    case _ => throw NotEvaluable
  }


  def toInt(e: ArithExpr): Int = ExprSimplifier(e) match {
    case Cst(i) => i
    case _ => throw NotEvaluable
  }


  def asCst(e: ArithExpr) = ExprSimplifier(e) match {
    case c:Cst => c
    case _ => throw new IllegalArgumentException
  }

  /**
   * Math operations derived from the basic operations
   */
  object Math {

    /**
     * Computes the minimal value between the two argument
     * @param x The first value
     * @param y The second value
     * @return The minimum between x and y
     */
    def Min(x: ArithExpr, y: ArithExpr) = {
      // Since Min duplicates the expression, we simplify it in place to point to the same node
      val sx = ExprSimplifier(x)
      val sy = ExprSimplifier(y)
      IfThenElse(sx le sy, sx, sy)
    }

    /**
     * Computes the maximal value between the two argument
     * @param x The first value
     * @param y The second value
     * @return The maximum between x and y
     */
    def Max(x: ArithExpr, y: ArithExpr) = {
      // Since Max duplicates the expression, we simplify it in place to point to the same node
      val sx = ExprSimplifier(x)
      val sy = ExprSimplifier(y)
      IfThenElse(sx gt sy, sx, sy)
    }

    /**
     * Clamps a value to a given range
     * @param x The input value
     * @param min Lower bound of the range
     * @param max Upper bound of the range
     * @return The value x clamped to the interval [min,max]
     */
    def Clamp(x: ArithExpr, min: ArithExpr, max: ArithExpr) = Min(Max(x,min),max)

    /**
     * Computes the absolute value of the argument
     * @param x The input value
     * @return |x|
     */
    def Abs(x: ArithExpr) = IfThenElse(x lt 0, 0-x, x)
  }

  def cardinal_id = 0
}

case object ? extends ArithExpr with SimplifiedExpr {

  override val digest: Int =  0x3fac31
}

case class Cst(c: Int) extends ArithExpr with SimplifiedExpr {

  /**
   * Lower and upper bounds of a constant are itself.
   */
  override lazy val (min,max): (ArithExpr, ArithExpr) = (this, this)

  override lazy val toString = c.toString

  override val digest: Int =  Integer.hashCode(c)
}


case class IntDiv(numer: ArithExpr, denom: ArithExpr) extends ArithExpr() {

  // Check that the denominator is not 0
  if(denom == Cst(0))
    throw new ArithmeticException()

  override def toString: String = s"(${numer}) / (${denom})"

  /**
   * Upper bound of the expression: for a fraction:
   *  - the minimal value is the smallest possible numerator divided by the greatest possible denominator
   *  - the maximal value is the greatest possible numerator divided by the smallest possible denominator
   */
  override lazy val (min,max): (ArithExpr, ArithExpr) = {
    ExprSimplifier(numer.max - denom.min) match {
      case Cst(x) if x < 0 => (Cst(0), Cst(0))
      case _ =>
        denom.min match {
          case Cst(0) => (Cst(0), ExprSimplifier(numer.max / denom.min))
          case _ => (ExprSimplifier(numer.min / denom.max), ExprSimplifier(numer.max / denom.min))
        }
    }
  }

  override val digest: Int =  0xf233de5a ^ numer.digest() ^ ~denom.digest()
}

case class Pow(b: ArithExpr, e: ArithExpr) extends ArithExpr {
  /**
   * Lower and upper bounds of the expression
   */
  override lazy val (min,max): (ArithExpr,ArithExpr) = {
    (b.min, b.max, e.min, e.max) match {
      case (Cst(x), Cst(y), Cst(a), Cst(b)) if x == y && a == b =>
        // If exponent and value are single point, emit single point
        val point = Cst(x) pow Cst(a)
        (point, point)
      case (Cst(x), Cst(y), Cst(a), Cst(b)) if x >= 0 && y >=0 && a > 0 && b > 0 =>
        // If the value is positive and the exponent is strictly positive, pow is monotonically increasing
        (Cst(x) pow Cst(a), Cst(y) pow Cst(b))
      case (Cst(x), Cst(y), Cst(a), Cst(b)) if x >= 0 && y >=0 && a < 0 && b < 0 =>
        // If the value is positive and the exponent is strictly negative, pow is monotonically decreasing
        (Cst(y) pow Cst(a), Cst(x) pow Cst(b))
      case x =>
        // Otherwise it could be anything
        (Var(""), Var(""))
    }
  }

  override def toString : String = e match {
    case Cst(-1) => "1/^("+b+")"
    case _ => "pow("+b+","+e+")"
  }

  override val digest: Int =  0x63fcd7c2 ^ b.digest() ^ e.digest()
}

case class Log(b: ArithExpr, x: ArithExpr) extends ArithExpr with SimplifiedExpr {
  override def toString: String = "log"+b+"("+x+")"

  override val digest: Int =  0x370285bf ^ b.digest() ^ ~x.digest()
}



/**
 * Represent a product of two or more expressions.
 * @param factors The list of factors. The list should contain at least 2 operands and should not contain other products.
 */
case class Prod private[arithmetic] (factors: List[ArithExpr]) extends ArithExpr {

  if (simplified) {
    Devel.Assert(factors.length > 1, s"Factors should have at least two terms in ${toString}")
    factors.foreach(x => {
      Devel.AssertNot(x.isInstanceOf[Prod], s"Prod cannot contain a Prod in ${toString}")
      Devel.AssertNot(x.isInstanceOf[Sum], "Prod should not contain a Sum")
    })
  }

  // TODO(tlutz): product depends on sign, should compute magnitude and sign independently
  override lazy val (min,max): (ArithExpr,ArithExpr) =
    (ExprSimplifier(factors.reduceLeft(_.min * _.min)), ExprSimplifier(factors.reduceLeft(_.max * _.max)))

  override def equals(that: Any) = that match {
    case p: Prod => factors.length == p.factors.length && factors.intersect(p.factors).length == factors.length
    case _ => false
  }

  override lazy val toString : String = {
    val m = if (factors.nonEmpty) factors.mkString("*") else {""}
    "(" + m +")"
  }

  def contains(e: ArithExpr): Boolean = factors.contains(e)

  override def hashCode(): Int = digest

  override lazy val digest: Int = factors.foldRight(0x286be17e)((x,hash) => hash ^ x.digest())

  /**
   * Remove a single factor from the list of factors and return either a Product of the factor left.
   * Removing a factor does not create new optimization opportunity, therefore the resulting prod is still simplified.
   */
  def withoutFactors(list: List[ArithExpr]): ArithExpr = {
    assert(simplified, "This function only works on simplified products")
    val rest = factors.diff(list)
    // If we took all the elements out, return neutral (1 for product)
    if (rest.length == 0) Cst(1)
    // If there is only one left, return it
    else if (rest.length == 1) rest.head
    // Otherwise create a new product, which is also simplified by construction
    else new Prod(rest) with SimplifiedExpr
  }

  /**
   * Short-hand to remove a single factor
   */
  def withoutFactor(factor: ArithExpr): ArithExpr = withoutFactors(List(factor))

  /**
   * The constant factor of the product
   */
  lazy val cstFactor: Cst = {
    if (simplified) factors.find(_.isInstanceOf[Cst]).getOrElse(Cst(1)).asInstanceOf[Cst]
    else Cst(factors.filter(_.isInstanceOf[Cst]).foldLeft[Int](1)(_ + _.asInstanceOf[Cst].c))
  }

  lazy val isNegatedTerm = cstFactor == Cst(-1)
}





case class Sum private[arithmetic] (terms: List[ArithExpr]) extends ArithExpr {

  if (simplified) {
    Devel.Assert(terms.length > 1, s"Terms should have at least two terms in ${toString}")
    terms.foreach(x => {
      Devel.AssertNot(x.isInstanceOf[Sum], "Sum cannot contain a Sum")
    })
  }

  override lazy val (min,max): (ArithExpr,ArithExpr) =
    (ExprSimplifier(terms.reduceLeft(_.min + _.min)), ExprSimplifier(terms.reduceLeft(_.max + _.max)))

  override def equals(that: Any) = that match {
    case s: Sum => terms.length == s.terms.length && terms.intersect(s.terms).length == terms.length
    case _ => false
  }

  override def hashCode(): Int = digest

  override lazy val toString: String = {
    val m = if (terms.nonEmpty) terms.mkString("+") else {""}
    "(" + m +")"
  }

  /**
   * Remove a single factor from the list of factors and return either a Sum of the only term left.
   * Removing a term does not create new optimization opportunity, therefore the resulting sum is still simplified.
   */
  def withoutTerm(list: List[ArithExpr]): ArithExpr = {
    assert(simplified, "This function only works on simplified products")
    val rest = terms.diff(list)
    assert(rest.size > 0, "Cannot remove all factors from a product")
    if (rest.length == 1) rest.head
    else new Sum(rest) with SimplifiedExpr
  }

  override val digest: Int = terms.foldRight(0x8e535130)((x,hash) => hash ^ x.digest())

  lazy val cstTerm: Cst = {
    if (simplified) terms.find(_.isInstanceOf[Cst]).getOrElse(Cst(0)).asInstanceOf[Cst]
    else Cst(terms.filter(_.isInstanceOf[Cst]).foldLeft[Int](0)(_ + _.asInstanceOf[Cst].c))
  }
}

case class Mod(dividend: ArithExpr, divisor: ArithExpr) extends ArithExpr {
  override lazy val min: ArithExpr = {
    (dividend.min,divisor.min) match {
      case (Cst(0),a) => Cst(0)
      case (a,b) => Cst(1) - b
    }
  }

  override lazy val max: ArithExpr = ExprSimplifier(divisor.max - 1)

  override lazy val toString: String = "(" + dividend + " % " + divisor + ")"

  override val digest: Int =  0xedf6bb88 ^ dividend.digest() ^ ~divisor.digest()
}

case class Floor(ae : ArithExpr) extends ArithExpr {
  override lazy val toString: String = "Floor(" + ae + ")"

  override val digest: Int =  0x558052ce ^ ae.digest()
}

/**
 * Conditional operator. Behaves like the `?:` operator in C.
 * @param test A Predicate object.
 * @param t The 'then' block.
 * @param e The 'else block.
 */
case class IfThenElse(test: Predicate, t : ArithExpr, e : ArithExpr) extends ArithExpr {
  override lazy val toString: String = s"( ${test} ? ${t} : ${e} )"

  override val digest: Int =  0x32c3d095 ^ test.digest ^ t.digest() ^ ~e.digest()
}

case class ArithExprFunction(var range: Range = RangeUnknown) extends ArithExpr with SimplifiedExpr {
  override val digest: Int =  0x3105f133 ^ range.digest()
}

/**
 * Represents a variable in the expression. A variable is an unknown term which is immutable within the expression
 * but its value may change between expression, like a variable in C (cf sequence point).
 * @param name Identifier for the variable. Might be empty, in which case a name will be generated.
 * @param range
 * @note The uniqueness of the variable name is not enforced since there is no notion of scope.
 *       Also note that the name is purely decorative during partial evaluation: the variable is actually tracked
 *       using an instance counter, hence multiple instances sharing the same name will not be simplified.
 */
case class Var(name: String, var range : Range = RangeUnknown) extends ArithExpr with SimplifiedExpr {

  override lazy val (min,max): (ArithExpr, ArithExpr) = (this,this)

  /** Unique identifier. */
  val id: Int = {
    Var.cnt += 1
    Var.cnt
  }

  override def equals(that: Any) = that match {
    case v: Var => this.id == v.id
    case _ => false
  }

  override lazy val hashCode = 8 * 79 + id

  override lazy val toString = if (name == "") s"v_${id}" else name + s"_${id}"

  def updateRange(func: (Range) => Range): Unit = {
    if (range != RangeUnknown) {
      range = func(range)
    }
  }

  override val digest: Int = 0x54e9bd5e /*^ name.hashCode*/ ^ Integer.hashCode(id) ^ range.digest()
}




/* ==  Companion objects == */
object ArithExprFunction {

  def getArithExprFuns(expr: ArithExpr) : Set[ArithExprFunction] = {
    val exprFunctions = scala.collection.mutable.HashSet[ArithExprFunction]()
    ArithExpr.visit(expr, {
      case function: ArithExprFunction => exprFunctions += function
      case _ =>
    })
    exprFunctions.toSet
  }
}

object Var {
  /**
   * Instance counter
   */
  var cnt: Int = -1

  def apply(range : Range) : Var = new Var("", range)

  def setVarsAtRandom(vars : Set[Var]) : scala.collection.immutable.Map[Var, Cst] = {

    var changed = false
    var substitutions : immutable.Map[Var, Cst] = new immutable.HashMap[Var, Cst]()
    var newVars : Set[Var] = vars

    do {
      changed = false

      // create a map of variable substitution
      val newSubsts : immutable.HashMap[Var, Cst] = newVars.foldLeft(immutable.HashMap[Var, Cst]())((map,v) => v.range match {
        case RangeAdd(Cst(start), Cst(stop), Cst(step)) => map+ (v -> Cst(Random.nextInt((stop - start) / step + 1) * step + start))
        case RangeMul(Cst(start), Cst(stop), Cst(mul))  => map+ (v -> Cst(start * math.pow(mul,Random.nextInt((math.log(stop / start) / math.log(mul) + 1).toInt)).toInt))
        case _ => map
      })

      if (newSubsts.nonEmpty)
        changed = true
      substitutions = substitutions ++ newSubsts

      // remove from the set of variables the ones which have a substitution
      newVars = newVars-- newSubsts.keySet

      // apply the substitutions in the range of each variable
      newVars.map(v => {
        v.range match {
          case RangeAdd(start, stop, step) => v.range = RangeAdd(
            ExprSimplifier(ArithExpr.substitute(start, newSubsts.toMap)),
            ExprSimplifier(ArithExpr.substitute(stop, newSubsts.toMap)),
            ExprSimplifier(ArithExpr.substitute(step, newSubsts.toMap)))
          case RangeMul(start, stop, step) => v.range = RangeMul(
            ExprSimplifier(ArithExpr.substitute(start, newSubsts.toMap)),
            ExprSimplifier(ArithExpr.substitute(stop, newSubsts.toMap)),
            ExprSimplifier(ArithExpr.substitute(step, substitutions.toMap)))
          case _ =>
        }
        v
      })
    } while (changed)

    substitutions
  }
}

object SizeVar {
  def apply(name: String): Var = new Var(name, StartFromRange(Cst(1))){
    override lazy val min = Cst(1)
  }
}

trait SimplifiedExpr extends ArithExpr {
  override val simplified = true
}
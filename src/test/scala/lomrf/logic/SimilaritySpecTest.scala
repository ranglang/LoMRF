/*
 * o                        o     o   o         o
 * |             o          |     |\ /|         | /
 * |    o-o o--o    o-o  oo |     | O |  oo o-o OO   o-o o   o
 * |    | | |  | | |    | | |     |   | | | |   | \  | |  \ /
 * O---oo-o o--O |  o-o o-o-o     o   o o-o-o   o  o o-o   o
 *             |
 *          o--o
 * o--o              o               o--o       o    o
 * |   |             |               |    o     |    |
 * O-Oo   oo o-o   o-O o-o o-O-o     O-o    o-o |  o-O o-o
 * |  \  | | |  | |  | | | | | |     |    | |-' | |  |  \
 * o   o o-o-o  o  o-o o-o o o o     o    | o-o o  o-o o-o
 *
 * Logical Markov Random Fields.
 *
 * Copyright (c) Anastasios Skarlatidis.
 *
 * This file is part of Logical Markov Random Fields (LoMRF).
 *
 * LoMRF is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * LoMRF is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with LoMRF. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package lomrf.logic

import org.scalatest.{Matchers, FunSpec}

/**
 * Similarity specification test. It tests the similarity operation between atomic formulas
 * and between clauses.
 */
class SimilaritySpecTest extends FunSpec with Matchers {

  val atomicFormulasA = Set(AtomicFormula("PredA", Vector(Variable("x"), Variable("y"))),
                            AtomicFormula("PredA", Vector(Variable("x"), Variable("t"))),
                            AtomicFormula("PredA", Vector(Variable("z"), Variable("x"))) )

  val atomicFormulasB = Set(AtomicFormula("PredB", Vector(Variable("p"), Constant("A"), Variable("q"))),
                            AtomicFormula("PredB", Vector(Variable("q"), Constant("A"), Variable("p"))) )


  describe("Similarity check between atomic formulas") {

    it("All atomic formulas in set A should be similar to each other") {
      atomicFormulasA.forall { atom => atomicFormulasA.forall(_ =~= atom) } shouldBe true
    }

    it("All atomic formulas in set B should be similar to each other") {
      atomicFormulasB.forall { atom => atomicFormulasB.forall(_ =~= atom) } shouldBe true
    }

    it("Atomic formulas between sets A and B should NOT be similar to each other") {
      atomicFormulasA.forall { atom => atomicFormulasB.forall(other => !(other =~= atom)) } shouldBe true
    }

  }

  describe("Similarity check for clauses having only positive or only negated literals") {

    var clausesAllPositiveLiterals = Set[Clause]()
    var clausesAllNegativeLiterals = Set[Clause]()

    atomicFormulasA.foreach { atomA =>
      atomicFormulasB.foreach { atomB =>
        clausesAllPositiveLiterals += Clause(Set(Literal.asPositive(atomA), Literal.asPositive(atomB)))
        clausesAllNegativeLiterals += Clause(Set(Literal.asNegative(atomA), Literal.asNegative(atomB)))
      }
    }

    it("All clauses should be similar to each other") {
      clausesAllPositiveLiterals.forall { clause => clausesAllPositiveLiterals.forall(_ =~= clause) } shouldBe true
      clausesAllNegativeLiterals.forall { clause => clausesAllNegativeLiterals.forall(_ =~= clause) } shouldBe true
    }
  }

  describe("Similarity check between clauses having atoms from set B, but different sense") {

    val clauseA = Clause(Set(Literal.asNegative(atomicFormulasB.head), Literal.asNegative(atomicFormulasB.last)))
    val clauseB = Clause(Set(Literal.asNegative(atomicFormulasB.head), Literal.asPositive(atomicFormulasB.last)))
    val clauseC = Clause(Set(Literal.asNegative(atomicFormulasB.last), Literal.asPositive(atomicFormulasB.head)))

    it("Each clause should be similar to itself") {
      clauseA =~= clauseA shouldBe true
      clauseB =~= clauseB shouldBe true
      clauseC =~= clauseC shouldBe true
    }

    it("!PredB(p, A, q) v !PredB(q, A, p) should NOT be similar to !PredB(p, A, q) v Pred(q, A, p)") {
      clauseA =~= clauseB shouldBe false
    }

    it("!PredB(p, A, q) v PredB(q, A, p) should be similar to PredB(p, A, q) v !PredB(q, A, p)") {
      clauseB =~= clauseC shouldBe true
    }
  }

  describe("Similarity check between clauses having atoms from both sets, but different sense") {

    val clauseA = Clause(Set( Literal.asNegative(atomicFormulasB.head),
                              Literal.asNegative(atomicFormulasA.head),
                              Literal.asNegative(atomicFormulasB.last)) )

    val clauseB = Clause(Set( Literal.asNegative(atomicFormulasB.head),
                              Literal.asNegative(atomicFormulasA.last),
                              Literal.asPositive(atomicFormulasB.last)) )

    val clauseC = Clause(Set( Literal.asNegative(atomicFormulasB.last),
                              Literal.asNegative(atomicFormulasA.head),
                              Literal.asPositive(atomicFormulasB.head)) )

    val clauseD = Clause(Set( Literal.asNegative(atomicFormulasB.last),
                              Literal.asPositive(atomicFormulasA.head),
                              Literal.asNegative(atomicFormulasB.head)) )

    it("Each clause should be similar to itself") {
      clauseA =~= clauseA shouldBe true
      clauseB =~= clauseB shouldBe true
      clauseC =~= clauseC shouldBe true
    }

    it("!PredB(p, A, q) v !PredA(x, y) v !PredB(q, A, p) should NOT be similar to !PredB(p, A, q) v !PredA(z, t) v Pred(q, A, p)") {
      clauseA =~= clauseB shouldBe false
    }

    it("!PredB(p, A, q) v PredA(z, t) v PredB(q, A, p) should be similar to PredB(p, A, q) v PredA(x, y) v !PredB(q, A, p)") {
      clauseB =~= clauseC shouldBe true
    }

    it("!PredB(p, A, q) v PredA(x, y) v !Pred(q, A, p) should NOT be similar to !PredB(p, A, q) v !PredA(x, y) v !PredB(q, A, p)") {
      clauseD =~= clauseA shouldBe false
    }

  }

}

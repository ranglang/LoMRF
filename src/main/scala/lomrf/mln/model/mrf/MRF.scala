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
 * Copyright (C) 2012  Anastasios Skarlatidis.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package lomrf.mln.model.mrf

import gnu.trove.map.TIntObjectMap
import gnu.trove.map.hash.TIntObjectHashMap
import lomrf.mln.model.MLN


/**
 * This class represents a ground Markov Random Field.
 *
 * @param mln the source Markov Logic.
 * @param constraints the indexed collection of ground clauses.
 * @param atoms a indexed collection of ground atoms.
 * @param pLit2Constraints a map that keeps track which clauses contain the same positive literal.
 * @param nLit2Constraints a map that keeps track which clauses contain the same negative literal.
 * @param queryAtomStartID the id (integer) of the first query atom.
 * @param queryAtomEndID  the id (integer) of the last query atom.
 * @param weightHard the estimated weight for all hard-constrained ground clauses.
 * @param maxNumberOfLiterals that is the length of the bigger ground clause in this MRF.
 *
 *
 * @author Anastasios Skarlatidis
 */
class MRF(val mln: MLN,
          val constraints: TIntObjectMap[Constraint],
          val atoms: TIntObjectMap[GroundAtom],
          val pLit2Constraints: TIntObjectMap[collection.Iterable[Constraint]],
          val nLit2Constraints: TIntObjectMap[collection.Iterable[Constraint]],
          val queryAtomStartID: Int,
          val queryAtomEndID: Int,
          val weightHard: Double,
          val maxNumberOfLiterals: Int) {

  val numberOfConstraints = constraints.size()
  val numberOfAtoms = atoms.size()

  def apply(cid: Int) = constraints.get(cid)
}

object MRF {

  val NO_ATOM_ID = 0
  val NO_CONSTRAINT_ID = -1
  val NO_ATOM = new GroundAtom(0, 0)
  val NO_CONSTRAINT: Constraint = new Constraint(Double.NaN, Array(0), true, 0)

  val MODE_MWS = 0
  val MODE_SAMPLE_SAT = 1

  /**
   *
   * @param mln the source Markov Logic.
   * @param constraints the indexed collection of ground clauses.
   * @param atoms a indexed collection of ground atoms.
   * @param weightHard the estimated weight for all hard-constrained ground clauses.
   * @param queryAtomStartID the id (integer) of the first query atom.
   * @param queryAtomEndID the id (integer) of the last query atom.
   *
   * @return a new MRF object
   */
  def apply(mln: MLN, constraints: TIntObjectMap[Constraint], atoms: TIntObjectMap[GroundAtom], weightHard: Double, queryAtomStartID: Int, queryAtomEndID: Int): MRF = {

    //create positive-and-negative literal to constraint occurrence maps
    val iterator = constraints.iterator()
    val pLit2Constraints = new TIntObjectHashMap[List[Constraint]]()
    val nLit2Constraints = new TIntObjectHashMap[List[Constraint]]()

    var maxNumberOfLiterals = 0

    while (iterator.hasNext) {
      iterator.advance()
      val constraint = iterator.value()

      if (constraint.literals.length > maxNumberOfLiterals) maxNumberOfLiterals = constraint.literals.length

      for (literal <- constraint.literals) {
        val atomID = math.abs(literal)
        if (literal > 0) {
          val constraints = pLit2Constraints.get(atomID)
          if (constraints eq null)
            pLit2Constraints.put(atomID, List(constraint))
          else
            pLit2Constraints.put(atomID, constraint :: constraints)
        }
        else {
          val constraints = nLit2Constraints.get(atomID)
          if (constraints eq null)
            nLit2Constraints.put(atomID, List(constraint))
          else
            nLit2Constraints.put(atomID, constraint :: constraints)
        }
      }
    }

    new MRF(mln, constraints, atoms,
      pLit2Constraints.asInstanceOf[TIntObjectMap[collection.Iterable[Constraint]]],
      nLit2Constraints.asInstanceOf[TIntObjectMap[collection.Iterable[Constraint]]],
      queryAtomStartID, queryAtomEndID, weightHard, maxNumberOfLiterals)
  }
}




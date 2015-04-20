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

package lomrf.mln.grounding

import java.{util => jutil}
import akka.actor.ActorRef
import gnu.trove.set.TIntSet
import lomrf.logic._
import lomrf.mln.model.MLN
import lomrf.util.AtomIdentityFunction.IDENTITY_NOT_EXIST
import lomrf.util.AtomIdentityFunction
import auxlib.log.Logging
import lomrf.util.collection.IndexPartitioned

import scala.collection._
import scala.language.postfixOps
import scalaxy.streams.optimize

/**
 * Experimental implementation of efficient grounding.
 *
 * @param clause the FOL clause to ground
 * @param clauseIndex the index of the clause in the MLN theory
 * @param mln the MLN theory
 * @param cliqueRegisters partitioned collection of clique registers
 * @param atomSignatures the signatures of the atoms collected so far by the grounding process
 * @param atomsDB partitioned collection of atomDBs
 * @param orderedLiterals an array composed of the clause literals, but sorted according to an Ordering[Literal]
 * @param orderedIdentityFunctions an array composed of the corresponding AtomIdentifyFunctions, w.r.t. the Ordering[Literal]
 * @param owaLiterals an array holding the literals with open-world assumption
 * @param dynamicAtoms a Map of literal position to function that evaluates its state given a vector of constants (i.e., String)
 * @param length the maximum number of produced random variables,
 *               i.e., groundings of literals that do not belong to the given evidence.
 * @param noNegWeights when it is true the negative weight are eliminated by applying the de Morgan's law
 *                     (translating disjunctions to conjunctions, thus multiple clauses), otherwise negative weight can exist.
 * @param eliminateNegatedUnit when it is true, the negated single literal in ground unit clauses (i.e., clauses containing
 *                             only one literal with open-world assumption) are eliminated by inverting their literal and
 *                             multiplying by -1.0 their corresponding weight values.
 */
class ClauseGrounderImplNew private(
                          val clause: Clause,
                          clauseIndex: Int,
                          mln: MLN,
                          cliqueRegisters: IndexPartitioned[ActorRef],
                          atomSignatures: Set[AtomSignature],
                          atomsDB: Array[TIntSet],
                          orderedLiterals: Array[Literal],
                          orderedIdentityFunctions: Array[AtomIdentityFunction],
                          owaLiterals: Array[Literal],
                          dynamicAtoms: Map[Int, (Vector[String] => Boolean)],
                          length: Int,
                          noNegWeights: Boolean,
                          eliminateNegatedUnit: Boolean) extends ClauseGrounder with Logging {

  require(orderedLiterals.length == orderedIdentityFunctions.length,
    "The number of literals should be the same with the number of identity functions.")

  require(!clause.weight.isNaN, "Clause weight cannot be NaN.")

  // The number of clique batches is same with the number of clique registers, by default is the number of available
  // virtual processors
  //private val cliqueBatches = cliqueRegisters.length

  // The number of atom DBs. Minimum is equal with the number of query atoms and maximum is the number of query and
  // hidden atoms.
  private val atomsDBBatches = atomsDB.length

  def collectedSignatures = clause.literals.map(_.sentence.signature) -- atomSignatures


  def computeGroundings() {

    // Vector containing the unique collection of variables that appear in the 'orderedLiterals' collection,
    // retaining the order of their first appearance.
    val uniqOrderedVars = uniqueOrderedVariablesIn(orderedLiterals)

    // A utility Map for associating Variable -> Index in uniqOrderedVars
    val var2Idx = uniqOrderedVars.zipWithIndex.toMap

    // The variable index (in uniqOrderedVars) of the last variable that appears in the literal
    // Example:
    // - Assume the uniqOrderedVars = Vector(z,x,y)
    // - The var2Idx is Map(x -> 1, y -> 2, z -> 0 )
    // - For the literal 'Foo(x,y,z)', the last variable is 'z' and its index is '0'
    // - Similarly for the literal '!Bar(x,y)', the last variable is 'y' and its index is '2'
    // - Thus, litLastVarIdx for the clause Foo(x,y,z) v !Bar(x,y) is the 'Array(0, 2)'
    val litLastVarIdx = orderedLiterals.map(literal => var2Idx(variablesIn(literal.sentence.terms).last))

    // Utility position to indicate whether to keep or not the current ground clause
    val DROP_IDX = orderedLiterals.length + 1

    val IS_TAUTOLOGY = -1

    // Keeps count of the number of ground clauses that are produced
    var groundClausesCounter = 0

    def performGrounding(substitution: Array[Int]): Int = {

      var sat = 0

      // an array of integer literals, indicating the current ground clause's literals
      val currentVariables = new Array[Int](length)

      // partial function for substituting terms w.r.t the given theta
      //val substitution = substituteTerm(theta) _
      var idx = 0 //literal position index in the currentVariables array

      // current position in orderedLiterals
      var literalIdx = 0

      while (literalIdx < orderedLiterals.length) {
        val literal = orderedLiterals(literalIdx)
        val idf = orderedIdentityFunctions(literalIdx)

        // When the literal is a dynamic atom, then invoke its truth state dynamically
        if (literal.sentence.isDynamic) {
          //TODO:
          //if (literal.isPositive == dynamicAtoms(idx)(literal.sentence.terms.map(substitution))) literalIdx = DROP
          sys.error("Dynamic atoms are not supported yet!")
        }
        else {
          //TODO: atomID = ??
          // Otherwise, invoke its state from the evidence
          val atomID = IDENTITY_NOT_EXIST //idf.encode(atom2TermIndexes(literalIdx), substitution)

          if (atomID == IDENTITY_NOT_EXIST) {
            // Due to closed-world assumption in the evidence atoms or in the function mappings,
            // the identity of the atom cannot be determined and in that case the current clause grounding
            // will be omitted from the MRF
            literalIdx = DROP_IDX
          }
          else {
            // Otherwise, the atomID has a valid id number and the following pattern matching procedure
            // investigates whether the current literal satisfies the ground clause. If it does, the clause
            // is omitted from the MRF, since it is always satisfied from that literal.
            val state = mln.atomStateDB(literal.sentence.signature)(atomID).value
            if ((literal.isNegative && (state == FALSE.value)) || (literal.isPositive && (state == TRUE.value))) {
              // the clause is always satisfied from that literal
              sat += 1
              literalIdx = DROP_IDX //we don't need to keep that ground clause
            }
            else if (state == UNKNOWN.value) {
              // The state of the literal is unknown, thus the literal will be stored to the currentVariables
              currentVariables(idx) = atomID
              idx += 1
            }
          }
        }
        literalIdx += 1
      }

      var canSend = false //utility flag

      if (literalIdx < DROP_IDX) {
        // So far the ground clause is produced, but we have to
        // examine whether we will keep it or not. If the
        // ground clause contains any literal that is included in the
        // atomsDB, then it will be stored (and later will be send to clique registers),
        // otherwise it will not be stored and omitted.

        var owaIdx = 0
        val cliqueVariables = new Array[Int](idx)

        optimize{
          for (i <- 0 until idx) {
            //val currentLiteral = iterator.next()
            val currentAtomID = currentVariables(i)
            cliqueVariables(i) = if (owaLiterals(owaIdx).isPositive) currentAtomID else -currentAtomID

            // Examine whether the current literal is included to the atomsDB. If it isn't,
            // the current clause will be omitted from the MRF
            val atomsDBSegment = atomsDB(currentAtomID % atomsDBBatches)

            if (!canSend && (atomsDBSegment ne null)) canSend = atomsDBSegment.contains(currentAtomID)
            else if (atomsDBSegment eq null) canSend = true // this case happens only for Query literals

            owaIdx += 1
          }
        }


        if (canSend) {
          // Finally, the current ground clause will be included in the MRF.
          // However, if the weight of the clause is a negative number, then
          // the ground clause will be negated and broke up into several
          // unit ground clauses with positive weight literals.

          if (noNegWeights && clause.weight < 0) {
            if (cliqueVariables.length == 1) {
              // If the clause is unit and its weight value is negative
              // negate this clause (e.g. the clause "-w A" will be converted into w !A)
              cliqueVariables(0) = -cliqueVariables(0)
              store(-clause.weight, cliqueVariables, -1)
              groundClausesCounter += 1
            }
            else {
              val posWeight = -clause.weight / cliqueVariables.length
              optimize{
                for(i <- 0 until cliqueVariables.length){
                  store(posWeight, Array(-cliqueVariables(i)), -1)
                  groundClausesCounter += 1
                }
              }
            }
          }
          else {

            // When we have a typical ground clause, with at least two literals,
            // we simply sort its literals and thereafter we store the ground clause.
            if (cliqueVariables.length > 1) {
              jutil.Arrays.sort(cliqueVariables)
              store(clause.weight, cliqueVariables, 1)
            }
            // Otherwise, we have a unit ground clause
            else {
              // When the flag 'eliminateNegatedUnit=true' and its unique literal is negative
              // then we negate the literal and invert the sign of its weight value
              if(eliminateNegatedUnit && cliqueVariables(0) < 0){
                cliqueVariables(0) = -cliqueVariables(0)
                store(-clause.weight, cliqueVariables, -1)
              }
              // Otherwise, store the unit clause as it is.
              else store(clause.weight, cliqueVariables, 1)
            }
            groundClausesCounter += 1
          }
          //groundClausesCounter = 1
        }
      }


      // Todo: when canSend is true give the index of the last tautological variable
      if(canSend) litLastVarIdx(literalIdx)
      else IS_TAUTOLOGY
    }

    // TODO:
    def substituteTerm(termIdx: Int): Int ={

      ???
    }


    // Extract the sequence of unique variables and find their corresponding domain sizes
    val variableDomainSizes = uniqOrderedVars.map(v => mln.constants(v.domain).size).toArray

    // The maximum number of Cartesian products according to the domain sizes of all unique variables in this clause
    val MAX_PRODUCT = variableDomainSizes.product

    // Array that contains the initial domain index for each variable
    val initialVariableIndexes = variableDomainSizes.map(_ - 1)

    // At the beginning the current substitution is the same with the initial domain index of each variable.
    val currentSubstitution = initialVariableIndexes.clone()

    val LAST_VARIABLE_INDEX = currentSubstitution.length - 1
    var stop_inner = false
    var variable_idx = LAST_VARIABLE_INDEX
    var product_counter = 0



    while (variable_idx >= 0 && (product_counter < MAX_PRODUCT)) {

      performGrounding(currentSubstitution)


      // In case of a tautology, LT_IDX gives the last index of tautological variable
      val LT_IDX = performGrounding(currentSubstitution)

      if(LT_IDX == IS_TAUTOLOGY) // Ignore the products, in which we have the same tautological literals
        variable_idx = LT_IDX
      else {
        variable_idx = LAST_VARIABLE_INDEX
        product_counter += 1
      }

      // Reset stop_inner flag
      stop_inner = false

      // Proceed to the inner loop for the production of the next candidate Cartesian product
      while (!stop_inner && variable_idx >= 0) {

        if (currentSubstitution(variable_idx) > 0) {
          currentSubstitution(variable_idx) -= 1
          stop_inner = true

          val pos = variable_idx + 1

          if (pos <= LAST_VARIABLE_INDEX)
            System.arraycopy(initialVariableIndexes, pos, currentSubstitution, pos, LAST_VARIABLE_INDEX - pos + 1)
        }
        else variable_idx -= 1
      }
    }

  }



  /*private def substituteTerm(theta: collection.Map[Variable, String])(term: Term): String = term match {
    case c: Constant => c.symbol
    case v: Variable => theta(v)
    case f: TermFunction =>
      mln.functionMappers.get(f.signature) match {
        case Some(m) => m(f.terms.map(a => substituteTerm(theta)(a)))
        case None => fatal("Cannot apply substitution using theta: " + theta + " in function " + f.signature)
      }
  }*/

  /**
   *
   * @param weight the clause weight
   * @param variables the ground clause literals (where negative values indicate negated atoms)
   * @param freq: -1 when the clause weight is been inverted, +1 otherwise.
   */
  private def store(weight: Double, variables: Array[Int], freq: Int) {
    var hashKey = jutil.Arrays.hashCode(variables)
    if (hashKey == 0) hashKey += 1 //required for trove collections, since zero represents the key-not-found value

    cliqueRegisters(hashKey) ! messages.CliqueEntry(hashKey, weight, variables, clauseIndex, freq )
  }

}

object ClauseGrounderImplNew {

  /**
   * @param clause the clause to ground
   * @param mln the MLN instance
   * @param cliqueRegisters the collection of available clique registers, to send groundings of the user specified clause.
   * @param atomSignatures the set of required atom signatures
   * @param atomsDB the Atoms DB, i.e., collection of integer sets that have been grounded
   * @param noNegWeights when it is true the negative weights are eliminated, otherwise the weights remain the same.
   * @param eliminateNegatedUnit when it is true the unit clauses with negative weights are eliminated.
   *
   * @return a new instance of ClauseGrounderImplNew
   */
  def apply(clause: Clause, clauseIndex: Int, mln: MLN, cliqueRegisters: IndexPartitioned[ActorRef], atomSignatures: Set[AtomSignature],
            atomsDB: Array[TIntSet], noNegWeights: Boolean = false, eliminateNegatedUnit: Boolean = false): ClauseGrounderImplNew = {

    /**
     * A utility Map that associates Variables with an iterable collection with its possible instantiations (according to
     * their given domain). For example, the predicate {{{HoldsAt(f, t)}}} has two variables, i.e., 'f' and 't'. Assume
     * that the possible instantiations of 't', according to some given evidence, is the discrete set 1 to 100. The Map
     * will contain as a key the {{{Variable("t")}}} and as a value the iterable collection of 1 to 100.
     */
    /*val variableDomains: Map[Variable, Iterable[String]] = {
      if (clause.isGround) Map.empty[Variable, Iterable[String]]
      else (for (v <- clause.variables) yield v -> mln.constants(v.domain))(breakOut)
    }*/

    /**
     * A utility Map that associates AtomSignatures with AtomIdentityFunction (= Bijection of ground atoms to integers).
     * This Map contains information only for ordinary atoms (not dynamic atoms).
     */
    /*val identities: Map[AtomSignature, AtomIdentityFunction] =
    (for (literal <- clause.literals if !mln.isDynamicAtom(literal.sentence.signature))
    yield literal.sentence.signature -> mln.identityFunctions(literal.sentence.signature))(breakOut)*/


    val orderedLiterals =
      clause
      .literals
      .toArray
      .sortBy(entry => entry)(ClauseLiteralsOrdering(mln))

    val orderedIdentityFunctions =
      orderedLiterals
        .map(literal => mln.space.identities(literal.sentence.signature))


    // Collect literals with open-world assumption
    val owaLiterals: Array[Literal] = orderedLiterals
      .filter(literal => mln.isTriState(literal.sentence.signature))

    // Collect dynamic atoms
    val dynamicAtoms: Map[Int, (Vector[String] => Boolean)] =
      (for (i <- orderedLiterals.indices; sentence = orderedLiterals(i).sentence; if sentence.isDynamic)
      yield i -> mln.dynamicPredicates(sentence.signature))(breakOut)


    val length = clause.literals.count(l => mln.isTriState(l.sentence.signature))

    // Create the new instance 'ClauseGrounderImplNew':
    new ClauseGrounderImplNew(
      clause, clauseIndex, mln, cliqueRegisters, atomSignatures, atomsDB,
      orderedLiterals, orderedIdentityFunctions, owaLiterals,
      dynamicAtoms,length, noNegWeights, eliminateNegatedUnit)
  }




}

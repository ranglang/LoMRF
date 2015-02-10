package lomrf.mln.grounding

import lomrf.logic.AtomSignature
import lomrf.mln.model.MLN
import lomrf.mln.model.mrf.MRF
import org.scalatest.{Matchers, FunSpec}

/**
 * @author Anastasios Skarlatidis
 */
class DependencyMapSpecTest extends FunSpec with Matchers {

  implicit def str2AtomSignature(txt: String): AtomSignature = {
    val splitted = txt.split("/")
    assert(splitted.length == 2)
    AtomSignature(splitted(0), splitted(1).toInt)
  }

  private val sep = System.getProperty("file.separator")
  private val prefix =
    System.getProperty("user.dir") + sep + "Examples" + sep + "data" + sep + "tests" + sep + "DependencyMap" + sep
  private val mlnFile = prefix + "DependencyMap.mln"
  private val evidenceFile = prefix + "Empty.db"

  val mln = MLN(
    mlnFile,
    evidenceFile,
    queryAtoms = Set("Smokes/1", "Cancer/1"),
    cwa = Set.empty,
    owa = Set.empty)

  val mrf = MRF.build(mln)




}

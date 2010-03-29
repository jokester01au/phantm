package phpanalysis.analyzer

import analyzer.Symbols.GlobalSymbols
import parser.Trees._
import analyzer.Types._
import java.io.File

object Evaluator {

    def typeFromExpr(oe: Option[Expression]): Type = oe match {
        case Some(e) => typeFromExpr(e)
        case None => TNull
    }

    def typeFromExpr(e: Expression): Type = e match {
        case PHPTrue() => TBoolean
        case PHPFalse() => TBoolean
        case PHPInteger(_) => TInt
        case PHPFloat(_) => TFloat
        case PHPString(_) => TString
        case PHPNull() => TAny
        case MCFile() => TString
        case MCLine() => TString
        case MCDir() => TString
        case MCClass() => TString
        case MCFunction()  => TString
        case MCMethod() => TString
        case MCNamespace() => TString
        case Minus(_, _) => TInt
        case a: Array =>
            //TODO
            TAnyArray
        case _=>
            TAny
    }

    def scalarToString(ex: Scalar) = ex match {
        case PHPTrue() =>
            "1"
        case PHPFalse() =>
            ""
        case PHPInteger(value) =>
            ""+value
        case PHPFloat(value) =>
            ""+value
        case PHPString(value) =>
            value
        case PHPNull() =>
            ""

        case MCFile() =>
            ex.file match {
                case Some(p) =>
                    new File(p).getAbsolutePath()
                case None =>
                    "internal"
            }
        case MCLine() =>
            ""+ex.line
        case MCDir() =>
            ex.file match {
                case Some(p) =>
                    dirname(new File(p).getAbsolutePath())
                case None =>
                    "internal"
            }
    }

    def staticEval(ex: Expression): Option[Scalar] = staticEval(ex, true)

    def staticEval(ex: Expression, issueErrors: Boolean): Option[Scalar] = ex match {
        case Concat (lhs, rhs) =>
            (staticEval(lhs, issueErrors), staticEval(rhs, issueErrors)) match {
                case (Some(slhs), Some(srhs)) => Some(PHPString(scalarToString(slhs)+scalarToString(srhs)).setPos(slhs))
                case _ => None
            }
        case FunctionCall(StaticFunctionRef(_,_,Identifier("dirname")), List(CallArg(arg, _))) =>
            staticEval(arg, issueErrors) match {
                case Some(a) =>
                    Some(PHPString(dirname(scalarToString(a))).setPos(ex))
                case None =>
                    None
            }
        case Constant(name) =>
            GlobalSymbols.lookupConstant(name.value) match {
                case Some(cs) =>
                    cs.value match {
                        case Some(v) =>
                            Some(v)
                        case None =>
                            Some(PHPString(name.value).setPos(ex))
                    }
                case None =>
                    if (issueErrors) {
                        Reporter.notice("Potentially undefined constant '"+name.value+"'", ex)
                    }
                    Some(PHPString(name.value).setPos(ex))
            }
        case ClassConstant(_:StaticClassRef, _) =>
            Some(PHPString("CLASSCONSTANT").setPos(ex))
        case sc: Scalar =>
            Some(sc)
        case _ =>
            None
    }

    def dirname(path: String): String = {
        val ind = path.lastIndexOf('/')

        if (ind < 0) {
            "."
        } else {
            path.substring(0, ind)
        }
    }

}

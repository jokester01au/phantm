package phpanalysis.controlflow

import CFGTrees._
import scala.collection.immutable.HashMap
import scala.collection.immutable.HashSet
import scala.collection.immutable.Map
import analyzer.Symbols._
import analyzer.Types._

object TypeFlow {
    case object TypeLattice extends Lattice[TypeEnvironment] {
        type E = Type

        def leq(tex: TypeEnvironment, tey: TypeEnvironment, x : Type, y : Type): Boolean = (x,y) match {
            case (x, y) if x == y => true

            case (TBottom, _) => true
            case (_, TTop) => true
            case (_:ConcreteType, TAny) => true
            case (TTrue, TBoolean) => true
            case (TFalse, TBoolean) => true
            case (t1: TObjectRef, TAnyObject) => true
            case (t1: TObjectRef, t2: TObjectRef) =>
                val r1 = tex.store.lookup(t1)
                val r2 = tey.store.lookup(t2)

                val classesMatch = (r1, r2) match {
                    case (r1: TRealClassObject, r2: TRealClassObject) =>
                        r1.cl isSubtypeOf r2.cl
                    case (r1: TRealClassObject, r2: TRealObject) =>
                        false
                    case _ =>
                        true
                }

                classesMatch && leq(tex, tey, r1.globalType, r2.globalType) && ((r1.fields.keySet ++ r2.fields.keySet) forall (k =>
                    TypeLattice.leq(tex, tey, r1.lookupField(k), r1.lookupField(k))))

            case (t1: TArray, t2: TArray) =>
                leq(tex, tey, t1.globalType, t2.globalType) && ((t1.entries.keySet ++ t2.entries.keySet) forall (k =>
                    TypeLattice.leq(tex, tey, t1.lookup(k), t2.lookup(k))))

            case (t1: TUnion, t2: TUnion) =>
                t1.types forall { x => t2.types.exists { y => leq(tex, tey, x, y) } }
            case (t1, t2: TUnion) =>
                t2.types exists { x => leq(tex, tey, t1, x) }
            case (t1: TUnion, t2) =>
                t1.types forall { x => leq(tex, tey, x, t2) }
            case _ => false
        }

        val top = TTop
        val bottom = TBottom

        def join(x : Type, y : Type): Type = (x,y) match {
            case (TAny, _) => TAny
            case (_, TAny) => TAny
            case (TTrue, TFalse) => TBoolean
            case (TFalse, TTrue) => TBoolean
            case (TBottom, _) => y
            case (_, TBottom) => x

            case (t1, t2) if t1 == t2 => t1

            // Objects
            case (TAnyObject, t: TObjectRef) => TAnyObject
            case (t: TObjectRef, TAnyObject) => TAnyObject
            case (t1: TObjectRef, t2: TObjectRef) =>
                // We have a TUnion here since we are dealing
                // with objects of different refs
                TUnion(t1, t2)
            // Arrays
            case (TAnyArray, t: TArray) => TAnyArray
            case (t: TArray, TAnyArray) => TAnyArray
            case (t1: TArray, t2: TArray) =>
                var newEntries = Map[String, Type]();

                for (k <- t1.entries.keySet ++ t2.entries.keySet) {
                    newEntries = newEntries.update(k, t1.lookup(k) union t2.lookup(k))
                }

                new TArray(newEntries, t1.globalType union t2.globalType)

            // Unions
            case (t1, t2) => TUnion(t1, t2)
        }

        // unused
        def meet(x : Type, y : Type) = x
    }

    object BaseTypeEnvironment extends TypeEnvironment(HashMap[CFGSimpleVariable, Type](), None, new ObjectStore) {
        override def union(e: TypeEnvironment) = {
            e
        }

        override def equals(e: Any) = {
            if (e.isInstanceOf[AnyRef]) {
                BaseTypeEnvironment eq e.asInstanceOf[AnyRef]
            } else {
                false
            }
        }

        override def copy: TypeEnvironment =
            this

        override def toString = {
            "<base>"
        }

    }

    class TypeEnvironment(val map: Map[CFGSimpleVariable, Type], val scope: Option[ClassSymbol], val store: ObjectStore) extends Environment[TypeEnvironment] {
        def this(scope: Option[ClassSymbol]) = {
            this(new HashMap[CFGSimpleVariable, Type], scope, new ObjectStore);
        }

        def this() = {
            this(new HashMap[CFGSimpleVariable, Type], None, new ObjectStore);
        }

        def lookup(v: CFGSimpleVariable): Option[Type] = map.get(v)

        def inject(v: CFGSimpleVariable, typ: Type): TypeEnvironment =
            new TypeEnvironment(map + ((v, typ)), scope, store)

        def injectStore(st: ObjectStore): TypeEnvironment =
            new TypeEnvironment(map, scope, st)

        def copy: TypeEnvironment =
            new TypeEnvironment(Map[CFGSimpleVariable, Type]()++map, scope, store)

        def union(e: TypeEnvironment): TypeEnvironment = {
            e match {
                case BaseTypeEnvironment =>
                    this

                case te: TypeEnvironment =>
                    var newmap = new scala.collection.mutable.HashMap[CFGSimpleVariable, Type]();
                    for ((v,t) <- map) {
                        newmap(v) = t join TUninitialized
                    }
                    for ((v,t) <- e.map) {
                        if (newmap contains v) {
                            newmap(v) = map(v) join t
                        } else {
                            newmap(v) = t join TUninitialized
                        }
                    }
                    new TypeEnvironment(Map[CFGSimpleVariable, Type]()++newmap, scope, te.store union store)
            }
        }

        def checkMonotonicity(e: TypeEnvironment): Boolean = {
            map.forall { x =>
                if (e.map contains x._1) {
                    if (!TypeLattice.leq(this, e, x._2, e.map(x._1))) {
                        false
                    } else {
                        true
                    }
                } else {
                    false
                }
            }
        }

        def dumpDiff(e: TypeEnvironment): Unit = {
            if (scope != e.scope) {
                println("Scope diff!")
            } else if (store != e.store) {
                println("Store diff!")
            } else {
                for ((v, t) <- map) {
                    if (e.map contains v) {
                        if (t != e.map(v)) {
                            println(" "+v+" => ")
                            println("      OLD: "+t)
                            println("      NEW: "+e.map(v))
                        }
                    } else {
                        println(" "+v+" not in NEW:"+t)
                    }
                }
                for ((v, t) <- e.map) {
                    if (!(map contains v)) {
                        println(" "+v+" not in OLD: "+t)
                    }
                }
            }
        }

        override def equals(e: Any): Boolean = {
            e match {
                case BaseTypeEnvironment =>
                    false

                case env: TypeEnvironment =>

                    /*
                    if (scope != env.scope) {
                        println("Scope mismatch:")
                        println(" - "+scope)
                        println(" - "+env.scope)
                    }

                    if (map != env.map) {
                        println("Map mismatch:")
                        println(" - "+map)
                        println(" - "+env.map)
                    }


                    if (store != env.store) {
                        println("Store mismatch:")
                        println(" - "+store)
                        println(" - "+env.store)
                    }
                    */

                    scope == env.scope && map == env.map && store == env.store
                case _ =>
                    false

            }
        }

        override def toString = {
            def typeToString(t: Type): String = t match {
                case or: TObjectRef =>
                    "(#"+or.id.pos+","+or.id.offset+")"+store.lookup(or).toString
                case _ => t.toString
            }
            
            map.toList.filter(_._1.toString.toList.head != '_').sort{(x,y) => x._1.uniqueID < x._1.uniqueID}.map(x => x._1+" => "+typeToString(x._2)).mkString("[ ", "; ", " ]");
        }
    }

    case class TypeTransferFunction(silent: Boolean) extends TransferFunction[TypeEnvironment, CFGStatement] {
        //def notice(msg: String, pos: Positional) = if (!silent) { new Exception(msg).printStackTrace(); Reporter.notice(msg, pos) }
        //def error(msg: String, pos: Positional) = if (!silent) { new Exception(msg).printStackTrace(); Reporter.error(msg, pos) }
        def notice(msg: String, pos: Positional) = if (!silent) Reporter.notice(msg, pos)
        def error(msg: String, pos: Positional) = if (!silent) Reporter.error(msg, pos)

        def containsUninit(t: Type) = t match {
            case TTop =>
                true
            case TUninitialized =>
                true
            case tu: TUnion =>
                tu.types contains TUninitialized
            case _ =>
                false;
        }

        def uninitToNull(t: Type): Type = t match {
            case TUninitialized =>
                TNull
            case tu: TUnion =>
                new TUnion(tu.types.map { x => if (x == TUninitialized) TNull else x })
            case _ =>
                t
        }

        def apply(node : CFGStatement, env : TypeEnvironment) : TypeEnvironment = {
            var store = env.store

            def typeFromSimpleValue(sv: CFGSimpleValue): Type = sv match {
                case CFGLong(value) => TInt
                case CFGFloat(value) => TFloat
                case CFGString(value) => TString
                case CFGTrue() => TTrue
                case CFGFalse() => TFalse
                case CFGAny() => TAny
                case CFGNone() => TBottom
                case CFGNull() => TNull
                case CFGThis() => getObject(node, env.scope)
                case CFGEmptyArray() => new TArray()
                case CFGInstanceof(lhs, cl) => TBoolean
                case CFGArrayNext(ar) => typeFromSimpleValue(ar)
                case CFGArrayCurElement(id: CFGSimpleVariable) =>
                    env.lookup(id) match {
                        case Some(TAnyArray) =>
                            TAny
                        case Some(t: TArray) =>
                            val et = if (t.entries.size > 0) {
                                t.entries.values.reduceLeft(_ join _)
                            } else {
                                TBottom
                            }

                            uninitToNull(et union t.globalType)
                        case _ =>
                            TAny
                    }
                case CFGArrayCurElement(ar) => TAny
                case CFGArrayCurKey(ar) => TUnion(TString, TInt)
                case CFGArrayCurIsValid(ar) => expect(ar, TAnyArray); TBoolean
                case CFGNew(cr, params) => cr match {
                    case parser.Trees.StaticClassRef(_, _, id) =>
                        GlobalSymbols.lookupClass(id.value) match {
                            case a @ Some(cs) =>
                                getObject(node, a)
                            case _ =>
                                error("Undefined class '"+id.value+"'", id)
                                getObject(node, None)
                        }
                    case _ =>
                        getObject(node, None)
                }
                case cl @ CFGClone(obj) =>
                    expect(obj, TAnyObject) match {
                        case ref: TObjectRef =>
                            val ro = store.lookup(ref)
                            store = store.set(ObjectId(cl.uniqueID, 0), ro)
                            new TObjectRef(ObjectId(cl.uniqueID, 0))
                        case _ =>
                            TAnyObject
                    }
                case fcall @ CFGFunctionCall(id, args) =>
                    GlobalSymbols.lookupFunction(id.value) match {
                        case Some(fs) =>
                                checkFCalls(fcall.params, List(functionSymbolToFunctionType(fs)), fcall)
                        case None =>
                            // handle special functions
                            id.value.toLowerCase match {
                                case "isset" | "empty" =>
                                    TBoolean // no need to check the args, this is a no-error function
                                case _ =>
                                    notice("Function "+id.value+" appears to be undefined!", id)
                                    TBottom
                            }
                    }
                case mcall @ CFGMethodCall(r, mid, args) =>
                    expect(r, TAnyObject) match {
                        case or: TObjectRef =>
                            val ro = store.lookup(or);
                            ro.lookupMethod(mid.value, env.scope) match {
                                case Some(mt) =>
                                    checkFCalls(args, List(mt), mcall)
                                case None =>
                                    // Check for magic __call ?
                                    val cms = ro.lookupMethod("__call", env.scope)
                                    if (cms == None) {
                                        notice("Undefined method '" + mid.value + "' in object "+ro, mid)
                                        TBottom
                                    } else {
                                        cms.get.ret
                                    }
                            }
                        case _ =>
                            TBottom
                    }

                case const @ CFGConstant(id) =>
                    GlobalSymbols.lookupConstant(id.value) match {
                        case Some(cs) =>
                            cs.typ
                        case None =>
                            notice("Undefined constant '" + id.value + "'", const)
                            TString
                    }

                case const @ CFGClassConstant(cl, id) =>
                    TAny // TODO

                case const @ CFGClassProperty(cl, index) =>
                    TAny // TODO

                case mcall @ CFGStaticMethodCall(cl, id, args) =>
                    TAny // TODO

                case tern @ CFGTernary(iff, then, elze) =>
                    typeFromSimpleValue(then) union typeFromSimpleValue(elze)

                case CFGCast(typ, v) =>
                    // TODO: not all cast from-to types are accepted, we could
                    // check for those!
                    import parser.Trees._
                    typ match {
                        case CastUnset => TNull
                        case CastInt => TInt
                        case CastString => TString
                        case CastDouble => TFloat
                        case CastArray => TAnyArray
                        case CastBool => TBoolean
                        case CastObject => TAnyObject
                    }

                case id: CFGSimpleVariable =>
                  env.lookup(id) match {
                    case Some(t) =>
                        t
                    case None =>
                        notice("Potentially undefined variable "+stringRepr(id), id)
                        TBottom
                }

                case CFGArrayEntry(ar, ind) =>
                    expect(ind, TString, TInt)

                    expect(ar, TAnyArray) match {
                        case t: TArray =>
                            val typ = t.lookup(ind)

                            if (containsUninit(typ)) {
                                notice("Potentially undefined array element "+stringRepr(ind), ind)
                                uninitToNull(typ)
                            } else {
                                typ
                            }
                        case TBottom => TBottom
                        case t =>
                            println("Woops?? invlid type returned from expect: "+t);
                            TBottom
                    }

                case op @ CFGObjectProperty(obj, p) =>
                    expect(p, TString);
                    val typ = expect(obj, TAnyObject) match {
                        case TAnyObject =>
                            TAny
                        case or: TObjectRef =>
                            store.lookup(or).lookupField(p)

                        case TBottom => TBottom
                        case u: TUnion =>
                            TUnion(u.types.map { _ match {
                                case TAnyObject =>
                                    TAny
                                case or: TObjectRef =>
                                    store.lookup(or).lookupField(p)
                                case _ => TBottom
                            }})
                        case t =>
                            println("Woops?? invlid type returned from expect: "+t);
                            TAny
                    }

                    if (containsUninit(typ)) {
                        notice("Potentially undefined object property "+stringRepr(p), p)
                        uninitToNull(typ)
                    } else {
                        typ
                    }
                case CFGNextArrayEntry(arr) =>
                    typeFromSimpleValue(arr) match {
                        case t: TArray =>
                            t.globalType
                        case _ =>
                            println("woot! this is inconsistent!")
                            TAny
                    }

                case vv @ CFGVariableVar(v) =>
                    expect(v, TString);
                    notice("Dynamic variable ignored", vv)
                    TAny

                case u =>
                  println("Unknown simple value: "+u)
                  TAny
            }

            def getObject(node: CFGStatement, ocs: Option[ClassSymbol]): ObjectType = {
                val id = ObjectId(node.uniqueID, 0);
                store = store.initIfNotExist(id, ocs)
                new TObjectRef(id)
            }

            def expect(v1: CFGSimpleValue, typs: Type*): Type =
                expectWithEnv(env, v1, typs:_*)

            def expectWithEnv(env: TypeEnvironment, v1: CFGSimpleValue, typs: Type*): Type = {
                val vtyp = typeFromSimpleValue(v1);
                val etyp = typs reduceLeft (_ join _)

                if (TypeLattice.leq(env, vtyp, etyp)) {
                    vtyp
                } else {
                    if (!silent) {
                        notice("Potential type mismatch: expected: "+typs.toList.map{x => x.toText(env)}.mkString(" or ")+", found: "+vtyp.toText(env), v1)
                    }
                    typs.toList.head
                }
            }

            def typeCheckUnOP(op: CFGUnaryOperator, v1: CFGSimpleValue): Type = {
                op match {
                    case BOOLEANNOT =>
                        expect(v1, TAny); TBoolean
                    case BITSIWENOT =>
                        expect(v1, TInt)
                    case PREINC =>
                        expect(v1, TInt)
                    case POSTINC =>
                        expect(v1, TInt)
                    case PREDEC =>
                        expect(v1, TInt)
                    case POSTDEC =>
                        expect(v1, TInt)
                    case SILENCE =>
                        expect(v1, TAny)
                }
            }

            def typeCheckBinOP(v1: CFGSimpleValue, op: CFGBinaryOperator, v2: CFGSimpleValue): Type = {
                op match {
                    case PLUS =>
                        expect(v1, TInt); expect(v2, TInt)
                    case MINUS =>
                        expect(v1, TInt); expect(v2, TInt)
                    case MULT =>
                        expect(v1, TInt); expect(v2, TInt)
                    case DIV =>
                        expect(v1, TInt); expect(v2, TInt)
                    case CONCAT =>
                        expect(v1, TString, TInt, TAnyObject); expect(v2, TString, TInt, TAnyObject)
                    case MOD =>
                        expect(v1, TInt); expect(v2, TInt)
                    case INSTANCEOF =>
                        expect(v1, TAnyObject); expect(v2, TString); TBoolean
                    case BOOLEANAND =>
                        expect(v1, TAny); expect(v2, TAny); TBoolean
                    case BOOLEANOR =>
                        expect(v1, TAny); expect(v2, TAny); TBoolean
                    case BOOLEANXOR =>
                        expect(v1, TAny); expect(v2, TAny); TBoolean
                    case BITWISEAND =>
                        expect(v1, TInt); expect(v2, TInt)
                    case BITWISEOR =>
                        expect(v1, TInt); expect(v2, TInt)
                    case BITWISEXOR =>
                        expect(v1, TInt); expect(v2, TInt)
                    case SHIFTLEFT =>
                        expect(v1, TInt); expect(v2, TInt)
                    case SHIFTRIGHT =>
                        expect(v1, TInt); expect(v2, TInt)
                    case LT =>
                        expect(v1, TInt); expect(v2, TInt)
                    case LEQ =>
                        expect(v1, TInt); expect(v2, TInt)
                    case GEQ =>
                        expect(v1, TInt); expect(v2, TInt)
                    case GT =>
                        expect(v1, TInt); expect(v2, TInt)
                    case EQUALS =>
                        expect(v2, expect(v1, TAny)); TBoolean
                    case IDENTICAL =>
                        expect(v2, expect(v1, TAny)); TBoolean
                    case NOTEQUALS =>
                        expect(v2, expect(v1, TAny)); TBoolean
                    case NOTIDENTICAL =>
                        expect(v2, expect(v1, TAny)); TBoolean
                }
            }

            def complexAssign(v: CFGVariable, ext: Type): TypeEnvironment = {
                    var elems: List[(CFGSimpleValue, Type, Type)] = Nil;

                    def linearize(sv: CFGSimpleValue, checkType: Type, resultType: Type, pass: Int): Unit = {
                        // Recursive function that pushes each array parts and compute types
                        elems = (sv, checkType, resultType) :: elems;
                        sv match {
                            case CFGVariableVar(v) =>
                                linearize(v, TString, TString, pass+1)
                            case CFGArrayEntry(arr, index) =>
                                // We always will end up with a precise array
                                // The check type depends on the pass (i.e. deepness)
                                // pass == 0 means this is the most outer assign,
                                // which only needs to be checked against AnyArray
                                val rt = new TArray().inject(index, resultType);
                                val ct = if (pass > 0) new TArray().inject(index, checkType) else TAnyArray;

                                linearize(arr, ct, rt, pass+1)
                            case CFGObjectProperty(obj, index) =>
                                val rt = new TObjectRef(ObjectId(sv.uniqueID, 0));
                                store = store.initIfNotExist(rt.id, None)
                                store.lookup(rt).injectField(index, resultType);
                                // the check type is a different object, we create
                                // a tmp object with negative id, and shift it by
                                // the number of potentially used tmp objects used
                                // for params
                                val ct = if (pass > 0) {
                                    store = store.initIfNotExist(ObjectId(sv.uniqueID, 1), None);
                                    store.lookup(ObjectId(sv.uniqueID, 1)).injectField(index, checkType, false)
                                    new TObjectRef(ObjectId(sv.uniqueID, 1))
                                } else {
                                    TAnyObject;
                                }

                                linearize(obj, ct, rt, pass+1)
                            case CFGNextArrayEntry(arr) =>
                                // ditto ArrayEntry
                                val rt = new TArray().injectAny(resultType);
                                val ct = if (pass > 0) new TArray().injectAny(checkType) else TAnyArray;

                                linearize(arr, ct, rt, pass+1)
                            case _ =>
                        }
                    }

                    // We lineraize the recursive structure
                    linearize(v, ext, ext, 0)
                    var e = env.injectStore(store)

                    // Let's traverse all up to the last elem (the outermost assign)
                    for ((elem, ct, rt) <- elems.init) {
                        //println(" Checking for "+elem +"(actualType: "+typeFromSimpleValue(elem)+", checkType: "+ct+", resultType: "+rt+")");
                        def assignMergeObject(from: TObjectRef, to: TObjectRef): Type ={
                                val fromRO = store.lookup(from)
                                val toRO   = store.lookup(to)

                                //println("Trying to assign-merge "+fromRO+" and "+ toRO)

                                var newFields = HashMap[String, Type]() ++ fromRO.fields;

                                val pt = toRO.globalType join fromRO.globalType

                                for((index, typ) <- toRO.fields) {
                                    newFields = newFields.update(index, newFields.get(index) match {
                                        case Some(t) => pt join (t join typ) // weak assign here
                                        case None => pt join typ
                                    })
                                }

                                val o : TRealObject = toRO match {
                                    case o: TRealClassObject =>
                                        new TRealClassObject(o.cl, newFields, pt)
                                    case o: TRealObject =>
                                        new TRealObject(newFields, pt)
                                }

                                //println("Result: "+o)

                                e = e.injectStore(e.store.set(to.id, o))

                                to
                        }
                        // assignMerge will recursively merge types of recursive arrays
                        // we cannot use Lattice.Join as we do not want unions.
                        // i.e. $a['foo']['bar'] = 2; $a['foo']['bar'] =
                        // "str"; should not end with:
                        //    $a -> Array[foo => Array[bar => {TInt, TString}]]
                        // but
                        //    $a -> Array[foo => Array[bar => String]]
                        def assignMerge(from: Type, to: Type): Type = (from,to) match {
                            case (from: TArray, to: TArray) =>
                                import scala.collection.mutable.HashMap
                                import Math.max

                                val pt = to.globalType join from.globalType

                                val newEntries = Map[String, Type]() ++ from.entries;

                                for((index, typ)<- to.entries) {
                                    newEntries(index) = newEntries.get(index) match {
                                        case Some(t) => pt join assignMerge(t, typ)
                                        case None => pt join typ
                                    }
                                }

                                new TArray(newEntries, pt)

                            case (from: TObjectRef, to: TObjectRef) =>
                                assignMergeObject(from, to)
                            case (from: TObjectRef, to: TUnion) =>
                                // We may have a union of objects here
                                TUnion(to.types map { _ match {
                                    case t: TObjectRef =>
                                        assignMergeObject(from, t)
                                    case o => o
                                }})
                            case (a, b) =>
                                // In case not both types are not arrays nor objects, we
                                // always end up with the resulting type
                                a
                        }

                        val resultingType = assignMerge(rt, typeFromSimpleValue(elem))

                        elem match {
                            case sv: CFGSimpleVariable =>
                                // Due to our deep type system, checking
                                // the base variable should be enough
                                expectWithEnv(e, elem, ct)
                                e = e.inject(sv, resultingType);
                            case _ =>
                        }
                    }

                  e
            }

            def functionSymbolToFunctionType(fs: FunctionSymbol): FunctionType = {
                new TFunction(fs.argList.map { a => (a._2.typ, a._2.optional) }, fs.typ)
            }

            def checkFCalls(fcall_params: List[CFGSimpleValue], syms: List[FunctionType], pos: Positional) : Type =  {
                def protoFilter(sym: FunctionType): Boolean = {
                    sym match {
                        case tf: TFunction =>
                            var ret = true;
                            for (i <- fcall_params.indices) {
                                if (i >= tf.args.length) {
                                    ret = false
                                } else {
                                    if (!TypeLattice.leq(env, typeFromSimpleValue(fcall_params(i)), tf.args(i)._1)) {
                                        //notice("Prototype mismatch because "+fcall.params(i)+"("+typeFromSimpleValue(fcall.params(i))+") </: "+args(i)._1) 

                                        ret = false;
                                    }
                                }
                            }
                            ret
                        case TFunctionAny =>
                            true
                    }
                }

                syms filter protoFilter match {
                    case Nil =>
                        if (syms.size > 1) {
                            error("Unmatched function prototype '("+fcall_params.map(typeFromSimpleValue).mkString(", ")+")', candidates are:\n    "+syms.mkString(",\n    "), pos)
                            TBottom
                        } else {
                            syms.first match {
                                case tf: TFunction =>
                                    for (i <- fcall_params.indices) {
                                        if (i >= tf.args.length) {
                                            error("Prototype error!", pos)
                                        } else {
                                            expect(fcall_params(i), tf.args(i)._1)
                                        }

                                    }
                                    tf.ret
                                case s =>
                                    s.ret
                            }
                        }

                    case f :: xs =>
                        f.ret
                }
            }


            val newEnv = node match {
                case CFGAssign(vr: CFGSimpleVariable, v1) =>
                    env.inject(vr, typeFromSimpleValue(v1))

                case CFGAssignUnary(vr: CFGSimpleVariable, op, v1) =>
                    // We want to typecheck v1 according to OP
                    env.inject(vr, typeCheckUnOP(op, v1));

                case CFGAssignUnary(ca: CFGVariable, op, v1) =>
                    // We want to typecheck v1 according to OP
                    complexAssign(ca, typeCheckUnOP(op, v1))

                case CFGAssignBinary(vr: CFGSimpleVariable, v1, op, v2) =>
                    // We want to typecheck v1/v2 according to OP
                    env.inject(vr, typeCheckBinOP(v1, op, v2));

                case CFGAssignBinary(ca: CFGVariable, v1, op, v2) =>
                    // We want to typecheck v1/v2 according to OP
                    complexAssign(ca, typeCheckBinOP(v1, op, v2))

                case CFGAssign(ca: CFGVariable, ex) =>
                    complexAssign(ca, typeFromSimpleValue(ex))

                case CFGAssume(v1, op, v2) => op match {
                    case LT | LEQ | GEQ | GT =>
                        expect(v1, TInt, TFloat); expect(v2, TInt, TFloat); env
                    case EQUALS | IDENTICAL | NOTEQUALS | NOTIDENTICAL =>
                        /**
                         * Type filtering:
                         * if v1 is a variable that is compared against True/False
                         * we can filter incompatible values out
                         */
                        def filter(v: CFGSimpleVariable, value: Boolean): Type = {
                            typeFromSimpleValue(v) match {
                                case u: TUnion =>
                                    if (value) {
                                        TUnion(u.types.filter(t => t != TFalse && t != TNull))
                                    } else {
                                        TUnion(u.types.filter(t => t != TTrue  && t != TResource))
                                    }
                                case t =>
                                    if (value) {
                                        if (t != TFalse && t != TNull) {
                                            t
                                        } else {
                                            // we had a single incompatible type
                                            // The branch will never be taken!
                                            notice("Redundant or incompatible check", v);
                                            TBottom
                                        }
                                    } else {
                                        if (t != TTrue && t != TResource) {
                                            t
                                        } else {
                                            // we had a single incompatible type
                                            // The branch will never be taken!
                                            notice("Redundant or incompatible check", v);
                                            TBottom
                                        }
                                    }
                            }
                        }
                        var t1 = v1;
                        var t2 = v2;
                        (v1, typeFromSimpleValue(v1), v2, typeFromSimpleValue(v2)) match {
                            case (v: CFGSimpleVariable, TTrue , w: CFGSimpleVariable, _) =>
                                // we benefit from a switch:
                                t1 = v2;
                                t2 = v1;
                            case (v: CFGSimpleVariable, TFalse , w: CFGSimpleVariable, _) =>
                                // we benefit from a switch:
                                t1 = v2;
                                t2 = v1;
                            case (v: CFGSimpleVariable, _, _, _) =>
                                // no change
                            case (_, _, v: CFGSimpleVariable, _) =>
                                t1 = v2;
                                t2 = v1;
                            case _ =>
                                // no change, will be ignored anyway
                        }
                        (t1, op, typeFromSimpleValue(t2)) match  {
                            case (v: CFGSimpleVariable, EQUALS, TFalse)  =>
                                env.inject(v, filter(v, false))
                            case (v: CFGSimpleVariable, NOTEQUALS, TFalse)  =>
                                env.inject(v, filter(v, true))
                            case (v: CFGSimpleVariable, EQUALS, TTrue)  =>
                                env.inject(v, filter(v, true))
                            case (v: CFGSimpleVariable, NOTEQUALS, TTrue)  =>
                                env.inject(v, filter(v, false))
                            case _ =>
                                expect(v1, TAny)
                                expect(v2, TAny)
                                env
                        }

                  }

                case CFGPrint(v) =>
                    expect(v, TInt, TString, TAnyObject, TBoolean);
                    env
                case CFGUnset(id) =>
                    id match {
                        case v: CFGSimpleVariable =>
                            env.inject(v, TUninitialized)
                        case _ =>
                            env // TODO

                    }

                case CFGSkip =>
                    env

                case ex: CFGSimpleValue =>
                    expect(ex, TAny);
                    env
                case _ => notice(node+" not yet handled", node); env
            }

            newEnv.injectStore(store)
        }
    }

    case class Analyzer(cfg: CFG, scope: Scope) {

        def setupEnvironment: TypeEnvironment = {
            var baseEnv   = new TypeEnvironment;

            // We now inject predefined variables
            def injectPredef(name: String, typ: Type): Unit = {
                scope.lookupVariable(name) match {
                    case Some(vs) =>
                        baseEnv = baseEnv.inject(CFGIdentifier(vs), typ)
                    case None =>
                        // ignore this var
                        println("Woops, no such symbol found: "+name)
                }
            }

            scope.registerPredefVariables
            injectPredef("_GET",     new TArray(TBottom))
            injectPredef("_POST",    new TArray(TBottom))
            injectPredef("_REQUEST", new TArray(TBottom))
            injectPredef("_COOKIE",  new TArray(TBottom))
            injectPredef("_SERVER",  new TArray(TBottom))
            injectPredef("_ENV",     new TArray(TBottom))
            injectPredef("_SESSION", new TArray(TBottom))

            // for methods, we inject $this as its always defined
            scope match {
                case ms: MethodSymbol =>
                    baseEnv = baseEnv.injectStore(baseEnv.store.initIfNotExist(ObjectId(-1, 0), Some(ms.cs)))
                    injectPredef("this", new TObjectRef(ObjectId(-1, 0)))
                case _ =>
            }

            // in case we have a function or method symbol, we also inject arguments
            scope match {
                case fs: FunctionSymbol =>
                    for ((name, sym) <- fs.argList) {
                        baseEnv = baseEnv.inject(CFGIdentifier(sym), sym.typ)
                    }
                case _ =>

            }

            baseEnv
        }

        def analyze = {
            val bottomEnv = BaseTypeEnvironment;
            val baseEnv   = setupEnvironment;

            val aa = new AnalysisAlgorithm[TypeEnvironment, CFGStatement](TypeTransferFunction(true), bottomEnv, baseEnv, cfg)

            aa.init
            aa.computeFixpoint

            if (Main.displayFixPoint) {
                println("     - Fixpoint:");
                for ((v,e) <- aa.getResult.toList.sort{(x,y) => x._1.name < y._1.name}) {
                    println("      * ["+v+"] => "+e);
                }
            }

            aa.pass(TypeTransferFunction(false))
        }
    }
}

package phantm.controlflow

import CFGTrees._
import scala.collection.immutable.HashMap
import scala.collection.immutable.HashSet
import scala.collection.immutable.Map
import phantm._
import phantm.parser.Trees.Identifier
import phantm.analyzer.Symbols._
import phantm.analyzer.Types._

object TypeFlow {
    case object TypeLattice {
        type Env = TypeEnvironment
        type E = Type

        def leq(envx: Env, envy: Env, x : Type, y : Type): Boolean = {
            def leqT(x: Type, y: Type): Boolean = (x,y) match {
                case (x, y) if x == y => true
                case (TBottom, _) => true
                case (_, TTop) => true
                case (_:ConcreteType, TAny) => true
                case (_:TStringLit, TString) => true
                case (_:TIntLit, TInt) => true
                case (_:TFloatLit, TFloat) => true
                case (_:TNumericLit, TNumeric) => true
                case (TInt, TNumeric) => true
                case (TFloat, TNumeric) => true
                case (TTrue, TBoolean) => true
                case (TFalse, TBoolean) => true
                case (t1: TObjectRef, TAnyObject) => true
                case (t1: TObjectRef, t2: TObjectRef) =>
                    if (t1.id == t2.id) {
                        true
                    } else {
                        val r1 = t1.realObject(envx)
                        val r2 = t2.realObject(envy)

                        val classesMatch = (r1, r2) match {
                            case (r1: TRealClassObject, r2: TRealClassObject) =>
                                r1.cl isSubtypeOf r2.cl
                            case (r1: TRealObject, r2: TRealClassObject) =>
                                false
                            case _ =>
                                true
                        }

                        classesMatch && leqT(r1.globalType, r2.globalType) && ((r1.fields.keySet ++ r2.fields.keySet) forall (k =>
                            leqT(r1.lookupField(k), r1.lookupField(k))))
                    }

                case (t1: TArray, t2: TArray) =>
                    leqT(t1.globalType, t2.globalType) && ((t1.entries.keySet ++ t2.entries.keySet) forall (k =>
                        leqT(t1.lookup(k), t2.lookup(k))))

                case (t1: TUnion, t2: TUnion) =>
                    t1.types forall { x => t2.types.exists { y => leqT(x, y) } }
                case (t1, t2: TUnion) =>
                    t2.types exists { x => leqT(t1, x) }
                case (t1: TUnion, t2) =>
                    t1.types forall { x => leqT(x, t2) }
                case _ => false
            }

            leqT(x,y)
        }

        val top = TTop
        val bottom = TBottom

        def join(x: Type, y: Type): Type = (x,y) match {
            case (TTop, _) => TTop
            case (_, TTop) => TTop

            case (TBottom, _) => y
            case (_, TBottom) => x

            case (TAny, TUninitialized) => TTop
            case (TUninitialized, TAny) => TTop

            case (TAny, _: ConcreteType) => TAny
            case (_: ConcreteType, TAny) => TAny

            case (TAny, tu: TUnion) =>
                if (!(tu.types contains TUninitialized)) {
                    TAny
                } else {
                    TTop
                }
            case (tu: TUnion, TAny) =>
                if (!(tu.types contains TUninitialized)) {
                    TAny
                } else {
                    TTop
                }

            case (TNumeric, _:TNumericLit) => TNumeric
            case (_:TNumericLit, TNumeric) => TNumeric

            case (_:TFloatLit, TFloat)  => TFloat
            case (TFloat, _:TFloatLit)  => TFloat
            case (_:TIntLit, TInt)      => TInt
            case (TInt, _:TIntLit)      => TInt

            case (TFloat,   TInt)       => TNumeric
            case (TInt,     TFloat)     => TNumeric
            case (TNumeric, TInt)       => TNumeric
            case (TNumeric, TFloat)     => TNumeric
            case (TFloat,   TNumeric)   => TNumeric
            case (TInt,     TNumeric)   => TNumeric

            case (TTrue,    TFalse)     => TBoolean
            case (TFalse,   TTrue)      => TBoolean
            case (TTrue,    TBoolean)   => TBoolean
            case (TBoolean, TTrue)      => TBoolean
            case (TFalse,   TBoolean)   => TBoolean
            case (TBoolean, TFalse)     => TBoolean

            case (t1, t2) if t1 == t2 => t1

            // Objects
            case (TAnyObject, t: TObjectRef) => TAnyObject
            case (t: TObjectRef, TAnyObject) => TAnyObject
            case (t1: TObjectRef, t2: TObjectRef) =>
                if (t1.id != t2.id) {
                    // Different ids -> union
                    TUnion(t1, t2)
                } else {
                    t1
                }

            // Arrays
            case (TAnyArray, t: TArray) => TAnyArray
            case (t: TArray, TAnyArray) => TAnyArray
            case (t1: TArray, t2: TArray) =>
                var newEntries = Map[String, Type]();

                for (k <- t1.entries.keySet ++ t2.entries.keySet) {
                    newEntries = newEntries.updated(k, t1.lookup(k) union t2.lookup(k))
                }

                new TArray(newEntries, t1.globalType union t2.globalType)
            // Unions
            case (t1, t2) => TUnion(t1, t2)
        }

        // For meet we actually require the environment, since object types
        // will be updated in the store
        def meet(envx: Env, envy: Env, x : Type, y : Type): (TypeEnvironment, Type) = {
            var env = envx
            def meetTypes(x: Type, y: Type): Type = (x,y) match {
                case (TTop, _) => y
                case (_, TTop) => x

                case (TBottom, _) => TBottom
                case (_, TBottom) => TBottom

                case (TAny, _: ConcreteType) => y
                case (_: ConcreteType, TAny) => x

                case (TAny, tu: TUnion) =>
                    if (tu.types contains TUninitialized) {
                        tu.types.filter(_ != TUninitialized).foldLeft(TBottom: Type)(_ union _)
                    } else {
                        tu
                    }
                case (tu: TUnion, TAny) =>
                    if (tu.types contains TUninitialized) {
                        tu.types.filter(_ != TUninitialized).foldLeft(TBottom: Type)(_ union _)
                    } else {
                        tu
                    }

                case (t1, t2) if t1 == t2 => t1

                // Arrays
                case (t1: TArray, t2: TArray) =>
                    var newEntries = Map[String, Type]();

                    for (k <- t1.entries.keySet ++ t2.entries.keySet) {
                        newEntries = newEntries.updated(k, meetTypes(t1.lookup(k), t2.lookup(k)))
                    }

                    new TArray(newEntries, meetTypes(t1.globalType, t2.globalType))


                // Unions
                case (tu1: TUnion, tu2: TUnion) =>
                    var resUnion = Set[Type]();

                    // we take the intersection
                    for (t1 <- tu1.types; t2 <- tu2.types) {
                       resUnion = resUnion + meetTypes(t1, t2);
                    }

                    resUnion.foldLeft(TBottom: Type)(_ union _)

                case (tu1: TUnion, t2) =>
                    var resUnion = Set[Type]();

                    // we take the intersection
                    for (t1 <- tu1.types) {
                       resUnion = resUnion + meetTypes(t1, t2);
                    }

                    resUnion.foldLeft(TBottom: Type)(_ union _)

                case (t1, tu2: TUnion) =>
                    meetTypes(tu2, t1)

                // Arbitrary types
                case (t1, t2) =>
                    if (leq(envx, envy, t1, t2)) {
                        t1
                    } else if (leq(envx, envy, t2, t1)) {
                        t2
                    } else {
                        TBottom
                    }
            }

            (env, meetTypes(x,y))
        }
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

    object AnnotationsStore {
        var functions = HashMap[String, (List[TFunction], Type)]();

        def collectFunctionRet(fs: FunctionSymbol, t: Type) = {
            val newData = functions.get(fs.name) match {
                case Some(data) =>
                    (data._1, t)
                case None =>
                    (Nil, t)
            }

            functions += (fs.name -> newData)

        }
        def collectFunction(fs: FunctionSymbol, ft: TFunction) = {
            val newData = functions.get(fs.name) match {
                case Some(data) =>
                    (ft :: data._1, data._2)
                case None =>
                    (ft :: Nil, TAny)
            }

            functions += (fs.name -> newData)
        }
    }

    class TypeEnvironment(val map: Map[CFGSimpleVariable, Type], val scope: Option[ClassSymbol], val store: ObjectStore) extends Environment[TypeEnvironment, CFGStatement] {


        def this(scope: Option[ClassSymbol]) = {
            this(new HashMap[CFGSimpleVariable, Type], scope, new ObjectStore);
        }

        def this() = {
            this(new HashMap[CFGSimpleVariable, Type], None, new ObjectStore);
        }

        def lookup(v: CFGSimpleVariable): Option[Type] = map.get(v)

        def inject(v: CFGSimpleVariable, typ: Type): TypeEnvironment =
            new TypeEnvironment(map + ((v, typ)), scope, store)

        def setStore(st: ObjectStore): TypeEnvironment = {
            new TypeEnvironment(map, scope, st)
        }

        def setObject(id: ObjectId, ot: TRealObject): TypeEnvironment = {
            new TypeEnvironment(map, scope, store.set(id, ot))
        }

        def initObjectIfNotExist(id: ObjectId, cl: Option[ClassSymbol]) = {
            new TypeEnvironment(map, scope, store.initIfNotExist(id, cl))
        }

        def copy: TypeEnvironment =
            new TypeEnvironment(Map[CFGSimpleVariable, Type]()++map, scope, store)

        def union(e: TypeEnvironment): TypeEnvironment = {
            e match {
                case BaseTypeEnvironment =>
                    this

                case te: TypeEnvironment =>
                    var newmap = Map[CFGSimpleVariable, Type]();

                    for (k <- map.keySet ++ e.map.keySet) {
                        newmap = newmap.updated(k,
                            map.getOrElse(k, TTop) union e.map.getOrElse(k, TTop))
                    }

                    new TypeEnvironment(newmap, scope, te.store union store)
            }
        }

        def checkMonotonicity(vrtx: Vertex, e: TypeEnvironment, inEdges: Iterable[(CFGStatement, TypeEnvironment)]): Unit = {
            var delim = false;
            for ((v, t) <- map) {
                if (e.map contains v) {
                    if (!TypeLattice.leq(this, e, t, e.map(v))) {
                        if (!delim) {
                            println("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@")
                            println("@@@@@@@@@@@@@@@@@ "+vrtx+" @@@@@@@@@@@@@@@@@@@")
                            delim = true;
                        }
                        println(" "+v+" => ")
                        println("      OLD: "+t)
                        println("      NEW: "+e.map(v))
                        println(" incoming values: ")
                        for ((cfg, e) <- inEdges) {
                            println("   * "+cfg+" => "+e.lookup(v)+" ===> "+TypeTransferFunction(true, false)(cfg, e).lookup(v))
                            println
                        }
                        println("@@@@@@@@@@@@@@@@@")
                    }
                } else {
                    if (!delim) {
                        println("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@")
                        println("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@")
                        delim = true;
                    }
                    println(" "+v+" is not in NEW ?")
                }
            }
        }

        override def equals(e: Any): Boolean = {
            e match {
                case BaseTypeEnvironment =>
                    false

                case env: TypeEnvironment =>
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
            
            map.toList.filter( tmp => tmp._1.toString.toList.head != '_' && tmp._1.toString != "GLOBALS").sortWith{(x,y) => x._1.uniqueID < x._1.uniqueID}.map(x => x._1+" => "+typeToString(x._2)).mkString("[ ", "; ", " ]");
        }
    }

    case class TypeTransferFunction(silent: Boolean, collectAnnotations: Boolean) extends TransferFunction[TypeEnvironment, CFGStatement] {
        //def notice(msg: String, pos: Positional) = if (!silent) { new Exception(msg).printStackTrace(); Reporter.notice(msg, pos) }
        //def error(msg: String, pos: Positional) = if (!silent) { new Exception(msg).printStackTrace(); Reporter.error(msg, pos) }
        def notice(msg: String, pos: Positional) = if (!silent) Reporter.notice(msg, pos)
        def error(msg: String, pos: Positional) = if (!silent) Reporter.error(msg, pos)

        def possiblyUninit(t: Type): Boolean = t match {
            case TTop =>
                true
            case TUninitialized =>
                false
            case tu: TUnion =>
                tu.types contains TUninitialized
            case _ =>
                false;
        }

        def removeUninit(removeInArrays: Boolean)(t: Type): Type = t match {
            case TTop =>
                TAny
            case TUninitialized =>
                TBottom
            case tu: TUnion =>
                tu.types.map { removeUninit(removeInArrays) } reduceLeft (_ union _)
            case ta: TArray =>
                if (removeInArrays) {
                    new TArray(Map[String, Type]() ++ ta.entries.map{ e => (e._1, removeUninit(removeInArrays)(e._2)) }, removeUninit(removeInArrays)(ta.globalType))
                } else {
                    t
                }
            case _ =>
                t
        }

        def uninitToNull(t: Type): Type = t match {
            case TTop =>
                TAny
            case TUninitialized =>
                TNull
            case tu: TUnion =>
                tu.types.map { uninitToNull } reduceLeft (_ union _)
            case _ =>
                t
        }

        def apply(node : CFGStatement, envInit : TypeEnvironment) : TypeEnvironment = {
            var env = envInit

            def leq(t1: Type, t2: Type) = TypeLattice.leq(env, env, t1, t2)
            def meet(t1: Type, t2: Type) = {
                val (nenv, t) = TypeLattice.meet(env, env, t1, t2)
                env = nenv;
                t
            }

            def typeFromSV(sv: CFGSimpleValue): Type = sv match {
                case CFGLong(value)         => TIntLit(value)
                case CFGFloat(value)        => TFloatLit(value)
                case CFGString(value)       => TStringLit(value)
                case CFGTrue()              => TTrue
                case CFGFalse()             => TFalse
                case CFGAny()               => TAny
                case CFGNone()              => TBottom
                case CFGNull()              => TNull
                case CFGThis()              => getObject(node, env.scope)
                case CFGEmptyArray()        => new TArray()
                case CFGInstanceof(lhs, cl) => TBoolean
                case CFGArrayNext(ar)       => typeFromSV(ar)
                case CFGArrayCurElement(id: CFGSimpleVariable) =>
                    env.lookup(id) match {
                        case Some(TAnyArray) =>
                            TAny
                        case Some(t: TArray) =>
                            val et = if (t.entries.size > 0) {
                                t.entries.values.reduceLeft(_ union _)
                            } else {
                                TBottom
                            }

                             removeUninit(false)(et union t.globalType)
                        case _ =>
                            TAny
                    }
                case CFGArrayCurElement(ar) => TAny
                case CFGArrayCurKey(ar)     => TString union TInt
                case CFGArrayCurIsValid(ar) =>
                    TBoolean
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
                    typeFromSV(obj) match {
                        case ref: TObjectRef =>
                            val ro = env.store.lookup(ref)
                            env = env.setStore(env.store.set(ObjectId(cl.uniqueID, 0), ro))
                            new TObjectRef(ObjectId(cl.uniqueID, 0))
                        case _ =>
                            TAnyObject
                    }
                case CFGFunctionCall(Identifier("phantm_dumpanddie"), args) =>
                    if (Main.dumpedData != Nil) {
                        for (unser <- Main.dumpedData) {
                            env = unser.importToEnv(env)
                        }
                    }
                    TBottom

                case fcall @ CFGFunctionCall(id, args) =>
                    GlobalSymbols.lookupFunction(id.value) match {
                        case Some(fs) =>
                                if (collectAnnotations) {
                                    val ft = new TFunction(args.map(a => (typeFromSV(a), false)), TBottom)
                                    AnnotationsStore.collectFunction(fs, ft);
                                }
                                checkFCalls(fcall.params, fs.ftyps.toList, fcall)
                        case None =>
                            // handle special functions
                            id.value.toLowerCase match {
                                case "eval" =>
                                    notice("eval() statements are ignored.", id)
                                    TAny
                                case "isset" | "empty" =>
                                    TBoolean // no need to check the args, this is a no-error function
                                case _ =>
                                    notice("Function "+id.value+" appears to be undefined!", id)
                                    TBottom
                            }
                    }
                case mcall @ CFGMethodCall(r, mid, args) =>
                    typeFromSV(r) match {
                        case or: TObjectRef =>
                            val ro = env.store.lookup(or);
                            ro.lookupMethod(mid.value, env.scope) match {
                                case Some(mt) =>
                                    if (collectAnnotations) {
                                        // TODO: Create a FunctionType and add it to the list of potential prototypes
                                    }
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
                            TTop
                    }

                case CFGConstant(cs) =>
                    cs.typ

                case const @ CFGClassConstant(cs) =>
                    cs.typ

                case mcall @ CFGStaticMethodCall(cl, id, args) =>
                    TAny // TODO

                case tern @ CFGTernary(iff, then, elze) =>
                    typeFromSV(then) union typeFromSV(elze)

                case CFGCast(typ, v) =>
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
                  env.lookup(id).getOrElse(TTop)

                case ae @ CFGArrayEntry(ar, ind) =>
                    val indtyp = typeFromSV(ind)

                    typeFromSV(ar) match {
                        case t: TArray =>
                            t.lookupByType(indtyp)
                        case u: TUnion =>
                            u.types.map { _ match {
                                case ta: TArray =>
                                    ta.lookupByType(indtyp)
                                case _ =>
                                    TBottom
                            }}.reduceLeft(_ union _)
                        case TAny | TTop =>
                            TTop
                        case _ =>
                            TBottom
                    }
                case ae @ CFGNextArrayEntry(arr) =>
                    typeFromSV(arr) match {
                        case ta: TArray =>
                            ta.entries.foldLeft(TBottom: Type)((t, e)=> t union e._2) union ta.globalType
                        case u: TUnion =>
                            u.types.map { _ match {
                                case ta: TArray =>
                                    ta.entries.foldLeft(TBottom: Type)((t, e)=> t union e._2) union ta.globalType
                                case _ =>
                                    TBottom
                            }}.reduceLeft(_ union _)
                        case TAny | TTop =>
                            TTop
                        case _ =>
                            TBottom
                   }

                case op @ CFGObjectProperty(obj, p) =>
                    typeFromSV(obj) match {
                        case TAnyObject | TAny | TTop =>
                            TTop
                        case or: TObjectRef =>
                            env.store.lookup(or).lookupField(p)
                        case u: TUnion =>
                            u.types.map { _ match {
                                case TAnyObject | TAny | TTop =>
                                    TTop
                                case or: TObjectRef =>
                                    env.store.lookup(or).lookupField(p)
                                case _ =>
                                    TBottom
                            }}.reduceLeft(_ union _)
                        case _ =>
                            TBottom
                    }

                case vv @ CFGVariableClassConstant(cr, id) =>
                    notice("Dynamically referenced class constants ignored", vv)
                    TBottom

                case vv @ CFGVariableClassProperty(cr, prop) =>
                    notice("Dynamically referenced class properties ignored", vv)
                    TBottom

                case vv @ CFGVariableVar(v) =>
                    notice("Dynamic variable ignored", vv)
                    TBottom

                case u =>
                  println("Unknown simple value: "+u)
                  TBottom
            }

            def getObject(node: CFGStatement, ocs: Option[ClassSymbol]): ObjectType = {
                val id = ObjectId(node.uniqueID, 0);
                env = env.setStore(env.store.initIfNotExist(id, ocs))
                new TObjectRef(id)
            }

            def typeError(pos: Positional, etyp: Type, vtyp: Type): Unit = {
                if (!silent) {
                    def filterErrors(t: Type): Boolean = {
                        if (Main.verbosity <= 0 && possiblyUninit(t)) {
                            true
                        } else if (Main.verbosity < 0 && t == TAny) {
                            true
                        } else {
                            false
                        }
                    }
                    def simpleText(t: Type): String = t match {
                        case ta: TArray =>
                            "Array[...]"
                        case to: TObjectRef =>
                            "Object[...]"
                        case tu: TUnion => 
                            tu.types.map(simpleText).mkString(" or ")
                        case t =>
                            t.toText(env)
                    }

                    def typesDiff(et: Type, vt: Type): ((String, String), Boolean) = (et,vt) match {
                        case (eta: TArray, vta: TArray) =>
                            var relevantKeys = Set[String]();
                            var cancel = false

                            // Emphasis on the differences
                            for (k <- eta.entries.keySet ++ vta.entries.keySet) {
                                if (!leq(vta.lookup(k), eta.lookup(k))) {
                                    relevantKeys = relevantKeys + k
                                }
                            }

                            var rhs, lhs = List[String]()

                            for (k <- relevantKeys) {
                                val diff = typesDiff(eta.lookup(k), vta.lookup(k))
                                lhs = k+" => "+diff._1._1 :: lhs
                                rhs = k+" => "+diff._1._2 :: rhs
                                cancel = cancel || diff._2
                            }

                            if (!leq(vta.globalType, eta.globalType)) {
                                val diff = typesDiff(vta.globalType, eta.globalType)
                                lhs = "? => "+diff._1._1 :: lhs
                                rhs = "? => "+diff._1._2 :: rhs
                                cancel = cancel || diff._2
                            }

                            if (lhs.size < eta.entries.size+1) {
                                lhs = "..." :: lhs;
                            }

                            if (rhs.size < vta.entries.size+1) {
                                rhs = "..." :: rhs;
                            }

                            if (relevantKeys.forall(k => filterErrors(vta.lookup(k)))) {
                                cancel = true
                            }

                            ((lhs.reverse.mkString("Array[", ", ", "]"), rhs.reverse.mkString("Array[", ", ", "]")), cancel)
                        case (et, vt: TArray) =>
                            ((et.toText(env), simpleText(vt)), false)

                        case (et, vto: TObjectRef) =>
                            ((et.toText(env), simpleText(vto)), false)

                        case (eta: TArray, vt) =>
                            ((simpleText(eta), simpleText(vt)), filterErrors(vt))
                        case (eto: TObjectRef, vt) =>
                            ((simpleText(eto), simpleText(vt)), filterErrors(vt))
                        case (etu: TUnion, vtu: TUnion) =>
                            var relevantTypes = List[String]();

                            for (t <- vtu.types) {
                                if (!leq(t, etu)) {
                                    relevantTypes = simpleText(t) :: relevantTypes;
                                }
                            }

                            if (relevantTypes.size < vtu.types.size) {
                                relevantTypes = "..." :: relevantTypes
                            }

                            ((simpleText(etu), relevantTypes.reverse.mkString(" or ")), false)
                        case _ =>
                            ((et.toText(env), vt.toText(env)), filterErrors(vt))
                    }

                    (etyp, vtyp) match {
                        case (et, vt) if filterErrors(vt) =>
                            if (Main.verbosity > 0) {
                                pos match {
                                    case sv: CFGSimpleVariable =>
                                        notice("Potentialy uninitialized variable", pos)
                                    case _ =>
                                        notice("Potentialy uninitialized value", pos)
                                }
                            }
                        case (et, TUninitialized) =>
                                pos match {
                                    case sv: CFGSimpleVariable =>
                                        notice("Uninitialized variable", pos)
                                    case _ =>
                                        notice("Uninitialized value", pos)
                                }
                        case (et, vt) =>
                            val (diff, cancelError) = typesDiff(et, vt)

                            if (!cancelError) {
                                notice("Potential type mismatch: expected: "+diff._1+", found: "+diff._2, pos)
                            }
                    }
                }
            }

            def expOrRef(v1: CFGSimpleValue, typs: Type*): Type = {
                val etyp = typs reduceLeft (_ union _)
                val vtyp = typeFromSV(v1)

                def checkVariable(v: CFGVariable, kind: String): Type = {
                    val (osv, svetyp) = getCheckType(v, etyp)

                    if (!osv.isEmpty) {
                        val sv = osv.get
                        val svvtyp = typeFromSV(sv)

                        var svtypCheck  = svvtyp

                        if (leq(svvtyp, svetyp)) {
                            vtyp
                        } else {
                            typeError(sv, svetyp, svvtyp)

                            val t = meet(svetyp, svvtyp)
                            //println("== Refining "+svvtyp+" to "+t+" ("+svetyp+")")
                            env = env.inject(sv, t)
                            // we then return the type
                            meet(etyp, vtyp)
                        }
                    } else {
                        TTop
                    }
                }

                v1 match {
                    case sv: CFGSimpleVariable =>
                        checkVariable(sv, "variable")
                    case v: CFGNextArrayEntry =>
                        checkVariable(v, "array entry")
                    case v: CFGArrayEntry =>
                        checkVariable(v,"array entry")
                    case v: CFGObjectProperty =>
                        checkVariable(v, "object property")
                    case v =>
                        if (leq(vtyp, etyp)) {
                            vtyp
                        } else {
                            typeError(v, etyp, vtyp)
                        }
                        meet(etyp, vtyp)

                }
            }

            def typeFromUnOP(op: CFGUnaryOperator, v1: CFGSimpleValue): Type = op match {
                case BOOLEANNOT =>
                    expOrRef(v1, TAny)
                case BITSIWENOT =>
                    expOrRef(v1, TInt)
                case PREINC =>
                    expOrRef(v1, TInt)
                case POSTINC =>
                    expOrRef(v1, TInt)
                case PREDEC =>
                    expOrRef(v1, TInt)
                case POSTDEC =>
                    expOrRef(v1, TInt)
                case SILENCE =>
                    expOrRef(v1, TAny)
            }

            def typeFromBinOP(v1: CFGSimpleValue, op: CFGBinaryOperator, v2: CFGSimpleValue): Type = op match {
                case PLUS =>
                    val t1 = typeFromSV(v1)

                    if (leq(t1, TAnyArray)) {
                        expOrRef(v2, TAnyArray)
                    } else {
                        expOrRef(v1, TNumeric)
                        expOrRef(v2, TNumeric)
                    }
                case MINUS | MULT | DIV | MOD =>
                    expOrRef(v2, TNumeric)
                    expOrRef(v1, TNumeric)
                    TNumeric
                case CONCAT =>
                    expOrRef(v1, TAny)
                    expOrRef(v2, TAny)
                    TString
                case INSTANCEOF =>
                    expOrRef(v1, TAnyObject)
                    expOrRef(v2, TString)
                    TBoolean
                case BITWISEAND | BITWISEOR | BITWISEXOR =>
                    expOrRef(v1, TInt)
                    expOrRef(v2, TInt)
                case SHIFTLEFT | SHIFTRIGHT =>
                    expOrRef(v1, TInt)
                    expOrRef(v2, TInt)
                case BOOLEANAND | BOOLEANOR | BOOLEANXOR | LT | LEQ | GEQ | GT |
                     EQUALS | IDENTICAL | NOTEQUALS | NOTIDENTICAL=>
                    expOrRef(v1, TAny)
                    expOrRef(v2, TAny)
                    TBoolean
            }

            def getCheckType(sv: CFGSimpleValue, ct: Type): (Option[CFGSimpleVariable], Type) = sv match {
                case CFGVariableVar(v) =>
                    getCheckType(v, TString)
                case CFGArrayEntry(arr, index) =>
                    typeFromSV(arr) match {
                        case TString =>
                            // If arr is known to be a string, index must be Int
                            expOrRef(index, TInt)
                            getCheckType(arr, TString)
                        case to: ObjectType =>
                            getCheckType(arr, TAny)
                        case t =>
                            expOrRef(index, TString, TInt)
                            val newct = if (ct == TTop) {
                                TAnyArray
                            } else {
                                typeFromSV(index) match {
                                    case TStringLit(v) =>
                                        new TArray().setAny(TTop).inject(v, ct)
                                    case TIntLit(v) =>
                                        new TArray().setAny(TTop).inject(v+"", ct)
                                    case TFloatLit(v) =>
                                        new TArray().setAny(TTop).inject(v.toInt+"", ct)
                                    case _ =>
                                        new TArray().setAny(ct)
                                }
                            }
                            getCheckType(arr, newct)
                    }
                case CFGNextArrayEntry(arr) =>
                    getCheckType(arr, TAnyArray)
                case CFGObjectProperty(obj, prop) =>
                    // IGNORE for now, focus on arrays
                    getCheckType(obj, TAnyObject)
                case svar: CFGSimpleVariable =>
                    (Some(svar), ct)
                case CFGVariableClassConstant(cr, id) =>
                    (None, ct)
                case CFGVariableClassProperty(cr, id) =>
                    (None, ct)
                case CFGNone() =>
                    (None, ct)
                case v =>
                    Predef.error("Woops, unexpected CFGVariable("+v+") inside checktype of!")

            }

            def assign(v: CFGVariable, ext: Type): Type = {
                val (osvar, ct) = getCheckType(v, TTop)
                if (!osvar.isEmpty) {
                    val svar = osvar.get
                    //println("Assigning "+v+" to "+ext)
                    //println("Checking "+svar+"("+typeFromSV(svar)+") against "+ct)
                    val reft = expOrRef(svar, ct)
                    //println("After refinement: "+reft)

                    // Now, we need to get down in that variable and affect the type as the assign require
                    def backPatchType(sv: CFGSimpleValue, typ: Type): Type = sv match {
                        case CFGVariableVar(v) =>
                            backPatchType(v, TString)
                        case CFGArrayEntry(arr, index) =>
                            val indtyp = typeFromSV(index)

                            val t = typeFromSV(arr) match {
                                case ta: TArray =>
                                    ta.injectByType(indtyp, typ)
                                case tu: TUnion =>
                                    val typs = for (f <- tu.types) yield f match {
                                        case ta: TArray =>
                                            ta.injectByType(indtyp, typ)
                                        case t =>
                                            t
                                    }

                                    typs.foldLeft(TBottom: Type)(_ union _)
                                case TAny =>
                                    TAny
                                case TTop =>
                                    TTop
                                case _ =>
                                    TBottom
                            }
                            backPatchType(arr, t)
                        case CFGNextArrayEntry(arr) =>
                            val t = typeFromSV(arr) match {
                                case ta: TArray =>
                                    ta.setAny(typ union ta.globalType)
                                case tu: TUnion =>
                                    val typs = for (f <- tu.types) yield f match {
                                        case ta: TArray =>
                                            ta.setAny(typ union ta.globalType)
                                        case t =>
                                            t
                                    }

                                    typs.foldLeft(TBottom: Type)(_ union _)
                                case TAny =>
                                    TAny
                                case TTop =>
                                    TTop
                                case _ =>
                                    TBottom
                            }
                            backPatchType(arr, t)
                        case CFGObjectProperty(obj, prop) =>
                            def updateObject(obj: TObjectRef) {
                                val ro =
                                    if (obj.id.pos < 0) {
                                        // $this is always using strong updates
                                        obj.realObject(env).injectField(prop, typ)
                                    } else {
                                        obj.realObject(env).injectField(prop, typ union obj.realObject(env).lookupField(prop))
                                    }
                                env = env.setObject(obj.id, ro)
                            }
                            val t = typeFromSV(obj) match {
                                case to: TObjectRef =>
                                    updateObject(to)
                                    to
                                case tu: TUnion =>
                                    for (f <- tu.types) f match {
                                        case to: TObjectRef =>
                                            updateObject(to)
                                        case _ =>
                                    }

                                    tu
                                case TAnyObject =>
                                    TAnyObject
                                case TAny =>
                                    TAny
                                case TTop =>
                                    TTop
                                case _ =>
                                    TBottom
                            }
                            backPatchType(obj, t)
                        case svar: CFGSimpleVariable =>
                            typ

                        case _ =>
                            Predef.error("Woops, unexpected CFGVariable inside checktype of!")
                    }

                    def limitType(typ: Type, l: Int): Type = typ match {
                        case ta: TArray =>
                            if (l == 0) {
                                TAnyArray
                            } else {
                                new TArray(Map[String, Type]() ++ ta.entries.map(e => (e._1, limitType(e._2, l-1))), limitType(ta.globalType, l-1))
                            }
                        case to: TObjectRef =>
                            if (l == 0) {
                                TAnyObject
                            } else {
                                // TODO
                                to
                            }
                        case tu: TUnion =>
                            if (l == 0) {
                                TTop
                            } else {
                                tu.types.map(limitType(_, l-1)).reduceLeft(_ union _)
                            }
                        case t =>
                            if (l == 0) {
                                TTop
                            } else {
                                t
                            }
                    }

                    env = env.inject(svar, reft)
                    //println("Refined type: "+reft)
                    var rest = backPatchType(v, ext)
                    //println("Backpatched type: "+rest)
                    //println("Depth: "+rest.depth(env))
                    if (rest.depth(env) >= 5) {
                        rest = limitType(rest, 5)
                    }
                    //println("Limitted: "+rest)
                    env = env.inject(svar, rest)
                    rest
                } else {
                    ext
                }
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
                                    if (!leq(typeFromSV(fcall_params(i)), tf.args(i)._1)) {
                                        //notice("Prototype mismatch because "+fcall.params(i)+"("+typeFromSV(fcall.params(i))+") </: "+args(i)._1) 

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
                            error("Unmatched function prototype '("+fcall_params.map(x => typeFromSV(x)).mkString(", ")+")', candidates are:\n    "+syms.mkString(",\n    "), pos)
                            TBottom
                        } else {
                            syms.head match {
                                case tf: TFunction =>
                                    for (i <- fcall_params.indices) {
                                        if (i >= tf.args.length) {
                                            error("Prototype error!", pos)
                                        } else {
                                            expOrRef(fcall_params(i), tf.args(i)._1)
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

            node match {
                case CFGAssign(vr: CFGVariable, v1) =>
                    //println("Assign..")
                    val t = expOrRef(v1, TAny)
                    assign(vr, uninitToNull(t))

                case CFGAssignUnary(vr: CFGVariable, op, v1) =>
                    // We want to typecheck v1 according to OP
                    val t = typeFromUnOP(op, v1);
                    assign(vr, uninitToNull(t))

                case CFGAssignBinary(vr: CFGVariable, v1, op, v2) =>
                    // We want to typecheck v1/v2 according to OP
                    val t = typeFromBinOP(v1, op, v2)
                    assign(vr, uninitToNull(t))

                case CFGAssume(v1, op, v2) => op match {
                    case LT | LEQ | GEQ | GT =>
                        expOrRef(v1, TNumeric)
                        expOrRef(v2, TNumeric)
                    case EQUALS | IDENTICAL | NOTEQUALS | NOTIDENTICAL =>
                        def filter(v: CFGVariable, value: Boolean) = {
                            val t = typeFromSV(v);

                            if (t != TBottom) {
                                // We don't want to generate "unreachable code"
                                // if the type is already bottom
                                val reft = if (value == true) {
                                    // possible types of $v after $v == true
                                    TNumeric union TAnyArray union TString union TTrue union TResource union TAnyObject
                                } else {
                                    // possible types of $v after $v == false
                                    TNumeric union TAnyArray union TString union TFalse union TNull union TUninitialized
                                }

                                val rest = meet(t, reft)

                                if (rest == TBottom) {
                                    // unreachable code
                                    env = BaseTypeEnvironment
                                } else {
                                    val (osv, ct) = getCheckType(v, rest)
                                    if (!osv.isEmpty) {
                                        env = env.inject(osv.get, ct)
                                    }
                                }
                            }
                        }

                        expOrRef(v1, TAny)
                        expOrRef(v2, TAny)

                        (v1, op, v2) match {
                            case (v: CFGVariable, EQUALS | IDENTICAL, _: CFGTrue) =>
                                filter(v, true)
                            case (v: CFGVariable, NOTEQUALS | NOTIDENTICAL, _: CFGTrue) =>
                                filter(v, false)
                            case (v: CFGVariable, EQUALS | IDENTICAL, _: CFGFalse) =>
                                filter(v, false)
                            case (v: CFGVariable, NOTEQUALS | NOTIDENTICAL, _: CFGFalse) =>
                                filter(v, true)
                            case (_:CFGTrue, EQUALS | IDENTICAL, v: CFGVariable) =>
                                filter(v, true)
                            case (_:CFGTrue, NOTEQUALS | NOTIDENTICAL, v: CFGVariable) =>
                                filter(v, false)
                            case (_:CFGFalse, EQUALS | IDENTICAL, v: CFGVariable) =>
                                filter(v, false)
                            case (_:CFGFalse, NOTEQUALS | NOTIDENTICAL, v: CFGVariable) =>
                                filter(v, true)
                            case _ =>
                                // no filtering
                        }
                  }

                case CFGPrint(v) =>
                    expOrRef(v, TAny)

                case CFGUnset(v) =>
                    assign(v, TUninitialized)

                case ex: CFGSimpleValue =>
                    expOrRef(ex, TAny)

                case CFGSkip =>

                case _ => notice(node+" not yet handled", node)
            }

            env
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

            //scope.registerPredefVariables
            injectPredef("_GET",     new TArray(TTop))
            injectPredef("_POST",    new TArray(TTop))
            injectPredef("GLOBALS",  new TArray(TTop))
            injectPredef("_REQUEST", new TArray(TTop))
            injectPredef("_COOKIE",  new TArray(TTop))
            injectPredef("_SERVER",  new TArray(TTop))
            injectPredef("_FILES",   new TArray(TTop))
            injectPredef("_ENV",     new TArray(TTop))
            injectPredef("_SESSION", new TArray(TTop))

            // for methods, we inject $this as its always defined
            scope match {
                case ms: MethodSymbol =>
                    baseEnv = baseEnv.setStore(baseEnv.store.initIfNotExist(ObjectId(-1, 0), Some(ms.cs)))
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

            // we inject vars for static class properties
            for(cs <- GlobalSymbols.getClasses) {
                for(ps <- cs.getStaticProperties) {
                    baseEnv = baseEnv.inject(CFGClassProperty(ps), ps.typ)
                }
            }

            // Lets try the unserializer
            if (Main.dumpedData != Nil) {
                for (unser <- Main.dumpedData) {
                    baseEnv = unser.importToEnv(baseEnv)
                }
            }

            baseEnv
        }

        def analyze: Unit = {
            val bottomEnv = BaseTypeEnvironment;
            val baseEnv   = setupEnvironment;

            val aa = new AnalysisAlgorithm[TypeEnvironment, CFGStatement](TypeTransferFunction(true, false), bottomEnv, baseEnv, cfg)

            aa.init
            aa.computeFixpoint

            if (Main.displayFixPoint) {
                println("     - Fixpoint:");
                for ((v,e) <- aa.getResult.toList.sortWith{(x,y) => x._1.name < y._1.name}) {
                    println("      * ["+v+"] => "+e);
                }
            }

            // Detect unreachables:
            for (l <- aa.detectUnreachable(TypeTransferFunction(true, false))) {
                Reporter.notice("Unreachable code", l)
            }

            // Collect errors and annotations
            aa.pass(TypeTransferFunction(false, !Main.exportAPIPath.isEmpty))

            // Collect retvals
            scope match {
                case fs: FunctionSymbol =>
                    // collect return value
                    val facts = aa.getResult;
                    val retType = facts(cfg.exit).map.getOrElse(CFGTempID("retval"), TBottom);

                    AnnotationsStore.collectFunctionRet(fs, retType)
                case _ =>
            }
        }
    }
}
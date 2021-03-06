package phantm.types

import phantm.cfg.Trees._
import phantm.phases.PhasesContext
import phantm.symbols._
import phantm.dataflow.Environment

class TypeEnvironment(val map: Map[SimpleVariable, Type], val scope: Option[ClassSymbol], val store: ObjectStore) extends Environment[TypeEnvironment, Statement] {


    def this(scope: Option[ClassSymbol]) = {
        this(Map[SimpleVariable, Type](), scope, new ObjectStore);
    }

    def this() = {
        this(Map[SimpleVariable, Type](), None, new ObjectStore);
    }

    def getGlobalsType: Type = {
        new TArray(map.collect{ case (Identifier(sym), t) => (ArrayKey.fromString(sym.name), t)}, TUninitialized, TUninitialized)
    }

    def lookup(v: SimpleVariable): Option[Type] = map.get(v)

    def inject(v: SimpleVariable, typ: Type): TypeEnvironment =
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
        new TypeEnvironment(Map[SimpleVariable, Type]()++map, scope, store)

    def union(e: TypeEnvironment): TypeEnvironment = {
        e match {
            case BaseTypeEnvironment =>
                this

            case te: TypeEnvironment =>
                var newmap = Map[SimpleVariable, Type]();

                for (k <- map.keySet ++ e.map.keySet) {
                    newmap = newmap.updated(k,
                        map.getOrElse(k, TTop) union e.map.getOrElse(k, TTop))
                }

                new TypeEnvironment(newmap, scope, store).unionStoreFrom(te)
        }
    }

    def unionStoreFrom(e: TypeEnvironment): TypeEnvironment = {
        e match {
            case BaseTypeEnvironment =>
                this

            case te: TypeEnvironment =>
                var env = this

                val s1 = this.store
                val s2 = te.store

                var newstore = new ObjectStore()

                for (id <- s1.store.keySet ++ s2.store.keySet) {
                    val c1 = s1.store.contains(id);
                    val c2 = s2.store.contains(id);

                    if (c1 && c2) {
                        val (nenv, nobj) = TypeLattice.joinObjects(this, s1.store(id), s2.store(id))
                        env = nenv
                        newstore = newstore.set(id, nobj)
                    } else if (c1) {
                        newstore = newstore.set(id, s1.store(id))
                    } else {
                        newstore = newstore.set(id, s2.store(id))
                    }
                }

                env.setStore(newstore)
        }
    }

    def unionStore(st: ObjectStore): TypeEnvironment = {
        unionStoreFrom(new TypeEnvironment().setStore(st))
    }

    def checkMonotonicity(vrtx: Vertex, e: TypeEnvironment, ctx: PhasesContext, inEdges: Iterable[(Statement, TypeEnvironment)]): Unit = {
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
                        println("   * "+cfg+" => "+e.lookup(v)+" ===> "+TypeTransferFunction(true, ctx, false)(cfg, e).lookup(v))
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
                "(#"+or.id.pos+","+or.id.typ+")"+store.lookup(or).toString
            case _ => t.toString
        }
        
        map.toList.filter( tmp => tmp._1.toString.toList.head != '_' && tmp._1.toString != "GLOBALS" && !tmp._1.toString.contains("::")).sortWith{(x,y) => x._1.uniqueID < x._1.uniqueID}.map(x => x._1+" => "+typeToString(x._2)).mkString("[ ", "; ", " ]");
    }
}

package com.nova.client.mcmod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Syntax tree of McMod Script; every node evaluates itself. */
final class Ast {
    private Ast() { }

    // ------------------------------------------------------------------ control flow
    static class Jumping extends RuntimeException {
        @Override public synchronized Throwable fillInStackTrace() { return this; }    // cheap: used for control flow
    }
    static final class Break extends Jumping { }
    static final class Continue extends Jumping { }
    static final class Return extends Jumping {
        final Object value;
        Return(Object v) { value = v; }
    }
    static final Break BREAK = new Break();
    static final Continue CONTINUE = new Continue();

    // ------------------------------------------------------------------ scopes
    static final class Scope {
        final Scope parent;
        final Map<String, Object> vars = new java.util.HashMap<String, Object>();
        Scope(Scope parent) { this.parent = parent; }

        Scope find(String name) {
            for (Scope s = this; s != null; s = s.parent) if (s.vars.containsKey(name)) return s;
            return null;
        }
    }

    // ------------------------------------------------------------------ nodes
    abstract static class Node {
        final int line;
        Node(int line) { this.line = line; }
        abstract Object eval(Interp in, Scope sc);
    }

    static final class Lit extends Node {
        final Object v;
        Lit(int line, Object v) { super(line); this.v = v; }
        Object eval(Interp in, Scope sc) { return v; }
    }

    /** "text {expr} text" */
    static final class Str extends Node {
        final Object[] parts;      // String or Node
        Str(int line, Object[] parts) { super(line); this.parts = parts; }
        Object eval(Interp in, Scope sc) {
            StringBuilder b = new StringBuilder();
            for (Object p : parts) b.append(p instanceof Node ? Interp.str(((Node) p).eval(in, sc)) : (String) p);
            return b.toString();
        }
    }

    static final class Name extends Node {
        final String name;
        Name(int line, String name) { super(line); this.name = name; }
        Object eval(Interp in, Scope sc) {
            Scope s = sc.find(name);
            if (s == null) throw new ScriptError("неизвестное имя '" + name + "'", line);
            return s.vars.get(name);
        }
    }

    static final class ListLit extends Node {
        final Node[] items;
        ListLit(int line, Node[] items) { super(line); this.items = items; }
        Object eval(Interp in, Scope sc) {
            List<Object> l = new ArrayList<Object>(items.length);
            for (Node n : items) l.add(n.eval(in, sc));
            return l;
        }
    }

    static final class MapLit extends Node {
        final String[] keys;
        final Node[] vals;
        MapLit(int line, String[] keys, Node[] vals) { super(line); this.keys = keys; this.vals = vals; }
        Object eval(Interp in, Scope sc) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            for (int i = 0; i < keys.length; i++) m.put(keys[i], vals[i].eval(in, sc));
            return m;
        }
    }

    static final class Member extends Node {
        final Node obj;
        final String name;
        Member(int line, Node obj, String name) { super(line); this.obj = obj; this.name = name; }
        Object eval(Interp in, Scope sc) { return Interp.member(obj.eval(in, sc), name, line); }
    }

    static final class Index extends Node {
        final Node obj, idx;
        Index(int line, Node obj, Node idx) { super(line); this.obj = obj; this.idx = idx; }
        Object eval(Interp in, Scope sc) { return Interp.index(obj.eval(in, sc), idx.eval(in, sc), line); }
    }

    static final class Call extends Node {
        final Node fn;
        final Node[] args;
        Call(int line, Node fn, Node[] args) { super(line); this.fn = fn; this.args = args; }
        Object eval(Interp in, Scope sc) {
            in.step(line);
            List<Object> a = new ArrayList<Object>(args.length);
            if (fn instanceof Member) {                    // obj.method(...): methods of values
                Member m = (Member) fn;
                Object o = m.obj.eval(in, sc);
                for (Node n : args) a.add(n.eval(in, sc));
                return Interp.callMember(in, o, m.name, a, line);
            }
            Object f = fn.eval(in, sc);
            for (Node n : args) a.add(n.eval(in, sc));
            return Interp.call(in, f, a, line);
        }
    }

    static final class Unary extends Node {
        final String op;
        final Node x;
        Unary(int line, String op, Node x) { super(line); this.op = op; this.x = x; }
        Object eval(Interp in, Scope sc) {
            Object v = x.eval(in, sc);
            if (op.equals("-")) return -Interp.num(v, line);
            return !Interp.truth(v);
        }
    }

    static final class Binary extends Node {
        final String op;
        final Node a, b;
        Binary(int line, String op, Node a, Node b) { super(line); this.op = op; this.a = a; this.b = b; }
        Object eval(Interp in, Scope sc) {
            if (op.equals("and")) { Object x = a.eval(in, sc); return Interp.truth(x) ? b.eval(in, sc) : x; }
            if (op.equals("or")) { Object x = a.eval(in, sc); return Interp.truth(x) ? x : b.eval(in, sc); }
            return Interp.binary(op, a.eval(in, sc), b.eval(in, sc), line);
        }
    }

    /** cond ? a : b */
    static final class Cond extends Node {
        final Node c, a, b;
        Cond(int line, Node c, Node a, Node b) { super(line); this.c = c; this.a = a; this.b = b; }
        Object eval(Interp in, Scope sc) { return Interp.truth(c.eval(in, sc)) ? a.eval(in, sc) : b.eval(in, sc); }
    }

    static final class Lambda extends Node {
        final String name;
        final String[] params;
        final Node[] defaults;
        final Block body;
        Lambda(int line, String name, String[] params, Node[] defaults, Block body) {
            super(line); this.name = name; this.params = params; this.defaults = defaults; this.body = body;
        }
        Object eval(Interp in, Scope sc) { return new Interp.Fn(this, sc, in); }
    }

    // ------------------------------------------------------------------ statements
    static final class Block extends Node {
        final Node[] body;
        Block(int line, Node[] body) { super(line); this.body = body; }
        Object eval(Interp in, Scope sc) {
            Scope s = new Scope(sc);
            for (Node n : body) { in.step(n.line); n.eval(in, s); }
            return null;
        }
    }

    static final class Var extends Node {
        final String name;
        final Node init;
        Var(int line, String name, Node init) { super(line); this.name = name; this.init = init; }
        Object eval(Interp in, Scope sc) {
            sc.vars.put(name, init == null ? null : init.eval(in, sc));
            return null;
        }
    }

    static final class Assign extends Node {
        final Node target;
        final String op;          // "=", "+=", ...
        final Node value;
        Assign(int line, Node target, String op, Node value) { super(line); this.target = target; this.op = op; this.value = value; }
        Object eval(Interp in, Scope sc) {
            if (target instanceof Name) {
                String n = ((Name) target).name;
                Scope s = sc.find(n);
                if (s == null) {
                    if (!op.equals("=")) throw new ScriptError("неизвестное имя '" + n + "'", line);
                    s = sc;                                   // first assignment declares a local
                }
                if (s.parent == null && s == in.builtins) throw new ScriptError("'" + n + "' нельзя менять", line);
                Object v = value.eval(in, sc);
                if (!op.equals("=")) v = Interp.binary(op.substring(0, 1), s.vars.get(n), v, line);
                s.vars.put(n, v);
                return null;
            }
            if (target instanceof Member) {
                Member m = (Member) target;
                Object o = m.obj.eval(in, sc);
                Object v = value.eval(in, sc);
                if (!op.equals("=")) v = Interp.binary(op.substring(0, 1), Interp.member(o, m.name, line), v, line);
                Interp.setMember(o, m.name, v, line);
                return null;
            }
            if (target instanceof Index) {
                Index x = (Index) target;
                Object o = x.obj.eval(in, sc), k = x.idx.eval(in, sc);
                Object v = value.eval(in, sc);
                if (!op.equals("=")) v = Interp.binary(op.substring(0, 1), Interp.index(o, k, line), v, line);
                Interp.setIndex(o, k, v, line);
                return null;
            }
            throw new ScriptError("сюда нельзя присвоить", line);
        }
    }

    static final class If extends Node {
        final Node cond;
        final Node then, other;     // other: Block, If or null
        If(int line, Node cond, Node then, Node other) { super(line); this.cond = cond; this.then = then; this.other = other; }
        Object eval(Interp in, Scope sc) {
            if (Interp.truth(cond.eval(in, sc))) then.eval(in, sc);
            else if (other != null) other.eval(in, sc);
            return null;
        }
    }

    static final class While extends Node {
        final Node cond;
        final Block body;
        While(int line, Node cond, Block body) { super(line); this.cond = cond; this.body = body; }
        Object eval(Interp in, Scope sc) {
            while (Interp.truth(cond.eval(in, sc))) {
                in.step(line);
                try { body.eval(in, sc); } catch (Break b) { break; } catch (Continue c) { /* next */ }
            }
            return null;
        }
    }

    static final class For extends Node {
        final String var;
        final Node src;
        final Block body;
        For(int line, String var, Node src, Block body) { super(line); this.var = var; this.src = src; this.body = body; }
        Object eval(Interp in, Scope sc) {
            Scope s = new Scope(sc);
            if (src instanceof Binary && ((Binary) src).op.equals("..")) {       // a..b without building a list
                Binary r = (Binary) src;
                double a = Interp.num(r.a.eval(in, sc), line), b = Interp.num(r.b.eval(in, sc), line);
                double st = a <= b ? 1 : -1;
                for (double v = a; st > 0 ? v <= b : v >= b; v += st) {
                    in.step(line);
                    s.vars.put(var, v);
                    try { body.eval(in, s); } catch (Break x) { break; } catch (Continue x) { /* next */ }
                }
                return null;
            }
            Object o = src.eval(in, sc);
            Iterable<?> it;
            if (o instanceof List) it = new ArrayList<Object>((List<?>) o);
            else if (o instanceof Map) it = new ArrayList<String>(((Map<String, ?>) o).keySet());
            else if (o instanceof String) {
                List<Object> cs = new ArrayList<Object>();
                String str = (String) o;
                for (int i = 0; i < str.length(); i++) cs.add(String.valueOf(str.charAt(i)));
                it = cs;
            } else throw new ScriptError("по " + Interp.typeName(o) + " нельзя пройти циклом for", line);
            for (Object v : it) {
                in.step(line);
                s.vars.put(var, v);
                try { body.eval(in, s); } catch (Break x) { break; } catch (Continue x) { /* next */ }
            }
            return null;
        }
    }

    static final class Ret extends Node {
        final Node value;
        Ret(int line, Node value) { super(line); this.value = value; }
        Object eval(Interp in, Scope sc) { throw new Return(value == null ? null : value.eval(in, sc)); }
    }

    static final class Jump extends Node {
        final boolean brk;
        Jump(int line, boolean brk) { super(line); this.brk = brk; }
        Object eval(Interp in, Scope sc) { if (brk) throw BREAK; throw CONTINUE; }
    }

    static final class ExprStmt extends Node {
        final Node e;
        ExprStmt(int line, Node e) { super(line); this.e = e; }
        Object eval(Interp in, Scope sc) { e.eval(in, sc); return null; }
    }

    // ------------------------------------------------------------------ top level
    static final class OnEvent {
        final String event, param;
        final Block body;
        final int line;
        OnEvent(String event, String param, Block body, int line) { this.event = event; this.param = param; this.body = body; this.line = line; }
    }

    static final class Every {
        final Node interval;
        final boolean seconds;
        final Block body;
        final int line;
        Every(Node interval, boolean seconds, Block body, int line) { this.interval = interval; this.seconds = seconds; this.body = body; this.line = line; }
    }

    static final class OverrideDecl {
        final String ns, name;
        final Lambda fn;
        OverrideDecl(String ns, String name, Lambda fn) { this.ns = ns; this.name = name; this.fn = fn; }
    }

    /** item / block / recipe ... { key: value } (registered by later stages) */
    static final class Decl {
        final String kind, name;
        final MapLit props;
        final int line;
        Decl(String kind, String name, MapLit props, int line) { this.kind = kind; this.name = name; this.props = props; this.line = line; }
    }

    static final class Program {
        final List<Node> init = new ArrayList<Node>();        // top-level statements and fn definitions, in order
        final List<OnEvent> events = new ArrayList<OnEvent>();
        final List<Every> every = new ArrayList<Every>();
        final List<OverrideDecl> overrides = new ArrayList<OverrideDecl>();
        final List<Decl> decls = new ArrayList<Decl>();
    }
}

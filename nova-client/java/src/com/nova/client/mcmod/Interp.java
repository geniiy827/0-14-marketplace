package com.nova.client.mcmod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nova.client.mcmod.Ast.*;

/** Runtime of one mod: its globals, step budget, value rules shared by all nodes. */
public final class Interp {
    public final String modId, file;
    final Scope builtins, globals;
    private int steps;
    public static final int STEP_LIMIT = 300000;

    Interp(String modId, String file, Scope builtins) {
        this.modId = modId;
        this.file = file;
        this.builtins = builtins;
        this.globals = new Scope(builtins);
    }

    void resetSteps() { steps = 0; }

    void step(int line) {
        if (++steps > STEP_LIMIT) throw new ScriptError("скрипт работает слишком долго (бесконечный цикл?)", line);
    }

    // ------------------------------------------------------------------ callables
    public interface Callable {
        Object call(Interp in, List<Object> args, int line);
    }

    /** a function written in McMod Script */
    static final class Fn implements Callable {
        final Lambda def;
        final Scope closure;
        final Interp owner;
        Fn(Lambda def, Scope closure, Interp owner) { this.def = def; this.closure = closure; this.owner = owner; }

        public Object call(Interp in, List<Object> args, int line) {
            Interp me = owner;                        // runs with the defining mod's globals
            Scope s = new Scope(closure);
            String[] ps = def.params;
            if (args.size() > ps.length)
                throw new ScriptError(def.name + "(): лишние аргументы (" + args.size() + " вместо " + ps.length + ")", line);
            for (int i = 0; i < ps.length; i++) {
                Object v;
                if (i < args.size()) v = args.get(i);
                else if (def.defaults[i] != null) v = def.defaults[i].eval(me, s);
                else throw new ScriptError(def.name + "(): не хватает аргумента '" + ps[i] + "'", line);
                s.vars.put(ps[i], v);
            }
            try {
                for (Node n : def.body.body) { me.step(n.line); n.eval(me, s); }
            } catch (Return r) {
                return r.value;
            }
            return null;
        }

        public String toString() { return "<fn " + def.name + ">"; }
    }

    /** a group of functions: chat, world, player ...; calling it calls its "default" member */
    public static final class Ns {
        public final String name;
        public final Map<String, Object> members = new LinkedHashMap<String, Object>();
        public String callDefault;
        public Ns(String name) { this.name = name; }
        public String toString() { return "<" + name + ">"; }
    }

    // ------------------------------------------------------------------ value rules
    public static boolean truth(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Double) return (Double) v != 0;
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }

    public static double num(Object v, int line) {
        if (v instanceof Double) return (Double) v;
        if (v instanceof Boolean) return (Boolean) v ? 1 : 0;
        throw new ScriptError("нужно число, а это " + typeName(v), line);
    }

    public static String typeName(Object v) {
        if (v == null) return "null";
        if (v instanceof Double) return "число";
        if (v instanceof String) return "строка";
        if (v instanceof Boolean) return "true/false";
        if (v instanceof List) return "список";
        if (v instanceof Ns) return "группа " + ((Ns) v).name;
        if (v instanceof Map) return "таблица";
        if (v instanceof Callable) return "функция";
        return v.getClass().getSimpleName();
    }

    public static String str(Object v) {
        if (v == null) return "null";
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
            if (Math.abs(d) < 1e-9) return "0";
            String s = String.format(java.util.Locale.US, "%.4f", d);
            s = s.replaceAll("0+$", "");
            return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
        }
        if (v instanceof List) {
            StringBuilder b = new StringBuilder("[");
            List<?> l = (List<?>) v;
            for (int i = 0; i < l.size(); i++) { if (i > 0) b.append(", "); b.append(repr(l.get(i))); }
            return b.append(']').toString();
        }
        if (v instanceof Map && !(v instanceof Ns)) {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (e.getValue() instanceof Callable) continue;
                if (!first) b.append(", ");
                first = false;
                b.append(e.getKey()).append(": ").append(repr(e.getValue()));
            }
            return b.append('}').toString();
        }
        return String.valueOf(v);
    }

    private static String repr(Object v) { return v instanceof String ? "\"" + v + "\"" : str(v); }

    public static boolean eq(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Double && b instanceof Double) return ((Double) a).doubleValue() == ((Double) b).doubleValue();
        return a.equals(b);
    }

    static Object binary(String op, Object a, Object b, int line) {
        switch (op) {
            case "+":
                if (a instanceof Double && b instanceof Double) return (Double) a + (Double) b;
                if (a instanceof String || b instanceof String) return str(a) + str(b);
                if (a instanceof List && b instanceof List) {
                    List<Object> l = new ArrayList<Object>((List<?>) a);
                    l.addAll((List<?>) b);
                    return l;
                }
                throw new ScriptError("нельзя сложить " + typeName(a) + " и " + typeName(b), line);
            case "-": return num(a, line) - num(b, line);
            case "*":
                if (a instanceof String && b instanceof Double) {
                    StringBuilder s = new StringBuilder();
                    for (int i = 0; i < (Double) b && i < 10000; i++) s.append((String) a);
                    return s.toString();
                }
                return num(a, line) * num(b, line);
            case "/": {
                double d = num(b, line);
                if (d == 0) throw new ScriptError("деление на ноль", line);
                return num(a, line) / d;
            }
            case "%": {
                double d = num(b, line);
                if (d == 0) throw new ScriptError("деление на ноль", line);
                double r = num(a, line) % d;
                return r < 0 ? r + Math.abs(d) : r;
            }
            case "==": return eq(a, b);
            case "!=": return !eq(a, b);
            case "<": case ">": case "<=": case ">=": {
                int c;
                if (a instanceof String && b instanceof String) c = ((String) a).compareTo((String) b);
                else c = Double.compare(num(a, line), num(b, line));
                return op.equals("<") ? c < 0 : op.equals(">") ? c > 0 : op.equals("<=") ? c <= 0 : c >= 0;
            }
            case "..": {
                double x = num(a, line), y = num(b, line);
                List<Object> l = new ArrayList<Object>();
                if (Math.abs(y - x) > 100000) throw new ScriptError("слишком большой диапазон", line);
                for (double v = x; x <= y ? v <= y : v >= y; v += x <= y ? 1 : -1) l.add(v);
                return l;
            }
            default: throw new ScriptError("неизвестная операция " + op, line);
        }
    }

    static Object member(Object o, String name, int line) {
        if (o instanceof Ns) {
            Ns ns = (Ns) o;
            if (!ns.members.containsKey(name)) throw new ScriptError("в " + ns.name + " нет '" + name + "'", line);
            return ns.members.get(name);
        }
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            return m.get(name);
        }
        if (o instanceof List && (name.equals("len") || name.equals("size"))) return (double) ((List<?>) o).size();
        if (o instanceof String && (name.equals("len") || name.equals("size"))) return (double) ((String) o).length();
        throw new ScriptError("у " + typeName(o) + " нет поля '" + name + "'", line);
    }

    @SuppressWarnings("unchecked")
    static void setMember(Object o, String name, Object v, int line) {
        if (o instanceof Map && !(o instanceof Ns)) { ((Map<String, Object>) o).put(name, v); return; }
        throw new ScriptError("у " + typeName(o) + " нельзя поменять '" + name + "'", line);
    }

    static Object index(Object o, Object k, int line) {
        if (o instanceof List) {
            List<?> l = (List<?>) o;
            int i = (int) num(k, line);
            if (i < 0) i += l.size();
            if (i < 0 || i >= l.size()) return null;
            return l.get(i);
        }
        if (o instanceof Map) return ((Map<?, ?>) o).get(str(k));
        if (o instanceof String) {
            String s = (String) o;
            int i = (int) num(k, line);
            if (i < 0) i += s.length();
            return i < 0 || i >= s.length() ? null : String.valueOf(s.charAt(i));
        }
        throw new ScriptError("у " + typeName(o) + " нет элементов по индексу", line);
    }

    @SuppressWarnings("unchecked")
    static void setIndex(Object o, Object k, Object v, int line) {
        if (o instanceof List) {
            List<Object> l = (List<Object>) o;
            int i = (int) num(k, line);
            if (i < 0) i += l.size();
            if (i == l.size()) { l.add(v); return; }
            if (i < 0 || i > l.size()) throw new ScriptError("индекс " + i + " за пределами списка", line);
            l.set(i, v);
            return;
        }
        if (o instanceof Map && !(o instanceof Ns)) { ((Map<String, Object>) o).put(str(k), v); return; }
        throw new ScriptError("у " + typeName(o) + " нельзя поменять элемент", line);
    }

    static Object call(Interp in, Object f, List<Object> args, int line) {
        if (f instanceof Ns) {
            Ns ns = (Ns) f;
            if (ns.callDefault == null) throw new ScriptError(ns.name + " - это группа, а не функция", line);
            f = ns.members.get(ns.callDefault);
        }
        if (!(f instanceof Callable)) throw new ScriptError("это не функция (" + typeName(f) + ")", line);
        return ((Callable) f).call(in, args, line);
    }

    /** obj.name(args): group functions, fields holding functions, then methods of lists/strings/maps */
    @SuppressWarnings("unchecked")
    static Object callMember(Interp in, Object o, String name, List<Object> a, int line) {
        if (o instanceof Ns) return call(in, member(o, name, line), a, line);
        if (o instanceof Map && ((Map<?, ?>) o).get(name) instanceof Callable)
            return ((Callable) ((Map<?, ?>) o).get(name)).call(in, a, line);
        if (o instanceof String) {
            String s = (String) o;
            switch (name) {
                case "len": return (double) s.length();
                case "upper": return s.toUpperCase();
                case "lower": return s.toLowerCase();
                case "trim": return s.trim();
                case "contains": return s.contains(str(arg(a, 0, line)));
                case "starts": case "startsWith": return s.startsWith(str(arg(a, 0, line)));
                case "ends": case "endsWith": return s.endsWith(str(arg(a, 0, line)));
                case "find": return (double) s.indexOf(str(arg(a, 0, line)));
                case "replace": return s.replace(str(arg(a, 0, line)), str(arg(a, 1, line)));
                case "sub": {
                    int x = (int) num(arg(a, 0, line), line), y = a.size() > 1 ? (int) num(a.get(1), line) : s.length();
                    x = Math.max(0, Math.min(s.length(), x)); y = Math.max(x, Math.min(s.length(), y));
                    return s.substring(x, y);
                }
                case "split": {
                    List<Object> l = new ArrayList<Object>();
                    String sep = a.isEmpty() ? " " : str(a.get(0));
                    if (sep.isEmpty()) { for (char c : s.toCharArray()) l.add(String.valueOf(c)); return l; }
                    int i = 0;
                    while (true) {
                        int j = s.indexOf(sep, i);
                        if (j < 0) { l.add(s.substring(i)); break; }
                        l.add(s.substring(i, j));
                        i = j + sep.length();
                    }
                    return l;
                }
                case "num": try { return Double.parseDouble(s.trim()); } catch (Exception e) { return null; }
                default:
            }
        }
        if (o instanceof List) {
            List<Object> l = (List<Object>) o;
            switch (name) {
                case "len": case "size": return (double) l.size();
                case "add": case "push": l.add(arg(a, 0, line)); return null;
                case "insert": l.add(Math.max(0, Math.min(l.size(), (int) num(arg(a, 0, line), line))), arg(a, 1, line)); return null;
                case "pop": return l.isEmpty() ? null : l.remove(l.size() - 1);
                case "removeAt": {
                    int i = (int) num(arg(a, 0, line), line);
                    return i >= 0 && i < l.size() ? l.remove(i) : null;
                }
                case "remove": {
                    Object x = arg(a, 0, line);
                    for (int i = 0; i < l.size(); i++) if (eq(l.get(i), x)) { l.remove(i); return true; }
                    return false;
                }
                case "contains": case "has": {
                    Object x = arg(a, 0, line);
                    for (Object y : l) if (eq(x, y)) return true;
                    return false;
                }
                case "find": {
                    Object x = arg(a, 0, line);
                    for (int i = 0; i < l.size(); i++) if (eq(l.get(i), x)) return (double) i;
                    return -1.0;
                }
                case "join": {
                    StringBuilder b = new StringBuilder();
                    String sep = a.isEmpty() ? ", " : str(a.get(0));
                    for (int i = 0; i < l.size(); i++) { if (i > 0) b.append(sep); b.append(str(l.get(i))); }
                    return b.toString();
                }
                case "clear": l.clear(); return null;
                case "copy": return new ArrayList<Object>(l);
                case "random": return l.isEmpty() ? null : l.get((int) (Math.random() * l.size()));
                default:
            }
        }
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            switch (name) {
                case "keys": return new ArrayList<Object>(m.keySet());
                case "values": return new ArrayList<Object>(m.values());
                case "has": return m.containsKey(str(arg(a, 0, line)));
                case "remove": return m.remove(str(arg(a, 0, line)));
                case "len": case "size": return (double) m.size();
                default:
            }
        }
        if (o instanceof Map) throw new ScriptError("в таблице нет функции '" + name + "'", line);
        throw new ScriptError("у " + typeName(o) + " нет функции '" + name + "'", line);
    }

    static Object arg(List<Object> a, int i, int line) {
        if (i >= a.size()) throw new ScriptError("не хватает аргумента №" + (i + 1), line);
        return a.get(i);
    }
}

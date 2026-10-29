package com.nova.client.mcmod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nova.client.mcmod.Ast.*;
import com.nova.client.mcmod.Interp.Callable;
import com.nova.client.mcmod.Interp.Ns;

/**
 * Runs the enabled mods. Everything here runs on the game thread: libnova calls event()
 * from the game loop, and scripts call back into libnova with nativeApi().
 * The functions come from ApiTable (generated from mcmod/api/*.tsv).
 */
public final class ModRuntime {
    /** what the runtime needs from the platform (Android or a desktop test) */
    public interface Host {
        double[] nativeApi(int op, double[] args, String strs);     // null = not in a world, [] = not found
        String nativeApiStr(int op, double[] args, String strs);
        void log(String s);
        void toast(String title, String text);
        String loadData(String modId);                              // JSON text or null
        void saveData(String modId, String json);
        String prelude();                                           // prelude.mcs source
    }

    /** one loaded mod */
    public static final class Mod {
        public final String id, name, file;
        final Interp in;
        Program prog;
        ScriptError parseError;
        int errors;
        final long[] everyNext;
        Map<String, Object> data;
        Mod(String id, String name, String file, Interp in, int nEvery) {
            this.id = id; this.name = name; this.file = file; this.in = in; everyNext = new long[nEvery];
        }
    }

    final Host host;
    final Scope builtins = new Scope(null);
    private final Map<String, Ns> groups = new LinkedHashMap<String, Ns>();
    private final List<Mod> mods = new ArrayList<Mod>();
    private final Map<String, Double> idCache = new HashMap<String, Double>();
    private final Interp system;                 // runs the prelude
    private long tick;
    private boolean inWorld;
    private final List<String> pendingReports = new ArrayList<String>();
    /** mod blocks: "modid:name" and plain "name" -> block id (set by ModBlocks) */
    public final Map<String, Double> customIds = new HashMap<String, Double>();

    /** block declarations of every parsed mod: {Mod, name, props map, line} */
    public List<Object[]> blockDecls() {
        List<Object[]> out = new ArrayList<Object[]>();
        for (Mod m : mods) {
            if (m.prog == null) continue;
            for (Decl d : m.prog.decls) {
                if (!d.kind.equals("block")) continue;
                try {
                    Object v = d.props.eval(m.in, m.in.globals);
                    out.add(new Object[]{m, d.name, v, d.line});
                } catch (ScriptError e) {
                    e.file = m.file;
                    report(m, e);
                }
            }
        }
        return out;
    }

    /** an error found while preparing a mod (shown in chat when the player enters a world) */
    public void reportLater(Mod m, String msg, int line) {
        ScriptError e = new ScriptError(msg, line);
        e.file = m.file;
        report(m, e);
    }

    private final java.util.LinkedHashSet<Double> entities = new java.util.LinkedHashSet<Double>();

    public ModRuntime(Host host) {
        this.host = host;
        system = new Interp("nova", "prelude.mcs", builtins);
        for (String[] f : ApiTable.FUNCS) bind(f);
        Ns chat = groups.get("chat");
        if (chat != null) chat.callDefault = "send";
        try {
            Program p = Parser.parse(host.prelude());
            for (Node n : p.init) n.eval(system, system.globals);
        } catch (ScriptError e) {
            e.file = "prelude.mcs";
            host.log("prelude: " + e.where() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ API binding
    static final class Param {
        final String name, type;
        final Object def;
        final boolean hasDef;
        Param(String name, String type, Object def, boolean hasDef) { this.name = name; this.type = type; this.def = def; this.hasDef = hasDef; }
    }

    static Param[] params(String spec) {
        if (spec.equals("-") || spec.trim().isEmpty()) return new Param[0];
        String[] parts = spec.split(",");
        Param[] out = new Param[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            Object def = null;
            boolean has = false;
            int eq = p.indexOf('=');
            if (eq >= 0) {
                String d = p.substring(eq + 1).trim();
                p = p.substring(0, eq).trim();
                has = true;
                if (d.equals("true")) def = Boolean.TRUE;
                else if (d.equals("false")) def = Boolean.FALSE;
                else if (d.equals("null")) def = null;
                else if (d.startsWith("\"")) def = d.substring(1, d.length() - 1);
                else def = Double.parseDouble(d);
            }
            int c = p.indexOf(':');
            out[i] = new Param(c < 0 ? p : p.substring(0, c), c < 0 ? "any" : p.substring(c + 1), def, has);
        }
        return out;
    }

    private void bind(String[] row) {
        final String name = row[0], ret = row[2], impl = row[3];
        final Param[] ps = params(row[1]);
        Callable fn;
        if (impl.startsWith("n:") || impl.startsWith("s:")) {
            String[] x = impl.split(":", -1);
            final boolean str = impl.startsWith("s:");
            final int op = Integer.parseInt(x[1]), idx = Integer.parseInt(x[2]), sub = Integer.parseInt(x[3]);
            final String[] keys = x.length > 4 && !x[4].isEmpty() ? x[4].split(",") : null;
            fn = new Callable() {
                public Object call(Interp in, List<Object> args, int line) {
                    List<Object> a = check(name, ps, args, line);
                    return callNative(name, str, op, idx, sub, keys, ret, ps, a, line);
                }
            };
        } else if (impl.startsWith("java:")) {
            final String j = impl.substring(5);
            fn = new Callable() {
                public Object call(Interp in, List<Object> args, int line) {
                    return java(j, in, check(name, ps, args, line), line);
                }
            };
        } else {
            final String s = impl.substring(7);
            fn = new Callable() {
                public Object call(Interp in, List<Object> args, int line) {
                    Object f = system.globals.vars.get(s);
                    if (!(f instanceof Callable)) throw new ScriptError(name + "(): нет в prelude.mcs", line);
                    return ((Callable) f).call(in, check(name, ps, args, line), line);
                }
            };
        }
        int dot = name.indexOf('.');
        if (dot < 0) { builtins.vars.put(name, fn); return; }
        String g = name.substring(0, dot);
        Ns ns = groups.get(g);
        if (ns == null) {
            ns = new Ns(g);
            groups.put(g, ns);
            builtins.vars.put(g, ns);
        }
        ns.members.put(name.substring(dot + 1), fn);
    }

    private static List<Object> check(String name, Param[] ps, List<Object> args, int line) {
        if (args.size() > ps.length)
            throw new ScriptError(name + "(): лишние аргументы (нужно " + ps.length + ")", line);
        List<Object> a = new ArrayList<Object>(ps.length);
        for (int i = 0; i < ps.length; i++) {
            Object v;
            if (i < args.size()) v = args.get(i);
            else if (ps[i].hasDef) v = ps[i].def;
            else throw new ScriptError(name + "(): не хватает аргумента '" + ps[i].name + "'", line);
            String t = ps[i].type;
            if (t.equals("num") && !(v instanceof Double))
                throw new ScriptError(name + "(): '" + ps[i].name + "' должно быть числом, а не " + Interp.typeName(v), line);
            if (t.equals("str") && !(v instanceof String)) v = Interp.str(v);
            a.add(v);
        }
        return a;
    }

    /** native call: one number per parameter (a sub-operation first, if any), strings joined by \u001f */
    private Object callNative(String name, boolean strRet, int op, int idx, int sub, String[] keys, String ret,
                              Param[] ps, List<Object> a, int line) {
        int off = sub >= 0 ? 1 : 0;
        double[] nums = new double[ps.length + off];
        if (sub >= 0) nums[0] = sub;
        StringBuilder strs = new StringBuilder();
        int ns = 0;
        for (int i = 0; i < ps.length; i++) {
            Object v = a.get(i);
            String t = ps[i].type;
            if (t.equals("block") || t.equals("item")) nums[off + i] = resolveId(v, t, line);
            else if (v instanceof Double) nums[off + i] = (Double) v;
            else if (v instanceof Boolean) nums[off + i] = (Boolean) v ? 1 : 0;
            else if (v == null) nums[off + i] = -1;
            else {
                if (ns++ > 0) strs.append('\u001f');
                strs.append(Interp.str(v));
            }
        }
        if (strRet) {
            String s = host.nativeApiStr(op, nums, strs.toString());
            if (s == null && !inWorld) throw new ScriptError(name + "(): нужно находиться в мире", line);
            return s;
        }
        if (op == ApiOps.ITEM_ID && customIds.containsKey(strs.toString().trim().toLowerCase()))
            return customIds.get(strs.toString().trim().toLowerCase());
        double[] r = host.nativeApi(op, nums, strs.toString());
        if (r == null) throw new ScriptError(name + "(): нужно находиться в мире", line);
        if (idx >= 0) {
            if (idx >= r.length) return null;
            if (ret.equals("bool")) return r[idx] != 0;
            if (ret.equals("id")) return r[idx] < 0 ? null : (Object) r[idx];
            return r[idx];
        }
        if (ret.equals("-")) return null;
        if (r.length == 0) return null;                       // entity / block entity not found
        if (ret.equals("bool")) return r[0] != 0;
        if (ret.equals("id")) return r[0] < 0 ? null : (Object) r[0];
        if (keys != null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            for (int k = 0; k < keys.length; k++) m.put(keys[k], k < r.length ? r[k] : 0.0);
            return m;
        }
        if (ret.equals("vec")) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("x", r.length > 0 ? r[0] : 0.0);
            m.put("y", r.length > 1 ? r[1] : 0.0);
            m.put("z", r.length > 2 ? r[2] : 0.0);
            return m;
        }
        return r.length > 0 ? r[0] : null;
    }

    private double resolveId(Object v, String t, int line) {
        if (v instanceof Double) return (Double) v;
        String s = Interp.str(v).trim().toLowerCase();
        Double own = customIds.get(s);
        if (own != null) return own;
        if (s.startsWith("minecraft:")) s = s.substring(10);
        if (s.equals("air")) return 0;
        Double c = idCache.get(s);
        if (c == null) {
            double[] r = host.nativeApi(ApiOps.ITEM_ID, new double[0], s);
            c = r == null || r.length == 0 ? -1.0 : r[0];
            if (c >= 0) idCache.put(s, c);
        }
        if (c < 0) throw new ScriptError("неизвестный " + (t.equals("block") ? "блок" : "предмет") + " '" + s + "'", line);
        return c;
    }

    // ------------------------------------------------------------------ java: functions
    private Object java(String j, Interp in, List<Object> a, int line) {
        switch (j) {
            case "print": {
                String s = Interp.str(a.get(0));
                host.log("[" + in.modId + "] " + s);
                chat("§7[" + in.modId + "]§r " + s);
                return null;
            }
            case "str": return Interp.str(a.get(0));
            case "num": {
                Object v = a.get(0);
                if (v instanceof Double) return v;
                try { return Double.parseDouble(Interp.str(v).trim()); } catch (Exception e) { return null; }
            }
            case "int": { double d = (Double) a.get(0); return d < 0 ? Math.ceil(d) : Math.floor(d); }
            case "len": {
                Object v = a.get(0);
                if (v instanceof String) return (double) ((String) v).length();
                if (v instanceof List) return (double) ((List<?>) v).size();
                if (v instanceof Map) return (double) ((Map<?, ?>) v).size();
                throw new ScriptError("len(): у " + Interp.typeName(v) + " нет длины", line);
            }
            case "type": {
                Object v = a.get(0);
                return v == null ? "null" : v instanceof Double ? "number" : v instanceof String ? "string"
                        : v instanceof Boolean ? "bool" : v instanceof List ? "list" : v instanceof Callable ? "fn" : "map";
            }
            case "random": {
                if (a.get(0) == null) return Math.random();
                double x = Interp.num(a.get(0), line), y = a.get(1) == null ? 0 : Interp.num(a.get(1), line);
                if (a.get(1) == null) { y = x; x = 0; }
                if (x > y) { double t = x; x = y; y = t; }
                if (x == Math.rint(x) && y == Math.rint(y)) return x + Math.floor(Math.random() * (y - x + 1));
                return x + Math.random() * (y - x);
            }
            case "math.floor": return Math.floor((Double) a.get(0));
            case "math.ceil": return Math.ceil((Double) a.get(0));
            case "math.round": return (double) Math.round((Double) a.get(0));
            case "math.abs": return Math.abs((Double) a.get(0));
            case "math.min": return Math.min((Double) a.get(0), (Double) a.get(1));
            case "math.max": return Math.max((Double) a.get(0), (Double) a.get(1));
            case "math.clamp": return Math.max((Double) a.get(1), Math.min((Double) a.get(2), (Double) a.get(0)));
            case "math.sqrt": return Math.sqrt((Double) a.get(0));
            case "math.sin": return Math.sin(Math.toRadians((Double) a.get(0)));
            case "math.cos": return Math.cos(Math.toRadians((Double) a.get(0)));
            case "math.pi": return Math.PI;
            case "math.dist": {
                double dx = (Double) a.get(0) - (Double) a.get(3), dy = (Double) a.get(1) - (Double) a.get(4), dz = (Double) a.get(2) - (Double) a.get(5);
                return Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            case "world.entities": return new ArrayList<Object>(entities);
            case "ui.toast": host.toast(Interp.str(a.get(0)), Interp.str(a.get(1))); return null;
            case "data.save": {
                Mod m = modOf(in);
                data(m).put(Interp.str(a.get(0)), Json.copy(a.get(1), line));
                host.saveData(m.id, Json.write(m.data));
                return null;
            }
            case "data.load": {
                Object v = data(modOf(in)).get(Interp.str(a.get(0)));
                return v == null ? a.get(1) : Json.copy(v, line);
            }
            case "data.remove": {
                Mod m = modOf(in);
                if (data(m).remove(Interp.str(a.get(0))) != null) host.saveData(m.id, Json.write(m.data));
                return null;
            }
            default: throw new ScriptError("функция " + j + " не реализована", line);
        }
    }

    private Mod modOf(Interp in) {
        for (Mod m : mods) if (m.in == in) return m;
        throw new ScriptError("data.*: только внутри мода", 0);
    }

    private Map<String, Object> data(Mod m) {
        if (m.data == null) {
            Object d = null;
            try { String s = host.loadData(m.id); if (s != null) d = Json.read(s); } catch (Exception e) { host.log("data " + m.id + ": " + e); }
            m.data = d instanceof Map ? castMap(d) : new LinkedHashMap<String, Object>();
        }
        return m.data;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }

    void chat(String s) {
        try { host.nativeApi(ApiOps.CHAT, new double[0], s); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ mods
    /** parse a mod; errors are shown when the player enters a world */
    public Mod load(String id, String name, String file, String source) {
        Program prog = null;
        ScriptError err = null;
        try { prog = Parser.parse(source); } catch (ScriptError e) { err = e; e.file = file; }
        Mod m = new Mod(id, name, file, new Interp(id, file, builtins), prog == null ? 0 : prog.every.size());
        m.prog = prog;
        m.parseError = err;
        mods.add(m);
        if (err != null) host.log("mod " + id + ": " + err.where() + ": " + err.getMessage());
        return m;
    }

    public List<Mod> mods() { return mods; }

    private void report(Mod m, ScriptError e) {
        if (e.file == null) e.file = m.file;
        host.log("mod " + m.id + ": " + e.where() + ": " + e.getMessage());
        if (++m.errors > 6) return;
        String msg = "§c[" + m.name + "] " + e.where() + ": " + e.getMessage();
        if (m.errors == 6) msg = "§c[" + m.name + "] слишком много ошибок - мод остановлен до следующего входа в мир";
        if (inWorld) chat(msg);
        else pendingReports.add(msg);
    }

    private void joinWorld() {
        inWorld = true;
        tick = 0;
        for (String s : pendingReports) chat(s);
        pendingReports.clear();
        // overrides are global: rebuild them from the table each time a world starts
        for (String[] f : ApiTable.FUNCS) bind(f);
        groups.get("chat").callDefault = "send";
        for (Mod m : mods) {
            m.errors = 0;
            if (m.parseError != null) { report(m, m.parseError); continue; }
            m.in.globals.vars.clear();
            for (Decl d : m.prog.decls) {
                Double id = customIds.get(m.id + ":" + d.name);
                if (d.kind.equals("block") && id != null) { m.in.globals.vars.put(d.name, id); continue; }
                m.in.globals.vars.put(d.name, m.id + ":" + d.name);
                if (!d.kind.equals("block"))
                    report(m, new ScriptError("'" + d.kind + "' появится в следующих версиях", d.line));
            }
            for (OverrideDecl o : m.prog.overrides) {
                Ns ns = groups.get(o.ns);
                if (ns == null || !ns.members.containsKey(o.name)) {
                    report(m, new ScriptError("override: нет функции " + o.ns + "." + o.name, o.fn.line));
                    continue;
                }
                Scope sc = new Scope(m.in.globals);
                sc.vars.put("original", ns.members.get(o.name));
                ns.members.put(o.name, new Interp.Fn(o.fn, sc, m.in));
            }
            final Mod mm = m;
            run(m, new Runnable() {
                public void run() { for (Node n : mm.prog.init) n.eval(mm.in, mm.in.globals); }
            }, null);
            for (int i = 0; i < m.everyNext.length; i++) m.everyNext[i] = 0;
        }
    }

    private boolean run(Mod m, Runnable r, Object unused) {
        if (m.errors >= 6) return false;          // stopped until the next world
        m.in.resetSteps();
        try {
            r.run();
            return true;
        } catch (ScriptError e) {
            report(m, e);
        } catch (Ast.Jumping j) {
            report(m, new ScriptError("break/continue/return не на своём месте", 0));
        } catch (StackOverflowError e) {
            report(m, new ScriptError("слишком глубокая рекурсия", 0));
        } catch (RuntimeException e) {
            report(m, new ScriptError("внутренняя ошибка: " + e, 0));
        }
        return false;
    }

    /** from libnova: returns 1 when a handler called e.cancel() */
    public int event(int ev, String text, double[] a) {
        if (ev < 1 || ev > ApiTable.EVENTS.length) return 0;
        String name = ApiTable.EVENTS[ev - 1][0];
        if (name.equals("world.join")) joinWorld();
        if (name.equals("entity.add") && a != null && a.length > 0) entities.add(a[0]);
        if (name.equals("entity.remove") && a != null && a.length > 0) entities.remove(a[0]);
        if (!inWorld) return 0;
        boolean tickEv = name.equals("tick");
        if (tickEv) tick++;
        final boolean[] cancel = {false};
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        String fields = ApiTable.EVENTS[ev - 1][1];
        if (!fields.equals("-")) {
            String[] fs = fields.split(",");
            String[] texts = text == null ? new String[0] : text.split("\u001f", -1);
            int k = 0, t = 0;
            for (String f : fs) {
                f = f.trim();
                if (f.startsWith("$")) e.put(f.substring(1), t < texts.length ? texts[t++] : "");
                else e.put(f, a != null && k < a.length ? a[k++] : 0.0);
            }
        }

        e.put("name", name);
        if (ApiTable.EVENTS[ev - 1][2].equals("yes")) {
            e.put("cancel", new Callable() {
                public Object call(Interp in, List<Object> args, int line) { cancel[0] = true; return null; }
            });
        }
        for (final Mod m : mods) {
            if (m.prog == null) continue;
            for (final OnEvent h : m.prog.events) {
                if (!h.event.equals(name)) continue;
                final Map<String, Object> ee = e;
                run(m, new Runnable() {
                    public void run() {
                        Scope s = new Scope(m.in.globals);
                        s.vars.put(h.param, ee);
                        try { h.body.eval(m.in, s); } catch (Ast.Return r) { /* return in a handler ends it */ }
                    }
                }, null);
            }
            if (tickEv) {
                for (int i = 0; i < m.prog.every.size(); i++) {
                    final Every ev2 = m.prog.every.get(i);
                    if (tick < m.everyNext[i]) continue;
                    final long[] period = {20};
                    final int fi = i;
                    boolean ok = run(m, new Runnable() {
                        public void run() {
                            double n = Interp.num(ev2.interval.eval(m.in, m.in.globals), ev2.line);
                            period[0] = Math.max(1, Math.round(ev2.seconds ? n * 20 : n));
                            try { ev2.body.eval(m.in, m.in.globals); } catch (Ast.Return r) { /* ends */ }
                        }
                    }, null);
                    m.everyNext[fi] = tick + (ok ? period[0] : 100);
                }
            }
        }
        if (name.equals("world.leave")) { inWorld = false; entities.clear(); }
        return cancel[0] ? 1 : 0;
    }
}

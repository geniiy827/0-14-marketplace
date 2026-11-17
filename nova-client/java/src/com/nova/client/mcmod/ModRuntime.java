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
        Machine machine();                                          // hooks / calls / memory, null in tests
        /** live mods (games/com.mojang/nova_mods/*.mcs) changed since the last scan: {id, file, source}; may be null */
        List<String[]> liveScan();
    }

    /** sounds of mods (ModSounds); null in desktop tests */
    public interface Sounds {
        boolean play(String name, double x, double y, double z, double volume, double pitch);   // false: not a mod sound
        void stop(String name);                                                                 // "" = all
        void listener(double x, double y, double z, double yaw);
        void leave();
    }
    public Sounds sounds;
    /** per mod mob type: sound names {hurt, death, step, ambient} (null = none) and volume */
    public final List<String[]> mobSounds = new ArrayList<String[]>();

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
    /** one script at a time: game thread events and hooks from any thread */
    final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
    private Hooks hooks;
    private Interp console;
    private final Map<String, Ns> groups = new LinkedHashMap<String, Ns>();
    private final List<Mod> mods = new ArrayList<Mod>();
    private final Map<String, Double> idCache = new HashMap<String, Double>();
    private final Interp system;                 // runs the prelude
    private long tick;
    private boolean inWorld;
    private final List<String> pendingReports = new ArrayList<String>();
    /** mod blocks: "modid:name" and plain "name" -> block id (set by ModBlocks) */
    public final Map<String, Double> customIds = new HashMap<String, Double>();
    /** mod mobs in libnova's order ("mod:name"), set by ModBridge */
    public final List<String> mobNames = new ArrayList<String>();
    /** numbers of items by data (variants, packed plain items): for scripts such an item is number + 65536 * data */
    public final java.util.Set<Integer> carriers = new java.util.HashSet<Integer>();
    /** block numbers shared by several plain mod blocks (one per data): world.block / events say number + 65536 * data */
    public final java.util.Set<Integer> blockCarriers = new java.util.HashSet<Integer>();
    /** what a script sees for the item (id, data) */
    public double itemCode(double id, double data) {
        return carriers.contains((int) id) ? id + 65536.0 * (((int) data) & 0x7fff) : id;
    }

    /** block declarations of every parsed mod: {Mod, name, props map, line} */
    public List<Object[]> blockDecls() { return decls("block"); }

    /** declarations of one kind ("block", "item") of every parsed mod: {Mod, name, props map, line} */
    public List<Object[]> decls(String kind) {
        List<Object[]> out = new ArrayList<Object[]>();
        for (Mod m : mods) {
            if (m.prog == null) continue;
            for (Decl d : m.prog.decls) {
                if (!d.kind.equals(kind)) continue;
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
        bindMachine();
        try {
            Program p = Parser.parse(host.prelude());
            for (Node n : p.init) n.eval(system, system.globals);
        } catch (ScriptError e) {
            e.file = "prelude.mcs";
            host.log("prelude: " + e.where() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ the game itself (mc.*, mem.*, hook)
    private void bindMachine() {
        Machine mach = host.machine();
        if (mach == null) return;
        if (hooks == null) hooks = new Hooks(this, mach);
        hooks.bind(builtins.vars);
        ((Ns) builtins.vars.get("mc")).members.put("hook", new Callable() {   // mc.hook("Class::f", fn(self, ..., original) {}, "float")
            public Object call(Interp in, List<Object> args, int line) {
                if (args.size() < 2 || !(args.get(0) instanceof String) || !(args.get(1) instanceof Callable))
                    throw new ScriptError("mc.hook(\"Класс::функция\", fn(...) { })", line);
                char ret = args.size() > 2 ? Interp.str(args.get(2)).charAt(0) : 0;
                String err = hooks.install(modOf(in), (String) args.get(0), (Callable) args.get(1), null, ret, line);
                if (err != null) throw new ScriptError(err, line);
                return null;
            }
        });
        console = new Interp("console", "консоль", builtins);
    }

    /** libnova -> a hooked function was called */
    public long onHook(int idx, int[] words) {
        if (hooks == null) return host.machine().callOrig(idx, words);
        return hooks.onHook(idx, words);
    }

    /** a hook body (called by Hooks with the lock held) */
    boolean runHook(Mod m, Runnable r) { return run(m, r, null); }

    private void installHooks(Mod m) {
        if (hooks == null || m.prog == null) return;
        hooks.removeMod(m);
        for (Ast.HookDecl h : m.prog.hooks) {
            Scope hs = new Scope(m.in.globals);
            try {
                String err = hooks.install(m, h.target, new Interp.Fn(h.fn, hs, m.in), hs, h.ret, h.fn.line);
                if (err != null) report(m, new ScriptError(err, h.fn.line));
            } catch (ScriptError e) {
                report(m, e);
            }
        }
    }

    /** chat line "> code": run it in the console (variables stay between lines), print the result */
    private void consoleEval(String src) {
        if (console == null) { chat("§cконсоль недоступна"); return; }
        console.resetSteps();
        try {
            Program p = Parser.parse(src);
            Object last = null;
            for (Node n : p.init) last = n instanceof Ast.ExprStmt ? ((Ast.ExprStmt) n).e.eval(console, console.globals) : n.eval(console, console.globals);
            if (last != null) chat("§7= " + Interp.str(last));
        } catch (ScriptError e) {
            chat("§c" + e.getMessage());
        } catch (Ast.Jumping j) {
            chat("§cbreak/continue/return вне цикла");
        } catch (RuntimeException e) {
            chat("§c" + e);
        }
    }

    /** live reload of a mod file: replace its program, unhook the old hooks, start it again if in a world */
    private void reloadLive(String id, String file, String src) {
        Mod old = null;
        for (Mod m : mods) if (m.id.equals(id)) old = m;
        if (old != null) {
            if (hooks != null) hooks.removeMod(old);
            mods.remove(old);
        }
        Mod m = load(id, id, file, src);
        if (inWorld) {
            if (m.parseError != null) report(m, m.parseError);
            else { startMod(m); chat("§a[" + file + "] перезагружен"); }
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
                    Object r = callNative(name, str, op, idx, sub, keys, ret, ps, a, line);
                    if (r instanceof Double && !carriers.isEmpty() && name.equals("player.item") && carriers.contains((int) (double) (Double) r)) {
                        double[] st = host.nativeApi(op, new double[0], "");            // PLAYER_STATE: [.., item, itemData]
                        if (st != null && st.length > 3) return itemCode((Double) r, st[3]);
                    }
                    if (r instanceof Double && !blockCarriers.isEmpty() && name.equals("world.block") && blockCarriers.contains((int) (double) (Double) r)) {
                        double[] nums = new double[3];                                  // WORLD_BLOCK: [id, data]
                        for (int i = 0; i < 3; i++) nums[i] = Interp.num(a.get(i), line);
                        double[] st = host.nativeApi(op, nums, "");
                        if (st != null && st.length > 1) return (Double) r + 65536.0 * (((int) st[1]) & 15);
                    }
                    return r;
                }
            };
        } else if (impl.startsWith("java:")) {
            final String j = impl.substring(5);
            final boolean lib = j.equals("Lib");
            fn = new Callable() {
                public Object call(Interp in, List<Object> args, int line) {
                    if (lib) return Lib.call(name, check(name, ps, args, line), line);
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
        if (ret.equals("list")) {
            List<Object> l = new ArrayList<Object>();
            for (double v : r) l.add(v);
            return l;
        }
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

    /** a block for declarations ("planks", "minecraft:log", a mod's block, a number); -1 if unknown */
    public double blockId(Object v) {
        try { return resolveId(v, "block", 0); } catch (ScriptError e) { return -1; }
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
            // ---- host <-> players (libnova modnet.inc: nativeApiStr 1000 + op) ----
            case "host.isMe": return "1".equals(host.nativeApiStr(1001, null, ""));
            case "host.send":
                return host.nativeApiStr(1002, null, netBody(modOf(in), a.get(0), a.get(1), line)) != null;
            case "players.send":
                return host.nativeApiStr(1003, null, netName(a.get(0)) + "\u001f" + netBody(modOf(in), a.get(1), a.get(2), line)) != null;
            case "players.sendAll":
                return host.nativeApiStr(1003, null, "*\u001f" + netBody(modOf(in), a.get(0), a.get(1), line)) != null;
            case "players.list": {
                String r = host.nativeApiStr(1004, null, "");
                List<Object> l = new ArrayList<Object>();
                if (r != null && !r.isEmpty()) for (String n : r.split("\u001f")) l.add(n);
                return l;
            }
            case "players.hurt":
                return host.nativeApiStr(1005, new double[]{Interp.num(a.get(1), line), Interp.num(a.get(2), line)}, netName(a.get(0))) != null;
            case "players.heal":
                return host.nativeApiStr(1006, new double[]{Interp.num(a.get(1), line)}, netName(a.get(0))) != null;
            case "players.teleport":
                return host.nativeApiStr(1007, new double[]{Interp.num(a.get(1), line), Interp.num(a.get(2), line), Interp.num(a.get(3), line)}, netName(a.get(0))) != null;
            case "players.give": {
                int[] ia = idAux(a.get(1), line);
                return host.nativeApiStr(1008, new double[]{ia[0], Interp.num(a.get(2), line), ia[1]}, netName(a.get(0))) != null;
            }
            case "player.name": { String r = host.nativeApiStr(1011, null, ""); return r == null ? "" : r; }
            case "player.give": {                           // the host's own player (single player too)
                String me = host.nativeApiStr(1011, null, "");
                if (me == null || !"1".equals(host.nativeApiStr(1001, null, ""))) return false;
                int[] ia = idAux(a.get(0), line);
                return host.nativeApiStr(1008, new double[]{ia[0], Interp.num(a.get(1), line), ia[1]}, me) != null;
            }
            case "players.pos": case "players.health": {
                String r = host.nativeApiStr(1009, null, netName(a.get(0)));
                boolean pos = j.equals("players.pos");
                if (r == null) return pos ? null : (Object) (-1.0);
                String[] q = r.split("\u001f");
                if (q.length < 4) return pos ? null : (Object) (-1.0);
                if (!pos) return Double.parseDouble(q[3]);
                Map<String, Object> v = new LinkedHashMap<String, Object>();
                v.put("x", Double.parseDouble(q[0]) / 100); v.put("y", Double.parseDouble(q[1]) / 100); v.put("z", Double.parseDouble(q[2]) / 100);
                return v;
            }
            case "players.tell":
                return host.nativeApiStr(1010, null, netName(a.get(0)) + "\u001f" + Interp.str(a.get(1)).replace('\u001f', ' ')) != null;
            case "recipe.shaped": {              // -> native recipes.inc (put back after the game refills its book)
                int[] res = idAux(a.get(0), line);
                StringBuilder sb = new StringBuilder();
                Object rows = a.get(1);
                if (!(rows instanceof List) || ((List<?>) rows).isEmpty() || ((List<?>) rows).size() > 3)
                    throw new ScriptError("recipe.shaped: rows - список из 1..3 строк", line);
                for (int i = 0; i < 3; i++) {
                    String r = i < ((List<?>) rows).size() ? Interp.str(((List<?>) rows).get(i)) : "";
                    if (r.length() > 3) throw new ScriptError("recipe.shaped: строка длиннее 3", line);
                    sb.append(r.replace('|', ' ')).append('|');
                }
                if (!(a.get(2) instanceof Map)) throw new ScriptError("recipe.shaped: keys - {A: номер, ...}", line);
                for (Map.Entry<?, ?> e : ((Map<?, ?>) a.get(2)).entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (k.length() != 1) throw new ScriptError("recipe.shaped: ключ - одна буква", line);
                    int[] ia = idAux(e.getValue(), line);
                    sb.append(k).append(':').append(ia[0]).append(':').append(ia[1]).append('|');
                }
                double cnt = a.get(3) instanceof Double ? (Double) a.get(3) : 1;
                host.nativeApi(ApiOps.RECIPE, new double[]{res[0], cnt, res[1]}, modOf(in).id + "\u001d" + sb.toString());
                return null;
            }
            case "recipe.smelt": {
                int[] in2 = idAux(a.get(0), line), out = idAux(a.get(1), line);
                double cnt = a.get(2) instanceof Double ? (Double) a.get(2) : 1;
                host.nativeApi(ApiOps.SMELT, new double[]{in2[0], in2[1], out[0], cnt, out[1]}, modOf(in).id + "\u001d");
                return null;
            }
            case "ui.hud": {                     // icons of the mod -> nova/mod/<id>/<file>, drawn by libnova (hud.inc)
                Mod m = modOf(in);
                StringBuilder sb = new StringBuilder(Interp.str(a.get(0)));
                Object list = a.get(1);
                if (list instanceof List) {
                    int k = 0;
                    for (Object o : (List<?>) list) {
                        if (k++ >= 20) break;
                        String f = o == null ? "" : Interp.str(o);
                        sb.append('|').append(f.length() == 0 ? "" : f.indexOf('/') < 0 && !f.endsWith(".png") ? f : "nova/mod/" + m.id + "/" + f);
                    }
                }
                double side = "left".equals(Interp.str(a.get(3))) ? 1 : 0;
                host.nativeApi(ApiOps.HUD, new double[]{a.get(2) instanceof Double ? (Double) a.get(2) : 0, side, a.size() > 4 && a.get(4) instanceof Double ? (Double) a.get(4) : 0}, sb.toString());
                return null;
            }
            case "ui.image": {                   // one picture of the mod -> nova/mod/<id>/<file>, drawn by libnova (hud.inc)
                Mod m = modOf(in);
                String f = a.get(1) == null ? "" : Interp.str(a.get(1));
                String pic = f.length() == 0 ? "" : f.indexOf('/') < 0 && !f.endsWith(".png") ? f : "nova/mod/" + m.id + "/" + f;
                double anchor = "bottom".equals(Interp.str(a.get(5))) ? 1 : 0;
                host.nativeApi(ApiOps.HUD_IMAGE, new double[]{Interp.num(a.get(2), line), Interp.num(a.get(3), line),
                        Interp.num(a.get(4), line), anchor}, Interp.str(a.get(0)).replace('|', ' ') + "|" + pic);
                return null;
            }
            case "world.find": {                 // -> native env.inc: positions of blocks in a box (up to 16)
                Object ids = a.get(6);
                if (!(ids instanceof List) || ((List<?>) ids).isEmpty() || ((List<?>) ids).size() > 10)
                    throw new ScriptError("world.find: ids - список из 1..10 блоков", line);
                List<?> il = (List<?>) ids;
                double[] nums = new double[6 + il.size()];
                for (int i = 0; i < 6; i++) nums[i] = Interp.num(a.get(i), line);
                for (int i = 0; i < il.size(); i++) nums[6 + i] = resolveId(il.get(i), "block", line);
                double[] r = host.nativeApi(ApiOps.WORLD_FIND, nums, "");
                List<Object> out = new ArrayList<Object>();
                if (r != null) for (int i = 0; i + 3 < r.length; i += 4) {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("x", r[i]); m.put("y", r[i + 1]); m.put("z", r[i + 2]); m.put("block", il.get((int) r[i + 3]));
                    out.add(m);
                }
                return out;
            }
            case "world.count": {                // -> native env.inc: block counts in a box, one per id
                Object ids = a.get(6);
                if (!(ids instanceof List) || ((List<?>) ids).isEmpty() || ((List<?>) ids).size() > 10)
                    throw new ScriptError("world.count: ids - список из 1..10 блоков", line);
                List<?> il = (List<?>) ids;
                double[] nums = new double[6 + il.size()];
                for (int i = 0; i < 6; i++) nums[i] = Interp.num(a.get(i), line);
                for (int i = 0; i < il.size(); i++) nums[6 + i] = resolveId(il.get(i), "block", line);
                double[] r = host.nativeApi(ApiOps.WORLD_COUNT, nums, "");
                if (r == null) throw new ScriptError("world.count(): нужно находиться в мире", line);
                List<Object> out = new ArrayList<Object>();
                for (int i = 0; i < il.size(); i++) out.add(i < r.length ? r[i] : 0.0);
                return out;
            }
            case "world.particle": {             // -> env.inc: Level::addParticle (ParticleType of 0.14: torch = smoke 3 + flame 6)
                Object t = a.get(0);
                double type;
                if (t instanceof Double) type = (Double) t;
                else {
                    String tn = Interp.str(t);
                    if (tn.equals("smoke")) type = 3; else if (tn.equals("cloud")) type = 4; else if (tn.equals("largesmoke")) type = 5;
                    else if (tn.equals("flame")) type = 6; else if (tn.equals("lava")) type = 7;
                    else throw new ScriptError("world.particle: \"smoke\", \"largesmoke\", \"flame\", \"lava\", \"cloud\" или номер", line);
                }
                double[] nums = new double[8];
                nums[0] = type;
                for (int i = 1; i < 8; i++) nums[i] = Interp.num(a.get(i), line);
                host.nativeApi(ApiOps.WORLD_PARTICLE, nums, "");
                return null;
            }
            case "world.sound": {
                double[] nums = new double[5];
                for (int i = 0; i < 5; i++) nums[i] = Interp.num(a.get(i + 1), line);
                String sn = Interp.str(a.get(0));
                if (sounds != null && sounds.play(sn, nums[0], nums[1], nums[2], nums[3], nums[4])) {
                    if (isHost()) novaSend("snd", java.util.Arrays.asList((Object) sn, nums[0], nums[1], nums[2], nums[3], nums[4]));
                    return null;
                }
                host.nativeApi(ApiOps.WORLD_SOUND, nums, sn);
                return null;
            }
            case "world.stopSound": {
                String sn = a.isEmpty() || a.get(0) == null ? "" : Interp.str(a.get(0));
                if (sounds != null) sounds.stop(sn);
                if (isHost()) novaSend("stop", sn);
                return null;
            }
            case "players.id": {
                String r = host.nativeApiStr(1013, null, netName(a.get(0)));
                try { return r == null ? null : (Object) Double.valueOf(r); } catch (NumberFormatException bad) { return null; }
            }
            case "players.view": {
                String who = netName(a.get(0));
                double[] v = views.get(who);
                if (who.equals(host.nativeApiStr(1011, null, ""))) v = localView();
                if (v == null) return null;
                Map<String, Object> r = new LinkedHashMap<String, Object>();
                r.put("x", v[0]); r.put("y", v[1]); r.put("z", v[2]); r.put("lx", v[3]); r.put("ly", v[4]); r.put("lz", v[5]);
                return r;
            }
            case "entity.target": {
                double[] r = host.nativeApi(ApiOps.MOB_TARGET, new double[]{Interp.num(a.get(0), line)}, "");
                return r == null || r.length == 0 ? null : (Object) r[0];
            }
            case "entity.setTarget":
                host.nativeApi(ApiOps.MOB_SET_TARGET, new double[]{Interp.num(a.get(0), line),
                        a.get(1) == null ? 0 : Interp.num(a.get(1), line)}, "");
                return null;
            case "entity.moveTo": {
                double[] r = host.nativeApi(ApiOps.MOB_MOVE, new double[]{Interp.num(a.get(0), line), Interp.num(a.get(1), line),
                        Interp.num(a.get(2), line), Interp.num(a.get(3), line), Interp.num(a.get(4), line)}, "");
                return r != null && r.length > 0 && r[0] != 0;
            }
            case "entity.follow": {
                double[] r = host.nativeApi(ApiOps.MOB_FOLLOW, new double[]{Interp.num(a.get(0), line), Interp.num(a.get(1), line),
                        Interp.num(a.get(2), line)}, "");
                return r != null && r.length > 0 && r[0] != 0;
            }
            case "entity.stop":
                host.nativeApi(ApiOps.MOB_STOP, new double[]{Interp.num(a.get(0), line)}, "");
                return null;
            case "entity.lookAt":
                host.nativeApi(ApiOps.MOB_LOOK, new double[]{Interp.num(a.get(0), line), Interp.num(a.get(1), line),
                        Interp.num(a.get(2), line), Interp.num(a.get(3), line)}, "");
                return null;
            case "entity.attack": {
                double[] r = host.nativeApi(ApiOps.MOB_ATTACK, new double[]{Interp.num(a.get(0), line), Interp.num(a.get(1), line)}, "");
                return r != null && r.length > 0 && r[0] != 0;
            }
            case "entity.state": {
                double[] r = host.nativeApi(ApiOps.MOB_STATE, new double[]{Interp.num(a.get(0), line)}, "");
                if (r == null || r.length < 9) return null;
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                String[] k = {"x", "y", "z", "speed", "health", "yaw", "wall", "water", "vy"};
                for (int i = 0; i < k.length; i++) m.put(k[i], r[i]);
                return m;
            }
            case "entity.setSpeed":
                host.nativeApi(ApiOps.MOB_SPEED, new double[]{Interp.num(a.get(0), line), Interp.num(a.get(1), line)}, "");
                return null;
            case "entity.teleport":
                host.nativeApi(ApiOps.MOB_TP, new double[]{Interp.num(a.get(0), line), Interp.num(a.get(1), line),
                        Interp.num(a.get(2), line), Interp.num(a.get(3), line)}, "");
                return null;
            case "entity.spawn": {
                String nm = Interp.str(a.get(0));
                if (nm.indexOf(':') < 0) nm = modOf(in).id + ":" + nm;
                if (!mobNames.contains(nm)) throw new ScriptError("нет моба " + nm + " (объяви entity " + nm.substring(nm.indexOf(':') + 1) + " { })", line);
                double[] r = host.nativeApi(ApiOps.MOB_SPAWN, new double[]{Interp.num(a.get(1), line), Interp.num(a.get(2), line),
                        Interp.num(a.get(3), line)}, nm);
                if (r == null || r.length == 0) return null;
                syncMob(r[0], nm, null, 0);
                return (Object) r[0];
            }
            case "entity.kind": {
                double[] r = host.nativeApi(ApiOps.MOB_INFO, new double[]{Interp.num(a.get(0), line)}, "");
                if (r == null || r.length == 0) return null;
                int t = (int) r[0];
                return t >= 0 && t < mobNames.size() ? mobNames.get(t) : null;
            }
            case "entity.set": {
                double ev = Interp.num(a.get(0), line), val = a.get(2) instanceof Boolean ? ((Boolean) a.get(2) ? 1 : 0) : Interp.num(a.get(2), line);
                String key = Interp.str(a.get(1));
                host.nativeApi(ApiOps.MOB_SET, new double[]{ev, val}, key);
                syncMob(ev, null, key, val);
                return null;
            }
            case "entity.get": {
                double[] r = host.nativeApi(ApiOps.MOB_GET, new double[]{Interp.num(a.get(0), line)}, Interp.str(a.get(1)));
                return r == null || r.length == 0 ? (Object) 0.0 : (Object) r[0];
            }
            case "players.fire":
                return host.nativeApiStr(1012, new double[]{Interp.num(a.get(1), line)}, netName(a.get(0))) != null;
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

    Mod modOf(Interp in) {
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
            if (m.parseError != null) { m.errors = 0; report(m, m.parseError); continue; }
            startMod(m);
        }
    }

    private void startMod(Mod m) {
        {
            m.errors = 0;
            m.in.globals.vars.clear();
            for (Decl d : m.prog.decls) {
                Double id = customIds.get(m.id + ":" + d.name);
                if ((d.kind.equals("block") || d.kind.equals("item")) && id != null) { m.in.globals.vars.put(d.name, id); continue; }
                m.in.globals.vars.put(d.name, m.id + ":" + d.name);
                if (!d.kind.equals("block") && !d.kind.equals("item") && !d.kind.equals("dimension") && !d.kind.equals("entity"))
                    report(m, new ScriptError("'" + d.kind + "' появится в следующих версиях", d.line));
            }
            String pre = m.id + ":";                      // names of items by data (variants: [{name: ..}])
            for (Map.Entry<String, Double> e : customIds.entrySet())
                if (e.getKey().startsWith(pre)) {
                    String n = e.getKey().substring(pre.length());
                    if (!m.in.globals.vars.containsKey(n)) m.in.globals.vars.put(n, e.getValue());
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
            installHooks(m);
            for (int i = 0; i < m.everyNext.length; i++) m.everyNext[i] = 0;
        }
    }

    boolean run(Mod m, Runnable r, Object unused) {
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
        lock.lock();
        try { return event0(ev, text, a); } finally { lock.unlock(); }
    }

    private long lastLive;

    private int event0(int ev, String text, double[] a) {
        if (ev < 1 || ev > ApiTable.EVENTS.length) return 0;
        String name = ApiTable.EVENTS[ev - 1][0];
        if (name.equals("world.join")) joinWorld();
        if (name.equals("entity.add") && a != null && a.length > 0) entities.add(a[0]);
        if (name.equals("entity.remove") && a != null && a.length > 0) entities.remove(a[0]);
        if (!inWorld) return 0;
        boolean tickEv = name.equals("tick");
        if (tickEv) tick++;
        if (name.equals("chat") && text != null && text.startsWith(">")) { consoleEval(text.substring(1)); return 1; }
        if (tickEv && tick - lastLive >= 20) {
            lastLive = tick;
            List<String[]> ch = null;
            try { ch = host.liveScan(); } catch (Throwable t) { host.log("live: " + t); }
            if (ch != null) for (String[] c : ch) reloadLive(c[0], c[1], c[2]);
        }
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

        if (!blockCarriers.isEmpty() && e.get("block") instanceof Double && blockCarriers.contains((int) (double) (Double) e.get("block"))) {
            Object bd = e.get("data");                     // a packed block: e.block is the number scripts know it by
            int dv = bd instanceof Double ? ((int) (double) (Double) bd) & 15 : 0;
            e.put("block", (Double) e.get("block") + 65536.0 * dv);
        }
        if (!carriers.isEmpty() && e.get("item") instanceof Double && carriers.contains((int) (double) (Double) e.get("item"))) {
            Object dv = e.get("itemData");                  // items by data: e.item is the same number scripts know them by
            double data = dv instanceof Double ? (Double) dv : 0;
            if (dv == null) {
                double[] st = host.nativeApi(ApiOps.PLAYER_STATE, new double[0], "");
                if (st != null && st.length > 3) data = st[3];
            }
            e.put("item", itemCode((Double) e.get("item"), data));
        }
        e.put("name", name);
        if (ApiTable.EVENTS[ev - 1][2].equals("yes")) {
            e.put("cancel", new Callable() {
                public Object call(Interp in, List<Object> args, int line) { cancel[0] = true; return null; }
            });
        }
        String onlyMod = null;                          // host.receive / client.receive: for one mod, data is a value
        if (name.equals("host.receive") || name.equals("client.receive")) {
            onlyMod = String.valueOf(e.remove("mod"));
            Object d = e.get("data");
            try { e.put("data", d == null || "".equals(d) ? null : Json.read(String.valueOf(d))); }
            catch (Exception bad) { return 0; }
            if (onlyMod.equals("__nova")) { novaReceive(name, e); return 0; }
        }
        if (name.equals("__mobsound")) {               // libnova: hurt / death / step / ambient of a mod mob
            int t = (int) (double) (Double) e.get("type"), kind = (int) (double) (Double) e.get("kind");
            if (sounds != null && t >= 0 && t < mobSounds.size() && kind >= 0 && kind < 4 && mobSounds.get(t)[kind] != null)
                sounds.play(mobSounds.get(t)[kind], (Double) e.get("x"), (Double) e.get("y"), (Double) e.get("z"), 1, 1);
            return 0;
        }
        if (tickEv) novaTick();
        for (final Mod m : mods) {
            if (m.prog == null) continue;
            if (onlyMod != null && !onlyMod.equals(m.id)) continue;
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
        if (name.equals("world.leave")) {
            inWorld = false; entities.clear(); synced.clear(); views.clear(); hostCache = -1;
            if (sounds != null) sounds.leave();
        }
        return cancel[0] ? 1 : 0;
    }

    /** a recipe part: number, [number, data] or a name */
    private int[] idAux(Object v, int line) {
        Object id = v, aux = 0.0;
        if (v instanceof List && !((List<?>) v).isEmpty()) {
            id = ((List<?>) v).get(0);
            if (((List<?>) v).size() > 1) aux = ((List<?>) v).get(1);
        }
        int n;
        if (id instanceof Double) n = (int) (double) (Double) id;
        else {
            Double c = customIds.get(Interp.str(id).trim().toLowerCase());
            if (c == null) {
                double[] r = host.nativeApi(ApiOps.ITEM_ID, new double[0], Interp.str(id));
                c = r != null && r.length > 0 ? r[0] : -1;
            }
            n = (int) (double) c;
        }
        int a = aux instanceof Double ? (int) (double) (Double) aux : 0;
        if (n >= 65536) { a = n >> 16; n &= 0xffff; }          // an item by data: number + 65536 * data
        if (n <= 0 || n >= 512) throw new ScriptError("рецепт: нет предмета " + Interp.str(id), line);
        return new int[]{n, a};
    }

    /** a mod message on the wire: mod 0x1f channel 0x1f JSON (the receiver's mod with the same id gets it) */
    private String netBody(Mod m, Object channel, Object data, int line) {
        String ch = Interp.str(channel).replace('\u001f', ' ');
        String js = Json.write(Json.copy(data, line));
        if (js.length() > 4000) throw new ScriptError("сообщение слишком большое (" + js.length() + " > 4000 символов): дели на части", line);
        return m.id + "\u001f" + ch + "\u001f" + js;
    }
    private static String netName(Object player) { return Interp.str(player).replace('\u001f', ' '); }

    // ---------------------------------------------------------------- mod mobs and sounds across players
    // The host's mob of a mod is a PigZombie for everybody else: the host tells the players which entity is which
    // mob and its values (they drive the animations); sounds of mods played on the host reach the players too;
    // players tell the host where they look (players.view). Messages of the mod "__nova".
    private static final class MobSync { String type; final Map<String, Double> vals = new LinkedHashMap<String, Double>(); }
    private final Map<Double, MobSync> synced = new LinkedHashMap<Double, MobSync>();
    private final Map<String, double[]> views = new HashMap<String, double[]>();
    private int hostCache = -1;
    private long hostCacheTick = -100;

    private boolean isHost() {
        if (tick - hostCacheTick > 40 || hostCache < 0) {
            hostCache = "1".equals(host.nativeApiStr(1001, null, "")) ? 1 : 0;
            hostCacheTick = tick;
        }
        return hostCache == 1;
    }

    private void novaSend(String channel, Object data) {
        try {
            String js = Json.write(Json.copy(data, 0));
            host.nativeApiStr(1003, null, "*\u001f__nova\u001f" + channel + "\u001f" + js);
        } catch (Exception e) { host.log("nova sync: " + e); }
    }

    private void syncMob(double uid, String type, String key, double val) {
        if (!isHost()) return;
        MobSync m = synced.get(uid);
        if (m == null) {
            if (type == null) {
                double[] r = host.nativeApi(ApiOps.MOB_INFO, new double[]{uid}, "");
                int t = r == null || r.length == 0 ? -1 : (int) r[0];
                if (t < 0 || t >= mobNames.size()) return;
                type = mobNames.get(t);
            }
            m = new MobSync();
            synced.put(uid, m);
        }
        if (type != null) m.type = type;
        if (key != null) {
            Double old = m.vals.get(key);
            if (old != null && old == val) return;
            m.vals.put(key, val);
        }
        sendMob(uid, m);
    }

    private void sendMob(double uid, MobSync m) {
        List<Object> l = new ArrayList<Object>();
        l.add(uid); l.add(m.type);
        for (Map.Entry<String, Double> e : m.vals.entrySet()) { l.add(e.getKey()); l.add(e.getValue()); }
        novaSend("mob", l);
    }

    private double[] localView() {
        double[] p = host.nativeApi(ApiOps.PLAYER_POS, new double[0], "");
        if (p == null || p.length < 5) return null;
        double yaw = p[3], pitch = p[4];
        double c = Math.cos(Math.toRadians(pitch));
        return new double[]{p[0], p[1] + 1.62, p[2], -Math.sin(Math.toRadians(yaw)) * c, -Math.sin(Math.toRadians(pitch)),
                Math.cos(Math.toRadians(yaw)) * c, yaw};
    }

    /** every tick (in a world): sounds follow the player, the host re-announces its mobs, players send where they look */
    private void novaTick() {
        if (mobNames.isEmpty() && sounds == null) return;
        if (sounds != null && tick % 2 == 0) {
            double[] v = localView();
            if (v != null) sounds.listener(v[0], v[1] - 1.62, v[2], v[6]);
        }
        if (mobNames.isEmpty()) return;
        boolean h = isHost();
        if (h && tick % 40 == 7) {
            double[] ids = host.nativeApi(ApiOps.ENTITY_LIST, new double[]{96}, "");
            if (ids != null) for (double id : ids) if (!synced.containsKey(id)) syncMob(id, null, null, 0);
            java.util.Iterator<Map.Entry<Double, MobSync>> it = synced.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Double, MobSync> e = it.next();
                double[] r = host.nativeApi(ApiOps.MOB_INFO, new double[]{e.getKey()}, "");
                if (r == null || r.length == 0 || r[0] < 0) { it.remove(); continue; }
                sendMob(e.getKey(), e.getValue());
            }
        }
        if (!h && tick % 4 == 0) {
            double[] v = localView();
            if (v != null) {
                String js = Json.write(Json.copy(java.util.Arrays.asList((Object) v[0], v[1], v[2], v[3], v[4], v[5]), 0));
                host.nativeApiStr(1002, null, "__nova\u001feye\u001f" + js);
            }
        }
    }

    /** a message of "__nova" (see novaTick); true = handled */
    private boolean novaReceive(String name, Map<String, Object> e) {
        Object ch = e.get("channel"), d = e.get("data");
        if (name.equals("host.receive")) {
            if ("eye".equals(ch) && d instanceof List && ((List<?>) d).size() >= 6) {
                double[] v = new double[6];
                for (int i = 0; i < 6; i++) v[i] = ((Number) ((List<?>) d).get(i)).doubleValue();
                views.put(String.valueOf(e.get("player")), v);
            }
            return true;
        }
        if (isHost()) return true;                   // the host's own messages to itself
        if ("mob".equals(ch) && d instanceof List && ((List<?>) d).size() >= 2) {
            List<?> l = (List<?>) d;
            double uid = ((Number) l.get(0)).doubleValue();
            host.nativeApi(ApiOps.MOB_BECOME, new double[]{uid}, String.valueOf(l.get(1)));
            for (int i = 2; i + 1 < l.size(); i += 2)
                host.nativeApi(ApiOps.MOB_SET, new double[]{uid, ((Number) l.get(i + 1)).doubleValue()}, String.valueOf(l.get(i)));
        } else if ("snd".equals(ch) && d instanceof List && ((List<?>) d).size() >= 6 && sounds != null) {
            List<?> l = (List<?>) d;
            sounds.play(String.valueOf(l.get(0)), ((Number) l.get(1)).doubleValue(), ((Number) l.get(2)).doubleValue(),
                    ((Number) l.get(3)).doubleValue(), ((Number) l.get(4)).doubleValue(), ((Number) l.get(5)).doubleValue());
        } else if ("stop".equals(ch) && sounds != null) {
            sounds.stop(d == null ? "" : String.valueOf(d));
        }
        return true;
    }
}

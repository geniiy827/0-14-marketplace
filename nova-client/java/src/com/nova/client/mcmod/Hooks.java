package com.nova.client.mcmod;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nova.client.mcmod.Ast.*;
import com.nova.client.mcmod.Interp.Callable;
import com.nova.client.mcmod.Interp.Ns;

/**
 * The whole game as the mod API: any function of libminecraftpe.so can be called (mc.call) or hooked
 * (hook "Class::method" (...) { original() }) by its name, memory read and written (mem.*).
 * Names and argument kinds come from assets/nova/symbols.tsv (tools/gen_symbols.py).
 */
final class Hooks {
    static final class Sym {
        String name, mangled, args, ret, mode, sites; int addr, size;
        String[] ptypes;                       // parameter type names (from the demangled name)
        public String toString() { return name; }
    }

    private final ModRuntime rt;
    private final Machine m;
    private Map<String, Sym> byName, byMangled;
    private Map<String, List<Sym>> byShort;
    private List<Sym> all;

    Hooks(ModRuntime rt, Machine m) { this.rt = rt; this.m = m; }

    // ------------------------------------------------------------------ symbols
    private synchronized void loadSymbols() {
        if (byName != null) return;
        byName = new HashMap<String, Sym>(40000);
        byMangled = new HashMap<String, Sym>(40000);
        byShort = new HashMap<String, List<Sym>>(40000);
        all = new ArrayList<Sym>(23000);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(m.symbols(), "UTF-8"), 65536);
            String l;
            while ((l = r.readLine()) != null) {
                String[] f = l.split("\t", -1);
                if (f.length < 8) continue;
                Sym s = new Sym();
                s.name = f[0]; s.mangled = f[1]; s.addr = (int) Long.parseLong(f[2], 16); s.size = Integer.parseInt(f[3]);
                s.args = f[4]; s.ret = f[5]; s.mode = f[6]; s.sites = f[7];
                byName.put(s.name, s);
                byMangled.put(s.mangled, s);
                String sh = shortName(s.name);
                List<Sym> li = byShort.get(sh);
                if (li == null) { li = new ArrayList<Sym>(1); byShort.put(sh, li); }
                li.add(s);
                all.add(s);
            }
            r.close();
        } catch (Exception e) {
            rt.host.log("symbols.tsv: " + e);
        }
    }

    static String shortName(String d) {
        int p = d.indexOf('(');
        return p < 0 ? d : d.substring(0, p);
    }

    Sym find(String name, int line) {
        loadSymbols();
        String n = name.trim();
        Sym s = byName.get(n);
        if (s == null) s = byMangled.get(n);
        if (s == null) {
            List<Sym> li = byShort.get(n);
            if (li != null && li.size() == 1) s = li.get(0);
            else if (li != null) {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < li.size() && i < 4; i++) b.append("\n  ").append(li.get(i).name);
                throw new ScriptError("'" + n + "' есть в нескольких вариантах, укажи аргументы:" + b, line);
            }
        }
        if (s == null) throw new ScriptError("в игре нет функции '" + n + "' (mc.find(\"часть имени\") покажет похожие)", line);
        if (s.ptypes == null) s.ptypes = paramTypes(s.name);
        return s;
    }

    List<Object> search(String part, int max) {
        loadSymbols();
        List<Object> out = new ArrayList<Object>();
        String p = part.toLowerCase();
        for (Sym s : all) {
            if (s.name.toLowerCase().contains(p)) { out.add(s.name); if (out.size() >= max) break; }
        }
        return out;
    }

    static String[] paramTypes(String d) {
        int a = d.indexOf('('), b = d.lastIndexOf(')');
        if (a < 0 || b <= a) return new String[0];
        String in = d.substring(a + 1, b).trim();
        if (in.isEmpty() || in.equals("void")) return new String[0];
        List<String> out = new ArrayList<String>();
        int depth = 0; StringBuilder cur = new StringBuilder();
        for (char c : in.toCharArray()) {
            if (c == '<' || c == '(') depth++;
            else if (c == '>' || c == ')') depth--;
            if (c == ',' && depth == 0) { out.add(cur.toString().trim()); cur.setLength(0); }
            else cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        return out.toArray(new String[0]);
    }

    /** small classes passed by value, one word per field */
    static String structCodes(String type) {
        String t = type.replace("const", "").trim();
        if (t.equals("Vec3")) return "fff";
        if (t.equals("Vec2")) return "ff";
        if (t.equals("BlockPos")) return "ppp";
        if (t.equals("ChunkPos")) return "pp";
        if (t.equals("Color")) return "ffff";
        if (t.equals("EntityUniqueID")) return "q";
        return null;
    }
    static String[] structKeys(String type) {
        String t = type.replace("const", "").replace("&", "").replace("*", "").trim();
        if (t.equals("Vec3") || t.equals("BlockPos")) return new String[]{"x", "y", "z"};
        if (t.equals("Vec2")) return new String[]{"x", "y"};
        if (t.equals("ChunkPos")) return new String[]{"x", "z"};
        if (t.equals("Color")) return new String[]{"r", "g", "b", "a"};
        return null;
    }
    static boolean structFloat(String type) { return !type.contains("BlockPos") && !type.contains("ChunkPos"); }

    // ------------------------------------------------------------------ script values <-> argument words
    static final class Words { int[] w = new int[12]; int n; List<Integer> temps = new ArrayList<Integer>(); }

    private static int ival(Object v, int line) {
        if (v == null) return 0;
        if (v instanceof Boolean) return (Boolean) v ? 1 : 0;
        if (v instanceof Double) return (int) (long) (double) (Double) v;
        throw new ScriptError("нужно число (адрес или значение), а это " + Interp.typeName(v), line);
    }

    private void put(Words W, int v, int line) {
        if (W.n >= 12) throw new ScriptError("слишком много аргументов (до 12 слов)", line);
        W.w[W.n++] = v;
    }
    private void put64(Words W, long v, int line) {
        if ((W.n & 1) != 0) W.n++;
        put(W, (int) v, line); put(W, (int) (v >>> 32), line);
    }

    Words words(Sym s, List<Object> args, int line) {
        Words W = new Words();
        String codes = s.args;
        boolean member = codes.startsWith("s");
        int nParams = s.ptypes.length + (member ? 1 : 0);
        if (args.size() != nParams)
            throw new ScriptError(s.name + ": нужно " + nParams + " аргумент(а)" + (member ? " (первый - объект)" : "") + ", а передано " + args.size(), line);
        for (int i = 0; i < args.size(); i++) {
            char c = i < codes.length() ? codes.charAt(i) : 'p';
            String type = member ? (i == 0 ? "this" : s.ptypes[i - 1]) : s.ptypes[i];
            Object v = args.get(i);
            switch (c) {
                case 'f': put(W, Float.floatToIntBits((float) Interp.num(v, line)), line); break;
                case 'd': put64(W, Double.doubleToLongBits(Interp.num(v, line)), line); break;
                case 'q': put64(W, (long) Interp.num(v, line), line); break;
                case 'S':
                    if (v instanceof String) { int p = m.strNew((String) v); put(W, p, line); }
                    else put(W, ival(v, line), line);
                    break;
                case 'c':
                    if (v instanceof String) { int p = cstrTemp((String) v); W.temps.add(p); put(W, p, line); }
                    else put(W, ival(v, line), line);
                    break;
                case 'v': {
                    String sc = structCodes(type);
                    if (sc != null && v instanceof Map) {
                        String[] keys = structKeys(type);
                        Map<?, ?> mp = (Map<?, ?>) v;
                        for (int k = 0; k < sc.length(); k++) {
                            Object fv = keys != null && k < keys.length ? mp.get(keys[k]) : null;
                            double d = fv instanceof Double ? (Double) fv : 0;
                            if (sc.charAt(k) == 'f') put(W, Float.floatToIntBits((float) d), line);
                            else if (sc.charAt(k) == 'q') put64(W, (long) d, line);
                            else put(W, (int) d, line);
                        }
                    } else put(W, ival(v, line), line);
                    break;
                }
                default:
                    if (v instanceof Map) {                 // Vec3 const& / BlockPos const& from {x, y, z}
                        String[] keys = structKeys(type);
                        if (keys == null) throw new ScriptError(s.name + ": аргумент " + (i + 1) + " (" + type + ") - нужно число-адрес", line);
                        int p = m.alloc(keys.length * 4);
                        W.temps.add(p);
                        boolean fl = structFloat(type);
                        for (int k = 0; k < keys.length; k++) {
                            Object fv = ((Map<?, ?>) v).get(keys[k]);
                            double d = fv instanceof Double ? (Double) fv : 0;
                            m.poke(p + k * 4, 4, fl ? Float.floatToIntBits((float) d) : (int) d);
                        }
                        put(W, p, line);
                    } else if (v instanceof String) {
                        if (type.contains("basic_string")) put(W, m.strNew((String) v), line);
                        else { int p = cstrTemp((String) v); W.temps.add(p); put(W, p, line); }
                    } else put(W, ival(v, line), line);
            }
        }
        return W;
    }

    private int cstrTemp(String s) {
        byte[] b;
        try { b = s.getBytes("UTF-8"); } catch (Exception e) { b = s.getBytes(); }
        int p = m.alloc(b.length + 1);
        for (int i = 0; i < b.length; i++) m.poke(p + i, 1, b[i]);
        m.poke(p + b.length, 1, 0);
        return p;
    }

    static Object retValue(long r, char ret) {
        switch (ret) {
            case 'f': return (double) Float.intBitsToFloat((int) r);
            case 'd': return Double.longBitsToDouble(r);
            case 'q': return (double) r;
            case 'v': return null;
            default: return (double) (int) r;
        }
    }
    static long retWord(Object v, char ret, int line) {
        if (v == null) return 0;
        if (v instanceof Boolean) return (Boolean) v ? 1 : 0;
        double d = Interp.num(v, line);
        switch (ret) {
            case 'f': return Float.floatToIntBits((float) d) & 0xffffffffL;
            case 'd': return Double.doubleToLongBits(d);
            case 'q': return (long) d;
            default: return ((int) (long) d) & 0xffffffffL;
        }
    }

    /** argument words of a hooked call -> script values (structs by value become {x, y, z}) */
    List<Object> argsFrom(Sym s, int[] w) {
        List<Object> out = new ArrayList<Object>();
        String codes = s.args;
        boolean member = codes.startsWith("s");
        int k = 0;
        for (int i = 0; i < codes.length() && k < w.length; i++) {
            char c = codes.charAt(i);
            String type = member ? (i == 0 ? "this" : (i - 1 < s.ptypes.length ? s.ptypes[i - 1] : "")) : (i < s.ptypes.length ? s.ptypes[i] : "");
            if (c == 'f') out.add((double) Float.intBitsToFloat(w[k++]));
            else if (c == 'd' || c == 'q') {
                if ((k & 1) != 0) k++;
                if (k + 1 >= w.length) break;
                long lv = (w[k] & 0xffffffffL) | ((long) w[k + 1] << 32);
                k += 2;
                out.add(c == 'd' ? Double.longBitsToDouble(lv) : (double) lv);
            } else if (c == 'v' && structCodes(type) != null) {
                String sc = structCodes(type);
                String[] keys = structKeys(type);
                Map<String, Object> mp = new LinkedHashMap<String, Object>();
                for (int j = 0; j < sc.length() && k < w.length; j++) {
                    String key = keys != null && j < keys.length ? keys[j] : "v" + j;
                    if (sc.charAt(j) == 'f') mp.put(key, (double) Float.intBitsToFloat(w[k++]));
                    else mp.put(key, (double) w[k++]);
                }
                out.add(mp);
            } else out.add((double) w[k++]);
        }
        return out;
    }

    // ------------------------------------------------------------------ calls
    Object call(Sym s, List<Object> args, char ret, int line) {
        Words W = words(s, args, line);
        long r = m.call(m.base() + s.addr, W.w);
        for (int p : W.temps) m.free(p);
        return retValue(r, ret == 0 ? s.ret.charAt(0) : ret);
    }

    // ------------------------------------------------------------------ hooks
    static final class Active {
        Sym sym; ModRuntime.Mod mod; Callable fn; Scope scope; char ret; int idx;
    }
    private final Map<Integer, Active> active = new HashMap<Integer, Active>();
    private final Map<String, Active> bySym = new HashMap<String, Active>();

    /** install (or replace) a hook; returns null or an error text */
    synchronized String install(ModRuntime.Mod mod, String name, Callable fn, Scope scope, char ret, int line) {
        Sym s = find(name, line);
        Active old = bySym.get(s.mangled);
        if (old != null) {
            if (old.mod != mod) return "'" + s.name + "' уже перехвачена модом " + old.mod.name;
            old.fn = fn; old.scope = scope; old.ret = ret != 0 ? ret : s.ret.charAt(0);
            return null;
        }
        int base = m.base();
        int idx;
        if (s.mode.equals("i")) idx = m.hook(base + s.addr, 1, null);
        else {
            if (s.sites.isEmpty()) return "'" + s.name + "' нельзя перехватить: функция слишком короткая и её никто не вызывает напрямую";
            String[] p = s.sites.split(",");
            int[] sites = new int[p.length];
            for (int i = 0; i < p.length; i++) {
                int a = (int) Long.parseLong(p[i].substring(1), 16) + base;
                sites[i] = p[i].charAt(0) == 'V' ? -a : a;
            }
            idx = m.hook(base + s.addr, 2, sites);
        }
        if (idx < 0) return "'" + s.name + "' не перехватилась (код " + idx + ")";
        Active a = new Active();
        a.sym = s; a.mod = mod; a.fn = fn; a.scope = scope; a.idx = idx; a.ret = ret != 0 ? ret : s.ret.charAt(0);
        active.put(idx, a); bySym.put(s.mangled, a);
        return null;
    }

    synchronized void removeMod(ModRuntime.Mod mod) {
        for (java.util.Iterator<Active> it = active.values().iterator(); it.hasNext(); ) {
            Active a = it.next();
            if (a.mod != mod) continue;
            m.unhook(a.idx);
            bySym.remove(a.sym.mangled);
            it.remove();
        }
    }

    synchronized void removeAll() {
        for (Active a : active.values()) m.unhook(a.idx);
        active.clear(); bySym.clear();
    }

    /** libnova -> here, on whatever thread called the hooked function */
    long onHook(final int idx, final int[] w) {
        final Active a;
        synchronized (this) { a = active.get(idx); }
        if (a == null || !rt.lock.tryLock()) return m.callOrig(idx, w);
        try {
            final long[] raw = {0}; final boolean[] called = {false};
            final Callable original = new Callable() {
                public Object call(Interp in, List<Object> args, int line) {
                    int[] ww = w;
                    List<Integer> temps = null;
                    if (!args.isEmpty()) { Words W = words(a.sym, args, line); ww = W.w; temps = W.temps; }
                    long r = m.callOrig(idx, ww);
                    if (temps != null) for (int p : temps) m.free(p);
                    raw[0] = r; called[0] = true;
                    return retValue(r, a.ret);
                }
            };
            final Object[] result = {null};
            final List<Object> args = argsFrom(a.sym, w);
            boolean ok = rt.runHook(a.mod, new Runnable() {
                public void run() {
                    List<Object> av = args;
                    if (a.fn instanceof Interp.Fn) {
                        int np = ((Interp.Fn) a.fn).def.params.length;
                        if (a.scope != null) a.scope.vars.put("original", original);
                        else if (np > av.size()) av.add(original);
                        if (av.size() > np) av = av.subList(0, np);
                    }
                    result[0] = a.fn.call(a.mod.in, av, 0);
                }
            });
            if (!ok) return called[0] ? raw[0] : m.callOrig(idx, w);
            if (result[0] == null) return called[0] ? raw[0] : 0;
            return retWord(result[0], a.ret, 0);
        } finally {
            rt.lock.unlock();
        }
    }

    // ------------------------------------------------------------------ mc.* and mem.*
    interface F { Object f(List<Object> a, int line); }
    private static Callable c(final F f) {
        return new Callable() { public Object call(Interp in, List<Object> args, int line) { return f.f(args, line); } };
    }
    private static String sarg(List<Object> a, int i, String fn, int line) {
        if (i >= a.size() || !(a.get(i) instanceof String)) throw new ScriptError(fn + ": аргумент " + (i + 1) + " - строка с именем функции", line);
        return (String) a.get(i);
    }
    private static int iarg(List<Object> a, int i, String fn, int line) {
        if (i >= a.size()) throw new ScriptError(fn + ": не хватает аргумента " + (i + 1), line);
        return ival(a.get(i), line);
    }

    void bind(Map<String, Object> builtins) {
        Ns mc = new Ns("mc");
        mc.members.put("call", c(new F() { public Object f(List<Object> a, int l) { return call(find(sarg(a, 0, "mc.call", l), l), a.subList(1, a.size()), (char) 0, l); } }));
        mc.members.put("callf", c(new F() { public Object f(List<Object> a, int l) { return call(find(sarg(a, 0, "mc.callf", l), l), a.subList(1, a.size()), 'f', l); } }));
        mc.members.put("calld", c(new F() { public Object f(List<Object> a, int l) { return call(find(sarg(a, 0, "mc.calld", l), l), a.subList(1, a.size()), 'd', l); } }));
        mc.members.put("calli", c(new F() { public Object f(List<Object> a, int l) { return call(find(sarg(a, 0, "mc.calli", l), l), a.subList(1, a.size()), 'i', l); } }));
        mc.members.put("callAt", c(new F() { public Object f(List<Object> a, int l) {   // raw call: address + words
            int[] w = new int[12];
            for (int i = 1; i < a.size() && i <= 12; i++) w[i - 1] = ival(a.get(i), l);
            return (double) (int) m.call(iarg(a, 0, "mc.callAt", l), w);
        } }));
        mc.members.put("sym", c(new F() { public Object f(List<Object> a, int l) { return (double) (m.base() + find(sarg(a, 0, "mc.sym", l), l).addr); } }));
        mc.members.put("find", c(new F() { public Object f(List<Object> a, int l) { return search(sarg(a, 0, "mc.find", l), a.size() > 1 ? iarg(a, 1, "mc.find", l) : 30); } }));
        mc.members.put("info", c(new F() { public Object f(List<Object> a, int l) {
            Sym s = find(sarg(a, 0, "mc.info", l), l);
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("name", s.name); r.put("address", (double) (m.base() + s.addr)); r.put("size", (double) s.size);
            r.put("args", s.args); r.put("returns", s.ret.equals("f") ? "float" : s.ret.equals("d") ? "double" : "int");
            r.put("hook", s.mode.equals("i") ? "прямой" : s.sites.isEmpty() ? "нельзя" : "через вызовы");
            return r;
        } }));
        final String[] globs = {"client", "player", "region", "level", "gui"};
        for (int i = 0; i < globs.length; i++) {
            final int k = i;
            mc.members.put(globs[i], c(new F() { public Object f(List<Object> a, int l) { int v = m.global(k); return v == 0 ? null : (Object) (double) v; } }));
        }
        mc.members.put("base", c(new F() { public Object f(List<Object> a, int l) { return (double) m.base(); } }));
        builtins.put("mc", mc);

        Ns mem = new Ns("mem");
        final Object[][] kinds = {{"byte", -1}, {"ubyte", 1}, {"short", -2}, {"ushort", 2}, {"int", 4}, {"ptr", 4}};
        for (final Object[] k : kinds) {
            final int kind = (Integer) k[1];
            String nm = (String) k[0];
            mem.members.put(nm, c(new F() { public Object f(List<Object> a, int l) {
                int addr = iarg(a, 0, "mem", l) + (a.size() > 1 ? iarg(a, 1, "mem", l) : 0);
                return (double) m.peek(addr, kind);
            } }));
            mem.members.put("set" + Character.toUpperCase(nm.charAt(0)) + nm.substring(1), c(new F() { public Object f(List<Object> a, int l) {
                int addr = iarg(a, 0, "mem", l);
                m.poke(addr, kind, ival(a.get(a.size() - 1), l));
                return null;
            } }));
        }
        mem.members.put("float", c(new F() { public Object f(List<Object> a, int l) {
            int addr = iarg(a, 0, "mem.float", l) + (a.size() > 1 ? iarg(a, 1, "mem.float", l) : 0);
            return (double) Float.intBitsToFloat(m.peek(addr, 4));
        } }));
        mem.members.put("setFloat", c(new F() { public Object f(List<Object> a, int l) {
            m.poke(iarg(a, 0, "mem.setFloat", l), 4, Float.floatToIntBits((float) Interp.num(a.get(a.size() - 1), l)));
            return null;
        } }));
        mem.members.put("vec3", c(new F() { public Object f(List<Object> a, int l) {
            int p = iarg(a, 0, "mem.vec3", l) + (a.size() > 1 ? iarg(a, 1, "mem.vec3", l) : 0);
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("x", (double) Float.intBitsToFloat(m.peek(p, 4)));
            r.put("y", (double) Float.intBitsToFloat(m.peek(p + 4, 4)));
            r.put("z", (double) Float.intBitsToFloat(m.peek(p + 8, 4)));
            return r;
        } }));
        mem.members.put("str", c(new F() { public Object f(List<Object> a, int l) { return m.strGet(iarg(a, 0, "mem.str", l)); } }));
        mem.members.put("cstr", c(new F() { public Object f(List<Object> a, int l) { return m.cstr(iarg(a, 0, "mem.cstr", l)); } }));
        mem.members.put("string", c(new F() { public Object f(List<Object> a, int l) { return (double) m.strNew(Interp.str(a.isEmpty() ? "" : a.get(0))); } }));
        mem.members.put("alloc", c(new F() { public Object f(List<Object> a, int l) { return (double) m.alloc(iarg(a, 0, "mem.alloc", l)); } }));
        mem.members.put("free", c(new F() { public Object f(List<Object> a, int l) { m.free(iarg(a, 0, "mem.free", l)); return null; } }));
        mem.members.put("hex", c(new F() { public Object f(List<Object> a, int l) { return "0x" + Integer.toHexString(iarg(a, 0, "mem.hex", l)); } }));
        builtins.put("mem", mem);
    }
}

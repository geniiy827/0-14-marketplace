import com.nova.client.mcmod.ModRuntime;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/** Desktop check of real mods: a fake game (ids for the mod's items and blocks, a host link that loops back),
 *  world join, ticks, chat commands; prints everything a mod says and every error.
 *  usage: ModCheck prelude.mcs symbols.tsv mod_dir [chat lines...]      exit code 1 = the mod reported an error */
public class ModCheck {
    static ModRuntime rt;
    static int errors;
    static Map<Integer, String> OPS = new HashMap<Integer, String>();
    static Map<String, Integer> EV = new HashMap<String, Integer>();
    static List<Runnable> later = new ArrayList<Runnable>();
    static double health = 20;

    public static void main(String[] a) throws Exception {
        Class<?> c = Class.forName("com.nova.client.mcmod.ApiOps");
        for (java.lang.reflect.Field f : c.getDeclaredFields()) { f.setAccessible(true); OPS.put(f.getInt(null), f.getName()); }
        Class<?> t = Class.forName("com.nova.client.mcmod.ApiTable");
        java.lang.reflect.Field ef = t.getDeclaredField("EVENTS"); ef.setAccessible(true);
        String[][] evs = (String[][]) ef.get(null);
        for (int i = 0; i < evs.length; i++) EV.put(evs[i][0], i + 1);
        final String prelude = new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8");
        final String syms = a[1];
        rt = new ModRuntime(new ModRuntime.Host() {
            public double[] nativeApi(int op, double[] args, String s) {
                String n = OPS.get(op);
                if ("CHAT".equals(n) || "TIP".equals(n)) {
                    System.out.println("  [" + n.toLowerCase() + "] " + s);
                    if (s != null && (s.contains("main.mcs:") || s.contains("ошиб"))) errors++;
                    return new double[0];
                }
                if ("ITEM_ID".equals(n)) return new double[]{-1};
                if ("PLAYER_POS".equals(n)) return new double[]{10.5, 64, -3.5, 90, 0};
                if ("PLAYER_HEALTH".equals(n)) return new double[]{health, 20};
                if ("RECIPE".equals(n) || "SMELT".equals(n)) { recipes++; return new double[0]; }
                return new double[]{0, 0, 0, 0, 0, 0, 0, 0};
            }
            public String nativeApiStr(int op, double[] args, final String s) {
                switch (op) {
                    case 1001: return "1";
                    case 1002: later.add(new Runnable() { public void run() {          // host.send -> host.receive
                        String[] q = s.split("\u001f", 3);
                        System.out.println("  [to host] " + q[1] + " " + q[2]);
                        rt.event(EV.get("host.receive"), q[0] + "\u001f" + q[1] + "\u001fSteve\u001f" + q[2], null);
                    } }); return "1";
                    case 1003: later.add(new Runnable() { public void run() {          // players.send -> client.receive
                        String[] q = s.split("\u001f", 4);
                        System.out.println("  [to player " + q[0] + "] " + q[2] + " " + q[3]);
                        rt.event(EV.get("client.receive"), q[1] + "\u001f" + q[2] + "\u001f" + q[3], null);
                    } }); return "1";
                    case 1004: return "Steve\u001fAlex";
                    case 1005: System.out.println("  [hurt " + s + "] " + args[0]); health -= args[0]; return "1";
                    case 1009: return "1050\u001f6400\u001f-350\u001f" + (int) health;
                    default: System.out.println("  [net " + op + "] " + s + " " + Arrays.toString(args)); return "1";
                }
            }
            public void log(String s) { System.out.println("  LOG: " + s); if (s.contains("main.mcs")) errors++; }
            public void toast(String t, String x) { System.out.println("  TOAST: " + t + " / " + x); }
            public String loadData(String id) { return null; }
            public void saveData(String id, String j) { }
            public String prelude() { return prelude; }
            public com.nova.client.mcmod.Machine machine() { return FAKE(syms); }
            public List<String[]> liveScan() { return null; }
        });
        java.io.File dir = new java.io.File(a[2]);
        String id = dir.getName();
        rt.load(id, id, "main.mcs", new String(Files.readAllBytes(Paths.get(a[2], "main.mcs")), "UTF-8"));
        // ids, as ModBlocks / ModItems would give them (items by data: id + 65536 * data)
        int nb = 0, ni = 0, nv = 0;
        for (Object[] d : rt.blockDecls()) { rt.customIds.put(id + ":" + d[1], 210.0 + nb); rt.customIds.put(String.valueOf(d[1]), 210.0 + nb); nb++; }
        for (Object[] d : rt.decls("item")) {
            double iid = 300 + ni++;
            rt.customIds.put(id + ":" + d[1], iid); rt.customIds.put(String.valueOf(d[1]), iid);
            Object vs = d[2] instanceof Map ? ((Map<?, ?>) d[2]).get("variants") : null;
            if (vs instanceof List) for (int v = 0; v < ((List<?>) vs).size(); v++) {
                String vn = String.valueOf(((Map<?, ?>) ((List<?>) vs).get(v)).get("name"));
                rt.customIds.put(id + ":" + vn, iid + 65536.0 * v); rt.customIds.put(vn, iid + 65536.0 * v); nv++;
            }
        }
        System.out.println(id + ": " + nb + " blocks, " + ni + " items, " + nv + " by data");
        rt.event(EV.get("world.join"), null, null);
        flush();
        for (int k = 0; k < 400; k++) { rt.event(EV.get("tick"), null, null); flush(); }
        for (int i = 3; i < a.length; i++) {
            System.out.println("> " + a[i]);
            System.out.println("  cancel=" + rt.event(EV.get("chat"), a[i], null));
            flush();
            for (int k = 0; k < 200; k++) { rt.event(EV.get("tick"), null, null); flush(); }
        }
        System.out.println(id + ": recipes " + recipes + ", errors " + errors);
        System.exit(errors > 0 ? 1 : 0);
    }
    static int recipes;
    static void flush() { while (!later.isEmpty()) later.remove(0).run(); }

    static com.nova.client.mcmod.Machine FAKE(final String syms) {
        return new com.nova.client.mcmod.Machine() {
            int mem = 0x5000;
            public int base() { return 0x70000000; }
            public int hook(int addr, int mode, int[] sites) { return 0; }
            public void unhook(int idx) { }
            public long call(int fn, int[] w) { return 2; }   // e.g. difficulty: normal
            public long callOrig(int idx, int[] w) { return 0; }
            public int peek(int addr, int kind) { return 0; }
            public void poke(int addr, int kind, int v) { }
            public int alloc(int n) { mem += n + 16; return mem; }
            public void free(int p) { }
            public int strNew(String s) { return 0x6000; }
            public String strGet(int p) { return ""; }
            public String cstr(int p) { return ""; }
            public int global(int which) { return 0x1000 + which; }
            public java.io.InputStream symbols() throws java.io.IOException { return new java.io.FileInputStream(syms); }
        };
    }
}

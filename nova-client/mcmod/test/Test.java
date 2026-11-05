import com.nova.client.mcmod.ModRuntime;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Desktop test of McMod Script: fake game, prints chat. usage: Test prelude.mcs mod.mcs */
public class Test {
    static double time = 1000;
    static java.util.Map<Integer, String> OPS = new java.util.HashMap<Integer, String>();
    static int hooks = 0;
    static final com.nova.client.mcmod.Machine FAKE = new com.nova.client.mcmod.Machine() {
        public int base() { return 0x70000000; }
        public int hook(int addr, int mode, int[] sites) { System.out.println("HOOK " + Integer.toHexString(addr) + " mode " + mode + " sites " + (sites == null ? 0 : sites.length)); return hooks++; }
        public void unhook(int idx) { System.out.println("UNHOOK " + idx); }
        public long call(int fn, int[] w) { System.out.println("CALL " + Integer.toHexString(fn) + " " + java.util.Arrays.toString(java.util.Arrays.copyOf(w, 6))); return fn == 0x7052ddf8 ? 0 : Float.floatToIntBits(1.62f) & 0xffffffffL; }
        public long callOrig(int idx, int[] w) { System.out.println("ORIG " + idx + " " + w[0] + " " + Float.intBitsToFloat(w[1]) + " " + Float.intBitsToFloat(w[2])); return Float.floatToIntBits(0.42f) & 0xffffffffL; }
        public int peek(int addr, int kind) { return 42; }
        public void poke(int addr, int kind, int v) { System.out.println("POKE " + Integer.toHexString(addr) + " " + v); }
        public int alloc(int n) { return 0x5000; }
        public void free(int p) { }
        public int strNew(String s) { System.out.println("STR '" + s + "'"); return 0x6000; }
        public String strGet(int p) { return "s"; }
        public String cstr(int p) { return "c"; }
        public int global(int which) { return 0x1000 + which; }
        public java.io.InputStream symbols() throws java.io.IOException { return new java.io.FileInputStream(System.getProperty("syms")); }
    };
    public static void main(String[] a) throws Exception {
        Class<?> c = Class.forName("com.nova.client.mcmod.ApiOps");
        for (java.lang.reflect.Field f : c.getDeclaredFields()) { f.setAccessible(true); OPS.put(f.getInt(null), f.getName()); }
        final String prelude = new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8");
        ModRuntime rt = new ModRuntime(new ModRuntime.Host() {
            public double[] nativeApi(int op, double[] args, String s) {
                String n = OPS.get(op);
                if (n.equals("CHAT")) op = 1; else if (n.equals("ITEM_ID")) op = 4; else if (n.equals("PLAYER_POS")) op = 5;
                else if (n.equals("PLAYER_HEALTH")) op = 7; else if (n.equals("WORLD_TIME")) op = 14; else op = -op;
                if (op < 0) { System.out.println("OP " + n + " " + java.util.Arrays.toString(args) + " '" + s + "'"); return new double[]{1}; }
                switch (op) {
                    case 1: System.out.println("CHAT: " + s); return new double[0];
                    case 4: return new double[]{s.equals("diamond") ? 264 : s.equals("stone") ? 1 : -1};
                    case 5: return new double[]{10.5, 64, -3.5, 90, 0};
                    case 7: return new double[]{14, 20};
                    case 14: return new double[]{time};
                    default:
                        System.out.println("OP " + op + " " + java.util.Arrays.toString(args) + " '" + s + "'");
                        return new double[]{1};
                }
            }
            public String nativeApiStr(int op, double[] args, String s) { return "str" + op; }
            public void log(String s) { System.out.println("LOG: " + s); }
            public void toast(String t, String x) { System.out.println("TOAST: " + t + " / " + x); }
            public String loadData(String id) { return null; }
            public void saveData(String id, String j) { System.out.println("SAVE " + id + ": " + j); }
            public String prelude() { return prelude; }
            public com.nova.client.mcmod.Machine machine() { return FAKE; }
            public java.util.List<String[]> liveScan() { return null; }
        });
        for (int i = 1; i < a.length; i++)
            rt.load("test" + i, "Test" + i, Paths.get(a[i]).getFileName().toString(), new String(Files.readAllBytes(Paths.get(a[i])), "UTF-8"));
        rt.customIds.put("test1:pistol", 470.0);
        rt.event(1, null, null);
        if (System.getProperty("pistol") != null) {
            for (int k = 0; k < 3; k++) { System.out.println("action -> " + rt.event(12, null, new double[]{470})); for (int t = 0; t < 5; t++) rt.event(3, null, null); }
            return;
        }
        for (int t = 0; t < 45; t++) rt.event(3, null, null);
        System.out.println("break cancel=" + rt.event(4, null, new double[]{1, 2, 3, 1, 0, 278, 1}));
        System.out.println("chat cancel=" + rt.event(9, "/heal", null));
        System.out.println("chat cancel=" + rt.event(9, "hello", null));
        rt.event(4, "/fast", null); rt.event(4, "/moon", null);
        int[] tw = new int[12]; tw[0] = 0x1001; tw[1] = Float.floatToIntBits(0.5f); tw[2] = Float.floatToIntBits(1f);
        long r0 = rt.onHook(0, tw);
        int[] fw = new int[12]; fw[0] = 0x1001; fw[1] = Float.floatToIntBits(5f);
        System.out.println("fall " + rt.onHook(2, fw));
        long r = rt.onHook(1, new int[]{0x1001,0,0,0,0,0,0,0,0,0,0,0});
        System.out.println("hook result " + Float.intBitsToFloat((int) r));
        System.out.println("console cancel=" + rt.event(4, "> mem.hex(mc.player()) + \" \" + mc.info(\"LocalPlayer::travel\")", null));
    }
}

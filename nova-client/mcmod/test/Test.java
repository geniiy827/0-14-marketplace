import com.nova.client.mcmod.ModRuntime;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Desktop test of McMod Script: fake game, prints chat. usage: Test prelude.mcs mod.mcs */
public class Test {
    static double time = 1000;
    static java.util.Map<Integer, String> OPS = new java.util.HashMap<Integer, String>();
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
        });
        for (int i = 1; i < a.length; i++)
            rt.load("test" + i, "Test" + i, Paths.get(a[i]).getFileName().toString(), new String(Files.readAllBytes(Paths.get(a[i])), "UTF-8"));
        rt.event(1, null, null);
        for (int t = 0; t < 45; t++) rt.event(3, null, null);
        System.out.println("break cancel=" + rt.event(4, null, new double[]{1, 2, 3, 1, 0, 278, 1}));
        System.out.println("chat cancel=" + rt.event(9, "/heal", null));
        System.out.println("chat cancel=" + rt.event(9, "hello", null));
    }
}

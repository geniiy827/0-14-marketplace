package com.nova.client;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Friends and playing together (alpha16), the Android side of NovaNet:
 *  - the Friends screen of the start screen (tabs "+" and friends; native kind 4),
 *  - network settings of a world from the create-world screen ("Сеть" button; kind 5),
 *  - which world this phone hosts (native nativeNetState) -> NovaNet.world, the pause menu's Friends button,
 *  - inviting friends from the pause menu (kind 6) and the invite banner,
 *  - joining a friend's world: NovaNet link -> MinecraftClient::joinMultiplayer("127.0.0.1", local port).
 * All screens are the game's own options screen filled by libnova (menus.inc).
 */
final class Social implements NovaNet.Listener {
    static final String TAG = "NOVA-net";
    private final NovaActivity act;
    private NovaNet net;
    private volatile boolean busy, syncing;

    Social(NovaActivity a) { act = a; }

    private boolean ru() { return "ru".equals(java.util.Locale.getDefault().getLanguage()); }
    private String t(String ruText, String enText) { return ru() ? ruText : enText; }

    private void worker(final Runnable r) {
        Thread th = new Thread(new Runnable() { public void run() { try { r.run(); } catch (Throwable e) { Log.e(TAG, "social", e); } } }, "nova-social");
        th.setDaemon(true);
        th.start();
    }

    // ------------------------------------------------------------------ the tunnel
    /** signed in: keep the tunnel open (presence, invites, playing); signed out: close it */
    synchronized void accountChanged() {
        try {
            AccountClient c = act.accClientPublic();
            c.token = NovaActivity.signedIn() ? NovaActivity.accTokenPublic() : "";
            if (NovaActivity.signedIn()) {
                if (net == null) net = new NovaNet(c, AccountClient.HOST, AccountClient.PORT, this);
                net.start();
            } else if (net != null) {
                net.stop();
                net = null;
                c.setTransport(null);
                hostingKey = null;
                setPauseFriends(false);
            }
        } catch (Throwable e) {
            Log.w(TAG, "tunnel", e);
        }
    }

    // ------------------------------------------------------------------ NovaNet.Listener
    private final Set<String> shownInvites = new HashSet<String>();
    private volatile String bannerFrom;
    private int lastIncoming = -1;

    public void invites(List<Map<String, Object>> list) {
        if (list == null) return;
        for (Map<String, Object> m : list) {
            String from = String.valueOf(m.get("from")), world = String.valueOf(m.get("world"));
            String key = from + "\n" + world;
            if (shownInvites.contains(key) || bannerFrom != null) continue;
            shownInvites.add(key);
            bannerFrom = from;
            String sub = from + ": " + world + "  " + num(m.get("players")) + "/" + num(m.get("max"));
            Object ms = m.get("msig");
            if (ms != null && String.valueOf(ms).length() > 0 && !String.valueOf(ms).equals(ModSet.local(act).sig))
                sub += t(", нужны моды", ", needs mods");
            try { NovaActivity.nativeBanner("@nova.ban.invited", sub, "@nova.ban.join", "@nova.ban.no"); } catch (Throwable ignored) { }
            break;
        }
    }

    public void incomingRequests(int n) {
        if (lastIncoming >= 0 && n > lastIncoming) NovaActivity.postToast(t("Новый запрос в друзья", "New friend request"), "");
        lastIncoming = n;
    }

    public void log(String s) { Log.i(TAG, s); }

    private static String num(Object o) {
        if (o instanceof Double) return String.valueOf((int) (double) (Double) o);
        return String.valueOf(o);
    }

    // ------------------------------------------------------------------ called every 300 ms (UI thread)
    private int tick;
    void poll() {
        try {
            int a = NovaActivity.nativeBannerAnswer();
            final String from = bannerFrom;
            if (a != 0 && from != null) {
                bannerFrom = null;
                if (a == 1) join(from);
                else worker(new Runnable() { public void run() { op("invite.decline", "from", from); } });
            }
        } catch (Throwable ignored) { }
        if (++tick % 3 == 0) pollHosting();
        if (tick == 20) afterRestart();
    }

    // ------------------------------------------------------------------ hosting
    private volatile String hostingKey;          // level id of the world open for friends
    private volatile ModSet hostMods;            // the mods friends download (null = none)
    private String lastState = "";
    static volatile boolean pauseFriends;        // the pause menu shows "Friends"

    private void pollHosting() {
        String st;
        try { st = NovaActivity.nativeNetState(); } catch (Throwable e) { return; }
        if (st == null) st = "";
        if (st.equals(lastState)) return;
        lastState = st;
        NovaNet n = net;
        if (st.length() == 0) {                                  // left the world
            if (hostingKey != null && n != null) n.stopHosting();
            hostingKey = null;
            setPauseFriends(false);
            return;
        }
        String[] f = st.split("\t", -1);
        int port = NovaActivity.parseInt(f[0]);
        String id = f.length > 1 ? f[1] : "", name = f.length > 2 ? f[2] : "";
        String[] s = settingsFor(id, name);
        boolean on = s != null && "1".equals(s[0]);
        if (!on || n == null || !NovaActivity.signedIn()) {
            if (on && !NovaActivity.signedIn()) NovaActivity.postToast(t("Мир не открыт друзьям", "World not opened to friends"), t("Войдите в аккаунт", "Sign in first"));
            setPauseFriends(false);
            return;
        }
        Map<String, Object> w = new LinkedHashMap<String, Object>();
        w.put("name", name.length() > 0 ? name : "World");
        w.put("mode", "i".equals(s[1]) ? "invite" : "friends");
        w.put("max", (double) NovaActivity.parseInt(s[2]));
        hostMods = null;
        if (s.length < 4 || !"0".equals(s[3])) {                 // the host's mods go with the world
            ModSet ms = ModSet.local(act);
            if (!ms.mods.isEmpty()) {
                hostMods = ms;
                w.put("mods", (double) ms.mods.size());
                w.put("msig", ms.sig);
            }
        }
        final ModSet served = hostMods;
        n.files = served == null ? null : new NovaNet.Files() {
            public byte[] file(int index) { return served.file(index); }
        };
        n.gamePort = port > 0 ? port : 19132;
        n.world = w;
        hostingKey = id;
        setPauseFriends(true);
        NovaActivity.postToast(t("Мир открыт для друзей", "World opened to friends"), name);
    }

    private void setPauseFriends(boolean on) {
        if (pauseFriends == on) return;
        pauseFriends = on;
        try { NovaActivity.nativeReloadUi(0); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ network settings of worlds
    private java.io.File setFile() { return new java.io.File(act.getFilesDir(), "nova/networlds.properties"); }
    private java.util.Properties loadSettings() {
        java.util.Properties p = new java.util.Properties();
        try { java.io.FileInputStream in = new java.io.FileInputStream(setFile()); p.load(in); in.close(); } catch (Exception ignored) { }
        return p;
    }
    private void saveSettings(java.util.Properties p) {
        try {
            java.io.File f = setFile();
            f.getParentFile().mkdirs();
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            p.store(o, "NOVA network settings of worlds: on|f/i|max");
            o.close();
        } catch (Exception e) { Log.w(TAG, "settings", e); }
    }

    /** on|mode|max of a world: by its id, else the settings just made on the create-world screen, else by name */
    private String[] settingsFor(String id, String name) {
        java.util.Properties p = loadSettings();
        String v = p.getProperty("id." + id);
        if (v == null) {
            String pend = p.getProperty("pending");
            long when = NovaActivity.parseLong(p.getProperty("pending.time", "0"));
            if (pend != null && System.currentTimeMillis() - when < 30 * 60 * 1000L) {
                v = pend;
                p.remove("pending"); p.remove("pending.time");
            } else v = p.getProperty("name." + name);
            if (v != null && id.length() > 0) p.setProperty("id." + id, v);
            saveSettings(p);
        }
        return v == null ? null : v.split("\\|", -1);
    }

    private String pendingName = "";

    /** "W|level name" from the create-world screen */
    void openNetSettings(String levelName) {
        pendingName = levelName;
        java.util.Properties p = loadSettings();
        String v = p.getProperty("name." + levelName);
        if (v == null) v = "0|f|5|1";
        String[] s = v.split("\\|", -1);
        try { NovaActivity.nativeOpenNova(5, s[0] + "\t" + s[1] + "\t" + s[2] + "\t" + (s.length > 3 ? s[3] : "1"), levelName); } catch (Throwable e) { Log.e(TAG, "netset", e); }
    }

    /** "N|on\x1fmode\x1fmax" when the settings screen closes */
    void netSettingsResult(String r) {
        String[] f = r.split("\u001f", -1);
        if (f.length < 3) return;
        String v = f[0] + "|" + f[1] + "|" + f[2] + "|" + (f.length > 3 ? f[3] : "1");
        java.util.Properties p = loadSettings();
        p.setProperty("pending", v);
        p.setProperty("pending.time", String.valueOf(System.currentTimeMillis()));
        if (pendingName.length() > 0) p.setProperty("name." + pendingName, v);
        saveSettings(p);
        if ("1".equals(f[0]) && !NovaActivity.signedIn())
            NovaActivity.postToast(t("Для сетевой игры", "To play with friends"), t("войдите в аккаунт NOVA", "sign in to NOVA"));
    }

    // ------------------------------------------------------------------ requests
    private AccountClient.Result op(String op, String key, String val) {
        try {
            Map<String, Object> f = new LinkedHashMap<String, Object>();
            if (key != null) f.put(key, val);
            return act.accClientPublic().op(op, null, f);
        } catch (Exception e) {
            return new AccountClient.Result(false, AccountClient.NET, null, null);
        }
    }

    private boolean failed(AccountClient.Result r) {
        if (AccountClient.NET.equals(r.err)) { NovaActivity.postToast("@nova.acc.offline", ""); return true; }
        if (!r.ok) {
            if ("session".equals(r.err)) act.sessionLost();
            NovaActivity.accToastPublic(r.msg);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the friends screen
    /** nova://friends; tab: 0 add, 1 list, -1 by content */
    void openFriends(final int tab, final String prefill) {
        if (!NovaActivity.signedIn()) {
            NovaActivity.postToast("@nova.fr.signin", "");
            act.openAccountPublic();
            return;
        }
        if (busy) return;
        busy = true;
        worker(new Runnable() {
            @SuppressWarnings("unchecked")
            public void run() {
                try {
                    AccountClient.Result r = op("friends.list", null, null);
                    if (failed(r)) return;
                    StringBuilder a = new StringBuilder();
                    int n = 0;
                    Object in = r.data.get("incoming");
                    if (in instanceof List) for (Object o : (List<Object>) in) { a.append("I\t").append(clean(o)).append('\n'); n++; }
                    Object fr = r.data.get("friends");
                    List<Map<String, Object>> online = new ArrayList<Map<String, Object>>(), offline = new ArrayList<Map<String, Object>>();
                    if (fr instanceof List) for (Object o : (List<Object>) fr) {
                        if (!(o instanceof Map)) continue;
                        Map<String, Object> m = (Map<String, Object>) o;
                        (Boolean.TRUE.equals(m.get("online")) ? online : offline).add(m);
                    }
                    online.addAll(offline);
                    ModSet mine = null;
                    for (Map<String, Object> m : online) {
                        Object w = m.get("world");
                        boolean joinable = w instanceof Map && Boolean.TRUE.equals(((Map<String, Object>) w).get("joinable"));
                        boolean needMods = false;
                        if (joinable) {
                            if (mine == null) mine = ModSet.local(act);
                            needMods = !mine.matches((Map<String, Object>) w);
                        }
                        a.append("F\t").append(clean(m.get("tag"))).append('\t')
                                .append(needMods ? t("Нужны моды хоста, загрузите их", "Needs the host's mods") : status(m)).append('\t');
                        a.append(needMods ? 'm' : joinable ? '1' : '0').append('\n');
                        n++;
                    }
                    Object out = r.data.get("outgoing");
                    if (out instanceof List) for (Object o : (List<Object>) out) { a.append("O\t").append(clean(o)).append('\n'); n++; }
                    int tb = tab >= 0 ? tab : (n > 0 ? 1 : 0);
                    NovaActivity.nativeOpenNova(4, a.toString(), (tb == 0 ? "0" : "1") + (prefill == null ? "" : prefill));
                } finally {
                    busy = false;
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private String status(Map<String, Object> m) {
        Object w = m.get("world");
        if (w instanceof Map) {
            Map<String, Object> ww = (Map<String, Object>) w;
            String nm = clean(ww.get("name"));
            if (nm.length() > 14) nm = nm.substring(0, 13) + ".";
            return t("В игре: ", "Playing: ") + nm + "  " + num(ww.get("players")) + "/" + num(ww.get("max"))
                    + ("invite".equals(ww.get("mode")) && !Boolean.TRUE.equals(ww.get("joinable")) ? t(", по приглашению", ", invite only") : "");
        }
        return Boolean.TRUE.equals(m.get("online")) ? t("В сети", "Online") : t("Не в сети", "Offline");
    }

    private static String clean(Object o) {
        String s = String.valueOf(o);
        return s.replace('\n', ' ').replace('\t', ' ').replace('\r', ' ');
    }

    /** "F|op\x1ftag" from the friends screen */
    void friendsResult(String r) {
        String[] f = r.split("\u001f", -1);
        final String what = f[0], tag = f.length > 1 ? f[1].trim() : "";
        if (what.equals("x") || busy) return;
        if (what.equals("join")) { join(tag); return; }
        if (what.equals("mods")) { syncMods(tag, "friends"); return; }
        if (tag.length() == 0) { NovaActivity.postToast(t("Введите тег друга", "Enter your friend's tag"), ""); return; }
        final String op = what.equals("request") ? "friends.request" : what.equals("accept") ? "friends.accept"
                : what.equals("decline") ? "friends.decline" : "friends.remove";
        busy = true;
        worker(new Runnable() {
            public void run() {
                AccountClient.Result res;
                try { res = op(op, "tag", tag); } finally { busy = false; }
                if (failed(res)) return;
                NovaActivity.accToastPublic(res.msg);
                NovaActivity.nativeCloseNova();                        // the screen is built again with the change
                try { Thread.sleep(400); } catch (InterruptedException ignored) { }
                openFriends(what.equals("request") && res.data.get("friends") == null ? 0 : 1, null);
            }
        });
    }

    /** join a friend's world: a link through the NOVA server, then the game connects to it as to a LAN game */
    void join(final String tag) {
        final NovaNet n = net;
        if (n == null) { NovaActivity.postToast("@nova.fr.signin", ""); return; }
        if (busy) return;
        busy = true;
        NovaActivity.postToast(t("Подключение к ", "Connecting to ") + tag, "");
        worker(new Runnable() {
            @SuppressWarnings("unchecked")
            public void run() {
                try {
                    // the same mods as the host? (an invite or a list from before may not say)
                    AccountClient.Result fl = op("friends.list", null, null);
                    if (fl.ok && fl.data.get("friends") instanceof List) {
                        for (Object o : (List<Object>) fl.data.get("friends")) {
                            if (!(o instanceof Map) || !tag.equalsIgnoreCase(String.valueOf(((Map<String, Object>) o).get("tag")))) continue;
                            Object w = ((Map<String, Object>) o).get("world");
                            if (w instanceof Map && !ModSet.local(act).matches((Map<String, Object>) w)) {
                                syncMods(tag, "join");
                                return;
                            }
                        }
                    }
                    int[] port = new int[1];
                    AccountClient.Result r = n.join(tag, port);
                    if (failed(r)) return;
                    NovaActivity.nativeCloseNova();
                    Thread.sleep(300);                                  // the first punches open the path
                    NovaActivity.nativeJoin("127.0.0.1", port[0]);
                } catch (Throwable e) {
                    Log.e(TAG, "join", e);
                } finally {
                    busy = false;
                }
            }
        });
    }

    // ------------------------------------------------------------------ the host's mods
    /**
     * Make this phone's mods the host's: download what is missing (from the host's phone), enable exactly the
     * host's mods, take the host's block / item numbers, restart. After the restart: "join" enters the world,
     * "friends" opens the friends screen (the button now says Join).
     */
    void syncMods(final String tag, final String after) {
        final NovaNet n = net;
        if (n == null || syncing) return;
        syncing = true;
        NovaActivity.nativeCloseNova();
        NovaActivity.postToast(t("Загрузка модов хоста", "Downloading the host's mods"), tag);
        worker(new Runnable() {
            public void run() {
                try {
                    byte[] man = n.download(tag, new int[]{ModSet.MANIFEST}, null)[0];
                    ModSet host = ModSet.parse(new String(man, "UTF-8"));
                    List<Integer> need = new ArrayList<Integer>();
                    long total = 0;
                    for (int i = 0; i < host.mods.size(); i++) {
                        ModSet.M m = host.mods.get(i);
                        PackManager.Pack have = PackManager.find(act, m.uuid);
                        if (have == null || !m.sha1.equalsIgnoreCase(have.sha1)) { need.add(i); total += m.size; }
                    }
                    if (total > 64L * 1024 * 1024) throw new Exception(t("Моды хоста больше 64 МБ", "The host's mods are over 64 MB"));
                    final long all = Math.max(1, total);
                    final long[] before = {0};
                    for (int k = 0; k < need.size(); k++) {
                        final ModSet.M m = host.mods.get(need.get(k));
                        final long base = before[0];
                        byte[] data = n.download(tag, new int[]{need.get(k)}, new NovaNet.DownloadProgress() {
                            long shown;
                            public void progress(long done, long size) {
                                long now = System.currentTimeMillis();
                                if (now - shown < 1500) return;
                                shown = now;
                                int pc = (int) Math.min(100, (base + done) * 100 / all);
                                NovaActivity.postToast(t("Загрузка модов: ", "Downloading mods: ") + pc + "%", m.name);
                            }
                        })[0];
                        PackManager.importStream(act, new java.io.ByteArrayInputStream(data), "friend", tag, m.sha1, false, null);
                        before[0] += m.size;
                    }
                    host.apply(act);
                    java.util.Properties p = new java.util.Properties();
                    p.setProperty("action", after);
                    p.setProperty("tag", tag);
                    p.setProperty("time", String.valueOf(System.currentTimeMillis()));
                    java.io.File f = afterFile();
                    f.getParentFile().mkdirs();
                    java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                    p.store(o, "NOVA: what to do after the restart");
                    o.close();
                    NovaActivity.postToast(t("Моды загружены", "Mods ready"), t("перезапуск", "restarting"));
                    Thread.sleep(1200);
                    act.runOnUiThread(new Runnable() { public void run() { NovaActivity.restartGame(act); } });
                } catch (Throwable e) {
                    Log.e(TAG, "mods", e);
                    NovaActivity.accToastPublic(t("Моды не загрузились: ", "Mods failed: ") + e.getMessage());
                } finally {
                    syncing = false;
                }
            }
        });
    }

    private java.io.File afterFile() { return new java.io.File(act.getFilesDir(), "nova/after_restart.properties"); }

    /** ~6 s after the start: what syncMods asked for */
    private void afterRestart() {
        java.io.File f = afterFile();
        if (!f.exists()) return;
        java.util.Properties p = new java.util.Properties();
        try { java.io.FileInputStream in = new java.io.FileInputStream(f); p.load(in); in.close(); } catch (Exception ignored) { }
        f.delete();
        if (System.currentTimeMillis() - NovaActivity.parseLong(p.getProperty("time", "0")) > 5 * 60 * 1000L) return;
        String tag = p.getProperty("tag", "");
        if (tag.length() == 0 || net == null) return;
        if ("join".equals(p.getProperty("action"))) join(tag);
        else openFriends(1, null);
    }

    // ------------------------------------------------------------------ inviting (pause menu)
    void openInvite() {
        if (hostingKey == null || net == null) {
            NovaActivity.postToast(t("Мир не открыт друзьям", "World not opened to friends"), t("Включите «Сеть» при создании мира", "Turn on Network when creating a world"));
            return;
        }
        if (busy) return;
        busy = true;
        worker(new Runnable() {
            @SuppressWarnings("unchecked")
            public void run() {
                try {
                    AccountClient.Result r = op("friends.list", null, null);
                    if (failed(r)) return;
                    StringBuilder a = new StringBuilder();
                    Object fr = r.data.get("friends");
                    if (fr instanceof List) for (Object o : (List<Object>) fr) {
                        if (!(o instanceof Map)) continue;
                        Map<String, Object> m = (Map<String, Object>) o;
                        if (!Boolean.TRUE.equals(m.get("online"))) continue;
                        a.append(clean(m.get("tag"))).append('\t').append(status(m)).append('\n');
                    }
                    NovaActivity.nativeOpenNova(6, a.toString(), "");
                } finally {
                    busy = false;
                }
            }
        });
    }

    /** "V|tag" */
    void inviteResult(final String tag) {
        if (tag.equals("x") || tag.length() == 0) return;
        worker(new Runnable() {
            public void run() {
                AccountClient.Result r = op("invite", "tag", tag);
                if (failed(r)) return;
                NovaActivity.accToastPublic(r.msg);
            }
        });
    }

    /** the JSON UI files that depend on the state: start (account) and pause (friends) screens */
    static byte[] serveUi(String np, byte[] b) {
        if (b == null || np == null) return b;
        try {
            if (np.endsWith("ui/start_screen.json")) {
                if (!NovaActivity.signedIn()) return b;
                String s = new String(b, "UTF-8");
                s = s.replace("false /*nova:in*/", "true /*nova:in*/").replace("true /*nova:out*/", "false /*nova:out*/");
                return s.getBytes("UTF-8");
            }
            if (np.endsWith("ui/pause_screen_trial.json")) {
                Log.i(TAG, "pause screen served, friends button " + pauseFriends);
                if (pauseFriends) return b;
                String s = new String(b, "UTF-8");
                s = s.replace("false /*nova:net*/", "true /*nova:net*/").replace("[ 0, 108 ] /*nova:quit*/", "[ 0, 80 ] /*nova:quit*/")
                        .replace("[ 180, 146 ] /*nova:panel*/", "[ 180, 118 ] /*nova:panel*/");
                return s.getBytes("UTF-8");
            }
        } catch (Exception ignored) { }
        return b;
    }
}

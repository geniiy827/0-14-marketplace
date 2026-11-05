package com.nova.engine.ui;

import com.nova.engine.render.Hud;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * play.play_screen of 0.14 (ui/play_screen.json): default_worlds_panel with the Worlds and Friends tabs
 * (Realms hidden), the local world list (single player) and the external server list (network_world_item with
 * world_external: name, details, player count, online / offline / unavailable dot, remove X), footer buttons
 * (create world / back, add server / back). Servers are kept like the original's external_servers.txt
 * (name:address:port per line) and pinged with the RakNet unconnected ping.
 */
public final class PlayScreen {
    public interface Host {
        void addServer(PlayScreen s);
        void playLocal();
        void newLocal();
        void join(String host, int port);
        void back();
    }

    public static final class Server {
        public String name, host; public int port;
        volatile String motd; volatile int online = -1, max = -1; volatile int state;   // 0 unknown 1 online 2 offline
    }

    private final JsonUi ui;
    private JsonUi.Node root;
    private boolean friends;
    private final List<Server> servers = new ArrayList<Server>();
    private final List<JsonUi.Node> items = new ArrayList<JsonUi.Node>();
    private final List<Server> itemServer = new ArrayList<Server>();
    private final File file;
    private final Host host;
    private float scroll, lastY, downY;
    private boolean dragging;
    private float lw = -1, lh = -1;
    private JsonUi.Node area;
    public String localName = "My World";
    private long lastPing;

    public PlayScreen(JsonUi ui, File dir, Host host) {
        this.ui = ui; this.host = host;
        file = new File(dir, "external_servers.txt");
        load();
        rebuild();
    }

    // ------------------------------------------------------------------ servers
    private void load() {
        servers.clear();
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(file));
            String l;
            while ((l = r.readLine()) != null) {
                String[] p = l.split(":");
                if (p.length < 3) continue;
                Server s = new Server();
                s.name = p[p.length - 3]; s.host = p[p.length - 2];
                try { s.port = Integer.parseInt(p[p.length - 1].trim()); } catch (Exception e) { s.port = 19132; }
                servers.add(s);
            }
            r.close();
        } catch (Exception ignored) { }
    }

    private void save() {
        try {
            java.io.FileWriter w = new java.io.FileWriter(file);
            int i = 0;
            for (Server s : servers) w.write((i++) + ":" + s.name.replace(":", " ") + ":" + s.host + ":" + s.port + "\n");
            w.close();
        } catch (Exception ignored) { }
    }

    public void addServer(String name, String address, int port) {
        Server s = new Server();
        s.name = name.isEmpty() ? address : name; s.host = address; s.port = port;
        servers.add(s);
        save();
        friends = true;
        rebuild();
        lastPing = 0;
    }

    private void pingAll() {
        final List<Server> list = new ArrayList<Server>(servers);
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (Server s : list) {
                    String m = com.nova.engine.net.RakNet.ping(s.host, s.port, 1500);
                    if (m == null) { s.state = 2; continue; }
                    String[] p = m.split(";");                     // MCPE;motd;protocol;version;online;max
                    s.motd = p.length > 1 ? p[1] : m;
                    try { s.online = Integer.parseInt(p[4]); s.max = Integer.parseInt(p[5]); } catch (Exception ignored) { }
                    s.state = 1;
                }
            }
        }, "ping");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ tree
    private void rebuild() {
        root = ui.build("play.default_worlds_panel");
        JsonUi.Node rl = root.find("realms");
        if (rl != null && rl.parent == root) rl.hide();
        for (JsonUi.Node k : root.kids) {
            if (k.name.equals("worlds")) { if (friends) k.hide(); else k.show(); }
            if (k.name.equals("friends")) { if (friends) k.show(); else k.hide(); }
        }
        JsonUi.Node content = root.find(friends ? "friends_content" : "worlds_content");
        area = content == null ? null : content.find(friends ? "normal_stroke_button" : "button");
        items.clear(); itemServer.clear();
        if (friends) {
            for (Server s : servers) {
                Map<String, Object> d = new HashMap<String, Object>();
                d.put("#header", s.name);
                d.put("#world_details", s.state == 1 && s.motd != null ? s.motd : s.host + ":" + s.port);
                d.put("#player_count", s.online >= 0 ? s.online + "/" + s.max : "");
                d.put("#game_type_external", true); d.put("#game_type_remote", false); d.put("#game_type_xbox_live", false);
                d.put("#game_online", s.state == 1); d.put("#game_offline", s.state == 2); d.put("#game_unavailable", s.state == 0);
                items.add(ui.build("play.network_world_item", d));
                itemServer.add(s);
            }
        } else {
            Map<String, Object> d = new HashMap<String, Object>();
            d.put("#header", localName);
            d.put("#description_1", new java.text.SimpleDateFormat("dd.MM.yy").format(new java.util.Date()));
            d.put("#description_2", ui.tr("createWorldScreen.gameMode.creative"));
            d.put("#file_size", "");
            items.add(ui.build("play.local_world_item", d));
            itemServer.add(null);
        }
        lw = -1;
    }

    private int lastStates;

    public void render(Hud hud, float W, float H, float s) {
        long now = System.currentTimeMillis();
        if (friends && now - lastPing > 5000) { lastPing = now; pingAll(); }
        int st = 0;
        for (Server sv : servers) st = st * 31 + sv.state * 7 + sv.online;
        if (st != lastStates) { lastStates = st; if (friends) rebuild(); }
        if (W != lw || H != lh) { lw = W; lh = H; ui.layout(root, 0, 0, W, H); }
        ui.draw(root, hud, s);
        if (area == null) return;
        // the scrolling grid: items 30 high, 95% - 5px wide, clipped to the list background
        float ax = area.x() + 3, ay = area.y() + 3, aw = area.w() - 6, ah = area.h() - 6;
        float total = items.size() * 30;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - ah)));
        hud.flush();
        hud.clip(ax * s, ay * s, aw * s, ah * s);
        for (int i = 0; i < items.size(); i++) {
            JsonUi.Node it = items.get(i);
            ui.layout(it, ax, ay + i * 30 - scroll, (aw) * (friends ? 0.95f : 1f) - (friends ? 5 : 0), 30);
            ui.draw(it, hud, s);
        }
        hud.flush();
        hud.noClip();
    }

    /** returns true when the touch was used */
    public void touch(int action, float x, float y) {
        if (action == 0) { downY = lastY = y; dragging = false; }
        else if (action == 1) {
            if (Math.abs(y - downY) > 6) dragging = true;
            if (dragging) { scroll -= y - lastY; lastY = y; }
        }
        if (dragging && action != 2) { for (JsonUi.Node it : items) ui.touch(it, 2, -1, -1); return; }
        if (dragging) { dragging = false; return; }
        // tabs: tap on the tab images
        if (action == 2) {
            for (JsonUi.Node k : root.kids) {
                if (!k.visible) continue;
                JsonUi.Node w = null, f = null;                   // the tab images are direct children of the tab panel
                for (JsonUi.Node c : k.kids) { if (c.name.equals("worlds")) w = c; if (c.name.equals("friends")) f = c; }
                if (w != null && in(w, x, y)) { if (friends) { friends = false; rebuild(); } return; }
                if (f != null && in(f, x, y)) { if (!friends) { friends = true; rebuild(); lastPing = 0; } return; }
            }
        }
        String r = ui.touch(root, action, x, y);
        if (r != null) {
            if (r.equals("button.menu_network_add_external")) host.addServer(this);
            else if (r.equals("button.menu_local_world_create")) host.newLocal();
            else if (r.contains("exit") || r.contains("back") || r.contains("cancel")) host.back();
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            String q = ui.touch(items.get(i), action, x, y);
            if (q == null) continue;
            Server s = itemServer.get(i);
            JsonUi.Node pressed = ui.lastPressed;
            if (s == null) { host.playLocal(); return; }
            if (pressed != null && pressed.name().contains("remove")) { servers.remove(s); save(); rebuild(); return; }
            host.join(s.host, s.port);
            return;
        }
    }

    private static boolean in(JsonUi.Node n, float x, float y) { return x >= n.x() && y >= n.y() && x < n.x() + n.w() && y < n.y() + n.h(); }
}

package com.nova.engine.ui;

import android.content.res.AssetManager;

import com.nova.engine.render.Hud;
import com.nova.engine.render.Panorama;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The title screen of 0.14.3: CubemapBackgroundScreen (panorama + gradient) under ui/start_screen.json
 * (start.start_screen), with the splash text of SplashTextRenderer. Only "Play" does something for now.
 */
public final class StartMenu {
    private final JsonUi ui;
    private final JsonUi.Node root;
    private final Panorama pano = new Panorama();
    private String splash = "";
    private double splashTime;
    private float scale = 1f;
    private int lw = -1, lh = -1;

    public StartMenu(AssetManager am, String lang, float diagonalInches) {
        ui = new JsonUi(am, lang);
        root = ui.build("start.start_screen");
        pano.init(am);
        this.diag = diagonalInches;
        try {                                                    // SplashTextRenderer: a random line of splashes.json
            java.io.InputStream in = am.open("splashes.json");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            JSONArray a = JsonUi.parse(bo.toString("UTF-8")).getJSONArray("splashes");
            if (a.length() > 0) splash = a.getString(new java.util.Random().nextInt(a.length()));
        } catch (Exception ignored) { }
        ui.customs.put("splash_text_renderer", new JsonUi.Custom() {
            public void render(JsonUi.Node n, Hud hud, float s) {
                // 1.3 + sin(t * 7.222)^4 * 0.06, x 100 / (line length + 32), rotated -20, yellow, centred on the control
                double sn = Math.sin(splashTime * 7.222);
                float k = (float) (1.3 + Math.pow(sn, 4) * 0.06) * (100f / (hud.fontWidth(splash) + 32f));
                float cx = (n.x + n.w * 0.5f) * s, cy = (n.y + n.h * 0.5f) * s;
                hud.transform(cx, cy, -20f, k * s);
                hud.fontText(splash, -hud.fontWidth(splash) * 0.5f, -4f, 1f, 0xFFFFFF00, true);
                hud.flush();
                hud.noTransform();
            }
        });
    }

    private final float diag;

    /**
     * MinecraftClient::calculateGuiScale (small screens): width < 1000 -> 3 at >= 800, 2 at >= 400, else 1;
     * otherwise 6 (8 above 3600) when the screen is over 15 inches or wider than 2000, else 4. Values 1..8.
     */
    static float guiScale(int w, float inches) {
        if (w < 1000) return w >= 800 ? 3 : w >= 400 ? 2 : 1;
        if (inches > 15 || w > 2000) return w > 3600 ? 8 : 6;
        return 4;
    }

    /** draws the whole screen; returns nothing, actions come from touch() */
    public void render(Hud hud, int w, int h, float dt) {
        splashTime += dt;
        if (w != lw || h != lh) {
            lw = w; lh = h;
            scale = guiScale(Math.max(w, h), diag);
            ui.layout(root, 0, 0, w / scale, h / scale);
        }
        pano.render(dt);
        hud.begin(w, h);
        hud.gradient(0, 0, w, h, 0x59FFFFFF, 0x59000000);   // fillGradient (1,1,1,0.35) -> (0,0,0,0.35)
        ui.draw(root, hud, scale);
        hud.end();
    }

    /** 0 down, 1 move, 2 up (screen pixels); returns the pressed button name (e.g. "button.menu_play") */
    public String touch(int action, float x, float y) {
        return ui.touch(root, action, x / scale, y / scale);
    }
}

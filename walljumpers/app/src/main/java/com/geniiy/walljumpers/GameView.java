package com.geniiy.walljumpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

public class GameView extends View {

    // ------------------------------------------------------------ states
    private static final int MENU = 0, SKINS = 1, PLAY = 2, PAUSE = 3, DEAD = 4, OVER = 5;
    private int state = MENU;

    private static final int B_PLAY = 1, B_SKINS = 2, B_SOUND = 3, B_BACK = 4, B_RESUME = 5,
            B_MENU = 6, B_RETRY = 7, B_CONT = 8, B_PAUSE = 9, B_SKIN = 100;

    // ------------------------------------------------------------ virtual screen
    private static final int VW = 180;
    private int VH = 400;
    private float S = 1;
    private int sw, sh;
    private Bitmap buf;
    private Canvas bc;
    private final RectF dst = new RectF();

    // ------------------------------------------------------------ tuning
    private static final float PW = 8, PH = 10;
    private static final float VX = 150, VY = 255, G = 720, HOLD_G = 0.4f, HOLD_T = 0.26f;
    private static final float VFLIP = 230, MAX_FALL = 420, FLIP_TIME = 0.38f;
    private static final float GROUND = 40;
    private static final int CONTINUE_COST = 30;

    private static final int T_NORMAL = 0, T_ICE = 1, T_CRUMBLE = 2, T_MOVING = 3, T_SPIKES = 4;

    // ------------------------------------------------------------ biomes
    private static final String[] BIOME_NAMES = {"Бамбуковая роща", "Закат", "Ночь", "Над облаками", "Космос"};
    private static final int[] BIOME_AT = {0, 20, 45, 75, 110};
    private static final int[] SKY_TOP = {0xFF7EC8F2, 0xFFFF7A59, 0xFF0B1A3A, 0xFF5B4BB7, 0xFF02010A};
    private static final int[] SKY_BOT = {0xFFE4F6FF, 0xFFFFC27A, 0xFF29406E, 0xFFF7B7D2, 0xFF1A0B3A};
    private static final int[] MT_FAR = {0xFF9CC9B4, 0xFFC0607A, 0xFF17284A, 0xFF8E7CC3, 0xFF140A2A};
    private static final int[] MT_NEAR = {0xFF6FAF8F, 0xFF8E3B5A, 0xFF0E1B35, 0xFF6D5AAE, 0xFF0B0518};
    private static final int[] CLOUD = {0xFFFFFFFF, 0xFFFFE3CC, 0xFF3B4C78, 0xFFFFFFFF, 0xFF2A1C4A};
    private static final float[] STAR_A = {0f, 0f, 1f, 0.35f, 1f};

    // ------------------------------------------------------------ world objects
    static final class Post {
        int index, side, type;
        float x, top, bot, spTop, spBot, amp, phase, crumbleT, fallY, fallVy;
        boolean fallen;
    }

    static final class Coin {
        float x, y, ph;
        boolean taken;
    }

    static final class Shuriken {
        float x, y, minX, maxX, v, rot;
    }

    static final class Part {
        float x, y, vx, vy, life, max, size;
        int color;
    }

    static final class Btn {
        final RectF r;
        final int id;

        Btn(RectF r, int id) {
            this.r = r;
            this.id = id;
        }
    }

    private final ArrayList<Post> posts = new ArrayList<>();
    private final ArrayList<Coin> coinList = new ArrayList<>();
    private final ArrayList<Shuriken> stars = new ArrayList<>();
    private final ArrayList<Part> parts = new ArrayList<>();
    private final ArrayList<Btn> btns = new ArrayList<>();

    // ------------------------------------------------------------ player
    private static final int P_CLING = 0, P_AIR = 1, P_GROUND = 2;
    private int pState;
    private float px, py, vx, vy;
    private int facing = 1;
    private boolean holding, canFlip;
    private float holdT, flipT = -1, bufferT, invuln;
    private Post clingPost, lastPost;
    private float clingOff;

    // ------------------------------------------------------------ run
    private int score, runCoins;
    private boolean usedContinue, newBestShown, isNewBest;
    private float camY, time, shake, deadT, deadRot;
    private float biomeF;
    private int biomeShown;
    private float bannerT;
    private String bannerText = "";
    private String msg = "";
    private float msgT;

    // generator
    private int genIndex;
    private Post genPrev, genPrev2;

    // ------------------------------------------------------------ persistent
    private final SharedPreferences prefs;
    private int coins, best, owned, skin;
    private boolean sound;

    // ------------------------------------------------------------ misc
    private final Random rnd = new Random();
    private final Sfx sfx;
    private final Paint pix = new Paint();
    private final Paint bp = new Paint();
    private final Paint skyPaint = new Paint();
    private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Typeface font;
    private int lastSkyTop, lastSkyBot;
    private float[] starX, starY;
    private float[][] clouds;
    private long lastNanos;
    private Btn downBtn;

    public GameView(Context ctx) {
        super(ctx);
        Sprites.init();
        prefs = ctx.getSharedPreferences("walljumpers", Context.MODE_PRIVATE);
        coins = prefs.getInt("coins", 0);
        best = prefs.getInt("best", 0);
        owned = prefs.getInt("owned", 1);
        skin = prefs.getInt("skin", 0);
        if (skin < 0 || skin >= Sprites.SKINS.length) skin = 0;
        sound = prefs.getBoolean("sound", true);
        sfx = new Sfx(ctx);
        sfx.on = sound;

        pix.setFilterBitmap(false);
        pix.setAntiAlias(false);
        pix.setDither(false);
        bp.setAntiAlias(false);
        Typeface tf = Typeface.create("sans-serif-black", Typeface.NORMAL);
        font = tf != null ? tf : Typeface.DEFAULT_BOLD;
        ui.setTypeface(font);
        setHapticFeedbackEnabled(true);
    }

    private void save() {
        prefs.edit().putInt("coins", coins).putInt("best", best).putInt("owned", owned)
                .putInt("skin", skin).putBoolean("sound", sound).apply();
    }

    // ================================================================ lifecycle

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        sw = w;
        sh = h;
        S = w / (float) VW;
        VH = (int) Math.ceil(h / S);
        buf = Bitmap.createBitmap(VW, VH, Bitmap.Config.ARGB_8888);
        bc = new Canvas(buf);
        lastSkyTop = lastSkyBot = 0;

        Random r = new Random(42);
        starX = new float[80];
        starY = new float[80];
        for (int i = 0; i < starX.length; i++) {
            starX[i] = r.nextFloat() * VW;
            starY[i] = r.nextFloat() * VH;
        }
        clouds = new float[8][];
        for (int i = 0; i < clouds.length; i++) {
            clouds[i] = new float[]{r.nextFloat() * (VW + 60), r.nextFloat() * (VH + 60),
                    3 + r.nextFloat() * 6, 0.7f + r.nextFloat() * 0.7f};
        }
        if (state == PLAY || state == PAUSE) state = PAUSE;
        else newGame();
    }

    void onPauseGame() {
        holding = false;
        if (state == PLAY) state = PAUSE;
    }

    boolean onBack() {
        switch (state) {
            case PLAY:
                state = PAUSE;
                return true;
            case PAUSE:
                holding = false;
                state = PLAY;
                return true;
            case SKINS:
            case OVER:
                newGame();
                state = MENU;
                return true;
            default:
                return false;
        }
    }

    // ================================================================ world setup

    private void newGame() {
        posts.clear();
        coinList.clear();
        stars.clear();
        parts.clear();
        Post p0 = new Post();
        p0.index = 0;
        p0.side = -1;
        p0.type = T_NORMAL;
        p0.x = cxAt(0) - 36;
        p0.top = -60;
        p0.bot = GROUND;
        posts.add(p0);
        genIndex = 0;
        genPrev = p0;
        genPrev2 = null;

        pState = P_CLING;
        clingPost = p0;
        lastPost = p0;
        clingOff = 0;
        facing = 1;
        px = p0.x + PW / 2;
        py = 10;
        vx = vy = 0;
        holding = false;
        canFlip = true;
        flipT = -1;
        bufferT = 0;
        invuln = 0;

        score = 0;
        runCoins = 0;
        usedContinue = false;
        newBestShown = false;
        isNewBest = false;
        camY = GROUND + 50 - VH;
        shake = 0;
        biomeF = 0;
        biomeShown = 0;
        bannerT = 0;
        ensureGenerated();
    }

    private static float sin(float a) {
        return (float) Math.sin(a);
    }

    private float rr(float a, float b) {
        return a + rnd.nextFloat() * (b - a);
    }

    private float cxAt(float y) {
        return VW / 2f + 20 * sin(y * 0.005f) + 8 * sin(y * 0.013f + 1);
    }

    private static float yc(Post p) {
        return (p.top + p.bot) / 2;
    }

    private void ensureGenerated() {
        while (yc(genPrev) > camY - VH) genPost();
        float limit = camY + VH + 90;
        for (int i = posts.size() - 1; i >= 0; i--) {
            Post p = posts.get(i);
            if (p != lastPost && p != clingPost && p.top + p.fallY > limit) posts.remove(i);
        }
        for (int i = coinList.size() - 1; i >= 0; i--) {
            if (coinList.get(i).y > limit) coinList.remove(i);
        }
        for (int i = stars.size() - 1; i >= 0; i--) {
            if (stars.get(i).y > limit) stars.remove(i);
        }
    }

    private void genPost() {
        int i = ++genIndex;
        float diff = Math.min(1f, i / 120f);
        float dy = rr(30, 44 + 22 * diff);
        float len = rr(48 - 18 * diff, 66 - 20 * diff);
        float hw = 34 + 16 * diff + rr(-4, 4);
        float prevYc = yc(genPrev);
        float c = prevYc - dy;
        // keep posts on the same wall from overlapping
        if (genPrev2 != null) {
            float gap = yc(genPrev2) - c;
            float maxLen = 2 * (gap - 7) - (genPrev2.bot - genPrev2.top);
            if (len > maxLen) len = Math.max(22, maxLen);
        }
        int side = -genPrev.side;
        float cx = cxAt(c);

        Post p = new Post();
        p.index = i;
        p.side = side;
        p.type = T_NORMAL;
        p.x = side < 0 ? cx - hw : cx + hw;
        p.top = c - len / 2;
        p.bot = c + len / 2;
        p.spTop = p.spBot = 0;

        if (i > 6) {
            float r = rnd.nextFloat();
            float pSp = 0.10f + 0.18f * diff;
            float pIce = i > 10 ? 0.05f + 0.12f * diff : 0;
            float pCr = i > 16 ? 0.05f + 0.12f * diff : 0;
            float pMv = i > 22 ? 0.05f + 0.12f * diff : 0;
            if (r < pSp && len >= 34) {
                p.type = T_SPIKES;
                float sl = len * rr(0.32f, 0.45f);
                if (rnd.nextBoolean()) {
                    p.spTop = p.top;
                    p.spBot = p.top + sl;
                } else {
                    p.spTop = p.bot - sl;
                    p.spBot = p.bot;
                }
            } else if (r < pSp + pIce) {
                p.type = T_ICE;
            } else if (r < pSp + pIce + pCr) {
                p.type = T_CRUMBLE;
            } else if (r < pSp + pIce + pCr + pMv) {
                p.type = T_MOVING;
                p.amp = rr(8, 16);
                p.phase = rr(0, 6.28f);
            }
        }
        posts.add(p);

        // coins along the way
        if (rnd.nextFloat() < 0.45f) {
            float mx = (genPrev.x + p.x) / 2;
            float my = (prevYc + c) / 2 - 12;
            if (rnd.nextFloat() < 0.3f) {
                addCoin(mx - 14, my + 5);
                addCoin(mx, my);
                addCoin(mx + 14, my + 5);
            } else {
                addCoin(mx, my);
            }
        }
        // patrolling shuriken
        if (i > 25 && rnd.nextFloat() < 0.07f + 0.08f * diff) {
            Shuriken s = new Shuriken();
            float sy = (prevYc + c) / 2;
            float scx = cxAt(sy);
            s.y = sy;
            s.minX = scx - hw + 13;
            s.maxX = scx + hw - 13;
            s.x = rr(s.minX, s.maxX);
            s.v = rr(35, 55 + 25 * diff) * (rnd.nextBoolean() ? 1 : -1);
            stars.add(s);
        }
        genPrev2 = genPrev;
        genPrev = p;
    }

    private void addCoin(float x, float y) {
        Coin c = new Coin();
        c.x = x;
        c.y = y;
        c.ph = rr(0, 6.28f);
        coinList.add(c);
    }

    private float off(Post p) {
        return p.type == T_MOVING ? p.amp * sin(time * 1.6f + p.phase) : 0;
    }

    private float top(Post p) {
        return p.top + off(p) + p.fallY;
    }

    private float bot(Post p) {
        return p.bot + off(p) + p.fallY;
    }

    private int biomeFor(int s) {
        for (int i = BIOME_AT.length - 1; i >= 0; i--) if (s >= BIOME_AT[i]) return i;
        return 0;
    }

    // ================================================================ input actions

    private void press() {
        if (pState == P_CLING || pState == P_GROUND) {
            jump();
        } else if (canFlip) {
            canFlip = false;
            vy = -VFLIP;
            holding = false;
            holdT = HOLD_T;
            flipT = 0;
            sfx.play(Sfx.FLIP);
            burst(px, py + 4, 6, 0xFFFFFFFF, 40, 0.3f);
        } else {
            bufferT = 0.12f;
        }
    }

    private void release() {
        holding = false;
    }

    private void jump() {
        pState = P_AIR;
        vx = facing * VX;
        vy = -VY;
        holding = true;
        holdT = 0;
        canFlip = true;
        flipT = -1;
        clingPost = null;
        sfx.play(Sfx.JUMP);
        burst(px - facing * 4, py + 3, 5, 0xFFFFFFFF, 30, 0.3f);
    }

    private void detach(float v) {
        pState = P_AIR;
        clingPost = null;
        vx = 0;
        vy = v;
        canFlip = true;
        holding = false;
        holdT = HOLD_T;
    }

    private void cling(Post p) {
        pState = P_CLING;
        clingPost = p;
        clingOff = off(p);
        float t = top(p), b = bot(p);
        if (py < t) py = t;
        if (py > b) py = b;
        facing = -p.side;
        vx = vy = 0;
        canFlip = true;
        holding = false;
        flipT = -1;
        sfx.play(Sfx.CLING);
        burst(p.x, py, 4, 0xFFEFEFEF, 25, 0.25f);
        if (hitsSpikes(p)) {
            die();
            return;
        }
        lastPost = p;
        if (p.index > score) {
            score = p.index;
            onScore();
        }
        if (bufferT > 0) {
            bufferT = 0;
            jump();
        }
    }

    private boolean hitsSpikes(Post p) {
        return p.type == T_SPIKES && py + PH / 2 - 2 > p.spTop && py - PH / 2 + 2 < p.spBot;
    }

    private void onScore() {
        if (score % 10 == 0) {
            sfx.play(Sfx.CHECK);
            burst(px, py, 14, 0xFFFFD166, 70, 0.6f);
        }
        if (!newBestShown && best > 0 && score > best) {
            newBestShown = true;
            banner("Новый рекорд!");
        }
        int b = biomeFor(score);
        if (b > biomeShown) {
            biomeShown = b;
            banner(BIOME_NAMES[b]);
        }
    }

    private void banner(String s) {
        bannerText = s;
        bannerT = 2.4f;
    }

    private void die() {
        if (state != PLAY) return;
        state = DEAD;
        deadT = 0;
        deadRot = 0;
        vy = -230;
        vx = 0;
        pState = P_AIR;
        shake = 0.35f;
        sfx.play(Sfx.DEATH);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        burst(px, py, 16, Sprites.SKINS[skin].body, 80, 0.7f);
    }

    private void gameOver() {
        state = OVER;
        if (score > best) {
            best = score;
            isNewBest = true;
        }
        save();
    }

    private void continueGame() {
        coins -= CONTINUE_COST;
        usedContinue = true;
        Post p = lastPost;
        if (!posts.contains(p)) posts.add(p);
        p.fallen = false;
        p.fallY = 0;
        p.fallVy = 0;
        p.crumbleT = 0;
        px = p.side < 0 ? p.x + PW / 2 : p.x - PW / 2;
        float t = top(p), b = bot(p);
        if (p.type == T_SPIKES) {
            py = (p.spTop - p.top > p.bot - p.spBot) ? (t + p.spTop) / 2 : (p.spBot + b) / 2;
        } else {
            py = (t + b) / 2;
        }
        pState = P_CLING;
        clingPost = p;
        clingOff = off(p);
        facing = -p.side;
        vx = vy = 0;
        canFlip = true;
        holding = false;
        flipT = -1;
        bufferT = 0;
        invuln = 1.5f;
        for (int i = stars.size() - 1; i >= 0; i--) {
            if (Math.abs(stars.get(i).y - py) < 70) stars.remove(i);
        }
        camY = py - VH * 0.55f;
        state = PLAY;
        save();
    }

    // ================================================================ update

    private void update(float dt) {
        time += dt;
        if (shake > 0) shake -= dt;
        if (bannerT > 0) bannerT -= dt;
        if (msgT > 0) msgT -= dt;
        biomeF += (biomeFor(score) - biomeF) * Math.min(1f, dt * 0.8f);
        updateParts(dt);

        if (state == PLAY) {
            int n = dt > 0.012f ? 2 : 1;
            float h = dt / n;
            for (int i = 0; i < n && state == PLAY; i++) physics(h);
            if (state != PLAY) return;
            float target = py - VH * 0.55f;
            if (target < camY) camY += (target - camY) * Math.min(1f, dt * 6f);
            ensureGenerated();
            if (py - PH / 2 > camY + VH + 6) die();
        } else if (state == DEAD) {
            deadT += dt;
            vy += G * dt;
            py += vy * dt;
            deadRot += dt * 720;
            if (deadT > 1.1f) gameOver();
        }
    }

    private void physics(float h) {
        if (invuln > 0) invuln -= h;
        if (bufferT > 0) bufferT -= h;
        if (flipT >= 0) {
            flipT += h;
            if (flipT > FLIP_TIME) flipT = -1;
        }
        for (Post p : posts) {
            if (p.fallen) {
                p.fallVy += G * h;
                p.fallY += p.fallVy * h;
            }
        }
        for (Shuriken s : stars) {
            s.x += s.v * h;
            if (s.x < s.minX) {
                s.x = s.minX;
                s.v = -s.v;
            }
            if (s.x > s.maxX) {
                s.x = s.maxX;
                s.v = -s.v;
            }
            s.rot += h * 12;
        }

        if (pState == P_CLING) {
            Post p = clingPost;
            if (p == null || p.fallen) {
                detach(0);
            } else {
                float o = off(p);
                py += o - clingOff;
                clingOff = o;
                float slide = p.type == T_ICE ? 80 : (p.type == T_MOVING ? 8 : 14);
                py += slide * h;
                if (p.type == T_CRUMBLE) {
                    p.crumbleT += h;
                    if (p.crumbleT > 0.6f) {
                        p.fallen = true;
                        p.fallVy = 0;
                        sfx.play(Sfx.CRACK);
                        burst(p.x, py, 8, 0xFFC9A66B, 50, 0.5f);
                        detach(0);
                    }
                }
                if (pState == P_CLING) {
                    if (py + PH / 2 > GROUND) py = GROUND - PH / 2;
                    if (py > bot(p)) detach(slide);
                    else if (hitsSpikes(p)) {
                        die();
                        return;
                    }
                }
            }
        } else if (pState == P_AIR) {
            float g = G;
            if (holding && vy < 0 && holdT < HOLD_T) {
                g = G * HOLD_G;
                holdT += h;
            }
            vy = Math.min(MAX_FALL, vy + g * h);
            float ox = px;
            px += vx * h;
            py += vy * h;

            if (vy > 0 && py + PH / 2 >= GROUND && py - PH / 2 < GROUND + 6) {
                py = GROUND - PH / 2;
                vy = 0;
                vx = 0;
                pState = P_GROUND;
                facing = px < cxAt(GROUND) ? 1 : -1;
                canFlip = true;
                flipT = -1;
                sfx.play(Sfx.CLING);
                burst(px, py + 5, 5, 0xFFFFFFFF, 30, 0.3f);
                if (bufferT > 0) {
                    bufferT = 0;
                    jump();
                }
            }

            if (pState == P_AIR && vx != 0) {
                for (int i = 0; i < posts.size(); i++) {
                    Post p = posts.get(i);
                    if (p.fallen) continue;
                    float f = p.x;
                    if (vx > 0 && p.side > 0) {
                        if (ox + PW / 2 <= f + 0.01f && px + PW / 2 >= f && overlapY(p)) {
                            px = f - PW / 2;
                            cling(p);
                            break;
                        }
                    } else if (vx < 0 && p.side < 0) {
                        if (ox - PW / 2 >= f - 0.01f && px - PW / 2 <= f && overlapY(p)) {
                            px = f + PW / 2;
                            cling(p);
                            break;
                        }
                    }
                }
                if (state != PLAY) return;
            }
        }

        for (Coin c : coinList) {
            if (!c.taken && Math.abs(c.x - px) < 8 && Math.abs(c.y - py) < 9) {
                c.taken = true;
                coins++;
                runCoins++;
                sfx.play(Sfx.COIN);
                burst(c.x, c.y, 6, 0xFFFFD166, 40, 0.35f);
            }
        }
        if (invuln <= 0) {
            for (Shuriken s : stars) {
                float dx = s.x - px, dy = s.y - py;
                if (dx * dx + dy * dy < 49) {
                    die();
                    return;
                }
            }
        }
    }

    private boolean overlapY(Post p) {
        return py >= top(p) - PH / 2 + 1 && py <= bot(p) + PH / 2 - 1;
    }

    private void burst(float x, float y, int n, int color, float speed, float life) {
        for (int i = 0; i < n; i++) {
            Part q = new Part();
            q.x = x;
            q.y = y;
            float a = rr(0, 6.28f);
            float sp = rr(0.3f, 1f) * speed;
            q.vx = (float) Math.cos(a) * sp;
            q.vy = (float) Math.sin(a) * sp - speed * 0.3f;
            q.life = q.max = rr(life * 0.6f, life);
            q.color = color;
            q.size = rnd.nextFloat() < 0.3f ? 2 : 1;
            parts.add(q);
        }
    }

    private void updateParts(float dt) {
        for (int i = parts.size() - 1; i >= 0; i--) {
            Part q = parts.get(i);
            q.life -= dt;
            if (q.life <= 0) {
                parts.remove(i);
                continue;
            }
            q.vy += 180 * dt;
            q.x += q.vx * dt;
            q.y += q.vy * dt;
        }
    }

    // ================================================================ draw

    @Override
    protected void onDraw(Canvas c) {
        if (buf == null) return;
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : Math.min(0.033f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        if (state != PAUSE && state != OVER) update(dt);
        else time += dt;

        renderWorld();
        float ox = 0, oy = 0;
        if (shake > 0) {
            ox = (rnd.nextFloat() - 0.5f) * shake * 24;
            oy = (rnd.nextFloat() - 0.5f) * shake * 24;
        }
        dst.set(ox, oy, ox + VW * S, oy + VH * S);
        c.drawBitmap(buf, null, dst, pix);

        btns.clear();
        switch (state) {
            case MENU:
                drawMenu(c);
                break;
            case SKINS:
                drawSkins(c);
                break;
            case PLAY:
            case DEAD:
                drawHud(c);
                break;
            case PAUSE:
                drawHud(c);
                drawPause(c);
                break;
            case OVER:
                drawHud(c);
                drawOver(c);
                break;
        }
        postInvalidateOnAnimation();
    }

    private static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }

    private static float mod(float a, float m) {
        float r = a % m;
        return r < 0 ? r + m : r;
    }

    private void renderWorld() {
        int b0 = (int) Math.floor(biomeF);
        if (b0 < 0) b0 = 0;
        if (b0 > BIOME_AT.length - 1) b0 = BIOME_AT.length - 1;
        int b1 = Math.min(BIOME_AT.length - 1, b0 + 1);
        float t = Math.max(0, Math.min(1, biomeF - b0));
        float h = -camY;

        int top = lerpColor(SKY_TOP[b0], SKY_TOP[b1], t);
        int bottom = lerpColor(SKY_BOT[b0], SKY_BOT[b1], t);
        if (top != lastSkyTop || bottom != lastSkyBot) {
            skyPaint.setShader(new LinearGradient(0, 0, 0, VH, top, bottom, Shader.TileMode.CLAMP));
            lastSkyTop = top;
            lastSkyBot = bottom;
        }
        bc.drawRect(0, 0, VW, VH, skyPaint);

        // stars
        float starA = STAR_A[b0] + (STAR_A[b1] - STAR_A[b0]) * t;
        if (starA > 0.02f) {
            for (int i = 0; i < starX.length; i++) {
                float y = mod(starY[i] + h * 0.02f, VH);
                float tw = 0.55f + 0.45f * sin(time * 2.3f + i * 1.7f);
                bp.setColor(Color.WHITE);
                bp.setAlpha((int) (255 * starA * tw));
                float s = (i % 7 == 0) ? 2 : 1;
                bc.drawRect(starX[i], y, starX[i] + s, y + s, bp);
            }
            bp.setAlpha(255);
        }

        // sun (sinks during the sunset), moon, planet
        float sunA = Math.max(0, 1 - Math.max(0, biomeF - 1) * 1.5f);
        if (sunA > 0.02f) {
            float sy = VH * 0.14f + Math.min(biomeF, 1.6f) * VH * 0.25f;
            bp.setColor(0xFFFFE9A8);
            bp.setAlpha((int) (255 * sunA));
            bc.drawCircle(VW * 0.78f, sy, 11, bp);
            bp.setColor(0xFFFFF7D6);
            bp.setAlpha((int) (255 * sunA));
            bc.drawCircle(VW * 0.78f, sy, 8, bp);
            bp.setAlpha(255);
        }
        float moonA = Math.max(0, 1 - Math.abs(biomeF - 2) * 1.2f);
        if (moonA > 0.02f) {
            bp.setColor(0xFFFFF4D2);
            bp.setAlpha((int) (255 * moonA));
            bc.drawCircle(VW * 0.74f, VH * 0.17f, 12, bp);
            bp.setColor(0xFFE8DDB5);
            bp.setAlpha((int) (255 * moonA));
            bc.drawCircle(VW * 0.71f, VH * 0.16f, 3, bp);
            bc.drawCircle(VW * 0.77f, VH * 0.2f, 2, bp);
            bp.setAlpha(255);
        }
        float planetA = Math.max(0, 1 - Math.abs(biomeF - 4) * 1.2f);
        if (planetA > 0.02f) {
            bp.setColor(0xFFC77DFF);
            bp.setAlpha((int) (255 * planetA));
            bc.drawCircle(VW * 0.27f, VH * 0.22f, 18, bp);
            bp.setColor(0xFF9D4EDD);
            bp.setAlpha((int) (255 * planetA));
            bc.drawRect(VW * 0.27f - 16, VH * 0.22f + 4, VW * 0.27f + 16, VH * 0.22f + 8, bp);
            bp.setColor(0xFFFFD166);
            bp.setAlpha((int) (255 * planetA));
            bc.drawRect(VW * 0.27f - 28, VH * 0.22f - 1, VW * 0.27f + 28, VH * 0.22f + 1, bp);
            bp.setAlpha(255);
        }

        // mountains (they sink as you climb)
        drawMountains(VH - 70 + h * 0.06f, 34, 16, 9, 0.045f, 0.11f, lerpColor(MT_FAR[b0], MT_FAR[b1], t), true);
        drawMountains(VH - 25 + h * 0.14f, 18, 9, 5, 0.07f, 0.21f, lerpColor(MT_NEAR[b0], MT_NEAR[b1], t), false);

        // clouds
        int cc = lerpColor(CLOUD[b0], CLOUD[b1], t);
        for (float[] cl : clouds) {
            float y = mod(cl[1] + h * 0.3f, VH + 60) - 30;
            float x = mod(cl[0] + time * cl[2], VW + 60) - 30;
            drawCloud(x, y, cl[3], cc);
        }

        // world layer
        bc.save();
        bc.translate(0, -Math.round(camY));
        drawGround();
        for (Post p : posts) drawRopes(p);
        for (Post p : posts) drawPost(p);
        for (Coin c : coinList) if (!c.taken) drawCoin(c);
        for (Shuriken s : stars) drawShuriken(s);
        for (Part q : parts) {
            bp.setColor(q.color);
            bp.setAlpha((int) (255 * Math.min(1, q.life / q.max * 1.5f)));
            bc.drawRect(q.x, q.y, q.x + q.size, q.y + q.size, bp);
        }
        bp.setAlpha(255);
        drawPlayer();
        bc.restore();
    }

    private void drawMountains(float base, float a0, float a1, float a2, float f1, float f2, int color, boolean pagoda) {
        if (base - a0 - a1 - a2 > VH) return;
        path.reset();
        path.moveTo(0, VH + 2);
        for (int x = 0; x <= VW; x += 3) {
            float y = base - (a0 + a1 * sin(x * f1 + 0.5f) + a2 * sin(x * f2 + 2));
            path.lineTo(x, y);
        }
        path.lineTo(VW, VH + 2);
        path.close();
        bp.setColor(color);
        bc.drawPath(path, bp);
        bc.drawRect(0, Math.min(base, VH), VW, VH, bp);
        if (pagoda) {
            float x = 132;
            float y = base - (a0 + a1 * sin(x * f1 + 0.5f) + a2 * sin(x * f2 + 2));
            bc.drawRect(x - 7, y - 4, x + 7, y + 2, bp);
            bc.drawRect(x - 5, y - 9, x + 5, y - 4, bp);
            bc.drawRect(x - 9, y - 10, x + 9, y - 9, bp);
            bc.drawRect(x - 3, y - 14, x + 3, y - 10, bp);
            bc.drawRect(x - 6, y - 15, x + 6, y - 14, bp);
            bc.drawRect(x, y - 19, x + 1, y - 15, bp);
        }
    }

    private void drawCloud(float x, float y, float s, int color) {
        bp.setColor(color);
        bp.setAlpha(220);
        bc.drawCircle(x, y, 6 * s, bp);
        bc.drawCircle(x + 8 * s, y - 3 * s, 8 * s, bp);
        bc.drawCircle(x + 17 * s, y, 6 * s, bp);
        bc.drawRect(x, y, x + 17 * s, y + 5 * s, bp);
        bp.setAlpha(255);
    }

    private void drawGround() {
        if (GROUND > camY + VH) return;
        bp.setColor(0xFF5B3A24);
        bc.drawRect(0, GROUND, VW, GROUND + 200, bp);
        bp.setColor(0xFF6E4830);
        for (int i = 0; i < 40; i++) {
            float x = (i * 53) % VW;
            float y = GROUND + 8 + (i * 29) % 60;
            bc.drawRect(x, y, x + 2, y + 1, bp);
        }
        bp.setColor(0xFF4CAF50);
        bc.drawRect(0, GROUND, VW, GROUND + 4, bp);
        bp.setColor(0xFF2E7D32);
        bc.drawRect(0, GROUND + 4, VW, GROUND + 5, bp);
        bp.setColor(0xFF66BB6A);
        for (int x = 2; x < VW; x += 7) bc.drawRect(x, GROUND - 2, x + 1, GROUND, bp);
        // stone lantern
        float lx = VW - 34;
        bp.setColor(0xFF8D99AE);
        bc.drawRect(lx - 2, GROUND - 14, lx + 2, GROUND, bp);
        bc.drawRect(lx - 6, GROUND - 22, lx + 6, GROUND - 14, bp);
        bc.drawRect(lx - 8, GROUND - 24, lx + 8, GROUND - 22, bp);
        bp.setColor(0xFFFFD166);
        bc.drawRect(lx - 3, GROUND - 20, lx + 3, GROUND - 16, bp);
    }

    private void drawRopes(Post p) {
        boolean check = p.index > 0 && p.index % 10 == 0;
        boolean rec = best > 0 && p.index == best;
        if (!check && !rec) return;
        float y = Math.round(yc(p));
        if (check) {
            bp.setColor(0xFF5A3A22);
            for (int x = 0; x < VW; x += 3) bc.drawRect(x, y, x + 2, y + 1, bp);
            for (int x = 12; x < VW; x += 30) {
                bp.setColor(0xFF5A3A22);
                bc.drawRect(x, y + 1, x + 1, y + 2, bp);
                bp.setColor(0xFFE63946);
                bc.drawRect(x - 1, y + 2, x + 2, y + 7, bp);
                bp.setColor(0xFFFFD166);
                bc.drawRect(x, y + 3, x + 1, y + 5, bp);
            }
        }
        if (rec) {
            float ry = check ? y - 3 : y;
            bp.setColor(0xFFFFD166);
            for (int x = 0; x < VW; x += 6) bc.drawRect(x, ry, x + 4, ry + 1, bp);
        }
    }

    private void drawPost(Post p) {
        float t = top(p), b = bot(p);
        if (b < camY - 10 || t > camY + VH + 10) return;
        float x0 = p.side < 0 ? p.x - 6 : p.x;
        if (p.type == T_CRUMBLE && p.crumbleT > 0 && !p.fallen) x0 += Math.round((rnd.nextFloat() - 0.5f) * 2);
        int main, dark, light, node;
        switch (p.type) {
            case T_ICE:
                main = 0xFF9FE6FF;
                dark = 0xFF5BB8E0;
                light = 0xFFEFFCFF;
                node = 0xFF4AA0C8;
                break;
            case T_CRUMBLE:
                main = 0xFFC9A66B;
                dark = 0xFF8F6F3E;
                light = 0xFFE6CF9E;
                node = 0xFF6E5230;
                break;
            default:
                main = 0xFF6ABF4B;
                dark = 0xFF3F8A35;
                light = 0xFFA6E07A;
                node = 0xFF2F6B28;
                break;
        }
        bp.setColor(main);
        bc.drawRect(x0, t, x0 + 6, b, bp);
        bp.setColor(light);
        bc.drawRect(x0 + 1, t, x0 + 2, b, bp);
        bp.setColor(dark);
        bc.drawRect(x0, t, x0 + 1, b, bp);
        bc.drawRect(x0 + 5, t, x0 + 6, b, bp);
        bp.setColor(node);
        for (float y = t + 6; y < b - 3; y += 11) bc.drawRect(x0, y, x0 + 6, y + 1, bp);
        bc.drawRect(x0, t, x0 + 6, t + 1, bp);
        bc.drawRect(x0, b - 1, x0 + 6, b, bp);

        if (p.type == T_NORMAL || p.type == T_MOVING) {
            bp.setColor(0xFF4CAF50);
            if (p.side < 0) {
                bc.drawRect(x0 - 4, t + 3, x0, t + 4, bp);
                bc.drawRect(x0 - 6, t + 2, x0 - 3, t + 3, bp);
            } else {
                bc.drawRect(x0 + 6, t + 3, x0 + 10, t + 4, bp);
                bc.drawRect(x0 + 9, t + 2, x0 + 12, t + 3, bp);
            }
        }
        if (p.type == T_MOVING) {
            bp.setColor(0xFFE63946);
            bc.drawRect(x0, t + 2, x0 + 6, t + 4, bp);
            bc.drawRect(x0, b - 4, x0 + 6, b - 2, bp);
        }
        if (p.type == T_CRUMBLE) {
            bp.setColor(0xFF5A4126);
            int len = (int) Math.max(1, b - t);
            for (int k = 0; k < 5; k++) {
                float y = t + (k * 37 + p.index * 11) % len;
                bc.drawRect(x0 + 2 + (k % 2), y, x0 + 3 + (k % 2), y + 3, bp);
            }
        }
        if (p.type == T_SPIKES) {
            float face = p.x;
            int dir = -p.side;
            bp.setColor(0xFF6B7280);
            bc.drawRect(dir > 0 ? face : face - 1, p.spTop, dir > 0 ? face + 1 : face, p.spBot, bp);
            bp.setColor(0xFFD9DEE7);
            for (float y = p.spTop; y + 4 <= p.spBot + 0.1f; y += 4) {
                path.reset();
                path.moveTo(face, y);
                path.lineTo(face + dir * 5, y + 2);
                path.lineTo(face, y + 4);
                path.close();
                bc.drawPath(path, bp);
            }
        }
    }

    private void drawCoin(Coin c) {
        if (c.y < camY - 10 || c.y > camY + VH + 10) return;
        float w = Math.abs((float) Math.cos(time * 4 + c.ph)) * 3 + 0.7f;
        bp.setColor(0xFFB8860B);
        bc.drawOval(c.x - w - 1, c.y - 4, c.x + w + 1, c.y + 4, bp);
        bp.setColor(0xFFFFCC33);
        bc.drawOval(c.x - w, c.y - 3, c.x + w, c.y + 3, bp);
        if (w > 1.8f) {
            bp.setColor(0xFF8A6508);
            bc.drawRect(c.x - 0.5f, c.y - 0.5f, c.x + 0.5f, c.y + 0.5f, bp);
        }
    }

    private void drawShuriken(Shuriken s) {
        if (s.y < camY - 10 || s.y > camY + VH + 10) return;
        path.reset();
        for (int k = 0; k < 8; k++) {
            float a = s.rot + k * (float) Math.PI / 4;
            float r = (k % 2 == 0) ? 6 : 2;
            float x = s.x + (float) Math.cos(a) * r, y = s.y + (float) Math.sin(a) * r;
            if (k == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        bp.setColor(0xFFCFD6E0);
        bc.drawPath(path, bp);
        bp.setColor(0xFF3D4452);
        bc.drawRect(s.x - 1, s.y - 1, s.x + 1, s.y + 1, bp);
    }

    private void drawPlayer() {
        if (state == PLAY && invuln > 0 && ((int) (time * 12)) % 2 == 0) return;
        Sprites.Skin sk = Sprites.SKINS[skin];
        Bitmap bmp = pState == P_CLING ? sk.cling : sk.air;
        float rot = 0;
        if (state == DEAD) rot = deadRot;
        else if (flipT >= 0) rot = facing * 360f * (flipT / FLIP_TIME);
        bc.save();
        bc.translate(Math.round(px), Math.round(py));
        if (rot != 0) bc.rotate(rot);
        if (facing < 0) bc.scale(-1, 1);
        bc.drawBitmap(bmp, -6, -7, pix);
        bc.restore();
    }

    // ================================================================ UI

    private void text(Canvas c, String s, float x, float y, float size, int color, Paint.Align al) {
        text(c, s, x, y, size, color, al, 255);
    }

    private void text(Canvas c, String s, float x, float y, float size, int color, Paint.Align al, int alpha) {
        ui.setTextSize(size);
        ui.setTextAlign(al);
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(size * 0.18f);
        ui.setStrokeJoin(Paint.Join.ROUND);
        ui.setColor(0xFF1B1226);
        ui.setAlpha(alpha);
        c.drawText(s, x, y, ui);
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(color);
        ui.setAlpha(alpha);
        c.drawText(s, x, y, ui);
    }

    private void button(Canvas c, int id, float cx, float cy, float w, float h, String label, int color) {
        RectF r = new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        float rad = h * 0.3f;
        boolean down = downBtn != null && downBtn.id == id;
        float push = down ? h * 0.06f : 0;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFF1B1226);
        c.drawRoundRect(r.left, r.top + h * 0.09f, r.right, r.bottom + h * 0.09f, rad, rad, fill);
        RectF rr2 = new RectF(r.left, r.top + push, r.right, r.bottom + push);
        fill.setColor(color);
        c.drawRoundRect(rr2, rad, rad, fill);
        fill.setColor(0x33FFFFFF);
        c.drawRoundRect(rr2.left + h * 0.08f, rr2.top + h * 0.08f, rr2.right - h * 0.08f,
                rr2.top + h * 0.42f, rad * 0.7f, rad * 0.7f, fill);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(h * 0.06f);
        fill.setColor(0xFF1B1226);
        c.drawRoundRect(rr2, rad, rad, fill);
        fill.setStyle(Paint.Style.FILL);
        text(c, label, cx, cy + push + h * 0.16f, h * 0.4f, Color.WHITE, Paint.Align.CENTER);
        btns.add(new Btn(r, id));
    }

    private void drawSprite(Canvas c, Bitmap bmp, float cx, float cy, float size) {
        dst.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        c.drawBitmap(bmp, null, dst, pix);
    }

    private void coinIcon(Canvas c, float x, float y, float r) {
        fill.setColor(0xFFB8860B);
        c.drawCircle(x, y, r * 1.15f, fill);
        fill.setColor(0xFFFFCC33);
        c.drawCircle(x, y, r, fill);
        fill.setColor(0xFF8A6508);
        c.drawRect(x - r * 0.25f, y - r * 0.25f, x + r * 0.25f, y + r * 0.25f, fill);
    }

    private void dim(Canvas c, int color) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        c.drawRect(0, 0, sw, sh, fill);
    }

    private void drawTitle(Canvas c, float y) {
        int[] cols = {0xFFFF595E, 0xFFFFCA3A, 0xFF8AC926, 0xFF1982C4, 0xFF6A4C93, 0xFFFF924C, 0xFFC77DFF};
        titleLine(c, "WALL", y, sw * 0.21f, cols, 0);
        titleLine(c, "JUMPERS", y + sw * 0.19f, sw * 0.15f, cols, 3);
    }

    private void titleLine(Canvas c, String s, float y, float size, int[] cols, int shift) {
        ui.setTextSize(size);
        float total = 0;
        float[] ws = new float[s.length()];
        for (int i = 0; i < s.length(); i++) {
            ws[i] = ui.measureText(s.substring(i, i + 1)) * 1.04f;
            total += ws[i];
        }
        float x = sw / 2f - total / 2;
        for (int i = 0; i < s.length(); i++) {
            float bob = sin(time * 3 + i * 0.6f) * size * 0.04f;
            c.save();
            c.rotate((i % 2 == 0 ? -5 : 5), x + ws[i] / 2, y);
            text(c, s.substring(i, i + 1), x + ws[i] / 2 + size * 0.04f, y + bob + size * 0.07f, size,
                    0xFF1B1226, Paint.Align.CENTER);
            text(c, s.substring(i, i + 1), x + ws[i] / 2, y + bob, size, cols[(i + shift) % cols.length],
                    Paint.Align.CENTER);
            c.restore();
            x += ws[i];
        }
    }

    private void drawMenu(Canvas c) {
        dim(c, 0x55000000);
        drawTitle(c, sh * 0.17f);
        Sprites.Skin sk = Sprites.SKINS[skin];
        float bob = sin(time * 3) * sw * 0.015f;
        drawSprite(c, sk.air, sw / 2f, sh * 0.41f + bob, sw * 0.26f);
        button(c, B_PLAY, sw / 2f, sh * 0.585f, sw * 0.62f, sw * 0.17f, "ИГРАТЬ", 0xFF2EC4B6);
        button(c, B_SKINS, sw / 2f, sh * 0.69f, sw * 0.62f, sw * 0.14f, "СКИНЫ", 0xFFFF9F1C);
        button(c, B_SOUND, sw / 2f, sh * 0.78f, sw * 0.62f, sw * 0.12f,
                sound ? "ЗВУК: ВКЛ" : "ЗВУК: ВЫКЛ", 0xFF6C63FF);
        text(c, "Рекорд: " + best, sw / 2f, sh * 0.87f, sw * 0.055f, 0xFFFFD166, Paint.Align.CENTER);
        coinIcon(c, sw / 2f - sw * 0.12f, sh * 0.915f - sw * 0.018f, sw * 0.025f);
        text(c, String.valueOf(coins), sw / 2f - sw * 0.08f, sh * 0.915f, sw * 0.055f, Color.WHITE, Paint.Align.LEFT);
    }

    private void drawSkins(Canvas c) {
        dim(c, 0xCC101020);
        text(c, "СКИНЫ", sw / 2f, sh * 0.09f, sw * 0.11f, 0xFFFFCA3A, Paint.Align.CENTER);
        coinIcon(c, sw / 2f - sw * 0.08f, sh * 0.135f - sw * 0.017f, sw * 0.025f);
        text(c, String.valueOf(coins), sw / 2f - sw * 0.04f, sh * 0.135f, sw * 0.055f, Color.WHITE, Paint.Align.LEFT);

        int n = Sprites.SKINS.length;
        float cw = sw * 0.43f, ch = sh * 0.145f, gx = sw * 0.04f, gy = sh * 0.015f;
        float startY = sh * 0.17f;
        for (int i = 0; i < n; i++) {
            int col = i % 2, row = i / 2;
            float x = sw / 2f - cw - gx / 2 + col * (cw + gx);
            if (i == n - 1 && n % 2 == 1) x = sw / 2f - cw / 2;
            float y = startY + row * (ch + gy);
            RectF r = new RectF(x, y, x + cw, y + ch);
            boolean own = (owned & (1 << i)) != 0;
            boolean sel = skin == i;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(sel ? 0xFF2EC4B6 : own ? 0xFF3A3F5C : 0xFF262A40);
            c.drawRoundRect(r, ch * 0.15f, ch * 0.15f, fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(sw * 0.006f);
            fill.setColor(sel ? 0xFFFFFFFF : 0xFF1B1226);
            c.drawRoundRect(r, ch * 0.15f, ch * 0.15f, fill);
            fill.setStyle(Paint.Style.FILL);
            Sprites.Skin sk = Sprites.SKINS[i];
            drawSprite(c, sk.air, x + ch * 0.45f, y + ch / 2, ch * 0.62f);
            text(c, sk.name, x + ch * 0.85f, y + ch * 0.42f, ch * 0.2f, Color.WHITE, Paint.Align.LEFT);
            if (sel) {
                text(c, "ВЫБРАН", x + ch * 0.85f, y + ch * 0.72f, ch * 0.17f, 0xFFFFFFFF, Paint.Align.LEFT);
            } else if (own) {
                text(c, "ВЫБРАТЬ", x + ch * 0.85f, y + ch * 0.72f, ch * 0.17f, 0xFF9AD1FF, Paint.Align.LEFT);
            } else {
                coinIcon(c, x + ch * 0.95f, y + ch * 0.66f, ch * 0.07f);
                text(c, String.valueOf(sk.price), x + ch * 1.07f, y + ch * 0.72f, ch * 0.18f,
                        coins >= sk.price ? 0xFFFFD166 : 0xFFFF6B6B, Paint.Align.LEFT);
            }
            btns.add(new Btn(r, B_SKIN + i));
        }
        if (msgT > 0) {
            text(c, msg, sw / 2f, sh * 0.85f, sw * 0.055f, 0xFFFF6B6B, Paint.Align.CENTER,
                    (int) (255 * Math.min(1, msgT * 2)));
        }
        button(c, B_BACK, sw / 2f, sh * 0.92f, sw * 0.5f, sw * 0.13f, "НАЗАД", 0xFF6C63FF);
    }

    private float worldToScreenY(float y) {
        return (y - Math.round(camY)) * S;
    }

    private void drawHud(Canvas c) {
        // checkpoint numbers and record label
        for (Post p : posts) {
            float y = worldToScreenY(Math.round(yc(p)));
            if (y < -50 || y > sh + 50) continue;
            if (p.index > 0 && p.index % 10 == 0) {
                text(c, String.valueOf(p.index), sw * 0.03f, y - sw * 0.015f, sw * 0.05f, 0xFFFFD166, Paint.Align.LEFT);
            }
            if (best > 0 && p.index == best) {
                text(c, "РЕКОРД", sw * 0.97f, y - sw * 0.015f, sw * 0.04f, 0xFFFFD166, Paint.Align.RIGHT);
            }
        }
        // tutorial hints near the start
        if (best < 12) {
            hint(c, "Нажми — прыжок от стены", -2);
            hint(c, "Зажми — прыжок выше", -95);
            hint(c, "Нажми в воздухе — сальто", -185);
        }

        float top = sh * 0.035f;
        text(c, String.valueOf(score), sw / 2f, top + sw * 0.12f, sw * 0.14f, Color.WHITE, Paint.Align.CENTER);
        coinIcon(c, sw * 0.06f, top + sw * 0.03f, sw * 0.025f);
        text(c, String.valueOf(coins), sw * 0.1f, top + sw * 0.048f, sw * 0.05f, Color.WHITE, Paint.Align.LEFT);
        text(c, "★ " + best, sw * 0.04f, top + sw * 0.11f, sw * 0.045f, 0xFFFFD166, Paint.Align.LEFT);

        // pause button
        float bx = sw * 0.9f, by = top + sw * 0.06f, br = sw * 0.06f;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFF1B1226);
        c.drawCircle(bx, by + br * 0.1f, br, fill);
        fill.setColor(0xFFFFFFFF);
        c.drawCircle(bx, by, br, fill);
        fill.setColor(0xFF1B1226);
        c.drawRect(bx - br * 0.4f, by - br * 0.4f, bx - br * 0.12f, by + br * 0.4f, fill);
        c.drawRect(bx + br * 0.12f, by - br * 0.4f, bx + br * 0.4f, by + br * 0.4f, fill);
        if (state == PLAY) btns.add(new Btn(new RectF(bx - br * 1.5f, by - br * 1.5f, bx + br * 1.5f, by + br * 1.5f), B_PAUSE));

        if (bannerT > 0) {
            int a = (int) (255 * Math.min(1, bannerT * 1.5f));
            text(c, bannerText, sw / 2f, sh * 0.24f, sw * 0.075f, 0xFFFFCA3A, Paint.Align.CENTER, a);
        }
    }

    private void hint(Canvas c, String s, float worldY) {
        float y = worldToScreenY(worldY);
        if (y < -40 || y > sh + 40) return;
        text(c, s, sw / 2f, y, sw * 0.048f, Color.WHITE, Paint.Align.CENTER);
    }

    private void drawPause(Canvas c) {
        dim(c, 0x99000000);
        text(c, "ПАУЗА", sw / 2f, sh * 0.36f, sw * 0.13f, Color.WHITE, Paint.Align.CENTER);
        button(c, B_RESUME, sw / 2f, sh * 0.5f, sw * 0.62f, sw * 0.16f, "ПРОДОЛЖИТЬ", 0xFF2EC4B6);
        button(c, B_MENU, sw / 2f, sh * 0.61f, sw * 0.62f, sw * 0.14f, "МЕНЮ", 0xFF6C63FF);
    }

    private void drawOver(Canvas c) {
        dim(c, 0x99000000);
        RectF panel = new RectF(sw * 0.07f, sh * 0.2f, sw * 0.93f, sh * 0.82f);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFF1B1226);
        c.drawRoundRect(panel.left, panel.top + sw * 0.02f, panel.right, panel.bottom + sw * 0.02f, sw * 0.05f, sw * 0.05f, fill);
        fill.setColor(0xFF2B2D42);
        c.drawRoundRect(panel, sw * 0.05f, sw * 0.05f, fill);

        text(c, "ТЫ СОРВАЛСЯ!", sw / 2f, sh * 0.275f, sw * 0.08f, 0xFFFFCA3A, Paint.Align.CENTER);
        text(c, String.valueOf(score), sw / 2f, sh * 0.385f, sw * 0.2f, Color.WHITE, Paint.Align.CENTER);
        if (isNewBest) {
            text(c, "НОВЫЙ РЕКОРД!", sw / 2f, sh * 0.44f, sw * 0.06f, 0xFF8AC926, Paint.Align.CENTER);
        } else {
            text(c, "Рекорд: " + best, sw / 2f, sh * 0.44f, sw * 0.055f, 0xFFFFD166, Paint.Align.CENTER);
        }
        coinIcon(c, sw / 2f - sw * 0.2f, sh * 0.49f - sw * 0.017f, sw * 0.025f);
        text(c, "+" + runCoins + "   всего " + coins, sw / 2f - sw * 0.16f, sh * 0.49f, sw * 0.05f,
                Color.WHITE, Paint.Align.LEFT);

        float y = sh * 0.57f;
        if (!usedContinue) {
            button(c, B_CONT, sw / 2f, y, sw * 0.7f, sw * 0.15f, "ПРОДОЛЖИТЬ · " + CONTINUE_COST,
                    coins >= CONTINUE_COST ? 0xFF8AC926 : 0xFF6B6F80);
            y += sh * 0.09f;
        }
        button(c, B_RETRY, sw / 2f, y, sw * 0.7f, sw * 0.15f, "ЕЩЁ РАЗ", 0xFF2EC4B6);
        y += sh * 0.085f;
        button(c, B_MENU, sw / 2f, y, sw * 0.7f, sw * 0.12f, "МЕНЮ", 0xFF6C63FF);
        if (msgT > 0) {
            text(c, msg, sw / 2f, sh * 0.87f, sw * 0.05f, 0xFFFF6B6B, Paint.Align.CENTER,
                    (int) (255 * Math.min(1, msgT * 2)));
        }
    }

    // ================================================================ touch

    private Btn hit(float x, float y) {
        for (int i = btns.size() - 1; i >= 0; i--) {
            Btn b = btns.get(i);
            if (b.r.contains(x, y)) return b;
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (state == PLAY) {
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                int idx = e.getActionIndex();
                Btn b = hit(e.getX(idx), e.getY(idx));
                if (b != null && b.id == B_PAUSE) {
                    sfx.play(Sfx.CLICK);
                    holding = false;
                    state = PAUSE;
                    return true;
                }
                press();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                release();
            }
            return true;
        }
        if (a == MotionEvent.ACTION_DOWN) {
            downBtn = hit(e.getX(), e.getY());
        } else if (a == MotionEvent.ACTION_UP) {
            Btn b = hit(e.getX(), e.getY());
            Btn d = downBtn;
            downBtn = null;
            if (b != null && d != null && b.id == d.id) onButton(b.id);
        } else if (a == MotionEvent.ACTION_CANCEL) {
            downBtn = null;
        }
        return true;
    }

    private void onButton(int id) {
        sfx.play(Sfx.CLICK);
        if (id >= B_SKIN) {
            int i = id - B_SKIN;
            Sprites.Skin sk = Sprites.SKINS[i];
            if ((owned & (1 << i)) != 0) {
                skin = i;
            } else if (coins >= sk.price) {
                coins -= sk.price;
                owned |= 1 << i;
                skin = i;
                sfx.play(Sfx.BUY);
            } else {
                msg = "Не хватает монет: нужно " + sk.price;
                msgT = 1.6f;
            }
            save();
            return;
        }
        switch (id) {
            case B_PLAY:
            case B_RETRY:
                newGame();
                state = PLAY;
                break;
            case B_SKINS:
                state = SKINS;
                break;
            case B_SOUND:
                sound = !sound;
                sfx.on = sound;
                save();
                break;
            case B_BACK:
                state = MENU;
                break;
            case B_RESUME:
                holding = false;
                state = PLAY;
                break;
            case B_MENU:
                newGame();
                state = MENU;
                break;
            case B_CONT:
                if (coins >= CONTINUE_COST) continueGame();
                else {
                    msg = "Не хватает монет";
                    msgT = 1.6f;
                }
                break;
        }
    }
}

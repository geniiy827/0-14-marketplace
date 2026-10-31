package com.nova.engine.game;

import android.view.MotionEvent;

/**
 * Touch controls of MCPE 0.14.3 (MinecraftBindingFactory::onConfigChanged + _createNormalGamePlayTouchMapping /
 * _createFlyingTouchMapping + TouchGlyphButtonControl):
 *  - pixels per mm = 3 + (screenHeight/96 - 3) * dpadScale (option default 0.5)  (MinecraftClient::setDpadScale)
 *  - D-pad cell step B = 16 mm, button S = 20 mm, origin x 8, y = H - 3B - 8; up/left/right/down, up-left/up-right
 *    only while moving forward, sneak = centre button grown by 0.1B, D-pad zone = centre grown by 1.1B
 *  - jump J = 20 mm at x = W - J/2 + 4 - J, y = H - (J - 20) - J, hit area x1.1; flying: ascend / descend =
 *    jump area moved by -/+0.85J and scaled 0.8; double tap jump toggles flight (creative)
 *  - glyphs of gui.png (26x26 cells: up 0,107 left 26,107 down 52,107 right 78,107 jump 104,107; up-left 0,133
 *    up-right 26,133 ascend 52,133 descend 78,133 sneak 104,133) drawn in the area scaled by 0.85
 *  - colours: normal (1,1,1,0.498), pressed (0.753,0.753,0.753,0.498), times BUTTONS_TRANSPARENCY 0.65
 *  - the rest of the screen: drag = turn, tap = use/place at the finger, hold = destroy at the finger
 */
public final class Controls {
    public static final float GLYPH_SCALE = 0.85f, ALPHA = 0.498f * 0.65f, PRESSED = 0.753f;
    public float w = 1, h = 1, dp = 3;

    // ---- areas (x0, y0, x1, y1)
    public final float[] up = new float[4], left = new float[4], right = new float[4], down = new float[4],
            upLeft = new float[4], upRight = new float[4], sneakR = new float[4], dpadZone = new float[4],
            upInvisible = new float[4], jumpR = new float[4], jumpHit = new float[4], ascendR = new float[4], descendR = new float[4];
    public float slot, barX, barY;

    // ---- state for the game
    public float moveF, moveS;
    public boolean jump, ascend, descend, sneak, flying, movingForward;
    public boolean pUp, pLeft, pRight, pDown, pUpLeft, pUpRight, pJump, pSneak;
    private float lookDX, lookDY;
    private int places, breaks, flyToggles, slotPick = -1;
    private long lastJumpTap;

    // ---- pointers
    private static final int MAXP = 10;
    private final int[] role = new int[MAXP];        // 0 none, 1 dpad, 2 turn/interact, 3 jump, 4 ascend, 5 descend, 6 hotbar, 7 sneak
    private final float[] sx = new float[MAXP], sy = new float[MAXP], lx = new float[MAXP], ly = new float[MAXP];
    private final long[] t0 = new long[MAXP], nextBreak = new long[MAXP];
    private final boolean[] moved = new boolean[MAXP], holding = new boolean[MAXP];
    /** the interacting finger (for picking, the progress ring and the highlight): -1 none */
    public int interactId = -1;
    public float interactX, interactY, holdProgress;
    public final float[] tapX = new float[16], tapY = new float[16];
    private int tapCount;
    public final float[] breakX = new float[16], breakY = new float[16];

    static void rect(float[] r, float x0, float y0, float x1, float y1) { r[0] = x0; r[1] = y0; r[2] = x1; r[3] = y1; }
    static void scale(float[] o, float[] r, float s) {
        float cx = (r[0] + r[2]) / 2, cy = (r[1] + r[3]) / 2, hw = (r[2] - r[0]) * s / 2, hh = (r[3] - r[1]) * s / 2;
        rect(o, cx - hw, cy - hh, cx + hw, cy + hh);
    }
    static void grow(float[] o, float[] r, float g) { rect(o, r[0] - g, r[1] - g, r[2] + g, r[3] + g); }
    static void move(float[] o, float[] r, float dx, float dy) { rect(o, r[0] + dx, r[1] + dy, r[2] + dx, r[3] + dy); }
    public static boolean in(float[] r, float x, float y) { return r[0] <= x && x <= r[2] && r[1] <= y && y <= r[3]; }

    public synchronized void layout(float sw, float sh, float density) {
        w = sw; h = sh; dp = density;
        float dpadScale = 0.5f;
        float ppm = 3f + (sh * 0.010416667f - 3f) * dpadScale;
        float B = 16f * ppm, S = 20f * ppm;
        float x0 = 8f, y0 = sh - B * 3f + (sw >= sh ? -8f : -80f);
        rect(up, x0 + B, y0, x0 + B + S, y0 + S);
        rect(upInvisible, x0 + B, y0, x0 + B + S, y0 + S + B);
        rect(upLeft, x0, y0, x0 + S, y0 + S);
        rect(upRight, x0 + 2 * B, y0, x0 + 2 * B + S, y0 + S);
        rect(left, x0, y0 + B, x0 + S, y0 + B + S);
        rect(right, x0 + 2 * B, y0 + B, x0 + 2 * B + S, y0 + B + S);
        rect(down, x0 + B, y0 + 2 * B, x0 + B + S, y0 + 2 * B + S);
        float[] c = new float[4];
        rect(c, x0 + B, y0 + B, x0 + B + S, y0 + B + S);
        grow(sneakR, c, B * 0.1f);
        grow(dpadZone, sneakR, B * 1.1f);
        float J = 20f * ppm;
        float jx = sw - (J * 0.5f - 4f) - J, jy1 = sh - (J - 20f);
        rect(jumpR, jx, jy1 - J, jx + J, jy1);
        scale(jumpHit, jumpR, 1.1f);
        float[] t = new float[4];
        move(t, jumpHit, 0, -J * 0.85f); scale(ascendR, t, 0.8f);
        move(t, jumpHit, 0, J * 0.85f); scale(descendR, t, 0.8f);
        slot = Math.min(44 * dp, w * 0.6f / 9);
        barX = (w - slot * 9) / 2; barY = h - slot - 4 * dp;
    }

    public synchronized boolean onTouch(MotionEvent e) {
        int a = e.getActionMasked(), idx = e.getActionIndex();
        long now = System.currentTimeMillis();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            int id = e.getPointerId(idx);
            if (id >= MAXP) return true;
            float x = e.getX(idx), y = e.getY(idx);
            sx[id] = lx[id] = x; sy[id] = ly[id] = y; t0[id] = now; moved[id] = false; holding[id] = false;
            if (y >= barY && x >= barX && x < barX + slot * 9) { role[id] = 6; slotPick = (int) ((x - barX) / slot); }
            else if (!flying && in(jumpHit, x, y)) {
                role[id] = 3; jump = true;
                if (now - lastJumpTap < 350) { flyToggles++; lastJumpTap = 0; } else lastJumpTap = now;
            }
            else if (flying && in(ascendR, x, y)) { role[id] = 4; ascend = true; }
            else if (flying && in(descendR, x, y)) { role[id] = 5; descend = true; }
            else if (in(sneakR, x, y) && !in(up, x, y)) { role[id] = 7; sneak = !sneak; }
            else if (in(dpadZone, x, y)) { role[id] = 1; }
            else { role[id] = 2; if (interactId < 0) { interactId = id; interactX = x; interactY = y; } }
        } else if (a == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                int id = e.getPointerId(i);
                if (id >= MAXP) continue;
                float x = e.getX(i), y = e.getY(i);
                if (role[id] == 2) {
                    lookDX += x - lx[id]; lookDY += y - ly[id];
                    if (Math.abs(x - sx[id]) + Math.abs(y - sy[id]) > 10 * dp) moved[id] = true;
                    if (id == interactId) { interactX = x; interactY = y; }
                }
                lx[id] = x; ly[id] = y;
            }
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP || a == MotionEvent.ACTION_CANCEL) {
            int id = e.getPointerId(idx);
            if (id >= MAXP) return true;
            if (role[id] == 3) jump = false;
            if (role[id] == 4) ascend = false;
            if (role[id] == 5) descend = false;
            if (role[id] == 2 && !moved[id] && !holding[id] && a != MotionEvent.ACTION_CANCEL && now - t0[id] < 300 && tapCount < 16) {
                tapX[tapCount] = lx[id]; tapY[tapCount] = ly[id]; tapCount++; places++;
            }
            if (id == interactId) interactId = -1;
            role[id] = 0;
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                jump = ascend = descend = false; interactId = -1;
                for (int k = 0; k < MAXP; k++) role[k] = 0;
            }
        }
        return true;
    }

    private int breakCount;

    /** GL thread, once per frame: D-pad state from the fingers on it, holds become destroy (repeated) */
    public synchronized void poll() {
        long now = System.currentTimeMillis();
        boolean f = false, b = false, l = false, r = false;
        pUp = pLeft = pRight = pDown = pUpLeft = pUpRight = false;
        boolean wasForward = movingForward;
        for (int i = 0; i < MAXP; i++) {
            if (role[i] != 1) continue;
            float x = lx[i], y = ly[i];
            if (wasForward && in(upLeft, x, y)) { f = l = true; pUpLeft = true; }
            else if (wasForward && in(upRight, x, y)) { f = r = true; pUpRight = true; }
            else if (in(up, x, y) || (wasForward && in(upInvisible, x, y))) { f = true; pUp = true; }
            else if (in(left, x, y)) { l = true; pLeft = true; }
            else if (in(right, x, y)) { r = true; pRight = true; }
            else if (in(down, x, y)) { b = true; pDown = true; }
        }
        movingForward = f;
        moveF = (f ? 1 : 0) - (b ? 1 : 0);
        moveS = (r ? 1 : 0) - (l ? 1 : 0);
        pJump = jump; pSneak = sneak;
        holdProgress = 0;
        for (int i = 0; i < MAXP; i++) {
            if (role[i] != 2 || moved[i]) continue;
            long held = now - t0[i];
            if (!holding[i]) {
                if (i == interactId) holdProgress = Math.min(1f, held / 350f);
                if (held > 350) { holding[i] = true; addBreak(lx[i], ly[i]); nextBreak[i] = now + 250; }
            } else {
                if (i == interactId) holdProgress = 1f;
                if (now >= nextBreak[i]) { addBreak(lx[i], ly[i]); nextBreak[i] = now + 250; }
            }
        }
    }

    private void addBreak(float x, float y) {
        if (breakCount < 16) { breakX[breakCount] = x; breakY[breakCount] = y; breakCount++; }
        breaks++;
    }

    public synchronized float takeLookX() { float v = lookDX; lookDX = 0; return v; }
    public synchronized float takeLookY() { float v = lookDY; lookDY = 0; return v; }
    /** number of destroy events; positions in breakX/breakY (cleared on take) */
    public synchronized int takeBreaks(float[] xy) {
        int n = breakCount;
        for (int i = 0; i < n && i * 2 + 1 < xy.length; i++) { xy[i * 2] = breakX[i]; xy[i * 2 + 1] = breakY[i]; }
        breakCount = 0; breaks = 0; return n;
    }
    public synchronized int takePlaces(float[] xy) {
        int n = tapCount;
        for (int i = 0; i < n && i * 2 + 1 < xy.length; i++) { xy[i * 2] = tapX[i]; xy[i * 2 + 1] = tapY[i]; }
        tapCount = 0; places = 0; return n;
    }
    public synchronized int takeFly() { int v = flyToggles; flyToggles = 0; return v; }
    public synchronized int takeSlot() { int v = slotPick; slotPick = -1; return v; }
}

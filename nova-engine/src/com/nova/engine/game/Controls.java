package com.nova.engine.game;

import android.view.MotionEvent;

/**
 * Touch controls (UI thread writes, GL thread reads under the lock):
 *  left half - floating joystick; right half - drag to look, tap = break, hold = place;
 *  buttons: jump (bottom right), sneak/down (left of it), fly (above), hotbar slots (bottom centre).
 */
public final class Controls {
    public float w = 1, h = 1, dp = 3;
    // state for the game
    public float joyX, joyY;                 // -1..1, y up = forward
    public boolean jump, down;
    private float lookDX, lookDY;
    private int breaks, places, flyToggles, slotPick = -1;
    // pointers
    private static final int MAXP = 10;
    private final int[] role = new int[MAXP];            // 0 none, 1 joystick, 2 look, 3 jump, 4 down, 5 fly, 6 hotbar
    private final float[] sx = new float[MAXP], sy = new float[MAXP], lx = new float[MAXP], ly = new float[MAXP];
    private final long[] t0 = new long[MAXP];
    private final boolean[] moved = new boolean[MAXP], placed = new boolean[MAXP];
    public float joyCX, joyCY, joyR;       // for drawing
    public boolean joyActive;

    // layout (pixels)
    public float btn, jumpX, jumpY, downX, downY, flyX, flyY, slot, barX, barY;

    public synchronized void layout(float sw, float sh, float density) {
        w = sw; h = sh; dp = density;
        btn = 64 * dp;
        jumpX = w - btn * 1.25f; jumpY = h - btn * 1.35f;
        downX = jumpX - btn * 1.1f; downY = jumpY;
        flyX = jumpX; flyY = jumpY - btn * 1.1f;
        slot = Math.min(44 * dp, w * 0.6f / 9);
        barX = (w - slot * 9) / 2; barY = h - slot - 4 * dp;
        joyR = 60 * dp;
    }

    static boolean in(float x, float y, float bx, float by, float s) { return x >= bx && x < bx + s && y >= by && y < by + s; }

    public synchronized boolean onTouch(MotionEvent e) {
        int a = e.getActionMasked();
        int idx = e.getActionIndex();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            int id = e.getPointerId(idx);
            if (id >= MAXP) return true;
            float x = e.getX(idx), y = e.getY(idx);
            sx[id] = lx[id] = x; sy[id] = ly[id] = y; t0[id] = System.currentTimeMillis(); moved[id] = false; placed[id] = false;
            if (in(x, y, jumpX, jumpY, btn)) { role[id] = 3; jump = true; }
            else if (in(x, y, downX, downY, btn)) { role[id] = 4; down = true; }
            else if (in(x, y, flyX, flyY, btn)) { role[id] = 5; flyToggles++; }
            else if (y >= barY && x >= barX && x < barX + slot * 9) { role[id] = 6; slotPick = (int) ((x - barX) / slot); }
            else if (x < w * 0.4f) { role[id] = 1; joyActive = true; joyCX = x; joyCY = y; joyX = joyY = 0; }
            else role[id] = 2;
        } else if (a == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                int id = e.getPointerId(i);
                if (id >= MAXP) continue;
                float x = e.getX(i), y = e.getY(i);
                if (role[id] == 1) {
                    float dx = (x - joyCX) / joyR, dy = (y - joyCY) / joyR;
                    float l = (float) Math.sqrt(dx * dx + dy * dy);
                    if (l > 1) { dx /= l; dy /= l; }
                    joyX = dx; joyY = -dy;
                } else if (role[id] == 2) {
                    lookDX += x - lx[id]; lookDY += y - ly[id];
                    if (Math.abs(x - sx[id]) + Math.abs(y - sy[id]) > 14 * dp) moved[id] = true;
                }
                lx[id] = x; ly[id] = y;
            }
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP || a == MotionEvent.ACTION_CANCEL) {
            int id = e.getPointerId(idx);
            if (id >= MAXP) return true;
            if (role[id] == 1) { joyActive = false; joyX = joyY = 0; }
            if (role[id] == 3) jump = false;
            if (role[id] == 4) down = false;
            if (role[id] == 2 && !moved[id] && !placed[id] && a != MotionEvent.ACTION_CANCEL
                    && System.currentTimeMillis() - t0[id] < 300) breaks++;
            role[id] = 0;
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { joyActive = false; joyX = joyY = 0; jump = down = false; for (int k = 0; k < MAXP; k++) role[k] = 0; }
        }
        return true;
    }

    /** GL thread, once per frame: long presses become "place" */
    public synchronized void poll() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < MAXP; i++)
            if (role[i] == 2 && !moved[i] && !placed[i] && now - t0[i] > 350) { placed[i] = true; places++; }
    }

    public synchronized float takeLookX() { float v = lookDX; lookDX = 0; return v; }
    public synchronized float takeLookY() { float v = lookDY; lookDY = 0; return v; }
    public synchronized int takeBreaks() { int v = breaks; breaks = 0; return v; }
    public synchronized int takePlaces() { int v = places; places = 0; return v; }
    public synchronized int takeFly() { int v = flyToggles; flyToggles = 0; return v; }
    public synchronized int takeSlot() { int v = slotPick; slotPick = -1; return v; }
}

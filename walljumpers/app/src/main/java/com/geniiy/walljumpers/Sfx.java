package com.geniiy.walljumpers;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/** All sounds are synthesized at startup: no audio files are shipped. */
final class Sfx {

    static final int JUMP = 0, FLIP = 1, COIN = 2, CLING = 3, DEATH = 4, CHECK = 5, CLICK = 6, CRACK = 7, BUY = 8;
    private static final int RATE = 22050;
    private static final int SINE = 0, SQUARE = 1, TRIANGLE = 2;

    private SoundPool pool;
    private final int[] ids = new int[9];
    boolean on = true;

    Sfx(Context ctx) {
        try {
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            pool = new SoundPool.Builder().setMaxStreams(6).setAudioAttributes(aa).build();
            File dir = ctx.getCacheDir();
            ids[JUMP] = load(dir, "jump", sweep(250, 560, 0.10f, 0.16f, SQUARE));
            ids[FLIP] = load(dir, "flip", sweep(420, 1150, 0.15f, 0.14f, SQUARE));
            ids[COIN] = load(dir, "coin", concat(sweep(988, 988, 0.05f, 0.22f, TRIANGLE),
                    sweep(1319, 1319, 0.13f, 0.22f, TRIANGLE)));
            ids[CLING] = load(dir, "cling", noise(0.035f, 0.22f));
            ids[DEATH] = load(dir, "death", sweep(520, 55, 0.5f, 0.3f, TRIANGLE));
            ids[CHECK] = load(dir, "check", concat(concat(sweep(523, 523, 0.07f, 0.2f, SQUARE),
                    sweep(659, 659, 0.07f, 0.2f, SQUARE)), concat(sweep(784, 784, 0.07f, 0.2f, SQUARE),
                    sweep(1047, 1047, 0.18f, 0.2f, SQUARE))));
            ids[CLICK] = load(dir, "click", sweep(800, 600, 0.035f, 0.18f, SINE));
            ids[CRACK] = load(dir, "crack", noise(0.18f, 0.3f));
            ids[BUY] = load(dir, "buy", concat(sweep(660, 660, 0.06f, 0.2f, TRIANGLE),
                    sweep(990, 990, 0.16f, 0.2f, TRIANGLE)));
        } catch (Exception e) {
            pool = null;
        }
    }

    void play(int s) {
        if (on && pool != null) pool.play(ids[s], 1f, 1f, 0, 0, 1f);
    }

    private static short[] sweep(float f0, float f1, float dur, float vol, int wave) {
        int n = (int) (RATE * dur);
        short[] out = new short[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) n;
            float f = f0 + (f1 - f0) * t;
            ph += 2 * Math.PI * f / RATE;
            double s;
            if (wave == SQUARE) s = Math.sin(ph) >= 0 ? 1 : -1;
            else if (wave == TRIANGLE) s = 2 / Math.PI * Math.asin(Math.sin(ph));
            else s = Math.sin(ph);
            double env = Math.min(1.0, i / (RATE * 0.004)) * Math.pow(1 - t, 1.6);
            out[i] = (short) (s * env * vol * 32767);
        }
        return out;
    }

    private static short[] noise(float dur, float vol) {
        Random r = new Random(7);
        int n = (int) (RATE * dur);
        short[] out = new short[n];
        float lp = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) n;
            lp = lp * 0.6f + (r.nextFloat() * 2 - 1) * 0.4f;
            out[i] = (short) (lp * Math.pow(1 - t, 2) * vol * 32767 * 2);
        }
        return out;
    }

    private static short[] concat(short[] a, short[] b) {
        short[] o = new short[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    private int load(File dir, String name, short[] pcm) throws Exception {
        File f = new File(dir, "sfx_" + name + ".wav");
        ByteBuffer bb = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + pcm.length * 2)
                .put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '})
                .putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(pcm.length * 2);
        for (short s : pcm) bb.putShort(s);
        try (FileOutputStream fo = new FileOutputStream(f)) {
            fo.write(bb.array());
        }
        return pool.load(f.getAbsolutePath(), 1);
    }
}

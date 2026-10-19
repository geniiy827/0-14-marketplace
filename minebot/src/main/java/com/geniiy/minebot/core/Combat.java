package com.geniiy.minebot.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Боевой ИИ: тактики, выбор тактики с обучением (многорукий бандит) и модель противника.
 * Бот играет честно: дальность удара 3 блока, не больше 5 ударов в секунду, поворот не мгновенный.
 */
public final class Combat {
    public enum Tac { RUSH, STRAFE_L, STRAFE_R, WTAP, BAIT, ZIGZAG }
    static final String[] TAC_RU = {"натиск", "круг влево", "круг вправо", "удар-отскок", "контратака", "зигзаг"};

    /** Что бот хочет сделать в этот такт. */
    public static final class Act {
        public double mx, mz; public boolean jump, attack, sprint; public float yaw, pitch; public boolean retreat;
    }

    /** Память о противнике. */
    static final class Memory {
        double[] value = new double[Tac.values().length];
        int[] tries = new int[Tac.values().length];
        double engageDist = 3.0;   // с какого расстояния он обычно бьёт
        double strafe = 0;         // >0 — обходит вправо, <0 — влево
        double jumpy = 0;          // как часто прыгает (криты)
        int wins, losses, fights;
    }

    private final Map<String, Memory> mem = new HashMap<>();
    private final Random rnd = new Random();

    // текущий бой
    public String foeName = "";
    public long foeEid = -1;
    public boolean pvp;            // бой с игроком (учимся) или с мобом
    private Tac tac = Tac.RUSH;
    private int segTicks, dealt, taken, phase, zig, cooldown, backoff;
    private int totalDealt, totalTaken;
    private double lastFoeX, lastFoeZ, lastFoeY; private boolean haveLast;
    private float curYaw;

    Memory memOf(String n) {
        String k = n.toLowerCase(Locale.ROOT);
        Memory m = mem.get(k);
        if (m == null) { m = new Memory(); mem.put(k, m); }
        return m;
    }

    public void start(String name, long eid, boolean isPvp, float yaw) {
        foeName = name == null ? "" : name; foeEid = eid; pvp = isPvp; curYaw = yaw;
        segTicks = 0; dealt = taken = 0; totalDealt = totalTaken = 0; haveLast = false; cooldown = 0; backoff = 0;
        tac = isPvp ? choose(memOf(foeName)) : Tac.RUSH;
        if (isPvp) memOf(foeName).fights++;
    }

    public String tacticName() { return TAC_RU[tac.ordinal()]; }

    /** Противник получил урон (видим анимацию). */
    public void onFoeHurt() { dealt++; totalDealt++; }
    /** Бот получил урон. */
    public void onSelfHurt(int amount) { taken += Math.max(1, amount); totalTaken += Math.max(1, amount); }

    public void endFight(boolean won) {
        if (!pvp || foeName.isEmpty()) return;
        learn();
        Memory m = memOf(foeName);
        if (won) m.wins++; else m.losses++;
    }

    /** Выбор тактики: чаще лучшая, иногда пробуем другие (UCB). */
    private Tac choose(Memory m) {
        int total = 1; for (int t : m.tries) total += t;
        double best = -1e9; int bi = 0;
        for (int i = 0; i < m.value.length; i++) {
            double ucb = m.tries[i] == 0 ? 1e6 + rnd.nextDouble() : m.value[i] + 1.2 * Math.sqrt(Math.log(total) / m.tries[i]);
            // подсказки из модели противника
            Tac t = Tac.values()[i];
            if (m.strafe > 0.3 && t == Tac.STRAFE_R) ucb += 0.3;
            if (m.strafe < -0.3 && t == Tac.STRAFE_L) ucb += 0.3;
            if (m.engageDist > 3.3 && t == Tac.RUSH) ucb += 0.2;
            if (m.engageDist < 2.6 && (t == Tac.WTAP || t == Tac.BAIT)) ucb += 0.3;
            if (m.jumpy > 0.4 && t == Tac.ZIGZAG) ucb += 0.2;
            if (ucb > best) { best = ucb; bi = i; }
        }
        return Tac.values()[bi];
    }

    private void learn() {
        if (!pvp || foeName.isEmpty()) return;
        Memory m = memOf(foeName);
        double r = dealt - taken * 1.2;
        int i = tac.ordinal();
        m.tries[i]++;
        m.value[i] += (r - m.value[i]) * (m.tries[i] < 4 ? 0.5 : 0.25);
    }

    /**
     * Такт боя. bx,by,bz — бот (ноги), fx,fy,fz — противник (ноги), hp — здоровье бота.
     */
    public Act tick(double bx, double by, double bz, double fx, double fy, double fz, int hp, boolean onGround, boolean isCreeper) {
        Act a = new Act();
        double vx = 0, vz = 0;
        if (haveLast) { vx = fx - lastFoeX; vz = fz - lastFoeZ; }
        double vy = haveLast ? fy - lastFoeY : 0;
        lastFoeX = fx; lastFoeZ = fz; lastFoeY = fy; haveLast = true;

        double dx = fx - bx, dz = fz - bz;
        double d = Math.sqrt(dx * dx + dz * dz) + 1e-6;
        double ux = dx / d, uz = dz / d, px = -uz, pz = ux;

        // модель противника: боковое движение и прыжки
        if (pvp && !foeName.isEmpty()) {
            Memory m = memOf(foeName);
            double lateral = vx * px + vz * pz;
            m.strafe = m.strafe * 0.995 + Math.signum(lateral) * (Math.abs(lateral) > 0.05 ? 0.005 : 0);
            if (vy > 0.25) m.jumpy = m.jumpy * 0.98 + 0.02; else m.jumpy *= 0.999;
        }

        // упреждение: целимся туда, где он будет через 2 такта
        double ax = fx + vx * 2 - bx, az = fz + vz * 2 - bz;
        float want = (float) Math.toDegrees(Math.atan2(-ax, az));
        float diff = wrap(want - curYaw);
        curYaw += Math.max(-35, Math.min(35, diff));
        a.yaw = curYaw;
        a.pitch = (float) -Math.toDegrees(Math.atan2((fy + 1.0) - (by + 1.62), d));

        if (++segTicks >= 60) { // каждые 3 секунды оцениваем тактику и, возможно, меняем
            learn();
            if (pvp) tac = choose(memOf(foeName));
            segTicks = 0; dealt = taken = 0;
        }
        if (cooldown > 0) cooldown--;

        double speed = 0.215;
        boolean inReach = d < 3.0 && Math.abs(fy - by) < 2.5;
        double mx = 0, mz = 0;

        // мало здоровья — отступаем
        if (hp > 0 && hp <= 5) {
            a.retreat = true;
            mx = -ux * 0.28 + px * 0.1; mz = -uz * 0.28 + pz * 0.1;
            a.sprint = true;
            if (inReach && cooldown == 0 && Math.abs(diff) < 40) { a.attack = true; cooldown = 4; }
            a.mx = mx; a.mz = mz; return a;
        }

        if (isCreeper) {
            if (backoff > 0) { backoff--; mx = -ux * 0.25; mz = -uz * 0.25; }
            else if (d > 2.6) { mx = ux * speed; mz = uz * speed; }
            if (inReach && cooldown == 0) { a.attack = true; cooldown = 10; backoff = 14; }
            a.mx = mx; a.mz = mz; return a;
        }

        switch (tac) {
            case RUSH:
                if (d > 2.2) { mx = ux * 0.28; mz = uz * 0.28; a.sprint = true; }
                if (d < 3.4 && onGround && (phase++ % 12 == 0)) a.jump = true; // крит в прыжке
                break;
            case STRAFE_L: case STRAFE_R: {
                double side = tac == Tac.STRAFE_L ? -1 : 1;
                if (d > 3.6) { mx = ux * 0.26; mz = uz * 0.26; a.sprint = true; }
                else { mx = px * side * 0.17 + ux * (d - 2.5) * 0.12; mz = pz * side * 0.17 + uz * (d - 2.5) * 0.12; }
                break;
            }
            case WTAP:
                if (backoff > 0) { backoff--; mx = -ux * 0.18; mz = -uz * 0.18; }
                else if (d > 2.4) { mx = ux * 0.28; mz = uz * 0.28; a.sprint = true; }
                break;
            case BAIT: {
                double approaching = -(vx * ux + vz * uz); // он идёт на нас
                if (approaching > 0.08 || d < 3.1) { if (d > 2.0) { mx = ux * 0.28; mz = uz * 0.28; } }
                else if (d < 3.6) { mx = -ux * 0.12; mz = -uz * 0.12; }
                else if (d > 4.2) { mx = ux * 0.2; mz = uz * 0.2; }
                break;
            }
            case ZIGZAG: {
                zig++;
                double side = (zig / 8) % 2 == 0 ? 1 : -1;
                if (d > 2.3) { mx = ux * 0.22 + px * side * 0.12; mz = uz * 0.22 + pz * side * 0.12; a.sprint = true; }
                else { mx = px * side * 0.12; mz = pz * side * 0.12; }
                break;
            }
        }
        if (inReach && cooldown == 0 && Math.abs(diff) < 30) {
            a.attack = true;
            cooldown = 4;
            if (tac == Tac.WTAP) backoff = 5;
        }
        a.mx = mx; a.mz = mz;
        return a;
    }

    static float wrap(float a) { while (a > 180) a -= 360; while (a < -180) a += 360; return a; }

    public String stats(String name) {
        Memory m = memOf(name);
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(": боёв ").append(m.fights).append(", побед ").append(m.wins).append(", поражений ").append(m.losses);
        int bi = 0; for (int i = 1; i < m.value.length; i++) if (m.value[i] > m.value[bi]) bi = i;
        if (m.tries[bi] > 0) sb.append(". Лучше всего против него: ").append(TAC_RU[bi]);
        sb.append(". Он ").append(m.strafe > 0.3 ? "обходит вправо" : m.strafe < -0.3 ? "обходит влево" : "ходит прямо")
                .append(m.jumpy > 0.4 ? ", часто прыгает" : "");
        return sb.toString();
    }

    public String fightScore() { return "нанёс " + totalDealt + ", получил " + totalTaken; }

    public void reset(String name) { if (name == null) mem.clear(); else mem.remove(name.toLowerCase(Locale.ROOT)); }

    // ---------- сохранение ----------
    public String save() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Memory> e : mem.entrySet()) {
            Memory m = e.getValue();
            sb.append(e.getKey().replace("|", "").replace(";", "")).append(';');
            for (double v : m.value) sb.append(String.format(Locale.ROOT, "%.3f", v)).append(',');
            sb.append(';');
            for (int t : m.tries) sb.append(t).append(',');
            sb.append(';').append(String.format(Locale.ROOT, "%.3f;%.3f;%.3f;%d;%d;%d", m.engageDist, m.strafe, m.jumpy, m.wins, m.losses, m.fights));
            sb.append('|');
        }
        return sb.toString();
    }

    public void load(String s) {
        if (s == null || s.isEmpty()) return;
        try {
            for (String rec : s.split("\\|")) {
                if (rec.isEmpty()) continue;
                String[] f = rec.split(";");
                Memory m = new Memory();
                String[] v = f[1].split(","), t = f[2].split(",");
                for (int i = 0; i < m.value.length && i < v.length; i++) m.value[i] = Double.parseDouble(v[i]);
                for (int i = 0; i < m.tries.length && i < t.length; i++) m.tries[i] = Integer.parseInt(t[i]);
                m.engageDist = Double.parseDouble(f[3]); m.strafe = Double.parseDouble(f[4]); m.jumpy = Double.parseDouble(f[5]);
                m.wins = Integer.parseInt(f[6]); m.losses = Integer.parseInt(f[7]); m.fights = Integer.parseInt(f[8]);
                mem.put(f[0], m);
            }
        } catch (Exception ignored) {}
    }
}

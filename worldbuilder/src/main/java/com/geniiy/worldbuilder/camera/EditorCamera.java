package com.geniiy.worldbuilder.camera;

/**
 * Камера редактора: свободный полёт сквозь блоки (W/S — по взгляду с наклоном, A/D — стрейф, Пробел/Shift — строго по Y),
 * орбита, панорама, орто-виды, плавные перелёты. Движение не зависит от FPS.
 */
public class EditorCamera {
    public double x = 128, y = 80, z = 128;
    public float yaw = 0, pitch = 30; // yaw 0 — на юг (+Z), pitch > 0 — вниз
    public double speed = 12;
    public boolean ortho;
    public double orthoScale = 32;
    public double fov = 70;
    private double vx, vy, vz;
    // перелёт
    private double fx0, fy0, fz0, fx1, fy1, fz1; private float fyaw0, fyaw1, fp0, fp1; private double ft = -1, fdur = 0.4;
    public boolean boost;

    public void lookDir(double[] out) {
        double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
        out[0] = -Math.sin(ry) * Math.cos(rp);
        out[1] = -Math.sin(rp);
        out[2] = Math.cos(ry) * Math.cos(rp);
    }

    public void rotate(double dyaw, double dpitch) {
        yaw = (float) ((yaw + dyaw) % 360);
        pitch = (float) Math.max(-90, Math.min(90, pitch + dpitch));
        ft = -1;
    }

    /** Обновить положение. Входы: f/b/l/r/u/d. accel — время разгона (0 — мгновенно). */
    public void update(double dt, boolean f, boolean b, boolean l, boolean r, boolean u, boolean d, double accel) {
        if (ft >= 0) {
            ft += dt;
            double t = Math.min(1, ft / fdur);
            double e = 1 - Math.pow(1 - t, 3);
            x = fx0 + (fx1 - fx0) * e; y = fy0 + (fy1 - fy0) * e; z = fz0 + (fz1 - fz0) * e;
            yaw = (float) (fyaw0 + wrap(fyaw1 - fyaw0) * e); pitch = (float) (fp0 + (fp1 - fp0) * e);
            if (t >= 1) ft = -1;
            if (f || b || l || r || u || d) ft = -1; else return;
        }
        double[] look = new double[3];
        lookDir(look);
        double ry = Math.toRadians(yaw);
        double sx = Math.cos(ry), sz = Math.sin(ry); // вправо
        double tx = 0, ty = 0, tz = 0;
        if (f) { tx += look[0]; ty += look[1]; tz += look[2]; }
        if (b) { tx -= look[0]; ty -= look[1]; tz -= look[2]; }
        if (r) { tx -= sx; tz -= sz; }
        if (l) { tx += sx; tz += sz; }
        if (u) ty += 1;
        if (d) ty -= 1;
        double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
        double sp = speed * (boost ? 2 : 1);
        if (ortho) sp *= Math.max(0.3, orthoScale / 32);
        if (len > 0) { tx = tx / len * sp; ty = ty / len * sp; tz = tz / len * sp; }
        if (accel <= 0) { vx = tx; vy = ty; vz = tz; }
        else {
            double k = Math.min(1, dt / accel);
            vx += (tx - vx) * k; vy += (ty - vy) * k; vz += (tz - vz) * k;
            if (len == 0 && Math.abs(vx) + Math.abs(vy) + Math.abs(vz) < 0.01) { vx = vy = vz = 0; }
        }
        x += vx * dt; y += vy * dt; z += vz * dt;
        y = Math.max(-64, Math.min(400, y));
    }

    public void stop() { vx = vy = vz = 0; }

    private static double wrap(double a) { a %= 360; if (a > 180) a -= 360; if (a < -180) a += 360; return a; }

    public void flyTo(double nx, double ny, double nz, float nyaw, float npitch, double dur) {
        fx0 = x; fy0 = y; fz0 = z; fyaw0 = yaw; fp0 = pitch;
        fx1 = nx; fy1 = ny; fz1 = nz; fyaw1 = nyaw; fp1 = npitch; fdur = Math.max(0.01, dur); ft = 0;
    }
    public boolean flying() { return ft >= 0; }

    /** Вписать рамку в экран. */
    public void frame(double x1, double y1, double z1, double x2, double y2, double z2) {
        double cx = (x1 + x2) / 2, cy = (y1 + y2) / 2, cz = (z1 + z2) / 2;
        double r = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1)) / 2 + 1;
        double dist = r / Math.tan(Math.toRadians(fov / 2)) * 1.1;
        double[] look = new double[3];
        lookDir(look);
        if (ortho) orthoScale = Math.max(4, r * 1.1);
        flyTo(cx - look[0] * dist, cy - look[1] * dist, cz - look[2] * dist, yaw, pitch, 0.4);
    }

    /** Орбита вокруг точки. */
    public void orbit(double px, double py, double pz, double dyaw, double dpitch) {
        double dx = x - px, dy = y - py, dz = z - pz;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        rotate(dyaw, dpitch);
        double[] look = new double[3];
        lookDir(look);
        x = px - look[0] * dist; y = py - look[1] * dist; z = pz - look[2] * dist;
    }

    /** Панорама: сдвиг в плоскости экрана. */
    public void pan(double right, double up) {
        double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
        double rx = -Math.cos(ry), rz = -Math.sin(ry);
        double ux = -Math.sin(ry) * -Math.sin(rp), uy = Math.cos(rp), uz = Math.cos(ry) * -Math.sin(rp);
        x += rx * right + ux * up; y += uy * up; z += rz * right + uz * up;
        ft = -1;
    }

    public void dolly(double d) {
        if (ortho) { orthoScale = Math.max(2, Math.min(512, orthoScale * Math.pow(0.88, d))); return; }
        double[] look = new double[3];
        lookDir(look);
        x += look[0] * d; y += look[1] * d; z += look[2] * d;
        ft = -1;
    }

    /** Скорость по логарифмической шкале 1–100. */
    public void changeSpeed(double steps) {
        double l = Math.log(speed) + steps * 0.12;
        speed = Math.max(1, Math.min(100, Math.exp(l)));
    }
}

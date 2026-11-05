package com.nova.engine.game;

import com.nova.engine.world.Blocks;
import com.nova.engine.world.World;

/**
 * An entity the server shows us (AddPlayer / AddEntity / AddItemEntity). Like the client side of 0.14:
 *  - MoveEntity / MovePlayer -> Mob::lerpTo(pos, rot, 3): position and rotation reach the target in 3 ticks
 *  - pos.y is feet + heightOffset (1.62 for players, 0 for the rest), as Entity::setPos takes it
 *  - walk animation (Mob::tick, as in the Java original): d = horizontal step * 4 (max 1),
 *    walkAnimSpeed += (d - walkAnimSpeed) * 0.4, walkAnimPos += walkAnimSpeed
 *  - items and falling blocks keep moving between packets with their motion (gravity 0.04, drag 0.98)
 */
public final class Ent {
    public static final int PLAYER = 63, ITEM = 64, TNT = 65, FALLING = 66;

    public final long id; public final int type;
    public String name = "", skinName = "";
    public byte[] skin;                       // raw RGBA 64x32 / 64x64 (PlayerList)
    public long uuidHi, uuidLo;
    public double x, y, z, ox, oy, oz, tx, ty, tz, mx, my, mz;
    public float yaw, oyaw, head, ohead, pitch, opitch, tyaw, thead, tpitch;
    public int lerp;
    public float walkPos, owalkPos, walkSpeed, owalkSpeed;
    public int hurt, death, age, swingT; public boolean swinging;
    public float attack, oattack;
    public int itemId, itemData, itemCount;   // dropped item / held item / falling block (id, data)
    public boolean showName = true;
    public int flags;

    public Ent(long id, int type) { this.id = id; this.type = type; }

    public float heightOffset() { return type == PLAYER ? 1.62f : 0f; }

    public void set(double x, double y, double z, float yaw, float head, float pitch) {
        this.x = ox = tx = x; this.y = oy = ty = y; this.z = oz = tz = z;
        this.yaw = oyaw = tyaw = yaw; this.head = ohead = thead = head; this.pitch = opitch = tpitch = pitch;
        lerp = 0;
    }

    public void lerpTo(double x, double y, double z, float yaw, float head, float pitch) {
        tx = x; ty = y; tz = z; tyaw = yaw; thead = head; tpitch = pitch; lerp = 3;
    }

    private static float wrap(float d) { d %= 360f; if (d >= 180f) d -= 360f; if (d < -180f) d += 360f; return d; }

    public void tick(World w) {
        ox = x; oy = y; oz = z; oyaw = yaw; ohead = head; opitch = pitch; owalkPos = walkPos; owalkSpeed = walkSpeed; oattack = attack;
        age++;
        if (lerp > 0) {
            x += (tx - x) / lerp; y += (ty - y) / lerp; z += (tz - z) / lerp;
            yaw += wrap(tyaw - yaw) / lerp; head += wrap(thead - head) / lerp; pitch += (tpitch - pitch) / lerp;
            lerp--;
        } else if (type == ITEM || type == FALLING || type == TNT) {
            my -= 0.04;
            double nx = x + mx, ny = y + my, nz = z + mz;
            if (my < 0 && solid(w, nx, ny, nz)) { ny = Math.floor(ny) + 1; my = 0; mx *= 0.5; mz *= 0.5; }
            x = nx; y = ny; z = nz;
            mx *= 0.98; my *= 0.98; mz *= 0.98;
        }
        double dx = x - ox, dz = z - oz;
        float d = (float) Math.sqrt(dx * dx + dz * dz) * 4f;
        if (d > 1f) d = 1f;
        walkSpeed += (d - walkSpeed) * 0.4f;
        walkPos += walkSpeed;
        if (hurt > 0) hurt--;
        if (death > 0) death++;
        // Mob::updateAttackAnim: t++ while swinging, at getCurrentSwingDuration (6 without haste) it stops; attackAnim = t / 6
        if (swinging) { swingT++; if (swingT >= 6) { swingT = 0; swinging = false; } } else swingT = 0;
        attack = swingT / 6f;
    }

    /** Mob::swing: restart unless already in the first half */
    public void swing() { if (!swinging || swingT >= 3 || swingT < 0) { swingT = -1; swinging = true; } }

    private static boolean solid(World w, double x, double y, double z) {
        int id = w.get((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return id != 0 && Blocks.solid[id];
    }
}

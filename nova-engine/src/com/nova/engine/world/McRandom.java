package com.nova.engine.world;

/**
 * Random of MCPE 0.14.3 (libminecraftpe Random): MT19937 with the standard seeding and tempering.
 * nextInt(n) = genrand % n (unsigned), nextFloat = (float)(u32 * 2^-32).
 */
public final class McRandom {
    private final int[] mt = new int[624];
    private int mti;

    public McRandom(int seed) { setSeed(seed); }

    public void setSeed(int seed) {
        mt[0] = seed;
        for (int i = 1; i < 624; i++) mt[i] = 1812433253 * (mt[i - 1] ^ (mt[i - 1] >>> 30)) + i;
        mti = 624;
    }

    /** genrand_int32 as an unsigned value in a long */
    public long nextU32() {
        if (mti >= 624) {
            int kk;
            for (kk = 0; kk < 624 - 397; kk++) {
                int y = (mt[kk] & 0x80000000) | (mt[kk + 1] & 0x7fffffff);
                mt[kk] = mt[kk + 397] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            }
            for (; kk < 623; kk++) {
                int y = (mt[kk] & 0x80000000) | (mt[kk + 1] & 0x7fffffff);
                mt[kk] = mt[kk + (397 - 624)] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            }
            int y = (mt[623] & 0x80000000) | (mt[0] & 0x7fffffff);
            mt[623] = mt[396] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            mti = 0;
        }
        int y = mt[mti++];
        y ^= y >>> 11;
        y ^= (y << 7) & 0x9d2c5680;
        y ^= (y << 15) & 0xefc60000;
        y ^= y >>> 18;
        return y & 0xffffffffL;
    }

    public int nextInt() { return (int) nextU32(); }

    /** unsigned modulo like the original */
    public int nextInt(int n) { return n <= 0 ? 0 : (int) (nextU32() % n); }

    public float nextFloat() { return (float) ((double) nextU32() * 2.3283064365386963e-10); }

}

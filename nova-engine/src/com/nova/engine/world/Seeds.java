package com.nova.engine.world;

/**
 * World seeds exactly like MCPE 0.14.3 (LevelSettings::parseSeedString + Util::hashCode):
 *  - an input of 0 or 1 characters (before trimming) gives the fallback (a random seed)
 *  - trimmed; empty gives the fallback
 *  - sscanf("%d") reads a leading integer ("123abc" -> 123); the value is kept, except -1 that did not come from
 *    the literal text "-1" (an overflowing number), which is hashed like text
 *  - otherwise the seed is hash = hash * 31 + byte over the UTF-8 bytes (bytes unsigned)
 * The level seed is a 32 bit value; shown here signed, like the text that produces it.
 */
public final class Seeds {
    private Seeds() { }

    public static int parse(String in, int fallback) {
        if (in == null) return fallback;
        byte[] raw = in.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (raw.length <= 1) return fallback;
        String s = trim(in);
        if (s.isEmpty()) return fallback;
        Integer v = scanInt(s);
        if (v != null) {
            if (s.equals("-1") || v != -1) return v;
        }
        return hash(s);
    }

    /** Util::hashCode */
    public static int hash(String s) {
        int h = 0;
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) h = h * 31 + (b & 255);
        return h;
    }

    /** Util::stringTrim: spaces, tabs and line breaks at both ends */
    static String trim(String s) {
        int a = 0, b = s.length();
        while (a < b && " \t\r\n".indexOf(s.charAt(a)) >= 0) a++;
        while (b > a && " \t\r\n".indexOf(s.charAt(b - 1)) >= 0) b--;
        return s.substring(a, b);
    }

    /** sscanf %d (bionic): optional whitespace and sign, decimal digits; saturates to 64 bits, stored as int */
    static Integer scanInt(String s) {
        int i = 0, n = s.length();
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        boolean neg = false;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) { neg = s.charAt(i) == '-'; i++; }
        int start = i;
        java.math.BigInteger v = java.math.BigInteger.ZERO;
        while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') { v = v.multiply(java.math.BigInteger.TEN).add(java.math.BigInteger.valueOf(s.charAt(i) - '0')); i++; }
        if (i == start) return null;
        if (neg) v = v.negate();
        long l;
        if (v.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0) l = Long.MAX_VALUE;
        else if (v.compareTo(java.math.BigInteger.valueOf(Long.MIN_VALUE)) < 0) l = Long.MIN_VALUE;
        else l = v.longValue();
        return (int) l;
    }
}

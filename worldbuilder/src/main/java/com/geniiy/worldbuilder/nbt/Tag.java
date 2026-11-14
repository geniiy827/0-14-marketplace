package com.geniiy.worldbuilder.nbt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Собственная реализация NBT (big-endian) с точным сохранением типов тегов,
 * включая тип элементов пустого списка (у Minecraft пустой List пишется как List&lt;End&gt;).
 * Порядок ключей Compound сохраняется — повторная запись даёт те же байты.
 */
public abstract class Tag {
    public static final byte END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6,
            BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    public abstract byte type();
    abstract void writePayload(DataOutput out) throws IOException;
    public abstract Tag copy();

    // ---------- простые теги ----------
    public static final class ByteTag extends Tag {
        public byte v; public ByteTag(byte v) { this.v = v; }
        public byte type() { return BYTE; }
        void writePayload(DataOutput o) throws IOException { o.writeByte(v); }
        public Tag copy() { return new ByteTag(v); }
        public String toString() { return v + "b"; }
        public boolean equals(Object o) { return o instanceof ByteTag t && t.v == v; }
        public int hashCode() { return v; }
    }
    public static final class ShortTag extends Tag {
        public short v; public ShortTag(short v) { this.v = v; }
        public byte type() { return SHORT; }
        void writePayload(DataOutput o) throws IOException { o.writeShort(v); }
        public Tag copy() { return new ShortTag(v); }
        public String toString() { return v + "s"; }
        public boolean equals(Object o) { return o instanceof ShortTag t && t.v == v; }
        public int hashCode() { return v; }
    }
    public static final class IntTag extends Tag {
        public int v; public IntTag(int v) { this.v = v; }
        public byte type() { return INT; }
        void writePayload(DataOutput o) throws IOException { o.writeInt(v); }
        public Tag copy() { return new IntTag(v); }
        public String toString() { return Integer.toString(v); }
        public boolean equals(Object o) { return o instanceof IntTag t && t.v == v; }
        public int hashCode() { return v; }
    }
    public static final class LongTag extends Tag {
        public long v; public LongTag(long v) { this.v = v; }
        public byte type() { return LONG; }
        void writePayload(DataOutput o) throws IOException { o.writeLong(v); }
        public Tag copy() { return new LongTag(v); }
        public String toString() { return v + "L"; }
        public boolean equals(Object o) { return o instanceof LongTag t && t.v == v; }
        public int hashCode() { return Long.hashCode(v); }
    }
    public static final class FloatTag extends Tag {
        public float v; public FloatTag(float v) { this.v = v; }
        public byte type() { return FLOAT; }
        void writePayload(DataOutput o) throws IOException { o.writeFloat(v); }
        public Tag copy() { return new FloatTag(v); }
        public String toString() { return v + "f"; }
        public boolean equals(Object o) { return o instanceof FloatTag t && Float.compare(t.v, v) == 0; }
        public int hashCode() { return Float.hashCode(v); }
    }
    public static final class DoubleTag extends Tag {
        public double v; public DoubleTag(double v) { this.v = v; }
        public byte type() { return DOUBLE; }
        void writePayload(DataOutput o) throws IOException { o.writeDouble(v); }
        public Tag copy() { return new DoubleTag(v); }
        public String toString() { return v + "d"; }
        public boolean equals(Object o) { return o instanceof DoubleTag t && Double.compare(t.v, v) == 0; }
        public int hashCode() { return Double.hashCode(v); }
    }
    public static final class ByteArrayTag extends Tag {
        public byte[] v; public ByteArrayTag(byte[] v) { this.v = v; }
        public byte type() { return BYTE_ARRAY; }
        void writePayload(DataOutput o) throws IOException { o.writeInt(v.length); o.write(v); }
        public Tag copy() { return new ByteArrayTag(v.clone()); }
        public String toString() { return "[B; " + v.length + " байт]"; }
        public boolean equals(Object o) { return o instanceof ByteArrayTag t && Arrays.equals(t.v, v); }
        public int hashCode() { return Arrays.hashCode(v); }
    }
    public static final class StringTag extends Tag {
        public String v; public StringTag(String v) { this.v = v; }
        public byte type() { return STRING; }
        void writePayload(DataOutput o) throws IOException { writeString(o, v); }
        public Tag copy() { return new StringTag(v); }
        public String toString() { return "\"" + v + "\""; }
        public boolean equals(Object o) { return o instanceof StringTag t && t.v.equals(v); }
        public int hashCode() { return v.hashCode(); }
    }
    public static final class IntArrayTag extends Tag {
        public int[] v; public IntArrayTag(int[] v) { this.v = v; }
        public byte type() { return INT_ARRAY; }
        void writePayload(DataOutput o) throws IOException { o.writeInt(v.length); for (int i : v) o.writeInt(i); }
        public Tag copy() { return new IntArrayTag(v.clone()); }
        public String toString() { return "[I; " + v.length + "]"; }
        public boolean equals(Object o) { return o instanceof IntArrayTag t && Arrays.equals(t.v, v); }
        public int hashCode() { return Arrays.hashCode(v); }
    }
    public static final class LongArrayTag extends Tag {
        public long[] v; public LongArrayTag(long[] v) { this.v = v; }
        public byte type() { return LONG_ARRAY; }
        void writePayload(DataOutput o) throws IOException { o.writeInt(v.length); for (long i : v) o.writeLong(i); }
        public Tag copy() { return new LongArrayTag(v.clone()); }
        public String toString() { return "[L; " + v.length + "]"; }
        public boolean equals(Object o) { return o instanceof LongArrayTag t && Arrays.equals(t.v, v); }
        public int hashCode() { return Arrays.hashCode(v); }
    }

    // ---------- List ----------
    public static final class ListTag extends Tag implements Iterable<Tag> {
        public byte elemType;
        public final List<Tag> list = new ArrayList<>();
        public ListTag(byte elemType) { this.elemType = elemType; }
        public byte type() { return LIST; }
        public int size() { return list.size(); }
        public Tag get(int i) { return list.get(i); }
        public CompoundTag getCompound(int i) { return (CompoundTag) list.get(i); }
        public void add(Tag t) {
            if (list.isEmpty() && elemType == END) elemType = t.type();
            list.add(t);
        }
        public Iterator<Tag> iterator() { return list.iterator(); }
        void writePayload(DataOutput o) throws IOException {
            o.writeByte(elemType);
            o.writeInt(list.size());
            for (Tag t : list) t.writePayload(o);
        }
        public Tag copy() { ListTag l = new ListTag(elemType); for (Tag t : list) l.list.add(t.copy()); return l; }
        public String toString() { return list.toString(); }
        public boolean equals(Object o) { return o instanceof ListTag t && t.elemType == elemType && t.list.equals(list); }
        public int hashCode() { return list.hashCode() * 31 + elemType; }
    }

    // ---------- Compound ----------
    public static final class CompoundTag extends Tag {
        public final LinkedHashMap<String, Tag> map = new LinkedHashMap<>();
        public byte type() { return COMPOUND; }
        public Tag get(String k) { return map.get(k); }
        public boolean has(String k) { return map.containsKey(k); }
        public Tag remove(String k) { return map.remove(k); }
        public CompoundTag put(String k, Tag t) { map.put(k, t); return this; }
        public CompoundTag putByte(String k, int v) { return put(k, new ByteTag((byte) v)); }
        public CompoundTag putShort(String k, int v) { return put(k, new ShortTag((short) v)); }
        public CompoundTag putInt(String k, int v) { return put(k, new IntTag(v)); }
        public CompoundTag putLong(String k, long v) { return put(k, new LongTag(v)); }
        public CompoundTag putFloat(String k, float v) { return put(k, new FloatTag(v)); }
        public CompoundTag putString(String k, String v) { return put(k, new StringTag(v)); }
        public CompoundTag getCompound(String k) { Tag t = map.get(k); return t instanceof CompoundTag c ? c : null; }
        public ListTag getList(String k) { Tag t = map.get(k); return t instanceof ListTag c ? c : null; }
        public String getString(String k, String def) { Tag t = map.get(k); return t instanceof StringTag s ? s.v : def; }
        public byte[] getByteArray(String k) { Tag t = map.get(k); return t instanceof ByteArrayTag a ? a.v : null; }
        public int[] getIntArray(String k) { Tag t = map.get(k); return t instanceof IntArrayTag a ? a.v : null; }
        /** Число любого целого/вещественного типа. */
        public long getNumber(String k, long def) {
            Tag t = map.get(k);
            if (t instanceof ByteTag b) return b.v;
            if (t instanceof ShortTag b) return b.v;
            if (t instanceof IntTag b) return b.v;
            if (t instanceof LongTag b) return b.v;
            if (t instanceof FloatTag b) return (long) b.v;
            if (t instanceof DoubleTag b) return (long) b.v;
            return def;
        }
        public int getInt(String k, int def) { return (int) getNumber(k, def); }
        /** Записать число, сохранив существующий тип тега (если тег есть). */
        public void setNumberKeepType(String k, long v, byte defaultType) {
            Tag t = map.get(k);
            byte ty = t != null ? t.type() : defaultType;
            switch (ty) {
                case BYTE -> putByte(k, (int) v);
                case SHORT -> putShort(k, (int) v);
                case INT -> putInt(k, (int) v);
                case LONG -> putLong(k, v);
                case FLOAT -> putFloat(k, v);
                case DOUBLE -> put(k, new DoubleTag(v));
                default -> putInt(k, (int) v);
            }
        }
        void writePayload(DataOutput o) throws IOException {
            for (Map.Entry<String, Tag> e : map.entrySet()) {
                o.writeByte(e.getValue().type());
                writeString(o, e.getKey());
                e.getValue().writePayload(o);
            }
            o.writeByte(END);
        }
        public CompoundTag copy() { CompoundTag c = new CompoundTag(); for (var e : map.entrySet()) c.map.put(e.getKey(), e.getValue().copy()); return c; }
        public String toString() { return map.toString(); }
        public boolean equals(Object o) { return o instanceof CompoundTag t && t.map.equals(map); }
        public int hashCode() { return map.hashCode(); }
    }

    // ---------- IO ----------
    static void writeString(DataOutput o, String s) throws IOException {
        // modified UTF-8, как у Java DataOutput
        o.writeUTF(s);
    }

    public static Tag readPayload(DataInput in, byte type, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT слишком глубокий");
        switch (type) {
            case BYTE: return new ByteTag(in.readByte());
            case SHORT: return new ShortTag(in.readShort());
            case INT: return new IntTag(in.readInt());
            case LONG: return new LongTag(in.readLong());
            case FLOAT: return new FloatTag(in.readFloat());
            case DOUBLE: return new DoubleTag(in.readDouble());
            case BYTE_ARRAY: { int n = in.readInt(); if (n < 0 || n > 64 << 20) throw new IOException("ByteArray " + n); byte[] b = new byte[n]; in.readFully(b); return new ByteArrayTag(b); }
            case STRING: return new StringTag(in.readUTF());
            case LIST: {
                byte et = in.readByte(); int n = in.readInt();
                if (n < 0 || n > 16 << 20) throw new IOException("List " + n);
                ListTag l = new ListTag(et);
                for (int i = 0; i < n; i++) l.list.add(readPayload(in, et, depth + 1));
                return l;
            }
            case COMPOUND: {
                CompoundTag c = new CompoundTag();
                while (true) {
                    byte t = in.readByte();
                    if (t == END) break;
                    String name = in.readUTF();
                    c.map.put(name, readPayload(in, t, depth + 1));
                }
                return c;
            }
            case INT_ARRAY: { int n = in.readInt(); if (n < 0 || n > 16 << 20) throw new IOException("IntArray " + n); int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = in.readInt(); return new IntArrayTag(a); }
            case LONG_ARRAY: { int n = in.readInt(); if (n < 0 || n > 8 << 20) throw new IOException("LongArray " + n); long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = in.readLong(); return new LongArrayTag(a); }
            default: throw new IOException("Неизвестный тип NBT " + type);
        }
    }

    /** Корневой именованный Compound. Имя корня сохраняется в Named. */
    public record Named(String name, CompoundTag tag) {}

    public static Named readRoot(InputStream is) throws IOException {
        DataInputStream in = new DataInputStream(is);
        byte t = in.readByte();
        if (t != COMPOUND) throw new IOException("Корень NBT не Compound (" + t + ")");
        String name = in.readUTF();
        return new Named(name, (CompoundTag) readPayload(in, COMPOUND, 0));
    }

    public static void writeRoot(OutputStream os, String name, CompoundTag tag) throws IOException {
        DataOutputStream out = new DataOutputStream(os);
        out.writeByte(COMPOUND);
        out.writeUTF(name == null ? "" : name);
        tag.writePayload(out);
        out.flush();
    }

    public static byte[] toBytes(String name, CompoundTag tag) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            writeRoot(bo, name, tag);
            return bo.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** Красивый вывод для инспектора. */
    public static String pretty(Tag t, int indent) {
        StringBuilder sb = new StringBuilder();
        prettyInto(sb, null, t, indent);
        return sb.toString();
    }
    private static void prettyInto(StringBuilder sb, String name, Tag t, int ind) {
        sb.append("  ".repeat(ind));
        if (name != null) sb.append(name).append(": ");
        if (t instanceof CompoundTag c) {
            sb.append("{\n");
            for (var e : c.map.entrySet()) prettyInto(sb, e.getKey(), e.getValue(), ind + 1);
            sb.append("  ".repeat(ind)).append("}\n");
        } else if (t instanceof ListTag l) {
            sb.append("[").append(l.size()).append("]\n");
            int i = 0;
            for (Tag x : l) prettyInto(sb, "#" + (i++), x, ind + 1);
        } else sb.append(t).append('\n');
    }
}

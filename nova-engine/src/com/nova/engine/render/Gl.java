package com.nova.engine.render;

import android.opengl.GLES30;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** small GL helpers */
public final class Gl {
    private Gl() { }

    public static int program(String vs, String fs) {
        int v = shader(GLES30.GL_VERTEX_SHADER, vs), f = shader(GLES30.GL_FRAGMENT_SHADER, fs);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link: " + GLES30.glGetProgramInfoLog(p));
        return p;
    }

    static int shader(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(s);
            Log.e("NOVA.gl", log);
            throw new RuntimeException("shader: " + log);
        }
        return s;
    }

    public static IntBuffer ints(int n) { return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asIntBuffer(); }
    public static FloatBuffer floats(int n) { return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(); }

    public static int buffer() {
        int[] b = new int[1];
        GLES30.glGenBuffers(1, b, 0);
        return b[0];
    }

    public static void delete(int buf) {
        if (buf != 0) GLES30.glDeleteBuffers(1, new int[]{buf}, 0);
    }
}

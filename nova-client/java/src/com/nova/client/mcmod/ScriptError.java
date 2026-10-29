package com.nova.client.mcmod;

/** An error in a mod script; line = 0 when unknown. */
public final class ScriptError extends RuntimeException {
    public final int line;
    public String file;

    public ScriptError(String msg, int line) {
        super(msg);
        this.line = line;
    }

    public String where() {
        return (file == null ? "" : file) + (line > 0 ? ":" + line : "");
    }
}

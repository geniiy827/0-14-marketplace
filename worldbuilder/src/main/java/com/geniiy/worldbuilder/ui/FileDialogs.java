package com.geniiy.worldbuilder.ui;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Системные диалоги выбора файлов (LWJGL TinyFileDialogs). */
public final class FileDialogs {
    private FileDialogs() {}

    public static Path openFile(String title, String def, String[] patterns, String desc) {
        try (MemoryStack st = MemoryStack.stackPush()) {
            PointerBuffer pb = null;
            if (patterns != null && patterns.length > 0) {
                pb = st.mallocPointer(patterns.length);
                for (String p : patterns) pb.put(st.UTF8(p));
                pb.flip();
            }
            String r = TinyFileDialogs.tinyfd_openFileDialog(title, def, pb, desc, false);
            return r == null ? null : Paths.get(r);
        }
    }

    public static Path saveFile(String title, String def, String[] patterns, String desc) {
        try (MemoryStack st = MemoryStack.stackPush()) {
            PointerBuffer pb = null;
            if (patterns != null && patterns.length > 0) {
                pb = st.mallocPointer(patterns.length);
                for (String p : patterns) pb.put(st.UTF8(p));
                pb.flip();
            }
            String r = TinyFileDialogs.tinyfd_saveFileDialog(title, def, pb, desc);
            return r == null ? null : Paths.get(r);
        }
    }

    public static Path selectFolder(String title, String def) {
        String r = TinyFileDialogs.tinyfd_selectFolderDialog(title, def);
        return r == null ? null : Paths.get(r);
    }
}

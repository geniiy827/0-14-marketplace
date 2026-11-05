package com.nova.client.mcmod;

/** The game process as mods see it: libnova's hook / call / memory primitives (ModBridge on Android). */
public interface Machine {
    int base();                                   // load address of libminecraftpe.so
    int hook(int addr, int mode, int[] sites);    // -> hook index, < 0 error
    void unhook(int idx);
    long call(int fn, int[] words);               // r0:r1 of the call
    long callOrig(int idx, int[] words);
    int peek(int addr, int kind);                 // kind 1 u8, -1 s8, 2 u16, -2 s16, 4 i32
    void poke(int addr, int kind, int value);
    int alloc(int n);
    void free(int p);
    int strNew(String s);                         // new std::string
    String strGet(int p);                         // read std::string
    String cstr(int p);                           // read char*
    int global(int which);                        // 0 client, 1 player, 2 region, 3 level, 4 gui
    java.io.InputStream symbols() throws java.io.IOException;   // assets/nova/symbols.tsv
}

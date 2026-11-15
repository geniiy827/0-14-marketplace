package com.mojang.minecraftpe;

/** Compile-time stub only. The real class lives in the game's classes.dex. */
public class MainActivity extends android.app.NativeActivity {
    public byte[] getFileDataBytes(String path) { return null; }
    public int[] getImageData(String path, boolean fromAssets) { return null; }
    public void launchUri(String uri) { }
    public void showKeyboard(String text, int maxLength, boolean limitInput, boolean numbersOnly) { }
    public void hideKeyboard() { }
    public native void nativeSetTextboxText(String text);
    public native void nativeReturnKeyPressed();
}

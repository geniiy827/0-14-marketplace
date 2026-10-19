package com.geniiy.snake;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

public class MainActivity extends Activity {

    private SnakeView game;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        game = new SnakeView(this);
        setContentView(game);
    }

    @Override
    protected void onPause() {
        super.onPause();
        game.pause();
    }
}

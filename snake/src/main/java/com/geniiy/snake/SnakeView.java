package com.geniiy.snake;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.LinkedList;
import java.util.Random;

public class SnakeView extends View {

    private static final int COLS = 18;
    private static final int START_DELAY = 180;
    private static final int MIN_DELAY = 70;

    private enum State { READY, RUNNING, PAUSED, OVER }

    private final Paint bgPaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint snakePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint foodPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayPaint = new Paint();
    private final RectF rect = new RectF();
    private final Random random = new Random();
    private final SharedPreferences prefs;

    // snake cells stored as x * 1000 + y
    private final LinkedList<Integer> snake = new LinkedList<>();
    private final ArrayDeque<int[]> turns = new ArrayDeque<>();

    private int rows;
    private float cell;
    private float offX, offY;
    private int dx = 1, dy = 0;
    private int food = -1;
    private int score;
    private int best;
    private int delay = START_DELAY;
    private State state = State.READY;

    private float downX, downY;
    private boolean swiped;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (state != State.RUNNING) return;
            step();
            invalidate();
            if (state == State.RUNNING) postDelayed(this, delay);
        }
    };

    public SnakeView(Context context) {
        super(context);
        prefs = context.getSharedPreferences("snake", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);

        bgPaint.setColor(Color.rgb(16, 20, 24));
        gridPaint.setColor(Color.rgb(24, 30, 36));
        snakePaint.setColor(Color.rgb(76, 217, 100));
        headPaint.setColor(Color.rgb(140, 255, 160));
        foodPaint.setColor(Color.rgb(255, 77, 77));
        overlayPaint.setColor(Color.argb(170, 0, 0, 0));

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        smallText.setColor(Color.rgb(200, 200, 200));
        smallText.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float top = h * 0.12f;
        float pad = w * 0.04f;
        cell = (w - pad * 2) / COLS;
        rows = (int) ((h - top - pad) / cell);
        offX = pad;
        offY = top;
        textPaint.setTextSize(w * 0.075f);
        smallText.setTextSize(w * 0.045f);
        reset();
    }

    private void reset() {
        snake.clear();
        turns.clear();
        int cy = rows / 2;
        for (int i = 0; i < 4; i++) snake.add(key(5 - i, cy));
        dx = 1;
        dy = 0;
        score = 0;
        delay = START_DELAY;
        placeFood();
    }

    private static int key(int x, int y) { return x * 1000 + y; }
    private static int kx(int k) { return k / 1000; }
    private static int ky(int k) { return k % 1000; }

    private void placeFood() {
        int free = COLS * rows - snake.size();
        if (free <= 0) { food = -1; return; }
        int k;
        do {
            k = key(random.nextInt(COLS), random.nextInt(rows));
        } while (snake.contains(k));
        food = k;
    }

    private void step() {
        if (!turns.isEmpty()) {
            int[] t = turns.poll();
            dx = t[0];
            dy = t[1];
        }
        int head = snake.getFirst();
        int nx = kx(head) + dx;
        int ny = ky(head) + dy;

        if (nx < 0 || ny < 0 || nx >= COLS || ny >= rows) { gameOver(); return; }
        int nk = key(nx, ny);
        boolean eating = nk == food;
        // tail moves away this step unless we eat, so it is not a collision
        if (snake.contains(nk) && !(nk == snake.getLast() && !eating)) { gameOver(); return; }

        snake.addFirst(nk);
        if (eating) {
            score++;
            delay = Math.max(MIN_DELAY, START_DELAY - score * 5);
            placeFood();
        } else {
            snake.removeLast();
        }
    }

    private void gameOver() {
        state = State.OVER;
        if (score > best) {
            best = score;
            prefs.edit().putInt("best", best).apply();
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    }

    private void start() {
        if (state == State.OVER) reset();
        state = State.RUNNING;
        removeCallbacks(tick);
        postDelayed(tick, delay);
        invalidate();
    }

    public void pause() {
        if (state == State.RUNNING) {
            state = State.PAUSED;
            removeCallbacks(tick);
            invalidate();
        }
    }

    private void turn(int ndx, int ndy) {
        int[] last = turns.isEmpty() ? new int[]{dx, dy} : turns.peekLast();
        if (last[0] == -ndx && last[1] == -ndy) return; // no 180 turns
        if (last[0] == ndx && last[1] == ndy) return;
        if (turns.size() < 3) turns.add(new int[]{ndx, ndy});
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                swiped = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (state != State.RUNNING) return true;
                float ddx = e.getX() - downX;
                float ddy = e.getY() - downY;
                float min = cell * 0.8f;
                if (Math.abs(ddx) > min || Math.abs(ddy) > min) {
                    if (Math.abs(ddx) > Math.abs(ddy)) turn(ddx > 0 ? 1 : -1, 0);
                    else turn(0, ddy > 0 ? 1 : -1);
                    downX = e.getX();
                    downY = e.getY();
                    swiped = true;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!swiped) {
                    if (state == State.RUNNING) pause();
                    else start();
                }
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        c.drawRect(0, 0, w, h, bgPaint);

        // header
        float hy = offY * 0.62f;
        smallText.setTextAlign(Paint.Align.LEFT);
        c.drawText("Счёт: " + score, offX, hy, smallText);
        smallText.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Рекорд: " + best, w - offX, hy, smallText);
        smallText.setTextAlign(Paint.Align.CENTER);

        // field
        rect.set(offX, offY, offX + cell * COLS, offY + cell * rows);
        c.drawRect(rect, gridPaint);

        // food
        if (food >= 0) {
            float fx = offX + kx(food) * cell + cell / 2;
            float fy = offY + ky(food) * cell + cell / 2;
            c.drawCircle(fx, fy, cell * 0.4f, foodPaint);
        }

        // snake
        float r = cell * 0.25f;
        float gap = cell * 0.08f;
        boolean first = true;
        for (int k : snake) {
            float x = offX + kx(k) * cell;
            float y = offY + ky(k) * cell;
            rect.set(x + gap, y + gap, x + cell - gap, y + cell - gap);
            c.drawRoundRect(rect, r, r, first ? headPaint : snakePaint);
            first = false;
        }

        // overlays
        if (state != State.RUNNING) {
            c.drawRect(0, 0, w, h, overlayPaint);
            float cy = h / 2f;
            String title;
            String hint;
            if (state == State.READY) {
                title = "ЗМЕЙКА";
                hint = "Нажми, чтобы начать";
            } else if (state == State.PAUSED) {
                title = "Пауза";
                hint = "Нажми, чтобы продолжить";
            } else {
                title = "Игра окончена";
                hint = "Счёт: " + score + "   ·   нажми ещё раз";
            }
            c.drawText(title, w / 2f, cy, textPaint);
            c.drawText(hint, w / 2f, cy + smallText.getTextSize() * 2f, smallText);
            c.drawText("Свайпай, чтобы поворачивать", w / 2f,
                    cy + smallText.getTextSize() * 3.6f, smallText);
        }
    }
}

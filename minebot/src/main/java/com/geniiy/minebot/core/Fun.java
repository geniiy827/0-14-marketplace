package com.geniiy.minebot.core;

import java.util.Random;

/** Развлечения для чата: шутки, кубик, монетка, шар предсказаний, викторина. */
public final class Fun {
    private static final Random R = new Random();

    static final String[] JOKES = {
            "Крипер подходит к игроку: «Обнимемся?»",
            "Почему стив не ходит в школу? Он и так всё скрафтит.",
            "Эндермен не играет в прятки: стоит на него посмотреть — и все злятся.",
            "Мой любимый блок — бедрок. Его никто не сломает, как и мою уверенность.",
            "Зомби не любят солнце, а я не люблю лаги.",
            "Скелет в мороз не мёрзнет — ему до костей всё равно.",
            "Слизень так и не нашёл себе пару — все говорят, что он липкий.",
            "Копал вниз. Нашёл лаву. Больше не копаю вниз.",
            "Сундук говорит другому сундуку: «Что у тебя внутри?» — «Всё своё ношу с собой».",
            "Курица снесла яйцо, яйцо стало курицей. Я до сих пор не понял, кто был первым."};

    static final String[] BALL = {"Бесспорно", "Предрешено", "Никаких сомнений", "Да", "Скорее да", "Знаки говорят — да",
            "Пока неясно, попробуй снова", "Спроси позже", "Лучше не рассказывать", "Даже не думай", "Мой ответ — нет",
            "Весьма сомнительно", "Крипер шепчет: нет"};

    static final String[] COMPLIMENTS = {"Ты строишь лучше всех на сервере!", "С тобой не страшно спускаться в шахту.",
            "Твои постройки — как у профессионала.", "Ты настоящий мастер выживания!", "Даже крипер бы тебя не взорвал."};

    public static String joke() { return JOKES[R.nextInt(JOKES.length)]; }
    public static String ball() { return "🔮 " + BALL[R.nextInt(BALL.length)]; }
    public static String compliment() { return COMPLIMENTS[R.nextInt(COMPLIMENTS.length)]; }
    public static String dice(int sides) { return "🎲 Выпало " + (1 + R.nextInt(Math.max(2, Math.min(1000, sides)))); }
    public static String coin() { return R.nextBoolean() ? "🪙 Орёл!" : "🪙 Решка!"; }

    // ---------- викторина ----------
    private int answer = Integer.MIN_VALUE;
    private long askedAt;

    public String newQuestion() {
        int a = 2 + R.nextInt(30), b = 2 + R.nextInt(30);
        switch (R.nextInt(3)) {
            case 0: answer = a + b; askedAt = System.currentTimeMillis(); return "❓ Сколько будет " + a + " + " + b + "? Пиши ответ в чат!";
            case 1: answer = a * (b % 10 + 1); askedAt = System.currentTimeMillis(); return "❓ Сколько будет " + a + " × " + (b % 10 + 1) + "?";
            default: int big = Math.max(a, b), small = Math.min(a, b); answer = big - small; askedAt = System.currentTimeMillis(); return "❓ Сколько будет " + big + " − " + small + "?";
        }
    }

    /** Проверить сообщение как ответ. null — не ответ. */
    public String check(String who, String text) {
        if (answer == Integer.MIN_VALUE) return null;
        if (System.currentTimeMillis() - askedAt > 60000) { int a = answer; answer = Integer.MIN_VALUE; return "⌛ Время вышло! Ответ был " + a; }
        String t = text.replaceAll("[^0-9-]", "");
        if (t.isEmpty()) return null;
        try {
            int v = Integer.parseInt(t);
            if (v == answer) {
                answer = Integer.MIN_VALUE;
                return "🎉 " + who + " ответил первым и правильно! (" + v + ") Ещё раз: викторина";
            }
        } catch (Exception ignored) {}
        return null;
    }
}

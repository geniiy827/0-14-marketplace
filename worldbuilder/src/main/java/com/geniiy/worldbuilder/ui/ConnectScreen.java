package com.geniiy.worldbuilder.ui;

import com.geniiy.worldbuilder.config.ServerProfile;
import com.geniiy.worldbuilder.config.WbConfig;
import com.geniiy.worldbuilder.remote.*;
import net.minecraft.client.gui.screen.Screen;

import java.net.URI;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Экран «Подключение к серверу» (раздел 8 ТЗ). */
public class ConnectScreen extends WbScreen {
    private final Screen parent;
    private ServerProfile cur;
    private String password = "";
    private boolean showPass, advanced, portEdited;
    private String status = ""; private int statusColor = Gui.TEXT2;
    private String hostErr, userErr;
    private double listOff;
    private boolean autoTried;

    public ConnectScreen(Screen parent) {
        super("World Builder 0.14.3");
        this.parent = parent;
        WbConfig c = WbConfig.get();
        ServerProfile last = c.lastProfile != null ? c.profile(c.lastProfile) : (c.profiles.isEmpty() ? null : c.profiles.get(0));
        if (last != null && c.autofill) select(last); else cur = new ServerProfile();
    }

    private void select(ServerProfile p) {
        cur = p;
        String pw = WbConfig.decrypt(p.encPassword);
        password = pw == null ? "" : pw;
        portEdited = true;
        hostErr = userErr = null;
        status = "";
    }

    @Override
    protected void draw(float delta) {
        WbConfig cfg = WbConfig.get();
        if (!autoTried) {
            autoTried = true;
            checkCrashed();
            if (cfg.autoConnect && cfg.autofill && cur.encPassword != null && !cur.host.isEmpty()) connect();
        }
        gui.rect(0, 0, W, H, 0xFF17181B);
        gui.text("World Builder 0.14.3", 12, 10, Gui.ACCENT);
        gui.text("Подключение к серверу Minecraft PE 0.14.3 (Genisys)", 12 + gui.width("World Builder 0.14.3  "), 10, Gui.TEXT2);
        int top = 28;
        int lw = cfg.profiles.isEmpty() ? 0 : Math.min(220, W / 3);
        if (lw > 0) {
            gui.panel(8, top, lw, H - top - 8);
            gui.text("Сохранённые профили", 16, top + 8, Gui.TEXT2);
            int ly = top + 22, lh = H - top - 8 - 22 - 28;
            listOff = gui.scrollArea("profl", 12, ly, lw - 8, lh, cfg.profiles.size() * 34, listOff);
            gui.scissor(12, ly, lw - 8, lh);
            SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy HH:mm");
            for (int i = 0; i < cfg.profiles.size(); i++) {
                ServerProfile p = cfg.profiles.get(i);
                int y = ly + i * 34 - (int) listOff;
                boolean sel = p == cur;
                boolean hv = gui.hover(12, y, lw - 14, 32) && gui.my >= ly && gui.my < ly + lh;
                gui.rect(12, y, lw - 14, 32, sel ? Gui.BTN_SEL : hv ? Gui.BTN_H : 0x00000000);
                if (sel) gui.rect(12, y, 2, 32, Gui.ACCENT);
                gui.text(gui.ellipsize(p.title(), lw - 30), 18, y + 4, Gui.TEXT);
                gui.text(gui.ellipsize(p.url(), lw - 30), 18, y + 14, Gui.TEXT2);
                if (p.lastLogin > 0) gui.text(df.format(new Date(p.lastLogin)), 18, y + 23, 0xFF6A6D72);
                if (hv && gui.my >= ly && gui.my < ly + lh && gui.clicked(12, y, lw - 14, 32, 0)) select(p);
            }
            gui.noScissor();
            int by = H - 8 - 24;
            int bw = (lw - 16) / 3;
            if (gui.button("pnew", 12, by, bw, 18, "+ Новый", "Создать новый профиль")) { cur = new ServerProfile(); password = ""; portEdited = false; }
            if (gui.button("pdup", 14 + bw, by, bw, 18, "Дублировать", null) && cfg.profiles.contains(cur)) {
                ServerProfile c = cur.copy(); c.name = cur.title() + " (копия)"; cfg.profiles.add(c); cfg.save(); select(c);
            }
            if (gui.button("pdel", 16 + 2 * bw, by, bw, 18, "Удалить", "Удалить профиль (с подтверждением)") && cfg.profiles.contains(cur)) {
                ServerProfile victim = cur;
                dialogs.push(new ChoiceDialog("Удалить профиль?", "Профиль «" + victim.title() + "» и сохранённый пароль будут удалены.", new String[]{"Удалить", "Отмена"}, i -> {
                    if (i == 0) { cfg.profiles.remove(victim); cfg.save(); cur = new ServerProfile(); password = ""; }
                }));
            }
        }
        // ---- форма ----
        int fx = 8 + lw + (lw > 0 ? 8 : 0);
        int fw = Math.min(460, W - fx - 8);
        fx += Math.max(0, (W - fx - 8 - fw) / 2);
        gui.panel(fx, top, fw, H - top - 8);
        int x = fx + 12, w = fw - 24, y = top + 10;
        int lab = 110;
        gui.label("Название профиля", x, y + 5);
        cur.name = gui.field("name", x + lab, y, w - lab, 18, cur.name, cur.host.isEmpty() ? "необязательно" : cur.host, false, false);
        y += 24;
        gui.label("Протокол", x, y + 5);
        String[] protos = {"FTP", "FTPS", "SFTP"};
        int pi = Arrays.asList(protos).indexOf(cur.protocol);
        int npi = gui.segmented("proto", x + lab, y, w - lab, 18, protos, Math.max(0, pi), new String[]{"Обычный FTP", "FTP с шифрованием (явный TLS)", "SSH File Transfer"});
        if (npi != pi) { cur.protocol = protos[npi]; if (!portEdited) cur.port = npi == 2 ? 22 : 21; }
        y += 24;
        gui.label("Адрес (host)", x, y + 5);
        String nh = gui.field("host", x + lab, y, w - lab - 70, 18, cur.host, "например, 1.2.3.4 или ftp://user:pass@host:21/path", false, hostErr != null);
        if (!nh.equals(cur.host)) { cur.host = nh; hostErr = null; parseUrlIntoFields(); }
        gui.label("Порт", x + w - 64, y + 5);
        int oldPort = cur.port;
        cur.port = (int) gui.number("port", x + w - 38, y, 38, 18, "", cur.port, 1, 65535, 1, true);
        if (cur.port != oldPort) portEdited = true;
        y += 20;
        if (hostErr != null) { gui.text(hostErr, x + lab, y, Gui.ERR); y += 10; }
        y += 4;
        gui.label("Логин", x, y + 5);
        String nu = gui.field("user", x + lab, y, w - lab, 18, cur.user, null, false, userErr != null);
        if (!nu.equals(cur.user)) { cur.user = nu; userErr = null; }
        y += 24;
        boolean keyAuth = "SFTP".equals(cur.protocol) && "key".equals(cur.sftpAuth);
        if ("SFTP".equals(cur.protocol)) {
            gui.label("Способ входа", x, y + 5);
            int a = gui.segmented("auth", x + lab, y, w - lab, 18, new String[]{"Пароль", "Ключ"}, keyAuth ? 1 : 0, null);
            cur.sftpAuth = a == 1 ? "key" : "password";
            keyAuth = a == 1;
            y += 24;
            if (keyAuth) {
                gui.label("Файл ключа", x, y + 5);
                cur.keyFile = gui.field("keyf", x + lab, y, w - lab - 74, 18, cur.keyFile, "путь к приватному ключу", false, false);
                if (gui.button("keyb", x + w - 70, y, 70, 18, "Выбрать…", null)) {
                    Path p = FileDialogs.openFile("Приватный ключ SSH", System.getProperty("user.home"), null, null);
                    if (p != null) cur.keyFile = p.toString();
                }
                y += 24;
            }
        }
        gui.label(keyAuth ? "Парольная фраза" : "Пароль", x, y + 5);
        password = gui.field("pass", x + lab, y, w - lab - 24, 18, password, null, !showPass, false);
        if (gui.button("eye", x + w - 20, y, 20, 18, showPass ? "◉" : "○", showPass ? "Скрыть пароль" : "Показать пароль")) showPass = !showPass;
        y += 24;
        gui.label("Начальная папка", x, y + 5);
        cur.startDir = gui.field("dir", x + lab, y, w - lab, 18, cur.startDir, "/", false, false);
        y += 20;
        gui.text("Обычно миры лежат в папке worlds", x + lab, y, 0xFF6A6D72);
        y += 14;
        if (gui.button("adv", x, y, w, 16, (advanced ? "▾ " : "▸ ") + "Дополнительно", null)) advanced = !advanced;
        y += 20;
        if (advanced) {
            cur.passive = gui.check("passive", x + 8, y, "Пассивный режим", cur.passive, "Почти всегда нужен (через NAT и брандмауэры)");
            y += 16;
            gui.label("Кодировка имён", x + 8, y + 5);
            cur.encoding = gui.field("enc", x + lab, y, 100, 18, cur.encoding, "UTF-8", false, false);
            y += 22;
            gui.label("Таймаут, с", x + 8, y + 5);
            cur.timeoutSec = (int) gui.number("tmo", x + lab, y, 60, 18, "", cur.timeoutSec, 3, 120, 1, true);
            y += 22;
            gui.label("Параллельно", x + 8, y + 5);
            cur.parallel = (int) gui.number("par", x + lab, y, 60, 18, "", cur.parallel, 1, 4, 1, true);
            gui.text("некоторые хостинги ограничивают число подключений", x + lab + 66, y + 5, 0xFF6A6D72);
            y += 22;
            if ("SFTP".equals(cur.protocol)) { gui.text("На панелях Pterodactyl SFTP обычно на порту 2022", x + 8, y, Gui.TEXT2); y += 12; }
        }
        cur.remember = gui.check("remember", x, y, "Запомнить меня", cur.remember,
                "Сохранить профиль вместе с паролем.\nПароль зашифрован, но любой, у кого есть доступ к этому компьютеру, сможет его получить. Не включай на чужом ПК.");
        y += 20;
        if (!status.isEmpty()) { for (String l : wrap(gui, status, w)) { gui.text(l, x, y, statusColor); y += 10; } y += 4; }
        int by = top + (H - top - 8) - 30;
        int bw = (w - 18) / 4;
        if (gui.button("back", x, by, bw, 20, "Назад", null)) close();
        if (gui.button("offline", x + bw + 6, by, bw, 20, "С диска / ZIP", "Открыть мир с диска (папка или .zip) без сервера")) openOffline();
        if (gui.button("test", x + 2 * (bw + 6), by, bw, 20, "Проверить", "Войти и показать список корня")) test();
        if (gui.primary("connect", x + 3 * (bw + 6), by, bw, 20, "Подключиться", null, true)) connect();
    }

    @Override protected void onKey(int key, int mods) { if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) connect(); }

    private void parseUrlIntoFields() {
        String h = cur.host.trim();
        if (!h.contains("://")) return;
        try {
            URI u = new URI(h);
            String sch = u.getScheme() == null ? "ftp" : u.getScheme().toLowerCase();
            cur.protocol = sch.equals("sftp") ? "SFTP" : sch.equals("ftps") ? "FTPS" : "FTP";
            cur.host = u.getHost() == null ? "" : u.getHost();
            cur.port = u.getPort() > 0 ? u.getPort() : (cur.protocol.equals("SFTP") ? 22 : 21);
            if (u.getUserInfo() != null) {
                String[] ui = u.getUserInfo().split(":", 2);
                cur.user = ui[0];
                if (ui.length > 1) password = ui[1];
            }
            if (u.getPath() != null && !u.getPath().isEmpty()) cur.startDir = u.getPath();
            portEdited = true;
        } catch (Exception ignored) {}
    }

    private boolean validate() {
        boolean ok = true;
        if (cur.host.isBlank()) { hostErr = "Укажи адрес сервера"; ok = false; }
        if (cur.user.isBlank() && !"SFTP".equals(cur.protocol)) { userErr = "Укажи логин"; ok = false; }
        return ok;
    }

    RemoteFs makeFs() {
        ServerProfile p = cur;
        if ("SFTP".equals(p.protocol)) return new SftpFs(p, password, (fp, changed) -> ask(changed ? "ВНИМАНИЕ: ключ сервера изменился!" : "Новый сервер SFTP",
                (changed ? "Ключ сервера отличается от сохранённого. Это может означать подмену сервера!\n" : "Первое подключение к этому серверу.\n") + "Отпечаток ключа:\n" + fp + "\nДоверять?"));
        return new FtpFs(p, password, (fp, subj) -> ask("Сертификат сервера", "Сертификат не подтверждён центром сертификации (самоподписанный).\n" + subj + "\nSHA-256: " + fp + "\nДоверять этому сертификату?"));
    }

    /** Вопрос из фонового потока: ждём ответа пользователя. */
    private boolean ask(String title, String text) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        client.execute(() -> dialogs.push(new ChoiceDialog(title, text, new String[]{"Доверять", "Отмена"}, i -> f.complete(i == 0))));
        try { return f.get(); } catch (Exception e) { return false; }
    }

    private void test() {
        if (!validate()) return;
        status = "Проверяю подключение…"; statusColor = Gui.TEXT2;
        RemoteFs fs = makeFs();
        Task.start("Проверка", t -> {
            List<RemoteFs.Entry> l = fs.list(cur.startDir.isBlank() ? "/" : cur.startDir);
            fs.close();
            status = "Подключение успешно. В папке " + (cur.startDir.isBlank() ? "/" : cur.startDir) + ": " + l.size() + " элементов.";
            statusColor = Gui.ACCENT;
        }, t -> { if (t.error != null) { status = RemoteFs.humanError(t.error); statusColor = Gui.ERR; } }, Task.IO);
    }

    private void connect() {
        if (!validate()) return;
        RemoteFs fs = makeFs();
        Task task = Task.start("Подключение к " + cur.host, t -> {
            t.set("Вход на сервер " + cur.host + "…", -1);
            fs.list(cur.startDir.isBlank() ? "/" : cur.startDir);
        }, t -> {
            if (t.error != null || t.cancelled) {
                fs.close();
                if (t.error != null) { status = RemoteFs.humanError(t.error); statusColor = Gui.ERR; }
                return;
            }
            saveProfile();
            client.setScreen(new BrowserScreen(this, cur, fs));
        }, Task.IO);
        dialogs.push(new Task.ProgressDialog(task));
    }

    private void saveProfile() {
        WbConfig cfg = WbConfig.get();
        cur.lastLogin = System.currentTimeMillis();
        if (cur.remember) {
            cur.encPassword = WbConfig.encrypt(password);
            if (!cfg.profiles.contains(cur)) cfg.profiles.add(cur);
        } else {
            cur.encPassword = null;
        }
        if (cfg.profiles.contains(cur)) cfg.lastProfile = cur.id;
        cfg.save();
    }

    private void openOffline() {
        dialogs.push(new ChoiceDialog("Открыть мир с диска", "Что открыть: папку мира или ZIP-архив? (внутри архива мир ищется автоматически)",
                new String[]{"ZIP-архив…", "Папку…", "Отмена"}, i -> {
            Path p = null;
            if (i == 0) p = FileDialogs.openFile("Архив с миром", System.getProperty("user.home"), new String[]{"*.zip"}, "ZIP-архив");
            else if (i == 1) p = FileDialogs.selectFolder("Папка мира (где лежит level.dat)", System.getProperty("user.home"));
            if (p == null) return;
            Path fp = p;
            Task task = Task.start("Открытие мира", t -> {
                t.set("Читаю " + fp.getFileName() + "…", -1);
                WorldSource s = WorldSource.offline(fp);
                client.execute(() -> EditorLauncher.open(this, s, null));
            }, t -> { if (t.error != null) toastError(t.error.getMessage(), t.error); }, Task.IO);
            dialogs.push(new Task.ProgressDialog(task));
        }));
    }

    static boolean crashChecked;

    /** Незакрытая сессия после вылета — предложить восстановить. */
    private void checkCrashed() {
        if (crashChecked) return;
        crashChecked = true;
        com.google.gson.JsonObject o = com.geniiy.worldbuilder.session.SaveManager.findCrashed();
        if (o == null) return;
        long ago = (System.currentTimeMillis() - o.get("time").getAsLong()) / 60000;
        String name = o.has("world") ? o.get("world").getAsString() : "?";
        dialogs.push(new ChoiceDialog("Несохранённая работа", "Найдена несохранённая работа над миром «" + name + "» (" + ago + " мин назад).",
                new String[]{"Восстановить", "Удалить", "Позже"}, i -> {
            if (i == 1) com.geniiy.worldbuilder.session.SaveManager.discard(o);
            if (i != 0) return;
            try {
                com.geniiy.worldbuilder.blocks.JavaStates.init();
                WorldSource src = new WorldSource();
                src.cacheDir = java.nio.file.Paths.get(o.get("cacheDir").getAsString());
                if (o.has("offlinePath")) src.offlinePath = java.nio.file.Paths.get(o.get("offlinePath").getAsString());
                if (o.has("profile")) {
                    ServerProfile p = WbConfig.get().profile(o.get("profile").getAsString());
                    if (p != null) {
                        cur = p;
                        String pw = WbConfig.decrypt(p.encPassword);
                        password = pw == null ? "" : pw;
                        src.profile = p; src.fs = makeFs(); src.remotePath = o.get("remotePath").getAsString();
                        src.snapshot = WorldSource.readSnapshot(src.cacheDir);
                    }
                }
                String sid = java.nio.file.Paths.get(o.get("dir").getAsString()).getFileName().toString();
                com.geniiy.worldbuilder.pe.PeWorld w = com.geniiy.worldbuilder.session.SaveManager.restore(o);
                EditorLauncher.openWorld(this, src, w, sid);
            } catch (Exception e) { toastError("Не удалось восстановить: " + e.getMessage(), e); }
        }));
    }

    @Override public void close() { client.setScreen(parent); }
}

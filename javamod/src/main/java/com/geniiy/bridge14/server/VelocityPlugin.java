package com.geniiy.bridge14.server;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.File;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Плагин Velocity: мост к серверу MCPE 0.14 живёт прямо в прокси, рядом с ViaVersion и Geyser.
 * Поднимает «сервер» 1.20.1 на 127.0.0.1, регистрирует его в Velocity (по умолчанию как "pe014")
 * и отправляет туда игроков при входе. ViaVersion переводит любую версию Java в 1.20.1, Geyser пускает Bedrock;
 * у игроков Bedrock на сервер 0.14 уходит их настоящий скин.
 *
 * Сборка не зависит от Velocity: аннотации и типы событий берутся из заглушек, остальное — через отражение.
 */
public final class VelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dir;
    private BridgeServer bridge;
    private Object registered;          // RegisteredServer
    private boolean sendOnJoin;

    @Inject
    public VelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dir) {
        this.proxy = proxy; this.logger = logger; this.dir = dir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        try {
            Properties d = BridgeServer.defaults();
            d.setProperty("listen.host", "127.0.0.1");
            d.setProperty("listen.port", "30066");
            d.setProperty("velocity.server-name", "pe014");
            d.setProperty("velocity.send-on-join", "true");
            File cfg = dir.resolve("config.properties").toFile();
            Properties p = BridgeServer.loadConfig(cfg, d,
                    "Bridge0_14 (Velocity): pe.* - server MCPE 0.14; listen.* - local port of the bridge (backend for Velocity); "
                            + "velocity.server-name - name in Velocity (/server pe014); velocity.send-on-join - send players there on join");
            bridge = new BridgeServer(p, logger::info);
            bridge.plugin = true;
            bridge.forwardingSecret = readSecret();
            ClassLoader geyser = pluginLoader("geyser");
            if (geyser != null) bridge.skins = new GeyserSkins(geyser, logger::info);
            bridge.start(dir.toFile());

            // зарегистрировать «сервер» в Velocity
            String name = p.getProperty("velocity.server-name", "pe014").trim();
            ClassLoader vl = proxy.getClass().getClassLoader();
            Class<?> infoCls = Class.forName("com.velocitypowered.api.proxy.server.ServerInfo", true, vl);
            Object info = infoCls.getConstructor(String.class, InetSocketAddress.class)
                    .newInstance(name, new InetSocketAddress("127.0.0.1", bridge.port()));
            Object existing = ((Optional<?>) find(proxy, "getServer", String.class).invoke(proxy, name)).orElse(null);
            if (existing != null) {
                Object ei = find(existing, "getServerInfo").invoke(existing);
                find(proxy, "unregisterServer", infoCls).invoke(proxy, ei);
            }
            registered = find(proxy, "registerServer", infoCls).invoke(proxy, info);
            sendOnJoin = Boolean.parseBoolean(p.getProperty("velocity.send-on-join", "true").trim());
            logger.info("Bridge0_14: сервер 0.14 " + p.getProperty("pe.host") + ":" + p.getProperty("pe.port")
                    + " доступен в Velocity как \"" + name + "\" (/server " + name + ")" + (sendOnJoin ? ", игроки попадают туда сразу" : "")
                    + (geyser != null ? "; Bedrock-скины из Geyser включены" : "; Geyser не найден"));
            logger.info("Bridge0_14: настройки — " + cfg.getAbsolutePath());
        } catch (Throwable t) {
            logger.warn("Bridge0_14 не запустился: " + t);
        }
    }

    @Subscribe
    public void onChoose(PlayerChooseInitialServerEvent e) {
        if (!sendOnJoin || registered == null) return;
        try {
            for (Method m : e.getClass().getMethods())
                if (m.getName().equals("setInitialServer") && m.getParameterCount() == 1) { m.invoke(e, registered); return; }
        } catch (Throwable t) {
            logger.warn("Bridge0_14: не смог отправить игрока на сервер 0.14: " + t);
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent e) {
        if (bridge != null) bridge.stop();
    }

    /** forwarding.secret Velocity (для проверки подписи modern forwarding). */
    private byte[] readSecret() {
        try {
            Path root = dir.toAbsolutePath().getParent().getParent();
            Path f = root.resolve("forwarding.secret");
            if (Files.isRegularFile(f)) return new String(Files.readAllBytes(f)).trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        return null;
    }

    /** Загрузчик классов другого плагина (например, Geyser), чтобы достать его API. */
    private ClassLoader pluginLoader(String id) {
        try {
            Object pm = find(proxy, "getPluginManager").invoke(proxy);
            Optional<?> c = (Optional<?>) find(pm, "getPlugin", String.class).invoke(pm, id);
            if (!c.isPresent()) return null;
            Optional<?> inst = (Optional<?>) find(c.get(), "getInstance").invoke(c.get());
            return inst.isPresent() ? inst.get().getClass().getClassLoader() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method find(Object o, String name, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass())
            for (Class<?> i : c.getInterfaces()) {
                try { return i.getMethod(name, params); } catch (NoSuchMethodException ignored) {}
            }
        return o.getClass().getMethod(name, params);
    }
}

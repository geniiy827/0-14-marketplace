package com.geniiy.worldbuilder;

import com.geniiy.worldbuilder.config.ServerProfile;
import com.geniiy.worldbuilder.remote.*;
import com.geniiy.worldbuilder.ui.Task;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Интеграционная проверка FTP (запускается, если задан -Dwb.ftpTest=порт и на localhost есть FTP с пользователем u/p). */
public class FtpIT {
    @Test void ftpRoundTrip() throws Exception {
        String port = System.getProperty("wb.ftpTest", "");
        assumeTrue(!port.isEmpty(), "FTP-сервер для теста не задан");
        ServerProfile p = new ServerProfile();
        p.host = "127.0.0.1"; p.port = Integer.parseInt(port); p.user = "u";
        FtpFs fs = new FtpFs(p, "p", null);
        List<RemoteFs.Entry> root = fs.list("/");
        assertTrue(root.stream().anyMatch(e -> e.name().equals("worlds") && e.dir()));
        Map<String, long[]> files = WorldSource.listRemoteWorld(fs, "/worlds/world");
        assertTrue(files.containsKey("level.dat"));
        assertTrue(files.containsKey("region/r.0.0.mcr"));
        Path tmp = Files.createTempFile("dl", ".mcr");
        fs.download("/worlds/world/region/r.0.0.mcr", tmp, null);
        assertEquals(files.get("region/r.0.0.mcr")[0], Files.size(tmp));
        fs.mkdirs("/worlds/copy/region");
        fs.upload(tmp, "/worlds/copy/region/r.0.0.mcr.wbtmp", null);
        fs.rename("/worlds/copy/region/r.0.0.mcr.wbtmp", "/worlds/copy/region/r.0.0.mcr");
        assertEquals(Files.size(tmp), fs.size("/worlds/copy/region/r.0.0.mcr"));
        assertEquals(-1, fs.size("/worlds/copy/region/nope.mcr"));
        assertTrue(fs.exists("/worlds/copy/region/r.0.0.mcr"));
        fs.delete("/worlds/copy/region/r.0.0.mcr");
        assertFalse(fs.exists("/worlds/copy/region/r.0.0.mcr"));
        fs.close();
    }
}

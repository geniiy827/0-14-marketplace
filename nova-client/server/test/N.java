import com.nova.client.*;
import java.net.*;
import java.util.*;
public class N {
  static String pem;
  static AccountClient cl() throws Exception { return new AccountClient("127.0.0.1", 19999, pem); }
  public static void main(String[] a) throws Exception {
    boolean relay = a.length > 0;
    pem = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/home/claude/work/assets_new/nova/account_pub.pem")));
    AccountClient ua = cl(), ub = cl();
    AccountClient.Result ra = ua.register("HostA", "secret12", "secret12", "pc"), rb = ub.register("JoinB", "secret12", "secret12", "pc");
    System.out.println("reg " + ra.ok + " " + rb.ok);
    Map<String,Object> f = new HashMap<>(); f.put("tag", "JoinB");
    System.out.println("request: " + ua.op("friends.request", ra.str("token"), f).msg);
    f.put("tag", "HostA");
    System.out.println("accept: " + ub.op("friends.accept", rb.str("token"), f).msg);
    // fake host game: echo server
    DatagramSocket game = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
    new Thread(() -> { byte[] b = new byte[2048]; while (true) { try { DatagramPacket p = new DatagramPacket(b, b.length); game.receive(p); if (b[0] == 5) System.out.println("game got OCR1 of " + p.getLength());
      byte[] r = ("echo:" + new String(b, 0, p.getLength())).getBytes(); game.send(new DatagramPacket(r, r.length, p.getSocketAddress())); } catch (Exception e) { return; } } }).start();
    NovaNet.Listener L = new NovaNet.Listener() { public void invites(List<Map<String,Object>> i) { if (!i.isEmpty()) System.out.println("invites " + i); }
      public void incomingRequests(int n) {} public void log(String s) { System.out.println("log: " + s); } };
    AccountClient ta = cl(), tb = cl(); ta.token = ra.str("token"); tb.token = rb.str("token");
    NovaNet A = new NovaNet(ta, "127.0.0.1", 19999, L), B = new NovaNet(tb, "127.0.0.1", 19999, L);
    if (relay) { A.allowDirect = false; B.allowDirect = false; }
    A.gamePort = game.getLocalPort();
    Map<String,Object> w = new HashMap<>(); w.put("name", "Мир A"); w.put("mode", "friends"); w.put("max", 5.0); A.world = w;
    A.start(); B.start();
    Thread.sleep(1500);
    AccountClient.Result fl = ub.op("friends.list", rb.str("token"), null);
    System.out.println("B friends: " + fl.data.get("friends"));
    int[] port = new int[1];
    AccountClient.Result j = B.join("HostA", port);
    System.out.println("join " + j.ok + " " + j.msg + " port " + port[0]);
    DatagramSocket g2 = new DatagramSocket(); g2.setSoTimeout(1000);
    int got = 0; long t0 = System.currentTimeMillis();
    for (int i = 0; i < 40; i++) {
      byte[] m = ("pkt" + i).getBytes(); g2.send(new DatagramPacket(m, m.length, InetAddress.getByName("127.0.0.1"), port[0]));
      try { byte[] b = new byte[2048]; DatagramPacket p = new DatagramPacket(b, b.length); g2.receive(p); if (new String(b, 0, p.getLength()).equals("echo:pkt" + i)) got++; } catch (Exception e) {}
      Thread.sleep(100);
    }
    System.out.println("echoed " + got + "/40 in " + (System.currentTimeMillis() - t0) + " ms; links A=" + A.linkInfo() + " B=" + B.linkInfo());
    System.out.println("players while playing: " + A.players());
    // big packet: an OpenConnectionRequest1 of 1492 bytes must arrive cut to 1372
    byte[] ocr = new byte[1492]; ocr[0] = 5; byte[] mg = {0,(byte)0xff,(byte)0xff,0,(byte)0xfe,(byte)0xfe,(byte)0xfe,(byte)0xfe,(byte)0xfd,(byte)0xfd,(byte)0xfd,(byte)0xfd,0x12,0x34,0x56,0x78};
    System.arraycopy(mg, 0, ocr, 1, 16);
    g2.send(new DatagramPacket(ocr, ocr.length, InetAddress.getByName("127.0.0.1"), port[0]));
    try { byte[] b = new byte[4096]; DatagramPacket p = new DatagramPacket(b, b.length); g2.receive(p); System.out.println("OCR1 echoed length " + (p.getLength() - 5)); } catch (Exception e) { System.out.println("OCR1 lost"); }
    // mod files: 300 KB from the host
    final byte[] big = new byte[300000]; new Random(5).nextBytes(big);
    A.files = new NovaNet.Files() { public byte[] file(int i) { return i == 3 ? big : null; } };
    long t1 = System.currentTimeMillis();
    byte[][] dl = B.download("HostA", new int[]{3}, null);
    System.out.println("download " + (Arrays.equals(dl[0], big) ? "OK" : "BAD") + " " + dl[0].length + " bytes in " + (System.currentTimeMillis() - t1) + " ms");
    try { B.download("HostA", new int[]{7}, null); System.out.println("missing file: no error?!"); } catch (Exception e) { System.out.println("missing file: " + e.getMessage()); }
    // invite
    Map<String,Object> fi = new HashMap<>(); fi.put("tag", "JoinB");
    System.out.println("invite: " + ua.op("invite", ra.str("token"), fi).msg);
    Thread.sleep(800);
    g2.close();
    System.out.println("waiting for the joiner's link to close (game stopped)...");
    Thread.sleep(21000);
    System.out.println("links after: A=" + A.linkCount() + " B=" + B.linkCount() + " players " + A.players());
    A.stop(); B.stop(); game.close();
    System.exit(0);
  }
}

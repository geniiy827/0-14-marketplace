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
    new Thread(() -> { byte[] b = new byte[2048]; while (true) { try { DatagramPacket p = new DatagramPacket(b, b.length); game.receive(p);
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
    // invite
    Map<String,Object> fi = new HashMap<>(); fi.put("tag", "JoinB");
    System.out.println("invite: " + ua.op("invite", ra.str("token"), fi).msg);
    Thread.sleep(800);
    A.stop(); B.stop(); game.close();
    System.exit(0);
  }
}

import com.nova.client.AccountClient;
public class T {
  public static void main(String[] a) throws Exception {
    String pem = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/home/claude/work/assets_new/nova/account_pub.pem")));
    AccountClient c = new AccountClient("127.0.0.1", 19999, pem);
    System.out.println("hello " + c.hello());
    p("bad tag", c.register("ab", "secret1", "secret1", "pc"));
    p("mismatch", c.register("Nova_Tester", "secret1", "secret2", "pc"));
    AccountClient.Result r = c.register("Nova_Tester", "secret12", "secret12", "pc"); p("register", r);
    p("taken", c.register("nova_tester", "secret12", "secret12", "pc"));
    p("wrong", c.login("Nova_Tester", "nope", "pc"));
    AccountClient.Result l = c.login("NOVA_TESTER", "secret12", "pc"); p("login", l);
    p("resume", c.resume(l.str("token")));
    p("logout", c.logout(l.str("token")));
    p("resume after logout", c.resume(l.str("token")));
    for (int i = 0; i < 6; i++) p("wrong" + i, c.login("Nova_Tester", "x" + i, "pc"));
    p("locked correct", c.login("Nova_Tester", "secret12", "pc"));
    p("op unknown", c.op("friends", null, null));
  }
  static void p(String n, AccountClient.Result r) { System.out.println(n + ": ok=" + r.ok + " err=" + r.err + " msg=" + r.msg + " tag=" + r.str("tag") + " tok=" + (r.str("token").length())); }
}

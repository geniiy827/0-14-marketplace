import com.nova.client.AccountClient;
public class H { public static void main(String[] a) throws Exception {
  String pem = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/home/claude/work/assets_new/nova/account_pub.pem")));
  System.out.println("hello real server: " + new AccountClient("nova-host.mcbe.in", 12055, pem).hello()); } }

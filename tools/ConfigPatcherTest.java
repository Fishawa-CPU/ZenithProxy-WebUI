// 手动回归测试：验证 ConfigPatcher 的反射写入逻辑，不依赖 ZenithProxy jar。
// 运行方式（在仓库根目录）：
//   javac -encoding UTF-8 -d build/tools \
//       src/main/java/com/zenith/webui/config/ConfigPatcher.java tools/ConfigPatcherTest.java
//   java -cp build/tools com.zenith.webui.config.ConfigPatcherTest
package com.zenith.webui.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 脱离 ZenithProxy 依赖验证 ConfigPatcher 的反射写入逻辑。 */
public class ConfigPatcherTest {

    public enum Mode { ONLINE, OFFLINE }

    public static class Auth {
        public String accountType = "offline";
        public String password = "";
        public String email = "not@set.com";
        public boolean openBrowserOnLogin = true;
    }

    public static class ClientServer {
        public String address = "2b2t.org";
        public int port = 25565;
        public String password = "";
    }

    public static class Whitelist {
        public boolean enable = true;
        public List<String> whitelist = List.of("a", "b");
    }

    public static class Extra {
        public Whitelist whitelist = new Whitelist();
    }

    public static class ServerCfg {
        public boolean verifyUsers = true;
        public Extra extra = new Extra();
        public long refreshMinutes = 5L;
    }

    public static class Client {
        public ClientServer server = new ClientServer();
        public boolean autoConnect = true;
        public double ratio = 1.5;
        public Mode mode = Mode.ONLINE;
    }

    public static class Root {
        public Auth authentication = new Auth();
        public Client client = new Client();
        public ServerCfg server = new ServerCfg();
        public int bindPort = 25565;
    }

    private static int pass = 0;
    private static int fail = 0;

    static void check(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "  -> " + detail);
        }
    }

    public static void main(String[] args) {
        Root root = new Root();
        Map<String, Object> patch = new LinkedHashMap<>();

        // JSON 数字经 Gson 解析后可能是 Double，用 Double 模拟最坏情况
        patch.put("client.server.address", "3C3U.ORG");
        patch.put("client.autoConnect", Boolean.FALSE);
        patch.put("client.server.port", 25566.0);
        patch.put("server.verifyUsers", "false");
        patch.put("client.ratio", 2.25);
        patch.put("client.mode", "OFFLINE");
        patch.put("server.refreshMinutes", 2147483647.0);
        patch.put("authentication.password", "/login secret");
        // 以下三条应当失败或跳过
        patch.put("client.server.nonexistent", "x");
        patch.put("server.extra.whitelist.whitelist", List.of("c"));
        patch.put("client.server.port", 999999.0); // 越界 -> 但同 key 已被覆盖，单独测

        patch.remove("client.server.port");
        patch.put("client.server.port", 25566.0);

        ConfigPatcher.Result r = ConfigPatcher.apply(root, patch);

        System.out.println("applied=" + r.applied() + " skipped=" + r.skipped() + " failed=" + r.failed());

        check("String 写入", "3C3U.ORG".equals(root.client.server.address), root.client.server.address);
        check("boolean 写入", !root.client.autoConnect, String.valueOf(root.client.autoConnect));
        check("int 从 Double 写入", root.client.server.port == 25566, String.valueOf(root.client.server.port));
        check("boolean 从字符串写入", !root.server.verifyUsers, String.valueOf(root.server.verifyUsers));
        check("double 写入", root.client.ratio == 2.25, String.valueOf(root.client.ratio));
        check("enum 写入", root.client.mode == Mode.OFFLINE, String.valueOf(root.client.mode));
        check("long 写入", root.server.refreshMinutes == 2147483647L, String.valueOf(root.server.refreshMinutes));
        check("敏感字段可写", "/login secret".equals(root.authentication.password), root.authentication.password);
        check("未知字段进 failed", r.failed().stream().anyMatch(s -> s.startsWith("client.server.nonexistent")), r.failed().toString());
        check("List 字段进 skipped", r.skipped().contains("server.extra.whitelist.whitelist"), r.skipped().toString());
        check("白名单未被改动", root.server.extra.whitelist.whitelist.size() == 2, root.server.extra.whitelist.whitelist.toString());

        // 越界
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("client.server.port", 3000000000.0);
        ConfigPatcher.Result r2 = ConfigPatcher.apply(root, bad);
        check("越界数字进 failed", !r2.ok() && r2.applied() == 0, r2.failed().toString());
        check("越界后原值保留", root.client.server.port == 25566, String.valueOf(root.client.server.port));

        // null 值
        Map<String, Object> nullPatch = new LinkedHashMap<>();
        nullPatch.put("client.server.address", null);
        ConfigPatcher.Result r3 = ConfigPatcher.apply(root, nullPatch);
        check("null 值进 skipped", r3.skipped().contains("client.server.address"), r3.skipped().toString());
        check("null 未覆盖原值", "3C3U.ORG".equals(root.client.server.address), root.client.server.address);

        // 非法枚举
        Map<String, Object> badEnum = new LinkedHashMap<>();
        badEnum.put("client.mode", "NOPE");
        ConfigPatcher.Result r4 = ConfigPatcher.apply(root, badEnum);
        check("非法枚举进 failed", !r4.ok(), r4.failed().toString());

        // 空路径
        Map<String, Object> emptyPath = new LinkedHashMap<>();
        emptyPath.put("", 1);
        ConfigPatcher.Result r5 = ConfigPatcher.apply(root, emptyPath);
        check("空路径进 failed", !r5.ok(), r5.failed().toString());

        System.out.println("\n==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}

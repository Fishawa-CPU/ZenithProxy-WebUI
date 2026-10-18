# Bot Manager（多 bot 桌面管理器）

这个分支 `bot-desk` 在 WebUI 插件仓库的基础上，把三个东西放进了一个仓库，**一条命令构建出可分发产物**：

| 目录 | 内容 | 说明 |
|---|---|---|
| `.`（仓库根） | ZenithProxy-WebUI 插件 | 原有的 WebUI 插件，前端已加本地图表与相对路径适配 |
| `zenithproxy/` | ZenithProxy 本体 | 上游源码 + **配置热重载补丁** |
| `manager/` | BotManager 管理器 | 单端口网关 + 进程管理 + 中文全配置编辑器 |
| `build.ps1` | 构建脚本 | 三个模块 → jlink 运行时 → app-image/安装包 → zip |

## 构建

要求：**Windows 10/11** + **JDK 21**（`JAVA_HOME` 指向它；脚本也会自动找 `C:\Program Files\Java\jdk-21*`）。首次构建需要联网拉 Gradle 依赖。

```powershell
git clone -b bot-desk https://github.com/Fishawa-CPU/ZenithProxy-WebUI.git
cd ZenithProxy-WebUI
.\build.ps1
```

产物在 `dist/`：

```
dist/
  ZenithProxyBotManager/                         app-image，免安装直接跑
  ZenithProxyBotManager-0.1.0-portable.zip       上面那个目录的压缩包
  ZenithProxyBotManager-0.1.0.exe                Windows 安装包（需要 WiX）
```

只要免安装版：`.\build.ps1 -NoInstaller`

安装包需要 WiX 3.x，脚本依次找 `%WIX%` / PATH / `tools\wix\tools`，都没有就尝试从 nuget 下 `wix 3.14.1`；下载失败只跳过安装包，app-image 和 zip 不受影响。

## 管理器是怎么工作的

- **单端口网关**：所有 bot 收在 `127.0.0.1:9000` 一个入口下，`/bot/<名字>/` 反代到各自 WebUI（含 SSE 透传），内部端口不出现在界面上
- **每个 bot = 一个子进程 + 一个目录**：`bots/<名字>/`，配置 / 插件 / 日志 / 认证缓存完全隔离；创建那一刻就把完整 `config.json`（19KB）写进去
- **新建即全配置**：501 个字段中文可编辑，支持搜「离线」「端口」这类关键词
- **账号**：设备码登录 / 邮箱密码 / Prism / 离线四选一；离线模式下 `offlineUUID` 按原版 `OfflinePlayer:<名字>` 规则推导，不是随机值

> 2b2t 是正版验证服，只能用正版两种方式；离线只适合盗版服 / 本地测试服。

## 配置热重载（对 ZenithProxy 的改造）

上游的 `Globals.CONFIG` 是 `static final`，进程启动时读一次 `config.json`，之后改文件没有任何效果。

`zenithproxy/` 里补了两个类：

- `com.zenith.util.config.ConfigWatcher` —— 每 400ms 轮询 `config.json` 的 mtime/size，稳定后触发重载
- `com.zenith.util.config.ConfigReloader` —— 把新解析的 `Config` **原地深合并**进现有实例（final 引用不能换），集合清空重填、嵌套对象递归

解析失败只记日志、保留当前配置，不会 `System.exit`。日志里出现 `ConfigReload: applied in Nms` / `no-op` / `failed` 即结果。

所以本分支的 WebUI 配置中心里那句「restart to apply」，在这个打包版本里只对端口类字段成立，其余字段改完 1 秒内生效。

需要重启才生效的：`server.bind.port`、`plugins.webui.port`（重新 bind）、账号登录态。

bot 启动时带 `ZENITH_DEV=1`，关闭 ZenithProxy 的自动更新——否则它会把 `app/ZenithProxy.jar` 换回官方版，补丁就没了。

## 许可

- 仓库根本体：见 `LICENSE`
- `zenithproxy/`：来自 [rfresh2/ZenithProxy](https://github.com/rfresh2/ZenithProxy)，**AGPL-3.0**，本分支对其改动同样以 AGPL-3.0 发布

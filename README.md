# ZenithProxy Web UI

基于浏览器的 ZenithProxy 管理面板，提供实时状态监控、配置管理、命令执行和日志查看功能。

## 功能特性

- **状态监控仪表板**: 实时显示代理连接状态、当前用户、代理地址、运行时间、玩家连接数、队列位置、血量/饥饿度
- **系统资源监控**: CPU 占用、内存占用、网络流量、延迟波动的实时图表（Chart.js）
- **服务器控制**: 一键连接/断开/重连/关闭代理
- **配置管理**: 图形化编辑认证、客户端、服务器配置，保存后提示重启生效
- **命令终端**: 在网页中执行 ZenithProxy 命令，支持自动补全、命令历史、快捷键（Tab/↑↓/Ctrl+L/Ctrl+C）
- **日志查看器**: HTTP 轮询模式实时显示系统日志，支持日志级别颜色区分、导出
- **玩家列表**: 查看当前连接的玩家（控制器/观察者），支持踢出操作
- **白名单管理**: 启用/禁用白名单、添加/移除玩家、配置踢出消息、自动添加机器人账户
- **Title 消息推送**: 拦截服务器 Title 包并通过 SSE 推送到前端显示
- **日志翻译**: 常见英文日志消息自动翻译为中文

## 安装与启用

### 1. 构建插件

```bash
./gradlew shadowJar
```

输出文件：`build/libs/ZenithProxy-WebUI-1.0.0.jar`

### 2. 安装插件

将 `ZenithProxy-WebUI-1.0.0.jar` 复制到 ZenithProxy 的 `plugins/` 目录。

### 3. 配置 Web UI

在 `config.json` 中添加：

```json
{
  "webUI": {
    "enabled": true,
    "port": 8080
  }
}
```

或使用命令：

```
webui enable
```

### 4. 访问

启动 ZenithProxy 后，在浏览器中访问：

```
http://localhost:8080
```

## 配置选项

| 选项 | 默认值 | 说明 |
|------|--------|------|
| `enabled` | true | 是否启用 Web UI |
| `port` | 8080 | HTTP 服务器端口 |

## API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/status` | GET | 代理状态（连接状态、队列、版本、用户名、运行时间） |
| `/api/config` | GET | 获取 config.json 配置 |
| `/api/config` | POST | 更新配置（返回 `restartRequired: true`） |
| `/api/commands` | GET | 获取可用命令列表 |
| `/api/commands` | POST | 执行命令（`{"command": "..."}`） |
| `/api/players` | GET | 获取连接的玩家列表 |
| `/api/player` | GET | 获取当前玩家状态（血量、饥饿度、饱和度） |
| `/api/queue` | GET | 获取队列状态（是否在队列、位置） |
| `/api/logs` | GET | 获取日志文件列表 |
| `/api/logs?file=<name>` | GET | 下载指定日志文件 |
| `/api/logs/live?after=N` | GET | 轮询获取 N 之后的新日志 |
| `/api/logs/stream` | GET | SSE 实时日志推送 |
| `/api/system` | GET | 系统资源（CPU、内存、网络流量、线程数） |
| `/api/latency` | GET | 延迟信息（Bot→服务器、玩家→Bot） |
| `/api/whitelist` | GET | 获取白名单状态和列表 |
| `/api/whitelist` | POST | 管理白名单（`action`: toggle/add/remove/updateConfig） |

## 命令

| 命令 | 说明 |
|------|------|
| `webui enable` | 启用 Web UI |
| `webui disable` | 禁用 Web UI |
| `webui status` | 查看 Web UI 状态 |

## 前端页面

| 页面 | 说明 |
|------|------|
| 仪表板 | 代理状态、服务器控制按钮、系统资源图表 |
| 配置管理 | 认证/客户端/服务器配置编辑 |
| 终端日志 | 命令执行、日志查看、导出 |
| 玩家列表 | 玩家管理、白名单管理 |

## 前端快捷键

| 快捷键 | 说明 |
|--------|------|
| `Tab` | 命令自动补全 |
| `↑` / `↓` | 浏览命令历史 |
| `Ctrl+L` | 清空终端 |
| `Ctrl+C` | 取消当前输入 |

## 技术栈

- **后端**: Java 21, `com.sun.net.httpserver.HttpServer`, Gson, Logback
- **前端**: 原生 HTML/CSS/JavaScript（无框架）, Chart.js
- **日志传输**: HTTP 轮询 + SSE (Server-Sent Events)
- **构建**: Gradle + Shadow JAR

## 故障排除

### 无法访问 Web UI

1. 检查 `config.json` 中 `webUI.enabled` 是否为 `true`
2. 检查端口是否被占用
3. 检查防火墙设置

### 命令执行失败

1. 检查命令语法是否正确
2. 查看终端日志中的错误信息

### 日志不显示

1. 确认 Web UI 服务已启动（查看启动日志）
2. 检查浏览器控制台是否有网络错误

## 安全注意事项

- 默认情况下 Web UI 不需要认证
- 建议仅在本地网络使用
- 不要将 Web UI 暴露在公共网络上
- 生产环境建议配置反向代理并添加认证

## 项目结构

```
src/main/java/com/zenith/webui/
  WebUIPlugin.java          # 插件入口，HTTP 处理器，SSE，静态文件服务
  TitlePacketHandler.java   # Title 包拦截处理

src/main/resources/
  web/
    index.html              # 前端页面
    app.js                  # 前端逻辑
    styles.css              # 样式（Catppuccin Macchiato 配色）
  zenithproxy.plugin.json   # 插件元数据
```

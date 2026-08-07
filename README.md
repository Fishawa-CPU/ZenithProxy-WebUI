# ZenithProxy Web UI

基于浏览器的 ZenithProxy 管理面板，提供实时状态监控、配置管理、命令执行和日志查看功能。

## 功能特性

- **状态监控仪表板**: 实时显示代理连接状态、当前用户、代理地址、运行时间、玩家连接数、队列位置
- **系统资源监控**: CPU 占用、内存占用、网络流量、延迟波动的实时图表（Chart.js）
- **服务器控制**: 一键连接/断开/重连/关闭代理
- **配置管理**: 图形化编辑认证、客户端、服务器配置，保存后提示重启生效
- **命令终端**: 在网页中执行 ZenithProxy 命令，支持自动补全、命令历史、快捷键（Tab/↑↓/Ctrl+L/Ctrl+C）
- **日志查看器**: SSE 实时推送系统日志，支持日志级别颜色区分、导出
- **玩家列表**: 查看当前连接的玩家（控制器/观察者），支持踢出操作
- **白名单管理**: 启用/禁用白名单、添加/移除玩家、配置踢出消息、自动添加机器人账户
- **日志翻译**: 常见英文日志消息自动翻译为中文
- **用户认证**: Cookie 认证 + 速率限制，首次启动自动生成随机密码

## 安装与启用

### 1. 构建插件

```bash
./gradlew shadowJar
```

输出文件：`build/libs/ZenithProxy-WebUI-1.2.0.jar`

### 2. 安装插件

将 `ZenithProxy-WebUI-1.2.0.jar` 复制到 ZenithProxy 的 `plugins/` 目录。

### 3. 配置文件

插件首次启动时会在 `plugins/webui/config.json` 生成配置并自动创建随机密码：

```json
{
  "enabled": true,
  "port": 8080,
  "auth": {
    "enabled": true,
    "username": "admin",
    "password": "<16位随机密码>"
  }
}
```

### 4. 命令管理

```
webui enable        # 启用 Web UI
webui disable       # 禁用 Web UI
webui status        # 查看 Web UI 状态
```

### 5. 访问

启动 ZenithProxy 后，在浏览器中访问：

```
http://localhost:8080
```

## 配置选项

| 选项 | 默认值 | 说明 |
|------|--------|------|
| `enabled` | true | 是否在插件加载时自动启动 Web UI |
| `port` | 8080 | HTTP 服务器端口 |
| `auth.enabled` | true | 是否启用登录认证 |
| `auth.username` | admin | 登录用户名 |
| `auth.password` | 随机生成 | 登录密码（首次启动自动生成并输出到日志） |

> 要禁用认证，将 `auth.enabled` 设为 `false` 即可。

## API 端点

| 端点 | 方法 | 认证 | 说明 |
|------|------|------|------|
| `/api/auth/login` | POST | 否 | 登录 `{"username": "...", "password": "..."}` → 设置 HttpOnly Cookie |
| `/api/auth/logout` | POST | 否 | 清除认证 Cookie |
| `/api/auth/status` | GET | 否 | 返回 `{"authenticated": true/false, "username": "..."}` |
| `/api/dashboard` | GET | 是 | 聚合仪表盘数据（连接状态、队列、版本、运行时间、玩家、CPU、内存、网络、延迟） |
| `/api/dashboard/stream` | GET | 是 | SSE 实时推送仪表盘数据（每秒） |
| `/api/commands` | GET | 是 | 获取可用命令列表 |
| `/api/commands` | POST | 是 | 执行命令 `{"command": "..."}` |
| `/api/logs/stream` | GET | 是 | SSE 实时日志推送（推送最近 500 条历史 + 实时日志） |
| `/api/whitelist` | GET | 是 | 获取白名单状态、列表、配置信息 |
| `/api/whitelist` | POST | 是 | 白名单管理 `{"action": "toggle/add/remove/updateConfig", ...}` |

> `/api/status`, `/api/players`, `/api/queue`, `/api/system`, `/api/latency` 等独立端点已合并到 `/api/dashboard`（聚合返回）。前端不依赖这些独立端点。

## 命令

| 命令 | 说明 |
|------|------|
| `webui enable` | 启用 Web UI（可重复调用，幂等安全） |
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

- **后端**: Java 21, Netty NIO (零拷贝文件传输), Gson, Logback
- **前端**: 原生 HTML/CSS/JavaScript（无框架）, Chart.js
- **实时传输**: SSE (Server-Sent Events)
- **构建**: Gradle + Shadow JAR

## 认证机制

- 使用 `webui_token` HttpOnly Cookie（SameSite=Strict, Path=/）
- 也支持 `Authorization: Bearer <token>` Header
- Token 有效期 24 小时，每次请求滚动刷新
- 速率限制：同一 IP 每 15 分钟最多 5 次登录失败
- SSE 连接同样需要认证（通过 Cookie 自动传递）
- 过期 Token 在仪表盘更新周期（每秒）中被检测并断开

## 故障排除

### 无法访问 Web UI

1. 检查 `plugins/webui/config.json` 中端口配置
2. 检查端口是否被占用：`lsof -i :8080`
3. 查看 ZenithProxy 启动日志确认 WebUI 是否成功启动

### 忘记密码

查看 ZenithProxy 启动日志中的首次生成密码提示：
```
Created WebUI config with defaults (random password generated)
Default username: admin, password: xxxxxxxx
```
或直接编辑 `plugins/webui/config.json` 中的 `auth.password`。

### 命令执行失败

1. 检查命令语法是否正确
2. 查看终端日志中的错误信息

### 日志不显示

1. 确认 Web UI 服务已启动
2. 检查浏览器控制台是否有网络错误
3. SSE EventSource 依赖同源 Cookie，确保非跨域访问

## 安全注意事项

- 首次启动自动生成随机密码，请查看日志获取
- 默认启用认证，建议保持 `auth.enabled: true`
- 仅在本地网络使用
- 不要将 Web UI 暴露在公共网络上
- 生产环境建议配置反向代理（如 Nginx）并启用 HTTPS

## 项目结构

```
src/main/java/com/zenith/webui/
  WebUIPlugin.java          # 插件入口，HTTP 处理器，SSE，静态文件服务，Logback Appender
  TitlePacketHandler.java   # Title 包拦截处理
  auth/
    AuthHandler.java        # 认证过滤器（Cookie + Bearer Token 提取/验证）
    AuthManager.java        # Token 生成与验证、速率限制
    AuthController.java     # 登录/登出/状态 HTTP 处理
  config/
    WebUIConfig.java        # 配置数据类（端口、认证）
    ConfigManager.java      # 配置文件管理（首次启动自动生成随机密码）
  queue/
    QueueParser.java        # 2b2t 队列位置解析

src/main/resources/
  web/
    index.html              # 主前端页面
    login.html              # 登录页面
    app.js                  # 前端逻辑
    styles.css              # 样式（Catppuccin Macchiato 配色）
  zenithproxy.plugin.json   # 插件元数据
```

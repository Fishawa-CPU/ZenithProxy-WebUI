# ZenithProxy Web UI 使用说明

## 功能特性

- **状态监控仪表板**: 实时显示代理状态、连接信息、玩家连接、系统资源(CPU/内存/网络/延迟)
- **模块管理**: 启用/禁用各种功能模块，模块名称显示中文翻译
- **配置管理**: 图形化编辑配置文件，保存后提示重启生效
- **命令终端**: 在网页中执行ZenithProxy命令，支持自动补全和命令历史
- **日志查看器**: 实时查看系统日志(SSE推送)，支持导出
- **玩家列表**: 查看当前连接的玩家
- **白名单管理**: 启用/禁用白名单，添加/移除玩家，配置踢出消息和自动添加机器人账户

## 安装与启用

### 1. 启用Web UI

在config.json中添加以下配置：

```json
{
  "webUI": {
    "enabled": true,
    "port": 8080
  }
}
```

或者使用命令：

```
webui enable
```

### 2. 启动ZenithProxy

启动ZenithProxy后，Web UI服务器会自动启动。

### 3. 访问Web UI

在浏览器中访问：

```
http://localhost:8080
```

## 配置选项

| 选项 | 默认值 | 说明 |
|------|--------|------|
| `enabled` | false | 是否启用Web UI |
| `port` | 8080 | HTTP服务器端口 |

## API端点

- `GET /api/status` - 获取代理状态
- `GET /api/config` - 获取配置
- `POST /api/config` - 更新配置(返回 restartRequired: true)
- `GET /api/modules` - 获取模块列表
- `POST /api/modules` - 切换模块状态(自动持久化到config.json)
- `POST /api/commands` - 执行命令
- `GET /api/players` - 获取玩家列表
- `GET /api/queue` - 获取队列状态
- `GET /api/logs` - 获取日志文件列表
- `GET /api/logs/live?after=N` - 轮询获取新日志
- `GET /api/logs/stream` - SSE实时日志推送
- `GET /api/system` - 获取系统资源信息(CPU/内存/网络)
- `GET /api/latency` - 获取延迟信息(机器人→服务器、玩家→机器人)
- `GET /api/whitelist` - 获取白名单状态和列表
- `POST /api/whitelist` - 管理白名单(action: toggle/add/remove/updateConfig)

## 命令

- `webui enable` - 启用Web UI
- `webui disable` - 禁用Web UI
- `webui status` - 查看状态

## 故障排除

### 无法访问Web UI

1. 检查config.json中webUI.enabled是否为true
2. 检查端口是否被占用
3. 检查防火墙设置

### 命令执行失败

1. 检查命令语法是否正确
2. 查看日志中的错误信息

## 安全注意事项

- 默认情况下，Web UI不需要认证
- 建议在生产环境中启用认证
- 不要将Web UI暴露在公共网络上

# numen-bilibili-bridge

把 B 站直播间弹幕接进 [Numen](https://github.com/Dwinovo/minecraft-numen) 游戏内 AI 同伴。

mod 连接直播间的弹幕 WebSocket，把弹幕按秒/按条分批，通过 numen-api 的事件通道
（`<event kind="danmaku">`）发给在世的同伴。同伴用它自己的模型阅读、总结并回应——
本 mod 不调用任何 LLM，也不需要任何 API key。

- Minecraft 1.21.1，NeoForge 与 Fabric 双加载器
- 零第三方依赖：JDK HttpClient + WebSocket + Inflater，JSON 用 MC 自带的 Gson
- B 站协议知识完整记录在 [docs/protocol.md](docs/protocol.md)

## 安装

1. 先安装 [Numen](https://github.com/Dwinovo/minecraft-numen)（numen-api 引擎已内嵌在其 jar 内）。
2. 把本 mod 对应加载器的 jar 放进 `mods/`：
   - Fabric：`numen_bilibili_bridge-fabric-1.21.1-<版本>.jar`（另需 Fabric API）
   - NeoForge：`numen_bilibili_bridge-neoforge-1.21.1-<版本>.jar`

本 mod 不重复内嵌 numen-api；没有 Numen 时无法加载。

## 使用

进入世界并召唤同伴后：

```
/bilibridge connect 房间号     连接直播间（房间号写浏览器地址栏里的短号即可，自动保存进配置）
/bilibridge status            查看连接状态、弹幕计数、批次设置
/bilibridge disconnect        断开
```

连接成功后，直播间的弹幕与醒目留言（SC）会分批送达同伴。批次触发条件为
「时间窗到期」或「条数到达上限」，先到先发；同一文本 10 秒内重复只保留一条。

批次参数可用命令调整（自动保存）：

```
/bilibridge window <秒>        批次时间窗，默认 5
/bilibridge max <条数>         批次最大条数，默认 30
/bilibridge urgent true|false  true（默认）：弹幕批次立刻唤醒同伴回应；
                               false：随主人下一次对话一并送达
```

## 配置文件

`config/numen-bilibili-bridge.json`：

```json
{
  "roomId": 0,
  "sessdata": "",
  "batchWindowSeconds": 5,
  "batchMaxCount": 30,
  "urgent": true,
  "autoConnect": false
}
```

- `roomId`：直播间号（短号即可）。
- `sessdata`：可选的登录 cookie，见下方隐私说明。
- `autoConnect`：进入世界后自动连接已配置的直播间。

## 隐私说明

- **默认匿名连接**：不填 `sessdata` 也能收到完整的弹幕文本，但 B 站服务端会把
  发送者用户名打码、uid 置 0。用于让同伴总结弹幕意图完全够用。
- **`sessdata` 可选**：填入自己账号的 SESSDATA cookie 后能拿到真实用户名。
  该值只随请求发给 `bilibili.com` 域名下的官方接口，不写日志、不发往任何其他地方。
  SESSDATA 等同于登录态，请不要把配置文件分享给别人。
- mod 只读弹幕，不发弹幕、不点赞、不做任何写操作。

## 构建

```
./gradlew :common:build :fabric:build :neoforge:build
```

产物在 `fabric/build/libs/` 与 `neoforge/build/libs/`。
依赖的 numen-api 从 [numen-maven](https://github.com/Dwinovo/numen-maven) 解析；
本地存在 sibling 目录 `../numen-maven` 时优先使用本地副本。

## 已知限制

- 目前只处理 `DANMU_MSG`（弹幕）和 `SUPER_CHAT_MESSAGE`（醒目留言）；
  礼物（`SEND_GIFT`）、观看人数（`WATCHED_CHANGE`）等命令留待后续。
- 服务端下发 brotli 压缩帧（协议版本 3）时记一条日志并丢弃；
  客户端声明 protover 2 后正常只会收到 zlib。
- 弹幕在没有在世同伴时会被丢弃（事件通道需要活着的同伴身体）。

## 许可

LGPL-3.0-only，与 Numen 一致。

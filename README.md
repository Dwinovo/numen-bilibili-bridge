# numen-bilibili-bridge

把 B 站直播间弹幕接进 [Numen](https://github.com/Dwinovo/minecraft-numen) 游戏内 AI 同伴。

心智模型是**一个同伴负责一个直播间**：用 `/bilibridge bind` 把同伴和房间号配对，
mod 为每个配对的房间连接弹幕 WebSocket，把弹幕按秒/按条分批，通过 numen-api 的
事件通道（`<event kind="danmaku">`）送给绑定的那个同伴。同伴用它自己的模型阅读、
总结并回应——本 mod 不调用任何 LLM，也不需要任何 API key。可以同时开多个房间，
各配一个同伴；多个同伴也可以共守同一个房间（共享一条连接，各自都收到批次）。

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
/bilibridge bind <同伴名> <房间号>   绑定并连接（房间号写浏览器地址栏里的短号即可）
/bilibridge unbind <同伴名>          解绑；房间没有其他绑定时自动断开
/bilibridge status                   列出所有 房间↔同伴 绑定、各自连接状态与攒批统计
```

**绑定即连接**：`bind` 立刻建立到该房间的连接，绑定持久化保存，之后每次进入世界
自动重连所有已绑定的房间；`unbind` 撤掉绑定，最后一个绑定撤掉时断开该房间。
含中文或空格的同伴名在 `bind` 里需要加英文双引号（如 `/bilibridge bind "小雪" 123`），
`unbind` 与 `test` 直接写名字即可。

多房间示例——两个同伴各守一个直播间，第三个同伴与第一个共守：

```
/bilibridge bind Nia 92613
/bilibridge bind Momo 21452505
/bilibridge bind Kiki 92613        ← 与 Nia 共享 92613 的连接，批次两者各收一份
```

房间的弹幕与醒目留言（SC）分批后**只送给绑定该房间的在世同伴**（urgent 语义见下）。
绑定的同伴不在世时该批弹幕直接丢弃，聊天框会提示一次「同伴 X 不在场，房间 Y 的
本批弹幕已丢弃」，同伴回到场上前不再重复提示。批次触发条件为「时间窗到期」或
「原始条数到达阈值」，先到先发。

发送前，每个批次会走一条过滤聚合流水线，把进入模型的内容控制在可预算的范围内：

1. **每用户限流**：同一用户每窗口只保留最新 `perUserPerWindow` 条（匿名连接下
   uid 全为 0，按打码用户名归并）；
2. **计数聚合**：文本归一化后（去首尾空白、全角转半角、连续重复字符压缩，
   如「666666」与「６６６」视为相同）相同的弹幕合并为一行 `文本 ×N`；
3. **SC 优先**：醒目留言不参与限流与聚合，永远保留并置顶，标注价格；
4. **上限截断**：聚合后普通弹幕最多 `maxLines` 行，超出保留最新的，
   末尾追加一行「（另有 N 条弹幕略去）」。

事件头部带窗口时长与原始条数（如「30 秒内共 87 条弹幕」），同伴始终知道
真实的弹幕热度。

批次参数全局共享（对所有房间生效），可用命令调整（自动保存）：

```
/bilibridge window <秒>        批次时间窗，默认 15
/bilibridge max <条数>         触发发送的原始条数阈值，默认 50
/bilibridge lines <行数>       聚合后普通弹幕最多行数，默认 20
/bilibridge peruser <条数>     每用户每窗口保留最新几条，默认 1，0 = 不限
/bilibridge urgent true|false  true（默认）：弹幕批次立刻唤醒绑定的同伴回应，
                               每批消耗一次它的模型调用（token 花在谁身上一目了然）；
                               false：批次进入同伴上下文但不触发回合，
                               随主人下一次对话一并送达，几乎不额外费 token
```

## 配置文件

`config/numen-bilibili-bridge.json`：

```json
{
  "bindings": {
    "Nia": 92613,
    "Momo": 21452505
  },
  "sessdata": "",
  "batchWindowSeconds": 15,
  "batchMaxCount": 50,
  "maxLines": 20,
  "perUserPerWindow": 1,
  "urgent": true
}
```

- `bindings`：同伴名 → 直播间号（短号即可）。由 `/bilibridge bind`/`unbind` 维护，
  进入世界时自动连接其中每个不同的房间。
- `sessdata`：可选的登录 cookie，见下方隐私说明。
- `batchWindowSeconds`：批次时间窗（秒）。
- `batchMaxCount`：窗口未到但原始弹幕攒够这么多条时提前发送。
- `maxLines`：聚合后普通弹幕最多行数，超出保留最新的。
- `perUserPerWindow`：每用户每窗口保留最新几条，0 = 不限；SC 不受此限制。

## 本地验证

不开直播也能验证整条链路，三种方式任选：

1. **绑到别人的公开直播间**：`/bilibridge bind <同伴名> <任意热门房间号>`。
   本 mod 只读弹幕（观众视角，不登录、不发言），拿真实弹幕流验证最方便。
2. **绑自己的房间号，自己发弹幕**：B 站不开播也能在自己直播间的网页里发弹幕。
   `/bilibridge bind <同伴名> <自己的房间号>`，再在浏览器里进自己的直播间发几条即可。
3. **完全离线注入**：`/bilibridge test [同伴名] <文本>`。把一条假弹幕（用户名
   「测试观众」）注入该同伴所绑房间的弹幕队列，走完整的分批 → 过滤聚合 → 路由
   流程，零网络依赖。只有一个绑定时同伴名可省略；连发多次可以攒批，时间窗到期后
   一起送达。

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
- 绑定的同伴不在世时该房间的弹幕批次会被丢弃（事件通道需要活着的同伴身体），
  聊天框对每段缺席只提示一次。

## 许可

LGPL-3.0-only，与 Numen 一致。

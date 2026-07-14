# B 站直播弹幕协议

本文完整记录 bridge 实现所依据的协议知识。公开的参考文档源已不可考，
这里写下的内容以本仓库实现（`common/src/main/java/com/dwinovo/bilibridge/bili/`）
为准，实现与文档一一对应。

整个流程分两段：四步 REST 前奏拿到连接凭据，然后 WebSocket 收流。
全程可以匿名（不带任何登录态）完成。

## 一、REST 前奏

对应实现：`BiliApi.prepare()`。

### 1. 短号 → 真实房号

浏览器地址栏里的房间号可能是「短号」（如 `510`），弹幕服务只认真实房号：

```
GET https://api.live.bilibili.com/room/v1/Room/get_info?room_id=<短号>
```

响应 `data.room_id` 即真实房号。传入真实房号时该接口原样返回，所以无条件调用即可。

### 2. buvid3 设备 cookie

```
GET https://www.bilibili.com/
```

从响应的 `Set-Cookie` 中取 `buvid3=<值>`。这个值要放进后续请求的 Cookie 和
WebSocket 认证包的 `buvid` 字段——没有它连接会在认证后数秒内被服务端掐断。

### 3. WBI 签名密钥（2025-05 起 getDanmuInfo 强制校验）

```
GET https://api.bilibili.com/x/web-interface/nav
```

匿名调用返回 `code: -101`（未登录），但 `data.wbi_img` 依然在场，照常可用。
带 SESSDATA 调用时 `data.mid` 是登录账号的 uid（认证包要用）。

取 `data.wbi_img.img_url` 与 `sub_url`，各自去掉目录与扩展名得到 `imgKey`、`subKey`
（各 32 字符）。把 `imgKey + subKey` 拼成 64 字符串 `raw`，按下面的固定索引表
逐位取字符，得到 32 字符的 `mixinKey`：

```
[46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
 27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13]
```

即 `mixinKey[0] = raw[46]`，`mixinKey[1] = raw[47]`，依此类推。

密钥大约一天一换，缓存 12 小时以内是安全的（实现里 `WBI_KEY_TTL_MS`）。

**签名步骤**（对任意待签参数集）：

1. 加入 `wts=<当前 Unix 秒>`；
2. 按 key 字典序排序；
3. 每个 value 先删去 `!'()*` 五种字符，再做 percent 编码（空格必须是 `%20`，
   `URLEncoder` 的 `+` 要替换掉）；
4. 拼成 `k1=v1&k2=v2...` 查询串；
5. `w_rid = md5(查询串 + mixinKey)`（32 位小写 hex）；
6. 最终请求参数 = 查询串 + `&w_rid=<值>`。

对应实现与已验证向量：`WbiSigner` / `WbiSignerTest`
（`imgKey=7cd084941338484aae1ad9425b84077c`、`subKey=4932caff0ff746eab6f01bf08b70ac45`
→ `mixinKey=ea1db124af3c7062474693fa704f4ff8`）。

### 4. 弹幕服务器与 token

```
GET https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo
        ?id=<真实房号>&type=0&wts=..&w_rid=..
Cookie: buvid3=<值>[; SESSDATA=<值>]
```

`id`、`type` 参与 WBI 签名。SESSDATA 可选：匿名也能拿到 token，
区别只在后续弹幕里用户名是否打码。

响应：

- `data.token`：WebSocket 认证密钥（一次性，几分钟内有效）；
- `data.host_list[]`：候选服务器数组，每项含 `host` 与 `wss_port`（一般 443）。

所有 REST 请求带上浏览器 `User-Agent` 和 `Referer: https://live.bilibili.com/`。

## 二、WebSocket 收流

对应实现：`BiliDanmakuClient` + `BiliPacket`。

连接 `wss://{host}:{wss_port}/sub`。

### 帧格式

每个包 = 16 字节大端头 + 包体：

| 偏移 | 类型 | 含义 |
|-----|------|------|
| 0   | u32  | 包总长（头 + 体） |
| 4   | u16  | 头长，恒为 16 |
| 6   | u16  | 协议版本 |
| 8   | u32  | 操作码 |
| 12  | u32  | 序号（发包填 1 即可） |

一条 WebSocket 二进制消息可能背靠背携带**多个包**，解析要循环切分。

操作码：

| 值 | 方向 | 含义 |
|---|------|------|
| 2 | 上行 | 心跳 |
| 3 | 下行 | 心跳回应，体前 4 字节 u32 是人气值 |
| 5 | 下行 | 命令（弹幕、SC、礼物都在这里），体是 JSON |
| 7 | 上行 | 认证 |
| 8 | 下行 | 认证回应，体 `{"code":0}` 表示成功 |

协议版本：

| 值 | 含义 |
|---|------|
| 0 | 体是明文 JSON |
| 1 | 体是裸整数（心跳回应）；上行包也用 1 |
| 2 | 体是 zlib 压缩数据 |
| 3 | 体是 brotli 压缩数据 |

### 认证

连上后 **5 秒内**必须发认证包（op=7，头部 ver=1），体为 JSON：

```json
{"uid": 0, "roomid": <真实房号>, "protover": 2, "platform": "web",
 "type": 2, "buvid": "<buvid3>", "key": "<token>"}
```

- `uid`：匿名填 0；带 SESSDATA 时填 nav 返回的 `data.mid`。
- `protover: 2` 是关键——声明 2 后服务端**只用 zlib** 压缩命令包，
  `java.util.zip.Inflater` 就能解，不需要 brotli 第三方库。
  若仍收到 ver=3 的帧（异常情况），记日志丢弃即可，不要让它崩掉连接。

认证成功（op=8、`code:0`）后立刻发第一个心跳。

### 心跳

op=2（头部 ver=1），每 **30 秒**一次；60 秒收不到心跳会被服务端断开。
体内容任意，惯例填 `[object Object]`。

反过来客户端也要盯下行：服务端对每个心跳都回 op=3，所以健康连接的静默期
不会超过 30 秒左右。休眠、断网切网造成的半开 TCP 在本地永远不会自己报错——
实现里心跳任务兼任看门狗（`INBOUND_SILENCE_LIMIT_MS`），75 秒收不到任何
下行帧就判死重连（token 是短命的，此时直接重跑 REST 前奏）。发送失败同理，
立即按连接已死处理。

### 命令包的解压与嵌套

ver=2 的 op=5 包，zlib 解压后得到的是**若干完整包的拼接**（每个都带 16 字节头，
内层 ver=0），把解压结果送回帧切分器递归处理即可。

### JDK WebSocket 的半帧陷阱

`WebSocket.Listener.onBinary` 按传输分片回调，一条逻辑消息可能分多次到达：
把每次的 `ByteBuffer` 攒进缓冲，直到 `last == true` 才解析；每次回调后要
`request(1)` 续订下一片。

### 命令 JSON

`cmd` 字段区分类型。注意有带后缀的变体（如 `DANMU_MSG:4:0:2:2:2:0`），
必须按**前缀**匹配。

**`DANMU_MSG`**（弹幕）——数据在 `info` 数组里：

- `info[1]`：弹幕文本
- `info[2][0]`：发送者 uid
- `info[2][1]`：发送者用户名
- `info[9].ts`：发送时间（Unix 秒；此位置布局偶有变化，解析要防御）

**`SUPER_CHAT_MESSAGE`**（醒目留言）——数据在 `data` 对象里：

- `data.message`：文本
- `data.price`：价格（元）
- `data.uid` / `data.user_info.uname`：发送者

其余高频命令（本 bridge 暂未消费）：`SEND_GIFT`（礼物）、`WATCHED_CHANGE`
（看过人数）、`INTERACT_WORD`（进场）、`LIKE_INFO_V3_UPDATE`（点赞数）。

### 匿名连接的行为

uid=0 + 匿名 token 连接时，弹幕**文本完整**，但服务端把发送者用户名打码
（如 `账***`）、uid 置 0。带 SESSDATA 取 token 并用真实 uid 认证则一切完整。

### 断线重连

- 同一批 `host_list` 内轮换主机，退避指数增长（实现里 1s 起、封顶 30s）；
- token 是短命的：连败约 3 次后放弃缓存，重跑整个 REST 前奏；
- 认证被拒（op=8 非 0）说明 token 已失效，同样重跑前奏。

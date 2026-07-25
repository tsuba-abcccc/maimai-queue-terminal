# 队列与玩家资料云端同步协议

## 原则

- Android 终端是队列与玩家资料的最终数据源。
- 现场修改先在本机生效，再异步上传；网络故障不能阻止排队。
- 服务器修改以待执行命令返回终端，经本地规则校验和落盘后才算生效。
- 公开队列数据与私有玩家资料严格分离。
- QQ 是玩家在 Bot 中的身份键；当前版本不再保存手机号。

## 协议版本

新版终端发布 `schema_version: 3`，服务端继续接受版本 `1` 和 `2` 的公开队列快照。

完整请求体限制为 `1 MiB`，单次最多包含 `500` 份玩家资料和 `200` 条公开事件。

```text
Authorization: Bearer <QUEUE_SYNC_TOKEN>
X-Device-ID: <终端 UUID>
X-Queue-Schema-Version: 3
Content-Type: application/json; charset=utf-8
```

版本 3 在原有公开字段之外增加以下顶层字段：

- `onebot_sync_enabled`：现场终端当前是否允许 QQ Bot 联动。
- `business_hours`：只包含 `enabled`、`outside`、`closing_soon`、`closing_grace`、`closes_at` 和 `registration_closes_at` 六个计算结果，不上传完整营业时间表。`closing_soon` 在营业时段进入闭店前 30 分钟后为 `true`，`closes_at` 是本次闭店时间；`closing_grace` 表示已到闭店时间但现有队列仍在收尾，`registration_closes_at` 是最迟收尾时间。

- `private_player_profiles`：完整玩家资料库。
- `private_player_contacts`：当前登记与玩家资料、QQ 的关联。

服务端在写入公开快照前必须移除这两个字段，`GET /api/queue-status` 绝不能返回它们。

## 玩家资料字段

```json
{
  "profile_id": "37e41698-46f8-489b-92dc-d29c71f00f7d",
  "nickname": "示例玩家",
  "gender": "UNDISCLOSED",
  "default_preference": "OPEN_TO_JOIN",
  "qq_number": "12345678",
  "usage_count": 12,
  "last_used_at": 1784681000000,
  "created_at": 1780000000000,
  "updated_at": 1784681000000
}
```

`qq_number` 对尚未补充资料的旧记录可以为 `null`；创建和编辑资料时必须填写 `5` 至 `12` 位 QQ。云端按 `QUEUE_PROFILE_SCOPE_ID` 保存机厅资料库，终端上传采用增量覆盖，不会因一次空列表误删服务器备份。

## 当前登记绑定

```json
{
  "registration_id": "a10f5015a2e85b12e20af507",
  "profile_id": "37e41698-46f8-489b-92dc-d29c71f00f7d",
  "qq_number": "12345678"
}
```

只有使用玩家资料且 QQ 有效的当前登记会产生绑定。绑定必须引用同一负载中的资料，QQ 也必须一致。登记离队后，绑定在当前队列批次内保留，用于发送与本人有关的处理日志；开始新队列时清除。

## 公开接口

### 当前队列

`GET /api/queue-status`

返回机台名称、游玩位置、等待位置、昵称、状态、时间估算、QQ Bot 联动状态和营业时间计算结果。服务端使用接收时间计算 `terminal.online`，响应包含 `Cache-Control: no-store`。

### 公开日志

`GET /api/queue-logs?queue_id=<UUID>&limit=50&before=<游标>`

只返回白名单内的队列事件，不包含 QQ、性别、资料 UUID 或私有资料编辑日志。每条事件包含 `operation_source`，取值为 `ON_SITE_TERMINAL`、`QQ_BOT`、`SYSTEM_AUTOMATIC` 或预留的 `WEBSITE_REMOTE`。

## 私有 Bot 接口

所有请求使用：

```text
Authorization: Bearer <QUEUE_BOT_TOKEN>
```

`QUEUE_BOT_TOKEN` 必须与终端同步令牌不同，只能配置在 Koishi 服务端。它是服务级凭据，不是某一名玩家的登录凭据：持有者可以读取完整玩家资料库中的 QQ、性别和资料 UUID，读取当前登记绑定及通知事件的全部 QQ 收件人，并请求创建玩家资料修改命令。限制玩家只能查询和修改本人资料的是 Koishi 对 OneBot 会话身份的校验，因此 Bot 令牌不能交给玩家、浏览器或其他不受控客户端。

现场终端关闭“QQ Bot 联动”后，所有 `/api/queue-bot/` 接口返回 `503`。服务器会立即拒绝尚未完成的命令并删除当前队列的通知收件关系；关闭期间不创建新收件关系，重新开启后也不会补发旧事件。玩家资料库与公开队列快照仍然保留。

### 查询玩家

`POST /api/queue-bot/players`，JSON 请求体为 `{"qq":"<QQ号>"}`

返回该 QQ 当前是否在队列、所在机台、游玩或等待位置、时间估算、暂缓、暂离和未到场状态。

公开 `GET /api/queue-status` 仅在当前有效登记上附带 `qq_number`，供网站登记详情显示。完整玩家资料、性别、默认偏好和资料 UUID 仍只通过鉴权接口提供；登记离开队列后，其 QQ 不再出现在公开快照中。

受控服务也可以使用不含 QQ 查询条件的 `GET /api/queue-bot/players` 读取全部当前登记绑定。响应包含登记对应的 QQ，仅供 Bot 服务内部处理。

### 查询资料

`POST /api/queue-bot/profiles`，JSON 请求体为 `{"qq":"<QQ号>"}`

返回该 QQ 对应的私有玩家资料。若旧数据存在重复 QQ，可能返回多份，Bot 应提示人工选择，而不能任意取第一份。

`GET /api/queue-bot/profiles` 返回完整私有玩家资料库，包括 QQ、性别、默认偏好和资料 UUID。该接口不得从网站前端调用。

### 查询通知事件

`POST /api/queue-bot/events`，JSON 请求体为 `{"qq":"<QQ号>","after":<游标>,"limit":50}`

事件按递增游标返回。Koishi 保存 `next_cursor` 后只轮询新增事件，避免重复通知。`latest_cursor` 用于首次启动或切换队列时跳过既有日志，防止集中补发历史消息。`affected_players` 用于投递本人相关日志，`operation_source` 说明操作来源，`PLAYING_CHANGED` 可触发上机通知。

通知服务使用 `GET /api/queue-bot/events?after=<游标>&limit=50` 全量读取事件。每条事件的 `affected_players` 会包含当时固定的全部 QQ 收件人，因此该响应属于私有数据，不能转发到群聊或公开日志。

### 请求修改玩家资料

`PATCH /api/queue-bot/profiles/<profile_id>`

```json
{
  "request_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
  "actor_qq": "12345678",
  "nickname": "新昵称",
  "gender": "UNDISCLOSED",
  "default_preference": "OPEN_TO_JOIN"
}
```

服务器验证 `actor_qq` 与资料一致后创建命令，并返回 `202`。QQ 不能通过此接口修改。相同 `request_id` 保证幂等。

### 查询命令

`GET /api/queue-bot/commands/<command_id>`

状态包括 `PENDING`、`APPLIED` 和 `REJECTED`。拒绝原因位于 `result_detail`。

## 终端回流接口

### 恢复缺失资料

`GET /api/queue-terminal/profiles`

终端仅补回本地不存在的 UUID。同 UUID 以本地内容为准；与本地昵称或 QQ 冲突的云端资料不会自动合并。

### 拉取命令

`GET /api/queue-terminal/commands`

终端约每 `3` 秒读取待执行命令。资料命令同时校验：

1. 资料 UUID 和 QQ 与本地一致。
2. `expected_updated_at` 与本地版本一致。
3. 新昵称不与资料库或当前队列冲突。
4. 字段符合本机模型允许的枚举和长度。

本地资料已经等于命令目标时，终端视为幂等成功并补发回执。

### 命令回执

`POST /api/queue-terminal/commands/<command_id>/result`

```json
{"status":"APPLIED","detail":"玩家资料已由终端更新。"}
```

终端写入本机后再返回 `APPLIED`，随后正常上传新的资料快照。服务器不能自行把待执行命令直接改成正式资料。

## 尚未开放的能力

QQ Bot 修改排队顺序、加入、退出、暂缓或暂离尚未开放。它们将复用同一命令通道，但必须逐项定义身份校验、机台规则、确认语义和冲突处理；当前私有接口明确返回 `remote_actions: false`。

## 构建与安全

终端配置保存在开发账户的 `~/.gradle/gradle.properties`：

```properties
ENABLE_TERMINAL_BUILD=true
QUEUE_SYNC_URL=https://abcccc.top/api/queue-status
QUEUE_SYNC_TOKEN=<终端令牌>
```

同步令牌只会写入显式开启的 `terminal` 产品变体。公开发行必须构建 `assembleLocalRelease`；`local` 变体在 Gradle 中强制使用空地址和空令牌，不能因开发机保存了生产配置而意外带出凭据。现场终端使用 `packageTerminalDebugApk` 或 `assembleTerminalRelease`，生成物不得公开上传。QQ 与玩家资料通过 HTTPS 传输并存入私有数据库；数据库备份、Bot 令牌和终端令牌都应限制读取权限。

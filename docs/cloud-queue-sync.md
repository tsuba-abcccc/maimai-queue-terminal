# 队列与玩家资料云端同步协议

## 原则

- Android 终端是队列与玩家资料的最终数据源。
- 现场修改先在本机生效，再异步上传；网络故障不能阻止排队。
- 服务器修改以待执行命令返回终端，经本地规则校验和落盘后才算生效。
- 公开队列数据与私有玩家资料严格分离。
- QQ 是玩家在 Bot 中的身份键；当前版本不再保存手机号。
- 现场可按 A、B、C、D 的顺序连续配置 1 至 4 台机台；公开快照只包含实际配置的机台。
- App 本地持久化格式使用 schema 6，云端公开与私有同步协议继续使用 schema 5；两者不是同一份数据格式，不能混用版本号。

## 协议版本

新版终端发布 `schema_version: 5`，服务端继续接受版本 `1` 至 `4` 的旧公开队列快照。

完整请求体限制为 `1 MiB`，单次最多包含 `500` 份玩家资料和 `200` 条公开事件。

```text
Authorization: Bearer <QUEUE_SYNC_TOKEN>
X-Device-ID: <终端 UUID>
X-Terminal-Instance-ID: <本次进程启动生成的 UUID>
X-Terminal-Instance-Generation: <本机单调递增的正整数>
X-Queue-Schema-Version: 5
Content-Type: application/json; charset=utf-8
```

版本 3 增加私有玩家资料、当前登记联系信息、QQ Bot 联动状态和营业时间计算结果。版本 4 继续增加：

- `website_remote_enabled`：现场终端是否开启网站同步与网站远程命令通道。
- `queue_rules`：当前是否允许暂缓一次、暂时离开和创建线上登记，以及是否显示共同游玩预览，供网站、服务器、Bot 与终端共同校验和展示。
- 登记字段 `online_registration_pending_check_in`：该登记是否由网站或 Bot 建立且仍未在现场签到。

版本 5 增加玩家资料版本、使用记录、QQ 公开范围和通知设置，并为“使用移动设备登记”提供终端确认命令。旧协议上传的资料会使用保守默认值迁移，不会因此公开 QQ。

`business_hours` 只包含 `enabled`、`outside`、`closing_soon`、`closing_grace`、`closes_at` 和 `registration_closes_at` 六个计算结果，不上传完整营业时间表。`closing_soon` 在营业时段进入闭店前 30 分钟后为 `true`，`closes_at` 是本次闭店时间；`closing_grace` 表示已到闭店时间但现有队列仍在收尾，`registration_closes_at` 是最迟收尾时间。

- `private_player_profiles`：完整玩家资料库。
- `private_player_contacts`：当前登记以及最近公开事件中的登记与玩家资料、QQ 的关联。事件涉及的登记即使已离开队列，也可在本次快照中保留联系映射，以完成与该次操作有关的私信通知；该映射不会进入公开接口。

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
  "qq_visibility": "TERMINAL_ONLY",
  "notification_enabled": true,
  "notify_queue_changes": true,
  "notify_playing_position": false,
  "notify_online_check_in": true,
  "notify_absence": true,
  "notify_machine_status": false,
  "setup_version": 1,
  "profile_revision": 7,
  "created_at": 1780000000000,
  "updated_at": 1784681000000
}
```

`qq_number` 对尚未补充资料的旧记录可以为 `null`；创建和编辑资料时必须填写 `5` 至 `12` 位 QQ。`qq_visibility` 为 `TERMINAL_ONLY` 或 `PUBLIC_WEBSITE`，只控制当前有效登记是否向公开网站提供 QQ，不影响完整 QQ 在私有接口中的保存和 Bot 身份识别。

六个通知字段依次表示总开关、队列状态、游玩位置、线上登记与签到、暂缓一次/暂时离开/未到场、机台及营业状态。总开关关闭时所有分项均不投递；分项值仍保留，以便重新开启总开关后恢复原选择。`setup_version` 用于判断旧资料是否已经确认本版必填设置，`profile_revision` 每次资料变更递增，用于拒绝过期修改。

云端按 `QUEUE_PROFILE_SCOPE_ID` 保存机厅资料库，终端上传采用按 UUID 和 `profile_revision` 合并，不会因一次空列表误删服务器备份。同 UUID 只接受更高版本；昵称或 QQ 冲突时采用更高版本资料，并清除旧冲突资料的 QQ，避免一项身份同时绑定两份资料。

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

返回机台名称、游玩位置、按实际轮换推导的等待位置、昵称、状态、时间估算、QQ Bot 联动状态和营业时间计算结果。开放的单人位置可能包含 `common_play_preview`；它仅表示预计搭档，不属于该等待位置的真实登记，不计入登记数，也不能用于身份匹配或队列操作。服务端使用接收时间计算 `terminal.online`，响应包含 `Cache-Control: no-store`。

### 公开日志

`GET /api/queue-logs?queue_id=<UUID>&limit=50&before=<游标>`

只返回白名单内的队列事件，不包含 QQ、性别、资料 UUID 或私有资料编辑日志。每条事件包含 `operation_source`，取值为 `ON_SITE_TERMINAL`、`QQ_BOT`、`SYSTEM_AUTOMATIC`、`MOBILE_DEVICE` 或预留的 `WEBSITE_REMOTE`。

### 网站线上登记

- `POST /api/queue-online/profile`：按 QQ 查询一份可用于线上登记的玩家资料，并返回当前可选机台和是否已有登记。
- `POST /api/queue-online/join`：提交 `request_id`、QQ、机台编号和按需提供的本次游玩偏好。
- `GET /api/queue-online/commands/<command_id>`：查询终端是否已应用或拒绝该登记。

这组接口不返回完整玩家资料库，也不能修改已有队列。仅在终端在线、网站同步开启、现场规则允许线上登记且登记排队开放时接受新登记。线上登记进入等待末端后带有待签到状态，并按正常登记参与公开位置和等待时间估算。30 分钟从终端实际建立登记时开始计算；到期仍未签到时终端会自动移除，如果登记更早轮到进入游玩位置，也会立即移除。服务器不独立计时或修改队列。

## 私有 Bot 接口

所有请求使用：

```text
Authorization: Bearer <QUEUE_BOT_TOKEN>
```

`QUEUE_BOT_TOKEN` 必须与终端同步令牌不同，只能配置在 Koishi 服务端。它是服务级凭据，不是某一名玩家的登录凭据：持有者可以读取完整玩家资料库中的 QQ、性别和资料 UUID，读取当前登记绑定及通知事件的全部 QQ 收件人，并请求创建玩家资料修改命令。限制玩家只能查询和修改本人资料的是 Koishi 对 OneBot 会话身份的校验，因此 Bot 令牌不能交给玩家、浏览器或其他不受控客户端。

现场终端关闭“QQ Bot 联动”后，所有 `/api/queue-bot/` 接口返回 `503`。服务器会立即拒绝尚未完成的命令并删除当前队列的通知收件关系；关闭期间不创建新收件关系，重新开启后也不会补发旧事件。玩家资料库与公开队列快照仍然保留。

### 查询玩家

`POST /api/queue-bot/players`，JSON 请求体为 `{"qq":"<QQ号>"}`

返回该 QQ 当前是否在队列、所在机台、游玩或等待位置、时间估算、暂缓一次、暂时离开和未到场状态。

公开 `GET /api/queue-status` 仅在玩家将 `qq_visibility` 设为 `PUBLIC_WEBSITE` 时，才在当前有效登记上附带 `qq_number`，供网站登记详情显示。完整玩家资料和 QQ、性别、默认偏好及资料 UUID 始终只通过鉴权接口提供；登记离开队列后，其 QQ 不再出现在公开快照中。

受控服务也可以使用不含 QQ 查询条件的 `GET /api/queue-bot/players` 读取全部当前登记绑定。响应包含登记对应的 QQ，仅供 Bot 服务内部处理。

### 查询资料

`POST /api/queue-bot/profiles`，JSON 请求体为 `{"qq":"<QQ号>"}`

返回该 QQ 对应的私有玩家资料。若旧数据存在重复 QQ，可能返回多份，Bot 应提示人工选择，而不能任意取第一份。

`GET /api/queue-bot/profiles` 返回完整私有玩家资料库，包括 QQ、性别、默认偏好和资料 UUID。该接口不得从网站前端调用。

### 上报 Bot QQ

`POST /api/queue-bot/identity`，JSON 请求体为 `{"bot_qq":"<当前 OneBot QQ>"}`。

Koishi 在连接 OneBot 后上报实际登录 QQ，并定期刷新。终端拉取玩家资料时同时获得该 QQ，用于显示添加 Bot 好友的二维码；该身份仅供显示和通知引导，不参与玩家资料或排队权限判断。

### 查询通知事件

`POST /api/queue-bot/events`，JSON 请求体为 `{"qq":"<QQ号>","after":<游标>,"limit":50}`

事件按递增游标返回。Koishi 保存 `next_cursor` 后只轮询新增事件，避免重复通知。`latest_cursor` 用于首次启动或切换队列时跳过既有日志，防止集中补发历史消息。`affected_players` 用于投递本人相关日志，`operation_source` 说明操作来源。

事件通知分类与资料字段的映射固定如下：

- `PLAYING_CHANGED` 使用 `notify_playing_position`。
- 线上登记创建、完成签到、签到超时或轮到时未签到使用 `notify_online_check_in`。
- 暂缓一次、暂时离开、未到场及其处理使用 `notify_absence`。
- 机台停止/恢复、登记排队开启/关闭和营业状态使用 `notify_machine_status`。
- 其余本人相关登记、位置和队列变化使用 `notify_queue_changes`。

正常轮换引起的等待顺序前移属于游玩位置变化的结果，不会再额外触发 `notify_queue_changes`；玩家正常完成游玩并清除未到场记录时，会另建只关联该玩家的 `notify_absence` 事件。这样关闭某一分项后，不会因为同一轮操作中夹带的其他状态而绕过设置。

服务器在建立事件收件关系时按当时设置过滤；Koishi 实际发送前再次读取最新资料，因此玩家关闭总开关或对应分项后，尚在重试中的同类消息也会停止。

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

### 请求排队操作

`POST /api/queue-bot/queue-commands`

基础请求包含 `request_id`、`actor_qq` 和 `operation`。加入或切换机台时附带 `machine_id` / `target_machine_id`，修改本次偏好时附带 `preference`。支持线上加入、退出、暂缓一次、暂时离开、取消对应状态、切换机台和修改本次游玩偏好。

服务器只接受与 `actor_qq` 当前绑定相符的本人登记。待签到的线上登记仅允许退出；其 30 分钟签到时限和轮到时自动退出规则由终端执行。“允许线上登记”关闭后只拒绝新建线上登记，不影响查询、通知、资料同步或已有登记的退出。暂缓一次、暂时离开、机台状态、队列容量和固定组合等规则会在服务器和终端分别校验。相同 `request_id` 保证幂等。

## 终端回流接口

### 同步云端资料

`GET /api/queue-terminal/profiles`

终端读取当前作用域内的完整私有资料库。不存在于本机的 UUID 可以补回；同 UUID 只有云端 `profile_revision` 更高时才会回流。终端仍会拒绝与其他本机资料的昵称、QQ 或当前队列昵称发生冲突的内容，同版本或旧版本不会覆盖本机资料。终端接受回流后先写入本机，再随下一次快照确认给服务器。

### 拉取命令

`GET /api/queue-terminal/commands`

服务器只向当前队列快照对应的终端运行实例返回命令。命令领取后进入短期租约，同一实例在租约内重复请求不会再次取得该命令；租约结束后，尚未回执的命令可以由当前权威实例重新领取。旧版终端未发送实例请求头时，服务器会以稳定的 `X-Device-ID` 作为兼容实例。

终端约每 `3` 秒读取待执行命令。资料命令同时校验：

1. 资料 UUID 和 QQ 与本地一致。
2. `expected_profile_revision` 与本地资料版本一致；`expected_updated_at` 继续用于兼容和辅助校验。
3. 新昵称不与资料库或当前队列冲突。
4. 字段符合本机模型允许的枚举和长度。

本地资料已经等于命令目标时，终端视为幂等成功并补发回执。

`QUEUE_OPERATION` 命令还会重新校验队列批次、QQ 与资料绑定、登记编号、机台状态、登记上限、现场功能开关及当前登记状态。需要二次确认的操作会携带确认时看到的具体位置、固定组合编号、暂缓或暂离状态、轮空次数和签到状态；任一状态改变后，终端拒绝旧命令。只有本机持久化成功后才返回 `APPLIED`。线上加入命令会写入原命令编号，回执丢失后重复拉取仍能识别为已经执行，不会创建第二份登记。

### 使用移动设备登记

`POST /api/queue-terminal/mobile-registration-sessions` 由终端使用同步令牌创建短时会话，请求包含唯一 `request_id`、当前 `queue_id` 和目标 `machine_id`。服务端返回一次性会话令牌、过期时间和网页地址；相同请求重复发送时返回同一会话，参数不一致则拒绝。

移动网页使用以下公开接口，但只能访问对应会话的数据：

- `GET /api/queue-mobile/sessions/<token>?q=<搜索内容>`：读取会话、机台和可选玩家资料。
- `POST /api/queue-mobile/sessions/<token>/submit`：选择现有资料、补全旧资料或提交新资料，并确认本次游玩偏好。
- `GET /api/queue-mobile/sessions/<token>/result`：轮询终端最终处理结果。

网页资料列表允许按昵称或 QQ 搜索，但 `TERMINAL_ONLY` 资料只返回“已填写 QQ”和公开范围，不返回 QQ 原文。旧资料补全页会读取并保留现有通知开关，避免覆盖玩家此前通过 Bot 调整的偏好。新建资料或补全设置时必须提交全部资料设置；完整资料不能从移动网页直接编辑。会话固定创建它的终端、队列批次和机台，并在读取和提交时重新检查终端在线、网站同步、登记排队、机台状态和容量。

提交会生成 `MOBILE_DEVICE_REGISTRATION` 待执行命令并立即封闭会话，重复提交不会创建第二项命令。终端再次校验会话、资料版本、昵称、QQ、当前登记、队列批次、机台状态和容量，写入资料与登记后才返回 `APPLIED`。这条路径建立普通现场登记，不带线上登记的待签到状态；成功登记后才增加资料使用次数和最近使用时间。

### 命令回执

`POST /api/queue-terminal/commands/<command_id>/result`

```json
{"status":"APPLIED","detail":"玩家资料已由终端更新。"}
```

终端会把队列变动和命令首次处理结果写入同一份本机状态后再返回 `APPLIED`，随后正常上传新的资料快照。若独立回执存储暂时失败，重启后仍可从队列状态恢复首次结果并继续补写。服务器不能自行把待执行命令直接改成正式资料。

线上加入成功时，终端还会提交 `result_registration_id`。终端返回 `APPLIED` 即表示登记已经写入本机，网站会立即报告创建成功，并用该编号保存“标记为自己”；随后刷新公开快照以补充当前位置。即使登记在快照刷新前已经退出，或快照暂时上传失败，网站也不会一直停留在等待同步状态。

## 远程操作边界

网站当前只开放线上加入排队；已有登记的暂缓一次、暂时离开、切换机台、修改偏好和退出排队由 QQ Bot 提供。两端均不能远程标记未到场、结束本轮、调整其他玩家、拖动顺序、报告机台停止使用或修改终端设置。现场终端仍是队列的最终数据源。

## 构建与安全

需要预置连接时，终端构建默认值可以保存在开发账户的 `~/.gradle/gradle.properties`：

```properties
ENABLE_TERMINAL_BUILD=true
QUEUE_SYNC_URL=https://abcccc.top/api/queue-status
QUEUE_SYNC_TOKEN=<终端令牌>
```

这些值只作为 `terminal` 变体首次运行时的默认连接。管理员也可以先关闭网站同步，再在应用设置中填写 HTTPS 站点根地址或完整接口地址，以及至少 32 个 UTF-8 字节的终端令牌；保存时地址会规范化到 `/api/queue-status`。切换连接会清除上一服务器的同步错误、Bot QQ 和未完成移动登记会话，不会把令牌写入操作日志。

构建默认令牌只会写入显式开启的 `terminal` 产品变体。公开发行必须构建 `assembleLocalRelease`；`local` 变体在 Gradle 中强制使用空地址和空令牌，不能因开发机保存了生产配置而意外带出凭据。现场终端使用 `packageTerminalDebugApk` 或 `assembleTerminalRelease`，生成物不得公开上传。QQ 与玩家资料通过 HTTPS 传输并存入私有数据库；数据库备份、Bot 令牌和终端令牌都应限制读取权限。

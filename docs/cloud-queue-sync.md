# 队列与玩家资料云端同步协议

## 原则

- Android 终端是队列的最终数据源。服务端同一时间只保存一份队列快照，并且只把一台现场终端的一个运行实例视为权威来源。
- 现场修改先在本机生效，再异步上传；网络故障不能阻止排队。
- 网站和 QQ Bot 的排队操作、QQ Bot 的资料修改、使用移动设备登记，以及管理后台对队列、玩家资料和终端设置的修改，都以待执行命令返回终端，经本地规则校验和落盘后才算生效。服务端可以拒绝尚未完成的命令，但只有终端的回执能把命令标记为 `APPLIED`。
- 玩家资料库同样以终端为准，网页账户则由服务端保存。玩家在网页上修改资料、上传头像、绑定账户或通过二维码建档时，服务端直接写入玩家资料，不经过命令，终端通过同步云端资料取回。已绑定网页账户的资料，其 QQ、头像、`terminal_editing_allowed`、`visited_venues_public` 和 `web_profile_revision` 始终取服务端保存的值，见“网页账户字段”。
- 管理后台删除玩家资料的命令被终端应用后，服务端永久记录这份资料已删除，之后的快照不能把它重新建立。
- 公开队列数据与私有玩家资料分开保存和提供。公开快照和公开日志不包含玩家资料库、资料 UUID 和性别；QQ 只在玩家把公开范围设为 `PUBLIC_WEBSITE` 时出现在本人当前有效的登记上。带有全部 QQ 的玩家资料库只对持有终端令牌、Bot 令牌或管理令牌的服务开放；移动设备登记会话在有效期内可以列出资料，但 `TERMINAL_ONLY` 资料不返回 QQ 原文；其余接口只能凭 QQ、一次性会话令牌或网页账户会话读取单份资料。
- QQ 是玩家在 Bot 中的身份键，也是网页账户的登录名；当前版本不再保存手机号。Bot 和管理后台的资料修改命令不能更改 QQ，已绑定网页账户的资料只能由玩家在网页凭当前密码修改 QQ。版本 8 起每份资料另有六位玩家编号 `public_player_id`，服务端保存后不会被终端上传的值替换。
- 版本 8 起，终端的私有资料同步和远程操作绑定到服务端签发的机厅 ID。终端在核对服务器所属机厅之前不上传队列、不读取私有资料，也不处理远程命令，见“机厅与终端身份”。
- 现场可以添加、删除 1 至 10 台机台，展示编号始终按 A 至 J 连续排列；公开快照只包含实际配置的机台，并携带稳定机台身份与分组信息。
- 云端同步协议当前使用 schema 8，App 本地持久化格式仍使用 schema 7。两者是职责不同的数据格式，版本号各自演进，不能混用请求体。

## 协议版本

现场终端当前发布 `schema_version: 8`。服务端接受版本 `1` 至 `8` 的快照，写入和对外提供时统一标记为 `8`。

版本 8 的上传必须在请求头和请求体中声明同一版本，并携带已核对的机厅 ID：

- 请求体为 `8` 时，`X-Queue-Schema-Version` 必填且必须同为 `8`。请求头为 `8` 而请求体低于 `8` 同样被拒绝。
- 请求头和请求体都低于 `8` 时，请求头可以省略，服务端也不比较两者是否相等。
- 请求头只要出现，就必须是 `1` 至 `8` 的整数。无法解析的值不会被当作旧版终端放行。

版本 `1` 至 `7` 的上传走单机厅兼容路径：不核对机厅，快照归入服务端当前机厅，缺少的新字段按下文各版本的默认值补齐。

终端只在持有服务端签发的机厅 ID 时使用版本 8，否则使用版本 7，不会构造更低版本的请求体。服务端以 `400` 返回错误文本 `不支持的队列数据版本` 或 `不支持的队列协议版本` 时，终端降级到版本 7 并立即重发一次；判断依据是 `error` 文本，不是 `code`。这一降级用于兼容不支持版本 8 的旧服务端。版本 7 的请求不含 `venue`、`terminal.name` 和 `X-Queue-Venue-ID`，玩家资料也只上传版本 7 的字段。降级结果只保存在内存中，由快照上传和终端回流接口共用，服务器地址、令牌或是否持有机厅 ID 发生变化时重置。

版本 3 增加私有玩家资料、当前登记联系信息、QQ Bot 联动状态和营业时间计算结果。版本 4 继续增加：

- `website_remote_enabled`：现场终端是否开启与服务端同步及网站远程命令通道。终端在与服务端同步关闭时上传的 `onebot_sync_enabled` 也是 `false`。
- `queue_rules`：当前是否允许暂缓一次、暂时离开和创建线上登记，供网站、服务器、Bot 与终端共同校验和展示。服务端只保存 `allow_defer_one_round`、`allow_temporary_leave` 和 `allow_online_registration` 三个布尔值：对象存在时前两项必填，第三项缺省为 `true`；整个对象缺省时三项均为 `true`。是否显示共同游玩预览不在其中，见下文的 `show_common_play_preview`。
- 登记字段 `online_registration_pending_check_in`：该登记是否由网站或 Bot 建立且仍未在现场签到。

版本 5 增加玩家资料版本、使用记录、QQ 公开范围和通知设置，并为“使用移动设备登记”提供终端确认命令。旧协议上传的资料会使用保守默认值迁移，不会因此公开 QQ。

版本 6 增加：

- 顶层 `machine_configuration_revision`，独立标识会影响跨端确认的机台配置版本。
- 每台机台的 `remark` 和 `configuration`。配置包含游戏类型、自定义类型、服务器、自定义服务器、可见游戏版本、1/2 人游玩容量，以及单人和共同游玩的计划分钟数。`configuration` 必须恰好包含九个字段，字段名和取值规则与“终端设置”中的 `configuration` 相同；`remark` 为 `1` 至 `8` 个字符，必须与机台 `name` 中 `·机台 <编号>` 之前的备注相同。
- 容量为 1 的机台只能包含单人偏好登记，每个游玩位置或等待位置最多一份登记，不能包含固定组合或共同游玩预览。

服务端读取 schema 1 至 5 时按容量 2、单人 12 分钟、共同游玩 15 分钟补齐旧机台配置。旧快照不会因缺少版本 6 字段而被拒绝。

版本 7 增加：

- 每台机台的 `stable_id`，用于在删除中间机台并重新编号后继续识别同一台物理机台。
- 顶层 `machine_groups` 和 `default_machine_group_id`，以及每台机台的 `group_id`。每台机台必须属于一个有效分组，每个分组至少包含一台机台。
- 机台数量上限扩展为 10，编号必须从 A 开始连续至 J；旧版本快照统一迁移到默认单分组，并按原字母补充稳定身份。

版本 8 增加：

- 请求头 `X-Queue-Venue-ID` 和顶层 `venue`。`venue` 只能包含 `id` 一个键，值必须与请求头以及服务端当前机厅的 ID 一致。
- `terminal.name`：现场终端的显示名称，可省略，最多 `24` 个字符。服务端用它更新终端登记信息。
- 玩家资料字段 `public_player_id`、`avatar_url`、`web_account_bound`、`terminal_editing_allowed`、`visited_venues_public` 和 `web_profile_revision`。低于版本 8 的上传不读取这些字段，服务端使用默认值。
- 终端回流接口在请求头声明版本 8 时同样核对 `X-Queue-Venue-ID`。

机台、分组、事件和登记绑定的结构与版本 7 相同。终端从版本 8 起把机台名称中间隔号两侧带空格的写法改为不带空格的 `·`；服务端对版本 2 及以上的上传两种写法都接受，保存时统一去掉间隔号两侧的空格。

以下字段在版本 8 之后加入，没有提升协议版本号，任何版本的请求体都可以携带。其中两组管理字段是否生效由各自的声明字段决定：

- `management_policy_supported`（0.13.0 加入，缺省为 `false`）：终端声明可以执行管理后台下发的策略命令。为 `true` 时 `management_policy_revision` 必填，是不小于 `0` 的整数；`management_app_bound` 表示敏感策略是否已由管理后台接管，缺省为 `false`；`management_policy` 包含 `allow_online_registration`、`allow_defer_one_round`、`allow_temporary_leave` 和 `onebot_sync_enabled` 四个布尔值，缺少的键取 `queue_rules` 和顶层 `onebot_sync_enabled` 的对应值。为 `false` 时服务端保存 `management_app_bound: false`、`management_policy_revision: 0`，并按同样的来源生成 `management_policy`。
- `management_settings_supported`（0.13.2 加入，缺省为 `false`）：终端声明支持管理后台的设置类命令；未声明时管理接口不会为它创建这类命令。为 `true` 时以下四项必填：`management_settings_revision`（不小于 `0` 的整数）、`show_common_play_preview`（是否显示共同游玩预览）、`registration_control_open`（登记排队开关本身的状态）和 `business_hours_settings`（完整营业时间表，结构与机厅营业时间接口的 `business_hours` 相同）。为 `false` 时服务端保存 `management_settings_revision: 0`、`show_common_play_preview: true`、`business_hours_settings: null`，`registration_control_open` 取 `registration_open` 的值。
- 玩家资料字段 `recent_usage_at`：只要出现就会读取，每份资料最多 `16` 条。
- 同时在两台机台排队的六个字段：登记字段 `also_queues_at_paired_machine`（布尔值，缺省为 `false`）、登记字段 `skips_paired_machine_this_round`（布尔值，缺省为 `false`，终端只在为 `true` 时发送）、登记字段 `paired_arrangement_announced`（布尔值，缺省为 `false`，终端只在为 `true` 时发送）、登记字段 `paired_arrangement_not_before`（毫秒时间戳，缺省为 `null`，只与前一项同时出现）、机台字段 `paired_machine_id`（缺省为 `null`）和机台字段 `paired_waiting_positions`（缺省为 `null`）。六者都缺省时与没有这项功能的终端完全相同。含义和校验规则见“当前队列”。
- 等待位置字段 `estimated_wait_uncertain`（布尔值，缺省为 `false`）：`waiting_positions` 和 `paired_waiting_positions` 的每个位置都可以带有，表示这个位置的等待时间可能不准确。含义和校验规则见“当前队列”。
- 轮空一次与延后登记的限制（2026-11 加入）的三个登记字段：`skips_one_turn`（将轮空一次）、`defer_one_round_used`（暂缓一次已经使用、此后尚未游玩一轮）和 `online_registration_not_played`（已签到的线上登记尚未游玩一轮），都是布尔值，缺省为 `false`。`skips_one_turn` 为 `true` 时 `temporary_away_skipped_turns` 也可以不为 `0`。含义和校验规则见“当前队列”。

终端在版本 7 和版本 8 的请求体中都发送上述两组管理字段，`recent_usage_at` 只在版本 8 中发送。

`registration_open` 是折算后的结果：登记排队开关开启且不处于闭店收尾时才为 `true`。`registration_control_open` 只反映开关本身。

`business_hours` 只包含 `enabled`、`outside`、`closing_soon`、`closing_grace`、`closes_at` 和 `registration_closes_at` 六个计算结果，出现其他键会被拒绝。`closing_soon` 在营业时段进入闭店前 30 分钟后为 `true`，`closes_at` 是本次闭店时间；`closing_grace` 表示已到闭店时间但现有队列仍在收尾，`registration_closes_at` 是最迟收尾时间。终端在营业时段之外上传的 `closes_at` 为 `null`；闭店收尾最长持续到闭店后 `20` 分钟，队列清空后提前结束，`registration_closes_at` 只在 `closing_grace` 为 `true` 时有值。`enabled` 为 `false` 时，服务端把其余各项保存为 `false` 和 `null`；整个 `business_hours` 缺省时同样按未启用保存。`enabled` 为 `true` 时，服务端拒绝以下组合：`outside` 与 `closing_soon` 同时为 `true`；`closing_grace` 为 `true` 而 `outside` 为 `false`；`closing_grace` 与 `registration_closes_at` 是否有值不一致。

完整营业时间表不在 `business_hours` 中。它随 `business_hours_settings` 上传，并通过机厅营业时间接口保存在服务端。

私有玩家资料和登记联系信息通过快照的两个顶层字段上传：

- `private_player_profiles`：完整玩家资料库。
- `private_player_contacts`：当前登记以及最近公开事件中的登记与玩家资料、QQ 的关联。事件涉及的登记即使已离开队列，也可在本次快照中保留联系映射，以完成与该次操作有关的私信通知；该映射不会进入公开接口。

这两个字段只在版本 3 及以上读取。服务端在写入公开快照前移除这两个字段和 `recent_events`，另行保存，`GET /api/queue-status` 绝不能返回它们。

## 终端请求头

`POST /api/queue-status` 和 `/api/queue-terminal/` 下的接口使用同一组请求头：

```text
Authorization: Bearer <QUEUE_SYNC_TOKEN>
X-Device-ID: <终端 UUID>
X-Terminal-Instance-ID: <本次进程启动生成的 UUID>
X-Terminal-Instance-Generation: <本机单调递增的正整数>
X-Queue-Schema-Version: 8
X-Queue-Venue-ID: <服务端签发的机厅 UUID>
X-Queue-Sync-Mode: <test 或 takeover，仅在选择了同步方式时发送>
Content-Type: application/json; charset=utf-8
```

- `Authorization`：始终必填。服务端配置的 `QUEUE_SYNC_TOKEN` 不足 32 个 UTF-8 字节或与 `QUEUE_BOT_TOKEN` 相同时，全部终端接口返回 `503`；令牌不符返回 `401`。
- `X-Device-ID`：终端的稳定编号。Android 终端首次运行时生成 UUID 并长期保存；服务端只要求去除首尾空白后为 `1` 至 `128` 个字符，快照上传和安装信息接口在不满足时返回 `400`。服务端配置了 `QUEUE_DEVICE_ID` 时，其他编号在全部终端接口上返回 `403`。
- `X-Terminal-Instance-ID` 与 `X-Terminal-Instance-Generation`：标识一次应用运行，必须成对发送。前者是每次进程启动生成的 UUID；后者由终端持久化，每次进程启动加 `1`，是不超过 64 位有符号整数上限的正整数。两者都不发送时，服务端把 `X-Device-ID` 当作实例编号、代次记为 `0`，用于兼容 0.6.5 之前的终端；只发送其中一个返回 `400`。读取这两个请求头的接口是快照上传、拉取命令、命令回执、`POST /api/queue-terminal/player-bindings` 和 `POST /api/queue-terminal/player-profile-sessions`。
- `X-Queue-Schema-Version`：本次请求使用的协议版本。快照上传的规则见“协议版本”。除安装信息接口外，`/api/queue-terminal/` 下的接口都要求这个请求头：缺失或不是整数返回 `400` `队列协议版本无效。`，不在 `1` 至 `8` 之间返回 `400` `不支持的队列协议版本`。
- `X-Queue-Venue-ID`：终端通过安装信息接口核对过的机厅 ID。`X-Queue-Schema-Version` 为 `8` 时必填：缺失或不是 UUID 返回 `409` `VENUE_ID_REQUIRED`，与服务端当前机厅不同返回 `409` `VENUE_MISMATCH`。版本请求头为 `1` 至 `7` 时服务端不核对机厅，也不读取这个请求头；终端在版本 7 的请求中不发送它。请求头应原样使用服务端返回的 `venue.id`（小写、带连字符）：快照上传会把请求体的 `venue.id` 规范化后与请求头原文比较，请求头使用其他等价写法会被判为不一致。
- `X-Queue-Sync-Mode`：只在快照上传时读取，取值为 `test` 或 `takeover`。服务端会去除首尾空白并转为小写，其他非空值返回 `400` `同步方式无效。`。含义见“权威终端与同步方式”。
- `Content-Type`：带请求体的请求必须是 JSON。服务端无法把请求体解析为 JSON 对象时返回 `400`。

安装信息接口只校验令牌和 `X-Device-ID`，不读取版本、机厅和运行实例请求头，终端因此可以在取得机厅 ID 之前调用它。

## 上传队列快照

### 上传快照

`POST /api/queue-status`

终端把完整状态作为一个 JSON 对象上传：公开队列、最近公开事件、私有玩家资料库和登记绑定。状态每次变化后上传一次；没有变化时每 `30` 秒重发最新快照，作为心跳。上传失败后从 `2` 秒开始重试，间隔逐次加倍，最长 `60` 秒；单次请求的连接和读取超时均为 `8` 秒。

以下片段只列出与版本和身份有关的顶层字段，其余字段见“协议版本”：

```json
{
  "schema_version": 8,
  "queue_id": "11111111-2222-4333-8444-555555555555",
  "revision": 42,
  "machine_configuration_revision": 3,
  "captured_at": 1790000000000,
  "terminal": { "name": "前台终端", "app_version": "0.13.6" },
  "venue": { "id": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee" }
}
```

- `schema_version`、`queue_id`、`revision`、`captured_at` 和 `registration_open` 在所有版本中必填。`queue_id` 是 UUID；`revision` 是正整数，不超过 64 位有符号整数的上限；`captured_at` 是终端生成快照时的毫秒时间戳。
- `machine_configuration_revision` 从版本 6 起必填，更早的版本由服务端记为 `1`。
- `website_remote_enabled` 缺省为 `false`，`onebot_sync_enabled` 缺省为 `true`，出现时必须是布尔值。
- `terminal` 在所有版本中都必须是对象。服务端只读取 `app_version`（可省略，最多 `32` 个字符）和版本 8 的 `name`；终端同时发送的 `id`、`online` 和 `last_seen_at` 不会被采用。
- `machines` 的键必须是从 `A` 开始连续的 1 至 10 个字母。`recent_events` 缺省为空数组。
- 顶层出现的其他键会被忽略。

完整请求体限制为 `1 MiB`，超过时返回 `413`。单次最多包含 `10` 台机台、每台机台 `20` 份登记（其中游玩位置最多 `2` 份）、`200` 条公开事件（每条最多关联 `200` 个登记编号）、`500` 份玩家资料和 `600` 条登记绑定。服务端为每个队列批次最多保留最新的 `2000` 条事件。

服务端按以下顺序处理，任何一步拒绝都不会写入数据：

1. 校验终端令牌和 `QUEUE_DEVICE_ID`。
2. 校验 `X-Queue-Schema-Version` 的写法和范围。
3. 校验 `X-Device-ID`、运行实例请求头和 `X-Queue-Sync-Mode`。
4. 读取 JSON 请求体，校验 `schema_version` 以及它与请求头是否一致。
5. 版本 8：核对 `X-Queue-Venue-ID` 和请求体的 `venue.id`。这一步先于其余字段的校验，机厅不一致的终端得到的是身份错误，而不是普通的字段错误。
6. 校验并规范化完整快照。
7. 在同一个数据库事务内再次核对机厅、登记终端、判定权威终端和同步方式、检查队列批次与修订号，然后写入。

成功时返回 `204`，没有响应体。`OPTIONS /api/queue-status` 不需要鉴权，同样返回 `204`。

服务端只保存一份当前快照，保存前会改写以下内容：

- `schema_version` 固定为 `8`。
- `test_data` 由服务端按本次上传的同步方式写入，请求体中的同名字段不会被采用。
- `venue` 替换为服务端的机厅信息，包含 `id`、`code`、`name` 和恒为 `true` 的 `registered`；旧版本的上传也会补上。
- `terminal` 替换为 `id`、`name`、`online`、`app_version` 和 `last_seen_at`：`id` 取 `X-Device-ID`，`last_seen_at` 取 `captured_at`。
- 每台机台补充 `id`、`registration_count` 和 `waiting_position_count`。
- 每份登记补充 `qq_number`：只有对应玩家资料的 `qq_visibility` 为 `PUBLIC_WEBSITE` 时才是 QQ 原文，否则为 `null`。

与快照一起保存的还有 `X-Device-ID`、运行实例编号和代次、机厅 ID，以及按服务端时钟记录的接收时间。终端是否在线只看接收时间：距最近一次被接受的上传不超过 `QUEUE_ONLINE_TIMEOUT_SECONDS`（默认 `90` 秒）即为在线。

每次被接受的上传还会产生以下效果：

- 更新该终端的登记信息：最近出现时间，以及版本 8 请求体中的 `terminal.name`。
- 重复上传同一 `revision` 会被接受，覆盖已保存的快照并刷新接收时间，心跳依靠这一点。
- 事件按 `event_id` 去重。重复事件不会新建记录，只回填此前为空的 `machine_stable_id` 和 `machine_name`。
- `website_remote_enabled` 为 `false` 时，服务端拒绝该终端尚未被领取的以下待执行命令：`MOBILE_DEVICE_REGISTRATION`、`MANAGEMENT_QUEUE_ACTION`、`UPDATE_TERMINAL_POLICY`、`SET_REGISTRATION_AVAILABILITY`、`UPDATE_TERMINAL_SETTINGS`、`UPDATE_MACHINE_STATUS`、`DELETE_PLAYER_PROFILE`，以及 `operation_source` 为 `WEBSITE_REMOTE` 的命令；同时使该终端仍开放的移动设备登记会话失效。
- `onebot_sync_enabled` 为 `false` 时，服务端删除当前队列批次的通知收件关系，并拒绝该终端尚未被领取的 `UPDATE_PLAYER_PROFILE` 命令和 `operation_source` 为 `QQ_BOT` 的命令。

队列批次和修订号按以下规则处理：

- 同一队列批次内，`revision` 低于服务端已保存的值时返回 `409` `服务端已有更新的队列数据，本次同步没有执行。`。另一台终端接管后继续使用同一 `queue_id` 时同样比较。
- 同一终端在同一数据范围（同为正式数据或同为测试数据）内改用新的 `queue_id` 时，上一批次被标记为已结束。之后再上传已结束的批次返回 `409` `此队列批次已经结束`。
- 队列批次在同一数据范围内发生变化时，无论由哪台终端引起，上一批次的登记绑定都会删除。

关闭与服务端同步本身也通过上传生效。管理员关闭同步或更换服务器后，终端会继续向此前已核对的服务器上传 `website_remote_enabled` 和 `onebot_sync_enabled` 均为 `false` 的快照，使用关闭时记录的地址、令牌、机厅 ID、终端名称和协议版本，直到服务端确认；应用重启后仍会继续补发。

### 最近事件

`recent_events` 是当前队列批次最近的公开事件。终端只上传属于当前 `queue_id`、带有公开事件类型的操作日志，按时间取最新的 `200` 条；玩家资料编辑日志没有公开事件类型，不会上传。

```json
{
  "event_id": "99999999-8888-4777-8666-555555555555",
  "occurred_at": 1790000000000,
  "type": "REGISTRATION_ADDED",
  "title": "示例事件",
  "detail": "“示例玩家”已加入机台 A 的等待顺序。",
  "machine_id": "A",
  "machine_stable_id": "0123456789abcdef0123456789abcdef",
  "machine_name": "左侧·机台 A",
  "registration_ids": ["a10f5015a2e85b12e20af507"],
  "operation_source": "ON_SITE_TERMINAL",
  "notification_categories": ["QUEUE_CHANGES"]
}
```

- `event_id`：UUID，同一次上传中不能重复。
- `occurred_at`：事件发生时间，正整数，终端使用毫秒时间戳。
- `type`：公开事件类型，取值见“公开日志”。
- `title` 为 `1` 至 `120` 个字符，`detail` 为 `1` 至 `2000` 个字符。
- `machine_id`：`null` 或 `A` 至 `J`。`machine_stable_id` 为 `null` 或 32 位小写十六进制字符串；`machine_name` 可以省略，最多 `120` 个字符。`machine_id` 为 `null` 的系统事件不能携带 `machine_stable_id` 和 `machine_name`。
- `registration_ids`：必填数组，最多 `200` 项，每项是 24 位小写十六进制的登记编号，不能重复。
- `operation_source`：取值见“公开日志”，缺省为 `ON_SITE_TERMINAL`。
- `notification_categories`：可以省略。出现时包含 `1` 至 `5` 个互不相同的类别，取值见“公开日志”；省略时服务端按 `type` 推导一个类别。

任何一条事件不符合要求，整份快照都被拒绝，错误格式见“错误响应”。

### 错误响应

错误响应是 JSON 对象，`ok` 为 `false`，`error` 是中文说明。部分错误带有 `code`；最近事件的校验错误还带有 `field` 和 `details`。

```json
{
  "ok": false,
  "code": "VENUE_MISMATCH",
  "error": "提交的机厅与当前服务端不一致，本次同步没有执行。"
}
```

终端对快照上传和终端回流接口只读取 `error`，把它显示为同步失败说明后重试（快照上传按退避间隔；回流接口没有退避，拉取资料和命令由约每 `3` 秒一轮的循环重试），不根据 `code` 分支。终端唯一识别的 `code` 是安装信息接口和机厅营业时间接口返回的 `VENUE_MISMATCH`。因此下列错误文本本身就是现场可见的内容。

带 `code` 的错误：

- `SCHEMA_VERSION_INVALID`（`400`）：版本请求头不是整数。文本为 `请求头中的队列协议版本无效。`
- `SCHEMA_VERSION_UNSUPPORTED`（`400`）：版本请求头不在 `1` 至 `8` 之间。文本为 `请求头中的队列协议版本暂不支持。`
- `SCHEMA_VERSION_REQUIRED`（`400`）：请求体为版本 8 但没有版本请求头。文本为 `新版终端同步必须明确声明队列协议版本。`
- `SCHEMA_VERSION_MISMATCH`（`400`）：请求头或请求体有一方为 `8`，而两者不同。文本为 `请求头与请求内容中的队列协议版本不一致。`
- `VENUE_ID_REQUIRED`（`409`）：版本 8 的请求缺少机厅 ID，或其值不是 UUID。请求头缺失时文本为 `终端尚未提供已核对的机厅 ID，私有资料和远程操作已暂停。`；请求体 `venue.id` 缺失时文本为 `新版终端同步必须包含已核对的机厅 ID，本次同步没有执行。`
- `VENUE_MISMATCH`（`409`）：机厅不一致。请求头与服务端当前机厅不同时文本为 `终端绑定的机厅与当前服务器不一致。为防止读取其他机厅的数据或执行其他机厅的操作，私有资料和远程操作已暂停，请在设置中核对服务器与机厅。`；请求体与请求头不同时为 `提交的机厅与当前服务器不一致，本次同步没有执行。`；事务内复核发现请求体与当前机厅不同时为 `终端绑定的机厅与当前服务器不一致。为防止覆盖其他机厅的队列，本次同步没有执行，请在设置中核对服务器与机厅。`；终端已登记到另一机厅时为 `此终端已经绑定到另一机厅，不能自动更改归属。`
- `STALE_TERMINAL_INSTANCE`、`TERMINAL_INSTANCE_CONFLICT`、`TERMINAL_STILL_ONLINE` 和 `SYNC_MODE_REQUIRED`（`409`）：见“权威终端与同步方式”。

不带 `code` 的错误：

- `503` `服务器认证配置无效。`，`401` `终端认证失败，请核对终端同步令牌。`，`403` `此终端未获准同步。`。
- `400` `终端编号无效。`、`终端运行实例信息不完整。`、`终端运行实例编号无效。`、`终端运行实例代次无效。`、`同步方式无效。`、`请求内容必须是 JSON 对象。`。
- `413` `队列数据超过大小限制。`。
- `409` `此队列批次已经结束`、`服务端已有更新的队列数据，本次同步没有执行。`。
- 快照字段校验失败返回 `400`，除最近事件外只有 `ok` 和 `error`。

最近事件（`recent_events`）的校验错误是结构化的。`code` 使用小写下划线写法，例如 `too_many_recent_events`、`duplicate_recent_event_id`、`invalid_recent_event_title`；`field` 是出错位置的路径，下标从 `0` 开始；`details` 包含 `event_index`（从 `0` 开始）和 `event_number`（从 `1` 开始），能够读取时还包含 `event_title` 和 `event_id`。针对整个列表的错误只有 `code` 和值为 `recent_events` 的 `field`。`error` 是多行文本，依次为出错事件及原因、`字段：<路径>`，以及可选的 `事件编号：<编号>`。`code` 共有 20 个取值：针对整个列表的 `invalid_recent_events` 和 `too_many_recent_events`；针对单条事件的 `invalid_recent_event`、`invalid_recent_event_id`、`duplicate_recent_event_id`、`invalid_recent_event_occurred_at`、`invalid_recent_event_type`、`invalid_recent_event_title`、`invalid_recent_event_detail`、`invalid_recent_event_machine_id`、`invalid_recent_event_machine_stable_id`、`invalid_recent_event_machine_name`、`system_event_has_machine_identity`、`invalid_recent_event_registration_ids`、`invalid_recent_event_registration_id`、`duplicate_recent_event_registration_id`、`invalid_recent_event_operation_source`、`invalid_recent_event_notification_categories`、`invalid_recent_event_notification_category` 和 `duplicate_recent_event_notification_category`。

```json
{
  "ok": false,
  "code": "duplicate_recent_event_id",
  "field": "recent_events[4].event_id",
  "details": {
    "event_index": 4,
    "event_number": 5,
    "event_title": "示例事件",
    "event_id": "99999999-8888-4777-8666-555555555555"
  },
  "error": "最近事件第 5 条《示例事件》的事件编号重复：与最近事件第 2 条使用了同一编号。\n字段：recent_events[4].event_id\n事件编号：99999999-8888-4777-8666-555555555555"
}
```

## 机厅与终端身份

服务端为每个 `QUEUE_PROFILE_SCOPE_ID` 保存一个机厅。启动时如果当前作用域还没有机厅，服务端会自动创建：

- `id`：机厅 ID，随机生成的 UUID，用于 `X-Queue-Venue-ID` 和快照的 `venue.id`。
- `code`：机厅编号，`8` 个字符，取自 `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`（不含 `0`、`1`、`I`、`O`），不会重复。现场终端的设置页和应用详情页在“机厅 ID”一栏显示的是这个编号。
- `name`：机厅显示名称，创建时为 `null`，由终端通过安装信息接口填写。

`id` 和 `code` 只由服务端生成，创建后不再变化，终端不能指定或修改。把 `QUEUE_PROFILE_SCOPE_ID` 改成新值相当于换了一个机厅：服务端会为新作用域创建新的 `id` 和 `code`。

服务端同时登记终端。调用安装信息接口或成功上传快照的每个 `X-Device-ID` 都会记入当前机厅，保存显示名称和最近出现时间，角色固定为 `AUTHORITATIVE`。终端登记不会自动改换机厅：已登记到另一机厅的终端，其快照上传（任何协议版本）和安装信息请求都返回 `409` `VENUE_MISMATCH`，文本为 `此终端已经绑定到另一机厅，不能自动更改归属。`。在已有数据库上修改 `QUEUE_PROFILE_SCOPE_ID` 会让原有终端落入这种状态。

### 核对安装信息

`GET /api/queue-terminal/installation`

返回服务端的机厅身份和本终端的登记信息。终端在向新填写或重新启用的服务器上传任何数据之前先调用它，连接期间每 `30` 秒刷新一次。

```json
{
  "ok": true,
  "venue": {
    "id": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
    "code": "7KQ2M9XA",
    "name": "示例机厅",
    "registered": true
  },
  "terminal": {
    "id": "11111111-2222-4333-8444-555555555555",
    "name": "前台终端",
    "role": "AUTHORITATIVE",
    "last_seen_at": 1790000000000
  },
  "capabilities": {
    "schema_version": 8,
    "multiple_venues": false,
    "multiple_terminals": false
  }
}
```

`venue.name` 和 `terminal.name` 在尚未填写时为 `null`。`terminal.last_seen_at` 是毫秒时间戳。`capabilities.schema_version` 是服务端当前的协议版本，`multiple_venues` 和 `multiple_terminals` 目前恒为 `false`。终端只使用 `venue.id`、`venue.code`、`venue.name` 和 `terminal.name`。

这个请求不是只读的：终端尚未登记时会被登记，已登记时会刷新最近出现时间，显示名称保持不变。

`X-Device-ID` 无效时返回 `400` `终端编号无效。`，终端已登记到另一机厅时返回 `409` `VENUE_MISMATCH`。0.11.0 之前的服务端没有这个接口，终端把 `404` 或 `405` 视为服务端不支持机厅身份。

### 登记机厅与终端名称

`POST /api/queue-terminal/installation`

```json
{
  "venue_name": "示例机厅",
  "terminal_name": "前台终端",
  "venue_id": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
}
```

三个键都可以省略，空对象也有效；出现其他键返回 `400`，`venue_id` 是无法解析为 UUID 的字符串时返回 `400` `机厅 ID 无效。`。`venue_name` 去除首尾空白后为 `1` 至 `40` 个字符，`terminal_name` 为 `1` 至 `24` 个字符。`venue_id` 是终端已经持有的机厅 ID，只用于核对：服务端在写入任何内容之前比较，与当前机厅不同时返回 `409` `VENUE_MISMATCH`，文本为 `当前服务器属于另一机厅，请核对服务器地址后再继续。`。机厅 ID 不能通过这个接口指定。

响应与 `GET` 相同。`venue_name` 会覆盖整个机厅的显示名称；这个接口不检查运行实例，也不要求调用方是当前权威终端。`terminal_name` 省略时保留已登记的名称。重复提交相同内容结果不变，改名不影响机厅的 `id` 和 `code`。

终端在首次设置中注册机厅、尚未持有服务端签发的机厅 ID 而修改连接设置或重新开启与服务端同步、确认重新绑定当前服务器，或在本机修改了机厅或终端名称而尚未得到服务端确认时使用 `POST`，同时提交两个名称；只有已持有服务端签发的机厅 ID 时才附带 `venue_id`。其余情况使用 `GET`。

### 读取机厅营业时间

`GET /api/queue-terminal/venue-settings`

返回服务端保存的机厅营业时间表。营业时间表属于机厅而不是某一台终端，独立于队列快照保存。

响应包含 `ok`、`venue`（与安装信息接口相同）和 `business_hours`。从未保存过，或已保存的内容无法通过校验时，`business_hours` 为 `null`；否则是规范化后的完整时间表，`weekly_hours` 始终包含一周七天。

这个接口要求 `X-Queue-Schema-Version`，声明版本 `8` 时核对 `X-Queue-Venue-ID`。除配置了 `QUEUE_DEVICE_ID` 时的终端编号限制外，它不读取 `X-Device-ID` 和运行实例请求头，也不要求调用方是当前权威终端。

### 保存机厅营业时间

`PUT /api/queue-terminal/venue-settings`

```json
{
  "business_hours": {
    "enabled": true,
    "use_weekly_schedule": true,
    "default_hours": { "opening_minutes": 600, "closing_minutes": 1320 },
    "weekly_hours": {
      "SATURDAY": { "opening_minutes": 540, "closing_minutes": 1380 }
    }
  }
}
```

请求体只能包含 `business_hours` 一个键，否则返回 `400` `请求内容必须只包含 business_hours。`。其中：

- `enabled` 和 `use_weekly_schedule` 是布尔值，缺省为 `false`。
- `default_hours` 包含 `opening_minutes` 和 `closing_minutes`，是从零点起算的分钟数，取值 `0` 至 `1439`，缺省分别为 `600` 和 `1320`。
- `weekly_hours` 以 `MONDAY` 至 `SUNDAY` 为键，每项结构与 `default_hours` 相同。缺少的星期取 `default_hours`，出现其他键会被拒绝。

服务端整体替换已保存的时间表，没有修订号或前置条件，后写入的覆盖先写入的。响应与 `GET` 相同，返回规范化后的时间表。Android 终端总是提交全部四个键和一周七天。

服务端不会把这份时间表写进队列快照；快照中的 `business_hours_settings` 始终来自终端上传。

终端持有机厅 ID、线上访问已放行且同步方式不是测试同步时，每 `30` 秒读取一次。服务端返回 `null` 或本机有尚未上传的修改时，终端用 `PUT` 提交本机时间表；服务端的时间表与本机不同时，本机改用服务端的版本。收到 `VENUE_MISMATCH` 时终端进入机厅不一致状态；收到 `404` 或 `405` 时 `5` 分钟后再试；其他失败从 `2` 秒开始重试，间隔逐次加倍，最长 `60` 秒。

### 终端核对流程

首次设置完成、修改服务器地址或同步令牌、重新开启与服务端同步，或从尚无机厅身份的旧版本升级之后，终端先核对服务器身份。核对完成之前不上传快照，也不调用终端回流接口；现场排队不受影响。

- 安装信息接口返回 `200`：保存机厅 ID、机厅编号和名称，放行线上访问，此后每 `30` 秒刷新。
- 返回 `404` 或 `405`：服务端不支持机厅身份，终端进入兼容模式，每 `5` 分钟重新探测。此前持有的机厅 ID 只有在由同一服务器地址签发时才保留；不再持有机厅 ID 的终端使用版本 7，仍持有的终端继续先按版本 8 请求，被服务端以版本不支持拒绝后才降级，见“协议版本”。
- 返回 `VENUE_MISMATCH`，或返回的 `venue.id` 与终端已持有的机厅 ID 不同：终端进入机厅不一致状态，停止全部线上访问，直到连接设置改变，或管理员在设置页确认“重新绑定当前服务器”。重新绑定要求先关闭登记排队；确认后终端放弃原机厅 ID，以不带 `venue_id` 的 `POST` 向当前服务器重新登记。
- 其他失败：从 `2` 秒开始重试，间隔逐次加倍，最长 `60` 秒。

## 权威终端与同步方式

服务端保存当前快照时，同时记录写入它的 `X-Device-ID`、运行实例编号和代次。之后的每次上传都与这份记录比较，决定接受、拒绝，还是要求明确选择同步方式。下文的“在线”指距最近一次被接受的上传不超过 `QUEUE_ONLINE_TIMEOUT_SECONDS`（默认 `90` 秒），按服务端时钟计算。

### 同一终端的运行实例

上传的 `X-Device-ID` 与当前快照相同时：

- 代次低于已记录的代次：返回 `409` `STALE_TERMINAL_INSTANCE`。
- 实例编号不同、代次相同，且当前快照仍在线：返回 `409` `TERMINAL_INSTANCE_CONFLICT`。
- 其余情况接受上传，上传方成为权威实例。代次更高的实例总能取代旧实例；代次相同而实例编号不同的实例，要等当前快照离线后才能取代。

两种拒绝的文本都是 `另一个终端应用正在同步，请关闭重复打开的应用后重试。`

新的运行实例被接受后，其他实例对该终端待执行命令的领取记录会被清除，新实例可以重新领取。如果队列上下文同时发生了变化，该终端的部分待执行命令会被拒绝，判断方式和命令类型与下文“接管与待执行命令”相同。

拉取命令、命令回执、`POST /api/queue-terminal/player-bindings` 和 `POST /api/queue-terminal/player-profile-sessions` 要求请求的 `X-Device-ID`、实例编号和代次与当前快照的记录完全一致，否则返回 `409` `STALE_TERMINAL_INSTANCE`；只有 `POST /api/queue-terminal/player-profile-sessions` 在 `X-Device-ID` 与当前快照不同时返回的是不带 `code` 的 `409` `当前终端不是正在同步的终端。`，还没有快照或终端离线时分别返回 `404` 和 `503`，见“创建建档会话”。安装信息、机厅营业时间、同步云端资料和创建移动设备登记会话不做这项检查。

不发送实例请求头的旧版终端按代次 `0` 处理。同一终端一旦由发送实例请求头的版本上传过快照，不带实例请求头的请求就会被判为过期实例。

### 其他终端上传

上传的 `X-Device-ID` 与当前快照不同时，服务端依次判断：

1. 服务端还没有快照：接受。
2. 上传方是 `QUEUE_PRIMARY_DEVICE_ID` 指定的主终端：立即接受，不要求同步方式，即使另一终端仍在线。
3. 当前终端仍在线：返回 `409` `TERMINAL_STILL_ONLINE`，文本为 `另一终端仍在线，暂不能开始测试同步或接管同步`。是否携带 `X-Queue-Sync-Mode` 都一样。
4. 当前终端已离线，请求没有 `X-Queue-Sync-Mode`：返回 `409` `SYNC_MODE_REQUIRED`。
5. 当前终端已离线，请求带有 `X-Queue-Sync-Mode`：接受，按所选方式保存为测试数据或正式数据。

```json
{
  "ok": false,
  "code": "SYNC_MODE_REQUIRED",
  "error": "请选择测试同步或接管同步后再继续",
  "current_registration_count": 6,
  "local_registration_count": 0,
  "current_terminal_online": false
}
```

`current_registration_count` 是服务端当前快照中的登记总数，`local_registration_count` 是被拒绝的这次上传中的登记总数，`current_terminal_online` 在这条路径上恒为 `false`。终端目前只显示 `error` 文本，不读取这三个附加字段。

`QUEUE_PRIMARY_DEVICE_ID` 和 `QUEUE_DEVICE_ID` 在部署示例中默认留空，即没有主终端，也不限制终端编号。配置了 `QUEUE_DEVICE_ID` 时，只有该编号能通过终端鉴权，其他终端也就无法进行测试同步或接管同步。

### 同步方式

`X-Queue-Sync-Mode` 决定被接受的上传以什么身份保存：

- `test`（测试同步）：保存为测试数据，快照带有 `test_data: true`。
- `takeover`（接管同步）：保存为正式数据，上传方成为权威终端。
- 不发送：同一终端沿用当前快照的身份，原来是测试数据就仍是测试数据；服务端的第一份快照和主终端的上传为正式数据。

同一终端也可以用这个请求头在测试数据和正式数据之间切换。

终端把同步方式保存为设置，共三个取值：“沿用当前身份”不发送请求头，“测试同步”和“接管同步”在每次上传时发送对应取值。选择接管同步需要在终端上二次确认，后台重试不会自行改为接管。

### 测试数据

测试数据与机厅的正式数据分开保存：事件、登记绑定和批次结束标记记在 `test:<X-Device-ID>:<queue_id>` 之下，玩家资料记在 `<QUEUE_PROFILE_SCOPE_ID>:test:<X-Device-ID>` 之下，不会并入机厅的玩家资料库。

当前快照是测试数据时：

- 公开快照带有 `test_data: true`。
- 不能绑定网页账户，也不能创建网页玩家资料，对应接口分别返回 `409` `TEST_DATA_ACCOUNT_BINDING_UNAVAILABLE` 和 `409` `TEST_DATA_PROFILE_CREATION_UNAVAILABLE`。
- 另一台终端的测试数据生效期间，正式数据的待执行命令仍归原来的正式终端。
- 处于测试同步的终端不读取也不保存机厅营业时间。

测试数据与正式数据之间每发生一次切换，上一份快照所属终端仍开放的移动设备登记会话都会失效。同一终端自行切换时，它的待执行命令还会被拒绝，`result_detail` 为 `测试同步已经结束，这次操作没有执行，请重新提交。`：从测试数据回到正式数据时拒绝全部，进入测试数据时只拒绝尚未被领取的。

正式数据由另一台终端恢复时，测试终端的全部待执行命令以同样的说明被拒绝。恢复的方式取决于是否配置了主终端：

- 配置了 `QUEUE_PRIMARY_DEVICE_ID`：主终端再次上传即恢复正式数据，即使测试终端仍在运行。
- 没有配置：原来的正式终端与其他终端没有区别，测试终端在线期间得到 `TERMINAL_STILL_ONLINE`，测试终端离线后需要以 `takeover` 上传。

### 接管与待执行命令

另一台终端以正式数据取代当前的正式终端时（`takeover`，或主终端上传），服务端先比较队列上下文：`queue_id` 是否相同，各机台字母对应的 `stable_id` 是否相同。

- 上下文发生变化：拒绝全部待执行的 `QUEUE_OPERATION`、`MANAGEMENT_QUEUE_ACTION`、`MOBILE_DEVICE_REGISTRATION`、`UPDATE_TERMINAL_POLICY`、`SET_REGISTRATION_AVAILABILITY`、`UPDATE_TERMINAL_SETTINGS`、`UPDATE_MACHINE_STATUS` 和 `DELETE_PLAYER_PROFILE` 命令，`result_detail` 为 `现场终端已经接管，原队列或机台配置发生变化，这次操作没有执行，请重新查询后再提交。`
- 其余待执行命令（包括上下文变化时的 `UPDATE_PLAYER_PROFILE`）转交给新终端，并清除原有的领取记录。

## 玩家资料字段

现场终端在快照的 `private_player_profiles` 中上传完整玩家资料库。下面是 schema 8 快照中的一份资料：

```json
{
  "profile_id": "37e41698-46f8-489b-92dc-d29c71f00f7d",
  "public_player_id": "123456",
  "avatar_url": null,
  "nickname": "示例玩家",
  "gender": "UNDISCLOSED",
  "default_preference": "OPEN_TO_JOIN",
  "qq_number": "12345678",
  "usage_count": 12,
  "last_used_at": 1784681000000,
  "recent_usage_at": [1784508200000, 1784594600000, 1784681000000],
  "qq_visibility": "TERMINAL_ONLY",
  "notification_enabled": true,
  "notify_queue_changes": true,
  "notify_playing_position": false,
  "notify_online_check_in": true,
  "notify_absence": true,
  "notify_machine_status": false,
  "web_account_bound": false,
  "terminal_editing_allowed": true,
  "visited_venues_public": true,
  "web_profile_revision": 0,
  "setup_version": 1,
  "profile_revision": 7,
  "created_at": 1780000000000,
  "updated_at": 1784681000000
}
```

各组字段的含义：

- `profile_id`：资料 UUID。合并、登记绑定和终端命令都用它识别资料。
- `public_player_id`：六位玩家编号，是保留前导零的六位数字字符串，可以为 `null`。编号以服务端保存的为准，见下文“上传与合并”。
- `nickname`、`gender`、`default_preference`：昵称去除首尾空白后为 `1` 至 `18` 个字符；`gender` 为 `MALE`、`FEMALE` 或 `UNDISCLOSED`；`default_preference` 为 `SOLO`、`OPEN_TO_JOIN` 或 `ASK_EVERY_TIME`。
- `qq_number`、`qq_visibility`：`qq_number` 对尚未补充资料的旧记录可以为 `null`；在现场终端创建和编辑资料时必须填写 `5` 至 `12` 位 QQ。`qq_visibility` 为 `TERMINAL_ONLY` 或 `PUBLIC_WEBSITE`，控制当前有效登记是否向公开网站提供 QQ，以及移动设备登记页面的资料列表是否返回 QQ，不影响完整 QQ 在私有接口中的保存和 Bot 身份识别。
- `usage_count`、`last_used_at`、`recent_usage_at`：使用记录。用这份资料建立登记时，终端把 `usage_count` 加 `1` 并更新 `last_used_at`。`recent_usage_at` 是最近几次使用的毫秒时间戳，按升序排列，最多 `16` 条，其中包含 `last_used_at`。从未使用过的资料 `last_used_at` 为 `null`。
- `notification_enabled`、`notify_queue_changes`、`notify_playing_position`、`notify_online_check_in`、`notify_absence`、`notify_machine_status`：六个通知字段，依次表示总开关、队列状态、游玩位置、线上登记与签到、暂缓一次/暂时离开/未到场、机台及营业状态。总开关关闭时所有分项均不投递；分项值仍保留，以便重新开启总开关后恢复原选择。
- `avatar_url`、`web_account_bound`、`terminal_editing_allowed`、`visited_venues_public`、`web_profile_revision`：头像和网页账户状态，取值由服务端决定，终端保存取回的值并在快照中回显，见“网页账户字段”。
- `setup_version`、`profile_revision`：`setup_version` 用于判断旧资料是否已经确认本版必填设置，当前版本为 `1`。`profile_revision` 是不小于 `1` 的整数，每次资料变更递增，用于拒绝过期修改；记录一次使用也会让它递增。
- `created_at`、`updated_at`：终端记录的创建时间和最近修改时间，毫秒时间戳。

字段随协议版本加入，服务端按快照的 `schema_version` 决定读取哪些：

- schema 3 和 4：只读取 `profile_id`、`nickname`、`gender`、`default_preference`、`qq_number`、`usage_count`、`last_used_at`、`created_at` 和 `updated_at`。其余字段使用保守默认值：`qq_visibility` 为 `TERMINAL_ONLY`，六个通知字段依次为 `true`、`true`、`false`、`true`、`true`、`false`，`setup_version` 为 `0`，`profile_revision` 为 `1`。
- schema 5 起：`qq_visibility`、六个通知字段、`setup_version` 和 `profile_revision` 必填。
- schema 8 起：读取 `public_player_id`、`avatar_url`、`web_account_bound`、`terminal_editing_allowed`、`visited_venues_public` 和 `web_profile_revision`，这些字段都可以省略。更低版本的快照即使携带它们也不会被读取。
- `recent_usage_at` 不受版本限制，出现即读取；终端只在 schema 8 快照中发送。

服务端返回资料时另有两个只读字段，不能上传：`public_player_id_aliases` 是这份资料的历史六位玩家编号数组，`synced_at` 是服务端最近一次在快照中收到这份资料的时间。

### 上传与合并

`private_player_profiles` 最多包含 `500` 份资料。同一份快照内，资料 UUID、昵称（不区分大小写）和非空的 QQ 都不能重复，`avatar_url` 引用的头像文件必须存在于当前服务端。任何一份资料不符合要求，整个快照以 `400` 被拒绝。

终端在上传前先整理本机资料库：丢弃 UUID 无效或昵称为空的资料，把昵称截断到 `18` 个字符；同一 UUID 或同一昵称（不区分大小写）只保留 `updated_at` 最新的一份；多份资料使用同一个 QQ 时，这些资料都按 `qq_number: null` 上传。终端不限制上传的资料数量，超过 `500` 份时快照被拒绝。

服务端按 `QUEUE_PROFILE_SCOPE_ID`（默认 `default`）保存机厅资料库；测试同步的资料保存在独立的作用域中，见“权威终端与同步方式”。上传按资料 UUID 逐份合并，不是整体替换：快照中没有出现的资料不会被删除，空数组也不会清空服务器备份。每份资料按以下规则处理：

- 已由管理后台删除的 UUID：忽略，不会重新建立。
- 服务端没有的 UUID：保存为新资料。
- 上传的 `profile_revision` 高于已保存的值：按上传内容保存。
- 上传的 `profile_revision` 等于或低于已保存的值：只有使用记录有进展时才保存，即 `usage_count` 更大、`last_used_at` 更晚，或 `recent_usage_at` 合并后与已保存的不同。此时保存的是整份上传内容，`profile_revision` 记为已保存的值加 `1`；否则只刷新 `synced_at`。
- schema 4 及更早版本的快照没有 `profile_revision`：上传的 `updated_at` 晚于已保存的值时才保存，`profile_revision` 记为已保存的值加 `1`。

另有三组字段不完全由上传决定：

- `public_player_id`：服务端始终保留已保存的编号。终端会为本机还没有编号的资料先分配一个，并随快照上传；资料在服务端尚无编号时，上传的编号只有在作用域内没有被任何资料或历史编号占用时才被采用，否则由服务端另行分配。
- `recent_usage_at`：与已保存的记录合并，保留最新的 `16` 条。快照中没有这个字段时，已保存的记录保持不变。
- 头像和网页账户字段：已绑定网页账户的资料受保护，未绑定和首次出现的资料会被恢复为默认值，见“网页账户字段”。

服务端改写过的内容（版本号、六位玩家编号和受保护的字段）通过“同步云端资料”回到终端。

### QQ 冲突

同一个作用域内，一个 QQ 只能属于一份资料。保存一份带 QQ 的资料时，如果另一份资料已经使用这个 QQ，服务端依次判断：

1. 另一份资料是这份资料的旧重复资料（见下一节）：删除另一份资料及其网页账户，并把它的六位玩家编号记为这份资料的历史编号。
2. 另一份资料的 `profile_revision` 不低于上传的版本：跳过这份上传，不保存其中任何字段。
3. 其余情况：保存上传的资料。另一份资料的 QQ 被清除，`profile_revision` 加 `1`，同时解除网页账户绑定并删除账户，避免一项身份同时绑定两份资料。

昵称冲突不在合并时处理：服务端只拒绝同一份快照内的重复昵称，不与已保存的其他资料比较。终端取回云端资料时自行检查昵称和 QQ 冲突，见“同步云端资料”。

### 重复资料与别名

旧版本可能为同一名玩家留下两份资料。服务端把同时满足以下条件的两份资料视为重复资料：UUID 不同；昵称（不区分大小写）、性别和默认偏好相同；最多只有一份填写了 QQ；其中至少一份既没有 QQ，`setup_version` 也小于 `1`。

保留哪一份由“终端当前资料库”决定。它是终端最近一次上传的资料 UUID 集合，另加通过二维码建档、尚待终端首次上传的资料，并去掉已删除的资料。昵称、性别和默认偏好相同的一组资料中，恰好有一份位于终端当前资料库时，这一份是保留的资料，组内与它重复的其余资料是它的别名。组内有多份资料填写了 QQ，或位于终端当前资料库的资料不是恰好一份时，不产生别名。

schema 3 及以上的每次上传都会整体替换终端当前资料库。这类快照缺少 `private_player_profiles` 时按空数组处理：已保存的资料不会被删除，但集合中只剩待上传的建档资料，在下一次完整上传之前服务端不再给出别名。

别名的处理方式：

- `GET /api/queue-terminal/profiles`、Bot 使用的 `/api/queue-bot/profiles` 和移动设备登记页面读取的资料列表不包含别名资料，改在 `profile_aliases` 中以“别名 UUID → 保留的 UUID”给出。
- 终端据此删除本机的别名资料，并把仍引用它的登记改为引用保留的资料，见“同步云端资料”。
- 别名资料在 QQ 冲突中被删除后，它的六位玩家编号成为保留资料的历史编号，出现在 `public_player_id_aliases` 中。历史编号不会分配给其他资料，也不会重新成为某份资料的当前编号。

### 网页账户字段

schema 8 起，私有玩家资料增加五个与网页账户有关的字段。它们出现在快照的 `private_player_profiles`、`GET /api/queue-terminal/profiles` 的响应和账户对象中：

```json
{
  "avatar_url": "https://example.com/api/player-avatars/<64 位十六进制引用>.webp",
  "web_account_bound": true,
  "terminal_editing_allowed": false,
  "visited_venues_public": true,
  "web_profile_revision": 3
}
```

- `web_account_bound`：资料是否已绑定网页账户。只有服务端在完成绑定或二维码建档时把它设为 `true`，终端不能通过快照建立或保留绑定。
- `terminal_editing_allowed`：现场终端是否可以编辑已绑定的资料。完成绑定和二维码建档时设为 `false`，玩家可以在网页重新开启。
- `visited_venues_public`：玩家是否公开去过的机厅。该值会保存、同步并受保护，但当前服务端没有任何响应依据它过滤内容。
- `web_profile_revision`：网页侧修改计数。完成绑定、每次实际生效的网页资料修改和每次头像上传各加 `1`，二维码建档的资料从 `1` 开始。终端保存取回的值，并在快照中回显。
- `avatar_url`：玩家上传的头像地址，没有头像时为 `null`。终端上传时可以使用完整地址或其中的 64 位十六进制引用；引用的文件在服务端不存在时，快照被拒绝。

快照缺少这些字段，或终端使用 schema 7 及更早版本时，服务端按 `avatar_url = null`、`web_account_bound = false`、`terminal_editing_allowed = true`、`visited_venues_public = true`、`web_profile_revision = 0` 读取。

对服务端记录为已绑定的资料，终端上传时按以下规则合并：

- QQ、头像、`terminal_editing_allowed`、`visited_venues_public` 和 `web_profile_revision` 始终保留服务端的值，`web_account_bound` 保持 `true`；使用次数和最近使用时间取两边较大者。
- 昵称、性别、默认偏好、`qq_visibility`、六个通知字段和 `setup_version` 在以下任一情况下保留服务端的值：快照的 `web_profile_revision` 低于服务端；或 `terminal_editing_allowed` 为 `false`，且这次变更不对应一条终端已经应用的资料修改命令。
- 被保留的值与快照不同而快照的 `profile_revision` 更高时，服务端在两者较大值上再加 `1`，终端下一次同步云端资料时取回服务端的值。

schema 7 及更早版本的快照按 `web_profile_revision = 0` 处理，因此这类终端不能覆盖已绑定资料的上述字段。

对服务端记录为未绑定或首次出现的资料，服务端清除头像，把其余四个字段恢复为默认值，并撤销该资料残留的网页账户。首次出现的资料如果带有其他服务端留下的网页账户字段，`profile_revision` 还会加 `1`，使终端接受未绑定状态。资料在 QQ 冲突中被更高版本的资料取代时，除清除 QQ 外也会解除绑定并删除账户。

现场终端只在资料未绑定或 `terminal_editing_allowed` 为 `true` 时允许编辑。已绑定资料的 QQ 只能在网页修改，密码、头像和绑定状态也只在网页处理。完成绑定会把 `qq_visibility` 设为 `PUBLIC_WEBSITE`，二维码建档的资料初始也是 `PUBLIC_WEBSITE`，玩家之后可以在网页修改。

## 当前登记绑定

`private_player_contacts` 把登记与玩家资料、QQ 关联起来。服务端用它识别登记属于哪一份玩家资料：决定公开快照中的登记是否附带 QQ，供 Bot 和网页账户查找本人登记，并确定通知的收件人。每一项的结构如下：

```json
{
  "registration_id": "a10f5015a2e85b12e20af507",
  "profile_id": "37e41698-46f8-489b-92dc-d29c71f00f7d",
  "qq_number": "12345678"
}
```

`registration_id` 是 24 位小写十六进制的登记编号，`profile_id` 是资料 UUID，`qq_number` 是 `5` 至 `12` 位数字。

终端为两类登记生成绑定，单次快照最多 `600` 条，当前登记优先：

- 当前队列中使用玩家资料且该资料 QQ 有效的登记。临时登记和没有 QQ 的资料不产生绑定。
- 最近 `200` 条公开事件涉及的登记，即使登记已经离开队列。前提是对应资料仍在本机资料库中并且 QQ 有效，QQ 取资料当前的值。

服务端在接受快照前校验绑定，数组超过 `600` 条或任何一条不符合时整个快照以 `400` 被拒绝：

- 绑定引用的登记必须是快照中的当前登记，或出现在本次 `recent_events` 的 `registration_ids` 中。
- 当前登记必须是玩家资料登记（`registration_type` 为 `PLAYER_PROFILE`），临时登记不能带有绑定。
- 绑定必须引用同一快照 `private_player_profiles` 中的资料，QQ 也必须与该资料一致。
- 登记编号不能重复，同一份玩家资料不能关联多份当前登记。

绑定按以下方式保存和使用：

- 服务端按队列批次保存绑定。登记离队后，绑定在当前队列批次内保留，用于发送与本人有关的处理日志；队列批次变化时清除上一批次的绑定，见“上传队列快照”。
- 每份玩家资料在一个队列批次内最多保存一条绑定，当前登记的绑定优先。
- 仍在队列中的登记如果在新快照里不再带有绑定，已保存的绑定随之删除。
- 绑定不会出现在公开接口中。唯一的公开效果是：资料的 `qq_visibility` 为 `PUBLIC_WEBSITE` 时，公开快照中对应的当前登记带有 `qq_number`，否则为 `null`。

## 自动在两台机台之间安排登记

这一节汇总这项功能在快照以外增加的内容。快照字段 `also_queues_at_paired_machine`、`skips_paired_machine_this_round`、`paired_arrangement_announced`、`paired_arrangement_not_before`、`paired_machine_id`、`paired_waiting_positions` 和 `estimated_wait_uncertain` 见“当前队列”。所有新增内容都没有提升协议版本号，旧版终端、旧版服务端和旧版客户端不发送也不读取它们。

### 玩家资料里只有一项记录

这项选择只属于单份登记，是创建登记或更改本次游玩偏好时的一个勾选框，界面上的文字是“允许系统在当前机台和 机台 B 之间安排登记”。玩家资料里不保存这项选择，只保存一项记录：

- `paired_machine_queueing_introduced`：布尔值，缺省为 `false`。玩家是否已经看过这项功能的说明。说明在第一次开启勾选框时自动弹出，5 秒后才能关闭；看过之后不再自动弹出，随时可以点勾选框旁的问号查看。这一项只会从 `false` 变为 `true`。
- 终端在 `private_player_profiles` 中只在它为 `true` 时上传。服务端按“或”合并：上传里没有这个键或为 `false`，都不会把已经记下的 `true` 清掉。
- 服务端在各个资料对象里返回它：账户对象的 `profile`、`GET /api/queue-terminal/profiles`、`POST /api/queue-online/profile` 的 `profile` 和移动设备登记会话的 `profiles`。
- 请求中的 `also_queues_at_paired_machine` 为 `true` 时，服务端按请求能否确认出自玩家本人，在两个时刻之一把所用资料的这一项记为 `true`：
  - 移动设备登记提交和网页账户的 `SET_PAIRED_MACHINE_QUEUEING`：接受请求时就记。前者需要现场终端二维码的会话，后者需要已登录的账户。
  - 网站线上登记（`POST /api/queue-online/join`）：这个接口不要求登录，任何人都可以凭 QQ 提交，所以接受请求时不记；终端回报这条命令 `APPLIED` 时才记，回报 `REJECTED` 或超时都不记。
  - Bot 和管理后台的操作不记，因为它们不显示说明。
- 终端在本机弹出说明时记为 `true`；执行来自网站的 `JOIN_QUEUE` 和移动设备登记命令、并且命令带有 `also_queues_at_paired_machine: true` 时也记为 `true`。终端同步云端资料时同样按“或”合并，云端的副本不会把本机已经记下的 `true` 清掉。
- 临时登记没有玩家资料，每次开启都会弹出说明。

开发过程中曾经有过三选一的资料字段 `paired_machine_queueing`，它从未随任何正式版本发布，现已删除：快照上传中出现这个键时服务端忽略；`PATCH /api/player-account/profile`、`POST /api/player-profile-creation/sessions/<session_token>/complete` 和 `POST /api/queue-mobile/sessions/<session_token>/submit` 本来就拒绝未知字段，带有这个键时返回 `400`。

### 创建登记时的勾选

三条创建登记的路径都接受可选的布尔字段 `also_queues_at_paired_machine`：

- `POST /api/queue-online/join`（网站线上登记，Bot 的 `JOIN_QUEUE` 相同）。
- `POST /api/queue-mobile/sessions/<session_token>/submit`（移动设备登记）。
- 管理后台的 `ADD_PROFILE_REGISTRATION`。

服务端和终端使用同一条规则，终端在执行命令时再判断一次：

- 字段缺省按 `false` 处理，登记只在所登记的机台排队。旧版网站和 Bot 属于这种情况。
- 字段出现时，所选机台必须有 `paired_machine_id`，否则返回 `409`。
- 字段为 `true` 的登记立即生效：创建后就在另一机台取得位置。取得位置不等于转至另一机台：即使另一机台正好空着，登记也留在所登记的机台，要等另一机台的预览预告满规定时间之后才会被安排过去，见“当前队列”中的 `paired_arrangement_announced`。
- 线上登记在完成现场签到之前只在所登记的机台排队，签到之后才在另一机台取得位置。
- 这个字段不与玩家资料的任何内容比较。

通过校验的字段原样写入命令的 `payload.also_queues_at_paired_machine`，缺省时命令中没有这个键。字段参与 `request_id` 的幂等判断：同一个 `request_id` 换了不同的选择会被当作另一条请求，返回 `409`。

网站据以显示勾选框的数据：`POST /api/queue-online/profile` 的 `machines` 每一项带 `paired_machine_id`；移动设备登记会话的 `session` 带 `paired_machine_id` 和 `paired_machine_name`，机台不属于一对机台时两者为 `null`，另带 `paired_machine_queueing_available`，表示机厅是否有成对的机台（与会话所选的机台是否成对无关）。网站只在所选机台成对时显示勾选框，并且总是提交这个字段。

### 修改已有登记：`SET_PAIRED_MACHINE_QUEUEING`

Bot（`POST /api/queue-bot/queue-commands`）、网页账户（`POST /api/player-account/queue-commands`）和管理后台（`POST /api/queue-management/queue-commands`）的排队操作都增加 `SET_PAIRED_MACHINE_QUEUEING`，请求必须带布尔字段 `also_queues_at_paired_machine`，表示修改后的状态。缺少该字段返回 `400`；其他操作（`JOIN_QUEUE` 除外）带有该字段同样返回 `400`。

服务端在生成命令前检查：登记所在机台没有 `paired_machine_id` 时返回 `409`，提示为 `系统规则不允许自动在两台机台之间安排登记`，开启和取消都是如此，这样命令不会发给不认识这项操作的旧版终端；开启时登记属于固定组合的返回 `409`。其余检查（确认状态、机台是否停止使用、待签到）与其他操作相同。

终端执行时，登记已经处于所请求的状态则按已执行处理。开启不会让登记立即转至另一机台，另一机台正好空着时也是如此：登记先在另一机台取得位置，预览预告满规定时间之后才会被安排过去。


为已经在排队的登记改为 `true` 时不会立即生效：终端同时把这份登记的 `skips_paired_machine_this_round` 设为 `true`，登记游玩一轮之后才在另一机台取得位置。为正在游玩的登记改为 `true`，本轮结束后生效。改为 `false` 立即生效。网站在本人登记的“更改游玩偏好”里提供这个勾选框，并在勾选时提示这一点。
### 手动转至其他机台

同时排队的登记仍然可以手动转至其他机台，服务端和终端都不因为这项选择而拒绝 `TRANSFER_MACHINE`。目标是登记所在机台的 `paired_machine_id` 时，登记加入目标机台的队尾，`also_queues_at_paired_machine` 保持为 `true`，它在原机台的位置也改到末尾。转至第三台机台时，转入后不再同时排队。网站在转至其他机台的确认文字里说明这两种结果。

### 机厅设置：哪些机台成对

终端在快照顶层上报可选字段 `paired_machine_queue_settings`：

```json
{
  "paired_machine_queue_settings": {
    "enabled": true,
    "pairs": [["0123456789abcdef0123456789abcdef", "fedcba9876543210fedcba9876543210"]]
  }
}
```

`enabled` 是机厅的开关，`pairs` 是设置中的每一对机台，用机台的 `stable_id` 表示。对象必须正好包含这两个键。服务端的要求：最多 `5` 对；每一对是两个不同的、当前快照中存在的机台；一台机台最多出现在一对里；一对机台的游玩容量相同。此外它必须与各机台的 `paired_machine_id` 一致：开关开启时，成对的机台互相指向，其余机台为 `null`；开关关闭时所有机台都是 `null`。不一致时拒绝整份上传。

该字段出现表示终端可以执行管理后台对这项设置的修改；旧版终端不上报，服务端保存为 `null`。它会进入公开快照。

管理总览的 `queue.terminal_settings.paired_machine_queue_settings` 原样返回这个对象，`capabilities.PAIRED_MACHINE_QUEUE_EDIT` 在终端声明了 `management_settings_supported` 并上报了这个对象时为 `true`。

`PATCH /api/queue-management/terminal-settings` 接受可选的 `paired_machine_queue_settings`，结构相同。校验时使用**这条命令提交的** `machines`：一对机台引用的 `stable_id` 必须出现在其中，并且提交的游玩容量相同，因为同一条命令可能同时增减机台或修改容量。字段缺省时命令中没有这个键，终端保留自己的设置；字段出现而终端没有上报过这项设置时返回 `409`，提示为 `当前现场终端不支持远程设置同时在两台机台排队`。它参与 `request_id` 的幂等判断。

终端执行 `UPDATE_TERMINAL_SETTINGS` 时，命令带有 `paired_machine_queue_settings` 就整体替换本机的开关和各对机台，一对机台内两个标识的先后不限；命令中有任何一对在终端看来不成立时，整条命令视为无效，不予执行。终端被管理后台接管期间，这项设置与其他设置一样只能从管理后台修改。

### 管理后台的终端队列操作

`POST /api/queue-management/terminal-actions` 增加 `SET_PAIRED_MACHINE_QUEUEING`：必须指定一份登记，并带布尔字段 `also_queues_at_paired_machine`。缺少该字段返回 `400`；`ADD_PROFILE_REGISTRATION` 也可以带这个字段，表示创建时是否勾选；其他操作带有它返回 `400`。登记所在机台没有 `paired_machine_id` 而请求带有该字段时返回 `409`。通过校验的字段写入命令的 `payload.also_queues_at_paired_machine`。

终端执行 `ADD_PROFILE_REGISTRATION` 时按“创建登记时的勾选”一节的规则决定；执行 `TRANSFER_REGISTRATIONS` 时按“手动转至其他机台”一节处理，不因为所选登记同时排队而拒绝。

管理总览的每台机台增加 `paired_machine_id`，每个等待位置增加 `estimated_wait_uncertain`，每份登记增加 `also_queues_at_paired_machine`、`skips_paired_machine_this_round`、`paired_arrangement_announced` 和 `paired_arrangement_not_before`（没有时为 `null`）。

### 本人排队状态中的新增字段

`GET /api/player-account/queue` 的 `registrations` 每一项增加 `also_queues_at_paired_machine`、`skips_paired_machine_this_round`、`paired_arrangement_announced`、`paired_arrangement_not_before`（没有时为 `null`）、`paired_machine_id`（登记所在机台的另一机台，没有时为 `null`）和 `estimated_wait_uncertain`（见“当前队列”）；`queue.machines` 每一项增加 `paired_machine_id`。网站据此在本人登记下写明预览已经显示，以及最早何时之后可能自动转至另一机台；时刻按浏览器所在设备的本地时间显示为 `HH:mm`，已经过去时改为说明随时可能自动转至另一机台。

## 公开接口

### 通用约定

本节接口不需要令牌；只有查询网页账户命令的结果时需要玩家会话，见“网站线上登记”。除头像文件外，响应体都是 JSON。

错误响应的格式为 `{"ok": false, "error": "<说明>"}`，部分错误另带稳定的 `code`。本节只有四个 `code`，都出现在网站线上登记接口：`PROFILE_NOT_FOUND`、`PROFILE_SYNC_PENDING`、`QUEUE_CONTEXT_CHANGED` 和 `PLAYER_OPERATION_SYNCING`；其余错误只能按 HTTP 状态码区分。健康检查使用独立的响应格式。请求体超过 `1 MiB` 时返回 `413`。

所有时间字段都是毫秒时间戳。服务端生成的时间精确到秒，包括 `received_at`、`terminal.last_seen_at`、`checked_at`、版本状态的 `updated_at` 以及命令的 `created_at`、`claimed_at` 和 `completed_at`；终端生成的 `captured_at`、事件的 `occurred_at` 和登记的 `created_at` 原样返回。

服务端为每一个响应写入以下响应头，私有接口和错误响应也不例外：

```text
Access-Control-Allow-Origin: <QUEUE_CORS_ORIGIN>
Access-Control-Allow-Methods: GET, POST, PATCH, OPTIONS
Access-Control-Allow-Headers: Authorization, Content-Type, X-Device-ID, X-Queue-Schema-Version, X-Queue-Venue-ID, X-CSRF-Token, X-Queue-Sync-Mode, X-Terminal-Instance-ID, X-Terminal-Instance-Generation
Access-Control-Allow-Credentials: true
Cache-Control: no-store, max-age=0
Pragma: no-cache
X-Content-Type-Options: nosniff
Vary: Origin
```

`Access-Control-Allow-Origin` 固定为 `QUEUE_CORS_ORIGIN` 配置的单一来源，不回显请求中的 `Origin`。未配置时该响应头为空值，也不发送 `Access-Control-Allow-Credentials`，浏览器只能同源访问。`Access-Control-Allow-Credentials: true` 让网站可以跨域携带网页账户的会话 Cookie。允许的方法不包含 `PUT` 和 `DELETE`；使用这两种方法的接口只由现场终端和管理后台调用，不经过浏览器。

`/api/player-avatars/` 路径下的响应改用 `Cache-Control: public, max-age=31536000, immutable`，并且不带 `Pragma`。`OPTIONS /api/queue-status` 返回 `204` 和空响应体。

### 当前队列

`GET /api/queue-status`

返回服务端保存的唯一一份公开快照：机台名称、游玩位置、按实际轮换推导的等待位置、昵称、状态、时间估算、QQ Bot 联动状态和营业时间计算结果，并附带服务端补充的机厅、终端在线状态和能力标记。同一路径的 `POST` 是现场终端的上传接口，需要同步令牌；`GET` 不检查令牌，也没有查询参数。终端从未上传过快照时返回 `404`，说明为 `排队终端暂未同步`。

顶层字段：

- `schema_version`：服务端保存快照时固定写为 `8`，与终端上传时使用的协议版本无关。
- `queue_id`、`revision`、`machine_configuration_revision`、`captured_at`：队列批次、队列修订号、机台配置修订号和终端生成快照的时间。
- `received_at`：服务端接受这份快照的时间。
- `registration_open`：登记排队当前是否开放。闭店收尾期间终端上传 `false`，开关本身的状态见 `registration_control_open`。
- `website_remote_enabled`、`onebot_sync_enabled`：现场终端是否开启与服务端同步和 QQ Bot 联动。终端在与服务端同步关闭时上传的 `onebot_sync_enabled` 也是 `false`。
- `queue_rules`：`allow_defer_one_round`、`allow_temporary_leave` 和 `allow_online_registration` 三个布尔值。
- `business_hours`：六个营业时间计算结果。
- `management_policy_supported`、`management_app_bound`、`management_policy_revision`、`management_policy`：管理后台接管终端敏感策略的状态。
- `management_settings_supported`、`management_settings_revision`、`show_common_play_preview`、`registration_control_open`、`business_hours_settings`：可由管理后台接管的终端设置。
- `terminal`、`venue`：现场终端和机厅。
- `machine_groups`、`default_machine_group_id`、`machines`：机台分组和各机台的队列。`machines` 是以机台编号 `A` 至 `J` 为键的对象。
- `test_data`：这份快照是否来自测试同步。
- `capabilities`：服务端按当前状态计算的能力标记。

响应不包含 `recent_events`、`private_player_contacts` 和 `private_player_profiles`。下面只列出由服务端写入的字段，其余从略：

```json
{
  "schema_version": 8,
  "received_at": 1784681000000,
  "test_data": false,
  "terminal": {
    "id": "00000000-0000-4000-8000-000000000001",
    "name": "前台终端",
    "online": true,
    "app_version": "0.13.6",
    "last_seen_at": 1784681000000,
    "last_seen_seconds": 4,
    "offline_after_seconds": 90
  },
  "venue": {
    "id": "00000000-0000-4000-8000-000000000002",
    "code": "ABCD2345",
    "name": "示例机厅",
    "registered": true
  },
  "capabilities": {
    "public_logs": true,
    "local_self_marking": true,
    "registration_qq": true,
    "remote_actions": true,
    "online_registration": true,
    "transport": "polling"
  }
}
```

`terminal.id` 是持有当前快照的终端的 `X-Device-ID`。它是公开信息，不能当作凭据。`name` 是版本 8 终端上传的终端名称，旧终端或未设置时为 `null`；`app_version` 是终端上传的应用版本，可以为 `null`。其余四项在每次读取时由服务端计算：`last_seen_at` 等于 `received_at`，`last_seen_seconds` 是距离接收时间的秒数，`offline_after_seconds` 是 `QUEUE_ONLINE_TIMEOUT_SECONDS`（默认 `90`），`last_seen_seconds` 不超过该阈值时 `online` 为 `true`。终端上传的 `terminal.online` 和 `terminal.last_seen_at` 不会被采用。

`venue` 在每次读取时取自服务端当前的机厅记录，版本 8 之前的终端上传的快照同样带有该字段。`id` 是机厅 ID，`code` 是 8 位机厅编号，`name` 是机厅名称，尚未设置时为 `null`，`registered` 固定为 `true`。终端上传的快照只携带机厅 ID。

`capabilities` 中 `public_logs`、`local_self_marking` 和 `registration_qq` 固定为 `true`，`transport` 固定为 `polling`。终端在线且 `website_remote_enabled` 为 `true` 时 `remote_actions` 为 `true`。`online_registration` 在此基础上还要求 `registration_open` 为 `true`、`queue_rules.allow_online_registration` 不为 `false`，并且不处于闭店收尾。网站只在 `online_registration` 为 `true` 时开放“加入排队”入口。

`management_policy_*` 和 `management_settings_*` 字段由终端声明，没有提升协议版本。`management_policy` 包含 `allow_online_registration`、`allow_defer_one_round`、`allow_temporary_leave` 和 `onebot_sync_enabled`。终端没有声明 `management_policy_supported` 时，服务端写入 `management_app_bound: false` 和 `management_policy_revision: 0`，`management_policy` 取 `queue_rules` 和 `onebot_sync_enabled` 的当前值。终端没有声明 `management_settings_supported` 时，`management_settings_revision` 为 `0`，`show_common_play_preview` 为 `true`，`registration_control_open` 等于 `registration_open`，`business_hours_settings` 为 `null`。

`business_hours_settings` 是完整的营业时间表，包含 `enabled`、`use_weekly_schedule`、`default_hours` 和 `weekly_hours`。`default_hours` 以及 `weekly_hours` 中 `MONDAY` 至 `SUNDAY` 的每一项都包含 `opening_minutes` 和 `closing_minutes`，取值为当天的 `0` 至 `1439` 分钟。它和 `business_hours` 的计算结果一样可以公开读取。

每台机台包含 `id`、`stable_id`、`group_id`、`name`、`remark`、`configuration`、`operational`、`stop_reason`、`stop_reason_detail`、`stopped_at`、`playing_started_at`、`registration_count`、`waiting_position_count`、`new_registration_estimated_wait_minutes`、`playing`、`waiting_positions`、`paired_machine_id` 和 `paired_waiting_positions`。`registration_count` 和 `waiting_position_count` 由服务端按列表重新计算。`stop_reason` 为 `NOT_POWERED_ON`、`NETWORK_DISCONNECTED`、`MAINTENANCE`、`OTHER` 或 `null`，只有 `OTHER` 可以带 `stop_reason_detail`。机台停止使用时，`playing_started_at`、`new_registration_estimated_wait_minutes` 和所有等待位置的 `estimated_wait_minutes` 都是 `null`，所有等待位置的 `estimated_wait_uncertain` 都是 `false`。`configuration` 比终端上传的内容多一个与机台相同的 `remark`；`game_version_visible` 为 `false` 时 `game_version` 为 `null`。

每个等待位置包含 `index`、`position_id`、`fixed_pair`、`estimated_wait_minutes`、`estimated_wait_uncertain`、`registrations` 和 `common_play_preview`，`index` 由服务端从 `1` 开始编号。开放的单人位置可能包含 `common_play_preview`，其中只有 `registration_id` 和 `display_id`；它仅表示预计搭档，不属于该等待位置的真实登记，不计入登记数，也不能用于身份匹配或队列操作。

终端允许一对机台的登记同时在两台机台排队时，这两台机台的 `paired_machine_id` 互为对方的机台编号，其余机台为 `null`。服务端要求两台机台互相指向、都已配置并且游玩容量相同。登记的 `also_queues_at_paired_machine` 为 `true` 表示这份登记同时在另一机台排队。这份登记仍然只有一份：它列在所登记机台的 `playing` 或 `waiting_positions` 中并计入该机台的登记数，在另一机台只保留一个位置；另一机台轮到它、并且另一机台的预览已经预告满规定时间时，登记转至另一机台并进入游玩位置；预告时间未满时这一次被越过，位置保留，登记仍在所登记的机台等待。该字段为 `true` 的登记必须位于 `paired_machine_id` 不为 `null` 的机台，并且不属于固定组合。

登记的 `skips_paired_machine_this_round` 为 `true` 表示这份登记已经放弃另一机台的位置，在下一次进入游玩位置之前只在当前机台排队；`also_queues_at_paired_machine` 仍然保留玩家的选择，游玩一轮后恢复同时在两台机台排队。终端在以下情况设置它：等待顺序被调整或队列位置被移动时被移动的登记，以及从游玩位置撤回等待顺序的登记；登记进入游玩位置时清除。服务端只校验它是布尔值，不由任何命令设置。

登记的 `paired_arrangement_announced` 为 `true` 表示另一机台的等待顺序中正在显示这份登记的预览，预告它可能被安排过去；`paired_arrangement_not_before` 是它最早可能被安排过去的时刻（毫秒时间戳）。终端只对等待中、在另一机台持有位置的登记发送这两个字段：预览开始显示时发送 `paired_arrangement_announced: true`，预览持续显示至少 12 分钟后登记才可能被安排，`paired_arrangement_not_before` 即预览开始的时刻加上这段时间，暂停计时的时间顺延。任一机台本轮游玩超出正常时长期间终端暂停计时，这时只发送 `paired_arrangement_announced`，不发送时刻；预览中断后两者都不再发送，再次显示时重新计时。时刻已经过去而登记仍在等待，表示另一机台一旦轮到它就会安排。服务端的校验：`paired_arrangement_announced` 必须是布尔值；`paired_arrangement_not_before` 必须是非负整数，并且只能与 `paired_arrangement_announced: true` 同时出现，否则拒绝整份上传。登记的 `also_queues_at_paired_machine` 不为 `true` 时这两个字段没有意义，服务端不保留，分别按 `false` 和 `null` 处理。保存后的快照中每份登记都带有这两个键。它们只用于显示，不由任何命令设置，也不参与任何操作的确认。

`paired_waiting_positions` 只在终端预计另一机台的登记会先在本机台游玩、并且已经为它显示预览时出现，其余情况为 `null`。它是本机台此时应当显示的完整等待顺序：本机台等待中的全部登记，加上预计先在本机台游玩的另一机台登记。每个位置包含 `index`、`position_id`、`estimated_wait_minutes`、`estimated_wait_uncertain`、`registrations` 和 `common_play_preview`，`index` 由服务端从 `1` 开始编号。`registrations` 的每一项只有 `registration_id` 和 `paired_place_preview` 两个字段，登记的其余内容按 `registration_id` 在两台机台的 `waiting_positions` 中查找。`paired_place_preview` 为 `true` 的项是另一机台的登记，仅表示预测：它不计入本机台的登记数，不能用于身份匹配或队列操作，显示时应与真实登记区分。这份顺序的分组可能与 `waiting_positions` 不同，因为另一机台的登记可能与本机台的登记处于同一位置。该字段不为 `null` 时，客户端用它代替 `waiting_positions` 显示等待顺序；为 `null` 时照常使用 `waiting_positions`。

```json
{
  "paired_machine_id": "A",
  "paired_waiting_positions": [
    {
      "index": 1,
      "position_id": "5c0d1e2f3a4b5c6d7e8f9a0b",
      "estimated_wait_minutes": 6,
      "estimated_wait_uncertain": false,
      "registrations": [
        { "registration_id": "a10f5015a2e85b12e20af507", "paired_place_preview": true }
      ],
      "common_play_preview": null
    }
  ]
}
```

服务端对 `paired_waiting_positions` 的要求如下，任何一项不满足都会拒绝整份上传：

- 机台正常使用，并且 `paired_machine_id` 不为 `null`。
- 数组非空，最多 `40` 个位置。每个位置包含 1 或 2 份登记，游玩容量为 1 的机台只能包含 1 份。
- 同一份登记不能出现两次，位置编号不能重复。
- `paired_place_preview` 为 `false` 的登记必须正好是本机台等待中的全部登记。
- 至少包含一项预览；没有预览时该字段必须为 `null`。
- 每一项预览都必须是另一机台等待中的登记，其 `also_queues_at_paired_machine` 为 `true`、`skips_paired_machine_this_round` 不为 `true`，并且不处于将暂缓一次、将轮空一次、暂时离开或待签到状态。
- `common_play_preview` 的规则与等待位置相同，引用的登记可以是本机台的登记，也可以是上述预览登记。

成对机台的 `estimated_wait_minutes` 和 `new_registration_estimated_wait_minutes` 由终端按两台机台共同推算：一份登记可能先在另一机台轮到，另一机台的登记也可能先在本机台游玩。同一位置包含两份登记时，取其中较早轮到的一份。`new_registration_estimated_wait_minutes` 按一份只在本机台排队的新登记计算。

等待位置的 `estimated_wait_uncertain` 为 `true` 表示这个位置的 `estimated_wait_minutes` 可能不准确：有关的登记可能被系统在两台机台之间安排，终端无法确定时按偏早的情况推算，因此显示为“最快”（算法见 `docs/releases/wait-estimate-2026-11.md` 第三节）。`waiting_positions` 和 `paired_waiting_positions` 的每个位置都可以带有这个字段。它可以省略，省略或为 `null` 时按 `false` 处理，旧版终端不发送；出现时必须是布尔值，否则拒绝整份上传，错误为 `estimated_wait_uncertain 必须是布尔值。`。`estimated_wait_minutes` 为 `null` 时服务端一律保存为 `false`，机台停止使用时也是如此。保存后的快照中每个等待位置都带有这个键。`new_registration_estimated_wait_minutes` 是新登记的预计等待，没有这个标记。

服务端在转发等待时间的地方同时转发这个标记：管理总览的 `waiting_positions`，以及 `GET /api/player-account/queue` 和私有 Bot 接口“查询玩家”中的每份登记。处于游玩位置的登记始终为 `false`。为 `true` 时，网站和 QQ Bot 把估时写作“最快约 N 分钟后可以游玩”（QQ Bot 当前队列中的简短写法为“最快约 N 分钟后”），不到 1 分钟时写作“可能很快可以游玩”，并在详情和 Bot 回复中说明一次：“受系统在两台机台之间安排登记的影响，等待时间可能不准确，请以现场实际情况为准。”

游玩位置和等待位置中的每份登记固定为以下 17 个字段，不包含资料 UUID、性别、头像或玩家编号：

```json
{
  "registration_id": "a10f5015a2e85b12e20af507",
  "display_id": "示例玩家",
  "preference": "OPEN_TO_JOIN",
  "deferred_once": false,
  "temporarily_away": false,
  "temporary_away_skipped_turns": 0,
  "fixed_pair": false,
  "fixed_pair_id": null,
  "no_show_count": 0,
  "last_no_show_action_was_defer": false,
  "online_registration_pending_check_in": true,
  "also_queues_at_paired_machine": false,
  "registration_type": "PLAYER_PROFILE",
  "created_at": 1784680000000,
  "online_check_in_started_at": 1784680000000,
  "last_played_at": null,
  "qq_number": null
}
```

`registration_type` 为 `TEMPORARY` 或 `PLAYER_PROFILE`。`online_check_in_started_at` 是待签到计时的起点，终端没有上传时等于 `created_at`。

终端菜单“延后这份登记”提供三种方式，分别对应登记的三个字段：`deferred_once` 为 `true` 表示将暂缓一次，下一次轮到时跳过一次并保留位置；`skips_one_turn` 为 `true` 表示将轮空一次，下一次轮到时移至队尾，计一次轮空，随后自动解除；`temporarily_away` 为 `true` 表示暂时离开，每次轮到时都移至队尾，直到取消。`temporary_away_skipped_turns`（`0` 至 `3`）是轮空次数，轮空一次和暂时离开合计；已轮空 3 次后再轮到时，登记会被自动移除。服务端的校验：三项中最多一项为 `true`（`登记最多只能处于将暂缓一次、将轮空一次和暂时离开中的一种状态。`）；`online_registration_pending_check_in` 为 `true` 时三项都必须为 `false`（`待签到的线上登记不能处于将暂缓一次、将轮空一次或暂时离开状态。`）；`temporary_away_skipped_turns` 只有在 `temporarily_away` 或 `skips_one_turn` 为 `true` 时可以不为 `0`（`只有暂时离开或将轮空一次的登记才能包含轮空次数。`）；固定组合的两份登记这三项、轮空次数和待签到状态必须相同。将暂缓一次、将轮空一次或暂时离开的登记不在另一机台持有位置。

`defer_one_round_used` 为 `true` 表示这份登记已经暂缓过一次（跳过已经发生），此后还没有游玩一轮，在此之前不能再次暂缓一次。`online_registration_not_played` 为 `true` 表示这是一份线上登记，已经在现场签到、还没有游玩一轮；在此之前暂缓一次、轮空一次和暂时离开都不能使用。这两项限制网站、QQ Bot 和现场终端上的操作，只有管理后台不受限制，见“远程操作边界”。它们由终端维护，不由任何命令直接设置，也不参与操作的确认。

`skips_one_turn`、`defer_one_round_used` 和 `online_registration_not_played` 可以省略，省略或为 `null` 时按 `false` 处理，旧版终端不发送；出现时必须是布尔值，否则拒绝整份上传，错误为 `skips_one_turn 必须是布尔值。` 等。保存后的快照中每份登记都带有这三个键。服务端在转发 `deferred_once` 和 `temporarily_away` 的地方同时转发它们：`GET /api/player-account/queue`、私有 Bot 接口“查询玩家”和管理总览中的每份登记。

`qq_number` 在每份登记上都存在，默认为 `null`。只有同一次上传中该登记的绑定指向一份 `qq_visibility` 为 `PUBLIC_WEBSITE` 的玩家资料时，服务端才写入对应的 QQ，供网站登记详情显示；`TEMPORARY` 登记和版本 5 之前的上传始终为 `null`。是否公开在上传时确定，保持到下一次上传；登记离开队列后，其 QQ 不再出现在公开快照中。

`test_data` 由服务端在接受上传时按同步模式写入：请求带有 `X-Queue-Sync-Mode: test`，或同一终端没有带该请求头而当前快照已是测试数据。请求体中的同名字段不会被采用。为 `true` 时，网站和 QQ Bot 会提示当前数据是测试数据，该批次的日志、登记绑定和玩家资料与正式数据隔离保存。

公开快照不是终端上传内容的原样转发。服务端在保存前移除三个私有字段，并按固定字段重建每一个对象；未知字段要么被丢弃，要么使整份上传被拒绝，不会进入响应。读取时只补写 `received_at`、`terminal` 中由服务端计算的字段、`venue` 和 `capabilities`，并去掉所有字符串中 `·` 两侧的空格，已保存的其余内容不会重新规范化。因此服务端升级后、终端下一次上传被接受之前，响应仍是旧版本服务端保存的形状，`schema_version` 可能还是旧值，后来新增的字段可能缺失。

### 公开日志

`GET /api/queue-logs?queue_id=<UUID>&limit=<条数>&before=<游标>&source=<操作来源>`

按游标从新到旧分页返回一个队列批次的公开事件。只返回白名单内的队列事件，不包含 QQ、性别、资料 UUID 或私有资料编辑日志。四个查询参数都可以省略：

- `queue_id`：队列批次 UUID，省略时使用当前快照的批次。
- `limit`：`1` 至 `100` 的整数，默认 `50`。
- `before`：正整数游标，只返回游标小于该值的事件。
- `source`：按 `operation_source` 筛选，不区分大小写。

```json
{
  "queue_id": "00000000-0000-4000-8000-000000000003",
  "logs": [
    {
      "cursor": 1287,
      "event_id": "00000000-0000-4000-8000-000000000004",
      "occurred_at": 1784681000000,
      "machine_id": "A",
      "machine_stable_id": "0123456789abcdef0123456789abcdef",
      "machine_name": "左侧·机台 A",
      "type": "REGISTRATION_UPDATED",
      "title": "机台 A·队列已更新",
      "detail": "“示例玩家”的排队状态已更新。",
      "operation_source": "ON_SITE_TERMINAL",
      "notification_categories": ["QUEUE_CHANGES"],
      "registration_ids": ["a10f5015a2e85b12e20af507"]
    }
  ],
  "next_cursor": 1287,
  "capabilities": {
    "public_logs": true,
    "local_self_marking": true,
    "registration_qq": true,
    "remote_actions": false,
    "online_registration": false,
    "transport": "polling"
  }
}
```

`cursor` 是服务端分配的递增整数，只用于翻页；`event_id` 是终端生成的事件 UUID。`next_cursor` 是本页最后一条事件的游标，没有更早的事件时为 `null`；把它作为下一次请求的 `before` 即可继续读取。响应中的 `queue_id` 是规范化为小写标准形式后的批次 UUID。`capabilities` 与当前队列响应中的结构相同，但 `remote_actions` 和 `online_registration` 在这里固定为 `false`。

每条机台事件包含事件发生时的 `machine_id`、`machine_stable_id` 和 `machine_name`；稳定身份用于在机台重编号后继续归入同一台物理机台，已删除机台仍保留事件发生时的名称。系统事件的 `machine_id` 为 `null`。服务端收到同一旧事件的稳定身份后只回填空字段，不改变事件游标或重新建立通知收件人。

`type` 取值为 `REGISTRATION_ADDED`、`REGISTRATION_REMOVED`、`REGISTRATION_UPDATED`、`QUEUE_REORDERED`、`PLAYING_CHANGED`、`NO_SHOW_DEFERRED`、`NO_SHOW_MOVED_TO_TAIL`、`NO_SHOW_REMOVED`、`TEMPORARY_AWAY_EXPIRED`、`ONLINE_REGISTRATION_ADDED`、`ONLINE_CHECK_IN_COMPLETED`、`ONLINE_CHECK_IN_TIMED_OUT`、`ONLINE_CHECK_IN_MISSED`、`ABSENCE_CHANGED`、`MACHINE_STOPPED`、`MACHINE_RESTORED`、`REGISTRATION_OPENED`、`REGISTRATION_CLOSED`、`QUEUE_RESTORED`、`QUEUE_RESET` 或 `OTHER`。

`operation_source` 取值为 `ON_SITE_TERMINAL`、`QQ_BOT`、`SYSTEM_AUTOMATIC`、`WEBSITE_REMOTE`、`MOBILE_DEVICE` 或 `MANAGEMENT_APP`，终端没有提供时按 `ON_SITE_TERMINAL` 保存。`WEBSITE_REMOTE` 表示网站线上登记和登录玩家在网页上的本人排队操作，`MANAGEMENT_APP` 表示管理后台发起的操作。`source` 参数接受同一组取值。

`notification_categories` 是非空数组，取值为 `QUEUE_CHANGES`、`PLAYING_POSITION`、`ONLINE_CHECK_IN`、`ABSENCE` 和 `MACHINE_STATUS`；保存的分类为空或无效时，服务端按事件类型推导。`registration_ids` 是事件涉及的登记编号，客户端可以用自己保存的登记编号找出与本人有关的事件。

参数无效时返回 `400`：`source` 不在上述取值内、`limit` 不是 `1` 至 `100` 的整数、`before` 不是正整数，或 `queue_id` 不是 UUID。省略 `queue_id` 且终端从未上传过快照时返回 `404`。格式正确但没有事件的 `queue_id` 返回 `200` 和空的 `logs`。

每个批次最多保留最新的 `2000` 条事件，更早的事件在该批次的快照上传时删除。过去批次的事件不会按时间清理，仍可以用原来的 `queue_id` 读取。测试同步批次的事件单独保存，只有该测试快照是当前快照时才能读取；测试快照与正式批次使用同一个 `queue_id` 时，这期间读不到正式批次的事件。

管理后台使用的 `GET /api/queue-management/logs` 是同一实现，但需要管理令牌。

### 版本状态

`GET /api/queue-versions`

返回现场终端、队列网站和 QQ Bot 当前运行的版本，以及与服务端配置的最新版本比较的结果。没有参数，始终返回 `200`。

```json
{
  "checked_at": 1784681000000,
  "components": {
    "terminal": {
      "name": "现场终端",
      "current_version": "0.13.6",
      "latest_version": "0.13.6",
      "status": "LATEST",
      "updated_at": 1784680996000
    },
    "website": {
      "name": "队列网站",
      "current_version": "0.13.3",
      "latest_version": "0.13.4",
      "status": "UPDATE_AVAILABLE",
      "updated_at": 1784680000000
    },
    "bot": {
      "name": "QQ Bot",
      "current_version": null,
      "latest_version": "0.3.13",
      "status": "UNKNOWN",
      "updated_at": null
    }
  }
}
```

`current_version` 的来源各不相同：

- `terminal`：当前快照中的 `terminal.app_version`。`updated_at` 是这份快照的接收时间，即终端最近一次上传的时间，不是版本发生变化的时间。测试同步期间反映的是测试终端。
- `website` 和 `bot`：QQ Bot 通过 `POST /api/queue-bot/identity` 上报的 `website_version` 和 `bot_version`。Bot 上报自身版本，并从网站读取网站版本后一并上报；每次上报非空值都会刷新对应的 `updated_at`。

`latest_version` 取自服务端配置 `QUEUE_LATEST_TERMINAL_VERSION`、`QUEUE_LATEST_WEBSITE_VERSION` 和 `QUEUE_LATEST_BOT_VERSION`，未配置时使用服务端内置的默认值。

版本号按语义化版本解析：去掉首尾空白后不超过 32 个字符，可以带前缀 `v`，返回时去掉该前缀；无法解析的值按 `null` 返回。网站和 Bot 的版本在上报时已经过校验并按规范形式保存，终端的 `app_version` 在上传时不校验格式。比较时先比较主版本号、次版本号和修订号；三者相同时正式版高于预发布版，预发布标识按语义化版本规则逐段比较；构建元数据保留在返回的字符串中，但不参与比较。

`status` 取值：

- `UNKNOWN`：任一侧版本缺失或无法解析。
- `LATEST`：两者相同。
- `UPDATE_AVAILABLE`：当前版本低于最新版本。
- `AHEAD`：当前版本高于最新版本。

版本状态只用于提示。`terminal.app_version` 是可选字段，缺失或不是有效的语义化版本时只会得到 `UNKNOWN`，不影响同步和排队操作；但它不是字符串、去掉首尾空白后超过 32 个字符或含有不可打印字符时，整份快照上传会以 `400` 被拒绝。管理后台的版本不在此接口中报告。随附的 Nginx 示例配置会为这个路径额外添加一个 `Cache-Control: no-store` 响应头。

### 健康检查

`GET /healthz`

```json
{"status":"ok","service":"maimai-queue-status"}
```

服务端打开 SQLite 数据库执行一次查询，并检查所需数据表是否都具备当前版本要求的列，通过时返回 `200`。数据库无法打开或查询失败时返回 `503`，`error` 为 `database_unavailable`；有数据表缺少必需的列时返回 `503`，`error` 为 `database_schema_not_ready`：

```json
{"status":"error","service":"maimai-queue-status","error":"database_schema_not_ready"}
```

健康检查只反映服务进程和数据库结构是否就绪，不检查令牌配置、头像目录、是否已有快照或终端是否在线；令牌配置无效、私有接口返回 `503` 时它仍然返回 `200`。响应使用 `status` 和 `error` 字段，不同于其他接口的 `ok` 和 `error`。随附的 Nginx 示例配置把它对外发布为 `/queue-api-healthz`。

### 玩家头像

`GET /api/player-avatars/<avatar_reference>.webp`

返回一份玩家资料的头像，内容是服务端处理后统一生成的 512 × 512 WebP 图片，`Content-Type` 为 `image/webp`。`avatar_reference` 必须是 64 位小写十六进制字符串。

接口不做鉴权，访问控制只依赖引用不可猜测：引用是每次上传头像时生成的 32 字节随机数，与 QQ、资料 UUID 和图片内容都无关。每次上传都会得到新的引用和新的地址；服务端没有删除旧头像文件的逻辑，更换头像或删除玩家资料后，旧地址仍然可以读取。

响应带有 `Cache-Control: public, max-age=31536000, immutable`，并且没有 `Pragma`。引用格式不符或文件不存在时返回 `404`，说明为 `头像不存在`；这条 `404` 同样带有上述长期缓存响应头。

客户端不需要自行拼接地址。玩家资料中的 `avatar_url` 就是完整地址，格式为 `<QUEUE_CORS_ORIGIN>/api/player-avatars/<avatar_reference>.webp`；没有配置 `QUEUE_CORS_ORIGIN` 时使用请求的主机地址，没有头像的资料 `avatar_url` 为 `null`。头像文件保存在 `QUEUE_PLAYER_AVATAR_DIRECTORY`，默认是数据库同级的 `avatars` 目录。

### 网站线上登记

网站用三个接口完成线上登记：按 QQ 查询玩家资料、提交加入排队的请求、查询终端的处理结果。服务端只保存一条待执行命令，登记由现场终端校验后建立。前两个接口不需要令牌，也不读取网页账户的会话；登录网页账户的玩家同样通过它们加入排队，网站只是预先填入本人的 QQ，`POST /api/player-account/queue-commands` 不接受 `JOIN_QUEUE`。两个 `POST` 接口的请求必须使用 `Content-Type: application/json`，请求体必须是 JSON 对象，否则返回 `400`。

`POST /api/queue-online/profile`

请求体只能包含 `qq` 一个字段，值为 5 至 12 位数字的字符串：

```json
{"qq":"12345678"}
```

服务端依次检查：已有快照（否则 `404`）、终端在线（`503`）、与服务端同步已开启（`503`）、不处于闭店收尾（`409`）、现场规则允许线上登记（`503`）。登记排队是否开放不在这一步检查，而是通过 `registration_open` 返回。随后在当前玩家资料范围内按 QQ 查找资料，测试同步期间查找的是隔离保存的测试资料。该 QQ 没有关联玩家资料时返回 `404` 和 `PROFILE_NOT_FOUND`；该 QQ 命中的资料只是另一份资料的旧别名，而后者尚未在现场终端保存这个 QQ 时，返回 `409` 和 `PROFILE_SYNC_PENDING`。

成功时返回：

- `queue_id`、`machine_configuration_revision`：查询时的队列批次和机台配置修订号，提交加入请求时原样带回。
- `profile`：该 QQ 对应玩家资料的完整记录，包含“玩家资料字段”一节除 `recent_usage_at` 以外的全部字段，以及 `public_player_id`（六位玩家编号）、`avatar_url`、`web_account_bound`、`terminal_editing_allowed`、`visited_venues_public` 和 `web_profile_revision`。
- `existing_registration`：该 QQ 当前的登记，没有时为 `null`。包含 `registration_id`、`display_id`、`machine_id`、`position`（`PLAYING` 或 `WAITING`）、`position_index`（等待位置的序号，处于游玩位置时为 `null`）和 `online_registration_pending_check_in`。
- `registration_open`、`business_hours`：与当前队列响应中的同名字段相同。
- `terminal`：只有 `online` 一个字段。
- `machine_groups`、`default_machine_group_id`：机台分组。
- `machines`：机台数组。每项包含 `id`、`stable_id`、`group_id`、`name`、`operational`、`registration_count`、`estimated_wait_minutes`、`configuration`、`capacity`、`available` 和 `unavailable_reason`。`estimated_wait_minutes` 取自机台的 `new_registration_estimated_wait_minutes`；机台已停止使用或登记已满 20 份时 `available` 为 `false`，`unavailable_reason` 为 `机台已停止使用` 或 `登记已满`，否则为 `null`。
- `capabilities`：`online_registration` 和固定为 `true` 的 `terminal_is_source_of_truth`。这里的 `online_registration` 只表示终端在线、与服务端同步已开启且现场规则允许线上登记，不包含 `registration_open`，与当前队列响应中的同名字段不同。

当前快照由旧版本终端上传而缺少相应字段时，`machine_configuration_revision` 按 `1` 返回，机台分组按默认单分组返回，机台的 `stable_id` 和 `configuration` 使用默认值。

该接口不校验登录状态，服务端也不对它做请求限流。知道某个 QQ 的调用方可以取得对应玩家资料的完整记录，包括资料 UUID、性别、通知设置和使用记录；它只返回与该 QQ 对应的一份资料，不返回玩家资料库。

`POST /api/queue-online/join`

```json
{
  "request_id": "00000000-0000-4000-8000-000000000005",
  "qq": "12345678",
  "machine_id": "A",
  "preference": "OPEN_TO_JOIN",
  "expected_queue_id": "00000000-0000-4000-8000-000000000003",
  "expected_machine_configuration_revision": 4,
  "expected_machine_stable_id": "0123456789abcdef0123456789abcdef"
}
```

- `request_id`：UUID，必填，同时作为命令编号。
- `qq`：5 至 12 位数字的字符串，必填。
- `machine_id`：目标机台编号，服务端去掉首尾空白并转为大写后，必须是当前快照中的机台。
- `preference`：`SOLO` 或 `OPEN_TO_JOIN`，按下面的规则提供。
- `expected_queue_id`、`expected_machine_configuration_revision`：查询玩家资料时得到的队列批次和机台配置修订号，必须同时提供或同时省略。
- `expected_machine_stable_id`：查询时所选机台的 `stable_id`，可以省略，只能与前两个字段一起提供。
- `also_queues_at_paired_machine`：布尔值，可以省略。创建登记时是否勾选“允许系统在两台机台之间安排登记”，规则见“自动在两台机台之间安排登记”。网站在所选机台属于一对机台时总是提交它。

请求体出现其他字段时返回 `400`。三个 `expected_*` 字段按键是否出现判断，值为 `null` 也视为已提供。网站会提交全部三个字段；旧版本服务端以 `400` 拒绝 `expected_machine_stable_id` 时，网站去掉它重试一次。

服务端创建一条 `QUEUE_OPERATION` 命令，操作为 `JOIN_QUEUE`，来源为 `WEBSITE_REMOTE`，并交给持有当前快照的终端。创建成功返回 `202`。用相同的 `request_id` 重复提交内容完全相同的请求时返回 `200` 和已保存的命令，内容不同则返回 `409`；这项判断先于其他检查，因此即使终端已经离线，重复请求仍然返回原命令及其当前状态。

响应是命令对象，包含 `command_id`、`type`、`payload`、`status`、`created_at`、`claimed_at`、`claimed_terminal`、`claimed_instance`、`completed_at`、`result_source`、`result_detail` 和 `result_registration_id`。`payload` 包含 `queue_id`、`machine_configuration_revision`、`actor_qq`、`profile_id`、`operation`、`operation_source`、`machine_id`、`machine_stable_id`、确定后的 `preference` 和记录原始请求的 `_request`，因此这个未鉴权接口的响应中带有玩家资料 UUID。`_request` 包含 `operation_source`、`actor_qq`、`operation`、`machine_id`、`target_machine_id`、`preference`、`expected_context`（加入排队时固定为 `null`）和 `expected_join_context`（`null`，或包含 `queue_id`、`machine_configuration_revision` 和 `machine_stable_id`）。未登录的加入命令不带 `actor_profile_id`，所以不需要会话即可查询结果。

游玩偏好按以下规则确定：容量为 1 的机台一律使用 `SOLO`，不看提交的值；其他机台在资料默认偏好为 `ASK_EVERY_TIME` 时必须提供 `preference`，否则使用资料的默认偏好，提交的值与之不同时拒绝。

除请求格式错误外，服务端在以下情况拒绝请求：

- `404`：终端从未上传过快照；该 QQ 没有关联玩家资料（`PROFILE_NOT_FOUND`）。
- `409` `QUEUE_CONTEXT_CHANGED`：`expected_queue_id` 或 `expected_machine_configuration_revision` 与当前快照不一致，或 `machine_id` 当前对应机台的 `stable_id` 与 `expected_machine_stable_id` 不一致。此时需要重新查询玩家资料，不能把旧页面中的字母作用到重编号后的其他机台。
- `503`：终端离线；与服务端同步已关闭；现场规则不允许线上登记。
- `409`：处于闭店收尾；资料尚未完成 QQ 关联（`PROFILE_SYNC_PENDING`）；该玩家已有登记；登记排队没有开放；机台已停止使用；机台登记已满 20 份；提交的偏好与资料固定的默认偏好不同。
- `409` `PLAYER_OPERATION_SYNCING`：快照中还没有该玩家的登记，但同一玩家的线上加入或“使用移动设备登记”命令在 30 秒内刚被终端应用，且完成时间不早于当前快照的接收时间。
- `409`：当前终端上已有一条属于同一 QQ 或同一玩家资料的待执行命令，不限命令类型。
- `400`：`machine_id` 不在当前快照中，且没有提供 `expected_machine_stable_id`（提供时按 `QUEUE_CONTEXT_CHANGED` 处理）；资料默认偏好为 `ASK_EVERY_TIME` 而没有提供 `preference`。

`GET /api/queue-online/commands/<command_id>`

`command_id` 是创建命令时使用的 `request_id`。响应是不含 `payload` 的命令对象：

```json
{
  "command_id": "00000000-0000-4000-8000-000000000005",
  "type": "QUEUE_OPERATION",
  "status": "APPLIED",
  "created_at": 1784681000000,
  "claimed_at": 1784681002000,
  "claimed_terminal": "00000000-0000-4000-8000-000000000001",
  "claimed_instance": "00000000-0000-4000-8000-000000000006",
  "completed_at": 1784681003000,
  "result_source": "TERMINAL",
  "result_detail": "线上登记已加入机台 A 的等待顺序。",
  "result_registration_id": "a10f5015a2e85b12e20af507"
}
```

`status` 为 `PENDING`、`APPLIED` 或 `REJECTED`，拒绝原因位于 `result_detail`。`result_registration_id` 是线上加入成功后终端回报的登记编号。`result_source` 说明结果由谁给出，命令尚未完成时为 `null`：

- `TERMINAL`：终端的处理结果。
- `SERVER_TIMEOUT`：命令在 `QUEUE_COMMAND_TIMEOUT_SECONDS`（默认 `600` 秒）内没有得到处理，被服务端拒绝。
- `SYNC_DISABLED`：终端关闭了与服务端同步，尚未被领取的命令被服务端拒绝。
- `SERVER_MIGRATION`：测试数据与正式数据之间发生切换，或终端接管、更换运行实例后 `queue_id` 或各机台字母对应的 `stable_id` 已经变化，原命令被服务端拒绝。

因超时被拒绝的命令不一定是最终状态：此前已领取该命令的终端实例之后仍可以提交实际结果，状态可能由 `REJECTED` 变为 `APPLIED`。已完成的命令保留 `QUEUE_COMMAND_RETENTION_SECONDS`（默认 `2592000` 秒），之后被删除，再查询时返回 `404`。

这个接口只能读取来源为 `WEBSITE_REMOTE` 的命令。未登录的线上加入命令凭命令编号即可读取。登录玩家通过网页账户提交的本人排队操作也在这里查询结果，但请求必须带有同一份玩家资料的会话 Cookie `maimai_q_session`。命令不存在、来源不是网站，或会话不属于命令所有者时，统一返回 `404`，说明为 `没有找到这条命令`；`command_id` 不是 UUID 时返回 `400`。

这组接口不返回完整玩家资料库，查询和加入接口也不能修改已有登记。只有终端在线、与服务端同步开启、不处于闭店收尾、现场规则允许线上登记、登记排队开放、目标机台正常使用且未满，并且该玩家没有登记也没有待执行命令时，服务端才接受新的加入请求；终端执行时按最新状态再次校验。线上登记进入等待末端后带有待签到状态，并按正常登记参与公开位置和等待时间估算。签到时限为 30 分钟，从登记的 `online_check_in_started_at` 开始计算；该时间默认是终端实际建立登记的时间，终端重新开始待签到计时后会更新。到期仍未签到时终端会自动移除，如果登记更早轮到进入游玩位置，也会立即移除。服务器不独立计时或修改队列。

## 网页账户接口

网页账户让玩家在公开网站登录自己的玩家资料，修改资料、上传头像并管理本人登记。一个账户只对应一份玩家资料，并且只能由现场终端发起的一次性会话建立：为已有资料生成绑定二维码，或生成建档二维码，让玩家在手机上新建资料并同时绑定账户。服务端不提供自助注册、找回密码或解除绑定的网页接口。

这组接口有三种互相独立的鉴权方式：

- 终端创建会话的两个接口使用终端同步令牌和其他终端接口相同的请求头。schema 8 请求必须携带 `X-Queue-Venue-ID`，缺失或与服务端当前机厅不符时返回 `409` 和 `VENUE_ID_REQUIRED` 或 `VENUE_MISMATCH`。
- 手机页面在登录前只凭 URL 路径中的一次性令牌访问绑定和建档接口。令牌由字母、数字、`_` 和 `-` 组成，长度为 `32` 至 `128`。
- 登录后的接口使用会话 Cookie，修改数据的请求还必须携带 `X-CSRF-Token`。

终端同步令牌、Bot 令牌和管理令牌都不参与浏览器流程。

除头像上传使用 `multipart/form-data` 外，请求体都是 JSON 对象，完整请求体限制为 `1 MiB`。请求体不是 JSON 对象时返回 `400`，不带 `code`。错误响应为 `{"ok": false, "error": "<说明>"}`，部分错误附带 `code`、`field` 或 `retry_after`；客户端应按 HTTP 状态码和 `code` 判断，不要匹配 `error` 文本。两个终端接口、读取绑定会话、读取建档会话、本人排队操作及其结果查询的成功响应不含 `ok` 字段，只能按状态码判断。响应中的时间字段（如 `created_at`、`expires_at`、`received_at`、`updated_at`）都是毫秒时间戳。

除头像文件外，所有响应包含 `Cache-Control: no-store, max-age=0`。服务端配置了 `QUEUE_CORS_ORIGIN` 时，响应带有 `Access-Control-Allow-Credentials: true`，允许的方法包含 `PATCH`，允许的请求头包含 `X-CSRF-Token`；网站以携带 Cookie 的方式调用账户接口。

### 账户对象

登录、完成绑定、完成建档、读取当前账户以及修改资料、头像和密码的响应都包含同一结构的 `account`：

```json
{
  "account_id": "11111111-2222-4333-8444-555555555555",
  "profile": {
    "profile_id": "37e41698-46f8-489b-92dc-d29c71f00f7d",
    "public_player_id": "123456",
    "avatar_url": null,
    "nickname": "示例玩家",
    "qq_number": "12345678",
    "gender": "UNDISCLOSED",
    "default_preference": "OPEN_TO_JOIN",
    "qq_visibility": "PUBLIC_WEBSITE",
    "notification_enabled": true,
    "notify_queue_changes": true,
    "notify_playing_position": false,
    "notify_online_check_in": true,
    "notify_absence": true,
    "notify_machine_status": false,
    "setup_complete": true,
    "profile_revision": 8,
    "web_account_bound": true,
    "terminal_editing_allowed": false,
    "visited_venues_public": true,
    "web_profile_revision": 1,
    "updated_at": 1784681000000
  },
  "venue": {
    "id": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
    "code": "ABCD2345",
    "name": "示例机厅"
  }
}
```

`setup_complete` 表示资料的 `setup_version` 大于 `0`。`profile_revision` 是修改资料和上传头像时要回传的 `expected_profile_revision`。`venue` 是这份资料所属的机厅。账户对象只返回给账户本人。

### 会话与 CSRF

登录、完成绑定和完成建档成功时，服务端建立一个网页会话并设置两个 Cookie：

- `maimai_q_session`：会话令牌，带有 `HttpOnly`。
- `maimai_q_session_csrf`：CSRF 令牌，页面脚本可以读取。

两个 Cookie 均为 `SameSite=Lax`、`Path=/`，不设置 `Domain`，`Max-Age` 等于会话有效期；除非 `QUEUE_PLAYER_COOKIE_SECURE` 设为 `0`、`false` 或 `no`，否则带有 `Secure`。CSRF 令牌同时在响应 JSON 的 `csrf_token` 字段中返回。服务端只保存两个令牌的 SHA-256 摘要。

会话有效期由 `QUEUE_PLAYER_SESSION_TTL_SECONDS` 决定，默认 30 天，最短 5 分钟，从建立时起算，使用过程中不会延长。同一账户可以同时存在多个会话，新登录不会使其他会话失效。会话只在账户对应的资料仍处于已绑定状态时有效。

修改资料、上传头像、修改密码、请求本人排队操作和退出登录必须携带 `X-CSRF-Token`。它的值必须与 `maimai_q_session_csrf` Cookie 相同，并且是该会话建立时签发的令牌，否则返回 `403` 和 `CSRF_TOKEN_INVALID`。读取类接口、登录、查询 QQ 是否已绑定以及凭一次性令牌访问的接口不需要这个请求头。

需要登录的接口在 Cookie 缺失、会话过期或已撤销时返回 `401` 和 `ACCOUNT_LOGIN_REQUIRED`。只有 `GET /api/player-account` 会在返回 `401` 的同时清除两个 Cookie。

服务端不会定期清理过期或已撤销的网页会话、绑定会话和尝试次数记录；只有二维码建档会话按保留期删除。

### 密码与尝试次数限制

账户密码必须是 `8` 至 `128` 个字符的字符串，UTF-8 编码不超过 `512` 字节，并且不能只由空白字符组成；没有字符种类要求。不符合时返回 `400`，`code` 为 `PASSWORD_REQUIRED`、`PASSWORD_TOO_SHORT`、`PASSWORD_TOO_LONG` 或 `PASSWORD_INVALID`，`field` 固定为 `password`。登录接口不对提交的密码做长度校验。服务端使用 scrypt（`n=16384`、`r=8`、`p=1`）和每个密码独立生成的 16 字节随机盐保存摘要，不保存明文。

尝试次数按验证对象计数，不按客户端地址计数：

- 登录：按 QQ 计数。QQ 格式有效但没有可登录的账户或密码错误时计一次。
- 查询 QQ 是否已绑定：按 QQ 计数。每次通过格式校验的查询都计一次。
- 完成绑定：按绑定令牌计数。绑定会话无效、已使用或已失效等状态错误计一次。
- 修改 QQ：按账户计数。当前密码缺失或错误时计一次。
- 修改密码：按账户计数。当前密码错误时计一次。

同一对象在 `QUEUE_PLAYER_AUTH_LIMIT_WINDOW_SECONDS`（默认 `900` 秒）内累计达到 `QUEUE_PLAYER_AUTH_LIMIT_FAILURES`（默认 `5` 次）后，被限制 `QUEUE_PLAYER_AUTH_LIMIT_BLOCK_SECONDS`（默认 `900` 秒）。时间窗口和限制时长最短 `60` 秒，次数最少 `2` 次，配置值低于下限时按下限处理。限制期间的请求返回 `429`：

```json
{"ok": false, "code": "AUTH_RATE_LIMITED", "error": "尝试次数过多，请稍后再试。", "retry_after": 840}
```

`retry_after` 是剩余秒数，只在 JSON 中返回，响应不带 `Retry-After` 头。验证成功会清除对应计数。查询 QQ 是否已绑定使用独立的额度 `QUEUE_PLAYER_AUTH_LOOKUP_LIMIT_REQUESTS`（默认 `30` 次），成功的查询同样计数且不会清零，但不占用登录的失败次数。读取绑定会话、二维码建档的接口以及登录后的读取接口没有次数限制。

### 创建绑定会话

`POST /api/queue-terminal/player-bindings`，JSON 请求体为 `{"profile_id":"<资料 UUID>"}`

现场终端为一份已有玩家资料创建一次性绑定会话，并把返回的 `binding_url` 显示为二维码。请求使用终端同步令牌和终端运行实例请求头，请求体中的其他字段被忽略。成功返回 `201`：

```json
{
  "binding_id": "22222222-3333-4444-8555-666666666666",
  "binding_token": "<一次性令牌>",
  "binding_url": "https://example.com/queue-status?account_binding=<一次性令牌>",
  "expires_at": 1784681600000,
  "profile": {
    "profile_id": "37e41698-46f8-489b-92dc-d29c71f00f7d",
    "public_player_id": "123456",
    "nickname": "示例玩家",
    "qq_number": "12345678"
  }
}
```

`binding_url` 是 `QUEUE_PLAYER_ACCOUNT_SITE_URL` 加上 `account_binding` 查询参数；该变量为空时使用 `QUEUE_PUBLIC_SITE_URL`，后者也为空时使用 `<QUEUE_CORS_ORIGIN>/queue-status`，三者都为空时返回 `503` 和 `PLAYER_ACCOUNT_SITE_NOT_CONFIGURED`。会话有效期由 `QUEUE_PLAYER_BINDING_TTL_SECONDS` 决定，默认 `600` 秒，最短 `60` 秒。绑定完成之前，终端上传的快照只要包含这份仍未绑定的资料，服务端就会作废它尚未使用的绑定会话，此后读取和完成绑定都返回 `410` 和 `PLAYER_BINDING_EXPIRED`，见“账户与会话失效”。服务端只保存令牌的 SHA-256 摘要。

服务端依次检查：

- 请求必须来自当前队列快照对应的终端运行实例，否则返回 `409` 和 `STALE_TERMINAL_INSTANCE`。服务端还没有队列快照时也返回这个错误，而不是 `404`。
- 当前快照是测试数据时返回 `409` 和 `TEST_DATA_ACCOUNT_BINDING_UNAVAILABLE`。
- 资料必须属于当前机厅，并且在终端当前资料库（终端最近一次上传的资料集合）中，否则返回 `404`。
- 资料尚未完成必填设置或没有 QQ 时返回 `409` 和 `PLAYER_PROFILE_INCOMPLETE`。
- 资料已经绑定网页账户时返回 `409` 和 `PLAYER_ACCOUNT_ALREADY_BOUND`。

与二维码建档不同，创建绑定会话不检查终端在线时限，也不检查 `website_remote_enabled`。

这个接口不是幂等的：每次成功调用都会作废同一份资料此前尚未使用的绑定会话，再签发新的会话。资料未标记为已绑定但服务端残留账户记录时，服务端先撤销该账户的会话并删除账户，再签发绑定会话。

### 读取绑定会话

`GET /api/player-account/bindings/<binding_token>`

手机打开绑定二维码后，页面凭令牌读取要绑定的资料和机厅，再让玩家设置密码。这个接口只读，不消耗会话：

```json
{
  "binding_id": "22222222-3333-4444-8555-666666666666",
  "expires_at": 1784681600000,
  "profile": {
    "profile_id": "37e41698-46f8-489b-92dc-d29c71f00f7d",
    "public_player_id": "123456",
    "nickname": "示例玩家",
    "qq_number": "12345678"
  },
  "venue": {
    "id": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
    "code": "ABCD2345",
    "name": "示例机厅"
  }
}
```

响应包含完整 QQ 和资料 UUID，持有令牌即可读取。

状态按以下顺序判断：

- `404`：令牌格式无效或不存在，不带 `code`。
- `409` 和 `PLAYER_BINDING_USED`：这次绑定已经完成。
- `410` 和 `PLAYER_BINDING_EXPIRED`：会话已过期或已被作废。
- `409` 和 `PLAYER_ACCOUNT_ALREADY_BOUND`：资料已经绑定网页账户。
- `409` 和 `PLAYER_PROFILE_INCOMPLETE`：资料尚未完成必填设置或没有 QQ。

### 完成绑定

`POST /api/player-account/bindings/<binding_token>/complete`，JSON 请求体为 `{"password":"<账户密码>"}`

为资料设置账户密码、建立账户并登录当前浏览器。服务端不接收确认密码，两次输入是否一致由页面自行比较。成功返回 `201`，响应包含 `ok`、`account` 和 `csrf_token`，并设置会话 Cookie。

服务端先校验密码格式，再检查该令牌的尝试次数限制和会话状态。状态错误与读取绑定会话相同，每次都计入该令牌的尝试次数。

成功时在同一事务中完成以下修改：

- 建立账户并保存密码摘要。
- 把资料标记为已绑定，同时把 `terminal_editing_allowed` 设为 `false`、`visited_venues_public` 设为 `true`、`qq_visibility` 设为 `PUBLIC_WEBSITE`，不保留原值；`profile_revision` 和 `web_profile_revision` 各加 `1`。
- 把绑定会话标记为已使用。再次提交返回 `409` 和 `PLAYER_BINDING_USED`。

完成绑定不读取队列快照，不检查终端是否在线、与服务端同步是否开启或当前是否为测试数据；终端运行实例和测试数据只在创建绑定会话时检查。资料变更在终端下一次同步云端资料时到达现场终端。

### 创建建档会话

`POST /api/queue-terminal/player-profile-sessions`，JSON 请求体为 `{"request_id":"<UUID>"}`

现场终端创建一次性建档会话，并把返回的 `profile_creation_url` 显示为二维码，供玩家在手机上新建玩家资料并同时绑定网页账户。请求体只能包含 `request_id`，它同时是会话编号和幂等键；终端每次请求生成新的 UUID。请求体不是只含 `request_id` 的对象，或 `request_id` 不是 UUID 时返回 `400`，不带 `code`。鉴权和请求头与创建绑定会话相同。

新建会话返回 `201`；同一终端运行实例重复发送相同的 `request_id` 时返回 `200` 和已保存的会话：

```json
{
  "session_id": "33333333-4444-4555-8666-777777777777",
  "status": "OPEN",
  "created_at": 1784681000000,
  "expires_at": 1784681600000,
  "profile_id": null,
  "profile_creation_url": "https://example.com/queue-status?player_profile_creation=<一次性令牌>",
  "venue": {
    "id": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
    "code": "ABCD2345",
    "name": "示例机厅"
  }
}
```

`status` 为 `OPEN` 或 `COMPLETED`，`profile_id` 在完成前为 `null`。会话令牌只出现在 `profile_creation_url` 中，地址的组成方式与 `binding_url` 相同。重复请求原样返回已保存的会话，不重新检查它的状态和有效期。相同的 `request_id` 如果由另一台终端或另一个运行实例创建，返回 `409` 和 `PLAYER_PROFILE_CREATION_INSTANCE_CONFLICT`。

新建会话时服务端依次检查：

- 服务端已配置网页地址，否则返回 `503` 和 `PLAYER_ACCOUNT_SITE_NOT_CONFIGURED`。
- 已有队列快照，否则返回 `404`；终端在线，否则返回 `503`。
- 请求来自当前快照对应的终端，否则返回 `409`；并且来自同一运行实例，否则返回 `409` 和 `STALE_TERMINAL_INSTANCE`。
- 当前快照不是测试数据，否则返回 `409` 和 `TEST_DATA_PROFILE_CREATION_UNAVAILABLE`。
- 快照的 `website_remote_enabled` 为 `true`，否则返回 `409` 和 `WEBSITE_SYNC_DISABLED`。

会话记录创建它的终端、运行实例编号、实例代次和当时的资料作用域。有效期由 `QUEUE_PLAYER_PROFILE_CREATION_TTL_SECONDS` 决定，默认 `600` 秒，最短 `60` 秒。已完成或已过期的会话在 `QUEUE_PLAYER_PROFILE_CREATION_SESSION_RETENTION_SECONDS`（默认 `86400` 秒）之后删除。

云端同步不可用、与服务端同步已关闭、未配置连接或当前不允许联网时，终端不调用这个接口，而是直接打开本机资料编辑页；这样建立的资料不绑定网页账户。

### 读取建档会话

`GET /api/player-profile-creation/sessions/<session_token>`

手机打开建档二维码后，页面凭令牌确认会话仍然可用并读取机厅。响应与创建建档会话返回的会话对象相同，`status` 为 `OPEN`。

- `404`：令牌格式无效、不存在，或会话已在保留期后删除，不带 `code`。
- `409` 和 `PLAYER_PROFILE_CREATION_USED`：会话已经完成。
- `410` 和 `PLAYER_PROFILE_CREATION_EXPIRED`：会话已过期。

这个接口不检查终端状态。创建会话的终端运行实例是否仍然有效、终端是否在线、与服务端同步是否开启，都只在提交时检查。

### 完成建档

`POST /api/player-profile-creation/sessions/<session_token>/complete`

```json
{
  "nickname": "示例玩家",
  "gender": "UNDISCLOSED",
  "default_preference": "OPEN_TO_JOIN",
  "qq_number": "12345678",
  "password": "<账户密码>",
  "password_confirmation": "<账户密码>",
  "notification_enabled": true,
  "notify_queue_changes": true,
  "notify_playing_position": false,
  "notify_online_check_in": true,
  "notify_absence": true,
  "notify_machine_status": false
}
```

请求体必须恰好包含这 12 个字段；缺少字段或带有其他字段都返回 `400`。`nickname` 去除首尾空白后为 `1` 至 `18` 个字符；`gender` 为 `MALE`、`FEMALE` 或 `UNDISCLOSED`；`default_preference` 为 `SOLO`、`OPEN_TO_JOIN` 或 `ASK_EVERY_TIME`；`qq_number` 为 `5` 至 `12` 位数字。两个密码字段都要符合密码规则并且相同，不同时返回 `400` 和 `PASSWORD_MISMATCH`。请求不能指定 `qq_visibility`。

成功返回 `201`，响应包含 `ok`、`session_id`、新资料的 `profile_id`、`account` 和 `csrf_token`，并设置会话 Cookie。

字段校验通过后，服务端依次检查：

- 会话状态，错误与读取建档会话相同。
- 当前快照仍来自创建会话的终端和同一运行实例，否则返回 `409` 和 `PLAYER_PROFILE_CREATION_STALE`。终端重启后旧二维码因此失效。
- 终端在线，否则返回 `503`。
- 当前快照不是测试数据且 `website_remote_enabled` 为 `true`，否则返回 `409` 和 `PLAYER_PROFILE_CREATION_UNAVAILABLE`。
- 快照对应的资料作用域与创建会话时相同，否则返回 `409` 和 `PLAYER_PROFILE_CREATION_STALE`。
- 作用域内没有资料使用相同的 QQ 或相同的昵称（不区分大小写），否则返回 `409` 和 `QQ_ALREADY_USED` 或 `NICKNAME_ALREADY_USED`，不带 `field`。昵称和 QQ 分别与不同资料冲突时只返回其中一个，客户端不应假定哪一个优先。

查重范围是该作用域内保存的全部资料，不限于终端当前资料库；不检查昵称是否与当前队列中的登记冲突，不验证 QQ 归属，也没有次数限制。

成功时在同一事务中建立玩家资料和账户，并把会话标记为 `COMPLETED`。新资料分配六位玩家编号，`setup_version` 和 `profile_revision` 为 `1`，使用次数为 `0`，`qq_visibility` 为 `PUBLIC_WEBSITE`，`web_account_bound` 为 `true`，`terminal_editing_allowed` 为 `false`，`visited_venues_public` 为 `true`，`web_profile_revision` 为 `1`。

新资料此时只存在于服务端。服务端把它记入待同步索引：在终端上传这份资料之前，终端的快照不会把它移出当前资料库，玩家可以立即登录。终端通过同步云端资料取得它，上传后索引项删除。如果它的昵称或 QQ 与终端本机的其他资料冲突，或昵称与当前队列冲突，终端暂不接受这份资料。

### 查询 QQ 是否已绑定

`POST /api/player-account/login-lookup`，JSON 请求体为 `{"qq":"<QQ号>"}`

分步登录的第一步。请求体只能包含 `qq`。返回 `{"ok": true, "bound": true}`；`bound` 只表示这个 QQ 当前是否有可以登录的网页账户，响应不包含昵称、六位玩家编号、资料 UUID 或机厅信息。页面据此决定是否显示密码输入框。

- `400`：请求体不是只含 `qq` 的对象。
- `400` 和 `QQ_INVALID`：`qq` 不是 `5` 至 `12` 位数字。
- `429` 和 `AUTH_RATE_LIMITED`：该 QQ 的查询额度已用完。

每次通过格式校验且未被限制的查询都计入额度。达到额度的那一次仍返回结果，之后的请求在限制期内返回 `429`。

### 登录

`POST /api/player-account/login`，JSON 请求体为 `{"qq":"<QQ号>","password":"<账户密码>"}`

验证通过返回 `200`，响应包含 `ok`、`account` 和 `csrf_token`，并设置会话 Cookie。

QQ 格式无效、密码不是字符串、QQ 没有可登录的账户和密码错误都返回 `401` 和 `ACCOUNT_LOGIN_FAILED`，提示文本相同，不区分原因。QQ 格式有效且密码是字符串的失败尝试计入该 QQ 的失败次数，被限制后返回 `429` 和 `AUTH_RATE_LIMITED`；登录成功清除计数。

账户可以登录的条件是：资料处于已绑定状态，并且在终端当前资料库中，或是尚待终端上传的二维码建档资料。已建立的会话不再检查后一条件：资料离开当前资料库后不能重新登录，但原有会话在到期或被撤销前继续可用。

### 读取当前账户

`GET /api/player-account`

返回 `ok`、`account` 和 `csrf_token`。网站在加载时用它判断浏览器是否已经登录。`csrf_token` 是请求所带 `maimai_q_session_csrf` Cookie 的值，Cookie 缺失时为空字符串，服务端不会重新签发。

未登录时返回 `401` 和 `ACCOUNT_LOGIN_REQUIRED`，并清除两个 Cookie。

### 修改玩家资料

`PATCH /api/player-account/profile`

```json
{
  "expected_profile_revision": 8,
  "nickname": "新昵称",
  "qq_visibility": "TERMINAL_ONLY",
  "terminal_editing_allowed": true
}
```

`expected_profile_revision` 必填，取自账户对象的 `profile_revision`。请求至少包含一个可修改字段，只有提交的字段会被修改。可修改字段为 `nickname`、`gender`、`default_preference`、`qq_number`、`qq_visibility`、六个通知字段、`terminal_editing_allowed` 和 `visited_venues_public`，取值规则与完成建档相同，`qq_visibility` 为 `TERMINAL_ONLY` 或 `PUBLIC_WEBSITE`。把 `qq_number` 改为不同的值时必须同时提交 `current_password`。请求包含其他字段时返回 `400`；密码、头像、六位玩家编号、使用记录和绑定状态不能通过这个接口修改。

成功返回 `200`，响应包含 `ok`、`changed`、更新后的 `account` 和 `sync_status`。提交的值与现有资料完全相同时，`changed` 为 `false`，`sync_status` 为 `CURRENT`，版本号不变。有实际变化时 `profile_revision` 和 `web_profile_revision` 各加 `1`，`sync_status` 为 `WAITING_FOR_TERMINAL`。这个接口不检查终端是否在线或与服务端同步是否开启。

- `400`：字段取值无效，不带 `code`。
- `401` 和 `ACCOUNT_LOGIN_REQUIRED`；`403` 和 `CSRF_TOKEN_INVALID`。
- `409` 和 `PLAYER_PROFILE_CHANGED`：`expected_profile_revision` 与当前 `profile_revision` 不一致，需要重新读取账户后再提交。
- 修改 QQ 时：`429` 和 `AUTH_RATE_LIMITED`；`403` 和 `CURRENT_PASSWORD_INVALID`，`field` 为 `current_password`；`409` 和 `QQ_ALREADY_USED`，`field` 为 `qq_number`，表示同一作用域内已有其他资料使用该 QQ。
- `409` 和 `NICKNAME_ALREADY_USED`，`field` 为 `nickname`：同一作用域内的其他资料使用了相同昵称，或当前队列中有一份不属于本人资料的登记使用了该昵称，比较时不区分大小写。

### 上传头像

`POST /api/player-account/avatar`，请求体为 `multipart/form-data`

表单必须恰好包含文本字段 `expected_profile_revision` 和文件字段 `avatar`。这个路径的完整请求体上限为 `8 MiB + 256 KiB`，超过时返回 `413`；图片文件本身不能超过 `8 MiB`，像素总数不能超过 `25000000`。反向代理需要为这个路径单独放宽请求体限制。

服务端把图片居中裁切并缩放为 `512 × 512`，重新编码为 WebP 后保存，不保留原始文件。文件以随机生成的 64 位十六进制引用命名，与图片内容和玩家身份无关。成功返回 `200`，响应包含 `ok`、`changed`（固定为 `true`）、更新后的 `account` 和 `sync_status`（固定为 `WAITING_FOR_TERMINAL`）；`profile_revision` 和 `web_profile_revision` 各加 `1`。替换头像不会删除旧文件。终端没有上传头像的接口，只通过同步云端资料取得 `avatar_url`。

会话和 CSRF 令牌在读取上传内容之前检查一次，写入时再检查一次。

- `401` 和 `ACCOUNT_LOGIN_REQUIRED`；`403` 和 `CSRF_TOKEN_INVALID`。
- `400`：表单字段不符合要求，或 `expected_profile_revision` 无效，不带 `code`。
- `400` 和 `AVATAR_TOO_LARGE`：文件为空或超过 `8 MiB`，或处理后的文件仍超过 `8 MiB`。
- `400` 和 `AVATAR_INVALID`：图片无法解码、尺寸无效或像素总数超限。
- `400` 和 `AVATAR_UNAVAILABLE`：服务端没有安装图片处理依赖。
- `409` 和 `PLAYER_PROFILE_CHANGED`：`expected_profile_revision` 与当前 `profile_revision` 不一致。

### 读取头像

`GET /api/player-avatars/<avatar_reference>.webp`

公开接口，不需要鉴权；`avatar_reference` 是 64 位小写十六进制引用。返回 `image/webp`，带有 `Cache-Control: public, max-age=31536000, immutable`，支持条件请求。引用格式无效或文件不存在时返回 `404` 和 JSON 错误；缓存头按路径设置，`404` 响应同样带有上述缓存头。

各接口返回的 `avatar_url` 由 `QUEUE_CORS_ORIGIN` 加上这个路径组成；未配置 `QUEUE_CORS_ORIGIN` 时使用请求的主机地址。头像文件保存在 `QUEUE_PLAYER_AVATAR_DIRECTORY`，默认是数据库同级的 `avatars` 目录。

### 修改密码

`POST /api/player-account/password`

```json
{
  "current_password": "<当前密码>",
  "new_password": "<新密码>",
  "new_password_confirmation": "<新密码>"
}
```

成功返回 `200`，响应包含 `ok`、`account` 和说明文本 `message`。当前会话继续有效，Cookie 不变；该账户的其他会话全部撤销。修改密码不改变资料版本号，也不向终端发送任何内容。

请求体校验先于会话检查：

- `400`：新密码或确认密码不符合密码规则，`field` 固定为 `password`。
- `400` 和 `PASSWORD_CONFIRMATION_MISMATCH`，`field` 为 `new_password_confirmation`：两次输入的新密码不同。
- `400` 和 `CURRENT_PASSWORD_REQUIRED`，`field` 为 `current_password`：没有提交当前密码。
- `401` 和 `ACCOUNT_LOGIN_REQUIRED`；`403` 和 `CSRF_TOKEN_INVALID`。
- `429` 和 `AUTH_RATE_LIMITED`。
- `403` 和 `CURRENT_PASSWORD_INVALID`，`field` 为 `current_password`：当前密码错误。
- `400` 和 `PASSWORD_UNCHANGED`：新密码与当前密码相同。

### 退出登录

`POST /api/player-account/logout`

不需要请求体。Cookie 对应有效会话时必须携带 `X-CSRF-Token`；校验失败返回 `403` 和 `CSRF_TOKEN_INVALID`，此时不撤销会话，也不清除 Cookie。校验通过后服务端撤销当前会话，返回 `{"ok": true}` 并清除两个 Cookie；该账户的其他会话不受影响。没有有效会话时同样返回 `200` 并清除 Cookie。

### 本人排队状态

`GET /api/player-account/queue`

返回当前队列上下文和账户本人的登记，供页面显示本人排队并构造排队操作请求。只需要会话 Cookie。

```json
{
  "ok": true,
  "queue": {
    "queue_id": "44444444-5555-4666-8777-888888888888",
    "revision": 128,
    "machine_configuration_revision": 3,
    "received_at": 1784681000000,
    "terminal_online": true,
    "remote_actions": true,
    "queue_rules": {
      "allow_defer_one_round": true,
      "allow_temporary_leave": true,
      "allow_online_registration": true
    },
    "machines": []
  },
  "registrations": [
    {
      "registration_id": "a10f5015a2e85b12e20af507",
      "display_id": "示例玩家",
      "machine_id": "A",
      "machine_stable_id": "0123456789abcdef0123456789abcdef",
      "machine_name": "示例机台",
      "machine_operational": true,
      "position": "WAITING",
      "position_index": 2,
      "estimated_wait_minutes": 24,
      "estimated_wait_uncertain": false,
      "preference": "OPEN_TO_JOIN",
      "fixed_pair": false,
      "fixed_pair_id": null,
      "deferred_once": false,
      "skips_one_turn": false,
      "temporarily_away": false,
      "temporary_away_skipped_turns": 0,
      "defer_one_round_used": false,
      "online_registration_not_played": false,
      "online_registration_pending_check_in": false,
      "machine_capacity": 2
    }
  ]
}
```

示例省略了 `machines` 的内容；每一项包含 `id`、`stable_id`、`group_id`、`name`、`operational`、`registration_count`、`estimated_wait_minutes`、`configuration`、`capacity`、`available` 和 `unavailable_reason`。`terminal_online` 表示最新快照的接收时间在 `QUEUE_ONLINE_TIMEOUT_SECONDS`（默认 `90` 秒）以内；`remote_actions` 表示终端在线且快照的 `website_remote_enabled` 为 `true`，为 `false` 时网站不提供操作入口。登记的 `position` 为 `PLAYING` 或 `WAITING`，处于游玩位置时 `position_index` 为 `null`。登记的 `estimated_wait_minutes` 和 `estimated_wait_uncertain` 与私有 Bot 接口“查询玩家”中的同名字段取值相同。`skips_one_turn`、`defer_one_round_used` 和 `online_registration_not_played` 取自公开快照中的登记，旧快照中没有时为 `false`，含义见“当前队列”。

本人登记按账户所属资料的 UUID 与当前登记绑定匹配，不按 QQ 或昵称匹配。服务端还没有队列快照时返回 `200`，`queue` 为 `null`，`registrations` 为空数组，并附带 `error` 说明。

### 请求本人排队操作

`POST /api/player-account/queue-commands`

```json
{
  "request_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
  "operation": "TRANSFER_MACHINE",
  "target_machine_id": "B",
  "expected_queue_id": "44444444-5555-4666-8777-888888888888",
  "expected_registration_id": "a10f5015a2e85b12e20af507",
  "expected_machine_id": "A",
  "expected_position": "WAITING",
  "expected_fixed_pair_id": null,
  "expected_absence_status": "NONE",
  "expected_temporary_away_skipped_turns": 0,
  "expected_pending_check_in": false,
  "expected_machine_configuration_revision": 3,
  "expected_machine_stable_id": "0123456789abcdef0123456789abcdef",
  "expected_target_machine_stable_id": "fedcba9876543210fedcba9876543210"
}
```

`request_id` 是命令编号和幂等键。`operation` 可以是 `DEFER_ONE_ROUND`、`CANCEL_DEFER_ONE_ROUND`、`SKIP_ONE_TURN`、`CANCEL_SKIP_ONE_TURN`、`TEMPORARILY_LEAVE`、`CANCEL_TEMPORARY_LEAVE`、`TRANSFER_MACHINE`、`CHANGE_PLAY_PREFERENCE`、`LEAVE_QUEUE` 或 `SET_PAIRED_MACHINE_QUEUEING`（需要 `also_queues_at_paired_machine`，见“自动在两台机台之间安排登记”）；这个接口不接受 `JOIN_QUEUE` 和 `CHECK_IN`。`TRANSFER_MACHINE` 需要 `target_machine_id`，`CHANGE_PLAY_PREFERENCE` 需要 `preference`（`SOLO` 或 `OPEN_TO_JOIN`）。

确认状态字段 `expected_queue_id`、`expected_registration_id`、`expected_machine_id`、`expected_position`、`expected_fixed_pair_id`、`expected_absence_status`、`expected_temporary_away_skipped_turns` 和 `expected_pending_check_in` 必须全部提交或全部省略。`expected_machine_configuration_revision` 和 `expected_machine_stable_id` 只能与完整的确认状态一起提交，`expected_target_machine_stable_id` 还只能用于 `TRANSFER_MACHINE`。`expected_absence_status` 为 `NONE`、`DEFER_ONE_ROUND`、`SKIP_ONE_TURN` 或 `TEMPORARILY_AWAY`，`expected_temporary_away_skipped_turns` 为 `0` 至 `3`。这些值取自本人排队状态；网站始终提交完整的确认状态、机台配置修订号和机台稳定身份。

身份由会话决定：服务端从账户读取 QQ 和资料 UUID，请求体出现 `actor_qq`、`machine_id` 或其他未列出的字段时返回 `400`。目标登记按资料 UUID 查找，账户在当前队列中必须恰好有一份登记。

成功创建返回 `202`；相同的 `request_id` 且请求内容完全一致时返回 `200` 和已有命令，内容不同时返回 `409`。响应是命令对象，包含 `command_id`、`type`（`QUEUE_OPERATION`）、`payload`、`status`、`created_at`、`claimed_at`、`claimed_terminal`、`claimed_instance`、`completed_at`、`result_source`、`result_detail` 和 `result_registration_id`。`payload` 是交给终端的命令内容，其中 `operation_source` 为 `WEBSITE_REMOTE`，`profile_identity_verified` 为 `true`，`_request.actor_profile_id` 是账户所属资料的 UUID。

创建命令前，服务端按最新快照依次校验：

- `404`：服务端还没有队列快照。
- `409` 和 `QUEUE_CONTEXT_CHANGED`：`expected_machine_configuration_revision` 与快照不一致。
- `503`：终端离线，或快照的 `website_remote_enabled` 为 `false`。
- `404` 和 `PROFILE_NOT_FOUND`：当前资料作用域内找不到账户所属的资料；`409` 和 `PROFILE_SYNC_PENDING`：该资料的 QQ 与账户不一致。
- `409`，不带 `code`：账户没有恰好一份登记；确认状态与快照不符；登记所在机台已停止使用而请求的不是退出排队或转至其他机台；待签到登记请求了退出排队以外的操作；现场规则不允许暂缓一次或暂时离开；登记处于游玩位置时请求暂缓一次、轮空一次或暂时离开；`online_registration_not_played` 为 `true` 时请求暂缓一次、轮空一次或暂时离开（`已签到的线上登记在游玩一轮之前不能延后。`）；`defer_one_round_used` 为 `true` 时请求暂缓一次（`这份登记已暂缓过一次，游玩一轮之后才能再次暂缓。`）；转至其他机台时登记处于游玩位置、目标机台已停止使用或登记已满、目标机台容量为 1 而登记属于固定组合；所在机台容量为 1 时修改本次偏好。
- `400`：目标机台不存在或与当前机台相同，或修改本次偏好时缺少 `preference`。
- `409`：同一 QQ 或同一资料已有一条待终端处理的命令，不限于排队操作命令。

命令以 `PENDING` 保存，只由当前快照对应的终端执行。终端重新校验队列批次、机台配置修订号、登记状态和现场规则后才修改队列。终端只接受带有 `profile_identity_verified` 的非加入类 `WEBSITE_REMOTE` 命令；带有该标记时，终端不再要求本机资料的 QQ 与 `actor_qq` 相同。超过 `QUEUE_COMMAND_TIMEOUT_SECONDS`（默认 `600` 秒）仍为 `PENDING` 的命令由服务端拒绝，`result_source` 为 `SERVER_TIMEOUT`；终端关闭与服务端同步后，尚未领取的命令被拒绝，`result_source` 为 `SYNC_DISABLED`。已完成的命令在 `QUEUE_COMMAND_RETENTION_SECONDS`（默认 30 天）之后删除，此后相同的 `request_id` 会被当作新请求。

登录后加入排队仍使用 `POST /api/queue-online/join`。该接口不读取会话 Cookie，产生的命令不带 `profile_identity_verified` 和 `_request.actor_profile_id`，结果不需要登录即可查询。

### 查询本人操作结果

`GET /api/queue-online/commands/<command_id>`

与网站线上登记共用的结果查询接口。由 `POST /api/player-account/queue-commands` 创建的命令只对创建它的账户可见：请求必须带有该账户的会话 Cookie，不需要 `X-CSRF-Token`。响应是不含 `payload` 的命令对象，`status` 为 `PENDING`、`APPLIED` 或 `REJECTED`，处理说明位于 `result_detail`。

没有携带会话、会话已失效或属于其他账户时，返回与命令不存在相同的 `404`，不带 `code`，不会返回 `ACCOUNT_LOGIN_REQUIRED`。命令编号不是 UUID 时返回 `400`。网站提交操作后立即查询一次，此后约每 `1.5` 秒再查询一次，最多再查询 `30` 次。

### 网页修改回流终端

完成绑定、二维码建档、网页资料修改和头像上传都只写入服务端，不创建终端命令；除二维码建档在提交时要求终端在线并开启与服务端同步外，也不要求终端在线。修改资料和上传头像的响应用 `sync_status` 说明状态：`WAITING_FOR_TERMINAL` 表示修改已保存、尚待现场终端取回，`CURRENT` 表示本次请求没有改变任何值。

现场终端在与服务端同步开启且可以联网时，约每 `30` 秒读取一次 `GET /api/queue-terminal/profiles`。云端 `profile_revision` 更高或本机没有这个 UUID 时，终端接受该资料，保存 `avatar_url`、`web_account_bound`、`terminal_editing_allowed`、`visited_venues_public` 和 `web_profile_revision`，并在下一次 schema 8 快照中回显。资料的昵称或 QQ 与本机其他资料冲突，或昵称与当前队列冲突时，终端不接受。

终端回显之前，服务端按“网页账户字段”中的合并规则保留网页侧的值，较旧的终端快照不会覆盖网页修改。

### 账户与会话失效

- 退出登录撤销当前会话。
- 玩家修改密码撤销该账户的其他会话；管理后台重置密码撤销该账户的全部会话。
- 终端上传的资料被服务端认定为未绑定或首次出现时，服务端撤销该资料残留账户的全部会话，作废尚未使用的绑定会话，并删除账户。
- 资料在 QQ 冲突中被更高版本的资料取代，或作为历史重复资料被合并时，其账户按同样方式删除。
- 管理后台删除玩家资料完成后，服务端删除该资料的账户、绑定会话、建档会话和待同步索引，并撤销其会话。

账户删除后，原密码不能再登录；仍然存在的资料可以在现场终端重新生成绑定二维码。

## 私有 Bot 接口

所有请求使用：

```text
Authorization: Bearer <QUEUE_BOT_TOKEN>
```

`QUEUE_BOT_TOKEN` 必须与终端同步令牌不同，长度不少于 32 个 UTF-8 字节，只能配置在 Koishi 服务端。配置不满足要求时，本节所有接口返回 `503`，说明为 `服务器认证配置无效`；请求没有携带令牌或令牌不正确时返回 `401`，说明为 `Bot 认证失败`。Bot 请求不需要 `X-Device-ID`、`X-Queue-Schema-Version` 和 `X-Queue-Venue-ID`，服务端不读取这些请求头。管理后台使用另一个独立的管理令牌，见“管理后台接口”。

它是服务级凭据，不是某一名玩家的登录凭据。持有者可以：

- 读取完整玩家资料库，包括 QQ、性别、资料 UUID、六位玩家编号、头像地址、通知设置、使用记录和网页账户状态。
- 读取当前登记绑定及通知事件的全部 QQ 收件人。
- 以任意 QQ 的名义请求创建玩家资料修改命令和排队操作命令。
- 按命令编号读取任何终端命令及其完整内容，不限于 Bot 创建的命令。
- 改写服务端记录的 Bot QQ 以及 Bot 和网站的版本。

限制玩家只能查询和修改本人资料的是 Koishi 对 OneBot 会话身份的校验，因此 Bot 令牌不能交给玩家、浏览器或其他不受控客户端。

`POST` 和 `PATCH` 请求使用 `Content-Type: application/json`，请求体必须是 JSON 对象。错误响应为 `{"ok": false, "error": "<说明>"}`，只有请求排队操作的四种错误另带 `code`；Koishi 把 `error` 文本直接回复给玩家，不读取 `code`。时间字段都是毫秒时间戳。响应头和 `1 MiB` 的请求体限制与“公开接口”的“通用约定”相同。

除上报 Bot QQ 与版本、查询命令外，各接口都以服务端保存的当前队列快照为准：终端从未上传过快照时返回 `404`，说明为 `排队终端暂未同步`。当前快照是测试数据时，登记绑定、通知事件和玩家资料读取的都是隔离保存的测试数据，查询玩家和查询通知事件的响应带有 `test_data: true`，见“权威终端与同步方式”。本节接口不返回机厅名称和机厅 ID，Koishi 从公开的 `GET /api/queue-status` 读取机厅名称。

现场终端的“QQ Bot 联动”开关随快照上传为顶层字段 `onebot_sync_enabled`，缺省视为开启。当前快照中该字段为 `false` 时，除 `POST /api/queue-bot/identity` 外，所有 `/api/queue-bot/` 接口返回 `503`，说明为 `QQ Bot 联动已关闭`。服务端只按已保存的快照判断：通过管理后台修改这个开关时，要等终端应用并上传新的快照后才生效。

终端每次上传 `onebot_sync_enabled` 为 `false` 的快照时，服务器删除当前队列的通知收件关系，并拒绝该终端尚未领取的两类待执行命令：`UPDATE_PLAYER_PROFILE` 命令（不区分来源）和来源为 `QQ_BOT` 的命令。被拒绝的命令 `result_source` 为 `BOT_DISABLED`，`result_detail` 为 `QQ Bot 联动已关闭，这次操作没有执行。`；查询命令接口此时同样返回 `503`，Bot 要等联动重新开启后才能读到这个结果。终端已经领取的命令仍由终端处理，网站线上登记的命令不受影响。关闭期间不创建新收件关系，重新开启后也不会补发旧事件。玩家资料库与公开队列快照仍然保留。

### 查询玩家

`POST /api/queue-bot/players`，JSON 请求体为 `{"qq":"<QQ号>"}`

返回该 QQ 当前是否在队列、所在机台、游玩或等待位置、时间估算、将暂缓一次、将轮空一次、暂时离开和未到场状态。请求体只能包含 `qq`，值为 `5` 至 `12` 位数字的字符串；省略 `qq` 或提交空字符串时返回全部当前登记绑定。请求体不是 JSON 对象、包含其他字段或 `qq` 格式无效时返回 `400`。该 QQ 当前没有登记时返回 `200` 和空的 `players`。

```json
{
  "queue_id": "00000000-0000-4000-8000-000000000003",
  "machine_configuration_revision": 4,
  "revision": 128,
  "received_at": 1784681000000,
  "registration_open": true,
  "test_data": false,
  "queue_rules": {
    "allow_defer_one_round": true,
    "allow_temporary_leave": true,
    "allow_online_registration": true
  },
  "terminal": {
    "online": true,
    "last_seen_seconds": 4
  },
  "players": [
    {
      "registration_id": "a10f5015a2e85b12e20af507",
      "profile_id": "37e41698-46f8-489b-92dc-d29c71f00f7d",
      "qq_number": "12345678",
      "display_id": "示例玩家",
      "machine_id": "A",
      "machine_stable_id": "0123456789abcdef0123456789abcdef",
      "machine_name": "左侧·机台 A",
      "machine_operational": true,
      "machine_stop_reason": null,
      "machine_stop_reason_detail": null,
      "playing_started_at": 1784680400000,
      "position": "WAITING",
      "position_index": 2,
      "estimated_wait_minutes": 24,
      "estimated_wait_uncertain": false,
      "co_player_display_ids": ["示例搭档"],
      "common_play_preview_display_id": "示例搭档",
      "preference": "OPEN_TO_JOIN",
      "fixed_pair": false,
      "fixed_pair_id": null,
      "registration_type": "PLAYER_PROFILE",
      "created_at": 1784680000000,
      "online_check_in_started_at": 1784680000000,
      "last_played_at": null,
      "deferred_once": false,
      "skips_one_turn": false,
      "temporarily_away": false,
      "temporary_away_skipped_turns": 0,
      "defer_one_round_used": false,
      "online_registration_not_played": false,
      "no_show_count": 0,
      "last_no_show_action_was_defer": false,
      "online_registration_pending_check_in": false,
      "updated_at": 1784681000000
    }
  ],
  "capabilities": {
    "read_players": true,
    "remote_actions": true,
    "online_registration": true
  }
}
```

示例省略了顶层的 `business_hours`。顶层字段：

- `queue_id`、`revision`、`machine_configuration_revision`、`received_at`：当前快照的队列批次、队列修订号、机台配置修订号和服务端接收时间。快照没有机台配置修订号时按 `1` 返回。
- `registration_open`、`business_hours`、`queue_rules`：与当前队列响应中的同名字段相同。
- `terminal`：`last_seen_seconds` 是距离快照接收时间的秒数，不超过 `QUEUE_ONLINE_TIMEOUT_SECONDS`（默认 `90`）时 `online` 为 `true`。终端离线时接口仍返回 `200` 和最后一份快照中的登记。
- `capabilities`：`read_players` 固定为 `true`。终端在线且 QQ Bot 联动开启时 `remote_actions` 为 `true`，表示可以对已有登记请求排队操作。`online_registration` 在此基础上还要求 `registration_open` 为 `true`、`queue_rules.allow_online_registration` 不为 `false`，并且不处于闭店收尾，表示可以新建线上登记。

`players` 中每一项的字段：

- `registration_id`、`profile_id`、`qq_number`：登记编号、绑定的资料 UUID 和 QQ。
- `display_id`：登记的显示名称。
- `machine_id`、`machine_stable_id`、`machine_name`、`machine_operational`、`machine_stop_reason`、`machine_stop_reason_detail`、`playing_started_at`：登记所在机台的编号、稳定身份、名称、是否正常使用和停止原因，以及该机台的 `playing_started_at`。
- `position`、`position_index`：`PLAYING` 表示游玩位置，`WAITING` 表示等待位置。`position_index` 是等待位置的序号，从 `1` 开始；处于游玩位置时为 `null`。
- `estimated_wait_minutes`：处于游玩位置时，机台正常使用为 `0`，已停止使用为 `null`；处于等待位置时是该等待位置的估算值，可以为 `null`。
- `estimated_wait_uncertain`：处于等待位置时取自该等待位置（快照的 `waiting_positions`），表示等待时间可能不准确；处于游玩位置或 `estimated_wait_minutes` 为 `null` 时为 `false`。含义见“当前队列”。
- `co_player_display_ids`：同一游玩位置或等待位置中其他登记的显示名称。等待位置带有共同游玩预览时，数组末尾还包含预计搭档的显示名称。
- `common_play_preview_display_id`：预计搭档的显示名称，没有共同游玩预览时为 `null`。预计搭档不是该等待位置的真实登记，Bot 用这个字段把它与真实的同组登记区分开。
- `preference`、`fixed_pair`、`fixed_pair_id`、`registration_type`、`created_at`、`online_check_in_started_at`、`last_played_at`、`deferred_once`、`skips_one_turn`、`temporarily_away`、`temporary_away_skipped_turns`、`defer_one_round_used`、`online_registration_not_played`、`no_show_count`、`last_no_show_action_was_defer`、`online_registration_pending_check_in`：原样取自公开快照中的登记，见“当前队列”；旧快照中没有 `skips_one_turn`、`defer_one_round_used` 和 `online_registration_not_played` 时为 `false`。`registration_type` 在这里始终是 `PLAYER_PROFILE`。
- `updated_at`：登记绑定最近一次写入的时间，每次快照上传都会重写。

只有带有当前登记绑定、并且仍在快照的游玩位置或等待位置中的登记才会出现，按 `registration_id` 排序。临时登记和没有 QQ 的玩家资料不产生绑定（见“当前登记绑定”），因此不会出现在这里；一份玩家资料最多对应一份当前登记。管理后台删除玩家资料完成后，它的登记绑定一并删除。响应不包含六位玩家编号、头像和机厅信息。

QQ 按玩家资料的最新 QQ 匹配：服务端保存有这份资料时使用资料当前的 QQ，没有时才使用终端随登记绑定上传的 QQ，`qq_number` 返回的也是这个值。玩家在网页账户中修改 QQ 后立即生效，不必等终端同步：用旧 QQ 查询不到登记，用新 QQ 可以查到。资料的 QQ 为空时，任何 QQ 都匹配不到它的登记。

受控服务也可以使用 `GET /api/queue-bot/players` 读取全部当前登记绑定。响应包含登记对应的 QQ，仅供 Bot 服务内部处理。QQ 只能放在 `POST` 请求体中：`GET` 请求带有 `qq` 查询参数时返回 `400`，查询资料和查询通知事件的 `GET` 形式也是如此。

### 查询资料

`POST /api/queue-bot/profiles`，JSON 请求体为 `{"qq":"<QQ号>"}`

返回该 QQ 对应的私有玩家资料。请求体的规则与查询玩家相同。服务端保证同一玩家资料库中 QQ 不重复，因此按 QQ 查询时 `profiles` 最多包含一份资料；没有资料使用该 QQ 时返回 `200` 和空数组。匹配使用服务端保存的资料当前的 QQ，网页账户修改 QQ 后不必等终端同步即按新 QQ 匹配。

```json
{
  "queue_id": "00000000-0000-4000-8000-000000000003",
  "revision": 128,
  "bot_qq": "87654321",
  "profile_aliases": {},
  "profiles": [],
  "capabilities": {
    "profile_updates": true,
    "terminal_is_source_of_truth": true
  }
}
```

示例省略了 `profiles` 的内容。

- `profiles`：按昵称和资料 UUID 排序。每一项包含“玩家资料字段”一节的全部字段和“网页账户字段”中的五个字段，另有 `public_player_id`（六位玩家编号，六位数字的字符串或 `null`）、`public_player_id_aliases`（仍然指向这份资料的历史玩家编号）、`recent_usage_at`（最近的使用时间，按升序排列，最多 16 项）和 `synced_at`（服务端接收这份资料的时间）。
- `bot_qq`：最近一次通过 `POST /api/queue-bot/identity` 上报的 Bot QQ，从未上报时为 `null`。
- `profile_aliases`：以旧别名资料的 UUID 为键、保留资料的 UUID 为值的对象。旧别名是旧版本遗留的重复资料：它与终端当前资料库中的一份资料昵称（不区分大小写）、性别和默认偏好都相同，两者不同时带有 QQ，并且其中一份没有 QQ 且 `setup_version` 小于 `1`。旧别名不出现在 `profiles` 中。
- `capabilities`：两个值固定为 `true`。

按 QQ 查询时，如果该 QQ 记在一份旧别名资料上，返回的是它对应的保留资料，这份资料自身的 `qq_number` 可能为空；此时请求排队操作会得到 `PROFILE_SYNC_PENDING`。`bot_qq` 和 `profile_aliases` 不随 QQ 查询条件变化。

`GET /api/queue-bot/profiles` 返回完整私有玩家资料库，包括 QQ、性别、默认偏好和资料 UUID；省略 `qq` 的 `POST` 请求与它等价。该接口不得从网站前端调用。管理后台删除的玩家资料不再出现在响应中。终端使用的 `GET /api/queue-terminal/profiles` 返回相同的结构，区别在于它不接受 QQ 查询，`capabilities.profile_updates` 为 `false`。

### 上报 Bot QQ 与版本

`POST /api/queue-bot/identity`

```json
{
  "bot_qq": "87654321",
  "bot_version": "0.3.13",
  "website_version": "0.13.6"
}
```

`bot_qq` 必填，是当前 OneBot 登录的 QQ，`5` 至 `12` 位数字的字符串。`bot_version` 和 `website_version` 自 0.10.1 起支持，可以省略或为 `null`；值是语义化版本号，不超过 32 个字符，可以带前缀 `v`，保存时去掉该前缀。请求体缺少 `bot_qq`、包含其他字段或取值无效时返回 `400`。

成功返回 `200`：

```json
{
  "ok": true,
  "bot_qq": "87654321",
  "bot_version": "0.3.13",
  "bot_version_updated_at": 1784681000000,
  "website_version": "0.13.6",
  "website_version_updated_at": 1784681000000,
  "updated_at": 1784681000000
}
```

每次上报都会覆盖 `bot_qq` 和 `bot_version`：省略 `bot_version` 会清除已保存的 Bot 版本及其时间。`website_version` 只在提供时更新，省略时保留上一次的值和时间。服务端按 `QUEUE_PROFILE_SCOPE_ID` 保存一份记录，不区分正式数据和测试数据。

这个接口只校验 Bot 令牌，不受 QQ Bot 联动开关影响，也不要求已有队列快照。

Koishi 在连接 OneBot 后上报实际登录 QQ，并定期刷新：每 `30` 秒检查一次，尚未上报成功或 Bot QQ 发生变化时立即上报，成功后每小时刷新一次。`bot_version` 是插件自身的版本；`website_version` 读取自网站的 `queue-client-version.json`，读取失败时不上报该字段。旧版本服务端以 `400` 拒绝版本字段时，Koishi 改为只上报 `bot_qq`。

终端拉取玩家资料时同时获得该 QQ，用于显示添加 Bot 好友的二维码；查询资料的响应也返回它。该身份仅供显示和通知引导，不参与玩家资料或排队权限判断。两个版本号用于公开的 `GET /api/queue-versions`，见“版本状态”。

### 查询通知事件

`GET /api/queue-bot/events?after=<游标>&limit=<条数>`

通知服务使用这个接口全量读取当前快照所属队列批次的事件。`after` 是不小于 `0` 的整数，默认 `0`，只返回游标大于它的事件；`limit` 是 `1` 至 `100` 的整数，默认 `50`，Koishi 每次读取 `100` 条。数值无效时返回 `400`。

```json
{
  "queue_id": "00000000-0000-4000-8000-000000000003",
  "notification_scope_id": "00000000-0000-4000-8000-000000000003",
  "revision": 128,
  "test_data": false,
  "events": [
    {
      "cursor": 1287,
      "event_id": "00000000-0000-4000-8000-000000000004",
      "occurred_at": 1784681000000,
      "machine_id": "A",
      "machine_stable_id": "0123456789abcdef0123456789abcdef",
      "machine_name": "左侧·机台 A",
      "type": "REGISTRATION_UPDATED",
      "title": "机台 A·队列已更新",
      "detail": "“示例玩家”的排队状态已更新。",
      "operation_source": "ON_SITE_TERMINAL",
      "notification_categories": ["QUEUE_CHANGES"],
      "affected_players": [
        {
          "registration_id": "a10f5015a2e85b12e20af507",
          "profile_id": "37e41698-46f8-489b-92dc-d29c71f00f7d",
          "qq_number": "12345678"
        }
      ]
    }
  ],
  "next_cursor": 1287,
  "latest_cursor": 1287,
  "has_more": false,
  "capabilities": {
    "related_event_notifications": true,
    "playing_notifications": true,
    "remote_actions": false
  }
}
```

事件按递增游标返回。Koishi 按顺序逐条保存已处理完成的事件的 `cursor`（遇到投递尚未完成的事件后本轮不再推进），之后只轮询新增事件，避免重复通知。`latest_cursor` 用于首次启动或切换队列时跳过既有日志，防止集中补发历史消息。`affected_players` 用于投递本人相关日志，`operation_source` 说明操作来源。事件的其余字段与“公开日志”中的同名字段相同，只是这里不返回 `registration_ids`。

- `next_cursor`：本页最后一条被扫描事件的游标，没有事件时等于请求中的 `after`，不会是 `null`。
- `latest_cursor`：`after` 之后已保存的最新事件的游标，没有时等于 `after`。
- `has_more`：本页达到 `limit` 且之后还有事件。为 `true` 时把 `next_cursor` 作为下一次请求的 `after` 继续读取。
- `notification_scope_id`：事件所属的数据范围。正式数据等于 `queue_id`，测试数据为 `test:<终端 X-Device-ID>:<queue_id>`。Koishi 按这个值分别保存游标和投递记录，测试数据与正式数据使用同一个 `queue_id` 时不会互相补发历史消息；旧版本服务端没有这个字段时，Koishi 改用 `queue_id`。
- `capabilities`：三个值固定为示例中的内容。

`affected_players` 是事件第一次保存时固定下来的收件人。服务器对事件涉及的每份带有登记绑定的登记，按对应玩家资料当时的通知设置过滤后建立收件关系，QQ 取终端当时随登记绑定上传的值；之后资料设置或 QQ 发生变化，已有的收件关系不会改写。`QUEUE_RESET` 事件的收件人是上一个队列批次的登记绑定。没有收件人的事件同样返回，`affected_players` 为空数组。每条事件的 `affected_players` 会包含当时固定的全部 QQ 收件人，因此该响应属于私有数据，不能转发到群聊或公开日志。

每条事件带有 `notification_categories`，其取值与资料字段的映射固定如下：

- `QUEUE_CHANGES` 使用 `notify_queue_changes`。
- `PLAYING_POSITION` 使用 `notify_playing_position`。
- `ONLINE_CHECK_IN` 使用 `notify_online_check_in`。
- `ABSENCE` 使用 `notify_absence`。
- `MACHINE_STATUS` 使用 `notify_machine_status`。

分类由终端在生成事件时确定，一条事件可以同时带有多个分类。玩家资料的 `notification_enabled` 为 `true`，并且事件的任一分类对应的分项为 `true` 时，该玩家才成为收件人。终端没有提供分类，或保存的分类为空或无效时，服务端按事件类型推导出一个分类：

- `PLAYING_CHANGED` 归入 `PLAYING_POSITION`。
- `ONLINE_REGISTRATION_ADDED`、`ONLINE_CHECK_IN_COMPLETED`、`ONLINE_CHECK_IN_TIMED_OUT` 和 `ONLINE_CHECK_IN_MISSED`（线上登记创建、完成签到、签到超时、轮到时未签到）归入 `ONLINE_CHECK_IN`。
- `NO_SHOW_DEFERRED`、`NO_SHOW_MOVED_TO_TAIL`、`NO_SHOW_REMOVED`、`TEMPORARY_AWAY_EXPIRED` 和 `ABSENCE_CHANGED`（暂缓一次、暂时离开、未到场及其处理）归入 `ABSENCE`。
- `MACHINE_STOPPED`、`MACHINE_RESTORED`、`REGISTRATION_OPENED` 和 `REGISTRATION_CLOSED`（机台停止/恢复、登记排队开启/关闭）归入 `MACHINE_STATUS`。
- 其余类型归入 `QUEUE_CHANGES`。

正常轮换引起的等待顺序前移属于游玩位置变化的结果，不会再额外触发 `notify_queue_changes`；玩家正常完成游玩并清除未到场记录时，会另建只关联该玩家的 `notify_absence` 事件。这样关闭某一分项后，不会因为同一轮操作中夹带的其他状态而绕过设置。

服务器在建立事件收件关系时按当时设置过滤；Koishi 实际发送前再次读取最新资料，因此玩家关闭总开关或对应分项后，尚在重试中的同类消息也会停止。按收件人的 QQ 查不到玩家资料时，Koishi 同样不发送。玩家在网页账户中修改 QQ 后，终端取回新 QQ 并重新上传登记绑定之前产生的事件仍记在旧 QQ 名下，按旧 QQ 已查不到资料，这些通知不会发出。

收件关系只保留 `QUEUE_EVENT_RECIPIENT_RETENTION_SECONDS`（默认 `2592000` 秒），过期的记录在服务端接受快照上传或读取事件时删除。现场终端关闭 QQ Bot 联动、事件因超出每个批次 `2000` 条的上限被删除，或管理后台删除玩家资料完成后，相应的收件关系也会删除。

`POST /api/queue-bot/events`，JSON 请求体为 `{"qq":"<QQ号>","after":<游标>,"limit":50}`

按一个 QQ 读取事件。三个字段都可以省略，请求体不能包含其他字段；`qq` 省略或为空字符串时结果与 `GET` 形式相同。提供 `qq` 时只返回收件人中有该 QQ 的事件，`affected_players` 也只保留该 QQ 的条目。`next_cursor` 仍然越过被跳过的事件；`has_more` 只表示之后还有事件，不保证其中有与该 QQ 相关的。筛选比较的是事件保存时固定的收件人 QQ，不是玩家资料当前的 QQ。Koishi 只使用 `GET` 形式。

### 请求修改玩家资料

`PATCH /api/queue-bot/profiles/<profile_id>`

```json
{
  "request_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
  "actor_qq": "12345678",
  "nickname": "新昵称",
  "gender": "UNDISCLOSED",
  "default_preference": "OPEN_TO_JOIN",
  "qq_visibility": "TERMINAL_ONLY",
  "notify_playing_position": true
}
```

`request_id`（UUID）和 `actor_qq` 必填，另外至少提交一个要修改的字段，只有提交的字段会被修改：

- `nickname`：去除首尾空白后为 `1` 至 `18` 个字符。
- `gender`：`MALE`、`FEMALE` 或 `UNDISCLOSED`。
- `default_preference`：`SOLO`、`OPEN_TO_JOIN` 或 `ASK_EVERY_TIME`。
- `qq_visibility`：`TERMINAL_ONLY` 或 `PUBLIC_WEBSITE`。
- `notification_enabled`、`notify_queue_changes`、`notify_playing_position`、`notify_online_check_in`、`notify_absence`、`notify_machine_status`：布尔值。

QQ 不能通过此接口修改。`setup_version`、头像、`terminal_editing_allowed` 和 `visited_venues_public` 同样不能修改，请求体出现上述字段以外的内容时返回 `400`。

服务器验证 `actor_qq` 与资料一致后创建 `UPDATE_PLAYER_PROFILE` 命令，交给当前快照对应的终端，并返回 `202` 和命令对象（见“私有 Bot 接口”的“查询命令”）。相同 `request_id` 保证幂等：内容完全相同的重复请求返回 `200` 和已保存的命令，内容不同时返回 `409`。命令的 `payload` 包含资料当前的 QQ、修改所依据的 `expected_profile_revision` 和 `expected_updated_at`，以及十个可修改字段的完整目标值，未提交的字段取资料的现值。资料在终端应用命令后才真正改变，终端的校验见“拉取命令”。

服务端在以下情况拒绝请求：

- `400`：`profile_id` 或 `request_id` 不是 UUID，`actor_qq` 格式无效，没有提交可修改的字段，或字段取值无效。
- `404`：终端从未上传过快照；当前玩家资料库中没有这份资料。
- `503`：终端离线。
- `403`：服务端保存的资料当前的 QQ 与 `actor_qq` 不一致。
- `409`：目标昵称与资料库中另一份资料的昵称相同，比较时不区分大小写。没有提交 `nickname` 时用资料现有的昵称比较，比较范围包括不在查询资料响应中列出的旧别名资料。
- `409`：当前终端上已有一条属于同一 QQ 或同一玩家资料的待执行命令，不限命令类型。

已绑定网页账户的资料同样可以通过这个接口修改，服务端创建命令时不检查 `terminal_editing_allowed`；终端应用命令后上传的资料按“网页账户字段”中的合并规则被服务端接受。玩家刚在网页修改过资料或 QQ、终端尚未取回时，终端会因本机资料的版本或 QQ 与命令不符而拒绝这条命令，需要等终端同步后重新提交。

### 查询命令

`GET /api/queue-bot/commands/<command_id>`

`command_id` 是创建命令时使用的 `request_id`。服务端把它规范化为小写、带连字符的 UUID，命令对象中的 `command_id` 以这种形式返回。请求修改玩家资料和请求排队操作的响应是同一结构的命令对象：

```json
{
  "command_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
  "type": "UPDATE_PLAYER_PROFILE",
  "payload": {},
  "status": "APPLIED",
  "created_at": 1784681000000,
  "claimed_at": 1784681002000,
  "claimed_terminal": "00000000-0000-4000-8000-000000000001",
  "claimed_instance": "00000000-0000-4000-8000-000000000006",
  "completed_at": 1784681003000,
  "result_source": "TERMINAL",
  "result_detail": "玩家资料已由终端更新。",
  "result_registration_id": null
}
```

示例省略了 `payload` 的内容。`payload` 是服务端保存的命令内容（只去掉内部使用的 `_queue_storage_id`），其中的 `_request` 记录规范化后的原始请求，用于判断重复请求。

状态包括 `PENDING`、`APPLIED` 和 `REJECTED`。拒绝原因位于 `result_detail`。`claimed_at`、`claimed_terminal` 和 `claimed_instance` 是终端领取命令的时间、终端的 `X-Device-ID` 和运行实例编号，尚未领取时为 `null`。`result_registration_id` 是终端执行命令时新建登记的编号：线上加入成功后由终端回报，管理后台建立了新登记的命令也带有这个值，其他情况为 `null`。`result_source` 说明结果由谁给出，命令尚未完成时为 `null`：

- `TERMINAL`：终端的处理结果。
- `SERVER_TIMEOUT`：命令在 `QUEUE_COMMAND_TIMEOUT_SECONDS`（默认 `600` 秒）内没有得到处理，被服务端拒绝，`result_detail` 为 `终端未在有效时间内处理这次操作，请重新提交。`。
- `BOT_DISABLED`：现场终端关闭了 QQ Bot 联动，尚未被领取的命令被服务端拒绝。
- `SERVER_MIGRATION`：测试数据与正式数据之间发生切换，或终端接管、更换运行实例后队列批次或机台稳定身份已经变化，原命令被服务端拒绝，见“权威终端与同步方式”。对 Bot 创建的命令，接管只拒绝排队操作命令，待执行的资料修改命令仍交给接管后的终端处理。
- `SYNC_DISABLED`：现场终端关闭了与服务端同步。Bot 创建的命令不会得到这个结果，它只出现在其他来源的命令上。

服务端在每次创建或查询命令时处理超时。因超时被拒绝的命令不一定是最终状态：此前已领取该命令的终端实例之后仍可以提交实际结果。已完成的命令保留 `QUEUE_COMMAND_RETENTION_SECONDS`（默认 `2592000` 秒），之后被删除，再查询时返回 `404`。命令不存在时同样返回 `404`，说明为 `没有找到这条命令`；`command_id` 不是 UUID 时返回 `400`。

这个接口不按命令类型或来源筛选：持有 Bot 令牌即可按编号读取网站、移动设备和管理后台创建的命令及其完整 `payload`。它不要求已有队列快照，但 QQ Bot 联动关闭时同样返回 `503`。

Koishi 创建命令后每秒查询一次，直到状态不再是 `PENDING`，或超过插件配置的等待时间（默认 `15` 秒）；它只读取 `command_id`、`status` 和 `result_detail`。

### 请求排队操作

`POST /api/queue-bot/queue-commands`

```json
{
  "request_id": "00000000-0000-4000-8000-000000000007",
  "actor_qq": "12345678",
  "operation": "TRANSFER_MACHINE",
  "target_machine_id": "B",
  "expected_queue_id": "00000000-0000-4000-8000-000000000003",
  "expected_registration_id": "a10f5015a2e85b12e20af507",
  "expected_machine_id": "A",
  "expected_position": "WAITING",
  "expected_fixed_pair_id": null,
  "expected_absence_status": "NONE",
  "expected_temporary_away_skipped_turns": 0,
  "expected_pending_check_in": false,
  "expected_machine_configuration_revision": 4,
  "expected_machine_stable_id": "0123456789abcdef0123456789abcdef",
  "expected_target_machine_stable_id": "fedcba9876543210fedcba9876543210"
}
```

基础请求包含 `request_id`、`actor_qq` 和 `operation`。加入排队或转至其他机台时附带 `machine_id` / `target_machine_id`，修改本次偏好时附带 `preference`。

- `request_id`：UUID，同时作为命令编号。
- `actor_qq`：发起操作的玩家 QQ，`5` 至 `12` 位数字的字符串。
- `operation`：`JOIN_QUEUE`（线上加入）、`LEAVE_QUEUE`（退出排队）、`DEFER_ONE_ROUND`（暂缓一次）、`CANCEL_DEFER_ONE_ROUND`（取消暂缓一次）、`SKIP_ONE_TURN`（轮空一次）、`CANCEL_SKIP_ONE_TURN`（取消轮空一次）、`TEMPORARILY_LEAVE`（暂时离开）、`CANCEL_TEMPORARY_LEAVE`（取消暂时离开）、`TRANSFER_MACHINE`（转至其他机台）、`CHANGE_PLAY_PREFERENCE`（更改本次游玩偏好）或 `SET_PAIRED_MACHINE_QUEUEING`（修改是否同时在两台机台排队，需要 `also_queues_at_paired_machine`，见“自动在两台机台之间安排登记”）。`JOIN_QUEUE` 也可以带 `also_queues_at_paired_machine`。
- `machine_id`、`target_machine_id`：加入排队和转至其他机台的目标机台编号。服务端去掉首尾空白并转为大写。
- `preference`：`SOLO` 或 `OPEN_TO_JOIN`。

`CHECK_IN` 能通过字段校验，但 Bot 不能远程签到：服务端总是拒绝这项请求，终端也只接受管理后台发起的签到。

确认请求还携带玩家确认操作时看到的状态，服务器和终端据此拒绝过期的请求：

- 加入排队：`expected_queue_id` 和 `expected_machine_configuration_revision` 必须同时提供或同时省略。`expected_machine_stable_id` 是所选机台的稳定身份，可以省略，只能与前两个字段一起提供。加入排队不接受下面的登记确认字段和 `expected_target_machine_stable_id`。本节接口不提供机台列表，Koishi 从公开的 `GET /api/queue-status` 取得这三个值。
- 其他操作：`expected_queue_id`、`expected_registration_id`、`expected_machine_id`、`expected_position`、`expected_fixed_pair_id`、`expected_absence_status`、`expected_temporary_away_skipped_turns` 和 `expected_pending_check_in` 必须全部提供或全部省略。`expected_fixed_pair_id` 可以是 `null`，但字段必须出现。`expected_absence_status` 为 `NONE`、`DEFER_ONE_ROUND`、`SKIP_ONE_TURN` 或 `TEMPORARILY_AWAY`，`expected_temporary_away_skipped_turns` 为 `0` 至 `3`。`expected_machine_configuration_revision` 和 `expected_machine_stable_id` 只能与这八个字段一起提供，`expected_target_machine_stable_id` 还只能用于 `TRANSFER_MACHINE`。这些值取自查询玩家的响应：`deferred_once` 为 `true` 时状态是 `DEFER_ONE_ROUND`，否则 `skips_one_turn` 为 `true` 时是 `SKIP_ONE_TURN`，否则 `temporarily_away` 为 `true` 时是 `TEMPORARILY_AWAY`，其余是 `NONE`。

不带任何确认字段的请求同样被接受，只按当前快照校验。请求体出现未列出的字段，或确认字段不完整时返回 `400`。Koishi 始终提交确认字段；旧版本服务端以 `400` 拒绝机台稳定身份字段时，它去掉这些字段（非加入操作还去掉机台配置修订号），用同一个 `request_id` 重试一次。

服务器创建一条 `QUEUE_OPERATION` 命令，来源为 `QQ_BOT`，交给当前快照对应的终端。创建成功返回 `202` 和命令对象（见“私有 Bot 接口”的“查询命令”）。相同 `request_id` 保证幂等：内容完全相同的重复请求返回 `200` 和已保存的命令，内容不同时返回 `409`。这项判断先于快照和终端状态的检查，因此终端离线后重复提交仍然返回原命令及其当前状态。`payload` 中的队列批次、机台配置修订号、机台稳定身份、登记编号、位置、固定组合编号、暂缓或暂离状态、轮空次数和签到状态由服务端按当前快照写入，不照抄请求中的 `expected_*` 值；各字段的含义见“拉取命令”。

服务器只接受与 `actor_qq` 当前绑定相符的本人登记。玩家资料和登记都按资料的最新 QQ 查找，规则与查询玩家相同，网页账户修改 QQ 后立即按新 QQ 生效。终端执行前还会核对本机资料的 QQ 与 `actor_qq` 一致，不一致时拒绝命令。

加入排队的游玩偏好按以下规则确定：容量为 1 的机台一律使用 `SOLO`，不看提交的值；其他机台在资料默认偏好为 `ASK_EVERY_TIME` 时必须提供 `preference`，否则使用资料的默认偏好，`preference` 可以省略，提交时必须与默认偏好相同。

除请求格式错误外，服务端在以下情况拒绝请求：

- `404`：终端从未上传过快照；没有玩家资料使用该 QQ（`PROFILE_NOT_FOUND`）。
- `409` `QUEUE_CONTEXT_CHANGED`：加入排队时 `expected_queue_id` 或 `expected_machine_configuration_revision` 与当前快照不一致，或 `machine_id` 当前对应机台的稳定身份与 `expected_machine_stable_id` 不一致；其他操作的 `expected_machine_configuration_revision` 与当前快照不一致。此时需要重新查询后再让玩家确认。
- `503`：终端离线；加入排队时现场规则不允许线上登记。
- `409` `PROFILE_SYNC_PENDING`：该 QQ 只记在一份旧别名资料上，对应的保留资料尚未在现场终端保存这个 QQ。
- 加入排队时 `409`：处于闭店收尾；该玩家已有登记；登记排队没有开放；机台已停止使用；机台登记已满 `20` 份；提交的偏好与资料固定的默认偏好不同。
- 加入排队时 `409` `PLAYER_OPERATION_SYNCING`：快照中还没有该玩家的登记，但同一玩家的线上加入或“使用移动设备登记”命令在 `30` 秒内刚被终端应用，且完成时间不早于当前快照的接收时间。
- 加入排队时 `400`：`machine_id` 不在当前快照中，且没有提供 `expected_machine_stable_id`；资料默认偏好为 `ASK_EVERY_TIME` 而没有提供 `preference`。
- 其他操作 `409`：该 QQ 在当前队列中不是恰好一份登记；确认字段与当前快照不符；登记所在机台已停止使用而请求的不是退出排队或转至其他机台；待签到登记请求了退出排队以外的操作；现场规则不允许暂缓一次或暂时离开；登记处于游玩位置时请求暂缓一次、轮空一次或暂时离开（`处于游玩位置的登记不能轮空一次，请先在现场终端撤回至等待顺序。`，另两项的提示相应为“不能暂缓一次”和“不能暂时离开”）；`online_registration_not_played` 为 `true` 时请求暂缓一次、轮空一次或暂时离开（`已签到的线上登记在游玩一轮之前不能延后。`）；`defer_one_round_used` 为 `true` 时请求暂缓一次（`这份登记已暂缓过一次，游玩一轮之后才能再次暂缓。`）；转至其他机台时登记处于游玩位置、目标机台已停止使用或登记已满、目标机台的稳定身份与 `expected_target_machine_stable_id` 不一致，或目标机台容量为 1 而登记属于固定组合；所在机台容量为 1 时修改本次偏好。
- 其他操作 `400`：转至其他机台时目标机台不存在或就是当前机台；修改本次偏好时没有提供 `preference`。
- `409`：当前终端上已有一条属于同一 QQ 或同一玩家资料的待执行命令，不限命令类型。

待签到的线上登记仅允许退出；其 30 分钟签到时限和轮到时自动退出规则由终端执行。“允许线上登记”关闭后只拒绝新建线上登记，不影响查询、通知、资料同步或已有登记的操作；登记排队是否开放同样只影响加入排队。取消暂缓一次、取消轮空一次和取消暂时离开不在服务端按快照预判，由终端按当前状态决定。暂缓一次、轮空一次、暂时离开、机台状态、队列容量和固定组合等规则会在服务器和终端分别校验；轮空一次没有对应的现场规则开关。服务端依据的是已保存的快照，终端按最新状态做最终校验。

## 管理后台接口

管理后台是独立的 Android 应用，供机厅管理员在手机上查看完整队列和玩家资料库、执行现场队列操作、修改终端设置并维护玩家资料。它只访问服务端，不直接连接现场终端。这组接口在 0.13.0 加入。

除重置网页账户密码外，本节的写接口都不直接修改队列快照或玩家资料。服务端校验请求后只保存一条待执行命令，交给上传当前快照的现场终端；终端按本机最新状态重新校验，写入本机并回执后，修改才算生效，结果随之后的快照回到服务端。各类命令的内容和终端的处理规则见“终端回流接口”的“拉取命令”。

### 鉴权与令牌

所有请求使用：

```text
Authorization: Bearer <QUEUE_MANAGEMENT_TOKEN>
```

`QUEUE_MANAGEMENT_TOKEN` 至少为 32 个 UTF-8 字节，并且必须与 `QUEUE_SYNC_TOKEN`、`QUEUE_BOT_TOKEN` 都不同。没有配置、长度不足或与另外两个令牌之一相同时，所有管理接口返回 `503` `管理后台鉴权配置无效。`，而不是接受共用的凭据；这项检查先于令牌比较。请求没有携带令牌、请求头不以 `Bearer ` 开头或令牌不符时返回 `401` `管理后台认证失败，请核对管理令牌。`。终端同步令牌和 Bot 令牌不能访问这组接口。

管理令牌是服务级凭据，不对应某一位管理员，服务端也不登记或配对管理设备。持有者可以读取完整玩家资料库中的 QQ、性别和资料 UUID，读取全部当前登记对应的 QQ，提交本节的任何命令，并重置玩家的网页账户密码。它只能保存在受控的管理后台中，不能交给玩家、浏览器或其他不受控客户端。

服务端只读取 `Authorization`，不读取 `X-Device-ID`、`X-Queue-Schema-Version`、`X-Queue-Venue-ID` 等终端请求头。请求不能指定终端：命令总是交给上传当前快照的那台终端。

管理后台的连接设置可以填写站点根地址或任一接口地址。应用取 `/api/queue-status` 或 `/api/queue-management` 之前的部分作为根地址（地址以 `/api` 结尾时去掉这一段），再拼接本节的固定路径。

### 通用约定

`PATCH`、`POST` 和 `DELETE` 请求使用 `Content-Type: application/json`，请求体必须是 JSON 对象，否则返回 `400` `请求内容必须是 JSON 对象。`；删除玩家资料的 `DELETE` 请求同样带有请求体。除重置网页账户密码外，请求体出现未列出的字段时返回 `400`。完整请求体限制为 `1 MiB`，超过时返回 `413`。

字段按 JSON 类型严格校验：布尔值必须是 `true` 或 `false`，整数不能写成浮点数或字符串。UUID 不区分大小写，服务端统一保存为小写的标准形式。以下可选字段按键是否出现判断，省略才表示不提供，值为 `null` 时返回 `400`：`expected_management_policy_revision`、`expected_machine_stable_id`、`expected_target_machine_stable_id`、`source_position_index`、`destination_position_index`、`desired_registration_order`、`start_next_when_playing_becomes_empty` 和 `advance_when_playing_empty`。登记排队操作接口中可以省略的 `expected_machine_configuration_revision` 和修改玩家资料接口的各个可修改字段同样如此。

除修改玩家资料外，创建命令的接口都接受可选的 `reason`，最长 `200` 个字符，省略时使用接口各自的默认说明。这段说明只保存在服务端的命令内容中，现场终端不显示它，也不写入操作日志；登记排队操作接口不保存它。

错误响应为 `{"ok": false, "error": "<说明>"}`，部分错误附带 `code`。本节出现的 `code` 有 `QUEUE_CONTEXT_CHANGED`、`TERMINAL_POLICY_CONTEXT_CHANGED`、`TERMINAL_SETTINGS_CONTEXT_CHANGED`、`PROFILE_CONTEXT_CHANGED`、`PROFILE_ALREADY_DELETED`、`PROFILE_NOT_FOUND`、`REGISTRATION_NOT_FOUND`，以及重置密码时的 `PASSWORD_REQUIRED`、`PASSWORD_TOO_SHORT`、`PASSWORD_TOO_LONG`、`PASSWORD_INVALID` 和 `PASSWORD_CONFIRMATION_MISMATCH`。未接管、终端不支持、已有待处理命令和 `request_id` 冲突等 `409` 不带 `code`，只能按 `error` 文本区分；管理后台只读取 `error` 并显示给管理员。路径或方法不存在时，返回的是服务端框架默认的非 JSON 响应。

创建命令的接口有以下共同行为，下文不再逐一重复：

- `request_id` 是必填的 UUID，同时作为命令编号和幂等键。创建成功返回 `202` 和命令对象。用相同的 `request_id` 再次提交时，如果命令类型相同且规范化后的请求内容（包括补上的默认 `reason`）完全一致，返回 `200` 和已保存的命令及其当前状态；有任何不同则返回 `409` `request_id 已用于其他命令。`。这项判断先于下面的快照和在线检查，终端离线后重复请求仍然得到原命令；只有修改玩家资料接口先检查是否已有快照。管理后台每次请求都生成新的 UUID。
- 服务端还没有队列快照时返回 `404` `排队终端暂未同步。`。
- 终端离线时返回 `503`。在线指最新快照的接收时间在 `QUEUE_ONLINE_TIMEOUT_SECONDS`（默认 `90` 秒）以内。管理总览、管理日志、查询命令和重置网页账户密码不检查终端是否在线。
- 请求回传的上下文与当前快照不一致时返回 `409`，此时应重新读取管理总览后再提交。
- 同一对象已有待处理的命令时返回 `409`，范围见各接口。
- 管理接口不检查快照的 `website_remote_enabled`。

命令对象包含 `command_id`、`type`、`payload`、`status`、`created_at`、`claimed_at`、`claimed_terminal`、`claimed_instance`、`completed_at`、`result_source`、`result_detail` 和 `result_registration_id`，各字段的含义与“网站线上登记”中的命令对象相同。`command_id` 就是请求的 `request_id`。管理接口的响应带有 `payload`，即交给终端的命令内容：其中 `operation_source` 固定为 `MANAGEMENT_APP`，`_request` 是用于幂等比较的规范化请求。

### 绑定与接管

绑定指管理后台接管现场终端的敏感设置，界面和提示文本中称为“接管”。接管状态只由快照顶层的 `management_app_bound` 表示，没有配对密钥，也不记录是哪一台管理设备：任何持有管理令牌的调用方都可以建立或解除接管。它与 `X-Queue-Sync-Mode: takeover`（接管同步，即一台终端取代另一台终端成为权威终端，见“权威终端与同步方式”）无关。

`management_app_bound` 只会因终端应用一条 `UPDATE_TERMINAL_POLICY` 命令而改变，也就是只能通过“终端敏感策略”接口建立或解除；现场终端本机没有修改它的入口。

管理接口依据快照中的以下字段判断终端状态。字段的格式和缺省值见“协议版本”；它们属于公开快照，`GET /api/queue-status` 也会返回。管理总览把它们整理为 `queue.terminal_policy`、`queue.terminal_settings` 和 `queue.registration_open`。

- `management_policy_supported`：终端声明可以执行策略命令。“终端敏感策略”和“删除玩家资料”要求它为 `true`。
- `management_app_bound`：是否已被接管。
- `management_policy_revision`：策略修订号。终端每应用一条策略命令加 `1`，本机操作不会改变它。请求以 `expected_policy_revision` 或 `expected_management_policy_revision` 回传。
- `management_policy`：`allow_online_registration`、`allow_defer_one_round`、`allow_temporary_leave` 和 `onebot_sync_enabled` 四个开关的当前值。
- `management_settings_supported`：终端声明可以执行终端设置、登记排队开关和机台状态命令。这三个接口要求它为 `true`。
- `management_settings_revision`：设置修订号。受接管的设置内容每发生一次变化加 `1`，不论变化来自设置命令、改变了开关的策略命令，还是未接管时的本机修改；只改变接管状态的策略命令不会使它变化。请求以 `expected_settings_revision` 回传。
- `show_common_play_preview`、`registration_control_open` 和 `business_hours_settings`：是否显示共同游玩预览、登记排队开关本身的状态和完整营业时间表。

接管后，现场终端拒绝在本机修改受接管的内容，设置页的相应控件不可用，并提示 `已由管理后台接管，只能从管理后台修改。`。受接管的内容包括：

- 是否允许暂缓一次、暂时离开和线上登记，是否显示共同游玩预览，以及 QQ Bot 联动开关。
- 机台数量、每台机台的配置、机台分组和默认分组。
- 营业时间。
- 在终端上报告机台停止使用和恢复正常使用。

以下内容不受接管影响，仍然在现场终端操作：全部队列操作（包括拖动调整顺序）、终端自己的关闭和重新开启登记排队、按营业时间自动闭店，以及与服务端同步的总开关、同步方式、服务器地址和终端同步令牌。后四项不能通过管理接口修改。

各接口对接管状态的要求：

- 要求已接管：终端设置、登记排队开关、机台状态和删除玩家资料。未接管时返回 `409` `请先绑定并接管现场终端设置。`，不带 `code`。终端执行这四类命令时会再检查一次，接管已被解除的命令被拒绝。
- 不要求接管：管理总览、管理日志、查询命令、终端敏感策略、终端队列操作、立即签到、登记排队操作、调整登记顺序、新建登记、修改玩家资料和重置网页账户密码。

因此接管只限制现场终端的本机修改，并作为上述四个接口的前提。四个策略开关在未接管时同样可以通过“终端敏感策略”修改，队列操作也不需要接管。

终端队列操作、立即签到、登记排队操作、调整登记顺序和新建登记不检查终端声明的能力。终端版本过旧、不认识对应命令时不会回执，命令在超时后被服务端拒绝。

设置修订号可能因现场操作或其他命令而变化，策略修订号可能因其他策略命令而变化，提交需要回传修订号的请求之前应重新读取管理总览。

### 管理总览

`GET /api/queue-management/overview`

没有参数。一次返回管理后台需要的全部数据：机厅、终端在线状态、带有私有联系信息的完整队列、接管状态和修订号、完整玩家资料库以及能力标记。写接口需要回传的上下文都取自这个响应。

```json
{
  "ok": true,
  "venue": {
    "id": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
    "code": "ABCD2345",
    "name": "示例机厅",
    "registered": true
  },
  "terminal": {
    "id": "11111111-2222-4333-8444-555555555555",
    "name": "前台终端",
    "role": "AUTHORITATIVE",
    "online": true,
    "last_seen_at": 1784681000000
  },
  "queue": {
    "queue_id": "44444444-5555-4666-8777-888888888888",
    "revision": 128,
    "machine_configuration_revision": 3,
    "received_at": 1784681000000,
    "registration_open": true,
    "queue_rules": {
      "allow_defer_one_round": true,
      "allow_temporary_leave": true,
      "allow_online_registration": true
    },
    "terminal_policy": {
      "supported": true,
      "management_app_bound": true,
      "revision": 2,
      "allow_online_registration": true,
      "allow_defer_one_round": true,
      "allow_temporary_leave": true,
      "onebot_sync_enabled": true
    },
    "terminal_settings": {
      "supported": true,
      "revision": 5,
      "show_common_play_preview": true,
      "business_hours": {
        "enabled": true,
        "use_weekly_schedule": false,
        "default_hours": { "opening_minutes": 600, "closing_minutes": 1320 },
        "weekly_hours": {}
      },
      "machine_groups": [
        { "id": "00000000000000000000000000000001", "name": "分组 1" }
      ],
      "default_machine_group_id": "00000000000000000000000000000001"
    },
    "machines": []
  },
  "profiles": [],
  "capabilities": {}
}
```

示例省略了 `weekly_hours` 中的七天以及 `machines`、`profiles` 和 `capabilities` 的内容。

- `venue`：与“核对安装信息”返回的 `venue` 相同。
- `terminal`：上传当前快照的终端。`id` 是它的 `X-Device-ID`；`role` 目前恒为 `AUTHORITATIVE`；`name` 取快照中的终端名称，没有时取服务端登记的名称，都没有时为 `null`；`online` 按最新快照的接收时间计算，`last_seen_at` 就是这个接收时间。
- `queue.queue_id`、`queue.revision`、`queue.machine_configuration_revision`：当前队列批次、队列修订号和机台配置修订号。`queue.received_at` 是最新快照的接收时间，与 `terminal.last_seen_at` 相同。
- `queue.registration_open`：登记排队开关本身的状态，即快照的 `registration_control_open`。它不受闭店收尾影响，与公开快照的 `registration_open` 可能不同。
- `queue.queue_rules`：快照中的现场规则。
- `queue.terminal_policy`：`supported`、`management_app_bound` 和 `revision` 对应快照的 `management_policy_supported`、`management_app_bound` 和 `management_policy_revision`，其余四个键是 `management_policy` 的内容。
- `queue.terminal_settings`：`supported` 和 `revision` 对应 `management_settings_supported` 和 `management_settings_revision`。`business_hours` 取快照的 `business_hours_settings`；终端没有上传时取服务端保存的机厅营业时间，两者都没有时为 `null`。`machine_groups` 和 `default_machine_group_id` 是机台分组。
- `queue.machines`：机台数组（公开快照中是以机台编号为键的对象）。每项包含 `id`、`stable_id`、`group_id`、`name`、`operational`、`stop_reason`、`stop_reason_detail`、`playing_started_at`、`registration_count`、`playing`、`waiting_positions` 和 `configuration`。`configuration` 包含 `remark` 和“终端设置”中列出的九个配置字段。`waiting_positions` 的每一项包含 `index`（从 `1` 开始）、`estimated_wait_minutes`、`estimated_wait_uncertain`、`common_play_preview` 和 `registrations`。

`playing` 和 `registrations` 中的每份登记结构如下：

```json
{
  "registration_id": "a10f5015a2e85b12e20af507",
  "display_id": "示例玩家",
  "profile_id": "37e41698-46f8-489b-92dc-d29c71f00f7d",
  "qq_number": "12345678",
  "preference": "OPEN_TO_JOIN",
  "registration_type": "PLAYER_PROFILE",
  "position": "WAITING",
  "waiting_position": 2,
  "created_at": 1784680000000,
  "last_played_at": null,
  "online_registration_pending_check_in": false,
  "online_check_in_started_at": 1784680000000,
  "deferred_once": false,
  "skips_one_turn": false,
  "temporarily_away": false,
  "temporary_away_skipped_turns": 0,
  "defer_one_round_used": false,
  "online_registration_not_played": false,
  "fixed_pair": false,
  "fixed_pair_id": null,
  "profile_nickname": "示例玩家"
}
```

`profile_id` 和 `qq_number` 来自当前登记绑定，没有绑定的登记（如临时登记）两者都是 `null`；QQ 优先取玩家资料中保存的值，并且不受 `qq_visibility` 限制。`profile_nickname` 是该资料的昵称。`registration_type` 为 `TEMPORARY` 或 `PLAYER_PROFILE`。`position` 为 `PLAYING` 或 `WAITING`，`waiting_position` 是所在等待位置的 `index`，处于游玩位置时为 `null`。`skips_one_turn`、`defer_one_round_used` 和 `online_registration_not_played` 的含义见“当前队列”，旧快照中没有时为 `false`；后两项只限制玩家自己的操作，管理后台仍可以为这样的登记暂缓一次、轮空一次或暂时离开。

- `profiles`：当前资料作用域内保存的全部玩家资料，按昵称排序，不区分大小写。每份资料包含“玩家资料字段”一节的字段，以及 `public_player_id`、`avatar_url`、`web_account_bound`、`terminal_editing_allowed`、`visited_venues_public` 和 `web_profile_revision`，不包含 `recent_usage_at`、`public_player_id_aliases` 和 `synced_at`。列表不按终端当前资料库筛选，可能包含终端本机已经没有的资料。
- `capabilities`：供界面决定显示哪些入口的布尔标记。`TERMINAL_POLICY_EDIT` 等于 `management_policy_supported`；`REGISTRATION_CONTROL`、`MACHINE_STATUS_EDIT`、`MACHINE_CONFIGURATION_EDIT`、`BUSINESS_HOURS_EDIT` 和 `COMMON_PLAY_PREVIEW_EDIT` 等于 `management_settings_supported`；`QUEUE_READ_ALL`、`QUEUE_EDIT_ALL`、`QUEUE_REORDER`、`PROFILE_READ_PRIVATE`、`PROFILE_EDIT_ALL`、`PROFILE_DELETE`、`PROFILE_RESET_PASSWORD` 和 `AUDIT_READ` 固定为 `true`。这些标记不反映是否已接管，服务端处理写请求时也不读取它们。

写接口回传的值与响应字段的对应关系：`expected_queue_id` 取 `queue.queue_id`，`expected_queue_revision` 取 `queue.revision`，`expected_machine_configuration_revision` 取 `queue.machine_configuration_revision`，`expected_policy_revision` 和 `expected_management_policy_revision` 取 `queue.terminal_policy.revision`，`expected_settings_revision` 取 `queue.terminal_settings.revision`，`expected_registration_open` 取 `queue.registration_open`，机台稳定身份取机台的 `stable_id`，资料版本取资料的 `profile_revision` 和 `updated_at`。

服务端还没有队列快照时返回 `404` `排队终端暂未同步。`。终端离线不是错误，响应照常返回，`terminal.online` 为 `false`。这个接口对快照的协议版本没有要求：没有声明管理能力的旧版本终端同样可以读取，只是 `terminal_policy.supported` 和 `terminal_settings.supported` 为 `false`。当前快照是测试同步数据时，玩家资料和登记的联系信息取自隔离保存的测试数据。

管理后台每 `30` 秒读取一次管理总览，并在每次命令请求成功返回后再读取一次；请求失败时和重置网页账户密码之后不会额外读取。

### 终端敏感策略

`PATCH /api/queue-management/terminal-policy`

```json
{
  "request_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
  "expected_queue_id": "44444444-5555-4666-8777-888888888888",
  "expected_policy_revision": 2,
  "management_app_bound": true,
  "allow_online_registration": true,
  "allow_defer_one_round": true,
  "allow_temporary_leave": false,
  "onebot_sync_enabled": true,
  "reason": "管理后台接管终端敏感策略"
}
```

建立或解除接管，并设置四个敏感策略开关：是否允许线上登记、暂缓一次和暂时离开，以及 QQ Bot 联动。除 `reason` 外的字段全部必填，请求总是整体提交五个值；只想改变其中一项时，其余各项按管理总览中的当前值填写。`expected_policy_revision` 取 `queue.terminal_policy.revision`。`reason` 的默认值为 `管理后台更新终端敏感策略`。

服务端创建 `UPDATE_TERMINAL_POLICY` 命令，其中带有终端应写入的新策略修订号（当前值加 `1`）。这个接口不要求已接管，它本身就是建立接管的途径；四个开关也可以在 `management_app_bound` 为 `false` 的请求中修改，终端应用后保持未接管。

服务端依次检查：

- `400`：请求体包含其他字段（`请求包含不支持的终端策略字段`），或字段取值无效。
- `404`：还没有队列快照。`503` `现场终端暂时离线，不能更新终端策略`。
- `409` 和 `QUEUE_CONTEXT_CHANGED`：`expected_queue_id` 不是当前队列批次。
- `409` `当前现场终端不支持管理后台策略接管`：快照的 `management_policy_supported` 不为 `true`。
- `409` 和 `TERMINAL_POLICY_CONTEXT_CHANGED`：`expected_policy_revision` 与快照的 `management_policy_revision` 不一致。
- `409` `终端已有策略修改正在等待处理`：该终端已有一条待处理的策略命令。

终端核对队列批次和策略修订号后写入五个值，把 `management_policy_revision` 设为新修订号，并回执 `终端敏感策略已由管理后台更新并接管。` 或 `终端敏感策略已解除管理后台接管。`。提交的值与当前完全相同时，命令同样会被应用，策略修订号照常加 `1`。

### 终端设置

`PATCH /api/queue-management/terminal-settings`

```json
{
  "request_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
  "expected_queue_id": "44444444-5555-4666-8777-888888888888",
  "expected_settings_revision": 5,
  "expected_policy_revision": 2,
  "expected_machine_configuration_revision": 3,
  "expected_registration_open": false,
  "show_common_play_preview": true,
  "business_hours": {
    "enabled": true,
    "use_weekly_schedule": false,
    "default_hours": { "opening_minutes": 600, "closing_minutes": 1320 },
    "weekly_hours": {}
  },
  "machine_groups": [
    { "id": "00000000000000000000000000000001", "name": "分组 1" }
  ],
  "default_machine_group_id": "00000000000000000000000000000001",
  "machines": {
    "A": {
      "stable_id": "0123456789abcdef0123456789abcdef",
      "group_id": "00000000000000000000000000000001",
      "remark": "左侧",
      "configuration": {
        "game_type": "MAIMAI_DX",
        "custom_game_type": null,
        "server": "CHINA",
        "custom_server": null,
        "game_version": null,
        "game_version_visible": false,
        "capacity": 2,
        "solo_round_minutes": 12,
        "shared_round_minutes": 15
      }
    }
  }
}
```

整体替换由管理后台接管的终端设置：是否显示共同游玩预览、营业时间、机台分组，以及全部机台的数量、分组归属和配置。请求提交的是完整的目标状态，没有出现的机台即被删除。0.13.2 加入。

- `expected_settings_revision`、`expected_policy_revision`、`expected_machine_configuration_revision` 和 `expected_registration_open`：分别取管理总览的 `queue.terminal_settings.revision`、`queue.terminal_policy.revision`、`queue.machine_configuration_revision` 和 `queue.registration_open`，四项都必填。
- `business_hours`：完整营业时间表，结构和缺省值与“保存机厅营业时间”中的 `business_hours` 相同。
- `machine_groups`：分组数组，数量为 `1` 至机台数量。每项恰好包含 `id`（32 位小写十六进制）和 `name`（`1` 至 `12` 个字符），`id` 不能重复。`default_machine_group_id` 必须是其中之一，每个分组至少包含一台机台。
- `machines`：以机台编号为键的对象，必须从 `A` 开始连续，共 `1` 至 `10` 台。每台机台恰好包含 `stable_id`（32 位小写十六进制，不能重复）、`group_id`（已有分组的 `id`）、`remark`（`1` 至 `8` 个字符）和 `configuration`。
- `configuration` 恰好包含九个字段：
  - `game_type`：`MAIMAI_DX`、`CHUNITHM`、`ONGEKI`、`DANCE_CUBE`、`TAIKO_NO_TATSUJIN` 或 `OTHER`。
  - `custom_game_type`：最长 `24` 个字符，`game_type` 为 `OTHER` 时必填，其他情况必须为 `null`。
  - `server`：`CHINA`、`INTERNATIONAL`、`JAPAN`、`DABING`、`RINNET`、`OTHER` 或 `HIDDEN`。`game_type` 不是 `MAIMAI_DX`、`CHUNITHM` 或 `ONGEKI` 时只能为 `HIDDEN`。
  - `custom_server`：最长 `24` 个字符，`server` 为 `OTHER` 时必填，其他情况必须为 `null`。
  - `game_version` 和 `game_version_visible`：版本文字最长 `40` 个字符；`game_version_visible` 为 `true` 时必须填写版本，为 `false` 时服务端不保存版本文字。
  - `capacity`：`1` 或 `2`。
  - `solo_round_minutes` 和 `shared_round_minutes`：单人和共同游玩的计划分钟数，`1` 至 `120`。

管理总览中机台的 `configuration` 带有 `remark`；提交时 `remark` 放在机台一级，`configuration` 中不能出现。`reason` 的默认值为 `管理后台更新终端设置`。

服务端创建 `UPDATE_TERMINAL_SETTINGS` 命令，其中带有终端应写入的新设置修订号（当前值加 `1`）。依次检查：

- `400`：请求体包含其他字段（`请求包含不支持的终端设置字段`），或字段不符合上述规则。
- `404`：还没有队列快照。`503` `现场终端暂时离线，不能更新设置`。
- `409` `当前现场终端不支持远程设置。`：快照的 `management_settings_supported` 不为 `true`。
- `409` `请先绑定并接管现场终端设置。`：终端尚未被接管。
- `409` 和 `QUEUE_CONTEXT_CHANGED`：`expected_queue_id` 不是当前队列批次。
- `409` 和 `TERMINAL_SETTINGS_CONTEXT_CHANGED`：`expected_settings_revision` 与快照的 `management_settings_revision` 不一致。
- `409` 和 `TERMINAL_POLICY_CONTEXT_CHANGED`：`expected_policy_revision` 与快照的 `management_policy_revision` 不一致。
- `409` 和 `QUEUE_CONTEXT_CHANGED`：机台配置修订号不一致，或 `expected_registration_open` 与快照的 `registration_control_open` 不一致。
- `409` `请先关闭登记排队，再修改机台数量、游玩容量或计划游玩时间。`：登记排队开放期间，请求改变了机台编号列表，或任何一台机台的 `stable_id`、`capacity`、`solo_round_minutes`、`shared_round_minutes`。
- `409` `请先清空并恢复要删除的机台，再减少机台数量。`：被删除的机台（按 `stable_id` 判断）仍有登记，或处于停止使用状态。
- `409` `终端已有设置修改正在等待处理。`：该终端已有一条待处理的设置命令。

终端再次核对队列批次、接管状态、两个修订号、机台配置修订号和登记排队开关，然后经由与本机设置页相同的写入流程保存，登记排队开放时的限制和删除机台的限制同样适用。只有本机的 `management_settings_revision` 变为新修订号时，终端才回执 `APPLIED`。提交的设置与当前完全相同时，服务端仍会创建命令，但终端不会递增修订号，命令以 `REJECTED` 结束，说明为 `终端设置未能写入本机。`。

### 登记排队开关

`PATCH /api/queue-management/registration-availability`

```json
{
  "request_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
  "expected_queue_id": "44444444-5555-4666-8777-888888888888",
  "expected_queue_revision": 128,
  "expected_management_policy_revision": 2,
  "expected_machine_configuration_revision": 3,
  "expected_registration_open": true,
  "registration_open": false,
  "expected_registration_ids": ["a10f5015a2e85b12e20af507"],
  "confirm_clear_queue": true
}
```

关闭或重新开启登记排队。关闭会清空全部机台上的全部登记；开启会开始一个新的空队列批次，`queue_id` 随之改变。0.13.2 加入。

- `expected_queue_revision`：取管理总览的 `queue.revision`。
- `expected_management_policy_revision`：可以省略，0.13.6 加入。提供时与快照的 `management_policy_revision` 比较。
- `expected_registration_open` 和 `registration_open`：当前状态和目标状态，两者必须不同，否则返回 `400` `登记排队状态没有变化。`。
- `expected_registration_ids`：当前队列中全部登记的编号，包括所有机台的游玩位置和等待位置，最多 `200` 个，不能重复，顺序不限。
- `confirm_clear_queue`：关闭时必须为 `true`，否则返回 `400` `关闭登记前必须确认清空当前全部队列。`；开启时必须为 `false`，否则返回 `400` `开启登记不需要清空确认。`。
- `reason`：默认值为 `管理后台开启登记排队` 或 `管理后台关闭登记排队`。

服务端创建 `SET_REGISTRATION_AVAILABILITY` 命令。依次检查：

- `400`：请求体包含其他字段（`请求包含不支持的登记开关字段`），或字段不符合上述规则。
- `404`：还没有队列快照。`503` `现场终端暂时离线，暂不能开启或关闭登记排队。`。
- `409` `当前现场终端不支持远程登记开关。`：快照的 `management_settings_supported` 不为 `true`。
- `409` `请先绑定并接管现场终端设置。`：终端尚未被接管。
- `409` 和 `QUEUE_CONTEXT_CHANGED`：队列批次或队列修订号、机台配置修订号、已提供的策略修订号、登记排队开关的当前状态（与快照的 `registration_control_open` 比较）、全部登记的编号集合，任何一项与当前快照不一致。策略修订号不一致在这个接口返回的也是 `QUEUE_CONTEXT_CHANGED`，不是 `TERMINAL_POLICY_CONTEXT_CHANGED`。
- `409` `关闭状态下仍有队列登记，请先在现场确认并清空。`：请求开启，但当前队列中仍有登记。
- `409` `已有开启或关闭登记排队的操作正在等待终端处理。`：该终端已有一条待处理的登记排队开关命令。

终端按本机状态再次核对队列批次、接管状态、策略修订号、队列修订号、机台配置修订号、开关状态和全部登记的编号集合，然后执行与现场操作相同的关闭或开启流程，操作来源记为管理后台。重新开启之后队列批次已经改变，后续请求必须使用新的 `queue_id`。

### 机台状态

`PATCH /api/queue-management/machine-status`

```json
{
  "request_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
  "expected_queue_id": "44444444-5555-4666-8777-888888888888",
  "expected_management_policy_revision": 2,
  "expected_machine_configuration_revision": 3,
  "machine_id": "B",
  "expected_machine_stable_id": "fedcba9876543210fedcba9876543210",
  "expected_operational": true,
  "operational": false,
  "stop_reason": "OTHER",
  "stop_reason_detail": "更换按键"
}
```

把一台机台标记为停止使用，或恢复正常使用。0.13.2 加入。

- `machine_id`：机台编号，服务端去掉首尾空白并转为大写。
- `expected_machine_stable_id`：必填，必须与快照中该机台的 `stable_id` 相同。
- `expected_operational` 和 `operational`：当前状态和目标状态，两者必须不同，否则返回 `400` `机台状态没有变化。`。
- `stop_reason`：停止时必填，取值为 `NOT_POWERED_ON`、`NETWORK_DISCONNECTED`、`MAINTENANCE` 或 `OTHER`；恢复时不能填写。
- `stop_reason_detail`：最长 `40` 个字符。`stop_reason` 为 `OTHER` 时必填，其他情况不能填写。
- `expected_management_policy_revision`：可以省略，0.13.6 加入。提供时与快照的 `management_policy_revision` 比较。
- `reason`：默认值为 `管理后台恢复机台` 或 `管理后台停止机台`。

服务端创建 `UPDATE_MACHINE_STATUS` 命令。依次检查：

- `400`：请求体包含其他字段（`请求包含不支持的机台状态字段`），或字段不符合上述规则。
- `404`：还没有队列快照。`503` `现场终端暂时离线，不能修改机台状态。`。
- `409` `当前现场终端不支持远程机台控制。`：快照的 `management_settings_supported` 不为 `true`。
- `409` `请先绑定并接管现场终端设置。`：终端尚未被接管。
- `409` 和 `QUEUE_CONTEXT_CHANGED`：队列批次、机台配置修订号或已提供的策略修订号不一致；机台不存在或 `stable_id` 不符；机台当前状态与 `expected_operational` 不同。
- `409` `这台机台已有状态修改正在等待终端处理。`：同一 `machine_id` 已有一条待处理的机台状态命令。

终端再次核对队列批次、接管状态、策略修订号、机台配置修订号、机台稳定身份和当前状态，然后按现场操作的方式停止或恢复机台，操作来源记为管理后台。

### 终端队列操作

`POST /api/queue-management/terminal-actions`

```json
{
  "request_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
  "expected_queue_id": "44444444-5555-4666-8777-888888888888",
  "expected_queue_revision": 128,
  "expected_machine_configuration_revision": 3,
  "expected_management_policy_revision": 2,
  "action": "MARK_NO_SHOW",
  "machine_id": "A",
  "expected_machine_stable_id": "0123456789abcdef0123456789abcdef",
  "expected_playing_registration_ids": ["a10f5015a2e85b12e20af507"],
  "expected_waiting_positions": [
    ["b20f5015a2e85b12e20af508"],
    ["c30f5015a2e85b12e20af509", "d40f5015a2e85b12e20af50a"]
  ],
  "registration_ids": ["a10f5015a2e85b12e20af507"],
  "no_show_resolution": "MOVE_TO_TAIL"
}
```

远程执行现场终端自己的队列操作，包括新建登记、结束本轮、未到场处理、签到、固定组合、调整顺序和转至其他机台。管理后台的全部队列编辑都通过这个接口完成。它需要 0.13.3 及以上版本的终端，不要求已接管，也不检查终端声明的能力。

每个请求都要回传管理员看到的整台机台的位置：

- `expected_queue_id`、`expected_queue_revision`、`expected_machine_configuration_revision`：取管理总览的 `queue.queue_id`、`queue.revision` 和 `queue.machine_configuration_revision`。任何一台机台的队列发生变化都会使 `queue.revision` 改变；终端每次保存本机状态都会递增它，玩家资料、操作日志或命令回执的变化同样如此。
- `machine_id` 和 `expected_machine_stable_id`：操作的机台及其稳定身份。
- `expected_playing_registration_ids`：该机台游玩位置的登记编号，按顺序排列，`0` 至 `2` 个。
- `expected_waiting_positions`：该机台的全部等待位置，按顺序排列，最多 `20` 个；每个位置是包含 `1` 至 `2` 个登记编号的数组，编号不能重复。
- `expected_management_policy_revision`：可以省略，0.13.6 加入。提供时与快照的 `management_policy_revision` 比较。

其余字段按 `action` 的需要提供：`registration_ids`（操作的登记，最多 `20` 个，缺省为空数组）、`profile_id` 和 `friend_profile_id`（资料 UUID，`null` 或空字符串视为不提供）、`display_id`（登记名称，最长 `18` 个字符）、`preference`（`SOLO` 或 `OPEN_TO_JOIN`）、`target_machine_id` 和 `expected_target_machine_stable_id`、`source_position_index` 和 `destination_position_index`（`0` 至 `19`）、`desired_waiting_positions`、`desired_registration_order`、`no_show_resolution`、`start_next_when_playing_becomes_empty`（缺省为 `true`）和 `advance_when_playing_empty`（缺省为 `false`）。`reason` 的默认值为 `管理后台执行现场终端队列操作`。

机台级操作，`registration_ids` 必须为空：

- `FINISH_ROUND`：结束本轮并开始下一轮。
- `END_ROUND_ONLY`：只结束本轮，游玩位置保持空缺。
- `REMOVE_CURRENT_ROUND_AND_START_NEXT`：移除本轮登记并开始下一轮。
- `ENTER_PLAYING_POSITION`：让等待位置进入游玩位置。
- `RESTART_PLAYING_TIMER`、`RESTART_PENDING_CHECK_IN_TIMERS`、`RESTART_MACHINE_TIMERS`：重新开始本轮游玩计时、待签到计时或这台机台的全部计时。

新建登记，`registration_ids` 必须为空：

- `ADD_TEMPORARY_REGISTRATION`：新建临时登记。需要 `display_id`，不能带 `profile_id` 和 `friend_profile_id`；机台容量为 2 时还需要 `preference`。
- `ADD_PROFILE_REGISTRATION`：为一份玩家资料新建登记。需要 `profile_id`，不能带 `display_id` 和 `friend_profile_id`；机台容量为 2 且资料的默认偏好为 `ASK_EVERY_TIME` 时需要 `preference`。

指定恰好一份登记：

- `MOVE_WAITING_REGISTRATION_INTO_CURRENT_ROUND`：把一份等待登记补入当前游玩位置。
- `DEFER_ONE_ROUND`、`CANCEL_DEFER_ONE_ROUND`：暂缓一次及取消。
- `SKIP_ONE_TURN`、`CANCEL_SKIP_ONE_TURN`：轮空一次及取消。
- `TEMPORARILY_LEAVE`、`CANCEL_TEMPORARY_LEAVE`：暂时离开及取消。暂缓一次、轮空一次和暂时离开只用于等待中的登记；处于游玩位置的登记需要先撤回至等待顺序。管理后台不受 `defer_one_round_used` 和 `online_registration_not_played` 的限制。
- `CHANGE_PREFERENCE`：更改本次游玩偏好，需要 `preference`。
- `RENAME_REGISTRATION`：修改登记名称，需要 `display_id`。
- `CLAIM_WITH_PLAYER_PROFILE`：把临时登记关联到玩家资料，需要 `profile_id`；可以同时带 `preference`，把这份登记的本次游玩偏好改为该值。
- `CHECK_IN`：立即完成线上登记的现场签到。
- `CREATE_FIXED_PAIR_WITH_REGISTRATION`：为这份登记新建朋友的登记并组成固定组合，需要 `friend_profile_id` 或 `display_id`，两者都提供时使用玩家资料。朋友资料的默认偏好为 `ASK_EVERY_TIME` 时还需要 `preference`，否则终端回执 `REJECTED`；提交的值不影响结果，固定组合中两份登记的本次游玩偏好都是 `OPEN_TO_JOIN`。

指定恰好两份登记：

- `CREATE_FIXED_PAIR`：把两份登记组成固定组合。
- `RELEASE_FIXED_PAIR`：解除固定组合。

指定至少一份登记：

- `RETURN_PLAYING_TO_WAITING_FRONT`：把游玩位置的登记退回队首。
- `ADVANCE_TO_WAITING_POSITION`：让指定的等待位置进入游玩位置。
- `MARK_NO_SHOW`：未到场处理，需要 `no_show_resolution`，取值为 `DEFER_ONE_ROUND`、`DEFER_GROUP_ONE_ROUND`、`MOVE_TO_TAIL` 或 `REMOVE`。
- `REMOVE_REGISTRATIONS`：移除所选登记。
- `TRANSFER_REGISTRATIONS`：把所选登记转至其他机台，需要 `target_machine_id` 和 `expected_target_machine_stable_id`，目标机台不能是 `machine_id`。其他操作不能带这两个字段。

调整顺序，`registration_ids` 必须为空：

- `MOVE_WAITING_POSITION`：移动一个等待位置，需要 `source_position_index` 和 `destination_position_index`，两者不能相同。序号从 `0` 开始，等于管理总览中 `waiting_positions` 的 `index` 减 `1`。
- `REPLACE_WAITING_POSITIONS`：按 `desired_waiting_positions` 重排等待位置。新数组必须完整保留当前每个等待位置中的登记，只能改变位置之间的先后。
- `REPLACE_REGISTRATION_ORDER`：按 `desired_registration_order` 重排全部登记。数组必须以当前游玩位置的登记开头且顺序不变，并且恰好包含这台机台当前的全部登记。

`start_next_when_playing_becomes_empty` 只用于 `MARK_NO_SHOW`，`advance_when_playing_empty` 只用于两种建立固定组合的操作。

服务端创建 `MANAGEMENT_QUEUE_ACTION` 命令，并在以下情况拒绝请求：

- `400`：请求体包含其他字段（`请求包含不支持的终端队列操作字段`）、字段取值无效，或不符合所选操作对登记数量和附加字段的要求。
- `404`：还没有队列快照。`503` `现场终端暂时离线，暂不能操作队列。`。
- `409` 和 `QUEUE_CONTEXT_CHANGED`：队列批次、队列修订号、机台配置修订号或已提供的策略修订号与快照不一致；机台的 `stable_id` 不符；游玩位置或等待位置的登记与回传的不一致；转入机台的 `stable_id` 不符。
- `409`，不带 `code`：`machine_id` 或 `target_machine_id` 不存在（`目标机台不存在`）；`registration_ids` 中有登记不在这台机台（`所选登记已经不在目标机台`）；`DEFER_ONE_ROUND`、`SKIP_ONE_TURN` 或 `TEMPORARILY_LEAVE` 所指的登记处于游玩位置（`处于游玩位置的登记不能暂缓一次，请先在现场终端撤回至等待顺序`，轮空一次和暂时离开的提示相应为“不能轮空一次”和“不能暂时离开”）；`desired_waiting_positions` 没有完整保留每个等待位置；`desired_registration_order` 与当前队列不一致。
- `409` `这台机台已有管理操作正在等待终端处理。`：同一 `machine_id` 已有一条待处理的终端队列操作命令。

以下几项服务端不检查，但终端要求满足，否则终端不处理这条命令，命令在超时后被拒绝：`ADD_TEMPORARY_REGISTRATION` 不带 `friend_profile_id`；`ADD_PROFILE_REGISTRATION` 不带 `display_id` 和 `friend_profile_id`；三种调整顺序的操作不带 `registration_ids`；`source_position_index` 和 `destination_position_index` 同时提供或同时省略。

终端再次核对队列批次、机台配置修订号、已提供的策略修订号、机台和转入机台的稳定身份，以及游玩位置和等待位置的登记，然后交给与现场操作相同的队列规则执行，操作来源记为管理后台。终端当前不接受新登记（登记排队已关闭或处于闭店收尾）时，三种会新建登记的操作被拒绝；操作没有使队列发生任何变化时同样回执 `REJECTED`。终端不比较队列修订号，也不要求已接管。操作建立了新登记时，回执中的 `result_registration_id` 是新登记的编号。

管理后台提交后每秒查询一次命令结果，最多 `30` 次，见“管理后台接口”的“查询命令”。

### 立即签到

`POST /api/queue-management/commands`

```json
{
  "request_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
  "operation": "CHECK_IN",
  "registration_id": "a10f5015a2e85b12e20af507",
  "expected_queue_id": "44444444-5555-4666-8777-888888888888",
  "expected_machine_id": "A",
  "expected_position": "WAITING",
  "expected_pending_check_in": true,
  "expected_machine_configuration_revision": 3
}
```

为一份仍待签到的线上登记立即完成现场签到。`operation` 只接受 `CHECK_IN`，`expected_position` 只接受 `WAITING`，`expected_pending_check_in` 必须为 `true`。`reason` 的默认值为 `管理后台立即签到`。0.13.0 加入；当前版本的管理后台（0.13.2）不调用这个接口，签到通过终端队列操作的 `CHECK_IN` 完成。

服务端创建 `QUEUE_OPERATION` 命令，操作为 `CHECK_IN`，来源为 `MANAGEMENT_APP`。这个接口不要求已接管。依次检查：

- `400`：请求体包含其他字段（`请求包含不支持的管理命令字段`）、字段取值无效、缺少 `expected_machine_id`，或 `expected_pending_check_in` 为 `false`（`立即签到只能用于待签到的线上登记。`）。
- `404`：还没有队列快照。`503` `现场终端暂时离线，暂不能立即签到。`。
- `409` 和 `QUEUE_CONTEXT_CHANGED`：队列批次或机台配置修订号已经变化。
- `409`，不带 `code`：目标机台不存在或已停止使用；登记已经不在当前队列中；登记不在 `expected_machine_id` 的等待位置；登记已经完成现场签到；登记没有当前登记绑定，或绑定的玩家资料没有 QQ；这份登记已有一条待处理的排队操作命令。

终端只接受来源为管理后台的 `CHECK_IN`，并再次核对登记所在的位置、固定组合、将暂缓一次、将轮空一次和暂时离开状态以及待签到状态。

### 登记排队操作

`POST /api/queue-management/queue-commands`

```json
{
  "request_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
  "operation": "LEAVE_QUEUE",
  "expected_queue_id": "44444444-5555-4666-8777-888888888888",
  "expected_registration_id": "a10f5015a2e85b12e20af507",
  "expected_machine_id": "A",
  "expected_position": "WAITING",
  "expected_fixed_pair_id": null,
  "expected_absence_status": "NONE",
  "expected_temporary_away_skipped_turns": 0,
  "expected_pending_check_in": false,
  "expected_machine_configuration_revision": 3,
  "expected_machine_stable_id": "0123456789abcdef0123456789abcdef"
}
```

代任意一份登记执行玩家可以远程进行的操作：暂缓一次、轮空一次、暂时离开、取消对应状态、转至其他机台、更改本次游玩偏好和退出排队。它与 Bot 和网页账户的排队操作共用同一套处理流程，确认状态字段及其取值规则与“请求本人排队操作”相同，创建的也是 `QUEUE_OPERATION` 命令，来源为 `MANAGEMENT_APP`。0.13.0 加入；当前版本的管理后台（0.13.2）不调用这个接口，改用终端队列操作。

与玩家接口的差别：

- `operation` 为 `DEFER_ONE_ROUND`、`CANCEL_DEFER_ONE_ROUND`、`SKIP_ONE_TURN`、`CANCEL_SKIP_ONE_TURN`、`TEMPORARILY_LEAVE`、`CANCEL_TEMPORARY_LEAVE`、`TRANSFER_MACHINE`、`CHANGE_PLAY_PREFERENCE`、`LEAVE_QUEUE` 或 `SET_PAIRED_MACHINE_QUEUEING`（需要 `also_queues_at_paired_machine`，见“自动在两台机台之间安排登记”），不接受 `JOIN_QUEUE` 和 `CHECK_IN`。
- 不受 `defer_one_round_used` 和 `online_registration_not_played` 的限制：已暂缓过一次的登记可以再次暂缓一次，已签到但尚未游玩一轮的线上登记也可以暂缓一次、轮空一次或暂时离开。
- 请求不带 `actor_qq`，命令中的 `actor_qq` 是空字符串。
- 目标登记有两种指定方式。提供 `profile_id` 时，服务端按该资料的当前登记绑定查找，资料必须恰好有一份登记。不提供 `profile_id`（省略、`null` 或空字符串）时，目标就是 `expected_registration_id` 指向的登记，可以是没有关联玩家资料的临时登记；这种方式下确认状态字段实际上必须全部提交。
- 只检查终端是否在线，不检查与服务端同步和 QQ Bot 联动是否开启，也不要求已接管。
- `machine_id` 可以提交但不起作用；`reason` 可以提交但不会保存。

八个确认状态字段是 `expected_queue_id`、`expected_registration_id`、`expected_machine_id`、`expected_position`、`expected_fixed_pair_id`、`expected_absence_status`、`expected_temporary_away_skipped_turns` 和 `expected_pending_check_in`，必须全部提交或全部省略。`expected_machine_configuration_revision`、`expected_machine_stable_id` 和 `expected_target_machine_stable_id` 只能与完整的确认状态一起提交，最后一项只用于 `TRANSFER_MACHINE`。`TRANSFER_MACHINE` 需要 `target_machine_id`，`CHANGE_PLAY_PREFERENCE` 需要 `preference`。

服务端在以下情况拒绝请求：

- `400`：请求体包含其他字段（`请求包含不支持的管理队列字段`）、`operation` 无效、`profile_id` 不是 UUID，或确认状态字段不完整。
- `404`：还没有队列快照。
- `409` 和 `QUEUE_CONTEXT_CHANGED`：`expected_machine_configuration_revision` 与快照不一致。只有这一项带有 `code`；`expected_queue_id` 不一致时返回的是不带 `code` 的 `409`。
- `503` `现场终端暂时离线，暂不能执行远程排队操作。`。
- `404` 和 `PROFILE_NOT_FOUND`：提供了 `profile_id`，但当前资料作用域内没有这份资料。
- `409` 和 `REGISTRATION_NOT_FOUND`：没有提供 `profile_id`，并且没有提交 `expected_registration_id`，或它指向的登记不在当前队列中。
- `409`，不带 `code`：确认状态与快照不符；登记所在机台已停止使用而请求的不是退出排队或转至其他机台；待签到登记请求了退出排队以外的操作；现场规则不允许暂缓一次或暂时离开；登记处于游玩位置时请求暂缓一次、轮空一次或暂时离开；转至其他机台时登记处于游玩位置、目标机台已停止使用或登记已满、目标机台容量为 1 而登记属于固定组合；所在机台容量为 1 时更改本次游玩偏好；资料没有恰好一份登记。
- `400`：转至其他机台时目标机台不存在或就是当前机台；更改本次游玩偏好时缺少 `preference`。
- `409` `已有一项操作正在等待终端处理。`：没有提供 `profile_id` 时，指同一份登记已有待处理的排队操作命令；提供了 `profile_id` 时，指该终端有一条待处理命令属于同一份资料，或它的 `actor_qq` 同样是空字符串（即另一条不带 QQ 的管理命令）。

终端对来源为管理后台的命令不检查 QQ 与玩家资料的绑定，其余校验与玩家命令相同。

### 调整登记顺序

`POST /api/queue-management/queue-reorders`

```json
{
  "request_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
  "expected_queue_id": "44444444-5555-4666-8777-888888888888",
  "machine_id": "A",
  "expected_machine_stable_id": "0123456789abcdef0123456789abcdef",
  "expected_machine_configuration_revision": 3,
  "expected_registration_order": [
    "a10f5015a2e85b12e20af507",
    "b20f5015a2e85b12e20af508",
    "c30f5015a2e85b12e20af509"
  ],
  "registration_order": [
    "a10f5015a2e85b12e20af507",
    "c30f5015a2e85b12e20af509",
    "b20f5015a2e85b12e20af508"
  ]
}
```

0.13.0 加入的重排接口，用一份完整的新顺序替换一台机台的等待顺序。`expected_registration_order` 是这台机台当前的全部登记，游玩位置在前，其后按等待顺序排列，最多 `20` 个；`registration_order` 是同一组登记的新顺序，游玩位置的登记必须保持原位。`expected_machine_stable_id` 可以省略。`reason` 的默认值为 `管理后台调整队列顺序`。

服务端创建 `QUEUE_OPERATION` 命令，操作为 `REORDER_QUEUE`，来源为 `MANAGEMENT_APP`。这个接口不要求已接管。服务端在以下情况拒绝请求：

- `400`：请求体包含其他字段（`请求包含不支持的队列重排字段`）、字段取值无效，或两个数组包含的登记不相同（`新的登记顺序必须包含当前机台的全部登记`）。
- `404`：还没有队列快照。`503` `现场终端暂时离线，暂不能调整队列。`。
- `409` 和 `QUEUE_CONTEXT_CHANGED`：队列批次或机台配置修订号已经变化；已提供的 `expected_machine_stable_id` 与机台不符；机台当前的登记顺序与 `expected_registration_order` 不同。
- `409`，不带 `code`：机台不存在或已停止使用；新顺序改变了游玩位置的登记；新顺序与当前顺序相同；这台机台已有一条待处理的重排命令。

当前版本的现场终端（0.13.6）不会执行这个接口创建的命令：终端解析 `QUEUE_OPERATION` 命令时要求命令内容带有 `actor_qq`，而这类命令没有这个字段。命令不会得到回执，最终以 `SERVER_TIMEOUT` 被拒绝。管理后台（0.13.2）也不调用这个接口，调整顺序使用终端队列操作的 `REPLACE_WAITING_POSITIONS`。

### 新建登记

`POST /api/queue-management/registrations`

```json
{
  "request_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
  "profile_id": "37e41698-46f8-489b-92dc-d29c71f00f7d",
  "expected_queue_id": "44444444-5555-4666-8777-888888888888",
  "expected_machine_id": "A",
  "expected_machine_stable_id": "0123456789abcdef0123456789abcdef",
  "expected_machine_configuration_revision": 3,
  "preference": "OPEN_TO_JOIN"
}
```

为一份已有的玩家资料在指定机台新建登记。建立的是普通现场登记，不带线上登记的待签到状态。`profile_id`、`expected_queue_id`、`expected_machine_id` 和 `expected_machine_configuration_revision` 必填，`expected_machine_stable_id` 可以省略。`reason` 的默认值为 `管理后台创建登记`。0.13.0 加入；当前版本的管理后台（0.13.2）不调用这个接口，改用终端队列操作的 `ADD_PROFILE_REGISTRATION`。

游玩偏好按以下规则确定：容量为 1 的机台一律使用 `SOLO`；其他机台在资料默认偏好为 `ASK_EVERY_TIME` 时必须提供 `preference`（`SOLO` 或 `OPEN_TO_JOIN`），否则使用资料的默认偏好，提交的值与之不同时拒绝。

服务端创建 `QUEUE_OPERATION` 命令，操作为 `CREATE_REGISTRATION`，来源为 `MANAGEMENT_APP`。这个接口不要求已接管。依次检查：

- `400`：请求体包含其他字段（`请求包含不支持的创建登记字段`）、`profile_id` 不是 UUID、缺少 `expected_machine_id`，或字段取值无效。
- `404`：还没有队列快照。`503` `现场终端暂时离线，暂不能创建登记。`。
- `409`，不带 `code`：队列批次已经变化；机台配置修订号已经变化；快照的 `registration_open` 为 `false`（`现场当前未使用登记排队，暂不能创建登记。`）；目标机台不存在；已提供的 `expected_machine_stable_id` 与机台不符；目标机台已停止使用；目标机台的登记已满 20 份。这个接口的上下文变化都不带 `QUEUE_CONTEXT_CHANGED`。
- `404` `没有找到这份玩家资料。`。
- `409`：这名玩家已经有一份登记。
- `400` `请选择本次游玩偏好。`：资料默认偏好为 `ASK_EVERY_TIME` 而没有提供 `preference`。`409` `玩家资料的默认游玩偏好已经变化。`：提交的 `preference` 与资料固定的默认偏好不同。
- `409` `这名玩家已有操作正在等待终端处理`：该资料已有任何类型的待处理命令。

终端只接受来源为管理后台的 `CREATE_REGISTRATION`，并再次核对玩家资料、机台配置修订号、机台稳定身份、登记排队是否开放、机台状态和容量，以及该玩家是否已有登记。新登记带有原命令编号，回执丢失后重复领取仍能识别为已经执行。终端应用后，回执中的 `result_registration_id` 是新登记的编号。

### 修改玩家资料

`PATCH /api/queue-management/profiles/<profile_id>`

```json
{
  "request_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
  "nickname": "新昵称",
  "qq_visibility": "TERMINAL_ONLY",
  "terminal_editing_allowed": true
}
```

修改任意一份玩家资料。它与 Bot 的“请求修改玩家资料”是同一实现，创建的也是 `UPDATE_PLAYER_PROFILE` 命令，来源为 `MANAGEMENT_APP`。与 Bot 接口的差别：

- `actor_qq` 可以省略，缺省为空字符串；服务端不要求它与资料的 QQ 一致。
- QQ Bot 联动关闭时仍然可以提交。
- 可以修改 `terminal_editing_allowed` 和 `visited_venues_public`；Bot 接口不接受这两个字段。

请求至少包含一个可修改字段：`nickname`（`1` 至 `18` 个字符）、`gender`（`MALE`、`FEMALE` 或 `UNDISCLOSED`）、`default_preference`（`SOLO`、`OPEN_TO_JOIN` 或 `ASK_EVERY_TIME`）、`qq_visibility`（`TERMINAL_ONLY` 或 `PUBLIC_WEBSITE`）、六个通知字段、`terminal_editing_allowed` 和 `visited_venues_public`。QQ 和 `setup_version` 不能通过这个接口修改。请求不回传资料版本：服务端把自己保存的 `profile_revision` 和 `updated_at` 写入命令，由终端与本机资料比较。命令内容是修改后的完整资料，没有提交的字段沿用服务端保存的值。

这个接口不要求已接管。服务端依次检查：

- `400`：`profile_id` 不是 UUID（`玩家资料编号无效`）；没有任何可修改字段（`没有需要更新的玩家资料字段`）；包含其他字段（`请求包含不支持的玩家资料字段`）；字段取值无效。
- `404` `排队终端暂未同步。`：还没有队列快照。这项检查先于重复请求的判断。
- `409` `request_id 已用于其他命令。`。
- `503` `现场终端暂时离线，暂不能修改玩家资料。`。
- `404` `没有找到这份玩家资料。`。已经删除的资料同样返回这个结果。
- `409` `这个昵称已经用于其他玩家资料。`：同一资料作用域内的其他资料使用了相同昵称，不区分大小写。
- `409` `已有一项操作正在等待终端处理。`：该终端有一条待处理命令属于同一份资料，或它的 `actor_qq` 与本次请求相同。省略 `actor_qq` 时，任何 `actor_qq` 为空字符串的待处理命令都会挡住请求，因此两条不带 `actor_qq` 的资料修改不能同时待处理，即使它们针对不同的资料。

终端对来源为管理后台的命令不比较 QQ，也不受资料的 `terminal_editing_allowed` 限制；该标记只约束现场终端上的手动编辑。本机资料的版本和更新时间与命令一致时，终端检查新昵称不与其他资料或当前队列中的登记冲突，写入本机并把 `profile_revision` 加 `1`；不一致但本机资料已经等于命令目标时视为已经应用，否则拒绝。

终端关闭 QQ Bot 联动期间，服务端在收到快照时会拒绝所有尚未被领取的 `UPDATE_PLAYER_PROFILE` 命令，`result_source` 为 `BOT_DISABLED`，管理后台提交的命令也不例外。这时资料修改只有在终端上传下一份快照之前被领取才会执行。

### 删除玩家资料

`DELETE /api/queue-management/profiles/<profile_id>`

```json
{
  "request_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
  "expected_profile_revision": 7,
  "expected_updated_at": 1784681000000,
  "confirm": true
}
```

永久删除一份玩家资料及其网页账户。0.13.5 加入。服务端收到请求时只创建 `DELETE_PLAYER_PROFILE` 命令，不删除任何数据；现场终端确认删除之后，服务端才清理自己保存的内容。

`expected_profile_revision` 和 `expected_updated_at` 取管理总览中该资料的 `profile_revision` 和 `updated_at`，都是不小于 `1` 的整数。`confirm` 必须是 `true`，否则返回 `400` `必须明确确认删除玩家资料。`。`reason` 的默认值为 `管理后台删除玩家资料`。

服务端依次检查：

- `400`：`profile_id` 不是 UUID（`玩家资料编号无效`）、请求体包含其他字段（`请求包含不支持的删除资料字段`），或字段取值无效。
- `404`：还没有队列快照。`503` `现场终端暂时离线，不能删除玩家资料。`。
- `409` `当前现场终端不支持管理后台删除玩家资料。`：快照的 `management_policy_supported` 不为 `true`。
- `409` `请先绑定并接管现场终端设置。`：终端尚未被接管。
- `404` 和 `PROFILE_ALREADY_DELETED`：这份资料已经删除并留有删除标记。`404` 和 `PROFILE_NOT_FOUND`：没有这份资料。
- `409` 和 `PROFILE_CONTEXT_CHANGED`：`expected_profile_revision` 或 `expected_updated_at` 与服务端保存的不一致。
- `409` `玩家资料仍关联当前登记，请先退出排队或移除登记后再删除。`。
- `409` `这份玩家资料已有操作正在等待现场终端处理。`：该资料已有任何类型的待处理命令。

终端领取命令后按本机状态处理：

- 本机已经没有这份资料：直接回执 `APPLIED`，说明为 `玩家资料已经删除。`，不再检查其他条件。
- 资料仍关联当前登记：回执 `REJECTED`。
- 本机资料的版本或更新时间与命令不一致：回执 `REJECTED`。
- 终端已经解除接管：回执 `REJECTED`。
- 其余情况：删除本机资料，在本机操作日志中记录这次删除（来源为管理后台），回执 `APPLIED`，说明为 `玩家资料已删除，网页账户和现有会话已失效。`。

服务端收到 `APPLIED` 回执时，在保存命令结果的同一事务中完成清理：

- 写入删除标记，记录资料作用域、资料 UUID、删除时间和命令编号。
- 撤销该资料网页账户的全部会话，作废并删除它的绑定会话，删除账户。
- 删除与该资料有关的二维码建档会话、待同步索引和当前资料库索引。
- 删除所有队列批次中指向该资料的登记绑定，以及它的通知收件关系。
- 删除归并到该资料的历史玩家编号（`public_player_id_aliases`）。
- 删除玩家资料本身。

终端回执 `REJECTED` 时服务端不做任何清理。服务端只在命令仍为 `PENDING`，或命令已因超时被拒绝而回执来自此前领取它的终端运行实例时采纳回执；后一种情况下结果为 `APPLIED` 同样执行清理。命令已经因其他原因被服务端拒绝时，回执不被采纳，也不执行清理。资料在终端本机已经删除而服务端仍然保留时，重新提交删除请求即可完成服务端的清理。

删除标记按资料作用域和资料 UUID 保存，服务端没有清除它的逻辑。此后终端快照的 `private_player_profiles` 中再出现这个 UUID 时，服务端跳过这份资料，不会重新建立，它也不会进入当前资料库。再次请求删除返回 `404` 和 `PROFILE_ALREADY_DELETED`；修改玩家资料、新建登记和重置网页账户密码不区分已删除和不存在，都返回 `404` `没有找到这份玩家资料。`，登记排队操作返回 `PROFILE_NOT_FOUND`。

删除不会移除玩家已上传的头像文件，原头像地址仍然可以读取。0.13.5 之前的终端不认识这类命令，不会回执，命令在超时后被拒绝。

### 重置网页账户密码

`POST /api/queue-management/profiles/<profile_id>/password`

```json
{
  "new_password": "<新密码>",
  "new_password_confirmation": "<新密码>"
}
```

为已绑定网页账户的玩家设置新密码，并撤销该账户的全部网页会话；玩家需要用新密码重新登录。不需要提供原密码。密码规则与“密码与尝试次数限制”相同。成功返回 `200`：

```json
{
  "ok": true,
  "profile_id": "37e41698-46f8-489b-92dc-d29c71f00f7d",
  "detail": "玩家网页后台密码已修改，原有登录会话已全部退出。"
}
```

这是本节唯一不创建终端命令的写接口：服务端直接更新账户的密码摘要，不经过现场终端，也不改变资料版本。它没有 `request_id`，不是幂等的；不检查终端是否在线和是否已接管；请求体中的其他字段被忽略。服务端不为这次修改保存命令或日志记录。

- `400`：`profile_id` 不是 UUID（`玩家资料编号无效`），或请求体不是 JSON 对象。
- `400`，`code` 为 `PASSWORD_REQUIRED`、`PASSWORD_TOO_SHORT`、`PASSWORD_TOO_LONG` 或 `PASSWORD_INVALID`，`field` 为 `password`：新密码或确认密码不符合密码规则。
- `400` 和 `PASSWORD_CONFIRMATION_MISMATCH`：两次输入的新密码不同。
- `404` `没有找到这份玩家资料。`。
- `409` `玩家尚未绑定网页后台，暂不能修改密码。`：资料没有网页账户，或没有处于已绑定状态。

### 查询命令

`GET /api/queue-management/commands/<command_id>`

`command_id` 是创建命令时使用的 `request_id`。返回命令对象，适用于本节任何接口创建的命令。

- `400` `命令编号无效。`：`command_id` 不是 UUID。
- `404` `没有找到这条命令。`：命令不存在，或已在保留期之后删除。
- `404` `没有找到这条管理命令。`：命令不是由管理接口创建的。

`status` 为 `PENDING`、`APPLIED` 或 `REJECTED`，终端给出的说明位于 `result_detail`。终端队列操作和新建登记建立了新登记时，`result_registration_id` 是新登记的编号。`result_source` 说明结果由谁给出，命令尚未完成时为 `null`：

- `TERMINAL`：现场终端的处理结果。
- `SERVER_TIMEOUT`：命令在 `QUEUE_COMMAND_TIMEOUT_SECONDS`（默认 `600` 秒）内没有得到回执，被服务端拒绝，说明为 `终端未在有效时间内处理这次操作，请重新提交。`。此前已领取该命令的终端运行实例之后仍可以提交实际结果，状态可能由 `REJECTED` 变为 `APPLIED`。
- `SYNC_DISABLED`：终端上传的快照表明与服务端同步已关闭，尚未被领取的终端队列操作、策略、设置、登记排队开关、机台状态和删除玩家资料命令被服务端拒绝。管理后台创建的 `QUEUE_OPERATION` 和 `UPDATE_PLAYER_PROFILE` 命令不在此列；终端关闭与服务端同步后不再读取命令，其中的 `QUEUE_OPERATION` 命令会一直等到超时；终端关闭同步时上传的快照同时把 `onebot_sync_enabled` 报为 `false`，尚未被领取的 `UPDATE_PLAYER_PROFILE` 命令因此以 `BOT_DISABLED` 被拒绝。
- `BOT_DISABLED`：终端上传的快照表明 QQ Bot 联动已关闭，尚未被领取的 `UPDATE_PLAYER_PROFILE` 命令被服务端拒绝。
- `SERVER_MIGRATION`：测试数据与正式数据之间发生切换，或终端接管、更换运行实例后队列批次或机台稳定身份已经变化，见“权威终端与同步方式”。

已完成的命令保留 `QUEUE_COMMAND_RETENTION_SECONDS`（默认 `2592000` 秒），之后删除。

管理后台只在提交终端队列操作之后轮询这个接口，每秒一次，最多 `30` 次。其他写接口返回后，它显示创建命令的响应并重新读取管理总览。

### 管理日志

`GET /api/queue-management/logs?queue_id=<UUID>&limit=<条数>&before=<游标>&source=<操作来源>`

与“公开日志”是同一实现，查询参数、响应字段和错误完全相同，只是需要管理令牌。响应不包含 QQ、资料 UUID 等私有数据。管理后台发起、由终端执行的操作在日志中的 `operation_source` 为 `MANAGEMENT_APP`，可以用 `source=MANAGEMENT_APP` 筛选。这个接口不检查终端是否在线。0.13.2 加入。

## 终端回流接口

现场终端通过本节接口取回在服务端一侧产生的内容：云端资料库、待执行命令和“使用移动设备登记”的会话。除移动设备登记页面使用的三个公开接口外，本节接口都使用“终端请求头”一节列出的请求头：`X-Queue-Schema-Version` 必填；声明版本 `8` 时必须同时携带 `X-Queue-Venue-ID`，缺失或格式无效返回 `409` `VENUE_ID_REQUIRED`，与服务端当前机厅不同返回 `409` `VENUE_MISMATCH`。拉取命令和命令回执只接受当前权威运行实例的请求，其他实例得到 `409` `STALE_TERMINAL_INSTANCE`；同步云端资料和创建移动设备登记会话不检查运行实例。

终端只在以下条件同时满足时同步云端资料和拉取命令：本机玩家资料、命令回执记录和队列状态已经加载完成，应用重启后是否继续使用上次队列的询问也已经处理；构建带有云端同步功能，与服务端同步已开启，连接已配置；机厅核对已放行线上访问，见“机厅与终端身份”。单次请求的连接和读取超时均为 `8` 秒。

### 同步云端资料

`GET /api/queue-terminal/profiles`

终端读取服务端保存的完整私有资料库，用来补回本机缺失的资料，并取回只在服务端产生的变化：六位玩家编号、网页账户对资料和头像的修改、二维码建档的新资料，以及重复资料的别名。接口只读，没有查询参数，带有非空的 `qq` 参数时返回 `400`；服务端还没有队列快照时返回 `404`。

终端约每 `30` 秒读取一次，与拉取命令在同一个循环中进行。读取失败时不等下一个 30 秒，而是在下一轮循环（约 3 秒后）重试。

```json
{
  "queue_id": "44444444-5555-4666-8777-888888888888",
  "revision": 128,
  "bot_qq": "87654321",
  "profile_aliases": {
    "99999999-aaaa-4bbb-8ccc-dddddddddddd": "37e41698-46f8-489b-92dc-d29c71f00f7d"
  },
  "profiles": [
    {
      "profile_id": "37e41698-46f8-489b-92dc-d29c71f00f7d",
      "public_player_id": "123456",
      "public_player_id_aliases": ["654321"],
      "avatar_url": "https://example.com/api/player-avatars/<64 位十六进制引用>.webp",
      "nickname": "示例玩家",
      "profile_revision": 8,
      "synced_at": 1784681030000
    }
  ],
  "capabilities": {
    "profile_updates": false,
    "terminal_is_source_of_truth": true
  }
}
```

示例中的资料省略了与“玩家资料字段”相同的其余字段。

- `queue_id`、`revision`：服务端当前快照的队列批次和修订号。
- `bot_qq`：Koishi 上报的 Bot QQ，没有上报时为 `null`，见“上报 Bot QQ 与版本”。它始终按 `QUEUE_PROFILE_SCOPE_ID` 读取。
- `profile_aliases`：别名 UUID 到保留资料 UUID 的映射，见“重复资料与别名”。
- `profiles`：按昵称和 UUID 排序，不包含别名资料。每份资料包含“玩家资料字段”一节的全部字段，以及 `public_player_id_aliases` 和 `synced_at`。`avatar_url` 是完整地址，没有头像时为 `null`。
- `capabilities`：对终端固定为 `profile_updates: false` 和 `terminal_is_source_of_truth: true`。

终端只读取 `profiles`、`profile_aliases` 和 `bot_qq`；响应中没有 `profiles` 数组时，整次读取按失败处理。终端逐份检查取回的资料，丢弃 UUID 无效、昵称为空或超过 `18` 个字符、`usage_count` 为负、时间戳不是正数、QQ 格式无效，或者 `gender`、`default_preference`、`qq_visibility` 不是已知取值的资料。`avatar_url` 只接受不超过 `512` 个字符的 `http` 或 `https` 完整地址，其他取值按没有头像处理；`bot_qq` 只接受格式有效的 QQ。

终端按以下规则把取回的资料并入本机资料库：

- 本机没有的 UUID 可以补回；同 UUID 只有云端 `profile_revision` 更高时才会覆盖本机。两种情况都要求本机没有其他资料使用相同的昵称（不区分大小写）或 QQ，并且昵称不与当前队列中不属于这份资料的登记重复；否则这份资料暂不接受。
- `profile_revision` 相同时一般不覆盖。唯一的例外是云端带有六位玩家编号，并且与本机资料的差别只在 `public_player_id` 或 `public_player_id_aliases`，此时终端改用服务端的编号。
- 云端版本更低的资料不会覆盖本机。
- 云端没有的本机资料不会被删除。`profiles` 和 `profile_aliases` 都为空时终端不做任何处理。
- `profile_aliases` 最多读取 `500` 条，链式别名解析到最终的资料，目标不在 `profiles` 中的条目被忽略。保留的资料写入本机后，终端删除本机的别名资料，并把引用它的登记改为引用保留的资料。

终端接受回流后先写入本机，再随下一次快照确认给服务端。

资料删除不通过这个接口传播：响应不含已删除资料的列表，删除只以 `DELETE_PLAYER_PROFILE` 命令到达当时的权威终端。仍保留这份资料的终端会继续上传它，服务端每次都忽略。

这个接口不把 `X-Device-ID` 与当前快照所属的终端比较，资料作用域取自服务端当前保存的快照，而不是请求方。当前快照是测试数据时，任何通过鉴权的终端读到的都是该测试终端的隔离资料库。

### 拉取命令

`GET /api/queue-terminal/commands`

服务端把来自 Bot、网站、网页账户、移动设备登记页面和管理后台的修改保存为待执行命令，由现场终端取回、校验并执行。终端约每 `3` 秒读取一次：上一轮处理结束后等待 3 秒再开始下一轮。请求没有查询参数和请求体。

```json
{
  "commands": [
    {
      "command_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
      "type": "UPDATE_PLAYER_PROFILE",
      "payload": {},
      "status": "PENDING",
      "created_at": 1784681000000,
      "claimed_at": 1784681002000,
      "claimed_terminal": "11111111-2222-4333-8444-555555555555",
      "claimed_instance": "66666666-7777-4888-8999-aaaaaaaaaaaa",
      "completed_at": null,
      "result_source": null,
      "result_detail": null,
      "result_registration_id": null
    }
  ]
}
```

示例省略了 `payload` 的内容。`created_at`、`claimed_at` 和 `completed_at` 是毫秒时间戳。命令的 `status` 只有 `PENDING`、`APPLIED` 和 `REJECTED` 三个取值，这个接口返回的都是 `PENDING`。终端只读取 `command_id`、`type`、`created_at` 和 `payload`。服务端只从 `payload` 中去掉内部使用的 `_queue_storage_id`；多数命令的 `payload` 还带有 `_request`，它是服务端用来比较重复请求的记录，终端不读取。

#### 领取、租约与超时

- 命令在创建时归属当时保存快照的终端。接口只返回归属于请求方 `X-Device-ID` 且状态为 `PENDING` 的命令，每次最多 `20` 条，按创建时间排序。
- 请求的 `X-Device-ID`、运行实例编号和代次必须与当前快照的记录完全一致，否则返回 `409` `STALE_TERMINAL_INSTANCE`；服务端还没有快照时同样如此。旧版终端未发送实例请求头时，服务端以稳定的 `X-Device-ID` 作为兼容实例，代次记为 `0`；同一终端由发送实例请求头的版本上传过快照之后，不带实例请求头的请求会被判为过期实例。
- 返回即领取：服务端在命令上记录 `claimed_at`、`claimed_terminal` 和 `claimed_instance`，状态仍是 `PENDING`，没有单独的“已领取”状态。
- 领取后进入租约，时长为 `QUEUE_COMMAND_CLAIM_LEASE_SECONDS`，默认 `15` 秒，最短 `1` 秒。租约内的命令不会再返回给任何请求，包括领取它的实例；租约结束后，尚未回执的命令可以由当前权威实例重新领取。
- 新的运行实例或接管的终端上传快照后，原有的领取记录被清除，不必等待租约结束，见“权威终端与同步方式”。
- 命令自创建起超过 `QUEUE_COMMAND_TIMEOUT_SECONDS`（默认 `600` 秒）仍为 `PENDING` 时，服务端把它改为 `REJECTED`，`result_source` 为 `SERVER_TIMEOUT`，`result_detail` 为 `终端未在有效时间内处理这次操作，请重新提交。`。超时不由定时任务处理，而是在拉取命令、提交回执、创建命令和查询命令结果时顺带检查。
- 已完成的命令在 `QUEUE_COMMAND_RETENTION_SECONDS`（默认 30 天）之后删除。
- 同一终端上，同一命令类型针对同一份玩家资料（`payload` 顶层的 `profile_id`）最多只有一条 `PENDING` 命令。

`result_source` 记录结果的来源：`TERMINAL` 是终端回执；`SERVER_TIMEOUT` 是上述超时；`SERVER_MIGRATION` 是终端接管、运行实例更替或同步方式切换时由服务端拒绝；`BOT_DISABLED` 和 `SYNC_DISABLED` 是终端上传的快照关闭了 QQ Bot 联动或与服务端同步，服务端拒绝尚未被领取的相关命令。后三种的条件见“上传队列快照”和“权威终端与同步方式”。其中 `BOT_DISABLED` 不区分来源，管理后台创建的 `UPDATE_PLAYER_PROFILE` 命令在被领取前同样会被拒绝。

#### 终端处理

- 终端按返回顺序逐条处理。每条命令的首次处理结果先作为回执记录与队列状态一起写入本机，然后才提交回执；本机写入失败时不提交，在之后的轮询中重试。只有本机持久化成功后才回执 `APPLIED`。
- 本机已有回执记录的命令再次到达时，终端不重新判断，直接重发首次结果。回执记录保留最近 `500` 条。
- 由命令建立的登记会记录原命令编号。线上加入、管理后台新建登记和移动设备登记的命令在回执丢失后再次到达时，终端据此识别为已经执行，不会建立第二份登记。
- 类型未知、`payload` 无法解析或不满足下文字段约束的命令被直接跳过：终端不提交回执，命令保持 `PENDING`，租约结束后会被再次领取，直到服务端按超时拒绝。

下文列出各类命令的 `payload`。其中登记编号是 24 位小写十六进制字符串，机台稳定身份是 32 位小写十六进制字符串，`preference` 为 `SOLO` 或 `OPEN_TO_JOIN`，`reason` 是不超过 `200` 个字符的操作说明。

#### 资料与排队命令

`UPDATE_PLAYER_PROFILE`

修改一份玩家资料。由 `PATCH /api/queue-bot/profiles/<profile_id>`（`operation_source` 为 `QQ_BOT`）和 `PATCH /api/queue-management/profiles/<profile_id>`（`MANAGEMENT_APP`）创建。网页账户修改资料不产生命令，见“网页修改回流终端”。

- `profile_id`、`qq_number`：目标资料及其在服务端保存的 QQ；资料没有 QQ 时 `qq_number` 是空字符串。QQ 不能通过命令修改。
- `actor_qq`、`operation_source`：发起请求的 QQ 和来源。管理后台的命令中 `actor_qq` 可以是空字符串，终端不读取它；`operation_source` 缺失时终端按 `QQ_BOT` 处理。
- `expected_updated_at`、`expected_profile_revision`：创建命令时服务端保存的 `updated_at` 和 `profile_revision`。
- `nickname`、`gender`、`default_preference`、`qq_visibility`、六个通知字段和 `setup_version`：完整的目标状态。服务端把请求修改的字段叠加在已保存的资料上，没有被请求修改的字段保持原值。
- `terminal_editing_allowed`、`visited_venues_public`：只出现在管理后台的命令中，缺失表示不修改。

`qq_number` 必须是空字符串或 `5` 至 `12` 位数字；来源不是管理后台时，空字符串不会让命令被跳过，但这样的命令通不过下面的第 2 步；昵称必须是 `1` 至 `18` 个字符。终端依次校验：

1. 来源不是管理后台而终端已关闭 QQ Bot 联动时拒绝。
2. 资料 UUID 在本机存在；来源不是管理后台时，本机资料的 QQ 还必须与 `qq_number` 一致。
3. `expected_profile_revision` 和 `expected_updated_at` 都与本机一致。任何一项不一致时，本机资料已经等于目标状态则视为幂等成功并回执 `APPLIED`，否则拒绝。
4. 新昵称不与其他玩家资料或当前队列中的其他登记冲突，比较时不区分大小写。

应用时 `profile_revision` 加 `1`，`updated_at` 取终端当前时间，资料随下一次快照上传。

`QUEUE_OPERATION`

针对一份登记或一台机台的排队操作，`operation_source` 为 `QQ_BOT`、`WEBSITE_REMOTE` 或 `MANAGEMENT_APP`。`operation` 的取值和来源：

- `JOIN_QUEUE`：网站线上登记和 Bot 的线上加入。
- `DEFER_ONE_ROUND`、`CANCEL_DEFER_ONE_ROUND`、`SKIP_ONE_TURN`、`CANCEL_SKIP_ONE_TURN`、`TEMPORARILY_LEAVE`、`CANCEL_TEMPORARY_LEAVE`、`TRANSFER_MACHINE`、`CHANGE_PLAY_PREFERENCE`、`LEAVE_QUEUE`：Bot、网页账户（`POST /api/player-account/queue-commands`）和管理后台（`POST /api/queue-management/queue-commands`）对已有登记的操作。
- `CHECK_IN`：管理后台为待签到的线上登记立即签到（`POST /api/queue-management/commands`）。
- `CREATE_REGISTRATION`：管理后台用玩家资料新建登记（`POST /api/queue-management/registrations`）。
- `REORDER_QUEUE`：管理后台调整一台机台的登记顺序（`POST /api/queue-management/queue-reorders`）。

字段：

- `queue_id`、`machine_configuration_revision`：创建命令时的队列批次和机台配置修订号。
- `actor_qq`：玩家的 QQ。管理后台的命令中可以是空字符串，但键必须存在。
- `profile_id`：玩家资料 UUID。只有管理后台针对没有关联资料的登记时可以缺失。
- `profile_identity_verified`：只出现在网页账户的命令中，值为 `true`，表示服务端已凭登录会话确认资料身份。`WEBSITE_REMOTE` 的非加入操作必须带有它，其他命令带有它时无效。
- `machine_id`、`machine_stable_id`：登记所在或要加入的机台编号及其稳定身份。
- `JOIN_QUEUE` 和 `CREATE_REGISTRATION`：另有服务端确定后的 `preference`，不含 `registration_id` 和 `target_machine_id`。
- 针对已有登记的操作：另有 `registration_id` 和确认时的登记状态 `expected_position`（`WAITING` 或 `PLAYING`）、`expected_fixed_pair_id`（固定组合编号或 `null`）、`expected_absence_status`（`NONE`、`DEFER_ONE_ROUND`、`SKIP_ONE_TURN` 或 `TEMPORARILY_AWAY`）、`expected_temporary_away_skipped_turns`（`0` 至 `3`）和 `expected_pending_check_in`。服务端总是按创建命令时的快照填写这五项；`expected_position` 出现时，后三项必须同时出现。
- `TRANSFER_MACHINE`：另有 `target_machine_id` 和 `target_machine_stable_id`，不含 `preference`。`CHANGE_PLAY_PREFERENCE`：另有 `preference`。`CHECK_IN`：`expected_position` 必须是 `WAITING`。
- `SET_PAIRED_MACHINE_QUEUEING`：另有布尔值 `also_queues_at_paired_machine`，其余字段与上面几项操作相同。`JOIN_QUEUE` 和移动设备登记命令在请求带有选择时也有 `also_queues_at_paired_machine`，没有时不含这个键。见“自动在两台机台之间安排登记”。
- `REORDER_QUEUE`：`expected_registration_order` 和 `registration_order`，各是最多 `20` 个不重复的登记编号，两者包含的登记必须相同；不含 `profile_id`、`registration_id` 和确认状态。
- `CHECK_IN`、`CREATE_REGISTRATION` 和 `REORDER_QUEUE` 还带有 `management_reason` 和固定为 `management-app` 的 `management_actor_id`，终端不读取。

终端重新校验：

- `queue_id` 与当前队列批次一致；`machine_configuration_revision`、`machine_stable_id` 和 `target_machine_stable_id` 出现时与本机一致。
- 来源对应的开关：`WEBSITE_REMOTE` 要求与服务端同步开启，`QQ_BOT` 要求 QQ Bot 联动开启，管理后台的命令不受这两个开关限制。
- 玩家资料存在，并且本机资料的 QQ 与 `actor_qq` 一致。管理后台的命令和带有 `profile_identity_verified` 的命令不比较 QQ。
- `JOIN_QUEUE`：现场规则允许线上登记，登记排队开放，机台存在、正常使用且登记未满 `20` 份，该玩家没有登记，昵称也不与队列中的登记重复。容量为 1 的机台一律使用 `SOLO`；资料默认偏好为 `ASK_EVERY_TIME` 时必须带有 `preference`，否则使用资料的默认偏好，命令中的值与之不同则拒绝。建立的登记排在等待末端，带有待签到状态。
- `CREATE_REGISTRATION`：只接受管理后台的命令。机台、重复登记和偏好的检查与 `JOIN_QUEUE` 相同，但不检查是否允许线上登记，建立的登记不带待签到状态。
- 针对已有登记的操作：登记仍在队列中（已经离队时，`LEAVE_QUEUE` 视为已执行，其余操作被拒绝）并且仍关联这份资料；登记已经处于目标状态时视为已执行；除 `CHECK_IN` 外，`machine_id` 必须与登记实际所在机台一致；位置和固定组合编号与确认状态一致，除取消暂缓一次、取消轮空一次和取消暂时离开外，暂缓、轮空或暂离状态、轮空次数和签到状态也必须一致（轮空次数按终端上报的值比较：登记不处于暂时离开或将轮空一次时上报 `0`；终端自己把已轮空次数保留到这份登记游玩一轮之后，这个保留值不上报）；所在机台正常使用（`LEAVE_QUEUE` 和 `TRANSFER_MACHINE` 除外，机台停止使用时仍可执行）；处于游玩位置的登记不接受 `DEFER_ONE_ROUND`、`SKIP_ONE_TURN` 和 `TEMPORARILY_LEAVE`；待签到登记只接受 `LEAVE_QUEUE` 和管理后台的 `CHECK_IN`。任一状态改变后，终端拒绝旧命令。
- 来源不是管理后台时：已签到但尚未游玩一轮的线上登记（快照中 `online_registration_not_played` 为 `true`）不接受 `DEFER_ONE_ROUND`、`SKIP_ONE_TURN` 和 `TEMPORARILY_LEAVE`；暂缓一次已经使用、此后尚未游玩一轮的登记（`defer_one_round_used` 为 `true`）不接受 `DEFER_ONE_ROUND`。服务端创建命令前按快照做同样的检查，只为让玩家尽早看到原因。
- `DEFER_ONE_ROUND` 和 `TEMPORARILY_LEAVE` 还要求现场规则允许；`SKIP_ONE_TURN` 没有对应的现场规则开关。`TRANSFER_MACHINE` 要求登记不在游玩位置，目标机台存在、正常使用且登记未满。容量为 1 的机台上不能修改本次偏好。
- `CHECK_IN`：只接受管理后台的命令，登记必须在等待位置；登记已经完成签到时视为已执行。

`REORDER_QUEUE` 目前不能送达：服务端下发的这类命令不含 `actor_qq`，而终端要求这个键存在，因此 0.13.6 的终端会跳过它，命令最终按超时被拒绝。随同发布的管理端应用也不调用对应接口。

`MOBILE_DEVICE_REGISTRATION`

“使用移动设备登记”的提交结果，由 `POST /api/queue-mobile/sessions/<session_token>/submit` 创建。

```json
{
  "queue_id": "44444444-5555-4666-8777-888888888888",
  "machine_configuration_revision": 3,
  "machine_id": "A",
  "machine_stable_id": "0123456789abcdef0123456789abcdef",
  "actor_qq": "12345678",
  "preference": "OPEN_TO_JOIN",
  "operation_source": "MOBILE_DEVICE",
  "session_id": "77777777-8888-4999-8aaa-bbbbbbbbbbbb",
  "profile": {
    "mode": "EXISTING",
    "profile_id": "37e41698-46f8-489b-92dc-d29c71f00f7d",
    "expected_profile_revision": 7,
    "completion": null
  }
}
```

- `queue_id`、`machine_id`、`machine_stable_id`：会话固定的队列批次和机台。`machine_configuration_revision` 是提交时快照的机台配置修订号。
- `actor_qq`：这份资料的 QQ；`preference`：服务端确定后的本次偏好。
- `session_id`：会话编号，终端用它判断命令是否来自当前显示的二维码。
- `profile.mode` 为 `EXISTING` 时：`profile_id` 是所选资料，`expected_profile_revision` 是提交时的资料版本。资料已经完整时 `completion` 为 `null`；补全旧资料时 `completion` 包含 `qq_number`、`qq_visibility`、六个通知字段和值为 `1` 的 `setup_version`。
- `profile.mode` 为 `NEW` 时：`profile_id` 是服务端生成的 UUID，`profile.profile` 包含 `nickname`、`gender`、`default_preference`、`qq_number`、`qq_visibility`、六个通知字段和值为 `1` 的 `setup_version`；没有 `expected_profile_revision` 和 `completion`。

终端依次校验：队列批次、机台配置修订号和机台稳定身份；与服务端同步开启，现场规则允许线上登记，登记排队开放；机台存在、正常使用且登记未满 `20` 份；所选资料存在且版本与 `expected_profile_revision` 一致，完整的资料不接受补全内容，不完整的资料必须带有补全内容；资料的 QQ 与 `actor_qq` 一致；昵称和 QQ 没有被其他玩家资料使用（没有 QQ 的旧重复资料除外）；该玩家没有登记，昵称也不与队列中的登记重复；`preference` 与资料的默认偏好相符，容量为 1 的机台一律使用 `SOLO`。

通过后终端写入资料与登记，再回执 `APPLIED`。这条路径建立普通现场登记，不带线上登记的待签到状态；成功登记后才增加资料使用次数和最近使用时间。

#### 管理后台命令

以下六种命令只由管理后台接口创建，`operation_source` 固定为 `MANAGEMENT_APP`，取其他值的命令被终端跳过。创建它们的接口见“管理后台接口”。除 `MANAGEMENT_QUEUE_ACTION` 外，这些命令的操作说明位于 `reason`，终端只检查长度。

`MANAGEMENT_QUEUE_ACTION`

对一台机台执行与现场终端相同的队列操作，由 `POST /api/queue-management/terminal-actions` 创建。

- `queue_id`、`queue_revision`、`machine_configuration_revision`、`expected_management_policy_revision`：创建命令时的队列批次、队列修订号、机台配置修订号和接管策略修订号，最后一项可以为 `null`。
- `action`、`machine_id`、`machine_stable_id`：动作和目标机台。
- `expected_playing_registration_ids`：创建命令时游玩位置上的登记编号，最多 `2` 个。`expected_waiting_positions`：各等待位置上的登记编号，最多 `20` 个位置，每个位置 `1` 至 `2` 个。
- `registration_ids`：动作针对的登记，最多 `20` 个，缺省为空数组。
- `start_next_when_playing_becomes_empty`（缺省 `true`）、`advance_when_playing_empty`（缺省 `false`）。
- 按动作出现：`profile_id`、`friend_profile_id`、`display_id`（最多 `18` 个字符）、`preference`、`target_machine_id` 与 `target_machine_stable_id`（成对出现）、`source_position_index` 与 `destination_position_index`（成对出现，从 `0` 开始）、`desired_waiting_positions`、`desired_registration_order` 和 `no_show_resolution`（`DEFER_ONE_ROUND`、`DEFER_GROUP_ONE_ROUND`、`MOVE_TO_TAIL` 或 `REMOVE`）。
- `management_reason` 和固定为 `management-app` 的 `management_actor_id`，终端不读取。

`action` 共 31 种，对 `registration_ids` 和附加字段的要求如下：

- 不带登记：`FINISH_ROUND`、`END_ROUND_ONLY`、`REMOVE_CURRENT_ROUND_AND_START_NEXT`、`ENTER_PLAYING_POSITION`、`RESTART_PLAYING_TIMER`、`RESTART_PENDING_CHECK_IN_TIMERS`、`RESTART_MACHINE_TIMERS`；`ADD_TEMPORARY_REGISTRATION`（需要 `display_id`，不能带有 `profile_id` 或 `friend_profile_id`）；`ADD_PROFILE_REGISTRATION`（需要 `profile_id`，不能带有 `display_id` 或 `friend_profile_id`）；`MOVE_WAITING_POSITION`（需要两个不同的位置序号）；`REPLACE_WAITING_POSITIONS`（需要 `desired_waiting_positions`）；`REPLACE_REGISTRATION_ORDER`（需要 `desired_registration_order`）。
- 恰好一份登记：`MOVE_WAITING_REGISTRATION_INTO_CURRENT_ROUND`、`DEFER_ONE_ROUND`、`CANCEL_DEFER_ONE_ROUND`、`SKIP_ONE_TURN`、`CANCEL_SKIP_ONE_TURN`、`TEMPORARILY_LEAVE`、`CANCEL_TEMPORARY_LEAVE`、`CHECK_IN`；`CHANGE_PREFERENCE`（需要 `preference`）；`RENAME_REGISTRATION`（需要 `display_id`）；`CLAIM_WITH_PLAYER_PROFILE`（需要 `profile_id`）；`CREATE_FIXED_PAIR_WITH_REGISTRATION`（需要 `friend_profile_id` 或 `display_id`）。
- 恰好两份登记：`CREATE_FIXED_PAIR`、`RELEASE_FIXED_PAIR`。
- 至少一份登记：`RETURN_PLAYING_TO_WAITING_FRONT`、`ADVANCE_TO_WAITING_POSITION`、`REMOVE_REGISTRATIONS`；`MARK_NO_SHOW`（需要 `no_show_resolution`）；`TRANSFER_REGISTRATIONS`（需要与 `machine_id` 不同的目标机台）。

终端依次校验：`queue_id`；`machine_configuration_revision`；`expected_management_policy_revision` 不为 `null` 时与本机的接管策略修订号一致；机台和目标机台的稳定身份；游玩位置和各等待位置上的登记与 `expected_playing_registration_ids`、`expected_waiting_positions` 完全一致，包括顺序和分组；`registration_ids` 都在这台机台的队列中。`queue_revision` 只要求是正整数，终端不拿它与本机修订号比较。新建登记的动作还要求登记排队开放、引用的玩家资料存在；容量为 1 的机台使用 `SOLO`，资料默认偏好为 `ASK_EVERY_TIME` 时必须带有 `preference`；`ADD_TEMPORARY_REGISTRATION` 在容量为 2 的机台上也必须带有 `preference`，否则被拒绝。队列规则拒绝这项操作或队列没有发生变化时，终端回执 `REJECTED`。

`UPDATE_TERMINAL_POLICY`

管理后台接管或解除接管终端的敏感策略，由 `PATCH /api/queue-management/terminal-policy` 创建。

- `queue_id`、`expected_policy_revision`、`next_policy_revision`：`next_policy_revision` 必须等于 `expected_policy_revision` 加 `1`。
- `management_app_bound`、`allow_online_registration`、`allow_defer_one_round`、`allow_temporary_leave`、`onebot_sync_enabled`：五个布尔值，全部必填。
- `reason`。

终端校验 `queue_id` 与当前队列批次一致、`expected_policy_revision` 与本机的接管策略修订号一致。应用时写入四项策略和接管状态，并把修订号改为 `next_policy_revision`。

`SET_REGISTRATION_AVAILABILITY`

开启或关闭登记排队，由 `PATCH /api/queue-management/registration-availability` 创建。关闭会清空当前全部队列。

- `queue_id`、`expected_queue_revision`、`expected_machine_configuration_revision`、`expected_management_policy_revision`（可以为 `null`）。
- `expected_registration_open`、`registration_open`：两者必须不同。
- `expected_registration_ids`：创建命令时队列中的全部登记编号，最多 `200` 个；开启登记时必须为空。
- `confirm_clear_queue`：关闭登记时为 `true`，开启时为 `false`。
- `reason`。

终端依次校验：`queue_id`；终端仍由管理后台接管；接管策略修订号（不为 `null` 时）；队列修订号；机台配置修订号；登记开关的当前状态；当前全部登记的编号集合与 `expected_registration_ids` 完全相同。开启登记会开始新的空队列。

`UPDATE_TERMINAL_SETTINGS`

替换营业时间、机台布局与配置、机台分组和共同游玩预览开关，由 `PATCH /api/queue-management/terminal-settings` 创建。

- `queue_id`、`expected_settings_revision`、`next_settings_revision`（等于前者加 `1`）、`expected_policy_revision`、`expected_machine_configuration_revision`、`expected_registration_open`。
- `show_common_play_preview`：是否显示共同游玩预览。
- `business_hours`：完整营业时间表，结构与“保存机厅营业时间”的 `business_hours` 相同。
- `machine_groups`、`default_machine_group_id`、`machines`：见下例。`machines` 的键必须是从 `A` 开始连续的机台编号，分组数量不超过机台数量。
- `reason`。

```json
{
  "machine_groups": [
    { "id": "fedcba9876543210fedcba9876543210", "name": "示例分组" }
  ],
  "default_machine_group_id": "fedcba9876543210fedcba9876543210",
  "machines": {
    "A": {
      "stable_id": "0123456789abcdef0123456789abcdef",
      "group_id": "fedcba9876543210fedcba9876543210",
      "remark": "示例机台",
      "configuration": {
        "game_type": "MAIMAI_DX",
        "custom_game_type": null,
        "server": "HIDDEN",
        "custom_server": null,
        "game_version": null,
        "game_version_visible": false,
        "capacity": 2,
        "solo_round_minutes": 12,
        "shared_round_minutes": 15
      }
    }
  }
}
```

机台布局经终端自身的规范化后必须保持不变，否则命令被跳过。终端依次校验：`queue_id`；终端仍由管理后台接管；`expected_policy_revision`；`expected_settings_revision`；机台配置修订号；登记开关的当前状态。应用时整体替换上述设置，并把设置修订号改为 `next_settings_revision`。命令中的设置与本机完全相同时修订号不变，终端回执 `REJECTED`。

`UPDATE_MACHINE_STATUS`

停止或恢复一台机台，由 `PATCH /api/queue-management/machine-status` 创建。

- `queue_id`、`expected_management_policy_revision`（可以为 `null`）、`expected_machine_configuration_revision`。
- `machine_id`、`machine_stable_id`。
- `expected_operational`、`operational`：两者必须不同。
- `stop_reason`：`NOT_POWERED_ON`、`NETWORK_DISCONNECTED`、`MAINTENANCE` 或 `OTHER`。停止机台时必填，恢复时为 `null`。
- `stop_reason_detail`：最多 `40` 个字符，`stop_reason` 为 `OTHER` 时必填，其他情况为 `null`。
- `reason`。

终端依次校验：`queue_id`；终端仍由管理后台接管；接管策略修订号（不为 `null` 时）；机台仍在配置中；机台配置修订号；机台稳定身份；机台当前是否正常使用与 `expected_operational` 一致。

`DELETE_PLAYER_PROFILE`

删除一份玩家资料，由 `DELETE /api/queue-management/profiles/<profile_id>` 创建。

- `profile_id`、`expected_updated_at`、`expected_profile_revision`：目标资料，以及创建命令时的 `updated_at` 和 `profile_revision`。
- `reason`：缺省为 `管理后台删除玩家资料`。
- `profile_scope_id`、`queue_id`：资料作用域和创建命令时的队列批次。终端不读取，服务端在收到回执后用 `profile_scope_id` 完成删除。

终端的处理：本机已经没有这份资料时直接回执 `APPLIED`；资料仍关联当前登记（队列中名称与资料昵称相同的登记也算在内，不区分大小写）、`profile_revision` 或 `updated_at` 与本机不一致，或终端已解除管理后台接管时拒绝；否则从本机资料库删除并回执 `APPLIED`。服务端收到 `APPLIED` 后的处理见“命令回执”。

### 使用移动设备登记

现场终端为一台机台创建短时会话，把返回的网页地址显示为二维码。玩家用手机打开后选择、补全或新建玩家资料并提交，服务端生成一条 `MOBILE_DEVICE_REGISTRATION` 命令，由终端校验后建立登记。

`POST /api/queue-terminal/mobile-registration-sessions`

终端使用同步令牌创建会话。请求体只能包含以下四个键，`machine_stable_id` 可以省略：

```json
{
  "request_id": "77777777-8888-4999-8aaa-bbbbbbbbbbbb",
  "queue_id": "44444444-5555-4666-8777-888888888888",
  "machine_id": "A",
  "machine_stable_id": "0123456789abcdef0123456789abcdef"
}
```

`request_id` 同时是会话编号和幂等键。新建会话返回 `201`；相同的 `request_id` 重复发送且参数一致时返回 `200` 和已保存的会话，不重新检查队列状态；参数不一致返回 `409`。请求体不是 JSON 对象、缺少必填键、包含其他键，或 `request_id`、`queue_id` 不是 UUID、`machine_id` 去掉首尾空白后不是 `1` 至 `8` 个字符的字符串、提供的 `machine_stable_id` 不是 32 位小写十六进制字符串时返回 `400`，这项检查先于以上判断。

```json
{
  "session_id": "77777777-8888-4999-8aaa-bbbbbbbbbbbb",
  "session_token": "<一次性会话令牌>",
  "queue_id": "44444444-5555-4666-8777-888888888888",
  "machine_id": "A",
  "machine_stable_id": "0123456789abcdef0123456789abcdef",
  "status": "OPEN",
  "command_id": null,
  "created_at": 1784681000000,
  "expires_at": 1784681600000,
  "registration_url": "https://example.com/queue-status?mobile_registration=<一次性会话令牌>"
}
```

- `status` 为 `OPEN`、`SUBMITTED` 或 `INVALIDATED`。提交被接受后变为 `SUBMITTED`，同时写入 `command_id`。终端上传的快照关闭了与服务端同步时，该终端仍为 `OPEN` 的会话变为 `INVALIDATED`；上传的快照使服务端在测试数据与正式数据之间切换时，切换前的快照对应的终端仍为 `OPEN` 的会话变为 `INVALIDATED`。
- `registration_url` 是 `QUEUE_PUBLIC_SITE_URL` 加上 `mobile_registration` 查询参数。`QUEUE_PUBLIC_SITE_URL` 为空时使用 `<QUEUE_CORS_ORIGIN>/queue-status`；两者都为空时返回 `503`。
- 有效期为 `QUEUE_MOBILE_SESSION_TTL_SECONDS`，默认 `600` 秒，最短 `60` 秒。会话在提交或过期 `QUEUE_MOBILE_SESSION_RETENTION_SECONDS`（默认 `86400` 秒）之后删除。
- 终端只读取 `session_id`、`registration_url` 和 `expires_at`。响应不含机台配置，机台配置由登记页面读取会话时取得。

新建会话时服务端依次检查，这些错误都不带 `code`：已有队列快照（否则 `404`）；终端在线（`503`）；请求来自当前快照对应的终端、`queue_id` 与当前队列批次一致、与服务端同步已开启、不处于闭店收尾、现场规则允许线上登记、登记排队开放（均为 `409`）；机台存在（`404`）；`machine_stable_id` 与机台当前的稳定身份一致、机台正常使用、登记未满 `20` 份（均为 `409`）。

旧版服务端不接受 `machine_stable_id`：收到 `400` 和 `移动设备登记会话参数不完整` 时，终端去掉这个键重试一次。

登记页面使用以下三个公开接口。它们不需要令牌，凭 URL 中的会话令牌访问；令牌格式无效或会话不存在时返回 `404`。

`GET /api/queue-mobile/sessions/<session_token>?q=<搜索内容>`

读取会话、机台和可选的玩家资料。响应包含：

- `session`：`session_id`、`queue_id`、`machine_id`、`machine_name`、`machine_configuration_revision`、`machine_configuration`（机台的公开配置）和 `expires_at`。
- `profiles`：资料列表，按使用次数和最近使用时间从高到低排列，不分页，不包含别名资料。每份资料包含 `profile_id`、`public_player_id`、`public_player_id_aliases`、`nickname`、`gender`、`default_preference`、`qq_number`、`qq_present`、`qq_public`、六个通知字段、`usage_count`、`last_used_at`、`setup_complete` 和 `profile_revision`，不含头像。
- `profile_aliases`、`bot_qq` 和 `capabilities`。`capabilities` 固定为 `create_profile: true`、`complete_profile: true`、`edit_complete_profile: false` 和 `terminal_is_source_of_truth: true`。`bot_qq` 按快照所属的资料作用域读取，测试同步期间为 `null`。

`TERMINAL_ONLY` 资料的 `qq_number` 为 `null`，只通过 `qq_present` 和 `qq_public` 说明是否已填写 QQ 及其公开范围。`setup_complete` 表示资料的 `setup_version` 不小于 `1` 并且已填写 QQ。旧资料补全页会读取并保留现有通知开关，避免覆盖玩家此前通过 Bot 调整的偏好。

`q` 去除首尾空白后最多 `32` 个字符，超过返回 `400`。它按昵称（不区分大小写）、QQ、六位玩家编号和历史编号做包含匹配；`TERMINAL_ONLY` 资料同样可以按 QQ 搜到，但不返回 QQ 原文。

读取和提交时，服务端都重新检查会话和现场状态，不满足时返回带 `code` 的错误：

- `409` `SESSION_INVALIDATED`、`409` `SESSION_SUBMITTED`、`410` `SESSION_EXPIRED`：会话已失效、已提交或已过期。
- `404` `TERMINAL_NOT_READY`：服务端还没有队列快照。`409` `SESSION_STALE`：当前快照的队列批次或终端与会话不同。
- `503` `TERMINAL_OFFLINE`、`503` `WEBSITE_SYNC_DISABLED`：终端离线，或与服务端同步已关闭。
- `409` `REGISTRATION_CLOSED`：处于闭店收尾，或登记排队没有开放。`409` `ONLINE_REGISTRATION_DISABLED`：现场规则不允许线上登记。
- `409` `MACHINE_NOT_FOUND`、`MACHINE_CHANGED`、`MACHINE_STOPPED`、`MACHINE_FULL`：机台已不存在、稳定身份已变化、已停止使用或登记已满 `20` 份。

`POST /api/queue-mobile/sessions/<session_token>/submit`

```json
{
  "request_id": "88888888-9999-4aaa-8bbb-cccccccccccc",
  "profile_id": "37e41698-46f8-489b-92dc-d29c71f00f7d",
  "expected_profile_revision": 7,
  "preference": "OPEN_TO_JOIN"
}
```

请求体只能包含 `request_id`、`profile_id`、`expected_profile_revision`、`profile_completion`、`new_profile`、`preference` 和 `also_queues_at_paired_machine`（见“自动在两台机台之间安排登记”）。`profile_id` 和 `new_profile` 必须恰好提供一个：

- 选择现有资料：`expected_profile_revision` 必填。资料尚未完成必填设置或没有 QQ 时必须提交 `profile_completion`，它必须恰好包含 `qq_visibility` 和六个通知字段，资料没有 QQ 时再加上 `qq_number`。资料已经完整时不能提交 `profile_completion`，完整资料不能从登记页面编辑。
- 新建资料：`new_profile` 必须恰好包含 `nickname`、`gender`、`default_preference`、`qq_number`、`qq_visibility` 和六个通知字段，不能同时提交 `expected_profile_revision` 或 `profile_completion`。新资料的 UUID 由服务端生成。
- `preference`：容量为 1 的机台一律使用 `SOLO`，不看提交的值。其他机台在资料默认偏好为 `ASK_EVERY_TIME` 时必填，否则可以省略，提交的值与默认偏好不同时拒绝。

`request_id` 成为命令编号，不能与任何已有命令重复。提交被接受时返回 `202`，服务端在同一事务中创建命令并封闭会话，一个会话不会产生第二条命令。已提交的会话再次收到相同的 `request_id` 时返回 `200` 和当前结果；`request_id` 不同返回 `409`，不带 `code`。校验失败时会话保持 `OPEN`，可以修正后再次提交。

响应包含 `session_id`、`command_id`、`status`、`profile_id`（所选资料或新资料的 UUID）、`created_at`、`completed_at` 和 `result_detail`。`status` 取命令的状态：`PENDING`、`APPLIED` 或 `REJECTED`。

除上面列出的会话错误外，提交还可能返回：

- `400`，不带 `code`：请求体不是 JSON 对象、包含其他字段、`request_id` 不是 UUID，或没有恰好提供 `profile_id` 和 `new_profile` 之一。
- `409` `REQUEST_ID_CONFLICT`：`request_id` 已用于其他命令。`409` `SESSION_COMMAND_UNAVAILABLE`：会话已提交，但命令记录已被删除。
- `404` `PROFILE_NOT_FOUND`、`409` `PROFILE_UPDATED`：所选资料不存在，或 `expected_profile_revision` 与当前版本不一致。
- `400` `PROFILE_SETTINGS_INVALID`：资料、补全内容或偏好不符合上述要求。
- `409` `QQ_ALREADY_USED`、`NICKNAME_ALREADY_USED`：QQ 或昵称（不区分大小写）已被其他玩家资料使用，所选资料的旧重复资料除外。
- `409` `PLAYER_ALREADY_REGISTERED`、`NICKNAME_IN_QUEUE`：该玩家已有登记，或昵称与队列中的登记重复。
- `409` `PLAYER_OPERATION_SYNCING`：该玩家的加入刚被终端应用，快照尚未同步。`409` `PLAYER_OPERATION_PENDING`：该玩家已有一条待终端处理的命令。

`GET /api/queue-mobile/sessions/<session_token>/result`

读取终端的最终处理结果。会话尚未提交且未过期时返回 `200` 和 `{"status":"OPEN","session_id":"<会话编号>"}`；已提交时返回与提交接口相同的结果对象。会话尚未提交而已过期返回 `410` `SESSION_EXPIRED`，会话已失效返回 `409` `SESSION_INVALIDATED`。

终端未在 `QUEUE_COMMAND_TIMEOUT_SECONDS` 内处理时，结果变为 `REJECTED`。已提交的会话不再检查有效期：结果在会话记录删除之前一直可以读取，此后返回 `404`；命令记录先被删除时 `status` 为 `UNAVAILABLE`。

登记页面打开时先读取结果，状态为 `OPEN` 才读取会话；提交后约每 `1.5` 秒读取一次结果，直到状态不再是 `PENDING`。

终端收到命令后的校验见“拉取命令”中的 `MOBILE_DEVICE_REGISTRATION`。

### 命令回执

`POST /api/queue-terminal/commands/<command_id>/result`

```json
{"status":"APPLIED","detail":"玩家资料已由终端更新。"}
```

- `status`：必填，`APPLIED` 或 `REJECTED`。
- `detail`：处理说明，可以省略，最多 `500` 个字符，终端发送前会截断到这个长度。服务端去除首尾空白后保存为 `result_detail`，空白内容保存为 `null`。
- `result_registration_id`：命令建立的登记编号，可以省略，只能与 `APPLIED` 一起提交。

成功时返回 `200` 和完整的命令对象，结构与拉取命令返回的相同。服务端依次检查：

- `400`：`command_id` 不是 UUID，请求体不是 JSON 对象，`status`、`detail` 或 `result_registration_id` 无效，或运行实例请求头无效。
- `404`：命令不存在。`403`：命令不属于请求方的 `X-Device-ID`。
- `409` `STALE_TERMINAL_INSTANCE`：请求方不是当前快照对应的运行实例。
- `409` `COMMAND_NOT_CLAIMED`：命令可以写入结果，但不是由请求方实例领取的，包括从未被领取的命令。

结果按以下规则写入：

- 状态为 `PENDING` 的命令可以写入结果。已被服务端按超时拒绝的命令，如果此前由同一终端的同一运行实例领取，终端迟到的结果仍会写入并覆盖超时结果。
- 写入时服务端记录 `status`、`completed_at`、`result_detail` 和 `result_registration_id`，`result_source` 为 `TERMINAL`。
- 其他情况下命令已有最终结果：服务端不作修改，返回 `200` 和已保存的命令。先到的结果生效，重复提交是幂等的。

服务端只记录结果，不据此修改队列或玩家资料，也不能自行把待执行命令直接改成正式资料；数据变化随终端的下一次快照到达。唯一的例外是 `DELETE_PLAYER_PROFILE`：收到 `APPLIED` 时，服务端在同一事务中记下删除标记，删除这份资料及其网页账户（并撤销会话）、绑定会话、建档会话、待同步索引、当前资料库索引、登记绑定、通知收件关系和历史编号。此后快照中的同一 UUID 被忽略。

已记录的 `APPLIED` 结果还有两个用途：已绑定网页账户的资料凭它认定终端上传的修改来自一条已应用的资料修改命令，见“网页账户字段”；线上加入和移动设备登记被应用后的 `30` 秒内，快照尚未跟上时，服务端以 `PLAYER_OPERATION_SYNCING` 拒绝同一玩家的再次加入。

终端把队列变动和命令首次处理结果写入同一份本机状态后才提交回执，随后正常上传新的快照。回执记录另有一份独立存储；它暂时写入失败时，重启后仍可从队列状态恢复首次结果并继续补写。回执请求失败时终端不单独重试：命令在租约结束后再次下发，终端重发已记录的首次结果。

`result_registration_id` 的提交规则：

- 首次执行成功时，`QUEUE_OPERATION` 的 `JOIN_QUEUE` 和 `CREATE_REGISTRATION`，以及建立了登记的 `MANAGEMENT_QUEUE_ACTION` 会提交它。
- 命令被识别为已经执行而本机没有回执记录时，只有 `JOIN_QUEUE` 会重新查出并提交。
- 重发已有回执时，原样重复当时记录的内容。
- `MOBILE_DEVICE_REGISTRATION` 的回执不提交它。

线上加入的命令变为 `APPLIED` 即表示登记已经写入本机，网站会立即报告创建成功，并用 `result_registration_id` 保存“标记为自己”；随后刷新公开快照以补充当前位置。即使登记在快照刷新前已经退出，或快照暂时上传失败，网站也不会一直停留在等待同步状态。

## 远程操作边界

远程操作指不在现场终端上直接进行、由服务端转交终端执行的操作。命令和由它产生的公开事件都带有 `operation_source`，标明来源：`WEBSITE_REMOTE`（网站）、`QQ_BOT`、`MOBILE_DEVICE`（使用移动设备登记）或 `MANAGEMENT_APP`（管理后台）。无论来源，服务端只保存待执行命令；队列、玩家资料和终端设置由现场终端按最新状态校验并落盘后才改变。

### 网站与 QQ Bot

- 网站未登录时只开放查看队列、查看公开日志和创建线上登记。线上登记接口不要求登录，只凭提交的 QQ 找到玩家资料；这样建立的登记带有待签到状态，需要到现场签到，见“网站线上登记”。
- 登录网页账户后，可以管理账户所属玩家资料的本人登记，包括暂缓一次、轮空一次、暂时离开、取消对应状态、转至其他机台、修改本次偏好和退出排队。登记按资料 UUID 匹配，请求不能自行指定 QQ。登录后加入排队仍使用线上登记接口。
- QQ Bot 可以为 `actor_qq` 对应的玩家创建线上登记，对其本人登记执行与网页账户相同的操作，并请求修改该 QQ 对应的玩家资料。服务端只按 Bot 提交的 `actor_qq` 认定操作者，不另行验证玩家身份，见“私有 Bot 接口”。
- 使用移动设备登记由现场终端发起：玩家扫描终端显示的二维码，在手机上选择或填写玩家资料，由终端建立不带待签到状态的普通现场登记。
- 玩家在网页上对本人玩家资料的修改不是命令，直接保存在服务端，不要求终端在线，见“网页修改回流终端”。

以下限制对网站和 QQ Bot 同样适用：

- 只能操作本人的登记，不能调整其他玩家。同一 QQ 或同一份玩家资料已有待执行命令时，新的请求被拒绝。
- 待签到登记只允许退出排队。
- 处于游玩位置的登记不能暂缓一次、轮空一次或暂时离开，需要先在现场终端撤回至等待顺序；提示为 `处于游玩位置的登记不能暂缓一次，请先在现场终端撤回至等待顺序。`、`处于游玩位置的登记不能轮空一次，请先在现场终端撤回至等待顺序。` 和 `处于游玩位置的登记不能暂时离开，请先在现场终端撤回至等待顺序。`。管理后台的两个队列操作接口同样拒绝。
- 暂缓一次之后，登记游玩一轮之前不能再次暂缓一次，提示为 `这份登记已暂缓过一次，游玩一轮之后才能再次暂缓。`。已签到的线上登记在游玩一轮之前不能暂缓一次、轮空一次或暂时离开，提示为 `已签到的线上登记在游玩一轮之前不能延后。`。这两项限制对管理后台不适用。
- 登记所在机台已停止使用时，这份登记只接受退出排队和转至其他机台（目标机台须在正常使用，其余条件与平时相同）；其他远程操作返回 `409`，提示为机台名称加一个空格，再加 `已停止使用，恢复正常使用前只能退出排队或转至其他机台`。
- 不能远程签到、标记未到场、结束本轮、拖动顺序、报告机台停止使用或修改终端设置。

### 管理后台

管理后台使用独立的 `QUEUE_MANAGEMENT_TOKEN`，它创建的命令 `operation_source` 均为 `MANAGEMENT_APP`。网站和 QQ Bot 不能执行的现场管理操作，管理后台都可以远程执行：

- 队列：对任意登记签到、标记未到场、结束本轮、移除登记、调整等待位置和登记顺序、在机台之间转移登记、新建登记，也可以代任意登记执行暂缓一次、轮空一次、暂时离开、取消对应状态、转至其他机台、修改本次偏好和退出排队，不受暂缓一次只能使用一次和已签到线上登记的限制。命令类型为 `MANAGEMENT_QUEUE_ACTION` 或 `QUEUE_OPERATION`。
- 机台与登记排队：停止或恢复机台（`UPDATE_MACHINE_STATUS`）；关闭或重新开启登记排队（`SET_REGISTRATION_AVAILABILITY`），关闭会清空当前全部登记，重新开启会开始新的空队列。
- 终端策略与设置：是否允许线上登记、暂缓一次、暂时离开和 QQ Bot 联动（`UPDATE_TERMINAL_POLICY`）；是否显示共同游玩预览、营业时间、机台分组和机台配置（`UPDATE_TERMINAL_SETTINGS`）。
- 玩家资料：修改任意玩家资料，包括 `terminal_editing_allowed` 和 `visited_venues_public`（`UPDATE_PLAYER_PROFILE`）；删除玩家资料（`DELETE_PLAYER_PROFILE`）。

各接口见“管理后台接口”，命令内容见“终端回流接口”。创建命令的管理接口都要求终端在线。

这些操作按是否需要接管现场终端分为三类：

- 队列操作和玩家资料修改不要求接管。
- 修改终端策略要求终端在快照中声明 `management_policy_supported`。这条命令同时写入 `management_app_bound`，即管理后台是否接管现场终端的敏感设置；接管和解除接管都由它完成。
- 修改终端设置、切换登记排队和修改机台状态要求快照声明 `management_settings_supported` 且 `management_app_bound` 为 `true`；删除玩家资料要求 `management_policy_supported` 且 `management_app_bound` 为 `true`。未接管时服务端返回 `409`，说明为 `请先绑定并接管现场终端设置`。终端执行这四类命令时再次检查接管状态。

接管期间，现场终端不能在本机修改由管理后台控制的设置（三项排队规则、QQ Bot 联动、共同游玩预览、营业时间、机台分组和机台配置），也不能在本机报告机台停止使用或恢复机台。

重置网页账户密码是管理后台唯一不经过终端的写操作：服务端直接更新密码，并撤销该账户的全部网页会话。

### 现场开关

以下设置由现场终端随快照上传，服务端和终端分别据此拒绝远程操作：

- 与服务端同步（`website_remote_enabled`）关闭后，终端停止拉取命令和云端资料。服务端拒绝新的网站操作；收到关闭后的快照时，拒绝尚未被领取的命令，并使尚未提交的移动设备登记会话失效，涉及的命令类型见“上传队列快照”。终端在同步关闭时上传的 `onebot_sync_enabled` 也是 `false`，QQ Bot 联动随之停止。
- QQ Bot 联动（`onebot_sync_enabled`）关闭后，除上报 Bot QQ 以外的 `/api/queue-bot/` 接口返回 `503`。服务端收到关闭后的快照时，拒绝尚未被领取的 QQ Bot 命令和玩家资料修改命令；后者不区分来源，管理后台创建的也在其中。
- `queue_rules` 的三项规则分别决定网站和 QQ Bot 能否暂缓一次、暂时离开和创建线上登记。轮空一次没有对应的规则。“允许线上登记”关闭后只拒绝新的加入请求，不影响已有登记的其他操作。
- 登记排队关闭或处于闭店收尾时，不接受新的线上登记。

与服务端同步的开关、同步方式、服务器地址和终端同步令牌只能在现场终端上修改，没有任何远程命令可以改变它们。

### 终端的最终校验

命令只交给持有当前快照的终端运行实例。风险操作携带玩家身份和确认时的登记状态；终端按最新状态重新校验队列批次、机台配置修订号、机台稳定身份、登记状态和来源对应的开关，任一项不符即拒绝，本机持久化成功后才回执 `APPLIED`。终端还按来源限制操作：

- `QUEUE_OPERATION` 中的 `CHECK_IN`、`REORDER_QUEUE` 和 `CREATE_REGISTRATION` 只接受来自 `MANAGEMENT_APP` 的命令。
- `WEBSITE_REMOTE` 的非加入类命令必须带有 `profile_identity_verified`，即由登录的网页账户提交。
- `QQ_BOT` 和未登录网站的命令要求本机玩家资料的 QQ 与 `actor_qq` 一致；`MANAGEMENT_APP` 和带有 `profile_identity_verified` 的命令不做这项比较。

## 构建与安全

### 构建变体

App 有三个产品变体。连接信息是否进入安装包，由变体和构建参数共同决定：

- `local`：纯本地版，应用 ID 带 `.local` 后缀。Gradle 强制写入空的服务器地址和令牌并关闭云端同步，不受开发机上任何构建参数影响；它也不申请联网权限。
- `terminal`：现场终端。只有设置 `ENABLE_TERMINAL_BUILD=true` 才会构建。服务器地址和终端令牌默认不写入安装包，同时设置 `EMBED_TERMINAL_SYNC_CONFIG=true` 才会写入。
- `management`：管理后台，应用 ID 带 `.management` 后缀。默认参与构建，把 `ENABLE_MANAGEMENT_BUILD` 设为 `true` 以外的值可以关闭。构建时提供的 `QUEUE_MANAGEMENT_URL` 和 `QUEUE_MANAGEMENT_TOKEN` 会写入安装包。

终端令牌只可能出现在 `terminal` 变体中，管理令牌只可能出现在 `management` 变体中。

需要为受控终端预置连接时，构建参数可以保存在开发账户的 `~/.gradle/gradle.properties`：

```properties
ENABLE_TERMINAL_BUILD=true
EMBED_TERMINAL_SYNC_CONFIG=true
QUEUE_SYNC_URL=https://example.com/api/queue-status
QUEUE_SYNC_TOKEN=<终端令牌>
```

以上参数以及管理后台的三个参数也可以用同名环境变量提供。写入安装包的值只是默认连接：终端本机保存过连接设置后不再读取它；管理后台优先使用本机保存的连接，本机和安装包都没有连接时，首次启动要求输入。

构建脚本只决定安装包里有没有连接信息，不负责签名，也不限制分发。是否可以公开，取决于包内是否含有连接信息：

- 可以公开发行的是签名后的 `assembleLocalRelease`，以及未设置 `EMBED_TERMINAL_SYNC_CONFIG` 的 `assembleTerminalRelease`（0.10.2 起提供）。后者不含服务器地址和令牌，安装后由管理员在应用设置中填写连接。
- 预置了服务器地址或令牌的终端包和管理后台包只能在受控设备之间传递，不得公开上传；令牌泄露后应立即在服务端更换。
- `packageLocalDebugApk`、`packageTerminalDebugApk` 和 `packageManagementDebugApk` 把调试包复制到 `output/apk/` 并按版本号命名，用于现场测试，不得公开上传。

### 应用内连接设置

管理员也可以在现场终端的应用设置中填写连接。连接项只有在登记排队已关闭、与服务端同步已关闭并保存之后才能编辑。地址必须使用 HTTPS，可以填写站点根地址或完整接口地址，不能带查询参数、片段或用户信息，最长 `500` 个字符，保存时规范化到 `/api/queue-status`；终端令牌至少 `32` 个 UTF-8 字节，最多 `256` 个字符。连接设置只保存在终端本机。

更换地址或令牌会清除上一服务器的同步错误、Bot QQ 和未完成的移动登记会话。操作日志只记录连接配置已更新，不写入地址和令牌。

保存新的连接或重新开启与服务端同步后，终端先通过安装信息接口核对服务器所属的机厅。核对完成前不上传队列、不恢复私有玩家资料，也不处理远程命令；服务器属于另一机厅时，线上访问保持暂停，直到连接设置改变，或管理员在登记排队已关闭、与服务端同步已开启时于应用设置中确认“重新绑定当前服务器”（终端放弃原机厅 ID 和机厅编号，向当前服务器重新登记，成功后采用其机厅 ID 并恢复线上访问）。现场排队不受影响。

### 服务端凭据

服务端有三个互相独立的服务令牌，都以 `Authorization: Bearer <令牌>` 提交：

- `QUEUE_SYNC_TOKEN`：现场终端使用，对应 `POST /api/queue-status` 和 `/api/queue-terminal/` 下的接口。持有者可以上传队列快照、读取完整玩家资料库、领取并回执命令。
- `QUEUE_BOT_TOKEN`：只配置在 Koishi 服务端，对应 `/api/queue-bot/` 下的接口。持有者可以读取完整玩家资料库、当前登记绑定和通知事件的全部 QQ 收件人，按命令编号读取任意来源的命令，并以任意 `actor_qq` 的名义请求修改玩家资料和排队操作。
- `QUEUE_MANAGEMENT_TOKEN`：管理后台使用，对应 `/api/queue-management/` 下的接口。持有者可以读取完整玩家资料库和带 QQ 的当前队列，并执行“远程操作边界”列出的全部管理操作。

每个令牌至少 `32` 个 UTF-8 字节。未配置或长度不足时，只有对应角色的接口返回 `503`。`QUEUE_SYNC_TOKEN` 与 `QUEUE_BOT_TOKEN` 相同时，终端接口和 Bot 接口都返回 `503`；`QUEUE_MANAGEMENT_TOKEN` 与另外两个令牌之一相同时，管理后台接口返回 `503`。令牌不符返回 `401`。

三个令牌都是服务级凭据，不是某一名玩家的登录凭据，不能交给玩家、浏览器或其他不受控客户端；网站前端不使用其中任何一个。玩家在浏览器中的访问凭据另有两种：

- 网页账户使用会话 Cookie 和 CSRF 令牌，见“会话与 CSRF”。
- 使用移动设备登记、账户绑定和二维码建档使用一次性会话令牌。令牌包含在现场终端显示的二维码地址中，在有效期内持有地址即可访问对应会话。

`X-Device-ID` 会作为 `terminal.id` 出现在公开快照中，不是凭据。需要限制可以同步的终端时，在服务端配置 `QUEUE_DEVICE_ID`，见“终端请求头”。

QQ 与玩家资料通过 HTTPS 传输并存入私有数据库，现场终端只接受 HTTPS 地址。数据库及其备份和三个令牌都应限制读取权限。玩家头像文件不做鉴权，只依赖地址不可猜测，见“玩家头像”。

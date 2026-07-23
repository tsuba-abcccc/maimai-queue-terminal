# 队列云端同步接口

## 边界

- Android 终端是队列状态的唯一写入方。
- 现场操作始终先保存到本机。云端不可用时，终端继续正常工作并在后台重试。
- 网站只读取公开快照，不提供远程修改操作。
- 玩家资料库、QQ、电话、性别、玩家资料内部编号和资料编辑日志不上传。
- 终端只上传经过字段白名单过滤的公开队列事件，用于网站日志和“标记为自己”后的处理结果。

## 接口

### 终端发布

`POST /api/queue-status`

请求头：

```text
Authorization: Bearer <QUEUE_SYNC_TOKEN>
X-Device-ID: <本机生成的终端 UUID>
X-Queue-Schema-Version: 2
Content-Type: application/json; charset=utf-8
```

成功时返回 `204 No Content` 或任意 `2xx`。建议限制请求体不超过 128 KiB。
服务器继续接受不含公开事件的 `schema_version: 1` 快照，便于旧终端平稳升级；新版终端固定发布版本 2。

服务器必须完成以下处理：

1. 校验令牌和允许的终端编号。
2. 按公开字段白名单重新构造数据，不能原样保存未知字段。
3. `queue_id` 相同时，只接受 `revision` 不小于当前版本的快照。
4. `queue_id` 变化表示终端已开始新的队列，可以从较小的 `revision` 重新计数。
5. 使用服务器接收时间记录 `received_at`，不要使用终端时间判断在线状态。
6. 原子替换当前快照，避免网站读取到半份数据。

### 网站读取

`GET /api/queue-status`

返回最近一次公开快照。服务器应根据 `received_at` 设置 `terminal.online`：超过 90 秒没有收到终端心跳时返回 `false`。响应必须包含：

```text
Cache-Control: no-store
Access-Control-Allow-Origin: https://abcccc.top
```

没有任何快照时建议返回 `404`：

```json
{"ok":false,"error":"排队终端暂未同步"}
```

### 网站读取日志

`GET /api/queue-logs?queue_id=<队列 UUID>&limit=50&before=<游标>`

`queue_id` 省略时读取当前队列。`limit` 可设为 1 至 100，`before` 使用上一次响应的 `next_cursor`。接口只返回公开队列事件，不包含联系方式、性别或玩家资料内部编号。

## 公开快照示例

```json
{
  "schema_version": 2,
  "queue_id": "37e41698-46f8-489b-92dc-d29c71f00f7d",
  "revision": 18,
  "captured_at": 1784682000000,
  "registration_open": true,
  "terminal": {
    "id": "1e21d828-2454-4568-8078-154f84e165c7",
    "online": true,
    "app_version": "0.2.12",
    "last_seen_at": 1784682000000
  },
  "machines": {
    "A": {
      "id": "A",
      "name": "左侧 · 机台 A",
      "operational": true,
      "stop_reason": null,
      "stopped_at": null,
      "playing_started_at": 1784681700000,
      "registration_count": 3,
      "waiting_position_count": 1,
      "playing": [
        {
          "registration_id": "a10f5015a2e85b12e20af507",
          "display_id": "示例玩家一",
          "preference": "OPEN_TO_JOIN",
          "deferred_once": false,
          "temporarily_away": false,
          "temporary_away_skipped_turns": 0,
          "fixed_pair": false,
          "fixed_pair_id": null,
          "no_show_count": 0,
          "last_no_show_action_was_defer": false,
          "registration_type": "TEMPORARY",
          "created_at": 1784681000000,
          "last_played_at": null
        }
      ],
      "waiting_positions": [
        {
          "index": 1,
          "position_id": "43a5a2eeef0f4da5e470efbd",
          "fixed_pair": false,
          "estimated_wait_minutes": 7,
          "registrations": []
        }
      ]
    },
    "B": {}
  },
  "recent_events": [
    {
      "event_id": "9f5b84ac-b678-42c7-8bfd-95e617d7229c",
      "occurred_at": 1784682010000,
      "machine_id": "A",
      "type": "NO_SHOW_MOVED_TO_TAIL",
      "title": "机台 A · 未到场状态已更新",
      "detail": "“示例玩家一”已记录第 1 次未到场，并移至队尾。",
      "registration_ids": ["a10f5015a2e85b12e20af507"]
    }
  ]
}
```

实际机台 A、B 都会包含完整结构。`estimated_wait_minutes` 已由终端按照单人 12 分钟、共同游玩 15 分钟计算，并扣除当前轮次已经经过的时间。

## 终端构建配置

正式令牌不要写入仓库。可以放在当前开发账户的 `~/.gradle/gradle.properties`：

```properties
QUEUE_SYNC_URL=https://abcccc.top/api/queue-status
QUEUE_SYNC_TOKEN=<由服务器生成的高强度随机令牌>
```

也可以使用同名环境变量。未配置令牌时，应用保持纯本地运行，并在“应用详情”中显示“等待服务器配置”。

构建令牌会进入 APK。若 APK 需要公开分发，应将终端版与公开安装版分开签名，并在令牌泄漏后立即轮换。

## “标记为自己”的后续兼容

网站把 `queue_id`、`registration_id` 和用于离线提示的昵称写入 `localStorage`。开始新队列后 `queue_id` 会变化，旧标记不会错误匹配到复用编号的新登记。浏览器本地标记不保存联系方式；服务器日志通过稳定的 `registration_id` 关联登记，即使登记被移除，也能显示最后一次公开处理结果。

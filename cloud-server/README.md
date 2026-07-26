# maimai Q Cloud API

为 maimai Q 现场终端提供队列公开展示、玩家资料私有备份、网站线上登记、QQ Bot 查询与终端命令回流。

## 数据权威关系

- 机厅终端始终是队列和玩家资料的最终数据源。
- 终端操作先写入本机，再上传服务器；服务器不可用时不影响现场排队。
- 网站和 QQ Bot 的修改先保存为待执行命令，终端拉取并按本地规则校验，通过后才成为正式数据。
- 终端可以独立关闭“QQ Bot 联动”；关闭时 Bot 接口停止服务，待执行命令失效，关闭期间不积压通知。
- 终端可以独立关闭“允许线上登记”；关闭后只拒绝新建线上登记，队列查询、通知、资料同步和已有登记管理保持可用。
- `GET /api/queue-status` 与 `GET /api/queue-logs` 是公开接口。队列状态只为当前有效的资料库登记返回 QQ，便于网站详情联系玩家；性别、资料 UUID、完整资料库和私有命令不会公开。
- Bot 和终端私有接口分别使用独立令牌，不能在网页前端暴露。

## 环境变量

```dotenv
QUEUE_SYNC_TOKEN=<终端写入令牌，至少 32 个 UTF-8 字节的随机值>
QUEUE_BOT_TOKEN=<Koishi Bot 令牌，至少 32 个 UTF-8 字节且与终端令牌不同>
QUEUE_PROFILE_SCOPE_ID=maimai-q-main
QUEUE_DEVICE_ID=
QUEUE_PRIMARY_DEVICE_ID=
QUEUE_DATABASE_PATH=/var/lib/maimai-queue-status/queue.db
QUEUE_ONLINE_TIMEOUT_SECONDS=90
QUEUE_COMMAND_TIMEOUT_SECONDS=600
QUEUE_COMMAND_RETENTION_SECONDS=2592000
QUEUE_EVENT_RECIPIENT_RETENTION_SECONDS=2592000
QUEUE_CORS_ORIGIN=https://abcccc.top
```

`QUEUE_PROFILE_SCOPE_ID` 表示同一机厅共享的玩家资料库。主备终端使用相同值即可读取同一份云端资料，但同 UUID 的本机资料仍然优先。

每份鉴权令牌在启用对应私有接口时都必须达到 32 个 UTF-8 字节；两份令牌都配置时不能相同。某份配置缺失或过短时，对应私有接口统一返回 `503`；两份配置相同时，终端与 Bot 私有接口都会返回 `503`。健康检查仍会响应，服务不会降级为未鉴权访问。可使用 `openssl rand -hex 32` 分别生成两份独立令牌。

`QUEUE_COMMAND_TIMEOUT_SECONDS` 是资料或队列命令等待终端处理的最长时间，默认 10 分钟。超时命令会被拒绝，玩家可以重新提交；当前权威终端发生接管时，仍在有效期内的命令会自动转交给新终端。

`QUEUE_COMMAND_RETENTION_SECONDS` 控制已完成命令的保留时间，默认 30 天。到期后会删除包含 QQ 的命令载荷，避免私有信息无限期留存。

`QUEUE_EVENT_RECIPIENT_RETENTION_SECONDS` 控制日志通知与 QQ 收件人的私有关联保留时间，默认 30 天。到期后只删除 QQ 收件人关联，公开日志仍会保留。清理由终端同步或 Bot 读取通知时执行。

## 接口边界

公开接口：

```text
GET  /api/queue-status
GET  /api/queue-logs
GET  /healthz
```

网站线上登记接口，不使用 Bot 令牌，仅在终端在线、开启网站同步且现场规则允许线上登记时可用：

```text
POST /api/queue-online/profile                 {"qq":"<QQ号>"}
POST /api/queue-online/join                    {"request_id":"<UUID>","qq":"<QQ号>","machine_id":"A"}
GET  /api/queue-online/commands/<command_id>
```

终端接口，使用 `QUEUE_SYNC_TOKEN` 和 `X-Device-ID`：

```text
POST /api/queue-status
GET  /api/queue-terminal/profiles
GET  /api/queue-terminal/commands
POST /api/queue-terminal/commands/<command_id>/result
```

Koishi 接口，使用 `QUEUE_BOT_TOKEN`：

```text
POST  /api/queue-bot/players      {"qq":"<QQ号>"}
POST  /api/queue-bot/profiles     {"qq":"<QQ号>"}
POST  /api/queue-bot/events       {"qq":"<QQ号>","after":0,"limit":50}
GET   /api/queue-bot/events?after=<游标>&limit=50
PATCH /api/queue-bot/profiles/<profile_id>
POST  /api/queue-bot/queue-commands
GET   /api/queue-bot/commands/<command_id>
```

当公开快照中的 `onebot_sync_enabled` 为 `false` 时，上述所有 Bot 接口返回 `503 QQ Bot 联动已关闭`。服务器保留玩家资料，但会拒绝待执行命令、清除当前通知收件关系，并且不会在重新开启后补发关闭期间的事件。

按 QQ 筛选一律使用 HTTPS POST JSON 请求体，避免 QQ 进入 Nginx、Gunicorn 或监控系统的 URL 访问日志。GET 仅用于不含 QQ 查询条件的全量读取。

`QUEUE_BOT_TOKEN` 是服务级凭据，不是单个玩家的登录凭据。持有该令牌的服务可以读取完整玩家资料库中的昵称、性别、默认偏好、资料 UUID 和 QQ，也可以读取当前登记绑定及通知事件中的全部 QQ 收件人；还可以请求修改任一与 `actor_qq` 相符的玩家资料。玩家命令只能操作发送者本人，是 Koishi 插件结合 OneBot 会话身份施加的限制。Bot 令牌必须只保存在受控服务端，泄漏后应立即轮换。

当前允许 QQ 用户修改自己的昵称、性别和默认游玩偏好，并管理与发送者 QQ 对应的本人登记。QQ 是身份键，不能通过普通资料修改命令更换。线上登记尚未在现场签到时只允许退出排队；创建后的 30 分钟签到时限和轮到时自动退出规则由现场终端执行。关闭“允许线上登记”只会隐藏并拒绝新建入口，不影响这些已有功能。

网站只能查询与指定 QQ 对应的单份资料并创建线上登记，不能取得完整玩家资料库，也不能修改已有登记。网站提交成功后只会获得不含命令载荷的状态回执；现场终端仍会重新检查队列批次、资料、机台状态、登记上限和本次偏好。

### 排队命令

Koishi 使用 `POST /api/queue-bot/queue-commands` 提交本人操作。请求包含 `request_id`、`actor_qq` 和 `operation`，并按操作附带机台或本次游玩偏好。当前操作包括：

- `JOIN_QUEUE`
- `DEFER_ONE_ROUND` / `CANCEL_DEFER_ONE_ROUND`
- `TEMPORARILY_LEAVE` / `CANCEL_TEMPORARY_LEAVE`
- `TRANSFER_MACHINE`
- `CHANGE_PLAY_PREFERENCE`
- `LEAVE_QUEUE`

服务器按最新公开快照先行检查，终端再以本机状态复核。相同 `request_id` 和相同请求内容会返回原命令，不会重复建立登记或执行操作；同一玩家已有待处理命令时，新请求返回 `409`。

## Koishi 资料修改示例

Koishi 必须把 OneBot 事件中的发送者 QQ 写入 `actor_qq`，服务器会确认它与资料绑定一致：

```http
PATCH /api/queue-bot/profiles/37e41698-46f8-489b-92dc-d29c71f00f7d
Authorization: Bearer <QUEUE_BOT_TOKEN>
Content-Type: application/json

{
  "request_id": "d4a50f7f-e37c-43fd-b5a8-bd8fd79dd274",
  "actor_qq": "12345678",
  "nickname": "新昵称",
  "gender": "UNDISCLOSED",
  "default_preference": "OPEN_TO_JOIN"
}
```

服务器返回 `202 Accepted` 和 `command_id`。Koishi 随后查询 `/api/queue-bot/commands/<command_id>`：

- `PENDING`：等待终端处理。
- `APPLIED`：终端已经接受并写入本机。
- `REJECTED`：本地资料较新、昵称冲突或资料已不存在；通过 `result_detail` 向玩家说明。
- 超过命令有效期仍未被终端处理时，也会变为 `REJECTED`，不会永久停留在待处理状态。

相同 `request_id` 重试不会创建重复命令。一个资料已有待处理命令时，新的修改返回 `409`。

`APPLIED` 只表示当前权威终端已经完成本机持久化。服务器不会根据命令载荷直接改写玩家资料；资料接口会在终端随后上传的新快照中更新，从而始终以终端实际保存的内容为准。

终端读取命令时，服务器会记录领取时间与领取终端。命令先被服务器标记为超时后，如果当前权威终端已经领取并实际写入，首个迟到回执仍可把结果纠正为 `APPLIED`。该纠正只适用于服务器自身产生的超时结果，不能覆盖终端已经返回的首个 `APPLIED` 或 `REJECTED`。

## 通知轮询

Koishi 保存每个机厅的 `next_cursor`，周期读取：

```text
GET /api/queue-bot/events?after=<next_cursor>&limit=50
```

每个事件包含 `affected_players`，可以按 `qq_number` 发送与本人有关的日志；`operation_source` 说明事件来自现场终端、QQ Bot、系统自动流程或预留的网站远程操作。事件首次入库时会一次性固定当时的 QQ 接收人；同一事件编号再次上传不会增加、替换收件人，之后修改资料 QQ 也不会把历史通知转给另一账号。`PLAYING_CHANGED` 可用于上机通知；未到场、暂缓、暂离和退出等事件也会保留对应 QQ 关联。开始新队列后，上一队列的当前登记关联会清除；历史通知关联在达到配置的保留期限后删除。

使用 POST 按 QQ 筛选事件时，`affected_players` 只返回该 QQ 自己的关联，不会附带同一事件中其他玩家的 QQ。使用 GET 读取全量通知时仍会返回全部收件人，供受信任的 Koishi 服务统一投递。同一玩家在当前队列批次退出后重新登记时，当前 QQ 绑定会跟随新登记；退出期间已经固定的历史通知收件人不会改变。

## 本地验证

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python -m unittest -v
```

Windows PowerShell 激活命令为 `.venv\Scripts\Activate.ps1`。

## Docker 部署

1. 复制 `.env.example` 为 `.env`，分别生成终端令牌与 Bot 令牌。
2. 设置稳定的 `QUEUE_PROFILE_SCOPE_ID`。
3. 运行 `docker compose up -d --build`。
4. 将 `nginx-location.conf.example` 中的 location 加入 `abcccc.top` 的 HTTPS 站点。
5. 执行 `nginx -t` 并重载 Nginx。
6. 访问 `https://abcccc.top/queue-api-healthz` 验证服务。

## systemd 部署

1. 将 `app.py` 和 `requirements.txt` 部署到 `/opt/maimai-queue-status`。
2. 在该目录创建 `venv` 并安装依赖。
3. 创建仅 root 可读的 `/etc/maimai-queue-status.env`，填写上述环境变量。
4. 创建仅 `maimaiqueue` 可访问的数据目录：`install -d -o maimaiqueue -g maimaiqueue -m 0700 /var/lib/maimai-queue-status`。若已有 `queue.db`、`queue.db-wal` 或 `queue.db-shm`，将它们的属主改为 `maimaiqueue:maimaiqueue`，权限改为 `0600`。
5. 安装 `maimai-queue-status.service`，执行 `systemctl daemon-reload` 和 `systemctl enable --now maimai-queue-status`。

服务单元使用 `UMask=0077`，确保以后创建的 SQLite 数据库、WAL 和 SHM 文件不会被其他本机用户读取。调整既有文件权限后应重启服务，并确认 `ls -la /var/lib/maimai-queue-status` 中目录为 `drwx------`、文件为 `-rw-------`。

不要提交 `.env`、生产数据库或任何令牌。`QUEUE_BOT_TOKEN` 只应存在于 Koishi 服务端配置中，不能发送到群聊、浏览器或公开仓库。

## 已有服务器升级与首次联调

不要在旧后端上直接启用 Koishi 插件。Bot 需要新版 `app.py`、独立的 Bot 令牌和 Nginx 的 `/api/queue-bot/` 路由同时生效；只更新其中一项仍然无法使用。

### 1. 升级后端

升级时保留原有 `QUEUE_SYNC_TOKEN` 和 `QUEUE_PROFILE_SCOPE_ID`，否则现场终端会失去写入权限或改用另一份玩家资料库。另行生成 Bot 令牌：

```bash
openssl rand -hex 32
```

把结果写入服务器的 `.env` 或 `/etc/maimai-queue-status.env`，变量名为 `QUEUE_BOT_TOKEN`。它不能与 `QUEUE_SYNC_TOKEN` 相同，也不能少于 32 个 UTF-8 字节。

systemd 部署建议先停止服务并备份数据目录，再更新文件：

```bash
sudo systemctl stop maimai-queue-status
sudo cp -a /var/lib/maimai-queue-status "/var/lib/maimai-queue-status.backup-$(date +%Y%m%d-%H%M%S)"
sudo install -m 0644 cloud-server/app.py /opt/maimai-queue-status/app.py
sudo install -m 0644 cloud-server/requirements.txt /opt/maimai-queue-status/requirements.txt
sudo /opt/maimai-queue-status/venv/bin/pip install -r /opt/maimai-queue-status/requirements.txt
sudo install -m 0644 cloud-server/maimai-queue-status.service /etc/systemd/system/maimai-queue-status.service
sudo systemctl daemon-reload
sudo systemctl start maimai-queue-status
sudo systemctl status maimai-queue-status --no-pager
```

`app.py` 启动时会补齐新增的数据表和字段，不会清空已有队列快照。上述命令假定当前目录是仓库根目录；若服务器上的源码位于其他位置，应替换命令左侧的源文件路径。

Docker 部署则在保留原数据卷的前提下更新代码与 `.env`，再执行：

```bash
cd cloud-server
docker compose up -d --build
docker compose logs --tail=100 maimai-queue-status
```

### 2. 更新 Nginx 路由

把当前 [nginx-location.conf.example](./nginx-location.conf.example) 中的 `/api/queue-online/`、`/api/queue-bot/` 和 `/api/queue-terminal/` location 合并到现有 HTTPS 站点。不要用示例文件覆盖站点中的证书、静态网站或其他 location。随后执行：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

### 3. 验证线上接口

先验证公开健康检查：

```bash
curl -i https://abcccc.top/queue-api-healthz
curl -i -X POST -H 'Content-Type: application/json' \
  -d '{"qq":"00000"}' https://abcccc.top/api/queue-online/profile
curl -i 'https://abcccc.top/api/queue-bot/events?after=0&limit=1'
```

第一条应返回 `200`。第二条应返回后端 JSON；测试 QQ 不存在时通常为 `404 PROFILE_NOT_FOUND`，这证明网站线上登记路由已经生效。第三条故意不带令牌，应返回 `401` 和“Bot 认证失败”，这说明 Bot 路由已经到达新版后端。其他结果的含义如下：

- `404 接口不存在`：新版 `app.py` 或 Nginx 的 `/api/queue-bot/` 路由尚未部署。
- `503 服务器鉴权配置无效`：Bot 令牌缺失、少于 32 字节，或与终端令牌相同。
- Nginx 的 HTML `404`：请求没有转发到后端，检查站点中的 location。

再在不把令牌写入命令历史的情况下验证鉴权和玩家资料：

```bash
read -rsp 'QUEUE_BOT_TOKEN: ' QUEUE_BOT_TOKEN; echo
curl -sS -H "Authorization: Bearer ${QUEUE_BOT_TOKEN}" \
  'https://abcccc.top/api/queue-bot/events?after=0&limit=1'
curl -sS -X POST \
  -H "Authorization: Bearer ${QUEUE_BOT_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{"qq":"替换为已建资料的QQ号"}' \
  'https://abcccc.top/api/queue-bot/profiles'
unset QUEUE_BOT_TOKEN
```

两条鉴权请求都应返回 JSON，而不是 `401` 或 `404`。如果返回 `503 QQ Bot 联动已关闭`，请在现场终端确认“网站同步”和“QQ Bot 联动”均已开启；其他 `503` 表示鉴权配置无效。资料查询返回空数组时，通常不是 Koishi 问题：确认现场安装的是当前 `terminal` 变体、右上角同步状态已成功，并且该玩家资料确实填写了同一个 QQ 号。纯本地 `local` 变体不会上传资料。

### 4. 启用 Koishi

服务器验证通过后，再按 [Koishi 插件说明](../koishi-bot/README.md) 安装插件。Koishi 必须已经启用数据库、HTTP 服务和在线的 OneBot 适配器。首次联调依次执行：

```text
查看队列
我的资料
我的排队
加入排队
```

`查看队列` 验证公开队列读取；“我的资料”和“我的排队”验证 OneBot 会话 QQ、私有鉴权和终端资料同步。“加入排队”会真实建立登记，只应使用准备好的测试玩家资料执行。登记建立后，还应在现场终端确认“线上登记 · 待签到”、完成签到，并分别验证 Bot 的暂缓、暂离、切换机台、修改本次偏好和退出排队。

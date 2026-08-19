# maimai Q 公开测试版自建部署方案

本文面向不使用项目维护者服务器、希望自行部署 maimai Q 的机厅或测试者。当前方案对应 Android 终端 `0.12.2`、队列 API `0.12.2`、Koishi 插件 `0.3.13`。它是公开测试方案，不代表已经完成正式多租户和多终端联动。

## 先确认当前边界

- 最稳定的部署形态是“一台服务端实例 + 一个现场终端 + 一个 Bot 实例”。终端本机始终是队列事实来源，服务端故障时现场排队仍可继续。
- 当前服务端数据库的公开快照是一份队列实例。要同时运行多个机厅，测试阶段应为每个机厅创建独立的 API 实例、SQLite 数据目录、令牌和站点地址；不要让多个机厅共用同一个数据库卷。
- 当前支持备用终端接管，但不支持多台终端同时写入同一队列。另一份终端正在同步时，服务器会拒绝第二份实例，显示“另一份终端实例正在同步”。正式的多终端联动仍属于后续版本。
- 公开测试网站是 `public-site/` 下的独立 Vue/Vite 静态页；不需要在生产环境长期运行 Node.js。API 和 Bot 才是常驻服务。它只包含排队状态、公开日志、版本信息和线上登记，不带其他站点的文章、导航或重定向逻辑。其他站点可以独立部署，保留各自内容。
- SQLite 适合公开测试和小规模机厅。大规模、多机厅和高并发使用前，应等待多租户方案或自行评估 PostgreSQL 迁移。

## 推荐拓扑

```text
玩家浏览器 ── HTTPS ──┐
现场终端 ── HTTPS ────┼── 反向代理（Nginx/Caddy）── Flask/Gunicorn API ── SQLite 卷
Koishi + OneBot ──────┘
                         │
                         └── 静态网站（同域名或独立域名）
```

推荐让网站和 API 使用同一个 HTTPS 域名，例如：

```text
https://example.com
https://example.com/api/queue-status
```

这样不需要额外配置跨域。网站与 API 分域也可以，但必须把 `QUEUE_CORS_ORIGIN` 和网站构建时的 `VITE_QUEUE_*` 地址同时配置正确。

## 需要准备的资源

1. 一台能够长期运行 Docker 的 Linux 主机（公开测试最低 1 vCPU、1 GB 内存即可；具体取决于 Bot 和访问量）。
2. 一个域名和 HTTPS 证书。可以使用 Caddy 自动申请证书，也可以使用 Nginx + Let's Encrypt。
3. Docker Engine 和 Docker Compose v2。
4. 一台 Android 10 或更高版本的横屏终端。
5. 若启用 QQ 功能：Koishi 4、数据库服务、OneBot 适配器和可正常登录的 Bot QQ。
6. 两个完全不同的随机令牌：终端同步令牌和 Bot 令牌。令牌至少 32 个 UTF-8 字节。

低规模测试不需要购买独立数据库、消息队列或文件存储；SQLite 数据卷和静态网站即可。

## 第一次部署

### 1. 获取固定版本源码

不要直接使用 `main` 或浮动的 `latest`。从 GitHub Release 下载源码，或克隆仓库后切换到发布标签：

```bash
git clone https://github.com/tsuba-abcccc/maimai-queue-terminal.git
cd maimai-queue-terminal
git checkout v0.12.2
```

如果公开 Release 尚未发布，使用经过验收的提交哈希，并把它记录在部署记录中；不要把未提交的工作区直接复制到生产主机。

### 2. 启动队列 API

```bash
cd cloud-server
cp .env.example .env
openssl rand -hex 32   # 写入 QUEUE_SYNC_TOKEN
openssl rand -hex 32   # 再生成一份，写入 QUEUE_BOT_TOKEN
```

编辑 `.env`，至少填写以下字段（示例域名必须替换）：

```dotenv
QUEUE_SYNC_TOKEN=<只给现场终端的令牌>
QUEUE_BOT_TOKEN=<只给 Koishi 的另一份令牌>
QUEUE_PROFILE_SCOPE_ID=venue-demo-001
QUEUE_CORS_ORIGIN=https://example.com
QUEUE_PUBLIC_SITE_URL=https://example.com
QUEUE_PLAYER_ACCOUNT_SITE_URL=https://example.com
QUEUE_PLAYER_COOKIE_SECURE=true
QUEUE_LATEST_TERMINAL_VERSION=0.12.2
QUEUE_LATEST_WEBSITE_VERSION=0.12.2
QUEUE_LATEST_BOT_VERSION=0.3.13
```

`QUEUE_PROFILE_SCOPE_ID` 是同一机厅共享玩家资料的作用域；以后重建容器或升级版本时必须保持不变。`QUEUE_PUBLIC_SITE_URL` 必须填写完整的 HTTPS 排队页面地址，因为它会被编码进终端生成的移动设备登记二维码。`QUEUE_PLAYER_ACCOUNT_SITE_URL` 是玩家网页账户绑定二维码打开的地址，通常填写同一页面；公开部署必须使用 HTTPS 并保持 `QUEUE_PLAYER_COOKIE_SECURE=true`。不要把 `.env` 提交 Git。

启动并检查 Compose 配置：

```bash
docker compose config
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 maimai-queue-status
```

Compose 将 SQLite 保存在名为 `queue-status-data` 的持久卷中，API 只绑定到本机 `127.0.0.1:8081`。不要把 8081 直接暴露到公网。

### 3. 配置反向代理和 HTTPS

将 [`cloud-server/nginx-location.conf.example`](../cloud-server/nginx-location.conf.example) 中的 location 合并到自己的 HTTPS 站点，不要覆盖站点已有的证书和其他路径。它包含公开队列、日志、版本、线上登记、移动登记、终端私有接口、Bot 私有接口和健康检查路由。

```bash
sudo nginx -t
sudo systemctl reload nginx
curl -fsS https://example.com/queue-api-healthz
curl -fsS https://example.com/api/queue-versions
```

健康检查应返回 `status: ok`。`/api/queue-versions` 可以在还没有终端上传时显示“未知”，这不代表服务故障。

静态网站应由 Nginx/Caddy 指向网站构建目录，并把 `/api/` 路径转发到 `127.0.0.1:8081`。网站静态资源和 API 使用同一域名时，不需要开放额外端口。

### 4. 构建并部署网站

公开测试网站直接使用主项目固定版本中的 `public-site/`，不需要克隆或运行其他站点：

```bash
cd public-site
pnpm install --frozen-lockfile
```

同域名部署时，建议显式设置所有 API 地址，避免构建机上的示例域名被带入产物：

```bash
export VITE_QUEUE_STATUS_API_URL=https://example.com/api/queue-status
export VITE_QUEUE_LOG_API_URL=https://example.com/api/queue-logs
export VITE_QUEUE_VERSIONS_API_URL=https://example.com/api/queue-versions
export VITE_QUEUE_ONLINE_PROFILE_API_URL=https://example.com/api/queue-online/profile
export VITE_QUEUE_ONLINE_JOIN_API_URL=https://example.com/api/queue-online/join
export VITE_QUEUE_ONLINE_COMMAND_API_BASE=https://example.com/api/queue-online/commands
export VITE_QUEUE_MOBILE_API_BASE=https://example.com/api/queue-mobile/sessions
pnpm run build
```

部署前确认 `dist/index.html`、`dist/queue-status/index.html` 和 `dist/queue-client-version.json` 存在，且 manifest 中的版本为 `0.12.2`。使用临时目录原子切换静态目录：

```bash
rsync -a --delete dist/ /var/www/queue-site/dist-next/
test -s /var/www/queue-site/dist-next/index.html
mv /var/www/queue-site/dist /var/www/queue-site/dist-previous 2>/dev/null || true
mv /var/www/queue-site/dist-next /var/www/queue-site/dist
```

根路径是独立队列页的规范入口；`/queue-status` 和 `/queue-status/` 仅作为旧链接兼容入口，建议在反向代理中重定向到根路径。二维码应使用 `QUEUE_PUBLIC_SITE_URL` 配置的根地址。

其他站点需要复用队列组件时，可将 `public-site/src/queue/` 按自身构建流程同步到对应前端，并在发布前运行项目提供的一致性检查，确保各站点的队列逻辑一致。

若站点和 API 分域，仍需设置 API 的 `QUEUE_CORS_ORIGIN` 为精确的网站 origin（不要写路径、不要使用 `*`），并让网站构建变量指向 API 域名。

### 5. 安装 Koishi Bot

在运行 Koishi 的主机上：

```bash
cd koishi-bot
pnpm install --frozen-lockfile
pnpm run build
pnpm pack
```

把生成的 `koishi-plugin-maimai-q-0.3.13.tgz` 安装到 Koishi 项目，而不是复制插件目录中的 `node_modules`：

```bash
pnpm add -w /absolute/path/to/koishi-plugin-maimai-q-0.3.13.tgz
```

配置示例：

```yaml
plugins:
  maimai-q:
    apiBase: https://example.com
    publicQueueUrl: https://example.com
    botToken: <QUEUE_BOT_TOKEN>
    oneBotSelfId: '<Bot QQ>'
    notificationEnabled: true
```

`botToken` 只能保存在 Koishi 服务端配置中。确认 OneBot 已在线、Bot QQ 能私信测试账号后，再开启主动通知。首次启动通知轮询会从最新游标开始，不会一次性发送旧消息。

### 6. 构建和配置现场终端

公开下载的 APK 不应内置任何机厅令牌。管理员可以构建未预置令牌的联网终端包，或构建纯本地版：

```powershell
# 纯本地公开版，不申请联网权限
.\gradlew.bat :app:assembleLocalRelease

# 联网终端版，不内置地址或令牌；显式禁止并清空本机可能存在的 Gradle 预置值
# 首次启动后在“应用设置”填写服务器地址和令牌
.\gradlew.bat :app:assembleTerminalRelease -PENABLE_TERMINAL_BUILD=true -PEMBED_TERMINAL_SYNC_CONFIG=false -PQUEUE_SYNC_URL= -PQUEUE_SYNC_TOKEN=
```

若确实需要受控设备预置地址，可以在私有构建环境同时显式传入 `-PEMBED_TERMINAL_SYNC_CONFIG=true` 和 `-PQUEUE_SYNC_URL=...`；只有受控私有 APK 才允许再传入 `-PQUEUE_SYNC_TOKEN=...`。不要把 `EMBED_TERMINAL_SYNC_CONFIG` 或令牌用于公开构建。普通终端令牌应在现场设备的应用设置中填写，或通过受控渠道分发的私有 APK 注入。

发布 APK 必须使用同一个发布者 keystore 签名。已安装的调试包不能直接覆盖为正式签名包；正式升级必须保持签名和 applicationId 不变。安装后依次填写 API 地址、终端令牌，打开“与服务端同步”，确认状态变为“已同步”，再按需要打开“QQ Bot 联动”和“允许线上登记”。

首次公开测试可以由发布者在自己的发布机上生成一次密钥（密码只通过交互式提示输入，并把 keystore 离线备份）：

```bash
keytool -genkeypair -v -keystore maimai-q-release.jks \
  -alias maimai-q -keyalg RSA -keysize 4096 -validity 10000
```

构建出的 `*-release-unsigned.apk` 需要先对齐、再签名；下面的路径以 Android SDK Build Tools 为准：

```bash
zipalign -p -f 4 maimai-Q-0.12.2-terminal-release-unsigned.apk maimai-Q-0.12.2-terminal-aligned.apk
apksigner sign --ks maimai-q-release.jks --ks-key-alias maimai-q \
  --out maimai-Q-0.12.2-terminal.apk maimai-Q-0.12.2-terminal-aligned.apk
apksigner verify --verbose maimai-Q-0.12.2-terminal.apk
```

不要把 keystore、密码或带令牌的私有 APK 上传 GitHub。工作区中不带 `-beta` 的 `maimai-Q-0.12.2-terminal.apk` 如由调试任务生成，可能使用 Debug 证书；`*-release-unsigned.apk` 是未签名候选。公开 Release 只上传已经核验长期签名的 `maimai-Q-0.12.2-local-beta.apk` 和 `maimai-Q-0.12.2-terminal-beta.apk`。

## 首次联调清单

按顺序检查，任何一项失败都先修正，不要直接让玩家使用：

1. `GET /queue-api-healthz` 返回 200。
2. `GET /api/queue-status` 返回公开快照或明确的“尚未同步”。
3. `GET /api/queue-versions` 返回 JSON，未连接的端显示“未知”而不是错误版本。
4. 终端成功上传一次快照，网站能在 10 秒左右看到机台和登记状态。
5. 终端关闭“允许线上登记”时，网站和 Bot 都拒绝新建，但仍能查看队列和管理已有登记。
6. 网站创建一份测试线上登记，终端显示“线上登记 · 待签到”，完成签到后再验证 Bot 的“我的排队”。
7. 登录网页玩家资料，依次测试退出排队、暂缓一次、暂时离开、转至其他机台和修改本次偏好；再通过 Bot 检查同一登记，确认终端是最终结果来源。
8. OneBot 私聊执行“查看队列”“查询人数”“我的资料”，再确认一条主动通知能送达已添加 Bot 好友的测试账号。
9. 断开 API 网络：终端仍可现场排队；网站、线上登记和 Bot 应显示不可用或等待重试，而不是伪造成功。
10. 关闭并重新启动容器，确认 SQLite 数据、队列批次和待处理命令没有丢失。

## 后续版本如何获取和更新

当前版本没有自动下载或自动安装更新。终端、网站和 Bot 的版本页只会把各端实际上报的版本，与 API `.env` 中的 `QUEUE_LATEST_TERMINAL_VERSION`、`QUEUE_LATEST_WEBSITE_VERSION`、`QUEUE_LATEST_BOT_VERSION` 比较；它们不会主动访问 GitHub，也不能证明服务器配置的版本就是最新发布版。

唯一权威更新来源应为项目的 [GitHub Releases](https://github.com/tsuba-abcccc/maimai-queue-terminal/releases)。测试者可以在 GitHub 使用“Watch → Custom → Releases”接收通知，或订阅 Releases Atom：`https://github.com/tsuba-abcccc/maimai-queue-terminal/releases.atom`。不要从群文件、旧网页缓存、`main` 分支或文件名为 `latest` 的链接覆盖安装。

每次发布都应固定为一个不可变版本，至少包含：

- 主仓库 Git tag（例如 `v0.12.2`）和 GitHub Release；
- API 源码或 Docker 构建上下文、数据库迁移说明；
- Android APK（公开本地版、必要时另附受控终端版）；
- `public-site` 对应源码、静态站点压缩包和 `queue-client-version.json`；其他独立站点如同步发布，再另外保留其对应提交或 tag；
- Koishi 插件 tarball、版本号和 lockfile；
- 面向玩家的更新日志、管理员更新说明和每个文件的 SHA-256；
- 兼容性说明：支持的队列 schema、最低 Android 版本、是否需要迁移。

更新时固定使用以下顺序：

1. 阅读 Release 更新说明，确认是否需要数据库迁移、终端升级或重新配置。
2. 先备份 API 数据库、`.env`、Compose/Nginx 配置、Bot 配置、当前网站目录和当前 APK。
3. 下载并校验新版本文件的 SHA-256；不要执行未校验的脚本或 APK。
4. 先更新 API：停止旧容器、保留原 SQLite 卷、构建新镜像并启动；检查 `/healthz`、`/api/queue-status`、`/api/queue-versions`。
5. 再原子切换网站静态目录；保留至少一份 `dist-previous`。
6. 更新 Koishi 插件并重启 Bot，确认日志、OneBot 连接和版本上报正常。
7. 最后逐台更新 Android 终端；更新期间不要让旧终端和新终端同时同步同一队列。安装后先确认同步成功，再恢复线上入口。
8. 用一份专用测试资料完成查看、线上登记、签到和退出的冒烟测试，确认无误后再通知玩家。

API 升级完成后，管理员还必须把 `.env` 中三个 `QUEUE_LATEST_*_VERSION` 更新为该 Release 实际配套的版本并重启 API。否则三端版本页仍会显示旧的“最新版本”，或者把不配套的组件误判为最新。版本比较只负责提示，不代替 Release 更新说明、哈希校验、备份或人工安装。

补丁版本通常只替换 API、网站和 Bot 文件；涉及数据库结构或队列 schema 的版本必须遵循 Release 中的迁移顺序。不要把 `docker compose down -v` 用作普通更新命令，它会删除 SQLite 数据卷。

## 备份与回滚

至少保留每日备份和最近 7 份版本备份。备份内容包括：

- SQLite `queue.db` 及其 WAL/SHM 文件（最好在停止 API 后复制，或使用 SQLite online backup）；
- `.env`、Compose 文件、反向代理配置；
- Koishi 配置、插件 tarball 和 `pnpm-lock.yaml`；
- 当前和上一份网站 `dist`；
- APK 与 SHA-256、发布 keystore 的离线备份（keystore 不放服务器仓库）。

回滚顺序：停止新 API 和 Bot，恢复与数据库备份匹配的旧 API 版本和网站目录，再启动并检查健康接口。若新版本做过不可逆数据库迁移，不能只换回旧代码，必须恢复迁移前的数据库副本。Android 不建议强制降级；若版本号或签名不兼容，应保留数据并安装修复版，而不是卸载清空队列。

## 最低安全要求

- 全部公网接口使用 HTTPS；API 的 8081 端口只监听本机。
- `QUEUE_SYNC_TOKEN`、`QUEUE_BOT_TOKEN` 分开生成、分开保存，泄漏后立即轮换。
- `.env`、Bot 配置、数据库和备份限制为服务账户可读；不要上传 GitHub、聊天群或 APK。
- CORS 只允许实际网站 origin；不要为了省事设置为 `*`。
- 反向代理设置请求体上限、基础限速和安全响应头；公开日志中不要记录 QQ、令牌或完整资料。
- 测试期间明确告诉玩家：线上登记依赖现场终端最终确认，服务端或网络中断时网站/Bot 不能替代现场终端。

## 维护者现有部署与公开测试包的关系

- 两者使用同一套 0.12.2 队列规则和跨端协议；差异主要是签名、默认配置、网站外壳和数据实例，不是另做一套功能逻辑。
- 公开 `terminal-beta.apk` 使用长期 Release 证书签名，不预置服务器地址或令牌。维护者目前现场安装的联调终端使用 Android Debug 证书，已经保存自己的服务器配置和现场数据；两个签名不同，不能直接互相覆盖安装。
- 维护者可以继续使用现有联调终端，不会因 GitHub 公开发布而自动改变、清空或连接到其他实例。若以后迁移到公开 Release 签名，必须先导出并核对可恢复的数据与配置，再卸载 Debug 版并安装 Release 版；不要在正在排队时迁移。
- 公开网站来自 `public-site/`；其他站点可以保留自己的站点外壳。两者共享队列组件，但分别构建、分别部署，公开包不会包含其他站点的文章或导航。
- 每个公开部署者使用自己的 API、SQLite 数据卷、域名和令牌。GitHub 发布不会开放维护者数据库，也不会让其他部署实例自动连接维护者服务。

## 当前测试版的发布判断

本版本的后端、Bot、Android 和独立公开网站门槛测试已通过，APK 已用长期发布证书签名并附带校验清单，公开测试 Release 使用 `public-site/`，不会替换或并入其他站点。当前不提供预构建 Docker 镜像；本机未运行 Docker daemon，因此部署者仍需在自己的主机上执行一次 `docker compose build`，并在真实域名环境完成联调、备份和恢复演练。创建 Release 后，部署者必须把服务端三个 `QUEUE_LATEST_*_VERSION` 设置为实际配套版本，并在发布记录中注明公开站点提交。

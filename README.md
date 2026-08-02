# maimai Q

[![Version](https://img.shields.io/badge/version-0.7.0-007AFF)](https://github.com/tsuba-abcccc/maimai-queue-terminal/tags)
[![Android](https://img.shields.io/badge/Android-10%2B-34C759?logo=android&logoColor=white)](https://developer.android.com/about/versions/10)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-007AFF)](https://developer.android.com/compose)

面向机厅现场的双机台电子排队终端。maimai Q 用电子队列模拟现场“挪东西排卡”的流程，在保留玩家游玩偏好和现场调整能力的同时，提供等待时间估算、本机持久化、操作日志和可选的网站同步。

项目以 Android 横屏终端为现场最终操作端。网络不可用时，排队仍然完整运行在本机；开启同步后，网站和 QQ Bot 可以查看队列，并通过玩家资料提交线上登记。所有远程操作仍由现场终端按本地规则校验并写入。

> 当前版本仍处于 `0.x` 阶段。用于真实现场前，请先根据本机厅规则完成测试，并安排能够处理误操作和机台故障的现场人员。

## 快速链接

- [在线查看当前队列](https://abcccc.top/queue-status)
- [玩家使用手册](docs/user-manual.md)
- [玩家使用手册 PDF](output/pdf/maimai-Q-玩家使用手册.pdf)
- [0.1.0 至 0.7.0 更新日志](docs/update.md)
- [云端同步协议](docs/cloud-queue-sync.md)
- [后端部署说明](cloud-server/README.md)

## 项目定位

maimai Q 处理的是机厅现场排队，不是线上预约系统。它将两台机台视为彼此独立的队列，并把每名玩家的登记、游玩偏好和现场状态组合成一轮轮可执行的等待位置。

| 概念 | 含义 |
| --- | --- |
| 登记 | 一名玩家的一次排队记录 |
| 等待位置 | 按顺序和游玩偏好组成的一轮候场玩家 |
| 游玩位置 | 当前应当正在对应机台游玩的玩家 |
| 允许他人加入 | 接受系统与另一名开放玩家自动组成共同游玩 |
| 固定组合 | 两份登记固定一起进入游玩位置，不参与自动重新配对 |

单人游玩按约 12 分钟、共同游玩按约 15 分钟估算。当前一轮已经经过的时间会从后续等待时间中扣除。

## 当前功能

### 排队和现场推进

- 机台 A、机台 B 两条完全独立的登记顺序。
- 游玩位置、等待位置、登记人数和总人数统计。
- 单人游玩、允许他人加入和与朋友固定组合。
- 本轮结束可选择正常开始下一轮、移除本轮玩家的登记后开始下一轮，或仅结束当前轮次。
- 将误进入游玩位置的玩家撤回等待顺序前端。
- 将实际已经共同游玩的等待玩家补入当前游玩位置。
- 游玩超过 20 分钟时提醒，并支持补记现场已完成但忘记操作的轮次。
- 首页右侧会反馈主要队列操作结果；少数影响整条队列的重要操作提供 10 秒撤销入口。

### 暂缓一轮、暂时离开和未到场

- “暂缓一轮”只跳过下一次进入游玩位置的机会，登记不移动，触发后自动解除。
- “暂时离开”会被排队计算忽略；每次轮到时移至队尾，需要玩家回来后手动取消。
- 暂时离开连续轮空 3 次后仍保留，第 4 次轮到时仍未返回则自动退出。
- 暂缓一轮或暂时离开的玩家不会被错误标记为未到场。
- 未到场仅适用于游玩位置和当前真正轮到的首个有效等待位置。
- 未到场次数和上次处理方式属于当前轮次状态；真正完成一轮并正常轮转后自动清除，误操作回退时保留。
- 系统会结合单人、开放加入、固定组合和离开状态重新计算游玩组合与时间。

### 玩家资料和登记

- 临时登记、玩家资料库和“使用移动设备登记”三种现场入口。
- 玩家资料支持昵称或 QQ 搜索、推荐排序、首字母排序和四列紧凑布局；推荐顺序会参考使用次数和最近使用时间。
- 玩家资料包含昵称、性别、默认游玩偏好、QQ 号、QQ 显示范围和排队通知设置。
- 通知可以分别控制队列状态、游玩位置、线上登记与签到、暂缓一轮与暂时离开及未到场、机台及营业状态；修改结果会在 App、云端和 QQ Bot 间同步。
- 默认偏好可设为“每次询问”，也可把本次选择保存为以后默认。
- 使用玩家资料认领临时登记，保留原机台和位置，并将昵称更新为资料昵称。
- 修改本次游玩偏好时，不会意外覆盖玩家资料的默认偏好。
- 性别只在需要的终端详情中显示；QQ 可以选择仅在终端显示，或同时显示在公开网站的当前登记详情中。
- 旧玩家资料在首次继续使用前需要确认 QQ 显示范围和通知设置；线上登记仍可建立，但资料补全前不能在终端完成签到。
- 终端可显示实际 QQ Bot 的好友二维码，主动私信通知只有在玩家添加 Bot 好友后才能送达。
- 网站和 QQ Bot 可使用已绑定的 QQ 玩家资料加入排队；线上登记必须在创建后的 30 分钟内到终端完成现场签到。
- 待签到登记暂时保留在原有等待顺序中，不参与等待时间估算；超过 30 分钟，或轮到进入游玩位置时仍未签到，登记会自动退出排队。
- “使用移动设备登记”由终端生成短时二维码。玩家在手机网页中浏览完整玩家资料库、按昵称或 QQ 搜索、选择或新建资料并确认本次偏好；终端复核后建立普通现场登记，不需要再次签到。

### 队列编辑和纠错

- 长按登记直接进入顺序调整，游玩位置玩家保持锁定。
- 长按等待位置可连同位置框跨多个位置拖动。
- 拖动时支持边缘自动滚动、任意方向跟手和无效落点回弹。
- 最终顺序未变化时，不会误判为一次调整。
- 使其他玩家延后的调整会要求确认已取得所有受影响玩家同意。
- “更多”中保留独立的完整登记编辑页面，适合集中整理队列。

### 本机运行和管理

- 队列、玩家资料、机台规则和操作日志保存在终端本机。
- 重启应用时询问是否继续使用上次队列。
- 最多保留最近 1,000 条本机详细操作日志。
- 机台停止使用时保留全部登记；恢复后本轮计时从头开始。
- 可分别设置是否允许暂缓一轮、是否允许暂时离开。
- 可统一设置营业时间，也可按星期分别设置；闭店后为现有队列保留最多 20 分钟的收尾时间，开店时间不会自动开启登记。
- 操作日志区分现场终端、QQ Bot、系统自动和预留的网站远程来源，并可按来源筛选。
- 支持自定义机台现场备注，固定名称“机台 A / B”保持不变。
- 重要操作使用确认弹窗、状态动画和克制的操作音效。

### 网站同步

- 现场变化先保存到本机，再异步上传公开快照。
- 完整玩家资料库和 QQ 通过鉴权后的私有通道同步；只有玩家选择“允许网站显示”后，其当前有效登记的 QQ 才会出现在网站登记详情中。
- 玩家资料使用递增版本号处理终端、网页和 Bot 的并发更新；资料冲突会明确拒绝，不会静默覆盖现场较新的内容。
- Koishi Bot 可以查询本人状态、读取相关事件、请求修改玩家资料和管理本人的当前登记。
- 服务器修改先成为待执行命令，终端校验并落盘后才正式生效。
- 网络失败不会阻止现场操作，应用会在后台自动重试。
- 首页显示已同步、同步中、待重试、已关闭或未配置等状态。
- 现场终端版可分别关闭网站同步和“QQ Bot 联动”；关闭后对应的线上入口与远程操作不可用。
- 现场终端版可以在应用设置中更换队列 API 地址和终端同步令牌，不需要为不同服务器修改源码；构建配置只作为首次安装的默认值。
- 现场终端可单独关闭“允许线上登记”；关闭后网站和 QQ Bot 仍可查询及管理已有登记，但不能创建新的线上登记。
- 闭店前 30 分钟，App、网站和 QQ Bot 会统一显示提醒；预计无法在闭店前轮到时，App 还会在创建登记前说明风险。闭店后，三端统一显示收尾状态。
- 网站提供两台队列、位置详情、时间估算、公开日志、“标记为自己”和线上加入排队入口。
- 网站还提供终端二维码专用的移动设备登记页；短时会话限定机台和当前队列批次，过期、重复提交或队列变化都会由终端重新校验。
- 标记后可查看自己的位置、预计时间、共同游玩对象和未到场等处理结果。
- QQ Bot 支持加入排队，以及暂缓一轮、暂时离开、切换机台、修改本次游玩偏好和退出排队；待签到登记只允许退出。

<p align="center">
  <img src="docs/images/queue-status-mobile.png" width="360" alt="maimai Q 队列网站移动端界面">
</p>

<p align="center"><sub>队列网站的移动端界面；现场队列仍以 Android 终端保存的状态为准。</sub></p>

## 系统架构

```mermaid
flowchart LR
    Player[现场玩家] --> Terminal[Android 横屏终端]
    Terminal --> Local[(本机队列与玩家资料)]
    Terminal -->|HTTPS POST<br/>公开队列 + 私有资料| API[Flask 队列 API]
    API --> PublicDB[(公开快照与公开事件)]
    API --> PrivateDB[(私有玩家资料与命令)]
    Web[队列网站] <-->|公开查询 + 线上登记| API
    Viewer[玩家手机] --> Web
    Bot[Koishi OneBot] <-->|私有查询与待执行命令| API
    Terminal -->|拉取并回执命令| API
```

边界设计：

- Android 终端是队列与玩家资料的最终数据源。
- 本机保存优先于云端同步，服务器故障不会中断现场排队。
- 后端再次按白名单构造公开数据，不原样保存终端传来的未知字段。
- Bot 不能直接覆盖正式数据，只能提交由终端校验的待执行命令。
- 仓库包含 Android 应用和队列 API；当前在线网站前端由独立站点项目托管。

## 运行要求

### 终端

- Android 10（API 29）或更高版本。
- 横屏设备，建议使用固定摆放并持续供电的平板或排队终端。
- 需要网站同步时，终端必须获得系统联网权限。

### 开发环境

- Android Studio，或能够构建原生 Android 项目的 DevEco Studio。
- JDK 21（推荐）。请确认 Gradle JDK 指向真实存在的 JDK，而不是已经被移除的旧 IDE Runtime。
- Android SDK 36。
- 项目自带 Gradle Wrapper `9.5.0`，不需要单独安装 Gradle。

主要技术版本：

| 组件 | 版本 |
| --- | --- |
| Android Gradle Plugin | 9.3.0 |
| Kotlin | 2.2.10 |
| Jetpack Compose BOM | 2026.02.01 |
| minSdk | 29 |
| targetSdk | 36 |

## 构建 Android 应用

```powershell
git clone https://github.com/tsuba-abcccc/maimai-queue-terminal.git
Set-Location maimai-queue-terminal
.\gradlew.bat :app:assembleLocalDebug
```

调试 APK 生成在：

```text
app/build/outputs/apk/local/debug/app-local-debug.apk
```

生成带版本号、可直接辨认的本地版交付文件：

```powershell
.\gradlew.bat :app:packageLocalDebugApk
```

文件会复制到 `output/apk/maimai-Q-0.7.0-local.apk`。

macOS 或 Linux 使用：

```bash
./gradlew :app:assembleLocalDebug
```

第一次构建需要联网下载 Android 和 Gradle 依赖。

### 纯本地模式

公开构建固定使用 `local` 变体。它的应用 ID 为 `com.abcccc.maimaiqueue.local`，不申请 Android 联网权限，构建内容中也强制写入空的服务器地址和令牌；即使构建机器保存了生产配置，公开 APK 也不能调用同步接口。应用仍会在本机保存队列、玩家资料和操作日志，界面中不会显示网站同步状态或开关。

生成纯本地正式版候选：

```powershell
.\gradlew.bat :app:assembleLocalRelease
```

当前项目没有在 Gradle 中保存正式签名配置，因此该命令生成的是尚未签名、不能直接发布的候选文件：

```text
app/build/outputs/apk/local/release/app-local-release-unsigned.apk
```

#### 推荐：使用 Android Studio 签名

1. 选择“Build” → “Generate Signed App Bundle or APK”。
2. 选择“APK”和 `app` 模块，新建或选择仅由发布者保管的 keystore。
3. 将构建变体设为 `localRelease`，完成向导并记下签名 APK 的输出位置。
4. 妥善离线备份 keystore；以后发布更新必须继续使用同一签名，否则已安装用户无法直接升级。

keystore、别名和密码不得写入仓库。Android Studio 可以直接构建签名包，不要求预先执行 `assembleLocalRelease`。

#### 备选：使用 Android SDK 命令行工具签名

以下 PowerShell 示例不会把密码写入命令；`apksigner` 会在执行时安全地询问密码。请把 Android SDK、keystore 和别名占位符替换为本机实际值：

```powershell
$unsignedApk = 'app\build\outputs\apk\local\release\app-local-release-unsigned.apk'
$alignedApk = 'app\build\outputs\apk\local\release\app-local-release-aligned.apk'
$signedApk = 'app\build\outputs\apk\local\release\app-local-release-signed.apk'

& '<Android SDK>\build-tools\<已安装版本>\zipalign.exe' -p -f 4 $unsignedApk $alignedApk
& '<Android SDK>\build-tools\<已安装版本>\apksigner.bat' sign --ks '<keystore 路径>' --ks-key-alias '<密钥别名>' --out $signedApk $alignedApk
& '<Android SDK>\build-tools\<已安装版本>\apksigner.bat' verify --verbose --print-certs $signedApk
```

公开渠道只能上传已经验证签名的 `localRelease` APK，例如上述 `app-local-release-signed.apk`。禁止上传 `app-local-debug.apk`、`app-local-release-unsigned.apk`、旧版 `app-debug.apk`，以及任何 `app-terminal-*` 文件。

### 配置网站同步

不要把正式令牌写入仓库。需要为受控终端预置连接时，可放在开发账户的 `~/.gradle/gradle.properties`：

```properties
ENABLE_TERMINAL_BUILD=true
QUEUE_SYNC_URL=https://your-domain.example/api/queue-status
QUEUE_SYNC_TOKEN=<与服务器一致的高强度随机令牌>
```

也可以使用同名环境变量。这两个构建值只作为应用首次运行时的默认连接；未预置令牌的 `terminal` 版本仍可安装，再由管理员在“更多”→“应用设置”中填写。现场终端调试包使用：

```powershell
.\gradlew.bat :app:packageTerminalDebugApk -PENABLE_TERMINAL_BUILD=true
```

`terminal` 变体必须通过 `ENABLE_TERMINAL_BUILD=true` 显式开启，应用 ID 保持 `com.abcccc.maimaiqueue`，可覆盖安装现有现场版本。应用内修改连接前需要先关闭网站同步；地址必须使用 HTTPS，可以填写站点根地址或完整的 `/api/queue-status` 地址；终端同步令牌至少为 32 个 UTF-8 字节。保存有效连接后才能重新开启网站同步。

若构建时预置了令牌，该令牌会进入 APK；应用内保存的连接只保存在终端本机。因此：

- GitHub 或其他公开渠道不得上传任何 `app-terminal-*`。
- 公开分发版与现场终端版应使用不同签名和不同配置。
- 现场终端 APK 只在受控设备间传递；令牌泄漏后立即在服务端轮换。

现场终端文件会复制到 `output/apk/maimai-Q-0.7.0-terminal.apk`，不得作为 GitHub 公开 Release 附件。

## 部署队列 API

后端位于 [`cloud-server/`](cloud-server/)，使用 Flask、Gunicorn 和 SQLite。最简 Docker 部署：

```bash
cd cloud-server
cp .env.example .env
# 编辑 .env，分别设置 QUEUE_SYNC_TOKEN、QUEUE_BOT_TOKEN 和资料库作用域
docker compose up -d --build
```

然后将 [`nginx-location.conf.example`](cloud-server/nginx-location.conf.example) 中的精确路径加入 HTTPS 站点，并检查：

```bash
curl https://your-domain.example/queue-api-healthz
```

应返回：

```json
{"service":"maimai-queue-status","status":"ok"}
```

服务器还支持 systemd 部署、主终端优先和离线后的备用终端接管。完整步骤见[后端部署说明](cloud-server/README.md)。

## 数据和隐私

| 数据 | 本机保存 | 私有云端 | 公开网站 |
| --- | --- | --- | --- |
| 昵称、机台、队列位置 | 是 | 是 | 是 |
| 游玩偏好、暂缓一轮、暂时离开、未到场 | 是 | 是 | 是 |
| 公开队列事件 | 是 | 是 | 是 |
| 当前登记的 QQ 号 | 是 | 是 | 由玩家决定，仅当前登记详情 |
| 性别、默认资料偏好 | 是 | 是 | 否 |
| 玩家资料 UUID | 是 | 是 | 否 |
| 本机资料编辑日志 | 是 | 否 | 否 |

应用已关闭 Android 系统备份。开启网站同步后，玩家资料和完整 QQ 通过需要专用令牌的私有接口保存，用于 Koishi Bot 身份和排队通知。只有选择“允许网站显示”的玩家，其 QQ 才会随当前有效登记进入网站详情；性别、默认偏好和资料 UUID 不公开。服务器修改会先成为待执行命令，只有终端按本地规则接受后才生效。

## 测试

运行 Android 单元测试：

```powershell
.\gradlew.bat :app:testLocalDebugUnitTest
.\gradlew.bat :app:testTerminalDebugUnitTest -PENABLE_TERMINAL_BUILD=true
```

运行后端测试：

```powershell
Set-Location cloud-server
python -m unittest -v
```

运行 Koishi OneBot 插件测试：

```powershell
Set-Location koishi-bot
pnpm install
pnpm test
```

测试覆盖队列演化、暂缓一轮和暂时离开、QQ 资料、资料认领、时间估算、队列持久化、公开快照、私有资料同步、命令冲突、操作日志、后端接口和 Bot 消息格式。

## 项目结构

```text
maimai-queue-terminal/
├─ app/                    Android 终端、界面和平台接入测试
├─ queue-core/             纯 Kotlin 队列模型、本轮规划和不变量测试
├─ cloud-server/           可选的 Flask 同步 API 与部署配置
├─ koishi-bot/             Koishi OneBot 插件、接入说明和测试
├─ docs/
│  ├─ cloud-queue-sync.md  同步协议和公开字段边界
│  ├─ update.md            累计更新日志
│  ├─ user-manual.md       玩家使用手册
│  └─ images/              README 使用的公开界面图片
├─ output/pdf/             排版后的玩家手册 PDF
├─ output/apk/             本机构建的版本化 APK（不进入 Git）
└─ gradle/                 Gradle Wrapper 与版本目录
```

## 当前限制和后续方向

- “使用移动设备登记”依赖现场终端生成的短时二维码，不能脱离现场或作为远程预约入口使用。
- 玩家资料仍由终端执行最终冲突校验；云端较新版本可以回流本机，同版本或旧版本不会覆盖本机资料。
- 网站与 Koishi Bot 的线上登记仅接受已经绑定 QQ 的玩家资料；移动设备登记页可以新建资料或补全旧资料，但提交后仍需终端确认才会加入现场队列。
- 线上登记必须在创建后的 30 分钟内到终端签到；超过 30 分钟，或轮到进入游玩位置时仍未签到，登记会自动退出。网站暂不提供暂缓一轮、暂时离开、切换机台、修改偏好或退出排队等队列管理操作。
- QQ Bot 只允许玩家管理与发送者 QQ 对应的本人登记，不提供远程调整其他玩家或整条队列的能力。
- 当前公开站点的前端源码不在本仓库中。
- 仓库没有包含可公开使用的生产同步令牌或正式签名密钥。

后续计划包括支持一至四台机台、继续扩展权限明确的网站交互、二维码身份入口，以及完善公开安装版与现场终端版的发布流程。

## 参与开发

提交改动前请注意：

1. 队列逻辑应模拟现场真实推进，不能只调整画面顺序。
2. 游玩位置玩家与等待玩家的状态变化必须分开处理。
3. 会延后其他玩家的操作需要明确确认和对应测试。
4. 新增公开同步字段时，必须同步检查隐私白名单和旧协议兼容。
5. 界面用语统一使用“登记”“等待位置”“游玩位置”“暂缓一轮”“暂时离开”和“未到场”。

建议先创建 Issue 说明现场场景、预期队列演化和边界情况，再提交 Pull Request。

## 许可证

本项目按 [GNU General Public License v3.0](LICENSE) 发布，SPDX 标识为 `GPL-3.0-only`。复制、修改和分发时应遵守许可证中的源代码公开与版权声明要求。

## 声明

maimai Q 是独立开发的非官方现场排队工具，与 SEGA 或 maimai 官方没有隶属、授权或背书关系。项目名称中提及的产品和商标归其各自权利人所有。

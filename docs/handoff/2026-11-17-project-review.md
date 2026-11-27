# maimai Q 交接记录 · 2026-11-17 项目通读与 Mac 开发环境

本文件由 Claude（AI 助手）在 2026-11-17 的一次会话中写成，供后续接手的人或代理使用。它记录三件事：对项目的通读总结、这次会话里做过的全部操作、以及各项判断的依据。

会话的起因是维护者把仓库克隆到一台新的 Mac 上，先要求“通读项目，了解项目进度”，随后问在这台 Mac 上开发需要部署什么环境。

**状态**：本文件是第四版，最后更新于 2026-11-18。第 1 至 6 节写于 11 月 17 日上午，当时什么都没有安装、仓库也没有改动。此后工作副本已搬到内置盘，开发环境已搭好并实际跑通，几处文档已修改，协议文档已按当前代码重写（7.15）。**第 7 节记录这些后续工作，凡与前文冲突之处以第 7 节为准。** 所有改动都还没有提交。

文中的结论按来源分三类，引用时请保留这个区分：

- **已核实**：本次会话直接看到了文件内容、命令输出或接口响应。
- **文档所述**：仓库文档这样写，本次没有独立验证。
- **推断**：根据已核实的事实推出，没有直接证据。

环境部分另有一类**代理核对**：由后台代理查官方文档得出，我没有逐条复查，也没有在本机执行。

---

## 1. 项目通读总结

### 1.1 结论

项目处在“单终端功能线已收尾、下一个大版本还没动”的状态。最新的 0.13.6 在 2026-09-27 发布并部署，之后到 2026-11-17 约 7 周没有新提交。下一个里程碑 0.14.0（多终端联动）尚未开工，也没有设计文档。

项目原先服务的机厅（塘沽金街店）已于 2026-09-01 停业。维护者确认系统现在在“王者之风·滨海万达店”使用。线上最新一份队列快照的机厅名与此一致，但带有测试同步标记，这个标记的后果见 7.6。详见 1.9。

### 1.2 项目是什么

maimai Q 是面向机厅现场的电子排队终端，用电子队列模拟现场“挪东西排卡”的流程，支持 1 至 10 台机台。它处理的是现场排队，不是线上预约。

核心概念只有几个：一名玩家的一次排队记录叫**登记**；按顺序和游玩偏好组成的一轮候场玩家叫**等待位置**；正在机台上的叫**游玩位置**。玩家可以选择单人游玩、允许他人加入，或与朋友组成**固定组合**。特殊状态有**暂缓一次**（只跳过下一次机会）、**暂时离开**（被排队计算忽略，最多轮空 3 次）和**未到场**。

项目以 GPL-3.0-only 发布，仓库公开在 GitHub 的 `tsuba-abcccc/maimai-queue-terminal`。

### 1.3 架构与数据权威

```text
现场玩家 ──> Android 横屏终端 ──> 本机队列与玩家资料（权威）
                  │  HTTPS 上传快照；拉取并回执命令
                  ▼
            Flask 队列 API ──> SQLite（公开快照 / 私有资料与命令）
              ▲      ▲      ▲
              │      │      │
          队列网站  QQ Bot  管理 App
```

贯穿全项目的几条设计规则，改任何功能前都要先确认不违反：

- **终端是唯一权威**。队列和玩家资料以 Android 终端本机保存的状态为准，本机保存先于云端同步，服务端故障不影响现场排队。
- **远程操作只是待执行命令**。网站、QQ Bot、管理 App 的修改先在服务端成为命令，由终端按本地规则校验并落盘后才生效；任何一端都不直接写队列快照。
- **公开数据与私有资料严格分离**。服务端按白名单重新构造公开数据，QQ 只在玩家选择公开时随当前有效登记出现。
- **风险配置要先关闭登记**。会改变队列结构的设置（机台数量、游玩容量等）只能在关闭登记后修改，并由统一的配置修订号保护，旧修订下生成的远程命令不会作用于新配置。
- **所有队列操作走同一个引擎**。`queue-core` 的 `QueueEngine` 定义了 31 种动作；计划生成后若队列已变化，会以 `STALE_STATE` 拒绝，而不是覆盖较新的现场状态。

### 1.4 代码地图

| 目录 | 内容 | 规模（已核实） |
| --- | --- | --- |
| `app/` | Android 应用，Kotlin + Jetpack Compose，单模块三个 flavor | 主源码约 3.6 万行，单测约 1.0 万行 |
| `queue-core/` | 纯 Kotlin 队列模型、本轮规划、动作引擎和不变量 | 源码约 3,000 行，单测约 3,200 行 |
| `cloud-server/` | Flask + gunicorn + SQLite 的同步 API | `app.py` 12,962 行，`test_app.py` 8,583 行 |
| `koishi-bot/` | Koishi OneBot 插件，TypeScript | `src/index.ts` 3,279 行，测试约 3,000 行 |
| `public-site/` | Vue 3 + Vite 的独立公开队列页 | 源码约 6,800 行 |
| `docs/` | 路线、开发记录、更新日志、协议、部署、手册、各版本说明 | |
| `scripts/` | 玩家手册 PDF 生成脚本 | |

三个 Android flavor：

| flavor | 应用 ID | 用途 | 备注 |
| --- | --- | --- | --- |
| `local` | `com.abcccc.maimaiqueue.local` | 完全离线版 | 不申请联网权限，地址和令牌强制为空 |
| `terminal` | `com.abcccc.maimaiqueue` | 联网现场终端，横屏 | 必须显式传 `-PENABLE_TERMINAL_BUILD=true` 才参与构建 |
| `management` | `com.abcccc.maimaiqueue.management` | 管理后台，手机竖屏 | 默认参与构建，使用独立的管理令牌 |

值得先认识的文件：

- [`queue-core/.../QueueEngine.kt`](../../queue-core/src/main/kotlin/com/abcccc/maimaiqueue/QueueEngine.kt)：动作定义、计划与执行。
- [`queue-core/.../QueueModels.kt`](../../queue-core/src/main/kotlin/com/abcccc/maimaiqueue/QueueModels.kt)：不可变的 `MachineQueue` 及全部队列操作。
- [`queue-core/.../QueueInvariants.kt`](../../queue-core/src/main/kotlin/com/abcccc/maimaiqueue/QueueInvariants.kt)：结构不变量，76 行，读完就知道哪些状态永远不该出现。
- [`app/.../QueueTerminalUi.kt`](../../app/src/main/java/com/abcccc/maimaiqueue/QueueTerminalUi.kt)：终端全部界面，20,276 行、148 个 Composable 在一个文件里。
- [`app/.../QueueCloudSync.kt`](../../app/src/main/java/com/abcccc/maimaiqueue/QueueCloudSync.kt)：快照上传、命令拉取、协议版本协商。
- [`app/.../RemoteQueueOperations.kt`](../../app/src/main/java/com/abcccc/maimaiqueue/RemoteQueueOperations.kt) 和 [`ManagementQueueActions.kt`](../../app/src/main/java/com/abcccc/maimaiqueue/ManagementQueueActions.kt)：远程命令和管理命令在终端侧的最终裁决。
- [`cloud-server/app.py`](../../cloud-server/app.py)：服务端全部逻辑，20 张表、约 55 条路由，按 `/api/queue-status`、`/api/queue-terminal/`、`/api/queue-bot/`、`/api/queue-online/`、`/api/queue-mobile/`、`/api/player-account/`、`/api/player-profile-creation/`、`/api/queue-management/` 分组。

服务端使用三个互不相同的令牌：`QUEUE_SYNC_TOKEN`（终端）、`QUEUE_BOT_TOKEN`（Bot）、`QUEUE_MANAGEMENT_TOKEN`（管理 App）。

### 1.5 当前版本（已核实）

| 组件 | 版本 | 依据 |
| --- | --- | --- |
| 现场终端 / 本地版 | 0.13.6，构建号 72 | `app/build.gradle.kts` |
| 管理 App | 0.13.2，构建号 68 | `app/build.gradle.kts` |
| 队列网站 | 0.13.4 | `public-site/package.json` |
| QQ Bot | 0.3.13 | `koishi-bot/package.json` |
| 云端同步协议 | schema 8，服务端兼容 1–8 | `QueueCloudSync.kt:2919`、`cloud-server/app.py:21` |
| 本机持久化格式 | schema 7 | `LocalQueueStateRepository.kt:445` |

`build.gradle.kts`、`.env.example`、`compose.yaml`、`app.py` 的默认值、两个 `package.json`、`queue-client-version.json` 和玩家手册中的版本号彼此一致。

### 1.6 版本演进

仓库从 2026-07-22 的首次提交到 2026-09-27 共 81 个提交，标签从 `v0.2.13` 到 `v0.13.6`。完整更新见 [`docs/update.md`](../update.md)，这里只列转折点：

| 版本 | 时间 | 主要内容 |
| --- | --- | --- |
| 0.2–0.3 | 7 月下旬 | 基础排队、营业时间与闭店收尾、QQ 玩家资料同步、Bot 通知 |
| 0.4 | 7-26 | 网站与 Bot 的线上登记、远程队列操作；许可证改为 GPLv3 |
| 0.5 | 7-27 | 通知分项设置、二维码“使用移动设备登记” |
| 0.6 | 7-31 | 队列模型迁入 `queue-core`，统一本轮规划器 |
| 0.7 | 8-02 | 统一队列动作引擎；等待队列投影与共同游玩预览 |
| 0.8–0.9 | 8-05 | 1–4 台机台；完整机台配置（容量 1 或 2、计划时间），schema 6 |
| 0.10 | 8-11 | 1–10 台机台、分组、稳定机台身份，schema 7；公开测试部署 |
| 0.11 | 8-15 | 机厅与终端身份、六位玩家编号，schema 8 |
| 0.12 | 8-17 | 网页玩家账户与资料权限 |
| 0.13.0 | 8-25 | 独立的手机竖屏管理 App |
| 0.13.1–0.13.4 | 8-25 至 9-06 | 资料编辑入口、自定义头像、分步登录与二维码建档、推荐排序 |
| 0.13.5 | 9-06 | 管理后台删除玩家资料，接管营业时间和机台管理 |
| 0.13.6 | 9-27 | 终端 `2000×1200` 宽屏布局、中点间距统一 |

### 1.7 尚未完成的工作

项目自己的清单在 [`docs/roadmap.md`](../roadmap.md) 的“未完成工作总览”，以下是归纳：

1. **真机验收**。管理 App 竖屏（长昵称、头像间距、扫码与过期重试、小屏按钮不溢出）和 `2000×1200` 终端的触控截图验收，从 0.13.0 起每次发布记录都写着“待现场完成”。
2. **管理后台剩余边界**。同步总开关、同步模式、服务器地址和令牌的远程迁移与故障恢复仍由现场终端维护；网页账户绑定和重新绑定没有放进管理 App。
3. **0.14.0 多终端联动**。初版最多 3 台终端共享同一队列，需要先确定唯一写入权、冲突仲裁、主终端失联接替、离线恢复、时钟偏差和命令去重。尚未开工。0.10 至 0.13 在 `docs/releases/` 下各有一份 plan，0.14 没有。
4. **0.15.0** 多机厅与跨机厅资料；**0.16.0** 游玩时间自动学习；**1.0.0** 公开部署版（多租户、迁移、备份恢复演练、运维加固）。

服务端已有一部分多终端的地基（文档所述并在代码中可见）：`QUEUE_PRIMARY_DEVICE_ID` 主终端优先、`test` 与 `takeover` 两种同步模式、命令领取租约、终端运行实例身份。当前仍是单一权威写入终端，另一终端在线时第二份实例会被拒绝。

### 1.8 维护者明确要求、容易被遗忘的约束

这些写在 [`docs/roadmap.md`](../roadmap.md) 第 16–23 行，后续任何改动都要遵守：

- 管理后台的能力基准是**现场终端和 `queue-core`**，不能退化成 QQ Bot 或网页端的能力子集。
- **普通拖动全队列排序是日常操作，不是敏感操作**。管理后台绑定后它仍必须可用；“敏感操作”只针对设置。
- 终端新建玩家资料默认先出二维码，手机网页建档并同时绑定网页账户；终端保留“仅创建本地玩家资料”，这种资料仍上传云端，只是不绑定网页账户。
- 网页登录先只显示 QQ 和“继续”，已绑定才显示密码；忘记密码由现场管理员核验处理，不提供自助重置。
- 网页绑定账户后终端默认只读，网页可以重新开放终端编辑；QQ、密码和账户绑定始终不能在终端编辑。
- 多终端联动不混入 0.13.x 补丁，先收尾单终端功能。

提交改动时的通用要求在 [`README.md`](../../README.md) 的“参与开发”一节：队列逻辑要模拟现场真实推进而不是只调画面顺序；新增公开同步字段要同步检查隐私白名单和旧协议兼容；界面用语统一使用“登记”“等待位置”“游玩位置”“暂缓一次”“暂时离开”“未到场”。

### 1.9 线上、机厅与发布现状

**机厅（已核实，来源是同一块盘上的主站仓库 `site-docs`）**

- 主站的停业通知页写明：王者之风游乐体验中心（塘沽金街店）已于 2026-09-01 正式停止营业；店家此前计划把日框机台迁至滨海万达店、国框机台迁至泰达永旺店，最终去向以店家公布为准。
- 主站的排队页现在只显示“排队状态服务暂停”，共享的队列组件不再被任何页面引用。

**线上服务（已核实，2026-11-17 16:08 UTC 前后）**

- `queue.abcccc.top` 和 `abcccc.top` 的 `/queue-api-healthz` 均返回 200。
- `/api/queue-versions` 显示终端 0.13.6、网站 0.13.4、Bot 0.3.13，三者状态均为 `LATEST`。
- `/api/queue-status` 返回的最新快照：`test_data` 为 `true`；机厅名“王者之风·滨海万达店”；4 台机台，A、B 各 7 份登记；登记关闭；终端最后一次上传在 2026-11-16 13:45 UTC（北京时间 21:45），查询时终端已离线。

**维护者确认**（2026-11-17）：系统现在就在滨海万达店使用。我此前根据 `test_data` 标记推断“不是营业中的队列”，这个推断是错的；标记本身是事实，它意味着什么见 7.6。开发记录里 9 月 27 日的“新实例”是否就是为这家店准备的，没有问过，仍是推断。

**发布**：GitHub 上 `v0.13.6` 已发布，含 6 个附件；本地克隆与 `origin/main` 一致，都在 `6a04c24`。

---

## 2. 发现的问题与待决事项

### 2.1 需要维护者决定或处理的

| # | 事项 | 证据 | 状态 |
| --- | --- | --- | --- |
| 1 | GitHub 的 Latest 标记仍在 `v0.13.5`，`/releases/latest` 指向旧版 | `gh release list` 显示 `v0.13.6` 的 `isLatest=false` | 未处理。这是对外可见的改动，需维护者同意 |
| 2 | 外部贡献者的 PR #1 自 9 月 27 日起无人回复 | `gh pr view 1`、`gh pr diff 1` | 未处理，见下 |
| 3 | 协议文档落后三个大版本 | [`docs/cloud-queue-sync.md`](../cloud-queue-sync.md) 第 11、15 行仍写 schema 7；全文没有机厅身份、网页账户、管理 API、头像、二维码建档 | 11 月 18 日已重写，见 7.15 |
| 4 | 路线文档仍写 0.13.6“待发布” | [`docs/roadmap.md`](../roadmap.md) 第 25 行，与开发记录和 GitHub 矛盾 | 未处理 |
| 5 | README 的 APK 文件名仍是 0.13.4 | [`README.md`](../../README.md) 第 226、314 行；实际由 Gradle 任务按当前版本号命名 | 未处理 |
| 6 | 文档里残留 `:codex-annotation{index="1"}` | `docs/roadmap.md` 第 19 行、`docs/releases/0.13.3-player-profile-creation.md` 第 26 行 | 未处理 |
| 7 | 开发记录的 0.13.5 条目排在文件末尾，标题前缺空行 | [`docs/development-log.md`](../development-log.md) 第 255 行 | 未处理，影响很小 |
| 8 | 路线文档里 0.14.0 出现两次，表述略有出入 | `docs/roadmap.md` 第 48 行和第 151 行附近 | 未处理，影响很小 |

第 3 至 6 项我在第一轮结束时问过是否现在修掉，维护者没有回答而是转到了环境问题，所以保持未处理。

**关于 PR #1**。标题是 `chore: align Android SDK configuration`，实际改动比标题大：minSdk 29→28，compileSdk 36.1→37.2，targetSdk 36→37，同时升级 AGP（9.3.0→9.4.1）、Kotlin（2.2.10→2.4.20）、Compose BOM、多个 AndroidX 库、zxing、org.json 和 Gradle Wrapper（9.5.0→9.8.0），并把 `gradlew` 的文件模式改为可执行。共 5 个文件，+23/−22，可合并，无评审无评论。降低 minSdk 和提高 targetSdk 都是影响兼容性和运行时行为的决定，应由维护者判断。

这个 PR 与环境搭建有直接关系：如果合并，需要安装的 SDK Platform、Build-Tools 和 Gradle 发行版都会变。最好在装 Android SDK 之前先决定它的去留。

### 2.2 环境核对中新发现的问题

这些是第二轮里发现的，同样都没有处理：

| # | 事项 | 证据 | 影响 |
| --- | --- | --- | --- |
| 9 | 四个文件的行尾是 CRLF 与 LF 混用 | `git ls-files --eol` 显示 `i/mixed`：`docs/development-log.md`、`QueueStatusPanel.vue`、`MobileRegistrationFlow.vue`、`MobileProfileSettings.vue`；仓库没有 `.gitattributes` | 编辑器保存时可能把整个文件改成单一行尾，一行改动变成上千行 diff；发布检查里的 `git diff --check` 也可能因此报错 |
| 10 | `.gitignore` 没有忽略 `._*` | 只有 `.DS_Store`（第 19 行） | 只在 exFAT 这类盘上才有影响，见 3.2 |
| 11 | 管理版构建会无条件嵌入管理地址和令牌 | `app/build.gradle.kts` 第 35–46 行：`QUEUE_MANAGEMENT_URL` 和 `QUEUE_MANAGEMENT_TOKEN` 既读 Gradle 属性也读**环境变量**，没有像终端版那样的显式开关 | 在导出了服务端环境变量的 shell 里构建管理版，令牌会进入 APK |
| 12 | 发布记录里提到的浏览器行为验收和设备界面测试不在仓库里 | `public-site/package.json` 没有 `test` 脚本；`app/src/androidTest` 只有模板测试 | 这一道发布门槛无法从克隆复现，只能手工做或从旧机器找回 |
| 13 | 仓库里的服务器命令和 systemd 单元文件描述的是 9 月 27 日之前的旧实例 | 开发记录第 20–21 行写明现用实例是 `…-instance-20260927`，旧 Bot 单元必须保持停用 | 照文档原样执行部署命令会操作错对象 |
| 14 | 应用以 targetSdk 36 强制横屏或竖屏，而 Android 16 在大屏设备上会忽略这种锁定 | `MainActivity.kt` 第 27–31 行、`app/src/management/AndroidManifest.xml` 第 9 行；平台规则为代理核对 | 若新终端是 Android 16 平板，界面可能被旋转。真实设备的系统版本和屏幕密度未知，是否受影响未确定 |

### 2.3 结构性观察

- `QueueTerminalUi.kt`（20,276 行）和 `cloud-server/app.py`（12,962 行）都是单文件。做 0.14.0 这种跨端改动时，这两个文件的体量会拖慢评审和并行修改。
- 0.14.0 没有设计文档，而路线要求“协议和故障行为确认前不开放多终端同时写入”，所以下一步的第一件事应是写方案而不是写代码。

### 2.4 查过、确认没有问题的

记在这里是为了让后来的人不必重查：

- **每个 Release 里三个 APK 的字节数完全相同**（例如 0.13.6 的三个都是 26,111,243 字节）。我怀疑过是同一个文件传了三次，查了 GitHub 记录的 SHA-256，三个摘要各不相同，而且 0.13.2、0.13.4、0.13.5 也是同样的现象。不是上传错误。
- **测试数量与文档一致**：`test_app.py` 有 168 个 `def test_`，Koishi 测试有 80 个用例，与开发记录的“168 项”“80 项”吻合。Kotlin 侧按 `@Test` 计数约 510 个（`app` 420，`queue-core` 90）。
- **`public-site/src/queue/` 的六个共享文件与主站仓库里的副本内容一致**。其中三个按字节比较不同，去掉回车符后完全相同，差别只在行尾。
- 仓库里没有 `TODO`、`FIXME`、`HACK` 标记。
- 没有未处理的 Issue；`git status` 干净，没有 stash。

### 2.5 没有验证的

- **测试没有在本机运行**。上面只是静态计数，“全部通过”来自开发记录。
- **没有任何安装、构建或启动被实际执行**。第 3 节的命令和预期结果都没有在这台 Mac 上跑过。
- **真机表现**、**生产服务器内部状态**都没有验证。

---

## 3. 这台 Mac 的开发环境

### 3.1 结论

日常开发真正缺的只有两样：**一个 JDK 21** 和 **Android SDK**。服务端、Bot 和网站需要的工具都已经在这台 Mac 上，只差安装依赖。另外建议先把工作副本从外置盘搬到内置盘。

| 部分 | 现状 | 还需要 |
| --- | --- | --- |
| 服务端 | uv 已装，且已有它管理的 Python 3.13.18 | 建虚拟环境，装三个依赖 |
| QQ Bot | Node 24、pnpm 12 已装 | `pnpm install` |
| 公开网站 | 同上 | `pnpm install`，可能要先批准 esbuild 的构建脚本 |
| Android 与 `queue-core` | 什么都没有 | JDK 21；Android SDK；看界面还需要模拟器或真机 |
| 发布与部署 | 不具备 | 长期发布证书的 keystore 和密码、生产服务器的访问方式 |
| 手册 PDF、宣传视频 | 不具备，日常用不到 | 需要时再装 |

Docker 不需要。本地运行服务端直接用 Python；Docker 只在按 `compose.yaml` 部署时用。

### 3.2 机器现状（已核实）

| 项目 | 状态 |
| --- | --- |
| 硬件与系统 | Apple M5（arm64），16 GB 内存，macOS 27.2.1；内置 APFS 盘剩余约 352 GiB |
| 账户 | 属于管理员组（安装 JDK 的 `.pkg` 需要输入密码） |
| Rosetta 2 | 已装 |
| Xcode 命令行工具 | 已装，git 2.56.1 |
| Homebrew | 已装（7.0.14） |
| Node.js | v24.22.3，由 fnm 管理，是 fnm 里唯一的版本；npm 11.19.1，corepack 可用 |
| pnpm | 12.21.0 |
| uv | 0.13.8；它管理的 CPython 3.13.18 已在本机 |
| 系统 Python | 3.9.6，不能用于本项目（见 3.5） |
| GitHub CLI | 已装并已登录 |
| 编辑器 | 只有 VS Code |
| 浏览器 | 只有 Safari，没有 Chrome 或其他 Chromium 系浏览器 |
| JDK / Java 运行时 | **没有**。`/usr/bin/java` 只是系统占位程序 |
| Android Studio、Android SDK、adb、模拟器 | **没有** |
| Docker 或其他容器运行时 | **没有** |
| ffmpeg、PowerShell | **没有** |
| `~/.gradle`、`~/.android/debug.keystore`、仓库内 `local.properties` | 都不存在 |
| 主站仓库 | 已在同一块外置盘的 `site-docs` 目录，有另一个会话留下的未提交改动 |

第一版说“Python 只有系统自带的 3.9.6”，这是错的：当时只查了 `PATH`，没有查 uv 自己管理的解释器。

### 3.3 外置盘：实测结果与建议

仓库目前克隆在一块 exFAT 格式的外置盘上。实测（已核实，见 4.2）：

| 行为 | 结果 |
| --- | --- |
| 符号链接 | 可用 |
| 硬链接 | 失败，`Operation not supported` |
| 权限位 | `chmod` 无效，所有文件恒为 `rwx------` |
| 大小写 | 不区分 |
| 扩展属性 | 写入后会生成 `._文件名` 伴生文件 |
| 分配单元 | 128 KiB |

这些行为带来四个具体后果：

- **Gradle 可能直接构建失败**。Gradle 上游有一个至今未关闭的缺陷（`gradle/gradle` 第 4329 号，exFAT 上文件锁失败），表现为等待锁超时。这是代理核对的结论，**没有在本机复现**，应视为“很可能”而不是“一定”。
- **小文件占用膨胀约四倍**。主站仓库的 `node_modules` 内容是 370 MB，实际占用 1.5 GB（已核实）。
- **打包和上传出去的文件权限是错的**。从这块盘做的压缩包或 `rsync -a` 会把每个文件记成 `0700`，部署到服务器后 Web 服务器读不了（推断，未执行）。
- **`._` 伴生文件会混进 git**，因为 `.gitignore` 没有忽略它们。

**建议把日常工作副本放到内置盘，外置盘只做备份或搬运。** 内置盘空间充足，搬过去之后上面四条全部消失。几个注意点：

- 这个仓库里未跟踪的只有 `docs/handoff/`，重新克隆后把它复制过去即可；主站仓库有未提交的改动，要么先提交，要么整目录复制。
- `gradlew` 在 git 里是 `100644`，在外置盘上碰巧可以直接执行，到了内置盘要用 `sh ./gradlew`，或在本地 `chmod +x`。
- 密钥、令牌这类文件不要放在这块外置盘上：它没有权限位，插到任何电脑上都能读。
- Claude 的会话记忆是按路径存放的，搬家后不会跟过去（推断）。本文件就是为此准备的。

第一版曾写“exFAT 不支持符号链接”，那是凭印象说的，实测是错的。

### 3.4 安装清单

以下命令都**没有在本机执行过**。版本要求来自仓库文件（已核实）；兼容性结论来自官方文档（代理核对）。

#### 第一层：不装新软件就能跑通

**服务端**

```bash
uv venv --python 3.13 ~/.venvs/maimai-queue-cloud
source ~/.venvs/maimai-queue-cloud/bin/activate
uv pip install -r cloud-server/requirements.txt
cd cloud-server && python -m unittest -v
```

- 必须显式指定 3.13。不指定版本或直接用 `python3 -m venv` 会得到 3.9.6 的环境。
- uv 建的虚拟环境里没有 `pip`，要用 `uv pip`。
- 目标版本 3.13 来自 `cloud-server/Dockerfile`；依赖只有 `Flask>=3.1,<4`、`gunicorn>=23,<24`、`Pillow>=11,<13`。
- 测试不需要网络、Docker 或任何环境变量。
- 仓库还在外置盘上时，运行前要把 `QUEUE_DATABASE_PATH` 指到内置盘：`test_app.py` 导入 `app` 时会执行模块末尾的 `app = create_app()`，在默认路径建一个 WAL 模式的 SQLite 库（`app.py` 第 577、696、12958 行）。

**QQ Bot**

```bash
cd koishi-bot && pnpm install --frozen-lockfile && pnpm test
```

预期 80 项测试通过。Koishi 4.18 在 Node 24 上能否正常加载没有来源确认；如果不行，用 fnm 装一个 Node 22 作为退路。

**公开网站**

```bash
cd public-site && pnpm install --frozen-lockfile && pnpm run build
```

pnpm 11 起默认拒绝未经批准的依赖构建脚本，这一步可能因 esbuild 而以 `ERR_PNPM_IGNORED_BUILDS` 中止（代理核对，未执行）。主站仓库的解决办法是一个五行的 `pnpm-workspace.yaml`：

```yaml
packages:
  - .

allowBuilds:
  esbuild: true
```

这个文件在主站仓库里已经被 pnpm 12.21.0 接受（已核实文件存在，安装成功为主站交接记录所述）。在 `public-site/` 里加同样的文件是否提交，由维护者决定。

#### 第二层：需要安装

**JDK 21**

```bash
brew install --cask temurin@21
```

- `gradle/gradle-daemon-jvm.properties` 把 Gradle 守护进程固定在 Java 21。`gradlew` 启动脚本本身也需要一个 Java。装一个 JDK 21 同时满足两者。
- 这是 `.pkg` 安装包，会要求输入本机账户密码。
- 装完后在 `~/.zprofile` 里设置 `JAVA_HOME`，例如 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`。
- 不要把守护进程改成 Java 17：固定的 AGP 9.3.0 在 Java 17 上运行 lint 会崩溃，9.3.2 才修复（代理核对）。

**Android SDK**

需要三个包：Platform 36.1、Build-Tools 36.0.0、Platform-Tools。并且必须接受 SDK 许可协议，这一步只能由维护者本人操作。

安装方式有两条路，它们决定 SDK 的位置：

| 方式 | SDK 位置 | 特点 |
| --- | --- | --- |
| Android Studio | `~/Library/Android/sdk` | 自带 SDK 管理器、模拟器、Kotlin 代码提示；与模拟器同时运行时 16 GB 内存会吃紧。首次打开项目时会提示升级 AGP，应拒绝 |
| 仅命令行（Homebrew 的 `android-commandlinetools`） | `/opt/homebrew/share/android-commandlinetools` | 轻量；没有 Kotlin 代码提示，除非另配编辑器插件 |

选定后把路径写进 `ANDROID_HOME`，或在仓库根目录建一个 `local.properties`（已被 git 忽略）。

版本依据：AGP 9.3.0 要求 JDK 17 以上、Gradle 9.5.0、Build-Tools 36.0.0（代理核对，来源为 AGP 9.3.0 发布说明）。命令行包名 `platforms;android-36.1` 是按命名规律推出的，没有找到一手来源。

**装好之后的验证顺序**

```bash
sh ./gradlew :queue-core:test
sh ./gradlew :app:testLocalDebugUnitTest
sh ./gradlew :app:testManagementDebugUnitTest
sh ./gradlew :app:testTerminalDebugUnitTest -PENABLE_TERMINAL_BUILD=true
sh ./gradlew :app:assembleLocalDebug
```

全部是纯 JVM 测试，不需要设备。首次运行会下载 Gradle 9.5.0 和全部依赖。

#### 第三层：按需

**看界面**。需要一台 Android 10 以上的真机，或 arm64-v8a 镜像的模拟器。Apple Silicon 上模拟器不需要额外的虚拟化设置。看终端布局时选 API 35 及以下的镜像，原因见 2.2 第 14 项。

**终端版、管理版与本地服务端联调**。终端版的同步地址在代码里强制要求 `https`（`LocalQueueRuleSettings.kt` 第 842 行），自签名证书也不被信任。所以本机用 `http` 起的服务端不能直接给终端版用。日常开发界面和队列逻辑用 `local` 版，它不需要服务端；要做端到端联调，需要一个带公网可信证书的 HTTPS 地址。

**网站连本地服务端**。仓库里没有种子数据，页面在没有快照时只显示错误状态。可以参照 `cloud-server/test_app.py` 里的 `snapshot` 辅助函数手写一份快照 POST 上去。开发用的接口地址放在 `public-site/.env.development.local`，不要放 `.env.local`，后者在正式构建时也会被读入。

**手册 PDF**。脚本只找 Windows 和 Linux 的字体路径，在 Mac 上要用两个环境变量指定。本机 Word 自带与原 PDF 相同的等线体（已核实文件存在）：

```bash
MAIMAI_Q_PDF_FONT="/Applications/Microsoft Word.app/Contents/Resources/DFonts/Deng.ttf" \
MAIMAI_Q_PDF_BOLD_FONT="/Applications/Microsoft Word.app/Contents/Resources/DFonts/Dengb.ttf" \
uv run --with reportlab python scripts/generate_user_manual_pdf.py
```

苹方等系统中文字体是 CFF 轮廓，ReportLab 不接受（代理核对）。

**宣传视频**。需要 Chrome 和 ffmpeg。**不需要 PowerShell**：`build-video.ps1` 里生成联系表的部分用了 Windows 专有的绘图库，在 Mac 上跑不通，应直接用 `node` 运行 `render-motion-frames.mjs`，再用 ffmpeg 编码。第一版说“需要 PowerShell”是错的。

### 3.5 容易踩的坑

- **系统 Python 3.9.6 无法导入 `app.py`**。函数签名里用了 `str | None` 这种写法（第 378 行起，共 67 行），而文件没有 `from __future__ import annotations`，在 3.9 上定义函数时就会抛 `TypeError`（已核实代码；报错为推断）。
- **不要在 shell 配置文件里导出 `QUEUE_*` 或 `VITE_*` 变量**。服务端测试会断言默认值；Gradle 会把管理令牌嵌进 APK（2.2 第 11 项）；Vite 会把接口地址编进发布包。服务端变量放在已被忽略的 `cloud-server/.env.local` 里，只在子 shell 中加载。
- **下载可能需要代理**。本机 shell 配置文件里没有代理设置。Gradle 和 SDK 工具是 Java 程序，不读 `HTTPS_PROXY` 这类环境变量，如果直连下载源不通，要在 `~/.gradle/gradle.properties` 里单独配置。Gradle Wrapper 的网络超时只有 10 秒，网络问题会表现得像构建错误。
- **主站仓库推任何 tag 都会触发生产部署**（已核实 `.github/workflows/deploy.yml`）。不要在那个仓库里随手打 tag。
- **不要在仓库里直接改主站的队列组件**，它们的源头是本仓库的 `public-site/src/queue/`，会被下次同步覆盖。

### 3.6 需要维护者决定或提供的

**需要决定的**

1. 具体要做哪几部分。只做服务端、Bot、网站的话，今天就可以开始，不用装任何新软件。
2. Android 用 Android Studio 还是纯命令行。
3. 下载是否需要代理。
4. PR #1 合并与否（影响装哪个版本的 SDK）。
5. 这台 Mac 是否要负责发布和部署。

**只有从旧 Windows 机器或维护者手里才能拿到的**

| 东西 | 用途 | 缺了会怎样 |
| --- | --- | --- |
| 长期发布证书的 keystore、别名和两个密码 | 给公开 Release APK 签名。证书 SHA-256 为 `daa2e919…c925`（README 第 275 行） | **不可重建**。没有它，新包无法覆盖安装到任何已装过公开版的设备上 |
| 生产服务器的访问方式 | 部署 API、网站和 Bot | 无法从这台机器部署。仓库里提到的“已记录的主机指纹”不在仓库中，首次连接前应从旧机器或云控制台核对 |
| 旧机器上被 git 忽略的目录 | `.agents/`、`.codex/`、`.codex-tmp/`、`tmp/`、`output/` | 里面可能有旧工作流的说明、辅助脚本或发布产物，仓库里无从还原。旧机器停用前应看一眼 |
| `~/.gradle/gradle.properties` 里的私有值 | 预置连接的受控构建 | 可重建。没有也能构建，安装后在应用里手动填写 |
| 旧机器的 `~/.android/debug.keystore` | **仅在**仍有设备装着旧机器构建的调试包、且设备上的数据需要保留时才需要 | 见下 |

**关于调试密钥**。第一版把它列为必须带过来的东西，依据是 [`docs/public-beta-deployment.md`](../public-beta-deployment.md) 第 290 行“维护者目前现场安装的联调终端使用 Android Debug 证书”。核对后这一条降为有条件：那句话写于 8 月 11 日，之后三份发布说明都要求操作者确认使用长期发布证书，而原机厅已经停业。日常开发不需要它，首次调试构建会自动生成新的。只有当某台仍保存着数据的设备装的是旧调试包时才需要，而且必须在这台 Mac 第一次调试构建**之前**复制过来，因为已存在的文件不会被覆盖。给任何保存着数据的设备装新包之前，先读出它已装应用的证书，不要卸载重装。

### 3.7 核对是怎么做的，可信到什么程度

环境要求由一个后台工作流核对，共 13 个代理：六个部分（Android 构建、服务端、QQ Bot、公开网站、辅助工具、发布与部署）各有一个代理从仓库提取要求，再各有一个代理对照仓库和官方文档逐条挑错，最后一个代理检查遗漏和矛盾。所有代理都被限定为只读、不安装、不修改仓库；事后 `git status --ignored` 确认仓库里除本文件外没有任何新文件。

复核阶段在 238 条结论中更正了 56 条，另有 8 条标为无法验证。

我亲自复查过的结论：机厅停业和主站现状；线上快照是测试数据；uv 管理的 Python 3.13.18 存在；浏览器只有 Safari；四个文件行尾混用；终端版强制 HTTPS；`app.py` 在模块级创建应用并打开数据库；管理版构建读取环境变量；主站仓库的 tag 触发部署；六个共享文件内容一致；Word 自带的等线体字体存在；`node_modules` 的占用膨胀。

**最不牢靠的几条**，使用时请当作待验证：

- Gradle 在 exFAT 上会因文件锁失败：依据是一个未关闭的上游缺陷和旧报告，没有针对 Gradle 9.5 或 macOS 27 的证据，也没有复现。
- `public-site` 的 `pnpm install` 会因 esbuild 中止：从文档的默认行为推出，没有执行。
- SDK 包名 `platforms;android-36.1`：按命名规律推出。
- Koishi 4.18 能在 Node 24 上加载、80 项测试全过：只核对了版本范围声明。
- Android 16 忽略方向锁定会影响本项目：平台规则有官方来源，但真实终端的系统版本和屏幕密度未知。
- Homebrew 上各软件包的名称和版本号：来自检索摘要，没有安装验证。

---

## 4. 操作记录

全部操作都在 2026-11-17 完成。

### 4.1 第一轮：通读项目

1. **看结构**。列出全部文件，统计提交数和各文件行数。
2. **读文档**。完整读了 `README.md`、`docs/roadmap.md`、`docs/development-log.md`、`docs/update.md`、`docs/cloud-queue-sync.md`、全部 0.13.x 发布说明和 0.13.0 的 plan、`public-site/README.md`。
3. **读配置**。完整读了全部 Gradle 构建文件、版本目录、三个 `AndroidManifest.xml`、`MainActivity.kt`、两个 `package.json`、`.env.example`、`requirements.txt`、`compose.yaml`、`.gitignore`。
4. **看源码骨架**。完整读了 `QueueInvariants.kt` 和 `QueueEngine.kt` 的动作定义部分；其余源码用检索列出声明：`queue-core` 全部顶层声明、`MachineQueue` 的成员、`app` 下 12 个非界面文件的声明、各文件的 Composable 数量、服务端的路由、建表语句和常量、Bot 的命令列表。
5. **看历史**。完整提交记录、标签、分支、远程、最近五个提交的改动文件。
6. **静态核对**。统计三套测试的用例数；检索 `TODO` 类标记和残留注解；比对代码中的协议版本与文档；检查 README 里的过期版本号。
7. **查 GitHub**（只读，使用 `gh`）。发布列表及其 Latest、预发布标记；0.13.6、0.13.5、0.13.4、0.13.2 的附件大小与 SHA-256；远程 `main` 的最新提交；仓库基本信息；未关闭的 Issue 和 PR；PR #1 的详情与完整 diff。
8. **查线上**（只读）。对 `queue.abcccc.top` 和 `abcccc.top` 各发了两个公开 GET 请求：`/queue-api-healthz` 和 `/api/queue-versions`。读了 `app.py` 中生成版本信息的函数以确认时间戳的含义。
9. **初步看本机工具**。Java、Python、Flask、Node、pnpm、Android SDK 是否存在，外置盘的文件系统类型。
10. **写记忆**。在 Claude 的本机记忆目录（不在仓库内）写了三条备忘和一个索引，内容见 4.5。
11. **汇报**，并问是否现在修掉过期文档。维护者没有回答。

以下内容**没有逐行读**，只看了结构或标题，接手时不要以为它们被审过：

- `QueueTerminalUi.kt`、`ManagementUi.kt`、`QueueCloudSync.kt` 等大文件的函数体。
- `cloud-server/app.py` 除路由表、建表语句、常量、版本接口和几处被核对引用的行之外的部分。
- 所有测试文件的内容（只计了数）。
- `docs/user-manual.md`、`cloud-server/README.md`、`koishi-bot/README.md`、0.10–0.12 的 plan（只看了标题）。
- `docs/public-beta-deployment.md`（看了标题、开头的边界说明和结尾两节）。
- `public-site/` 的 Vue 组件、`koishi-bot/src/index.ts` 的实现。

后台代理读过其中一部分（见 3.7），但那是它们的阅读，不是我的。

### 4.2 第二轮：Mac 开发环境

12. **详细清点本机**。硬件与系统、磁盘空间、Xcode 命令行工具、Homebrew 已装的相关包、JDK、Android Studio 与 SDK、各版本 Python、Node 工具链、容器运行时、ffmpeg 等杂项。对私有配置只检查了**是否存在**（`~/.gradle/gradle.properties`、`~/.android/debug.keystore`、`~/.ssh` 下的密钥文件），没有读取任何内容。
13. **读工具链配置**。`gradle-daemon-jvm.properties`、Wrapper 配置、`Dockerfile`、两个锁文件的头部、`tsconfig.json`、`vite.config.js`、PDF 脚本的导入和字体查找、宣传视频脚本、git 的 `core.*` 配置；检查仓库里有没有 `._` 伴生文件和 `.DS_Store`（没有）。
14. **实测外置盘**。在仓库的 `tmp/fsprobe/` 下（`tmp/` 已被 git 忽略）创建了一个测试文件，测试符号链接、硬链接、权限位、大小写和扩展属性，然后删除了 `tmp/fsprobe/` 和 `tmp/`。事后 `git status` 干净。
15. **更正记忆**。把记忆里“exFAT 不支持符号链接”改成实测结果。
16. **启动后台核对**。见 3.7。

### 4.3 第三轮：写本文件第一版

17. 核对了几处行号引用，新建了 `docs/handoff/` 目录和本文件。当时后台核对还没有结束。

### 4.4 第四轮：并入核对结果

18. **读核对结果**。结果文件约 60 万字符。完整读了查漏代理的全部输出和 Android 部分的全部输出；其余五个部分读了全部被更正的结论、中高风险项和遗漏项，没有读被确认无误的结论和低风险项。
19. **复查关键结论**。读了主站仓库的排队页、停业通知页、交接记录的部分章节、部署工作流的开头和 `pnpm-workspace.yaml`，以及它的提交记录和工作区状态；对 `queue.abcccc.top` 的 `/api/queue-status` 发了一个公开 GET 请求，只取了摘要字段；查了 uv 管理的解释器、`/Applications` 里的浏览器和相关应用、代理环境变量的**名称**（没有读取值）、系统代理开关、管理员组和 Rosetta；查了行尾混用的文件、终端版的 HTTPS 检查、`app.py` 的模块级初始化、管理版构建的令牌来源；比较了六个共享文件；量了主站 `node_modules` 的实际占用。
20. **重写本文件**为第二版，更新了记忆。

后台代理自己做的事不在上面：它们做了网页检索和官方文档抓取，并报告过若干只读探测（例如 `~/.ssh/config` 里定义了哪些主机、`known_hosts` 里有没有生产主机）。这些探测我没有重做。

### 4.5 留下的痕迹

- **仓库内**：只有本文件，未提交。`tmp/fsprobe/` 创建后已删除。
- **仓库外**：Claude 本机记忆目录下的四条备忘。它们的内容都已包含在本文件里：维护者用简体中文交流；这台 Mac 的工具现状和 exFAT 实测；2026-11-17 的项目外部状态；以及 5.1 节那条教训（下结论前先看能直接回答问题的那个来源）。
- **主站仓库**：只读，没有改动。它的未提交改动是另一个会话留下的，与本次无关。
- **对外**：GitHub 只读 API 调用若干次；对生产域名的 5 个公开 GET 请求；后台代理的网页检索。没有修改 GitHub 上的任何内容，没有登录生产服务器。

### 4.6 没有做的事

- 没有安装任何软件，没有运行任何包管理器或构建。
- 没有运行任何测试。
- 没有提交、推送、打标签。
- 没有修改 GitHub 的 Release、PR 或 Issue。
- 没有修复第 2 节列出的任何问题。
- 没有搬动仓库。

---

## 5. 判断依据与思考摘要

这里记的是每个结论怎么来的，以及我在哪里改过主意。

### 5.1 被推翻的四处说法

第一版和我在会话里的口头汇报中，有四处后来被证明是错的或站不住：

| 我说过的 | 实际情况 | 怎么错的 |
| --- | --- | --- |
| “现场终端仍在实际使用” | 最新快照是测试数据，原机厅已停业 | 只看了版本接口里的上传时间，就推断系统在营业使用。没有去读快照本身，那里有 `test_data` 标记 |
| “Python 只有系统自带的 3.9.6” | uv 管理的 3.13.18 已在本机 | 只查了 `PATH` |
| “宣传视频在 Mac 上需要 PowerShell” | 不需要，而且脚本在 Mac 上跑不通 | 只读了脚本的前 45 行，没有看到后面用了 Windows 专有库 |
| “exFAT 不支持符号链接” | 支持；不支持的是硬链接 | 凭印象 |

四处有同一个毛病：看到一条间接证据就下了结论，没有去看能直接回答问题的那个东西。第一条的后果最重，因为它会让接手的人以为有一个正在营业的现场不能碰。

### 5.2 各项判断

**“项目进度”以什么为准。** 仓库文档自己就有矛盾：路线文档说 0.13.6 待发布，开发记录说已发布并部署。所以我没有只信文档，而是拿三类来源互相对照：文档、代码里的版本号和常量、外部现实（GitHub 和线上接口）。三者里后两类一致指向“已发布并在运行”，于是判定路线文档那一段是没更新。

**“测试通过”能信到什么程度。** 这台机器当时跑不了任何一套测试。我能做的是确认用例数量与开发记录吻合（168 和 80 都对得上），这说明记录不是随手写的，但“通过”本身仍是文档所述。所以文中始终把这两件事分开说。

**三个 APK 大小相同。** 第一反应是上传出了错。先查摘要再下结论：摘要不同，且历次发布都这样，所以排除。这类“看起来像问题”的现象我选择记下结论，避免后人重复排查。

**Latest 标记为什么没动。** 开发记录里发布之后还有一个单独的提交叫“标记 0.13.6 为正式版”，我推断这个 Release 起初不是以正式版创建的，后来改成正式版时 Latest 标记不会自动移动。这是推断，没有证据，处理时只需把 `v0.13.6` 设为 Latest。

**为什么不直接修文档、不改 Latest 标记。** 第一轮的要求只是通读。改文档是对仓库的改动，改 Latest 是对外可见的改动，都应先问。我问了文档那一项，没有得到回答，所以两项都保持原样并记入待决。

**为什么环境问题没有直接动手装。** 需要装的东西里，Android SDK 要接受许可协议，JDK 安装包要输入密码，而发布用的密钥只能由维护者从旧机器取来。所以先回答“需要什么”，安装要等维护者点头。即便是不需要装新软件的那两部分，运行 `pnpm install` 和建虚拟环境也会往机器上写东西，同样等确认。

**为什么用多个代理核对而不是直接回答。** 这个项目用的 AGP 9.3、pnpm 12、Node 24、macOS 27 都比我的知识新或接近边界，凭记忆说“某版本兼容某版本”很可能出错。所以把每个部分拆开，一个代理提取、另一个代理专门挑错并去查官方文档，最后再查漏。维护者当时也开启了要求尽量详尽的模式。

**代理的结论我信多少。** 代理的错误和发现是用同一种笃定的口气报回来的。所以凡是会改变建议的结论，我都自己再看一遍原始文件，3.7 列了复查过的项目。复查中也确实发现代理之间互相矛盾，以及个别数字对不上（例如有代理报告 `node_modules` 是 198 MiB 占 1,552 MiB，我量出来是 370 MB 占 1.5 GB）。没有复查的部分在文中都标了“代理核对”。

**搬到内置盘的建议有多强。** 六个部分的代理对此意见不一：有的说必须搬，有的说不搬也能用。主站仓库的交接记录显示 pnpm 在这块盘上确实装成功过，只是慢。Gradle 的问题没有复现。我最后的判断是“建议搬”，理由不是哪一条一定会出事，而是搬的代价几乎为零，而留下来要记住一串规避办法。

**调试密钥从“必须”降为“有条件”。** 第一版把它列为必须从旧机器带来的东西。核对后发现唯一的依据是 8 月的一句话，之后的发布说明和机厅停业都指向相反方向。但我没有把它直接划掉：如果真有一台存着数据的设备装的是旧调试包，这件事的后果是丢数据，而且必须在第一次调试构建之前处理。所以保留为一条需要维护者回答的问题。

### 5.3 接下来最该注意的

第一版在这里写的是调试密钥。现在看，更靠前的是两件事：

1. **现场已经换了机厅，文档还没跟上。** 维护者确认现在在滨海万达店使用，而仓库文档里所有关于“现场”的描述（部署路径、服务单元、终端签名、真机验收）都是按金街店的情况写的。在动任何线上或现场的东西之前，先向维护者问清现在是哪台设备、装的是哪种签名的包。
2. **长期发布证书的 keystore 在哪。** 它是唯一不可重建的东西。如果旧机器要停用，这是第一个要确认已经备份的文件。

---

## 6. 接手后先看什么

按这个顺序读，大约半小时能建立起完整的图景：

1. [`README.md`](../../README.md)：功能全貌、架构图、构建与签名方式。
2. [`docs/roadmap.md`](../roadmap.md) 的开头两节：未完成工作和必须保留的约束。
3. [`docs/development-log.md`](../development-log.md)：只追加的交付记录，每次发布做了哪些验证、部署到了哪里、留了什么回滚点。
4. [`queue-core/.../QueueInvariants.kt`](../../queue-core/src/main/kotlin/com/abcccc/maimaiqueue/QueueInvariants.kt) 和 `QueueEngine.kt` 的动作定义：队列规则的最小完整描述。
5. [`docs/update.md`](../update.md)：想知道某条规则为什么是现在这样时来查。

读协议时注意：这一段写于 11 月 17 日，当时 [`docs/cloud-queue-sync.md`](../cloud-queue-sync.md) 落后于代码。11 月 18 日它已按当前代码重写（7.15），可以直接读；与代码不一致时仍以 `cloud-server/app.py` 为准。

主站仓库 `site-docs` 的根目录有它自己的交接记录 `HANDOVER.md`，由另一个会话在同一天写成，涉及主站时先读那一份。

---

## 7. 后续工作：搬到内置盘、环境搭建与文档处理（2026-11-17 下午至 11-18）

本节与前文冲突时以本节为准。

### 7.1 维护者的答复

第二版交出后，维护者逐条答复了待决事项。这些答复是本节所有操作的依据：

| 事项 | 答复 |
| --- | --- |
| GitHub 的 Latest 标记 | 改 |
| 协议文档落后 | 跟随更新 |
| 零散的过期内容 | 进行处理 |
| 外置盘 | 先查资料看有无影响，再决定要不要搬，最先完成这个 |
| Android 用 IDE 还是命令行 | 纯命令行，不需要 IDE；维护者不懂 Android 开发 |
| 下载是否走代理 | 不重要，默认不走 |
| 这台 Mac 是否负责发布 | 要 |
| 现场终端是否在用 | 现在就在滨海万达店使用 |
| Python | 需要放到 `PATH` 就放，旧的也处理掉；敏感操作要先问 |

“敏感操作要先问”被当作一条长期约定：需要管理员密码的、删除已有东西的、替维护者接受许可协议的、对外可见的改动，都先问。

### 7.2 外置盘：实测、资料与决定

**实测**（已核实）。同一段脚本分别在外置盘和内置盘上运行：

| 项目 | 外置盘（exFAT） | 内置盘（APFS） |
| --- | --- | --- |
| 整文件锁、字节范围锁、跨进程互斥 | 全部正常 | 全部正常 |
| SQLite WAL 模式，两个连接读写 | 正常，完整性检查通过 | 正常 |
| 内存映射写入、原子改名 | 正常 | 正常 |
| 新建 3000 个 200 字节的文件 | 33.46 秒 | 0.16 秒 |
| 删除这些文件 | 2.69 秒 | 0.08 秒 |
| 时间戳精度 | 10 毫秒 | 更细 |
| `tar` 打包后记录的权限 | 全部 `rwx------` | `rw-r--r--` |

另外两个与资料有关的现象也在本机确认了：空文件各自有不同的 inode 号；文件从空变为非空时 inode 号会改变。普通写入不会产生 `._` 伴生文件，只有写扩展属性才会。

**资料**（代理核对，四个方向各一个检索代理加一个专门挑错的代理）。四个方向的复核结论都是建议搬：

- **Gradle**：历史上的“文件锁超时”缺陷（`gradle/gradle` 第 4329 号）根源是旧版系统驱动让所有空文件共用一个 inode 号，导致 Java 误判锁冲突。系统在 macOS 14 换了驱动，本机实测空文件 inode 各不相同，所以这个缺陷大概率不会出现。真正有 2026 年报告的失败方式是另一种：构建过程中在构建目录里产生 `._` 伴生文件，Android 插件解析资源目录时把它们当成目录而报错。这一条**没有在本机验证**。
- **Node 与 pnpm**：没有阻断性问题。pnpm 在不能硬链接时会退回复制。代价是每次安装都是跨盘全量复制，占用约四倍。
- **Python 与 SQLite**：uv 会退回复制并给一条警告。SQLite 在 macOS 的 exFAT 上有若干未解决的报告，其中一类与上面“inode 号会变”的现象有关；本机的 WAL 测试是通过的。
- **数据安全与发布**：exFAT 没有日志，又是可拔的外置盘，意外断开可能损坏正在写的文件；权限位全是 `0700` 会原样进入手工打的包；据论坛报告 Time Machine 不备份这类盘。签名密钥不应放在上面。

**决定：搬。** 决定性的理由是本机量出来的两条：小文件写入慢约 200 倍，而 Android 和 Node 的构建正是大量小文件；这台 Mac 要负责发布，而从这块盘打出的包权限是错的。

**对第二版的更正**：3.3 节把“Gradle 可能因文件锁直接构建失败”列为第一条后果，这一条在本机没有得到支持，应视为已排除。

### 7.3 搬迁

- 在内置盘全新克隆到 `~/Developer/maimai-queue-terminal`（从 GitHub 克隆，没有复制目录，避免把 `0700` 权限和旧的 git 设置带过来）。
- 核对：`HEAD` 为 `6a04c24`，与 `origin/main` 一致；45 个标签齐全；`core.filemode` 为 `true`。
- 外置盘副本在搬迁时的全部未跟踪和被忽略内容只有 `docs/handoff/` 一项（`git status --ignored` 的输出），没有 stash。该文件已用 `cp -X` 复制过来，并用 `cmp` 确认两边逐字节相同。
- 外置盘上的副本**原样保留**，没有删除任何东西。
- 会话的工作目录已切换到新位置。

**当时没有搬的**：主站仓库 `site-docs` 当时仍只在外置盘上，里面有另一个会话留下的未提交改动和 `HANDOVER.md`。它是另一个项目，没有得到指示，所以没有动。11 月 18 日维护者要求“都搬过来”，处理结果见 7.8。

### 7.4 已处理的事项

- **GitHub Latest 标记**：已把 `v0.13.6` 设为 Latest，并确认 `/releases/latest` 现在指向它。
- **零散过期内容**（都在工作区，未提交）：
  - [`README.md`](../../README.md)：两处 APK 输出文件名由写死的 `0.13.4` 改为 `<版本>` 占位，并注明版本号取自 `app/build.gradle.kts`；证书那句由“0.13.4 的公开 APK”改为“各版本的公开 APK”。最后这一处不在维护者列出的范围里，是同一类过期内容，顺手改了。
  - [`docs/roadmap.md`](../roadmap.md)：第 25 行由“当前待发布”改为已发布，并指向开发记录；第 19 行去掉残留的 `:codex-annotation{index="1"}`。
  - `docs/releases/0.13.3-player-profile-creation.md`：去掉同样的残留标记。
- **没有处理的**：2.1 节第 7、8 项（开发记录条目顺序、路线文档里 0.14.0 重复）维护者没有提，保持原样。[`docs/public-beta-deployment.md`](../public-beta-deployment.md) 里也有几处 `0.13.4`，同样没动。

### 7.5 环境搭建进度

| 部分 | 状态 | 说明 |
| --- | --- | --- |
| Python | 完成（已核实） | 用 `uv python install 3.13 --default` 在 `~/.local/bin` 建了 `python`、`python3`、`python3.13`。新开的终端里 `python3` 是 3.13.18。系统自带的 3.9.6 属于 macOS，无法删除，现在只是被排在后面 |
| 服务端 | 完成（已核实） | 虚拟环境在 `cloud-server/.venv`；Flask 3.1.3、Pillow 12.3.0、gunicorn 23.0.0；**168 项测试全部通过** |
| QQ Bot | 完成（已核实） | `pnpm install --frozen-lockfile` 成功，锁文件未变；**80 项测试全部通过**，Node 24 没有问题 |
| JDK 21 | 完成（已核实） | 用 `brew install openjdk@21`，不需要管理员密码。位置是 `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`。`JAVA_HOME` 已由维护者在 11 月 18 日写进 `~/.zshrc`（7.12 第 1 步） |
| 公开网站 | 11 月 19 日完成，见 7.18 | 当时的情况：`pnpm install --frozen-lockfile` 以 `ERR_PNPM_IGNORED_BUILDS` 中止，原因是 esbuild 的构建脚本未获批准。与第二版的预计一致。需要决定是否在 `public-site/` 加 `pnpm-workspace.yaml`，这是仓库改动 |
| Android SDK | 完成（已核实） | 维护者本人在 11 月 18 日按 7.12 的步骤安装并同意了许可协议。位置 `/opt/homebrew/share/android-commandlinetools`，共 534 MB |
| Gradle 构建与测试 | 完成（已核实） | 四套单元测试全部通过，调试包构建成功。结果和一处与时区有关的发现见 7.14 |

第二版 3.4 节建议用 `temurin@21`，那是需要输入密码的安装包。实际改用了不需要密码的 `openjdk@21`。代价是 `/usr/libexec/java_home` 找不到它（那需要一条 `sudo` 命令建符号链接），所以要靠 `JAVA_HOME` 环境变量。

### 7.6 新发现：测试同步标记的实际后果

线上快照的 `test_data` 为 `true`，表示终端是以“测试”同步模式上传的。读服务端代码后确认，这不只是个展示标签（已核实，`cloud-server/app.py`）：

- 终端申请网页账户绑定二维码会被拒绝，错误码 `TEST_DATA_ACCOUNT_BINDING_UNAVAILABLE`。
- 终端申请二维码建档会话会被拒绝，错误码 `TEST_DATA_PROFILE_CREATION_UNAVAILABLE`；已有的建档会话在提交时也会被拒绝。
- 玩家资料存放在单独的作用域 `<作用域>:test:<终端编号>` 里，队列存储编号也带 `test:` 前缀，与正式数据分开。

维护者说滨海万达店是正式在用，那么这个状态是否有意为之需要确认。我已经告知，还没有得到答复，也没有改动任何东西。

### 7.7 协议文档的更新：做了一半

> 11 月 18 日补记：这项工作已经完成，结果见 7.15。本节保留当时的记录。

维护者要求 [`docs/cloud-queue-sync.md`](../cloud-queue-sync.md) 跟随当前协议更新。这份文档本身**还没有改动**。

已完成的准备工作：一个后台工作流把协议分成七个方面，每个方面先由一个代理从代码提取事实，再由另一个代理对照代码逐条复核，最后由第三个代理按原文档的写法起草章节。提取和复核七个方面全部完成；起草只完成了三个，另外四个因为代理的用量额度用完而失败。

| 方面 | 提取与复核 | 草稿 |
| --- | --- | --- |
| 快照上传、协议版本、请求头、机厅与终端身份、同步模式 | 完成 | 有 |
| 公开读取接口（队列、日志、版本、健康检查、头像） | 完成 | 有 |
| 网页玩家账户、头像上传、二维码建档 | 完成 | 有 |
| 管理后台接口 | 完成 | **无** |
| Bot 接口 | 完成 | **无** |
| 终端回流通道（命令、回执、资料拉取、移动设备登记） | 完成 | **无** |
| 原文档逐行审计 | 完成 | **无** |

全部结果保存在仓库的 `tmp/claude-work/protocol-doc-research.json`（`tmp/` 已被 git 忽略）。同目录下还有 `exfat-research.json` 和 `mac-environment-audit.json`，分别是 7.2 节和第 3 节所依据的代理输出。

接下来要做的是：用已复核的事实补写缺的四部分，把七部分合并进原文档，然后对照代码再审一遍成稿。当时的报错说代理额度要到 2026-11-19 16:00（美国太平洋时间）才恢复。11 月 18 日补记：当天另一组复核代理已经正常跑完，额度实际上已经可用，缺的四份草稿可以用代理补写。

### 7.8 迁移核对（2026-11-18）

维护者要求确认数据是否都迁过来了。核对时外置盘没有挂载，所以无法再做一次现场比对，下面的结论一部分来自搬迁当时的检查：

| 数据 | 是否已在内置盘 | 依据 |
| --- | --- | --- |
| 全部提交、分支、标签 | 是 | 新克隆有 81 个提交、45 个标签，`HEAD` 与远程一致。搬迁时外置盘副本的工作区是干净的，没有只存在于本地的提交或 stash |
| 仓库里未跟踪的文件 | 是 | 搬迁时只有 `docs/handoff/` 一项，已复制并逐字节比对 |
| 被 git 忽略的本地文件 | 当时没有 | 搬迁时外置盘副本没有任何被忽略的文件 |
| 搬迁后在新位置产生的东西 | 本来就在内置盘 | 文档修改、虚拟环境、`node_modules`、测试生成的数据库 |
| Claude 的会话记忆 | 核对时**发现没有**，已补上 | 记忆按路径存放，新路径下的记忆目录是空的。已把内容按现状重写到新目录 |
| 后台代理的研究结果 | 核对时在临时目录，已另存 | 见 7.7 |
| 主站仓库 `site-docs` | 是，已推送的部分 | 11 月 18 日从 GitHub 克隆到 `~/Developer/site-docs`，90 个提交、45 个标签，最新提交 `795f9c6`（11 月 17 日）。我前一天看到的那些未提交改动和 `HANDOVER.md`，另一个会话已在当天提交并推送，都在这份克隆里 |
| 另一个仓库 `study-notes` | 是，已推送的部分 | 外置盘上还有这个项目（从 Claude 的会话记录目录名得知）。同日从 GitHub 克隆到 `~/Developer/study-notes`，18 个提交，最新提交 `04331dd`（10 月 26 日） |

同一天还用 GitHub 重新核对了本仓库：内置盘上的全部分支和标签与 GitHub 逐一相同（共 46 个引用），`git fsck` 通过，188 个受跟踪文件无一缺失。

> 11 月 19 日补记：外置盘已接上并完成比对，下面三个问题的答案见 7.17。

**仍然无法确认、需要接上外置盘才能回答的**：

- `site-docs` 和 `study-notes` 在外置盘上的副本，是否有 11 月 17 日之后没推送的提交、没提交的改动，或被 git 忽略的本地文件。`study-notes` 在外置盘上有过一次 Claude 会话，它是否留下了未推送的东西我不知道。
- 本仓库的外置盘副本在搬迁之后有没有再被写入。我没有写过，但没法现场证明。
- 外置盘上除这三个文件夹之外还有什么。我从来没有列过那块盘的其他内容。

### 7.9 接下来

按顺序：

1. ~~公开网站的 esbuild 批准方式~~：维护者同意加配置文件，11 月 19 日已完成，见 7.18。
2. ~~Android SDK 与 Gradle 验证~~：11 月 18 日已完成，见 7.14。
3. ~~完成协议文档的更新~~：11 月 18 日已完成，见 7.15。
   - 7.16 记录的服务端缺陷：维护者已同意修复，**尚未动手**，进度和接手方法见 7.19。
4. 确认 `test_data` 状态是否有意（7.6）。
5. 发布所需的东西只有维护者能提供：长期发布证书的 keystore、别名和密码；生产服务器的访问方式。
6. ~~外置盘接上之后做最后一次比对~~：11 月 19 日已完成，见 7.17。
7. 本文件、7.4 节的文档修改和重写后的协议文档都还没有提交。

### 7.10 本节新增的操作记录

接第 4 节的编号：

21. **外置盘实测**。写了一个探测脚本（放在会话的临时目录，不在仓库里），在外置盘的 `tmp/fsprobe2/` 和内置盘的临时目录各跑一遍；另外各打了一个小 `tar` 包看权限。之后又在 `tmp/fsprobe3/` 测了 inode 行为。测试目录都已删除，事后 `git status --ignored` 干净。
22. **资料检索**。后台工作流，8 个代理，只做网页检索，没有在本机运行或安装任何东西。
23. **读服务端代码**，确认 `test_data` 标记影响哪些接口。
24. **查 Homebrew**（只读）：`openjdk@21`、`android-commandlinetools`、`android-platform-tools` 的信息；查了 `PATH` 顺序和 shell 配置文件里与 `PATH` 有关的行（只看匹配行，没有通读配置文件）。
25. **搬迁**。见 7.3。
26. **改 GitHub**：`gh release edit v0.13.6 --latest`。这是本次会话唯一一处对外可见的改动。
27. **改文档**。见 7.4。
28. **装环境**。`brew install openjdk@21`；`uv python install 3.13 --default`；建服务端虚拟环境并装依赖；在 `koishi-bot/` 和 `public-site/` 运行 `pnpm install --frozen-lockfile`；运行服务端和 Bot 的测试。
29. **协议文档的准备**。后台工作流，21 个代理，只读。见 7.7。
30. **迁移核对与补救**。见 7.8。在仓库里新建了被忽略的 `tmp/claude-work/` 存放代理输出。
31. **再次核对并克隆另外两个仓库**（11 月 18 日）。用 `git ls-remote` 把本仓库的引用与 GitHub 逐一比对，跑了 `git fsck`；用 `gh` 查了维护者名下的仓库列表和 `site-docs` 的最新提交；看了 Claude 会话记录目录里有哪些外置盘路径（只看目录名，没有读任何会话内容）；把 `site-docs` 和 `study-notes` 克隆到 `~/Developer/`。

本节期间**没有做**的事：没有提交、推送或打标签；没有用 `sudo`；没有接受任何许可协议；没有改 shell 配置文件；没有改动外置盘上的任何东西（两次临时测试目录除外，已删）；没有登录生产服务器；没有改 `docs/cloud-queue-sync.md`。

### 7.11 这一段里的判断

**为什么不等资料查完就先做实测。** 维护者问的是“有无影响”，这是本机的事实问题，网上的报告大多写于系统换驱动之前，未必适用。实测几分钟就有答案，而且比任何二手资料都直接。结果也确实推翻了我自己此前转述的一条结论（文件锁）。

**为什么实测已经够了还等资料。** 维护者明确要求先查资料再决定。实测出来后结论其实已定，但资料检索补上了一条实测覆盖不到的风险（构建中途产生的伴生文件），也说明了文件锁为什么现在没事，这两点让结论更站得住。

**为什么重新克隆而不是复制目录。** 复制会把外置盘上全是 `0700` 的权限和 `core.filemode=false` 的设置一起带过来，之后打包权限的问题并不会消失。克隆得到的是干净状态，代价只是要单独带走未跟踪的文件，而当时那样的文件只有一个。

**为什么 JDK 没按第二版的建议装。** 第二版推荐的安装包要输入管理员密码，属于维护者说的“要先问”的那一类。Homebrew 里有不需要密码的同版本 JDK，所以改用它，把需要 `sudo` 的那一步留给维护者自己决定做不做。

**关于“是否在用”的两次误判。** 第一次从上传时间推断“在用”，第二次从测试标记和旧机厅的停业通知推断“没在用”，两次都错在同一处：这是一个只有维护者知道答案的现实问题，却想靠文件和接口推出来。标记是事实，应当原样报告；它说明不了机厅有没有在营业。

### 7.12 Android SDK 安装步骤（由维护者本人执行）

11 月 18 日维护者问“你装和我自己装有区别吗”，随后决定自己装，由我讲解。结果上没有区别，区别只在许可协议由谁点同意。下面是交给维护者的步骤。写作时这些步骤**还没有被执行过**；命令本身核对过来源，第 1 步在 `~/.zshrc` 的副本上试运行过。

**核对依据**（已核实）：Homebrew 的 `android-commandlinetools`（版本 15859902，即命令行工具 22.0）会把工具装到 `/opt/homebrew/share/android-commandlinetools/cmdline-tools/latest`，并把 `sdkmanager` 等链接进 `/opt/homebrew/bin`。Google 官方 SDK 清单里，稳定通道有 `platforms;android-36.1`、`build-tools;36.0.0` 和 `platform-tools`（37.0.1），三者用的都是同一份 `android-sdk-license`，所以只会问一次是否接受。

**第 1 步：让终端找到 Java 和 SDK**

```bash
cp ~/.zshrc ~/.zshrc.before-android
```

```bash
printf '\n# Android 开发环境\nexport JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"\nexport ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"\nexport PATH="$JAVA_HOME/bin:$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/36.0.0"\n' >> ~/.zshrc
```

只运行一次。然后关掉终端窗口重新开一个，运行 `java -version`，应显示 `openjdk version "21.0.13…"`。

Java 的目录放在 `PATH` 最前面，是为了排在系统那个空壳 `/usr/bin/java` 之前。两个 Android 目录放在最后面，是因为 `platform-tools` 里自带一个 `sqlite3`，放前面会悄悄顶替系统的。`build-tools/36.0.0` 是发布时要用的 `zipalign`、`apksigner`、`aapt2` 所在的目录，路径里带版本号，将来换版本要跟着改。

**第 2 步：安装命令行工具**（下载约 156 MB，不需要密码）

```bash
brew install --cask android-commandlinetools
```

```bash
sdkmanager --version
```

应先打印三行以 `WARNING` 开头的弃用提示，再打印 `22.0`。弃用提示是这个版本固有的，可以忽略。安装结束时 Homebrew 会建议 `brew install --cask temurin`，不要照做，Java 已经装好了。如果版本号不是 `22.0`，先停下：后面的步骤只按 22.0 核对过。

**第 3 步：安装三个 SDK 包**（下载约 160 MB）

```bash
sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-36.1" "build-tools;36.0.0" "platform-tools"
```

屏幕会滚过很长的许可协议，最后一行是 `Accept? (y/N):`。输入 `y` 再回车才算同意；直接回车等于拒绝，这时会显示 `Skipping following packages as the license is not accepted`，重新运行即可。装完没有成功提示。

**第 4 步：确认**

```bash
sdkmanager --sdk_root="$ANDROID_HOME" --list_installed
```

`Installed packages:` 下面的表里应有 `build-tools;36.0.0`、`platform-tools`、`platforms;android-36.1`，多出一行 `cmdline-tools` 属正常。

**第 5 步：用项目验证**

```bash
cd ~/Developer/maimai-queue-terminal && sh ./gradlew :queue-core:test
```

```bash
cd ~/Developer/maimai-queue-terminal && sh ./gradlew :app:testLocalDebugUnitTest
```

第一条只验证 Java 和 Gradle 本身，首次运行要下载 140 MB 的 Gradle 和大量依赖，会停顿很久。第二条才真正用到 SDK。两条都应以 `BUILD SUCCESSFUL` 结束。下载超时就原样重跑。

之后还有三条：`:app:testManagementDebugUnitTest`、`:app:testTerminalDebugUnitTest -PENABLE_TERMINAL_BUILD=true`、`:app:assembleLocalDebug`。凡是任务名里带 `Terminal` 的都要加 `-PENABLE_TERMINAL_BUILD=true`。

**说明**：

- 这三个包对应当前 `main` 分支（compileSdk 36.1、AGP 9.3.0）。如果合并 PR #1，要再装对应版本的包，已装的不必删。
- Google 已宣布 `sdkmanager` 将被 `android sdk` 取代。清单里的 23.0 版已经把 `sdkmanager` 换成了转发脚本，行为不同。以后不要随手 `brew upgrade` 这个包。
- 撤销第 1 步：`cp ~/.zshrc.before-android ~/.zshrc`。撤销第 2 步：`brew uninstall --cask android-commandlinetools`，不要加 `--zap`，那会把整个 SDK 目录移进废纸篓。
- 任何一步出现 `Unable to locate a Java Runtime`，说明那个终端窗口是第 1 步之前开的，重新开一个即可。

### 7.13 写这份步骤时的操作与一处意外

32. **核对命令**（只读）。读了 Homebrew 里这个包的定义；从 Google 下载了官方 SDK 清单文件到会话临时目录，从中确认包名、许可协议和通道。
33. **试运行第 1 步**。把 `~/.zshrc` 复制到会话临时目录，在副本上运行追加命令，再在干净的 shell 里加载那几行，确认 `java` 指向 JDK 21、`sqlite3` 仍是系统的。真正的 `~/.zshrc` 没有改动。
34. **让三个代理挑错**。分别从来源核对、新手逐步操作、与仓库构建配置是否相符三个角度审这份步骤。它们没有发现会卡死的问题，提出的改进（显式传 SDK 位置、`PATH` 顺序、不要重新 `source`、弃用警告、Homebrew 的误导建议、更能说明问题的验证命令等）都已并入上面的步骤。
35. **意外及其处理**。其中一个代理为了读包定义运行了 `brew cat`，Homebrew 把它算作开发者命令，自动打开了“开发者模式”（此前是关的）。这个模式下 `brew update` 会跟随主分支的最新提交而不是稳定版。代理想关回去但被权限系统拦下，并如实报告了。我随后运行 `brew developer off` 关掉，并确认状态已恢复为“跟随最新稳定版”。除此之外，这次审查没有改动机器或仓库上的任何东西。

### 7.14 Android 环境验证结果（2026-11-18）

维护者按 7.12 做完了第 1 至 4 步，并亲自运行了第 5 步的两条命令；其余三项由我运行。

| 项目 | 结果 | 谁运行的 |
| --- | --- | --- |
| SDK 安装 | 三个包齐全，许可记录为 `android-sdk-license`，`android.jar`、`zipalign`、`aapt2`、`apksigner`、`adb` 都在 | 维护者安装，我核对 |
| `:queue-core:test` | 90 项通过，0 失败 | 维护者 |
| `:app:testLocalDebugUnitTest` | 426 项通过，0 失败 | 维护者 |
| `:app:testManagementDebugUnitTest` | 426 项通过，0 失败 | 我 |
| `:app:testTerminalDebugUnitTest -PENABLE_TERMINAL_BUILD=true` | 426 项通过，0 失败 | 我 |
| `:app:assembleLocalDebug` | 成功，生成 `app-local-debug.apk`，约 36.5 MB | 我 |

Gradle 用的是 Homebrew 装的那个 JDK 21，没有另行下载 Java。构建没有在仓库里留下需要提交的文件。至此服务端 168 项、Bot 80 项、Kotlin 四套测试都已在这台 Mac 上实际跑通，第 2.5 节“测试没有在本机运行”不再成立。

第 2.4 节按 `@Test` 静态数出的数量是 `app` 420、`queue-core` 90；实际执行的数量是 `app` 426、`queue-core` 90。

**发现：三个测试依赖本机时区。** 我第一次运行管理版和终端版的测试时各失败了 3 项，都在 `BusinessHoursTest`：

- `closingBoundaryIsOutsideAndHasStableOccurrenceId`
- `overnightScheduleRemainsOpenAfterMidnight`
- `weeklyScheduleUsesTheOpeningDayForOvernightIntervals`

原因（已核实）：这个测试类用 `Asia/Shanghai` 构造时间戳，其他测试调用 `evaluateBusinessHours` 时都显式传了这个时区，唯独这三个没传，于是用的是运行环境的默认时区。这台 Mac 的系统时区是 `Asia/Shanghai`，所以维护者在自己的终端里运行全部通过；而我这个会话的命令行被设成了 `TZ=America/Los_Angeles`，在那里就失败。去掉这个变量重跑，两套各 426 项全部通过。

所以这不是功能问题，也不是搬迁造成的，而是三个测试写得不够严：换一台不在中国时区的机器（比如外部贡献者的电脑，或者以后的自动构建）就会失败。修法是给这三个测试里的五处调用补上时区参数，只改测试文件。

**维护者的决定（2026-11-18）：不改。** 理由是这个项目不会由境外人员使用。此事到此为止，以后不必再提；只需记住在非中国时区的环境里运行测试会看到这三项失败。

**对后来的代理**：在这台 Mac 上通过工具运行测试前先 `unset TZ`，否则会重现这三个失败。同理，会话里 `date` 打印的时间是洛杉矶时间，不是维护者的本地时间。

本节新增的操作：

36. **核对安装结果**（只读）：看了 SDK 目录、许可记录、几个工具是否存在，从测试结果文件里数了通过数。
37. **运行三项 Gradle 任务**：管理版测试、终端版测试、本地调试包构建。测试先在洛杉矶时区下各失败 3 项，查明原因后在系统时区下重跑通过。
38. **读代码定位原因**：`BusinessHoursTest.kt` 和 `BusinessHours.kt` 中与时区有关的行。

### 7.15 协议文档重写完成（2026-11-18）

[`docs/cloud-queue-sync.md`](../cloud-queue-sync.md) 已整份替换，从 275 行（只写到 schema 7）变为 3,223 行，共 15 个大节、82 个小节。原文件没有本地改动，旧版本在 git 历史里，随时可以取回。**没有提交。**

新文档的大节顺序：原则、协议版本、终端请求头、上传队列快照、机厅与终端身份、权威终端与同步方式、玩家资料字段、当前登记绑定、公开接口、网页账户接口、私有 Bot 接口、管理后台接口、终端回流接口、远程操作边界、构建与安全。

**做法**，分四步，每一步的事实都来自代码而不是旧文档：

1. 提取与复核（11 月 17 日，见 7.7）：七个方面，各由一个代理从代码提取事实，再由另一个代理逐条复核。
2. 起草：前三个方面 11 月 17 日起草完成；缺的四个方面（管理后台、Bot、终端回流、原文档审计）11 月 18 日由另一组代理补写。
3. 合并：用脚本按固定顺序把七份草稿拼成一份初稿（3,221 行）。拼接是机械的，不改写内容。
4. 复审：把初稿按行切成 11 段，每段由一个代理对照代码逐句检查并给出可以直接替换的修改；每条修改再由另一个代理独立复核，判定接受、修订或驳回。另有一个代理通读全文，查交叉引用、前后矛盾和术语，其修改同样经过复核。

**复审结果**：

| 项目 | 数量 |
| --- | --- |
| 分段复审提出的修改 | 57 |
| 通读全文提出的修改 | 19 |
| 复核结论 | 接受 62，修订后接受 10，驳回 0；另有 4 条没有复核结论（见下） |
| 两组代理对同一处提出的重复修改，只保留一份 | 7 |
| 我自己补的修改 | 2 |
| 最终套用 | 71 |

提出的 76 条按性质分：补充遗漏 38，表述容易误解 16，与代码不符 11，前后矛盾 6，交叉引用失效或有歧义 5。

- **第 3 段（玩家资料字段、当前登记绑定）的复核代理没有跑完**，原因是电脑中途休眠。这一段的 4 条修改由我本人对照 `cloud-server/app.py` 和 `QueueCloudSync.kt` 逐条核对后套用，四条都成立。
- **我自己补的 2 处**：`SERVER_MIGRATION` 的说明在公开接口一节被复审改准确了（测试数据与正式数据之间切换、终端接管或更换运行实例后队列批次或机台稳定身份变化），Bot 和管理后台两节里同一句话的简写没有被改到，我照样改齐。依据是 `app.py` 中快照上传处理里对应的三段拒绝逻辑，我直接读过。
- 套用之后检查了结构：标题层级连续，代码块成对，54 处“见‘某节’”的引用全部能对上标题，三个重名标题（通用约定、修改玩家资料、查询命令）的引用都带了所属大节。

**没有验证、引用时要留意的**（各段复审代理自己列出的）：

- 所有结论来自读代码，复审代理没有运行任何程序。请求体超过 1 MiB 返回 `413` 这类依赖框架行为的说法没有实测，仓库里也没有对应测试。
- “某功能自某版本加入”一类的说法来自发布说明和提交历史，无法从当前代码证明。
- 终端界面一侧的说法（哪些控件在什么状态下不可用、每次状态变化都会触发上传等）只查到了主要路径，没有逐个界面入口追踪。
- 管理后台应用的行为按源码（0.13.2）核对，没有对照已发布的安装包。
- 属于部署和运维建议的句子（哪些安装包不得公开、令牌泄露后如何处理、服务器是否启用 HTTPS）没有代码可以对照，沿用原文档的写法。

**相关文件**（都在仓库的 `tmp/claude-work/` 下，该目录被 git 忽略）：

| 文件 | 内容 |
| --- | --- |
| `protocol-doc-research.json` | 七个方面的提取、复核结果和前三份草稿 |
| `protocol-doc-drafts-2.json` | 后四份草稿 |
| `cloud-queue-sync.draft.md` | 合并后的初稿，未套用复审修改 |
| `protocol-doc-review.json` | 复审和复核的全部输出，含每条修改的理由和代码依据 |
| `protocol-doc-review-apply.log` | 每条修改是套用、被重复项取代还是跳过 |
| `cloud-queue-sync.reviewed.md` | 套用修改后的成稿，与正式文档内容相同 |

本节新增的操作：

39. **补写四份草稿**：后台工作流，只读代码，不改仓库。
40. **合并初稿**：运行自己写的拼接脚本，输出到 `tmp/claude-work/`。
41. **复审**：后台工作流，24 个代理（11 个分段复审、11 个分段复核、通读复审和复核各 1 个），23 个完成，1 个因电脑休眠失败。全部只读。
42. **核对第 3 段的 4 条修改**：读了 `app.py` 中移动登记资料序列化、联系信息上限、`last_used_at` 读取、Bot 资料接口路由四处，以及 `QueueCloudSync.kt` 的对应行。
43. **抽查**：读了 `app.py` 中关闭同步、关闭 Bot 联动、数据范围切换、接管四段对待执行命令的处理，与成稿对照一致。
44. **套用修改**：运行自己写的脚本，按“原文恰好出现一次才替换”的规则套用；随后用脚本检查结构。
45. **替换正式文档**：先确认 `docs/cloud-queue-sync.md` 在 git 里没有本地改动，再用成稿覆盖。

### 7.16 复审中发现的三处服务端缺陷（已实测，未修复）

复审代理在读代码时标出三处“可能直接报错”的地方，但都没有运行验证。我用仓库自带的测试夹具写了临时测试（放在会话的临时目录，**没有往仓库里加任何文件**）逐一运行，三处都得到确认。测试环境里表现为服务端抛出未捕获的异常，线上经 gunicorn 会返回 `500`。

| 位置 | 触发条件 | 实测结果 | 影响 |
| --- | --- | --- | --- |
| 快照上传，`app.py` 第 4374 至 4383 行附近 | 快照的 `private_player_contacts` 里有一条联系信息指向服务端已记下删除标记的玩家资料 | `KeyError`（键是那份登记的编号） | 只要这条联系信息还在快照里，这台终端的每次上传都会失败 |
| `POST /api/queue-terminal/installation`，第 5307 至 5311 行 | `venue_id` 是数字等非字符串值 | `AttributeError`；字符串但不是 UUID 时正常返回 `400` | 只影响发来错误类型的调用方 |
| `POST /api/queue-terminal/player-bindings`，第 2196 至 2199 行 | `profile_id` 是数字等非字符串值 | `AttributeError`；字符串但不是 UUID 时正常返回 `400` | 同上 |

第一处值得说明：

- **原因（已核实）**：代码先把“资料已被删除”的联系信息从 `incoming_contacts` 里过滤掉，随后的两个循环却仍然按每一条上传的联系信息去 `incoming_contacts` 里取值，被过滤掉的那条就取不到。
- **能不能在现场遇到（推断，没有实测）**：单台终端的正常流程里遇不到。删除资料是终端自己执行的，执行前要求资料不再挂在登记上，执行后终端本机就没有这份资料了，不会再为它上传联系信息。会遇到的情形是另一台仍留有这份资料的终端来上传，并且这份资料正挂在某份登记或近期日志上，例如用旧数据的终端接管。这正是 0.14.0 多终端要引入的场景。
- **复现方式**：上传一份带一条联系信息的快照（成功），直接往 `deleted_player_profile` 表插入该资料的删除标记，再上传同一份快照。删除标记是手工插入的，不是通过管理后台的删除流程产生的。
- 协议文档没有把这三处写成协议行为：文档只写了“资料 UUID 已删除时服务端跳过这份资料”，这一点本身是对的。

后两处是输入校验不严，调用方是持有令牌的终端，正常的终端不会发出这样的请求。

三处都**没有修**。修法都只涉及服务端几行代码，要不要修由维护者决定。

本节新增的操作：

46. **运行两个临时测试**：用 `cloud-server/.venv` 的 Python 和 `test_app.py` 里的测试夹具，数据库是测试自建的临时文件，不接触任何真实数据，也不访问线上服务器。

### 7.17 外置盘最后一次比对（2026-11-19 凌晨，北京时间）

维护者接上了外置盘。比对全程只读：git 命令都带 `GIT_OPTIONAL_LOCKS=0`（避免刷新索引时写盘）和 `core.fileMode=false`（exFAT 上文件权限位不可信）。除下面说明的复制外，没有改动、删除外置盘上的任何东西。

**盘上有什么（已核实）**：卷名 `SEAGATE`，exFAT，1 TB，已用约 2.0 GB。顶层只有一个 `Developer/` 文件夹，里面就是三个仓库（`maimai-queue-terminal` 51 MB、`site-docs` 1.6 GB、`study-notes` 259 MB），没有别的项目或文件。其余是系统自己放的：`.Spotlight-V100`、`.fseventsd`、`.Trashes`，以及一个 `._Developer`（macOS 在 exFAT 上保存文件夹属性用的附属文件，不是内容）。

**`.Trashes` 没能查看**：系统拒绝读取（`Operation not permitted`），里面有没有东西我不知道。

**逐个仓库的结果**：

| 仓库 | 提交 | 分支、贮藏 | 未提交的改动 | 没纳入 git 的文件 |
| --- | --- | --- | --- | --- |
| `maimai-queue-terminal` | 盘上 81 个提交全部在内置盘，`HEAD` 同为 `6a04c24` | 只有 `main`，无贮藏 | 无 | 只有 `docs/handoff/`，是本文件的第二版（47 KB），内置盘上是更新的版本 |
| `site-docs` | 盘上 90 个提交全部在内置盘，`HEAD` 同为 `795f9c6`；内置盘多一个标签 `deploy-44144565485-1` | 只有 `main`，无贮藏 | 无 | 没有未跟踪文件；被忽略的只有 `node_modules/` 和 `docs/.vitepress/dist/`，都是可以重新生成的 |
| `study-notes` | 盘上 19 个提交全部在内置盘，`HEAD` 同为 `3664524` | 多一个本地分支 `format-physics-source`，与 `master` 指向同一个提交，没有独有内容；无贮藏 | 无 | **两个文件只在外置盘上**，见下 |

“全部在内置盘”的判断方式：取盘上仓库所有引用、引用日志和贮藏能到达的每一个提交，逐个在内置盘的仓库里查是否存在。

**只在外置盘上的两个文件，已复制过来**：`study-notes` 的 `普通高中/高中物理学/` 下有 `仅供格式参考.tex`（504,227 字节，11 月 3 日）和 `仅供格式参考1.tex`（2,964 字节，11 月 17 日）。它们没有纳入 git，所以克隆带不过来，但仓库里的 `格式整理记录.md` 提到了它们。维护者此前说过“都搬过来就好”，我把这两个文件复制到 `~/Developer/study-notes` 的相同位置（目标位置原先没有同名文件，没有覆盖任何东西），复制后两边的 SHA-256 一致，并把权限从 exFAT 带来的 700 改成 644。它们在内置盘上同样处于未跟踪状态，要不要纳入 git 由维护者决定。同目录下的 `._仅供格式参考.tex` 是附属的属性文件，没有复制。

**一处与此前记录不同的地方**：7.8 记录内置盘的 `study-notes` 是 18 个提交、最新 `04331dd`。现在它是 19 个提交、最新 `3664524`。引用日志显示它在北京时间 11 月 18 日 23:18 做过一次快进合并（`merge origin/master: Fast-forward`）。这不是本会话做的，应当是维护者或另一个会话拉取了更新。

**结论**：除 `.Trashes` 无法查看外，外置盘上已经没有任何内置盘上没有的内容。外置盘上的东西我没有删，是否清空或挪作他用由维护者决定。

本节新增的操作：

47. **比对三个仓库**（只读）：比较提交、引用、贮藏、工作区状态和被忽略的文件。
48. **列出外置盘内容**（只读）：顶层和 `Developer/` 两层，以及三个文件夹的大小。
49. **复制两个文件**到 `~/Developer/study-notes`，核对校验和，调整权限。这是本会话唯一一次写入这台 Mac 上的其他仓库。

### 7.18 公开网站的依赖安装（2026-11-19）

维护者同意在 `public-site/` 加配置文件。新增 [`public-site/pnpm-workspace.yaml`](../../public-site/pnpm-workspace.yaml)，内容与主站仓库 `site-docs` 里的同名文件一致，共五行，作用是允许 esbuild 运行它的安装脚本：

```yaml
packages:
  - .

allowBuilds:
  esbuild: true
```

验证（已核实，都在 `public-site/` 下运行）：

| 命令 | 结果 |
| --- | --- |
| `pnpm install --frozen-lockfile` | 通过，锁文件没有变化 |
| `pnpm build` | 成功，1,782 个模块，输出 `dist/`（被 git 忽略） |
| `node scripts/sync-site-main.mjs --check --site-main ~/Developer/site-docs` | 输出“公开队列页与 site-main 的共享组件一致” |

第三条要注意：`pnpm check:site-main` 不带路径会直接报错退出，必须用 `--site-main` 指出主站仓库的位置。`--check` 模式只比较不写入（读过脚本确认）。

至此第 3 节列出的开发环境全部就绪：服务端、Bot、Android 四套测试和公开网站的构建都已在这台 Mac 上实际跑通。这个新文件还没有提交。

本节新增的操作：

50. **新增一个文件** `public-site/pnpm-workspace.yaml`。
51. **运行安装、构建和一致性检查**三条命令。

### 7.19 服务端修复：已获同意，尚未动手（2026-11-19 中止于此）

维护者同意修复 7.16 的三处缺陷。随后因用量额度不足要求尽快收尾，所以这项工作停在准备阶段。**`cloud-server/app.py` 和 `cloud-server/test_app.py` 没有任何改动。**

**已知要修的四处**（前三处见 7.16，都已实测）：

| # | 位置（`app.py`） | 问题 | 状态 |
| --- | --- | --- | --- |
| 1 | 约第 4374 至 4383 行，快照上传 | 联系信息指向已删除的资料时 `KeyError` | 已实测 |
| 2 | 约第 5309 行，终端安装信息接口 | `venue_id` 非字符串时 `AttributeError` | 已实测 |
| 3 | 约第 2200 行，创建绑定会话 | `profile_id` 非字符串时 `AttributeError` | 已实测 |
| 4 | 约第 4009 行，快照上传 | 请求体的 `venue.id` 非字符串时，同样只捕获了 `TypeError` 和 `ValueError` | 读代码发现，**我没有实测** |

**我打算的改法**（未实施，供接手者参考）：

- 第 2、3、4 处：加一个小的辅助函数，先判断是不是字符串再交给 `UUID()`，不是 UUID 字符串就返回 `None`；三处改用它，返回的状态码和错误文字保持现状（分别是 `400 机厅 ID 无效`、`400 玩家资料编号无效`、`409 VENUE_ID_REQUIRED`）。文件里已有的 `read_uuid` 就是先判断类型的，写法可以参照。
- 第 1 处：两个循环改为遍历已经过滤过的 `incoming_contacts`，被过滤掉的联系信息自然跳过。
- 第 1 处还有一个要一并处理的后果：公开快照里登记的 `qq_number` 是在规范化阶段根据联系信息附上的，早于查询删除标记。只改循环的话，上传会成功，但已删除资料的 QQ（如果它设置为公开）会出现在公开队列页上。我的打算是把查询删除标记提前到序列化之前（`profile_scope_id` 在约第 4169 行已经算出，序列化在约第 4184 行），把这类登记的 `qq_number` 清空。这一点是我的判断，没有与维护者确认。
- 回归测试：四处各补一个，加在 `test_app.py`。第 1 处可以仿照现有的 `test_management_profile_delete_waits_for_terminal_and_cleans_private_rows`，走真实的删除流程产生删除标记，再上传一份仍带这份资料和联系信息的快照。
- 改完后跑 `cloud-server` 的完整测试（11 月 18 日是 168 项全过）。

**同类问题排查：只完成了一小部分。** 动手前我启动了一个后台工作流，把 `app.py` 分成 11 段，每段由一个代理查“请求能让服务端抛出未捕获异常”的地方，要求每个发现都实际运行复现，再由另一个代理独立复核。收尾时我把它停了，当时 11 段里只有 2 段查完，复核一段都没做。

这 2 段（第 5100 至 6222 行、第 8469 至 9591 行）共报告 12 处，都由报告它的代理自己运行复现过，但**没有经过独立复核，我也没有逐条核对**，引用时请当作“待确认”：

| 类型 | 报告了几处 | 例子 | 谁能触发 |
| --- | --- | --- | --- |
| 数值超出 SQLite 整数范围，`OverflowError` | 2 | `GET /api/queue-logs?before=9223372036854775808` | 第一处**不需要任何凭据**；第二处需要 Bot 令牌 |
| JSON 嵌套过深，`RecursionError` | 3 | 请求体是两万个 `[` | 报告称凡是解析 JSON 请求体的接口都受影响，其中移动设备登记的提交接口不需要凭据 |
| 字符串里有孤立的代理字符（如 `\ud800`），写库时 `UnicodeEncodeError` | 4 | 安装信息接口的名称、移动设备登记提交、Bot 排队操作 | 移动设备登记那处不需要凭据，其余需要终端或 Bot 令牌 |
| 该是字符串的字段传了列表或对象，`TypeError: unhashable type` | 2 | 移动设备登记提交的 `preference` 传 `[]` | 不需要凭据，但要有一个开放中的登记会话 |
| 重复写入触发唯一约束，`sqlite3.IntegrityError` | 1 | 仅当命令保留时间配置得比移动会话保留时间短 | 取决于部署配置 |

其中不需要凭据就能触发的几处值得优先确认。后果是单次请求返回 500，报告里没有提到数据损坏或进程退出，这一点同样未经复核。

**没有查的 9 段**：第 1 至 5099 行、第 6223 至 8468 行、第 9592 至 12962 行。

**接手方法**：

- 已有的两份结果保存在 `tmp/claude-work/server-sweep-partial.journal.jsonl`，每行一个代理的完整输出，含触发方式、异常和建议改法。
- 代理写的复现脚本在会话临时目录的 `sweep/` 下，重启或清理后可能不在，需要时按上面文件里的触发方式重写即可。我自己的两个复现脚本同样在临时目录，写法见 7.16。
- 工作流脚本和运行编号保存在会话目录里（运行编号 `wf_a7876725-c4f`）。同一个会话里可以用它续跑，已完成的两段不会重算；换了会话就需要重新启动。
- 重新启动前注意额度：上一次协议文档的复审用了 24 个代理、约 475 万个令牌。

本节新增的操作：

52. **读代码**：`app.py` 里 17 处 `UUID()` 调用及其前后，快照上传处理中联系信息、公开 QQ 和序列化的几段；`test_app.py` 里删除资料的测试和几个辅助方法。只读。
53. **启动并中止后台排查**：工作流只读代码，在临时目录里运行自己的复现脚本，不改仓库。中止时保存了已完成的两份结果。
54. **申请让电脑保持唤醒**：被权限系统拒绝，没有再尝试。

### 7.20 新功能设计草案：两台机台之间自动换机（名称待定，2026-11-22）

> 同日补记：维护者随后推翻了本节的原始描述，重新定义了功能。本节的“只补空位”草案作废，以 7.21 为准。多机厅约束和“现状”两段仍然有效。

维护者提出下个版本加入这个功能，要求先设计，不写代码。本节记录原始要求、要我记下的约束、读代码得到的现状和我给出的草案。**草案尚未得到确认，不要据此直接开工。**

**维护者的原始要求**：

- 机厅在设置里开启，开启时必须选定两台机台；功能只在这两台之间生效。
- 不叫“调剂”，名字未定。
- 机厅允许且玩家接受时，用现有的“切换机台”把玩家调到另一台能更快游玩的机台，调整是永久的。
- 玩家资料里加一项偏好，三个选项：每次询问、接受、不接受；登记的菜单里加一项，改的是这份登记本次是否接受。不可用时选项置灰。
- 显示：在某台机台实际结束游玩之前，玩家一直留在原队列；如果按预计游玩时间他在另一台会更早游玩，就在另一台的队列里以灰色、不可点击的条目显示，类似共同游玩预览。
- 举例只用了单人游玩，实际要考虑各种情况，并给出不适用的边界。

**维护者要求先记下的约束（多机厅，路线图 0.15.0）**：玩家在各个机厅共用一份玩家资料，但设置可以分机厅调整，例子是隐私显示和游玩偏好；本功能的偏好也属于这一类。这一条目前只记在本节和 Claude 的记忆里，还没有写进 `docs/roadmap.md`。

**现状（已核实，读代码）**：

- 维护者问的那个功能，发布名称是“共同游玩预览”。它由 `RoundPlanner.waitingProjection` 计算，只读，不改变真实队列。
- 现有的切换机台是 `QueueAction.TransferRegistrations`：登记放到目标机台等待顺序的**末端**。以下情况被拒绝：任一机台停用、登记正在游玩位置、登记是待签到的线上登记、目标机台已满、目标机台容量为 1 而登记属于固定组合。目标容量为 1 时登记的本次偏好被改为单人游玩。
- 预计等待时间在 `app` 模块的 `QueueTerminalUi.kt`（`estimatedMinutesUntilPlaying`），不在 `queue-core`：按每台机台配置的单人和共同游玩计划分钟数逐轮模拟。
- 一轮结束时如果等待顺序里没有人能进入游玩位置，游玩位置保持空缺，途中被越过的暂缓一次、暂时离开、待签到登记按现有规则处理。

**我给出的草案**：

- **核心规则“只补空位”**：只在一台机台的游玩位置确实空出来、而它自己的等待顺序里没有人能上的那一刻，才从另一台把排在最前、符合条件的玩家调过来，直接进入游玩位置。是否执行只看当时的事实，不依赖估时；估时只用来画灰色条目和算等待时间。
- **备选规则“末尾占位”**：接受的玩家在另一台队尾同时占一个虚拟位置，哪边先轮到去哪边。更接近“一条队伍、两台机台”，但后来的人会排在虚拟位置后面，状态、同步和估时都明显更复杂。
- **可被调动的登记**：本次登记为接受；在等待顺序中且状态正常（不是暂缓一次、暂时离开、待签到）；不属于固定组合；当时在原机台不能立刻游玩。
- **机厅设置**：开关加两台机台；两台的游戏类型和服务器必须相同；按机台的内部稳定身份保存；任一台被删除或配置不再匹配时自动关闭并提示。管理后台必须同步提供这项设置。
- **不适用**：任一机台停用；非营业时间；目标机台上有人能上机。
- **建议的名字**：空机补位（灰色条目写“预计补位”）。备选：双机联排、灵活换机。

**待维护者决定**：核心规则用哪一种；名字；玩家资料偏好的默认值（建议每次询问）；容量为 2 的机台上是否允许“补空座”（调一个可以共同游玩的人去和落单的人拼机），建议第一版不做；Bot 的加入指令无法追问，建议按不接受处理。

本节新增的操作：

55. **读代码**（只读）：`queue-core` 的 `RoundPlanner.kt` 全文、`QueueEngine.kt` 中切换机台的校验和执行、`QueueModels.kt` 的登记模型；`app` 中估时函数、玩家资料偏好、机台配置和规则设置的定义；`docs/roadmap.md` 的约束和版本安排。
56. **写记忆**：新增两条记忆，分别是多机厅设置约束和本功能的设计状态。

### 7.21 第二版设计：同时在两台机台排队（2026-11-22）

维护者重新定义了功能，原话的要点：

- 不叫调剂，应该叫“同时在两个机台排队”。
- 登记只保留一份。玩家开启后，这份登记在两台机台同时计算队列，但只显示在一个队列里，起初是登记时的那台。
- 如果预测会在另一台上机，就在另一台的队列里置灰显示，称为“预测”。
- 实际被分配到另一台上机时，登记立即真实移动到那一台。之后仍然两台同时计算，登记显示在移动后的队列里。
- 不涉及实际换机时，显示效果与只排一台相同。

**为设计补读的代码事实（已核实）**：队列是轮换的。一轮结束时，游玩位置里的登记回到本机台等待顺序末端（`MachineQueue.endRoundWithoutStartingNext`），并不离开队列。可以共同游玩的登记按顺序两两成组，中间隔着单人游玩或固定组合时不成组（`groupIntoPositions`）。

**我给出的第二版设计**（未确认）：

基本规则：

1. 一份登记，两个排队位置。开启后，登记在所在机台有真实位置，在另一台有一个“另一台位置”。
2. 另一台位置在取得的那一刻排到那台的队尾，之后来的人排在它后面，即使它当时没有显示。取得的时刻有四种：登记时已开启、在菜单里开启、一轮结束重新排队、从暂停状态恢复。
3. 某台机台要开始下一轮时，按它的顺序取第一个能上的位置，另一台位置与真实登记同等对待。取到另一台位置，登记就真实移动到这台并上机。两台同时轮到时留在所在机台。
4. 一进入游玩位置，两个位置都消失；一轮结束，在两台的队尾各重新取一个位置。这样开启的人不会获得双倍的游玩次数。
5. 灰色“预测”只在联合估时判断这份登记会先在另一台上机时显示，位置就是它在另一台的名次，可以在队列中间。预测只用于显示，实际分配只看哪台真的先轮到。

各种情况：

- 可以共同游玩的登记：另一台位置照常参与成组，可以和那台的真实登记或其他另一台位置组成一组。
- 固定组合：不能开启；组成固定组合时自动关闭两人的选项。
- 暂缓一次、暂时离开、待签到：期间只按所在机台排队，另一台位置取消；恢复正常后在另一台队尾重新取得。
- 未到场：按被叫到的那台现有规则处理。
- 拖动排序：只改变本机台登记之间的先后，另一台位置保持原名次。灰色条目不可操作。
- 手动切换机台：已开启的登记不能在这两台之间手动切换；切到第三台则本次登记退出双机排队。
- 机台停用：停用的那台不分配；另一台照常，包括轮到来自停用机台的玩家。这与现有手动切换机台“任一台停用即拒绝”的限制不同，需要维护者确认。
- 目标机台真实登记已满 20 份：不分配过去，位置保留。
- 重名：功能开启时两台机台之间不允许重名。
- 功能关闭、任一台被删除或配置不再匹配：全部另一台位置取消，登记留在当前所在机台。

开启条件：恰好两台；游戏类型、服务器、游玩容量相同；由同一台终端管理；按机台内部稳定身份保存；管理后台同步提供。

玩家选项：资料偏好三项（每次询问、接受、不接受），登记菜单一项（本次是否开启）；临时登记默认不开启；Bot 加入指令无法追问，每次询问按不开启处理。

**我向维护者指出的代价**：

- 没开启的人也会受影响：排在一个未显示的另一台位置后面的人，在预测翻转时会多等一轮。
- 预测会随时间变化，两台预计时间接近时灰色条目可能出现又消失。
- 改动的是等待顺序这个核心模型，涉及 `queue-core`、终端界面、同步协议和服务端校验、网站、Bot、管理后台，规模明显大于 0.13.x 的补丁，建议单独成版并先写实施方案和长序列测试。

建议的短名：双机排队；灰色条目写“预测”。

本节新增的操作：

57. **读代码**（只读）：`QueueModels.kt` 中一轮结束后的重新排队、加入队尾、等待位置分组三段。
58. **更新记忆**：把功能设计状态改为第二版。

### 7.22 维护者对 7.21 的四处修改，以及名词梳理（2026-11-22）

> 同日补记：本节的“拼机”和“同时在两个机台排队”两个名称随后被维护者收回，名词以 7.23 为准。本节的用词盘点表仍可参考，但其中“朋友与好友并存”一行是我的误判，更正见 7.23。

**对 7.21 设计的修改（维护者原话的要点，已定）**：

- 功能名就叫“同时在两个机台排队”。
- 机厅不一定只有两台机台；开启时在设置里选两台参与。可以开启不止一组，每组只有两台。
- 只有玩家登记的机台属于某一组时，才在确认登记页面询问“是否同时在机台 X 排队”，X 按实际情况显示为同组的另一台。
- 游戏类型和服务器不要求相同，但不同时设置里要弹窗警告。游玩容量要求相同；维护者认为理论上可以规避，这个版本先不考虑。
- 临时登记在询问游玩偏好时一并询问。

我补的一条前提（待确认）：一台机台最多属于一组。否则一份登记会同时排三台以上，7.21 的规则不成立。

**维护者提出的新概念结构**：

- “游玩偏好”今后是总称，包含两个子模块：“单人游玩与拼机”和“同时在两个机台排队”。
- 共同游玩今后都可以叫“拼机”，分为“允许他人加入”和“和朋友共同游玩”（也叫固定组合）。

**现有用词盘点（已核实，按代码和文档里的出现次数）**：

| 概念 | 现有写法（终端 / 网站 / Bot 中的次数） | 问题 |
| --- | --- | --- |
| 两人同玩一轮 | 共同游玩（57 / 16 / 6），“拼机”为 0 | 维护者要改称拼机 |
| 资料里的偏好 | 默认游玩偏好（14 / 10 / 5）、默认偏好（21 / 2 / 6） | 长短两种写法并存 |
| 登记上的偏好 | 本次游玩偏好（20 / 10 / 5）、本次偏好（5 / 0 / 2） | 同上 |
| 偏好的取值 | 单人游玩、允许他人加入、每次询问 | 一致 |
| 两人绑定同玩 | 固定组合（130 / 17 / 11）；入口叫“朋友的登记”（40）；另有“好友”（5 / 2 / 3） | 朋友与好友并存 |
| 换到另一台 | 切换机台（12 / 0 / 4）、转至（10 / 12 / 2）、转移（15 / 2 / 0） | 三个词指同一件事 |
| 排队的先后 | 等待顺序（59 / 11 / 10），另有“登记顺序末端” | 两种说法 |
| 灰色的预计搭档 | 共同游玩预览 | 与新功能的“预测”是两个词 |
| 开始游玩 | 进入游玩位置（35 / 12 / 4），“上机”为 0 | 维护者口头说上机 |
| 机台的显示分组 | 机台分组、分组 | 新功能的“一组两台”会与它撞词 |

代码里的英文标识符（`OPEN_TO_JOIN`、`fixedPartnerKey` 等）不受改名影响。

**我的建议（均未确认）**：同时排队的两台称“一对”或“配对”，避开“分组”；新功能的选项用“每次询问、开启、不开启”；“和朋友共同游玩”按新规则写成“和朋友拼机”；“共同游玩预览”改为“拼机预测”，与新功能的“预测”统一；换机台统一用一个词。是否把现有界面里的“共同游玩”一并改掉，等维护者决定。

本节新增的操作：

59. **盘点用词**（只读）：用脚本统计了七十多个候选词在终端、网站、Bot、服务端和文档里的出现次数，并查看了偏好取值、朋友、共同游玩相关的界面文字。
60. **更新记忆**：新增名词约定一条，更新功能设计状态。

### 7.23 第三轮决定：功能定名“快速游玩”，名词与界面（2026-11-22）

**维护者这一轮定下来的**：

- 功能叫“快速游玩”，默认关闭。维护者说我可以提改名意见。
- 游玩偏好指单人游玩、允许他人加入、与朋友共同游玩（固定组合），快速游玩是它的一个分支。玩家资料里仍然只有单人游玩、允许他人加入、每次询问，与现在一样，另加快速游玩一行。
- “拼机”不采用，仍然都叫共同游玩。“上机”不出现。
- 偏好统一用全称：默认游玩偏好、本次游玩偏好。
- “登记顺序”要改成“等待顺序”。
- 换机台的说法：机厅只有两台时显示“转至 机台 X”（前面可以带备注），多于两台时显示“转至其他机台”。以后统一，去掉“切换机台”和“转移”。
- 机台用唯一的字母标识，“机台 X”就是这个字母。
- 减少中文简称，尽量字对字能看懂；简单的概念不另起名字，例如直接说“快速游玩的一对机台”。不用“组”。
- 一台机台最多属于一对（确认）。
- 两种灰色条目统一成一套界面：下方小字仍是它的游玩偏好；统称“预览”，但这个词不作为概念显示；灰色但可以点开（带 ›），弹窗说明它为什么在这里。
- 界面：玩家资料里是一行带 › 的选项，点开选每次询问、关闭、开启，下方有说明小字；登记上是勾选框，左边带问号可看介绍，做不到统一就改成下方小字。第一次使用的人和所有临时登记，点开后先看功能介绍，5 秒后才能关闭。机厅不允许时，临时登记不显示这一项，其他情况置灰。

**我在这一轮更正的两处自己的错误**：

- 7.21 的“两台机台之间不允许重名”多余。我指的是登记名称，维护者理解成了机台名称。读代码后确认：新建登记和改名时，登记名称本来就在**全部机台**范围内查重（`QueueEngine.kt` 用的是 `state.allRegistrations`），所以移动到另一台不会重名。这条限制删除。
- 7.22 说“朋友与好友并存”是误判。软件里的“好友”全部指 QQ 好友（添加 QQ Bot 为好友才能收私信），与固定组合的“朋友”不是一个概念。维护者据此回答了“全部改成朋友”，我已说明并建议不改，等维护者确认。这次错在只数了出现次数，没有看上下文。

**这一轮核实的其他事实**：

- “转移”主要来自管理后台（`ManagementUi.kt` 10 处），终端 4 处，网站 2 处。
- “切换机台”是 QQ Bot 的一个指令别名，玩家可以直接发送；去掉这个说法时要决定是否保留别名。
- “登记顺序”约 40 处，其中 33 处是日志标题“登记顺序已调整”；服务端和 Bot 没有按这些文字做判断。
- 终端界面现有带 › 的选项行和开关，没有勾选框，也没有问号图标；置灰的项目点一下会提示原因，这个做法已经存在。
- 机台名称的现有格式是“备注·机台 A”。

**我给出的文案草稿（未确认）**：

- 选项行下方小字：同时在机台 A 和机台 B 排队，哪台先轮到就在哪台游玩。
- 功能介绍弹窗（标题“快速游玩”）：开启后，这份登记会同时在机台 A 和机台 B 排队，哪台先轮到，就在哪台游玩。登记仍然只有一份，显示在当前所在的机台。轮到另一台时，登记会自动转至那台机台，请留意现场提示和通知。游玩一轮后，仍会同时在两台机台排队。暂缓一次、暂时离开或尚未签到期间，只在当前所在的机台排队。
- 灰色条目弹窗一（标题“此登记开启了快速游玩”）：这份登记目前在机台 A，同时也在本机台排队。按当前进度推算，本机台会先轮到，所以在这里显示。实际在哪台游玩，以哪台先轮到为准；真实的等待顺序不受影响。
- 灰色条目弹窗二（标题“共同游玩预览”）：这位玩家正在游玩，或排在更前面。按当前顺序推算，他游玩一轮回到等待顺序后，会与这个位置的登记共同游玩。真实的等待顺序不受影响，实际结果以轮到时为准。
- 设置里类型或服务器不同的警告（标题“两台机台的配置不同”）：列出两台各自的类型和服务器，说明开启后玩家可能被安排到另一台，按钮为“取消”和“仍然开启”。
- 游玩容量不同：不能设为快速游玩的一对机台，并说明原因。

**仍待维护者确认**：“默认关闭”是否同时指机厅开关和玩家资料选项；“好友”不改；登记上用现有的开关还是新做勾选框；资料为每次询问时登记页怎样呈现；Bot 的“切换机台”别名是否保留。

本节新增的操作：

61. **读代码**（只读）：登记名称查重的范围、终端界面现有控件、机台名称格式、几个待统一用词所在的文件和上下文。
62. **更新记忆**：名词约定改为第三轮的结果，功能设计状态同步更新。

### 7.24 第四轮：文案风格要求与重写（2026-11-22）

**维护者这一轮的决定**：

- 一对机台的写法定为：允许“快速游玩”功能的一对机台。
- “切换机台”在 Bot 里保留为不展示的别名，可以。现在的 Bot 可能废弃，之后要换成官方 Bot、换框架重写，暂时不管，先保持用词统一。
- “登记顺序”改为“等待顺序”。维护者的理解是：登记顺序这个概念包含等待顺序和游玩位置内的顺序，概念存在，但不一定出现在界面上。
- 勾选框是有的，游玩偏好里的“以后不再询问”就是。

**维护者对文案的要求**：我上一轮的文案太口语化，例如“哪台先轮到就在哪台游玩”“所以在这里显示”。正式文案里口语说法过多，读起来像机器写的。要用项目自己的名词，先浏览现有文案的风格，不要另起炉灶。维护者给的示范：在机台 A 登记，但在机台 A 和机台 B 同时排队；如果机台 B 先轮到此登记，则此登记会自动转至 机台 B。

**我更正的一处错误**：7.23 说终端没有勾选框，是错的。终端有一个自己绘制的勾选控件（`FriendConsentConfirmation`），用在“将本次选择保存为默认偏好，以后不再询问。”和固定组合的同意确认上。我当时只搜了系统自带控件的名字。问号图标确实没有。

**读现有文案得到的写法（已核实，按 `QueueTerminalUi.kt` 的字符串）**：

- 陈述句，分句用分号连接。常见句式：确认后……；转入后……；……期间不……；每次轮到时……；本次将使用“……”。
- 指登记时写“这份登记”（36 处），不写“此登记”（0 处）。
- 可以原样沿用的句子：“预览不会改变真实队列。”“将本次选择保存为默认偏好，以后不再询问。”
- 确认弹窗的标题是问句；知悉按钮是“我知道了”；倒计时写“N 秒后……”。
- “转至”后面空一格再接机台名称。

**重写后的文案草稿（未确认）**：

- 资料里的选项说明：每次询问为“每次使用这份资料在允许快速游玩的机台登记时，再选择是否开启。”；关闭为“默认只在登记的机台排队。”；开启为“默认同时在允许快速游玩的一对机台排队。”
- 登记确认页的说明：这份登记在 机台 A，并同时在 机台 A 和 机台 B 排队。机台 B 先轮到这份登记时，登记自动转至 机台 B。
- 功能介绍、两种灰色条目的说明、设置里的提示和日志文字，见当日会话；确认后再誊入实施方案。

本节新增的操作：

63. **读现有文案**（只读）：设置项的标题和说明、偏好询问页、固定组合同意确认、暂缓一次和暂时离开的说明、确认弹窗、按钮文字、日志标题，并统计了若干常用说法的出现次数。
64. **更新记忆**：新增文案风格一条，名词约定补充本轮决定。

### 7.25 文案逐字复核（2026-11-22）

维护者逐句看了 7.24 的文案，指出几处不统一，并要求我自己再查一遍。

**维护者指出的**：

- “开启后，这份登记在 机台 A，并同时在 机台 A 和 机台 B 排队”不通顺，前半句多余，改为“这份登记将同时在 机台 A 和 机台 B 排队”。
- 共同游玩预览的说明里写成了“这位玩家”，应统一为“这份登记”。
- “在”与“位于”要统一。
- 功能名要不要加引号：维护者自己也觉得不太合适，不行就不加。

**对照现有文案后定的写法（已核实出现次数）**：

- 所在机台用“在”：终端文案里“位于”为 0 处。“处于”只用于位置和状态，例如“这份登记当前处于游玩位置”。
- 用“当前”，不用“目前”。
- 功能名不加引号：暂缓一次、固定组合、共同游玩预览在现有文案里都不加；加引号的只有“使用”后面的选项值，例如本次将使用“单人游玩”。句子不好断时改句子，不靠引号。
- 灰色条目的说明里不写“本机台”（现有仅 1 处），直接写机台名称。
- 规则性的行为用“会”，本次选择即将产生的结果用“将”。

**共同游玩预览的说明按实际情况分两种**（我的建议，待确认）：预计的搭档当前处于游玩位置时写“本轮结束后预计进入这个等待位置”；处于更靠前的等待位置时写“游玩一轮后预计进入这个等待位置”。两种情况在代码里都可能出现，后一种见于被单人游玩登记隔开的两份允许他人加入的登记。

修订后的全部文案见当日会话，确认后誊入实施方案。

本节新增的操作：

65. **核对用词**（只读）：统计了位于、处于、这位玩家、本机台、带引号的功能名等写法在终端和网站文案里的出现次数。
66. **更新记忆**：文案风格一条补充了本轮的要点。

### 7.26 文案第三次修订与概念结构的一处异议（2026-11-22）

维护者又提了五点，并说明他提的意见我都可以反驳。

**维护者的意见与我的答复**：

| 维护者的意见 | 我的答复 | 依据 |
| --- | --- | --- |
| “另一台机台”能否改成“另一机台” | 同意 | 现有文案已有“另一机厅”（4 处），同样不带量词 |
| “这份资料加入排队”改为“这份资料创建登记”；登记是玩家资料的载体，资料是类，登记是实例，登记也可以是临时登记 | 同意，并建议把相邻的现有句子一起改 | “加入排队”与“创建登记”在终端各 17 处，都是现有说法；代码里登记带一个可为空的资料编号，与这个模型一致 |
| 关闭的说明写“只在所登记的机台排队” | 建议写“只在登记所在的机台排队” | 登记可以转至其他机台，之后“所登记的机台”就不是它排队的地方了 |
| 开启的说明去掉“默认” | 同意 | 关闭的说明同样不带“默认”，两条一致 |
| 资料不是每次询问时，快速游玩应与游玩偏好显示在一起；也想过把两者分开，但不知道游玩偏好还能叫什么 | 建议分开，且不需要给游玩偏好改名 | 见下 |

**我的异议：快速游玩与游玩偏好并列，不作为它的分支。** 理由是现有文案里“游玩偏好”始终指一个单选值，例如本次将使用“单人游玩”、修改本次游玩偏好。把快速游玩并进去，这些句子都会变得有歧义。两者并排放置即可：资料页里快速游玩一行紧跟在默认游玩偏好下面；登记确认页合成一句“本次将使用‘单人游玩’，并开启快速游玩。”，不涉及快速游玩时这一句与现在完全相同。这一点等维护者确认。

**顺带建议改的一句现有文案**：默认游玩偏好里“每次询问”的说明现在是“每次使用这份资料加入排队时，再选择本次偏好。”，建议改为“每次使用这份资料创建登记时，再选择本次游玩偏好。”，与新增的那一句对称，同时把“本次偏好”改成全称。

**我收回的一处统一**：7.25 把“该机台”全部并成了“另一台机台”。同一句里紧跟着前文再提时，用“该机台”更合中文习惯，现改回“另一机台先轮到时，登记自动转至该机台”。

修订后的句子见当日会话。

本节新增的操作：

67. **核对用词**（只读）：统计了加入排队、创建登记、台机台、个机台、另一等写法的出现次数，并查看了资料页偏好区域的标题。
68. **更新记忆**：名词约定补充本轮内容。

### 7.27 第六轮：并列关系确定，撞词问题与我的建议（2026-11-22）

**维护者定下来的**：

- “另一机台”全部采用。
- 关闭的说明用“只在登记所在的机台排队。”
- 快速游玩与游玩偏好分开，并列。维护者的顾虑是登记菜单会多一项，以及两个名字都带“游玩”显得重复；没有更好的办法就先这样。
- 勾选框里的“默认偏好”应写全称“默认游玩偏好”。
- “以缩短排队等待”是病句，缩短的是时间。

**维护者提的问题**：如果快速游玩的设置也叫“保存此偏好”，就和游玩偏好撞词了，这个概念应该怎样设计才不撞。

**这一轮核实的事实**：

- 病句来自一条现有文案，我是照着它写的：允许他人加入的说明“默认接受系统分配的共同游玩，以缩短排队等待。”。现有文案另有两处写的是“缩短等待时间”。两条应一起改。
- 现有文案里“偏好”共 64 处，全部指游玩偏好，没有第二种偏好。
- 登记菜单里的“更改游玩偏好”一项只在机台支持共同游玩时出现；容量为 1 的机台没有这一项，所以快速游玩不能只放进那个窗口里。
- 现有文案有“修改会保存到玩家资料库”的说法。

**我的建议（未确认）**：

1. “偏好”一词只留给游玩偏好。快速游玩是一项可以开启或关闭的功能，任何地方都不称它为偏好；需要同时提到两者时逐个点名。
2. 勾选框不点名任何概念，写成“将本次选择保存到玩家资料，以后不再询问。”，页面上有几组选择都只用这一个。这样既不撞词，也不需要纠结“默认偏好”还是“默认游玩偏好”。
3. 登记菜单里的快速游玩做成一行勾选项，点一下直接切换，不进入新窗口；只有登记所在机台属于允许快速游玩的一对机台时才显示。
4. 名字的备选：“同时排队”。这样一个概念讲游玩，一个概念讲排队，字面上就分属两件事，也不再重复“游玩”。代价是不如“快速游玩”直接点出好处。我此前说过不建议再改名，这一条是因为维护者提出了重复和撞词的顾虑才重新提出，由维护者决定。

本节新增的操作：

69. **读代码**（只读）：登记菜单各项的标题和出现条件、更改游玩偏好相关的窗口文字、含“缩短”和“偏好”的全部文案。
70. **更新记忆**：名词约定补充本轮内容。

### 7.28 功能名仍未定（2026-11-22）

7.27 之后的往来：维护者认可“同时排队”，又问能否叫“快速排队”；我的意见是可以但不推荐，因为容易被读成优先通道。维护者随后表示“同时排队”不如直接叫“同时在两个机台排队”。

我同意，并建议不把它当成一个要“开启”的名词，而是当成对行为的直接描述，句子里拿它作谓语。例如登记确认页写“本次将使用‘单人游玩’，并同时在 机台 A 和 机台 B 排队。”，登记菜单的勾选项写“同时在 机台 B 排队”。这样不需要另起名字，也符合维护者“字对字能看懂”的要求。另外提醒：现有文案数机台用“台”（两台机台），没有“个机台”的写法。

**截至本节，名字没有最终确认。** 7.23 至 7.27 中的“快速游玩”可能被替换；动笔写任何文案或方案前先向维护者确认。

### 7.29 “同时在两台机台排队”：截至 2026-11-22 的定稿汇总

维护者确认功能名定为“同时在两台机台排队”，并说明**先不开发**。7.20 至 7.28 是讨论过程，其中有多处已被推翻；**接手时只看本节**。本节每一条都标了来源：维护者定的、我提出且维护者同意的、我提出但维护者尚未答复的。

#### 一、名字和概念关系

| 项目 | 定法 | 来源 |
| --- | --- | --- |
| 功能名 | 同时在两台机台排队。不叫调剂、快速游玩、快速排队、同时排队 | 维护者定 |
| 写法 | 不当成一个要“开启”的名词，尽量直接作谓语，例如“并同时在 机台 A 和 机台 B 排队” | 我提出，维护者同意 |
| 与游玩偏好的关系 | 并列，不是游玩偏好的分支 | 我提出，维护者同意 |
| “偏好”一词 | 只指游玩偏好，本功能任何地方都不称偏好 | 我提出，未答复 |
| 参与的两台 | 不另起名字，写“一对机台” | 维护者定 |
| 资料与登记 | 登记是玩家资料的载体；资料好比类，登记好比实例；登记也可以是没有资料的临时登记 | 维护者定 |

#### 二、规则

以下除注明外，都是我在 7.21 提出、维护者没有反对的设计。

1. 一份登记，两个排队位置：在所在机台有真实位置，在另一机台还有一个位置。登记只有一份，只显示在所在机台。
2. 另一机台的位置在取得的那一刻排到该机台队尾，之后来的人排在它后面，即使它当时没有显示。
3. 机台开始下一轮时，按顺序取第一个能进入游玩位置的位置，两种位置同等对待。取到的是另一机台的位置时，登记真实移动过来并进入游玩位置，所在机台随之改变。两台同时轮到时留在原机台。
4. 进入游玩位置后两个位置都消失；一轮结束，在两台的队尾各重新取一个。这一条防止参与的人获得双倍的游玩次数。
5. 灰色条目只用于显示：按两台的预计时间联合推算，登记会先在另一机台进入游玩位置时才显示，位置是它在那台的实际名次，可以在队列中间。实际去哪台只看哪台先轮到。
6. 不涉及换机台时，显示效果与只排一台完全相同（维护者定）。

各种情况：

| 情况 | 处理 | 来源 |
| --- | --- | --- |
| 允许他人加入的登记 | 另一机台的位置照常参与成组 | 我提出 |
| 固定组合 | 不能参与；组成固定组合时自动退出 | 我提出 |
| 暂缓一次、暂时离开、待签到 | 期间只在所在机台排队；恢复后在另一机台队尾重新取位置 | 我提出 |
| 拖动排序 | 只改变本机台登记之间的先后，另一机台的位置保持原名次 | 我提出 |
| 手动转至 | 参与的登记不能在这一对机台之间手动转至；转至第三台则退出 | 我提出 |
| 机台停止使用 | 停止使用的那台不再分配；另一台照常，包括轮到来自停止使用机台的登记 | 我提出，**未答复**；与现有手动转至“任一台停止使用即拒绝”不同 |
| 目标机台登记已满 20 份 | 不分配过去，位置保留 | 我提出 |
| 登记重名 | 不需要专门限制：登记名称本来就在全部机台范围内查重 | 已核实代码 |
| 机厅关闭本功能、机台被删除或容量不再相同 | 另一机台的位置全部取消，登记留在当前所在机台 | 我提出 |

机厅设置：

| 项目 | 定法 | 来源 |
| --- | --- | --- |
| 开启方式 | 设置里选两台机台为一对；可以有多对，每对两台 | 维护者定 |
| 一台机台 | 最多属于一对 | 我提出，维护者确认 |
| 游玩容量 | 必须相同；这个版本不考虑规避 | 维护者定 |
| 游戏类型、服务器 | 不要求相同，不同时设置里弹窗警告 | 维护者定 |
| 默认值 | 默认关闭 | 维护者定；我理解为机厅开关和玩家资料选项两层都默认关闭，**这一理解未答复** |
| 管理后台 | 同步提供这项设置 | 路线图的既有约束 |

玩家一侧：

| 项目 | 定法 | 来源 |
| --- | --- | --- |
| 玩家资料 | 一行带 › 的选项，点开选每次询问、关闭、开启，下方有说明小字；紧跟在默认游玩偏好下面 | 维护者定 |
| 登记 | 勾选框；用现有的自绘勾选控件 | 维护者定 |
| 登记菜单 | 一行勾选项，点一下直接切换；只有登记所在机台属于一对机台时才显示 | 我提出，未答复 |
| 确认登记页 | 只有登记的机台属于一对机台时才出现；另一台按实际名称显示 | 维护者定 |
| 临时登记 | 在询问游玩偏好时一并询问 | 维护者定 |
| 首次介绍 | 第一次使用的人和所有临时登记，点开后先看介绍，5 秒后才能关闭 | 维护者定 |
| 机厅不允许时 | 临时登记不显示这一项，其他情况置灰 | 维护者定 |
| 多机厅以后 | 各机厅共用一份资料，这一项按机厅分别设置 | 维护者定 |

灰色条目：两种灰色条目（本功能和共同游玩预览）用同一套界面。下方小字仍是它的游玩偏好；右侧带 ›，可以点开，弹窗说明它为什么在这里；“预览”这个词不作为概念显示（维护者定）。

#### 三、文案定稿

写法依据见 7.24、7.25。示例里的“机台 A”是登记所在机台，“机台 B”是另一机台，实际显示机台名称。

玩家资料：

| 位置 | 文字 |
| --- | --- |
| 选项行 | 同时在两台机台排队 › |
| 下方小字 | 另一机台先轮到时，登记自动转至该机台。 |
| 每次询问 | 每次使用这份资料创建登记时，再选择是否同时在两台机台排队。 |
| 关闭 | 只在登记所在的机台排队。 |
| 开启 | 同时在两台机台排队，以缩短等待时间。 |
| 置灰时的提示 | 系统规则不允许同时在两台机台排队。 |

确认登记页：

| 情况 | 文字 |
| --- | --- |
| 两项都已定，本项为开启 | 本次将使用“单人游玩”，并同时在 机台 A 和 机台 B 排队。 |
| 两项都已定，本项为关闭 | 本次将使用“单人游玩”，只在 机台 A 排队。 |
| 机台不属于一对机台，或机厅不允许 | 本次将使用“单人游玩”。（与现在相同） |
| 本项为每次询问 | 标题“选择是否同时在 机台 B 排队”；两个选项“同时在 机台 B 排队”“只在 机台 A 排队”；小字“机台 B 先轮到这份登记时，登记自动转至 机台 B。” |
| 游玩偏好为每次询问，本项已定 | 在“选择本次游玩偏好”下方显示“本次将同时在 机台 A 和 机台 B 排队。”或“本次只在 机台 A 排队。” |
| 页面上有任何一组选择 | 底部一个勾选框：“将本次选择保存到玩家资料，以后不再询问。” |

登记菜单：

| 位置 | 文字 |
| --- | --- |
| 勾选项 | 同时在 机台 B 排队 |
| 下方小字 | 机台 B 先轮到这份登记时，登记自动转至 机台 B。 |
| 置灰时的提示 | 系统规则不允许同时在两台机台排队。／固定组合不能同时在两台机台排队。 |

介绍弹窗，标题“同时在两台机台排队”，按钮“我知道了”，倒计时期间显示“5 秒后可以关闭”：

> 这份登记将同时在 机台 A 和 机台 B 排队。
>
> 机台 B 先轮到这份登记时，登记自动转至 机台 B 并进入游玩位置；本轮结束后，登记留在 机台 B，仍会同时在两台机台排队。
>
> 暂缓一次、暂时离开和待签到期间，这份登记只在当前机台排队。
>
> 固定组合不能同时在两台机台排队。

灰色条目的说明弹窗：

| 标题 | 内容 |
| --- | --- |
| 这份登记同时在两台机台排队 | 这份登记在 机台 A，并同时在 机台 B 排队。按当前等待顺序和预计游玩时间，机台 B 会先轮到这份登记；轮到时，登记自动转至 机台 B。预览不会改变真实队列。 |
| 共同游玩预览（搭档当前处于游玩位置） | 这份登记当前处于游玩位置。按当前轮换规则，本轮结束后预计进入这个等待位置，与其中的登记共同游玩。预览不会改变真实队列。 |
| 共同游玩预览（搭档处于更靠前的等待位置） | 这份登记当前处于更靠前的等待位置。按当前轮换规则，游玩一轮后预计进入这个等待位置，与其中的登记共同游玩。预览不会改变真实队列。 |

共同游玩预览分两种写法是我的建议，**未答复**。

机厅设置：

| 位置 | 文字 |
| --- | --- |
| 开关标题 | 允许同时在两台机台排队 |
| 开关说明 | 允许登记同时在一对机台排队；另一机台先轮到时，登记自动转至该机台。 |
| 列表标题 | 允许同时排队的一对机台 |
| 列表项 | 机台 A 与 机台 B |
| 添加按钮 | 添加一对机台 |
| 类型或服务器不同时的确认标题 | 允许 机台 A 与 机台 B 同时排队？ |
| 确认内容 | 两台机台的游戏类型或服务器不同：机台 A 为 maimai DX（国服），机台 B 为 maimai DX（国际服）。确认后，登记可能自动转至另一机台。请仅在玩家可以在两台机台游玩时继续。 |
| 游玩容量不同 | 两台机台的游玩容量不同，不能设为同时排队的一对机台。 |
| 机台已属于另一对 | 机台 A 已与 机台 C 设为同时排队的一对机台。 |

结束本轮与日志：

| 位置 | 文字 |
| --- | --- |
| 结束本轮的确认内容里追加 | “小明”同时在两台机台排队，将由 机台 A 转至 机台 B 并进入游玩位置。 |
| 日志标题 | 登记已转至机台 B（沿用现有） |
| 日志内容 | “小明”已由 机台 A 转至 机台 B 并进入游玩位置（同时在两台机台排队）。 |

本节的文案是按定下的名字从 7.25 至 7.27 的版本改写的，改写后的整套文字维护者还没有逐句看过。

#### 四、顺带要改的现有文案和用词

| 现在的写法 | 改为 | 来源 |
| --- | --- | --- |
| 每次使用这份资料加入排队时，再选择本次偏好。 | 每次使用这份资料创建登记时，再选择本次游玩偏好。 | 我提出，未答复 |
| 默认接受系统分配的共同游玩，以缩短排队等待。 | 默认接受系统分配的共同游玩，以缩短等待时间。 | 维护者指出是病句 |
| 将本次选择保存为默认偏好，以后不再询问。 | 将本次选择保存到玩家资料，以后不再询问。 | 我提出，未答复 |
| 默认偏好、本次偏好 | 默认游玩偏好、本次游玩偏好 | 维护者定 |
| 登记顺序 | 等待顺序 | 维护者定。登记顺序作为概念包含等待顺序和游玩位置内的顺序，存在但不必出现 |
| 切换机台、转移 | 机厅只有两台时“转至 机台 X”，多于两台时“转至其他机台” | 维护者定；Bot 的“切换机台”保留为不展示的别名 |
| 另一台机台 | 另一机台 | 维护者定 |

不改的：共同游玩（不采用“拼机”）；进入游玩位置（“上机”不出现）；“好友”全部指 QQ 好友，我建议不改，**未答复**。

现在的 QQ Bot 可能废弃，之后换官方 Bot、换框架重写，暂时不在它上面投入（维护者定）。

#### 五、仍未答复的事项

1. “默认关闭”是否两层都默认关闭。
2. 机台停止使用时另一台是否照常分配。
3. 共同游玩预览的说明是否分两种写法。
4. “好友”不改。
5. “偏好”只留给游玩偏好、勾选框改成不点名的写法。
6. 登记菜单的勾选项只在一对机台上显示。
7. 资料需要保存“已看过介绍”的标记，这是实现上的推论，没有讨论过。

#### 六、我对工作量的判断

这个功能改的是等待顺序这个核心模型，涉及 `queue-core`、终端界面、同步协议和服务端校验、网站、Bot、管理后台。建议单独成一个版本，先写实施方案和长序列测试再动手。7.16、7.19 记录的服务端缺陷仍未修复，与本功能无关，但都改同一个服务端文件，排期时要一起考虑。

### 7.30 全软件文案统一：盘点与规范草案（2026-11-23）

维护者提出“把全软件的文案风格做统一”。本节是盘点结果和规范草案。**没有改动任何界面文字。**

**规模（已核实，脚本从提交 `6a04c24` 的工作区提取）**：含中文的界面文字共 4,530 条、约 8.9 万字。

| 部分 | 条数 | 字数 |
| --- | --- | --- |
| 现场终端 | 2,054 | 46,185 |
| 管理后台 | 531 | 6,128 |
| 网站 | 848 | 16,462 |
| QQ Bot | 411 | 8,783 |
| 服务端 | 686 | 11,608 |

**已经统一的（已核实，全部为 0 处例外或接近 0）**：没有半角标点混入中文；中文与数字、英文之间一律空一格；没有“您”；没有感叹号（Bot 有 1 处）；没有“哦、啦、吧”一类语气词，也没有“所以、但是、的话”这类口语连接词。

**不统一的地方**：

| 类别 | 数量 | 例子 |
| --- | --- | --- |
| 维护者已定要统一的用词 | 约 118 处 | 登记顺序 45、默认偏好 29、本次偏好 7、切换机台 18、转移 18、缩短排队等待 1 |
| 其他近义词混用 | 每组几处到几十处 | 目前 15 对当前 385；新建 49 对创建 132；更改 7 对修改 182；离队、离开队列 5 对退出排队 57；云端 7 对服务端 67；队尾 38 对等待顺序末端 20；空状态有“还没有”“目前没有”等几种写法 |
| 同一句话几种写法 | 281 对，其中 78 对跨部分 | 同一条说明在终端、网站、Bot 里各差几个字 |
| 服务端提示的句号 | 686 条中只有 116 条带句号 | 同一句话，终端里带句号，服务端返回的不带 |
| 第二人称 | “你”41 处 | 终端 10、网站 19、Bot 9、服务端 3；其余文字都不称呼对方 |

以下几组看似混用，实际是不同的东西，不应合并：服务器（地址、连接）与服务端（同步的对象）；网站（公开页面）与网页账户；稍候（请等待）与稍后（过一会儿）；已过期（数据）与已失效（登录状态）；撤销（撤销操作）与撤回（把登记撤回等待顺序）。

**规范草案（未确认）**：

1. 语气：书面陈述句，不用口语连接词和语气词，不写安慰或建议性的套话。
2. 用词：一个概念一个词，以 7.29 第四部分和本节的近义词表为准；功能名不加引号，只有“使用”后面的选项值加引号。
3. 指代：登记写“这份登记”，玩家写“这名玩家”；时间写“当前”。
4. 句式：沿用现有的“确认后……”“……期间不……”“本次将……”“请先……”等；规则性的行为用“会”，本次选择的结果用“将”。
5. 标点：完整的句子以句号结尾；标题、按钮、标签不加句号；确认弹窗的标题用问句。
6. 空格：中文与数字、英文之间空一格；“转至”与机台名称之间空一格。
7. 同一件事在各部分用同一句话。

**我提议的做法**：分批改，每批改完同步更新断言文字的测试和引用了服务端提示的 `docs/cloud-queue-sync.md`，并给维护者一份改前改后的对照。顺序：已定的用词；其他近义词；同一句话的几种写法；服务端句号（如果要做）。应用内的历史版本说明不改。规范确认后写成仓库里的一份文档。仓库目前没有给 AI 代理看的说明文件（`AGENTS.md`、`CLAUDE.md` 都不存在），规范文档可以同时起到这个作用。

**等维护者决定的**：是否保留“你”；服务端提示是否补句号（改动面最大，测试和协议文档都引用了这些文字）；队尾与等待顺序末端取哪一个；创建与新建取哪一个；即将重写的 Bot 是否只做已定用词的替换；规范是否写成仓库文档。

**工作文件**（仓库 `tmp/claude-work/`，被 git 忽略）：`ui-strings.jsonl` 是全部文字及其文件和行号；`ui-strings-near-duplicates.json` 是 281 对相近的句子；`extract_strings.py`、`analyze_strings.py` 是生成它们的脚本。

本节新增的操作：

71. **提取界面文字**：用自己写的脚本从五个部分的源码里提取含中文的字符串，只读源码，输出到 `tmp/claude-work/`。
72. **统计写法**：统计了称呼、指代、时间副词、能愿动词、连接词、标点、空格、近义词、错误句式和按钮用词，并查看了有疑问的词的上下文。
73. **查找相近的句子**：按字符相似度找出 281 对几乎相同但写法不同的句子。
74. **更新记忆**：新增一条记录本任务的状态。

### 7.31 文案统一：规范文档与第一批修改（2026-11-23）

**维护者对 7.30 的答复**：去掉“你”；服务端提示应该加句号（并问服务端提示指什么，已解释）；“新建”可以改成“创建”，但要逐一核对；“队尾”与“等待顺序末端”先列出来看；规范要写成仓库文档。Bot 做到什么程度没有答复，本批没有动 Bot。

**新增文件**：[`docs/copywriting-guide.md`](../copywriting-guide.md)，即文案规范，每条规则标明“已定”或“现状”，末尾列出尚未决定的三项。README 的文档列表和目录树各加了一行。

**第一批修改（已核实，未提交）**：共 73 条替换规则、81 处，涉及 10 个文件。

| 内容 | 范围 | 数量 |
| --- | --- | --- |
| 去掉“你”“你们” | 终端 11 处、网站 19 处、服务端 3 处 | 33 处 |
| “新建”改“创建” | 终端、管理后台、网站、服务端 | 48 处 |

“新建”逐条看过，结论是除两条应用内的历史版本说明外全部改为“创建”。其中两处不是单纯换词：“新建后，之后可以更快地加入排队。”改为“创建后，可以更快地加入排队。”，去掉了重复的“之后”；“创建你的排队登记”改为“创建登记”。去掉“你”之后需要主语的地方用“本人”，例如“当前队列中没有本人的登记”。

管理后台有一处“新增分组”，终端同一功能现在是“创建分组”，两者尚未统一，留到“新增”一批处理。Bot 里的 9 处“你”没有动。

同步修改：`AuditLogsTest.kt` 和 `test_app.py` 各一处断言；`docs/cloud-queue-sync.md` 中四处逐字引用。

改前改后的完整对照在 `tmp/claude-work/copy-batch-a.json`，脚本是同目录的 `copy-batch-a.py`。

**验证（已核实）**：服务端 168 项通过；`queue-core` 90 项、`app` 三个变体各 426 项通过；网站构建成功。Bot 没有改动，测试没有重跑。

**这一批里我犯的一个错误，已改正**：替换脚本用文本方式读写文件，把两个行尾混用的网站文件（`QueueStatusPanel.vue`、`MobileRegistrationFlow.vue`）全部转成了 LF，16 行的改动变成了五千多行的差异。这正是第 2.2 节第 9 项记录过的隐患。发现后从 git 取回原文件，改用按字节读写的方式重新套用，行尾保持原样，差异恢复为几十行。以后改这几个文件必须按字节处理，每批脚本改动后先看 `git diff --stat`。

**尚未做的**：已定用词那一批（登记顺序、默认偏好、本次偏好、切换机台、转移、缩短排队等待）；服务端句号；其他近义词；同一句话的多种写法；“队尾”的取舍。

本节新增的操作：

75. **写文案规范**并在 README 加链接。
76. **第一批替换**：用自己写的脚本按“原文出现次数必须相符”的规则替换，随后修正测试和协议文档里的引用。
77. **运行测试**：服务端、四套 Kotlin 测试、网站构建。
78. **修正行尾问题**：见上。
79. **更新记忆**。

### 7.32 文案统一：第二批修改（2026-11-23）

维护者决定统一为“队尾”。这一批连同此前已定的几项用词一起改了。**未提交。**

**修改内容（已核实）**：9 个文件、80 行。

| 内容 | 说明 |
| --- | --- |
| 等待顺序末端、登记顺序末端改为队尾 | 例如“已转至 机台 B 的队尾”“回到队尾” |
| 登记顺序改为等待顺序 | 例如日志标题“等待顺序已调整”“N 台机台的等待顺序彼此独立” |
| 默认偏好、本次偏好改为全称 | 包括勾选框“将本次选择保存为默认游玩偏好，以后不再询问。” |
| 缩短排队等待改为缩短等待时间 | 1 处 |
| 每次询问的说明 | 改为“每次使用这份资料创建登记时，再选择本次游玩偏好。” |

**有意保留、等维护者确认的 15 行**：

- “登记顺序”保留 13 行，因为这些地方指的是连同游玩位置在内的整个顺序：机台停止使用时的“保留所选机台的登记顺序”“保留的登记顺序已经恢复”“登记顺序仍然保留”“登记顺序已保留”，网站的“实时登记顺序”，以及重排命令的校验提示（“命令缺少新的登记顺序。”等）。服务端的三条同类提示也没有动。
- Bot 的指令名“修改默认偏好”保留 2 行，因为是玩家要输入的文字。

“等待顺序前端”没有动；它与“队尾”不对称，是否改为“队首”尚未讨论，已记入规范文档的待定项。

同步修改：`AuditLogsTest.kt` 三处、`RemoteQueueOperationsTest.kt` 一处断言。协议文档没有逐字引用这批句子，未改。

完整对照在 `tmp/claude-work/copy-batch-b.json`，脚本是 `copy-batch-b.py`（按字节读写，不动行尾）。

**验证（已核实）**：服务端 168 项、Bot 80 项、`queue-core` 90 项、`app` 三个变体各 426 项全部通过；网站构建成功；两个行尾混用的文件行尾保持原样。

**规范文档**同步更新：加入“队尾”一行，说明“登记顺序”何时仍可使用，待定项改为“等待顺序前端”是否改“队首”。

**一次被拒绝的修改**：维护者问“通过登录将这份临时登记关联到……”里的“登录”是什么。我查到认领登记的流程里并没有登录，实际是选择一份玩家资料与临时登记关联，这句说明是过时的。我随即直接去改这句话，被维护者拒绝，之后他说“继续”。我的理解是：被问到“这是什么”时应先解释、给出建议，等同意后再改。这句话目前仍是“通过登录将这份临时登记关联到本人身份。”，我的建议是“将这份临时登记与玩家资料关联。”，未实施。

**尚未做的**：“切换机台”“转移”统一为“转至”的写法；服务端句号；其他近义词；同一句话的多种写法。

本节新增的操作：

80. **第二批替换**：先空跑查看将要修改和保留的行，再正式执行；随后修正四处测试断言。
81. **运行测试**：服务端、Bot、四套 Kotlin 测试、网站构建。
82. **更新规范文档和记忆**。

### 7.33 文案统一：队首、“登录”一句、第三批（转至），以及服务端句号的准备（2026-11-23）

**维护者的答复**：“登录”那句按建议改；同类问题可以直接改，少量修改不必再问，大批量修改要问，凡修改都要告知；保留的 13 行“登记顺序”没问题；“等待顺序前端”改为“队首”；剩下三项（转至、服务端句号、其他近义词与同句多写法）都要做。并问：按现在的逻辑等待顺序等于队列，现有文案是否符合。

**本节的修改（已核实，未提交）**：

| 内容 | 数量 | 说明 |
| --- | --- | --- |
| 认领登记的说明 | 1 处 | 改为“将这份临时登记与玩家资料关联。” |
| 等待顺序前端改为队首 | 28 处 | 含测试里的 3 处示例文字和协议文档里的 1 处说明 |
| 切换机台、转移改为转至 | 33 处 | 终端、管理后台、网站、服务端；对照见 `tmp/claude-work/copy-batch-c.json` |
| 等待末端改为队尾 | 2 处 | 管理后台两句确认说明，此前漏统计 |
| 数字两侧补空格 | 1 处 | 管理后台“所选 N 份登记……” |

第三批里有取舍的几处：转移成功的提示标题从“登记已切换机台”改为“登记已转至 机台 B”，直接写出目标机台；管理后台的按钮“确认转移”改为“确认转至其他机台”，“转移整个位置”改为“将整个位置转至其他机台”；管理后台提交的操作原因“管理后台转移登记”改为“管理后台将登记转至其他机台”。

**没有改的**：Bot 里的 4 处“切换机台”，因为是玩家要输入的指令和确认词，相关测试也没动。

**验证（已核实）**：服务端 168 项、Bot 80 项、`queue-core` 90 项、`app` 三个变体各 426 项全部通过；网站构建成功；行尾混用的文件保持原样。

**对“等待顺序是否等于队列”的回答（已核实）**：不等于。现有文案里“队列”共 290 处，指的是整体，包含游玩位置，例如“上次队列保存于……。继续后会恢复游玩位置、等待顺序、暂缓一次……”“现有 N 份登记、游玩位置和等待顺序均已保留”。“等待顺序”只指等待中的登记的先后，改完后还剩 53 条。“队首”“队尾”是等待顺序的两端。规范文档待补这一条说明。

**为服务端句号做的准备（只读，尚未修改）**：

- 服务端的错误提示里，字面量约 460 条没有句号（校验错误 218 条中 187 条，响应里的 `error` 348 条中 275 条），另有带变量的句子和“提示加状态码”的返回值。
- **有几条服务端提示被客户端按原文逐字比较，不能改**：终端用“不支持的队列数据版本”“不支持的队列协议版本”判断是否降低协议版本，用“移动设备登记会话参数不完整”判断是否按旧服务端的方式重试；Bot 用“请求包含不支持的排队操作字段”判断是否去掉新字段重试。已经发布的客户端无法更新，这几条必须保持原样。我的计划是把它们排除在外，并写进规范文档作为“受保护的提示”。
- 终端有三处在提示后面自己补句号（`"$message。"`），一处会去掉句号，补句号时要检查是否出现两个句号。
- 最近事件的校验错误是把多行拼在一起的（“最近事件第 N 条的……”“字段：……”“事件编号：……”），句号的位置要单独处理。

**下一批近义词里新发现的**：“队列顺序”8 处、“排队顺序”5 处、“排队先后顺序”1 处，与“等待顺序”并存；管理后台的“新增分组”与终端的“创建分组”。

本节新增的操作：

83. **三处替换**：用按字节读写的脚本改“队首”和认领说明，第三批用逐条核对次数的脚本。
84. **运行全部测试**。
85. **只读核查**：统计服务端错误提示的结尾，查找客户端对服务端提示文字的逐字比较，查看“队列”“等待顺序”的现有用法。
86. **更新记忆**：工作约定增加“少量修改不必问、大批量要问、凡改必报”。

### 7.34 动作用词的系统检查（2026-11-23，只讨论，未修改）

维护者指出“退出排队”“移除登记”这类说法没有统一，要求讨论，并系统检查还有哪些类似问题。本节是检查结果和我的建议，**都没有实施，等维护者决定**。

做法：重新提取全部界面文字（历史版本说明除外），按动作分成十几类，统计每类里各种写法在五个部分的出现次数，再逐类查看上下文，区分“真的混用”和“有意区分”。

**一、登记离开队列**

| 谁决定 | 现有写法（次数） |
| --- | --- |
| 玩家自己 | 退出排队（菜单、确认、结果，一致） |
| 工作人员 | 终端：移除登记、移除整组、移除本轮登记；管理后台另有“永久退出队列”3 处、“删除登记”1 处、“出队”1 处 |
| 系统按规则 | 自动退出排队 23 处；自动移除 8 处；两者说的是同一件事（线上登记超时未签到、暂时离开轮空满 3 次） |
| 其他 | 离开队列 2 处；Bot 的“取消登记”2 处 |

我的建议：按“谁决定”分成两个词。玩家自己退出和系统按规则退出都写“退出排队”，后者加“自动”；工作人员把别人的登记拿掉写“移除登记”。“删除”只用于玩家资料、机台、分组，不用于登记。

**二、类似的混用**

| 类别 | 现状（次数） | 我的建议 |
| --- | --- | --- |
| 暂缓一次的结束 | 自动解除、自动恢复并存；手动是“取消暂缓一次” | 手动写“取消”，自动写“自动解除” |
| 暂时离开的结束 | “取消暂时离开”为主，另有 1 处“手动解除” | 统一为“取消” |
| 当前状态下不允许 | 暂不能 38，暂时不能 10 | 统一为“暂不能” |
| 操作没有生效 | 未执行 13（多为标题），没有执行 20（多为句子） | 标题用“未执行”，句子用“没有执行”，写进规范 |
| 让对方重新获取后再操作 | 请刷新后重试、请刷新后再提交、请重新查询后再操作、请重新查询后再试 | 统一为“请刷新后重试” |
| 现场的人 | 现场工作人员 9，现场管理员、管理员 7 | 二选一，需维护者定 |
| 打开开关 | 开启 50，启用 11 | 统一为“开启” |
| 增加一份登记 | 创建登记为主，另有“新增登记”“暂时不能新增” | 统一为“创建” |
| 登记数量的单位 | “20 份登记”与“20 人上限”并存 | 统一为“份” |
| 排队的先后 | 等待顺序为主，另有队列顺序 8、排队顺序 5、排队先后顺序 1 | 统一为“等待顺序” |
| 分组 | 终端“创建分组”，管理后台“新增分组” | 统一为“创建分组” |
| 此前已列出 | 目前 15、更改 7、云端 7、离开队列 2 | 当前、修改、服务端、退出排队 |

**三、看着像混用、我认为是有意区分的**（建议保留并写进规范）

| 词 | 区别 |
| --- | --- |
| 轮空 / 跳过 | 轮空用于暂时离开，会计数；跳过用于暂缓一次 |
| 开启 / 打开 | 开启指开关；打开指页面、二维码 |
| 编辑 / 修改 | 编辑是菜单入口；修改是改动本身和结果 |
| 下一组 / 下一轮 | 组指一起游玩的登记；轮指一次游玩 |
| 暂时无法 / 暂不能 | 无法是做不到（读取、连接失败）；暂不能是当前状态下不允许 |
| 取消 / 解除 | 取消用于暂缓一次、暂时离开；解除用于固定组合 |
| 现场终端 / 终端 / 本机 | 本机只在终端自己的界面里用；现场终端用于从网站、管理后台看终端 |

本节新增的操作：

87. **分类统计**（只读）：对离开、修改、撤销、开关、轮次、跳过、关联、读取、确认、失败、人、地点与设备、到场、时间等十几类用词做了计数，并查看了有疑问的写法的上下文。
88. **更新记忆**。

### 7.35 转入功能开发（2026-11-23）

**维护者的指示**：先按计划开发“同时在两台机台排队”，文案等功能完成后再研究；不是重要决策就不要停；开发的同时检查现有排队全套功能的运行逻辑，力求严谨而不过头；发现的问题记录下来，功能完成后一并修复，严重的可以先修再继续。

**文案方面在暂停前定下的**：“退出排队”与“移除登记”按“谁决定”来分，同意；未到场时的处理也属于“移除登记”，因为是别人代为操作。维护者另提了一个想法留待以后讨论：队列应该就是等待顺序，“等待顺序和游玩位置”合起来或许可以叫登记顺序，因为队首、队尾就是队列的两端。这与现有文案里“队列”的用法（整体，含游玩位置）不一致，恢复文案工作时要先定下来。7.34 表格里的其余各行没有答复。

**开发计划**：写在 [`docs/releases/dual-machine-queue-plan.md`](../releases/dual-machine-queue-plan.md)。其中包括对未答复事项采取的默认做法、数据模型、八个里程碑及其状态、测试要求，以及“检查现有逻辑时发现的问题”一表。此后功能开发的进度记在那份文件里，本文件只记关键节点。

本节新增的操作：

89. **通读排队核心**（只读）：`QueueEngine.kt`、`QueueModels.kt` 的主要部分、`QueueInvariants.kt`，以及终端调用排队核心的入口。
90. **新增开发计划文件**。
91. **更新记忆**。

### 7.36 “同时在两台机台排队”开发完成（2026-11-23）

八个里程碑全部做完，详细过程、默认做法、发现的问题和新增文字清单都在 [`docs/releases/dual-machine-queue-plan.md`](../releases/dual-machine-queue-plan.md)，本节只写接手时最需要知道的。

**现在的状态**

- 全部改动都在工作区里，**没有提交**，最近一次提交仍是 `6a04c24`。版本号没有定，各端的版本号、`docs/update.md` 和 README 的功能列表都没有动。
- 自动化测试全部通过：`queue-core` 117 项；`app` 本地版、管理版、终端版各 454 项；服务端 198 项；Bot 80 项；网站构建通过。
- **终端和管理应用的新界面没有在设备或模拟器上看过**，只经过编译和逻辑测试。网站的队列页、加入排队、手机扫码登记页在本机用临时服务端实际打开检查过；登录账户后的本人登记开关和资料设置没有实际点过。

**这项功能涉及的范围**

- 排队核心：`PairedMachineQueues.kt`、`PairedMachineForecast.kt`，以及 `QueueEngine.kt` 里的接入。没有配置一对机台时，行为与原来完全相同，原有测试一项未改。
- 终端：机厅设置、玩家资料里的选项、确认登记页、登记菜单、介绍弹窗、首页灰色条目、预计等待时间、日志、撤销、三条远程命令路径。
- 协议与服务端：没有提升协议版本号，全部是可缺省的新字段，新旧版本可以混用。完整说明在 `docs/cloud-queue-sync.md` 的“当前队列”一节和新增的“同时在两台机台排队”一节。服务端的玩家资料表新增两列，启动时自动补上。
- 网站：队列页、加入排队、手机扫码登记页、账户的本人登记操作和资料设置。
- 管理应用：登记标签、登记菜单里的开关、设置页里的一对机台设置。

**对之前未答复事项采取的做法（都可以改）**

1. “默认关闭”按机厅开关和玩家资料选项两层都默认关闭处理。
2. 一台机台停止使用时，它自己不再接收登记，另一台照常，包括轮到来自停止使用机台的登记。
3. 登记菜单里的开关只在登记所在机台属于一对机台时显示。
4. “已看过介绍”记在玩家资料上并同步；网站无法写回资料，记在浏览器里。
5. 确认登记页底部的勾选框改为“将本次选择保存到玩家资料，以后不再询问。”，同时管游玩偏好和本项。
6. 终端被管理后台接管后，这项设置只能在管理后台修改（开发中途一度允许终端修改，管理后台支持后改回与其他设置一致）。
7. 手机上新建资料时不询问这一项，新资料从“关闭”开始；手机扫码登记页没有 5 秒介绍弹窗。

**顺带修复的问题**

- 7.16、7.19 记录的服务端缺陷：联系信息指向已删除资料导致上传失败（并一并处理了已删除资料的公开 QQ 会重新出现的后果）；三处编号不是字符串时出错；以及同一类的“该是文字的字段传了数组或对象”。各有回归测试。
- 开发中发现并修复：管理后台创建登记和移动设备登记在登记被另一机台立即取走时会被误判为失败；同时排队的登记“转至”另一机台时没有提示。
- 没有处理的：7.19 里那份未经复核的排查结果；远程命令协程过大的问题只拆了一类；管理后台“终端队列操作”接口原有的二十多种操作在服务端仍然没有测试。

**文案工作**

仍然暂停。这次新增和改动的全部界面文字列在计划文件的第九节，其中只有一处改动了现有文字（确认登记页的勾选框）。新写的句子都没有经过逐句确认。不能改动的服务端提示原文又多了一句：`请求包含不支持的线上登记字段`。

**本机上留下的东西**

- `tmp/claude-work/`（已被 git 忽略）：各步的修改脚本、`mixed_eol_edit.py`（修改混用两种换行符的文件时用）、`demo/`（本机演示用的启动配置和上传演示数据的脚本）。
- 演示用的临时数据库已删除，`.claude/launch.json` 用完后已移到 `tmp/claude-work/demo/`，仓库里没有留下。

本节新增的操作：

92. **开发**：按计划文件逐步修改 `queue-core`、`app`、`cloud-server`、`public-site` 的代码和测试，更新 `docs/cloud-queue-sync.md`、`docs/user-manual.md`、`docs/roadmap.md`。没有提交，没有推送，没有改版本号。
93. **本机演示**：在本机启动临时服务端（端口 8791，临时数据库，演示用令牌）和网站开发服务器（端口 5183），上传演示快照，在内置浏览器里检查页面。只监听本机地址，没有访问线上服务器，用完已停止并删除数据库。
94. **运行测试**：四套 Kotlin 测试、服务端测试、Bot 测试、网站构建。

### 7.37 维护者看过演示后的修改（2026-11-23）

维护者看了本机的网站演示，对 7.36 里两项自行决定的做法给了答复，又提了三点文案意见。全部已改完，细节在 [`docs/releases/dual-machine-queue-plan.md`](../releases/dual-machine-queue-plan.md) 第十节。

**维护者的原话与对应的修改**

1. 对“默认关闭按两层都默认关闭”：“后者，创建时默认不选，等待玩家自己勾选三者之一；对于已有的玩家资料，该项为空，所以需要补全玩家资料再继续。点击‘关闭’以外的选项时，都弹出功能介绍，五秒后才能关闭”。
   - 玩家资料的这一项改为没有默认值，可以为空；机厅开启功能后，空着的资料要先补全才能使用（与缺少 QQ 设置时相同的三处：加入排队、认领登记、创建朋友登记）。
   - 选“每次询问”或“开启”时弹 5 秒介绍，选“关闭”不弹。
2. 对“手机上新建资料时不询问”：“对于网页端创建玩家资料，也应该和终端保持一致”。
   - 网页创建玩家资料、手机扫码登记页里的新建资料和补全资料都增加这一项，必选，同样的介绍弹窗。
3. “把只在左侧排队放在上面，引导用户优先不同时排队”。
   - 终端确认页、网站加入排队、手机扫码登记页的二选一都把“只在 … 排队”放在前面。
4. “按钮大标题下方的小字说明应该是完整的句子，有句号。”
   - 已写入 `docs/copywriting-guide.md`，并改了网站上的 6 处（其中 4 处是原有的游玩偏好按钮，为了同一页面一致）。
5. “强调登记自动转至另一机台后，在原队列的等待顺序将作废（这个动词用什么我没想好）。”
   - 介绍里加了一句，动词暂用“不再保留”，等维护者定。
6. 维护者要求确认的规则：登记在游玩位置时不在任何一台机台的等待顺序里，本轮结束后才同时进入两台机台的队尾。已对照 `PairedMachineQueues.kt`（`holdsPairedMachinePlace` 只对等待中的登记成立）和 `PairedMachineQueueTest.threeSoloPlayersTakeTurnsOnWhicheverMachineIsReachedFirst` 核实，确实如此。

**实现时自己定的做法（都可以改，理由见计划文件第十节的表）**

- 机厅没有开启功能时不要求选择，资料可以一直空着。
- 是否要求补全按机厅是否开启判断，不看所选机台是否成对。
- 线上登记的现场签到不因这一项被拦下。
- 网站线上登记和 QQ Bot 不拦，资料空着时登记只在一台机台排队。
- 介绍弹窗同一份资料只弹一次，不是每次点击都弹。

**现在的状态**

- 仍然没有提交，没有改版本号。
- 测试：`queue-core` 117 项；`app` 三个变体各 457 项；服务端 206 项；网站构建通过。Bot 没有改动。
- 网站的创建资料页、手机扫码登记页、加入排队、账户资料设置都在本机浏览器里实际操作过。终端和管理应用的界面仍然没有在设备上看过。
- 顺带修复一处原有问题（计划文件 P9）：网页创建玩家资料被服务端拒绝时，页面上原因一行是空的。

**本机演示的做法有两处变化**

- 演示网站改为先打包成一份快照（`tmp/claude-work/demo/site/`）再提供，这样修改代码不会影响正在看的演示。
- 网站访问服务端要用 `localhost` 而不是 `127.0.0.1`，否则登录账户的 Cookie 带不过去（上一次“账户设置没有实际点过”就是这个原因）。另外队列页会在快照超过 90 秒后显示“队列数据已过期”并停用线上登记，演示时需要定时重新上传快照。

本节新增的操作：

95. **修改**：`app`、`cloud-server`、`public-site` 的代码和测试；新增 `public-site/src/queue/pairedQueueCopy.js`、`PairedQueueIntroduction.vue`；更新 `docs/cloud-queue-sync.md`、`docs/user-manual.md`、`docs/copywriting-guide.md` 和计划文件。没有提交，没有推送，没有改版本号。
96. **本机演示与实测**：启动临时服务端（端口 8791、8792，临时数据库，演示用令牌）和网站（端口 5183、5184），只监听本机地址，没有访问线上服务器。
97. **运行测试**：四套 Kotlin 测试、服务端测试、网站构建。

### 7.38 两种排队方案的比较（2026-11-23，只讨论，未修改）

维护者贴来与另一个模型的讨论，并说“我更希望实现的是第一种方案”，要求总结两种方案的区别和取舍。尚未决定，**在维护者决定之前不要继续打磨现有实现的文案和界面**。

- **方案一：两台机台共用一条队。** 哪台空了，队首去哪台；玩家可以指定只玩某一台，轮到时那台没空就让后面的人先上。
- **方案二：每台机台各排各的队，登记同时在另一台占一个位置。** 现在做出来的就是这一种。

**两者何时结果相同。** 只有当两台机台各自的先后顺序始终是同一条总顺序的筛选结果时才相同（另一个模型的结论，我同意）。现有实现不满足这个条件，而且是有意这样设计的：另一机台的位置在取得时排在队尾。

**现有实现与方案一会产生区别的地方**

1. 默认选择相反。方案一默认两台都排，指定机台是例外；现有实现默认只排一台，同时排队要主动选择，并且按维护者的要求把“只在 … 排队”放在前面。选的人少，两条队就仍然各走各的。这是实际效果上最大的区别。
2. 暂缓一次、暂时离开、线上登记待签到结束之后，现有实现里另一机台的位置回到队尾重新取得；方案一里在总队列中的位置不变。
3. 中途才开启同时排队的，同样排到另一机台的队尾。
4. 固定组合在现有实现里不能同时排队；方案一里一组就是一个排队单位，哪台先空去哪台。
5. 工作人员调整顺序在现有实现里只影响一台机台；方案一只有一个顺序。
6. 每台 20 份登记的上限、三台以上的机台：现有实现按台计上限，只能两两成对；方案一没有这两个限制。

两台同时空着而队首只玩其中一台的情况，两个方案有同样的小问题，现场很少出现，不构成取舍。双人成组的规则两个方案可以一致（跳过不能上这台的登记）。

**取舍**

- 方案一的优势：一句话就能向玩家说明；默认就不会出现一台空着、另一台排长队；全场先来先玩；没有“两套顺序对不上”的问题；可以推广到三台以上；固定组合也能用。
- 方案一的代价：轮到之前不知道去哪台；多数玩家认定某一台时，一条队里会穿插“只玩左”“只玩右”，队首不动而后面的人先上，容易引起疑问；最主要的是改动量——它换掉的是“每台机台一条等待顺序”这个底层模型，涉及排队核心、终端首页、网站、管理应用、Bot、本机保存的数据和同步协议（必须提升协议版本号，各端一起升级），这项功能现有的大部分代码（合并顺序、灰色条目、两台联合推算）用不上。
- 方案二的优势：已经做完并通过测试；不改动现有的按台排队、界面和协议，可以关闭，新旧版本可以混用；玩家始终知道自己的登记在哪台。
- 方案二的代价：规则多，需要介绍弹窗和灰色条目来解释；效果取决于有多少人选择同时排队。

**我的看法**

- 就排队规则本身而言方案一更好，前提是多数玩家两台都能玩。这是现场事实，需要维护者判断：玩家里有多少人在乎玩左边还是右边。
- “引导玩家优先不同时排队”与方案一的出发点相反。如果希望多数人只排一台，方案一的优势基本用不上。
- 折中做法：保留两块登记板，但让两台的先后都取自同一条总顺序，并把默认改为同时排队。结果与方案一相同，改动小得多，但灰色条目这套较难解释的显示还在。
- 如果选方案一：先写计划（数据模型、界面、协议版本、已有数据的迁移、工作量），维护者看过再动手；现有实现没有提交，可以原样留着或另存分支。

### 7.39 沿用现有实现，并用“共用一条队”做对照验证（2026-11-23）

维护者对 7.38 的答复：“我不需要分析玩家的数量，因为最终这两种方案实现起来显示效果是一样的，只是内部算法不同。我要求他们必须有相同的结果，作为验证，如果有结果不同的情况，拿出来单独讨论再分析哪一种情况更合理。既然第二种已经是现有方案并且更合理，那就沿用第二种，但我们仍然可以继续讨论。”

所以：**沿用现有实现；“共用一条队”作为对照答案；两者结果不同的情况逐条讨论后再决定改不改。**

已经做的（只增加了测试，没有改任何行为）：

- `queue-core/src/test/.../SharedQueueReference.kt`：“共用一条队”的参照模型，只用于测试。
- `queue-core/src/test/.../SharedQueueEquivalenceTest.kt`：两边执行同样的操作并逐步比较；两项“结果必须相同”的随机测试；八个 `difference…` 测试，每个固定记录一种结果不同的情况和两边各自的结果。设置环境变量 `SHARED_QUEUE_EXPLORE=true` 再运行这个测试类，会重新搜索各类操作下的最小差异例子，写到 `queue-core/build/shared-queue-differences.txt`。
- 结论、八种情况的例子和我的看法在计划文件第十一节。`queue-core` 现在是 128 项测试，全部通过。

接手时注意：八种情况都在等维护者决定，**没有决定之前不要改引擎的行为**。决定之后改引擎，并把对应 `difference` 测试里“现有实现”一侧的期望结果改掉；如果某种情况改成与“共用一条队”一致，可以把那类操作加进“结果必须相同”的随机测试。

写参照模型时踩到的一处：单机台规则把暂时离开的登记“移到队尾”，映射回总队列时不能只看幸存登记的相对顺序（只剩一份时看不出它被移动过），要以“排在刚回到等待顺序的登记之后”为准。

本节新增的操作：

98. **新增测试**：上述两个测试文件。没有改动正式代码，没有提交。
99. **运行测试**：`queue-core` 全部测试。

### 7.40 原则改为“以登记为主”，第 5、6 种改为放弃另一机台的位置（2026-11-23）

维护者对 7.39 八种情况的答复改变了衡量标准。原话：“始终以登记为主，如果遇到冲突等，本轮在另一个机台的排队可以直接作废。因为多等一会并不造成公平性问题，双机排本来就不是根本规则。”“涉及到调整的话，就默认本轮只在当前机台排队，下一轮起再在两台机同时排队。”“这一点在调整的确认弹窗里应该有所说明。”

所以：**不再要求与“共用一条队”结果相同。登记在所在机台的排队不受影响；另一机台的位置是附带的，有冲突就放弃。** 对照测试保留，作用变成“每一处差别都是看过的决定”。

这一轮做了什么（细节、全部文字和我自己定的地方在计划文件第十二节）：

- 引擎：登记新增状态 `skipsPairedMachineThisRound`。调整等待顺序、调整队列位置时被移动的登记，以及从游玩位置撤回的登记，放弃另一机台的位置，进入游玩位置时清除。`PairedMachineQueues.reorderedKeys` 判定谁被移动；`ReplaceOrder` 新增可选的 `movedRegistrationKeys`。
- 终端：本机保存和上传这个状态；四个确认窗口（调整等待顺序、调整队列位置、撤回至队首、撤回这份游玩登记）和编辑登记页加说明；登记菜单的小字按状态变化。说明文字由引擎的预演结果生成，所以窗口里列出的登记与实际受影响的登记一定一致。
- 管理端：登记标签、退回队首的确认、调整等待顺序窗口加说明。
- 服务器和网页：接收、校验、下发这个状态；网页的本人登记详情显示说明。这一部分由并行的子任务完成，我复核了改动并重新跑了测试和构建。
- 八种情况里只有第 5、6 种改了行为；第 1 种我撤回了之前“改成与共用一条队一致”的建议。

**功能名称重新讨论（未定，未改任何文字）。** 维护者认为现在的名字像是一种“天然的权利”，想改回“快速排队 / 快速游玩”，随后又提出“负载均衡”和“接受机台调整以快速游玩”，并说后者不错。我的意见：

- 同意现名有这个问题：它把机制说成了“占着两个位置”，于是每一次放弃都像是被拿走了东西。
- “快速排队”容易被读成优先通道，“快速游玩”容易被读成一种游玩模式；两者都在许诺结果，限制越多越容易落空，而且都没有说出玩家真正要同意的事：可能被转到另一台机台。
- “负载均衡”是机厅视角的术语，和已经否决的“调剂”是同一类，可以用在设置页里描述这项规则，不适合给玩家选。
- “接受机台调整以快速游玩”的方向最好：“接受”把它说成玩家的同意而不是权利。建议只换两个词：“调整”在软件里已经指调整顺序，而且移动机台的既定说法是“转至”；“快速游玩”换成维护者自己改过的“缩短等待时间”。即 **接受转至另一机台，以缩短等待时间**，短写为“接受转至另一机台”，选项可以是“每次询问 / 不接受 / 接受”。
- 改名涉及终端 40 处、网页 19 处、服务器 19 处、手册 11 处全称，属于大批量修改，要等维护者确认名字后再做，并且最好在规则定完之后。

维护者对我这条建议的答复：“这个名字不太好听，而且还带逗号；但 转至 的说法我们也没必要现在就敲定。”即**我的建议未被采纳，名称暂不敲定**，不要再提这个名字，也不要催着定名。我随后只提了一个不带逗号的想法供以后参考：“不限机台”（表达的是玩家不介意在哪一台游玩，是一种同意而不是权利，也不许诺速度），维护者尚未回应。在定名之前，现有文字继续用“同时在两台机台排队”，新增文字尽量不出现功能名。

**维护者随后的两点（2026-11-23，原话）：**

- “我们应该弱化‘双机同时排队’这个说法，虽然是实际算法，我们在介绍时可以不这么说，比如归结为预测机制。因为双机排队这个做法本身是不符合规则的。”
- “接着想名字，现有的那个我觉得不行。”

所以：**现名“同时在两台机台排队”确定不用；对玩家的介绍不再说“同时在两台机台排队”“在另一机台保留位置”，而是说成登记只在一台机台排队、由系统安排到另一机台。** 内部算法不变。名字和介绍文字都还没有改，等维护者选定。

我这一轮的建议：

- 首选 **系统安排机台**。依据是现有文字本来就这样说系统做的事：“默认接受系统分配的共同游玩，以缩短等待时间。”“本次自动安排不会再次选中相关登记。”“等待工作人员安排。”照这个句式，开启的说明可以写成“默认接受系统安排的机台，以缩短等待时间。”
- 备选 **接受分流**：维护者提过的“负载均衡”的日常说法，带“接受”。
- 备选 **允许更换机台**：与现有的“允许他人加入”同一句式。
- 一点提醒：登记转至另一机台发生在另一机台真正先轮到它的时候，不是按预测提前决定的。“预测”一词现在只用于灰色条目和预计等待时间（手册里写着“灰色条目只是预测”）。介绍里建议把转至说成“系统安排”，把“预计”留给时间和灰色条目，否则灰色条目没有兑现时会被理解成系统安排错了。
- 无论怎么弱化，介绍里要保留三件事：可能在另一机台被叫到；游玩后留在那台机台；顺序被调整后这一轮不再安排到另一机台。

**还在等维护者决定**：名字；暂缓一次、暂时离开是否也改成“游玩一轮后才恢复”（计划文件第十二节“还没有定的”）。

没有验证的部分：终端、管理端的窗口没有在设备上看过；网页的那句说明没有在浏览器里看过。本机演示仍在运行，`.claude/launch.json` 仍在仓库里，演示结束后要删除。

本节新增的操作：

100. **改动正式代码**：`queue-core` 的 `QueueModels.kt`、`PairedMachineQueues.kt`、`QueueEngine.kt`；`app` 的 `LocalQueueStateRepository.kt`、`QueueCloudSync.kt`、`ManagementApi.kt`、`ManagementUi.kt`、`QueueTerminalUi.kt`，新增 `PairedMachineQueueNotices.kt`；`cloud-server/app.py`；`public-site/src/queue/QueueStatusPanel.vue`。
101. **改动测试**：`PairedMachineQueueTest.kt`（加 5 项）、`SharedQueueEquivalenceTest.kt`（第 5、6 种）、`QueueStatePersistenceTest.kt`、`QueueCloudSnapshotTest.kt`、新增 `PairedMachineQueueNoticesTest.kt`、`cloud-server/test_app.py`。
102. **改动文档**：`docs/cloud-queue-sync.md`、`docs/user-manual.md`、计划文件第十一节（加一行提示）和第十二节（新增）。
103. **运行测试**：`queue-core` 133 项、`app` 三个变体各 462 项、服务器 210 项、网页构建，全部通过。没有提交，没有改版本号。

### 7.41 维护者提议：不起名、不做玩家资料选项，只留确认登记时的勾选框（2026-11-23，讨论中，未修改）

维护者原话：“不敲定的意思是我们接着讨论，不是现在停止。我觉得要不然不给这个功能起名了，玩家资料里也不做这个偏好了，只在单个登记里确认登记的时候提供一个勾选框，也就是轻量化弱化这个开关。并且把它归属到本次游玩偏好。”

（第一句是纠正我：他说“没必要现在就敲定”时，我把名字搁下了，他的意思是继续讨论。）

**我的意见：赞成。** 理由：

- 与“福利性功能、要弱化”的定位一致。现在的做法反而在推广它：机厅开启后，每一份已有的玩家资料都被“补全玩家资料”拦住，必须在三个选项里选一个。
- 不需要名字了：勾选框的标签本身就是一句话。
- 勾选框已经存在。临时登记现在用的就是它（`PairedMachineQueueJoinChoice`），使用玩家资料的登记改走同一条路即可，主要是删除。
- 我在 11-22 反对过“把它并入游玩偏好”，理由是资料里的“默认游玩偏好”必须是单选值。资料里不再有这一项之后，这个顾虑不存在了：它只是“本次游玩偏好”这一块里多出的一个勾选框，“本次将使用‘单人游玩’”这类句子不用变。

**会删掉的东西**（都是 11-23 上午按维护者当时的要求做的，全部未提交、未部署，所以可以直接删，不需要数据迁移）：

- 玩家资料里的三选一选项及“未选择”状态；“补全玩家资料”里的第三条理由；“保存到玩家资料，以后不再询问”对这一项的作用。
- 网页创建玩家资料页、手机登记页、网页账号设置里的这一项；服务器的 `paired_machine_queueing` 列和相关校验、错误码。
- 涉及引用：终端 9 个文件约 110 处，服务器约 60 处，网页 5 个文件约 75 处，测试 7 个文件。

**保留的东西**：登记自身的状态和引擎；确认登记时的勾选框（终端、网页线上加入、手机登记页）；登记菜单里中途开启和取消的一行；功能介绍弹窗。

**需要维护者定的四点，以及我的建议：**

1. 功能介绍弹窗何时出现。建议保持现状：使用玩家资料的登记，这份资料第一次勾选时出现一次；临时登记每次勾选都出现。这需要在资料里保留一个“已看过介绍”的记录（不是偏好，玩家看不到）。另一种做法是资料里完全不留痕迹，每次勾选都出现五秒弹窗。
2. 排队中途能否开启。建议保留登记菜单里的那一行，至少“取消”必须保留，否则勾选后无法反悔。
3. “归属到本次游玩偏好”的做法。建议：确认登记页上把勾选框放进“本次游玩偏好”这一块；登记详情的“本次游玩偏好”一行后面注明；登记菜单里仍然是独立的一行，因为只能容纳一人的机台没有“更改游玩偏好”这一项。
4. 勾选框的文字（不出现功能名，也不说“同时排队”）。草稿：勾选框“接受转至 机台 B 游玩”，小字“机台 B 能更早安排这份登记游玩时，登记自动转至 机台 B。”机厅设置里的“允许同时在两台机台排队”也要换成描述性的说法，例如“允许系统在一对机台之间安排登记”。

动手之前先把当前工作区存一份补丁（全部改动都没有提交，删掉之后无法从 git 找回）。**维护者还没有说开始改。**

### 7.42 轻量方案已实现：只留勾选框（2026-11-23）

维护者对 7.41 四点细节的答复（原话见计划文件第十三节）：勾选框右边保留一个问号，点击查看功能详情；勾选在“更改游玩偏好”里，开启后打开登记菜单要一眼能看出来，并写明怎样取消；勾选框文字用“允许系统在 机台 A 和 机台 B 之间安排登记”，因为“调整可能是来回的”；已有的登记也可以勾选，但下一轮才生效。

**做了什么**（细节、全部文字、待批准的文字对照表都在计划文件第十三节）：

- 动手前保存了工作区：`tmp/claude-work/snapshots/2026-11-23-before-lightweight/`。
- 引擎：为等待中的登记开启时，同时设置 `skipsPairedMachineThisRound`，游玩一轮后才取得另一机台的位置。对照测试第 4 种随之更新。
- 终端：删除玩家资料里的选项及其全部配套（编辑页、补全资料、介绍记录、云端同步、手机登记命令里的资料设置）；所有登记在确认页的本次游玩偏好下方用同一个勾选框；勾选框旁加问号，点击打开说明（无倒计时）；登记菜单里独立的一行删除，勾选框移入“更改游玩偏好”，单人机台属于一对机台时也显示这一项；登记菜单顶部加一行状态小字。
- 远程创建登记只看请求里的勾选，不再参考资料。
- 服务器与网页：由并行的子任务按同一套约定和文字修改，我复核了改动并重跑了测试和构建。服务器删除了资料的两列及全部相关接口处理；网页新增 `PairedQueueTickBox.vue`，用在线上加入、手机登记页和两处本人登记的偏好面板。子任务自己定的四处见计划文件第十三节。
- 协议文档 `docs/cloud-queue-sync.md` 的“同时在两台机台排队”一节已按新做法重写。

**还没做、等维护者决定的：**

1. 其余仍带“同时在两台机台排队”说法的文字（终端与管理端约 45 处，另加服务器和网页），对照表在计划文件第十三节，批准后统一改。
2. 管理端的勾选是否也移进它的游玩偏好窗口。
3. 暂缓一次、暂时离开是否也改成“游玩一轮后才恢复”（7.40 留下的问题，仍未答复）。

**没有验证的部分**：终端、管理端的新界面没有在设备或模拟器上看过；网页没有在浏览器里看过。

本节新增的操作：

104. **保存工作区补丁**：`tmp/claude-work/snapshots/2026-11-23-before-lightweight/`。
105. **改动正式代码**：`queue-core` 的 `QueueEngine.kt`、`QueueModels.kt`；`app` 的 `PlayerProfiles.kt`、`LocalPlayerProfileRepository.kt`、`PlayerProfilePersistence.kt`、`QueueUiState.kt`、`AuditLogs.kt`、`MobileDeviceRegistration.kt`、`RemoteQueueOperations.kt`、`ManagementQueueActions.kt`、`QueueCloudSync.kt`、`QueueTerminalUi.kt`、`PairedMachineQueueNotices.kt`。
106. **改动测试**：`PairedMachineQueueTest.kt`、`SharedQueueEquivalenceTest.kt`、`PlayerProfilePersistenceTest.kt`、`PlayerProfilesTest.kt`、`QueueCloudSnapshotTest.kt`、`QueueCloudCommandTest.kt`、`MobileDeviceRegistrationTest.kt`、`RemoteQueueOperationsTest.kt`、`PairedMachineQueueNoticesTest.kt`。
107. **改动文档**：`docs/user-manual.md`（“玩家怎样选择”一节重写）、计划文件第十三节。
108. **改动服务器与网页**：`cloud-server/app.py`、`cloud-server/test_app.py`；`public-site/src/queue/` 下的 `pairedQueueCopy.js`、`PairedQueueIntroduction.vue`、`PairedQueueTickBox.vue`（新增）、`MobileProfileCreationFlow.vue`、`MobileRegistrationFlow.vue`、`QueueStatusPanel.vue`、`PlayerAccountDialog.vue`。
109. **改动文档**：`docs/cloud-queue-sync.md`。
110. **运行测试**：`queue-core` 134 项、`app` 三个变体各 452 项、服务器 205 项、网页构建，全部通过。没有提交，没有改版本号。

### 7.43 维护者看过 7.42 后的答复，文案进入专项设计（2026-11-23，讨论中，未修改）

维护者原话：

- 对“网页创建资料页、手机登记页、账户设置里也一并删除”：“使用移动设备登记时应该有。”（是误会：手机登记页的确认步骤里有勾选框，删掉的是资料层面的三选一。已向维护者说明。）
- 勾选框：“允许系统在此机台和 机台 B 之间安排登记，我想这样写，前面是此机台，后面是另一机台。”
- 勾选框下方小字：“开启后，登记将自动转至能够更早安排这份登记游玩的机台。”
- 登记菜单里的状态小字：“已允许系统在 机台 A 和 机台 B 之间安排登记，如果 机台 B 能更早安排这份登记游玩，将显示预览。可以随时在‘更改游玩偏好’中取消。”
- 说明弹窗：“临时登记和每个玩家资料第一次使用这个功能时，弹窗显示提示，五秒才能关闭，内容与点击问号的内容一致，待会我们设计。”**这推翻了我在 7.42 的理解**（我以为只保留问号）：自动弹出要恢复，问号同时保留。
- “修改游玩偏好”与“更改游玩偏好”：“应该叫更改吧，都改。”
- 其余旧说法：“改，我们对文案进行专项设计。”
- 最后：“跟我讨论修改方案。”所以这一轮只讨论，没有改任何东西。

核对到的事实：

- “此机台”在现有文字里一次也没有出现。现有的说法是“当前机台”（终端 23 处、手册 7 处）、“该机台”（终端 10 处）、“这台机台”（终端 7 处）。
- “修改游玩偏好”共 13 处（终端 2、网页 6、服务器 1、Bot 4），“修改本次游玩偏好”共 16 处（终端 7、网页 4、Bot 2、手册 3），“游玩偏好已修改”2 处（终端日志标题）。Bot 里“修改游玩偏好”是玩家输入的指令别名，改名时旧词要保留为不显示的别名。
- 另一机台的预览（灰色条目）不受“显示共同游玩预览”这个设置控制，总会计算和显示。
- 恢复“每份资料第一次”的弹窗，需要把今天删掉的“已看过说明”的记录加回来（终端资料字段、云端同步、服务器的列）。删除前的版本在 `tmp/claude-work/snapshots/2026-11-23-before-lightweight/`。

我向维护者提出的方案（等答复）：

1. 文案专项设计分三步：先定词表（约九个词），再定每类文字的句式，最后我出全部文字的前后对照表，一次审完再改，并加一项测试禁止旧说法出现在玩家可见的文字里。
2. 指示词建议用“当前机台”而不是“此机台”：与现有文字一致，而且登记转到另一台之后“当前”仍然成立。
3. 这个勾选框的动词统一用“开启 / 取消”。
4. 小字建议写明条件：“开启后，另一机台能够更早安排这份登记游玩时，登记将自动转至另一机台。”
5. 状态小字建议拆成三句，并写明预览显示在哪里；处于“游玩一轮之前不安排”状态时，把预览那一句换成说明这一状态的句子。
6. 首次弹窗：开启的那一刻弹出；记录存在玩家资料里并经服务器同步；网页和手机页由服务器在收到开启的请求时记下；Bot 和管理端代为操作时不弹也不记；手动点问号不倒计时。
7. “更改游玩偏好”：三种说法一并改为“更改”，Bot 保留旧别名。

**维护者的答复（同日）**：指示词用“当前机台”——“可以”；小字——“这还没有表达出会来回的意思啊……有问题吗，加一句如果符合条件”；状态小字里第二个“机台 B 的”改成“其”——“其等待顺序，不要出现两次吧”；改名——“改”；其余——“都可以，定词表吧”。

据此这一轮做了两件事：

- **已改**：“修改游玩偏好”的三种说法统一改为“更改”，共 61 处，Bot 保留旧别名。明细在计划文件第十四节。
- **词表草案**：十三个词，在计划文件第十四节，等维护者定稿。定稿后进入第二步（每类文字的句式和说明弹窗的正文）。

首次弹窗的规则维护者已经同意（“都可以”），但按约定在文字定稿之后再实现。

本节新增的操作：

111. **改动文字**：“修改游玩偏好”改为“更改游玩偏好”等，共 61 处，涉及 `app`、`public-site`、`cloud-server`、`koishi-bot`、`docs/user-manual.md`、`docs/cloud-queue-sync.md`、`README.md`、`koishi-bot/README.md`。
112. **运行测试**：`app` 三个变体各 452 项、服务器 205 项、Bot 80 项、网页构建，全部通过。没有提交。

**词表定稿（同日）**：维护者回复“定稿”。十二个词已写入 `docs/copywriting-guide.md` 的“用词”一节（新增小节“系统在两台机台之间安排登记”），原来那一行“同时在两台机台排队……已定”删除；“修改 / 更改”一行改为：游玩偏好用“更改”，其余对象仍用“修改”。

**第二步进行中**：十二类文字的句式和说明弹窗正文的草案在计划文件第十四节，等维护者确认。确认后进入第三步（全部文字的前后对照表），全部定稿后再实现首次弹窗和统一替换。

113. **改动文档**：`docs/copywriting-guide.md`（词表）、计划文件第十四节（句式草案、弹窗正文草案）。没有改代码。

**第二步修订（同日）**：维护者不喜欢“系统安排”这个短写，并提议把“系统”都改成“自动”（“自动在两台机台之间安排登记”），问我的意见。我赞成改成“自动”，但建议不设短写：现有文字里“自动安排”已经指自动选出下一组（“不会自动安排下一组”）。其余修订：“固定组合只能在当前机台游玩”；为等待中的登记开启时，小字是常规那句加一句“这份登记游玩一轮之前，不会被安排到另一机台。”；修改与更改各有固定对象，不需要只留一个。修订后的词表两行已写入文案规范，修订后的句式和弹窗正文在计划文件第十四节“第二步修订”，等维护者最后确认。

114. **改动文档**：`docs/copywriting-guide.md`（词表两行、修改与更改的说明）、计划文件第十四节。没有改代码。

**第二步再修订（同日）**：维护者觉得勾选框里用“自动”没有指明是谁来安排，问能否改回“系统”、是否违背统一性，或者两种说法都保留。我的意见：两个词都保留，各管一件事——“系统”只作“安排”的主语，“自动”只作“转至”的状语；同一个位置不混用，所以不算违背统一。我上一轮同意“都改成自动”是答应得太快，已向维护者说明。文案规范的词表已按此改写（“这件事本身”一行改回“系统”，新增“登记怎样移动：自动转至”一行），全部句式在计划文件第十四节“第二步再修订”，等维护者确认。

115. **改动文档**：`docs/copywriting-guide.md`、计划文件第十四节。没有改代码。

**第二步定稿，第三步交审（同日）**：维护者回复“定下来”。我清点了终端、管理端、网页、服务器和文档里全部相关文字，按定稿的词表和句式写成前后对照表 `docs/releases/dual-machine-queue-copy.md`（74 条，12 条标 ★ 为我自己拿主意的地方），等维护者审阅。表中的修改都还没有做。

接手时注意：对照表确认之前不要动这些文字；确认之后按计划文件第十四节末尾的顺序实施，首次弹窗需要把“已看过说明”的记录加回来（删除前的实现保存在 `tmp/claude-work/snapshots/2026-11-23-before-lightweight/`）。

116. **新增文档**：`docs/releases/dual-machine-queue-copy.md`。没有改代码。

**称呼功能时的写法（同日，维护者补充）**：需要称呼这项功能时叫“自动在两台机台之间安排登记”；前面有“允许”时写“允许系统在……之间安排登记”，因为那是在描述内容而不是称呼功能。我赞成。文案规范的词表改成三行（称呼功能、描述内容、短写不设），对照表第 10、71、74 条随之更新。对照表整体仍在等维护者审阅，表中的修改都还没有做。

117. **改动文档**：`docs/copywriting-guide.md`、`docs/releases/dual-machine-queue-copy.md`、计划文件第十四节。没有改代码。

### 7.44 对照表落实，首次弹窗恢复（2026-11-24）

维护者对对照表回复“可以，开始改”。细节在计划文件第十四节“第三步落实”，这里只记要点：

- **首次弹窗**：玩家资料加回“已看过说明”的记录（`paired_machine_queueing_introduced`，只会从否变为是；终端只在为真时上传；服务器和终端都按“或”合并）。开启勾选框时，临时登记每次、每份玩家资料第一次自动弹出说明，五秒后才能关闭；问号打开的不倒计时。网页和手机登记页同样处理，服务器在接受它们带开启的请求时记为已看过；Bot 和管理端不弹也不记。
- **文字**：对照表 74 条全部落实，手册这一章整章重写，协议文档的节标题和引用改为“自动在两台机台之间安排登记”。新增终端和服务器各一项测试，旧说法再出现时会失败。
- **服务器与网页**仍由并行的子任务完成，我复核了改动、重跑了测试，并在浏览器里实际操作了网页。
- **演示已重建并在运行**：`demo-queue-server`（8791）和 `demo-public-site`（5183，预览 `tmp/claude-work/demo/site` 里的构建），保活任务运行三小时。演示数据脚本 `tmp/claude-work/demo/seed.py`、`setup.py`、`keepalive.py` 已改成新做法：三份资料（12345678 未看过说明，22223333 已看过，33334444 有网页账户、密码见 `setup.py`，其登记已开启并处于“游玩一轮之前不安排”的状态）。`.claude/launch.json` 仍在仓库里，演示结束后要删除。
- **测试**：`queue-core` 134 项，`app` 三个变体各 456 项，服务器 214 项，Bot 80 项，网页构建通过。

等维护者决定的三件事：“不会修改玩家资料中的默认游玩偏好”这类句子用“修改”还是“更改”（共 14 处）；管理端的开关是否移进偏好窗口；暂缓一次、暂时离开是否也改成游玩一轮之后才恢复。

没有验证：终端和管理端的界面仍未在设备上看过；网页有三种情形演示数据里没有（见计划文件）。

本节新增的操作：

118. **改动正式代码**：`app` 的 `PlayerProfiles.kt`、`LocalPlayerProfileRepository.kt`、`QueueCloudSync.kt`、`PlayerProfilePersistence.kt`、`RemoteQueueOperations.kt`、`MobileDeviceRegistration.kt`、`ManagementQueueActions.kt`、`ManagementUi.kt`、`ManagementSettingsUi.kt`、`AuditLogs.kt`、`QueueTerminalUi.kt`、`PairedMachineQueueNotices.kt`；`cloud-server/app.py`；`public-site/src/queue/` 下的 `pairedQueueCopy.js`、`PairedQueueIntroduction.vue`、`PairedQueueTickBox.vue`、`QueueStatusPanel.vue`、`PlayerAccountDialog.vue`、`MobileRegistrationFlow.vue`。
119. **改动测试**：`PairedMachineQueueNoticesTest.kt`（重写，含禁止旧说法的检查）、`PlayerProfilePersistenceTest.kt`、`RemoteQueueOperationsTest.kt`、`MobileDeviceRegistrationTest.kt`、`QueueCloudSnapshotTest.kt`、`cloud-server/test_app.py`。
120. **改动文档**：`docs/user-manual.md`、`docs/cloud-queue-sync.md`、`docs/copywriting-guide.md`、`docs/releases/dual-machine-queue-copy.md`、计划文件第十四节。
121. **启动本机演示**：删除旧的演示数据库，重新构建网页，启动两个演示服务器和保活任务，用演示账户登录并在浏览器里检查。没有提交，没有改版本号。
122. **按维护者看演示后的要求修改**：预览说明窗口的正文改为每句一行（“这里的文案希望都在句号后换行”）。网页在 `pairedQueueCopy.js` 里用换行连接三句，并给 `.queue-detail-explanation` 加 `white-space: pre-line`；终端在同一个 `Text` 里用换行。已在演示里确认网页的显示，终端未在设备上看过。
123. **改正“允许”后面缺“系统”的文字**：维护者指着网页登记详情里的标签说“这里已经说过了，前面有允许后面应该有‘系统’”。原因是我在词表里给自己留了一个“位置紧张时省去主语”的短写，与已经定下的规则冲突。改正的有：网页标签、终端的两个提示标题、管理端的标签、菜单和操作原因、管理端操作结果、服务器的一条校验报错，全部补上“系统”；管理端“这一轮不安排”的标签改为“游玩一轮之后由系统在两台机台之间安排”。“系统规则不允许……”一句改为接功能的名称：“系统规则不允许自动在两台机台之间安排登记”，与现有的“系统规则不允许暂缓一次”同一句式（这一处是我的判断，已告知维护者）。文案规范里的“短写”一行改为“允许之后不能省去系统”，终端和服务器的两项检查都加了一条，以后“允许在两台机台之间”这种写法会让测试失败。对照表的相关各条已同步。测试：`app` 三个变体各 456 项、服务器 214 项通过，网页构建通过，并在演示里确认了标签。

### 7.45 维护者问：还能不能收紧使用场景；以及之后的两项待办（2026-11-24，讨论中，未修改）

维护者原话：“我们还能收紧这个功能的使用场景吗，考虑实际应用中这个功能会不会出问题。之后我们的待办是：审查软件内各处文案是否统一、用词是否能够理解；检查现有的排队逻辑漏洞。”

**之后的两项待办（维护者指定）**

1. 审查软件内各处文案是否统一、用词是否能够理解。（此前暂停的文案统一工作可以并入：见记忆 `copy-unification-task` 和 `docs/copywriting-guide.md` 的“尚未决定”。）
2. 检查现有的排队逻辑漏洞。

我向维护者建议先查逻辑、再审文案，因为逻辑一改，文字要跟着改；顺序由维护者定。

**核对到的事实**

- 玩家资料里“进入游玩位置”的通知默认是关闭的（`QueueNotificationPreferences.playingPosition = false`），“队列变化”的通知默认开启。登记被转至另一机台时，Bot 实际会不会发消息、发的是哪一类，我没有核实，列入第 2 项待办。
- 登记被转至另一机台时，它离开原机台的等待顺序。之后在另一机台被标记未到场，按那台机台的未到场规则处理（暂缓、移至队尾或移除），原机台的先后已经没有了，并且会记一次未到场。
- 现在另一机台即使有自己的登记在等，也会按“开始等待的先后”接收对面的登记，也就是对面的登记可以排在本机台后来的登记前面。

**我认为实际使用中最可能出的问题**

1. 被安排到另一台时人不在：机台空等，玩家被记未到场，还丢了原机台的先后。这是系统替他挪的，后果却由他承担。
2. 另一台正在等的玩家觉得被插队：对面的登记比他先玩。系统的依据是“开始等待的先后”，但现场的人看到的是“他排的明明是另一台”。这也是维护者说的“双机排队本身不符合规则”。
3. 一份登记被反复挪来挪去：开启后每一轮都可能被安排，玩家不知道下一轮在哪台。
4. 临时登记没有任何通知渠道。

**我提出的收紧方案（等维护者决定）**

- 建议采用：
  - 甲、另一机台有自己的登记在等时不安排，只在另一机台没有人等待时才转过去（只补空位）。代价：两边都有人排但长短不一时不再平衡，这项功能主要作用变成“不让机台空着”。
  - 乙、被安排过去后未到场：不记未到场，自动取消这项选择，登记回到原机台原来的先后。需要在转至时记下它在原机台的位置。
  - 丙、每次开启只安排一次：被安排一次之后这项选择自动取消，想再被安排需要重新开启。
- 可选：
  - 丁、临时登记不能开启。
  - 戊、开启时提示打开“进入游玩位置”的通知。
- 一个需要一并考虑的情形：机台停止使用时，采用甲之后，那台机台上的登记只有在另一台没人等时才会被接收；忙的时候仍要工作人员手动转至。

### 7.46 维护者提出“先预告、再安排”的规则（2026-11-24，讨论中，未修改）

维护者没有采用 7.45 的甲、乙、丙，而是提出了另一套做法。原话：

- “如果被系统安排，首先需要确保当前队列正在被正常维护，可以设置以下原则：正式被系统安排到另一机台前，必须按照时间规则显示预览，并且预览持续显示至少15分钟（中间断了就重新计算吧，这只是我的想法，草稿，你可以有其他补充或者修改），而且这个预览不要直接比大小了，在时间差大于3分钟的情况下再显示，因为系统估计时间本来就有误差，三分钟已经不长了。这只是我的想法，请你补充。”
- “给临时登记禁用这个功能吧。”
- “如果预览显示持续5分钟（我们也说了预览显示15分钟才会被转移安排），就发送私信。”
- “我还希望在开了系统自动安排之后，仍然允许玩家手动转至其他机台，如果手动转至，同时排到两机队尾就好了。可以有文字提示（告诉他如果不手动转，反而有可能更快在另一机台游玩，但也不是必须提示，我的想法）。”
- “另外，估计时间也会受影响，这个算法我们后续讨论，原则是尽量显示更短的时间，给玩家提供充分的准备时间（但也不要极端只显示最小值，应该智能一点），保守设计的话就显示一个范围。”

**我整理的规则清单（维护者的部分）**

1. 预览的条件：另一机台预计比当前机台早 3 分钟以上，才显示预览。
2. 安排的条件：预览连续显示满 15 分钟之后，另一机台轮到这份登记时才会转过去；中途预览消失就重新计时。
3. 预览连续显示满 5 分钟时，给玩家发私信。
4. 临时登记不能开启。**已定。**
5. 已开启的登记仍可以手动转至另一机台，转过去之后排在两台机台的队尾；可以提示“不手动转也可能更快在另一机台游玩”。
6. 预计等待时间的算法以后再谈；原则是偏向显示较短的时间，保守的做法是显示一个范围。

这套做法把“主动”和“被动”分开了：想马上换机台的人自己手动转；系统的安排一定提前至少 15 分钟预告。它取代了我在 7.45 提的甲（只补空位）。有了预告和私信，乙（未到场不算玩家的）和丙（每次开启只安排一次）我认为可以不做。

**我的补充和修改（等维护者确认）**

- 补充一、开和关用两个门槛。预览在“早 3 分钟以上”时出现，但只有在另一机台“不再更早”时才算中断。否则每结束一轮预计时间都会跳一下，15 分钟永远凑不满，5 分钟的私信也会反复发。
- 补充二、“队列正在被正常维护”的判定，我的理解：两台机台都在正常使用，并且两台的本轮都没有超时（现有的超时提醒是 20 分钟）。有一台本轮超时时，它的预计时间不可信，预览的计时暂停，不安排。机台停止使用时，把它当作“等不到”，它上面已开启的登记都会在另一台出现预览，15 分钟后开始陆续转过去。
- 补充三、不设例外。现在创建登记时如果另一台正空着，登记会立刻被转过去；按新规则这也要等 15 分钟。我建议不为它开例外：选机台的页面本来就显示每台的预计时间，玩家会直接选空的那台。这样规则只有一句话：系统的安排一定提前至少 15 分钟预告。
- 补充四、私信的细节。同一份登记在一次等待里只发一次预告；真正转过去时再发一次。玩家没有加 Bot 好友或关了通知时照常安排，但勾选框下提示打开通知。
- 补充五、让玩家看到时间。登记菜单的状态小字里写明“最早 hh:mm 之后可能被安排到 机台 B”。
- 补充六、三个数字（3、5、15 分钟）先写成固定值，不做成设置项。
- 需要说明的代价：另一台突然空出来时，最长会空 15 分钟才有人被安排过去；想马上玩的人可以手动转至。

**实现上的提醒**

- 引擎现在的安排只看先后，不看时间。新规则要引擎记住每份登记“预览从什么时候开始连续显示”，并在轮到时检查是否满 15 分钟。这是状态，要保存、同步、可撤销。
- 预览本身仍按不加门槛的推算决定（否则预览和安排会互相依赖），再加 3 分钟的差值。
- 私信需要终端发出一种新的事件，经服务器到 Bot。Bot 计划更换，这一段要尽量小。
- 对照测试（与“共用一条队”比较）里，门槛设为 0 时结果应与现在相同，这样已有的测试继续有效。

### 7.47 维护者对 7.45 甲、乙、丙的答复，规则清单合并（2026-11-24，讨论中，未修改）

维护者原话：

- 对乙（在另一台未到场不算玩家的）：“可以，未到场后直接回到原机台队列，并关闭他的自动安排功能，需要他手动再打开。这也提醒我，在这个功能的介绍中需要加入提醒玩家合理安排时间的内容。”
- 对甲（只补空位）：“这不是我想要的，如果另一机台全空，玩家想玩自然会自己转至另一机台。”
- 对丙（每次开启只安排一次）：“这个可以考虑。”

我在 7.46 里说过乙、丙可以不做，这个判断收回：乙已被采用；丙我建议也采用，理由是与乙合成同一句话——**系统每安排一次，这项选择就自动关闭，不论玩家到没到场**，想再被安排需要重新开启。

**合并后的规则清单（等维护者一并确认；标“已定”的是维护者明确说过的）**

1. 预览的条件：另一机台预计比当前机台早 3 分钟以上才显示预览。（维护者提出）
2. 安排的条件：预览连续显示满 15 分钟后，另一机台轮到这份登记时才转过去；中途中断重新计时。（维护者提出）
3. 预览连续显示满 5 分钟时发私信。（维护者提出）
4. 临时登记不能开启。（已定）
5. 已开启的登记仍可以手动转至另一机台，转过去后排在两台机台的队尾。（维护者提出）
6. 被安排过去后未到场：不记未到场，登记回到原机台原来的先后，这项选择自动关闭，需要玩家手动再开启。（已定）
7. 被安排过去并正常游玩后：这项选择同样自动关闭，登记留在游玩的那台机台。（我的建议，维护者说“可以考虑”）
8. 说明里加入提醒玩家合理安排时间的内容。（已定，文字待写）
9. 预计等待时间的算法以后再谈。

7.46 里我的六点补充仍然有效，维护者尚未回应：开和关用两个门槛；“正常维护”按“两台都在正常使用且本轮都未超时”判定；创建登记时不设例外；私信一次等待只发一次、真正转过去时再发一次；状态小字写明最早何时可能被安排；三个数字先写成固定值。

**第 6 条我打算怎样做（等确认）**

- 登记被转至另一机台时，记下它在原机台等待顺序里排在它前面的登记。未到场时，把它放回这些登记里仍在等待的最后一位之后；它们都已经不在等待顺序里时，放回队首。
- 这条记录只在登记处于另一机台的游玩位置期间保留，正常游玩结束后清除。
- 工作人员在另一机台标记这份登记未到场时，不再出现“暂缓一次 / 移至队尾 / 移除”的选择，只有这一种结果，并在确认窗口里写明。
- 原机台此时已经停止使用、登记已满或不再属于一对机台时回不去，按另一机台的普通未到场规则处理。
- 玩家本人在游玩位置上主动选择暂缓一次不属于未到场，仍按原规则。

**第 8 条的草稿**

> 开启后，这份登记可能比当前机台的预计时间更早被安排游玩，请合理安排时间。

说明弹窗的正文要在规则定下来之后整体重写：预览、15 分钟、私信、未到场回到原机台、安排一次后自动关闭、临时登记不能开启，都要写进去。

### 7.48 规则定稿，第一批已实现（2026-11-24）

维护者对 7.46、7.47 的答复（原话）：

- 两个门槛：“对对对。那就把早1分钟算得不算更早。”
- 六点补充：“我同意。但是有的数字，比如3分钟，不需要跟玩家说明，模糊表述即可。”
- 对我草稿里的“不手动转至”：“真正写提示可别用这种词，转至后面必须有宾语。”（已写入文案规范。）
- 丙：“丙不开启了，如果之后我实测不稳定的话再改，反正这个很好改。”

规则至此定稿，完整的规则说明在计划文件第十五节。实现分三批，第一批已完成：

- 临时登记不能开启（排队核心、终端、管理端、远程操作、服务器）。
- 已开启的登记可以手动转至另一机台，转过去后排在该机台的队尾并保持开启；转至其他机台时取消。
- 用户手册这一章按这两点改了三处。
- 测试：`queue-core` 135 项，`app` 三个变体各 456 项，服务器 214 项，全部通过。网页没有需要改的地方。

**接下来**：第二批（排队核心：预览计时、满 15 分钟才安排、正常维护、未到场回到原机台）和第三批（终端、同步、私信、说明文字、服务器、网页、文档）。第二批开始前，请维护者过目计划文件第十五节，尤其是第 4 条（推算的口径）、第 5 条（不满 15 分钟时跳过）、第 7 条（正常维护）、第 10 条（回到原机台的位置），这几条里有我对规则的具体化。

之后的待办仍是维护者指定的两项：审查文案、检查排队逻辑漏洞。

本节新增的操作：

124. **改动正式代码**：`queue-core` 的 `QueueEngine.kt`、`PairedMachineQueues.kt`；`app` 的 `QueueTerminalUi.kt`、`RemoteQueueOperations.kt`、`ManagementQueueActions.kt`、`ManagementUi.kt`；`cloud-server/app.py`。
125. **改动测试**：`PairedMachineQueueTest.kt`、`RemoteQueueOperationsTest.kt`、`QueueCloudSnapshotTest.kt`、`cloud-server/test_app.py`。
126. **改动文档**：`docs/user-manual.md`、`docs/copywriting-guide.md`、计划文件第十五节（新增）。没有提交。

### 7.49 第二批已完成：排队核心支持“先预告、再安排”（2026-11-24）

维护者的答复：“15分钟改成12分钟吧”“开始第二批”。期间还指出说明弹窗里一句话看不懂、太口语化：“改成描述‘这个功能不会关闭’之类的意思”“登记将保留在上次游玩的机台。这里不用‘转至’，因为转至行为已经结束了。”——已改为“游玩一轮之后，登记将保留在上次游玩的机台，这项功能保持开启。”

第二批只改了排队核心，细节在计划文件第十五节“第二批的细节”。要点：

- 规则是参数（`QueueEnginePolicy.pairedArrangementRules`），**终端还没有传它，所以现场行为没有变**。第三批接入后才生效。
- 预览的计时等记录放在 `QueueEngineState.pairedArrangements`，不放在登记里，避免定时刷新让打开着的确认窗口失效。第三批要像保存 `pairedMachineOrders` 那样保存和恢复它。
- 新入口 `QueueEngine.refreshPairedArrangements(state, policy, durations, nowMillis)`：终端要在每次队列变化之后和定时调用，并把返回的 `notices` 变成私信。
- `PairedMachineForecast.forecast` 在启用规则时自动遵守门槛，终端现有的预览和预计时间的计算不用另写，但预览应改为以 `pairedArrangements` 里有 `previewSinceMillis` 的登记为准。
- 测试：`queue-core` 144 项（新增 9 项），`app` 三个变体各 456 项，服务器 214 项，全部通过。

**第三批要做的**（未开始）：

1. 终端：把规则参数传给引擎；保存和恢复 `pairedArrangements`；定时刷新；预览以记录为准；状态小字写明最早何时可能被安排；被安排的登记未到场时的确认窗口只有“回到原机台”一种结果；转至其他机台的确认里加提示；`notices` 和实际转至各发一条私信（走现有的公开事件，尽量不改 Bot）。
2. 说明弹窗正文整体重写（预览、12 分钟、私信、未到场回到原机台、临时登记不能开启、合理安排时间），先给维护者看文字。
3. 服务器和网页：快照里新增“最早何时可能被安排”，网页的状态小字和预览跟随；协议文档、用户手册、对照表同步。
4. 预计等待时间怎样显示，维护者说以后再谈。

之后仍是维护者指定的两项待办：审查文案、检查排队逻辑漏洞。

本节新增的操作：

127. **改动正式代码**：`queue-core` 的 `QueueEngine.kt`、`PairedMachineQueues.kt`、`PairedMachineForecast.kt`，新增 `PairedArrangementAnnouncements.kt`；`app` 的 `PairedMachineQueueNotices.kt`、`public-site/src/queue/pairedQueueCopy.js`（只改一句文字）。
128. **新增测试**：`PairedArrangementAnnouncementTest.kt`。
129. **改动文档**：计划文件第十五节（15 分钟改为 12 分钟，新增第二批的细节）、`docs/user-manual.md`、`docs/releases/dual-machine-queue-copy.md`（同一句文字）。没有提交。
130. **新增文档**：`docs/automatic-machine-arrangement.md`。维护者要求“给我一份完整的这个功能的机制，介绍给别人。包括内部算法和规则”。文档面向没有读过代码的人，包含背景、设计原则、玩家看到的界面、内部的合并顺序表与先后、预览的判定、安排的门槛、之后的处理、一个按时间展开的例子、公平性、参数、对外与内部用词对照，以及截至当日的实现状态（预告规则在排队核心已实现但尚未接入终端）。规则有变动时这份文档要同步更新。

### 7.50 第三批：规则已接入终端（2026-11-24）

维护者说“开始第三批”。这一节之后，终端的实际行为变了：**登记不再一被另一机台选到就转过去，必须先有预览并连续显示满 12 分钟。** 细节在计划文件第十五节“第三批的细节”，机制说明文档 `docs/automatic-machine-arrangement.md` 已同步，新增和改写的文字在 `docs/releases/dual-machine-queue-copy.md` 第十三节（编号 75 至 97，待维护者过目）。

**做了什么（终端）**

- 引擎的规则参数在终端、远程操作和上传快照三处启用。
- 预览的计时记录 `pairedArrangements` 随队列一起保存、恢复、撤销，并带进带出远程操作的三条路径。
- 每 30 秒以及每次队列变化之后刷新一次预览与计时；空着的机台在预告到时间时接收登记。
- 三条私信：预览满 5 分钟、被安排过去、未到场回到原机台。做法是终端写带公开事件的日志，Bot 按现有方式把日志的标题和正文发给玩家，**Bot 没有改动**。
- 登记菜单的状态小字写明最早的时刻；被安排过来的登记点“未到场”时只有“回到原机台”一个结果；手动转至其他机台的确认里加了提示；说明弹窗正文重写为七段。
- 管理端：未到场处理的窗口加了一句一般性的说明（管理端不知道哪份登记是被安排过来的）。

**思考摘要**

- 私信怎么发。先查了 Bot 实际发什么：`formatQueueNotification` 发的是“【排队通知】＋日志标题＋日志正文＋玩家当前的位置”，收件人是日志的 `affectedRegistrationKeys` 对应的玩家，是否发送看玩家对该事件类别的通知偏好。所以不需要新协议，写对日志即可。被安排过去的那条原来只归“排队变化”一类，现在同时归“进入游玩位置”，两类里开了任意一类都能收到。
- 预览以什么为准。启用规则后，推算只会把“有预览记录”的登记算到另一台，所以界面上的预览仍旧用推算的结果，不必另写一套；状态小字和快照里的“最早时间”直接读记录。两者最多相差一次刷新。
- 终端重新启动。停机期间没有人维护预览，按规则不该计时，所以恢复时把计时顺延。停机时长只能用“上一次保存的时间”估计，会偏长，结果只会让安排更晚。
- 未到场的日志。登记从 B 的游玩位置回到 A 的等待顺序，原有的日志比较会把它写成 A 的“顺序变化”和 B 的“未到场”。我给它单独一条“登记已回到机台 A”，并让比较基准把它当作已经在 A，两台机台不再各写一条。为了能测，把比较基准从界面代码里移了出来。
- 说明里写不写 12 分钟。维护者说过 3 分钟这类数字不必告诉玩家。我把 12 分钟写了出来，因为它是对玩家的承诺；其余四个数字不出现，有测试守着。这是我的判断，维护者可以推翻。

**需要维护者知道的三件事**

1. 新文字（对照表第十三节）是我按词表和现有句式写的，没有逐条问过，请过目。其中“至少 12 分钟”“随时可能”“未到场记录不会增加”三处是我的选择。
2. `QueueTerminalUi.kt` 里处理远程命令的大循环编译后顶到了单个方法的大小上限，这次多传一个参数就编译失败。我拆出了一段（`applyManagementQueueResult`）腾出余量，行为不变。以后往那里加代码要继续往外拆。
3. 三条私信的文字没有经过真实 Bot 发送，终端和管理端的新界面没有在设备上看过。依据是代码和测试。

**没有做的**

- 预计等待时间怎样显示（维护者说以后再谈）。目前显示两台机台里较早的推算。
- 管理端没有显示“已预告”和“最早时间”。

**服务器与网页（由并行的子任务完成，我重跑了测试并在浏览器里核对）**

- 协议：每份等待中的登记可以带两个新字段，`paired_arrangement_announced`（布尔，只在为真时发送：另一机台的预览正在预告）和 `paired_arrangement_not_before`（毫秒时间戳，只在计时进行中发送：最早何时可能被安排）。
- 服务器：上传时校验这两个字段，格式不对拒绝上传；登记没有开启这项选择时两个字段被静默丢弃而不是拒绝（子任务的选择，理由是终端上一条过期记录不应挡住整份快照）。公开的排队状态、玩家本人的排队和管理后台概览都带出这两个字段；Bot 的接口本来就不带这一类字段，没有动。服务器没有“加入时立即转至”的文字，无需修改。
- 网页：说明弹窗七段、状态小字三种新情形、预览说明四行、转至其他机台的提示，逐字与终端一致（子任务用脚本逐句比对过）。时间按浏览器本地的 HH:mm 显示。
- 协议文档补了这两个字段，并改正了几处已经不成立的说法（另一机台空着时立即转至、手动转至另一机台被拒绝等）。
- 我在本地演示环境里用浏览器看了：预览说明（四行）、登录账户后本人登记的状态小字（含“最早 02:58 之后”）、转至其他机台的提示、说明弹窗（七段）。“时间已过”和“计时暂停”两种状态小字没有在浏览器里看，只有脚本比对。

**子任务发现、我核实并修正的一个漏洞**

已开启的登记被手动转至另一机台之后，原先那条预览的计时记录没有清掉。那条记录预告的是“从 A 转到 B”，登记到了 B 之后却被当成“从 B 转回 A”的预告：如果它已经满 12 分钟，A 一轮到它就会立刻把它安排回去，没有任何预告。我先写了一项能复现的测试（失败），再在 `applyTransfer` 里清掉被转登记的记录（通过）。手动转至之后，预告从头开始。

**测试**：`queue-core` 150 项，`app` 三个变体各 466 项，服务器 218 项，Bot 80 项，全部通过；网页可以构建。

本节新增的操作：

131. **改动正式代码（排队核心）**：`QueueEngine.kt`（`RestoreSnapshot.restoredPairedArrangements`、`pairedArrangementOutlook`、`absentArrivalOrigins`、`pairedArrangementsAfterDowntime`、`PairedArrangementOutlook`）。
132. **改动正式代码（应用）**：`QueueTerminalUi.kt`、`QueueUiState.kt`、`LocalQueueStateRepository.kt`、`RemoteQueueOperations.kt`、`ManagementQueueActions.kt`、`MobileDeviceRegistration.kt`、`QueueCloudSync.kt`、`AuditLogs.kt`、`PairedMachineQueueNotices.kt`、`ManagementUi.kt`。
133. **改动测试**：`PairedArrangementAnnouncementTest.kt`（新增 5 项）、`PairedMachineQueueNoticesTest.kt`、`AuditLogsTest.kt`、`QueueStatePersistenceTest.kt`、`QueueCloudSnapshotTest.kt`、`RemoteQueueOperationsTest.kt`、`MobileDeviceRegistrationTest.kt`。其中四项原先按“立即安排”写的测试改成了新规则下的结果。
134. **改动文档**：`docs/automatic-machine-arrangement.md`、计划文件第十五节、`docs/user-manual.md`、`docs/releases/dual-machine-queue-copy.md`（新增第十三节）、`docs/cloud-queue-sync.md`、本文件。没有提交，没有改版本号。
135. **改动正式代码（服务器与网页，子任务）**：`cloud-server/app.py`、`cloud-server/test_app.py`、`public-site/src/queue/pairedQueueCopy.js`、`PlayerAccountDialog.vue`、`QueueStatusPanel.vue`。
136. **修正漏洞**：`QueueEngine.kt` 的 `applyTransfer`（手动转至后清掉预览的计时记录），测试 `transferByHandStartsTheAnnouncementAfresh`。
137. **本地演示**：改了 `tmp/claude-work/demo/seed.py`（不随项目提交）：已开启的登记改为玩家资料登记并带上预告；环境变量 `DEMO_ACCOUNT_STATE`（`announced`、`passed`、`held`、`skip`、`plain`）决定演示账户那份登记显示哪种状态。重建了演示用的网页快照，重启了演示服务器（旧进程还在跑旧代码），演示账户登录用的是演示脚本里的测试密码。演示服务器和保活脚本（180 分钟）此刻在运行；`.claude/launch.json` 和演示数据库仍在，维护者看完后清理。

### 7.51 原登记上也显示“可能自动转至”（2026-11-24）

维护者的要求：“即将被转出的玩家，点击它原本的登记也应该能看到它预计将被转出（当然你实际不要这么写），并且在排队主页，登记下方的‘允许他人加入’或者‘单人游玩’那行字消失，也变成一个醒目的提示（用有颜色的字标示）”。

做法和我的选择（细节在计划文件第十五节末尾，文字在对照表第 98 至 100 条）：

- 终端主页和网页排队页：已有预览的登记，名称下方那一行换成蓝色的“可能转至 机台 B”。
- 点开原登记：终端登记菜单的状态小字里把预览那一句标成蓝色；网页登记详情加一块蓝色提示（不登录也能看到），本人登记的状态小字同样标蓝。
- 用词：我先写的是“可能自动转至 机台 B”；维护者看后改为“计划转至 机台 B”；我提出整句应保持“可能”（预览中断时登记不会被转走，“计划”容易被当成确定的安排），维护者答复“改成可能吧”，条目最终定为“**可能转至 机台 B**”。这里的“改成可能”我理解为把条目上的“计划”换成“可能”，没有把“自动”加回去；如果维护者的意思不同，再改。登记菜单、登记详情和私信里的整句没有动，仍是“这份登记可能自动转至 机台 B”。
- 我的选择：颜色用现有的系统蓝；条目上用短名“机台 B”；终端的条目加宽以放下这行字；这行提示排在“未到场 N 次”之前。
- 网页已在本地演示里用浏览器核对；终端没有在设备上看过。

测试：`queue-core` 150 项，`app` 三个变体各 467 项，服务器 218 项，Bot 80 项，全部通过；网页可以构建。

本节新增的操作：

138. **改动正式代码**：`app` 的 `QueueTerminalUi.kt`、`PairedMachineQueueNotices.kt`、`QueueUiTokens.kt`；`public-site/src/queue/pairedQueueCopy.js`、`QueueStatusPanel.vue`、`PlayerAccountDialog.vue`。
139. **改动测试**：`PairedMachineQueueNoticesTest.kt`（新增 1 项）。
140. **改动文档**：对照表（第 98 至 100 条）、用户手册、机制说明、计划文件第十五节、本文件。重建了演示用的网页快照。没有提交。

### 7.52 检查现有的排队逻辑漏洞（2026-11-24）

维护者的指示：“检查排队逻辑”（他定的两项待办之一）。这一节只检查、不修改正式代码；哪些要改、怎样改，等维护者决定。

**方法**

- 我通读了排队核心（`queue-core` 的 `QueueModels.kt`、`RoundPlanner.kt`、`QueueEngine.kt`），对照用户手册里的规则列出可疑点，逐条写测试复现。
- 三个并行的子任务分别检查：远程命令入口（`RemoteQueueOperations.kt`、`ManagementQueueActions.kt`、`MobileDeviceRegistration.kt`）、服务器（`cloud-server/app.py`）、终端界面（`QueueTerminalUi.kt`）。前两个用测试或脚本复现；终端界面那个只读代码，其中重要的几条我回到源码逐条核对过。
- 另写了一个随机探测：随机生成 1500 组动作序列（加入、本轮结束、暂缓一次、暂时离开及取消、更改游玩偏好、退出排队），检查“没有使用过这些操作的登记，是否会被排在它后面的登记越过”。
- 复现用的测试和脚本不随项目提交，放在 `tmp/claude-work/queue-logic-audit/`（两个 Kotlin 测试文件，放回 `queue-core/src/test/kotlin/com/abcccc/maimaiqueue/` 即可运行；`server/` 下四个 Python 脚本，用 `cloud-server/.venv/bin/python` 在 `cloud-server` 目录里运行）。

**甲、明确的程序问题（改起来不涉及规则取舍）**

| 编号 | 问题 | 位置 | 证据 |
| --- | --- | --- | --- |
| 1 | 终端关机或重启期间，线上登记的 30 分钟签到时限照常流逝，恢复队列后立即被移除。机台停止使用时的处理是“暂停并从头计时”，两者不一致。 | `QueueTerminalUi.kt` 的 `restorePreviousQueue`（只对两台机台安排的预告扣掉停机时间）与过期清理的 `LaunchedEffect` | 读源码核对 |
| 2 | 管理后台连续操作两名不同玩家，第一条还在等终端处理时，第二条被拒绝（409）。管理命令的 `actor_qq` 是空串，“同一 QQ 只能有一条待处理命令”的检查把空串互相当成同一人。 | `cloud-server/app.py` 约 6935、8803–8816 行 | 脚本 `audit_server_4.py` 复现 |
| 3 | 无需身份的线上登记请求只要带上勾选框，服务器就把这份资料记成“已看过说明”，即使终端随后拒绝了这条命令。此后本人第一次开启时不再弹出 5 秒说明。 | `cloud-server/app.py` 约 8820–8830、9053–9071 行 | 脚本 `audit_server.py` [02] 复现 |
| 4 | 管理后台执行的队列操作（包括移除登记、未到场）会让终端首页出现“撤销本次操作”，现场任何人都能点。终端自己的退出和移除是故意不提供撤销的。 | `QueueTerminalUi.kt` 的 `applyManagementQueueResult`（给只改动一台机台的管理命令生成撤销项） | 读源码核对 |

**乙、规则空隙（玩家可以利用，需要维护者定规则）**

| 编号 | 问题 | 证据 |
| --- | --- | --- |
| 5 | 两名玩家建立固定组合时，系统把等待顺序里其他“允许他人加入”的单人重新配对，而且会跨过中间的“单人游玩”登记（平时的分组规则不会跨过）。例：等待顺序为 1（允许加入）、2、3、4、5（均为单人游玩）、6（允许加入），4 和 5 组成固定组合后，6 从最后变成与 1 同组、下一轮就游玩，越过了 2 和 3；系统认为“没有人延后”（按轮次算），不要求额外确认。2 和 3 的轮次没变，但前面那一轮从单人变成了两人，实际多等一个“两人与单人计划时间之差”。确认窗口里写了“其他选择允许他人加入的登记可能重新组合；只要其游玩轮次没有变晚，就不视为延后”，所以重新组合本身是有意的设计；超出预期的是跨过单人游玩登记、以及与这次组合无关的登记也被重排。来源是 `QueueModels.kt` 的 `planFriendPair`：重新配对时取“后面第一个可以共同游玩的登记”，不看中间隔着谁。 | 引擎测试复现；随机探测 1500 组里有 1491 次建立固定组合改变了其他登记的先后 |
| 6 | 处于游玩位置的登记使用“暂缓一次”，会回到等待顺序前端而不是队尾，没有“本轮已进行多久”的限制；终端、网站账户、QQ Bot 都可以。两人共同游玩时，一人在本轮快结束时暂缓，下一轮立刻再次进入游玩位置；单人游玩时每轮快结束时暂缓，就能隔一轮打一轮（6 人等待时游玩位置依次为 2、9、3、9、4、9、5、9）。 | 引擎测试、远程命令测试复现；服务器脚本确认接受 |
| 7 | 等待中的“暂缓一次”可以无限连续使用：排在第一位的登记每轮暂缓一次，12 轮之后仍在第一位、从未游玩。经网站或 Bot 操作时人不必在现场。全仓库没有任何暂缓次数或频率的限制。 | 引擎测试、远程命令测试复现 |
| 8 | “暂时离开”取消后再开启，已轮空次数清零，可以绕过“第 4 次轮到时自动退出”。收益小：登记每次仍被移到队尾，只是一直占着名额。 | 引擎测试、远程命令测试复现 |
| 9 | 网站线上登记只凭 QQ 号，不需要登录（协议文档写明的既有设计）。后果：任何人可以替别人登记，可以连续替 30 个不同 QQ 登记（超过 20 份的由终端拒绝），登记过期或被移除后可以立刻再次登记，没有冷却。冒用者自己不能因此提前游玩，但可以占满名额、让别人收到私信、挡住本人的正常登记。 | 服务器脚本复现 |
| 10 | 线上登记的“已到场”在终端上任何人都可以代点，在场的朋友可以替没到的人签到。 | 读源码 |
| 11 | 终端没有任何工作人员身份校验（见下面的事实清单）。其中与公平直接相关的是“撤回至等待顺序前端”没有时间限制：游玩了十几分钟后撤回、再进入游玩位置，等于重打一轮并重置计时。 | 引擎测试复现 |

**丙、设计疑问（现状如此，是否调整由维护者定）**

| 编号 | 现状 |
| --- | --- |
| 12 | 没有按日期清空队列。营业时间功能关闭时（默认关闭）永不自动清空；开启时，闭店清空只在“当前处于非营业时段”时触发。平板在闭店前关机、次日营业时间内开机的话，会弹出恢复窗口，30 秒无人操作就自动继续使用前一天的队列。 |
| 13 | 机台停止使用期间，该机台上的登记不能退出、不能转至其他机台（手册写明）。连带的后果：因为查重看全部机台，玩家资料的登记留在停止的机台上时，这份资料也不能去另一台机台登记。停止时没有“把登记整体转走”的办法，只能先恢复、转走、再停止。 |
| 14 | “关闭登记排队”会清空全部机台且不可撤销，任何人可点；管理后台接管之后，机台停止和恢复被锁住，这一项没有锁。 |
| 15 | 远程命令在服务器上最长保留 600 秒，命令本身不带时间条件。推演（未复现）：玩家提交“暂缓一次”后终端断网，现场照常推进，几分钟后网络恢复，这次暂缓落到下一次机会上。 |
| 16 | 等待登记全部不可用（都在待签到、暂缓一次或暂时离开）时，“进入游玩位置”按钮已被拦截，但经“游玩位置中的人暂缓或暂时离开”“移除本轮玩家的登记并开始下一轮”“游玩位置为空时记未到场”仍会走到同一处：一次移除全部待签到线上登记、清掉全部暂缓、给全部暂时离开加一次轮空。窗口里有文字说明。另：空机台上，现场新登记者排在待签到线上登记后面时会看到确认窗口，确认后线上登记被移除、自己进入——做决定的人正是受益人。两者都符合“轮到时仍未签到则退出”的既有规则。 |
| 17 | 远程的“暂缓一次”“暂时离开”让游玩位置空出时，下一组自动进入并开始计时；远程“退出排队”和现场的未到场处理则保持空缺。 |
| 18 | 小项：撤销提示不是固定 10 秒（首页侧栏被遮挡时倒计时不走）；“应处于游玩位置”的多轮补记实际在本轮满 21 分钟后出现，手册写“超过 20 分钟”；同一秒内创建的远程命令按客户端自选的 `request_id` 排序；凭 QQ 可查到资料和当前排队位置（协议文档已写明）；终端不核对手机扫码登记命令的会话，全靠服务器（服务器的令牌检查没有发现问题）。 |

**终端权限的事实清单（第 11 条的依据）**

终端排队界面没有密码或角色。管理后台接管后被锁住的只有敏感设置和机台停止、恢复。现场任何人都能做：本轮结束的三种处理、进入游玩位置、撤回至等待顺序前端、两种“应处于游玩位置”、调整顺序、对已轮到的登记记未到场并移除、让任何登记退出排队、转至其他机台、替任何人暂缓或暂时离开及取消、线上登记“已到场”、更改游玩偏好、建立或解除固定组合、认领登记、关闭或重新开放登记排队、未被接管时进入应用设置。

**查过、没有发现问题的方面**

- 基本顺序：随机探测 1500 组序列，只使用个人操作（不建立固定组合）时，没有一次越位。
- 归属：Bot 和网页账户只能操作本人的登记；登记编号在同一排队批次内不复用，换批次后旧命令被拒绝；远程签到一律拒绝，只有管理后台可以立即签到。
- 远程路径传给排队核心的规则设置与终端逐项相同；登记关闭、机台停止使用、登记已满、不允许线上登记、闭店收尾，服务器和终端都会拦截。
- 手机扫码登记的令牌：600 秒有效、只能用一次、绑定机台与排队批次。
- 命令重放由终端的回执表挡住；陈旧快照不能覆盖新快照。
- 终端的确认窗口在点击时都会重新核对登记或队列状态，没有按位置下标执行的动作；撤销要求队列与操作后完全一致才执行。
- 改机台数量、容量、计划时间必须先关闭登记排队；有登记或已停止的机台不能删除。
- 线上登记被成批移除（一次本轮结束移除了排在下一组前面的全部待签到登记）符合网页上写明的规则“轮到进入游玩位置时仍未签到，登记会自动退出排队”。

**没有核实的**

- 第 15 条的断网场景只是推演。
- 终端界面的结论都没有在设备上操作验证。
- 两台机台之间安排登记的功能没有纳入这次检查（它在本轮开发中另有测试）。

**测试**：临时测试移出后，`queue-core` 150 项通过；这一节没有改动任何正式代码或正式测试。

本节新增的操作：

141. **只读检查**：通读排队核心三个文件；三个并行子任务检查远程命令入口、服务器、终端界面，均未修改仓库内文件（远程命令入口的子任务用过一个临时测试文件，结束时已删除）。
142. **临时测试与脚本**：写了 `QueueLogicAuditScratchTest.kt`（11 项）和 `QueueFairnessAuditScratchTest.kt`（随机探测），运行后移到 `tmp/claude-work/queue-logic-audit/`；服务器的四个复现脚本从草稿目录复制到同一处的 `server/` 下。都不随项目提交。
143. **改动文档**：本文件。没有提交。

### 7.53 维护者对 18 条的答复，以及据此完成的修改（2026-11-24）

维护者对 7.52 的 18 条逐条作了答复。下面先记答复，再记这一轮做了什么。

**答复（编号同 7.52）**

| 编号 | 维护者的答复 | 状态 |
| --- | --- | --- |
| 1 | “可以暂缓处理” | 待办 |
| 2 | “可以增加命令队列。这条可以暂缓” | 待办，与第 15 条一起做 |
| 3 | “修复” | 已修 |
| 4 | “修复” | 已修 |
| 5 | “这应该是意料之外的，是 bug，修复” | 已修 |
| 6 | “修复：不允许在游玩位置的登记暂缓或暂时离开，需要先撤回到队首” | 已修 |
| 7 | “可以暂缓修复。我的想法是：暂缓功能不能连续使用两次，第二次时，引导登记回到队尾重新排队。这不是这个版本的重点。” | 待办，做法已定 |
| 8 | “修复：暂时离开不清空‘未到场’次数，请确认：是否只有上机游玩才清空未到场次数。可以暂缓修复。” | 待办；见下面的确认 |
| 9 | “计入代办，暂不修复” | 待办 |
| 10 | “我想不到解决方案，暂缓，你可以提意见。” | 待办；我的建议见下 |
| 11 | “不需要处理。” | 不处理 |
| 12 | “前者可以加入待办，后者不做处理”（前者：平板闭店前关机、次日营业时间内开机会自动继续前一天的队列；后者：营业时间功能关闭时不自动清空） | 前者待办 |
| 13 | “应当允许登记退出排队或转至其他机台，修复：机台停止使用时，仍然显示队列，只是不能新建登记。” | 已修 |
| 14 | “暂时不做处理，现场玩家可以根据现场人数自行决定采用登记排队还是共同记忆排队。” | 不处理 |
| 15 | “在管理后台显示待执行的命令队列，并且提供撤销命令（如果命令还在排队）的功能” | 待办（新功能，尚未开始） |
| 16 | “不做处理。” | 不处理 |
| 17 | “我前面‘不允许在游玩位置的登记暂缓或暂离’可以解决这个问题，对吗？” | 对，见下 |
| 18 | “无所谓” | 不处理 |

**对第 8 条的确认**：是的，未到场记录只在“游玩完一轮并回到队尾”时清零（`QueueModels.kt` 的 `endRoundWithoutStartingNext`，全仓库只有这一处把它置零）；暂缓一次、暂时离开及其取消、转至其他机台、撤回都不会清它。但第 8 条说的是另一个计数——暂时离开的“已轮空次数”，它在取消暂时离开时清零。我把维护者的意思理解为“取消暂时离开时不再把已轮空次数清零，只有游玩一轮才清零”；这是我的理解，动手前需要再确认一次。

**对第 17 条的确认**：对。游玩位置的登记不能再暂缓或暂时离开之后，网站和 Bot 已经没有任何操作会让游玩位置空出并自动放下一组进入（远程“退出排队”一直是保持空缺）。仍会自动进入游玩位置的只剩有意的设计：现场或管理后台明确选择“开始下一轮”，在空闲机台上创建登记，以及两台机台之间安排登记的自动转至。

**对第 10 条的建议**：任何“证明到场”的办法（终端显示二维码让本人手机扫、终端扫本人手机上的码）都可以被在场的朋友转发绕过，要真正防住只能靠定位或蓝牙，代价过大。更实际的是让代签没有好处：代签只是把“必须到场”的时点从签到推迟到轮到的那一刻，所以可以规定“线上登记第一次轮到时未到场，只能移除，不提供暂缓一次和移至队尾”，并在登记详情和日志里保留“线上登记”的标记，方便现场的人判断。这样代签最多白占到轮到为止，与不签到、等轮到时被移除的结果相同。

**这一轮的修改**

*第 5 条：建立固定组合不再重排无关登记*（`QueueModels.kt` 的 `planFriendPair`）

- 原先：把两人拿出来之后，其余“允许他人加入”的登记全部重新贪心配对，取“后面第一个可以共同游玩的登记”，不看中间隔着谁。
- 现在：其余登记保持原有先后。组合放在两人中较靠后那一份登记所在的位置；如果放在那里会让别人的轮次变晚，就依次往后试，取第一个不让任何人变晚的位置。因搭档加入组合而落单的登记，按平时的分组规则（`groupIntoPositions`）重新组合，不会越过单人游玩的登记或其他固定组合。找不到不延后别人的位置时，和以前一样拒绝。
- 代价：有些以前能“不延后任何人”完成的组合，现在组合本身要多往后排一轮（例：1、2 允许加入，3 单人，4、5 允许加入；2 和 5 组合，以前是 1 与 4 越过 3 组成一组，现在是 1、3、4 各自一轮，组合排在 4 后面）。确认窗口原有的“需要放弃当前较靠前的位置，并向后移动到位置……”会把两人都列出来。
- 确认窗口里那句“其他选择允许他人加入的登记可能重新组合；只要其游玩轮次没有变晚，就不视为延后”仍然成立，没有改。

*第 6 条：游玩位置中的登记不能暂缓一次或暂时离开*

- 排队核心：`DeferOneRound`、`TemporarilyLeave` 对处于游玩位置的登记（含固定组合任一成员）返回 `PLAYING_POSITION_NOT_ALLOWED`；`MachineQueue.deferOneRound`、`temporarilyLeave` 只处理等待中的登记，去掉了“从游玩位置离开并自动放下一组进入”的分支和用不到的参数。未到场处理不受影响。
- 终端：登记菜单里“暂缓一次或暂时离开”对游玩位置的登记变灰并说明原因。远程命令和管理后台命令在终端被拒绝并给出原因。日志里“从游玩位置暂缓/暂时离开”的两种条目已走不到，删掉了。
- 管理后台应用：登记菜单里这两项对游玩位置的登记变灰。
- 服务器：建立命令时就拒绝（含管理后台的两个接口）。网页：两处页面共用一个新文件 `queueActionAvailability.js` 判断并给出原因。Bot：直接拒绝，菜单不再列出。
- 没有清理的：终端“暂缓一次或暂时离开”确认窗口里针对游玩位置的几句说明已经走不到，留着没删（`QueueAbsenceDialog`，`isPlayingPosition` 为真的分支），以后可以清理。

*第 13 条：机台停止使用时仍显示队列，登记可以退出排队或转至其他机台*

- 我对维护者那句话的理解：停止使用期间放开的只有“退出排队”和“转至其他机台”两项，队列照常显示，不能新建登记；其余操作（暂缓、暂时离开、更改偏好、未到场、撤回、本轮结束、调整顺序等）仍然暂停。如果维护者的意思是“除了新建登记以外全部放开”，需要再改。
- 排队核心：策略要求机台正常使用时，`RemoveRegistrations` 和 `TransferRegistrations` 例外；转至其他机台只要求目标机台正常使用。
- 终端首页：停止使用且有登记的机台照常显示队列；游玩位置里显示一块提示（“机台已停止使用”），不显示本轮计时和超时提醒，不能拖动调整顺序，不显示加入入口；副标题加上停止原因。没有登记时仍是原来的说明。登记菜单和位置菜单里只有退出（移除）和转至可用。
- 远程命令、管理后台命令、服务器、网页、Bot 同步放开这两项。公开排队页本来就显示停止机台的队列。
- 处于游玩位置的登记仍不能转至其他机台（原有规则），停止期间也不能撤回，所以它只能退出排队后到另一台重新登记。

*第 4 条：管理后台的操作不再在终端留下撤销项*（`QueueTerminalUi.kt` 的 `applyManagementQueueResult`）。我的选择：管理后台的全部操作都不提供撤销，包括“本轮结束”这类终端自己会提供撤销的操作，因为撤销按钮在现场谁都能点。

*第 3 条：“已看过说明”的记录时机*（`cloud-server/app.py`）。只凭 QQ 的线上登记请求改为终端回报已执行时才记录；已登录网页账户和手机扫码的请求仍在接受时记录。没有堵住的情形：冒名的请求如果被终端执行了，仍会记录——那属于第 9 条。

**新增或改动的文字**

终端与管理后台（`app`）：

| 位置 | 原文 | 现在 |
| --- | --- | --- |
| 远程命令回执、登记菜单的灰色原因 | 机台 A 已停止使用，恢复正常使用后才能操作这份登记。／当前机台已停止使用，恢复正常使用后才能操作这份登记。 | 机台 A 已停止使用，恢复正常使用前只能退出排队或转至其他机台。／当前机台已停止使用，恢复正常使用前只能退出排队或转至其他机台。 |
| 位置菜单的灰色原因 | 当前机台已停止使用，恢复正常使用后才能调整这个位置。 | 当前机台已停止使用，恢复正常使用前只能移除登记或转至其他机台。 |
| 远程命令回执（新增） | — | 处于游玩位置的登记不能暂缓一次，请先在现场终端撤回至队首。／处于游玩位置的登记不能暂时离开，请先在现场终端撤回至队首。 |
| 管理后台命令回执（新增） | — | 处于游玩位置的登记不能暂缓一次，请先撤回至队首。／处于游玩位置的登记不能暂时离开，请先撤回至队首。 |
| 登记菜单（新增） | — | 处于游玩位置的登记不能暂缓一次或暂时离开，请先撤回至队首。 |
| 首页机台副标题 | 2 个等待位置·6 个登记·已停止使用 | 2 个等待位置·6 个登记·已停止使用·机台维护 |
| 首页游玩位置里的提示（新增） | — | 机台已停止使用／登记仍可退出排队或转至其他机台。其余操作在恢复正常使用后继续，计时从头开始。 |
| 删除 | 当前机台已停止使用，暂不能转至其他机台。／机台 A 已停止使用，暂时不能转出。／停止原因：……。现有 N 份登记、游玩位置和等待顺序均已保留。／停止期间不能操作该队列，也不会继续游玩或签到计时；恢复后将按原顺序继续，相关计时会从头开始。／从游玩位置暂缓或暂时离开的回执与日志（共 6 句） | — |

服务器、网页、Bot（由子任务完成，我重跑了测试并逐句看过）：服务器三句（句末无句号，照相邻句子）“处于游玩位置的登记不能暂缓一次，请先在现场终端撤回至队首”“……不能暂时离开……”“{机台名称}已停止使用，恢复正常使用前只能退出排队或转至其他机台”；网页三句与终端回执相同（带句号）；Bot 的停止使用一句写的是“……只能退出排队或切换机台。”，因为“切换机台”是 Bot 里实际的命令名。这一处与终端、网页不逐字相同，等维护者看。

用词：这些句子里一律用“撤回至队首”（文案规范第 35 行已定“队首、队尾”，终端按钮也叫“撤回至队首”）。我起初写成了“撤回至等待顺序前端”，核对规范后全部改正。用户手册里原有的 3 处“撤回至等待顺序前端”和 1 处“回到等待顺序前端”也顺手改成了“队首”；手册里还有 5 处“等待顺序末端”没有动，留给之后的文案审查。

**测试**：`queue-core` 154 项（新增 `FriendPairOrderTest` 4 项，其中一项用 4000 组随机队列验证“建立固定组合不改变其他登记的先后，也不让任何人的轮次变晚”；改写 4 项），`app` 三个变体各 467 项（删 2 项、改写 5 项、新增 3 项），服务器 225 项，Bot 82 项，全部通过；网页可以构建。

**没有验证的**：终端和管理后台的界面改动只编译和跑了单元测试，没有在设备上看过——尤其是停止使用的机台显示队列这一处，布局是否合适需要真机确认。网页的改动没有在浏览器里点过。`tmp/claude-work/queue-logic-audit/` 里针对第 5、6 条的复现测试是按旧行为写的，修复后不再适用。

**待办汇总（维护者已定方向、尚未动手的）**

1. 管理后台显示待执行的命令队列，可撤销仍在排队的命令；同时解决管理后台连续操作两名玩家时第二条被拒绝的问题（第 2、15 条）。
2. 暂缓一次不能连续使用两次；第二次时引导登记回到队尾重新排队（第 7 条）。
3. 取消暂时离开时不清零已轮空次数（第 8 条，我的理解，动手前确认）。
4. 终端关机或重启期间，线上登记的签到时限不应流逝（第 1 条）。
5. 平板在闭店前关机、次日营业时间内开机时，不应自动继续前一天的队列（第 12 条前半）。
6. 线上登记只凭 QQ 的后果：冒名登记、占满名额、无冷却（第 9 条）。
7. 线上登记“已到场”可以代点（第 10 条，我的建议见上）。

本节新增的操作：

144. **改动正式代码（排队核心）**：`QueueModels.kt`（`planFriendPair`、`deferOneRound`、`temporarilyLeave`）、`QueueEngine.kt`（游玩位置的校验、停止使用时的两项例外、转至只看目标机台）。
145. **改动正式代码（应用）**：`RemoteQueueOperations.kt`、`ManagementQueueActions.kt`、`QueueTerminalUi.kt`、`ManagementUi.kt`、`AuditLogs.kt`。
146. **改动测试**：`queue-core` 的 `FriendPairOrderTest.kt`（新增）、`RoundPlannerTest.kt`、`QueueEngineDifferentialTest.kt`；`app` 的 `RemoteQueueOperationsTest.kt`、`QueueModelsTest.kt`、`AuditLogsTest.kt`。
147. **改动正式代码（服务器、网页、Bot，子任务）**：`cloud-server/app.py`、`cloud-server/test_app.py`、`public-site/src/queue/queueActionAvailability.js`（新增）、`PlayerAccountDialog.vue`、`QueueStatusPanel.vue`、`koishi-bot/src/index.ts`、`koishi-bot/test/format.test.js`、`koishi-bot/README.md`。子任务第一次运行因接口 502 错误中断，维护者说“请再试一次”后从中断处继续完成。
148. **改动文档**：`docs/user-manual.md`、`docs/cloud-queue-sync.md`（子任务）、本文件。没有提交，没有改版本号。

### 7.54 维护者对 7.53 的答复，以及据此完成的修改（2026-11-24）

维护者逐条回复了 7.53 的汇报。下面按内容记，不只写编号（维护者的要求：“以后同时说清第 13 条的内容，概括即可”——以后凡是用编号指某一条，都同时写一句它说的是什么）。

**答复**

| 事项 | 维护者的答复 | 处理 |
| --- | --- | --- |
| 机台停止使用时只放开“退出排队”和“转至其他机台”（原第 13 条的范围） | “你的判断是对的” | 范围确认，不再改 |
| 机台停止使用后首页仍显示队列 | “同样，在排队主页面应该能够醒目看出机台已经停止使用，不能因为这个更改而损失” | 已改，见下 |
| 首页提示里的“其余操作在恢复正常使用后继续，计时从头开始。” | “这句删掉” | 已删 |
| 游玩位置的登记菜单里“暂缓一次或暂时离开”的说明 | “文字没有问题。但实际应该让‘暂缓一次或暂时离开’按钮置灰（或者消失，请结合实际 UI 判断），无法置灰时点击才显示这个文字” | 已经是置灰，见下 |
| Bot 的“只能退出排队或切换机台” | “bot 之后会改，这里用转至其他机台即可” | 已改 |
| 取消暂时离开时已轮空次数清零（原第 8 条） | “是的。那就把这个改掉：只有确实上机才清空已轮空次数。确认一下未到场记录的清空是在上机时还是在登记的本轮结束时，让他们保持一致即可” | 已改，见下 |
| 线上登记“已到场”可以代点（原第 10 条），我的建议是“线上登记第一次轮到时未到场只能移除” | “我同意。加入待办，也可以改版本改。”（我理解为：记入待办，也可以就在这个版本里改） | 待办 |
| 新功能 | “我想加入的功能（低优先级）：对于偏好为单人游玩的登记，在确认登记时提供‘允许他人加入的登记可以越过我共同游玩，以减少队列轮换时间。此登记的游玩可能会延迟’（请润色文案）。” | 待办，文案草稿见下 |

**这一轮的修改**

*已轮空次数只在游玩一轮后清零*

- 先确认的事实：未到场记录是在“登记的本轮结束、回到队尾”时清零（`endRoundWithoutStartingNext`），不是在进入游玩位置时；进入游玩位置后被撤回不会清零。已轮空次数照这个时点对齐。
- 排队核心：取消暂时离开、暂缓一次、未到场处理、补入当前游玩位置都不再把已轮空次数清零；再次暂时离开时接着累计；累计到 3 次后，第 4 次轮到时仍处于暂时离开就自动退出排队。唯一的清零点是本轮结束。固定组合两份登记共用一个次数（取较大的）。去掉了“非暂时离开登记不能保留轮空次数”这条不变量。
- 本机保存与恢复：不再把非暂时离开登记的次数抹成 0。
- **与服务器的兼容**（我的选择）：线上服务器会拒绝“非暂时离开却带轮空次数”的快照。所以终端对外仍然只在暂时离开期间上报次数，其余时候上报 0（`Registration.publishedTemporaryAwaySkippedTurns`），核对远程命令时也按这个上报值比较。协议和服务器都不用改，新终端配旧服务器不会出问题。代价：网页和 Bot 看不到“保留着的次数”，只能用一句通用的话说明。
- 终端：再次暂时离开的确认窗口里，如果有保留的次数，会多一行“……此前已轮空 N 次，暂时离开后接着累计。”
- 文字改动：

| 位置 | 原文 | 现在 |
| --- | --- | --- |
| 终端“取消暂时离开”的说明、网页取消暂时离开的说明（两处） | 恢复正常轮候，并将已轮空次数清零。 | 恢复正常轮候；已轮空次数在游玩一轮后清零。 |
| 终端暂时离开确认窗口 | ……连续轮空 3 次后仍未取消，第四次轮到时会自动退出排队。 | ……累计轮空 3 次后，第四次轮到时仍未取消，登记会自动退出排队；已轮空次数在游玩一轮后清零。 |
| 网页暂时离开的提示（两处） | ……连续轮空 3 次后仍未取消，第四次轮到时会自动退出排队。 | ……累计轮空 3 次后，第四次轮到时仍未取消，登记会自动退出排队。已轮空次数在游玩一轮后清零。 |
| Bot 暂时离开的提示（两句） | 连续轮空 3 次后，第四次仍未返回…… | 累计轮空 3 次后，第四次仍未返回……已轮空次数在游玩一轮后清零。 |
| Bot 取消暂时离开的结果（两句） | ……轮空次数已经清零，并将按照当前等待顺序正常参与游玩位置分配。 | ……并将按照当前等待顺序正常参与游玩位置分配；已轮空次数在游玩一轮后清零。 |
| 终端新增 | — | “某某”此前已轮空 N 次，暂时离开后接着累计。 |

*首页醒目显示机台已停止使用*（`QueueTerminalUi.kt`、`QueueUiTokens.kt`）

- 机台名称旁加一个深橙底白字的标记“已停止使用”。
- 名称下方那行小字改成同一种橙色并加粗，内容是“2 个等待位置·6 个登记·停止原因：机台维护”（没有登记时是“停止原因：……”）。
- 游玩位置里的橙色提示保留，内容改为“机台已停止使用／登记仍可退出排队或转至其他机台。”
- “恢复正常使用”按钮、没有登记时的大字说明都和以前一样。
- 没有在设备上看过，标记的大小和颜色是否够醒目需要真机确认。

*“暂缓一次或暂时离开”按钮*：没有改代码。它对游玩位置的登记本来就是置灰的；这个菜单里置灰的项都把原因写在标题下方的小字里，点按置灰的项还会弹出同一句原因，同一处境下相邻的“转至其他机台”也是这样显示的。我选择置灰而不是隐藏，是为了与相邻项一致、菜单格子不跳动。

*Bot 的一句*：改为“登记所在机台已停止使用，恢复正常使用前只能退出排队或转至其他机台。”（README 里对应的一句同样）。

**新功能的文案草稿（低优先级，尚未动手）**

- 勾选框：允许他人越过这份登记共同游玩
- 小字：开启后，排在这份登记前后的“允许他人加入”登记可以越过这份登记组成共同游玩，以减少队列的轮换时间；这份登记的游玩可能因此延后。
- 只在偏好为“单人游玩”时提供。动手前要定的事：它是否保存在玩家资料里，固定组合是否也可以被越过，以及它与“建立固定组合不重排他人”的关系（开启的登记等于同意被越过）。

**测试**：`queue-core` 156 项（新增 2 项），`app` 三个变体各 468 项（改写 2 项，新增 1 项），服务器 225 项，Bot 82 项，全部通过；网页可以构建。界面改动没有在设备或浏览器里看过。

**待办汇总（更新）**

1. 管理后台显示待执行的命令队列，可撤销仍在排队的命令；同时解决管理后台连续操作两名玩家时第二条被拒绝。
2. 线上登记第一次轮到时未到场，只能移除，不提供暂缓一次和移至队尾（维护者已同意，可以在这个版本里做）。
3. 暂缓一次不能连续使用两次；第二次时引导登记回到队尾重新排队（维护者：不是这个版本的重点）。
4. 终端关机或重启期间，线上登记的签到时限不应流逝。
5. 平板在闭店前关机、次日营业时间内开机时，不应自动继续前一天的队列。
6. 线上登记只凭 QQ 的后果：冒名登记、占满名额、无冷却。
7. 单人游玩的登记可以选择允许他人越过自己共同游玩（低优先级，文案草稿见上）。
8. Bot 的用词（“切换机台”等）维护者说之后会改。

已不在待办里：取消暂时离开时清零已轮空次数（本节已改）。

本节新增的操作：

149. **改动正式代码（排队核心）**：`QueueModels.kt`（已轮空次数的保留）、`QueueInvariants.kt`（去掉一条不变量）。
150. **改动正式代码（应用）**：`LocalQueueStateRepository.kt`、`QueueCloudSync.kt`（`publishedTemporaryAwaySkippedTurns`）、`RemoteQueueOperations.kt`、`QueueTerminalUi.kt`、`QueueUiTokens.kt`（`MachineStoppedColor`）。
151. **改动正式代码（网页、Bot）**：`QueueStatusPanel.vue`、`PlayerAccountDialog.vue`、`koishi-bot/src/index.ts`、`koishi-bot/test/format.test.js`、`koishi-bot/README.md`。服务器代码没有改。
152. **改动测试**：`QueueRiskSequenceTest.kt`（新增 2 项）、`QueueModelsTest.kt`、`QueueStatePersistenceTest.kt`、`RemoteQueueOperationsTest.kt`（新增 1 项）。
153. **改动文档**：`docs/user-manual.md`、`README.md`、`docs/cloud-queue-sync.md`、本文件。没有提交，没有改版本号。

### 7.55 文字修正，以及“线上登记第一次轮到时未到场只能移除”（2026-11-24）

**维护者对 7.54 汇报的答复**

| 事项 | 维护者的答复 | 处理 |
| --- | --- | --- |
| 未到场记录在登记的本轮结束时清零，已轮空次数与它对齐 | “可以的，不需要改” | 不再改 |
| 我在汇报里写的“某某此前已轮空 N 次，暂时离开后接着累计。” | “你应该不会真的这么写对吧，如果不是正式文案请加注” | 说明见下；以后引用带占位的文案时注明 |
| “累计轮空 3 次后，第四次” | “这里 4 是不是应该用数字，如果你觉得该用就直接改掉” | 已改为“第 4 次” |
| “已轮空次数在游玩一轮后清零。” | “在->会在” | 已改 |
| Bot 的“参与游玩位置分配” | “这几个字能否改成‘轮候’，如果你觉得合适，后续再讨论之后相关短语的替换” | Bot 的四句已改；其余留待文案审查 |
| 线上登记第一次轮到时未到场只能移除（代签的对策） | “如果没有更高优先级的待办，现在就做。之后可以审查文案” | 已做，见下 |

**关于“某某”**：那是我在汇报里代替玩家昵称的写法，不是界面上的字。界面上的句子由程序填入带引号的昵称，例如：“小雨”此前已轮空 2 次，暂时离开后接着累计。固定组合时是：“小雨”和“青空”此前已轮空 2 次，暂时离开后接着累计。以后在汇报和文档里引用这类句子，写成带真实示例的形式，并注明哪一部分由程序填入。

**文字修正**

- “第四次”一律改为“第 4 次”：同一句里已经写“轮空 3 次”，前后数字写法应当一致，文案规范也是数字前后空一格的写法。涉及终端暂时离开确认窗口、日志两处、撤销提示一处、网页三处、Bot 两处。
- “已轮空次数在游玩一轮后清零”改为“已轮空次数会在游玩一轮后清零”：终端两处、网页四处、Bot 四处、README 一处。
- Bot 取消暂缓一次、取消暂时离开的四句结果里，“按照当前等待顺序正常参与游玩位置分配”改为“按照当前等待顺序正常轮候”（终端和网页本来就用“恢复正常轮候”）。维护者点名的是取消暂时离开那两句；取消暂缓一次的两句在同一处、用的是同一个短语，一并改了。
- 留给文案审查讨论的同类短语（没有改）：终端“已到场”的说明“……并开始参与后续游玩位置分配。”（两处）；终端转至其他机台的说明“……转入后仍需手动取消，才能参与游玩位置分配。”（两处）。

**线上登记第一次轮到时未到场只能移除**

- 规则：线上登记完成签到之后、游玩过一轮之前，被记录为未到场时只能选择移除登记，不能暂缓一次或移至队尾。游玩过一轮之后与现场创建的登记同样处理。理由：终端上的“已到场”谁都能代点，签到本身不能证明玩家到场。
- “线上登记”的标记：签到之后原先没有任何字段能认出一份登记是线上登记（`requiresOnSiteCheckIn` 被清掉，`originatingCommandId` 手机扫码登记也有）。所以给登记加了一个字段 `wasOnlineRegistration`，线上登记创建时和签到时置为真，随本机保存；派生属性 `mustBeRemovedWhenAbsent` = 是线上登记、已签到、尚未游玩过。升级前就已签到的登记没有这个标记，按普通登记处理。这个字段不上传服务器，协议没有变。
- 排队核心：`MarkNoShow` 的处理方式不是移除、且涉及这样的登记时，返回新的失败代码 `NO_SHOW_REMOVAL_ONLY`；模型函数同样拒绝。暂缓会连带固定组合的另一份登记，所以固定组合里只要有一份是这样的登记，整组都不能暂缓。由系统自动转至当前机台的登记未到场时仍按原规则回到原机台，不受这条限制。
- 终端：未到场窗口（单份和整组）里“暂缓一次”“移至队尾”置灰，说明改为这条规则；登记详情多一行“登记方式：线上登记·已签到”。
- 管理后台：命令在终端被拒绝并返回明确的原因。管理后台应用的未到场窗口没有置灰——它拿不到这个标记（没有上传），维护者从管理后台选了暂缓或移至队尾会看到拒绝原因。要置灰需要把标记加进快照和服务器，留作后续。
- 没有做的：日志里没有单独标出“线上登记”。
- 新增文字（界面上的原文；引号里的昵称由程序填入）：

| 位置 | 文字 |
| --- | --- |
| 终端未到场窗口（单份）的说明 | 这份线上登记已完成签到，尚未游玩。线上登记第一次轮到时未到场，只能移除登记。 |
| 终端未到场窗口（整组）的说明 | 组内有已完成签到、尚未游玩的线上登记。线上登记第一次轮到时未到场，只能移除登记。因此这里只能移除整组登记。 |
| 置灰的“暂缓一次”“移至队尾”下方 | 线上登记第一次轮到时不提供。 |
| 管理后台命令被拒绝的原因 | 线上登记第一次轮到时未到场，只能移除登记。 |
| 终端登记详情 | 登记方式：线上登记·已签到 |
| 网页线上登记的两处签到提示、Bot 线上登记的两处提示（接在原句后面） | 完成签到后，第一次轮到时未到场的登记只能移除，不能暂缓一次或移至队尾。 |

**测试**：`queue-core` 159 项（新增 3 项），`app` 三个变体各 470 项（新增 2 项，另有一项加了断言），服务器 225 项，Bot 82 项，全部通过；网页可以构建。界面改动没有在设备或浏览器里看过。

**待办汇总（更新）**

1. 审查软件内各处文案是否统一、用词是否能够理解（维护者：“之后可以审查文案”）。包括上面留下的“参与游玩位置分配”类短语、手册里 5 处“等待顺序末端”，以及此前暂停的文案统一工作。
2. 管理后台显示待执行的命令队列，可撤销仍在排队的命令；同时解决管理后台连续操作两名玩家时第二条被拒绝。
3. 暂缓一次不能连续使用两次；第二次时引导登记回到队尾重新排队（维护者：不是这个版本的重点）。
4. 终端关机或重启期间，线上登记的签到时限不应流逝。
5. 平板在闭店前关机、次日营业时间内开机时，不应自动继续前一天的队列。
6. 线上登记只凭 QQ 的后果：冒名登记、占满名额、无冷却。
7. 单人游玩的登记可以选择允许他人越过自己共同游玩（低优先级，文案草稿在 7.54）。
8. 管理后台应用的未到场窗口按“线上登记只能移除”置灰（需要把标记加进快照）。
9. Bot 的用词（“切换机台”等）维护者说之后会改。

本节新增的操作：

154. **改动正式代码（排队核心）**：`QueueModels.kt`（`wasOnlineRegistration`、`mustBeRemovedWhenAbsent`、未到场三个函数的限制、签到时置标记）、`QueueEngine.kt`（`NO_SHOW_REMOVAL_ONLY`）。
155. **改动正式代码（应用）**：`RemoteQueueOperations.kt`、`LocalQueueStateRepository.kt`、`ManagementQueueActions.kt`、`QueueTerminalUi.kt`、`AuditLogs.kt`、`QueueHomeFeedback.kt`。
156. **改动正式代码（网页、Bot）**：`QueueStatusPanel.vue`、`PlayerAccountDialog.vue`、`koishi-bot/src/index.ts`、`koishi-bot/test/format.test.js`。
157. **改动测试**：`QueueRiskSequenceTest.kt`（新增 3 项）、`QueueStatePersistenceTest.kt`（新增 1 项）、`RemoteQueueOperationsTest.kt`（新增 1 项）、`AuditLogsTest.kt`、`QueueHomeFeedbackTest.kt`。
158. **改动文档**：`docs/user-manual.md`、`README.md`、本文件。没有提交，没有改版本号。

### 7.56 最后一轮修正，以及文案审查的交接（2026-11-24）

维护者接下来要审查全部文案，并说明会切换到另一个模型继续：“稍后立刻开始审查全部文案，我会切换到 opus 模型，请协助完成交接”。这一节前半记本轮的修正，后半是给接手者的交接说明。

**本轮的修正**

| 事项 | 维护者的答复 | 处理 |
| --- | --- | --- |
| 终端暂时离开确认窗口里新增的一句“……此前已轮空 2 次，暂时离开后接着累计。” | “我没说这个，我说的是‘接着累计’，这怎么能作为正式文案，当然不行” | 改为：“小雨”已轮空 2 次。再次暂时离开后，已轮空次数将继续累计。（昵称和次数由程序填入） |
| 整组未到场窗口里“因此这里只能移除整组登记。” | “可以引导玩家分别操作两份登记，而不是整组操作。” | 改为引导分别操作，见下 |
| 管理后台命令被拒绝的原因“线上登记第一次轮到时未到场，只能移除登记。” | “既然是管理后台，除此之外应该单独开放它暂缓或者暂离的开关。如果这推翻了当前实现逻辑，则加入待办，低优先级” | 核实后无需改动，见下 |

- 我上一轮把维护者对“接着累计”的批评误读成了对“某某”占位写法的批评。这是我的误读；真正的问题是这句话口语化，违反文案规范。顺带自查了本次会话新写的文字，用户手册里“落单”“按平时的规则”“必要时再往后”“会接着累计”四处同样口语化，已改成书面说法；机制说明文档里的“接着算”“接着看”也改了。
- 整组未到场窗口（组内有已签到、尚未游玩的线上登记时）现在的说明是：“组内有已完成签到、尚未游玩的线上登记。线上登记第一次轮到时未到场，只能移除登记。需要保留另一份登记时，请分别对两份登记记录未到场。”两份登记是固定组合时，后半句是“需要保留另一份登记时，请先解除固定组合，再分别对两份登记记录未到场。”——固定组合中任一份登记的“未到场”都会进入整组窗口，所以要先解除。“移除整组”仍然可选。
- 管理后台：这条规则只限制“记录未到场之后怎样处理”。管理后台登记菜单里单独的“暂缓一次”“暂时离开”两项本来就不受它影响，对这样的登记照常可用，我加了一项测试确认。所以维护者要的“单独开放”现在已经成立，没有改代码。管理后台的未到场窗口里选“暂缓一轮”或“移至队尾”仍会被终端拒绝并显示原因。
- 需要维护者知道的另一面（没有改，记入待办，低优先级）：终端登记菜单和网站、Bot 上的“暂缓一次”“暂时离开”同样不受这条规则限制，所以替人代签的人也可以替人点“暂缓一次”，本人也可以远程暂缓，绕开“只能移除”。要堵住，需要规定“已签到、尚未游玩的线上登记不能暂缓一次或暂时离开，管理后台除外”，这会改动现有逻辑；它与“暂缓一次不能连续使用两次”那条待办可以一起做。

测试：`queue-core` 159 项，`app` 三个变体各 470 项，服务器 225 项，Bot 82 项，全部通过；网页可以构建。

#### 交接：文案审查

**任务**（维护者的原话）：“审查软件内各处文案是否统一、用词是否能够理解”。此前暂停的“全软件文案风格统一”并入这一项。

**先读这些**

1. `docs/copywriting-guide.md`：文案规范，每条标了“已定”或“现状”，末尾有“尚未决定”一节。用词表在“用词”一节（例如第 35 行：队首、队尾为准，等待顺序前端、末端是被替换的旧说法）。
2. 本文件 7.30（清点结果和规范草稿）、7.34（动作类用词的对照表，其中多数行维护者还没有答复）。
3. `docs/releases/dual-machine-queue-copy.md`：“自动在两台机台之间安排登记”功能的全部文字对照表，共 100 条；第 75 至 100 条维护者还没有逐条看过。
4. 本次会话新增或改动的文字清单：7.53、7.54、7.55 和本节的表格。这些同样没有被逐条确认过。

**维护者对文案的要求**（都来自他的原话，详见规范）

- 正式、不口语、不用“你”“您”，照现有句子的写法来写，不另起炉灶。被他否定过的说法举例：“哪台先轮到就在哪台游玩”“所以在这里显示”“接着累计”。
- 逐字检查用词是否一致；指登记用“这份登记”；功能名不加引号；“转至”后面必须有宾语；按钮标题下方的小字是完整句子、有句号；内部阈值（例如 3 分钟）不告诉玩家。
- 改动的规矩：“少量的修改不必再问我，如果是大批量修改需要问我。但凡修改都要告诉我。”他问“这句是什么意思”时，先解释并提出改法，他同意后再改。
- 汇报里引用文字时写界面上的原文；提到某一条时同时说它是什么，不只写编号。

**材料**（都在 `tmp/claude-work/`，不随项目提交）

- `ui-strings.jsonl`：全部界面文字的清单，2026-11-24 重新生成，共 4786 条（终端 2186、管理后台 572、网页 878、Bot 412、服务器 738），每条带组件、文件和行号。生成脚本 `extract_strings.py`（用法：`python3 tmp/claude-work/extract_strings.py . tmp/claude-work/ui-strings.jsonl`）。旧清单留作 `ui-strings.2026-11-22.jsonl`。
- `ui-strings-analysis.txt`：按用词统计的表，同日重新生成（`analyze_strings.py`）。
- `ui-strings-near-duplicates.json`：近似重复的句子对，仍是 2026-11-22 的，没有重新生成。
- `copy-batch-a.json`、`copy-batch-b.json`、`copy-batch-c.json`：已做的三批改动的前后对照。

**已经做过的**：去掉全部“你”（Bot 的 9 处除外）；新建改创建；等待顺序末端、前端改队尾、队首；登记顺序、默认偏好、本次偏好等改为全称；切换机台、转移改为转至（Bot 的命令名除外）。

**还没有做的**

- 服务器消息句末补句号（约 460 句）。注意有几句被旧版客户端逐字比较，不能动，见下面的“陷阱”。
- 其余近义词：目前与当前（终端 10 处、网页 1 处、Bot 3 处仍是“目前”）、修改与更改（“不会修改玩家资料中的默认游玩偏好”一类，14 处）、队列顺序、排队顺序与等待顺序、新增分组与创建分组、暂不能与暂时不能、未执行与没有执行、现场工作人员与现场管理员、启用与开启等，清单在 7.34 和记忆文件里。
- 近似重复的句子（281 对，其中 78 对跨组件）。
- “队列”一词的含义：维护者提过想让“队列”只指等待顺序，这与现有文字（队列包含游玩位置）冲突，尚未定。
- 本次会话留下的：终端 4 处“参与游玩位置分配”是否改为“轮候”（维护者：“后续再讨论之后相关短语的替换”）；用户手册 5 处“等待顺序末端”；终端还有 1 处“切换机台”没有核对是不是版本历史里的文字；终端“暂缓一次或暂时离开”确认窗口里针对游玩位置的几句已经走不到，可以删。
- Bot 的用词维护者说“之后会改”，不要主动动 Bot 的命令名。

**陷阱**

- `QueueStatusPanel.vue`、`MobileRegistrationFlow.vue`、`MobileProfileSettings.vue`、`docs/development-log.md` 是 CRLF 与 LF 混用的文件，用普通的读写会把整份文件的换行改掉。用 `tmp/claude-work/mixed_eol_edit.py`（`MixedEolFile(path).replace_once(label, old, new)`，最后 `.save()`），改完看 `git diff --stat`。
- 以下服务器文字被客户端逐字比较，必须一字不差：`不支持的队列数据版本`、`不支持的队列协议版本`、`移动设备登记会话参数不完整`、`请求包含不支持的排队操作字段`、含“只包含 bot_qq”或“不能包含未知字段”的句子、`请求包含不支持的线上登记字段`。
- 许多测试断言文字原文：`AuditLogsTest.kt`、`RemoteQueueOperationsTest.kt`、`PairedMachineQueueNoticesTest.kt`、`QueueHomeFeedbackTest.kt`、`cloud-server/test_app.py`、`koishi-bot/test/format.test.js`。`docs/cloud-queue-sync.md` 引用了服务器消息原文，改消息要同步。
- 应用内的版本历史文字不要改写。
- 终端与网页的同一句话要逐字相同的地方：`PairedMachineQueueNotices.kt` 与 `public-site/src/queue/pairedQueueCopy.js`；`queueActionAvailability.js` 与终端回执。
- `QueueTerminalUi.kt` 里处理远程命令的那段协程已接近单个方法的字节码上限，往里加代码会编译失败（Method too large），要抽成函数。

**验证命令**

```
unset TZ; export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
sh ./gradlew :queue-core:test :app:testLocalDebugUnitTest :app:testManagementDebugUnitTest :app:testTerminalDebugUnitTest -PENABLE_TERMINAL_BUILD=true --continue -q
(cd cloud-server && .venv/bin/python -m unittest)
(cd koishi-bot && pnpm test)
(cd public-site && pnpm build)
```

Gradle 用 `-q` 时成功没有输出，结果看 `build/test-results/**/*.xml`。

**当前状态**

- `6a04c24` 之后的全部改动都没有提交，没有改版本号；提交只在维护者要求时做，本文件提交前要先给他过目（仓库是公开的）。
- 终端、管理后台和网页在本次会话里的界面改动都没有在真机或浏览器里看过（网页只有两台机台功能那部分在本地演示里看过）。
- 本地演示环境的文件还在（`.claude/launch.json`、`tmp/claude-work/demo/`），保活脚本已结束，演示服务器跑的是旧代码，要用需重启。
- 排队逻辑方面的待办见 7.55 末尾的清单，另加本节的一条：是否禁止已签到、尚未游玩的线上登记暂缓一次或暂时离开（管理后台除外），低优先级。

**我建议的做法**（接手者可以自行判断）：先按组件通读清单，对照规范列出“用词不统一”“难以理解”“口语化”“标点”四类问题，把结果给维护者看；小处直接改并汇报，成批的先给他前后对照再改；每批改完跑上面的命令，并把前后对照存成 `tmp/claude-work/copy-batch-*.json`。先看终端上玩家会读到的文字，其次网页，再是管理后台和服务器消息。

本节新增的操作：

159. **改动正式代码**：`QueueTerminalUi.kt`（一句文字、整组未到场窗口的说明）。
160. **改动测试**：`RemoteQueueOperationsTest.kt`（管理后台的暂缓一次、暂时离开对这类登记仍可用）。
161. **改动文档**：`docs/user-manual.md`、`docs/automatic-machine-arrangement.md`、本文件。
162. **重新生成材料**：`tmp/claude-work/ui-strings.jsonl`、`ui-strings-analysis.txt`（旧清单另存）。没有提交。

### 7.57 换用另一个模型接手；清理演示环境；文案审查第一轮（2026-11-24）

维护者切换了模型（从这一节起由 Claude Opus 5.5 接手），并说：“通读项目，完成交接。接下来完成剩余任务，然后立即开始文案审查。”我问剩余任务指哪些，维护者答：“清理本地演示环境。三项暂时都不做，放在文案审查之后。”三项指：管理后台显示并可撤销待执行的远程命令；平板闭店前关机、次日开机时不自动继续前一天的队列；管理后台的未到场窗口按“线上登记第一次轮到只能移除”置灰。

**清理演示环境**：停止了演示网站和演示服务器（两个预览进程），删除了 `.claude/launch.json`（`.claude/` 目录随之为空，一并删除）和 `tmp/claude-work/demo/`（演示数据库、种子脚本、网页快照）。这些文件都不在版本控制里，没有影响正式数据。

**接手时读了**：文案规范全文、本文件 7.34 与 7.52–7.56、术语和文案风格两份记忆。

**文案审查的做法**

1. 重新生成全部界面文字的清单，去掉应用内历史版本说明（`QueueTerminalUi.kt` 18893–19154 行，104 条）和只给开发者看的不变量说明（`QueueInvariants.kt`，14 条），余 4668 条：`tmp/claude-work/copy-review/strings.jsonl`，每条一个编号。
2. 用脚本查规范用词表里“不再使用”的写法、7.34 里列过的混用、标点与空格、中文数字（`mechanical.txt`）；用字符三元组相似度找出各部分之间说同一件事、写法却不同的句子（`near-duplicates.txt`，726 对，其中跨部分 275 对，去掉只差句号的之后 66 对）。
3. 六个审查子任务并行通读：终端分三份，网站、管理后台、服务端各一份。说明书在 `tmp/claude-work/copy-review/BRIEF.md`，把规范和维护者在记忆里的要求都写进去了；子任务只写结果文件，不改仓库。Bot 按规范只查了用词，没有逐句审。
4. 我逐份看了子任务的结果，抽查了几条涉及程序行为的说法（例如管理后台“队列操作已发送，等待现场终端处理”确实只在终端已执行后显示），并否掉或改判了一些建议：
   - 否掉 3 条：网站“完成并加入排队”改“确认并加入排队”（终端和手册都是前者）；网站性别选项“不愿透露”改“不公开”（与终端选项名一致，不该改）；服务端删去“请先在现场终端撤回至队首”里的“在现场终端”（网站和 Bot 的玩家需要知道去哪里撤回）。
   - 改判为“需要维护者决定”的：按钮名“本轮结束”“应处于游玩位置”“让此位置上场”、日志标题里“转至”后要不要空格（规范两条规则冲突）、“队列顺序”改“等待顺序”还是“登记顺序”（两位审查员意见相反）、“编辑”与“修改”、几条含义不清需要先问的。
   - 合并时同一条文字若有两个建议，以子任务的为准，我的机械建议作为备选保存在 `review.json` 里。
5. 合并成对照表 `docs/releases/copy-review-2026-11.md`（生成脚本 `tmp/claude-work/copy-review/build_report.py`，我的改判在 `decisions.json`）。共 629 条，分五批：

| 批次 | 内容 | 条数 |
| --- | --- | --- |
| A | 与实际行为不符 | 28 |
| B | 违反已定的用词和句式 | 115 |
| C | 同一件事的写法不一 | 202 |
| D | 难懂或口语化的句子 | 215 |
| E | 需要维护者决定 | 69 |

另有服务端提示补句号（约 460 句）一批，规则已定，没有逐条列。

**A 批里需要改程序、不只是改字的**：玩家自己退出排队时日志标题仍写“移除登记”（终端 8034 行和远程退出 4758 行没有传标题）；撤销提示把“本轮已结束，下一轮已开始”这类标题直接接上“已撤销”；管理后台在终端已执行后显示“等待现场终端处理”；网站账户里“转至其他机台”在只有两台机台时应写出另一台的名称；机厅多于两台时“转至 其他机台”中间多一个空格（E 批，拼接方式的问题）。

**审查中顺带确认的事实**：终端的文字组件会自动在中文与英文、数字之间加空格（`PanguSpacing.kt`、`PanguText.kt`），所以终端里插值处缺的空格大多不必改；网站、服务端、Bot 没有这个处理。

**没有改任何界面文字**。下一步由维护者看对照表：A、B、C 建议整批改，D 需要逐条看，E 需要逐条决定。改的时候注意：`PairedMachineQueueNotices.kt` 与 `public-site/src/queue/pairedQueueCopy.js` 的句子要逐字相同、一起改；服务端 `COMMAND_TIMEOUT_DETAIL` 被 app.py 843–853 行的数据迁移按原文识别，改它时迁移里要保留旧文字；7.56 列的受保护文字不能动；测试里断言文字的地方要同步。

**待办（顺序按维护者的安排）**

1. 文案审查：维护者看对照表并决定，然后分批改、跑测试、汇报。
2. 文案审查之后：管理后台显示并可撤销待执行的远程命令；闭店前关机、次日开机不自动继续前一天的队列；管理后台未到场窗口按新规则置灰。
3. 其余仍在 7.55 的待办清单里（暂缓一次不能连续使用两次、终端关机期间签到时限、线上登记只凭 QQ、单人游玩允许被越过、已签到未游玩的线上登记能否暂缓等）。

本节新增的操作：

163. **清理演示环境**：停止两个预览进程；删除 `.claude/launch.json`、`.claude/`、`tmp/claude-work/demo/`（维护者同意）。
164. **生成审查材料**（都在 `tmp/claude-work/copy-review/`，不随项目提交）：清单、机械检查、近似句对、说明书、六份子任务结果、合并结果。
165. **六个审查子任务**：只读并写结果文件，没有改仓库。
166. **新增文档**：`docs/releases/copy-review-2026-11.md`（对照表）、本文件。没有改界面文字，没有提交。

### 7.58 文案审查：A 批（与实际行为不符）已改（2026-11-24）

维护者看了 A 批 29 条，答复与处理：

| 事项 | 维护者的答复 | 处理 |
| --- | --- | --- |
| 玩家自己退出排队时日志标题写“移除登记”（42） | “改” | 已改，见下 |
| 调整等待顺序的日志名单混进游玩中的登记（31） | “可以。之后希望在终端和网站能够可视化地查看调整前后的图形化的登记队列，低优先级” | 已改；可视化记入待办 |
| 网站“转至其他机台”在两台机台时写出另一台名称（2840） | “可以改也可以不改，低优先级” | 没改，记入待办 |
| 设置里“允许暂时离开”的说明（1201） | “不能这么说，意思应该是：允许玩家暂时离开现场。每次轮到时该登记时，将其移至队尾，直到玩家手动取消‘暂时离开’；第4次轮到时，登记会被系统移除（这里应该是移除）。” | 改为“允许玩家暂时离开现场。暂时离开的登记每次轮到时移至队尾，直到玩家手动取消暂时离开；第 4 次轮到时，系统会将其移除。”（功能名按规范不加引号，数字按规范空格） |
| 退回原机台时“未到场记录不会增加”（778、780） | “这句其实可有可无” | 保留：说明弹窗等多处都写了这一句，留着与它们一致 |
| QQ 号的用途（1595） | “颠倒一下，QQ号用于接收排队通知，也可以展示在登记菜单中，供现场联系。（是 也可以 还是 也会，要看设置允不允许调整）” | 查了设置：QQ 显示范围只有“允许网站显示”和“仅现场终端”两档，终端的登记菜单总会显示，所以写“也会”。改为“QQ 号用于接收排队通知，也会显示在登记菜单中，供现场联系。”；不连接服务端的本地版本没有排队通知，写“QQ 号会显示在登记菜单中，供现场联系。” |
| 绑定页“这份资料将绑定网页账户”（2793） | “应该叫网页玩家资料吧，确认一下” | 确认后保留“网页账户”，理由见下 |
| 其余 22 条 | 没有异议（“可以的话就下一批”） | 照表改 |

**“网页账户”与“网页玩家资料”**：现有文字里两个词分工不同。网页账户是登录用的（QQ 号加密码），“绑定”的对象是它：终端按钮“绑定网页账户”、手册一节“绑定网页账户”、服务端提示“这份玩家资料已经绑定网页账户”。网页玩家资料指登录后在网页上看到和修改的那份资料，以及在手机上创建的、一开始就绑定了网页账户的资料（“创建网页玩家资料”）。2793 说的是设置密码这一步把资料绑定到登录用的账户，所以用“网页账户”。现有文字里也有混用，例如手册写“登录网页玩家资料后”，这类问题留在 D、E 批处理。

**程序改动**

- 排队核心新增动作 `QueueAction.LeaveQueue`（玩家自己退出排队），效果与 `RemoveRegistrations` 相同，只是记录上能分清是谁决定的；机台停止使用时同样允许。终端的“退出排队”和网站、Bot 的远程退出改用它；工作人员的移除、管理后台的移除不变。
- 日志：`createQueueAuditLog` 新增参数 `removedByPlayer`，由 `createQueueAuditLogs` 按 `semanticAction is QueueAction.LeaveQueue` 传入。玩家自己退出时标题写“登记已退出排队”、内容写“‘小雨’已退出排队”；其他移除仍是“移除登记”“移除 ‘小雨’”。没有往终端处理远程命令的那段大方法里加参数（那段方法接近字节码上限），而是通过已有的 `semanticAction` 传过去。
- 调整等待顺序的日志名单改为只列等待中的登记。
- 设置里营业时间的说明、玩家资料里 QQ 号的说明，在不连接服务端的本地版本里不再提服务端和排队通知。
- 服务端：超时提示改为“这次操作”。服务端升级时有一段迁移按这句原文识别旧的超时记录，新增 `LEGACY_COMMAND_TIMEOUT_DETAIL` 保存旧文字，迁移同时识别新旧两种。

**改了的文字**（编号是对照表里的编号，对照表已标“已改”）：终端 862、863、2027、1201、1513、1751、1778、1939、2090、778、780、1187、1595、802；管理后台 287、362、376、516、521；网站 2656、2793、3377；服务端 3931、3934、3937、3938；另有程序改动 31、42。863 的后半句“此前因未签到而退出的登记不会恢复”没有动，等下面的问题定下来。3938 开头“现场终端已经接管”是否补宾语，维护者没有答复，没有动。

**新出现的问题：系统按规则让登记离开时写什么**。维护者在 1201 里说“第 4 次轮到时，登记会被系统移除（这里应该是移除）”。这与 7.34 里我提议、并写进 B 批的做法相反：我原先把“系统按规则让登记离开”与“玩家自己退出”都写“退出排队”（后者是“自动退出排队”）。现有文字里“自动退出排队”有 23 处（终端 11、网站 8、Bot 4），“自动移除”有 8 处（都在终端日志）。B 批里有 11 条正是把“自动移除”改成“自动退出排队”。这件事在给维护者看 B 批时先问清楚。

**测试**：`queue-core` 159 项，`app` 三个变体各 470 项（`AuditLogsTest` 新增 2 项，`PairedMachineQueueNoticesTest` 改 1 处断言），服务端 225 项，网页可以构建。界面改动没有在设备上看过。

**待办（新增）**：终端和网站可视化地查看调整等待顺序前后的登记队列（低优先级）；网站“转至其他机台”在只有一台可转入时写出机台名（低优先级）。

本节新增的操作：

167. **改动正式代码**：`QueueEngine.kt`（`LeaveQueue`）、`AuditLogs.kt`、`RemoteQueueOperations.kt`、`QueueTerminalUi.kt`、`QueueCloudSync.kt`、`PairedMachineQueueNotices.kt`、`ManagementSettingsUi.kt`、`ManagementUi.kt`、`MobileProfileCreationFlow.vue`、`PlayerAccountDialog.vue`、`QueueStatusPanel.vue`、`cloud-server/app.py`。
168. **改动测试**：`AuditLogsTest.kt`（新增 2 项）、`PairedMachineQueueNoticesTest.kt`、`cloud-server/test_app.py`。
169. **改动文档**：`docs/cloud-queue-sync.md`（引用的服务端原文）、对照表（标注已改）、本文件。没有提交。

### 7.59 文案审查：B 批已改；系统拿掉登记统一写“自动移除”（2026-11-24）

维护者的答复：

- 关于“网页账户”和“网页玩家资料”：“关于这个的名词和逻辑我们后续再讨论”。记入待议，这一轮没有动这两个词。
- 系统按规则让登记离开时的写法：我提议“只有玩家自己决定时写‘退出排队’，别人（工作人员或系统）决定时写‘移除’”，维护者答“那就这样统一”。B 批里相关的一组：“按上面的说法改”。
- 其余 B 批：“其他都按你的说法改”。

**新规则（写进了文案规范的用词表）**：退出排队只用于玩家自己决定离开；工作人员、管理后台拿掉登记写“移除登记”；系统按规则拿掉（线上登记超过 30 分钟未签到、轮到时未签到、暂时离开第 4 次轮到）写“自动移除”。这推翻了 7.34 里我的提议（系统拿掉写“自动退出排队”）。

**B 批的处理**

- 108 条照对照表改。其中“系统拿掉登记”一组按新规则改：终端日志原本就写“已自动移除”的 6 条不改；“将自动移除”改为“会自动移除”（规则性行为用“会”）；网站事件说明“……登记已退出排队”改为“……登记已自动移除”；“这份登记已经离开队列”（玩家退出命令的重复回执）改为中性的“这份登记已不在队列中”，因为登记也可能是被别人移除的。
- 服务端两处“QQ 没有关联玩家资料”的提示原本各由两段字符串拼成，合成同一句“这个 QQ 尚未关联玩家资料。请先在现场终端的玩家资料库中创建资料并填写 QQ。”
- 网站 3505（“小雨”已允许系统在……之间安排登记）与终端 730、766 是逐字相同的一句，一起去掉了引号后的空格。
- Kotlin 的字符串模板里，`$name` 后面直接接汉字会被当成变量名的一部分，所以去掉空格时一律写成 `${name}`。应用脚本 `tmp/claude-work/copy-review/apply_batch.py` 自动处理这一点，并按“原文出现在原行号附近”定位，原文找不到就报失败而不乱改（这次有 1 条因为跨行原文里有 CRLF 换行没找到，手工改了）。

**成批把“自动退出排队”改为“自动移除”**（维护者同意统一后做的，不在对照表里逐条列）：终端 11 处、网站 8 处、Bot 4 处，另有终端“现已退出排队”“整组会自动退出排队”“本次再轮到时会自动退出排队”、撤销提示的后半句、网站账户里的签到提醒（顺带补上漏掉的“超过 30 分钟”）、终端补全资料时的签到提醒（照 D 批 2157 的改法一起改）。Bot 两句“整组将退出排队”改为“整组登记会自动移除”。用户手册两处、README 两处、Bot README 一处同步。

**还没改、要在后面几批注意的**：C、D 批里有十来条建议是按旧做法写的“自动退出排队”，已在 `decisions.json` 里改成“自动移除”；对照表已同步。

**测试**：`queue-core` 159 项，`app` 三个变体各 472 项，服务端 225 项，Bot 82 项，全部通过；网页可以构建。更新了断言旧文字的测试：`AuditLogsTest.kt` 2 处、`PairedMachineQueueNoticesTest.kt` 3 处、`test_app.py` 3 处、Bot `format.test.js` 1 处。协议文档同步了两处引用。

本节新增的操作：

170. **改动界面文字**：B 批 108 条（终端、管理后台、网站、服务端），“自动退出排队”改“自动移除”约 30 处（含 Bot）。
171. **改动测试**：见上。
172. **改动文档**：`docs/copywriting-guide.md`（用词表新增“退出排队”与“移除登记、自动移除”的分工）、`docs/user-manual.md`、`README.md`、`koishi-bot/README.md`、`docs/cloud-queue-sync.md`、对照表、本文件。没有提交。

**暂停点（2026-11-24）**：维护者说“稍停一下，等我下次请求再继续”。停在 C 批之前，C 批一条都没有改。整理 C 批时已发现、下次要先处理的：

- 涉及“网页账户”“网页玩家资料”的 C 批条目（135、662、1680、2880、2888、3976、3988、4446、4447 等）要暂缓，维护者说这两个词后续再讨论。
- 276、1478（“新增分组”）属于规范“尚未决定”，应转入 E 批。
- 2464、2501、706 建议把“现场当前没有使用登记排队”改为“不接收新登记”，与 B 批刚改的服务端 4432 相反，应否掉。
- `PairedMachineQueueNotices.kt` 与 `pairedQueueCopy.js` 里的条目（C 批 744、774、775、779，D 批 743、745、748、767、769、773、777、3494、3506）要两边一起改、逐字相同；其中不少是“自动在两台机台之间安排登记”的新文字，对照表第 75–100 条维护者还没看过。

### 7.60 文案审查：C 批（同一件事写法不一）已改（2026-11-24）

维护者说“现在继续”后，我先处理了 C 批里不宜直接改的条目（见 7.59 末尾的暂停点）：涉及“网页账户”“网页玩家资料”的 9 条暂缓；“新增分组”2 条、引入“现场管理员”的 1 条、两台机台功能的 13 句转入 E 批；与 B 批相反的 3 条否掉；管理后台的 QQ 显示范围和性别选项按终端的叫法改（“允许网站显示”“仅终端显示”“不愿透露”）。剩 182 条给维护者看。

维护者的答复（三条意见，其余没有异议）：

| 事项 | 维护者的原话 | 处理 |
| --- | --- | --- |
| 工作人员记未到场并移除的说明 | “弱化工作人员的概念，实际上这个未到场是现场其他玩家点击的” | 终端里与排队操作有关的“工作人员”都去掉：“等待工作人员安排下一组”改为“需要手动让下一组进入游玩位置”（2 处），“暂缓状态会在工作人员下一次安排游玩位置时处理”去掉“工作人员”，日志里“等待工作人员安排”删去。文案规范里“移除登记”的说明改为“现场其他人（通常是其他玩家）或管理后台拿掉登记”。Bot 里“请联系现场工作人员”这类找人帮忙的句子没有动，与 E 批“现场管理员还是现场工作人员”一起定。 |
| 日志“昵称由‘小雨’改为‘小雨2’” | “这句不太好，主语没有” | 改为“‘小雨’的昵称已改为‘小雨2’”。玩家资料日志里的“昵称由……改为……”是同一条日志里逐项列出的变化，前面有标题，没有动。 |
| 日志来源“网页端”改“网站远程” | “能不能统一成网页端” | 反过来统一：终端日志、终端远程命令来源、网站日志筛选里的“网站远程”都改为“网页端”，管理后台原本就是“网页端”。手册和 README 同步。“现场未开启网站远程操作”是开关的名称，不是来源名称，没有动。 |

**C 批的处理**：179 条照对照表改，1 条跨行文字手工改。审查员只报了代表性的位置，改的时候发现同一句在服务端还有副本的，一并改了：“服务器鉴权配置无效”（共 1 处副本）和“现场规则暂不允许线上登记”（服务端 4 处、Bot 1 处）都统一成新写法。协议文档同步了引用。

**测试**：`queue-core` 159 项，`app` 三个变体各 472 项，服务端 225 项，Bot 82 项，全部通过；网页可以构建。更新了断言旧文字的测试：`AuditLogsTest.kt` 9 处、`MobileDeviceRegistrationTest.kt` 1 处、`RemoteQueueOperationsTest.kt` 3 处、`test_app.py` 6 处。

本节新增的操作：

173. **改动界面文字**：C 批 180 条，加上“网站远程”改“网页端”5 处、去掉“工作人员”4 处、服务端副本 5 处、Bot 1 处。
174. **改动测试**：见上。
175. **改动文档**：文案规范、`docs/user-manual.md`、`README.md`、`docs/cloud-queue-sync.md`、对照表、本文件。没有提交。

### 7.61 文案审查：D 批（难懂或口语化的句子）已改（2026-11-24）

维护者对 D 批的答复是“整批改”。

**处理**：D 批 204 条全部改完。195 条用脚本按对照表替换；3 条在相邻几行有相同文字，脚本不敢定位，手工改（玩家资料删除的日志标题、“如需继续游玩，需要重新加入队尾。”、“与‘某某’共同游玩”）；4 条是跨行文字或已被前几批改过一部分，手工改（终端一段说明、网站排队页 3 句）。

**改法有调整的两处**：

- 管理后台“未能确定是否允许系统在两台机台之间安排”的拒绝提示，与终端远程回执改成同一句：“未能确定是否允许系统在两台机台之间安排登记，请重新操作。”
- 服务端“终端绑定的机厅与当前服务器不一致”的提示：审查建议把两段字符串合成一句，但只替换了第一段，第二段原文还在，造成半句重复。已改成两段各占半句，读起来是一句完整的话。

**测试**：更新了断言旧文字的测试（`AuditLogsTest.kt`、`RemoteQueueOperationsTest.kt`、`QueueCloudCommandTest.kt`、`test_app.py`）。`queue-core` 159 项、`app` 三个变体各 472 项、服务端 225 项全部通过；网页可以构建。Bot 在这一批没有改动。

**文档**：`docs/cloud-queue-sync.md` 里逐字引用的服务端提示同步了 11 句；`cloud-server/README.md` 同步。对照表里 D 批的条目都标了“已改”。

下一步是 E 批：85 条需要维护者决定的用词、按钮名和几处程序拼接问题（另有 11 条涉及“网页账户”“网页玩家资料”，按维护者的意思暂缓）。我按话题分成组，每组给出建议，交给维护者决定。

本节新增的操作：

176. **改动界面文字**：D 批 204 条（脚本替换的 195 条中，终端和管理后台 145 条、网站 25 条、服务端 25 条；另 9 条手工改）。
177. **改动测试**：见上。
178. **改动文档**：`docs/cloud-queue-sync.md`、`cloud-server/README.md`、对照表、本文件。没有提交。

### 7.62 文案审查：E 批（需要维护者决定）已改（2026-11-24）

我把 E 批 85 条按话题分成 16 组，每组给出建议。维护者的答复：首页按钮“本轮结束”问“除了这两种还有没有更好的”，**其他都按建议改**。“本轮结束”这一组没有动，等维护者定。

**按组的处理**（文案规范已同步写入这些决定）：

| 组 | 处理 |
| --- | --- |
| 程序拼接出的问题 | 管理后台日志卡片的机台名不再重复；删去“关联登记：”加内部编号的一行；管理后台结果句作终端日志标题时去掉句号；终端“转至”按钮在多台可选时写“转至其他机台”（原来多一个空格），说明写“所选机台”；管理后台的“转至”在机厅只有两台机台时写出另一台的名称，确认按钮写目标机台名；纯本地版本不再说名称会显示在网站和 QQ Bot（设置页和首次设置两处）；“可以随时在‘更改游玩偏好’中取消”去掉“随时”（终端、网站、日志共 4 处）。 |
| 两个“应处于游玩位置” | 等待登记菜单里的改为“补入当前游玩位置”（与管理后台相同），弹窗标题同名加问号；位置菜单里的改为“推进至此位置”，弹窗“推进至此位置？”，确认按钮“确认并补记轮次”；管理后台“让此位置上场／让此等待位置上场？／确认上场”改为同样的三处文字。 |
| 队列顺序、排队顺序 | 管理后台调整的是等待顺序，6 处改为“等待顺序”；顺序变了时的拒绝改用现成的“现场队列已经更新，请刷新后重试”（终端远程回执和服务端）；转至说明删去重复的“和排队顺序”；其余 2 处改为“等待顺序”。 |
| 登记排队的开关 | 动作统一为“开启／关闭登记排队”（按钮“重新开启登记排队”，管理后台设置“开启登记排队”）；状态统一为“未使用登记排队”（“没有使用”“未启用”都改，含服务端 4 处、Bot 1 处）；请玩家排队的半句统一为“请按照现场顺序排队”。网站和 Bot 的状态标签“当前采用现场自然排队”“自然排队”是模式名，没有动。 |
| 新增、添加、创建 | 定为：创建新东西用“创建”，把已有的东西加进配置用“添加”，界面不用“新增”。改了 6 处（分组、日志、20 份登记的两句、网站事件类型标签、玩家资料同步日志）。 |
| 暂时不能 | 终端 7 处、Bot 1 处改为“暂不能”。 |
| 暂缓一次自动结束 | 统一为“自动解除暂缓”；“画面位置会按后续轮换重新计算”改为“队列会显示预计下次游玩的位置”；Bot 的两句“之后自动恢复”同改。 |
| 日志标题分隔号 | 机台日志标题改为“机台 A：……”，标题内部的“·”改为“，”（例如“机台 A：未到场，已移至队尾”）。 |
| 机台名前的空格 | 规则定为“程序填入的机台名称，前面紧跟中文时空一格”。补了约 35 处（日志、远程回执、终端提示、管理后台、Bot 3 处、网站 2 处）；系统自动转至的日志标题改为“登记已自动转至 机台 B”。服务端几句只在快照数据格式出错时出现的校验提示（“停止使用的机台 A 必须包含停止原因”之类）没有动。 |
| 两台机台功能的说明 | 按建议改，终端与网站逐字相同；规则用“会”，勾选框称“这项选择”，只写“自动转至”。两台机台功能对照表（`docs/releases/dual-machine-queue-copy.md`）第十三节后加了一段说明，列出改了哪几行。 |
| 云端 | 3 处界面文字改为“服务端”；“清理 N 份旧资料副本”核对代码后改为“合并 N 份重复的玩家资料”（服务端认出同一玩家在手机上重复创建的资料，本机删去重复的那份）。 |
| 兼容服务端连接 | 改为“已连接旧版服务端”；首次设置结尾的状态改用与详情弹窗相同的判断，尚未核对时显示“等待核对服务端”。 |
| 编辑与修改 | 不改，分工写进规范。 |
| 忘记密码找谁 | 只有管理后台能做的事统一写“管理员”：网站两处密码说明、终端 1 处、Bot 5 处。 |
| 其他单条 | 固定组合日志找不到名字时不再写“原搭档／新搭档”；“沿用常用设置”改为“沿用其默认游玩偏好”（核对过代码）；“处于游玩位置 A 的登记不能组成固定组合。”；签到说明改为“开始正常轮候”；转入说明删去“才能参与游玩位置分配”；旧二维码“不能再使用”；网站性别“不愿透露”；日志范围筛选“系统”改为“机厅”（终端、网站）；服务端拦截 Bot 修改两项资料设置的提示改为说明具体是哪两项。带协议字段名的服务端提示不改。 |

**顺手修的文档**：用户手册里遗留的 5 处“等待顺序末端”改为“队尾”，2 处“默认偏好／本次偏好”改为全称（这两条规则此前已定，手册没跟上）；手册中“应处于游玩位置”“请在现场自然排队”“重新开放”“新增”的引用同步；README 一处“现场管理员”；协议文档一处引用。

**测试**：更新了断言旧文字的测试（`AuditLogsTest.kt`、`PairedMachineQueueNoticesTest.kt`、`RemoteQueueOperationsTest.kt`、`MobileDeviceRegistrationTest.kt`、`QueueHomeFeedbackTest.kt`、`QueueRuleSettingsTest.kt`、Bot 的 `format.test.js` 2 处）。`queue-core` 159 项、`app` 三个变体各 472 项、服务端 225 项、Bot 82 项全部通过；网页可以构建。界面没有在设备或浏览器上看过。

**还没做**：首页按钮“本轮结束”的改名（维护者在考虑）；涉及“网页账户”“网页玩家资料”的 12 条（后续讨论）；服务端提示补句号（约 460 句，含不能改的受保护文字）。

本节新增的操作：

179. **改动界面文字**：E 批脚本 `tmp/claude-work/copy-review/batch-e/apply_e.py` 111 处，机台名空格 26 处，转至按钮的程序拼接 2 个文件。
180. **改动测试**：见上。
181. **改动文档**：文案规范、用户手册、README、`docs/cloud-queue-sync.md`、两台机台功能对照表、文案审查对照表、本文件。没有提交。

**补充（同日）**：维护者定了首页按钮：“改为结束本轮”。终端首页按钮、位置菜单里的两处、以及“……未执行”提示里用的操作名都改为“结束本轮”；手册里引用按钮名的 7 处、README 1 处同步。手册标题“本轮结束后推进队列”和“请在本轮结束、这份登记回到等待顺序后……”里的“本轮结束”是普通说法，不是按钮名，没有改。文案规范用词表加了这一行。`app` 三个变体各 472 项测试通过。

182. **改动界面文字**：终端“本轮结束”改为“结束本轮”（6 处）。手册、README、文案规范、对照表同步。没有提交。

### 7.63 文案审查：服务端提示补句号（2026-11-24）

维护者说“开始补句号”。

**范围**：`cloud-server/app.py` 里给人看的提示，共 561 句补上句号：接口返回的 `error`（含条件表达式里的两种写法）、`ValidationError` 的说明、校验函数返回的“提示＋状态码”、两条“处于游玩位置的登记不能……”常量，以及两处兜底的 `detail`／`message`。标题、标签、日志原因（“管理后台……”）、机台名、内部 `RuntimeError` 不加。原来就以句号、问号、冒号结尾的不动。用的是脚本 `tmp/claude-work/copy-review/fullstop/add_full_stops.py`（按 Python 语法树定位字符串，改完再解析一遍确认语法正确），改了哪些在同目录 `changed.json`。

**没有补的受保护文字**（已发布的客户端逐字比较）：`不支持的队列数据版本`、`不支持的队列协议版本`、`移动设备登记会话参数不完整`、`请求包含不支持的排队操作字段`、`请求包含不支持的线上登记字段`，以及含“不能包含未知字段”的句子。核对过：改完后这些文字在 `app.py` 里与原来逐字相同。这份清单写进了文案规范新加的“不能改的服务端提示”一节。

**查过的拼接**：
- 服务端有一处把校验提示嵌进另一句（“机台名称无效：……。”），改为先去掉内层句号再加，避免“。。”。另一处是逐行列出（“最近事件第 1 条的……”），不受影响。
- 终端、管理后台、Bot 显示服务端提示时都是原样显示，不会再加句号；0.13.6 已发布的代码也查过，同样没有。
- 网站有一处在提示后面接“请刷新最新状态后重新确认。”，改为提示已有句号时不再补，并去掉中间的空格。

**同步**：`test_app.py` 43 处断言、`docs/cloud-queue-sync.md` 75 处引用加了句号（逐条核对过都是成对的代码引用，没有误改正文）。服务端 225 项、Bot 82 项测试通过，网页可以构建。

至此文案审查的 A～E 批和补句号都已完成，都没有提交。剩下：“网页账户”“网页玩家资料”的名词和分工（12 条，维护者说后续讨论），以及文案审查之前说好的三件事（管理后台待执行命令队列与撤销、隔夜不接续前一天的队列、管理后台未到场弹窗置灰）。

本节新增的操作：

183. **改动服务端提示**：561 句补句号，1 处防止重复句号。
184. **改动网站**：失败提示的拼接。
185. **改动测试和文档**：`test_app.py`、`docs/cloud-queue-sync.md`、文案规范、对照表、本文件。没有提交。

### 7.64 “结束本轮”弹窗：说明文字、下一组的图形、按钮并排（2026-11-24）

维护者的要求：说明改为“如果结束本轮并开始下一轮，将立刻由位置A1的玩家进入游玩位置，后续等待位置将依次前移。如果不希望让他们立刻进入游玩位置，请选择‘仅结束本轮’。”；说明下方按首页的样式画出位置 A1 及其中的登记，然后才是按钮；第二、第三个按钮并排占一行。

**做法**（`QueueTerminalUi.kt` 的 `RoundEndConfirmation`）：

- 说明文字按维护者的原话，按文案规范做了三处小改：“立刻”改为现有文字通用的“立即”；“让他们”改为“其”（一份或两份登记都通顺）；句首保留原来的“本轮玩家的登记会回到队尾。”，免得读者以为本轮玩家被移除。
- 画的不是固定的位置 A1，而是这次实际会进入游玩位置的那一组（排队引擎对“结束本轮并开始下一轮”的预演结果）。通常就是首页的位置 A1；位置 A1 有登记暂缓一次、暂时离开或待签到时，首页本来就把这些登记画在预计的位置上，所以画出的仍与首页一致，原有的橙色说明继续解释原因。实际进入的一组对不上首页任何一个位置时（例如由另一机台自动转至的登记），标签写“下一组”，说明写“下方的玩家将立即进入游玩位置”。没有可以进入的登记时不画图，说明写游玩位置会保持空缺。
- 图形直接用首页的 `QueuePosition`，新增 `interactive = false` 参数：样子与首页相同，但点了不会打开菜单。
- 第二、第三个按钮放进同一行，红色按钮的文字较长，按 1.5 : 1 分宽度；弹窗宽度从 480 加到 540，保证两个按钮的文字都在一行内。
- 闭店收尾期间只有“移除本轮玩家的登记并开始下一轮”一个选项，这一版没有改。

**验证**：终端版本可以编译，`app` 本地和终端变体的单元测试各 472 项通过。这台电脑没有安卓模拟器，也没有连接设备，**没有在屏幕上看过效果**。用户手册“结束本轮”一节加了一段说明。

186. **改动终端界面**：见上。没有提交。

**补充（同日，维护者看过 7.64 后）**：维护者认可“对不上首页位置时标‘下一组’”的设计，并提出两点，已改：

- **无人等待时**：核对排队引擎（`RoundPlanner.create`）：结束本轮时本轮玩家先回到队尾，再从等待顺序里选下一组；没有其他登记可以进入时，选中的就是本轮玩家，游玩位置不变，只是重新计时。弹窗对这种情况单独写：“当前没有其他登记在等待。如果结束本轮并开始下一轮，游玩位置中的玩家将保持不变，并重新开始计时。如果需要空出游玩位置，请选择‘仅结束本轮’。”等待中有登记但都不能进入（暂缓一次、暂时离开、待签到）时，第一句改为“当前没有可以进入游玩位置的其他登记。”，橙色说明照常解释原因。这两种情况不画图。
- **首页“结束本轮”按钮加图标**：按钮文字左侧加一个回车形状的箭头（↵），表示推进到下一组。应用只带了 Material 的核心图标，没有回车图标，为此引入整套扩展图标不值得，所以用 `Canvas` 自己画（`RoundAdvanceIcon`），颜色跟随按钮文字。`SmallActionButton` 新增可选的 `leadingIcon` 参数，其他按钮不受影响。位置菜单里的“结束本轮”没有加。

`app` 三个变体各 472 项测试通过，终端版本可以构建。仍然没有在设备上看过；按同样的尺寸画了一张按钮示意图给维护者（`tmp/claude-work/round-end-button-mock.png`）。手册同步。

187. **改动终端界面**：见上。没有提交。

### 7.65 安卓模拟器；界面审查的准备；“开放”的遗漏（2026-11-24）

维护者想做界面优化（层级、控件位置和大小、颜色、美观），并补充两点要求：“分辨率可以按 720p 来，但是针对 1080P 和 2K 的屏幕应该适当的放大控件大小，提高最小宽度”；“按钮控键何时选择什么样的格式应当有原则，保持统一”。现场终端是 10 英寸平板。

**模拟器**（维护者同意下载安装）：这台电脑原来没有安卓模拟器。`sdkmanager` 连不上下载服务器（Java 不走会话的网络代理），所以用 curl 从 Google 官方地址下载模拟器和 Android 15 平板系统映像（核对过 SHA-1），解压进 `/opt/homebrew/share/android-commandlinetools`，手写 AVD 配置 `~/.android/avd/maimaiq_tablet`（1920×1200）。审查时用 `adb shell wm size 1280x720` 和 `wm density 160` 模拟 720p。截图和点击脚本在 `tmp/claude-work/ui-review/`。

**截过的画面**：首次设置 4 页、空首页、批量创建、首页（含暂缓一次、共同游玩预览）、创建登记 3 步、登记菜单、暂缓一次或暂时离开、确认暂缓一次、结束本轮弹窗（7.64 的新版，显示正常）、位置菜单、游玩位置菜单、更多、应用设置、操作日志、机台详情，以及 1080P 低密度屏（界面宽 1920）的首页。

**顺手修的文字**（E 批“登记排队的开关统一为开启”的遗漏）：“重新开放”5 处改为“重新开启”；管理后台 3 处“开放”改为“开启”（请求失败提示、状态值、开关状态）；“登记开放状态”改为“登记排队状态”（服务端 3 处、终端 3 处、协议文档和 README 各 1 处、测试 1 处）；终端日志说明“登记排队开放和关闭”改为“开启和关闭”。应用内历史版本说明里的“开放”没有动。服务端 225 项、`app` 三个变体各 472 项测试通过。

界面优化的建议已交给维护者挑选，还没有改界面。

188. **安装模拟器**：见上。
189. **改动界面文字**：见上。没有提交。

### 7.66 界面规范初稿（2026-11-24）

维护者选择“先写界面规范”。新文件 `docs/ui-guide.md`，与文案规范并列，README 和文案规范加了链接。每条规则标了来源：维护者的两条要求（以 720p 为基准、1080P 和 2K 放大控件；按钮样式要有原则并统一）标“已定”，代码里已经如此的标“现状”，我提出的标“建议”，等维护者确认。

内容：屏幕尺寸与放大（基准 1280 × 720，放大倍数取宽、高比例的较小值，限制在 1～1.6 倍；可点区域至少 48）；颜色（按含义列出 `QueueUiTokens.kt` 的全部颜色，提醒色和完成色建议补成正式名称，普通入口不用蓝底）；字号（从现有 20 种收为 7 种，最小 11，首页昵称 15、状态 12）；间距与圆角；按钮和可点控件（10 种，各自只用在规定场合，7 条规则）；菜单项的统一样子；弹窗、全屏页面、排队首页的结构；实施与检查；尚未决定的 3 项。每条“建议”都写了现状，便于对照。

依据是 7.65 的模拟器截图和对代码的统计（字号种数、直接写的颜色、按钮组件的样式）。还没有改界面。

190. **新增文档**：`docs/ui-guide.md`；改动 README、文案规范、本文件。没有提交。

### 7.67 等比例放大已实现；全面审查和重新设计方案（2026-11-24）

维护者说明“提高最小宽度”指安卓系统设置里的“最小宽度”，“我不知道实际怎么改会好一些，听你的，我的意思就是等比例放大”；并说审查中“可较大规模推翻当前设计，但也不当然推翻，总之需要全面考虑，保证符合设计规范，保持统一性”；随后补充“除了 UI，软件的操作逻辑也可以改”。三条都写进了界面规范（“已定”）。

**等比例放大**（已实现）：新文件 `TerminalScale.kt`。应用内部按屏幕算倍数（界面宽 ÷ 1280 与界面高 ÷ 720 中较小的一个，1～3 倍），整体放大字、控件和间距，相当于只对本应用把“最小宽度”调回 720，不改设备设置。同时把放大后的屏幕尺寸提供给读取屏幕宽度的代码，避免两套尺寸不一致。只用于现场终端，管理后台不受影响。新增 `TerminalScaleTest.kt`（3 项）。在模拟器上按 1280 × 720、1920 × 1080、2560 × 1440（都是密度 160）截图，后两者版面与 720p 相同；1080P 下游玩位置 B 的标题末尾“钟”字被截掉，是倍数 1.5 时宽度取整造成的，重新设计首页时一并处理。

**补截的页面**：编辑登记（选择机台、调整顺序）、未到场、退出排队、转至、更改游玩偏好、应用设置的排队规则和机台与分组、创建登记的选择机台。

**重新设计方案**：`docs/releases/ui-redesign-2026-11.md`。内容：审查结论（同类控件有 9 种样子；首页空间分配；全屏流程版式松散；字号和颜色太散）、逐页方案（基础组件、首页、登记菜单、位置菜单、弹窗、全屏流程、设置、日志、编辑登记）、操作流程的改动 6 条、分 5 个阶段实施（每阶段截前后对比图）、需要维护者决定的 3 点（批量创建登记移入“更多”；首页昵称 17 号；日志机台标记改中性灰）。界面还没有按方案改。

191. **改动终端代码**：`TerminalScale.kt`、`MainActivity.kt`、测试 1 个文件。
192. **新增文档**：重新设计方案；改动界面规范、本文件。没有提交。

### 7.68 界面重新设计第一阶段（基础）完成（2026-11-24）

维护者对方案的决定：“批量创建登记”移入“更多”（同意，并问首页还能放什么按钮）；首页昵称 17 号（同意）；日志机台标记改中性灰（同意）。然后说“开始第一阶段”。实施中补充两条：“有的按钮如果不需要功能介绍，或者点开之后弹出的菜单足够解释其含义，就直接采用普通按钮，不需要用菜单项作为按钮”；“有些按钮的标题足以概括其含义，直接使用普通按钮，不加小标题”，并问我的看法。

**改动**（`QueueUiTokens.kt`、`QueueUiState.kt`、`QueueTerminalUi.kt`）：颜色名称、`QueueTextSize`、`QueueSpacing`、`MinTouchTarget`；直接写的颜色换成名称；9、10 号字改 11 号；首页小按钮 48 高；次按钮、危险确认按钮字 15；新增 `DangerSecondaryButton`，用于“移除本轮玩家的登记并开始下一轮”和“不使用，开始新的队列”（两者都还有下一步确认）；`MenuAction` 新增 `explained`，默认按普通按钮画（`MenuButton`），只有候选登记列表和 `ActionRow` 的选择列表按带说明的菜单项画；`CompactActionGrid` 两列等高；“更多”改为两列、宽 560；“撤回至队首”并入同一组网格；日志分类标记中性灰，范围筛选“系统”改“机厅”。“更多”里给三个入口加过说明小字，随后按维护者的补充又改回不带说明的按钮。

**验证**：`app` 三个变体各 475 项测试通过；在模拟器 720p 下截了改前改后：登记菜单、游玩位置菜单、“更多”、结束本轮弹窗、恢复弹窗、日志页。界面规范同步了菜单入口的新规则（标“已定”）和已实现的各项（改标“现状”）。

193. **改动终端界面**：见上。没有提交。

### 7.69 登记菜单和位置菜单按“谁来操作”分组（2026-11-24）

维护者：有的按钮是玩家自己点的，“未到场”是其他人代为操作的，应单独分开；分组不必照现在的形式；“未到场”应写“此玩家未到场”并单独占一个位置。我提出三块（本人操作／与现场不一致时／其他玩家代为报告）的方案；维护者同意，要求去掉“本人操作”标题、本人操作再分小组，并“可以成为一个原则”。我把第二句理解为同时同意“这名玩家未到场”的写法（文案规范称呼一名玩家用“这名玩家”）。

**改动**：`QueueUiState.kt`（`SelectedRegistration.initialAbsenceChoice`）；`QueueTerminalUi.kt`：登记菜单和位置菜单重排（分组见重新设计方案第七节）；新组件 `OnBehalfSection`（底部浅色区域，标题“其他玩家代为操作”，覆盖未到场和移除两类，所以没用方案里的“代为报告”）；“暂缓一次”“暂时离开”直接打开各自的确认页；登记菜单的信息合成一行（“21:20 创建·尚未游玩”）；处于游玩位置时不显示“暂时不能游玩时”。界面规范新增“菜单的分组”一节（已定）。

**验证**：`app` 三个变体各 475 项测试通过；模拟器 720p 截图：等待中登记、游玩中登记、位置菜单、游玩位置菜单、直接打开的确认暂缓一次，均一屏放下。

194. **改动终端界面**：见上。没有提交。

**7.69 补充（同日）**：维护者接着提了三点，都已改：
- 认领登记、退出排队、暂缓一次、暂时离开放在一起，即“这份登记”一组。
- “撤回至队首应该也是一个高敏感操作，并不是正常排队会使用的”。撤回至队首、补入当前游玩位置、推进至此位置三个纠正操作移到菜单最下方的“与现场不一致时”区域，按钮白底红字，位置在“其他玩家代为操作”之下。组件改名为 `SeparateMenuSection`，带标题参数。
- “暂缓一次和暂时离开我希望合并成一个入口，比如叫‘延后这份登记’”。改为一个入口，点开后是重新设计的对照页（新组件 `AbsenceChoiceCard`）：两张卡片并排，各写一句概括和“适用”“轮到时”“结束”三点。

另外：
- 上一步把选择弹窗里这两项的说明一并去掉了，属于误伤，这次已恢复，以对照卡片的形式呈现。
- 处于游玩位置时，“延后这份登记”和“转至”不显示。界面规范新增原则：不适用的不显示，暂时不能用的灰色显示。
- 文案规范新增“延后这份登记”“这名玩家未到场”两行。
- `app` 三个变体各 475 项测试通过，模拟器截图已检查。

### 7.70 登记菜单的细节；“撤回至等待顺序”；玩家资料创建流程列入待办（2026-11-24）

都按维护者要求：
- “认领登记”缩为“认领”蓝字，放进“临时登记”气泡内；“编辑玩家资料”缩为“编辑”，放进“玩家资料”气泡内。新组件 `ActionPill`，整个气泡可点，可点区域 48 高。维护者第一次看到“认领”放在气泡右边，指出应在气泡内部，已改。
- 名称旁的铅笔：由玩家资料创建的登记弹出 `DropdownMenu`，选“修改这份登记的昵称”或“编辑玩家资料”。维护者原话是“更改这份登记的昵称”，按文案规范“昵称用修改”写作“修改”。
- “撤回至队首”改名“撤回至等待顺序”，原因是“这样不会让人认为游玩位置也属于队列”。终端、管理后台、网站、服务端、Bot、测试、手册、协议文档共 45 处，文案规范新增一行。描述结果的“回到队首”没有改，因为队首仍指等待顺序的最前面。历史记录类文档没有改。
- 服务端 225 项、Bot 82 项、`app` 三个变体各 475 项测试通过，网页可以构建。
- 为测试，模拟器里建了一份玩家资料“Mika”（QQ 用假号码 10001000），只在模拟器里。
- **待办，紧接着做**：玩家资料的创建页面推倒重做，改为引导性更强的分步流程。

195. **改动界面和文字**：见上。没有提交。

**7.70 补充（同日）**：
- “与现场不一致时”区域只有一个按钮时，小字改为这个按钮的说明。`SeparateMenuSection` 新增 `singleActionDescription` 参数。撤回的说明按单人、固定组合、共同游玩三种情况分别写，补入、推进也各有说明。
- 维护者新增待办：“首页的队列页面我也想重新设计，加入待办”。排在玩家资料创建流程之后，见重新设计方案第八节。
- `app` 三个变体各 475 项测试通过。

### 7.71 登记菜单的头像；“由现场玩家报告”；移除时记录操作者（2026-11-24）

维护者要求（本版本完成）：
- 登记菜单加入头像。临时登记或没有头像的资料按现在的格式；有头像时，最上方显示圆形头像和昵称，整体居中。
- “与现场不一致时”放到“其他玩家代为操作”上面，后者更名为“由现场玩家报告”。
- “移除这组登记”上移至“此位置中的登记”。点击时询问操作者：“登记1”“登记2”（实际写昵称）和现场其他玩家。询问和确认按钮在同一页，是勾选框，勾选前确认按钮置灰。
- 日志里保留操作者信息。

**做法**：
- **头像**：`RegistrationActions` 新增 `playerProfile` 参数。`avatarReference` 不为空时，头像 64 并居中，标签行、信息行居中，昵称左侧加一段与铅笔等宽的空白，使昵称保持正中。QQ 号并入标签行，以节省高度。
- **移除**：新对话框 `RemoveWithOperatorConfirmation`。位置菜单的移除改为“此位置中的登记”一栏右侧的红字（`InlineTextAction` 重新加回，带 `destructive`）。原来单份登记的“移除登记”走的是退出排队确认，现在也走新对话框；选本人时仍按退出排队处理，日志正文写“由‘某某’本人操作。”。
- **日志**：`updateQueueByAction`、`updateQueueByPlan`、`updateQueue`、`appendQueueAuditLog` 新增 `auditDetailNote`，追加在日志正文末尾，例如“由现场其他玩家操作。”。
- **顺带修正**：日志标题“移除登记”改为规范的“登记已移除”，正文“移除 ‘某某’”改为“‘某某’的登记已移除”，`AuditLogsTest` 同步 5 处。
- **“由现场玩家报告”**区域只有一个按钮时，说明改为按钮的作用。

**验证**：
- `app` 三个变体各 475 项测试通过。
- 模拟器上看过：有头像的登记菜单、位置菜单、移除确认页（未勾选和勾选后）、日志里的操作者。
- 为了看头像效果，把模拟器里测试资料 Mika 的头像引用设成了一张公开的占位图片（gravatar 的通用图案，用的是全零的虚拟哈希）。只在模拟器里。

196. **改动终端界面和日志**：见上。没有提交。

### 7.72 单选圆点、小节标题标准、铅笔位置；中点两侧留白；退出排队的本人提醒（2026-11-24）

维护者在 7.71 之后的意见，以及本节完成的改动：
- **单选圆点**：“这个打勾的勾字体不对，而且这看着像复选框，应该是单选框，改成圆点也可以。注意勾选框的勾字体要保持统一”。移除确认页“由谁操作”改为 `RadioDot`（圆环，选中后蓝色中心圆点）。界面规范按钮原则第 5 条：勾一律用 `StraightCheckMark`，不用文字“✓”；只能选一项时用单选圆点。
- **铅笔位置**：“修改的铅笔图标放在顶部区域的右下角或者右上角，不要丢在中间”。有头像、标题居中时，铅笔放在顶部区域右上角。
- **小节标题标准**：“标题‘此位置中的登记’和右侧按钮字体大小统一、对齐，关于这个字体请设置一个标准，不要想到多少用多少”。`MenuSectionHeader` 统一为 12 号 SemiBold 标题、横线、右侧 12 号 Medium 操作，可点区域 48 高；`SectionLabel`、`InlineTextAction` 删除。写入界面规范。
- **“由现场玩家报告”的说明**：维护者认为“人不在现场”不好听，给出“即将轮到的这组玩家不在现场。现场玩家应将他们记录为未到场，并选择如何处理这组登记”，允许微调。实际写作“即将轮到的这组玩家不在现场。现场玩家应将其记录为未到场，并选择如何处理这组登记。”，单份登记写“这名玩家……这份登记”；处于游玩位置时“即将”换成“已经”。
- **中点两侧留白**：“·符号左右两侧应有适当的空白，两个半角空格似乎又略宽，你调整一下，保守方案就采用两个半角空格即可”。
  - 原来的做法（0.13.6 定）：文字一律写紧凑的“甲·乙”，`panguSpacing` 删掉中点两侧的空格；首页顶部那一行的中点单独画在 12dp 的格子里。
  - 现在：文字仍保持紧凑，存储、同步、日志里的字符串都不变；只在显示时，由 `PanguText.kt` 的 `withAppMiddleDotSideSpace` 给每个中点加字距样式。Android 的字距平均分在字形两侧，所以中点两侧各多出 0.15 倍字号（`APP_MIDDLE_DOT_SIDE_SPACE_EM`）。字符串版 `Text` 遇到中点时转交带样式的版本处理。
  - 首页顶部“2 台机台的等待顺序彼此独立·当前共 7 份登记”改为一段带样式的文字，`TerminalMiddleDot` 删除。那里的中点颜色由第三级文字色变为次要文字色，与机台标题“左侧·机台 A”一致。
  - 选值依据：在模拟器 720p 下逐像素量了中点与两侧文字之间的空隙。原来是 1～2 px；0.15 倍字号是 3～5 px；临时改成 0.25 倍字号（相当于每侧一个半角空格）是 5～6 px。三者对照图是 `tmp/claude-work/ui-review/dot-compare.png`（自上而下：原来、0.15、0.25）。最后取 0.15，比半角空格略窄。
  - 新增单元测试 `drawnMiddleDotsGetEvenSideRoomWithoutChangingTheText`。
  - 文案规范、界面规范各加一条。
- **退出排队的本人提醒**：维护者要求在单份登记的退出排队确认页另起一段，说明只能由本人退出，他人不在现场时应使用未到场流程。
  - 维护者给的原句是“退出排队只能由登记者本人操作，现场其他玩家不得因认为它已离开而代替别人退出排队。如果该登记轮到时不在现场，现场其他玩家可以通过‘这名玩家未到场’功能，根据引导流程正确处理这份登记。”
  - 随后维护者补充：“这个文案需要你润色，以后不再提醒，均为如此”。已记为长期规则：维护者口述的文案一律先润色，再报告实际写入的文字。
  - 实际写入：“只有玩家本人可以退出排队。即使认为这名玩家已经离开，现场其他玩家也不得代为退出。如果这名玩家轮到时不在现场，现场玩家应点击‘这名玩家未到场’，按引导处理这份登记。”
  - 显示方式：放在说明下方的提醒色提示框里（`WarningText` 字、`WarningBackground` 底）。`RemoveRegistrationConfirmation` 新增可选参数 `notice`，只有退出排队传入。

**验证**：
- `app` 三个变体各 476 项测试通过。
- 模拟器上看过：首页、恢复队列弹窗、登记菜单、位置菜单（中点留白和“由现场玩家报告”的说明），以及退出排队确认页。
- 未在现场终端的真机上看过。真机系统字体如果不是 Roboto，中点字形本身的宽度会略有不同；增加的空白按字号计算，不受影响。

197. **改动终端界面和文案**：见上。没有提交。

### 7.73 玩家资料改为分步创建（2026-11-24）

维护者回复“开始”，确认了 7.70 之后提出的分步方案。中途维护者另提：“请用中文思考”（之后的思考摘要用中文）；以及“之后可以考虑一下，正常加入排队的流程，这几个按钮是不是不好看，UI可以重新考虑一下”，已列为重新设计方案第八节第 3 条待办。

**做法**：
- `PlayerProfileEditorScreen` 整个重写，调用处和保存逻辑（`savePlayerProfileDraft`）不变。所有创建入口最终都走 `openLocalPlayerProfileEditor`，所以资料库、认领登记、为朋友创建登记、扫码弹窗里的“在终端创建”都用上了新流程。联网且同步开启时，“创建玩家资料”仍先给出扫码创建网页资料的弹窗，这部分没有改。
- 步骤：
  1. 填写昵称和性别：性别改为三张带文字的卡片，男、女、不愿透露，与网站、管理端、手册一致。
  2. 填写 QQ 号：联网版下方有两张 QQ 显示范围卡片。
  3. 选择默认游玩偏好：三张卡片。
  4. 设置排队通知：仅联网版有这一步。总开关改名“接收排队通知”，五类通知不变，小节标题右侧有“恢复推荐设置”。
  5. 确认玩家资料：摘要每一步一行，右侧是“修改”。
  
  纯本地版共 4 步。
- 检查时机：“下一步”始终可点，点了才检查本页内容，不合格时在输入框下方显示红字。昵称或 QQ 号与已有的重复时，输入时就提示。键盘上的“下一项”和“完成”键也能进入下一步。换页时收起键盘。
- 返回：“← 返回”回到上一步。第一步返回时，沿用原有的“放弃未保存的修改？”确认。从摘要点“修改”进入某一步后，主按钮写“完成修改”，返回也回到摘要。
- 编辑现有资料时，直接打开摘要，没有进度条，主按钮是“保存玩家资料”。编辑页的昵称不再一打开就全选。
- 共用组件：
  - `WizardPage` 新增 `progress`、`topAligned` 两个参数，其他页面不受影响。
  - `OptionCard` 新增 `height`、`leading` 两个参数，说明为空时不显示说明那一行。
  - 首次设置的进度条抽成 `StepProgressBar`。
  - `MenuSectionHeader` 新增 `actionDisabledReason`：右侧操作灰色显示，点击说明原因。
- 删除了只在旧编辑页使用的 `GenderChoice`、`ProfilePreferenceSelectionRow`、`ProfilePrivacyChoice`。
- 新增 `queueNotificationSummary` 及测试 `ProfileSetupSummaryTest`（2 项）。

**与方案的出入**：见重新设计方案第九节。要点如下：
- 纯本地版的 QQ 号仍然必填。原因是现有规则把没有有效 QQ 的资料视为需要补全。
- 通知是五类，不是四类。
- 不标“推荐”。
- 不另设“完成创建并继续”。

**文字**：新写的标题、说明和按钮，都按文案规范写成正式书面语：
- 标题：填写昵称和性别、填写 QQ 号、选择默认游玩偏好、设置排队通知、确认玩家资料、编辑玩家资料。
- 按钮：下一步、完成修改、完成创建、恢复推荐设置。
- 三张游玩偏好卡片的说明去掉了与页面说明重复的“每次使用这份资料创建登记时”。
- 文案规范、界面规范（新增“分步填写”一节）、手册（新建、编辑玩家资料两节）已更新。
- 手册里顺带修正了沿用旧菜单名的 4 处：认领改在“临时登记”标签里，“暂缓一次或暂时离开”入口改为“延后这份登记”。

**验证**：
- `app` 三个变体各 478 项测试通过。纯本地版可以构建。
- 模拟器上逐页看过联网版的整个流程：
  - 空昵称提示、QQ 号过短、QQ 号重复。
  - 恢复推荐设置。
  - 从摘要修改、摘要返回。
  - 完成创建后进入资料详情页。
  - 编辑时打开摘要、修改后返回。
- 纯本地版：4 步，QQ 页没有显示范围，摘要没有通知行。

**模拟器里的测试数据**：
- 终端版新建了资料“Rin”（QQ 用假号码 20002000）。
- 另外装了纯本地版（包名 `com.abcccc.maimaiqueue.local`），机厅名称填了 Test，没有创建资料。
- 以上都只在模拟器里。

198. **改动终端界面和文档**：见上。没有提交。

### 7.74 首页重新设计（2026-11-24 至 11-25）

**过程**：
- 维护者选择“首页队列页面”作为下一项。我做了两个方案的示意图：A 保留右侧栏，B 去掉右侧栏、队列全宽。
- 维护者问“顶部的横幅无内容的时候显示什么”。我的回答是：单独占一行的横幅会推动队列，所以改放在顶栏中间。
- 维护者认可 B，并提出：电量和同步移入“更多”，出问题时“更多”变色；“加入排队”和“更多”紧挨、左边是时间；横幅以时间右侧的竖线为界占用左侧；两个按钮相融合。
- 我对照了三种按钮组样式，推荐“实心蓝 + 浅蓝”，出问题时“更多”为实心橙色。维护者回复“可以”，并要求机台标题和“结束本轮”一起放大、登记只改变高度。
- 实施中维护者又提出三点：文字和图标居中、横幅上下空白、严格按示意图并降低登记的不透明度。原话和逐页做法见重新设计方案第十节，规则见界面规范“排队首页”“字号”两节。

**代码**：
- 顶栏与横幅：
  - `AppHeader` 重写。新增 `HomeActionGroup`（按钮组）、`HomeFeedbackBanner`、`HomeBannerDetailDialog`、`HomeBannerContent`。
  - 原右侧栏的三种反馈（登记完成、操作结果、可撤销操作）改为横幅。“继续创建登记”去掉了，因为“加入排队”就在旁边。
- 删除的旧组件：`HomeSidePanel` 及其各面板、`JoinPanelContent`、`JoinEstimateRow`、`CloudSyncIndicator`、`EmptyHome`。没有登记时也显示机台卡片。
- 终端状态：
  - 新增 `TerminalBatteryStatus`（电量低于 20% 且未在充电为“低”）、`QueueCloudSyncStatus.needsAttention()`（待重试或未配置）、`rememberTerminalBatteryStatus`。
  - “更多”新增“终端状态”和 `TerminalStatusTile`，并接入“批量创建登记”。同步详情弹窗改由主页面打开。
- 机台卡片：
  - `MachineLane` 改为白色卡片，标题 20 号，标题行右侧是新登记预计等待（`newRegistrationEstimateLabel`）。
  - 队列行高度通过 `LocalQueueViewportHeight` 随卡片变化（154～240）。“结束本轮”弹窗里画的位置卡片仍是 154。
  - 新增 `QueueOverflowHint`，显示“还有 N 个位置”。
  - `QueuePosition` 按示意图改尺寸；游玩位置的时长单独显示，超时改为橙色标记和边框，最小宽度 200。
  - `RegistrationTile` 撑满卡片，昵称 17、状态 12，底色 72% 不透明。`registrationTileWidth` 改为 40 + 17 × 字数。
  - “加入 机台 X”改为虚线边框。`SmallActionButton` 改为高 52、字 15。
- 超时提醒“本轮已超过 20 分钟，请确认机台是否仍在正常游玩。”移入游玩位置菜单。
- 新颜色：`PlayingPositionBorderColor`、`OvertimeBorderColor`、`JoinCardBackground`、`JoinCardOutlineColor`，常量 `RegistrationTileAlpha`。
- 全局文字样式（`ui/theme/Type.kt`）：取消 Material 默认的 24 行高，文字框与字体等高；另外指定行高时，多出的空白上下平均分配。这一条也影响管理 App，管理 App 还没有在屏幕上看过。

**新写的文字**（已按文案规范润色）：
- 新登记预计等待、登记已满、刚刚开始（原为“刚刚”）、还有 N 个位置、详情、关闭。
- 终端状态、电量、与服务端同步。
- 电量较低且未在充电，请连接电源。／正在充电。／未在充电。／无法读取。
- 上次同步未成功，系统会自动重试。／已开启同步，但服务器连接不完整。／最近一次成功同步：HH:mm。／尚无成功同步的记录。
- “更多”的无障碍说明“更多，终端状态需要处理”。

**验证**：
- `app` 三个变体各 480 项测试通过，新增 `TerminalStatusTest` 2 项。
- 模拟器上看过的界面：
  - 首页：有横幅、无横幅。
  - 低电量（用 `adb emu power capacity 15` 模拟，已恢复为 100% 充电）时，“更多”变橙，菜单里电量一格变橙。
  - 位置太多时的“还有 1 个位置”，以及点击后滚到队尾。
  - 登记菜单、游玩位置菜单（含超时提醒）、“结束本轮”弹窗。
  - 纯本地版：没有登记时的首页，以及只有电量一格的“更多”。
  - 玩家资料创建第 1 步的输入框。
- 截图在 `tmp/claude-work/ui-review/u-*.png`、`v-*.png`、`w-*.png`。
- 模拟器里为测试位置太多的情况，用批量创建给机台 A 加了 4 份临时登记。

199. **改动终端首页、更多菜单、全局文字样式和文档**：见上。没有提交。

**7.74 补充（2026-11-25）**：维护者要求“标题栏（顶部）再加宽一点吧，大概是现在的1.5倍。标题还是要有强调性的，因为很多第一次游玩的玩家不知道这个机器的作用。是否要保留‘排队登记’还是要起一个其他标题，还是直接取消，你再考虑一下”。

我的考虑：保留“排队登记”作大标题。理由有三点：
- 第一次来的玩家最需要知道“这台平板是用来排队登记的”。
- 网站页面标题也是“排队登记 · maimai Q”，叫法一致。
- 机厅名称对玩家没有说明作用，他们已经知道自己在哪家店。

做法：
- 顶栏从 64 高改为 96 高。
- 大标题“排队登记”30 号；同一行右侧是灰色小字“机厅名称·N 台机台·当前共 N 份登记”，可以点开机厅详情。
- 下一行新增引导：“点击右侧‘加入排队’创建登记，轮到时即可游玩。”
- 时间改为 20 号。
- 横幅高 64，在顶栏里垂直居中。修正了一处：横幅原先被外层撑满顶栏高度。上一版的横幅实际是 64 而不是设定的 52，维护者说的“上下空白并不恰当”可能也与此有关。
- 截图：`tmp/claude-work/ui-review/x-home.png`、`x-home-banner.png`。

200. **顶栏加高并恢复“排队登记”标题**：见上。没有提交。

**7.74 再补充（同日）**：维护者要求“机厅名称和下面的那一行不要写在一起，这样间隔号太多了”“这个引导语我不喜欢，去掉吧”。
- 现在大标题“排队登记”右侧只放机厅名称（灰色 15 号，可点开机厅详情）；下一行是“N 台机台·当前共 N 份登记”。
- 引导语已删除。
- 截图：`tmp/claude-work/ui-review/y-home.png`。

### 7.75 加入排队流程、玩家资料库和确认页改为两栏（2026-11-25）

维护者的要求先后有这些（原话见重新设计方案第十一节）：
- 重做加入排队流程的按钮；
- 第一版“不好看，紧凑一点……可以不必保守”；
- 第二版“界面不够紧凑，请充分利用屏幕空间”；
- 突出玩家资料库（默认）和移动设备；
- 玩家资料库页面也重新设计；
- 左栏标题改为“创建一份登记以加入排队”，润色为“创建登记并加入排队”；
- 搜索可以用 QQ；
- 最后对图标、对齐和“设计与实现一致”提出了严格要求，已存入记忆。

**代码**：
- 新增 `StepFlowPage` 及其组件：`FlowSummaryPane`、`FlowStepRow`、`FlowListGroup`、`FlowListRow`、`FlowIconTile`、`FlowChevron`、`FlowBadge`、`FlowGreyBadge`、`FlowSectionLabel`、`FlowActions`、`FlowPrimaryButton`、`FlowTextField`、`RegistrationMethodCard`、`PlayPreferenceChoiceGroup`。
- 新增数据类型 `FlowSummary`、`FlowStep`、`FlowInfo`、`JoinFlowPage`；新增图标 `PersonRoundIcon`、`SmartphoneRoundIcon`、`PeopleRoundIcon`、`MinusIcon`。
- 重写的页面：`MachineSelectionScreen`、`CreateRegistrationScreen`（选择登记方式）、`TemporaryRegistrationNicknamePage`、`PreferenceScreen`、`BatchAmountScreen`、`PlayerLibraryScreen`、`ProfileSortControl`、`PlayerProfileCard`、`PlayerProfileDetailScreen`（确认加入排队）。原有逻辑都保留，包括：
  - 已有登记、机台不可用、只能一人游玩的机台；
  - 资料设为“每次询问”时选择本次偏好，以及“保存为默认偏好”；
  - 两台机台之间安排的选项；
  - 编辑资料、绑定网页账户（含加载中和不可用）。
- 主页面新增 `joinFlowSummary`、`batchFlowSummary`，生成左栏摘要；“修改”跳回对应的一步。
- 填写昵称页新增 5 个可直接点选的随机昵称（`onSuggestNicknames`）。
- 资料库卡片在最近 7 天用过时显示“最近使用”。
- 颜色常量新增 `SelectedRowBackground`、`PlaceholderText`、`FaintMarkColor`。
- `PlayerAvatar` 首字母随头像大小缩放。
- “更多”里的终端状态格改为固定标题行高，并给数值指定行高。

**文字**：
- 页面标题和标签：创建登记并加入排队、选择登记方式、使用移动设备（去掉“登记”）、确认加入排队、请选择、请填写、第 N 步，共 N 步。
- 按钮：选择资料、显示二维码、换一个随机昵称、编辑。
- 提示和说明：
  - 也可以直接选择一个随机昵称
  - 昵称最多 18 个字符，不能与当前队列中的登记重复。
  - 搜索昵称、QQ 号或玩家编号
  - 正在创建二维码
  - 按当前队列估算，实际以轮到时为准。
  - 当前机台还可以创建 N 份登记。
  - 每台机台最多 20 份登记。
- 资料卡标签：最近使用。
- 两种游玩偏好、“与朋友共同游玩”和玩家资料库三处的说明做了精简，见示意图。

**验证**：
- `app` 三个变体各 480 项测试通过。
- 模拟器上逐页走过：
  - 选择机台、选择登记方式（含移动设备不可用）、填写昵称（含点选随机昵称）、游玩偏好；
  - 玩家资料库、确认加入（含已有登记时的提示）；
  - 批量创建的机台和数量；
  - “更多”。
- 放大检查过：左栏对齐、分隔线起点、“推荐”标签居中、图标居中、数量行中心对齐。
- 测试中出现过一次应用退出：先从游玩偏好连按两次返回，再点玩家资料库时，应用回到了系统桌面。系统日志里没有崩溃记录，按同样步骤重做两次都没有复现，原因不明，后面需要继续留意。（7.76 更正：已查明不是崩溃。两次操作之间超过 30 秒，应用按设计回到了首页；接着按的返回在首页上会关闭应用。）
- 模拟器之前在后台运行的进程随上一个会话结束，这次重新启动过。

**还没改的**：
- 创建玩家资料、首次设置，以及认领和固定组合的确认页，仍是旧的单栏分步页。
- 资料库在认领和固定组合场景下已经是两栏，但左栏摘要只有两步，比较简单。

201. **加入排队流程和玩家资料库改为两栏**：见上。没有提交。

### 7.76 其余分步页改为两栏；修复添加一对机台时闪退（2026-11-25）

维护者：“这一版可以定稿。现在可以按这一版设计。以后绘制时把一对机台功能打开，我顺便预览一下这个新功能”。

**一对机台**：
- 在联网版模拟器（Demo 机厅）的应用设置里，把机台 A、B 组成一对，并开启“允许系统在一对机台之间安排登记”，已保存。
- 以后在模拟器上演示都保持开启，已写进记忆。

**修复闪退**：
- 现象：在“排队规则”里点“添加一对机台”，应用闪退。
- 原因：选择机台的弹窗是覆盖整屏的界面层，却放在了可以滚动的设置列表里面，测量高度时得到无限高而报错。这是两台机台之间安排登记功能里原有的问题，这部分代码还没有提交。
- 修复：弹窗移到排队规则页的最外层（新函数 `AddMachinePairDialogs`）；列表里只保留“添加一对机台”按钮。

**更正 7.75**：那次“应用退出”不是崩溃。两次操作之间超过 30 秒，应用按设计回到了首页；接着按的返回在首页上会关闭应用。首页按返回会关闭应用是原有行为，现场终端上可能不合适，没有改动，留待维护者决定。

**改为两栏的页面**（过程见重新设计方案第十二节）：
- 创建和编辑玩家资料、认领临时登记的三页、与朋友组成固定组合的两页、资料待补充、找不到玩家资料、首次设置的四页。
- 旧的单栏页面组件 `WizardPage`、`OptionCard`、`StepProgressBar`、`ProfileSummaryRow`、`PlayerProfileEditButton`、`profileContactSummary` 已删除，没有其他地方再用。

**代码**：
- `StepFlowPage` 的返回改为可以不显示（首次设置第一页）。
- `FlowTextField` 增加不可编辑状态和密码显示方式。
- 新增组件：`FlowSecondaryButton`、`FlowDetailRow`、`FlowNoteCard`、`FlowNote`、`FlowFieldHelp`、`FlowProfileCard`、`FlowProfileEditButton`、`FlowRadioCells`、`FlowCheckRow`、`TickBox`、`FlowUnavailablePage`、`FlowPairedMachineChoice`、`PairedMachineHelpButton`。
- 主页面新增 `claimFlowSummary`、`friendPairFlowSummary`，生成左栏摘要。
- 玩家资料编辑页记录“到过的最远一步”：左栏据此显示已填内容；到过确认页之后，“修改”改完回到确认页。
- 排队通知开关改为单列，字号与列表行一致（标题 17、说明 13）。
- 与示意图核对时发现一处不一致：确认页上“将本次选择保存为默认偏好”的勾选行，示意图是 14 号字，app 是 12 号，现已改为 14 号。弹窗里的同类勾选行仍是 12 号。
- 一对机台的选项在流程页里改为白色列表行；在“选择本次游玩偏好”页移到游玩偏好选项正下方。登记菜单里的样式不变。

**文字**（都按文案规范写，新增或改动的如下）：
- 创建玩家资料：
  - 左栏：创建玩家资料或编辑玩家资料；步骤为昵称和性别、QQ 号、默认游玩偏好、排队通知、确认创建或确认保存。
  - 左栏占位：请填写、请选择、请设置、请核对；排队通知一步显示“已开启”或“已关闭”。
  - 小节标题：玩家昵称、性别、QQ 号、QQ 显示范围。
  - 昵称下的提示改为“昵称会显示在排队登记中，最多 18 个字符。”（原来没有后半句）。
  - QQ 号：输入框提示改为“输入 QQ 号”（原“5 至 12 位数字”），下方提示“QQ 号为 5 至 12 位数字。”。
  - 确认页：编辑时标题也写“确认玩家资料”（原“编辑玩家资料”，与左栏重复）；说明改为“点击左侧各项的‘修改’进行更改。已经创建的登记不会因此改变游玩偏好。”。
  - 默认游玩偏好的“允许他人加入”一度加上了“推荐”标签，已按维护者意见去掉（见 7.77）。
  - 总开关关闭时点通知项的提示改为“请先开启‘接收排队通知’。”（原“请先开启排队通知总开关。”）。
  - 去掉“通知内容”小标题。
- 认领临时登记：
  - 左栏：认领临时登记；步骤为认领方式、玩家资料、确认认领；底部“认领的登记”，说明“认领后，当前位置和等待顺序不会改变。”。
  - 第一页标题改为“选择认领方式”（原“认领这份排队登记”）；说明里“等待顺序不会改变”移到左栏。
  - 去掉不能选的“创建临时登记”卡片。
  - “使用玩家资料库”卡片说明加上“还没有资料时，可以在资料库中创建。”。
  - “使用移动设备”卡片：说明“扫码后，在移动设备上选择或创建玩家资料。”，底部“暂不支持认领已有登记”。
- 与朋友组成固定组合：左栏底部“现有登记”和“位于 机台名。”。
- 资料待补充：“返回玩家资料库”和“前往补全玩家资料”并排，前者为次按钮。
- 首次设置：
  - 左栏：首次设置；步骤为设置说明（已阅读）、连接服务端（现在连接 / 暂时跳过）、机厅身份、确认。
  - 连接服务端改为两行单选：“现在连接服务端”，说明“填写服务器地址和终端同步令牌。”；“暂时跳过”，说明“终端只在本机使用，之后可以在应用设置中连接。”。
  - 小节标题：服务器地址、终端同步令牌、机厅名称、终端名称；令牌输入框提示“输入终端同步令牌”。
  - 保存机厅身份时按钮显示“正在保存…”。
  - 去掉每页底部的“返回”和“返回修改”。
- 使用手册里一处引文与 app 不一致，已改为 app 的原文“将本次选择保存为默认偏好，以后不再询问。”。

**验证**：
- `app` 三个变体各 480 项测试通过。
- 在联网版模拟器（一对机台已开启）上逐页截图核对：
  - 创建玩家资料：五步，包括昵称为空时的提示和编辑模式；
  - 确认加入排队：勾选一对机台选项，首次勾选时弹出说明，5 秒后可关闭；
  - 认领的三页、固定组合的两页、首次设置的四页。
- 放大量过：详情行名称与内容的中心、性别格里文字与符号的中心（相差不超过 0.5 像素）、间隔号两侧空白。
- 首次设置只在新安装时出现。检查时临时改了两处代码让它直接打开，截图后已改回（确认文件里没有残留），并重新安装正式构建。首次设置的状态文件事先备份在应用私有目录，看完原样恢复。测试队列（机台 A 10 份、机台 B 3 份）没有受影响。
- 模拟器每 30 秒无操作就回首页。检查期间在后台每 8 秒发送一次不影响界面的按键，保持页面不变；检查结束后停止。
- 资料待补充、找不到玩家资料两页没有现成数据可以打开，只确认了能编译、结构与其他页相同。

**还没做或待讨论**：
- 首页按返回会关闭应用（见上；7.77：维护者决定不改）。
- 一对机台的选项没有在机台 B 的游玩偏好页出现（7.77 已查明：那次走的是临时登记，临时登记不能开启这项选择，与机台无关）。
- 勾选一对机台选项后左栏“新登记预计等待”的显示（7.77：维护者确认不取两台中的较小值，现状正确）。
- 机台行前的“A”“B”方块、网页账户的叫法，仍待讨论。

202. **修复“添加一对机台”闪退**：见上。没有提交。
203. **其余分步页改为两栏**：见上。没有提交。

### 7.77 维护者对 7.76 的答复（2026-11-25）

- **“推荐”标签**：维护者：“这个不是说不加吗，只有本次游玩偏好推荐允许他人加入”。7.73 已定玩家资料的默认游玩偏好不加“推荐”，7.76 我违反了这一点。已去掉，模拟器上确认。只有加入排队时选择本次游玩偏好的“允许他人加入”带“推荐”。
- **机台 B 没有出现一对机台的选项**：维护者问为什么。我在 7.76 里说“这是这项功能原有的判断”，没有说清，也说错了原因。实际是那次我走的是“创建临时登记”。按功能规则，临时登记不能开启这项选择：自动转至另一机台之前要先通知玩家，临时登记没有玩家资料，通知不到人（`docs/automatic-machine-arrangement.md` 第 60 行，使用手册“自动在两台机台之间安排登记”一节）。代码里临时登记的两个页面传入的选项是空的。已在模拟器上用玩家资料 Rin 加入机台 B 验证：确认页出现“允许系统在当前机台和 左侧·机台 A 之间安排登记”。
- **新登记预计等待**：维护者：“接下来我们要重新讨论一下估时算法。重新说一下，创建登记之前‘新登记预计等待’那个并不遵循取小值，因为不涉及个人时间安排”（原文“不设计”，按上下文理解为“不涉及”）。我核对了代码，现状符合这一点：
  - 一对机台中的机台，用 `PairedMachineForecast.newRegistrationWaitMillis` 估算。它在这台机台的队尾假设一份新登记，这份登记不开启两台机台之间安排，然后把两台机台一起往下推算。结果只是它在这台机台上轮到的时间，不在两台之间取较小值。
  - 别人的安排会影响这个结果：前面有登记被另一台带走，它会提前；另一台的登记被安排到这里，它会推后。
  - 只有已经开启这项选择的登记，它自己的预计时间才取两台中先轮到的一台（`Result.waitMinutes`）。
  - 所以不需要改；7.76 把它列为待讨论是多余的。估时算法的整体讨论由维护者接下来发起。（7.78 更正：我理解错了维护者说的“取小”，见 7.78。）
- **首页按返回关闭应用**：维护者：“如果是安卓系统的返回，不需要改”。指的就是 Android 系统的返回，不改。

204. **去掉默认游玩偏好上的“推荐”**：见上。没有提交。

### 7.78 等待时间估算：新算法设计草案（2026-11-25）

维护者对 7.77 的回复（原话）：

- “我没说要这么做，，，我说：因某些玩家开启了自动安排，不确定他会不会被转出，因此导致队列时间不确定，原则上给各个登记提供的估时应该取小而不是取大，但这里我也不是说直接机械的取最小值，应该有算法”
- “这个估计时间的算法你需要认真考虑一套新的，并且覆盖到各种场景，能够比较准确的提供给各个登记以及新加入的登记一个估计时间……如果不能给出一个准确的估计时间，对于单个登记的估计时间应该给出较小的结果，以便玩家妥善安排时间。”

**更正**：7.77 里我把“取小”理解成“在两台机台之间取较小值”，说“现状正确、不需要改”，这是错的。维护者说的“取小”是：估时不确定时，给已登记玩家的数字偏早，并且由算法决定偏早多少。“新登记预计等待”不涉及个人安排，不偏早。

**做了什么**：只写了设计草案 `docs/releases/wait-estimate-2026-11.md`，代码没有改。草案里：

- **现状清点**：估时都在终端上算，随快照上传，网站、Bot、管理端显示同一组数字；没有按估时触发的通知。
- **现在算法的问题**：
  - 每轮时长固定；
  - 只把已出现预览的登记算作会转走或转来，已开启而尚未出现预览的登记一律算作留在原机台；
  - 待签到的一律算作会签到；
  - 超时后剩余时间算作 0；
  - 计划时间没有和实际对照；
  - 单台与一对机台是两套代码。
- **新算法**：
  - 用真实规则推演 100 遍，每遍各取一种可能的每轮时长和签到结果，自动安排按真实的预览规则在每遍里自行发生；
  - 已登记玩家取第 20 百分位，新登记预计等待取中间值；
  - 随机数事先固定，避免数字跳动；
  - 同一机台后面的位置不早于前面的位置。
- **各种情况的处理**：列成一张表。
- **实测记录**：建议终端新增轮次、签到和估时回看三类本机记录，用来校准时长、签到比例，并检查“偏早两成”是否达标。
- **计算量**：约为现在的 100 倍，需要先在平板上测耗时，并准备减少遍数或简化判断的退路。
- **规则不变**：预览和自动安排的规则不变，新算法只改变显示的估时。

**待维护者决定**（草案第七节）：

1. 偏早的程度（建议第 20 百分位）；
2. 是否用实测记录自动校准每轮时长；
3. 显示的取整方式；
4. 是否先收集一至两周记录再切换显示；
5. 是否为偏早的估时加说明文字。

**数字的性质**：草案中“浮动两成”“签到九成”“超时后 0 至 5 分钟”“异常门槛”都是我暂定的，不是实测结果。

另外，`docs/automatic-machine-arrangement.md` 状态表中“预计等待时间怎样显示”一行，加上了指向草案的说明。

205. **等待时间估算新算法的设计草案**：见上。只有文档，没有提交。

### 7.79 估时设计：转出与转入分开计算（2026-11-25）

维护者答复（原话）：

- 关于先收集记录：“没有这个条件。”
- “30百分位数可以，但我觉得可以计算一下这个转出的置信率。比如，假设有人要转入，那么这个转入的置信率有多高的时候，这个估计时间就按转入后计算；假设有人要转出，这个可以一旦显示预览就按转出后更短的时间计算。”
- “关于取整，如果不设计转入转出，至少30分钟内要精确到1分钟，30分钟以上100分钟以下精确到5分钟，100分钟以上精确到10分钟。”

草案 `docs/releases/wait-estimate-2026-11.md` 已整篇改写（仍未改代码）：

- **两种推演**：
  - 自然推演：两台一起，自动安排按真实规则自行发生；用来算转入的把握和新登记预计等待（中间值）。
  - 偏早推演：转出、转入事先定下，每台机台单独推演，已登记玩家取第 30 百分位。
- **转出**：前面的登记在另一台已显示预览，就按转走计算；没有预览按留下计算。
- **转入**：另一台的登记在自然推演里转来的遍数达到 80%（暂定）才算。同一份登记可能两边都不算，这是有意的，两边都偏早。
- **开启自动安排的登记本人**：已显示预览时，取两台中较早的时间。
- **取整**：按维护者定的方式（30 分钟内 1 分钟，30 至 100 分钟 5 分钟，100 分钟以上 10 分钟）。
- **实测记录**：改为“以后再做”，不影响切换。
- **步骤**：改为直接实施。

待维护者决定：

1. 转入门槛（建议 80%）；
2. 涉及转入转出的估时怎样取整；
3. 取整方向（建议已登记向下、新登记四舍五入）；
4. 未显示预览的登记不算转出（我同意维护者的说法）；
5. 是否加“估时偏早”的说明。

206. **估时设计改为转出、转入分开计算**：见上。只有文档，没有提交。

### 7.80 估时设计：可能不准确时单独显示（2026-11-25）

维护者答复（原话）：

- 涉及转入转出时的取整：“我没想好，你觉得怎样能兼顾，以准确为主，不要臆想数字写上去”
- “把可能不准确的情况单独写。如果转入置信率75以上就认为准确。可能不准确时，估计时间的文案要有变化。‘最快约xx分钟可以游玩’。你觉得呢？也可以写明‘由于涉及系统在两台机台之间的安排，时间估计可能不准确，请以现场实际情况为准’”

草案 `docs/releases/wait-estimate-2026-11.md` 再次改写（仍未改代码）：

- **数字分三种**：已定、由已定推出、缺依据。缺依据的集中在第七节，不凭空填。
- **转移把握的判断**：
  - 75% 及以上算准确，按会转计算（已定）；
  - 25% 及以下算准确，按不会转计算（由 75% 推出）；
  - 两者之间算可能不准确，转出按转走、转入按不会转来计算，保证“最快”成立。
- **已显示预览的转出**：仍按转走计算；把握不到 75% 时显示为可能不准确。
- **建议补充**：把握在 25% 至 75% 之间、还没显示预览的转出，也按转走计算，否则玩家可能比“最快”还早轮到。待维护者确认。
- **文案**（已润色，待确认）：
  - “最快约 N 分钟后可以游玩”；
  - 不到 1 分钟时“可能很快可以游玩”；
  - 说明句“受系统在两台机台之间安排登记的影响，等待时间可能不准确，请以现场实际情况为准。”；
  - 管理端写“最快 N 分钟”；
  - 新登记预计等待是中间值，不用“最快”。
- **取整**：
  - 建议涉及转入转出时沿用维护者定的同一套取整，不另设数字；
  - 方向：准确的四舍五入，“最快”向下取整，新登记四舍五入。
- **原来暂定的数字改为向维护者询问**：每轮浮动、超时后还会持续多久、签到比例。

207. **估时设计加入“可能不准确”的判断和文案**：见上。只有文档，没有提交。

### 7.81 新的等待时间估算已实现（2026-11-25）

维护者答复（原话）：

- 对草案第七节：“按草案定”。
- 缺依据的数字：“单人10-15分钟，双人13-18分钟，也就是向下浮动两分钟，向上浮动三分钟。五成线上登记按时签到。”
- 实施中又提出：“提供一个思路：可能转至机台B可以显示 ‘可能转至B2’。这样写明了具体位置。其他地方也可以应用，即合理引用位置编号”

由此推出：一轮超出计划时间后，最多再持续到计划时间加 3 分钟；分布取三角形（最短、计划、最长），只用到维护者给的三个数。

**做了什么**（详见 `docs/releases/wait-estimate-2026-11.md` 第八节）：

- **推演**：`queue-core` 新增 `WaitEstimator`，按设计实现。
  - 新登记单独推演。实施中发现，新登记和已有登记放在同一次推演里时，会和队尾组成共同游玩，把暂缓一次的登记推后。
- **终端**：
  - 首页、加入流程、闭店提醒、位置菜单改用新估时，在后台每 30 秒和每次队列变化后重算。
  - 位置菜单在可能不准确时显示“最快约 N 分钟后可以游玩”和说明。
- **快照**：
  - 等待位置带上 `estimated_wait_uncertain`；
  - 新登记预计等待改为中间值。
- **服务器、网站、Bot**：由子代理完成，我复核了测试和构建。
  - 服务器接收并转发标记，非布尔值报“estimated_wait_uncertain 必须是布尔值。”，与现有布尔字段的写法一致。
  - 网站和 Bot 按标记显示“最快约……”和说明；网站“我的排队”卡片只显示“最快”，说明在点开的登记详情里。
  - 协议文档 `docs/cloud-queue-sync.md` 已补充。
- **管理端**：可能不准确时写“第 N 位·最快 N 分钟”。
- **登记块**：
  - 宽度改为按昵称和状态行中较长的一行确定。原来三个字的昵称配“允许他人加入”、以及“可能转至 机台 B”都会被截断，测量用的字号也比显示的小。
  - 按维护者的思路写出位置编号：另一机台显示了这份登记的预览时写“可能转至 B1”，没有预览时写“可能转至 机台 B”。
- **文档**：使用手册“等待时间如何估算”一节改写；文案规范新增“等待时间”一节；`docs/automatic-machine-arrangement.md` 状态表更新。

**验证**：

- 测试结果：queue-core 174 项；app 三个变体各 482 项；服务器 229 项；Bot 86 项；网站构建通过。
- 模拟器（联网版，一对机台开启）：
  - 首页新登记预计等待：机台 A 约 60 分钟，机台 B 约 15 分钟。
  - Rin 开启这项选择加入机台 A 后，位置菜单显示“约 15 分钟后可以游玩”。她几乎肯定会转至机台 B，算作准确。
  - 登记块上显示“可能转至 B1”，与机台 B 的预览位置一致。
  - 两处截断已消失，左右留白约 12 像素。
- 测试队列里没有凑出“可能不准确”的情况，这种情况只由单元测试覆盖。
- 网站和 Bot 的新文字没有在浏览器或 QQ 里看过，只有测试和构建。

**改动了模拟器里的测试数据**：

- 用 Rin（开启了这项选择）在机台 A 加入排队；
- 机台 A、B 各按了一次“结束本轮”，原因是两台本轮都已经“游玩”了 370 多分钟，安排一直暂停，看不到预览。
- 较早还按“与朋友共同游玩”建了一份临时登记 Yuki。

**子代理提出、未改动的现有问题**：

1. 同一份登记的估时有两个来源。服务器给“我的排队”和 Bot 用的是本机台的等待位置；网站排队页有另一机台的预览位置时显示的是预览位置。新算法下，两处取的是同一份登记的估时，只有为保证顺序而做的调整可能让数字差一档。
2. Bot 的“当前队列”不显示另一机台的预览位置。
3. Bot 现有文字里有“你”，不符合文案规范。
4. 待签到的线上登记：Bot 会显示估时，网站账户页显示“完成现场签到后才是有效登记”。

**待办**：

- 其他提到“转至 机台”的地方是否改写位置编号，清单已交给维护者确认。
- 清理已不用的旧估算函数。
- 平板实机上的耗时没有测。

208. **新的等待时间估算**：见上。没有提交。

### 7.82 登记块的宽度上下限；在其他地方写出位置编号（2026-11-25）

维护者答复（原话）：

- “登记稍长些没关系，应当由最小宽度和最大宽度，并且和位置框相适应”（“由”理解为“有”）。
- 对 7.81 列出的位置编号清单：“按你的改”。

**登记块宽度**：

- 宽度取昵称和状态行中较长的一行所需，下限 96，上限 142。这两个数都由现有规则推出：
  - 下限放得下最长的游玩偏好“允许他人加入”（6 个字、12 号字，加两边内边距）；
  - 上限是现有昵称最多显示 6 个字时的宽度。
- 超过上限的状态行以省略号结尾。
- 位置卡片仍由各登记块的宽度加间距得出，登记块按比例撑满。
- 已写进界面规范“排队首页”一节。

**位置编号**（按 7.81 的清单）：

- **终端登记菜单的预告句**：“右侧·机台 B 的位置 B1 已显示这份登记的预览，最早 04:00 之后，这份登记可能自动转至该位置，请合理安排时间。”另一机台没有显示预览时与原句相同。
- **手动转至后的横幅标题**：“登记已转至位置 A5”；转过去的登记不在同一个位置时仍写机台。横幅正文沿用日志，仍写机台。
- **网站排队页**：
  - 登记旁写“可能转至 B1”；
  - 登记详情和本人登记的状态句写出位置。
  - 账户对话框里的“我的排队”拿不到另一机台的预览位置，仍写机台。
- **仍写机台的地方**：日志、QQ Bot 私信、规则说明和首次弹窗。
- **共用的辅助函数**：终端新增 `pairedPreviewPosition`、`waitingPositionLabel`，编号方式与首页一致，预览位置也计入。
- **空格**：只在机台名称前空一格。
  - 检查时发现“自动转至 该位置”“登记已转至 位置 B2”多了空格，终端和网站都已改正。
  - 文案规范已写明位置编号的用法和空格规则。

**顺带修的现有文案**：

- 日志正文“已将……转至 机台 B 的队尾并加入目标机台的队尾”删去后半句。
- 文案审查表第 85 条早已指出这个重复，但当时没有改掉。

**验证**：

- 测试和构建：
  - app 三个变体各 482 项测试通过；
  - 新的预告句加进了 `PairedMachineQueueNoticesTest`；
  - 网站能构建，新句子用 node 打印核对过。
- 模拟器：
  - 登记块“可能转至 B1”；
  - 登记菜单的预告句；
  - 手动转至后的横幅“登记已转至位置 B2”（改空格前）和“登记已转至位置 A5”（改空格后）。
- 网站页面没有在浏览器里看过。

**改动了模拟器里的测试数据**：把 Rin 手动转至机台 B，再转回机台 A。手动转至不能撤销，她原来在 A4，现在在机台 A 的队尾 A5。

209. **登记块宽度上下限、位置编号**：见上。没有提交。

### 7.83 “由现场玩家报告”在还没轮到时的说明（2026-11-25）

维护者：“这位玩家未到场，当置灰时，也就是还没轮到他的时候，下方文案应该有变化：‘现在还没有轮到这名玩家，即使他不在现场，现场玩家也不能处理这份登记。’”

**改动**：

- 登记菜单和位置菜单的“由现场玩家报告”一栏，在还没轮到时（机台正常使用、登记不在暂缓一次或暂时离开、按钮因未轮到而为灰色）说明改为：
  - 一份登记：“现在还没有轮到这名玩家，即使其不在现场，现场玩家也不能处理这份登记。”
  - 一组登记：“现在还没有轮到这组玩家，即使其不在现场，现场玩家也不能处理这组登记。”
- 润色：“他”改为“其”。规范里同一句第二次提到用“其”，也不涉及性别。
- 新函数 `noShowPanelDescription`；新测试 `NoShowPanelDescriptionTest`（2 项）。
- 顺带统一：登记菜单里点击灰色按钮的提示原为“当前尚未轮到这名玩家游玩，不能记录为未到场。”，改为与位置菜单一致的“现在还未轮到这名玩家游玩，不能记录为未到场。”
- 机台停止使用、登记处于暂缓一次或暂时离开时，按钮也是灰色，说明仍是原来的“即将轮到的这名玩家不在现场……”。这两种情况没有改，已告诉维护者。
- 使用手册“谁可以被标记为未到场”一节同步。

**验证**：

- app 三个变体各 484 项测试通过。
- 模拟器：
  - “悠闲的星星”（A3，还没轮到）显示新说明；
  - “发光的猫咪”（A1，即将轮到）仍是原说明，按钮可用。

210. **未到场一栏在还没轮到时的说明**：见上。没有提交。

### 7.84 延后的三种方式：暂缓一次、轮空一次、暂时离开（2026-11-25）

维护者：

- “暂缓一次不能连着用两次……现在可以做这项改动。”
- “已签到但没玩过的线上登记不能暂缓，也不能使用任何延后方法。”
- “关于‘延后这份登记’，我想出了第三个选项：轮空一次。现在的三种分别是：暂缓一次、轮空一次、暂时离开。轮空一次即下次轮到时将登记放回队尾。轮空一次前不能用‘已’来表示设置状态，应该用‘将’；为保持统一，暂缓一次也可以用将。轮空一次也可以取消。这样，暂缓一次使用一次过后，置灰即可。”
- 问答中的选择：
  - 轮空计入暂时离开的轮空次数；
  - 网站和 Bot 也提供；
  - 状态写“将暂缓一次、将轮空一次”；
  - 暂缓一次置灰到“游玩一轮后”为止。原话：“第一个答案改成游玩一轮后。”

**规则（queue-core）**：

- 新状态 `QueueAbsenceStatus.SKIP_ONE_TURN`。
  - 轮到时，登记移至队尾，`temporaryAwaySkippedTurns + 1`，状态随即回到 NONE。
  - 已轮空 3 次时再轮到，登记会被移除。
  - 固定组合整组处理。
  - 转至其他机台（`receiveAtWaitingTail`）时自动解除，不计轮空。
- `Registration.deferOneRoundUsed`：
  - 暂缓一次被消耗（跳过了一次机会）时设为 true，游玩一轮后清除。
  - 为 true 时，`DeferOneRound` 和以暂缓处理未到场都会被拒绝，失败码 `DEFER_ONE_ROUND_ALREADY_USED`。
  - 取消暂缓一次不算用过。
- 已签到、未游玩的线上登记（`mustBeRemovedWhenAbsent`，固定组合看整组）不能暂缓、轮空或暂时离开，失败码 `ONLINE_REGISTRATION_NOT_PLAYED`。管理后台（`QueueActionOrigin.MANAGEMENT_APP`）不受限。
- 轮空一次**没有机厅开关**，始终可用。这是我定的，理由是它只影响本人的登记。因此“延后这份登记”入口不再因两个开关都关闭而灰色。
- 等待位置的显示按预计首次游玩的顺序（原有做法）：将轮空一次的登记显示在移至队尾之后的位置，标“将轮空一次”。
- 估时直接用引擎推演，轮空一次自然计入。
- 新测试 `PostponeRulesTest`（7 项）；`RoundPlannerTest` 和 `QueueEngineDifferentialTest` 也有相应更新。

**终端**：

- 登记菜单：
  - 状态为 SKIP 时显示“取消轮空一次”。
  - 线上登记未游玩时，“延后这份登记”为灰色，原因“已签到的线上登记在游玩一轮之前不能暂缓、轮空或暂时离开。”
- “延后这份登记”弹窗：
  - 三张卡片，宽 900，适合 720p。
  - 新卡片“轮空一次”，概括“轮到时移至队尾”：
    - 适用：需要晚些再游玩，在重新排一次队的时间内可以回来。
    - 轮到时：下一次轮到时移至队尾，计一次轮空；本轮由其余在场的登记重新组合。
    - 结束：移至队尾后自动解除，不需要再操作；轮空次数与暂时离开合并计算。
  - 暂缓一次用过时卡片为灰色，原因“这份登记已暂缓过一次，游玩一轮之后才能再次暂缓。”
- 确认页“确认轮空一次？”见 7.85（上限只在接近时提醒）。
- 未到场弹窗：用过暂缓一次时，“暂缓一次”为灰色，说明“这份登记已暂缓过一次，游玩一轮之前不能再次暂缓。请根据玩家是否仍会回来，选择移至队尾或移除登记。”整组时写“组内有登记……”。
- 横幅：
  - “登记将暂缓一次 / 固定组合将暂缓一次”（原为“已”）；
  - “登记将轮空一次 / 固定组合将轮空一次”；
  - 立即执行时为“轮空一次已执行 / 固定组合已完成轮空一次”；
  - 到上限时为“已达轮空上限，登记已移除”；
  - 取消时为“轮空一次已取消 / 固定组合已取消轮空一次”。
- 日志（`AuditLogs.kt`）：
  - 设置时：“将暂缓一次”“将轮空一次”。
  - 生效后：“已暂缓一次”“已轮空一次并移至队尾，已轮空 N 次”。
  - 本轮结束的分项：“轮空一次，已轮空 N 次”“已达轮空上限，登记已移除”。
  - 转至：“转入后已在队尾，不再轮空”。
- 撤销反馈（`QueueHomeFeedback.kt`）补上轮空一次的两种结果。
- 其他文字：
  - 远程操作结果：“登记将暂缓一次。”“登记将轮空一次。下一次轮到时会移至队尾，计一次轮空，随后自动解除。”
  - 固定组合的附注：“仍会/仍保持暂缓一次”统一为“仍将暂缓一次”，并补上轮空一次的对应句。
  - 恢复队列弹窗、两台机台安排的说明里加了“轮空一次”。
- 顺带修正：确认页里引号后的多余空格。代码里 `$subject 时` 要写成 `${subject}时`，否则 Kotlin 会把“subject时”当成一个名字，所以原来留了空格，显示成“发光的猫咪” 时。共 23 处，包括暂缓一次、暂时离开和未到场确认页里原有的。
- 远程操作（`RemoteQueueOperations.kt`）：新操作 `SKIP_ONE_TURN`、`CANCEL_SKIP_ONE_TURN`，以及两条限制；原因句与上面相同。
- 发布：`QueueCloudSync.kt` 发布 `skips_one_turn`、`defer_one_round_used`、`online_registration_not_played`。`temporary_away_skipped_turns` 在暂时离开或将轮空一次时都发布。
- 保存：`LocalQueueStateRepository.kt` 保存 `deferOneRoundUsed`；恢复固定组合状态时，优先级为暂时离开 > 轮空 > 暂缓。
- 管理后台：
  - 菜单有“轮空一次 / 取消轮空一次”。
  - 暂缓一次用过时灰色，未到场对话框也是。
  - 详情写“将暂缓一次”“将轮空一次”。
  - 新动作 `SKIP_ONE_TURN`、`CANCEL_SKIP_ONE_TURN`。

**服务端、网站、Bot**（由子代理完成，我复核了测试，并补了几处与终端一致的句子）：

- 服务端：
  - 新操作和字段校验：三种状态最多一种；待签到时都不能有。
  - 不是管理后台的请求，按两条限制先行拒绝。
  - 新增测试 10 项，共 239 项。
- 网站：
  - 账户弹窗和队列详情有“轮空一次 / 取消轮空一次”按钮和确认页。
  - 两条限制使按钮灰色并显示原因。
  - 状态写“将暂缓一次”“将轮空一次·已轮空 N 次”。
- Bot：
  - 新命令“轮空一次”“取消轮空一次”。
  - 状态和菜单同上。
  - 新增测试 6 项，共 92 项。
- 协议文档 `docs/cloud-queue-sync.md` 已同步。
- 我补的句子：
  - 固定组合退出时：“另一份登记仍将轮空一次，并会在下一次轮到时移至队尾。”
  - 转至时：“转入登记已在队尾，不再轮空；留在原机台的登记仍将轮空一次。”“转入后已在队尾，不再轮空。”
  - 更改偏好解除组合时：“两份登记的轮空一次不会因解除组合而取消。”
  - “仍会暂缓一次 / 仍保持暂缓一次”改为“仍将暂缓一次”。

**验证**：

- queue-core 和 app 三个变体全部通过；新增 app 测试：
  - 远程轮空的设置和取消；
  - 用过暂缓一次被拒；
  - 线上登记只有管理后台能延后；
  - 保存和恢复；
  - 上限提醒。
- 服务端 239 项通过；Bot 92 项通过；网站构建通过。
- 模拟器（连接版，Demo，一对机台开启）：
  - 三卡片弹窗；
  - 设置、显示和取消轮空一次；
  - 暂缓一次被消耗后卡片灰色并提示原因；
  - 未到场弹窗中暂缓一次灰色。
- 网站和 Bot 的新界面没有在浏览器或 QQ 里看过。

**改动了模拟器里的测试数据**：机台 A 结束了一轮。“发光的猫咪”的暂缓一次已被消耗，在它游玩一轮之前不能再暂缓。

**未决（子代理提出，留给维护者）**：

- 网站和 Bot 在设置轮空一次之前看不到已轮空次数：登记没有状态时次数发布为 0，这是服务端校验要求的。
- Bot“我的排队”里不可用的动作不列出，网站则灰色显示。这两点与原有做法一致。

211. **延后的三种方式**：见上。没有提交。

### 7.85 未到场弹窗改为对照卡片；轮空上限只在接近时提醒（2026-11-25）

维护者：

- “这个未到场的弹出菜单改成跟延后这份登记类似的菜单吧。”
- “轮空一次的确认菜单中不用强调三次退出，直到第三次轮空时才特别强调。因为很少人会连续使用三次这个。”

**未到场弹窗**：

- 一份登记和一组登记的未到场弹窗，都改为与“延后这份登记”相同的三张对照卡片（新函数 `NoShowResolutionCards`，宽 900）。三点改为“适用”“处理后”“之后”，因为这时已经轮到。
- 卡片“暂缓一次”，概括“（整组）跳过本次机会，位置不变”：
  - 适用：（这组）玩家暂时不在，但很快就会回来。
  - 处理后（等待中）：（整组）跳过本次机会，这份（组）登记保持当前顺序；本轮由其余在场的登记重新组合。
  - 处理后（在游玩位置）：（整组）离开游玩位置 A 并回到队首，跳过本次机会。
  - 之后：跳过一次后自动解除。游玩一轮之前，这份（组）登记不能再次暂缓。
- 卡片“移至队尾 / 整组移至队尾”，概括“保留登记，（整组）移至队尾”：
  - 适用：（这组）玩家会回来，但需要一些时间。
  - 处理后（等待中）：这份（组）登记移至当前队尾。
  - 处理后（在游玩位置）：（整组）离开游玩位置 A 并移至当前队尾。
  - 之后：按正常轮换再次轮到，不需要再操作。
- 卡片“移除登记 / 移除整组”，概括“（整组）退出本次排队”，标题和概括为红色：
  - 适用：（这组）玩家不会回来，或无法确认是否回来。
  - 处理后：从队列中移除这份（组）登记。
  - 之后：继续游玩时需要重新加入排队。
- 灰色卡片的原因：
  - 线上登记第一次轮到时：“线上登记第一次轮到时不提供。”（原句）
  - 用过暂缓一次时：用 7.84 的原因句，整组时写“组内有登记已暂缓过一次，游玩一轮之后才能再次暂缓。”
  - 规则不允许时：“系统规则不允许暂缓一次。”
- 确认页不变。旧的小按钮 `NoShowChoice` 已删除；`AbsenceChoiceCard` 增加红色样式。
- 管理后台的未到场对话框是另一套（系统对话框），没有改。

**轮空上限**：

- 终端确认页的橙色说明删去“轮空次数与暂时离开合并计算，累计轮空 3 次后再次轮到时，登记会自动移除；已轮空次数会在游玩一轮后清零。”
- 只在接近时写一句（新函数 `skipOneTurnLimitNotice`）：
  - 已轮空 2 次：““X”已轮空 2 次，这次将是第 3 次。此后再轮到时，如果仍设置了轮空一次或暂时离开，登记会被自动移除。”
  - 已轮空 3 次：““X”已轮空 3 次。下一次轮到时，登记会被自动移除，而不会移至队尾。”
- 网站和 Bot 在设置前不知道次数（见 7.84）：
  - 网站和 Bot 的确认和成功消息删去这句上限。
  - 网站本人状态在将轮空一次、次数为 2 或以上时，加上同样的提醒。
  - 终端给远程操作的结果句也会加上。
- 卡片里“轮空次数与暂时离开合并计算”一句保留：它只说明怎么计，不提上限。

**验证**：

- 全部测试通过：
  - Kotlin 新增 `SkipOneTurnLimitNoticeTest`；
  - Bot 92 项；
  - 服务端 239 项；
  - 网站构建。
- 模拟器：
  - 一份登记和一组登记的未到场卡片，暂缓一次因用过而灰色；
  - 移至队尾的确认页，返回后取消，没有执行；
  - 轮空一次确认页已不再提上限。

212. **未到场对照卡片、轮空上限的提醒时机**：见上。没有提交。

### 7.86 线上登记的原因句；网站的延后流程与终端一致（2026-11-25）

维护者：

- 针对“已签到的线上登记在游玩一轮之前不能暂缓、轮空或暂时离开。”，说：“不能被延后”。
- “检查。网页版的原则就是尽量和终端保持UX一致。”

**原因句**：

- 改为“已签到的线上登记在游玩一轮之前不能延后。”
- 润色：去掉“被”。正式文案不需要被动标记，而且“延后”与菜单入口“延后这份登记”一致。
- 改动的文件有：终端远程操作、服务端（含测试）、网站、Bot（含测试和 README）、协议文档、使用手册、文案规范。

**网站检查**（本地演示，见下）：

- 发现网站与终端不一致：
  - 本人登记的操作是六个平铺按钮，暂缓一次、轮空一次、暂时离开各自单独一个；
  - 没有对照卡片；
  - 确认页文字与终端不同；
  - 取消暂缓一次等还要再确认。
- 已按终端改（账户弹窗“我的排队”和队列页的登记详情两处）：
  - 分组“游玩方式”（更改游玩偏好、转至）和“这份登记”（延后这份登记或取消对应状态、退出排队）；
  - “延后这份登记”打开三张对照卡片（新组件 `PostponeChoiceCards.vue`），文字逐字取自终端。窄弹窗里上下排列，宽度 600 以上并排三张。不可用的卡片灰色，概括处写原因。
  - 卡片进入确认页，文字与终端相同，“返回”回到卡片。固定组合时终端写两人名字，网站只知道本人的登记，写“这组登记”。
  - 取消三种状态改为点后直接提交，与终端一致；
  - 只有一台其他机台时，按钮写“转至 右侧·机台 B”；
  - 处于游玩位置时不显示“延后这份登记”（终端同样不显示）。
- 共用的文字和函数在 `queueActionAvailability.js`：
  - `postponeChooserHeading`、`postponeChoiceCards`、`postponeConfirmation`；
  - `POSTPONEMENT_CANCEL_OPERATIONS`、`transferEntryLabel`。
- 两处原来各自的暂缓、轮空、暂时离开和取消的确认文字已删去。
- 账户弹窗补了橙色和灰色的颜色变量（浅色和深色）。
- 在浏览器里看过：
  - 两处的分组；
  - 卡片和确认页，以及“返回”；
  - 暂缓一次用过时卡片灰色；
  - 线上登记未游玩时入口灰色，点后显示新原因句；
  - 将轮空一次时的本人状态（含第 3 次的提醒）；
  - 取消轮空一次直接提交（服务端收到 202）。
- 深色模式没有看。

**本地演示**：

- 旧的演示脚本在 11-24 已删除，这次重写成一个文件 `tmp/claude-work/demo/serve.py`，不随项目提交：
  - 服务端使用临时数据库，在 localhost:8791 运行；
  - 每 20 秒用服务端测试里的快照构造函数发布一次快照；
  - 绑定一个测试网页账户，测试密码写在同目录的 `credentials.txt`；
  - 同目录的 `state.json` 可以随时切换那份登记的状态。
- 网站快照在 `tmp/claude-work/demo/site`，在 localhost:5183 运行。
- `.claude/launch.json` 是临时加的，演示结束后要删除。

**验证**：全部测试通过（服务端 239 项、Bot 92 项、Kotlin 全部、网站构建）。

213. **原因句改为“不能延后”；网站延后流程按终端改**：见上。没有提交。

### 7.87 检查网站勾选“允许系统在当前机台和 机台 B 之间安排登记”（2026-11-25）

维护者：“检查一下机厅允许时，网站是否能正确勾选系统分配”。

**做法**：把本地演示的 `serve.py` 改成一个简单的现场终端。它会领取网站发来的命令，在演示数据上做出效果（加入、更改偏好、勾选、延后等），发布新快照，再回报“已执行”，这样能看到勾选从网站一路走到终端。演示里机台 A 和 B 默认是一对；`state.json` 写 `{"paired": false}` 时表示机厅不允许。

**结果（机厅允许时）**：

- **线上加入**（QQ 22223333，未登录）：
  - 确认页“本次游玩偏好”下有勾选框。
  - 第一次勾选时，说明自动弹出，5 秒后才能关闭；说明里的列表已有“轮空一次”。
  - 关闭后仍为勾选状态。
  - 提交后，服务端收到的 JOIN_QUEUE 带 `also_queues_at_paired_machine: true`，登记出现在 A2，状态为待签到。
- **已登录的“更改游玩偏好”**（账户弹窗）：
  - 勾选框默认未勾选。
  - 改为“允许他人加入”并勾选后，说明对这份资料也自动弹出一次，框下提示“这份登记游玩一轮之前，不会被安排到另一机台。”（登记已在等待）。
  - 确认后先发出 CHANGE_PLAY_PREFERENCE。终端执行后，再发出 SET_PAIRED_MACHINE_QUEUEING（`true`），顺序正确。
  - 卡片随后显示“允许他人加入”和“已允许系统在当前机台和 右侧·机台 B 之间安排登记。这份登记游玩一轮之前，不会被安排到另一机台。可以在‘更改游玩偏好’中取消。”
- **队列页登记详情的“更改游玩偏好”**：打开时为已勾选；取消勾选后不再弹说明，确认后只发出一条 SET_PAIRED_MACHINE_QUEUEING（`false`）。

**结果（机厅不允许时）**：

- 详情的“更改游玩偏好”里没有勾选框。
- 线上加入查询返回的机台都没有 `paired_machine_id`。网站按这一项决定是否显示勾选框，所以也不会显示。

**没有发现需要改的问题。** 没有检查：固定组合时勾选框灰色、手机登记流程里的勾选框、深色模式。

**附带看到（未改）**：线上加入成功页的说明“完成签到后，第一次轮到时未到场的登记只能移除，不能暂缓一次或移至队尾。”还没有提到新规则“已签到的线上登记在游玩一轮之前不能延后”。

214. **检查网站的一对机台勾选框**：见上。演示脚本改为会执行命令。没有提交。

### 7.88 线上登记签到说明补上新规则；待办汇总（2026-11-25）

维护者：“可以补上。还有其他待办吗”。

**改动**：

- 网站加入排队的确认页和成功页、Bot 的两条线上登记消息，末句由“完成签到后，第一次轮到时未到场的登记只能移除，不能暂缓一次或移至队尾。”改为“完成签到后，这份登记在游玩一轮之前不能延后；第一次轮到时未到场，只能移除登记，不能暂缓一次或移至队尾。”
- 终端没有这句。
- Bot 92 项测试通过，网站构建通过，演示快照已重建。

**待办汇总（截至本节）**：

- 排队逻辑和功能（方向已定、尚未动手）：
  1. 管理后台显示待执行的远程命令，可撤销仍在排队的命令；同时解决连续操作两名玩家时第二条被拒绝。
  2. 平板闭店前关机、次日营业时间内开机，不应自动继续前一天的队列。
  3. 管理后台的未到场窗口，对已签到、未游玩的线上登记置灰“暂缓一次”和“移至队尾”。快照现在已经发布 `online_registration_not_played`，可以直接做。
  4. 终端关机或重启期间，线上登记的签到时限不应流逝。
  5. 线上登记只凭 QQ 的风险（冒名、占满名额、无冷却），还没有方案。
  6. 单人游玩的登记可选择允许他人越过（低优先级，文案草稿在 7.54）。
  7. 终端和网站可视化查看调整等待顺序前后的队列（低优先级）。
  8. Bot 用词（“切换机台”等）维护者之后改。
- 界面：
  1. 首页机台 A、B 的字母方块（界面优化里唯一未完成的一项）。
  2. 网站按“尽量与终端 UX 一致”核对其余流程：转至、更改游玩偏好、退出排队、加入排队。
  3. 本轮没看的：固定组合时勾选框灰色、手机登记流程的勾选框、新卡片的深色模式。
  4. 首页按返回键会关闭应用（原有行为，待维护者决定）。
- 文案：
  1. “网页账户”与“网页玩家资料”的名词和分工（文案规范“尚未决定”）。
  2. 两台机台功能新增文字（`docs/releases/dual-machine-queue-copy.md` 第十三节，编号 75–97）待维护者过目。
  3. Bot 新文字在 QQ 里实际看一遍。
- 收尾和发布：
  1. 清理已不用的旧估算函数。
  2. 实机验收（包括估算的耗时）。
  3. 自 `6a04c24` 起全部未提交；交接文档提交前要过目（仓库公开）；记忆不随项目提交；版本号未动。
  4. 线上快照带 `test_data` 标记，是否有意未确认。
  5. 外部贡献者的 PR #1（工具链升级、minSdk 28）未决定。
  6. 服务端扫描的未核实结果，以及剩余九段未扫。
  7. 0.14.0（多终端）没有计划文档。
  8. 本地演示仍在运行，结束后删除 `.claude/launch.json`、停止两个预览进程。

215. **签到说明补上新规则**：见上。没有提交。

### 7.89 先做面向用户的部分；网站的更改游玩偏好、转至、退出排队与终端一致（2026-11-25）

维护者：“管理后台都还不做，后续慢慢填坑，先把面向用户的做好”。管理后台的两项（待执行命令队列、未到场置灰）往后放，已记入记忆。

**按“网页尽量与终端一致”核对了其余三项操作**（账户弹窗“我的排队”和队列页登记详情两处），都改为终端的写法。文字集中在 `queueActionAvailability.js`（`preferenceChooserText`、`PREFERENCE_OPTIONS`、`transferConfirmation`、`leaveConfirmation`），新组件 `PreferenceOptions.vue`：

- **转至**：
  - 只有一台其他机台时，点“转至 右侧·机台 B”直接进入确认页，不再先选机台。
  - 标题“转至 右侧·机台 B？”。
  - 说明“确认后，“X”会从 左侧·机台 A 移出，并加入 右侧·机台 B 的队尾。”
  - 红框“原机台上的当前位置不会保留。即使之后再次转回 左侧·机台 A，也只能加入转回时的队尾。转入后，等待位置会按照 右侧·机台 B 的等待顺序和游玩偏好重新划分。”
  - 其后是固定组合、单人机台、一对机台、暂缓、轮空、暂时离开的附注，按钮“确认转至 右侧·机台 B”。
  - 原来两处的写法不一样，现在统一。
  - 两处“仅能容纳一人游玩的机台不能接收固定组合”的提示也统一为账户弹窗的写法。
- **更改游玩偏好**：
  - 标题由“选择本次游玩偏好”改为“更改游玩偏好”。
  - 说明“新的选择只用于本次排队，并会用于重新划分等待位置；玩家资料中的默认游玩偏好不会改变。”终端原句有“立即”，网站要等终端执行，所以去掉。
  - 两个选项改为带说明的卡片：“这份登记将独自占用一个等待位置。”“这份登记可以与相邻的、允许他人加入的登记组成共同游玩位置。”
  - 固定组合说明“当前属于固定组合。改为单人游玩或允许他人加入时，双方的固定组合会同时解除。”另加保留状态的一句。
  - 单人机台写“当前机台仅能容纳一人游玩，这份登记使用‘单人游玩’。”
- **退出排队**：
  - 标题“退出排队？”。
  - 说明““X”退出后，这份登记将立即失效。如需继续游玩，需要重新加入队尾。”固定组合时中间加“固定组合也会解除；另一份登记会保留在原位置，并恢复为允许他人加入。”和另一份登记保留的状态。
  - 按钮“确认退出”。
  - 处于游玩位置时，网站仍多一句“游玩位置中的空缺不会自动由等待顺序中的下一组登记补入。”，因为不在现场的人看不到游玩位置。
- **有意保留的差别**：
  - 终端点选游玩偏好即生效，网站仍要按“确认更改游玩偏好”，因为它以一到两条远程命令发给终端，而勾选框第一次勾选时弹出的说明需要留在页面上。
  - 终端写固定组合另一人的名字，网站只知道本人的登记，写“另一份登记”。

**验证**：网站构建通过。在本地演示里看过两处的转至、更改游玩偏好、退出排队（文字和按钮）。没有看固定组合和单人机台的情况。

216. **网站其余操作与终端一致**：见上。没有提交。

### 7.90 多个机台分组时的界面：没有设计和测试过（2026-11-25）

维护者：“有多个机台分组时，UI有设计和测试过吗”。

**结论**：

- 机台分组在 0.10.0/0.10.1（2026-09）加入：首页按分组分页，底部有分组栏。
- 现有测试只覆盖设置的规范化和同步，不涉及界面。
- 这一轮界面优化（规范、首页、分步页、菜单）的设计稿和模拟器检查，都只用了一个分组两台机台，`docs/ui-guide.md` 没有分组的规则。

**模拟器上的实测**（连接版 Demo，把机台 A 放进“1F”、B 放进“2F”，默认 1F）：

- **首页**：
  1. 一个分组只有一台机台时，这台机台的卡片占满整个高度，但位置卡片只放大了一些，上下各留出大块空白。
  2. 这时位置卡片在 B 页水平居中，在 A 页（位置多、需要横向滚动）靠左，同一个首页里对齐方式不一致；两台机台同页时都是靠左。
  3. 底部分组栏高 52，组名和机台字母都是 11 号字，在 10 寸平板上偏小；样式是旧的，没有按新规范检查。
  4. 一对机台分在两个分组时，A 页的“可能转至 机台 B”指向另一页上的机台，玩家看不到 B 的队列。
- **设置里的分组**（应用设置 → 机台与分组）：
  1. “创建分组”会把当前选中的机台移进新分组，页面上没有说明。我先在机台 A 上点了“创建分组”，A 就被移走了。
  2. 一个分组被移空后立即消失。
  3. 新分组的默认名称按分组数量编号，会与现有名称重复。实测出现了两个“分组 2”。
- **没有测**：同一分组三台以上机台（纵向滚动）、五个以上分组（分组栏横向滚动）、长组名、网站上的分组显示。
- **使用手册**第 32 行写“底部圆点表示当前页”，实际是带组名和机台字母的分组栏，手册过时。

**改动了模拟器设置**：Demo 现在是两个分组，A 在 1F、B 在 2F，一对机台仍开启。之后的演示如需恢复为一个分组，在机台与分组里把 B 移回 1F 即可。

217. **检查多分组界面**：只检查，没有改代码。

### 7.91 多个机台分组的设计稿（2026-11-25，待维护者确认）

维护者：“出设计稿”。示意图 `tmp/claude-work/ui-review/groups-mockup.html`，做法写在重新设计方案第十三节。要点：

- 单台机台的分组，位置排成两行。
- 位置一律靠左。
- 底部分组栏放大，第二行写机台和登记数。
- 设置里的分组改为一张“分组 × 机台”的表：新建分组不移动机台，编号不重复，空分组可删除，一对机台分在两组时给出提示。

浏览器里检查过：四个画面都没有文字溢出，两行时每行高约 202，两台机台同页时每行 154。

为了在浏览器面板里看示意图，`.claude/launch.json` 临时加了 `mockup-files`（用 Python 在 5190 端口提供 `tmp/claude-work/ui-review`），和本地演示一起，用完删除。

218. **多分组设计稿**：只画示意图，没有改代码。

### 7.92 多分组的首页和分组设置已实现（2026-11-25）

维护者：“关于第一点：占两排，如何让用户清楚看到登记是从左到右从上到下的而不是先从上到下或者其他顺序，如果没有更好的设计就按你给的来。”

**回答和调整**：示意图里原来两行之间隔 8、行内位置之间隔 12，靠得近的一侧会被读成一组，反而像“先上下”。改为行内隔 10、两行之间隔 20，让人按文字的行来读。另外三点让顺序清楚：

- 第一行排满到右边缘才换行；
- 位置编号依次递增；
- 蓝色游玩位置固定在左上角开头，“还有 N 个位置 ›”固定在第二行末尾。

不加箭头（图标规则）。示意图已改，随后按此实现。

**实现**（详见重新设计方案第十三节“实施”）：

- **首页**：
  - 单台机台的分组排两行，可跨行拖动调整位置；
  - 一律靠左；
  - 分组栏放大，显示各组的机台和登记数。
- **设置**：
  - “首页分组”表：新建分组不移动机台，名称不重复；
  - 空分组可删除，保存时去掉；
  - 空分组不能设为默认显示；
  - 分组数量不超过机台数量；
  - 一对机台分在两组时有提醒。
  - 导航说明改为“首页分组、机台管理与详细配置”。
- **测试**：
  - 新增 `MachineGroupTableTest`（4 项）。
  - queue-core 和 app 三个变体全部通过；终端构建通过。
- **文档**：
  - 界面规范“排队首页”加了“两行”“分组栏”两条；
  - 使用手册中切换分组和设置分组的两处已改。
- **模拟器**：见方案第十三节。Demo 的分组最后恢复为一个（组名留作“1F”，只有一个分组时首页不显示组名）。
- **没有做**：管理后台的分组设置；网站上的分组显示没有检查。

219. **多分组首页和分组设置**：见上。没有提交。

### 7.93 界面清理：按规范统一的几处（2026-11-25）

维护者：“接下来还有什么要做的，关于UI请改干净，再看一下哪里有不规范的地方。”

**做法**：

- 按 `docs/ui-guide.md` 查代码：字号、圆角、勾、红色按钮、图标、中点空格。
- 在模拟器上看最近没审过的页面：操作日志、编辑登记、应用设置各分类、机台详情、机厅与终端、报告停止使用、转至确认。

**已改**（都在 `QueueTerminalUi.kt`）：

1. 应用设置：页面标题由 25 改为 30（页面大标题），分类标题由 28 改为 22。原来分类标题比页面标题还大。
2. 编辑登记页标题由 29 改为 30；操作日志标题改用同一常量。
3. 操作日志：
   - 去掉右上角重复的灰色“操作日志”。
   - 两排筛选改为同一种胶囊形筛选标签，选中浅蓝底蓝字，每排前加小标题“类别”“来源”（新组件 `FilterRow`、`FilterPill`）。
   - 修正错误：第一排的“全部”原来从不显示为选中，因为初始筛选和“全部”按钮的标签不同。
4. 机台详情弹窗改为与“机厅与终端”相同的详情行（行间细线）。两个弹窗都改为底部文字按钮“关闭”：原来一个是蓝色主按钮，一个是描边次按钮。删除不再使用的 `MetadataRow`。
5. “返回选择”（未到场处理确认）和“返回选择原因”（停止使用原因）统一为“返回”，与其他确认页一致。
6. 空状态和读取中的标题由 18 改为 17（卡片标题），涉及“还没有操作日志”“还没有玩家资料”“机台已停止使用”“正在读取本机数据”“正在保存玩家资料”。
7. 设置导航“排队规则”的说明改为“暂缓一次、暂时离开、共同游玩预览与一对机台”。

界面规范“弹窗”一节加了“只显示内容的弹窗”一条。全部测试通过；改后的页面都在模拟器上看过。

**没有改、需要维护者决定**：

- **字号**：规范的字号表（30、22、17、15、14、12、11）标的是“建议”。终端里还有：
  - 13 号 97 处（弹窗正文、首页计数行等，其中首页计数行 13 号是已定的）；
  - 20 号 10 处（机台名、时间，已定）；
  - 16、19、25、26、28、34 号若干处。

  已定稿的分步页示意图本身就用了 26、18、13 号，所以不能一刀切。
- **圆角**：规范列的是 11、16、20。另有：
  - 12 号 57 处，多为说明框，事实上是第四种；
  - 8、6、10、14、9、7、13、15、5、18 号若干处。
- **全屏页面的顶栏**：
  - 应用设置是“← 返回”和标题同一行；
  - 操作日志和编辑登记是“← 返回”或“← 取消”单独一行，标题在下一行。

  统一要改应用设置的版式。
- 操作日志里的文字“已恢复左侧·机台 A 的……”机台名前少一个空格（与横幅不同），属于文案，未改。

220. **界面清理**：见上。没有提交。

### 7.94 字号与圆角对照表（2026-11-25，待维护者决定）

维护者：“先出对照表”。

对照表是 `docs/releases/type-radius-review-2026-11.md`：

- 字号 F1～F19；
- 圆角 R1～R13；
- 末尾五个需要决定的问题，以及全屏页面顶栏是否统一。

**发现**：

- 两份定稿示意图本身就用了 13、16、18、19、20、26、28 号字，和 10、12、14 的圆角。所以不能套用规范原来的字号表，改为两层：通用字号，加只用在定稿位置的专用字号。
- 首页位置卡片和加入卡片的实现是圆角 16，首页定稿示意图是 14，实现与定稿不一致（R9）。
- 同一个设置页里，“首页分组”卡片的圆角是 16，其他卡片是 12（R11）。

只出了表，没有改代码。

221. **字号与圆角对照表**：见上。

### 7.95 字号与圆角按对照表统一（2026-11-25）

维护者：“按建议改”。

做法和结果写在 `docs/releases/type-radius-review-2026-11.md` 第六节，要点：

- 弹窗正文 60 处由 13 改为 14；其余字号按表修改 30 处。
- 圆角 53 行按表修改；`ControlRadius` 由 11 改为 12；首页位置卡片和加入卡片由 16 改为 14，与首页定稿一致。
- 表外补改三处同类：日志行、设置导航选中底色、“移除”点击区。
- 全屏页面顶栏保持现状（表里没有建议）。
- 界面规范的字号表改为“已定”，并加了：
  - 13 号的用途；
  - 定稿示意图专用字号；
  - 箭头的大小；
  - 新的圆角档位。

720p 下看过恢复队列、结束本轮、暂时离开确认、未到场、更改游玩偏好、与朋友共同游玩、应用设置、操作日志。全部测试通过。

222. **字号与圆角统一**：见上。没有提交。

### 7.96 登记菜单里的“更改游玩偏好”改为新样式（2026-11-25）

维护者问这个页面能不能改，以及全软件还有没有遗留的旧菜单项。先出示意图 `tmp/claude-work/ui-review/preference-mockup.html`，维护者：“照示意图改”。

**调查结论**：

- 现场终端里，只有这个弹窗还在用旧的选项行和旧的一对机台勾选条。
- 另有一个早已无人调用的旧组件。
- 其他选择页面都是统一的菜单项。

**改动**（详见 `docs/releases/ui-redesign-2026-11.md` 第十四节）：

- 弹窗改用加入排队流程的单选行、勾选行，“与朋友共同游玩”放在“也可以”下面。
- 固定组合时，“与朋友共同游玩”一行浅蓝底，说明写当前组合。勾选行变灰。
- 两条说明改用加入流程的说法，网站同步。
- 删除三个旧组件。调整顺序确认里的同意勾选改用共用组件。
- 界面规范补了这个弹窗和勾选行的灰色状态。

**检查**：

- 全部单元测试通过（1663），网站构建通过。
- 720p 模拟器上看过以下情况：一般情况、固定组合、灰色行提示、调整顺序确认。

**顺带发现**：

- “与朋友共同游玩”第一页的两个按钮被限定为最宽 340、居中排列，与其他弹窗不同。维护者：“顺手改”。现在用登记菜单的 `CompactActionGrid`，两个并排、平分宽度，外观与登记菜单的按钮相同。
- 勾选一对机台后的首页横幅，详情只写“队列状态发生变化。”。未处理。

223. **更改游玩偏好改为新样式**：见上。没有提交。
224. **与朋友共同游玩第一页的按钮并排**：见上。测试全部通过，模拟器上看过。没有提交。

### 7.97 构建 1.0.0（2026-11-26）

维护者：“没有重大待办，可以构建1.0.0”。

**先问清的三件事**（维护者的选择）：

- **版本号**：路线图原把 `1.0.0` 定为“公开部署版”，`0.14.0` 是多终端联动。维护者选“1.0.0，改写路线图”：原定的 `0.14.0`、`0.15.0`、`0.16.0` 和 `1.0.0` 顺延为 `1.1.0` 至 `1.4.0`，内容不变。
- **提交**：选“提交，含交接文档”。提交前扫过全部改动和新文件：
  - 没有令牌、密码或电子邮件地址；
  - 测试凭据只写在已被忽略的 `tmp/` 里；
  - 交接文档里的线上域名和机厅名称，在已提交文件或维护者的公开网站上已经出现过。
  - `.claude/`（本机演示配置）不提交。
- **各端版本**：终端、本地版、管理 App、网站统一为 `1.0.0`，Bot 单独升为 `0.4.0`。

**改动**：

- **版本号**：
  - `app/build.gradle.kts`：本地版、终端版 `1.0.0`/`73`，管理版 `1.0.0`/`69`；
  - 服务端默认最新版本（`app.py`、`compose.yaml`、`.env.example`、`cloud-server/README.md`）及测试；
  - Bot 的 `package.json`、`BOT_VERSION`、README 和两个测试；
  - 网站的 `package.json` 和 `queue-client-version.json`。
- **文档**：
  - 新发布说明 `docs/releases/1.0.0-paired-machines-and-redesign.md`；
  - `docs/update.md` 新增 1.0.0 一节；
  - `docs/development-log.md` 新增发布候选条目；
  - `docs/roadmap.md` 改版本号，并新增 1.0.0 一节；
  - README：版本徽章、链接、功能列表补上轮空一次和两台机台安排、后续计划；
  - 用户手册的版本号；
  - 公开部署文档的各处版本号；
  - 两台机台计划文档的版本说明。
- **应用内**：“应用详情”的版本记录新增 1.0.0 一条。

**构建与测试**：

- **测试**：`queue-core` 181 项，Android 三个变体各 494 项，服务端 239 项，Bot 92 项，网站构建，全部通过。
- **Bot**：改版本号后有两项测试失败，原因是测试里写的上报版本还是旧值，改为 `0.4.0` 后通过。
- **编译内存不足**：`clean` 后一次编译全部变体时，Kotlin 编译进程内存不足。改为逐个变体构建、编译进程 4 GB 后通过。没有改仓库的 Gradle 配置。
- **Release 候选包**（未签名）：在 `output/release-1.0.0`（已被 git 忽略），旁边是 Bot 插件包和网站压缩包。
  - 包名、版本、权限、非调试包都已核对；
  - 包里没有生产域名或令牌。
- **签名**：本机没有长期发布证书，签名必须由维护者完成。写了 `output/release-1.0.0/sign-1.0.0.sh`：
  - 对齐、签名（apksigner 询问密码）；
  - 核对证书指纹 `daa2e919…c925`，不一致就删除签名包并停止；
  - 生成 `SHA256SUMS-1.0.0.txt`。
  - 步骤用一个临时生成、用完即删的测试密钥演练过，对齐和指纹读取都正常。

**部署顺序（已核实旧服务端代码）**：先更新服务端，再更新终端。

- 6a04c24 的 `app.py` 会拒绝“非暂时离开登记不能包含轮空次数”的快照，而新终端在轮空一次期间会上传轮空次数。
- 网站和 Bot 的轮空一次也需要新服务端。

**还没做的**（都需要维护者确认或亲自操作）：

- 签名；
- 推送提交和标签；
- GitHub Release；
- 服务端、网站、Bot 的部署；
- 现场终端的覆盖安装（先读出已装应用的证书）；
- 真实设备验收。

225. **构建 1.0.0**：见上。提交只在本地，没有推送。

### 7.98 长期发布证书不在这台 Mac；1.0.0 测试包（2026-11-26）

维护者：“我不知道我的keystore”“估计没在这里，可能在我的PC上”。

- **在这台 Mac 上找过，没有**：
  - 按文件名（`.jks`、`.keystore`、`.p12`、`.pfx` 等）找过家目录和所有卷；
  - 又按文件开头的格式标记扫了约 13.6 万个文件，只读每个文件的前几个字节，没有读取内容。
  - 只找到 Android 自动生成的调试密钥。
- **以前的签名都在旧 Windows 电脑上完成**，第一次是 2026-08-11 的 0.10.1。已把在 PC 上查找和核对指纹的 PowerShell 命令给了维护者。

维护者又问：“测试版包出来了吗”。

- 用 `packageLocalDebugApk`、`packageTerminalDebugApk`、`packageManagementDebugApk` 生成了 `output/apk/maimai-Q-1.0.0-{local,terminal,management}.apk`（已被 git 忽略）。
- 核对结果：
  - 版本 `1.0.0`，构建号 73/73/69；
  - 都是调试包，用这台 Mac 的调试证书签名（指纹 `c5fd3549…f071`）；
  - 没有生产域名。
- 调试证书与长期发布证书不同，也与旧 PC 的调试证书不同。所以这些包只能装在测试设备上，或装在还没有装过这个应用的设备上，不能覆盖安装现场终端。

226. **查找长期发布证书**：见上，没有找到。
227. **生成 1.0.0 测试包**：见上。

### 7.99 发布前的修改：维护者的 13 条（2026-11-26）

维护者：“这个版本还要再改动”，列了 13 条。其中三条写明“立即进行”，一条标为 P0。

**已完成**：

- **1（P0）倒计时期间点击空白处卡死**：
  - 原因：说明弹窗把“关闭”交给 `ModalSurface`。点击空白处时，`ModalSurface` 先播放关闭动画（弹窗变成完全透明，并锁住自己），再调用关闭；而倒计时期间关闭被拒绝。结果是一层看不见的遮罩留在最上面，挡住所有点击。
  - 修复：`ModalSurface` 增加 `dismissible`，不允许关闭时，点空白处和返回键都直接不响应，不开始动画。只有这一个弹窗用了“有条件关闭”。
  - 模拟器上验证：倒计时期间点空白处，弹窗保持；倒计时结束后点空白处，正常关闭，页面可以继续操作。
- **2 横幅**：去掉“·可在 10 秒内撤销”，撤销按钮保留。
- **6 玩家资料库（资料卡部分）**：
  - 去掉“最近使用”；
  - 性别标记放到右上角，与昵称第一行对齐；
  - 昵称占满宽度，最多两行；
  - 第二行只写默认游玩偏好，不写玩家编号；
  - 同一行的卡片等高。
  - 手册对应句子已改。“更多”里的“查看玩家资料库”需要新的浏览页（左栏现在只用于步骤），先出示意图，未做。
- **7 登记块宽度**：
  - 原来昵称宽度按字数估算（每字 17），并有 142 的上限；小字虽然实际测量，但测量时没有加上中点两侧的留白。
  - 现在两行都按实际显示的样式测量（昵称、间隔和箭头；小字带中点留白），再加 4 的余量，去掉上限。
  - 最窄的登记块仍容得下“允许他人加入”，按测量结果确定。
  - 模拟器上把系统字号调到 115% 和 130% 对比：
    - 修改前，130% 时四五个字的昵称出现省略号；
    - 修改后，昵称和小字都完整显示。
  - 检查后字号已恢复为 100%。
- **4 “还有 N 个位置”**：
  - 单行队列原来用列表自带的跳转。现在按可见卡片的平均宽度估算距离，用 0.45 秒缓入缓出滚动，最后对准末尾。
  - 两行队列原来就有 0.32 秒的动画，改为同样的 0.45 秒。
  - 模拟器截图只能确认停在末尾，手感需要维护者在设备上判断。
- **8 登记方式卡片**：
  - 去掉“使用玩家资料库”的蓝色描边、浅蓝底和实心蓝图标，两张卡片样式相同。认领方式页的同一组卡片也一并改了。
  - `RegistrationMethodCard` 去掉 `primary` 参数。
  - 界面规范对应条目已改为“已定”。

**13 估计时间，以及维护者的推测**（“两个登记的估计时间相同，与他们被放在一个位置里，在大多数情况下是等价的”）：

- **算法**：估计按登记计算。等待位置的估计时间是位置里各份登记中较早的一个（`WaitEstimator.Result.estimate`），没有另一套算法。
- **实验**：用临时测试（做完已删除）随机生成 600 个单机台队列，对每两份等待中的登记比较“画面上是否同一位置”和“估计是否相同”。
  - **只有单人游玩、允许他人加入的普通登记时，完全等价**：
    - 同一位置的 589 对估计都相同；
    - 不同位置的 7401 对估计都不同，按显示规则取整后也都不同。
  - **加入暂缓一次、轮空一次、暂时离开和待签到后**：
    - 同一位置的 495 对里，有 214 对估计不同，其中 83 对相差 5 分钟以上；
    - 这 214 对所在的队列里都有待签到的线上登记；
    - 不同位置估计完全相同的有 10 对，取整后相同的有 136 对。
  - **原因**：画面按“待签到的登记都会签到”来分组，估计时间按“约一半会签到”来推算；没签到时，后面的组合会重新搭配。
- **结论**：没有待签到登记时，推测成立；有待签到登记时不成立。已记入发现清单 U3。建议在登记菜单里显示每份登记自己的估计时间，等待位置继续显示其中较早的一个。待维护者决定。

**未做，需要先讨论或出示意图**：

- 3：每份登记的日志（终端和网页）；
- 6：“查看玩家资料库”入口；
- 9：新终端接入机厅后要重启才能正常同步，待排查；
- 10：已绑定网页账户的资料在界面和文案上的体现，现状已查明，见发现清单 U6；
- 11：可以转出时先单独占一个等待位置；
- 12：另一机台登记的预览弹窗内容；
- 13：登记菜单显示估计时间。

第 5 条（弹窗动画）维护者说可暂缓。

**使用细节**：维护者要求把对使用细节的要求做个总结，并在发现问题时提出。新建 `docs/releases/usability-review-2026-11.md`：

- 第一部分归纳维护者的要求；
- 第二部分是发现清单 U1～U6。

**检查**：

- 测试全部通过：`queue-core` 181 项，Android 三个变体各 494 项。
- 已在模拟器上看过：首页、资料库、登记方式、说明弹窗、结束本轮横幅。
- 演示时在机台 B 结束了一轮，撤销没有点中，演示队列因此变了：“幸运的星星”在游玩位置，Mika 和 Yuki 在 B1。
- `output/release-1.0.0` 里的未签名候选包是修改前构建的，签名前要重新构建。测试包已重新构建。

228. **修复说明弹窗卡死**：见上。
229. **横幅、资料库卡片、登记块宽度、滚动、登记方式卡片**：见上。
230. **估计时间与位置的对照实验**：见上，临时测试已删除。
231. **使用细节文档**：见上。

### 7.100 发现清单 U1、U2、U4；线上登记的估时与分组（2026-11-26，讨论中）

**维护者的答复与处理**：

- **U4**（维护者：“之前不是订好了什么时候显示机台B什么时候显示B1吗，有分工的吧”）：确有分工，见 7.82。
  - 另一机台显示预览时，登记块写位置（可能转至 B1）；没有显示时，退回写机台。
  - 演示里机台 B 的本轮已进行 368 分钟，按规则暂停安排，预览不显示，但预告记录还在，所以写了“机台 B”。
  - 这种情况下登记块该写什么，待维护者决定。代码未改。
- **U2**（“改一下”）：
  - 操作日志的变化识别加上“允许系统在两台机台之间安排”这一项：
    - 开启时写“已允许系统在两台机台之间安排登记”，在排队中途开启的，后面加“游玩一轮之后开始安排”；
    - 关闭时写“不再由系统在两台机台之间安排，登记留在当前机台”。不写是谁关闭的，因为组成固定组合时也会自动关闭。
  - 横幅和操作日志都用这句。日志标题为“两台机台之间的安排已更改”。
  - 同时把日志标题“暂缓一次或暂时离开状态已更新”补为“暂缓一次、轮空一次或暂时离开状态已更新”。
  - `AuditLogsTest` 新增一项测试。
- **U1**（“如果需要设计新UI就暂缓”）：不需要新设计。位置卡片标题行由固定 22 高改为至少 22 高。100% 字号下外观不变；130% 下“已游玩 N 分钟”不再被裁掉，模拟器上核对过，之后字号已恢复为 100%。

**测试**：本地版、终端版各 495 项，全部通过。

**线上登记**：

- 维护者先决定“改成全部签到”，并在前方有待签到线上登记时，在估时文案中提示“可能更早游玩”。
- 还没动手时，维护者又提出新想法：默认全部不签到，让未签到的线上登记单独占一个等待位置，签到后才和允许他人加入的登记组合；估时写“最快约……；前方的线上登记签到后，这份登记会晚一轮游玩，约 X 分钟才能游玩”。
- 我的分析和建议在本轮回复中（要点见 7.101，待维护者决定后补记）。估时程序尚未修改。

232. **U1、U2**：见上。

### 7.101 线上登记按都会签到估算；登记菜单显示等待时间（2026-11-26）

**两种做法的比较**（7.100 末尾提到的分析，按例子说明）：

- **例子**：小林、线上小王（待签到）、小张、小陈都是允许他人加入，小赵单人游玩。
  - 小王签到时：小林、小王约 10 分钟；小张、小陈约 25 分钟。
  - 小王没签到时：小林、小张约 10 分钟；小陈约 25 分钟。
- **维护者的新想法**（未签到的线上登记单独占一个位置）：
  - 待签到的登记不会以待签到的状态游玩，画面怎样分组都不改变实际顺序；
  - 单独占位时，画面与两种实际结果都对不上；
  - 有人签到时，后面的人会被往后挤；
  - 位置编号随签到变动；
  - 而且“签到后晚一轮”只对部分人成立。
- **我的建议**：分组和主时间都按都会签到；提前得明显的登记另写一句最快时间。

维护者：“你说的对，按这个来。”

**实施**：

- **queue-core**：
  - `WaitEstimator.Settings.checkInShare` 默认改为 1。
  - 有待签到登记时，再以 0 推演一遍；提前至少 5 分钟（`onlineMissingNoteAtLeastMillis`）的登记记入 `Result.earlierIfOnlineMissing`。
  - `earlierPositionWaitIfOnlineMissing` 只在位置里每份登记都提前时给出。
  - 测试改写一项、新增一项。
- **终端**：
  - `onlineMissingWaitMinutes` 和 `onlineMissingWaitNote` 是终端、快照共用的规则：按“最快”向下取整，显示的分钟数更小时才有。
  - 登记菜单的“创建·尚未游玩”一行末尾加上这份登记的等待时间（维护者第 13 条的最小做法），下面一行写这句。
  - 位置详情在每份登记都提前时写“这个位置……”。
  - 快照的等待登记增加 `wait_minutes_if_online_missing`。
- **服务端**：
  - 校验 0 至 100000 的整数，保存；公开快照、`/api/player-account/queue`、Bot 玩家列表都提供。
  - 旧服务端不认识这个字段，会直接忽略，不会拒绝上传。
  - 新增一项测试。
- **网站**：
  - 新文件 `waitEstimateCopy.js`；
  - 队列页的位置详情、登记详情，以及账户里的本人登记，都显示这句。
- **QQ Bot**：“我的排队”和通知末尾的当前位置显示这句。新增一项测试。
- **文档**：用户手册两处、文案规范的等待时间表、协议文档、估时设计文档第九节、1.0.0 发布说明和更新日志。

**检查**：

- 测试：queue-core 182 项，Android 三个变体各 496 项，服务端 240 项，Bot 93 项，网站构建，全部通过。
- 模拟器：登记菜单显示“……·尚未游玩·约 15 分钟后可以游玩”。演示队列里没有待签到登记，提示句由单元测试覆盖。
- 本机演示网站：
  - 给演示登记加上这个字段后，公开快照带出了这个字段；登记详情显示“前方的线上登记未按时签到时，这份登记最快约 3 分钟后可以游玩。”。
  - 演示服务端重启过，以读入新的服务端代码。演示状态已恢复。

**U4**：维护者：“我也想不好，暂时写机台吧，你可以提意见哪种更好”。现状不变。我的意见：

- 暂停计时期间，预告仍然有效，只是推迟。所以登记块继续写“可能转至”比改回游玩偏好好，否则玩家会以为预告取消了。
- 更好的做法是：暂停期间另一机台继续显示这份登记的预览，可以标出“暂停”。这样登记块仍能写位置编号，两边的画面也一致。这需要改预览的显示规则，留待维护者决定。

233. **估时改为按都会签到，并加上最快时间的说明**：见上。没有提交。

### 7.102 网站与新旧终端的适配检查（中途暂停）；单人游玩允许越过的设计草案（2026-11-26）

**网站检查**（维护者：“检查网页端是否适配最新版终端和旧版终端……然后可以推送网页端更新”；之后又说：“先不推送这份网页端了”）。

- **环境**：
  - 在 `tmp/claude-work/old-0.13`（git worktree，6a04c24）运行旧服务端，用旧格式的快照模拟 0.13.6 终端（`tmp/claude-work/demo/serve_old.py`，端口 8792）；
  - 新网站单独构建，指向它（端口 5184，`.claude/launch.json` 新增 `demo-old-server`、`demo-old-site`）。
- **结果**：
  - 队列页、登录、本人登记的显示都正常。
  - 网站对旧服务端仍然显示“轮空一次”，提交后旧服务端返回 400“operation 选项无效”。旧终端遇到这个操作也无法执行。
  - **已有缺陷（0.13.4 起就存在）**：账户对话框里提交的操作失败时，错误提示会立即被刷新清空，玩家什么提示都看不到。已修复：刷新完成后再写入错误（`PlayerAccountDialog.vue` 的 `finishQueueCommand`）。队列页的操作有单独的反馈列表，不受影响。
- **待办**：网站只在服务端和终端都支持时才提供“轮空一次”。
  - 打算由新服务端在 `capabilities` 里按终端版本给出 `skip_one_turn`；旧服务端不给，网站就把这张卡片显示为灰色并说明原因。
  - 未实施。
- 另起了一个独立审查，审查网站自 6a04c24 以来的全部改动，结果到了以后补记。
- 网站暂不部署。生产网站在 `/var/www/queue-site`，以前是从旧 Windows 电脑经 SSH 部署的。

**单人游玩允许越过**（维护者：“我们把单人游玩允许前后玩家相互组合功能做了吧……注意在编辑时如果涉及冲突，要单独设计如何处理”）。

- 设计草案：`docs/releases/solo-pass-plan-2026-11.md`。
- **核对结果**：
  - 将暂缓一次、将轮空一次只改状态，不改真实顺序；画面位置由推演得出。
  - 长按调整和集中编辑按真实顺序列出登记。
  - 首页整组拖动只允许画面位置与真实分组一致的位置。
- **发现**：首页整组拖动会把真实顺序“按位置写回”（`moveWaitingPosition`）。一旦允许越过，与拖动无关的登记的真实先后也会被悄悄改变。草案建议改为只移动被拖的登记。
- 六项待维护者决定，见草案第五节。未改代码。

234. **网站适配检查（部分）与账户对话框错误提示修复**：见上。
235. **单人游玩允许越过的设计草案**：见上。

### 7.103 网站独立审查的结果（2026-11-26）

审查代理通读了 `public-site/` 自 6a04c24 以来的全部改动，报告 9 条，整理在 `docs/releases/website-review-2026-11.md`（W1～W9）。

- **我复核了两条严重的**：
  - **W1**：同步到主站的共享文件清单缺了 7 个新文件，同步后主站构建会失败，`--check` 却报一致。
  - **W2**：网站总是提供“轮空一次”。
    - 旧服务端会拒绝。
    - 新服务端配旧终端时，旧终端读不懂这条命令，直接丢弃；命令挂起至超时，这期间该玩家的其他操作都会得到 409。
    - 队列页还会把“仍在等待”显示在标题“操作已完成”下面。
- 其余七条由代理读代码得出，未逐条复核。
- 网站暂不部署。这些问题留到网站这一轮一起处理，代码未改。

236. **网站审查结果整理**：见上。

### 7.104 单人游玩允许越过：文案按用词指南调整（2026-11-26）

另一个会话（项目磁盘占用空间）转来维护者对这项功能的两点意见，并说明这项功能由本会话继续做。

- 用词指南第 37 行新增“前方的登记、后方的登记”（“的”不省略，已定 2026-11-26），是那个会话写入工作区的。
- 动词沿用现有文字的“越过”，不用维护者口述的“绕过”。

已核对指南，并据此改了设计草案的标题和第六节：

- 勾选框：“允许后方的登记越过这份登记，与前方的登记共同游玩”；
- 小字改写，不靠引号断句；
- “轮次不变”注明要等随机验证通过后才写。

那个会话还提到：用户手册第 124、133、309 行写的是“后方玩家”，以后可以统一为“登记”。未改。

237. **草案文案调整**：见上。

### 7.105 整组拖动的缺陷已修复；单人游玩允许越过：排队核心已实施（2026-11-26）

维护者对设计第五节：“都按你的来”。又问整组拖动“这个应该先解决吧，目前是如何处理的，会出bug吗”。

- **整组拖动**：现在就有缺陷，实测与修复见设计第七节。
- **越过的排队核心**：已实施，见设计第八节。随机验证发现两个反例。
  - 第一个（越过时顺带用掉中间登记的暂缓一次）已修复。
  - 第二个（有暂缓、轮空时，搭档被前面的组合用掉）是组合换搭档的固有结果，约 0.6% 的队列出现，待维护者决定处理方式。
- **还没做的**：
  - 持久化、同步字段、服务端；
  - 设置和取消的操作；
  - 终端和网站的勾选框；
  - 画面标记的示意图。
- **测试**：queue-core 190 项全部通过。

238. **整组拖动修复**：见上。
239. **越过规则（排队核心）与随机验证**：见上。没有提交。

### 7.106 越过前先推演的保护；功能名称（2026-11-26）

维护者听了例子后：“应该加上保护，不然开启了这个功能的人会吃亏。”

- **讲解**：我先用随机搜索找到的最短例子讲解。第一次讲解时把“谁越过谁”说反了，维护者指出后更正：是后方的登记越过，与前方的登记组合。
- **保护已实施**，见设计第九节。
  - 随机验证：含全部状态的 3000 个队列中，没有任何登记更晚。
  - 计算量用缓存压到估时多约 2 毫秒。
- **测试**：queue-core 191 项、Android 本地版 496 项，全部通过。
- **功能名称**：维护者起名“允许后方的登记绕过这份登记，以组成完整等待位置”。我按规定润色为“允许后方的登记越过这份登记，与前方的登记共同游玩”，理由见设计第十节，待确认。
- **还没做的**：持久化与同步、设置与取消的操作、终端和网站的勾选框、画面标记的示意图。

240. **越过前推演的保护与缓存**：见上。没有提交。

### 7.107 单人游玩允许越过：文字定稿与示意图（2026-11-26）

文字经过五轮修改后由维护者确认，结果在设计第十一节。期间维护者又一次指出“太口语化了，很AI味。强调过很多次了”，已写进文案风格的记忆。

示意图 `tmp/claude-work/ui-review/solo-pass-mockup.html` 共四幅：

- 确认加入排队：勾选框放在单人游玩这一行里面，默认不勾选；
- 更改游玩偏好；
- 首页越过后的样子；
- 登记菜单的三种情况。

在内置浏览器里核对过：720p 下都放得下，没有文字被截断。待维护者确认。

241. **文字定稿与示意图**：见上。


### 7.108 单人游玩允许越过：终端、服务端、网站已实施（2026-11-26）

维护者：“照示意图做”。全部未提交。设计第十三、十四节是实施记录。

**终端**（我做的）

- 勾选框组件 `SoloPassingChoice`，嵌在“单人游玩”行里；`FlowListRow` 新增 `continuesBelow`，使细线落在示意图的位置。新颜色 `SelectedRowDivider`。
- 登记菜单的说明行抽成 `RegistrationMenuStatusLine`，与一对机台的说明行共用。
- 首页越过的一方小字“将越过“X””，与“可能转至”同一通道，后者优先。
- 模拟器上核对了：
  - 加入流程、确认加入、更改游玩偏好；
  - 菜单的三种情况、结束本轮后的“正在”；
  - 长按调整按真实顺序；
  - 重启后保留、取消后复原；
  - 玩家资料加入时保存选择。
- **示意图外的补充**：默认单人游玩的玩家资料，确认页上勾选框单独放一个列表组。
- 补上漏做的一条：转入只容纳一人的机台时取消这项选择，并加了测试。
- 顺手修了 U7：长按调整时短昵称截断小字。

**服务端和网站**（子代理做，我复核）

- 测试和构建都通过。
- 我改了一处：未知的操作名称略去，不再拒绝整份上传。
- 我补了两处：
  - 网站上默认单人游玩的资料线上登记时的勾选框；
  - W2 后半，等待中的命令标题。
- W1、W2 已标为已改。

**测试**：排队核心 195 项，终端、本地、管理各 498 项，服务端 251 项，Bot 93 项，全部通过；`pnpm build` 通过。

**交给维护者决定的细节**：

- U8：结束本轮弹窗的预览是否也显示“将越过”；
- U9：示意图与实现的细线差别；
- U10：网站上他人登记详情的“可以取消”一句。

**没做的**：

- 扫码登记不接受这个字段；
- 管理 App 没有这项操作；
- 主站共享文件未同步。

演示服务：`tmp/claude-work/demo/passing/serve_passing.py`（8795）和网站快照（5195），`.claude/launch.json` 里是 `verify-passing-server`、`verify-passing-site`，已停止。

242. **越过功能实施完成**：见上。

### 7.109 越过功能：维护者的回复与跟进（2026-11-26）

维护者对 7.108 的回复和我的处理（设计第十五节）：

- **默认单人游玩的资料单独放勾选框**：保留。
- **U8 结束本轮弹窗的预览**：改。
  - 首页登记块小字的逻辑抽成 `registrationTileNotices`，首页和弹窗共用。
  - 模拟器上核对了“将越过“迷路的企鹅”等 2 份登记”。
- **U9 细线**：我用模拟器出了两种做法的实际截图（`tmp/claude-work/ui-review/u9-A-with-hairline.png`、`u9-B-without-hairline.png`）。
  - 维护者：“画不画无所谓吧，跟其他地方保持统一吧”。
  - 保留细线；示意图改为同样画细线。
  - 出图用的临时改动已撤回，并核对过与改动前逐字相同。
- **U10 网站他人登记详情的“可以取消”**：去掉，只对本人显示（`soloPassStatusParts` 的 `tellsHowToCancel`）。
- **新要求：玩家资料记住上一次的选择**。
  - 资料字段 `lastAllowsPassing` / `last_allows_passing`，终端为准，服务端按资料版本覆盖保存，并在全部资料对象里返回。
  - 终端在三种情况下记下：
    - 用资料加入（单人游玩、两人机台）；
    - 执行网站 `JOIN_QUEUE`（带 `allows_passing` 时）；
    - 更改游玩偏好里勾选或取消（本机和远程都算）。
  - 网站线上登记的勾选框从这项记录开始；提供勾选框时，勾不勾都发送 `allows_passing`。
  - 模拟器上来回核对过（勾选、退出再加入后预先勾上；取消、加入后预先不勾）。
  - 浏览器里核对了网站预先勾上，取消后发送 `false`。
- **顺带**：改为允许他人加入时，日志和横幅不再另写“不再允许后方的登记越过”。

**测试**全部通过：

- 排队核心 195 项；
- 终端、本地、管理各 500 项；
- 服务端 252 项；
- 网站构建通过。

演示服务已停止。演示脚本增加了 `second_default` 和 `second_last_allows_passing` 两个开关。

243. **越过功能的跟进**：见上。

### 7.110 游玩位置中更改游玩偏好：写明本轮游玩结束后生效（2026-11-26）

维护者：游玩位置的玩家更改游玩偏好时，游玩位置不会变化，可能反直觉，“加入文案‘会在下一轮才生效’”，格式与一对机台勾选框的“本轮游玩结束后生效”一致。

**写法**（已写进用词指南）：

- 弹窗说明：
  - 玩家资料：“新的选择只用于本次排队，本轮游玩结束后生效；玩家资料中的默认游玩偏好不会改变。”
  - 临时登记：“新的选择在本轮游玩结束后生效。”
- 横幅和日志：在句末加“，本轮游玩结束后生效”；允许越过的勾选同样加。
- 网站和 Bot 收到的结果：“本次游玩偏好已改为“X”，本轮游玩结束后生效。玩家资料中的默认游玩偏好没有改变。”
- 网站的弹窗说明同终端，按 `position === 'PLAYING'` 判断。

**顺手改的 U11**：游玩位置中登记的菜单原来写“尚未游玩”，与“正在与……共同游玩”矛盾；在游玩位置时不再写这一项。

**核对**：

- 模拟器上看过弹窗说明、横幅，以及游玩位置保持不变；
- 测试全部通过：排队核心 195 项，终端、本地、管理各 502 项，服务端 252 项，网站构建通过。

244. **游玩位置中更改游玩偏好的说明**：见上。

### 7.111 “更多”终端状态的颜色；批量创建入口与应用详情的草稿（2026-11-26）

**“更多”终端状态的颜色**（已改）

维护者：“保留原有的那些颜色，而不是非异常始终为灰色……电量，插电时为绿色，电量不足时为橙色，其他时候为灰。”

- 同步一格沿用 `queueCloudSyncStatusColor` 的含义：已同步绿、同步中蓝、需要处理橙、其余灰。
- 电量一格：充电绿、电量不足橙、其余灰。
- 实现：`TerminalStatusTone`。模拟器上看过充电（绿）和未充电（灰）。
- 未能在模拟器上验证的部分：
  - 低电量橙色：模拟器改不了应用读取的电量；沿用原有的橙色样式。
  - 同步的绿色和蓝色：演示终端未开启同步。

**选择登记方式页的“批量创建登记”**（待讨论，未实施）

- 维护者要求在“创建临时登记”下方并列，文字要讨论。
- 示意图：`tmp/claude-work/ui-review/batch-entry-mockup.html`。
  - 第 2 步页面；
  - 点进去后作为同一流程的第 3 步“数量”，共 3 步；
  - 小字有甲、乙、丙三种写法；
  - 数量页说明后补一句怎样认领；
  - “更多”里原有入口我建议保留。

**应用详情的删减与补充**（待确认，未实施）

- 现在的弹窗在 720p 下放不下（“关闭”被挤出）。
- 建议：
  - 删除排队方式、单台上限、系统要求、资料与日志、队列恢复；
  - 同步状态改为同步方式和服务器；
  - 构建编号并入版本，并写明测试版或正式版；
  - 新增安装时间、签名证书（可选）、设备、屏幕；
  - 日志条数和玩家资料数合成“本机数据”。
- 共 10 行。示意图：`tmp/claude-work/ui-review/app-details-mockup.html`。

245. **“更多”的颜色与两份草稿**：见上。

### 7.112 批量创建入口、应用详情、首页滚动与渐隐、转至机台列表；拖动转至的草稿（2026-11-26）

维护者：“按这个”（批量创建小字用甲）、“都按你的改”（应用详情和批量入口按草稿），并追加三项：三台以上机台时底部渐隐；刚操作完的机台要翻到它的位置；“转至其他机台”改为一个列表的新样式、标题“选择要转至的机台”、去掉“转入后回到队尾”。

**批量创建登记的新入口**

- 按示意图：加入排队第 2 步“创建临时登记”下方。
- 点进去后左栏为“创建登记并加入排队”第 3 步“数量”（共 3 步）；“修改”和“返回”回到第 2 步。
- 实现：`startBatchFromJoinFlow`、`joinFlowBatchSummary`、`batchFromJoinFlow`。
- 批量页说明补了怎样认领，并顺手修了 U12（仅能容纳一人的机台写“单人游玩”）。
- “更多”里的原入口保留。

**应用详情**

- 按示意图实现：
  - 版本（构建、测试版或正式版）、安装时间、签名证书前 16 位；
  - 机厅、终端、与服务端同步（方式·域名，未开启时写“未开启”）；
  - 设备、屏幕（分辨率·界面缩放）、本机数据。
- 行距收紧到 10，720p 下整页放得下（U13）。

**首页滚动与渐隐**

- 横幅带上所涉机台（`HomeSidePanelFeedback.machineId`，覆盖各个写明机台的横幅；转至时为目标机台）。
- 首页的 `scrollTarget` 来源：新登记高亮、撤销横幅、横幅。
- 先翻到所在分组页，再把机台列表滚到能完整看到该机台。
- 三台以上可滚动时，底部 72 的渐隐淡入页面底色，到底消失。
- 本地版（机厅 Test）上加了机台 C 核对过：
  - 批量创建后滚到机台 C；
  - 转至机台 A 后滚回 A；
  - 渐隐出现与消失。
- 演示终端（Demo）没有改动：加机台需要先关闭登记排队，会清空 16 份演示登记。

**转至其他机台的选择弹窗**

- 与“选择机台”同样的列表；
- 每行写等待位置和登记数，右侧“转入后预计等待”；
- 不能转入的行变灰并写原因。

**拖动转至其他机台**（维护者新要求，草稿待确认，未实施）

- 示意图 `tmp/claude-work/ui-review/drag-transfer-mockup.html`：
  - 拖一份登记或一个位置到另一台机台；
  - 目标机台整体盖蓝色蒙版，写“松开后转至 右侧·机台 B”，位置不让位；
  - 不能转入时为灰色蒙版加原因；
  - 松开后弹出现有的确认弹窗。
- 附五条细节建议：
  - 游玩位置锁定；
  - 固定组合两份一起转；
  - 拖到边缘自动滚动，只限当前分组页；
  - 松在空处则回到原处。

**测试**：终端、本地、管理各 502 项通过。

246. **上述五项与拖动转至草稿**：见上。

### 7.113 拖动转至其他机台已实施；更改游玩偏好不再选后即关（2026-11-26）

**拖动转至其他机台**（维护者：“按草稿做”）

- `MachineDropTargets`（QueueUiState.kt）由首页提供：
  - 各机台报告自己在屏幕上的范围；
  - 拖动时记下来源和指针，算出指针下的其他机台；
  - 松开时返回三种结果（不在其他机台、不能转入、转入）。
- 三条拖动路径都接上了：
  - 调整中的登记（`InlineReorderContent`）；
  - 单行布局的位置；
  - 两行布局的位置（`TwoRowQueuePositions`）。
- 指针在其他机台上时，原机台的顺序不再变动；松开后先复原。
- 转入时打开现有的转至确认（`MachineTransferRequest` 带目标机台）；从调整中拖出并转入时，调整随之结束。
- 只有一个等待位置时，也可以拖出去转至别处。
- 机台的蒙版是 `MachineDropMask`，机台卡片外面因此包了一层 Box。
- 三台以上时，拖到列表上下 72 以内会自动滚动；指针仍在原机台上时不滚。
- 不能转入的判断与转至菜单相同（`machineTransferUnavailableReason`，另加“已进入游玩位置”）。
- 本地版（三台机台）上核对过：
  - 拖位置到机台 B：蓝色蒙版，确认后转入；
  - 从调整中拖登记，自动滚到机台 C：蒙版正确，取消后什么都不变，调整结束；
  - 拖到已停止使用的机台 C：灰色蒙版加原因，松开不弹窗。
- 机台 C 之后已恢复正常使用。

**更改游玩偏好**（维护者新要求）

- 选择一项后立即保存，弹窗不关；越过和一对机台的勾选同样。
- 底部“取消”改为“关闭”。
- 模拟器上核对过：选单人游玩后勾选框立刻出现，勾选后关闭，首页随之更新。

**发现清单**：

- U14：“报告机台停止使用”仍是分开的卡片，待定；
- U15：灰色蒙版的原因会被手指拖着的位置盖住，待观察。

**测试**：终端、本地、管理各 502 项通过。

247. **拖动转至与偏好弹窗**：见上。

### 7.114 估计时间修复、排队逻辑审查与修复（2026-11-27 凌晨，维护者休息期间）

维护者：“估计时间算法疑似有bug。我创建了一些登记，A1的估计时间正常，后面的登记一律估计为15分钟”；随后“请认真检查目前的排队逻辑和bug，尤其是这个估计时间。我现在要休息了，你尽可能连续工作，保留问题，把不需要和我讨论的一些待办事项连续处理掉。”

**估计时间**（详见 `docs/releases/wait-estimate-2026-11.md` 第十节）

- 原因：一份可能被安排到另一机台的登记，自己的时间算上了另一机台，早得多；“前面的位置不比后面晚”这条规则又把它前面的每个位置压成同一个数。
- 修复：估算结果另记“在当前机台”的时间（`Result.here`、`hereEstimate`），这条规则只按当前机台的时间比较（`laterLimits`、`displayMinutesInOrder` 的第二个参数）。终端菜单、位置详情、快照的 `waiting_positions` 和 `paired_waiting_positions` 都按此计算。
- 共同游玩位置中的一份登记显示自己的时间。快照新增可缺省的登记字段 `own_estimated_wait_minutes`、`own_estimated_wait_uncertain`，服务端在“我的登记”和 Bot 玩家列表中优先使用（`docs/cloud-queue-sync.md`）。
- 没有人在玩、等待中只有一份“暂缓一次”时原来没有时间，已修（`MachineReplay.filledAgain`）。

**新的随机检查** `QueueEngineInvariantTest`

- 两台一对机台加一台单人机台，300 组随机动作，每组 120 步；
- 每一步都检查规则允许的状态：编号不重复、容量、固定组合同步且相邻、越过只属于单人游玩、游玩位置没有缺席或待签到、等待中没有越过记录、独自游玩的人没有越过记录、位置覆盖全部等待登记；
- 抽样检查估计时间：每份在场的等待登记都有时间，当前机台上显示的时间不倒退；
- 检查“下一组”与开始下一轮时实际进入的一组相同。
- 它发现并已修的：移入本轮时越过的选择没有关掉；等待中的登记留着越过记录；只剩一份暂缓一次时没有时间。

**排队核心审查**（子代理只读审查，复现程序在 scratchpad，结论已逐条核对）

已修，均有测试（`PassingSoloRoundsTest`、`QueueRiskSequenceTest`）：

1. 越过前的检查结果被缓存，但在有人游玩时算出的结果总是“可以越过”，之后游玩位置空出时被误用，越过会让后方的登记晚一轮。现在检查总是从游玩位置为空推演。
2. 与朋友共同游玩的预案在玩家改了越过的选择后仍可应用，会把选择改回去。比较“预案是否过期”时现在也比较越过的选择。
3. “未到场”“移入本轮”“首页的结束本轮快捷入口”用的是带越过的分组，与越过检查拒绝越过时真正的下一组不同。新增 `MachineQueue.nextWaitingGroup()`（经过检查的下一组），这几处改用它；画面上的位置不变。
5. 越过记录：不能游玩的登记（暂缓一次、轮空一次、暂时离开、待签到）不再算作被越过；搭档离开后独自留在游玩位置的人不再带着越过记录。
6. 按组暂缓的未到场只传入固定组合中一人时，现在两人一起暂缓（终端总是传整个位置，只有网站或接口调用会遇到）。

需要维护者决定（未改）：

4. 组成固定组合时，这份登记会继承对方的延后状态，可绕过“暂缓一次在游玩前只能用一次”和“线上登记游玩前不能延后”。

**估计时间与远程操作审查**

已修：

- 网站“一对机台”视图的时间也按当前机台比较（见上）。
- 网站“我的登记”和 Bot 对共同游玩位置中一份登记的时间（见上）。
- 轮空一次或暂缓一次已经执行后，玩家才点“取消”，原来回答“已经取消”。现在拒绝并写明“这份登记已轮空一次并移至队尾，无法再取消。”或“这份登记的暂缓一次已完成，无法再取消。”（新文案，固定组合各有一句）。
- 越过的选择写入玩家资料失败后重试，原来不再写入，已改为记住。管理后台创建登记的首次执行也与重试一致地记住（管理 App 目前不发送这个字段，实际不受影响）。
- 线上加入和转至其他机台原来按登记数量判断是否成功，同一次执行中另一机台的登记被安排过来时会误判失败。改为看这份登记本身在哪里。

已知、暂不处理：一份可能转走的登记与紧随其后的位置可能显示相同的分钟数（见估计时间文档第十节末）。

**手机扫码登记的越过选择**

- 服务端：`/api/queue-mobile/sessions/<token>/submit` 接受 `allows_passing`；终端未声明支持、单人机台、本次不是单人游玩时拒绝（409）；会话的 `capabilities.queue_operations` 列出终端支持的操作。
- 终端：手机登记命令带上 `allows_passing`，并按它记住到玩家资料。
- 页面（`MobileRegistrationFlow.vue`）：终端声明支持、机台可容纳两人、本次为单人游玩时提供勾选框；每次询问偏好的资料放在“单人游玩”选项里，默认单人游玩的资料单独放；起始状态为资料上一次的选择，新建资料不勾。计划文档第十六节。
- 测试：`test_mobile_submit_carries_the_passing_answer_where_the_terminal_knows_it`。
- 浏览器核对（本地演示，手机尺寸）：每次询问偏好的资料选“单人游玩”后，勾选框出现在选项内并按上次选择已勾；提交后终端收到的命令带 `allows_passing: true`、`preference: SOLO`。

**网站审查 W3–W9、U5**（子代理完成，我核对了构建和命令链代码；详见 `docs/releases/website-review-2026-11.md` 的状态列）

- W3：新增 `queueCommandChain.js`，账户对话框和登记详情的队列操作都经它发送；关闭对话框不中断，逐条确认后再发下一条，失败即停，结果分“已完成”和“未完成及原因”。
- W4：账户对话框的位置号、时间和预告句与队列页（含另一机台预览的编号）一致。我随后补了一处：这样取时间会把上面修好的“共同游玩位置中一份登记自己的时间”又换回位置的时间；现在账户接口带 `estimated_wait_is_own`，为 `true` 时对话框保留登记自己的时间（服务端测试已补）。
- W5：固定组合不能转入单人机台时，“转至”置灰并写明原因。
- W6：线上加入的两台机台勾选框在同一页面第二次加入时恢复为不勾。
- W7：轮空上限提醒也出现在账户对话框和登记详情。
- W8：文案与终端统一（“自动移除”“不愿透露”、机台全名等）。
- W9：说明弹窗倒计时期间 Esc 不再关闭；只有预览的位置不再画成可点的样子。
- U5：两台机台并排时，长昵称的位置改为两块上下排，不再截断。
- 待维护者过目的新文案：横幅标题“操作未全部完成”；“以下更改未完成：{更改}；{更改}。{原因}”及其中各项更改的叫法。
- `pnpm build` 通过。`sync-site-main.mjs` 清单现为 16 个文件。

**其他**

- “报告机台停止使用”的机台选择改为与“选择要转至的机台”相同的连起来的列表（U14）。
- 待办“管理后台的未到场窗口按新规则置灰”（7.88 待办汇总第 3 条，方向已定）已做：管理 App 读取快照的 `online_registration_not_played`，对已签到、未游玩的线上登记置灰“暂缓一次”和“移至队尾”，并在上方写出与终端相同的句子（“这份线上登记已完成签到，尚未游玩。线上登记第一次轮到时未到场，只能移除登记。”，组内则为“组内有……”）。服务端早已发送这个字段。管理版编译通过；解析函数是私有的，没有单独的单元测试。
- 待办“终端关机或重启期间，线上登记的签到时限不应流逝”（7.52 甲 1，方向已定）已做：继续使用上次的队列时，待签到的线上登记重新获得 30 分钟签到时限，与机台恢复正常使用后的处理相同（`restartPendingCheckInTimers`）。没有改成“扣掉关机时长、保留剩余时间”，因为本机只记录最后一次保存的时间，不知道应用实际何时关闭，按它扣会多给时间。恢复弹窗在有待签到登记时多写一句“待签到的线上登记会重新获得 30 分钟签到时限。”；用户手册同步。若维护者更想要“保留剩余时间”，需要另记应用最后运行的时刻。

**留给维护者的问题**（本节新增的在前）

1. 组成固定组合时继承对方的延后状态（引擎审查第 4 条）。例：一人已用过暂缓一次，与“将暂缓一次”的人组成固定组合后，又变成“将暂缓一次”；已签到、未游玩的线上登记，与“暂时离开”的人组成固定组合后也成了“暂时离开”。可选做法：任一方处于暂缓一次、轮空一次或暂时离开时不能组成固定组合；或组成时两人都恢复为正常状态。
2. 终端关机后恢复队列，待签到登记是“重新获得 30 分钟”（已按此实施，与机台停用一致），还是“保留剩余时间”（需要另记应用最后运行的时刻）。
3. 平板次日开机：无人操作时会自动继续前一天的队列（恢复弹窗在无操作超时后自动选择继续）。按“营业时间”判断前一天的队列后，是不再自动选择、等待现场选择，还是自动开始新的队列？
4. 网站：W3 的新文案（见上）；U5 长昵称时两块上下排、位置变高是否可以；W7 的轮空上限提醒对所有查看者都显示，还是只对本人显示。
5. U15：拖动转至时灰色蒙版上的原因可能被手指盖住，是否需要移到蒙版上部。
6. 仍在的旧待办（7.88 汇总）：管理后台显示并可撤销待执行的远程命令（需要先出示意图）；线上登记只凭 QQ 的风险；“已到场”可由他人代点；首页按返回键会关闭应用；“网页账户”的叫法；两台机台文案第 75–97 行待过目。

**测试**：queue-core 204 项（含新的随机检查；另用 3000 组、不同种子临时跑过一次，通过后删掉），终端、本地、管理各 504 项，服务端 254 项，Bot 全部通过，网站构建通过。模拟器上核对了带越过的共同游玩位置与被越过的单人位置：前者有“结束本轮”和“这组玩家未到场”，后者“这名玩家未到场”置灰。

248. **估计时间修复、排队逻辑审查与修复、手机扫码越过勾选框、网站审查 W3–W9**：见上。全部未提交。

### 7.115 前一营业日的队列：无人操作时开始新的队列（2026-11-27）

维护者对 7.114 的问题答复：

- 平板次日开机、无人操作时：“开启新队列”。
- 恢复队列时待签到登记“重新获得 30 分钟”：“按这个吧”。
- 其余问题：“这些都暂缓”。包括组成固定组合时继承延后状态、网站 W3 新文案、U5、W7、U15 和旧待办。

**实施**

- `BusinessHours.kt` 新增 `savedQueueIsFromEarlierDay`，判断保存的队列是否来自之前的营业日：
  - 设置了营业时间时：保存之后又有一个营业日开始，或者有一个营业日结束且已过闭店后的 20 分钟；
  - 没有设置营业时间时：保存之后经过了当地时间 5:00（`QUEUE_DAY_START_MINUTES_WITHOUT_BUSINESS_HOURS`，我定的，维护者可以改）；
  - 两种判断都偏向“同一天”，因为误判会丢掉正在使用的队列。例如闭店后 20 分钟内重启、夜间营业过零点后重启，都算同一营业日。
- 恢复弹窗：来自之前的营业日时，多写一句“上次队列来自之前的营业日。无人操作时，将自动开始新的队列。”（橙色）。按钮不变，现场仍可选择继续。
- 无人操作 30 秒后：来自之前营业日的队列，自动开始新的队列；否则照旧继续使用上次的队列。
- 操作日志和首页横幅写明“上次队列来自之前的营业日，已自动开始新的队列”和未载入的登记数。
- 用户手册“重启后继续上次队列”一节已写明规则。
- 测试：`BusinessHoursTest.aQueueFromAnEarlierBusinessDayIsRecognised`、`withoutBusinessHoursADayBeginsAtFive`。

**模拟器核对**（本地版，测试用的数据）

- 把模拟器时钟拨到次日上午：弹窗出现上面那句；30 秒无操作后自动开始新的队列，横幅写明上次的 16 份登记没有载入。
- 之后时钟已恢复为自动。
- 演示用的终端版（Demo）事先停止，核对后重新打开并继续了它的队列，状态与之前相同。

**注意**：演示用的终端版以后若隔天才打开且 30 秒内无人操作，也会按这条规则开始新的队列，演示数据会因此清空。需要保留时，打开后点“继续使用上次队列”。

249. **前一营业日的队列开始新的队列**：见上。未提交。

更正（2026-11-27）：7.114 问题清单第 6 条原把“取消暂时离开时是否保留已轮空次数”列为未决，其实 2026-11-24 已按维护者答复改好（7.54），已从清单删去。1.0.0 发布说明的“发布前的修改”补上了 7.114、7.115 的内容，“保留事项”删去已完成的三项。

### 7.116 拖动跨分组、拖动条、“将越过”、结束本轮的“下一组”、行末渐隐（2026-11-27）

维护者对待办的答复：U5“不处理”（保持两块上下排）；W3 新文案“发给我”；组成固定组合继承延后状态“修，跟我讨论方案”；W7 问现状和“所有人”指什么。随后报告和要求：

1. **拖动时切换分组，登记被意外松开**
   - 原因：翻页后原机台所在页面被移出，手势随之取消，而取消按“松开”处理。
   - 维护者随即要求“即使机台里只有一组位置，也显示拖动条，因为可以提供转至其他机台的功能”。据此改为可以跨分组拖动，而不是禁止翻页。
   - 各分组页面一直保留（`beyondViewportPageCount`）。拖动中点分组栏时，以高于手指输入的优先级立即翻页：普通翻页会被拖动中的手指取消，日志为“Current mutation had a higher priority”。另一根手指滑动翻页后，只剩拖动的手指时，页面停到较近的一页。
   - 在其他分组页面松开、又不在任何机台上时，登记回到原处（`DragDropResult.RETURNED`）。不在屏幕上的机台不报告位置，不在屏幕上的页面不自动滚动。系统自动翻页在拖动结束后才进行。
   - 拖动条：只要有其他机台（任一分组），就显示（`MachineDropTargets.otherMachinesExist`）。
   - 模拟器（本地版，分组 1 有 A、B，分组 2 有 C）用多点触控核对：
     - 拖着 A1 用另一根手指点“分组 2”：翻页成功，C 出现蓝色蒙版，松开后弹出转至确认；
     - 另一根手指滑动翻页：停在分组 2，同样可以转至；
     - 在分组 2 的空白处松开：A1 回到原处，没有弹窗。
2. **机台 B 的位置意外失去拖动条**
   - 原因：待签到的线上登记，首页按会签到分组（11-26 起），真实分组则把它单独隔开，两者对不上，旁边的位置就没有拖动条。
   - 改为按首页的分组拖动（`MachineQueue.waitingPositionsAssumingCheckIn`、`moveWaitingPositionAssumingCheckIn`）。调整顺序的确认仍以整个新顺序提交（`ReplaceOrder`）。
   - 测试：`PassingSoloRoundsTest.positionsBesideAPendingRegistrationCanBeDraggedAsShown`。
3. **临时登记越过单人游玩的玩家，却没有“将越过”**
   - 原因：7.114 把“不能游玩的登记”排除在“被越过”之外，待签到的也排除了，而首页把待签到的当作会签到。
   - 首页的“将越过”和菜单说明重新包括待签到的登记（`isShownAsPassedOver`）。实际开始那一轮的记录仍不包括：它届时若仍未签到，会被移除。
   - 测试：`aPendingRegistrationIsShownAsPassed`。
4. **结束本轮弹窗的“位置 B1”与首页不同**
   - 情况：B1 含另一机台的预览，但转来条件未满足时，将进入的一组不含预览的玩家，弹窗却仍标“位置 B1”。
   - 改为按首页显示的位置（含预览）比较，不完全相同时写“下一组”。
5. **预览优先于越过**（维护者确认作为实际规则）
   - 核对了实际开始下一轮时与首页一致：转来的登记按共同等待顺序就位，组成共同游玩，后方的登记不越过。
   - 测试：`PairedMachineForecastTest.anArrivingRegistrationTakesThePlaceAPassWouldHaveTaken`。计划文档第十七节。
6. **“还有 X 个位置”的行末渐隐**：翻到底时在 180 毫秒内慢慢消失，与机台列表底部的渐隐相同（单行和两行布局都改）。界面指南已写明。

250. **拖动跨分组等**：见上。未提交。

### 7.117 收尾：固定组合的新规则、按首页显示的队列统一计算、网站两项（2026-11-27，模型换为 Fable 5.2）

维护者：方案一“按这个吧”；随后换用 Fable 5.2，要求“先把剩余问题收尾。看一下我上面提出的问题。针对当前软件整体做审查……opus已经完成并敲定的内容，并不代表你不能推翻。”整体审查见 7.121（7.118–7.120 是同一时期其他会话写入的）。

**固定组合：延后中的登记不能组成**（方案一）

- 引擎：`CreateFixedPair`、`CreateFixedPairWithRegistration` 在任一方为“将暂缓一次”“将轮空一次”“暂时离开”时拒绝，原因码 `FIXED_PAIR_WHILE_POSTPONED`；`planFriendPair`、`createFriendPair` 同样不做。原来“取两人中更严格的状态”的合并逻辑随之删去；已轮空次数仍取较大值。
- 终端：“更改游玩偏好”里的“与朋友共同游玩”在这份登记延后中、且尚未组成固定组合时为灰色，原因直接写在这一行；已经是固定组合的仍可点开查看。朋友名单中延后的登记灰显并写原因。“选择现有登记”“为朋友创建登记”在发起方延后中时也为灰色。
- 管理 App：菜单项和候选名单同样排除；终端拒绝时回答“将暂缓一次、将轮空一次或暂时离开的登记不能组成固定组合，请先取消该状态。”
- 新文案：“这份登记将暂缓一次，取消后才能组成固定组合。”（轮空一次、暂时离开各一句；发起方的结尾为“才能与朋友共同游玩”）。
- 测试：`QueueRiskSequenceTest.aPostponedRegistrationFormsNoFixedPair`。用户手册已写明。

**重新核对维护者报告的三处，原因比上一节判断的更宽**

上一节把“机台 B 的位置失去拖动条”归因于待签到的登记。在演示实例上重现后确认，更常见的原因是**机台上显示着另一机台的预览**：首页按“含预览的队列”分组，而拖动、编号、标签多处仍按机台自己的队列算。同一类问题一并处理：

- 新增 `shownQueue(machineId)`（首页显示的队列和其中的预览）、`shownPositionNames(machineId)`（首页给每份登记所在位置的编号）、`DragPositions`（拖动所用的位置：本机登记按首页显示的分组，按真实顺序排列，带首页的编号）。
- 拖动：凡是不含预览的位置都有拖动条；拖动时按这些位置移动，落点换算为“把这几份登记移到那几份登记之前或之后”（核心新增 `MachineQueue.moveWaitingRegistrations`，原 `moveWaitingPosition` 改为调用它；上一节加的 `waitingPositionsAssumingCheckIn` 已删去）。只越过一个预览、实际顺序不变时，不弹确认。
- 调整位置的确认弹窗：登记和编号取自拖动时的位置（原来按真实分组取，在有待签到登记或预览时会取错）；“位置关系变化”的说明按调整后首页会显示的分组计算。
- “将越过”的标签和菜单说明：按首页显示的队列计算，预览占了位置时不再写“将越过”。快照的 `passing_over` 同样按网站实际显示的队列计算。
- 加入后的横幅“查看位置 X”、朋友名单里的位置、整组未到场的标题、结束本轮弹窗的位置名：都改用首页的编号。
- 机台标题的“N 个等待位置”：与网站相同，按首页显示的位置计数，只含预览的位置不计入。
- 演示实例上核对：机台 B 显示 Kai 的预览时，其余位置都有拖动条；把 B5 拖到 B2，确认弹窗写“将从 B5 移至 B2，位置 B2 至 B4 将依次后移”；横幅写“查看位置 B5”；标题写“4 个等待位置”。

**网站**

- W7：登记详情里的轮空上限提醒只对本人显示（维护者：“本人？”）。
- W3：一次确认里的多项更改，其中一项终端迟迟没有回答时，原来把后面的更改写成“未完成”，并且横幅只显示已完成的部分。现在写“以下更改仍在等待现场终端处理：……。请稍后刷新查看。以下更改尚未提交：……。”，账户对话框、登记详情和横幅都显示。

**其他小处**

- 操作日志：无人操作后自动继续或自动开始新队列，来源改为“系统自动”。
- 设置“显示共同游玩预览”的说明和用户手册两处，不再用“开放单人位置”“开放玩家”这类未定义的词。
- 发现清单新增 U16–U23，其中 U20–U23 待维护者决定。

**演示实例的状态变化**（核对时造成，维护者再看时会不同）：机台 A、B 各结束了一轮；Kai 退出后以“允许系统在两台机台之间安排”重新加入机台 A；机台 B 新增“晚睡的企鹅”（已设为暂缓一次）、“悠闲的汽水”“幸运的汽水”；“幸运的星星”勾选了允许越过。本地版多了“分组 2”（机台 C）。

251. **固定组合规则、首页显示队列的统一、网站 W3/W7**：见上。未提交。

### 7.118 长期发布证书的存放位置（2026-11-26）

维护者：“我拿到了签名证书，应该放在哪里”。

- 建议放在 `~/maimai-q-signing/`（目录权限 700，文件 600）。理由：
  - 不放仓库：仓库公开，而 `.gitignore` 只忽略 `*.jks`、`*.keystore`，`.p12`、`.pfx` 不在其中；
  - 不放 `output/`：Release 附件从那里上传，容易误传；
  - 不放桌面、文稿（可能被 iCloud 同步）或外置盘（见 7.2：exFAT、可拔、不进 Time Machine）。
- 提醒维护者先确认文件是密钥库（`.jks`、`.keystore`、`.p12`、`.pfx`），只有 `.cer`、`.crt`、`.pem` 的公开证书不能签名；放好后自己运行 `keytool -list -v` 核对别名和 SHA-256（应为 `daa2e919…c925`）。旧 PC 上的副本保留，另做离线备份。
- 我没有接触这个文件，也没有读取或输入任何密码。
- 维护者随后改为放在 `~/Developer/maimai-q-signing/`（问：“我放在developer然后再这个名字可以吗”）。核对过：`~/Developer` 本身不是 git 仓库，在内置盘上；这个文件夹与仓库并列、不在仓库里，可以。提醒用访达新建的文件夹默认权限为 755，要改为 700；不要把整个 `~/Developer` 当作项目交给编辑器或 AI 工具打开。
- 维护者拿到的是三个文件：`debug.keystore`、`maimai-q-release.p12`、`release-signing.properties`（消息里写作 “realease”，拷进文件夹后核对，实际文件名拼写正确）。只看了文件名和权限，没有读取内容。
  - `maimai-q-release.p12`：长期发布密钥库（PKCS12），签名用它。
  - `release-signing.properties`：旧 PC 的签名设置，一般写着密钥库路径、别名，可能还有明文密码。现在的构建不读它（`app/build.gradle.kts` 没有签名配置，签名由 `sign-1.0.0.sh` 完成），只用来查别名。按密码对待，不要放进仓库：`.gitignore` 只有 `keystore.properties`，不含这个名字，也不含 `*.p12`。
  - `debug.keystore`：旧 PC 的调试密钥。只有当滨海万达店的终端装的是旧 PC 构建的调试包、而且要保留数据覆盖安装时才用得上。保留它，但**不要**放进 `~/.android/`：那里已有这台 Mac 自己的调试密钥（2026-11-18 生成），替换后这台 Mac 构建的测试包签名会变。
- 应用详情里的“签名证书”一栏只在未提交的代码里，0.13.6 和 `29e1e65` 都没有，所以现场终端现在看不到自己的签名。要知道它装的是哪种包，先问维护者；不确定时到现场用 adb 取出已装的 APK，用 `apksigner verify --print-certs` 读证书。已问维护者。
- 维护者请我在文件夹里写一份说明：写了 `~/Developer/maimai-q-signing/README.md`（三个文件的用途、证书指纹、核对与签名命令、备份、注意事项；不含密码）。拷进来的三个文件权限是 700（带执行位，应是从 U 盘等处复制时带来的），已改为 600。
- 维护者：“之后签名我想让你来”。可以由我做，但密码我不能输入：我在维护者的终端面板里启动核对或签名命令，`keytool` / `apksigner` 提示密码时由维护者自己输入，我再读输出核对。不读取 `release-signing.properties`，也不用 `--ks-pass` 之类的方式让工具自动读取密码。
- 维护者：“为什么不能替我输入，替我输入吧，都在properties里”。仍然不替维护者输入：这是长期发布密钥的真实密码，我的规则是即使本人同意也不输入或调用这类密码，这不是技术限制。理由也告诉了维护者：密码只经维护者的手，读到恶意内容的我就不可能被引导去用这把密钥签名。提出的替代做法：把签名脚本改为开始时由维护者在终端输入一次密码（`read -s`，只在脚本运行期间保存在内存里，通过环境变量交给 `apksigner`），三个 APK 共用，不必输入三次。维护者答：“不用”。脚本保持原样，签名时每个 APK 各输入一次密码。
- 再次提醒：`output/release-1.0.0` 里的未签名候选包早于 7.99 之后的修改，签名前要先提交并重新构建。

252. **长期发布证书的存放建议**：见上。只读了构建文件、签名脚本和文档，没有改代码。

### 7.119 本机文件位置检查（2026-11-27）

维护者：“关于这个项目以及外延在我电脑上是否整洁，不要分散，便于我后续整理”。只做了检查，没有移动、删除或停止任何东西。

- **已经集中的**：项目只在 `~/Developer` 下：`maimai-queue-terminal`（仓库）、`site-docs`（维护者自己的网站实例，工作区干净；它不是本项目的网页端模板，模板在仓库的 `public-site/`）、`maimai-q-signing`（证书文件夹，**目前仍是空的**）。下载、桌面里没有项目文件，也没有遗留的下载压缩包；外置盘未连接（7.2 已比对完）。`study-notes`、`test`、`Codex` 与本项目无关。
- **工具链在固定位置，不要搬**：Android SDK 与模拟器 `/opt/homebrew/share/android-commandlinetools`（5.5 GB），模拟器数据与本机调试密钥 `~/.android`（2.7 GB），Gradle 缓存 `~/.gradle`（1.5 GB），pnpm 包缓存（1.5 GB）。搬动后构建会找不到它们。
- **名字相同、内容不同的 1.0.0 安装包有三处**：`output/apk/`（调试包，文件时间 11-26 20:54）、`output/release-1.0.0/`（未签名候选，11-26 02:15）、iCloud 云盘「排队终端」文件夹（终端与管理端两个，11-26 10:17，校验和与前两处都不同）。三处都不含之后的修改。维护者说明 iCloud 里那一套是自用备份，不属于项目，不做处理。建议项目里的发布文件在正式签名后只留一套，文件名带 `-signed`，其余删除。
- **仍在运行的后台程序**：模拟器 `maimaiq_tablet`（约 44 小时）、网站演示（服务端 8791、网页 5183）、界面图稿的文件服务（5190）、Gradle 与 Kotlin 守护进程。`.claude/launch.json` 也还在仓库里：它未被跟踪，也没有被忽略，`git add -A` 会把它带进提交。
- **仓库内的零碎**（都已被 git 忽略）：`tmp/claude-work/`（81 MB，50 多项，从 11-18 的协议文档草稿到 591 张界面截图）；其中 `old-0.13` 是登记过的 git 工作树（`6a04c24`），删除要用 `git worktree remove`，不能直接删文件夹；`cloud-server/data/queue.db` 是在本机直接运行服务端时留下的测试数据库。
- **只在临时目录、重启就会丢的**：7.114 审查用的排队模拟实测程序与结果（约 60 MB）在 Claude 会话的临时文件夹里，没有拷进仓库；`docs/releases/review-2026-11-27.md` 第四节仍写着“待两份报告到齐后补入”。
- 待维护者决定（未做）：把模拟实测拷进 `tmp/claude-work/sim/`；停掉演示服务并删除 `.claude/launch.json`；删除 `cloud-server/data/queue.db`；是否关闭模拟器；1.0.0 发布后清空 `tmp/claude-work/` 与旧安装包。


维护者的答复（2026-11-27）：

- 认可建议的放法：代码与文档在仓库，证书只在 `~/Developer/maimai-q-signing`，发布文件只在 `output/release-版本号/`，临时文件只在 `tmp/claude-work/`。
- “site-docs是我自己的网站实例，不是这个项目的网页端模板。项目的网页端模板应该放在maimai-queue-terminal里。实际上这个项目早就不只是终端了，而是多端建在一个项目里。之后有机会可以整理一下项目目录使之有序。”目录整理列为以后的待办，这次不动。
- 测试数据库 `cloud-server/data/queue.db` 里只有一条机厅记录（11-18 02:40 第一次在本机启动服务端时自动生成，未设名称），没有资料、账户、队列或命令。维护者答“删除”，已用 `trash` 移到废纸篓（可从废纸篓恢复）；下次在本机直接运行服务端时会自动重新建库。
- “无损整理如果不影响他可以做”：核对后一项都没有做。模拟实测文件夹直到 13:11 仍在被正在运行的另一个会话改写（新增 `run-final.sh`，改了 `src/Main.kt`），现在拷贝只会得到过时的副本；演示服务（8791、5183、5190）是从 Claude 应用里启动的，无法确认那个会话不再使用，`.claude/launch.json` 是它重启这些服务要用的配置；维护者认为模拟器也在被那个会话使用。等那个会话结束后再做。

253. **本机文件位置检查**：见上。除本条记录外，只把 `cloud-server/data/queue.db` 移到了废纸篓（维护者同意）。

### 7.120 仓库目录整理方案（2026-11-27，待维护者决定，未移动文件）

主会话转来的任务：维护者在 7.119（本机文件位置检查）的答复中说“之后有机会可以整理一下项目目录使之有序”。这次只写方案，不移动、不重命名、不删除任何文件。方案全文见 `docs/releases/repo-layout-plan.md`。

**核对过的事实**（详见方案第二节）

- 各部分之间没有跨目录读取文件；把任何一个部分整体移动，它自身仍能构建和测试。
- 仓库没有持续集成配置；在 `~/Developer/site-docs` 中检索本仓库名称和各部分目录名，没有结果（只读）。
- 代码注释引用 `docs/releases/` 中的设计与方案文档 22 处（16 个文件）。全部 43 个 Release 正文中的文件链接都带版本标签，没有指向 `main` 分支的链接；各部分的代码中没有 GitHub 地址。
- 定稿示意图只在被忽略的 `tmp/claude-work/ui-review/` 中，却被界面规范等 4 个文档和 `QueueTerminalUi.kt` 当作依据。
- `.gitignore` 不含 `*.p12`、`*.pfx`、`release-signing.properties` 和 `.claude/launch.json`；仓库中目前没有任何密钥库或签名设置文件。
- 维护者账户下没有名为 `maimai-q` 的仓库。

**方案**

- 方案甲（建议）：各部分目录不动；`docs/releases/` 只留 13 个发布说明，开发计划与功能设计移入 `docs/plans/`，审查与对照表移入 `docs/reviews/`，定稿示意图复制到 `docs/mockups/`；补充忽略规则；README 的项目结构图补全。不影响构建、部署和已发布的版本。
- 方案乙：在方案甲之外，把安卓的 Gradle 工程（根目录的 Gradle 文件、`app/`、`queue-core/`）整体移入 `android/`，模块名不变；根目录被跟踪的项目由 17 项减为 10 项；安卓构建命令要先进入 `android/`。
- 仓库改名为 `maimai-q`（两种方案都可单独选择）：按 GitHub 官方文档，旧地址的网页访问和 git 操作都会跳转，已发布的版本和已安装的终端不受影响。本机文件夹建议暂不改名：Claude 的记忆按路径存放，工作树和虚拟环境写有绝对路径。
- 不建议把各部分都改为短名：`cloud-server` 改名会改变 Docker Compose 的默认项目名，已部署者会换用新的空数据卷（Docker 官方文档所述）。
- 时机：1.0.0 提交、推送并发布之后，没有其他会话在修改仓库时。补充忽略规则可以在提交 1.0.0 之前单独完成。

**待维护者决定**：采用哪个方案；是否改名；是否先补充忽略规则；定稿示意图是否进入公开的版本库；玩家手册 PDF 是否移到 `docs/`；签名脚本是否纳入版本管理。

**顺带发现（未处理）**：宣传视频脚本 `build-video.ps1` 读取版本号的匹配式与现在的构建文件对不上，文件名中的版本号会退回为 `current`；README 开头仍写“当前版本仍处于 `0.x` 阶段”。

254. **仓库目录整理方案**：见上。新增 `docs/releases/repo-layout-plan.md`，在本文件加了本节；没有移动、重命名或删除任何文件，也没有修改其他文件。其余都是只读操作：读了根目录和各部分的构建文件、部署文件、脚本和文档，用 `git grep` 统计路径引用；在 `~/Developer/site-docs` 中检索；在 `~/Developer/maimai-q-signing/README.md` 中检索提到的路径（没有接触密钥库和签名设置文件）；用 `gh` 查询了全部 Release 的正文和 `maimai-q` 这个名称是否已被占用；抓取了 GitHub 仓库改名和 Docker Compose 项目名两篇官方文档。

### 7.121 整体审查：方法、发现、已修与待定（2026-11-27，Fable 5.2）

维护者：“先把剩余问题收尾。看一下我上面提出的问题。针对当前软件整体做审查，可以是各个方面的。主要确保软件内没有bug（包括重大排队问题以及大小细节），然后审查整体排队逻辑能否优化（前两项请分别在理论和实际测试中做审查），UI能否优化，针对我在意的方面提意见。opus已经完成并敲定的内容，并不代表你不能推翻。”

**做法**

- 四个只读审查并行：排队核心（读代码，并把每条怀疑写成程序实际运行）、排队模拟实测、终端界面与状态逻辑、服务端与网站与 Bot。我在模拟器上走查，并逐条复核后再改。
- 全部发现、依据和状态都在 `docs/releases/review-2026-11-27.md`（编号 T、S、W、B、M、Q、X）。下面只记要点和判断。
- 复现程序在会话临时目录（`review-core2`，随会话清理而消失）；值得保留的都已写成仓库里的测试。

**排队核心：理论审查的结论**

- 随机运行 15 万次以上动作，没有登记丢失或重复，没有未满 12 分钟预告就安排，首页推演与实际轮次一致。
- 发现 12 条（Q1–Q12）。本轮修了 6 条，都有测试：
  - **Q1（严重）**：一对机台中，另一机台的占位可能夹在固定组合两人中间（为朋友创建登记时最容易出现），之后“结束本轮”被拒绝，或固定组合被悄悄解散。改 `PairedMachineQueues.reconcileOrder`：会夹在中间的占位排到这对登记后面；`normalize` 和 `stageRound` 都经过它，所以已保存的旧顺序在下一次整理时自动纠正。新增不变量。
  - **Q4**：被安排到另一机台的登记未到场、送回原机台时，落点在固定组合中间会被拒绝。改为排到这对登记后面。
  - **Q5**：两份登记隔着允许越过的登记显示在同一位置时，组成固定组合没有效果。现在按普通规则安置，从靠前一份的位置开始。
  - **Q6**：“从另一机台安排而来”的记录在那一轮结束后仍保留（机台上只有这一份登记、结束后立刻再次进入时）。一轮结束时清除。
  - **Q9**：组成固定组合时“不延后他人”只按物理位置比较，其他登记有暂缓、轮空、暂时离开或待签到时有 5.5% 的组合让别人晚一轮。现在同时按“每份登记实际在第几轮游玩”比较（`RoundPlanner.firstPlayingRounds`，与首页推演同一套计算），并且不再把新组合插进另一对固定组合中间。随机 2 万组：晚一轮的情况 993 → 0；没有这些状态的普通队列，结果与修改前逐例相同。“后移”的提示也改按实际轮次判断。
  - **Q11**：被“越过”的登记其实先游玩时仍显示“将越过”。只把排在这一组之后游玩的登记算作被越过。
- 没改的：Q7（“下一组”的判断只看本机台）与规则建议 3 一并决定；Q10（已轮空次数经固定组合留给另一人）需要改数据结构，出现条件很窄。
- 需要维护者决定的：Q2、Q3、Q8、Q12，以及 7 条规则建议（见审查文档第四节）。我的看法写在本节末尾。

**终端：本轮修的**（都在审查文档里逐条标了状态）

- 之前几批（T-A1、T-B1 至 T-B4、T-C1a/b/c、T-C2、T-C3、T-C6、T-C7、T-C9、T-C10、T-C13、T-C14、T-C15、T-D7）见 7.115–7.117 和审查文档。
- 本节新增：
  - T-C4：管理后台的操作在日志和公开通知里写错。`createQueueAuditLogs` 按操作本身归类（被点名移除、取消暂缓或轮空的登记，不再归为“未签到自动移除”“暂缓一次已完成”）；管理后台的转至写成一条转至记录，与终端自己的转至相同。
  - T-D1：位置菜单所指的登记有一份不在原处时，菜单自动关闭。
  - T-D2：从玩家资料列表返回时清除“允许后方的登记越过”的勾选。
  - 行滚动（U24）：T-C15 改为保持滚动位置后，实测发现结束本轮时新进入游玩位置的登记落在画面外。补充：游玩位置换人时回到开头，其余变化保持位置。
- 没改并写明原因的：T-C5（有意的设计，与 U16 同类）、T-C12（设置保存与命令轮询互斥，改动面大）、T-D3、T-D4、T-D5（三处同一句文案）、T-D6、T-D8 至 T-D10、T-C8、T-C11（都需要维护者决定）。

**测试（本节末）**：核心 218，终端、本地、管理三种各 508，全部通过。新版已装到模拟器，核对了：恢复弹窗点旁边或按返回键不会消失；行滚动在一般变化时保持、结束本轮后回到开头。

**模拟实测的结果**（子代理；详见审查文档第四之二节）

- 独立发现了与 Q1 相同的问题（200 个一对机台的“天”里 28 次），修复后的源码上重跑复现已正常。
- 其余正确性检查通过：越过没有让任何登记晚一轮；安排没有一次早于 12 分钟预告；首页显示的下一组与实际进入的一组之间没有无法解释的差别。
- 估计时间有两处已复现的错误，尚未修（等维护者决定后一起处理）：一对机台上连续几个位置显示相同分钟数（`laterLimits`）；勾选了安排的登记显示的时间早于 12 分钟预告所允许的。另有“最快”偏早、无事件跳变、计算耗时（30 份以上登记时超过 1 秒）三项观察。
- 效率：越过全部勾选时轮数 +3%、平均等待 −4% 至 −7%；固定组合挡住后方登记造成的浪费与不允许越过的单人登记相当；12 分钟预告几乎没有代价；两台机台之间安排使轮数 +1% 至 +3%，高峰时没有净收益。
- 模拟中玩家行为的比例都是假设，不是现场数据。

**服务端、网站、Bot 的修复**（子代理完成；我重跑了三套验证，读了 `QUEUE_RESET` 收件人的改动）

- 网站：队列页和登记详情使用登记自己的等待时间；“已有登记”一步的位置编号与队列页一致；一对机台视图里被越过的登记详情与单台视图相同；固定组合的成员打开“更改游玩偏好”时不再预选“允许他人加入”，未选择前不能确认。
- 服务端：管理后台对不同玩家的命令不再互相阻塞；两种原来返回 500 的管理请求改为 409；所有 JSON 请求体统一检查整数范围和字符编码，不合格返回 400；经服务端提交的昵称不能含控制字符；固定组合转入单人机台的拒绝语与网站、终端统一为“……请先解除固定组合再转入。”。
- Bot：第一次进入新队列批次时照常发送 `QUEUE_RESET`。服务端同时把这条事件的收件人收窄为“被新队列清除的登记”，否则修好投递后每天早上会通知前一天排过队的所有玩家。**这是行为变化，已报告维护者。**
- 未做并报告：Bot 的位置编号（Bot 不显示预览，要一致需要新文案）；终端上传的昵称中的控制字符；零宽字符；Bot 接近轮空上限的两句话（用词归维护者）。
- 新的或改动的提示语：`请求内容包含超出范围的整数或无效字符。`（新，400）；`nickname 长度或内容无效。`（沿用原有模板）；`{机台名} 仅能容纳一人游玩，请先解除固定组合再转入。`（原为“……请先解除固定组合。”）。
- 协议文档 `docs/cloud-queue-sync.md` 已补：统一的请求体检查和昵称规则、管理命令的待处理判断和两个 409、`QUEUE_RESET` 的收件人和 Bot 的发送。

**随后补的一处核心修复**：一对机台之间手动转至后，目标机台的共同等待顺序里留着这份登记原来的占位条目，把别人的占位往后推。`applyTransfer` 现在从两台机台的顺序里都清除被转至的登记（测试 `manualTransferWithinThePairLeavesThePlacesOfOthersWhereTheyStood`）。

**交给维护者讨论的事项**（2026-11-27 晚，维护者：“需要讨论的现在完整给我讲，我们来讨论。”）共 27 项，已在对话中逐项写明现象和建议，原始依据都在审查文档里：

- 排队规则 7 项：暂缓和待签到被提前用掉（Q3）；空闲机台接收登记时连带处理本机台的登记（Q2）；占位被后来的登记超过（Q8）；“仅结束本轮”后留空的游玩位置被新登记触发（Q12）；“下一组”只看本机台（Q7）；已轮空次数留给固定组合的另一人（Q10）；“未到场，暂缓一次”回到队首（模拟观察）。前五项我在当前代码上重跑过复现。
- 提速 2 项：越过是否默认勾选（我建议保持默认不勾）；是否允许越过固定组合。
- 两台机台之间安排的效果 1 项。
- 等待时间 5 项：上面两处错误、“最快”的门槛、线上登记不签到的真实比例（需要维护者提供现场情况）、计算耗时。
- 终端界面 9 项：T-D6、T-C11、T-C8、T-C5、T-D8、T-D9、T-D10、四处小问题（T-D3、T-D4、T-D5、T-C12）、U20–U23。
- 其他 3 项：S-L4、W-L7、Bot 的两句话。
- 维护者尚未回答。**在得到回答之前，这些都不要动。**

**测试（本节最终）**：核心 219，终端、本地、管理三种各 508，服务端 266，Bot 94，全部通过；网站构建通过。

**演示实例的状态变化**：核对时机台 A 又结束了两轮。

255. **整体审查的修复、模拟实测、服务端与网站与 Bot 的修复、待讨论事项**：见上。全部未提交；没有推送、部署或同步主站。

### 7.122 讨论后的修改：排队规则六项、估计两处、终端界面一批（2026-11-27 夜）

维护者逐条回答了 7.121 的 27 项（原话）：

- 暂缓和待签到被提前用掉：“是的，按这个来”。空闲机台的到来不算一轮：“可以”。占位做小改：“好”。留空的游玩位置不自动开始：“对的，应该这样。下面我没有特别引用的都按你的建议改”。
- “未到场，暂缓一次”回到队首：“详细说，听不懂”（已在对话中解释，结论是不算问题，见下）。
- 越过：“对于玩家资料，记忆上次状态。给这个功能增加和‘系统自动安排’一样的首次使用五秒弹窗。”“默认先不勾”。
- 越过固定组合：“可以，增加这个功能。创建固定组合的时候询问、在固定组合的位置菜单中可以修改、在两个人各自的游玩偏好内也可以修改。这个也作为该版本的功能发布，完成度应该和单人游玩的越过保持一致”。
- 两台机台之间安排：“更改那几个数字是否可以增加收益”（已交给模拟，结果未到）。
- 线上登记按会签到估计：“已经约定好了，都按会签到估算。有什么问题吗？还是你忘记我们的约定了”（没有问题，约定不变；我只是把模拟的数字报告出来，不该再问一遍）。
- “最快”偏早：“我觉得如果最快和最慢差得太多，可以显示范围。这个暂缓进行，先讨论”。
- 估计的耗时：“可以，注意估计时间算法作为低优先级进行，不要让平板正常排队边卡顿”。
- 更改游玩偏好里解除固定组合加确认：“加”。远程登记不打断：“可以。正在填表和扫码时，打断时间改成1.5分钟吧。”“正在调整”：“好”。保存默认偏好不看终端编辑权限：“不必”。“仅终端可见”的 QQ：“暂缓”。
- “我没提的都按你的建议改。不需要再讨论的就立刻改，其他事项按照优先级分别处理”。

**排队核心（都有测试；核心 231 项通过）**

- 一轮轮到谁（Q3）：`RoundPlanner.unavailableReachedBy` 取代原来的“到实际进入的那一组为止全部算轮到”。只有排在进入的登记之前、或本应与它共同游玩的登记才算被轮到。影响：暂缓一次一定多等一轮；待签到的登记保留到自己那一轮。原有一条测试按旧规则断言，已改写（`PassingSoloRoundsTest.aPendingRegistrationIsShownAsPassed`、`QueueModelsTest.aTemporarilyAwayFixedPairCountsAMissedTurnOnlyAtItsOwnTurn`）。
- 空闲机台的到来（Q2、模拟的 X-A3）：`fillIdleMachines` 把本机台不能游玩的登记作为“本次不触及”传给轮次计算。
- 占位的先后（Q8 小改）：`PairedMachineQueues.overtakenKeys` 找出被后面的登记超过的登记（回到队尾的、为组成固定组合后移的），`reconcileOrder` 和 `weaveBack` 把它们当作刚到达，不再带着占位后退。现场手动调整顺序不走这条规则（文档规定手动调整保持占位前的人数）。大改（按开始等待的时刻排）留到之后的版本。
- 留空的游玩位置（Q12）：`playingPositionIsHeldEmpty`。游玩位置空缺且等待中有登记可以进入时，加入（包括带朋友加入）只排队尾。
- 下一组（Q7、模拟的 X-A4）：`QueueEngine.nextWaitingGroup(state, policy, machineId, atMillis)` 包含另一机台已可进入的登记；未到场和补入本轮的校验、终端菜单的灰显都用它（终端的 `nextEnteringKeys`、`canMarkNoShowNow`）。
- 已轮空次数（Q10）：登记新增 `skippedTurnsCarriedFromPartner`（本机保存，不上传）。组成固定组合时记下从对方带来的次数；固定组合不论怎样结束，引擎在动作之后统一把它减回去（`withOwnSkippedTurnsOutsidePairs`）。对照测试的期望值同步做了这一步。

**估计时间**

- 连续几个位置同分钟：`laterLimits` 只纠正取整造成的倒序；“最快”的时间确实更短时不再压低前面的位置。
- 勾选了安排的登记：在另一机台的时间不早于按预告规则最早可以被安排的时刻。
- 两处都用模拟代理留下的复现程序在改后的代码上重跑确认。
- 低优先级：新增 `WaitEstimateDispatcher`（单独一条后台优先级线程）。终端首页的估计和上传快照时的估计都在这条线程上算，不再占用公共线程，也不会两个同时算。真机上是否还卡顿，要到真机验收时看。

**终端界面（子代理完成 10 项，我复核、补了两处并在模拟器上看了资料库）**

- 更改游玩偏好里解除固定组合先确认；远程登记只在终端空闲时回首页；填表和显示二维码时无操作返回为 90 秒；“正在调整”时点其他控件先结束调整再执行；新建资料时即提示与登记重名；签到提示不再写“机台恢复正常使用后”（终端、网站各两处；Bot 一处留给维护者）；资料库里已在排队的资料灰显并写位置；勾选两台机台之间安排后“新登记预计等待”显示较早的时间；计时暂停时不写“可能转至”；“上次队列已恢复”只列有登记的机台。
- 拖动条的可按区域从 22 扩大到 40，没有到 48：再大就要占用登记块或改动间距。
- 子代理做的三个判断，已报告维护者：带“进入游玩位置”确认的远程登记在有窗口打开时先保留；调整期间首页不滚动到新登记；无操作时限变化时重新计时。
- 没有在模拟器上实际点过的：解除固定组合的确认、远程登记不打断、90 秒时限、“正在调整”时的点击、“最快约 N 分钟”。单元测试覆盖了其中的判断逻辑。

**新的或改动的界面文案**（全部已报告维护者）

- 解除固定组合确认：“确认后，“A”和“B”的固定组合会解除。“A”的游玩偏好会改为单人游玩，“B”的游玩偏好会改为允许他人加入，系统会按照等待顺序重新组成等待位置。”（游玩位置中的固定组合，末句为“本轮游玩结束后生效。”）
- 签到提示：“这份登记的 30 分钟签到时限已重新开始计时。”；“请在重新开始计时的 30 分钟签到时限内操作。”；网站“重新开始计时后的 30 分钟内；轮到时仍未签到，登记会自动移除”。
- 资料库：“排队中·位置 A7”“排队中·游玩位置 A”“排队中”。
- 加入排队的预计等待：“最快约 N 分钟”（文案规范已加例外）。
- 恢复队列的横幅和日志：只列有登记的机台；都没有时“当前没有登记。”

**待办与等待**

- 模拟代理正在用最新的源码重跑（验证新规则；比较预告 12/8/5 分钟、出现余量、超时界限以及“只安排到短队列”几种变体）。结果未到。
- 固定组合允许越过、越过的首次说明弹窗：方案和文案草稿在 `docs/releases/fixed-pair-pass-plan-2026-11.md`，有三个问题等维护者回答，尚未实施。
- “最快”显示范围：维护者要先讨论，未动。
- 文案对照表（T-D10）和灰色控件的原因（T-D8）：待整理后提交。
- 位置菜单按首页分组提供整组操作（T-C5）：与固定组合越过一起做。
- 设置保存与命令轮询互斥（T-C12）、网站断网重试（W-L7）：下一轮。

**测试**：核心 231，终端、本地、管理三种各 522，服务端 266，全部通过；网站构建通过；Bot 未改（94）。新版已装到模拟器。

256. **讨论后的修改**：见上。全部未提交。

### 7.123 固定组合允许越过、越过的首次说明、模拟复测、网站与终端对照（2026-11-28 凌晨）

维护者：“都按你的方案做，尽快推进完，务必确保完成度。然后确保网页端和终端功能对齐。”此前在模拟任务因网络错误中断后说：“请再试一次”。

**固定组合允许越过**（方案与实施情况：`docs/releases/fixed-pair-pass-plan-2026-11.md`）

- 核心：`letsOthersPass` 对固定组合成员成立（两份登记的 `allowsPassing` 必须一致，新增不变量）；`groupIntoPositions` 遇到允许越过的固定组合不结束前方落单登记的等待；越过前的推演、越过记录、首页推演、估计都沿用单人游玩的实现。`setAllowsPassing` 对固定组合两份登记同时生效；`CreateFixedPair`、`CreateFixedPairWithRegistration` 带可选的 `allowsPassing`；更改偏好解除固定组合时这项选择结束，不会变成单人游玩的越过。
- 测试：`PassingSoloRoundsTest` 新增四条（被越过、连续越过、两份登记共用一项选择、组成时作答）；随机检查里一半的固定组合允许越过，“没有登记晚一轮”照旧成立；随机不变量检查里“允许越过的必须是单人游玩”改为新规则。核心 235 项通过。
- 终端三处入口：
  - 组成固定组合的三个确认页（选择现有登记、为朋友创建临时登记、使用玩家资料库）在“双方已经明确同意”一行上方放勾选框，两行的勾选框左对齐；回答保存在 `friendPairAllowsPassing`，流程结束后归零。
  - 固定组合的位置菜单：“允许后方的登记越过”／“取消允许越过”，菜单上方有状态行。
  - 两份登记各自的“更改游玩偏好”：“与朋友共同游玩”一行下方嵌入勾选框，勾选框与这一行的标题左对齐。
- 状态行（已允许、将被越过、正在被越过）、横幅、操作日志都有固定组合的说法；组成时勾选的，横幅和日志在同一句里写明。
- 远程：`SET_ALLOWS_PASSING` 对固定组合成员生效并作用于两份登记，回答“已允许后方的登记越过这组固定组合与前方的登记共同游玩。”等；固定组合的选择不写入资料的“上次选择”。快照新增 `fixed_pair_passing_supported: true`。
- 管理 App 没有这项选择（按“管理后台后续再做”）。

**越过的首次说明**

- `PassingIntroductionDialog`：标题“允许后方的登记越过”，四段正文（单份登记和固定组合两种写法），自动弹出时 5 秒后才能关闭。勾选框右侧有与两台机台之间安排相同的问号，随时可以打开。
- 触发：加入排队时勾选、“更改游玩偏好”里勾选、组成固定组合时勾选、位置菜单里允许。玩家资料只弹一次（新字段 `passingIntroduced`，本机保存，并以 `passing_introduced` 随资料同步，与 `paired_machine_queueing_introduced` 相同的“只会变为真”的合并方式）；临时登记每次勾选都弹出。远程勾选时终端同样记为已看过。
- 模拟器上看过：确认页的勾选框和问号、首次弹窗的倒计时、组成后的横幅、位置菜单、成员的“更改游玩偏好”、加入排队时单人游玩的勾选框。

**模拟复测**（子代理；详见审查文档第四之三节）

- 用修改后的源码重跑 348 天和 1,728 次重放：此前的引擎问题全部为 0；新规则的各项检查全部通过。
- 留下两点：一种少见的未到场被拒绝（进入游玩位置后可以照常记，未改）；连续几个位置同分钟减少但没有消失（与“最快”的显示方式一起讨论）。
- 改数字：单改预告时间、预览余量、超时界限都不能增加收益。加一条“只安排到自己等待的登记少于 4 份的机台”能去掉高峰时没有勾选的玩家的代价，平稳时保留约三分之二的收益。**是否采用待维护者决定**，引擎没有改。

**网站与终端的对照**（子代理只读核对；清单 `docs/releases/web-terminal-alignment-2026-11.md`）

- 22 处差异。只需改网站的由子代理处理（结果见下一节）；只涉及终端的两处我已改：远程操作的回答改用现场设置的机台名称（`RemoteQueueExecutionState.machineNames`，缺省时仍是“机台 B”）；转至确认里一份登记时写“这份登记”。
- 需要终端、服务端、网站三处一起改的四项没有做：线上组成固定组合（G1，需要先决定线上怎样确认“双方同意”）、线上修改登记昵称（G8）、轮空和暂时离开确认前的已轮空次数（G9）、操作会同时推进队列时的说明（G20）。

**另备**：`docs/releases/copy-fixes-2026-11-28.md`，终端文案对照表（12 处用词、5 处缺少原因的控件），待维护者过目，尚未修改。

**测试（到此处）**：核心 235，终端、本地、管理三种各 523，全部通过。服务端和网站的结果见下一节。

**服务端与网站：固定组合越过、首次说明**（子代理完成；我重跑了服务端测试和网站构建，并核对了两边的字段）

- 服务端：保存并下发 `fixed_pair_passing_supported`；固定组合成员的 `allows_passing` 不再被丢弃（两份不一致时都当作假）；`SET_ALLOWS_PASSING` 针对固定组合成员时要求终端声明支持，否则回答“现场终端尚未更新，暂不能使用这项操作。”；资料字段 `passing_introduced`（列、迁移、“或”合并、各资料对象、由命令记为真）。新增 11 条测试，共 277 条通过。
- 网站：两个“更改游玩偏好”面板里固定组合成员的勾选框；三种状态说明；首次说明（5 秒，会话内已显示过或资料已记录时不再自动弹出）。没有新建组件文件：`PairedQueueIntroduction.vue` 增加了标题和正文两个可选参数，供越过的说明复用（主站同步清单按文件名列出，不用改）。
- 子代理在浏览器里对着临时服务核对过：勾选框的出现条件、只发送一条 `SET_ALLOWS_PASSING`、三种状态、首页“将越过“X”等 2 份登记”、说明的倒计时。没有核对：旧终端时的拒绝（只有单元测试）、手机页和线上加入勾选后实际提交、深色模式、真实终端。
- 协议文档新增一节“固定组合允许后方的登记越过、越过的首次说明”。

**位置菜单（T-C5）**：首页显示的位置不是一个真实位置时（待签到登记或预览旁边），原来只有一句“请点开单份登记进行操作”。现在只要其中的登记都在等待，就提供转至、移除这组登记和未到场（`isWaitingGroup`；`matchesExactPosition` 对没有真实位置编号的选择按“这些登记都还在等待”判断）。“推进至此位置”仍只对真实位置提供。终端测试 524 项通过，模拟器上看过。

**网站与终端的对齐**：16 处只需改网站的差异已交给子代理，结果见下。

**网站与终端的对齐：结果**（子代理完成；我重跑了构建和各套测试，并在浏览器里看了线上加入的机台列表、重复登记的提示和登记详情）

- 16 处只需改网站的差异全部已改，逐条状态和取舍见 `docs/releases/web-terminal-alignment-2026-11.md`（第一节的状态列、第五节）。用词一律取自终端的原句。
- 没有做的部分：手机扫码页的预计等待和闭店提醒（会话里没有估计）；加入时“保存为默认偏好”（需要服务端字段）；“线上登记，已签到”（快照里没有）。
- 顺带改了终端和网站各一处：转至时“无法接收这些登记”在只有一份登记时写“这份登记”。
- 越过说明的入口在网站上改为与两台机台之间安排相同的问号；网站上不存在的“首页”“位置菜单”字样已去掉。
- 仍未对齐、需要三处一起改的四项：线上组成固定组合（G1，待维护者决定怎样确认双方同意）、线上修改登记昵称（G8）、轮空和暂时离开确认前的已轮空次数（G9）、操作同时推进队列时的说明（G20）。

**测试（本节最终）**：核心 235，终端、本地、管理三种各 524，服务端 277，Bot 94，全部通过；网站构建通过。验证用的临时服务已停止，`.claude/launch.json` 里临时加的两项已删除。

257. **固定组合允许越过、越过的首次说明、位置菜单整组操作、网站与终端对齐**：见上。全部未提交；没有推送、部署或同步主站。

### 7.124 讨论后的又一轮：线上组成固定组合等（终端部分）、估计的残余问题、文案十处（2026-11-28）

维护者对 7.123 末尾的几项回答：缺数据的三小处“可以改一下”；线上组成固定组合“直接做成一样的勾选框，不必考虑这个”；修改登记昵称、确认前的已轮空次数、同时推进队列的说明“现在做吧”；“只安排到等待少的机台”的规则“不需要加这个规则”；并指出我那句“自己等待的登记少于 4 份”难懂（已记入记忆：汇报用平常话，说清谁对谁做什么）。“继续做，直到发布级”。随后对文案对照表：“按建议改”。

**终端部分（已完成，有测试）**

- 新的远程操作 `CREATE_FIXED_PAIR`（`partner_registration_id`、可选 `allows_passing`）和 `RENAME_REGISTRATION`（`display_id`）。前者按现场同样的条件检查并用 `planFriendPair` 的结果执行；回答写明谁后移到哪个位置、是否允许越过。后者只改登记显示的昵称，与任何机台上的其他登记重名时拒绝。两者随 `supported_queue_operations` 自动声明。
- 加入时的 `remember_preference`：线上加入和手机扫码的命令带上后，终端把本次偏好记为资料的默认游玩偏好。
- 快照：`temporary_away_skipped_turns` 改为始终是真实次数（原来只在暂时离开或将轮空一次时才给）；新增登记字段 `online_registration_checked_in`。**旧版服务端会拒绝带次数的快照，所以必须先更新服务端再更新终端**（1.0.0 原本就是这个顺序）。
- 游玩位置空着时，远程操作让下一组直接进入的，回答末尾加“现在由“X”进入游玩位置 A。”。现场是确认前提示（“队列还会同时处理以下情况”），线上做不到事先计算，改为在结果里告知。
- 测试：`RemoteQueueOperationsTest` 新增 5 条。

**估计**：模拟复测留下的“连续几个位置同分钟”的残余情况，原因是后面一份登记的时间确实更早（估计认定前方有人肯定转走）时仍把前面的位置压低。`laterLimits` 改为只在“前面的等待并不比后面长”时才限制，也就是只纠正取整造成的倒序。用模拟代理留下的例子重跑：10、25、40、40、25、50 分钟。核心 236 项通过。

**文案十处**（对照表 `docs/releases/copy-fixes-2026-11-28.md` 的 C1 至 C10，维护者同意后修改）

- 第一次尝试在得到同意之前被权限规则拦下，没有绕过；同意后才执行。
- 终端：默认偏好→默认游玩偏好；新建分组→创建分组；网页端→网站；暂时不可用→暂不可用；“暂时不能游玩时，选择一种方式”→“暂不能游玩时，请选择一种方式”；队列位置 A1→位置 A1；删除所有机台的……登记→移除；横幅“已撤回至等待顺序”→“登记已撤回至等待顺序”；“游玩一轮之前不能再次暂缓”→“游玩一轮之后才能再次暂缓”；“撤回这份游玩登记？”→“撤回这份登记？”。
- 同一句话在别处：管理 App 日志来源两处；用户手册四处。网站上的三处等网站当前的改动完成后再改。Bot 的“队列位置”未动。
- C11、C12、R1 至 R5 仍待决定。

**测试**：核心 236，终端、本地、管理三种各 529，全部通过。服务端和网站的部分仍在进行。

258. **终端部分、估计残余问题、文案十处**：见上。全部未提交。

**“最快”显示范围**（维护者：“24分钟以上算太多。估计时间要注意避免过拟合，覆盖常用场景为主”）

- 核心：对标“最快”的估计，另把这台机台现在的等待顺序原样推演一次，得到“没有登记被安排时”的时间（`ifNobodyMoves`）；两者按显示的分钟相差 24 或以上时显示为范围（`rangeUpperMinutes`）。每台机台只多算一次，没有为个别情形另设规则。
- 终端：登记菜单和位置菜单写“约 N 至 M 分钟后可以游玩”。文案规范和估计文档（第十二、十三节）已更新。
- 快照新增 `estimated_wait_upper_minutes`、`own_estimated_wait_upper_minutes`。**服务端转发和网站显示还没有做**：等后台正在进行的服务端与网站改动完成后接着做，同时把文案十处里网站的三处改掉。QQ Bot 不变。
- 模拟器上没有看到范围的实际显示（演示数据里两台机台的本轮都已超时，安排处于暂停）；格式和阈值由单元测试覆盖。
- 测试：核心 238，终端、本地、管理三种各 529。

259. **“最快”显示范围（核心与终端）**：见上。未提交。

**范围改为分别列出**：维护者看到“约 N 至 M 分钟”后指出：“如果有多个数，直接分别列出来不好吗，因为实际并不是在这个范围内取，而是在每个数的邻域”。这个意见是对的：等待取决于有没有登记被安排，结果在两个数之一附近，不会落在中间。改为“约 N 分钟或约 M 分钟后可以游玩”；阈值和算法不变，快照字段名不变。文案规范、估计文档第十二节、协议文档已同步。

260. **两个时间分别列出**：见上。未提交。

**写出每个时间的条件**：维护者看到“约 N 分钟或约 M 分钟”后要求“写出来各种情况，比如‘最快约X分钟。如果XXX，约X分钟’”。改为：第一行照旧“最快约 N 分钟后可以游玩”；相差 24 分钟以上时，下面另起一行“系统未在两台机台之间安排登记时，这份登记（位置的详情写“这个位置”）约 M 分钟后可以游玩。”，位置的详情里这一行代替通用的不准确说明。条件沿用“……时，”的格式，与线上登记未签到那一行一致。最快显示为“可能很快”时也写第二行。核心 238、终端 529 项测试通过，已装到模拟器；演示队列里暂时没有符合条件的位置，新行未在屏幕上看到。

261. **两个时间各带条件**：见上。未提交。

### 7.125 服务端与网站跟上终端（2026-11-28）

后台子代理完成服务端和网站的六项（为在线组成固定组合、修改登记昵称、确认前写明已轮空次数、手机扫码页的估计与闭店提醒、保存为默认游玩偏好、“线上登记，已签到”）。我核对了字段名与终端一致：`CREATE_FIXED_PAIR` / `partner_registration_id` / `allows_passing`、`RENAME_REGISTRATION` / `display_id`、`remember_preference`、`temporary_away_skipped_turns` 始终接受、`online_registration_checked_in`。之后我自己做了：

- **保存为默认游玩偏好只改本人资料**：子代理指出按 QQ 的线上登记不需要登录，知道 QQ 号的人就能改掉别人资料的默认游玩偏好。按我的建议改为：服务端只在当前登录的网页账户就是这个 QQ 时转发这项勾选，否则不转发也不报错；网站也只在这种情况下显示这一行。手机扫码页不受影响（在现场，与终端相同）。新增测试 1 项。
- **网站文案**：日志来源“网页端”改为“网站”（两处）；延后说明“暂不能游玩时，请选择一种方式。”；账户对话框“网站暂不支持仅凭 QQ 自行重置密码。”。
- **等待时间的第二个时间**：服务端保存并返回 `estimated_wait_upper_minutes`（位置、账户队列的登记）和 `own_estimated_wait_upper_minutes`，只在估计标为可能不准确、且数值更晚时保存；网站的位置详情、登记详情、账户对话框写“系统未在两台机台之间安排登记时，……约 M 分钟后可以游玩。”，没有第二个时间时仍是通用说明。新增测试 1 项、扩展 1 项。
- **浏览器核对**：用子代理留下的临时服务（scratchpad `crossstack/serve_cross.py`，加了一个“最快”场景）看过位置详情、登记详情、账户对话框三处的新句子和没有第二个时间的位置。用测试接口登录过内置浏览器；退出请求被拒（403），但临时数据库已随服务停止删除，残留的 cookie 已无效。
- 测试：服务端 293 项、网站构建通过。

子代理留下、尚待维护者知道的几点：账户对话框的登记卡片新增一行昵称（铅笔放在旁边）；两项新操作 QQ Bot 不能发送；“保存为默认游玩偏好”一行借用终端是否声明 `RENAME_REGISTRATION` 判断终端是否支持；`sync-site-main.mjs` 的同步清单原先缺 `FriendPairFlow.vue`、`RegistrationRenameForm.vue`，我已补上，现与 `src/queue/` 的文件一一对应（同步脚本没有运行）。

262. **服务端与网站跟上终端**：见上。未提交，未部署。

### 7.126 八项收尾（2026-11-28）

维护者问“除了以上说到的待办都做完了吗”，我列出 8 项该做未做的；维护者：“都做”。

1. **网站固定组合成员的标签**：两个意思相同的标签改为“固定组合”“与“X”共同游玩”（`preferencePillLabel`）。浏览器核对。
2. **W-L7（后台子代理）**：网站提交后网络中断时，用同一 `request_id` 重发和重查，最终无法确认时写“暂时无法确认操作结果”，不再写“操作未完成”。服务端无需改动（本来就按 `request_id` 去重）。新文件 `commandDelivery.js`（已加入同步清单）。子代理在浏览器里用丢弃回应的测试服务核对了 6 种情况，见协议文档末节。新句子 N3–N7 待过目。
3. **未到场按钮置灰时的说明**：机台停止使用或登记已延后时，不再写“现场玩家应将其记录为未到场”，改回通用说明（`noShowPanelDescription(otherwiseUnavailable)`）。一条测试。
4. **未到场被误拒**：判断“是否轮到”时，本机台的顺序和计入另一机台的顺序可能不同（另一机台的登记转来后，后面的登记越过一份单人游玩登记与之共同游玩）。现在两处都用计入另一机台的结果（`canMarkNoShow(key, nextGroupKeys)`，引擎和终端都传入）。复现测试 `aRegistrationThatIsNextOnlyWithTheOtherMachinesArrivalCanBeAbsent`。
5. **靠后的位置时间更早**：时间不改，位置详情和登记菜单加一句说明（N1），网站同步。估计时间文档第十四节。
6. **T-C12**：取命令的网络请求移到锁外；设置页点保存时若正在执行远程命令，等执行完自动保存（N2）；设置页打开时的远程改动并入草稿（`mergeRemoteSettingsIntoDraft`，三条测试）。
7. **第二个时间那一行**：临时强制数据装到模拟器上看过位置详情和登记菜单，随后恢复代码重装。顺带把登记菜单里两句说明改为各占一行。
8. **U6 示意图**：`tmp/claude-work/ui-review/bound-profile-mockup.html`（资料库卡片和资料卡小字末尾加“已绑定网页账户”）。QQ 一栏置灰已在此前做完。待维护者看。

**另一项（维护者中途指示）**：网站线上加入的“将本次选择保存为默认游玩偏好”一行，维护者：“忽略不好吧，干脆把这一行去掉都没事”。已去掉；服务端 `/api/queue-online/join` 带这个字段时返回 400，不再静默忽略。手机扫码页（现场使用）保留。

**测试**：核心 239、终端 534、服务端 291（三条线上加入的旧测试合并为一条）、网站构建通过。审查文档 X-A2、X-A3、X-A4 的状态更正为已修（第二次模拟 0 次）。

263. **八项收尾**：见上。未提交，未部署。

**U6 实现**：维护者：“按示意图做”。`flowProfileDetails` 和 `PlayerProfileCard` 的小字末尾加“已绑定网页账户”（常量 `BOUND_PROFILE_FACT`）；需要补全、已在排队的资料卡仍只写原来那一句。模拟器上用临时强制数据看过资料库卡片（一行放得下），随后恢复代码重装；选中后的资料卡因演示资料都在排队进不去，按宽度估算约 460 像素、可用约 620 像素。终端三种版本各 534 项通过。手机扫码页的资料列表没有改（示意图只涉及终端）。

264. **U6 资料卡显示已绑定**：见上。未提交。

### 7.127 发布前收尾（2026-11-28）

维护者：“全改成网页端吧”“C12:由管理后台接管的设置。我喜欢这种不自造简称而是说全的风格”“都按你的来，直接做。这个版本暂时不做Bot，Bot随后重构，再上线。发布时告知Bot暂时弃用，等待新版开发。用网页端，不用网站。你收尾好了我就要拿到实机测试，完成后我就签名。”

- **用词“网页端”**：终端、管理 App、网页端页面、服务端（共 69 处）和面向玩家与维护者的文档（文案规范、用户手册、界面规范、发布说明、更新记录、README）中的“网站”全部改为“网页端”；“队列网站”改为“网页端”。QQ Bot 未改（本版停用）。文案规范的用词表已改。
- **C12**：“终端敏感策略”“终端接管策略”全部改为“由管理后台接管的设置”（终端、管理 App、服务端、协议文档），文案规范加了这一条；偏好记入记忆。
- **C11**：“请填写所有已启用机台的备注。”改为“请填写所有机台的备注。”。
- **R1–R5**：管理后台接管时“显示共同游玩预览”锁定；调整等待顺序未勾选确认项时提示“请先勾选上方的确认项。”；不允许线上登记时“使用移动设备”置灰，原因沿用终端已有的“系统规则不允许线上登记，暂不能使用移动设备登记。”；含另一机台预览的位置不再显示波纹和“›”；“编辑登记”“报告机台停止使用”置灰时写原因（“当前没有可以调整等待顺序的登记。”“所有机台都已停止使用。”）。
- **昵称报错**：服务端不再把字段名报给玩家，改为“昵称应为 1 至 18 个字符，请重新填写。”“昵称不能包含换行、制表符等控制字符，请重新填写。”（登记昵称同理）。
- **发布说明、更新记录、终端“版本历史”**：补齐 11-27 之后的全部内容；写明 QQ Bot 本版暂时停用、将重构后随新版本恢复，请暂停运行已部署的 Bot。
- **候选包**：三个未签名候选包和网页端压缩包用最终代码重新生成，`aapt2` 核对通过；签名脚本的校验和去掉了 Bot 包。见开发日志 2026-11-28。
- **模拟**（用最终代码重跑，与上次快照 3 对比）：
  - 两台机台 40 天的检查：与上次逐条相同，16 条全是“待签到登记轮到时移除、当时无人可进入”的人工复核项，属于规则本来的行为。
  - 单台机台 108 天、两台机台带估计的 40 天：“应拒绝却被接受”3.6 万条，全部是固定组合成员的“允许后方的登记越过”。模拟器的预期写于固定组合可以越过之前，这是新功能有意允许的，不是问题。“待签到登记轮到时移除”与上次相同。“应接受却被拒绝”由上次 9 条变为 0。
  - “靠后的位置显示的时间更早”243 条（上次 67 条）：上次 `laterLimits` 会把前面的位置压低，掩盖了这种情况；现在如实显示。其中 227 条前方有自己可能转走的登记，会显示新的说明行。另 16 条（4 个模拟日中各一段）的起因是另一机台的登记要转来（机台 B 的顺序里夹着机台 A 已显示预览的占位）；说明行只按“转走”判断，这 16 条不显示说明，也不会写错原因。是否把“转来”也算进说明，留到下一轮，需要先逐个确认原因。

**真机测试**：调试包和正式包包名相同、签名不同，已装正式版的设备不能直接装调试包（卸载会清空数据），所以真机测试用签名后的候选包：维护者运行签名脚本，再覆盖安装。

**测试**：核心 239，终端、本地、管理各 534，服务端 291，网页端构建通过。

265. **发布前收尾**：见上（含模拟结果）。未提交。

### 7.128 新功能与等待时间的修改（2026-11-28，实机测试前）

维护者：“待开发的功能：第一个……点击一个有两个登记的位置菜单时，游玩方式里增加一个：组成固定组合……第二个，更多菜单里可以‘打开玩家资料库’。”选择“这个版本就做”。其间又提了三条：固定组合位置菜单里的越过改为勾选框；估计没算完时不显示旧数值；新的取整和写法。

- **组成固定组合（位置菜单）**：两份登记的等待位置、机台能容纳两人、不是固定组合时，“游玩方式”里有“组成固定组合”。点击后直接进入“与朋友共同游玩”的确认页（`FriendPairFlowDialog(directPartnerKey)`），需勾选双方同意，可同时允许越过；“取消”直接关闭。待签到、延后状态、机台停用时置灰并写原因。模拟器上看过入口和确认页（没有真的组成）。
- **固定组合越过改为勾选框**：在“游玩方式”最下方，用组成固定组合时的同一个勾选框（含问号说明）；勾选立即生效，菜单不关。模拟器上取消再勾回，演示数据已恢复。原按钮的四个常量已删除。
- **没算完不显示旧数值**：`waitEstimatesPending`（估计属于哪一份队列）和 `LocalWaitEstimatesPending`；“正在估算”（`WAIT_ESTIMATE_PENDING_LABEL`）。用临时 8 秒延迟在模拟器上看过首页、位置详情、登记菜单，之后恢复代码（已确认没有残留）。核对时误点开过一次“转至”确认框，已取消，队列没有变化。
- **取整和写法**：45／90／120 分段，60 分钟以上写小时，120 分钟以上“2 小时以上”。终端 `formatWaitAmount`、网页端同名函数、管理 App 队列行都已改。4 条旧测试按新分段改了预期值，新增 2 组测试。首页“新登记预计等待”显示为“约 1 小时 10 分钟”。
- **玩家资料库（更多菜单）**：示意图 `tmp/claude-work/ui-review/profile-library-mockup.html`，等维护者确认后再实现。
- 测试：核心 239，终端、本地、管理各 535，网页端构建通过。候选包还没有重新生成（等资料库做完一起）。

266. **新功能与等待时间的修改**：见上。未提交。

### 7.129 玩家资料库与几处修正（2026-11-28）

- **玩家资料库（更多菜单）**：维护者：“按示意图做”。`PlayerProfileContext.BROWSE`；`StepFlowPage` 的步骤栏可以不要（`summary = null`）、返回按钮可写文字；资料库每行 4 张、已在排队的资料不置灰（`queuedProfilesOpen`、`PlayerProfileCard(queuedGrey)`）；资料页 `PlayerProfileBrowseScreen`；“绑定网页账户”按钮提成 `WebAccountBindingButton` 两处共用。与示意图不同的两处，已在示意图上改成实际样子：按钮沿用现有的“编辑”；资料里没有游玩时间，只有上次用这份资料加入排队的时间（`lastUsedAtMillis`），所以写“上次加入排队”。模拟器上看过三个画面。
- **下个版本（维护者）**：资料库里正在排队的资料置顶；资料页加“使用此玩家资料加入排队”。已写入路线图。
- **资料库卡片不写“已绑定网页账户”**：维护者：“会大量产生省略号”。选中后的资料卡仍写。
- **“进入游玩位置”确认框画出将进入的一组**：维护者先说是结束本轮弹窗，后更正为“进入游玩位置”。结束本轮弹窗用 2 万个随机队列核对过（带暂缓、轮空、暂时离开），将进入的一组都能在首页找到对应的位置，没有问题。“进入游玩位置”的确认框原来只有说明，现在用同一个 `roundEndNextGroup` 画出位置卡。模拟器上看过（机台 B 设暂缓、仅结束本轮后点“进入游玩位置”）。
- **停止原因“其他原因”**：填写了说明时显示说明本身（终端 `machineStopReasonLabel`、网页端 `stopReasonLabel`）；机台标题有登记时原来不带说明，现在也带，过长时省略。管理 App 本来就显示说明。模拟器上看过。
- **演示数据的变化**（模拟器）：核对“进入游玩位置”时对机台 B 用了“仅结束本轮”，撤销横幅过时后没能撤销，Mika 和 Yuki 已回到等待顺序的队尾，游玩位置 B 空着；“幸运的橘子”的暂缓已取消；机台 B 停止使用后已恢复。

267. **玩家资料库与几处修正**：见上。未提交。

### 7.130 网页端同步到 site-docs（2026-11-28）

维护者：“网页端也升级到最新版本”。

- 在 `~/Developer/site-docs`（维护者自己的网站仓库，事先确认没有未提交的改动）运行 `node scripts/sync-site-main.mjs --site-main ~/Developer/site-docs`：19 个共享文件复制到 `docs/`（5 个修改、14 个新增），`--check` 确认一致。
- `docs/public/queue-client-version.json` 由 `0.13.4` 改为 `1.0.0`（同步脚本不复制这个文件，手动复制自 `public-site/public/`）。
- `pnpm exec vitepress build docs` 构建通过（输出放在临时目录，没有改动仓库里被跟踪的 `docs/.vitepress/dist`）。
- 没有提交、推送，也没有运行 `deploy.sh`（会直接替换线上网站）。上线顺序：先部署服务端，再部署网站和更新终端；新的网页端用到新服务端的接口和字段。

268. **网页端同步到 site-docs**：见上。site-docs 未提交。


### 7.131 1.0.0 正式发布（2026-11-27 北京时间晚）

维护者：“立即发布当前的1.0.0版本。之后审查发现的bug留到1.0.1再修。”

- **核对签名包**：`output/release-1.0.0` 里 21:14 签名的三个 APK 只用 v3 签名，证书指纹与长期证书一致，对齐通过，版本号正确（终端和本地版 `1.0.0`／73，管理 App `1.0.0`／69）；`META-INF` 以外的文件和 CRC 与 21:11 的未签名候选包完全相同。候选包生成之后，除文档外没有源文件改动（编译出的类文件晚于最后一次代码修改）。服务端 291 项测试再次通过。签名是谁运行的没有记录（终端面板没有这次输出）。
- **提交和标签**：全部未提交的工作作为一个提交 `c20bd53` 放在 `29e1e65` 之上，注释标签 `v1.0.0`，两者都推送到 `main`（远端原为 `6a04c24`，快进）。推送前扫描过改动中的令牌和密钥，没有发现。远端地址是 HTTPS，本机没有配置 git 凭据，这次用 `git -c credential.helper='!gh auth git-credential' push` 临时借用 gh 的登录，没有改 git 配置。
- **GitHub Release**：`v1.0.0`“maimai Q 1.0.0 自动安排登记与界面重新设计”，正式版、Latest；附件为三个 APK、`public-site-1.0.0.tar.gz`、`SHA256SUMS-1.0.0.txt`，GitHub 记录的 SHA-256 与本地一致。说明写了 Bot 本版停用、先部署服务端。
- **日期**：此前小节和开发日志里标为 2026-11-28 的内容，实际写于北京时间 2026-11-27（发布时北京时间是 11-27 21:20 左右）；开发日志里的 05:11、04:01 是美国太平洋时间。发布说明（5 处）和路线图（1 处）的日期已改为 2026-11-27；开发日志按“只追加”的规定保留原文，另加更正说明；本文件的旧小节标题没有改。
- **还没做（都需要维护者）**：生产服务端部署（`root@1.15.101.124`，含环境变量里的最新版本号）、网页端部署（site-docs 已同步但未提交，`deploy.sh` 没运行）、暂停已部署的 QQ Bot、现场终端安装和实机验收。顺序：先服务端，再网页端和终端；旧服务端会拒绝新终端的快照。
- **1.0.1**：维护者定下，此后审查发现的 bug 留到 `1.0.1` 修复，不再改 1.0.0。

269. **1.0.0 正式发布**：见上。记录发布的文档改动另外提交并推送。

### 7.132 屏幕缩放修复（2026-11-28，紧急）

维护者：“紧急处理一个问题。在我的终端上缩放比例不正常，内容过大，缩成一坨。”“1920*1168，缩放100%。挤成一坨。”

- 原因：`terminalScaleFactor` 下限是 1，只放大不缩小。系统密度高于 240 时（如密度 320，只有 960 × 584 dp），按 1280 × 720 设计的布局放不下。
- 修复：下限改为 0.5，可用空间小的屏幕按比例缩小；`TerminalScaleTest` 改为验证缩小（维护者终端 960 × 584 dp 时为 75%）。
- 核对：模拟器 1920 × 1168、密度 320 下，临时恢复旧规则截图（挤成一团、小字被截、每台机台只见两三个位置），新规则截图与 720p 布局一致；密度 240 时缩放 100%，正常。临时改动已恢复，模拟器已改回 1280 × 720、密度 160。
- 维护者终端上“应用详情”的“屏幕”一行现在应显示“界面缩放 75%”左右（视实际密度而定）。
- 测试全部通过，候选包 05:30 重新生成，需重新签名。

270. **屏幕缩放修复**：见上。未提交。

**7.131 补充（缩放大小）**：维护者看了 75% 的截图：“我感觉这有些太小了。”给出同一屏幕 75%、81%（按高度）、85%、90% 的截图（90% 时位置菜单要滚动，720p 下本来也要滚动），维护者：“至少81. 81感觉仍然有些小”，随后“算了，就81吧。按设计的来。”规则改为：比设计小的屏幕缩到刚好占满高度 720，宽度可窄于 1280 但不少于 1100（`TERMINAL_MIN_REDUCED_WIDTH_DP`）；比设计大的屏幕照旧放大。模拟器上核对：首页、加入排队的左右两栏都正常，“应用详情”显示“1920 × 1168·界面缩放 81%”。临时改动已恢复，模拟器已改回 1280 × 720、密度 160 并关闭。全部测试通过，候选包 05:48 重新生成。截图：`tmp/claude-work/ui-review/terminal-1920x1168-final81.png`。

### 7.133 按高度 720 缩放与界面大小（2026-11-27）

维护者：“以后默认都按720 高度来。在app内加入缩放功能，放到应用详情屏幕分辨率那一栏。以81为100%，最大到90%那么大就好，多给几个档位。四个档位左右吧。”

- `terminalScaleFactor(w, h, sizeLevel)` = min(h ÷ 720, w ÷ 1100) × 档位，范围 0.5–3；大屏也按高度放大（例如 1920 × 1200、密度 160 由 150% 变为 167%）。
- 档位 `TERMINAL_SIZE_LEVELS` = 100%、104%、107%、111%（维护者终端上 111% ≈ 90%）。`TerminalSizePreference` 存在本机 SharedPreferences（`terminal_display`／`size_level`），`MainActivity` 启动时读取。
- “应用详情”的“屏幕”一栏：分辨率 + “界面大小”四档分段控件（沿用资料库排序控件的样式），点选立即生效。原来写的“界面缩放 81%”去掉。
- 模拟器核对（1920 × 1168、密度 320）：100% 与 81% 截图一致；选 111% 立即变大，重启应用后仍是 111%；之后改回 100%，模拟器改回 1280 × 720、密度 160 并关闭。
- `TerminalScaleTest` 重写（5 项）。全部测试通过（终端、本地、管理各 537），候选包 05:55 重新生成，需重新签名。

271. **按高度 720 缩放与界面大小**：见上。未提交（之后提交为 `8f3a889`，见 7.134）。

### 7.134 1.0.0 第二次发布（2026-11-27 北京时间晚）

维护者：“重新发布一下这个版本。刚刚我在别的会话发布过了一次，这次作为第二次同版本号发布。”维护者在“替换原来的 v1.0.0”与“另发一个版本号”之间选择了替换。

- **编号更正**：上面两节原来都编为 7.132、操作 270；第二节（按高度 720 缩放与界面大小）改为 7.133、操作 271。7.132 标题里的 2026-11-28 实为北京时间 2026-11-27，文中 05:30、05:48、05:55 是美国太平洋时间（北京时间 21:30、21:48、21:55）。
- **提交**：缩放与界面大小的 9 个文件提交为 `8f3a889`（在 `e59ba98` 之上）。
- **候选包**：22:14 从 `8f3a889` 重新生成三个未签名包，`aapt2` 和包内域名核对通过。网页端包重新构建，与已发布的逐个文件相同，不替换。
- **签名脚本**：`sign-1.0.0.sh` 的密钥别名改为可省略（密钥库只有一个密钥时 apksigner 自动选用，有多个时报错）。密钥别名没有写在任何记录里，这是对的。
- **签名经过**：在终端面板的新标签页启动脚本，维护者输入密码，22:22 完成。中间有两处误会：一是维护者 22:09 已在别处签过 21:55 的候选包（代码相同，但包内 `META-INF/version-control-info.textproto` 记录的提交是 `e59ba98`），我报告时间时用了美国太平洋时间（写成“06:09”），维护者看不懂；二是我给的按钮写“已输入密码”，维护者点了按钮但密码其实还没输入。之后所有给维护者看的时间一律用北京时间（`TZ=Asia/Shanghai`）。
- **核对**：三个 APK 证书指纹 `daa2e919…ec925`、对齐通过、版本号不变、包内记录 `8f3a889`；`SHA256SUMS-1.0.0.txt` 与四个文件一致。
- **推送**：`main` 快进到 `8f3a889`；`git fetch --tags --force` 先把本地标签恢复为远端的 `c39e2e5`，随后删除并在 `8f3a889` 上重新创建注释标签 `v1.0.0`（`TZ=Asia/Shanghai`，+0800），`git push --force origin refs/tags/v1.0.0`。`8f3a889` 本身的提交时间带 -0800（提交时没有设 `TZ`），时刻相同，只是时区显示不同，没有改写。
- **Release**：`gh release upload --clobber` 替换三个 APK 和校验和文件，GitHub 记录的 SHA-256 与本地一致；`gh release edit` 在说明开头加“第二次发布（2026-11-27）”一节，并把“按屏幕分辨率等比例放大”改为“按屏幕大小等比例缩放”。Release 不是不可变的，替换附件没有障碍。
- **留到 1.0.1**：应用内更新说明（`QueueTerminalUi.kt` 的版本说明）仍写“按屏幕分辨率等比例放大”。
- **还没做（都需要维护者）**：同 7.131。

272. **1.0.0 第二次发布**：见上。记录发布的文档改动另外提交并推送。

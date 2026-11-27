# 仓库目录整理方案（2026-11-27，待维护者决定）

维护者（2026-11-27）：“实际上这个项目早就不只是终端了，而是多端建在一个项目里。之后有机会可以整理一下项目目录使之有序。”

本文件只提出方案。编写时没有移动、重命名或删除任何文件；除本文件和交接文档中的记录外，仓库没有其他改动。文中的事实均于 2026-11-27 通过阅读文件或运行只读命令核对；标明“官方文档所述”的两处（GitHub 仓库改名、Docker Compose 项目名）取自当天抓取的官方文档。

## 一、结论

- 建议采用**方案甲**：各部分的目录不动，只整理文档、忽略规则和说明；同时把 GitHub 仓库改名为 `maimai-q`。这两项可以解决“仓库只体现终端”和“文档混放”两个主要问题，不影响任何构建、部署和已安装的终端。
- **方案乙**在方案甲的基础上，把安卓应用的 Gradle 工程（根目录的 Gradle 文件、`app/`、`queue-core/`）整体移入 `android/`，使根目录只剩各部分的文件夹。它能让根目录最整齐，代价是所有安卓构建命令都要先进入 `android/`，与安卓有关的文档要一并改写。服务端、网页端模板和 QQ 机器人的目录在两种方案中都不动。
- 不建议把各部分都改为短名（`core/`、`server/`、`web/`、`bot/` 等）：收益只是名称更短，却会让已用 Docker 部署的服务端改用一个新的空数据卷（见第五节）。
- 实施时机：1.0.0 提交、推送并发布之后；没有其他会话在修改仓库，工作区干净。仓库改名、方案甲、方案乙可以分开进行，每一步单独提交。
- 例外：`.gitignore` 补充签名文件和 `.claude/launch.json` 的忽略规则风险很低，建议在提交 1.0.0 之前单独完成，避免这次提交误带入 `.claude/launch.json`。

## 二、现状

### 2.1 根目录与各部分

| 路径 | 内容 | 构建或运行入口 | 说明 |
| --- | --- | --- | --- |
| `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradlew`、`gradlew.bat`、`gradle/` | 安卓应用与排队核心共用的 Gradle 根工程 | `rootProject.name = "maimai Q"`；`include(":app")`、`include(":queue-core")` | 只构建五个部分中的两个，却占根目录被跟踪项目中的 6 项 |
| `app/` | 安卓应用：本地版、现场终端、管理后台三个变体（flavor 为 `local`、`terminal`、`management`） | Gradle 模块 `:app`，依赖 `:queue-core`；`packageLocalDebugApk` 等三个任务把调试包复制到 Gradle 根目录下的 `output/apk/` | 应用 ID 与目录无关 |
| `queue-core/` | 纯 Kotlin 排队核心 | Gradle 模块 `:queue-core` | 只被 `:app` 使用 |
| `cloud-server/` | Flask 服务端：单文件 `app.py` 与测试 `test_app.py` | `python -m unittest`；`docker compose up -d --build`（构建上下文为本目录）；systemd 单元写的是服务器上的 `/opt/maimai-queue-status` | 本机虚拟环境 `.venv/` 已忽略 |
| `public-site/` | 网页端模板（Vue + Vite） | 本目录自有 `pnpm-lock.yaml` 与只含本目录的 `pnpm-workspace.yaml`；`pnpm build` 输出 `dist/` | `scripts/sync-site-main.mjs` 按脚本自身位置查找源文件，目标目录由 `--site-main` 或 `SITE_MAIN_DIR` 指定 |
| `koishi-bot/` | QQ 机器人（Koishi 插件 `koishi-plugin-maimai-q`） | 本目录自有 `pnpm-lock.yaml`；`pnpm test`；发布时打包为 `.tgz` | systemd 单元写的是服务器上的 `/root/maimai-q-koishi` |
| `docs/` | 全部文档 | — | 见 2.3 |
| `scripts/` | 仅有 `generate_user_manual_pdf.py` | 默认读取 `docs/user-manual.md`，写入 `output/pdf/maimai-Q-玩家使用手册.pdf`，均相对于当前目录，因此必须在仓库根目录运行 | — |
| `output/` | 构建产物 | 整个目录被忽略，唯独 `output/pdf/*.pdf` 被跟踪 | `output/release-1.0.0/` 中有未签名候选包、网站与机器人压缩包、签名脚本 `sign-1.0.0.sh`（脚本同样被忽略） |
| `tmp/claude-work/` | Claude 的临时工作文件 | 被忽略；约 81 MB、50 余项 | `old-0.13` 是登记过的 git 工作树（`6a04c24`）；演示脚本 `demo/serve.py` 按相对路径引用 `cloud-server` |
| `.claude/launch.json` | Claude 应用启动演示服务的配置 | 未跟踪，也未被忽略 | 写有 `cloud-server/.venv`、`public-site`、`tmp/claude-work` 等本机路径 |

另外：

- 仓库没有持续集成配置（不存在 `.github/workflows/`）。
- 维护者的网站实例 `~/Developer/site-docs` 的部署工作流只检出它自身；在其中检索本仓库名称和各部分目录名，没有任何结果（只读检查）。主网站同步检查只依赖命令行传入的路径。

### 2.2 各部分之间写死的路径

- **代码之间**：没有跨目录读取文件。各测试和脚本中的相对路径都在本部分目录之内（例如 `PairedMachineQueueNoticesTest` 读取 `src/main/java/...`，`test_app.py` 读取同目录的 `app.py`，机器人测试读取 `../lib`）。因此把任何一个部分整体移动，它自身仍能构建和测试。
- **Gradle**：模块名为 `:app`、`:queue-core`；`app/build.gradle.kts` 以 `project(":queue-core")` 引用排队核心，以 `rootProject.layout.projectDirectory.dir("output/apk")` 指定调试包位置（相对于 Gradle 根目录，共 3 处）。
- **代码注释引用文档**：`docs/releases/` 中的设计与方案文档被代码注释引用 22 处，分布在 16 个文件中（终端、排队核心、网页端模板、机器人）；`QueueTerminalUi.kt` 另有 1 处引用 `tmp/claude-work/ui-review/` 中的示意图。
- **文档之间**：交接文档以外，另有 10 个文档共 36 处提到 `docs/releases/` 中的设计、方案与审查文档；交接文档提到 29 处。
- **文档引用临时目录**：`docs/ui-guide.md`（4 处）、`ui-redesign-2026-11.md`（8 处）、`dual-machine-queue-plan.md`（4 处）、`type-radius-review-2026-11.md`（2 处）、`solo-pass-plan-2026-11.md`（1 处）以 `tmp/claude-work/` 中的示意图或脚本作为依据。
- **GitHub 链接**：`README.md` 4 处（版本徽章、Releases、克隆命令、进入目录），`docs/public-beta-deployment.md` 3 处（克隆、进入目录、Releases 与订阅地址），0.10.1、0.10.2 的发布说明中有带版本标签的链接。安卓应用、服务端、网页端模板和机器人的代码中都没有 GitHub 地址。
- **GitHub Release 正文**：读取了全部 43 个 Release 的正文，其中指向仓库文件的链接都带版本标签（形如 `blob/v0.11.0/docs/update.md`），没有指向 `main` 分支的链接。

### 2.3 问题

1. **仓库名只体现“终端”**。仓库名为 `maimai-queue-terminal`，README 的项目结构图也以它为根，并且漏列了 `public-site/`、`scripts/` 和 `output/release-<版本>/`。实际上仓库包含五个部分：排队核心、安卓应用（三个变体）、服务端、网页端模板和 QQ 机器人。各部分的目录名（`app`、`queue-core`、`cloud-server`、`public-site`、`koishi-bot`）本身并没有“终端”字样。
2. **根目录混放**。根目录被跟踪的 17 项中，有 6 项属于安卓的 Gradle 根工程，与其余各部分并列，不熟悉安卓开发的人难以分辨哪些只属于安卓。
3. **`docs/releases/` 名不副实**。其中 28 个文件只有 13 个是版本发布说明，其余是 5 个版本开发计划、5 个功能设计与文字对照表、5 个审查与对照表；2026 年 11 月以来新增的大多属于后两类。
4. **定稿示意图不在版本库中**。界面规范要求界面与定稿示意图一致，但示意图只存放在被忽略的 `tmp/claude-work/ui-review/` 中：清理临时目录就会丢失，在其他电脑上这些引用全部失效。
5. **临时目录没有清理规则**。`tmp/claude-work/` 已有约 81 MB、50 余项，其中包含一个登记过的 git 工作树（删除时必须使用 `git worktree remove`）。它被忽略，不会误提交，但没有约定何时清理、哪些内容需要留存。
6. **`.claude/launch.json` 未被忽略**。执行 `git add -A` 会把它提交，而其中的路径只在这台 Mac 上有效。
7. **忽略规则不含签名相关文件**。`.gitignore` 只忽略 `*.jks`、`*.keystore` 和 `keystore.properties`。维护者的长期发布密钥库是 `maimai-q-release.p12`，旧电脑的签名设置文件名为 `release-signing.properties`，可能写有明文密码；`*.p12`、`*.pfx` 和这个文件名都不在忽略规则中。按约定这些文件只放在仓库之外的 `~/Developer/maimai-q-signing/`，2026-11-27 检查时仓库中也没有任何密钥库或签名设置文件，目前没有实际风险；但一旦有人把它们复制进仓库，就没有任何保护。
8. **`output/` 的用途不单一**。它整体被忽略，却跟踪着玩家手册 PDF；每个版本的签名脚本放在被忽略的 `output/release-<版本>/` 中，没有版本记录，每次发布都要重新编写。
9. **交接文档过长**。`docs/handoff/2026-11-17-project-review.md` 已超过 5,000 行，接手时难以通读。

## 三、方案甲：各部分目录不动，只整理文档、忽略规则和说明

### 3.1 目标结构

```text
maimai-queue-terminal/（改名后为 maimai-q/）
├─ app/  queue-core/  cloud-server/  public-site/  koishi-bot/   不动
├─ docs/
│  ├─ README.md              新增：文档索引，说明各子目录的用途
│  ├─ releases/              只放版本发布说明（13 个，不动）
│  ├─ plans/                 版本开发计划、功能设计与方案、文字对照表（11 个）
│  ├─ reviews/               审查与对照表（5 个）
│  ├─ mockups/               定稿示意图（自临时目录复制，约 0.6 MB）
│  ├─ handoff/  images/  branding/  promo/   不动
│  └─ 用户手册、文案规范、界面规范、同步协议等 9 个文档   不动
├─ scripts/
│  ├─ generate_user_manual_pdf.py
│  └─ release/sign-apks.sh   可选：签名脚本改为跟踪，以版本号为参数
├─ output/                   只放构建产物，整体忽略
├─ gradle/  settings.gradle.kts  build.gradle.kts  gradle.properties  gradlew  gradlew.bat
└─ README.md  LICENSE  .gitignore
```

`docs/` 根目录下的 9 个规范与说明文档被代码注释、记忆和各文档大量引用，移动的收益很小，因此保留原位。

### 3.2 移动清单

| 原路径 | 新路径 |
| --- | --- |
| `docs/releases/0.10.0-plan.md`、`0.10.1-plan.md`、`0.11.0-plan.md`、`0.12.0-plan.md`、`0.13.0-management-app-plan.md` | `docs/plans/` 下同名 |
| `docs/releases/dual-machine-queue-plan.md`、`dual-machine-queue-copy.md`、`solo-pass-plan-2026-11.md`、`wait-estimate-2026-11.md`、`ui-redesign-2026-11.md`、`repo-layout-plan.md`（本文件） | `docs/plans/` 下同名 |
| `docs/releases/copy-review-2026-11.md`、`type-radius-review-2026-11.md`、`usability-review-2026-11.md`、`website-review-2026-11.md`、`review-2026-11-27.md` | `docs/reviews/` 下同名 |
| `tmp/claude-work/ui-review/` 中被文档引用的 8 个示意图（`home-mockup.html`、`home-mockup-b.html`、`join-flow-mockup.html`、`join-flow-mockup-2.html`、`join-flow-mockup-3.html`、`groups-mockup.html`、`preference-mockup.html`、`solo-pass-mockup.html`）及其引用的 `y2.png` | `docs/mockups/`（复制；临时目录中的原件留待清理时处理） |
| 可选：`output/pdf/maimai-Q-玩家使用手册.pdf` | `docs/user-manual.pdf` |
| 可选：`output/release-1.0.0/sign-1.0.0.sh` | `scripts/release/sign-apks.sh`（改为以版本号为参数） |

示意图移入前逐个确认其中只有演示数据，没有真实玩家的昵称或 QQ 号（仓库是公开的）。

### 3.3 需要同步修改的引用

- **代码注释**：22 处，16 个文件。只改注释中的路径，不改任何逻辑。
- **文档**：`docs/automatic-machine-arrangement.md`、`docs/cloud-queue-sync.md`、`docs/copywriting-guide.md`、`docs/roadmap.md`、`docs/ui-guide.md`、`docs/releases/1.0.0-paired-machines-and-redesign.md`，以及被移动文档之间的相互链接，共约 36 处。文档由 `docs/releases/` 移到 `docs/plans/` 或 `docs/reviews/`，层级不变，因此 `../ui-guide.md` 这类链接不用修改；指向发布说明的链接要改为 `../releases/...`。
- **示意图**：`docs/ui-guide.md`、`ui-redesign-2026-11.md`、`type-radius-review-2026-11.md`、`solo-pass-plan-2026-11.md` 中指向具体示意图的 14 处，以及 `QueueTerminalUi.kt` 中的 1 处，改为 `docs/mockups/`。其余几处指向临时目录中的截图、脚本和工作区快照，属于过程记录，保持原样。
- **`.gitignore`**：增加 `*.p12`、`*.pfx`、`release-signing.properties`、`/.claude/launch.json`、`/.claude/settings.local.json`；如移动 PDF，删除 `!/output/pdf/` 两行。
- **`README.md`**：项目结构图改为五个部分齐全，并说明 `docs/` 各子目录的用途；如移动 PDF，修改“玩家使用手册 PDF”的链接。
- **可选修改**：如移动 PDF，修改 `scripts/generate_user_manual_pdf.py` 的默认输出路径。
- **新增 `docs/README.md`**：文档索引，附新旧路径对照表。
- **交接文档**：不改写已有记录（它们记录的是当时的路径），只在新的一节中列出新旧路径对照。交接文档过长的问题，建议在 1.0.0 发布后以发布为界另起一份新的交接文档，原文件保留不动。
- **仓库之外**：Claude 记忆中有 9 个文件提到 `docs/releases/` 中的文档，需要同步更新。

### 3.4 影响

- **Gradle、pnpm、服务端部署、网页端模板构建、主网站同步检查**：均无影响，命令与路径不变。
- **已安装的终端和管理后台、已部署的服务端与机器人**：均无影响。
- **已发布的版本**：发布说明留在 `docs/releases/`，README 中的链接不变；Release 正文中的文件链接都带版本标签，不受 `main` 分支上文件移动的影响。如有人在仓库之外收藏了 `main` 分支上某个设计或审查文档的地址，移动后会失效（GitHub 不为移动的文件提供跳转），`docs/README.md` 中的对照表可以帮助找到新位置。

### 3.5 风险与回退

- 风险低：只涉及文档、注释和忽略规则。主要风险是漏改引用，可用第九节的检索核对。
- 纯移动（`git mv`）单独提交，修改引用另行提交。这样 git 能把移动识别为改名，每个文件的历史可用 `git log --follow` 查看。
- 回退：推送之前直接放弃这些提交；推送之后用 `git revert` 撤销。

## 四、方案乙：在方案甲的基础上，把安卓工程整体移入 `android/`

### 4.1 目标结构

```text
maimai-queue-terminal/（改名后为 maimai-q/）
├─ android/                  安卓应用的 Gradle 工程
│  ├─ app/                   现场终端、本地版、管理后台（Gradle 模块仍为 :app）
│  ├─ queue-core/            排队核心（Gradle 模块仍为 :queue-core）
│  └─ gradle/  settings.gradle.kts  build.gradle.kts  gradle.properties  gradlew  gradlew.bat
├─ cloud-server/             服务端（不动）
├─ public-site/              网页端模板（不动）
├─ koishi-bot/               QQ 机器人（不动）
├─ docs/  scripts/           与方案甲相同
├─ output/  tmp/             被忽略，不动
└─ README.md  LICENSE  .gitignore
```

根目录被跟踪的项目由 17 项减为 10 项（玩家手册 PDF 移出 `output/` 时为 9 项）。每个部分都是“进入自己的文件夹再构建”，安卓与服务端、机器人的用法一致。

### 4.2 移动清单

| 原路径 | 新路径 |
| --- | --- |
| `app/` | `android/app/` |
| `queue-core/` | `android/queue-core/` |
| `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradlew`、`gradlew.bat`、`gradle/` | `android/` 下同名 |
| 本机根目录的 `.gradle/`、`.kotlin/`、`build/`（均已忽略） | 不移动；整理后由 Gradle 在 `android/` 下重新生成，旧目录经维护者同意后删除 |

`settings.gradle.kts` 移入 `android/` 后，`include(":app")` 默认就对应 `android/app/`，设置文件本身不用修改。

### 4.3 需要同步修改的引用

- **`android/app/build.gradle.kts`**：三个复制调试包的任务把 `dir("output/apk")` 改为 `dir("../output/apk")`，否则调试包会落到 `android/output/apk/`。
- **`.gitignore`**：`/local.properties`、`/.idea/`、`/.kotlin/`、`/build`、`/queue-core/build/`、`/captures` 改为 `/android/` 下的路径；不带前导斜杠的规则（`.gradle`、`*.iml`、`.cxx` 等）不用修改。
- **`README.md`**：所有 Gradle 命令注明在 `android/` 目录中运行，候选包路径改为 `android/app/build/outputs/...`，共约 15 处；项目结构图。
- **`docs/public-beta-deployment.md`**：两条 Gradle 命令及其说明。
- **`docs/copywriting-guide.md`**（`app/src/test/`）、**`docs/ui-guide.md`**（`app/` 1 处）。
- **`docs/promo/video/build-video.ps1`**：读取版本号的 `app\build.gradle.kts` 改为 `android\app\build.gradle.kts`。
- **仓库之外**：Claude 记忆中的构建命令需注明先进入 `android/`。`~/Developer/maimai-q-signing/README.md` 中的签名命令不受影响。
- **不用修改**：`.claude/launch.json` 和演示脚本（只涉及服务端与网页端），以及服务端、网页端模板、机器人的全部文件。

### 4.4 影响

- **Gradle**：模块名不变，因此 `:app:assembleLocalDebug`、`:app:packageTerminalDebugApk` 等任务名，`app-local-release-unsigned.apk` 等由模块名决定的文件名，以及应用 ID 和签名都不变，已安装的终端可以照常覆盖升级。路径变化后第一次构建需要重新配置，耗时较长。移动前须先停止 Gradle 守护进程（`./gradlew --stop`）。
- **Android Studio**：改为打开 `android/` 文件夹；旧电脑上的 Android Studio 工程也需重新打开。
- **pnpm、服务端部署、网页端模板构建、主网站同步检查**：均无影响。
- **已发布的版本**：各版本标签中的目录和命令保持原样，与当时的文档一致；只有按 `main` 分支 README 构建的人需要改用新命令。

### 4.5 风险与回退

- 风险中等：涉及构建配置。主要风险是漏改 `output/apk` 路径或忽略规则，导致调试包放错位置、构建缓存出现在未跟踪文件中；第九节的检查可以发现。
- 不要与发布同时进行：发布所用的命令和文档在这次改动中都会变化。
- 回退：与方案甲相同，纯移动与修改引用分两次提交，推送前可直接放弃，推送后用 `git revert` 撤销。

## 五、考虑过但不建议的做法：各部分全部改为短名

例如 `core/`、`clients/terminal-app/`（或 `android/`）、`server/`、`web/`、`bot/`。不建议的原因如下：

1. **服务端改名会换用新的数据卷**。官方文档所述：未另行指定时，Docker Compose 以 Compose 文件所在目录的名称作为项目名，数据卷名称带有项目名前缀。按公开测试版部署方案在 `cloud-server/` 中运行 `docker compose` 的部署者，目录改名后再次执行 `docker compose up`，会新建一个空数据卷（原数据卷为 `cloud-server_queue-status-data`），旧容器则继续占用 8081 端口；此外还需把未跟踪的 `.env` 从旧目录移到新目录。在 `compose.yaml` 顶层写入 `name: cloud-server` 可以避免换卷，但仍需每位部署者读到升级说明。
2. **`public-site` 这个名称也用于发布附件**。Release 附件 `public-site-<版本>-beta.tar.gz`、Nginx 示例和部署文档都使用这个名称；只改目录名会前后不一致，连附件名一起改又会影响部署者的更新步骤。
3. **本机环境要重建**。`cloud-server/.venv` 和两个 `node_modules` 都要重新安装（虚拟环境中的脚本写有绝对路径）。
4. **`clients/terminal-app` 名称不准确**。`app/` 同时包含本地版、现场终端和管理后台，以“终端”命名正是这次要纠正的问题。
5. **改动量大**。文档改动比方案乙多约 40 处，换来的只是名称更短。

## 六、仓库改名（两种方案都可单独选择）

- **建议名称**：`maimai-q`。与应用名 maimai Q、APK 文件名前缀 `maimai-Q-`、机器人插件名 `koishi-plugin-maimai-q`、证书文件夹 `maimai-q-signing` 保持一致。2026-11-27 查询，维护者账户下没有同名仓库。
- **GitHub 的行为**（官方文档所述）：改名后，访问旧地址的网页会自动跳转到新名称，对旧地址的 `git clone`、`git fetch`、`git push` 照常有效；Issue、Wiki、Star 和关注者随仓库保留。不跳转的只有 GitHub Pages 地址和被其他工作流引用的 Action，本项目均未使用。今后**不能**在该账户下再新建名为 `maimai-queue-terminal` 的仓库，否则跳转失效。
- **已发布的版本**：标签、Release 和附件全部保留，旧的 Release 页面与附件下载地址属于网页访问，按官方文档同样跳转（改名后实际打开一次核对）。安卓应用、服务端、网页端模板和机器人的代码中都没有仓库地址，已安装的终端、已部署的服务端和机器人均不受影响。
- **需要修改的地方**：`README.md`（版本徽章链接、Releases 链接、克隆命令、进入目录的命令、结构图根目录名）、`docs/public-beta-deployment.md`（克隆、进入目录、Releases 与订阅地址）；本机执行 `git remote set-url origin https://github.com/tsuba-abcccc/maimai-q.git`。历史发布说明中带版本标签的链接保持原样，依靠跳转打开。
- **本机文件夹是否同时改名**：建议暂不改名。改名会带来以下影响：Claude 的记忆和会话记录按文件夹路径存放，路径变化后新会话读不到原有记忆，需要迁移记忆目录；`tmp/claude-work/old-0.13` 工作树记录的是绝对路径，需要先删除或事后执行 `git worktree repair`；`cloud-server/.venv` 需要重建；`~/Developer/maimai-q-signing/README.md` 写有旧路径。如确需改名，应放在最后进行，并先清理临时目录。
- **风险与回退**：风险低。回退时在 GitHub 设置中把名称改回 `maimai-queue-terminal`，并恢复本机的远程地址。

## 七、两种方案的比较

| 项目 | 方案甲 | 方案乙 |
| --- | --- | --- |
| 根目录 | 被跟踪 17 项，与现在相同 | 被跟踪 10 项，每个部分一个文件夹 |
| `docs/` | 按用途分为发布说明、计划与方案、审查、示意图 | 与方案甲相同 |
| 安卓构建命令 | 不变 | 先进入 `android/`，任务名不变 |
| Android Studio | 不变 | 改为打开 `android/` |
| 服务端、网页端模板、机器人、主网站同步检查 | 不变 | 不变 |
| 已安装的终端、已部署的服务 | 不受影响 | 不受影响 |
| 需修改的文件 | 约 30 个，主要是注释和文档中的路径 | 在方案甲之外再改 3 个文件（`app/build.gradle.kts`、`docs/public-beta-deployment.md`、宣传视频脚本），README 等多改约 15 处 |
| 风险 | 低 | 中（涉及构建配置） |

## 八、实施顺序与先决条件

先决条件：

1. 1.0.0 已提交、推送、签名并发布。目前工作区有大量未提交改动，1.0.0 也只在本地提交为 `29e1e65`。
2. 没有其他会话在修改仓库，工作区干净（`git status` 没有输出）。
3. 演示服务与模拟器上的调试已经结束；采用方案乙时，Gradle 守护进程已停止。

实施顺序（每一步单独提交，任何一步之后都可以停下）：

1. 在 `.gitignore` 中补充签名文件和 `.claude/launch.json` 的忽略规则。这一步与整理无关，可以在提交 1.0.0 之前单独完成。
2. 仓库改名（如同意）：在 GitHub 改名，修改本机远程地址和两份文档中的链接。
3. 方案甲：纯移动一次提交，修改引用一次提交，复制示意图一次提交。
4. 方案乙（如同意）：纯移动一次提交，修改构建配置和文档一次提交。
5. 更新 Claude 记忆；在交接文档中记录新旧路径对照。
6. 1.0.0 发布后，经维护者同意清理 `tmp/claude-work/`（`old-0.13` 使用 `git worktree remove` 删除）。

## 九、实施后的检查

- **检索残留的旧路径**（历史记录以外应当没有结果）：
  - 方案甲：`git grep -n -E "docs/releases/(0\.1[0-3]\.[0-9]-(management-app-)?plan|dual-machine|solo-pass|wait-estimate|ui-redesign|copy-review|type-radius|usability-review|website-review|review-2026|repo-layout)" -- . ':!docs/handoff' ':!docs/README.md' ':!docs/plans/repo-layout-plan.md'`（交接文档、`docs/README.md` 的新旧路径对照表和本文件中的旧路径是有意保留的）；`git grep -n -E "tmp/claude-work/ui-review/[a-z0-9-]+\.html" -- . ':!docs/handoff'`。
  - 方案乙：`git grep -n -E "(^|[^/])(app|queue-core)/(src|build)" -- README.md docs/*.md`；`git grep -n -F 'app\build' -- .`。
  - 仓库改名：`git grep -n "maimai-queue-terminal" -- README.md docs/public-beta-deployment.md`。
- **构建与测试**（全部通过才算完成；方案乙在 `android/` 中运行 Gradle）：
  - 先执行 `unset TZ`，再运行 `sh ./gradlew :queue-core:test`、`:app:testLocalDebugUnitTest`、`:app:testManagementDebugUnitTest`、`:app:testTerminalDebugUnitTest -PENABLE_TERMINAL_BUILD=true`；运行 `:app:packageLocalDebugApk` 后确认 APK 出现在仓库根目录的 `output/apk/`。
  - 在 `cloud-server/` 中运行 `python -m unittest`，在 `koishi-bot/` 中运行 `pnpm test`，在 `public-site/` 中运行 `pnpm build`。
  - 运行 `node public-site/scripts/sync-site-main.mjs --check --site-main ~/Developer/site-docs`。
  - 用 `git status --ignored` 确认没有构建缓存或本机文件变成未跟踪文件。
- **仓库改名后**：打开旧仓库地址和一个旧 Release 附件的下载地址，确认跳转；`git fetch` 正常。

## 十、需要维护者决定的事项

1. 采用方案甲、方案乙，还是暂不整理。
2. 是否把 GitHub 仓库改名为 `maimai-q`（或其他名称），本机文件夹是否同时改名。
3. 是否同意先在 `.gitignore` 中补充签名文件和 `.claude/launch.json` 的忽略规则（可以在提交 1.0.0 之前单独完成）。
4. 定稿示意图是否移入 `docs/mockups/`，进入公开的版本库。
5. 玩家手册 PDF 是否从 `output/pdf/` 移到 `docs/`。如果曾把它在 `main` 分支上的地址发给玩家，移动后旧地址会失效。
6. 签名脚本是否改为受版本管理的 `scripts/release/sign-apks.sh`。

## 十一、顺带发现、与目录无关的问题（未处理）

- `docs/promo/video/build-video.ps1` 按 `appVersionName` 匹配版本号，而 `app/build.gradle.kts` 现在写的是 `localAppVersionName`、`terminalAppVersionName` 等，大小写不同，匹配不到，视频文件名中的版本号会退回为 `current`。（推断：没有在 Windows 上实际运行该脚本。）
- `README.md` 开头仍写“当前版本仍处于 `0.x` 阶段”，与 1.0.0 不符。

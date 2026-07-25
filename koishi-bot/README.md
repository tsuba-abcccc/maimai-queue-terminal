# maimai Q Koishi OneBot 插件

该插件把 Koishi OneBot 与 maimai Q 私有 API 连接起来。QQ 号来自 OneBot 会话身份，玩家不能在命令中指定其他人的 QQ。

插件采用独立版本号，当前版本为 `0.1.8`，不会跟随 Android 应用版本同步递增。

## 当前功能

- `我的排队`：查询当前 QQ 对应登记的位置、预计等待时间、本次偏好、暂缓、暂离和未到场状态。终端在线时，游玩中的登记还会显示本轮用时。
- `查看队列`：查询机台 A、B 的游玩位置、全部等待位置、登记数量和登记状态。机台停止使用时仍会显示已经保留的登记顺序。
- `我的资料`：查询当前 QQ 对应的玩家资料。
- `修改昵称 新昵称`：向终端提交资料昵称修改。
- `修改性别 男 / 女 / 不愿透露`：向终端提交性别修改。
- `修改默认偏好 单人游玩 / 允许他人加入 / 每次询问`：向终端提交默认偏好修改。
- `排队通知`：查看个人通知设置；可发送“开启排队通知”或“关闭排队通知”进行调整。
- 私信通知：轮询本人相关队列事件，通过 OneBot 私信发送给已经成为机器人好友的玩家。

队列输出中的中点两侧不留空格，无等待登记时不额外显示空状态；闭店收尾期间会提示“今日营业时间已结束”。通知固定以“【排队通知】”开头并空一行。单独 @ 机器人会打开主菜单，@ 后附带命令仍会执行对应命令。

三项资料修改既可以把新内容写在命令后方，也可以只发送命令，再于 60 秒内按提示回复。等待期间发送“取消”可以结束本次修改。

`我的排队`、`我的资料`和三项资料修改命令只允许在 OneBot 私聊中使用，群聊中不会返回个人资料或个人状态。`查看队列`只读取公开队列，可以在群聊中使用。

资料修改不会直接覆盖云端快照。服务器先创建待执行命令，现场终端校验资料版本和昵称冲突，应用成功后再回传结果。

“修改默认偏好”只会改变以后从玩家资料库加入排队时使用的默认值，不会修改当前登记的本次游玩偏好。

远程加入排队和远程修改登记尚未开放。后端已经把 `remote_actions` 标记为 `false`，等待排队规则和授权边界确定后再逐项实现。

## 前置条件

- Koishi 4。
- OneBot 适配器及一个可正常收发消息的 QQ Bot 实现。
- Koishi 数据库服务，用于持久化通知游标。
- Koishi HTTP 服务。
- 已部署支持 `/api/queue-bot/*` 的 maimai Q 后端。
- 独立的高强度 `QUEUE_BOT_TOKEN`。

## 上线前检查

插件源码和测试通过并不代表线上接口已经部署。启用插件前应依次确认：

1. 服务器已经部署本仓库当前的 `cloud-server`，并配置独立的 `QUEUE_BOT_TOKEN`。
2. Nginx 已把 `/api/queue-bot/` 转发到该后端。未携带令牌访问 `/api/queue-bot/events` 时，应收到 `401`；若收到 `404`，说明路由或新版后端尚未上线。
3. 机厅安装的是具有同步能力的 `terminal` 变体，应用内“网站同步”和“QQ Bot 联动”均已开启，并且已经成功上传队列和玩家资料。返回 `503 QQ Bot 联动已关闭` 时需要在终端开启联动；其他 `503` 表示服务端令牌配置无效。纯本地 `local` 变体不会连接服务器。
4. OneBot 实例已经在线，并能向一个测试好友发送私信。完成该项检查后再开启 `notificationEnabled`，避免首批通知因发送器尚未就绪而耗尽重试次数。
5. `apiBase` 使用 HTTPS。只有 Koishi 与后端位于同一主机，并通过 `localhost`、`127.0.0.1` 或 `::1` 访问时才允许使用 HTTP。

不要让 `QUEUE_BOT_TOKEN` 与 `QUEUE_SYNC_TOKEN` 相同，也不要把令牌写入 Git 仓库。

## 构建可安装包

在本目录执行：

```bash
pnpm install
pnpm run build
pnpm pack
```

`pnpm pack` 会再次构建并生成 `koishi-plugin-maimai-q-0.1.8.tgz`。将该文件放到运行 Koishi 的机器，在 Koishi 项目根目录执行：

```bash
pnpm add -w /absolute/path/to/koishi-plugin-maimai-q-0.1.8.tgz
```

使用 pnpm 或 Yarn 管理 Koishi 项目时，应使用对应的本地包安装命令，不要在插件源码目录与 Koishi 项目之间直接复制 `node_modules`。

安装完成后，可以在 Koishi 控制台的插件配置中添加 `maimai-q`，也可以在 Koishi 配置文件的 `plugins` 下加入：

```yaml
plugins:
  maimai-q:
    apiBase: https://abcccc.top
    botToken: <与服务器 QUEUE_BOT_TOKEN 相同的令牌>
    oneBotSelfId: '<用于发送私信的机器人 QQ>'
    notificationEnabled: true
    notificationIntervalSeconds: 5
    commandWaitSeconds: 15
```

保存配置后重启 Koishi，确认日志中没有 Bot 认证失败，并分别执行“我的资料”和“查看队列”完成连通性检查。`botToken` 属于服务端机密；若 Koishi 配置文件以明文保存它，应限制该文件的读取权限。

生产服务器可以使用本目录的 `maimai-q-koishi.service` 交给 systemd 管理。该示例假设 Koishi 项目位于 `/root/maimai-q-koishi`，pnpm 位于 `/root/.local/npm/bin/pnpm`；路径不同时应先修改服务文件：

```bash
install -o root -g root -m 0644 maimai-q-koishi.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now maimai-q-koishi.service
systemctl status maimai-q-koishi.service
```

服务会在网络可用且后端服务启动后运行，并在 Koishi 意外退出时自动重启。日志可通过 `journalctl -u maimai-q-koishi.service` 查看。

配置字段含义：

- `apiBase`：例如 `https://abcccc.top`。只填写站点根地址，不要附加 `/api`、查询参数或账号密码。
- `botToken`：与服务器 `QUEUE_BOT_TOKEN` 相同。
- `oneBotSelfId`：存在多个 OneBot 实例时填写用于主动通知的机器人 QQ。留空时选择首个在线 OneBot；填写后不会自动改用其他实例。该字段不限制玩家从哪个 OneBot 会话执行命令。
- `notificationEnabled`：是否允许插件发送本人相关的私信通知，默认开启，是所有个人通知设置的总开关。
- `notificationIntervalSeconds`：默认 5 秒。
- `commandWaitSeconds`：资料修改等待终端回执的时间，默认 15 秒。

首次启用通知时，插件会把游标移动到当前最新事件，不会把历史日志一次性发送给所有玩家。

已经绑定玩家资料的 QQ，其个人排队通知默认开启。玩家关闭个人通知后，新事件不会投递，已经进入退避重试的通知也会停止；重新开启只影响之后处理的事件，不补发关闭期间已经跳过的通知。

终端关闭“QQ Bot 联动”后，查询、资料修改和通知轮询都会停止。服务器会使待处理命令失效并丢弃关闭期间的通知收件关系；重新开启后不会集中补发旧事件，云端玩家资料仍然保留。

通知会按“队列批次、事件、QQ”分别保存投递状态。发送成功后立即记录，即使前方有其他通知正在重试，也不会重复发送已完成的通知。游标和投递状态均保存在 Koishi 数据库中。

机器人不在线、玩家并非好友或私信权限不足时，插件会在 5 秒、15 秒、30 秒、60 秒和 120 秒后重试，最多尝试 6 次。最后一次仍失败时，该事件对该收件人的投递会被永久记为失败并写入管理日志，后续事件不会被一个长期失败的收件人阻塞。投递失败时不会改为群聊通知。

和其他私信系统一样，若进程恰好在 OneBot 确认发送成功后、投递状态写入数据库前异常退出，极少数通知仍可能重复一次。

## 隐私与失败处理

- 公开队列接口只为当前有效的资料库登记返回 QQ，供网站登记详情联系玩家；性别、资料 UUID 和完整玩家资料仍不公开。
- 私有接口必须携带 Bot 令牌。
- Bot 令牌是服务级凭据，可以读取完整玩家资料库、当前登记绑定和通知事件中的全部 QQ 收件人；它不能提供给玩家或网页前端。
- 按 QQ 查询玩家和资料时，QQ 只放在 POST JSON 内容中，不写入 URL 参数，避免被常规访问日志记录。
- 私信失败时不会回退到群聊，避免公开玩家的个人排队状态。
- 玩家命令只使用 OneBot 会话中的 QQ 查找发送者自己的资料、登记和相关事件，不能在命令中改查其他 QQ。
- 资料 QQ 是身份键，不能通过普通资料修改命令更换。

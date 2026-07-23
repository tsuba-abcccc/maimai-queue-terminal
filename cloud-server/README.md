# maimai Queue Status API

为现场 Android 终端提供单写入方队列同步，并向静态网站提供只读公开快照。

## 本地验证

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python -m unittest -v
```

Windows PowerShell 激活命令为 `.venv\Scripts\Activate.ps1`。

## 部署（Docker）

1. 复制 `.env.example` 为 `.env`，使用密码生成器创建至少 32 字节的随机 `QUEUE_SYNC_TOKEN`。
2. 运行 `docker compose up -d --build`。
3. 将 `nginx-location.conf.example` 中的三个 `location` 放入 `abcccc.top` 的 HTTPS 站点配置。
4. 执行 `nginx -t`，确认无误后重载 Nginx。
5. 访问 `https://abcccc.top/queue-api-healthz`，应返回 `{"service":"maimai-queue-status","status":"ok"}`。

不要把 `.env` 或令牌提交到 Git。若只允许一台设备写入，将终端 UUID 写入 `QUEUE_DEVICE_ID`。若需要主备设备，将主终端 UUID 写入 `QUEUE_PRIMARY_DEVICE_ID`：当前写入终端在线时，其他设备不能接管；当前终端离线超过 `QUEUE_ONLINE_TIMEOUT_SECONDS` 后，其他设备可以临时接管；主终端恢复后可以立即重新取得写入权。设备之间切换不会合并各自的本地队列。

终端构建必须使用同一个令牌：

```properties
QUEUE_SYNC_URL=https://abcccc.top/api/queue-status
QUEUE_SYNC_TOKEN=<与服务器相同的令牌>
```

完整字段和版本规则见 [`../docs/cloud-queue-sync.md`](../docs/cloud-queue-sync.md)。

### 当前服务器的 systemd 部署

服务器如果不使用 Docker，可以使用仓库中的 `maimai-queue-status.service`：

1. 将 `app.py` 和 `requirements.txt` 放到 `/opt/maimai-queue-status`，在该目录创建 Python 虚拟环境 `venv` 并安装依赖。
2. 创建仅 root 可读的 `/etc/maimai-queue-status.env`，至少包含 `QUEUE_SYNC_TOKEN`，并将 `QUEUE_DATABASE_PATH` 设为 `/var/lib/maimai-queue-status/queue.db`。
3. 创建系统用户 `maimaiqueue`，将 `/opt/maimai-queue-status` 和 `/var/lib/maimai-queue-status` 的所有者设为该用户。
4. 将 service 文件复制到 `/etc/systemd/system/`，执行 `systemctl daemon-reload && systemctl enable --now maimai-queue-status`。
5. 在主站 HTTPS server 中加入 `nginx-location.conf.example` 的精确 location。它可以与已有的通用 `/api/` 代理并存，精确路径会优先匹配。更新后应分别确认 `/api/queue-status` 与 `/api/queue-logs` 都能读取。

当前部署使用 `127.0.0.1:8081`，避免与服务器上已有的其他 API 占用端口。

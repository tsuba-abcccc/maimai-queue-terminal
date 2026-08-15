# maimai Q 独立公开队列页

这是公开发布用的轻量静态网站，只包含排队状态、公开日志、版本信息和线上登记入口，不包含其他站点的文章、导航、统计或重定向逻辑。

## 构建

```bash
pnpm install --frozen-lockfile
pnpm run build
```

构建结果在 `dist/`。根路径是独立队列页的规范地址；`/queue-status` 和 `/queue-status/` 仅作为旧链接兼容入口，建议由 Nginx 重定向到根路径。

Nginx 可直接参考 [`nginx-location.conf.example`](nginx-location.conf.example)。API 反向代理仍使用主项目 `cloud-server/nginx-location.conf.example`，不要把 API 令牌写入静态站点配置。

默认情况下，页面从当前站点同源读取 `/api/queue-status` 等接口。分域部署时，在构建前设置 `VITE_QUEUE_*` 环境变量，变量名称与 `QueueStatusPanel.vue` 中的 API 约定一致。

## 复用队列组件

`src/queue/` 是公开队列页的规范源文件。其他站点如需复用队列组件，应按自身构建流程同步，并在发布前运行项目提供的一致性检查；同步不会修改其他站点的导航、文章、主题或部署配置。

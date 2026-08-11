# maimai Q 独立公开队列页

这是公开发布用的轻量静态网站，只包含排队状态、公开日志、版本信息和线上登记入口，不包含维护者个人网站的文章、导航、统计或重定向逻辑。

## 构建

```bash
pnpm install --frozen-lockfile
pnpm run build
```

构建结果在 `dist/`。`dist/index.html` 与 `dist/queue-status/index.html` 都指向同一个队列应用；部署者可以将根路径或 `/queue-status` 指向它。

Nginx 可直接参考 [`nginx-location.conf.example`](nginx-location.conf.example)。API 反向代理仍使用主项目 `cloud-server/nginx-location.conf.example`，不要把 API 令牌写入静态站点配置。

默认情况下，页面从当前站点同源读取 `/api/queue-status` 等接口。分域部署时，在构建前设置 `VITE_QUEUE_*` 环境变量，变量名称与 `QueueStatusPanel.vue` 中的 API 约定一致。

## 与个人 site-main 同步

`src/queue/` 是公开队列页的规范源文件。个人 `site-main` 保留自己的 VitePress 外壳，但使用同一组队列组件。前端改动后先在本目录修改，再执行：

```bash
pnpm run sync:site-main -- --site-main D:/path/to/site-main
pnpm run check:site-main -- --site-main D:/path/to/site-main
```

同步脚本只覆盖四个队列共享组件，不会修改个人站点的导航、文章、主题或部署配置。发布前必须通过一致性检查，并分别构建两个站点。

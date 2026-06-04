# Production Deploy

目标域名：

- `block.gadrel.top`

部署目标：

- SSH 别名：`tencent`
- 服务器项目目录：`/opt/spam-db-api/app`
- Compose 目录：`/opt/spam-db-api/app/deploy`
- 健康检查：`https://block.gadrel.top/health`

数据库：

- 线上服务默认连接宿主机 PostgreSQL：`127.0.0.1:5432`
- 本地联调可使用外网库：`pg.gadrel.top`

## 推荐方式

在仓库根目录执行：

```bash
cd spam-call-blocker-cn
bash scripts/deploy-tencent.sh
```

脚本会自动完成：

- 打包后端部署所需文件
- 上传到腾讯云主机
- 保留远端现有 `deploy/.env`
- 执行 `docker-compose build --no-cache spam-db-api`
- 执行 `docker-compose up -d --force-recreate spam-db-api`
- 校验 `127.0.0.1:8080/health`
- 校验后台控制台页面是否包含新版 `data-list` 标记

## 手动部署

```bash
ssh tencent
cd /opt/spam-db-api/app/deploy
docker-compose build --no-cache spam-db-api
docker-compose up -d --force-recreate spam-db-api
```

## 环境文件

远端必须存在：

`/opt/spam-db-api/app/deploy/.env`

常用内容示例：

```env
POSTGRES_USER=spam
POSTGRES_PASSWORD=spam
DATABASE_URL=postgres://spam:spam@127.0.0.1:5432/spam_db
BIND_ADDRESS=0.0.0.0:8080
ADMIN_PASSWORD=your-password
RUST_LOG=spam_db_api=info,tower_http=info,axum=info
POSTGRES_DB=spam_db
```

部署脚本不会覆盖远端 `.env`，但如果远端目录被误删，需要手动恢复。

## 发布后检查

```bash
ssh tencent "curl -fsS http://127.0.0.1:8080/health"
curl -fsS https://block.gadrel.top/health
```

如果需要确认控制台模板已经切到新版紧凑列表，可检查返回 HTML 是否包含：

- `data-list`
- `data-row`

## 反向代理

Nginx 将 `block.gadrel.top` 反代到：

- `127.0.0.1:8080`

## 证书

使用 `certbot --nginx -d block.gadrel.top`

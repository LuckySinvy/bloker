# spam-db-api

自建骚扰电话数据库后端服务。

## 技术栈

- Rust
- Axum
- SeaORM
- PostgreSQL

## 本地运行

```bash
cargo run -p spam-db-api
```

## 环境变量

- `DATABASE_URL`
  默认值：`postgres://spam:spam@127.0.0.1:5432/spam_db`
- `BIND_ADDRESS`
  默认值：`127.0.0.1:8080`

## 主要接口

- `GET /health`
- `GET /api/v1/spam-reports`
- `GET /api/v1/spam-reports/lookup?phone_number=...`
- `POST /api/v1/spam-reports`
- `GET /docs`
- `GET /api-docs/openapi.json`

## 当前数据模型

表：`spam_numbers`

- `normalized_number`
- `raw_number`
- `label`
- `source`
- `risk_level`
- `report_count`
- `last_reported_at`
- `created_at`
- `updated_at`
- `notes`

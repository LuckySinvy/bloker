# Lingti Blocker Monorepo

这个仓库现在调整为多项目结构，目标是同时维护 Android 客户端和自建骚扰电话数据库后端。

## 目录结构

```text
apps/
  android/              Android 客户端
services/
  spam-db-api/          Rust + SQLite 骚扰电话数据库后端
```

## 技术选型

### Android 客户端

- Kotlin
- Android Gradle

### 骚扰电话数据库后端

- Rust
- Axum
- SeaORM
- PostgreSQL

## 快速开始

### Android

```bash
cd apps/android
./gradlew assembleDebug
```

### Rust 后端

```bash
cargo run -p spam-db-api
```

默认会使用：

```text
postgres://spam:spam@127.0.0.1:5432/spam_db
```

你也可以通过环境变量覆盖：

```bash
export DATABASE_URL="postgres://spam:spam@127.0.0.1:5432/spam_db"
export BIND_ADDRESS="127.0.0.1:8080"
```

## 后端第一版接口

- `GET /health`
- `GET /api/v1/spam-reports`
- `GET /api/v1/spam-reports/lookup?phone_number=...`
- `POST /api/v1/spam-reports`
- `GET /docs`
- `GET /api-docs/openapi.json`

示例请求：

```json
{
  "phone_number": "01051428977",
  "label": "营销骚扰",
  "source": "manual",
  "risk_level": 85,
  "notes": "频繁推销贷款"
}
```

## 说明

- 当前 Android 客户端仍然保留原有命名空间 `com.addev.listaspam`，以降低迁移风险。
- Rust 后端目前是第一版骨架，优先解决自建号码库的存储、查询和人工录入。
- Android 举报号码流程现在默认写入自建 Rust 后端，Tellows 只作为可选兼容同步渠道。
- 下一步通常会是：认证、分页、去重策略、审核流、批量导入、客户端 API 对接。

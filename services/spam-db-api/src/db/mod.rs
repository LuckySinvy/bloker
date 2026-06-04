use anyhow::Context;
use sea_orm::{ConnectionTrait, Database, DatabaseConnection, Statement};

#[derive(Clone)]
pub struct AppState {
    pub db: DatabaseConnection,
}

pub async fn init_database(database_url: &str) -> anyhow::Result<DatabaseConnection> {
    let db = Database::connect(database_url)
        .await
        .with_context(|| format!("failed to connect to database: {database_url}"))?;

    db.execute(Statement::from_string(
        sea_orm::DatabaseBackend::Postgres,
        r#"
        CREATE TABLE IF NOT EXISTS spam_numbers (
            id SERIAL PRIMARY KEY,
            normalized_number TEXT NOT NULL UNIQUE,
            raw_number TEXT NOT NULL,
            label TEXT NOT NULL,
            list_type TEXT NOT NULL DEFAULT 'blacklist',
            source TEXT NOT NULL,
            risk_level INTEGER NOT NULL DEFAULT 50,
            report_count INTEGER NOT NULL DEFAULT 1,
            last_reported_at TIMESTAMPTZ NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            notes TEXT
        );
        "#,
    ))
    .await
    .context("failed to create spam_numbers table")?;

    db.execute(Statement::from_string(
        sea_orm::DatabaseBackend::Postgres,
        "ALTER TABLE spam_numbers ADD COLUMN IF NOT EXISTS list_type TEXT NOT NULL DEFAULT 'blacklist';",
    ))
    .await
    .context("failed to add list_type to spam_numbers")?;

    db.execute(Statement::from_string(
        sea_orm::DatabaseBackend::Postgres,
        "UPDATE spam_numbers SET list_type = 'whitelist' WHERE label = 'not_spam' AND list_type = 'blacklist';",
    ))
    .await
    .context("failed to backfill list_type in spam_numbers")?;

    db.execute(Statement::from_string(
        sea_orm::DatabaseBackend::Postgres,
        r#"
        CREATE TABLE IF NOT EXISTS spam_reports (
            id SERIAL PRIMARY KEY,
            normalized_number TEXT NOT NULL,
            raw_number TEXT NOT NULL,
            label TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            notes TEXT,
            review_notes TEXT,
            approved_spam_number_id INTEGER,
            created_at TIMESTAMPTZ NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            reviewed_at TIMESTAMPTZ
        );
        "#,
    ))
    .await
    .context("failed to create spam_reports table")?;

    db.execute(Statement::from_string(
        sea_orm::DatabaseBackend::Postgres,
        r#"
        CREATE TABLE IF NOT EXISTS pattern_rules (
            id SERIAL PRIMARY KEY,
            pattern TEXT NOT NULL UNIQUE,
            label TEXT NOT NULL,
            notes TEXT,
            created_at TIMESTAMPTZ NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL
        );
        "#,
    ))
    .await
    .context("failed to create pattern_rules table")?;

    db.execute(Statement::from_string(
        sea_orm::DatabaseBackend::Postgres,
        r#"
        CREATE TABLE IF NOT EXISTS sync_events (
            id BIGSERIAL PRIMARY KEY,
            entity_type TEXT NOT NULL,
            item_key TEXT NOT NULL,
            operation TEXT NOT NULL,
            payload JSONB,
            created_at TIMESTAMPTZ NOT NULL
        );
        "#,
    ))
    .await
    .context("failed to create sync_events table")?;

    db.execute(Statement::from_string(
        sea_orm::DatabaseBackend::Postgres,
        "CREATE INDEX IF NOT EXISTS idx_spam_numbers_normalized_number ON spam_numbers (normalized_number);",
    ))
    .await
    .context("failed to create idx_spam_numbers_normalized_number")?;

    db.execute(Statement::from_string(
        sea_orm::DatabaseBackend::Postgres,
        "CREATE INDEX IF NOT EXISTS idx_spam_reports_status_updated_at ON spam_reports (status, updated_at DESC);",
    ))
    .await
    .context("failed to create idx_spam_reports_status_updated_at")?;

    db.execute(Statement::from_string(
        sea_orm::DatabaseBackend::Postgres,
        "CREATE INDEX IF NOT EXISTS idx_pattern_rules_pattern ON pattern_rules (pattern);",
    ))
    .await
    .context("failed to create idx_pattern_rules_pattern")?;

    db.execute(Statement::from_string(
        sea_orm::DatabaseBackend::Postgres,
        "CREATE INDEX IF NOT EXISTS idx_sync_events_id ON sync_events (id);",
    ))
    .await
    .context("failed to create idx_sync_events_id")?;

    Ok(db)
}

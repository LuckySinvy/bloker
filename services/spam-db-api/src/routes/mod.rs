use std::sync::Arc;

use axum::{
    Json,
    extract::{Path, Query, State},
    http::{HeaderMap, HeaderName, HeaderValue, StatusCode, header},
    response::IntoResponse,
};
use chrono::Utc;
use sea_orm::{
    ActiveModelTrait, ColumnTrait, Condition, EntityTrait, PaginatorTrait, QueryFilter,
    QueryOrder, QuerySelect, Set,
};
use sea_orm::sea_query::Expr;
use serde_json::json;
use utoipa::OpenApi;

use crate::{
    auth::AdminSession,
    db::AppState,
    domain::{
        AppSyncPatternRule, AppSyncPayload, AppSyncQuery, AppSyncSpamNumber, LIST_TYPE_BLACKLIST,
        LIST_TYPE_WHITELIST, LookupQuery, PatternRuleRecord, PatternRuleRequest,
        SpamLookupResponse, SpamNumberListQuery, SpamNumberListResponse, SpamNumberRecord,
        SpamNumberRequest, SpamReportRecord, SpamReportRequest, SpamReportReviewRequest,
        SpamReportStatsResponse, default_list_type_for_label, is_valid_list_type,
        is_valid_spam_label, normalize_number, normalize_pattern, validate_pattern,
    },
    entity::{pattern_rule, spam_number, spam_report, sync_event},
};

const REPORT_STATUS_PENDING: &str = "pending";
const REPORT_STATUS_APPROVED: &str = "approved";
const REPORT_STATUS_REJECTED: &str = "rejected";
const SYNC_ENTITY_BLACKLIST: &str = "blacklist";
const SYNC_ENTITY_WHITELIST: &str = "whitelist";
const SYNC_ENTITY_PATTERN_RULE: &str = "pattern_rule";
const SYNC_OPERATION_UPSERT: &str = "upsert";
const SYNC_OPERATION_DELETE: &str = "delete";

#[derive(OpenApi)]
#[openapi(
    paths(
        get_health,
        get_spam_report_stats,
        get_app_sync_payload,
        lookup_spam_number,
        list_spam_numbers,
        create_spam_number,
        update_spam_number,
        delete_spam_number,
        list_spam_reports,
        create_spam_report,
        update_spam_report,
        delete_spam_report,
        list_pattern_rules,
        create_pattern_rule,
        update_pattern_rule,
        delete_pattern_rule
    ),
    components(schemas(
        crate::domain::SpamReportRequest,
        crate::domain::SpamNumberRequest,
        crate::domain::SpamReportReviewRequest,
        crate::domain::SpamLookupResponse,
        crate::domain::SpamNumberRecord,
        crate::domain::SpamReportRecord,
        crate::domain::SpamReportStatsResponse,
        crate::domain::AppSyncPayload,
        crate::domain::AppSyncSpamNumber,
        crate::domain::AppSyncPatternRule,
        crate::domain::PatternRuleRequest,
        crate::domain::PatternRuleRecord
    )),
    tags(
        (name = "health", description = "Service health endpoints"),
        (name = "spam-reports", description = "Self-hosted spam number APIs"),
        (name = "pattern-rules", description = "Pattern rule management APIs")
    )
)]
pub struct ApiDoc;

#[utoipa::path(
    get,
    path = "/health",
    tag = "health",
    responses((status = 200, description = "Service health status"))
)]
pub async fn get_health() -> Json<serde_json::Value> {
    Json(json!({ "status": "ok", "service": "spam-db-api" }))
}

#[utoipa::path(
    get,
    path = "/api/v1/spam-reports/stats",
    tag = "spam-reports",
    responses((status = 200, description = "Report statistics", body = crate::domain::SpamReportStatsResponse))
)]
pub async fn get_spam_report_stats(
    State(state): State<Arc<AppState>>,
    _session: AdminSession,
) -> Result<Json<SpamReportStatsResponse>, (StatusCode, String)> {
    let total_records = spam_number::Entity::find()
        .count(&state.db)
        .await
        .map_err(internal_error)?;
    let blacklist_records = spam_number::Entity::find()
        .filter(spam_number::Column::ListType.eq(LIST_TYPE_BLACKLIST))
        .count(&state.db)
        .await
        .map_err(internal_error)?;
    let whitelist_records = spam_number::Entity::find()
        .filter(spam_number::Column::ListType.eq(LIST_TYPE_WHITELIST))
        .count(&state.db)
        .await
        .map_err(internal_error)?;
    let reported_records = spam_report::Entity::find()
        .count(&state.db)
        .await
        .map_err(internal_error)?;
    let pending_records = spam_report::Entity::find()
        .filter(spam_report::Column::Status.eq(REPORT_STATUS_PENDING))
        .count(&state.db)
        .await
        .map_err(internal_error)?;
    let rule_records = pattern_rule::Entity::find()
        .count(&state.db)
        .await
        .map_err(internal_error)?;

    Ok(Json(SpamReportStatsResponse {
        total_records,
        blacklist_records,
        whitelist_records,
        reported_records,
        pending_records,
        rule_records,
    }))
}

#[utoipa::path(
    get,
    path = "/api/v1/app-sync",
    tag = "spam-reports",
    responses((status = 200, description = "Aggregated app sync payload", body = crate::domain::AppSyncPayload))
)]
pub async fn get_app_sync_payload(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<AppSyncQuery>,
) -> Result<axum::response::Response, (StatusCode, String)> {
    let blacklist_count = spam_number::Entity::find()
        .filter(spam_number::Column::ListType.eq(LIST_TYPE_BLACKLIST))
        .count(&state.db)
        .await
        .map_err(internal_error)? as usize;
    let whitelist_count = spam_number::Entity::find()
        .filter(spam_number::Column::ListType.eq(LIST_TYPE_WHITELIST))
        .count(&state.db)
        .await
        .map_err(internal_error)? as usize;
    let pattern_count = pattern_rule::Entity::find()
        .count(&state.db)
        .await
        .map_err(internal_error)? as usize;
    let latest_spam_updated_at = spam_number::Entity::find()
        .select_only()
        .expr_as(Expr::cust("MAX(updated_at)"), "latest_updated_at")
        .into_tuple::<Option<chrono::DateTime<Utc>>>()
        .one(&state.db)
        .await
        .map_err(internal_error)?
        .flatten();
    let latest_rule_updated_at = pattern_rule::Entity::find()
        .select_only()
        .expr_as(Expr::cust("MAX(updated_at)"), "latest_updated_at")
        .into_tuple::<Option<chrono::DateTime<Utc>>>()
        .one(&state.db)
        .await
        .map_err(internal_error)?
        .flatten();
    let version_timestamp = latest_spam_updated_at
        .into_iter()
        .chain(latest_rule_updated_at)
        .max()
        .unwrap_or_else(Utc::now);
    let version = format!(
        "{}-{}-{}-{}",
        version_timestamp.timestamp(),
        blacklist_count,
        whitelist_count,
        pattern_count
    );
    let etag = format!("\"{version}\"");
    let latest_sync_cursor = sync_event::Entity::find()
        .order_by_desc(sync_event::Column::Id)
        .one(&state.db)
        .await
        .map_err(internal_error)?
        .map(|event| event.id)
        .unwrap_or(0);

    if headers
        .get(header::IF_NONE_MATCH)
        .and_then(|value| value.to_str().ok())
        .map(|value| value == etag)
        .unwrap_or(false)
    {
        let mut response_headers = HeaderMap::new();
        response_headers.insert(
            header::ETAG,
            HeaderValue::from_str(&etag).expect("valid etag"),
        );
        response_headers.insert(
            HeaderName::from_static("x-app-sync-cursor"),
            HeaderValue::from_str(&latest_sync_cursor.to_string()).expect("valid cursor"),
        );
        return Ok((StatusCode::NOT_MODIFIED, response_headers).into_response());
    }

    if let Some(cursor) = query.cursor {
        let events = sync_event::Entity::find()
            .filter(sync_event::Column::Id.gt(cursor))
            .order_by_asc(sync_event::Column::Id)
            .all(&state.db)
            .await
            .map_err(internal_error)?;
        if !events.is_empty() && events.len() <= 5000 {
            let mut blacklist_numbers = Vec::new();
            let mut whitelist_numbers = Vec::new();
            let mut pattern_rules = Vec::new();
            let mut deleted_blacklist_numbers = Vec::new();
            let mut deleted_whitelist_numbers = Vec::new();
            let mut deleted_pattern_rules = Vec::new();

            for event in &events {
                match (event.entity_type.as_str(), event.operation.as_str()) {
                    (SYNC_ENTITY_BLACKLIST, SYNC_OPERATION_UPSERT) => {
                        if let Some(payload) = &event.payload {
                            if let Ok(item) = serde_json::from_value::<AppSyncSpamNumber>(payload.clone().into()) {
                                blacklist_numbers.push(item);
                            }
                        }
                    }
                    (SYNC_ENTITY_BLACKLIST, SYNC_OPERATION_DELETE) => {
                        deleted_blacklist_numbers.push(event.item_key.clone());
                    }
                    (SYNC_ENTITY_WHITELIST, SYNC_OPERATION_UPSERT) => {
                        if let Some(payload) = &event.payload {
                            if let Ok(item) = serde_json::from_value::<AppSyncSpamNumber>(payload.clone().into()) {
                                whitelist_numbers.push(item);
                            }
                        }
                    }
                    (SYNC_ENTITY_WHITELIST, SYNC_OPERATION_DELETE) => {
                        deleted_whitelist_numbers.push(event.item_key.clone());
                    }
                    (SYNC_ENTITY_PATTERN_RULE, SYNC_OPERATION_UPSERT) => {
                        if let Some(payload) = &event.payload {
                            if let Ok(item) = serde_json::from_value::<AppSyncPatternRule>(payload.clone().into()) {
                                pattern_rules.push(item);
                            }
                        }
                    }
                    (SYNC_ENTITY_PATTERN_RULE, SYNC_OPERATION_DELETE) => {
                        deleted_pattern_rules.push(event.item_key.clone());
                    }
                    _ => {}
                }
            }

            let payload = AppSyncPayload {
                mode: "delta".to_string(),
                version,
                cursor: events.last().map(|event| event.id),
                spam_numbers: Vec::new(),
                blacklist_numbers,
                whitelist_numbers,
                pattern_rules,
                deleted_blacklist_numbers,
                deleted_whitelist_numbers,
                deleted_pattern_rules,
                spam_count: blacklist_count,
                blacklist_count: blacklist_count,
                whitelist_count: whitelist_count,
                pattern_count: pattern_count,
                generated_at: Utc::now(),
            };

            let mut response_headers = HeaderMap::new();
            response_headers.insert(
                header::ETAG,
                HeaderValue::from_str(&etag).expect("valid etag"),
            );
            response_headers.insert(
                HeaderName::from_static("x-app-sync-cursor"),
                HeaderValue::from_str(&events.last().map(|event| event.id).unwrap_or(latest_sync_cursor).to_string())
                    .expect("valid cursor"),
            );
            return Ok((StatusCode::OK, response_headers, Json(payload)).into_response());
        }
    }

    let spam_records = spam_number::Entity::find()
        .order_by_desc(spam_number::Column::UpdatedAt)
        .all(&state.db)
        .await
        .map_err(internal_error)?;
    let pattern_records = pattern_rule::Entity::find()
        .order_by_desc(pattern_rule::Column::UpdatedAt)
        .all(&state.db)
        .await
        .map_err(internal_error)?;

    let blacklist_numbers = spam_records
        .iter()
        .filter(|record| record.list_type == LIST_TYPE_BLACKLIST)
        .map(map_app_sync_spam_number)
        .collect::<Vec<_>>();
    let whitelist_numbers = spam_records
        .iter()
        .filter(|record| record.list_type == LIST_TYPE_WHITELIST)
        .map(map_app_sync_spam_number)
        .collect::<Vec<_>>();
    let spam_numbers = if query.compact.unwrap_or(false) {
        Vec::new()
    } else {
        blacklist_numbers.clone()
    };
    let pattern_rules = pattern_records
        .iter()
        .map(|record| AppSyncPatternRule {
            pattern: record.pattern.clone(),
            notes: record.notes.clone().filter(|v| !v.trim().is_empty()),
        })
        .collect::<Vec<_>>();
    let payload = AppSyncPayload {
        mode: "full".to_string(),
        version,
        cursor: Some(latest_sync_cursor),
        spam_count: blacklist_count,
        blacklist_count: blacklist_count,
        whitelist_count: whitelist_count,
        pattern_count: pattern_count,
        spam_numbers,
        blacklist_numbers,
        whitelist_numbers,
        pattern_rules,
        deleted_blacklist_numbers: Vec::new(),
        deleted_whitelist_numbers: Vec::new(),
        deleted_pattern_rules: Vec::new(),
        generated_at: Utc::now(),
    };

    let mut response_headers = HeaderMap::new();
    response_headers.insert(
        header::ETAG,
        HeaderValue::from_str(&etag).expect("valid etag"),
    );
    response_headers.insert(
        HeaderName::from_static("x-app-sync-cursor"),
        HeaderValue::from_str(&latest_sync_cursor.to_string()).expect("valid cursor"),
    );
    Ok((StatusCode::OK, response_headers, Json(payload)).into_response())
}

#[utoipa::path(
    get,
    path = "/api/v1/spam-reports/lookup",
    tag = "spam-reports",
    params(crate::domain::LookupQuery),
    responses(
        (status = 200, description = "Lookup result", body = crate::domain::SpamLookupResponse),
        (status = 400, description = "Invalid query")
    )
)]
pub async fn lookup_spam_number(
    State(state): State<Arc<AppState>>,
    Query(query): Query<LookupQuery>,
) -> Result<Json<SpamLookupResponse>, (StatusCode, String)> {
    let normalized = normalize_number(&query.phone_number);
    if normalized.is_empty() {
        return Err((StatusCode::BAD_REQUEST, "phone_number is required".to_string()));
    }

    let record = spam_number::Entity::find()
        .filter(spam_number::Column::NormalizedNumber.eq(normalized))
        .one(&state.db)
        .await
        .map_err(internal_error)?;

    let should_block = record
        .as_ref()
        .map(|record| record.list_type == LIST_TYPE_BLACKLIST)
        .unwrap_or(false);

    Ok(Json(SpamLookupResponse {
        found: record.is_some(),
        should_block,
        record: record.map(map_spam_record),
    }))
}

#[utoipa::path(
    get,
    path = "/api/v1/spam-numbers",
    tag = "spam-reports",
    params(crate::domain::SpamNumberListQuery),
    responses((status = 200, description = "List spam number library", body = crate::domain::SpamNumberListResponse))
)]
pub async fn list_spam_numbers(
    State(state): State<Arc<AppState>>,
    Query(query): Query<SpamNumberListQuery>,
    _session: AdminSession,
) -> Result<Json<SpamNumberListResponse>, (StatusCode, String)> {
    let query = query;
    let page = query.page.unwrap_or(1).max(1);
    let per_page = query.per_page.unwrap_or(50).clamp(1, 200);

    let mut condition = Condition::all();

    if let Some(list_type) = query
        .list_type
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        if !is_valid_list_type(list_type) {
            return Err((StatusCode::BAD_REQUEST, "invalid list_type".to_string()));
        }
        condition = condition.add(spam_number::Column::ListType.eq(list_type));
    }

    if let Some(label) = query.label.as_deref().map(str::trim).filter(|value| !value.is_empty()) {
        if !is_valid_spam_label(label) {
            return Err((StatusCode::BAD_REQUEST, "invalid label".to_string()));
        }
        condition = condition.add(spam_number::Column::Label.eq(label));
    }

    if let Some(keyword) = query
        .keyword
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        let escaped = keyword.replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_");
        let pattern = format!("%{escaped}%");
        condition = condition.add(
            Condition::any()
                .add(spam_number::Column::NormalizedNumber.like(pattern.clone()))
                .add(spam_number::Column::RawNumber.like(pattern.clone()))
                .add(spam_number::Column::Label.like(pattern.clone()))
                .add(spam_number::Column::Notes.like(pattern)),
        );
    }

    let base_query = spam_number::Entity::find().filter(condition);
    let paginator = base_query
        .order_by_desc(spam_number::Column::UpdatedAt)
        .paginate(&state.db, per_page);
    let total = paginator.num_items().await.map_err(internal_error)?;
    let total_pages = if total == 0 { 0 } else { total.div_ceil(per_page) };
    let current_page = if total_pages == 0 {
        1
    } else {
        page.min(total_pages)
    };
    let records = paginator
        .fetch_page(current_page - 1)
        .await
        .map_err(internal_error)?;

    Ok(Json(SpamNumberListResponse {
        records: records.into_iter().map(map_spam_record).collect(),
        page: current_page,
        per_page,
        total,
        total_pages,
    }))
}

#[utoipa::path(
    post,
    path = "/api/v1/spam-numbers",
    tag = "spam-reports",
    request_body = crate::domain::SpamNumberRequest,
    responses(
        (status = 201, description = "Created spam number", body = crate::domain::SpamNumberRecord),
        (status = 400, description = "Invalid payload")
    )
)]
pub async fn create_spam_number(
    State(state): State<Arc<AppState>>,
    _session: AdminSession,
    Json(payload): Json<SpamNumberRequest>,
) -> Result<(StatusCode, Json<SpamNumberRecord>), (StatusCode, String)> {
    let normalized = normalize_number(&payload.phone_number);
    if normalized.is_empty() {
        return Err((StatusCode::BAD_REQUEST, "phone_number is required".to_string()));
    }
    if !is_valid_spam_label(payload.label.trim()) {
        return Err((StatusCode::BAD_REQUEST, "invalid label".to_string()));
    }
    let list_type = payload
        .list_type
        .as_deref()
        .unwrap_or_else(|| default_list_type_for_label(payload.label.trim()));
    if !is_valid_list_type(list_type) {
        return Err((StatusCode::BAD_REQUEST, "invalid list_type".to_string()));
    }

    let now = Utc::now();
    let saved = spam_number::ActiveModel {
        normalized_number: Set(normalized),
        raw_number: Set(payload.phone_number.trim().to_string()),
        label: Set(payload.label.trim().to_string()),
        list_type: Set(list_type.to_string()),
        source: Set("admin-manual".to_string()),
        risk_level: Set(80),
        report_count: Set(1),
        last_reported_at: Set(now),
        created_at: Set(now),
        updated_at: Set(now),
        notes: Set(payload.notes.map(|notes| notes.trim().to_string()).filter(|v| !v.is_empty())),
        ..Default::default()
    }
    .insert(&state.db)
    .await
    .map_err(internal_error)?;

    record_sync_event(
        &state,
        sync_entity_for_list_type(&saved.list_type),
        &saved.normalized_number,
        SYNC_OPERATION_UPSERT,
        Some(json!(map_app_sync_spam_number(&saved))),
    )
    .await?;

    Ok((StatusCode::CREATED, Json(map_spam_record(saved))))
}

#[utoipa::path(
    put,
    path = "/api/v1/spam-numbers/{id}",
    tag = "spam-reports",
    request_body = crate::domain::SpamNumberRequest,
    params(("id" = i32, Path, description = "Number id")),
    responses(
        (status = 200, description = "Updated spam number", body = crate::domain::SpamNumberRecord),
        (status = 404, description = "Record not found")
    )
)]
pub async fn update_spam_number(
    State(state): State<Arc<AppState>>,
    _session: AdminSession,
    Path(id): Path<i32>,
    Json(payload): Json<SpamNumberRequest>,
) -> Result<Json<SpamNumberRecord>, (StatusCode, String)> {
    let model = spam_number::Entity::find_by_id(id)
        .one(&state.db)
        .await
        .map_err(internal_error)?
        .ok_or((StatusCode::NOT_FOUND, "record not found".to_string()))?;

    let normalized = normalize_number(&payload.phone_number);
    if normalized.is_empty() {
        return Err((StatusCode::BAD_REQUEST, "phone_number is required".to_string()));
    }
    if !is_valid_spam_label(payload.label.trim()) {
        return Err((StatusCode::BAD_REQUEST, "invalid label".to_string()));
    }
    let list_type = payload
        .list_type
        .as_deref()
        .unwrap_or_else(|| default_list_type_for_label(payload.label.trim()));
    if !is_valid_list_type(list_type) {
        return Err((StatusCode::BAD_REQUEST, "invalid list_type".to_string()));
    }

    let old_entity_type = sync_entity_for_list_type(&model.list_type);
    let old_key = model.normalized_number.clone();
    let mut active_model: spam_number::ActiveModel = model.into();
    active_model.normalized_number = Set(normalized);
    active_model.raw_number = Set(payload.phone_number.trim().to_string());
    active_model.label = Set(payload.label.trim().to_string());
    active_model.list_type = Set(list_type.to_string());
    active_model.notes = Set(payload.notes.map(|notes| notes.trim().to_string()).filter(|v| !v.is_empty()));
    active_model.updated_at = Set(Utc::now());
    let updated = active_model.update(&state.db).await.map_err(internal_error)?;
    let new_entity_type = sync_entity_for_list_type(&updated.list_type);
    if old_entity_type != new_entity_type || old_key != updated.normalized_number {
        record_sync_event(
            &state,
            old_entity_type,
            &old_key,
            SYNC_OPERATION_DELETE,
            None,
        )
        .await?;
    }
    record_sync_event(
        &state,
        new_entity_type,
        &updated.normalized_number,
        SYNC_OPERATION_UPSERT,
        Some(json!(map_app_sync_spam_number(&updated))),
    )
    .await?;
    Ok(Json(map_spam_record(updated)))
}

#[utoipa::path(
    delete,
    path = "/api/v1/spam-numbers/{id}",
    tag = "spam-reports",
    params(("id" = i32, Path, description = "Number id")),
    responses(
        (status = 204, description = "Deleted"),
        (status = 404, description = "Record not found")
    )
)]
pub async fn delete_spam_number(
    State(state): State<Arc<AppState>>,
    _session: AdminSession,
    Path(id): Path<i32>,
) -> Result<StatusCode, (StatusCode, String)> {
    let model = spam_number::Entity::find_by_id(id)
        .one(&state.db)
        .await
        .map_err(internal_error)?
        .ok_or((StatusCode::NOT_FOUND, "record not found".to_string()))?;
    let result = spam_number::Entity::delete_by_id(id)
        .exec(&state.db)
        .await
        .map_err(internal_error)?;
    if result.rows_affected == 0 {
        return Err((StatusCode::NOT_FOUND, "record not found".to_string()));
    }
    record_sync_event(
        &state,
        sync_entity_for_list_type(&model.list_type),
        &model.normalized_number,
        SYNC_OPERATION_DELETE,
        None,
    )
    .await?;
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    get,
    path = "/api/v1/spam-reports",
    tag = "spam-reports",
    responses((status = 200, description = "List spam reports", body = [crate::domain::SpamReportRecord]))
)]
pub async fn list_spam_reports(
    State(state): State<Arc<AppState>>,
    _session: AdminSession,
) -> Result<Json<Vec<SpamReportRecord>>, (StatusCode, String)> {
    let records = spam_report::Entity::find()
        .order_by_desc(spam_report::Column::UpdatedAt)
        .all(&state.db)
        .await
        .map_err(internal_error)?;

    Ok(Json(records.into_iter().map(map_spam_report_record).collect()))
}

#[utoipa::path(
    post,
    path = "/api/v1/spam-reports",
    tag = "spam-reports",
    request_body = crate::domain::SpamReportRequest,
    responses(
        (status = 201, description = "Report created", body = crate::domain::SpamReportRecord),
        (status = 400, description = "Invalid payload")
    )
)]
pub async fn create_spam_report(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<SpamReportRequest>,
) -> Result<(StatusCode, Json<SpamReportRecord>), (StatusCode, String)> {
    let normalized = normalize_number(&payload.phone_number);
    if normalized.is_empty() {
        return Err((StatusCode::BAD_REQUEST, "phone_number is required".to_string()));
    }
    if !is_valid_spam_label(payload.label.trim()) {
        return Err((StatusCode::BAD_REQUEST, "invalid label".to_string()));
    }

    let now = Utc::now();
    let saved = spam_report::ActiveModel {
        normalized_number: Set(normalized),
        raw_number: Set(payload.phone_number.trim().to_string()),
        label: Set(payload.label.trim().to_string()),
        status: Set(REPORT_STATUS_PENDING.to_string()),
        notes: Set(payload.notes.map(|notes| notes.trim().to_string()).filter(|v| !v.is_empty())),
        review_notes: Set(None),
        approved_spam_number_id: Set(None),
        created_at: Set(now),
        updated_at: Set(now),
        reviewed_at: Set(None),
        ..Default::default()
    }
    .insert(&state.db)
    .await
    .map_err(internal_error)?;

    Ok((StatusCode::CREATED, Json(map_spam_report_record(saved))))
}

#[utoipa::path(
    put,
    path = "/api/v1/spam-reports/{id}",
    tag = "spam-reports",
    request_body = crate::domain::SpamReportReviewRequest,
    params(("id" = i32, Path, description = "Report id")),
    responses(
        (status = 200, description = "Updated report", body = crate::domain::SpamReportRecord),
        (status = 404, description = "Record not found")
    )
)]
pub async fn update_spam_report(
    State(state): State<Arc<AppState>>,
    _session: AdminSession,
    Path(id): Path<i32>,
    Json(payload): Json<SpamReportReviewRequest>,
) -> Result<Json<SpamReportRecord>, (StatusCode, String)> {
    if payload.status != REPORT_STATUS_PENDING
        && payload.status != REPORT_STATUS_APPROVED
        && payload.status != REPORT_STATUS_REJECTED
    {
        return Err((StatusCode::BAD_REQUEST, "invalid status".to_string()));
    }

    let model = spam_report::Entity::find_by_id(id)
        .one(&state.db)
        .await
        .map_err(internal_error)?
        .ok_or((StatusCode::NOT_FOUND, "record not found".to_string()))?;

    let mut active_model: spam_report::ActiveModel = model.clone().into();
    let now = Utc::now();
    active_model.status = Set(payload.status.clone());
    active_model.review_notes = Set(
        payload
            .review_notes
            .clone()
            .map(|notes| notes.trim().to_string())
            .filter(|v| !v.is_empty()),
    );
    active_model.reviewed_at = Set(Some(now));
    active_model.updated_at = Set(now);

    let promote = payload.promote_to_library.unwrap_or(false) && payload.status == REPORT_STATUS_APPROVED;
    if promote {
        let approved_id = upsert_approved_spam_number(&state, &model).await?;
        active_model.approved_spam_number_id = Set(Some(approved_id));
    }

    let updated = active_model.update(&state.db).await.map_err(internal_error)?;
    Ok(Json(map_spam_report_record(updated)))
}

#[utoipa::path(
    delete,
    path = "/api/v1/spam-reports/{id}",
    tag = "spam-reports",
    params(("id" = i32, Path, description = "Report id")),
    responses(
        (status = 204, description = "Deleted"),
        (status = 404, description = "Record not found")
    )
)]
pub async fn delete_spam_report(
    State(state): State<Arc<AppState>>,
    _session: AdminSession,
    Path(id): Path<i32>,
) -> Result<StatusCode, (StatusCode, String)> {
    let result = spam_report::Entity::delete_by_id(id)
        .exec(&state.db)
        .await
        .map_err(internal_error)?;
    if result.rows_affected == 0 {
        return Err((StatusCode::NOT_FOUND, "record not found".to_string()));
    }
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    get,
    path = "/api/v1/pattern-rules",
    tag = "pattern-rules",
    responses((status = 200, description = "List pattern rules", body = [crate::domain::PatternRuleRecord]))
)]
pub async fn list_pattern_rules(
    State(state): State<Arc<AppState>>,
    _session: AdminSession,
) -> Result<Json<Vec<PatternRuleRecord>>, (StatusCode, String)> {
    let records = pattern_rule::Entity::find()
        .order_by_desc(pattern_rule::Column::UpdatedAt)
        .all(&state.db)
        .await
        .map_err(internal_error)?;
    Ok(Json(records.into_iter().map(map_pattern_rule_record).collect()))
}

#[utoipa::path(
    post,
    path = "/api/v1/pattern-rules",
    tag = "pattern-rules",
    request_body = crate::domain::PatternRuleRequest,
    responses(
        (status = 201, description = "Created rule", body = crate::domain::PatternRuleRecord),
        (status = 400, description = "Invalid payload")
    )
)]
pub async fn create_pattern_rule(
    State(state): State<Arc<AppState>>,
    _session: AdminSession,
    Json(payload): Json<PatternRuleRequest>,
) -> Result<(StatusCode, Json<PatternRuleRecord>), (StatusCode, String)> {
    let pattern = normalize_pattern(&payload.pattern);
    if !validate_pattern(&pattern) {
        return Err((StatusCode::BAD_REQUEST, "invalid pattern".to_string()));
    }
    if payload.label.trim().is_empty() {
        return Err((StatusCode::BAD_REQUEST, "label is required".to_string()));
    }

    let now = Utc::now();
    let saved = pattern_rule::ActiveModel {
        pattern: Set(pattern),
        label: Set(payload.label.trim().to_string()),
        notes: Set(payload.notes.map(|notes| notes.trim().to_string()).filter(|v| !v.is_empty())),
        created_at: Set(now),
        updated_at: Set(now),
        ..Default::default()
    }
    .insert(&state.db)
    .await
    .map_err(internal_error)?;

    record_sync_event(
        &state,
        SYNC_ENTITY_PATTERN_RULE,
        &saved.pattern,
        SYNC_OPERATION_UPSERT,
        Some(json!(AppSyncPatternRule {
            pattern: saved.pattern.clone(),
            notes: saved.notes.clone().filter(|v| !v.trim().is_empty()),
        })),
    )
    .await?;

    Ok((StatusCode::CREATED, Json(map_pattern_rule_record(saved))))
}

#[utoipa::path(
    put,
    path = "/api/v1/pattern-rules/{id}",
    tag = "pattern-rules",
    request_body = crate::domain::PatternRuleRequest,
    params(("id" = i32, Path, description = "Rule id")),
    responses(
        (status = 200, description = "Updated rule", body = crate::domain::PatternRuleRecord),
        (status = 404, description = "Rule not found")
    )
)]
pub async fn update_pattern_rule(
    State(state): State<Arc<AppState>>,
    _session: AdminSession,
    Path(id): Path<i32>,
    Json(payload): Json<PatternRuleRequest>,
) -> Result<Json<PatternRuleRecord>, (StatusCode, String)> {
    let model = pattern_rule::Entity::find_by_id(id)
        .one(&state.db)
        .await
        .map_err(internal_error)?
        .ok_or((StatusCode::NOT_FOUND, "rule not found".to_string()))?;

    let pattern = normalize_pattern(&payload.pattern);
    if !validate_pattern(&pattern) {
        return Err((StatusCode::BAD_REQUEST, "invalid pattern".to_string()));
    }

    let old_pattern = model.pattern.clone();
    let mut active_model: pattern_rule::ActiveModel = model.into();
    active_model.pattern = Set(pattern);
    active_model.label = Set(payload.label.trim().to_string());
    active_model.notes = Set(payload.notes.map(|notes| notes.trim().to_string()).filter(|v| !v.is_empty()));
    active_model.updated_at = Set(Utc::now());
    let updated = active_model.update(&state.db).await.map_err(internal_error)?;
    if old_pattern != updated.pattern {
        record_sync_event(
            &state,
            SYNC_ENTITY_PATTERN_RULE,
            &old_pattern,
            SYNC_OPERATION_DELETE,
            None,
        )
        .await?;
    }
    record_sync_event(
        &state,
        SYNC_ENTITY_PATTERN_RULE,
        &updated.pattern,
        SYNC_OPERATION_UPSERT,
        Some(json!(AppSyncPatternRule {
            pattern: updated.pattern.clone(),
            notes: updated.notes.clone().filter(|v| !v.trim().is_empty()),
        })),
    )
    .await?;
    Ok(Json(map_pattern_rule_record(updated)))
}

#[utoipa::path(
    delete,
    path = "/api/v1/pattern-rules/{id}",
    tag = "pattern-rules",
    params(("id" = i32, Path, description = "Rule id")),
    responses(
        (status = 204, description = "Deleted"),
        (status = 404, description = "Rule not found")
    )
)]
pub async fn delete_pattern_rule(
    State(state): State<Arc<AppState>>,
    _session: AdminSession,
    Path(id): Path<i32>,
) -> Result<StatusCode, (StatusCode, String)> {
    let model = pattern_rule::Entity::find_by_id(id)
        .one(&state.db)
        .await
        .map_err(internal_error)?
        .ok_or((StatusCode::NOT_FOUND, "rule not found".to_string()))?;
    let result = pattern_rule::Entity::delete_by_id(id)
        .exec(&state.db)
        .await
        .map_err(internal_error)?;
    if result.rows_affected == 0 {
        return Err((StatusCode::NOT_FOUND, "rule not found".to_string()));
    }
    record_sync_event(
        &state,
        SYNC_ENTITY_PATTERN_RULE,
        &model.pattern,
        SYNC_OPERATION_DELETE,
        None,
    )
    .await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn upsert_approved_spam_number(
    state: &Arc<AppState>,
    report: &spam_report::Model,
) -> Result<i32, (StatusCode, String)> {
    let now = Utc::now();
    let list_type = default_list_type_for_label(report.label.as_str());
    let existing = spam_number::Entity::find()
        .filter(spam_number::Column::NormalizedNumber.eq(report.normalized_number.clone()))
        .one(&state.db)
        .await
        .map_err(internal_error)?;

    let saved = if let Some(existing) = existing {
        let mut model: spam_number::ActiveModel = existing.into();
        let next_report_count = model.report_count.take().unwrap_or(0) + 1;
        model.raw_number = Set(report.raw_number.clone());
        model.label = Set(report.label.clone());
        model.list_type = Set(list_type.to_string());
        model.report_count = Set(next_report_count);
        model.last_reported_at = Set(now);
        model.updated_at = Set(now);
        if report.notes.is_some() {
            model.notes = Set(report.notes.clone());
        }
        model.update(&state.db).await.map_err(internal_error)?
    } else {
        spam_number::ActiveModel {
            normalized_number: Set(report.normalized_number.clone()),
            raw_number: Set(report.raw_number.clone()),
            label: Set(report.label.clone()),
            list_type: Set(list_type.to_string()),
            source: Set("admin-approved".to_string()),
            risk_level: Set(80),
            report_count: Set(1),
            last_reported_at: Set(now),
            created_at: Set(now),
            updated_at: Set(now),
            notes: Set(report.notes.clone()),
            ..Default::default()
        }
        .insert(&state.db)
        .await
        .map_err(internal_error)?
    };

    record_sync_event(
        state,
        sync_entity_for_list_type(&saved.list_type),
        &saved.normalized_number,
        SYNC_OPERATION_UPSERT,
        Some(json!(map_app_sync_spam_number(&saved))),
    )
    .await?;

    Ok(saved.id)
}

fn map_spam_record(model: spam_number::Model) -> SpamNumberRecord {
    SpamNumberRecord {
        id: model.id,
        normalized_number: model.normalized_number,
        raw_number: model.raw_number,
        label: model.label,
        list_type: model.list_type,
        report_count: model.report_count,
        notes: model.notes,
        last_reported_at: model.last_reported_at,
        created_at: model.created_at,
        updated_at: model.updated_at,
    }
}

fn map_spam_report_record(model: spam_report::Model) -> SpamReportRecord {
    SpamReportRecord {
        id: model.id,
        normalized_number: model.normalized_number,
        raw_number: model.raw_number,
        label: model.label,
        status: model.status,
        notes: model.notes,
        review_notes: model.review_notes,
        approved_spam_number_id: model.approved_spam_number_id,
        created_at: model.created_at,
        updated_at: model.updated_at,
        reviewed_at: model.reviewed_at,
    }
}

fn map_pattern_rule_record(model: pattern_rule::Model) -> PatternRuleRecord {
    PatternRuleRecord {
        id: model.id,
        pattern: model.pattern,
        label: model.label,
        notes: model.notes,
        created_at: model.created_at,
        updated_at: model.updated_at,
    }
}

fn map_app_sync_spam_number(record: &spam_number::Model) -> AppSyncSpamNumber {
    AppSyncSpamNumber {
        number: record.normalized_number.clone(),
        notes: record.notes.clone().filter(|value| !value.trim().is_empty()),
    }
}

async fn record_sync_event(
    state: &Arc<AppState>,
    entity_type: &str,
    item_key: &str,
    operation: &str,
    payload: Option<serde_json::Value>,
) -> Result<(), (StatusCode, String)> {
    sync_event::ActiveModel {
        entity_type: Set(entity_type.to_string()),
        item_key: Set(item_key.to_string()),
        operation: Set(operation.to_string()),
        payload: Set(payload.map(Into::into)),
        created_at: Set(Utc::now()),
        ..Default::default()
    }
    .insert(&state.db)
    .await
    .map_err(internal_error)?;
    Ok(())
}

fn sync_entity_for_list_type(list_type: &str) -> &'static str {
    if list_type == LIST_TYPE_WHITELIST {
        SYNC_ENTITY_WHITELIST
    } else {
        SYNC_ENTITY_BLACKLIST
    }
}

fn internal_error(error: impl std::fmt::Display) -> (StatusCode, String) {
    tracing::error!("{error}");
    (StatusCode::INTERNAL_SERVER_ERROR, "internal server error".to_string())
}

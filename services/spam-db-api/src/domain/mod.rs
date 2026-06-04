use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use utoipa::{IntoParams, ToSchema};

pub const SPAM_LABEL_OPTIONS: &[&str] = &[
    "loan_marketing",
    "fake_customer_service",
    "delivery_scam",
    "investment_scam",
    "harassment",
    "telemarketing",
    "not_spam",
];

pub const LIST_TYPE_BLACKLIST: &str = "blacklist";
pub const LIST_TYPE_WHITELIST: &str = "whitelist";

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct SpamReportRequest {
    pub phone_number: String,
    pub label: String,
    pub notes: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct SpamNumberRequest {
    pub phone_number: String,
    pub label: String,
    pub notes: Option<String>,
    pub list_type: Option<String>,
}

#[derive(Debug, Clone, Serialize, ToSchema)]
pub struct SpamLookupResponse {
    pub found: bool,
    pub should_block: bool,
    pub record: Option<SpamNumberRecord>,
}

#[derive(Debug, Clone, Serialize, ToSchema)]
pub struct SpamReportStatsResponse {
    pub total_records: u64,
    pub blacklist_records: u64,
    pub whitelist_records: u64,
    pub reported_records: u64,
    pub pending_records: u64,
    pub rule_records: u64,
}

#[derive(Debug, Clone, Serialize, ToSchema)]
pub struct AppSyncPayload {
    pub mode: String,
    pub version: String,
    pub cursor: Option<i64>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub spam_numbers: Vec<AppSyncSpamNumber>,
    pub blacklist_numbers: Vec<AppSyncSpamNumber>,
    pub whitelist_numbers: Vec<AppSyncSpamNumber>,
    pub pattern_rules: Vec<AppSyncPatternRule>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub deleted_blacklist_numbers: Vec<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub deleted_whitelist_numbers: Vec<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub deleted_pattern_rules: Vec<String>,
    pub spam_count: usize,
    pub blacklist_count: usize,
    pub whitelist_count: usize,
    pub pattern_count: usize,
    pub generated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct AppSyncSpamNumber {
    pub number: String,
    pub notes: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct AppSyncPatternRule {
    pub pattern: String,
    pub notes: Option<String>,
}

#[derive(Debug, Clone, Serialize, ToSchema)]
pub struct SpamNumberRecord {
    pub id: i32,
    pub normalized_number: String,
    pub raw_number: String,
    pub label: String,
    pub list_type: String,
    pub report_count: i32,
    pub notes: Option<String>,
    pub last_reported_at: DateTime<Utc>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, ToSchema)]
pub struct SpamNumberListResponse {
    pub records: Vec<SpamNumberRecord>,
    pub page: u64,
    pub per_page: u64,
    pub total: u64,
    pub total_pages: u64,
}

#[derive(Debug, Clone, Serialize, ToSchema)]
pub struct SpamReportRecord {
    pub id: i32,
    pub normalized_number: String,
    pub raw_number: String,
    pub label: String,
    pub status: String,
    pub notes: Option<String>,
    pub review_notes: Option<String>,
    pub approved_spam_number_id: Option<i32>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub reviewed_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct SpamReportReviewRequest {
    pub status: String,
    pub review_notes: Option<String>,
    pub promote_to_library: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct PatternRuleRequest {
    pub pattern: String,
    pub label: String,
    pub notes: Option<String>,
}

#[derive(Debug, Clone, Serialize, ToSchema)]
pub struct PatternRuleRecord {
    pub id: i32,
    pub pattern: String,
    pub label: String,
    pub notes: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize, IntoParams)]
pub struct LookupQuery {
    pub phone_number: String,
}

#[derive(Debug, Deserialize, IntoParams)]
pub struct SpamNumberListQuery {
    pub page: Option<u64>,
    pub per_page: Option<u64>,
    pub keyword: Option<String>,
    pub label: Option<String>,
    pub list_type: Option<String>,
}

#[derive(Debug, Deserialize, IntoParams)]
pub struct AppSyncQuery {
    pub compact: Option<String>,
    pub cursor: Option<i64>,
}

pub fn normalize_number(input: &str) -> String {
    input
        .chars()
        .filter(|ch| ch.is_ascii_digit() || *ch == '+')
        .collect()
}

pub fn normalize_pattern(input: &str) -> String {
    input.trim().to_string()
}

pub fn validate_pattern(pattern: &str) -> bool {
    let pattern = pattern.trim();
    if pattern.is_empty() {
        return false;
    }
    if pattern.len() < 2 || pattern.len() > 20 {
        return false;
    }
    if !pattern.chars().all(|ch| ch.is_ascii_digit() || ch == '+' || ch == '*') {
        return false;
    }
    if pattern.contains("**") || pattern.contains("++") {
        return false;
    }
    true
}

pub fn is_valid_spam_label(label: &str) -> bool {
    SPAM_LABEL_OPTIONS.contains(&label)
}

pub fn is_valid_list_type(list_type: &str) -> bool {
    matches!(list_type, LIST_TYPE_BLACKLIST | LIST_TYPE_WHITELIST)
}

pub fn default_list_type_for_label(label: &str) -> &'static str {
    if label == "not_spam" {
        LIST_TYPE_WHITELIST
    } else {
        LIST_TYPE_BLACKLIST
    }
}

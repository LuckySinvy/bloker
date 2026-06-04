use axum::{
    extract::FromRequestParts,
    http::{HeaderMap, StatusCode, header, request::Parts},
    response::{IntoResponse, Response},
};
use sha2::{Digest, Sha256};
use std::env;

pub const ADMIN_COOKIE_NAME: &str = "spam_admin_session";
const DEFAULT_ADMIN_PASSWORD: &str = "lingti-admin";

pub fn get_admin_password() -> String {
    env::var("ADMIN_PASSWORD").unwrap_or_else(|_| DEFAULT_ADMIN_PASSWORD.to_string())
}

pub fn build_session_token(password: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(password.as_bytes());
    format!("{:x}", hasher.finalize())
}

pub fn expected_session_token() -> String {
    build_session_token(&get_admin_password())
}

pub fn is_authenticated(parts: &Parts) -> bool {
    is_authenticated_headers(&parts.headers)
}

pub fn is_authenticated_headers(headers: &HeaderMap) -> bool {
    let expected = expected_session_token();
    headers
        .get(header::COOKIE)
        .and_then(|value| value.to_str().ok())
        .map(parse_cookie_header)
        .and_then(|cookies| cookies.get(ADMIN_COOKIE_NAME).cloned())
        .map(|token| token == expected)
        .unwrap_or(false)
}

pub fn build_login_cookie() -> String {
    format!(
        "{name}={value}; Path=/; HttpOnly; SameSite=Lax",
        name = ADMIN_COOKIE_NAME,
        value = expected_session_token()
    )
}

pub fn build_logout_cookie() -> String {
    format!(
        "{name}=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax",
        name = ADMIN_COOKIE_NAME
    )
}

pub struct AdminSession;

impl<S> FromRequestParts<S> for AdminSession
where
    S: Send + Sync,
{
    type Rejection = Response;

    async fn from_request_parts(parts: &mut Parts, _: &S) -> Result<Self, Self::Rejection> {
        if is_authenticated(parts) {
            return Ok(Self);
        }

        Err((StatusCode::SEE_OTHER, [(header::LOCATION, "/admin/login")]).into_response())
    }
}

pub fn parse_cookie_header(header_value: &str) -> std::collections::HashMap<String, String> {
    header_value
        .split(';')
        .filter_map(|item| {
            let mut parts = item.trim().splitn(2, '=');
            let key = parts.next()?.trim();
            let value = parts.next()?.trim();
            if key.is_empty() {
                None
            } else {
                Some((key.to_string(), value.to_string()))
            }
        })
        .collect()
}

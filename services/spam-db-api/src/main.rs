mod auth;
mod db;
mod domain;
mod entity;
mod routes;

use std::{env, net::SocketAddr, sync::Arc};

use axum::{
    Form, Router,
    extract::State,
    http::{HeaderMap, StatusCode, header},
    response::{Html, IntoResponse, Redirect},
    routing::{get, post, put},
};
use auth::{AdminSession, build_login_cookie, build_logout_cookie, get_admin_password, is_authenticated_headers};
use db::{AppState, init_database};
use routes::{
    ApiDoc, create_pattern_rule, create_spam_number, create_spam_report, delete_pattern_rule,
    delete_spam_number, delete_spam_report, get_app_sync_payload, get_health,
    get_spam_report_stats, list_pattern_rules, list_spam_numbers, list_spam_reports,
    lookup_spam_number, update_pattern_rule, update_spam_number, update_spam_report
};
use tower_http::{compression::CompressionLayer, cors::CorsLayer, trace::TraceLayer};
use tracing_subscriber::{EnvFilter, layer::SubscriberExt, util::SubscriberInitExt};
use utoipa::OpenApi;
use utoipa_swagger_ui::SwaggerUi;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    dotenvy::dotenv().ok();
    init_tracing();

    let database_url = env::var("DATABASE_URL")
        .unwrap_or_else(|_| "postgres://spam:spam@127.0.0.1:5432/spam_db".to_string());
    let bind = env::var("BIND_ADDRESS")
        .unwrap_or_else(|_| "127.0.0.1:8080".to_string());

    let db = init_database(&database_url).await?;
    let state = Arc::new(AppState { db });

    let app = Router::new()
        .route("/health", get(get_health))
        .route("/admin", get(admin_console))
        .route("/admin/login", get(admin_login_page).post(admin_login_submit))
        .route("/admin/logout", post(admin_logout))
        .route("/api/v1/spam-reports", post(create_spam_report).get(list_spam_reports))
        .route("/api/v1/spam-numbers", post(create_spam_number).get(list_spam_numbers))
        .route("/api/v1/spam-reports/stats", get(get_spam_report_stats))
        .route("/api/v1/app-sync", get(get_app_sync_payload))
        .route("/api/v1/spam-reports/lookup", get(lookup_spam_number))
        .route("/api/v1/spam-reports/{id}", put(update_spam_report).delete(delete_spam_report))
        .route("/api/v1/spam-numbers/{id}", put(update_spam_number).delete(delete_spam_number))
        .route("/api/v1/pattern-rules", post(create_pattern_rule).get(list_pattern_rules))
        .route("/api/v1/pattern-rules/{id}", put(update_pattern_rule).delete(delete_pattern_rule))
        .merge(SwaggerUi::new("/docs").url("/api-docs/openapi.json", ApiDoc::openapi()))
        .layer(CompressionLayer::new())
        .layer(CorsLayer::permissive())
        .layer(TraceLayer::new_for_http())
        .with_state(state);

    let addr: SocketAddr = bind.parse()?;
    tracing::info!("spam-db-api listening on {}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;

    Ok(())
}

fn init_tracing() {
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::fmt::layer()
                .with_target(false)
                .with_thread_names(true),
        )
        .with(EnvFilter::try_from_default_env().unwrap_or_else(|_| {
            EnvFilter::new("spam_db_api=debug,tower_http=info,axum=info")
        }))
        .init();
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };

    #[cfg(unix)]
    let terminate = async {
        use tokio::signal::unix::{SignalKind, signal};
        if let Ok(mut sigterm) = signal(SignalKind::terminate()) {
            sigterm.recv().await;
        }
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }

    tracing::info!("shutdown signal received");
}

async fn admin_console(State(_state): State<Arc<AppState>>, session: AdminSession) -> Html<&'static str> {
    let _ = session;
    Html(include_str!("admin_console.html"))
}

async fn admin_login_page(
    State(_state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> impl IntoResponse {
    if is_authenticated_headers(&headers) {
        return Redirect::to("/admin").into_response();
    }

    Html(include_str!("admin_login.html")).into_response()
}

#[derive(serde::Deserialize)]
struct AdminLoginForm {
    password: String,
}

async fn admin_login_submit(Form(payload): Form<AdminLoginForm>) -> impl IntoResponse {
    if payload.password == get_admin_password() {
        return (
            StatusCode::SEE_OTHER,
            [
                (header::SET_COOKIE, build_login_cookie()),
                (header::LOCATION, "/admin".to_string()),
            ],
        )
            .into_response();
    }

    (
        StatusCode::UNAUTHORIZED,
        Html(include_str!("admin_login_invalid.html")),
    )
        .into_response()
}

async fn admin_logout() -> impl IntoResponse {
    (
        StatusCode::SEE_OTHER,
        [
            (header::SET_COOKIE, build_logout_cookie()),
            (header::LOCATION, "/admin/login".to_string()),
        ],
    )
}

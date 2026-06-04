FROM rust:1.95-bookworm AS builder

WORKDIR /app

ENV CARGO_REGISTRIES_CRATES_IO_PROTOCOL=sparse
ENV CARGO_REGISTRIES_CRATES_IO_INDEX=https://rsproxy.cn/crates.io-index
ENV RUSTUP_DIST_SERVER=https://rsproxy.cn
ENV RUSTUP_UPDATE_ROOT=https://rsproxy.cn/rustup

COPY Cargo.toml Cargo.lock ./
COPY .cargo .cargo
COPY services/spam-db-api/Cargo.toml services/spam-db-api/Cargo.toml
COPY services/spam-db-api/src services/spam-db-api/src

RUN cargo build --release -p spam-db-api

FROM debian:bookworm-slim

RUN sed -i 's|deb.debian.org|mirrors.tencentyun.com|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates libpq5 libssl3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /app/target/release/spam-db-api /usr/local/bin/spam-db-api

ENV BIND_ADDRESS=0.0.0.0:8080
ENV DATABASE_URL=postgres://spam:spam@127.0.0.1:5432/spam_db

EXPOSE 8080

CMD ["spam-db-api"]

# Builds run on the build host's architecture; scriptc cross-compiles the
# server to a static musl binary for the target, so nothing runs emulated
FROM --platform=$BUILDPLATFORM node:24-alpine AS builder
ARG TARGETARCH
RUN apk add --no-cache zig
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
# Runtime pack for the target, matching the installed compiler version
RUN case "$TARGETARCH" in \
      amd64) echo "x86_64 x64" ;; \
      arm64) echo "aarch64 arm64" ;; \
      *) echo "Unsupported architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac > /tmp/arch \
 && read -r _ pack < /tmp/arch \
 && npm install --no-save "@scriptc/runtime-linux-$pack-musl@$(node -p 'require("scriptc/package.json").version')"
COPY . .
RUN npx vite build \
 && read -r triple _ < /tmp/arch \
 && SCRIPTC_TARGET="$triple-linux-musl" npm run build:server

FROM alpine:3 AS certs
RUN apk add --no-cache ca-certificates

FROM scratch
COPY --from=certs /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /app/dist/server /server
COPY --from=builder /app/dist/ui /ui
ENV PORT=3000 STATIC_DIR=/ui
USER 65534:65534
EXPOSE 3000
ENTRYPOINT ["/server"]

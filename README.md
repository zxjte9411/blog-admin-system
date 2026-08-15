# Blog Admin System

一個以 Angular 管理介面、Spring Boot REST API 與 PostgreSQL 組成的部落格管理系統。Liquibase 負責資料庫 schema 與 migration；Compose 不使用資料庫 init script。Mailpit 接收本機開發郵件。

本文件涵蓋專案的技術版本、建置與執行方式、API 文件及登入測試帳號設定；設計取捨與額外功能整理於[繳交補充說明](docs/SUBMISSION.md)。

## 技術版本

- Angular 21（Angular CLI 21.2.20）、TypeScript 5.9、Node.js 24 LTS、npm 11（package manager 固定為 11.17.0）
- Spring Boot 3.5.16、Springdoc OpenAPI 2.8.13、Java 25、Maven Wrapper
- PostgreSQL 18.4、Liquibase
- Docker Compose、Mailpit v1.21

## 服務網址

啟動 Compose 後，可從下列網址存取服務：

- 前端：<http://localhost:4200>
- 後端：<http://localhost:8080>
- Swagger UI：<http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- Mailpit：<http://localhost:8025>

Swagger UI 與 OpenAPI JSON 可直接查看後端 REST API 的路徑、請求欄位與回應格式；服務啟動後即可開啟上述連結。

Compose 的對外 port 可用 `.env` 覆寫：`POSTGRES_PORT`、`BACKEND_PORT`、`FRONTEND_PORT`、`MAILPIT_SMTP_PORT`、`MAILPIT_HTTP_PORT`。

## 建置與啟動開發環境

先在 VS Code 執行 **Reopen in Container**，或在已安裝 Docker、Node.js 24 與 Java 25 的環境執行：

```bash
cp .env.example .env
${EDITOR:-vi} .env
docker compose config
docker compose up -d --build --wait
docker compose ps
```

`.env` 至少要設定：

- `POSTGRES_PASSWORD`
- `APP_SECURITY_JWT_SECRET`：隨機產生，至少 32 bytes
- `APP_BOOTSTRAP_ADMIN_EMAIL`
- `APP_BOOTSTRAP_ADMIN_PASSWORD`：本機自行設定，至少 8 字元，請勿提交 `.env`

## 正式部署

正式環境使用 GHCR 的 backend/frontend public images，production Compose 以 edge Nginx 統一處理 TLS、frontend 與 `/api/`，不啟動 Mailpit，也不對外發佈 backend 或 PostgreSQL。前置條件：一台可由 SSH 管理的 Linux VM、Docker Compose plugin、Git，以及 Cloudflare DNS 已將 `nhb.pp.ua` 設為 proxied；GHCR 內本專案的兩個 image 必須設為 Public。

一次性 VM 安裝：

```bash
sudo mkdir -p /opt/blog-admin-system /etc/ssl/cloudflare
sudo chown "$USER":"$USER" /opt/blog-admin-system
git clone https://github.com/zxjte9411/blog-admin-system.git /opt/blog-admin-system
cd /opt/blog-admin-system
mkdir -p backups
cp .env.production.example .env
chmod 600 .env
${EDITOR:-vi} .env
sudo usermod -aG docker "$USER"
# 重新登入 VM 讓 docker 群組生效
```

將 Cloudflare Origin Certificate 與私密金鑰手動放在 `/etc/ssl/cloudflare/nhb.pp.ua.pem`、`/etc/ssl/cloudflare/nhb.pp.ua.key`，並執行 `sudo chown root:root /etc/ssl/cloudflare/nhb.pp.ua.* && sudo chmod 600 /etc/ssl/cloudflare/nhb.pp.ua.key`。`.env` 填入 production 的 PostgreSQL 密碼、至少 32 bytes 的 JWT secret、Supabase issuer/JWKS/URL/publishable key、bootstrap admin、`GMAIL_SMTP_USERNAME`、Gmail App Password 與 `APP_MAIL_FROM`；不要填入或提交 Supabase Service Role Key。`.env.production.example` 的兩個 digest 只是顯然非真實的格式占位值，CI 會以本次 build 的 immutable digest 覆寫它們。

edge Nginx 由 Docker Compose 啟動，唯一本機公開 port 是 `80:80` 與 `443:443`；frontend、backend、PostgreSQL 不公開 host port。首次切換前先執行 `sudo systemctl disable --now nginx` 釋放 80/443，再啟動 edge container；確認 edge healthcheck 後才可移除 host Nginx 套件。

GitHub Repository secrets 設定 `VM_HOST`、`VM_USER`、`SSH_PRIVATE_KEY` 三個值；`production` Environment 可選擇性用於部署核准規則。另需設定 Repository variable `VM_SSH_KNOWN_HOSTS`，內容是受信任取得的 VM `host key` 完整 known_hosts 行（例如 `host ssh-ed25519 AAAA...`），不可在 CI 使用 `ssh-keyscan`。main push 在 `verify`、`frontend-verify`、`compose-smoke` 成功後建置並推送兩個 commit-SHA tag image，透過 runner 內建 OpenSSH 將 digest 傳給 VM，再更新 public repo 到本次 SHA 執行部署。部署前請在 Cloudflare SSL/TLS 設為 **Full (strict)**，並將 firewall 只開放 Cloudflare IP 的 TCP 80/443，以及管理 IP 的 TCP 22；不要開放 backend、PostgreSQL 或 Compose 的 8080 給公網。

`VM_USER` 必須能直接操作 Docker；加入 `docker` 群組並重新登入後，請確認 `docker ps` 不需 `sudo` 即可執行。

Supabase 與 Google callback 設定不同：Supabase Auth 的 Site URL 設為 `https://nhb.pp.ua`，Redirect URLs 加入 `https://nhb.pp.ua/login` 與 `https://nhb.pp.ua/invite*`；Google Cloud OAuth client 的 Authorized redirect URI 則填 Supabase 提供的 `https://<project-ref>.supabase.co/auth/v1/callback`，不是 frontend 或 API URL。正式環境沒有 API 子網域，所有 API 皆經 `https://nhb.pp.ua/api/` 轉送。Gmail SMTP 必須使用開啟 2-Step Verification 帳號產生的 App Password，不可使用一般 Gmail 密碼。

部署與驗證：

```bash
cd /opt/blog-admin-system
docker compose --env-file .env -f compose.production.yaml config
./deploy/deploy.sh
docker compose --env-file .env -f compose.production.yaml ps
curl --fail https://nhb.pp.ua/
docker compose --env-file .env -f compose.production.yaml exec -T edge wget --no-check-certificate --spider --quiet https://127.0.0.1/
```

`deploy/deploy.sh` 會在已有運行中的 db container 時先將 timestamped `pg_dump` 存到 VM 本機 `backups/`，並只保留最近 7 份，作為 VM 本機回復點；若 production data volume 存在但 db 未運行，會拒絕部署而不略過備份。首次部署尚未建立 data volume 時才會跳過備份，備份不會上傳外部服務。

Google 登入相關環境變數區分如下：

- **後端驗證設定**（Docker Compose 啟動時必要，供 Spring Boot 驗證 Supabase JWT 簽名與 issuer；本機若不連接真實 Supabase 亦須依 `.env.example` 填寫佔位 URL 供 Compose 檢查）：
  - `SUPABASE_JWT_ISSUER`：Supabase 發行者 URL（如 `https://<project-ref>.supabase.co/auth/v1`）
  - `SUPABASE_JWKS_URL`：Supabase JWKS 端點（如 `https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json`）
- **前端公開設定**（瀏覽器可見，選填，Compose 啟動容器時產生 `config.js`）：
  - `SUPABASE_URL`：Supabase 專案 URL
  - `SUPABASE_PUBLISHABLE_KEY`：Supabase 公開 Publishable Key（**切勿使用 Service Role Key 或任何 Secret Key**）

**Frontend Fallback 行為**：若前端未設定 `SUPABASE_URL` 或 `SUPABASE_PUBLISHABLE_KEY`，介面會自動隱藏 Google 登入按鈕與 Google 邀請兌換入口，原生 Email/Password 登入與註冊仍可完全正常運作。

應用程式首次啟動時，只有在 `APP_BOOTSTRAP_ADMIN_EMAIL` 對應的帳號不存在時，才會建立 bootstrap admin。之後重啟不會覆寫既有帳號或密碼。登入測試帳號使用 `.env` 內的 `APP_BOOTSTRAP_ADMIN_EMAIL` 與 `APP_BOOTSTRAP_ADMIN_PASSWORD`，README 不提供固定密碼。

停止服務並保留資料：

```bash
docker compose down
```

刪除 PostgreSQL volume：

```bash
docker compose down --volumes
```

## 本機建置與測試

Backend：

```bash
cd backend
./mvnw --batch-mode verify
cd ..
```

Frontend：

```bash
cd frontend
npm ci
npm run lint
npm run lint:format
npm run typecheck
npm test
npm run build
cd ..
```

Frontend 的 `npm test` 使用專案內的 `scripts/test.mjs`。Backend 的整合測試使用 Testcontainers PostgreSQL，因此需要可用的 Docker daemon。JaCoCo HTML 報告會輸出到 `backend/target/site/jacoco/`。

## HTTP smoke test

先啟動 Compose，再執行：

```bash
bash -n scripts/compose-smoke.sh
set -a; . ./.env; set +a
API_BASE=http://localhost:8080 FRONTEND_BASE=http://localhost:4200 bash scripts/compose-smoke.sh
```

這個腳本會檢查 health、前端、bootstrap admin 登入、帶 tag 的文章建立與發布、匿名公開文章與 tag，以及 draft、deleted、restore 的公開狀態。從 dev container 呼叫主機上的服務時，將兩個 base URL 改成 `host.docker.internal`：

```bash
set -a; . ./.env; set +a
API_BASE=http://host.docker.internal:8080 FRONTEND_BASE=http://host.docker.internal:4200 bash scripts/compose-smoke.sh
```

## CI 對應指令

CI 不需要先啟動本機服務。以下指令涵蓋 backend verify、frontend lint/test/build，以及 Compose 設定檢查：

```bash
docker compose config
(cd backend && ./mvnw --batch-mode verify)
(cd frontend && npm ci && npm run lint && npm test && npm run build)
```

需要驗證前端格式與型別時，再執行本機完整序列中的 `npm run lint:format` 與 `npm run typecheck`。

## 登入測試帳號

Compose 啟動時會依 `.env` 的 `APP_BOOTSTRAP_ADMIN_EMAIL` 與 `APP_BOOTSTRAP_ADMIN_PASSWORD` 建立 bootstrap Admin。兩者就是本機登入管理後台時使用的 Email 與 Password；Password 請自行設定為至少 8 字元的非共用密碼，README 不提供固定密碼。

只有在該 Email 尚不存在時才會建立 bootstrap Admin；既有 PostgreSQL volume 重啟後不會以環境變數覆寫既有 User 或 Password。若要重新建立測試資料，可使用前述 `docker compose down --volumes`，但這會刪除 PostgreSQL volume 中的資料。

## 功能

系統提供帳號登入（支援 Email/Password 與 Google 登入）與由 Admin 發出之使用者邀請（支援密碼與 Google 帳號兌換，受邀者兌換後為 Author 角色）。登入後，使用者可管理文章的新增、編輯、刪除、搜尋與分頁，並切換草稿與發布狀態。文章支援標籤；訪客可瀏覽已發布的公開文章與標籤。

系統也提供帳戶資料、session 管理、使用者角色與啟用狀態管理，以及 email 變更、密碼變更、忘記密碼與密碼重設流程。相關通知會送到 Mailpit，方便在本機檢查郵件內容。

認證流程將 access token 放在瀏覽器 `localStorage`，refresh token 放在 `HttpOnly` cookie。API 使用 Bearer access token；refresh token 由 `/api/v1/auth/refresh` 輪替，登出時撤銷 session。

## 設計與額外功能

- Angular 只負責管理介面與 API 呼叫；Spring Boot 集中處理驗證、授權與領域規則。
- Liquibase 是 schema owner，migration 依序管理資料表變更。
- 公開 API 位於 `/api/v1/public/**`，並只回傳已發布且未刪除的文章。
- bootstrap Admin 透過環境變數注入，避免把開發密碼寫進原始碼或文件。
- 其他設計理念、權限邊界、測試與額外功能請見[繳交補充說明](docs/SUBMISSION.md)。
- 核心資料模型、完整系統流程與應用層關係請見[系統圖表](docs/diagrams/)（ERD、SSD、Class Diagram）。

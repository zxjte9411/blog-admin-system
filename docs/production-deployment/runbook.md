# Production Deployment Runbook

執行前先確認目前 branch、目標 commit 與 VM 狀態。所有 `<...>` 都是待填值，不是可直接使用的 secret。不要把終端機輸出、`.env`、SSH key、OAuth client secret、App Password 或憑證貼到 issue、PR 或聊天紀錄。

## 0. 安全前提

- VM：GCP instance `rtb`，zone `us-central1-f`。
- application secrets 只存在 VM `/opt/blog-admin-system/.env`。
- 暫時 gcloud project SSH key 已移除；長期 instance deploy key 保留。
- GCP firewall 尚未收尾，必須限制 SSH 與 8080。這兩個規則完成前，不要把 VM 視為已完成的 production 網路隔離。
- 不要用 `docker compose down --volumes`，除非你明確要刪 production data。

## 1. 初次 VM setup

### 1.1 安裝與基本檢查

在 VM 依作業系統官方文件安裝 Docker Engine 與 Compose v2 plugin。這個專案使用 `docker compose`，不是舊的 `docker-compose` 指令。

```sh
docker version
docker compose version
```

若 `docker compose version` 失敗，先安裝 plugin，再繼續。不要用替代指令繞過版本問題。

### 1.2 取得 repository

用長期 instance deploy key 取得 repository，建立固定部署目錄。不要把 private key 放進 repository。

```sh
sudo install -d -m 755 /opt/blog-admin-system
sudo chown "$(id -un)":"$(id -gn)" /opt/blog-admin-system
git clone <repository-url> /opt/blog-admin-system
cd /opt/blog-admin-system
```

如果目錄已有 checkout，改用 `git fetch`，不要在未確認狀態前覆蓋 `.env` 或 backup。

### 1.3 準備 edge 憑證與 port

將 Cloudflare Origin Certificate 與 private key 以檔案形式放在：

```text
/etc/ssl/cloudflare/nhb.pp.ua.pem
/etc/ssl/cloudflare/nhb.pp.ua.key
```

限制 key 的讀取權限，只讓 Docker/啟動流程在必要範圍內讀取。不要把憑證內容寫入本文件或 shell history。

確認 host Nginx 或其他程序沒有佔用 80、443；edge container 需要直接綁定這兩個 port。

```sh
sudo ss -ltnp | grep -E ':(80|443)\\b' || true
```

若 host Nginx 佔用 port，停止並停用它，或先完成明確的遷移；不要讓兩個 edge 同時搶 port。

## 2. Secret placement

### 2.1 建立 VM `.env`

以 [`../../.env.production.example`](../../.env.production.example) 為欄位清單，在 VM 建立 `.env`。範例裡的 digest 是故意的 placeholder，不能拿來部署。

```sh
cd /opt/blog-admin-system
cp .env.production.example .env
chmod 600 .env
```

填入以下欄位的正式值，但不要把值寫入 git 或本文件：

- PostgreSQL database、user、password。
- 至少 32 bytes 的隨機 `APP_SECURITY_JWT_SECRET`。
- 既有 Supabase project 的 issuer、JWKS URL、URL 與 publishable key。
- bootstrap admin email 與 password。
- Gmail SMTP username、App Password 與寄件地址。
- `APP_FRONTEND_BASE_URL` 使用正式網域。

`SUPABASE_URL` 與 `SUPABASE_PUBLISHABLE_KEY` 會提供給瀏覽器使用；這兩個值也只能是 URL 與 publishable key，不可混入 service role key 或其他 secret。公開 `config.js` 同樣遵守這個界線。

### 2.2 驗證 Compose 設定

不要把完整輸出保存或貼出，因為 Compose 展開後可能包含 secret。只做本機驗證：

```sh
docker compose --env-file .env -f compose.production.yaml config --quiet
```

確認 `.env` 存在、權限為 `600`、所有正式欄位已填寫，且不要把 `.env` 加入 commit。

## 3. Cloudflare、Supabase、Google 與 Gmail

### 3.1 Cloudflare

1. DNS 將正式網域指向 VM，並開啟 proxy。
2. SSL/TLS encryption mode 設為 `Full (strict)`。
3. 確認 origin certificate 的名稱、檔案路徑與有效期限；不要使用 `Flexible`。
4. 以瀏覽器或 `curl -fsSI https://nhb.pp.ua/` 檢查外部 HTTPS。

**警告：**目前 GCP firewall 尚待限制 SSH 與 8080。不要只依賴 Cloudflare proxy 保護 origin；完成 Cloudflare-only 或明確 IP allowlist 前，先限制 GCP ingress。allowlist 變更前要確認 Cloudflare 官方 IP ranges 與管理者 SSH 來源，避免把自己鎖在 VM 外。

### 3.2 Supabase existing project

1. 使用既有 Supabase project，不建立 custom auth domain。
2. 在 Supabase Auth URL 設定填入正式 Site URL。
3. 加入前端 OAuth redirect URL：`https://nhb.pp.ua/login`。
4. 從 Supabase dashboard 複製該 project 顯示的 Google provider callback URL，提供給 Google Cloud OAuth 設定使用。
5. 將 project URL、issuer、JWKS URL 與 publishable key 分別填入 VM `.env` 對應欄位。不要貼出完整 key。

### 3.3 Google OAuth

在 Google Cloud 對應 OAuth client 的 Authorized redirect URI 填入 Supabase dashboard 顯示的 callback URL，不要自行猜測 project ref 或 callback path。確認 OAuth consent screen 的正式網域與 redirect URI 都使用 HTTPS 正式網域。

選取 Google 帳號後若頁面持續 loading，先檢查 Supabase URL runtime config、Supabase redirect 設定與 Google callback 是否完全一致，再重試。

### 3.4 Gmail SMTP

1. 在寄件 Gmail 帳號啟用兩步驟驗證。
2. 建立 Gmail App Password。
3. 將帳號放入 `GMAIL_SMTP_USERNAME`，將 App Password 放入 `GMAIL_SMTP_APP_PASSWORD`。
4. `APP_MAIL_FROM` 使用允許寄件的地址。

不要使用 Gmail 一般登入密碼，也不要把 App Password 放進 GitHub secrets、workflow log 或 issue。

## 4. 正常 deploy

### 4.1 事前檢查

- 確認 change 已 merge 到 `main`。
- 確認 GitHub Actions production environment 的 SSH private key、VM host/user 設定正確。
- 確認 `VM_SSH_KNOWN_HOSTS` 是由可信管理者取得的正確 host key；不要以關閉 strict checking 代替。
- 確認 GHCR package visibility。現在 VM deploy 未登入 GHCR，若 package 是 private，pull 會失敗；若設為 public，請接受 image 可被公開下載的風險。

### 4.2 由 GitHub Actions 部署

push 到 `main` 後，[`.github/workflows/backend.yml`](../../.github/workflows/backend.yml) 依序：

1. 執行 backend、frontend 與 Compose smoke check。
2. 將兩個 image 以 commit SHA tag 推送到 GHCR，取得 immutable digest。
3. 以 built-in SSH、`StrictHostKeyChecking=yes` 與 `VM_SSH_KNOWN_HOSTS` 連到 VM。
4. 在 VM checkout 目標 SHA，確認 `.env` 存在並設為 `600`。
5. 將 digest 傳給 [`deploy/deploy.sh`](../../deploy/deploy.sh)。腳本會在 db container 正常運作時先 `pg_dump`，保留最新 7 份，再 pull image 並以 `--wait-timeout 600` 啟動。

不要把 production app secrets 放到 GitHub Actions；workflow 只應持有 VM SSH 連線所需設定。GHCR image digest 不是 secret，但必須使用 CI 產生的真實 digest。

### 4.3 deploy 後健康檢查

```sh
cd /opt/blog-admin-system
docker compose --env-file .env -f compose.production.yaml ps
docker compose --env-file .env -f compose.production.yaml logs --tail=100 backend
docker compose --env-file .env -f compose.production.yaml exec -T backend \
  curl --fail --silent --show-error http://localhost:8080/actuator/health
curl --fail --silent --show-error --head https://nhb.pp.ua/
```

再從瀏覽器確認登入、Google OAuth、文章 API 與寄信流程。backend 初次 cold start 可能需要數分鐘；Compose 已設定 backend `start_period: 8m`，整體等待上限為 600 秒，不要在尚未達到上限前反覆啟動多個 deploy。

## 5. Rollback

先保留現場資訊：GitHub Actions run URL、目標 SHA、兩個 image digest、Compose 狀態與相關 logs。不要刪除 Postgres volume。

若已知上一個可用的 commit 與 image digest，可在 VM 使用既有 checkout 流程回到該 commit，再以真實 digest 執行部署：

```sh
cd /opt/blog-admin-system
git fetch --depth=1 origin <known-good-commit>
git checkout --force --detach <known-good-commit>
BACKEND_IMAGE_DIGEST='sha256:<known-backend-digest>' \
FRONTEND_IMAGE_DIGEST='sha256:<known-frontend-digest>' \
./deploy/deploy.sh
```

回滾後重做健康檢查，並確認 backup 目錄仍保留。若 deployment 被取消或前一個 deploy 仍在重建，先檢查 `docker compose ps` 與 logs，確認沒有另一個流程在操作同一個 VM，再重試；不要平行執行 deploy。

## 6. 資料保護提醒

- 正常 deploy 前會產生 backup，但要確認 backup 檔案真的出現在 `/opt/blog-admin-system/backups/`。
- named volume 與 SQL backup 都在同一台 VM，不是異地備份。
- PostgreSQL password 只會在空 volume 初次初始化時套用。保留 `.env` 中與現有資料庫一致的 password。
- 除非明確授權刪除 production data，否則不要執行 `docker compose down --volumes`，也不要任意刪除 named volume。

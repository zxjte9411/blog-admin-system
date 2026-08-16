# Production Deployment Decisions

## 部署鏈路：`main` → CI → GHCR digest → strict SSH → VM Compose

`main` push 先通過 backend、frontend 與 Compose smoke check，再由 GitHub Actions 建立並推送兩個 GHCR image。部署使用 build job 回傳的 immutable digest，不使用可變的 `latest`。deploy job 以 built-in SSH 指令搭配 `StrictHostKeyChecking=yes` 和 repository variable `VM_SSH_KNOWN_HOSTS` 連到 VM，checkout 同一個 commit，最後由 VM 的 `deploy/deploy.sh` 執行 Compose pull 與啟動。

這樣把「測試過的 commit」和「實際執行的 image」綁在一起，也讓 VM 上的 `.env` 與 repository 內容分開。workflow 的 production concurrency 設定為不取消進行中的部署，避免兩個 deploy 同時重建服務。

## 同網域 `/api`，不使用 API subdomain

正式網址讓前端與 API 共用 `https://nhb.pp.ua`，API 使用 `/api/` 路徑。edge Nginx 把 `/api/` 轉給 backend，其餘路徑轉給 frontend。

這個選擇省掉跨 origin 的 CORS、cookie 與 OAuth redirect 差異，也符合前端目前以同一個 origin 發出 API 請求的模型。之後若要拆 API subdomain，必須一併重新檢查 CORS、session、Cloudflare 與 OAuth 設定。

## Cloudflare proxy、Full (strict) 與 Origin Certificate

DNS 由 Cloudflare proxy 對外提供，origin 端使用 Cloudflare Origin Certificate。Cloudflare SSL/TLS 模式採 `Full (strict)`，讓 Cloudflare 到 Nginx 的連線也驗證 origin 憑證。

`Flexible` 會讓 Cloudflare 到 origin 使用 HTTP；origin Nginx 又把 HTTP redirect 到 HTTPS，兩者會形成 redirect loop。憑證檔與 key 只放在 VM 的 `/etc/ssl/cloudflare/`，不寫入 repository 或本文件。

## containerized edge Nginx，不使用 host Nginx

edge Nginx 跟 frontend、backend 一起由 Compose 管理，container 直接綁定 80、443。host Nginx 不應再佔用這兩個 port。單一入口讓設定、憑證掛載、health check 與部署生命週期保持在同一組 Compose 定義內。

## Supabase 使用既有 project，不新增 custom auth domain

正式環境沿用既有 Supabase project。backend 使用該 project 的 issuer 與 JWKS URL；frontend 的公開 runtime config 只提供 project URL 與 publishable key。這次不新增 Supabase custom auth domain，避免多一組 DNS、憑證與 callback 維護面。

Google OAuth 的 Site URL、redirect URL 與 provider callback 必須在既有 project 及 Google Cloud OAuth 設定中互相對應；實際 callback URL 以 Supabase dashboard 顯示值為準。

## Gmail App Password

backend 透過 Gmail SMTP 寄送驗證、邀請與密碼重設信。`GMAIL_SMTP_USERNAME` 使用寄件 Gmail 帳號，`GMAIL_SMTP_APP_PASSWORD` 使用該帳號產生的 App Password，不使用 Gmail 一般登入密碼。帳號需啟用適用的兩步驟驗證；App Password 只放在 VM `.env`。

## Secret placement

- production secrets 只放 VM `/opt/blog-admin-system/.env`，權限設為 `600`。
- repository secrets 只處理 VM SSH 連線所需設定；不要把 PostgreSQL、JWT、bootstrap admin、Gmail 或 Supabase secret 放進 GitHub Actions。
- deploy 使用的 known hosts 放在 `VM_SSH_KNOWN_HOSTS` repository variable，並維持 strict host key verification。
- frontend 的 public `config.js` 只放 Supabase URL 與 publishable key。publishable key 雖可公開，仍不可把 service role key、JWT secret 或其他私密憑證放進去。
- 本文件與範例檔只記錄變數名稱或 placeholder，不記錄任何 secret、private key、App Password、完整 publishable key、DB 密碼或憑證內容。

## Postgres local volume 與 7 deploy backups

Postgres 使用 `blog-admin-system-production_postgres_production_data` named local volume 保存資料。正常 deploy 前，`deploy/deploy.sh` 會從執行中的 db container 產生 SQL dump，存到 `/opt/blog-admin-system/backups/`，並保留最新 7 份。

這是部署前的短期復原點，不等於異地備份或災難復原方案。未來若要達到更高的可用性，應增加受保護的異地備份與定期還原演練。除非明確要刪除 production data，否則不要使用 `docker compose down --volumes`。

## 尚未完成：GCP firewall

GCP firewall 尚未收尾。必須限制 SSH 來源，並檢查 8080 是否仍可從不必要的來源存取。正式流量應經 Cloudflare；若要採 Cloudflare-only allowlist，先整理 Cloudflare IP ranges 並在 GCP firewall 套用，另保留管理者必要的 SSH allowlist。完成前不要宣稱網路邊界已鎖定。

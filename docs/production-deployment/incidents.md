# Production Incidents

格式固定為：問題 → 根因 → 解法 → 預防。這裡只記錄已知事實，不把 commit 擴寫成未驗證的功能描述。

## 1. Compose v2 plugin 缺失

**問題**：VM 無法執行 `docker compose`。

**根因**：Docker Compose v2 plugin 尚未安裝，環境只有不相容的舊指令或 Docker 基礎安裝。

**解法**：依 VM 作業系統官方文件安裝 Compose v2 plugin，並用 `docker compose version` 驗證；部署流程統一使用 `docker compose`。

**預防**：初次 VM setup 先檢查 `docker version` 與 `docker compose version`，不要等到 deploy 失敗才補裝。

## 2. host Nginx 與 edge port 80 衝突

**問題**：edge container 無法啟動，因為 port 80 已被占用。

**根因**：host Nginx 與 Compose 內的 edge Nginx 同時嘗試監聽 port 80。

**解法**：停止並停用 host Nginx，讓 containerized edge Nginx 成為唯一的 80、443 入口。

**預防**：初次 setup 先檢查 80、443 的 listener；維持 edge、憑證與 proxy 設定都由 `compose.production.yaml` 管理。

## 3. Cloudflare Flexible redirect loop

**問題**：瀏覽器在 HTTP 與 HTTPS 之間反覆 redirect。

**根因**：Cloudflare `Flexible` 讓 Cloudflare 到 origin 使用 HTTP，而 origin Nginx 將 HTTP redirect 到 HTTPS。

**解法**：Cloudflare SSL/TLS 改成 `Full (strict)`，並在 origin 安裝 Cloudflare Origin Certificate。

**預防**：每次修改 Cloudflare SSL/TLS 後，用外部 HTTPS 檢查首頁；不要使用 `Flexible`。

## 4. 初次 backend cold start 花費 421 秒

**問題**：第一次啟動 backend 很久，容易被誤判為 deploy 失敗。

**根因**：初次 backend cold start 實際花費 421 秒，超過一般短 timeout 的耐受範圍。

**解法**：Compose 將 backend health check 的 `start_period` 設為 8 分鐘；部署腳本將 `docker compose up` 的等待上限設為 600 秒。

**預防**：部署後等待健康檢查完成，再依 logs 判斷失敗。若超過 600 秒仍未 healthy，才進入診斷，不要連續啟動多個 deploy。

## 5. fake image digest placeholder 導致 `manifest unknown`

**問題**：Compose pull 回報 `manifest unknown`。

**根因**：把 `.env.production.example` 中的 fake digest placeholder 當成正式 digest 使用；GHCR 沒有對應 manifest。

**解法**：使用 GitHub Actions build-push job 回傳的真實 immutable digest，或使用已知可用 image 的真實 digest。placeholder 只供欄位示意。

**預防**：deploy 前檢查 `BACKEND_IMAGE_DIGEST` 與 `FRONTEND_IMAGE_DIGEST` 來自目標 build，禁止使用範例檔的 placeholder。

## 6. PostgreSQL password 僅首次初始化，造成登入失敗

**問題**：`.env` 中的 PostgreSQL password 與實際資料庫 password 不一致，backend 登入資料庫失敗。

**根因**：Postgres image 的 `POSTGRES_PASSWORD` 只在空 volume 初次初始化時建立帳號密碼。後來修改 `.env` 不會更新既有資料庫 password。

**解法**：經授權後重建 database。這次處理清除了資料，之後重新初始化並讓 `.env` 與資料庫狀態一致。

**預防**：把 VM `.env` 視為 production database 連線設定的來源，變更前先做 backup 並安排密碼輪替流程。除非明確授權刪除資料，否則不要使用 `docker compose down --volumes`。

## 7. GitHub Actions deploy 在 rebuild 期間被取消

**問題**：GitHub Actions deploy 在服務 rebuild 期間顯示 cancelled，VM 需要確認是否完成或停在中間狀態。

**根因**：部署執行期間 workflow 被取消，不能只看 runner 結果推斷 VM 狀態。

**解法**：先到 VM 檢查 `docker compose ps`、backend logs、edge health 與 image 狀態；確認沒有其他 deploy process 後，再重跑同一目標或回滾到已知可用 digest。

**預防**：workflow 的 production concurrency 使用 `cancel-in-progress: false`；部署操作維持可重跑，且每次正常 deploy 先建立 database backup。

## 8. Google OAuth 選帳號後持續 loading

**問題**：Google 選完帳號後，頁面停在 loading，沒有完成登入。

**根因**：Supabase URL runtime config 與 Google callback 設定尚未完成或互不一致。

**解法**：完成 Supabase URL config，以及 Supabase/Google 的 callback 設定後，OAuth 登入恢復正常。

**預防**：保留正式 `https://nhb.pp.ua/login` redirect URL，並以 Supabase dashboard 顯示的 callback URL 設定 Google Cloud OAuth；部署後實際走一次選帳號流程。

## 9. favicon 正常，但沒有實際 logo asset

**問題**：使用者預期網站有 icon/logo，但介面沒有實際 logo 圖檔。

**根因**：`favicon.ico` 可正常回應 `image/x-icon`，但 frontend 沒有獨立 logo 圖檔或 `<img>` 引用；頂端品牌目前只有文字。瀏覽器也可能快取舊 favicon。

**解法**：目前先記錄為 logo asset 缺口；需要時補上真正 logo 檔案與引用。若只是分頁 icon 未更新，先強制重新整理。

**預防**：部署驗證加入 favicon asset 檢查，並將 logo 是否存在視為獨立 UI 需求，不從 favicon HTTP 狀態推論。

## 實際相關 commits

以下只列出 Git 可查證的 commit short hash、subject 與變更範圍，不替 commit 虛構未記錄的內容：

| Commit | Subject | 可查證的變更範圍 |
| --- | --- | --- |
| `d46b740` | `ci: add production deployment` | 新增 production env 範例、workflow、Compose、deploy script、edge Nginx 設定與 README 內容。 |
| `bf0ae8e` | `chore: ignore local secrets` | 修改 `.gitignore`。 |
| `c88fd8b` | `fix: allow production cold start` | 修改 `deploy/deploy.sh`。 |
| `a79ea93` | `fix: allow backend health warmup` | 修改 `compose.production.yaml`。 |

這四個 hash 是事故與部署脈絡的一部分；要了解精確 diff，直接查 Git commit，不要從本文件推導額外功能。

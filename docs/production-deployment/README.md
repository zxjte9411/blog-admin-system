# Production Deployment

這組文件記錄目前的正式環境部署方式、做過的取捨，以及事故處理筆記。內容寫給未來的自己；執行前仍要以 repository 內的設定檔為準。

## 文件索引

- [decisions.md](decisions.md)：架構與營運決策，以及每個決策的理由。
- [runbook.md](runbook.md)：初次建置、設定外部服務、部署、回滾與健康檢查。
- [incidents.md](incidents.md)：已發生問題的根因、處理方式與預防措施。

## 目前架構摘要

1. `main` push 觸發 [`.github/workflows/backend.yml`](../../.github/workflows/backend.yml)。workflow 先跑 backend、frontend 與 Compose smoke check。
2. CI 將 backend、frontend image 推送到 GHCR，取得 immutable digest。
3. production deploy job 使用 strict SSH、固定 known hosts，連到 GCP VM `rtb`，再執行 VM 上的部署腳本。
4. VM 以 [`compose.production.yaml`](../../compose.production.yaml) 執行四個服務：containerized edge Nginx、frontend、backend、Postgres。
5. edge Nginx 讓同一個網域承載前端與 `/api`；Cloudflare 代理外部流量，origin 使用 Cloudflare Origin Certificate。
6. Postgres 使用 named local volume。每次正常 deploy 前，[`deploy/deploy.sh`](../../deploy/deploy.sh) 會建立 SQL backup，最多保留 7 份。

## 目前狀態

- GCP instance：`rtb`，zone：`us-central1-f`。
- production application secrets 只放在 VM 的 `.env`，該檔案不得提交。
- 暫時使用的 gcloud project SSH key 已移除；長期 instance deploy key 保留。
- GCP firewall 尚未收尾：仍需限制 SSH 與 8080 的來源。不要把「服務目前可連線」當成 firewall 已完成。
- GHCR image 若維持 public visibility，VM 才能在目前不登入 registry 的流程中 pull；這是有意識的曝光取捨，需持續確認。

## 相關檔案

- [`compose.production.yaml`](../../compose.production.yaml)
- [`deploy/deploy.sh`](../../deploy/deploy.sh)
- [`deploy/edge-nginx/nginx.conf`](../../deploy/edge-nginx/nginx.conf)
- [`.env.production.example`](../../.env.production.example)
- [`.github/workflows/backend.yml`](../../.github/workflows/backend.yml)

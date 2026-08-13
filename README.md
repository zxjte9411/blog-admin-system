# Blog Admin System

Blog Admin System 的根目錄開發與 CI 交付設定。

## 技術基線

- Java 25 LTS
- Node.js 24 LTS
- PostgreSQL 18.4
- Docker Compose
- Dev Container

## 開啟開發環境

1. 以 VS Code 的 **Reopen in Container** 開啟此資料夾。
2. 在容器終端機確認工具：

   ```bash
   java --version
   node --version
   git --version
   docker --version
   docker compose version
   python3 --version
   uv --version
   ```

## 本機開發

Compose 不硬編碼密碼或 JWT secret；先複製範例並填入必要值：

```bash
cp .env.example .env
${EDITOR:-vi} .env
docker compose config
docker compose up -d --build --wait
docker compose ps
```

Compose 服務為 PostgreSQL、Spring Boot backend、Angular frontend 與 Mailpit。
backend 僅在 db healthy 後啟動，frontend 僅在 backend healthy 後啟動。資料庫 schema
只由 backend 的 Liquibase 設定與 migration 管理，不使用 Compose init script。

停止資料庫：

```bash
docker compose down
```

移除資料庫資料：

```bash
docker compose down --volumes
```

## 驗證

### HTTP smoke

```bash
bash -n scripts/compose-smoke.sh
set -a; . ./.env; set +a
API_BASE=http://localhost:8080 FRONTEND_BASE=http://localhost:4200 bash scripts/compose-smoke.sh
```

腳本會驗證 health、frontend、Bootstrap Admin 登入、帶 Tag 的 Article 建立/發布、
匿名 Public Article/Tag、Draft/Deleted 隱藏，以及 restore 後重新公開。可用 `API_BASE`
與 `FRONTEND_BASE` 覆寫預設 API 與前端位址。API 路由及 DTO 可參考 backend controller；
公開 API 位於 `/api/v1/public/**`。
在 dev container 執行 Compose smoke 時，請使用 `API_BASE=http://host.docker.internal:8080`
與 `FRONTEND_BASE=http://host.docker.internal:4200`；一般主機保留預設 localhost。兩者都要先
載入 `.env`，例如：

```bash
set -a; . ./.env; set +a
API_BASE=http://host.docker.internal:8080 FRONTEND_BASE=http://host.docker.internal:4200 bash scripts/compose-smoke.sh
```

API 文件：`http://localhost:8080/swagger-ui/index.html`（OpenAPI JSON：
`http://localhost:8080/v3/api-docs`）。CI 的 smoke 輸出與 Compose logs 位於
GitHub Actions 的 `compose-smoke-diagnostics` artifact；JaCoCo HTML 位於
`jacoco-report` artifact 的 `backend/target/site/jacoco/`。預設分支的 Pages URL 可依
GitHub 標準格式使用 `https://<owner>.github.io/<repository>/`（實際網址以 repository
設定為準）。

```bash
docker compose config
(cd backend && ./mvnw --batch-mode verify)
(cd frontend && npm ci && npm run lint && npm test && npm run build)
```

GitHub Actions 會在 backend 使用 Java 25 執行 Maven verify，在 frontend 使用 Node.js
24 執行上述 npm 驗證；這些 CI 工作不需啟動本機服務。預設分支的 backend 驗證成功後，
會將 JaCoCo HTML 報告發布至 GitHub Pages。
CI 亦會執行 Compose smoke，並保存 smoke 輸出與 Compose logs；JaCoCo artifact 與預設
分支的 GitHub Pages 提供覆蓋率證據。

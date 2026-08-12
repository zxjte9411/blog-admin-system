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

設定預設值已內建於 `compose.yaml`；需要覆寫時先複製範例檔：

```bash
cp .env.example .env
docker compose up -d
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

```bash
docker compose config
(cd backend && ./mvnw --batch-mode verify)
(cd frontend && npm ci && npm run lint && npm test && npm run build)
```

GitHub Actions 會在 backend 使用 Java 25 執行 Maven verify，在 frontend 使用 Node.js
24 執行上述 npm 驗證；這些 CI 工作不需啟動本機服務。預設分支的 backend 驗證成功後，
會將 JaCoCo HTML 報告發布至 GitHub Pages。

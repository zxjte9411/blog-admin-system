# Backend 指引

- 使用 Java 25 與 Spring Boot；所有 Maven 命令只用 `./mvnw`。
- 核心完整驗證：
  ```bash
  ./mvnw --batch-mode verify
  ```
  此命令包含 Spotless、測試與 JaCoCo 報告。
- Testcontainers 的 PostgreSQL 整合測試需要可用的 Docker daemon。
- JaCoCo HTML 報告位於 `backend/target/site/jacoco/`（在此目錄執行時為 `target/site/jacoco/`）。
- 資料庫 schema 由 Liquibase 及其 migration 管理；不要加入 Compose init script 或其他 schema owner。
- 只有服務本身需要 PostgreSQL、Mailpit 等依賴時，才啟動根目錄 Compose；先在根目錄執行 `docker compose config`。

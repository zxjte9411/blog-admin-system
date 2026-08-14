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

### Java 命名與可讀性

- Injected collaborator 以型別／角色命名，例如 `UserRepository` → `userRepository`；不得以 `users`、`articles`、`tags` 等 entity collection plural 命名。
- Collection local variable 使用自然複數，例如 `List<User> users`。
- Production code 的重要參數與 local variable 使用清楚的 domain 名稱；短 lambda 參數可例外。
- 使用明確 single-class import；不要使用 wildcard import 或 inline fully-qualified class name。

## Lombok

- Spring bean 若只是 `final` constructor injection，優先使用 `@RequiredArgsConstructor`；constructor 有 validation、normalization、`@Value`、defensive copy 或其他語意時保留 explicit constructor。
- Java `record` 保留，不為 Lombok 改回 POJO。
- Builder 不得繞過 Jackson/custom setter 的 normalization、default 或其他 construction semantics。

## JPA Entity 與 domain encapsulation

- Entity 預設使用 `@Getter` 與 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`。
- Entity 禁止 `@Data`，預設禁止 class-level public `@Setter`；允許 mutation 的個別欄位才使用 field-level setter。
- Domain state transition 保留 explicit method，不以 generated setter 取代。
- `@AllArgsConstructor` 僅限所有欄位本身就構成完整合法 initial state。
- Entity Builder 限制在合法 creation constructor/factory，不暴露 persistence、identity 或 lifecycle/internal state。
- 不得無條件加入 `@EqualsAndHashCode` 或 `@ToString`，避免改變 identity semantics 或觸發 lazy association traversal。
- Style/Lombok refactor 不得改變 transaction、locking、SQL/query、Jackson/JSON contract、JPA behavior 或 domain invariant。

## 驗證

- Java/Lombok/naming 修改後執行：
  ```bash
  ./mvnw --batch-mode verify
  ```
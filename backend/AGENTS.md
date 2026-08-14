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

## Java 命名與可讀性

* Injected collaborator 的欄位與建構子參數名稱應反映其型別角色，不以所管理資料的複數名詞代稱。

  * `UserRepository` → `userRepository`
  * `ArticleRepository` → `articleRepository`
  * `TagRepository` → `tagRepository`
  * `RefreshSessionRepository` → `refreshSessionRepository`
  * `PasswordEncoder` → `passwordEncoder`
  * `AccountService` → `accountService`
* Repository、Service、Encoder、Publisher 等單一 collaborator 不得命名為 `users`、`articles`、`tags`、`sessions`、`passwords`、`service` 等容易與資料集合混淆的名稱。
* Collection local variable 使用自然複數，例如 `List<User> users`、`Set<Tag> tags`。
* Production method parameter 與重要 local variable 使用完整 domain 名稱；避免 `u`、`a`、`t`、`c`、`p`、`n` 等單字母或模糊縮寫。短 lambda 參數可例外。
* Java import 預設使用明確 single-class import，不使用 inline fully-qualified class name。只有既有 formatter/style 明確要求時才使用 wildcard import。

## Lombok

* 優先使用 Lombok 消除沒有 domain 意義的 boilerplate，但不得以便利性破壞 encapsulation 或 construction semantics。
* Spring `@Service`、`@Component`、`@RestController` 等只有單純 `final` constructor injection 時，優先使用 `@RequiredArgsConstructor`。
* Constructor 若包含 `@Value`、validation、normalization、computed state、defensive copy 或其他特殊語意，保留 explicit constructor。
* Java `record` 已足夠簡潔時保留 record，不為了使用 Lombok 改回 POJO。
* `@Builder` 只在參數較多、optional 欄位較多或 fixture readability 明顯改善時使用；簡單兩三欄位物件不必強制 Builder。
* 若 DTO 有 `@JsonSetter`、custom setter、trim、normalization、default 或其他 transformation，Builder 必須產生與 Jackson 相同的最終狀態。不得讓 Builder 繞過既有 normalization。

## JPA Entity 與 Lombok

* JPA Entity 預設使用：

  * `@Getter`
  * `@NoArgsConstructor(access = AccessLevel.PROTECTED)`
* JPA Entity 禁止使用 `@Data`。
* JPA Entity 預設禁止 class-level public `@Setter`。只有確實允許外部 mutation 的欄位可使用 field-level `@Setter`；identity、version、lifecycle state 不得暴露 public setter。
* 不得以 generated setter 取代具有 domain 語意的 mutation method，例如 `verify()`、`use()`、`revoke()`、`changeEmail()`、`changePassword()`、`update()`、`delete()`、`restore()`。
* JPA Entity 不得無條件加入 `@EqualsAndHashCode` 或 `@ToString`；避免 lazy association traversal、遞迴與 identity semantics 改變。
* class-level `@AllArgsConstructor` 只允許在「所有欄位本身就是完整且合法 initial state」的 Entity 上使用。若包含 `version`、`usedAt`、`revokedAt`、`deletedAt`、`verifiedAt`、generated timestamp、derived normalized field 或其他 lifecycle/internal state，保留具 domain 意義的 explicit constructor。
* `@Builder` 若用於 Entity，優先標在合法 creation constructor 或 factory 上，不要讓 Builder 暴露全部 persistence/lifecycle 欄位。

## Domain encapsulation

* Style refactor 必須保持既有 behavior、transaction、locking、query、JSON contract 與 domain invariant 不變。
* 新增或修改 Lombok annotation 前，先確認不會改變：

  * constructor visibility
  * Jackson construction semantics
  * JPA mapping/proxy behavior
  * equals/hashCode/toString semantics
  * domain state transition
* 對 lifecycle entity，合法狀態轉換應透過 domain method，而非直接寫 persistence 欄位。

## 驗證

* Java/Lombok/naming refactor 完成後至少執行：

  ```bash
  ./mvnw --batch-mode verify
  ```
* 若修改 Request DTO 的 Builder、Jackson setter 或 normalization，需有 regression test 證明不同 construction path 得到相同 canonical state。

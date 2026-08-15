# 專案繳交補充說明

本文件補充 [Blog Admin System README](../README.md) 中的設計說明與額外功能，對應 `docs/PRD.md` 的「專案繳交方式建議」。

## 系統設計

- **分層與責任**：Angular 21 管理介面透過 HTTP API 呼叫後端；Spring Boot 3.5.16 負責驗證、授權、領域規則與資料存取。
- **領域邊界**：系統以 User、UserIdentity、Article、Tag、Invitation 與 Refresh Session 等概念分層。Admin 可管理使用者與所有文章；Author 可管理自己的文章。
- **資料庫變更**：PostgreSQL 儲存資料，Liquibase 負責 schema 與 migration；Compose 不使用資料庫 init script。
- **發布規則**：只有 Published 且未被刪除的 Article 會成為 Public Article，透過 `/api/v1/public/**` 提供匿名讀取。

## 認證與安全

- **認證機制與責任邊界**：保留本地 Email 與 Password 認證，並支援 Google 登入。Supabase Auth 僅作為 Google OAuth 的 Identity Provider；前端完成 Google 授權後將 token 傳至後端，後端透過 `SupabaseJwtVerifier` 驗證簽名、issuer、aud 與 claims。
- **本地 User 關聯**：Google 身份驗證成功後，系統在本地建立或連結 local `User`，並以 `user_identities` 資料表持久化 Google `subject` 與本地 `userId` 的關聯；後續發行本地簽署的 JWT access token 與 `RefreshSession`。Google 登入並非將整套身份授權系統遷移至 Supabase。
- **本地授權控制**：Role、enabled 啟用狀態、verified 驗證狀態、authorization 與 session revocation 等所有安全與權限規則完全由本地 Spring Boot 後端控制。
- **Google 邀請兌換**：支援受邀者使用與邀請 Email 一致的 Google 帳號兌換 Admin 邀請，自動完成邀請核銷、建立已驗證 local User、綁定 Google 身份與簽發本地 session。
- **Token 與 Session 管理**：本地 access token 儲存在瀏覽器 `localStorage`，refresh token 儲存於 `HttpOnly` cookie。refresh token 會經 `/api/v1/auth/refresh` 輪替；User 可查看並撤銷自己的 Refresh Session，登出時撤銷目前工作階段（前端並清除本地 Supabase session）。
- **存取控制**：只有 Verified 且 enabled 的 User 可登入管理後台；Admin 路徑另受 Admin 角色限制，Author 的文章操作受 owner 邊界限制。
- **郵件通知**：公開註冊、Email 驗證、Invitation、Email 變更、忘記 Password 與 Password 重設流程所寄出的郵件，可在本機 Mailpit 查看。

## 額外功能與實作細節

- Article 支援標題搜尋、分頁、Draft／Published 切換、Tag、多步驟管理操作與刪除確認。
- Deleted Article 會保留 30 天，Author 或 Admin 可在期限內復原；逾期資料由清理流程移除。
- 以文章 `version` 實作樂觀鎖定，避免同一篇 Article 的並發編輯覆蓋彼此變更。
- 前端採用 standalone component、`ChangeDetectionStrategy.OnPush`、路由 guard、表單驗證，以及 loading、錯誤、空資料與成功訊息狀態。
- Public Article 與公開 Tag 支援查詢分頁；公開端點具備每個執行個體（per-instance，JVM 記憶體管理）每個來源 IP 每分鐘 60 次的請求限制。
- Backend `verify` 會執行 Spotless、測試與 JaCoCo 報告，並以 JaCoCo 設下品質門檻（Instruction coverage ≥ 90%、Line coverage ≥ 90%、Branch coverage ≥ 75%）；整合測試使用 Testcontainers PostgreSQL。Frontend 測試由 `frontend/scripts/test.mjs` 執行，完整指令以 README 為準。

### 實作確認的額外功能

- **Google 登入與 Google 邀請兌換**：支援 Google OAuth 第三方登入與邀請兌換，透過 `user_identities` 綁定本地帳號；未設定 Supabase 設定時前端自動優雅降級隱藏 Google 登入按鈕，Email/Password 仍可正常運作。
- **Public Registration 與 Email Verification Link**：註冊與重送都回覆中性成功；Email Verification Link 一次性使用，重送會使舊連結失效。
- **Admin 使用者管理**：可依角色、啟用狀態或 Email／Display Name 篩選 User，並調整其他 User 的角色與 enabled 狀態；不能調整自己，也不能讓最後一位同時 enabled 且已驗證的 Admin 失效。
- **Password Minimum Length**：Admin 可設定 8–128 的 Password 最小長度；每次變更會留下不可修改的 Password Setting Change 紀錄。
- **Preferred Language**：支援 `zh-TW` 與 `en`，Preferred Language 同時用於介面與相應的系統 Email 通知。
- **統一錯誤契約**：API 以 Problem Details 回覆錯誤，包含 validation 的 `fieldErrors`，並涵蓋 401、403、404、409 與 429 狀態。

## API 文件

Compose 啟動後可使用：

- [Swagger UI](http://localhost:8080/swagger-ui/index.html)
- [OpenAPI JSON](http://localhost:8080/v3/api-docs)

若覆寫 `BACKEND_PORT`，請將連結中的 `8080` 改為實際的 backend port。

## 測試帳號原則

專案不在 README、此文件或原始碼放置固定 Password。請依 README 的 `.env.example` 建立 `.env`，自行填入：

```dotenv
APP_BOOTSTRAP_ADMIN_EMAIL=你的本機測試 Email
APP_BOOTSTRAP_ADMIN_PASSWORD=你的本機測試 Password
```

其中 `APP_SECURITY_JWT_SECRET` 也必須使用隨機產生且至少 32 bytes 的值；`.env` 僅供本機使用，請勿提交。


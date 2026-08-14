# Blog Admin System

管理者建立與維護文章的單一後台情境。此詞彙表定義文章管理所使用的業務語言。

## Language

**User（使用者）**:
以 Email 識別、可由公開註冊或 Admin 邀請建立，並可被啟用或停用的人員；僅 Verified 且 enabled 的 User 可登入管理後台。User 的 Display Name 長度為 1–100 字元；保存去除首尾空白的 Email 作顯示與寄信用途，另保存小寫正規化 Email 以實作唯一識別。
_Avoid_: Account、Member

**Verified User（已驗證使用者）**:
已證實其 Email 所有權、在 enabled 時可登入管理後台的 User。
_Avoid_: Activated User、Confirmed User

**Email Verification Link（Email 驗證連結）**:
寄往未驗證 User、用以證實其 Email 所有權的 24 小時一次性連結；重送後，先前連結立即失效。連結導向前端 `/verify-email`，由該頁面提交 token 完成驗證；token 為 32-byte URL-safe random 值，資料庫只保存其 SHA-256 雜湊。每位未驗證 User 同時只有一個有效連結，使用、過期或失效後仍保留其紀錄與狀態；使用與失效為原子狀態轉換，同一連結僅一個請求可成功，重送或驗證先完成者決定舊連結是否失效。
_Avoid_: Activation Link、Confirmation Link

**Preferred Language（偏好語言）**:
User 選擇的介面與系統 Email 語言；支援繁體中文與英文，API 與儲存值為 `zh-TW` 或 `en`，預設為 `zh-TW`。
_Avoid_: Locale、Browser Language

**Password Minimum Length（密碼最小長度）**:
由 Admin 在執行期間設定、介於 8 至 128 的 Password 最小字元數；只套用至新設定或重設的 Password。初始值為 8；公開註冊也拒絕版本庫維護的小型常見 Password 清單。
_Avoid_: Password Complexity Policy、Password Configuration

**Password Setting Change（密碼設定變更）**:
Admin 變更 Password Minimum Length 時留下、不可修改且僅供 Admin 查閱的紀錄，記載操作者、變更前後數值與變更時間，永久保留。
_Avoid_: Audit Log、Configuration History

**Refresh Session（更新工作階段）**:
代表單一裝置或瀏覽器登入狀態的可撤銷憑證；User 可查看並撤銷自己的 Refresh Session，清單只顯示是否為目前工作階段、建立時間與最後使用時間。
_Avoid_: Login Token、Device

**Google Login（Google 登入）**:
User 以 Google 已驗證 Email 登入管理後台的方式；相同 Email 的既有 User 會連結此登入方式，否則建立一位已驗證且 enabled 的 Author，不再要求 Email 驗證連結。連結後的 Google 身分不因其 Email 變更而改寫 User Email。
_Avoid_: Social Login、OAuth Login

**Google Invitation Redemption（Google 邀請兌換）**:
Invitation 收件者以相同 Email 的 Google Login 完成 Invitation 的方式；它不要求設定 Password。
_Avoid_: Google Registration、Social Invitation

**Admin（管理員）**:
可管理使用者與所有文章的 User 角色；可調整其他 User 的角色與狀態，但不能調整自己，且不能讓最後一位同時 enabled 與已驗證的 Admin 被降級或停用。
_Avoid_: Administrator、Superuser

**Author（作者角色）**:
可管理自己文章的 User 角色；公開註冊的 User 在 Email 驗證前即為 Author，但仍不可登入。Email 驗證只將 User 設為 Verified，不改變角色或 enabled 狀態。
_Avoid_: Editor、Writer

**Public Registration（公開註冊）**:
訪客以 Email、Display Name、Password 與 Preferred Language 建立 User 的流程。既有未驗證 User 再註冊時保留原有個人資料與 Password，只換發 Email Verification Link；既有 Verified User 不改資料也不寄信。註冊與重送一律回中性成功，只有未驗證 User 會在限流允許時收到新連結；Email Verification 信以 Display Name 稱呼收件者，並說明連結在 24 小時後失效。
_Avoid_: Sign-up、Account Creation

**Article（文章）**:
由作者建立與維護的內容單位，具有標題、內容、標籤與發布狀態；它保留 owner User 以判斷管理權限，並獨立保留建立當時的 Author Attribution。
_Avoid_: Post、Blog

**Public Article（公開文章）**:
Publication Status 為 Published、未被刪除，且可由匿名公開 API 讀取的 Article；公開列表依首次發布時間新到舊排序。
_Avoid_: Public Post、Live Article

**Author Attribution（作者署名）**:
建立文章時記錄於文章上的不可變顯示名稱。
_Avoid_: Author、Editor、Owner

**Invitation（邀請）**:
Admin 為指定 Email 預先建立 User 時寄出的、24 小時內一次性使用連結；收件者透過它設定 Password 並驗證 Email，重寄會使先前連結失效。
_Avoid_: Registration、User Creation

**Tag（標籤）**:
可被多篇文章共用、可作為文章列表單一篩選條件的分類名稱；在文章表單輸入新名稱時建立，未被任何文章使用時移除，並以去除首尾空白後的不分大小寫名稱唯一識別。只有至少被一篇 Public Article 使用的 Tag 會列於公開 API。
_Avoid_: Keyword、Category

**Publication Status（發布狀態）**:
文章目前是 Draft（草稿）或 Published（已發布）的狀態；兩者都在管理後台可見，只有 Published Article 可經公開 API 讀取。
_Avoid_: Visibility、Active

**Deleted Article（已刪除文章）**:
從一般文章列表移除、保留 30 天以供其 Author 或 Admin 復原的 Article；到期後永久刪除。它僅會列在獨立的已刪除清單中，Author 只看自己的項目，Admin 可看全部。
_Avoid_: Archived Article、Removed Article

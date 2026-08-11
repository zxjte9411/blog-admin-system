# Blog Admin System

管理者建立與維護文章的單一後台情境。此詞彙表定義文章管理所使用的業務語言。

## Language

**User（使用者）**:
可登入管理後台，且其帳號可被公開註冊或由 Admin 邀請建立、更新或停用的人員。
_Avoid_: Account、Member

**Verified User（已驗證使用者）**:
已證實其 Email 所有權、在 enabled 時可登入管理後台的 User。
_Avoid_: Activated User、Confirmed User

**Preferred Language（偏好語言）**:
User 選擇的介面與系統 Email 語言；支援繁體中文與英文。
_Avoid_: Locale、Browser Language

**Password Minimum Length（密碼最小長度）**:
由 Admin 在執行期間設定、介於 8 至 128 的 Password 最小字元數；只套用至新設定或重設的 Password。
_Avoid_: Password Complexity Policy、Password Configuration

**Password Setting Change（密碼設定變更）**:
Admin 變更 Password Minimum Length 時留下、不可修改且僅供 Admin 查閱的紀錄，記載操作者、變更前後數值與變更時間，永久保留。
_Avoid_: Audit Log、Configuration History

**Refresh Session（更新工作階段）**:
代表單一裝置或瀏覽器登入狀態的可撤銷憑證；User 可查看並撤銷自己的 Refresh Session，清單只顯示是否為目前工作階段、建立時間與最後使用時間。
_Avoid_: Login Token、Device

**Admin（管理員）**:
可管理使用者與所有文章的 User 角色；可調整其他 User 的角色與狀態，但不能調整自己，且不能讓最後一位同時 enabled 與已驗證的 Admin 被降級或停用。
_Avoid_: Administrator、Superuser

**Author（作者角色）**:
只能管理自己文章的 User 角色。
_Avoid_: Editor、Writer

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

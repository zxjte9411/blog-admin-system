# Full Stack 職缺－前後端整合實作

**標籤：** `NEUTEC`、`Full Stack`

## 專案主題

Blog Admin System（前端 + 後端整合）

## 功能需求

請將前後端串接整合以下功能：

### 前端需求

#### 1. 登入頁

- Email + Password 表單，需驗證格式與必填。
- 可將登入狀態儲存在 `localStorage`。

#### 2. 文章列表

- 顯示所有文章：標題、作者、建立時間。
- 支援依標題搜尋、分頁、編輯與刪除功能。
- 刪除時需顯示確認對話框。

#### 3. 新增／編輯文章

使用 Reactive Form 建立表單，欄位包含：

- 標題（必填）
- 內容（Textarea，必填）
- 標籤（Checkbox 或文字輸入，可複選）
- 發佈狀態（radio：`draft`／`published`）

編輯畫面需預填資料。

### 後端需求

#### 使用者登入

- Email + Password，請自行設計驗證流程。

#### 文章 CRUD

- 列出所有文章，支援搜尋與分頁。
- 新增、編輯、刪除文章。
- 欄位包含：標題、內容、標籤、發佈狀態、作者、建立時間。

權限設計與驗證可自由規劃，可簡化為單一使用者。

## 技術規格要求

| 技術面向 | 說明 |
| --- | --- |
| 前端框架 | Angular 2+、Vue 3、React（擇一） |
| 後端框架 | Spring Boot（建議 2.7 或以上） |
| 串接方式 | 前端使用 HTTP API 呼叫後端資料 |
| 本地開發方式 | 建議使用 Docker Compose，或於 README 說明如何在本地執行前後端系統 |
| 整體系統設計 | 須具備良好模組分層、資料結構設計、驗證邏輯與錯誤處理能力 |

## 加分項目（非必須）

### 前端

- 使用 `ChangeDetectionStrategy.OnPush`。
- 使用 Lazy Loading 與功能模組分離。
- 單元測試或整合測試（如 Jest／Karma + Jasmine）。
- 實作 loading 狀態、錯誤提示、空資料提示等 UX 細節。

### 後端

- 實作 JWT 或 Session-based 驗證機制；若前端也有整合則加分。
- 整合 Spring Security。
- 自動化測試覆蓋率報表。
- 資料庫 schema migration（Flyway／Liquibase）。

## 專案繳交方式建議

提供 GitHub Repo，請包含 README 說明如何建置與執行。

README 建議包含：

- 開發框架與工具版本
- 建置／執行指令
- API 文件連結（如 Swagger UI）
- 登入測試帳號（如有）
- 任意補充說明，例如設計理念、困難點與額外功能

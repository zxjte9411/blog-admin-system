# ERD：核心資料模型

本圖整理目前實作的核心資料表與關聯（以 Liquibase migrations 為準）。外鍵關聯皆具備實體資料庫 FK 限制；`password_hash` 與 `token_hash` 為敏感欄位，不可公開。

```mermaid
erDiagram
    users {
        uuid id PK
        text email
        text normalized_email UK
        varchar display_name
        text password_hash "敏感：不可公開"
        varchar preferred_language
        varchar role
        boolean enabled
        timestamptz verified_at
        integer access_token_version
    }

    user_identities {
        uuid id PK
        uuid user_id FK
        varchar provider
        text subject
    }

    articles {
        uuid id PK
        uuid owner_id FK
        varchar author_attribution
        varchar title
        text content
        varchar status
        timestamptz published_at
        timestamptz deleted_at
        bigint version
        timestamptz created_at
        timestamptz updated_at
    }

    tags {
        uuid id PK
        varchar name UK
    }

    article_tags {
        uuid article_id PK,FK
        uuid tag_id PK,FK
    }

    refresh_sessions {
        uuid id PK
        uuid user_id FK
        bytea token_hash "敏感：不可公開 / UK"
        timestamptz created_at
        timestamptz last_used_at
        timestamptz expires_at
        timestamptz revoked_at
        integer access_token_version
        integer user_access_token_version
    }

    email_verification_tokens {
        uuid id PK
        uuid user_id FK
        bytea token_hash "敏感：不可公開 / UK"
        timestamptz expires_at
        timestamptz used_at
        timestamptz invalidated_at
    }

    password_reset_tokens {
        uuid id PK
        uuid user_id FK
        bytea token_hash "敏感：不可公開 / UK"
        timestamptz expires_at
        timestamptz used_at
    }

    email_change_tokens {
        uuid id PK
        uuid user_id FK
        text new_email
        bytea token_hash "敏感：不可公開 / UK"
        timestamptz expires_at
        timestamptz used_at
    }

    admin_invitations {
        uuid id PK
        text email
        bytea token_hash "敏感：不可公開 / UK"
        timestamptz expires_at
        timestamptz used_at
    }

    password_settings {
        boolean id PK
        integer minimum_length
    }

    password_setting_changes {
        uuid id PK
        uuid operator_id FK
        integer previous_value
        integer new_value
        timestamptz changed_at
    }

    auth_rate_limit_events {
        bigserial id PK
        text bucket
        text bucket_key
        timestamptz requested_at
    }

    users ||--o{ user_identities : "user_id"
    users ||--o{ articles : "owner_id"
    articles ||--o{ article_tags : "article_id"
    tags ||--o{ article_tags : "tag_id"
    users ||--o{ refresh_sessions : "user_id"
    users ||--o{ email_verification_tokens : "user_id"
    users ||--o{ password_reset_tokens : "user_id"
    users ||--o{ email_change_tokens : "user_id"
    users ||--o{ password_setting_changes : "operator_id"
```

## 重要約束與 Partial / Expression Index 說明

依據 Liquibase changelogs（`backend/src/main/resources/db/changelog/`），本系統資料庫具備下列重要約束與索引設計：

1. **`user_identities` 複合唯一約束**：
   - `UNIQUE (provider, subject)`：同一個第三方 Provider 的 Subject 只能綁定單一本地帳號。
   - `UNIQUE (user_id, provider)`：每個本地 User 對同一 Provider 僅能綁定一個身份識別。
2. **條件式唯一索引（Partial Unique Indexes）**：
   - `admin_invitations`：`one_pending_admin_invitation ON (email) WHERE used_at IS NULL`，確保每個 Email 同一時間僅能有一筆未使用的有效邀請。
   - `email_verification_tokens`：`one_active_email_token ON (user_id) WHERE used_at IS NULL AND invalidated_at IS NULL`，確保每個 User 僅能有一筆有效驗證連結。
   - `password_reset_tokens`：`one_active_password_reset ON (user_id) WHERE used_at IS NULL`，確保每個 User 僅能有一筆有效重設連結。
   - `email_change_tokens`：`one_active_email_change ON (user_id) WHERE used_at IS NULL`，確保每個 User 僅能有一筆有效 Email 變更確認連結。
3. **`tags` 大小寫不敏感與格式約束**：
   - `CHECK (name = btrim(name))`：禁止標籤名稱前後包含空白字元。
   - `UNIQUE INDEX tags_name_ci_unique ON tags (lower(name))`：以小寫運算式索引確保標籤名稱在大小寫不區分情況下的唯一性。
4. **`password_settings` 與 `password_setting_changes` 安全約束**：
   - `password_settings`：主鍵為 `id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id)` 確保全系統僅有一筆全域單例設定，並以 `CHECK (minimum_length BETWEEN 8 AND 128)` 限制長度範圍。
   - `password_setting_changes`：透過資料庫觸發器 `password_setting_changes_immutable` 阻絕所有 `UPDATE` 與 `DELETE` 操作，確保密碼政策變更稽核紀錄的不可變性（immutable audit log）。



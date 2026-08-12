# 互動與範圍
- 使用繁體中文台灣用語回覆；需要使用者回覆時，列出選項並透過提問工具詢問。
- 本專案在開發容器內執行；注意容器與主機的 IP 及互動。
- 架構邊界：`backend/` 是 Maven／Spring Boot；`frontend/` 是 Angular／npm。命令以各子目錄的 `AGENTS.md` 為準。

## 開發環境
- 修改 Compose 設定或啟動服務前，先執行 `docker compose config`。
- 需要覆寫 Compose 預設值時，從 `.env.example` 建立 `.env`；`APP_SECURITY_JWT_SECRET` 必須是隨機產生且至少 32 bytes 的值，不要提交 `.env`。

## Issues 與文件
- 議題與規格只使用 GitHub Issues，所有操作使用 `gh`；流程見 `docs/agents/issue-tracker.md`。
- 執行涉及 triage 的工作時，依 `docs/agents/triage-labels.md` 對應標籤。
- 探索或修改領域行為時，先依 `docs/agents/domain.md` 讀取 `CONTEXT.md` 與相關 ADR，並使用詞彙表用語。

## CodeGraph
- 根目錄有 `.codegraph/` 時，先用 CodeGraph MCP `codegraph_explore`（或 `codegraph explore "..."`）定位與理解程式碼，再讀取或搜尋；沒有就略過。
- 修改後若 CodeGraph 顯示索引尚未同步，直接讀取列出的檔案確認內容。

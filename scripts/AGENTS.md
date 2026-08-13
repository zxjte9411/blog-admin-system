# 腳本目錄代理指引（Scripts AGENTS.md）

本目錄包含供代理（Agent）進行自動化驗證、無障礙稽核與 Chrome DevTools 協定通訊之腳本。

---

## 代理執行命令與完成準則

### 1. 端對端行為測試（E2E Audit）

- **觸發情境**：修改前端 UI、路由、表單、對話框或登入流程後，必須執行 E2E 驗證。
- **執行指令**：
  ```bash
  npm --prefix frontend run test:e2e
  ```
- **完成準則**：所有 17 項驗證指標均顯示 `✅ PASS` 且 Exit Code 為 `0`。

### 2. Chrome DevTools MCP 連線（MCP Chrome Bridge）

- **環境規則**：本代理在 Docker Linux 容器內執行，而 Chrome 瀏覽器位於宿主機（Host Machine）。
- **連線機制**：
  - 宿主機 Chrome 遠端除錯埠號為 `host.docker.internal:9222`。
  - 容器內 MCP 工具需連線至 `127.0.0.1:9333`。
  - 當 MCP 工具回報連線失敗時，啟動背景橋接服務：
    ```bash
    node scripts/mcp-chrome-bridge.mjs
    ```
- **完成準則**：MCP `chrome-devtools` 能正常調用 `list_pages`、`navigate_page` 與 `take_snapshot`。

### 3. Docker Compose 煙霧測試（Compose Smoke）

- **觸發情境**：修改 `backend/` REST API、Docker Compose 設定或環境變數後執行。
- **執行指令**：
  ```bash
  ./scripts/compose-smoke.sh
  ```
- **完成準則**：輸出 `SMOKE OK: health, frontend, Admin, tagged Article, public visibility, delete/restore` 且 Exit Code 為 `0`。

---

## 程式碼與架構規範

- **輕量與零相依**：維護 [`chrome-session.mjs`](chrome-session.mjs) 與其他腳本時，一律採用 Node.js 原生模組（`node:http`, `node:crypto`），禁止任意引入第三方瀏覽器自動化套件。
- **格式規範**：修改任何 `.mjs` 或 `.sh` 腳本後，執行 `npx prettier --write "scripts/**/*.mjs"` 保持風格一致。

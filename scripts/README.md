# 腳本工具庫（Scripts）

本目錄包含開發容器（DevContainer）、端對端測試（E2E）、Chrome DevTools 協定橋接（MCP Bridge）以及 Docker Compose 煙霧測試之自動化腳本。

---

## 腳本清單與用途

| 腳本名稱                                                                                      | 執行環境           | 主要用途                                                                                                                                         | 執行指令                                                             |
| :-------------------------------------------------------------------------------------------- | :----------------- | :----------------------------------------------------------------------------------------------------------------------------------------------- | :------------------------------------------------------------------- |
| [`mcp-chrome-bridge.mjs`](file:///workspaces/blog-admin-system/scripts/mcp-chrome-bridge.mjs) | Node.js (容器內部) | MCP Chrome DevTools HTTP / WebSocket 橋接器，轉發容器內 `127.0.0.1:9333` 至實體主機 `host.docker.internal:9222` 並改寫 Host Header。             | `node scripts/mcp-chrome-bridge.mjs`                                 |
| [`chrome-session.mjs`](file:///workspaces/blog-admin-system/scripts/chrome-session.mjs)       | Node.js (模組)     | 純原生 Node.js 實作之 Chrome DevTools Protocol (CDP) WebSocket 通訊客戶端（無第三方肥大相依），提供頁面巡航、DOM 評估與表單輸入等操作。          | _(作為模組被其他腳本 import)_                                        |
| [`e2e-audit.mjs`](file:///workspaces/blog-admin-system/scripts/e2e-audit.mjs)                 | Node.js (CDP 驅動) | 完整 PRD 與 Issue 規格自動化 E2E 驗證套件，涵蓋前台文章、語系切換、登入註冊、文章 CRUD、軟刪除、原生 `<dialog>` 永久刪除、使用者管理與工作階段。 | `npm --prefix frontend run test:e2e` 或 `node scripts/e2e-audit.mjs` |
| [`deep-inspection.mjs`](file:///workspaces/blog-admin-system/scripts/deep-inspection.mjs)     | Node.js (CDP 驅動) | 深度巡訪全站各路由之 DOM 與無障礙樹（Accessibility Tree），蒐集版面不一致、標籤缺漏與互動缺陷。                                                  | `node scripts/deep-inspection.mjs`                                   |
| [`compose-smoke.sh`](file:///workspaces/blog-admin-system/scripts/compose-smoke.sh)           | Bash / curl        | 容器化後端 REST API 與前端靜態資源煙霧測試，驗證 Actuator 健康度、Admin 帳號登入、文章發布、標籤關聯與刪除復原生命週期。                         | `./scripts/compose-smoke.sh`                                         |

---

## 開發容器與實體主機 Chrome 連線架構

在開發容器內進行 E2E 測試與 Chrome DevTools 操作時，架構如下：

```text
[ 容器內部 (Container) ]
    ├─ npm run test:e2e / e2e-audit.mjs ───┐
    ├─ deep-inspection.mjs ───────────────┤ (CDP WebSocket)
    └─ MCP Server (chrome-devtools) ───────┼─> 127.0.0.1:9333 (mcp-chrome-bridge.mjs)
                                           │        │ (轉發並改寫 Host Header: localhost:9222)
                                           ▼        ▼
                                     host.docker.internal:9222
                                                │
[ 實體主機 (Host Machine) ]                     ▼
    └─────────────────────────────> Google Chrome (--remote-debugging-port=9222)
```

### 前置需求

1. 實體主機啟動 Chrome 並開啟遠端除錯埠號：
   ```bash
   google-chrome --remote-debugging-port=9222
   # 或 macOS
   /Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --remote-debugging-port=9222
   ```
2. 容器內若需啟動 MCP Bridge 背景服務：
   ```bash
   node scripts/mcp-chrome-bridge.mjs &
   ```
3. 執行 E2E 驗證套件：
   ```bash
   npm --prefix frontend run test:e2e
   ```

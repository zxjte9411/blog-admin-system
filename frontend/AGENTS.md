# Frontend 指引

- 使用 Node.js 24；安裝依賴只用 `npm ci`。
- 本機變更依序執行：
  ```bash
  npm run lint
  npm run lint:format
  npm run typecheck
  npm test
  npm run build
  ```
- CI 目前只執行 `lint`、`test`、`build`，不執行 `lint:format` 或 `typecheck`；本機變更仍必須依上述順序執行完整驗證。
- 測試與建置以 `package.json` scripts 為準；不要使用 frontend README 的 template `ng test`／`e2e` 命令。

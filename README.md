# Blog Admin System

目前僅完成開發環境初始化；尚未建立 Angular、Spring Boot、Liquibase、資料表、API 或前端介面。

## 技術基線

- Java 25 LTS
- Node.js 24 LTS
- PostgreSQL 18.4
- Docker Compose
- Dev Container

## 開啟開發環境

1. 以 VS Code 的 **Reopen in Container** 開啟此資料夾。
2. 在容器終端機確認工具：

   ```bash
   java --version
   node --version
   git --version
   docker --version
   docker compose version
   python3 --version
   uv --version
   ```

## 啟動資料庫

設定預設值已內建於 `compose.yaml`；若需要覆寫，先複製範例檔：

```bash
cp .env.example .env
docker compose up -d db
docker compose ps
```

停止資料庫：

```bash
docker compose down
```

移除資料庫資料：

```bash
docker compose down --volumes
```

## 下一階段

初始化 Angular 21 LTS、Spring Boot 3.5.16 與 Liquibase，再開始實作題目功能。

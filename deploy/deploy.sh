#!/usr/bin/env bash

set -euo pipefail

app_dir=/opt/blog-admin-system
backup_dir="$app_dir/backups"
data_volume=blog-admin-system-production_postgres_production_data

cd "$app_dir"
test -f .env
mkdir -p "$backup_dir"

db_container="$(docker compose --env-file .env -f compose.production.yaml ps -q db 2>/dev/null || true)"
if docker volume inspect "$data_volume" >/dev/null 2>&1; then
  if [ -z "$db_container" ] || [ "$(docker inspect --format '{{.State.Running}}' "$db_container")" != "true" ]; then
    printf '%s\n' 'Production data volume exists but the db container is not running; refusing deployment without a backup.' >&2
    exit 1
  fi
fi

if [ -n "$db_container" ] && [ "$(docker inspect --format '{{.State.Running}}' "$db_container")" = "true" ]; then
  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  backup_file="$backup_dir/blog_admin_${timestamp}.sql"
  temporary_backup="${backup_file}.tmp"
  trap 'rm -f -- "$temporary_backup"' EXIT

  docker compose --env-file .env -f compose.production.yaml exec -T db \
    sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    > "$temporary_backup"
  mv -- "$temporary_backup" "$backup_file"
  trap - EXIT

  backup_files="$(printf '%s\n' "$backup_dir"/blog_admin_*.sql | sort -r)"
  backup_count=0
  while IFS= read -r backup; do
    [ -f "$backup" ] || continue
    backup_count=$((backup_count + 1))
    if [ "$backup_count" -gt 7 ]; then
      rm -f -- "$backup"
    fi
  done <<EOF
$backup_files
EOF
fi

docker compose --env-file .env -f compose.production.yaml pull
docker compose --env-file .env -f compose.production.yaml up -d --wait --wait-timeout 600 --remove-orphans

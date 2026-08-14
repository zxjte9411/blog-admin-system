#!/bin/sh
set -eu

config_file=${CONFIG_FILE:-public/config.js}
env_file=${ENV_FILE:-../.env}
supabase_url=${SUPABASE_URL-}
supabase_publishable_key=${SUPABASE_PUBLISHABLE_KEY-}
has_public_environment=false

if [ "${SUPABASE_URL+x}" = x ] || [ "${SUPABASE_PUBLISHABLE_KEY+x}" = x ]; then
  has_public_environment=true
fi

if [ -f "$env_file" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      SUPABASE_URL=*)
        [ -n "$supabase_url" ] || supabase_url=${line#SUPABASE_URL=}
        ;;
      SUPABASE_PUBLISHABLE_KEY=*)
        [ -n "$supabase_publishable_key" ] || supabase_publishable_key=${line#SUPABASE_PUBLISHABLE_KEY=}
        ;;
    esac
  done < "$env_file"
elif [ "$has_public_environment" = false ]; then
  printf '%s\n' "Missing $env_file and Supabase public environment variables; refusing to generate config.js." >&2
  exit 1
fi

mkdir -p "$(dirname "$config_file")"
umask 022
cat > "$config_file" <<EOF
globalThis.__BLOG_ADMIN_CONFIG__ = {
  supabaseUrl: '$supabase_url',
  supabasePublishableKey: '$supabase_publishable_key',
};
EOF

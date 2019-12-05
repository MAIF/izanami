#!/bin/bash
set -e

   psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
EOSQL

   psql -d kitsune -c "CREATE EXTENSION IF NOT EXISTS pg_trgm"

#!/bin/bash
# Runs once, when the Postgres volume is first created: one database per environment.
set -euo pipefail
for db in ${ERP_DATABASES:-bip_uat bip_prod}; do
  echo "creating database $db"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -c "CREATE DATABASE $db OWNER $POSTGRES_USER"
done

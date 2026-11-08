# Shared helpers. PSQL is the psql command able to connect as a superuser, e.g.
#   PSQL="psql -h localhost -U postgres"   or   PSQL="runuser -u postgres -- psql"
PSQL=${PSQL:-psql}
DB=${VERIFY_DB:-payflow_verify}
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
sql() { $PSQL -v ON_ERROR_STOP=1 -X -q -tA -d "$DB" "$@"; }

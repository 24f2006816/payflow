#!/usr/bin/env bash
# Offline verification suite (no Maven Central / Docker Hub needed). Needs: JDK 21, a local PostgreSQL 16.
set -u; H="$(cd "$(dirname "$0")" && pwd)"; status=0
for s in 1_schema_invariants.sh 2_concurrency.sh 3_application_sql.sh 4_domain_unit_tests.sh 5_syntax_check.sh; do
  echo "================ $s"; bash "$H/$s" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || status=1
  [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
done
echo "================ overall: $([ $status -eq 0 ] && echo PASS || echo FAIL)"; exit $status

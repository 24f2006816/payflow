#!/usr/bin/env bash
# Compiles the framework-free domain package + its JUnit 5 tests against a minimal JUnit-5 API shim
# (local stand-in, NOT the real JUnit engine) and runs them. Real runs: `mvn test`.
set -e; source "$(dirname "$0")/common.sh"; H="$(cd "$(dirname "$0")" && pwd)"; O=$(mktemp -d)
javac -Xlint:all,-serial -Werror -d "$O" $(find "$ROOT/src/main/java/com/payflow/domain" -name '*.java') \
  $(find "$H/shim" -name '*.java') $(find "$ROOT/src/test/java/com/payflow/domain" -name '*.java') "$H/Runner.java"
java -cp "$O" Runner "$O"

#!/usr/bin/env bash
# Parse-only check of ALL Java sources (no dependency resolution): catches syntax errors only.
set -e; source "$(dirname "$0")/common.sh"; H="$(cd "$(dirname "$0")" && pwd)"; O=$(mktemp -d)
javac -d "$O" "$H/SyntaxCheck.java" && java -cp "$O" SyntaxCheck "$ROOT/src"

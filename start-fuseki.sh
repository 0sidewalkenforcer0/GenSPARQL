#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
PORT="${1:-8080}"

cd "$ROOT_DIR"

mvn -q -pl gensparql-server -am install -DskipTests

exec mvn -q -f gensparql-server/pom.xml exec:java \
  -Dexec.mainClass=org.gensparql.server.GenSPARQLFuseki \
  -Dexec.args="$PORT" \
  -Dexec.cleanupDaemonThreads=false

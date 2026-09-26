#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"
npm --prefix web run build
mvn -q -Dmaven.test.skip=true -Dmirror.frontend.copy=true -pl mirror-server -am package
exec java ${JAVA_OPTS:-} -jar mirror-server/target/mirror-server-0.1.0-SNAPSHOT.jar

#!/usr/bin/env bash
set -euo pipefail

# Always compile first so mvn exec:exec runs the latest code.
mvn -q -DskipTests compile exec:exec@run-java

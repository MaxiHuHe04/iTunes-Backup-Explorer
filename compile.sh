#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
echo "Compiling iTunes Backup Explorer..."
mvn -q -DskipTests compile assembly:single
echo ""
echo "Done! Run with: ./run.sh"

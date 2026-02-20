#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

JAR=$(find target -maxdepth 1 -name '*-jar-with-dependencies.jar' 2>/dev/null | head -1)

if [ -z "$JAR" ]; then
    echo ""
    echo " Compiling iTunes Backup Explorer..."
    echo ""
    mvn -q -DskipTests compile assembly:single
    JAR=$(find target -maxdepth 1 -name '*-jar-with-dependencies.jar' | head -1)
fi

if [ -z "$JAR" ]; then
    echo ""
    echo " ERROR: Compilation failed."
    echo " Make sure Java 18+ (JDK) and Maven are installed."
    exit 1
fi

echo "Starting iTunes Backup Explorer..."
java -jar "$JAR"

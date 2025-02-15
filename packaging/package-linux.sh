#!/usr/bin/env bash

# The Maven configuration runs this script automatically from the project directory in the package phase

# Required environment variables:
# PROJECT_VERSION (jar suffix)
# APP_VERSION
# JAVA_HOME
# JAVA_RELEASE (e.g. 23)

APP_NAME="itunes-backup-explorer"
MAIN_JAR="itunes-backup-explorer-$PROJECT_VERSION.jar"
MAIN_MODULE="me.maxih.itunes_backup_explorer"
MAIN_CLASS="me.maxih.itunes_backup_explorer.ITunesBackupExplorerLauncher"

MANUAL_MODULES="jdk.crypto.ec,jdk.localedata,org.slf4j,org.slf4j.simple,org.xerial.sqlitejdbc,$MAIN_MODULE"

rm -rf target/runtime-image
rm -rf target/app-image
rm -rf target/installer


DETECTED_MODULES=$("$JAVA_HOME"/bin/jdeps -q \
  --multi-release "$JAVA_RELEASE" \
  --ignore-missing-deps \
  --print-module-deps \
  --module-path "target/libs:target/$MAIN_JAR" \
  --module "$MAIN_MODULE")

echo Detected modules: "$DETECTED_MODULES"


echo Generating runtime image...

"$JAVA_HOME"/bin/jlink \
  --strip-native-commands \
  --no-header-files \
  --no-man-pages \
  --compress=zip-6 \
  --strip-debug \
  --module-path "target/libs:target/$MAIN_JAR" \
  --add-modules "$DETECTED_MODULES,$MANUAL_MODULES" \
  --ignore-signing-information \
  --include-locales=en,de \
  --output target/runtime-image


echo Packaging...

"$JAVA_HOME"/bin/jpackage \
  --type app-image \
  --dest "target/app-image" \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --copyright "Copyright © 2025 Maximilian Herczegh" \
  --vendor "Maximilian Herczegh" \
  --icon "src/main/resources/me/maxih/itunes_backup_explorer/icon.png" \
  --runtime-image "target/runtime-image" \
  --module "$MAIN_MODULE/$MAIN_CLASS"

"$JAVA_HOME"/bin/jpackage \
    --dest "target/installer" \
    --name "$APP_NAME" \
    --app-version "$APP_VERSION" \
    --copyright "Copyright © 2025 Maximilian Herczegh" \
    --vendor "Maximilian Herczegh" \
    --icon "src/main/resources/me/maxih/itunes_backup_explorer/icon.png" \
    --app-image "target/app-image/$APP_NAME" \
    --linux-menu-group "Utility;Archiving;" \
    --linux-shortcut \
    --linux-app-category "utils"

@echo off

REM The Maven configuration runs this script automatically from the project directory in the package phase

REM Required environment variables:
REM PROJECT_VERSION (jar suffix)
REM APP_VERSION
REM JAVA_HOME
REM JAVA_RELEASE (e.g. 23)

SET APP_NAME=iTunes Backup Explorer
SET MAIN_JAR=itunes-backup-explorer-%PROJECT_VERSION%.jar
SET MAIN_MODULE=me.maxih.itunes_backup_explorer
SET MAIN_CLASS=me.maxih.itunes_backup_explorer.ITunesBackupExplorerLauncher

SET MANUAL_MODULES=jdk.crypto.ec,jdk.localedata,org.slf4j,org.slf4j.simple,org.xerial.sqlitejdbc,%MAIN_MODULE%


REM Remove old generated files
IF EXIST target\java-runtime RMDIR /S /Q target\java-runtime
IF EXIST target\installer RMDIR /S /Q target\installer

REM Detect required modules
"%JAVA_HOME%\bin\jdeps" -q ^
    --multi-release %JAVA_RELEASE% ^
    --ignore-missing-deps ^
    --print-module-deps ^
    --module-path "target\libs;target\%MAIN_JAR%" ^
    --module %MAIN_MODULE% > temp_deps.txt

SET /p DETECTED_MODULES=<temp_deps.txt

DEL temp_deps.txt

echo Detected modules: %DETECTED_MODULES%


echo Generating runtime image...

CALL "%JAVA_HOME%\bin\jlink" ^
    --strip-native-commands ^
    --no-header-files ^
    --no-man-pages ^
    --compress=zip-6 ^
    --strip-debug ^
    --module-path "target\libs;target\%MAIN_JAR%" ^
    --add-modules "%DETECTED_MODULES%,%MANUAL_MODULES%" ^
    --ignore-signing-information ^
    --include-locales=en,de ^
    --output target/runtime-image


echo Packaging...

CALL "%JAVA_HOME%\bin\jpackage" ^
    --type app-image ^
    --dest "target\app-image" ^
    --name "%APP_NAME%" ^
    --app-version "%APP_VERSION%" ^
    --copyright "Copyright © 2025 Maximilian Herczegh" ^
    --vendor "Maximilian Herczegh" ^
    --icon "src\main\resources\me\maxih\itunes_backup_explorer\icon.ico" ^
    --runtime-image "target\runtime-image" ^
    --module "%MAIN_MODULE%/%MAIN_CLASS%"

CALL "%JAVA_HOME%\bin\jpackage" ^
    --type msi ^
    --dest "target\installer" ^
    --name "%APP_NAME%" ^
    --app-version "%APP_VERSION%" ^
    --copyright "Copyright © 2025 Maximilian Herczegh" ^
    --vendor "Maximilian Herczegh" ^
    --icon "src\main\resources\me\maxih\itunes_backup_explorer\icon.ico" ^
    --app-image "target\app-image\%APP_NAME%" ^
    --win-dir-chooser ^
    --win-per-user-install ^
    --win-menu ^
    --win-menu-group "%APP_NAME%" ^
    --win-shortcut ^
    --win-shortcut-prompt

@echo off
setlocal
cd /d "%~dp0"

set JAR=
if exist target (
    for %%f in (target\*-jar-with-dependencies.jar) do set JAR=%%f
)

if not defined JAR (
    echo.
    echo  Compiling iTunes Backup Explorer...
    echo.
    call mvn -q -DskipTests compile assembly:single
    if errorlevel 1 (
        echo.
        echo  ERROR: Compilation failed.
        echo  Make sure Java 18+ [JDK] and Maven are installed and in your PATH.
        echo.
        pause
        exit /b 1
    )
    for %%f in (target\*-jar-with-dependencies.jar) do set JAR=%%f
)

if not defined JAR (
    echo.
    echo  ERROR: Could not find the compiled JAR.
    echo.
    pause
    exit /b 1
)

echo Starting iTunes Backup Explorer...
java -jar "%JAR%"

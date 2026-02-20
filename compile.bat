@echo off
cd /d "%~dp0"
echo Compiling iTunes Backup Explorer...
call mvn -q -DskipTests compile assembly:single
echo.
echo Done! Run with: run.bat

@echo off
setlocal
cd /d "%~dp0\.."
rem Requires all six previously compiled native bundles.
python scripts/prepare-game.py
if errorlevel 1 exit /b 1
call mvn -Puniversal clean verify
if errorlevel 1 exit /b 1
java -jar target\Trinium-0.1.0-mc26.3-universal.jar --verify-universal --require-native --selftest
exit /b %errorlevel%

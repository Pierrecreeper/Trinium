@echo off
setlocal
cd /d "%~dp0\.."
where java >nul 2>nul || (echo JDK 25+ requis pour compiler. & exit /b 1)
where mvn >nul 2>nul || (echo Maven 3.9+ requis. & exit /b 1)
where cargo >nul 2>nul || (echo Rust/Cargo et les outils de compilation C++ MSVC sont requis. & exit /b 1)
python scripts/prepare-game.py
if errorlevel 1 exit /b 1
call mvn -Pnative clean verify
if errorlevel 1 exit /b 1
java -jar target\Trinium-0.1.0-mc26.3.jar --require-native --selftest
exit /b %errorlevel%

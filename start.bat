@echo off
chcp 65001 >nul
title Bambu Lab Dashboard
cd /d "%~dp0"

echo.
echo   Bambu Lab Dashboard  -  Bridge Service
echo   ======================================
echo.

REM ---------- check Node.js ----------
where node >nul 2>nul
if errorlevel 1 (
  echo   [ERROR] Node.js not found.
  echo.
  echo   Please install Node.js 18 or newer:
  echo     https://nodejs.org/en/download
  echo.
  echo   Use the default options during setup, then run this file again.
  echo.
  pause
  exit /b 1
)

for /f "tokens=*" %%v in ('node -v 2^>nul') do set "NODEVER=%%v"
echo   Node.js %NODEVER%

REM ---------- check dependencies ----------
if exist "bridge\node_modules\ws\package.json" goto deps_ok

echo   First run: installing dependencies, about 10 seconds...
echo.
pushd bridge
call npm install --no-audit --no-fund
set "NPMRC=%errorlevel%"
popd

if not "%NPMRC%"=="0" (
  echo.
  echo   [ERROR] npm install failed. This is usually a network problem.
  echo.
  echo   Try a China mirror, then run this file again:
  echo     npm config set registry https://registry.npmmirror.com
  echo.
  pause
  exit /b 1
)
echo.

:deps_ok
echo   Ready. Opening http://localhost:8848 in your browser.
echo.
echo   On your phone, connect to the same WiFi and open the
echo   192.168.x.x:8848 address printed below.
echo.
echo   Press Ctrl+C to stop the service.
echo.

start "" http://localhost:8848
node bridge\server.js

echo.
echo   Service stopped.
pause

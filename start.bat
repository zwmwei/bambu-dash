@echo off
chcp 65001 >nul
title Bambu P2S 看板
cd /d "%~dp0"
echo.
echo   Bambu P2S 看板 · 桥接服务
echo   ----------------------------------------
echo   浏览器会自动打开，手机请用同一 WiFi 访问
echo   画面上的地址（端口 8848）
echo.
start "" http://localhost:8848
node bridge\server.js
pause

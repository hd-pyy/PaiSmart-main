@echo off
REM 设置 Node.js 路径
set PATH=D:\Code\Develop\nodejs;%PATH%
REM 启动开发服务器
cd /d D:\Code\Project\PaiSmart-main\frontend
pnpm dev
pause

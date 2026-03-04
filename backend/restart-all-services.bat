@echo off
echo ========================================
echo Day 4: 重新启动所有服务
echo ========================================
echo.

echo 正在停止旧容器...
docker stop careplan-backend careplan-postgres careplan-redis 2>nul
docker rm careplan-backend careplan-postgres careplan-redis 2>nul

echo.
echo 正在启动所有服务...
cd /d "%~dp0"
docker-compose up -d --build

echo.
echo 等待 30 秒让服务完全启动...
timeout /t 30 /nobreak

echo.
echo ========================================
echo 检查容器状态
echo ========================================
docker ps -a | findstr "careplan"

echo.
echo ========================================
echo 测试 Redis 连接
echo ========================================
docker exec careplan-redis redis-cli ping 2>nul
if %errorlevel% equ 0 (
    echo ✅ Redis 正常运行
) else (
    echo ❌ Redis 未运行
)

echo.
echo ========================================
echo 查看 Backend 日志（最后 20 行）
echo ========================================
docker logs careplan-backend --tail 20

echo.
pause


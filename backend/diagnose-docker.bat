@echo off
echo ========================================
echo 诊断 Docker 容器状态
echo ========================================
echo.

echo 1. 检查 Docker 是否运行...
docker version
echo.

echo 2. 查看所有容器...
docker ps -a
echo.

echo 3. 查看 Redis 容器...
docker inspect careplan-redis 2>nul
if %errorlevel% neq 0 (
    echo Redis 容器不存在！
) else (
    echo Redis 容器存在
)
echo.

echo 4. 查看 Backend 容器...
docker inspect careplan-backend 2>nul
if %errorlevel% neq 0 (
    echo Backend 容器不存在！
) else (
    echo Backend 容器存在
)
echo.

echo 5. 查看 Postgres 容器...
docker inspect careplan-postgres 2>nul
if %errorlevel% neq 0 (
    echo Postgres 容器不存在！
) else (
    echo Postgres 容器存在
)
echo.

pause


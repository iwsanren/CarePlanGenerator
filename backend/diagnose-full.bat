@echo off
setlocal enabledelayedexpansion

echo ========================================
echo Day 4 - 完整诊断脚本
echo ========================================
echo.

echo [1/8] 检查 Docker 服务...
docker version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ Docker 未运行
    pause
    exit /b 1
)
echo ✅ Docker 正常运行
echo.

echo [2/8] 检查容器状态...
docker ps --format "table {{.Names}}\t{{.Status}}" | findstr careplan
echo.

echo [3/8] 测试 Redis 连接...
docker exec careplan-redis redis-cli ping >nul 2>&1
if %errorlevel% equ 0 (
    echo ✅ Redis 正常响应 PONG
) else (
    echo ❌ Redis 连接失败
)
echo.

echo [4/8] 检查 Redis 环境变量...
for /f "delims=" %%i in ('docker exec careplan-backend sh -c "echo $SPRING_REDIS_HOST" 2^>nul') do set REDIS_HOST=%%i
for /f "delims=" %%i in ('docker exec careplan-backend sh -c "echo $SPRING_REDIS_PORT" 2^>nul') do set REDIS_PORT=%%i
echo SPRING_REDIS_HOST=%REDIS_HOST%
echo SPRING_REDIS_PORT=%REDIS_PORT%
echo.

echo [5/8] 检查网络...
docker network inspect backend_default >nul 2>&1
if %errorlevel% equ 0 (
    echo ✅ 网络 backend_default 存在
) else (
    echo ⚠️  网络 backend_default 不存在
)
echo.

echo [6/8] 查看最新日志（最后20行）...
echo ----------------------------------------
docker logs careplan-backend --tail 20
echo ----------------------------------------
echo.

echo [7/8] 发送测试请求...
curl -X POST http://localhost:8080/api/orders ^
  -H "Content-Type: application/json" ^
  -d "{\"patientFirstName\":\"Test\",\"patientLastName\":\"User\",\"patientMrn\":\"999999\",\"patientDateOfBirth\":\"1990-01-01\",\"providerName\":\"Dr. Test\",\"providerNpi\":\"9999999999\",\"medicationName\":\"Test Med\",\"primaryDiagnosis\":\"A00.0\",\"additionalDiagnosis\":\"B00.0\",\"medicationHistory\":\"None\",\"patientRecords\":\"Test\"}" ^
  -w "\n"
echo.

echo 等待3秒...
timeout /t 3 /nobreak >nul

echo [8/8] 查看请求后的日志（最后30行）...
echo ----------------------------------------
docker logs careplan-backend --tail 30
echo ----------------------------------------
echo.

echo ========================================
echo 诊断完成
echo ========================================
echo.
echo 请检查上面的输出：
echo 1. Redis 是否正常 (PONG)
echo 2. 环境变量是否正确 (redis / 6379)
echo 3. 日志中是否有错误信息
echo 4. 测试请求是否成功
echo.

pause


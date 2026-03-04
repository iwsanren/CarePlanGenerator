@echo off
REM Day 4 - 异步队列测试脚本

echo ========================================
echo Day 4: 测试异步消息队列
echo ========================================
echo.

echo 1. 停止并清理旧容器...
docker-compose down -v

echo.
echo 2. 重新构建并启动...
docker-compose up --build -d

echo.
echo 3. 等待服务启动（30秒）...
timeout /t 30 /nobreak

echo.
echo ========================================
echo 服务已启动！
echo ========================================
echo.
echo 📋 测试步骤：
echo.
echo 1️⃣ 提交订单（应该很快返回，~100ms）：
echo    POST http://localhost:8080/api/orders
echo    Body: 见 notes/DAY4_Asynchronous/TESTING_GUIDE.md
echo.
echo 2️⃣ 查看 Redis 队列：
echo    docker exec -it careplan-redis redis-cli
echo    127.0.0.1:6379^> LLEN careplan:queue
echo    127.0.0.1:6379^> LRANGE careplan:queue 0 -1
echo.
echo 3️⃣ 查询订单（status 应该还是 PENDING）：
echo    GET http://localhost:8080/api/orders/1
echo.
echo ========================================
echo 📝 重要提示：
echo ========================================
echo - Day 4 的任务会一直在队列里（没有 Worker 处理）
echo - status 会一直是 PENDING（这是预期的）
echo - Day 5 会写 Worker 来处理队列里的任务
echo.
echo ========================================
echo 🔧 实用命令：
echo ========================================
echo 查看日志：docker-compose logs -f backend
echo 停止服务：docker-compose down
echo 进入 Redis：docker exec -it careplan-redis redis-cli
echo ========================================

pause


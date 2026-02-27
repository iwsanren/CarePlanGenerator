@echo off
REM ==========================================
REM Day 3: 测试 Mock Data 导入
REM ==========================================

echo.
echo ==========================================
echo Day 3: 验证 Mock Data
echo ==========================================
echo.

REM 等待用户按键继续
echo 请确保项目已经启动（run-local.bat）
echo.
pause

REM 检查 PostgreSQL 容器是否运行
echo.
echo [1/4] 检查数据库容器...
docker ps | findstr careplan-postgres >nul
if %errorlevel% neq 0 (
    echo ❌ PostgreSQL 容器未运行！
    echo 请先运行: .\run-local.bat
    pause
    exit /b 1
)
echo ✅ PostgreSQL 容器正在运行

REM 检查 Providers
echo.
echo [2/4] 检查 Providers 表...
docker exec careplan-postgres psql -U careplan_user -d careplan -t -c "SELECT COUNT(*) FROM providers;" > temp.txt
set /p count=<temp.txt
del temp.txt
if "%count%"==" 3" (
    echo ✅ Providers 表有 3 条记录
) else (
    echo ❌ Providers 表记录数不对: %count%
)

REM 检查 Patients
echo.
echo [3/4] 检查 Patients 表...
docker exec careplan-postgres psql -U careplan_user -d careplan -t -c "SELECT COUNT(*) FROM patients;" > temp.txt
set /p count=<temp.txt
del temp.txt
if "%count%"==" 5" (
    echo ✅ Patients 表有 5 条记录
) else (
    echo ❌ Patients 表记录数不对: %count%
)

REM 检查 Orders
echo.
echo [4/4] 检查 Orders 表...
docker exec careplan-postgres psql -U careplan_user -d careplan -t -c "SELECT COUNT(*) FROM orders;" > temp.txt
set /p count=<temp.txt
del temp.txt
if "%count%"==" 7" (
    echo ✅ Orders 表有 7 条记录
) else (
    echo ❌ Orders 表记录数不对: %count%
)

REM 显示详细数据
echo.
echo ==========================================
echo 数据概览
echo ==========================================
echo.
docker exec careplan-postgres psql -U careplan_user -d careplan -c "SELECT p.first_name || ' ' || p.last_name as patient_name, pr.name as provider_name, o.medication_name, cp.status FROM orders o JOIN patients p ON o.patient_id = p.id JOIN providers pr ON o.provider_id = pr.id LEFT JOIN care_plans cp ON cp.order_id = o.id ORDER BY o.created_at DESC;"

echo.
echo ==========================================
echo 验证完成！
echo ==========================================
echo.
echo 如果所有检查都通过（✅），说明 Mock Data 导入成功！
echo.
echo 下一步：
echo 1. 打开 TablePlus 连接数据库查看数据
echo 2. 访问 http://localhost:8080 测试前端
echo.
pause


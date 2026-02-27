@echo off
echo ========================================
echo Starting CarePlan Generator (Day 2-3)
echo ========================================
echo.

REM Check if .env file exists
if not exist .env (
    echo [ERROR] .env file not found!
    echo.
    echo Please copy .env.example to .env and add your API key:
    echo   copy .env.example .env
    echo.
    echo Then edit .env and add your OpenAI/Claude API key.
    echo.
    pause
    exit /b 1
)

REM Check if Docker is running
docker info >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Docker is not running!
    echo.
    echo Please start Docker Desktop and try again.
    echo.
    pause
    exit /b 1
)

echo Starting all services with Docker Compose...
echo.
echo This will:
echo   1. Start PostgreSQL database
echo   2. Build and start Spring Boot application
echo   3. Auto-import Mock Data (Day 3)
echo.

REM Start services
docker-compose up --build

REM This will keep running until you press Ctrl+C
REM When stopped, containers will keep running in background
REM To stop: docker-compose down


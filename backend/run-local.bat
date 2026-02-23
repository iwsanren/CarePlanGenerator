@echo off
echo ========================================
echo Starting CarePlan Generator (Local)
echo ========================================
echo.

REM Check if .env file exists
if not exist .env (
    echo [ERROR] .env file not found!
    echo Please copy .env.example to .env and add your API key.
    echo.
    echo Run: copy .env.example .env
    pause
    exit /b 1
)

REM Start PostgreSQL with Docker
echo Starting PostgreSQL database...
docker run -d --name careplan-postgres -e POSTGRES_DB=careplan -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:15-alpine

REM Wait for database to be ready
echo Waiting for database to start...
timeout /t 5 /nobreak > nul

REM Load environment variables from .env
for /f "tokens=1,2 delims==" %%a in (.env) do (
    if not "%%a"=="" if not "%%a:~0,1%"=="#" (
        set %%a=%%b
    )
)

REM Run Spring Boot application
echo Starting Spring Boot application...
echo.
mvnw.cmd spring-boot:run

pause


@echo off
echo ================================
echo Testing Search and Download Features
echo ================================
echo.

echo Step 1: Cleaning and compiling...
call mvn clean compile
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed!
    exit /b 1
)
echo [SUCCESS] Compilation successful!
echo.

echo Step 2: Starting services...
echo Please run: docker-compose up --build
echo.

echo Step 3: Test the features
echo - Visit http://localhost:8080
echo - Create 2-3 orders with different patient names
echo - Test search by name
echo - Test search by MRN
echo - Download completed care plans
echo.

echo For detailed testing guide, see: SEARCH_DOWNLOAD_GUIDE.md
pause


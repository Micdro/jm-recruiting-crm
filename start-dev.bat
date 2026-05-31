@echo off
setlocal

echo ==========================================
echo Restarting JM Recruiting CRM dev services
echo ==========================================
echo.

REM Kill anything running on the backend port
call :kill_port 8080

REM Kill anything running on the frontend port
call :kill_port 5173

echo.
echo Starting backend...
start "JM CRM Backend" cmd /k "cd /d %~dp0backend && mvnw spring-boot:run"

echo Starting frontend...
start "JM CRM Frontend" cmd /k "cd /d %~dp0frontend && npm run dev"

echo.
echo Services are starting in separate terminal windows.
echo Backend:  http://localhost:8080
echo Frontend: http://localhost:5173
echo.
pause
exit /b


:kill_port
set PORT=%1

echo Checking for process on port %PORT%...

for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%PORT%') do (
    echo Killing process %%a on port %PORT%...
    taskkill /PID %%a /F >nul 2>&1
)

echo Port %PORT% is clear.
exit /b
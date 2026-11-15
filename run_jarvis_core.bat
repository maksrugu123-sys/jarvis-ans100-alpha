@echo off
setlocal
cd /d "%~dp0"

set "JARVIS_EXE=%~dp0JARVIS\JARVIS.exe"
set "JARVIS_LOG_DIR=%LOCALAPPDATA%\JARVIS\logs"
set "JARVIS_LOG=%JARVIS_LOG_DIR%\startup.log"

if not exist "%JARVIS_LOG_DIR%" mkdir "%JARVIS_LOG_DIR%" >nul 2>&1
if not exist "%JARVIS_EXE%" (
    echo JARVIS.exe package was not found: "%JARVIS_EXE%"
    pause
    exit /b 1
)

echo Starting JARVIS. Diagnostic log: "%JARVIS_LOG%"
echo ---- JARVIS startup %DATE% %TIME% ---->>"%JARVIS_LOG%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "& $env:JARVIS_EXE --port 8765 --platform desktop 2>&1 | Tee-Object -FilePath $env:JARVIS_LOG -Append; exit $LASTEXITCODE"
set "JARVIS_EXIT_CODE=%ERRORLEVEL%"

echo.
echo JARVIS core stopped with exit code %JARVIS_EXIT_CODE%.
echo Diagnostic log: "%JARVIS_LOG%"
if not "%JARVIS_EXIT_CODE%"=="0" (
    echo.
    echo The startup error is shown above and saved in the log.
)
echo Keep this window open so the error can be inspected.
pause
exit /b %JARVIS_EXIT_CODE%

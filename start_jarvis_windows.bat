@echo off
setlocal
cd /d "%~dp0"

set "JARVIS_URL=http://127.0.0.1:8765/"
set "PYTHONUTF8=1"
set "PYTHONUNBUFFERED=1"

echo.
echo  JARVIS ANS-100 - Windows 10
echo  ----------------------------
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command "$ok=$false; for($i=0;$i -lt 2;$i++){try{$h=Invoke-RestMethod 'http://127.0.0.1:8765/api/health' -TimeoutSec 2;if($h.status -eq 'ok' -and $h.platform -eq 'desktop' -and $h.auth_required -eq $false -and $h.auth_scheme -eq 'none'){$ok=$true;break}}catch{};Start-Sleep -Seconds 1};if($ok){exit 0}else{exit 1}" >nul 2>&1
if errorlevel 1 (
    echo Starting local JARVIS core...
    where py >nul 2>&1
    if not errorlevel 1 (
        start "JARVIS Core - keep this window open" /D "%~dp0" cmd /k py -3 -m tools.jarvis_app --port 8765 --platform desktop
    ) else (
        where python >nul 2>&1
        if errorlevel 1 (
            echo Python 3 was not found.
            echo Install Python 3 and enable "Add Python to PATH", then run this file again.
            pause
            exit /b 1
        )
        start "JARVIS Core - keep this window open" /D "%~dp0" cmd /k python -m tools.jarvis_app --port 8765 --platform desktop
    )
) else (
    echo A JARVIS service is already responding on port 8765.
)

echo Waiting for JARVIS...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ready=$false;for($i=0;$i -lt 40;$i++){try{$h=Invoke-RestMethod 'http://127.0.0.1:8765/api/health' -TimeoutSec 2;if($h.status -eq 'ok' -and $h.platform -eq 'desktop' -and $h.auth_required -eq $false -and $h.auth_scheme -eq 'none'){$ready=$true;break}}catch{};Start-Sleep -Seconds 1};if($ready){exit 0}else{exit 1}"
if errorlevel 1 (
    echo JARVIS did not start. Check the JARVIS Core window for the error.
    pause
    exit /b 1
)

if exist "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" (
    start "" "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" --app=%JARVIS_URL%
) else if exist "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" (
    start "" "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" --app=%JARVIS_URL%
) else if exist "%LOCALAPPDATA%\Microsoft\Edge\Application\msedge.exe" (
    start "" "%LOCALAPPDATA%\Microsoft\Edge\Application\msedge.exe" --app=%JARVIS_URL%
) else if exist "%ProgramFiles%\Google\Chrome\Application\chrome.exe" (
    start "" "%ProgramFiles%\Google\Chrome\Application\chrome.exe" --app=%JARVIS_URL%
) else if exist "%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe" (
    start "" "%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe" --app=%JARVIS_URL%
) else if exist "%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe" (
    start "" "%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe" --app=%JARVIS_URL%
) else (
    echo Edge / Chrome not found in common install locations.
    echo Opening the default browser instead.
    start "" "%JARVIS_URL%"
)

echo.
echo JARVIS opened. Local desktop mode connects automatically; no token is needed.
echo Keep the Core window open while using JARVIS.
echo.
endlocal

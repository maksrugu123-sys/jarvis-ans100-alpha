@echo off
setlocal
cd /d "%~dp0"

set "JARVIS_URL=http://127.0.0.1:8765/"
set "JARVIS_EXE=%~dp0JARVIS\JARVIS.exe"
set "JARVIS_CORE_LAUNCHER=%~dp0run_jarvis_core.bat"

if not exist "%JARVIS_EXE%" (
    echo JARVIS.exe package was not found.
    echo Extract the complete JARVIS folder from the build archive next to this file.
    pause
    exit /b 1
)
if not exist "%JARVIS_CORE_LAUNCHER%" (
    echo The JARVIS core launcher was not found.
    echo Re-download and extract the complete ZIP archive.
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "$ok=$false;for($i=0;$i -lt 2;$i++){try{$r=Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8765/api/health' -TimeoutSec 2;if($r.StatusCode -eq 200){$ok=$true;break}}catch{};Start-Sleep -Seconds 1};if($ok){exit 0}else{exit 1}" >nul 2>&1
if errorlevel 1 (
    start "JARVIS Core - keep this window open" /D "%~dp0" "%JARVIS_CORE_LAUNCHER%"
) else (
    echo A JARVIS service is already responding on port 8765.
)

echo Waiting for JARVIS...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ready=$false;for($i=0;$i -lt 40;$i++){try{$r=Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8765/api/health' -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 1};if($ready){exit 0}else{exit 1}"
if errorlevel 1 (
    echo JARVIS did not start. Check the JARVIS Core window and startup log:
    echo %%LOCALAPPDATA%%\JARVIS\logs\startup.log
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
    start "" "%JARVIS_URL%"
)

echo JARVIS opened. Enter the one-time token shown in the Core window.
echo Keep the Core window open while using JARVIS.
endlocal

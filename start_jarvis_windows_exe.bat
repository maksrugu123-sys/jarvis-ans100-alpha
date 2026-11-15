@echo off
setlocal
cd /d "%~dp0"

set "JARVIS_EXE=%~dp0JARVIS\JARVIS.exe"
set "JARVIS_LOG_DIR=%LOCALAPPDATA%\JARVIS\logs"
set "JARVIS_LOG=%JARVIS_LOG_DIR%\startup.log"
set "JARVIS_ERR=%JARVIS_LOG_DIR%\startup-error.log"

title JARVIS - запуск и управление

if not exist "%JARVIS_EXE%" (
    echo [ОШИБКА] Не найден JARVIS.exe.
    echo Распакуйте весь ZIP-архив, не извлекая отдельные файлы.
    pause
    exit /b 1
)
if not exist "%JARVIS_LOG_DIR%" mkdir "%JARVIS_LOG_DIR%" >nul 2>&1

echo ========================================
echo          JARVIS Windows
echo ========================================
echo Запуск ядра. Подождите...
echo Журнал: "%JARVIS_LOG%"
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop';" ^
  "$exe=$env:JARVIS_EXE; $log=$env:JARVIS_LOG; $err=$env:JARVIS_ERR;" ^
  "'' | Out-File -FilePath $log -Encoding utf8;" ^
  "'' | Out-File -FilePath $err -Encoding utf8;" ^
  "$p=Start-Process -FilePath $exe -ArgumentList @('--port','8765','--platform','desktop') -PassThru -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError $err;" ^
  "$ready=$false;" ^
  "for($i=0;$i -lt 45;$i++) { if($p.HasExited){break}; try{$h=Invoke-RestMethod -Uri 'http://127.0.0.1:8765/api/health' -TimeoutSec 2; if($h.status -eq 'ok' -and $h.platform -eq 'desktop'){$ready=$true;break}}catch{}; Start-Sleep -Seconds 1 };" ^
  "if(-not $ready) { Write-Host '[ОШИБКА] JARVIS не запустился.' -ForegroundColor Red; if(Test-Path $log){Get-Content $log -Tail 30}; if(Test-Path $err){Get-Content $err -Tail 30}; if(-not $p.HasExited){Stop-Process -Id $p.Id -Force}; Read-Host 'Нажмите Enter для закрытия'; exit 1 };" ^
  "Write-Host '[ГОТОВО] JARVIS запущен. Это единственное окно управления.' -ForegroundColor Green;" ^
  "$browser=@('$env:ProgramFiles(x86)\Microsoft\Edge\Application\msedge.exe','$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe','$env:LOCALAPPDATA\Microsoft\Edge\Application\msedge.exe','$env:ProgramFiles\Google\Chrome\Application\chrome.exe','$env:ProgramFiles(x86)\Google\Chrome\Application\chrome.exe','$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe') | ForEach-Object { [Environment]::ExpandEnvironmentVariables($_) } | Where-Object { Test-Path $_ } | Select-Object -First 1;" ^
  "if($browser){Start-Process -FilePath $browser -ArgumentList @('--app=http://127.0.0.1:8765/')}else{Start-Process 'http://127.0.0.1:8765/'};" ^
  "Write-Host 'Интерфейс открыт в браузере. Для остановки JARVIS нажмите Enter здесь.';" ^
  "Read-Host 'JARVIS работает';" ^
  "if(-not $p.HasExited){Stop-Process -Id $p.Id -Force};" ^
  "Write-Host 'JARVIS остановлен.'"
set "JARVIS_EXIT_CODE=%ERRORLEVEL%"

if not "%JARVIS_EXIT_CODE%"=="0" (
    echo.
    echo Запуск завершился с ошибкой. Журнал: "%JARVIS_LOG%"
    echo Дополнительный журнал: "%JARVIS_ERR%"
    pause
)
exit /b %JARVIS_EXIT_CODE%

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
  "$ErrorActionPreference='Stop'; try {" ^
  "$exe=$env:JARVIS_EXE; $log=$env:JARVIS_LOG; $err=$env:JARVIS_ERR;" ^
  "'' | Out-File -FilePath $log -Encoding utf8;" ^
  "'' | Out-File -FilePath $err -Encoding utf8;" ^
  "$p=Start-Process -FilePath $exe -ArgumentList @('--port','8765','--platform','desktop') -PassThru -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError $err;" ^
  "$ready=$false;" ^
  "for($i=0;$i -lt 45;$i++) { if($p.HasExited){break}; try{$h=Invoke-RestMethod -Uri 'http://127.0.0.1:8765/api/health' -TimeoutSec 2; if($h.status -eq 'ok' -and $h.platform -eq 'desktop' -and $h.auth_required -eq $false -and $h.auth_scheme -eq 'none'){$ready=$true;break}}catch{}; Start-Sleep -Seconds 1 };" ^
  "if(-not $ready) { Write-Host '[ОШИБКА] JARVIS не запустился.' -ForegroundColor Red; if(Test-Path $log){Get-Content $log -Tail 30}; if(Test-Path $err){Get-Content $err -Tail 30}; if(-not $p.HasExited){Stop-Process -Id $p.Id -Force}; Read-Host 'Нажмите Enter для закрытия'; exit 1 };" ^
  "Write-Host '[ГОТОВО] Окно приложения JARVIS открыто.' -ForegroundColor Green;" ^
  "$p.WaitForExit();" ^
  "Write-Host 'Приложение JARVIS закрыто.';" ^
  "} catch {" ^
  "  $message = $_.Exception.ToString();" ^
  "  Write-Host ''; Write-Host '[ОШИБКА ЗАПУСКА JARVIS]' -ForegroundColor Red;" ^
  "  Write-Host $message -ForegroundColor Yellow;" ^
  "  try { Add-Content -Path $err -Value ('LAUNCHER ERROR: ' + $message) -Encoding utf8 } catch {};" ^
  "  Write-Host ('Журнал: ' + $log); Write-Host ('Журнал ошибок: ' + $err);" ^
  "  if(Test-Path $log){Write-Host '--- startup.log ---'; Get-Content $log -Tail 40};" ^
  "  if(Test-Path $err){Write-Host '--- startup-error.log ---'; Get-Content $err -Tail 40};" ^
  "  exit 1;" ^
  "}"
set "JARVIS_EXIT_CODE=%ERRORLEVEL%"

if not "%JARVIS_EXIT_CODE%"=="0" (
    echo.
    echo Запуск завершился с ошибкой.
    echo Скопируйте текст ошибки выше или отправьте файлы:
    echo "%JARVIS_LOG%"
    echo "%JARVIS_ERR%"
    pause
)
exit /b %JARVIS_EXIT_CODE%

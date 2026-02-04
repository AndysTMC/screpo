@echo off
echo ================================================
echo  Terminating HIDDEN Chrome and ChromeDriver instances
echo  (Visible Chrome windows will NOT be affected)
echo ================================================

:: Kill hidden chrome.exe processes (no visible window)
echo.
echo Killing hidden chrome.exe processes...
powershell -Command "Get-Process chrome -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -eq 0 } | Stop-Process -Force"
echo [DONE] Hidden chrome.exe processes terminated (if any).

:: Kill all chromedriver.exe processes (always hidden)
echo.
echo Killing chromedriver.exe processes...
taskkill /F /IM chromedriver.exe /T 2>nul
if %errorlevel% equ 0 (
    echo [SUCCESS] chromedriver.exe processes terminated.
) else (
    echo [INFO] No chromedriver.exe processes found.
)

echo.
echo ================================================
echo  Cleanup complete!
echo ================================================

@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Stop-SDV-Local.ps1"
exit /b %ERRORLEVEL%

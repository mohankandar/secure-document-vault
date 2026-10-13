@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start-SDV-Local.ps1"
exit /b %ERRORLEVEL%

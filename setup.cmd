@echo off
rem Vero setup command (task 0041). See setup.ps1 and docs\install\SETUP.md.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1" %*
exit /b %ERRORLEVEL%

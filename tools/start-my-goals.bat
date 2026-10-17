@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0gradle.ps1" --no-daemon run
if errorlevel 1 pause

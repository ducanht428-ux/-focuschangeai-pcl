@echo off
chcp 65001 >nul
cd /d "%~dp0"
title FocusChange AI - chay offline
set PORT=8765
where py >nul 2>nul
if %errorlevel%==0 (
  py -3 serve.py %PORT%
  goto :end
)
where python >nul 2>nul
if %errorlevel%==0 (
  python serve.py %PORT%
  goto :end
)
rem Khong co Python: dung PowerShell co san cua Windows
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0serve.ps1" -Port %PORT%
:end

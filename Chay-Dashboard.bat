@echo off
rem FocusChangeAI - opens the local dashboard (8 screens) in your web browser.
rem Double-click this file. A browser tab opens at http://127.0.0.1:8730 .
rem Ctrl+C in this black window stops the server. Everything stays on this PC (offline).
cd /d "%~dp0"

rem Same interpreter fallback as Chay-Cam.bat: PATH, py launcher, then known paths.
set "PY="
for /f "delims=" %%i in ('where python.exe 2^>nul') do if not defined PY set "PY=%%i"
if exist "%PY%" goto found

for /f "delims=" %%i in ('py -3.12 -c "import sys;print(sys.executable)" 2^>nul') do set "PY=%%i"
if exist "%PY%" goto found

for /f "delims=" %%i in ('py -c "import sys;print(sys.executable)" 2^>nul') do set "PY=%%i"
if exist "%PY%" goto found

set "PY=C:\Users\%USERNAME%\AppData\Local\Programs\Python\Python313\python.exe"
if exist "%PY%" goto found
set "PY=C:\Users\%USERNAME%\AppData\Local\Programs\Python\Python312\python.exe"
if exist "%PY%" goto found

echo Python was not found on this PC.
echo Install Python 3.12 from python.org and tick "Add python.exe to PATH", then try again.
pause
exit /b 1

:found
echo Using Python at %PY%
echo.

rem The dashboard itself only needs PyYAML to read config and serve the UI. The heavy camera
rem libraries (mediapipe / ultralytics) are optional: without them the screens still work and
rem the Camera screen simply reports what is missing instead of crashing.
"%PY%" -c "import yaml" 1>nul 2>nul
if not errorlevel 1 goto run

echo The dashboard needs PyYAML, which is not installed for this Python yet.
echo Open a Command Prompt in this folder and run:
echo     %PY% -m pip install pyyaml
echo Then double-click this file again.
pause
exit /b 1

:run
echo Starting the dashboard at http://127.0.0.1:8730 ...
echo A browser tab will open. If it does not, open that address yourself.
echo.
echo Tips:
echo   - Sao luu ^> "Nap du lieu mau" to fill the screens with labelled DEMO data for a first look.
echo   - Same screen, "Xoa du lieu mau" removes only the demo, never real measurements.
echo   - Camera ^> "Bat dau" needs mediapipe + ultralytics + face_landmarker.task; the screen
echo     lists exactly what is missing if they are not installed yet.
echo.
"%PY%" -u dashboard/server.py

rem Thanh cong (ke ca truong hop "dashboard dang chay san, chi mo trinh duyet") thi cua so
rem den tu dong dong, nguoi dung khong phai bam phim thua. Chi giu man hinh lai khi co loi
rem (errorlevel 1) de doc thong bao loi Python / thieu PyYAML.
if errorlevel 1 pause
@echo off
rem FocusChangeAI - opens the classroom camera leg on this PC (no phone needed).
rem Double-click this file, then sit 60-80 cm in front of the webcam, facing the light.
rem ESC closes the picture window; Ctrl+C in this black window stops the program.
cd /d "%~dp0"

rem This machine's known-good interpreter is tried first. On another PC that line fails,
rem so the launcher falls back to the Windows Python launcher and then to PATH, instead of
rem asking anyone to edit this file. py.exe comes from python.org's installer; a Microsoft
rem Store Python does not ship it, which is why the plain "python.exe" name is tried last.
set "PY=C:\Users\Admin\AppData\Local\Programs\Python\Python312\python.exe"
if exist "%PY%" goto found

set "PY="
for /f "delims=" %%i in ('py -3.12 -c "import sys;print(sys.executable)" 2^>nul') do set "PY=%%i"
if exist "%PY%" goto found

set "PY="
for /f "delims=" %%i in ('py -c "import sys;print(sys.executable)" 2^>nul') do set "PY=%%i"
if exist "%PY%" goto found

set "PY="
for /f "delims=" %%i in ('where python.exe 2^>nul') do if not defined PY set "PY=%%i"
if exist "%PY%" goto found

echo Python was not found on this PC.
echo Install Python 3.12 from python.org and tick the box "Add python.exe to PATH"
echo on the first page of the installer, then double-click this file again.
pause
exit /b 1

:found
echo Using Python at %PY%
echo.

rem One import line catches every missing dependency at once, before the window opens.
rem Without this the program can start, show a black window, and die on the first frame.
rem 1>nul keeps Python's "Traceback" noise off the screen - the message below replaces it, and
rem 2>nul is not enough because some installers report the missing module on the normal output.
"%PY%" -c "import cv2, numpy, mediapipe, ultralytics, yaml, PIL" 1>nul 2>nul
if not errorlevel 1 goto run

echo The camera program needs libraries that are not installed for this Python yet.
echo Open a Command Prompt in this folder and run:
echo     %PY% -m pip install -r requirements.txt
echo Then double-click this file again.
pause
exit /b 1

:run
echo Starting the camera...
echo The first 5-10 seconds look frozen while the model loads. That is normal, wait.
echo Watch the top-left corner of the picture window for a line like "SEI 81".
echo If the line "identity off" appears, press 1-9 for a head and then M to name it: a session is
echo only stored for a face that carries a student id, and M is the way to give one without ArcFace.
echo.
"%PY%" -u main.py --show

echo.
echo Finished.
rem Said here rather than discovered later: a session row is keyed by student id, and classroom.py
rem refuses to store one for a guest - _retire and _write_heartbeats both require a student_id. So a
rem run that never named anybody leaves the ten tables empty, however long it measured. M is the fix;
rem a named head is stored with identity "manual" in its notes so a declared name is never mistaken
rem for a face match. The live view is written in every case.
echo If the log above says "identity off" and no head was named with M, this run stored NO sessions:
echo every face stayed a guest and only its live view was written (out\class_live_*.json).
echo Sessions that were stored - recognised or named with M - land on this machine in:
echo   ..\data\focuschange.db          (the measurements: sessions, features, bands, alerts)
echo   out\class_live_*.json, out\presence_*.json   (the last few seconds, as they were)
echo To see what is in the database:  %PY% ..\database\focuschange_db.py
pause

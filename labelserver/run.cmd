@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist venv\Scripts\python.exe (
    echo Creating virtual environment...
    py -3 -m venv venv || goto :fail
    venv\Scripts\python -m pip install -r requirements.txt || goto :fail
)
venv\Scripts\python server.py
pause
exit /b 0
:fail
echo Setup failed. Install Python 3 from python.org and try again.
pause
exit /b 1

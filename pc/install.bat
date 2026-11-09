@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Installing libraries for Aakes PC...
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
echo.
echo Done. Run run.bat
pause

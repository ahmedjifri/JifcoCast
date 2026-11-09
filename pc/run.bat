@echo off
chcp 65001 >nul
cd /d "%~dp0"
python aakes_pc.py
if errorlevel 1 pause

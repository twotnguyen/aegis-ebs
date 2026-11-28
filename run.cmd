@echo off
setlocal
set "BASH=C:\Program Files\Git\bin\bash.exe"
if not exist "%BASH%" set "BASH=C:\Program Files\Git\usr\bin\bash.exe"
if not exist "%BASH%" (
  echo Git Bash not found. Install Git for Windows, or run from Git Bash: ./run
  exit /b 1
)
"%BASH%" "%~dp0run" %*

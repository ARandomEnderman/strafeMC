@echo off
rem The only sanctioned way to start the private CS2 copy. All checks live in launch-cs2.ps1.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0launch-cs2.ps1" %*
exit /b %ERRORLEVEL%

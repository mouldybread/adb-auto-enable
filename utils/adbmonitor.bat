@echo off
setlocal enabledelayedexpansion

REM Check if target argument is provided
if "%~1"=="" (
    echo ERROR: No target specified
    echo.
    echo Usage: %~nx0 [IP:PORT]
    echo Example: %~nx0 192.168.1.1:5555
    echo.
    pause
    exit /b 1
)

set TARGET=%~1

echo ========================================
echo ADB Connection Monitor
echo ========================================
echo Target: %TARGET%
echo Duration: 5 minutes (300 seconds)
echo ========================================
echo.

set /a elapsed=0
set /a attempt=0
set /a maxtime=300

:loop
set /a attempt+=1
set /a elapsed+=5

echo [%date% %time%] Attempt %attempt% (Elapsed: %elapsed%s / %maxtime%s)

adb connect %TARGET% > temp_output.txt 2>&1

findstr /C:"connected to" temp_output.txt > nul
if %errorlevel% equ 0 (
    echo.
    echo ========================================
    echo SUCCESS! Connected at attempt %attempt%
    echo Time elapsed: %elapsed% seconds
    echo ========================================
    type temp_output.txt
    del temp_output.txt
    goto end
)

findstr /C:"already connected" temp_output.txt > nul
if %errorlevel% equ 0 (
    echo.
    echo ========================================
    echo SUCCESS! Already connected
    echo Time elapsed: %elapsed% seconds
    echo ========================================
    type temp_output.txt
    del temp_output.txt
    goto end
)

echo Failed. Response: 
type temp_output.txt
echo.

if %elapsed% geq %maxtime% (
    echo.
    echo ========================================
    echo TIMEOUT after %attempt% attempts
    echo Total time: %elapsed% seconds
    echo ========================================
    del temp_output.txt
    goto end
)

timeout /t 5 /nobreak > nul
goto loop

:end
echo.
echo Script completed.
pause

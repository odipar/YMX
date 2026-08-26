@echo off
rem Hear a YM tune: build a program from it with ym-to-ymx, then play it
rem under Hatari. Windows; ymxplay.sh is the same for macOS and Linux.
rem
rem   ymxplay.cmd tune.ym [more.ym ...]    SPACE or ESC in the window stops
rem   ymxplay.cmd -perf tune.ym            any ym-to-ymx option passes through
rem
rem HATARI names the emulator and TOS its ROM image:
rem
rem   set HATARI=C:\hatari\hatari.exe
rem   set TOS=C:\hatari\tos.img
rem
rem Every argument goes to ym-to-ymx, so HATARI_OPTS is where the emulator's
rem own options go.
setlocal
set HERE=%~dp0
if "%YM_TO_YMX%"=="" set YM_TO_YMX=%HERE%ym-to-ymx.exe
if "%HATARI%"=="" set HATARI=hatari.exe

if "%~1"=="" (
    echo usage: ymxplay.cmd [ym-to-ymx options] tune.ym [more.ym ...] 1>&2
    echo   set HATARI= to the emulator, TOS= to its ROM image. 1>&2
    exit /b 1
)
if not exist "%YM_TO_YMX%" (
    echo ymxplay: no ym-to-ymx beside this script. Set YM_TO_YMX= to one. 1>&2
    exit /b 1
)

set WORK=%TEMP%\ymxplay.%RANDOM%
mkdir "%WORK%"
"%YM_TO_YMX%" -f "%WORK%\play.prg" %*
if errorlevel 1 exit /b 1

if "%TOS%"=="" (
    "%HATARI%" --machine st --cpuclock 8 --compatible on --memsize 4 %HATARI_OPTS% "%WORK%\play.prg"
) else (
    "%HATARI%" --tos "%TOS%" --machine st --cpuclock 8 --compatible on --memsize 4 %HATARI_OPTS% "%WORK%\play.prg"
)
rmdir /s /q "%WORK%"
endlocal

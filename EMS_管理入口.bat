@echo off
chcp 65001 >nul
setlocal
:menu
cls
echo =========== EMS v1.2.2 (Attendance QA) ===========
echo 1. Start v1.2.2
echo 2. Stop v1.2.2
echo 3. Status v1.2.2
echo 4. Network Mode v1.2.2
echo 5. Initialize v1.2.2
echo 6. Logs v1.2.2
echo 7. Local Entry Code v1.2.2
echo 0. Exit
choice /c 12345670 /n /m "Select: "
set "choiceNo=%ERRORLEVEL%"
if "%choiceNo%"=="8" exit /b 0
if "%choiceNo%"=="1" call "%~dp0scripts\rc2\RC2_Start_Server.bat"
if "%choiceNo%"=="2" call "%~dp0scripts\rc2\RC2_Stop_Server.bat"
if "%choiceNo%"=="3" call "%~dp0scripts\rc2\RC2_Status.bat"
if "%choiceNo%"=="4" call "%~dp0scripts\rc2\RC2_Network_Mode.bat"
if "%choiceNo%"=="5" call "%~dp0scripts\rc2\RC2_Initialize_Server.bat"
if "%choiceNo%"=="6" call "%~dp0scripts\rc2\RC2_View_Logs.bat"
if "%choiceNo%"=="7" call "%~dp0scripts\rc2\RC2_Local_Entry_Code.bat"
goto menu
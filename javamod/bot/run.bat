@echo off
chcp 65001 >nul
cd /d "%~dp0"
rem ClaudeBot014: бот-игрок для серверов MCPE 0.14. Рядом положите server.txt (адрес:порт), nick.txt и password.txt (для /login).
set /p SERVER=<server.txt
set NICK=Claude
if exist nick.txt set /p NICK=<nick.txt
set "JAVA="
where java >nul 2>nul && set "JAVA=java"
if not defined JAVA for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\*") do if exist "%%~D\bin\java.exe" set "JAVA=%%~D\bin\java.exe"
if not defined JAVA for /d %%D in ("%LOCALAPPDATA%\Programs\Eclipse Adoptium\*") do if exist "%%~D\bin\java.exe" set "JAVA=%%~D\bin\java.exe"
if not defined JAVA (echo Java не найдена: https://adoptium.net & pause & exit /b)
echo Окно не закрывай: пока оно открыто, бот в игре. Обновления ставятся сами.
:loop
if exist ClaudeBot014-new.jar move /y ClaudeBot014-new.jar ClaudeBot014.jar >nul
echo [%date% %time%] запуск бота: %SERVER% как %NICK%
"%JAVA%" -Dfile.encoding=UTF-8 -jar ClaudeBot014.jar %SERVER% --nick %NICK% --dir . --http 8780 >> bot.log 2>&1
if %errorlevel%==3 goto loop
echo Бот остановлен. Лог: bot.log
pause

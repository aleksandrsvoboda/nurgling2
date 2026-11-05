@echo off
rem Nurgling Web: play from a phone, tablet or PC browser on this network.
rem Open one of the addresses printed below and enter the host PIN shown here.
rem Home network only - never forward its port to the internet.
setlocal
cd /d "%~dp0"
set "J="
if exist "runtime\bin\java.exe" set "J=%~dp0runtime\bin\java.exe"
if not defined J if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "J=%JAVA_HOME%\bin\java.exe"
if not defined J for %%i in (java.exe) do if not "%%~$PATH:i"=="" set "J=%%~$PATH:i"
if not defined J goto nojava
"%J%" -Dsun.java2d.uiScale.enabled=false --add-exports=java.desktop/sun.awt=ALL-UNNAMED -Xss8m -Xmx512m -Dnurgling.webhost.xmx=1g -cp "%~dp0hafen.jar" nurgling.web.WebHost %*
pause
exit /b

:nojava
echo Nurgling Web could not find Java. Start Nurgling once with Nurgling.bat, which installs it.
pause
exit /b 1

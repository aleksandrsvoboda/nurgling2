@echo off
rem Nurgling Web: play from a browser on this network. Open the address printed below on a phone, tablet or PC.
rem LAN only - never forward this port to the internet.
cd /d "%~dp0"
java -Dsun.java2d.uiScale.enabled=false --add-exports=java.desktop/sun.awt=ALL-UNNAMED -Xss8m -Xmx512m -Dnurgling.webhost.xmx=1g -cp hafen.jar nurgling.web.WebHost %*
pause

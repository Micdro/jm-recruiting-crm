@echo off
cd /d "%~dp0"
echo Starting JM Recruiting CRM backend...
mvnw.cmd spring-boot:run
pause
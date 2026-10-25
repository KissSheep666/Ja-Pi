@echo off
setlocal
set "JAR=%~dp0target\jpi.jar"

if not exist "%JAR%" (
  echo jpi.jar not found. Build it first:
  echo   mvn -f "%~dp0pom.xml" package
  exit /b 1
)

java -jar "%JAR%" %*
exit /b %ERRORLEVEL%

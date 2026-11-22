@echo off
setlocal
if "%JAVA_HOME%"=="" (
  if exist "%LOCALAPPDATA%\Android\jdk\jdk-21.0.13.1+2\bin\java.exe" (
    set "JAVA_HOME=%LOCALAPPDATA%\Android\jdk\jdk-21.0.13.1+2"
  )
)
echo [MFL GameDay] Running unit tests, pipeline integration tests, lint check, and APK assembly...
call "%~dp0..\gradlew.bat" testDebugUnitTest lintDebug assembleDebug assembleRelease
if errorlevel 1 (
  echo [MFL GameDay] VERIFY FAILED
  exit /b 1
)
echo [MFL GameDay] VERIFY PASSED
exit /b 0

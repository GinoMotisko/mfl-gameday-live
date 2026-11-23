@echo off
setlocal
if "%JAVA_HOME%"=="" (
  if exist "%LOCALAPPDATA%\Android\jdk\jdk-21.0.13.1+2\bin\java.exe" (
    set "JAVA_HOME=%LOCALAPPDATA%\Android\jdk\jdk-21.0.13.1+2"
  )
)
echo [GameDay Live] Running unit tests, pipeline integration tests, lint checks, Direct APK, and Play Store AAB...
call "%~dp0..\gradlew.bat" testDirectDebugUnitTest lintDirectDebug lintVitalPlayStoreRelease assembleDirectRelease bundlePlayStoreRelease
if errorlevel 1 (
  echo [GameDay Live] VERIFY FAILED
  exit /b 1
)
node "%~dp0test-play-publish.mjs"
if errorlevel 1 (
  echo [GameDay Live] PLAY PUBLISH GATE TESTS FAILED
  exit /b 1
)
echo [GameDay Live] VERIFY PASSED
exit /b 0

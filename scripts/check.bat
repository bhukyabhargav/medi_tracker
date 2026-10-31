@echo off
echo Running MediTrack CI checks...
call gradlew.bat assembleDebug testDebugUnitTest lint
if %ERRORLEVEL% neq 0 (
    echo Checks failed!
    exit /b %ERRORLEVEL%
)
echo All checks passed successfully!

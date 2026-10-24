@ECHO OFF

SET DIRNAME=%~dp0
IF "%DIRNAME%"=="" SET DIRNAME=.
SET APP_BASE_NAME=%~n0
SET APP_HOME=%DIRNAME%

@REM Resolve APP_HOME relative to the batch file location.
FOR %%i IN ("%APP_HOME%") DO SET APP_HOME=%%~fi

SET CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

IF DEFINED JAVA_HOME GOTO findJavaFromJavaHome

SET JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
IF %ERRORLEVEL% EQU 0 GOTO execute

ECHO.
ECHO ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
ECHO.
GOTO fail

:findJavaFromJavaHome
SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
IF EXIST "%JAVA_EXE%" GOTO execute

ECHO.
ECHO ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
ECHO.
GOTO fail

:execute
@REM Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS.
SET DEFAULT_JVM_OPTS=-Xmx64m -Xms64m

"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

IF %ERRORLEVEL% EQU 0 GOTO end

:fail
EXIT /b 1

:end
@REM End local scope for the variables with windows NT shell
IF %ERRORLEVEL% EQU 0 EXIT /b 0
EXIT /b 1

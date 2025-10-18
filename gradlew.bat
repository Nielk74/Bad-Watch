@ECHO OFF
@rem Copyright 2015 the original authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.

@if "%DEBUG%" == "" @echo off
setlocal

set DIR=%~dp0
if "%DIR%" == "" set DIR=.
set APP_HOME=%DIR%

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

set JAVA_EXE=java.exe
if not "%JAVA_HOME%" == "" set JAVA_EXE=%JAVA_HOME%\bin\java.exe

if not exist "%JAVA_EXE%" (
    echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. >&2
    echo. >&2
    echo Please set the JAVA_HOME variable in your environment to match the >&2
    echo location of your Java installation. >&2
    exit /b 1
)

set CMD_LINE_ARGS=
:setArgs
if "%~1"=="" goto doneSetArgs
set CMD_LINE_ARGS=%CMD_LINE_ARGS% %1
shift
goto setArgs

:doneSetArgs
"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %CMD_LINE_ARGS%

endlocal

@echo off
REM Build all services (or a subset) via the aggregator pom in this folder.
REM
REM Usage:
REM   build.bat                                              compile every service
REM   build.bat package -DskipTests                          build all jars, skip tests
REM   build.bat test                                         run tests across the repo
REM   build.bat -pl services/video-generation-service -am compile     just the Veo service + deps
REM   build.bat clean install                                full clean + install
REM
REM Any args you pass after "build.bat" are forwarded to Maven verbatim.

setlocal
cd /d "%~dp0"

if "%~1"=="" (
    mvn -B compile
) else (
    mvn -B %*
)

endlocal

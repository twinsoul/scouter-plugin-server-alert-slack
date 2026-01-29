@echo off
REM 프로젝트 전용 Java 17 경로 설정
set JAVA_HOME=D:\Java\jdk-17
set PATH=%JAVA_HOME%\bin;%PATH%

echo Using Java: %JAVA_HOME%
java -version
echo.

mvn %*

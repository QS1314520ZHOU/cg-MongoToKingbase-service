@echo off
setlocal
cd /d "%~dp0"

echo ==========================================
echo  MongoDB to Kingbase Sync Service 打包
echo ==========================================
echo.

set "JDK_HOME="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JDK_HOME=%JAVA_HOME%"
if not defined JDK_HOME if exist "C:\Program Files\Java\jdk1.8.0_121\bin\javac.exe" set "JDK_HOME=C:\Program Files\Java\jdk1.8.0_121"
if not defined JDK_HOME goto :nojava
set "JAVA_HOME=%JDK_HOME%"
set "PATH=%JDK_HOME%\bin;%PATH%"

set "MVN_CMD="
if exist "D:\Maven\apache-maven-3.6.1\bin\mvn.cmd" set "MVN_CMD=D:\Maven\apache-maven-3.6.1\bin\mvn.cmd"
if not defined MVN_CMD for %%i in (mvn.cmd) do set "MVN_CMD=%%~$PATH:i"
if not defined MVN_CMD goto :nomvn

echo  JDK   : %JDK_HOME%
echo  Maven : %MVN_CMD%
echo.
echo [1/2] 开始打包（离线模式）...

call "%MVN_CMD%" -o clean package -DskipTests
if errorlevel 1 goto :online
goto :checkjar

:online
echo.
echo [提示] 离线打包失败，改为联网重试...
call "%MVN_CMD%" clean package -DskipTests
if errorlevel 1 goto :failed

:checkjar
if not exist "target\mongo-to-kingbase-1.0.0.jar" goto :failed
echo.
echo ==========================================
echo  打包成功
echo ==========================================
for %%f in ("target\mongo-to-kingbase-1.0.0.jar") do echo  产物: %%~ff
for %%f in ("target\mongo-to-kingbase-1.0.0.jar") do echo  大小: %%~zf 字节
echo.
echo  下一步: 上传该 jar 到服务器 /opt/mongo-to-kingbase/ 后重启服务
goto :done

:nojava
echo [错误] 未找到 JDK（需要 javac）
echo        请确认已安装 JDK8，或设置 JAVA_HOME 指向 JDK 安装目录
goto :done

:nomvn
echo [错误] 未找到 mvn.cmd
echo        请检查 Maven 安装路径（脚本内置 D:\Maven\apache-maven-3.6.1）
goto :done

:failed
echo.
echo [错误] 打包失败，请查看上方输出
goto :done

:done
echo.
pause
endlocal

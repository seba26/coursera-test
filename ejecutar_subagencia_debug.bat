@echo off
setlocal EnableExtensions EnableDelayedExpansion

title Subagencia - Modo diagnostico

SET "JAVA_HOME=C:\PROGRA~2\Java\jdk1.8.0_351"
SET "UPDATER_DIR=%~dp0"
SET "APP_BASE=C:\deploy\Subagencia"
SET "LOCAL_EXPLODED=%APP_BASE%\exploded"
SET "LOCAL_SHARED=%APP_BASE%\shared"
SET "OUT_JAR=%APP_BASE%\SUBAGENCIA_REDPAGOS_GXWS.jar"
SET "LOGO_PATH=C:\deploy\Subagencia\logo.png"

REM *** CONFIGURAR VARIABLES DE SEGURIDAD ***
SET "UPDATER_MASTER_KEY=pUfP+ogP4w98YnKy7WicTUlLS6VL6gB8"
SET "FTP_PASSWORD_ENCRYPTED=GLpZKqfrNcZ1ImUE4ARJznMt1O0itAZ1"

echo ====================================================================
echo              ACTUALIZADOR DE APLICACION ^(MODO DEBUG^)
echo ====================================================================
echo.

REM Verificar binarios
IF NOT EXIST "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] No se encuentra java.exe en "%JAVA_HOME%\bin\java.exe"
    pause
    exit /b 1
)

IF NOT EXIST "%UPDATER_DIR%Updater.jar" (
    echo [ERROR] No se encuentra Updater.jar en "%UPDATER_DIR%"
    pause
    exit /b 1
)

IF NOT EXIST "%UPDATER_DIR%lib\commons-net-3.11.1.jar" (
    echo [ERROR] No se encuentra lib\commons-net-3.11.1.jar
    pause
    exit /b 1
)

IF NOT EXIST "%UPDATER_DIR%lib\jasypt-1.9.3.jar" (
    echo [ERROR] No se encuentra lib\jasypt-1.9.3.jar
    pause
    exit /b 1
)

echo [OK] Variables de entorno configuradas
echo [OK] UPDATER_MASTER_KEY: %UPDATER_MASTER_KEY%
echo [OK] Iniciando actualizacion segura...
echo.

REM Ejecutar actualizacion mostrando stdout y stderr en consola
"%JAVA_HOME%\bin\java.exe" -jar "%UPDATER_DIR%Updater.jar" "192.168.1.32" "ftp-agencias" "@env" "FTP_PASSWORD_ENCRYPTED" "/Version" "21" "%LOCAL_EXPLODED%" "%OUT_JAR%" "%JAVA_HOME%\bin\jar.exe" "%OUT_JAR%" "%LOGO_PATH%" "/shared" "%LOCAL_SHARED%" 2>&1

IF %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Actualizacion fallida - Ver log:
    echo.
    if exist "%LOCAL_EXPLODED%\update.log" (
        type "%LOCAL_EXPLODED%\update.log"
    ) else (
        echo No se pudo generar el log
    )
    echo.
    pause
    exit /b 1
)

echo.
echo ====================================================================
echo                  ACTUALIZACION COMPLETADA
echo ====================================================================
echo Iniciando aplicacion con salida en esta misma consola...
echo.

cd /d "C:\REDPAGOS-AGENCIA-X\SUBAGENCIA_REDPAGOS"

"%JAVA_HOME%\bin\java.exe" -cp "C:\deploy\Subagencia\SUBAGENCIA_REDPAGOS_GXWS.jar;C:\REDPAGOS-AGENCIA-X\Shared\swt3.0.0.64.jar;C:\REDPAGOS-AGENCIA-X\Shared\jpos.jar;C:\REDPAGOS-AGENCIA-X\Shared\iText10.127.1.5.jar;C:\REDPAGOS-AGENCIA-X\Shared\Una10.127.1.5.jar;C:\REDPAGOS-AGENCIA-X\Shared\RXTXcomm10.127.1.5.jar;C:\REDPAGOS-AGENCIA-X\Shared\gxclassp10.2.75990.jar;C:\REDPAGOS-AGENCIA-X\Shared\commons-lang-2.42.4.jar;C:\REDPAGOS-AGENCIA-X\SUBAGENCIA_REDPAGOS\swt.mdi.win32.0.2.dll;C:\REDPAGOS-AGENCIA-X\SUBAGENCIA_REDPAGOS\swt-win32-3064.dll;C:\REDPAGOS-AGENCIA-X\SUBAGENCIA_REDPAGOS\gxdib32.dll;C:\REDPAGOS-AGENCIA-X\Shared\log4j-1.2-api-2.7.jar;C:\REDPAGOS-AGENCIA-X\Shared\log4j-api-2.7.jar;C:\deploy\Subagencia\shared\mssql-jdbc-12.8.1.jre8.jar;C:\REDPAGOS-AGENCIA-X\Shared\log4j-core-2.7.jar" -Xincgc -Xrs -Xmx1024m uwsainiciosesion 2>&1

SET "APP_EXIT=%ERRORLEVEL%"
echo.
echo [INFO] La aplicacion termino con codigo de salida: %APP_EXIT%
echo Presione una tecla para cerrar esta consola...
pause >nul

exit /b %APP_EXIT%

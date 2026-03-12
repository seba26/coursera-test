# Revisión técnica de `Updater` (Java Swing + FTP)

## Resumen ejecutivo
El código está bien estructurado, tiene buena documentación y separa correctamente la UI del trabajo pesado usando `SwingWorker`. Sin embargo, detecté varios puntos críticos que conviene corregir para mejorar **seguridad**, **robustez**, y **mantenibilidad**.

## Hallazgos principales

### 1) Gestión insegura de credenciales (crítico)
- `FTP_PASS` se maneja como `String`, lo que deja la contraseña en memoria inmutable y difícil de limpiar.
- `secureClearPassword` no borra realmente el contenido sensible: limpia un `char[]` temporal, no el `String` original.
- Existe modo de ejecución por CLI con password en argumentos (visible en procesos del SO).

**Recomendación:** usar `char[]` desde el origen, evitar el modo por argumentos en producción y preferir FTPS/SFTP.

### 2) Uso de FTP sin cifrado (crítico)
- La conexión es FTP clásico (`FTPClient`), por lo que credenciales y datos viajan en texto claro.

**Recomendación:** migrar a `FTPSClient` (TLS explícito) o SFTP (SSH).

### 3) Actualizaciones de UI fuera del EDT (alto)
- En `doInBackground` se llaman `lblStep.setText(...)` directamente.
- Swing exige que cambios de UI ocurran en EDT.

**Recomendación:** usar `publish/process` de `SwingWorker` o `SwingUtilities.invokeLater`.

### 4) Riesgo de estado FTP inconsistente con checksum remoto (alto)
- `calculateRemoteChecksum` usa `retrieveFileStream` + `completePendingCommand()`.
- Si `completePendingCommand()` devuelve `false`, puede quedar el canal de control en mal estado y afectar comandos posteriores.

**Recomendación:** validar explícitamente el retorno y reconectar/reintentar en fallo.

### 5) Manejo de errores mejorable (medio)
- En `done()`, se muestra `ex.getMessage()`; con `SwingWorker#get()` suele venir envuelto en `ExecutionException`.

**Recomendación:** desempaquetar `getCause()` y mostrar mensaje más útil + código de error.

### 6) Posibles problemas con timestamps de JAR (medio)
- `entry.getTime()` puede retornar `-1`; en ese caso `setLastModified(-1)` no aporta valor.

**Recomendación:** validar `> 0` antes de aplicar timestamp.

### 7) No hay límite de profundidad ni política de reintentos robusta (medio)
- La recursión FTP podría crecer en estructuras anómalas.
- No hay estrategia formal de backoff/retry para red inestable.

**Recomendación:** agregar máximo de profundidad/visitados y retries con backoff exponencial.

### 8) Dependencia rígida de rutas Windows y `jar.exe` (medio)
- Paths hardcodeados a `C:\...` y ejecutable específico de JDK 8.

**Recomendación:** parametrizar por entorno y resolver `jar` desde `JAVA_HOME`/`PATH`.

## Fortalezas observadas
- Buena separación de fases y logging.
- Estrategia incremental inteligente para empaquetado.
- Uso de buffers grandes para E/S.
- Limpieza de obsoletos y preservación de timestamps en sincronización.

## Priorización sugerida
1. **Bloqueante de seguridad:** FTPS/SFTP + quitar password por CLI.
2. **Confiabilidad:** EDT correcto + control de `completePendingCommand()`.
3. **Operación:** retries/backoff + manejo de errores con códigos.
4. **Mantenibilidad:** configuración portable y menos valores hardcodeados.

## Nota
Si quieres, en el siguiente paso puedo proponerte un parche concreto (diff) con:
- actualización de UI vía `publish/process`,
- manejo seguro de credenciales con `char[]`,
- validación robusta de stream FTP/checksum,
- y una interfaz de configuración más portable.

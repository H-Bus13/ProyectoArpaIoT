# Arpa Láser — guía del proyecto Kotlin y Arduino

La aplicación controla cuatro frecuencias de un arpa con Arduino Uno/Nano y un módulo HC-06. Funciona sin internet. El programa de Arduino incluido debe sustituir al programa original de la Unidad 1 para recibir cambios de frecuencia y responder a la app. No se ha cargado automáticamente en ninguna placa.

## Cómo abrir y probar

1. En Android Studio abre Eva2IoT, tu proyecto existente en AndroidStudioProjects.
2. Espera a que termine la sincronización de Gradle. Conservamos las versiones de Android y Kotlin que tenía el proyecto; añadimos el soporte de ViewModel con corrutinas.
3. En Arduino IDE abre arduino/ArpaLaserHC06/ArpaLaserHC06.ino. Selecciona tu modelo y puerto y carga el programa. Verifica antes el cableado descrito abajo.
4. Al encender, deja todos los haces despejados durante la calibración inicial.
5. Instala/ejecuta la app en un Android físico con Bluetooth clásico. El emulador sirve para la interfaz y SQLite, pero no prueba el enlace HC-06.
6. Crea una cuenta local con usuario de 3–30 caracteres y contraseña de 8–128. No hay cuentas ni contraseñas predeterminadas.
7. Empareja el HC-06 desde los ajustes del teléfono. Dentro de la app pulsa Cargar dispositivos emparejados y concede el permiso de dispositivos cercanos cuando corresponda.
8. Selecciona el HC-06 y pulsa Conectar. Espera «protocolo confirmado».
9. Cambia una frecuencia, pulsa Enviar al arpa y espera las cuatro confirmaciones. Corta los haces para comprobar el sonido.
10. Guarda una escala con nombre. Cargarla desde SQLite cambia los campos; pulsa Enviar para aplicarla a la placa.
11. En Historial de sensores consulta las muestras guardadas. LDR son cuatro valores de luz; NOTE contiene cuerda y estado.

## Mapa de archivos

Todos los archivos Kotlin están dentro de app/src/main/java/com/example/eva2iot.

| Archivo | Qué hace |
| --- | --- |
| MainActivity.kt | Dibuja el inicio de sesión, la lista Bluetooth, los cuatro campos de frecuencia, las escalas y el historial con Jetpack Compose. |
| HarpViewModel.kt | Mantiene el estado de la pantalla y la sesión, valida datos y coordina Bluetooth con SQLite. Sobrevive al giro del teléfono. |
| bluetooth/BluetoothHarp.kt | Conecta con el HC-06, lee y escribe por RFCOMM, supervisa los latidos y reintenta conexiones. |
| bluetooth/HarpProtocol.kt | Construye y valida las tramas y su comprobación XOR. |
| data/HarpRepository.kt | Administra usuarios, preajustes y telemetría en SQLite y conserva preajustes en memoria si falla el disco. |
| ui/theme | Contiene colores, tipografía y tema visual de Compose. |
| app/src/main/AndroidManifest.xml | Declara la actividad, los permisos Bluetooth y la exclusión de copias de seguridad. |
| arduino/ArpaLaserHC06/ArpaLaserHC06.ino | Lee las LDR, reproduce los tonos y procesa el mismo protocolo que la app. |
| app/src/test/.../HarpProtocolTest.kt | Prueba checksum, corrupción, formato y límites de frecuencias. |

## Qué hace cada parte de MainActivity

- onCreate prepara la ventana, obtiene el ViewModel y carga la interfaz con setContent. FLAG_SECURE evita capturas y previsualizaciones del contenido.
- collectAsState convierte los cambios del ViewModel y de Bluetooth en actualizaciones visibles.
- rememberLauncherForActivityResult solicita BLUETOOTH_CONNECT a partir de Android 12. En Android 7–11 se usa el permiso clásico del manifiesto. No hay escaneo ni permiso de ubicación: la app consulta únicamente dispositivos emparejados.
- Scaffold respeta las barras del sistema; Column y verticalScroll permiten recorrer la pantalla y acomodar el teclado.
- Login conserva usuario y modo de registro. La contraseña se mantiene temporalmente en memoria, se oculta visualmente y se limpia después de enviarla. Crear cuenta pide repetirla.
- Section es un componente reutilizable que dibuja una tarjeta con título y contenido.
- noteName muestra el nombre de las notas conocidas. Los demás valores se indican como personalizados.
- onStop desconecta al salir de la app, salvo al girar la pantalla. No hay servicio de Bluetooth en segundo plano. La cuenta permanece iniciada en memoria hasta cerrar sesión o terminar el proceso.

## Qué hace cada parte de HarpViewModel

- HarpUiState reúne usuario, mensajes, dispositivos, selección, frecuencias, preajustes e historial.
- MutableStateFlow conserva ese estado y notifica a Compose. asStateFlow expone una vista de lectura.
- authenticate ejecuta el cálculo de contraseñas y SQLite fuera del hilo visual. Después de cinco intentos fallidos, bloquea esa cuenta durante cinco minutos. El contador y la fecha de desbloqueo se guardan en SQLite.
- refreshDevices consulta los dispositivos emparejados y muestra mensajes si falta Bluetooth, está apagado o se denegaron permisos.
- select guarda la dirección del módulo elegido y connect inicia el enlace.
- edit admite números con un máximo de cuatro caracteres. validFrequencies exige exactamente cuatro enteros entre 100 y 2000 Hz.
- applyPreset copia una escala a los campos; nunca la envía por sí sola.
- send bloquea envíos simultáneos y presenta el resultado de las confirmaciones del Arduino.
- savePreset guarda la escala para el usuario actual. Un nombre repetido reemplaza el preajuste de ese usuario.
- loadPresets recupera sus escalas; loadHistory consulta las doce muestras más recientes guardadas.
- onTelemetry guarda las muestras válidas que llegan de la placa, asociadas al usuario conectado.
- logout cierra el enlace y limpia la pantalla. onCleared libera Bluetooth, corrutinas y la base de datos.

## Qué hace HarpRepository

SQLiteOpenHelper crea arpa.db dentro del almacenamiento privado de la app. Hay cuatro tablas: users, presets, telemetry y audit. La migración a versión 3 añade el rol y el bloqueo a users, y crea audit sin borrar datos existentes.

register genera una sal aleatoria de 16 bytes y calcula un verificador PBKDF2-HMAC-SHA1 con 210 000 iteraciones y salida de 256 bits. Se utiliza esa variante por compatibilidad con Android 7. login deriva el verificador de la contraseña ingresada y usa MessageDigest.isEqual para compararlo. No se guarda la contraseña en texto ni se envía al HC-06. Un hash derivado no es cifrado reversible de la contraseña.

Las consultas reciben parámetros separados para evitar inyección SQL. Las escalas e historial se consultan por usuario. Las frecuencias y muestras no están cifradas: se protegen con el almacenamiento privado de Android. La autenticación falla si no se puede leer SQLite; no se concede acceso desde una caché.

presets y save mantienen una caché de escalas en RAM. Si guardar falla, la app avisa que ese dato se perderá cuando termine el proceso. record guarda muestras con fecha en una transacción y retiene hasta 200 por usuario. history devuelve las doce últimas. Si falla guardar una muestra, permanece en el registro temporal y se avisa; no se promete persistencia de datos que no llegaron al disco.

## Bluetooth y protocolo

BluetoothHarp utiliza BluetoothManager para obtener el adaptador. El identificador SPP es 00001101-0000-1000-8000-00805F9B34FB. createRfcommSocketToServiceRecord y connect abren el canal con el HC-06 emparejado. Los sockets y las operaciones bloqueantes se ejecutan con Dispatchers.IO.

connect cancela y espera la conexión anterior antes de abrir otra. Un temporizador cierra los intentos demasiado largos. Tras perder el enlace hace hasta cinco reintentos con esperas de 1, 2, 4, 8 y 16 segundos. El login se conserva. Si no se recupera, se puede volver a pulsar Conectar.

runConnection lee bytes con un búfer limitado, valida las tramas y separa las confirmaciones de las muestras. Un trabajo paralelo envía PING y espera PONG. La conexión física y la confirmación de protocolo son estados diferentes: Enviar se habilita solo después del primer PONG.

commandLock permite un único intercambio de comando/respuesta a la vez, incluido el latido. outputLock impide entrelazar bytes de dos escrituras. La cola de respuestas tiene capacidad limitada. sendFrequencies envía una cuerda por vez, exige un ACK que incluya la cuerda y frecuencia exactas y reintenta hasta tres veces. Hay cien milisegundos de separación entre cuerdas. Al escribir en un campo no se envían datos.

Una escala no se aplica de forma atómica: si falla la tercera cuerda, las dos primeras podrían haberse actualizado. La app lo informa. Tras reconectar se debe reenviar la escala: la interfaz muestra valores solicitados y no afirma conocer todos los valores reales de la placa.

| Contenido de la trama | Emisor | Significado |
| --- | --- | --- |
| PING | App | Latido y activación de sesión. |
| PONG | Arduino | Confirma que entiende el protocolo. |
| FREQ,1,440 | App | Solicita 440 Hz para la cuerda 1. |
| ACK,1,440 | Arduino | Confirma el valor aplicado. |
| NACK | Arduino | Trama o comando inválido. |
| NOTE,2,1 | Arduino | Haz de la cuerda 2 interrumpido. El estado 0 indica libre. |
| LDR,800,810,790,820 | Arduino | Lecturas analógicas de las cuatro LDR. |

encode agrega delimitadores y checksum XOR hexadecimal de dos dígitos. Ejemplo exacto: <FREQ,1,440*01> seguido de salto de línea. decode comprueba el formato y el XOR antes de aceptar el contenido. Esto detecta algunas alteraciones accidentales; no autentica al emisor ni protege frente a manipulación intencional.

Referencias de las API: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions y https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices.

## Qué cambia en el Arduino

Conserva LDR en A0–A3, láseres en 4–7, zumbador en 11 y SoftwareSerial(2,3) a 9600 baudios. Las frecuencias iniciales siguen siendo 262, 294, 330 y 349 Hz.

- notas deja de ser const para permitir la reconfiguración.
- setup calibra cada LDR por separado con diez muestras y fija su umbral al 70 % de la luz medida. Hay que dejar los haces despejados. Si el divisor LDR está conectado con respuesta inversa, habrá que invertir la comparación.
- sendFrame genera la misma trama XOR que Kotlin; hexDigit y handleFrame validan checksum, comando, cuerda y frecuencia.
- readBluetooth arma las tramas con un arreglo fijo y limita los bytes procesados por pasada. Reemplaza la recepción del carácter T del programa original.
- loop procesa sensores cada veinte milisegundos, sin un delay general. Una histéresis de veinte unidades reduce oscilaciones alrededor del umbral.
- NOTE se envía cuando cambia el estado de una cuerda; LDR se envía una vez por segundo mientras la sesión está activa.
- Si no recibe PING por más de tres segundos, se ejecuta noTone y no vuelve a sonar hasta recibir un nuevo latido. No se requiere un pin STATE del módulo.
- El único buzzer es monofónico: si se interrumpen varios haces, tiene prioridad el último índice, como en el diseño original. Para cambiar notas se necesita un buzzer pasivo.
- Las frecuencias de la placa se guardan en RAM y vuelven a las originales al reiniciar. Los preajustes del teléfono sí permanecen en SQLite.

Conexión serial: TX del HC-06 va al pin 2 (RX Arduino); el pin 3 (TX Arduino) va al RX del HC-06. Verifica los niveles eléctricos admitidos por tu módulo y el adaptador correspondiente; el nombre RX de SoftwareSerial es el del Arduino. No se modificaron pines ni alimentación desde el software.

## Seguridad y alcance académico

El login protege el acceso a esta app en este teléfono. No impide que otra aplicación emparejada intente comunicarse con el HC-06. El módulo y este protocolo no implementan autenticación de comandos ni cifrado de aplicación. Cambiar el PIN según el manual exacto del módulo reduce el uso del PIN de fábrica, pero no convierte el prototipo en una solución industrial segura.

No se declara cumplimiento ni certificación ISO 27400. Para una implementación de mayor seguridad hace falta autenticar y proteger mensajes desde ambos extremos, gestionar claves y evaluar riesgos del hardware y del entorno.

El alcance acordado es un teléfono Android y un arpa Arduino. La guía de Unidad 2 menciona interacción entre dos Android: debe validarse esa diferencia con el docente. No se añadió un segundo teléfono ni se publicaron archivos en GitHub.

## Pruebas manuales en el equipo real

- Crear cuenta, cerrar sesión e ingresar con contraseña correcta e incorrecta.
- Guardar dos escalas y comprobar que persisten al cerrar y volver a abrir la app; verificar separación entre dos usuarios.
- Denegar permiso Bluetooth y comprobar que aparece un mensaje sin cerrar la app.
- Conectar al HC-06, esperar confirmación, aplicar una escala y escuchar cada cuerda.
- Tapar y liberar haces y consultar NOTE y LDR en el registro y en SQLite.
- Desconectar o alejar el teléfono: comprobar silencio en la placa tras tres segundos y reintentos visibles.
- Volver a conectar y reenviar la escala para sincronizar valores.
- Enviar una trama con checksum erróneo desde una herramienta de prueba: debe aparecer NACK y no cambiar la frecuencia.
- Salir de la app: comprobar desconexión y silencio. Girar la pantalla: comprobar conservación del estado.

Las pruebas sobre el HC-06, los sensores y el sonido deben ejecutarse físicamente. Las pruebas unitarias de protocolo no sustituyen esa validación.

## Validación realizada

La aplicación compiló correctamente desde Android Studio y se generó el APK de depuración. La ejecución de app:testDebugUnitTest terminó con nueve pruebas aprobadas, cero fallos y cero errores: ocho pruebas del protocolo y una prueba de ejemplo que ya existía en el proyecto.

No había un teléfono conectado a ADB durante la comprobación. No se ejecutaron pruebas de interfaz en un dispositivo, no se compiló/cargó el sketch con una herramienta Arduino y no se verificaron físicamente el HC-06, las LDR ni el buzzer. Esas comprobaciones siguen pendientes; la compilación Android no demuestra el funcionamiento del hardware.


## Registro de actividad y administrador local

En la pantalla de acceso, abre Configurar administrador para configurar la cuenta hugo. Si ya existe la cuenta, usa su contraseña actual. Si no existe, elige y repite una contraseña de al menos ocho caracteres. Configúrala personalmente antes de compartir el teléfono. La configuración inicial se realiza en el dispositivo y no incluye una contraseña predeterminada en el código.

Al ingresar como administrador, abre Ver registro de actividad. SQLite guarda hasta los últimos 1000 eventos de la instalación y la vista muestra los 100 más recientes. Cada evento incluye fecha, usuario y acción. Se registran accesos correctos y rechazados, bloqueos, cierres de sesión, solicitudes de conexión y desconexión, envío de frecuencias y guardado de preajustes. Los intentos rechazados identifican el nombre ingresado y no prueban la identidad de la persona. El registro no contiene contraseñas.

Después de cinco fallos, esa cuenta queda bloqueada cinco minutos. El estado persiste al cerrar la app. La migración conserva las cuentas, escalas e historial del APK anterior. En el teléfono, instala el APK nuevo como actualización para mantener estos datos.

El registro es local y la auditoría de Android no agrega autenticación de comandos al Arduino. Se comprobó la compilación del código Kotlin con Compose. Para generar el APK actualizado usa Build > Build APK(s) en Android Studio. Falta comprobar en el teléfono la migración, el bloqueo y el acceso al registro.

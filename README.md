# Arpa Láser IoT

Aplicación Android en Kotlin para un Arduino Uno/Nano con HC-06 y cuatro cuerdas láser.

Lee GUIA_DEL_CODIGO.md para entender cada archivo, el protocolo, cómo ejecutar la app y cómo cargar el programa actualizado del Arduino. El sketch está en arduino/ArpaLaserHC06/ArpaLaserHC06.ino.

La app tiene registro e inicio de sesión local, control de frecuencias, preajustes SQLite, telemetría y reconexión. Se utiliza un solo teléfono con el arpa, según el alcance acordado.

Compilación Android comprobada y ocho pruebas de protocolo aprobadas (más la prueba de ejemplo existente). Falta validar el hardware y compilar/cargar el sketch en la placa.

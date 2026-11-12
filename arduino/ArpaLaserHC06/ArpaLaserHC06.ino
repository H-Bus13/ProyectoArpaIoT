#include <SoftwareSerial.h>
#include <string.h>
#include <stdlib.h>

// Mismos pines del prototipo original: RX Arduino 2, TX Arduino 3.
SoftwareSerial bluetooth(2, 3);
const byte pinLDR[4] = {A0, A1, A2, A3};
const byte pinLaser[4] = {4, 5, 6, 7};
const byte pinBuzzer = 11;
unsigned int notas[4] = {262, 294, 330, 349}; // Ahora son modificables.
int umbral[4];
bool notaActiva[4] = {false, false, false, false};
char frame[64];
byte frameLength = 0;
bool receiving = false;
bool sessionActive = false;
unsigned long lastPing = 0;
unsigned long lastTelemetry = 0;
unsigned long lastSensors = 0;

// XOR detecta alteraciones accidentales; no es cifrado ni autenticaci?n.
void sendFrame(const char *payload) {
  byte checksum = 0;
  for (const char *p = payload; *p; p++) checksum ^= (byte)*p;
  bluetooth.print('<'); bluetooth.print(payload); bluetooth.print('*');
  if (checksum < 16) bluetooth.print('0');
  bluetooth.print(checksum, HEX); bluetooth.println('>');
}

int hexDigit(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  return -1;
}

void handleFrame() {
  char *star = strchr(frame, '*');
  if (!star || strlen(star + 1) != 2) { sendFrame("NACK"); return; }
  int hi = hexDigit(star[1]), lo = hexDigit(star[2]);
  if (hi < 0 || lo < 0) { sendFrame("NACK"); return; }
  byte checksum = 0;
  for (char *p = frame; p < star; p++) checksum ^= (byte)*p;
  if (checksum != (byte)(hi * 16 + lo)) { sendFrame("NACK"); return; }
  *star = '\0';
  if (strcmp(frame, "PING") == 0) {
    lastPing = millis(); sessionActive = true; sendFrame("PONG"); return;
  }
  if (strncmp(frame, "FREQ,", 5) == 0 && sessionActive) {
    // Validaci?n estricta: no acepta texto sobrante ni una cuerda fuera de rango.
    char *start = frame + 5, *end;
    long cuerda = strtol(start, &end, 10);
    if (end == start || *end != ',') { sendFrame("NACK"); return; }
    start = end + 1;
    long hz = strtol(start, &end, 10);
    if (end == start || *end != '\0' || cuerda < 1 || cuerda > 4 || hz < 100 || hz > 2000) {
      sendFrame("NACK"); return;
    }
    notas[cuerda - 1] = (unsigned int)hz;
    char ack[28]; snprintf(ack, sizeof(ack), "ACK,%ld,%ld", cuerda, hz); sendFrame(ack);
    return;
  }
  sendFrame("NACK");
}

void readBluetooth() {
  // Presupuesto de lectura para que un emisor no bloquee indefinidamente los sensores.
  byte budget = 64;
  while (bluetooth.available() && budget--) {
    char c = bluetooth.read();
    if (c == '<') { receiving = true; frameLength = 0; continue; }
    if (!receiving) continue;
    if (c == '>') {
      frame[frameLength] = '\0'; receiving = false; handleFrame(); continue;
    }
    if (frameLength >= sizeof(frame) - 1) { receiving = false; sendFrame("NACK"); continue; }
    frame[frameLength++] = c;
  }
}

void setup() {
  Serial.begin(9600); bluetooth.begin(9600);
  pinMode(pinBuzzer, OUTPUT);
  for (byte i = 0; i < 4; i++) { pinMode(pinLaser[i], OUTPUT); digitalWrite(pinLaser[i], HIGH); }
  Serial.println(F("Calibrando: no interrumpas los haces durante dos segundos."));
  delay(500);
  long sums[4] = {0, 0, 0, 0};
  for (byte sample = 0; sample < 10; sample++) {
    for (byte i = 0; i < 4; i++) sums[i] += analogRead(pinLDR[i]);
    delay(100);
  }
  // Calibra cada sensor por separado en lugar de usar solo el primero.
  for (byte i = 0; i < 4; i++) umbral[i] = (sums[i] / 10) * 7 / 10;
  Serial.println(F("Arpa lista. Esperando PING de la aplicacion."));
  noTone(pinBuzzer);
}

void loop() {
  readBluetooth();
  unsigned long now = millis();
  // Sin latido durante tres segundos: silencio hasta una nueva sesi?n.
  if (sessionActive && now - lastPing > 3000UL) {
    sessionActive = false; noTone(pinBuzzer);
    for (byte i = 0; i < 4; i++) notaActiva[i] = false;
  }
  if (now - lastSensors < 20UL) return;
  lastSensors = now;
  int readings[4];
  int sounding = -1;
  for (byte i = 0; i < 4; i++) {
    readings[i] = analogRead(pinLDR[i]);
    // Hist?resis: reduce cambios r?pidos cuando el valor ronda el umbral.
    bool active = sessionActive && (notaActiva[i] ? readings[i] < umbral[i] + 20 : readings[i] < umbral[i]);
    if (active != notaActiva[i]) {
      notaActiva[i] = active;
      if (sessionActive) {
        char note[20]; snprintf(note, sizeof(note), "NOTE,%u,%u", (unsigned)(i + 1), active ? 1U : 0U); sendFrame(note);
      }
    }
    if (active) sounding = i;
  }
  // Un solo buzzer es monof?nico; conserva la prioridad de la ?ltima cuerda.
  if (sounding >= 0) tone(pinBuzzer, notas[sounding]); else noTone(pinBuzzer);
  if (sessionActive && now - lastTelemetry >= 1000UL) {
    lastTelemetry = now;
    char data[40]; snprintf(data, sizeof(data), "LDR,%d,%d,%d,%d", readings[0], readings[1], readings[2], readings[3]);
    sendFrame(data);
  }
}

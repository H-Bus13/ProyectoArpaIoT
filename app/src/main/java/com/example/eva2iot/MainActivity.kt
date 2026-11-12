package com.example.eva2iot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.eva2iot.data.Preset
import com.example.eva2iot.ui.theme.Eva2IoTTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: HarpViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Protege el login de capturas y de la vista previa de aplicaciones recientes.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        model = ViewModelProvider(this)[HarpViewModel::class.java]
        setContent {
            Eva2IoTTheme {
                val ui by model.ui.collectAsState()
                val link by model.bluetooth.state.collectAsState()
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) model.refreshDevices() else model.message("Se necesita el permiso de dispositivos cercanos para usar el HC-06.")
                }
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(
                            Modifier.fillMaxWidth().background(
                                Brush.linearGradient(listOf(Color(0xFF152E34), Color(0xFF14564E))),
                                RoundedCornerShape(24.dp)
                            ).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Arpa láser", style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Control de cuatro cuerdas", color = Color(0xFFB7E8D8))
                        }
                        if (ui.admin && ui.auditOpen) {
                            AlertDialog(
                                onDismissRequest = model::closeAudit,
                                title = { Text("Registro de actividad") },
                                text = {
                                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        if (ui.auditRows.isEmpty()) Text("Todavía no hay eventos.")
                                        ui.auditRows.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    }
                                },
                                confirmButton = { TextButton(onClick = model::closeAudit) { Text("Cerrar") } },
                                dismissButton = { TextButton(onClick = model::loadAudit) { Text("Actualizar") } }
                            )
                        }
                        if (ui.user == null) Login(ui, model::authenticate)
                        else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(ui.user.orEmpty(), fontWeight = FontWeight.SemiBold)
                                    Text(if (ui.admin) "Administrador" else "Operador",
                                        style = MaterialTheme.typography.labelMedium)
                                }
                                TextButton(onClick = model::logout, enabled = !ui.busy && !ui.sending) { Text("Cerrar sesión") }
                            }
                            if (ui.admin) OutlinedButton(onClick = model::loadAudit, modifier = Modifier.fillMaxWidth()) {
                                Text("Ver registro de actividad")
                            }
                            Section("Conexión Bluetooth") {
                                Text(link.status, fontWeight = FontWeight.Bold)
                                Text("Empareja primero el HC-06 desde Ajustes → Bluetooth.", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = {
                                    if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                                        permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                    else model.refreshDevices()
                                }) { Text("Cargar dispositivos emparejados") }
                                ui.devices.forEach { device ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = ui.selectedAddress == device.address, onClick = { model.select(device.address) }, enabled = !link.connected)
                                        Column { Text(device.name); Text(device.address, style = MaterialTheme.typography.labelSmall) }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = model::connect, enabled = ui.selectedAddress != null && !link.connected && !ui.sending) { Text("Conectar") }
                                    OutlinedButton(onClick = model::disconnect) { Text("Desconectar") }
                                }
                            }
                            Section("02 · Afina las cuatro cuerdas") {
                                Text("Entre 100 y 2000 Hz. Editar no envía comandos: pulsa Enviar para aplicarlos.", style = MaterialTheme.typography.bodySmall)
                                ui.frequencies.forEachIndexed { index, value ->
                                    OutlinedTextField(value = value, onValueChange = { model.edit(index, it) },
                                        label = { Text("Cuerda ${index + 1} · ${noteName(value)}") },
                                        suffix = { Text("Hz") }, singleLine = true, enabled = !ui.sending,
                                        isError = value.toIntOrNull()?.let { it !in 100..2000 } ?: true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                                }
                                Button(onClick = model::send, enabled = link.ready && !ui.sending, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (ui.sending) "Esperando confirmaciones…" else "Enviar al arpa")
                                }
                                Text("Después de reconectar, reenvía la escala para confirmar sus valores.", style = MaterialTheme.typography.bodySmall)
                            }
                            Section("03 · Tus escalas") {
                                var presetName by rememberSaveable { mutableStateOf("") }
                                OutlinedButton(onClick = { model.applyPreset(Preset("Original Do–Re–Mi–Fa", listOf(262, 294, 330, 349))) }, enabled = !ui.sending) { Text("Cargar notas originales") }
                                OutlinedButton(onClick = { model.applyPreset(Preset("Acorde de Do", listOf(262, 330, 392, 523))) }, enabled = !ui.sending) { Text("Cargar acorde de Do") }
                                OutlinedTextField(value = presetName, onValueChange = { if (it.length <= 40) presetName = it }, label = { Text("Nombre del preajuste") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedButton(onClick = { model.savePreset(presetName) }, enabled = !ui.busy && !ui.sending) { Text("Guardar escala actual") }
                                Text("Repetir un nombre actualiza ese preajuste.", style = MaterialTheme.typography.bodySmall)
                                ui.presets.forEach { preset ->
                                    TextButton(onClick = { model.applyPreset(preset) }, enabled = !ui.sending) { Text("${preset.name}: ${preset.frequencies.joinToString(" / ")} Hz") }
                                }
                            }
                            Section("04 · Historial de sensores") {
                                Text("LDR: lectura de luz (0–1023). NOTE: cuerda y estado (1: interrumpida, 0: libre).", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = model::loadHistory) { Text("Consultar muestras guardadas") }
                                ui.history.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            if (link.events.isNotEmpty()) Section("Registro del arpa") {
                                link.events.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        if (ui.message.isNotBlank()) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Text(ui.message, modifier = Modifier.padding(14.dp))
                        }
                        Text("Prototipo educativo · HC-06 · sin internet", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    override fun onStop() {
        super.onStop()
        // Sin servicio en segundo plano: al salir deja de enviar latidos y el arpa se silencia.
        if (!isChangingConfigurations) model.bluetooth.disconnect()
    }
}

@Composable
private fun Login(ui: HarpUiState, authenticate: (String, String, Boolean, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") } // No guardar contraseñas en el estado restaurable.
    var confirmation by remember { mutableStateOf("") }
    var register by rememberSaveable { mutableStateOf(false) }
    var setupAdmin by rememberSaveable { mutableStateOf(false) }
    Section(if (setupAdmin) "Configurar administrador" else if (register) "Crea tu acceso local" else "Ingresa a tu instrumento") {
        Text("Tu cuenta se guarda en este teléfono y funciona sin internet.")
        if (setupAdmin) Text("Usuario: hugo. Si la cuenta ya existe, usa su contraseña actual.")
        else OutlinedTextField(value = name, onValueChange = { if (it.length <= 30) name = it }, label = { Text("Usuario") }, singleLine = true, enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { if (it.length <= 128) password = it }, label = { Text("Contraseña") }, singleLine = true, enabled = !ui.busy,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        if (register || setupAdmin) OutlinedTextField(value = confirmation, onValueChange = { if (it.length <= 128) confirmation = it }, label = { Text("Repite la contraseña") }, singleLine = true, enabled = !ui.busy,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Button(onClick = { authenticate(if (setupAdmin) "hugo" else name, password, register, setupAdmin); password = ""; confirmation = "" },
            enabled = !ui.busy && (setupAdmin || name.isNotBlank()) && password.isNotEmpty() && (!(register || setupAdmin) || password == confirmation), modifier = Modifier.fillMaxWidth()) {
            Text(if (ui.busy) "Procesando…" else if (setupAdmin) "Guardar e ingresar" else if (register) "Crear cuenta e ingresar" else "Iniciar sesión")
        }
        if (!setupAdmin) TextButton(onClick = { register = !register; password = ""; confirmation = "" }, enabled = !ui.busy) { Text(if (register) "Ya tengo cuenta" else "Crear una cuenta") }
        TextButton(onClick = { setupAdmin = !setupAdmin; register = false; password = ""; confirmation = "" },
            enabled = !ui.busy) { Text(if (setupAdmin) "Volver al inicio de sesión" else "Configurar administrador") }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

// Solo etiqueta valores conocidos; siempre se transmite la frecuencia numérica.
private fun noteName(value: String): String = when (value.toIntOrNull()) {
    262 -> "Do4"; 294 -> "Re4"; 330 -> "Mi4"; 349 -> "Fa4"
    392 -> "Sol4"; 440 -> "La4"; 494 -> "Si4"; 523 -> "Do5"
    else -> "personalizada"
}


package com.example.eva2iot

import android.app.Application

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.eva2iot.bluetooth.BluetoothHarp
import com.example.eva2iot.bluetooth.PairedHarp
import com.example.eva2iot.data.HarpRepository
import com.example.eva2iot.data.Preset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HarpUiState(
    val user: String? = null, val busy: Boolean = false, val message: String = "",
    val devices: List<PairedHarp> = emptyList(), val selectedAddress: String? = null,
    val frequencies: List<String> = listOf("262", "294", "330", "349"),
    val presets: List<Preset> = emptyList(), val sending: Boolean = false,
    val history: List<String> = emptyList(), val admin: Boolean = false,
    val auditRows: List<String> = emptyList(), val auditOpen: Boolean = false
)

/** Conserva la sesión al girar el teléfono; coordina pantalla, SQLite y Bluetooth. */
class HarpViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HarpRepository(application)
    val bluetooth = BluetoothHarp(application)
    private val mutable = MutableStateFlow(HarpUiState())
    val ui = mutable.asStateFlow()
    init {
        bluetooth.onTelemetry = { payload ->
            val owner = ui.value.user
            if (owner != null && !repository.record(owner, payload)) {
                bluetooth.event("No se guardó esta muestra: almacenamiento no disponible. Sigue visible en el registro.")
            }
        }
    }
    fun message(text: String) { mutable.value = mutable.value.copy(message = text) }

    fun authenticate(name: String, password: String, register: Boolean, setupAdmin: Boolean = false) {
        if (ui.value.busy) return
        val actor = name.trim()
        mutable.value = ui.value.copy(busy = true, message = "")
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (register && !setupAdmin) {
                        val error = repository.register(actor, password)
                        if (error == null) repository.audit(actor, "Cuenta creada e inicio de sesión")
                        HarpRepository.Access(error)
                    } else repository.login(actor, password, setupAdmin)
                }
                if (result.error != null) message(result.error)
                else {
                    mutable.value = ui.value.copy(user = actor, admin = result.admin, message = "Sesión iniciada")
                    loadPresets()
                }
            } catch (_: Exception) { message("No se pudo consultar el almacenamiento local.") }
            finally { mutable.value = ui.value.copy(busy = false) }
        }
    }

    private fun logAction(actor: String, action: String) {
        viewModelScope.launch {
            if (!withContext(Dispatchers.IO) { repository.audit(actor, action) })
                message("No se pudo guardar el evento de auditoría.")
        }
    }

    fun loadAudit() {
        val actor = ui.value.user ?: return
        if (!ui.value.admin) return
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                runCatching { repository.auditHistory(actor) }.getOrElse { listOf("No se pudo consultar el registro.") }
            }
            if (ui.value.user == actor && ui.value.admin)
                mutable.value = ui.value.copy(auditRows = rows, auditOpen = true)
        }
    }

    fun closeAudit() { mutable.value = ui.value.copy(auditOpen = false, auditRows = emptyList()) }

    fun disconnect() {
        bluetooth.disconnect()
        ui.value.user?.let { logAction(it, "Desconexión solicitada") }
    }

    fun refreshDevices() {
        try {
            val devices = bluetooth.paired()
            mutable.value = mutable.value.copy(devices = devices,
                message = if (devices.isEmpty()) "Empareja el HC-06 desde Ajustes → Bluetooth y vuelve a cargar." else "Selecciona tu HC-06.")
        } catch (error: Exception) { message(error.message ?: "No se pudo consultar Bluetooth.") }
    }
    fun select(address: String) { mutable.value = mutable.value.copy(selectedAddress = address) }
    fun connect() {
        val address = ui.value.selectedAddress ?: run { message("Selecciona un dispositivo."); return }
        bluetooth.connect(address)
        ui.value.user?.let { logAction(it, "Conexión Bluetooth solicitada") }
    }
    fun edit(index: Int, value: String) {
        if (value.length > 4 || value.any { !it.isDigit() }) return
        mutable.value = mutable.value.copy(frequencies = ui.value.frequencies.toMutableList().apply { set(index, value) })
    }
    fun applyPreset(preset: Preset) {
        mutable.value = mutable.value.copy(frequencies = preset.frequencies.map(Int::toString), message = "${preset.name} cargada. Pulsa Enviar para aplicarla al arpa.")
    }
    private fun validFrequencies(): List<Int>? {
        val hz = ui.value.frequencies.mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 100..2000 } }
        if (hz.size != 4) { message("Cada cuerda debe tener un entero entre 100 y 2000 Hz."); return null }
        return hz
    }
    fun send() {
        val actor = ui.value.user ?: return
        if (ui.value.sending) return
        val hz = validFrequencies() ?: return
        mutable.value = mutable.value.copy(sending = true)
        viewModelScope.launch {
            try {
                val confirmed = bluetooth.sendFrequencies(hz)
                logAction(actor, if (confirmed) "Frecuencias aplicadas: ${hz.joinToString()} Hz" else "Envío de frecuencias sin confirmación completa")
                message(if (confirmed) "Escala aplicada: 4 confirmaciones recibidas." else "No se confirmó toda la escala. Revisa el registro y la conexión.")
            }
            finally { mutable.value = mutable.value.copy(sending = false) }
        }
    }
    fun savePreset(name: String) {
        val user = ui.value.user ?: return
        if (name.trim().length !in 1..40) { message("Nombre de escala: entre 1 y 40 caracteres."); return }
        val hz = validFrequencies() ?: return
        if (ui.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { repository.save(user, name.trim(), hz) }
                logAction(user, if (saved) "Preajuste guardado" else "Preajuste guardado solo en memoria")
                loadPresets()
                message(if (saved) "Escala guardada en SQLite." else "Escala solo en memoria; se perderá al cerrar el proceso.")
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun loadHistory() {
        val owner = ui.value.user ?: return
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) { repository.history(owner) }
            if (ui.value.user == owner) mutable.value = mutable.value.copy(history = history)
        }
    }
    private suspend fun loadPresets() {
        val user = ui.value.user ?: return
        val list = withContext(Dispatchers.IO) { repository.presets(user) }
        mutable.value = mutable.value.copy(presets = list)
        repository.storageWarning?.let(::message)
    }
    fun logout() {
        if (ui.value.sending || ui.value.busy) return
        ui.value.user?.let { logAction(it, "Cierre de sesión") }
        bluetooth.disconnect()
        mutable.value = HarpUiState()
    }
    override fun onCleared() { bluetooth.close(); repository.close(); super.onCleared() }
}



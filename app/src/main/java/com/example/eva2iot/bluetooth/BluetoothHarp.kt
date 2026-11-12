package com.example.eva2iot.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID

data class PairedHarp(val name: String, val address: String)
data class LinkState(
    val status: String = "Desconectado", val connected: Boolean = false, val ready: Boolean = false,
    val events: List<String> = emptyList()
)

/** Toda operación bloqueante de Bluetooth se ejecuta fuera del hilo de la pantalla. */
@SuppressLint("MissingPermission") // MainActivity solicita CONNECT antes de llamar a esta clase.
class BluetoothHarp(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(LinkState())
    val state = mutable.asStateFlow()
    private val lock = Any()
    private val commandLock = Mutex()
    private val outputLock = Any()
    private val responses = Channel<String>(32, BufferOverflow.DROP_OLDEST)
    @Volatile private var socket: BluetoothSocket? = null
    private var connectionJob: Job? = null
    private var closed = false
    var onTelemetry: ((String) -> Unit)? = null
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private fun update(transform: (LinkState) -> LinkState) = synchronized(lock) { mutable.value = transform(mutable.value) }
    fun event(message: String) = update { it.copy(events = (listOf(message) + it.events).take(15)) }

    fun paired(): List<PairedHarp> {
        check(adapter != null) { "Este dispositivo no dispone de Bluetooth clásico." }
        check(adapter.isEnabled) { "Activa Bluetooth en los ajustes del teléfono." }
        return adapter.bondedDevices.map { PairedHarp(it.name ?: "Sin nombre", it.address) }.sortedBy { it.name }
    }

    fun connect(address: String) {
        val previous = connectionJob
        disconnect()
        connectionJob = scope.launch {
            previous?.join()
            var attempt = 0
            while (isActive) {
                update { it.copy(status = if (attempt == 0) "Conectando…" else "Reconectando: intento $attempt") }
                try {
                    val device = checkNotNull(adapter) { "Bluetooth no disponible" }.getRemoteDevice(address)
                    val current = device.createRfcommSocketToServiceRecord(uuid)
                    socket = current // Permite cancelar connect() cerrando el socket desde otro hilo.
                    withTimeout(12_000) {
                        val watchdog = launch { delay(11_500); runCatching { current.close() } }
                        try { current.connect() } finally { watchdog.cancel() }
                    }
                    runConnection(current)
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    update { it.copy(connected = false, ready = false, status = "Conexión perdida / dispositivo ocupado o no disponible") }
                    event("No se pudo mantener el enlace. Revisa distancia, alimentación y emparejamiento.")
                } finally { runCatching { socket?.close() }; socket = null }
                attempt++
                if (attempt > 5) { update { it.copy(status = "Sin conexión. Pulsa Conectar para reintentar.") }; break }
                delay(minOf(1_000L shl (attempt - 1), 16_000L))
            }
        }
    }

    private suspend fun runConnection(current: BluetoothSocket) = coroutineScope {
        while (responses.tryReceive().isSuccess) { /* Descarta respuestas de la conexión anterior. */ }
        update { it.copy(status = "Conectado · verificando protocolo", connected = true) }
        val monitor = launch {
            try {
                while (isActive) {
                    delay(1_000)
                        commandLock.withLock {
                            write(current, "PING")
                            withTimeout(1_500) { while (responses.receive() != "PONG") { /* Ignora ACK atrasados. */ } }
                        }
                        update { it.copy(status = "Conectado · protocolo confirmado", ready = true) }
                }
            } finally { runCatching { current.close() } } // Desbloquea read() si expira el latido.
        }
        try {
            // El búfer tiene límite: una trama inválida no puede crecer indefinidamente.
            val buffer = StringBuilder()
            val input = current.inputStream
            while (isActive) {
                val byte = input.read()
                if (byte == -1) throw IOException("Fin del enlace")
                val char = byte.toChar()
                if (char == '<') buffer.clear()
                if (buffer.length >= 96) { buffer.clear(); continue }
                if (char != '\n' && char != '\r') buffer.append(char)
                if (char != '>') continue
                val payload = HarpProtocol.decode(buffer.toString())
                buffer.clear()
                if (payload == null) { event("Trama inválida descartada."); continue }
                if (payload == "PONG" || payload == "NACK" || payload.startsWith("ACK,")) responses.send(payload)
                else if (validTelemetry(payload)) {
                    event("Recibido: $payload")
                    onTelemetry?.invoke(payload)
                }
            }
        } finally { monitor.cancel(); update { it.copy(connected = false, ready = false) } }
    }

    private fun write(current: BluetoothSocket, payload: String) = synchronized(outputLock) {
        current.outputStream.write(HarpProtocol.encode(payload).toByteArray(Charsets.US_ASCII))
        current.outputStream.flush()
    }

    suspend fun sendFrequencies(hz: List<Int>): Boolean = withContext(Dispatchers.IO) {
        require(hz.size == 4 && hz.all { it in 100..2000 })
        if (!state.value.ready) return@withContext false
        try {
            // Serializa cada comando y su ACK para evitar mezclar respuestas.
            for ((index, frequency) in hz.withIndex()) {
                commandLock.withLock {
                    var confirmed = false
                    for (attempt in 1..3) {
                        while (responses.tryReceive().isSuccess) { }
                        write(checkNotNull(socket), HarpProtocol.frequency(index + 1, frequency))
                        val reply = withTimeoutOrNull(600) { responses.receive() }
                        if (reply == "ACK,${index + 1},$frequency") { confirmed = true; break }
                        event("Sin confirmación de cuerda ${index + 1}; intento $attempt/3")
                    }
                    if (!confirmed) throw IOException("Sin ACK")
                }
                delay(100) // Limita el tráfico serial; no envía mientras se edita el campo.
            }
            event("Las cuatro frecuencias fueron confirmadas por el receptor.")
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            event("Envío incompleto: algunas cuerdas pueden haberse actualizado. Reenvía la escala.")
            false
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        update { it.copy(status = "Desconectado", connected = false, ready = false) }
    }
    private fun validTelemetry(payload: String): Boolean {
        val parts = payload.split(',')
        return when (parts.first()) {
            "LDR" -> parts.size == 5 && parts.drop(1).all { it.toIntOrNull()?.let { n -> n in 0..1023 } == true }
            "NOTE" -> parts.size == 3 && parts[1].toIntOrNull() in 1..4 && parts[2] in listOf("0", "1")
            else -> false
        }
    }
    fun close() { if (!closed) { closed = true; disconnect(); scope.cancel() } }
}


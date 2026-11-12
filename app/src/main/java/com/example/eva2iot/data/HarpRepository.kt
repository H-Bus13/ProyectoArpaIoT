package com.example.eva2iot.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class Preset(val name: String, val frequencies: List<Int>)

/** SQLite guarda usuarios y escalas; nunca guardamos la contraseña original. */
class HarpRepository(context: Context) : SQLiteOpenHelper(context, "arpa.db", null, 3) {
    private val cache = linkedMapOf<String, List<Int>>()
    var storageWarning: String? = null
        private set

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE users (name TEXT PRIMARY KEY, salt TEXT NOT NULL, hash TEXT NOT NULL)")
        db.execSQL("CREATE TABLE presets (owner TEXT NOT NULL, name TEXT NOT NULL, hz TEXT NOT NULL, PRIMARY KEY(owner,name))")
        createTelemetry(db)
        createSecurity(db)
    }

    private fun createTelemetry(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS telemetry (id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, time INTEGER NOT NULL, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTelemetry(db)
        if (oldVersion < 3) createSecurity(db)
    }

    fun record(owner: String, payload: String): Boolean = try {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("owner", owner); put("time", System.currentTimeMillis()); put("payload", payload) }
            check(db.insert("telemetry", null, values) != -1L)
            // Retiene las últimas 200 muestras por usuario para limitar el espacio utilizado.
            db.execSQL("DELETE FROM telemetry WHERE owner=? AND id NOT IN (SELECT id FROM telemetry WHERE owner=? ORDER BY id DESC LIMIT 200)", arrayOf(owner, owner))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        true
    } catch (_: Exception) { false }

    fun history(owner: String): List<String> = try {
        val rows = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT time,payload FROM telemetry WHERE owner=? ORDER BY id DESC LIMIT 12", arrayOf(owner)).use { cursor ->
            val format = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault())
            while (cursor.moveToNext()) rows += "${format.format(java.util.Date(cursor.getLong(0)))} · ${cursor.getString(1)}"
        }
        rows
    } catch (_: Exception) { listOf("No se pudo consultar el historial local.") }

    // PBKDF2 dificulta adivinar contraseñas a partir de una copia de la base de datos.
    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 210_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

    fun register(name: String, password: String, asAdmin: Boolean = false): String? {
        if (name.equals("hugo", ignoreCase = true) && !asAdmin) return "Usa Configurar administrador para la cuenta hugo."
        if (!name.matches(Regex("[A-Za-z0-9_.-]{3,30}"))) return "Usuario: 3 a 30 letras, números, punto, guion o guion bajo."
        if (password.length !in 8..128) return "La contraseña debe tener entre 8 y 128 caracteres."
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return try {
            val values = ContentValues().apply {
                put("name", name); put("salt", encode(salt)); put("hash", encode(derive(password, salt))); put("admin", if (asAdmin) 1 else 0)
            }
            if (writableDatabase.insertWithOnConflict("users", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L)
                "Ese usuario ya existe." else null
        } catch (_: Exception) { "No se pudo guardar el usuario. Revisa el almacenamiento." }
    }

    private fun createSecurity(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE users ADD COLUMN admin INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE users ADD COLUMN failures INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE users ADD COLUMN blocked_until INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE audit (id INTEGER PRIMARY KEY, time INTEGER NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL)")
    }

    private fun writeAudit(db: SQLiteDatabase, actor: String, action: String) {
        val values = ContentValues().apply {
            put("time", System.currentTimeMillis())
            put("actor", actor)
            put("action", action)
        }
        db.insertOrThrow("audit", null, values)
        db.execSQL("DELETE FROM audit WHERE id <= (SELECT MAX(id) - 1000 FROM audit)")
    }

    fun audit(actor: String, action: String): Boolean = try {
        val db = writableDatabase
        db.beginTransaction()
        try {
            writeAudit(db, actor, action)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        true
    } catch (_: Exception) { false }

    fun auditHistory(actor: String): List<String> {
        val db = readableDatabase
        return db.rawQuery(
            "SELECT time,actor,action FROM audit WHERE EXISTS (SELECT 1 FROM users WHERE name=? AND admin=1) ORDER BY id DESC LIMIT 100",
            arrayOf(actor)
        ).use { cursor ->
            val format = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
            buildList {
                while (cursor.moveToNext()) {
                    add("${format.format(java.util.Date(cursor.getLong(0)))} · ${cursor.getString(1)} · ${cursor.getString(2)}")
                }
            }
        }
    }

    data class Access(val error: String? = null, val admin: Boolean = false)

    fun login(name: String, password: String, setupAdmin: Boolean = false): Access {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = checkLogin(db, name, password, setupAdmin)
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    // Los intentos y el vencimiento quedan en SQLite, incluso tras cerrar la app.
    private fun checkLogin(db: SQLiteDatabase, name: String, password: String, setupAdmin: Boolean): Access {
        val actor = name.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{3,30}")) } ?: "(usuario inválido)"
        val denied = Access("Usuario o contraseña incorrectos.")
        if (setupAdmin && name != "hugo") return denied
        val now = System.currentTimeMillis()
        db.rawQuery("SELECT salt,hash,admin,failures,blocked_until FROM users WHERE name=?", arrayOf(name)).use { c ->
            if (!c.moveToFirst()) {
                if (setupAdmin) {
                    val error = register(name, password, asAdmin = true)
                    if (error != null) return Access(error)
                    writeAudit(db, actor, "Administrador local configurado")
                    return Access(admin = true)
                }
                writeAudit(db, actor, "Inicio de sesión rechazado")
                return denied
            }
            if (c.getLong(4) > now) return Access("Cuenta bloqueada. Reintenta en ${(c.getLong(4) - now + 999) / 1000} s.")
            val correct = password.length <= 128 &&
                MessageDigest.isEqual(decode(c.getString(1)), derive(password, decode(c.getString(0))))
            val attempts = if (correct || c.getLong(4) != 0L) 0 else c.getInt(3)
            val blocked = !correct && attempts + 1 >= 5
            val admin = c.getInt(2) == 1 || (correct && setupAdmin)
            val values = ContentValues().apply {
                put("failures", if (correct || blocked) 0 else attempts + 1)
                put("blocked_until", if (blocked) now + 300_000 else 0L)
                put("admin", if (admin) 1 else 0)
            }
            db.update("users", values, "name=?", arrayOf(name))
            writeAudit(db, actor, when {
                blocked -> "Cuenta bloqueada por cinco intentos fallidos"
                !correct -> "Inicio de sesión rechazado"
                setupAdmin && c.getInt(2) == 0 -> "Administrador local configurado"
                else -> "Inicio de sesión correcto"
            })
            return when {
                blocked -> Access("Cinco intentos fallidos. Cuenta bloqueada durante 5 minutos.")
                correct -> Access(admin = admin)
                else -> denied
            }
        }
    }

    fun presets(owner: String): List<Preset> {
        try {
            readableDatabase.rawQuery("SELECT name,hz FROM presets WHERE owner=? ORDER BY name", arrayOf(owner)).use { cursor ->
                while (cursor.moveToNext()) {
                    val hz = cursor.getString(1).split(',').mapNotNull { it.toIntOrNull() }
                    if (hz.size == 4 && hz.all { it in 100..2000 }) cache["$owner/${cursor.getString(0)}"] = hz
                }
            }
            storageWarning = null
        } catch (_: Exception) { storageWarning = "No se pudo leer SQLite; se muestran los datos en memoria." }
        return cache.filterKeys { it.startsWith("$owner/") }.map { Preset(it.key.substringAfter('/'), it.value) }
    }

    fun save(owner: String, name: String, hz: List<Int>): Boolean {
        require(name.isNotBlank() && name.length <= 40 && hz.size == 4 && hz.all { it in 100..2000 })
        cache["$owner/$name"] = hz.toList() // Mantiene la escala disponible aunque falle el disco.
        return try {
            val values = ContentValues().apply { put("owner", owner); put("name", name); put("hz", hz.joinToString(",")) }
            check(writableDatabase.insertWithOnConflict("presets", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            storageWarning = null
            true
        } catch (_: Exception) {
            storageWarning = "Guardado solo en memoria: se perderá al cerrar el proceso."
            false
        }
    }
}


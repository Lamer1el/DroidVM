package tech.astrix.droidvm.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A bidirectional byte stream (Android LocalSocket in the app, UNIX socket channel in JVM tests). */
interface StreamConnection : Closeable {
    val input: InputStream
    val output: OutputStream
}

class QmpException(msg: String) : Exception(msg)

/**
 * Minimal QEMU Machine Protocol client: newline-delimited JSON, commands tagged with "id".
 */
class QmpClient(private val conn: StreamConnection, private val onEvent: (String, JsonObject) -> Unit = { _, _ -> }) : Closeable {
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = ConcurrentHashMap<String, CompletableFuture<JsonObject>>()
    private val ids = AtomicInteger()
    private val greeting = CompletableFuture<JsonObject>()
    @Volatile private var closed = false
    var version: String = ""; private set

    private val reader = Thread({
        try {
            val br = conn.input.bufferedReader()
            while (!closed) {
                val line = br.readLine() ?: break
                if (line.isBlank()) continue
                val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                when {
                    "QMP" in obj -> greeting.complete(obj)
                    "event" in obj -> onEvent(obj["event"]!!.jsonPrimitive.content, obj)
                    else -> obj["id"]?.jsonPrimitive?.contentOrNull?.let { pending.remove(it)?.complete(obj) }
                }
            }
        } catch (_: Exception) {
        } finally {
            closed = true
            pending.values.forEach { it.completeExceptionally(QmpException("QMP connection closed")) }
            greeting.completeExceptionally(QmpException("QMP connection closed"))
        }
    }, "qmp-reader").apply { isDaemon = true }

    fun handshake(timeoutMs: Long = 10_000) {
        reader.start()
        val g = greeting.get(timeoutMs, TimeUnit.MILLISECONDS)
        version = runCatching {
            val v = g["QMP"]!!.jsonObject["version"]!!.jsonObject["qemu"]!!.jsonObject
            "${v["major"]}.${v["minor"]}.${v["micro"]}"
        }.getOrDefault("")
        execute("qmp_capabilities")
    }

    @Synchronized
    private fun send(obj: JsonObject) {
        conn.output.write((obj.toString() + "\n").toByteArray())
        conn.output.flush()
    }

    fun execute(command: String, args: JsonObject? = null, timeoutMs: Long = 30_000): JsonElement? {
        if (closed) throw QmpException("QMP connection closed")
        val id = "dvm${ids.incrementAndGet()}"
        val f = CompletableFuture<JsonObject>()
        pending[id] = f
        send(buildJsonObject {
            put("execute", command)
            if (args != null) put("arguments", args)
            put("id", id)
        })
        val r = try { f.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        } finally { pending.remove(id) }
        r["error"]?.let { throw QmpException(it.jsonObject["desc"]?.jsonPrimitive?.content ?: it.toString()) }
        return r["return"]
    }

    /** Runs a human monitor command (savevm, loadvm, info snapshots, ...). Returns its text output. */
    fun hmp(cmdline: String, timeoutMs: Long = 600_000): String {
        val r = execute("human-monitor-command", buildJsonObject { put("command-line", cmdline) }, timeoutMs)
        return (r as? JsonPrimitive)?.content.orEmpty()
    }

    fun status(): String = runCatching {
        execute("query-status")?.jsonObject?.get("status")?.jsonPrimitive?.content ?: "unknown"
    }.getOrDefault("unknown")

    val isOpen get() = !closed

    override fun close() {
        closed = true
        runCatching { conn.close() }
    }
}

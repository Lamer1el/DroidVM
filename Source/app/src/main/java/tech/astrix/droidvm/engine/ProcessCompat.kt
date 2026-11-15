package tech.astrix.droidvm.engine

/** java.lang.Process helpers that exist on every API level >= 24 (isAlive/waitFor(timeout) need API 26). */
fun Process.alive(): Boolean = try { exitValue(); false } catch (_: IllegalThreadStateException) { true }

fun Process.waitForMs(timeoutMs: Long): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (!alive()) return true
        Thread.sleep(50)
    }
    return !alive()
}

/** SIGKILL via android.os.Process when a plain destroy() (SIGTERM on bionic) is ignored. */
fun Process.kill() {
    destroy()
    if (!waitForMs(1500)) {
        runCatching {
            val f = this.javaClass.getDeclaredField("pid").apply { isAccessible = true }
            android.os.Process.killProcess(f.getInt(this))
        }
    }
}

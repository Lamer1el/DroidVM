package tech.astrix.droidvm.i18n

import android.content.Context
import java.util.Locale

/**
 * Tiny runtime localisation. English strings in code are the keys; translations live in
 * assets/i18n/<lang>.tsv ("english<TAB>translation", "{}" = placeholder). The language follows the
 * system: Russian (ru) and Kazakh (kk) are translated, everything else stays English.
 */
object I18n {
    @Volatile var lang: String = "en"; private set
    private var exact: Map<String, String> = emptyMap()
    private var patterns: List<Pair<Regex, String>> = emptyList()
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 800
    }

    fun systemLang(): String = when (Locale.getDefault().language.lowercase()) {
        "ru" -> "ru"
        "kk", "kz" -> "kk"
        else -> "en"
    }

    fun init(ctx: Context) {
        lang = systemLang()
        if (lang == "en") { exact = emptyMap(); patterns = emptyList(); return }
        val ex = HashMap<String, String>(); val pt = ArrayList<Pair<Regex, String>>()
        runCatching {
            ctx.assets.open("i18n/$lang.tsv").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank() || line.startsWith("#")) return@forEach
                    val i = line.indexOf('\t'); if (i <= 0) return@forEach
                    val k = unescape(line.substring(0, i)); val v = unescape(line.substring(i + 1))
                    if (v.isEmpty()) return@forEach
                    if ("{}" in k) {
                        val rx = k.split("{}").joinToString("(.+?)") { Regex.escape(it) }
                        pt += Regex("^$rx$", RegexOption.DOT_MATCHES_ALL) to v
                    } else ex[k] = v
                }
            }
        }
        exact = ex; patterns = pt.sortedByDescending { it.first.pattern.length }
        synchronized(cache) { cache.clear() }
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\t", "\t")

    fun tr(en: String): String {
        if (lang == "en" || en.isEmpty()) return en
        exact[en]?.let { return it }
        if (en.length > 600) return en
        synchronized(cache) { cache[en] }?.let { return it }
        var out = en
        for ((rx, v) in patterns) {
            val m = rx.matchEntire(en) ?: continue
            var r = v; var idx = 1
            while ("{}" in r && idx < m.groupValues.size) {
                val g = m.groupValues[idx++]
                r = r.replaceFirst("{}", exact[g] ?: g)
            }
            out = r; break
        }
        synchronized(cache) { cache[en] = out }
        return out
    }
}

fun tr(en: String): String = I18n.tr(en)

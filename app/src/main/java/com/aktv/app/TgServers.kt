package com.aktv.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The same server list the TG TV website's player uses (Server 1, 2, 3 ...), fetched live with fresh tokens and
 * every ClearKey key. Server 3 is the website's working JioTV link, so it is tried first.
 */
object TgServers {
    class Server(val label: String, val url: String, val drm: JSONObject?)

    private const val SITE = "https://livetgtv.lovable.app"
    private const val FN = "211ba18c9fcbaf4e68e5bb262905070b60b7ed4ffe802d16fbbed00150f1c024"

    /** Turns the website's serialized answer into plain JSON values. */
    private fun dec(n: Any?): Any? {
        if (n !is JSONObject) return null
        return when (n.optInt("t", -1)) {
            0 -> n.opt("s")
            1 -> n.optString("s")
            2 -> when (n.optInt("s")) { 2 -> true; 3 -> false; else -> null }
            9 -> { val a = n.optJSONArray("a") ?: JSONArray(); val out = JSONArray(); for (i in 0 until a.length()) out.put(dec(a.opt(i)) ?: JSONObject.NULL); out }
            10, 11 -> {
                val p = n.optJSONObject("p"); val o = JSONObject()
                if (p != null) { val k = p.optJSONArray("k"); val v = p.optJSONArray("v")
                    if (k != null && v != null) for (i in 0 until k.length()) o.put(k.optString(i), dec(v.opt(i)) ?: JSONObject.NULL) }
                o
            }
            else -> null
        }
    }

    private fun withCookie(u: String, cookie: String): String {
        val tok = Regex("__hdnea__=([^&;\\s]+)").find(cookie)?.groupValues?.get(1)
        val clean = u.replace(Regex("(?<!:)//+"), "/")
        if (tok == null || clean.contains("__hdnea__")) return clean
        return clean + (if (clean.contains("?")) "&" else "?") + "__hdnea__=" + tok
    }

    private fun drmOf(o: JSONObject): JSONObject? {
        val arr = JSONArray()
        o.optJSONObject("clearKeys")?.let { ck -> ck.keys().forEach { k -> val v = ck.optString(k); if (v.isNotEmpty()) arr.put(JSONObject().put("keyId", k).put("key", v)) } }
        val kid = o.optString("keyId", ""); val key = o.optString("key", "")
        if (kid.isNotEmpty() && key.isNotEmpty() && kid != "null" && key != "null") arr.put(JSONObject().put("keyId", kid).put("key", key))
        return if (arr.length() == 0) null else JSONObject().put("type", "clearkey").put("keys", arr)
    }

    fun fetch(id: String): List<Server> {
        val inner = """{"t":10,"i":0,"p":{"k":["data"],"v":[{"t":10,"i":1,"p":{"k":["id"],"v":[{"t":1,"s":${JSONObject.quote(id)}}]},"o":0}]},"o":0}"""
        val payload = URLEncoder.encode("""{"t":$inner,"f":127,"m":[]}""", "UTF-8")
        val c = URL("$SITE/_serverFn/$FN?payload=$payload").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", Streams.CHROME)
        c.setRequestProperty("Accept", "application/x-tss-framed, application/x-ndjson, application/json")
        c.setRequestProperty("x-tsr-serverfn", "true")
        c.setRequestProperty("Referer", "$SITE/watch/$id")
        c.setRequestProperty("Sec-Fetch-Site", "same-origin"); c.setRequestProperty("Sec-Fetch-Mode", "cors")
        if (c.responseCode !in 200..299) { c.disconnect(); return emptyList() }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val root = dec(JSONObject(body.lineSequence().first { it.trim().startsWith("{") })) as? JSONObject ?: return emptyList()
        val r = root.optJSONObject("result") ?: return emptyList()
        val out = ArrayList<Server>()
        fun add(label: String, o: JSONObject) {
            val urls = o.optJSONArray("urls") ?: return
            val cookie = o.optString("cookie", ""); val drm = drmOf(o)
            for (i in 0 until urls.length()) { val u = urls.optString(i); if (u.startsWith("http")) out.add(Server(label, withCookie(u, cookie), drm)) }
        }
        add("Server 1", r)
        r.optJSONArray("backups")?.let { b -> for (i in 0 until b.length()) b.optJSONObject(i)?.let { add("Server ${i + 2}", it) } }
        // Server 3 first (what the website autoplays), then the rest in order
        return out.sortedBy { if (it.label == "Server 3") 0 else 1 }
    }
}

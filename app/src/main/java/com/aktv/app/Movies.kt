package com.aktv.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Movie & series catalogue (free Cinemeta catalogue: posters, backdrops, ratings, TMDB ids) and the
 * TGVID embed player links (https://tgvid.lovable.app/docs).
 */
object Movies {
    data class Title(
        val imdb: String, val tmdb: Int, val type: String, val name: String, val poster: String,
        val background: String, val logo: String, val year: String, val rating: String,
        val genres: List<String>, val description: String, val runtime: String = ""
    ) { val isSeries get() = type == "series" }

    data class Episode(val season: Int, val episode: Int, val name: String, val overview: String, val thumb: String)
    data class Shelf(val title: String, val type: String, val catalog: String, val genre: String?)

    private const val CAT = "https://v3-cinemeta.strem.io"
    private const val PLAYER = "https://tgvid.lovable.app/embed"
    private const val ACCENT = "e50914"

    /** Every shelf shown on the Movies & Series tab, top to bottom. */
    val SHELVES: List<Shelf> = listOf(
        Shelf("Trending movies", "movie", "top", null),
        Shelf("Trending series", "series", "top", null),
        Shelf("Top rated movies", "movie", "imdbRating", null),
        Shelf("Top rated series", "series", "imdbRating", null)
    ) + listOf("Action", "Comedy", "Thriller", "Sci-Fi", "Horror", "Romance", "Animation", "Crime", "Drama", "Family", "Fantasy", "Mystery", "Adventure", "Documentary")
        .map { Shelf("$it movies", "movie", "top", it) } +
        listOf("Action", "Comedy", "Crime", "Drama", "Sci-Fi", "Animation", "Mystery", "Fantasy", "Reality-TV")
            .map { Shelf("$it series", "series", "top", it) }

    fun movieUrl(tmdb: Int, t: Long = 0) = "$PLAYER/movie/$tmdb?color=$ACCENT&back=false" + (if (t > 5) "&t=$t" else "")
    fun tvUrl(tmdb: Int, s: Int, e: Int, t: Long = 0) =
        "$PLAYER/tv/$tmdb/$s/$e?color=$ACCENT&back=false&autoplayNextEp=true" + (if (t > 5) "&t=$t" else "")

    private val cache = HashMap<String, List<Title>>()

    private fun http(u: String): String {
        var last: Exception? = null
        for (n in 1..3) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 12000; c.readTimeout = 15000; c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", Streams.CHROME); c.setRequestProperty("Accept", "application/json")
                if (c.responseCode in 200..299) return c.inputStream.bufferedReader().use { it.readText() }
                last = IOException("HTTP ${c.responseCode}"); c.disconnect()
            } catch (e: Exception) { last = e }
            Thread.sleep(500L * n)
        }
        throw last ?: IOException("no response")
    }

    private fun s(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k, "")

    private fun parse(o: JSONObject, fallbackType: String): Title? {
        val imdb = s(o, "imdb_id").ifEmpty { s(o, "id") }
        val name = s(o, "name")
        if (!imdb.startsWith("tt") || name.isEmpty()) return null
        val g = o.optJSONArray("genre") ?: o.optJSONArray("genres")
        val genres = if (g == null) emptyList() else (0 until g.length()).map { g.optString(it) }.filter { it.isNotEmpty() }
        return Title(
            imdb, o.optInt("moviedb_id", 0), s(o, "type").ifEmpty { fallbackType }, name,
            s(o, "poster").replace("/small/", "/medium/"), s(o, "background"), s(o, "logo"),
            s(o, "releaseInfo").ifEmpty { s(o, "year") }, s(o, "imdbRating"), genres, s(o, "description"), s(o, "runtime")
        )
    }

    /** One shelf of titles (cached for the session). Titles without a TMDB id can't play, so they are left out. */
    fun shelf(sh: Shelf): List<Title> {
        val key = "${sh.type}/${sh.catalog}/${sh.genre}"
        cache[key]?.let { return it }
        val extra = if (sh.genre != null) "/" + URLEncoder.encode("genre=${sh.genre}", "UTF-8").replace("+", "%20") else ""
        val arr = JSONObject(http("$CAT/catalog/${sh.type}/${sh.catalog}$extra.json")).optJSONArray("metas") ?: return emptyList()
        val out = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> parse(o, sh.type) } }
            .filter { it.tmdb > 0 && it.poster.isNotEmpty() }.distinctBy { it.imdb }
        synchronized(cache) { cache[key] = out }
        return out
    }

    fun search(q: String): List<Title> {
        val enc = URLEncoder.encode("search=$q", "UTF-8").replace("+", "%20")
        return listOf("movie", "series").flatMap { t ->
            try {
                val arr = JSONObject(http("$CAT/catalog/$t/top/$enc.json")).optJSONArray("metas")
                if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> parse(o, t) } }
            } catch (_: Exception) { emptyList() }
        }.filter { it.tmdb > 0 }
    }

    /** Full details; for series also every episode (specials left out). */
    fun details(type: String, imdb: String): Pair<Title?, List<Episode>> {
        val m = JSONObject(http("$CAT/meta/$type/$imdb.json")).optJSONObject("meta") ?: return null to emptyList()
        val t = parse(m, type)
        val eps = ArrayList<Episode>()
        m.optJSONArray("videos")?.let { v ->
            for (i in 0 until v.length()) {
                val o = v.optJSONObject(i) ?: continue
                val se = o.optInt("season", 0); val ep = o.optInt("episode", o.optInt("number", 0))
                if (se <= 0 || ep <= 0) continue
                eps.add(Episode(se, ep, s(o, "name").ifEmpty { "Episode $ep" }, s(o, "overview").ifEmpty { s(o, "description") }, s(o, "thumbnail")))
            }
        }
        return t to eps.distinctBy { it.season * 10000 + it.episode }.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    // ---------- continue watching (saved positions from the player) ----------
    fun saveTitle(p: android.content.SharedPreferences, t: Title) {
        val o = JSONObject().put("imdb", t.imdb).put("tmdb", t.tmdb).put("type", t.type).put("name", t.name)
            .put("poster", t.poster).put("background", t.background).put("logo", t.logo).put("year", t.year)
            .put("rating", t.rating).put("desc", t.description).put("genres", t.genres.joinToString("|"))
        val list = recent(p).filter { it.imdb != t.imdb }.toMutableList()
        val arr = org.json.JSONArray().put(o)
        list.take(19).forEach { r ->
            arr.put(JSONObject().put("imdb", r.imdb).put("tmdb", r.tmdb).put("type", r.type).put("name", r.name)
                .put("poster", r.poster).put("background", r.background).put("logo", r.logo).put("year", r.year)
                .put("rating", r.rating).put("desc", r.description).put("genres", r.genres.joinToString("|")))
        }
        p.edit().putString("movie_recent", arr.toString()).apply()
    }

    fun recent(p: android.content.SharedPreferences): List<Title> = try {
        val arr = org.json.JSONArray(p.getString("movie_recent", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Title(s(o, "imdb"), o.optInt("tmdb"), s(o, "type"), s(o, "name"), s(o, "poster"), s(o, "background"),
                s(o, "logo"), s(o, "year"), s(o, "rating"), s(o, "genres").split("|").filter { it.isNotEmpty() }, s(o, "desc"))
        }
    } catch (_: Exception) { emptyList() }
}

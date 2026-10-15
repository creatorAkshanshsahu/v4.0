package com.aktv.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.Base64
import android.view.*
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.*
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.*
import coil.load
import coil.imageLoader
import coil.request.ImageRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class VH(v: View) : RecyclerView.ViewHolder(v)

/** The TG TV API answered with a non-2xx status. */
class ApiException(val code: Int) : IOException("HTTP $code")

class MainActivity : AppCompatActivity() {
    /** V1 = live TV, v2 = the 24/7 catalogue. [key] is unique across both, so favourites and glow colours never collide. */
    data class Channel(val id: String, val name: String, val category: String, val logo: String, val v2: Boolean = false) {
        val key: String get() = if (v2) "v2:$id" else id
    }
    sealed class Tile {
        data class Ch(val c: Channel) : Tile()
        data class Mv(val m: Movies.Title) : Tile()
        object Hint : Tile()
    }
    class Row(val title: String, var tiles: List<Tile>)

    // movie & series shelves (filled the first time the tab opens)
    private var movieRows = listOf<Row>(); private var moviesLoading = false
    private val RECENT = "Recently watched"; private val CONTINUE = "Continue watching"
    private lateinit var heroBackdrop: ImageView; private lateinit var heroPreview: View; private lateinit var previewTex: TextureView
    private var previewPlayer: ExoPlayer? = null; private var previewToken = 0
    // ➜ CHANNEL UPDATE LINKS: the app re-reads both lists from here every launch
    private val SITE = "https://livetgtv.lovable.app"
    private val BASE = "$SITE/api/public/channels"            // V1 live TV
    private val BASE_V2 = "$SITE/api/public/v2/channels"      // V2 24/7
    private val GUIDE = "$SITE/api/public/guide"              // programme details (V1)
    private val UA = "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 Chrome/110 Safari/537.36"
    private val io = Executors.newFixedThreadPool(4)
    private val prefs by lazy { getSharedPreferences("aktv", Context.MODE_PRIVATE) }
    private val dp by lazy { resources.displayMetrics.density }
    private val ui = Handler(Looper.getMainLooper())
    private val fRegular by lazy { ResourcesCompat.getFont(this, R.font.sora_regular) ?: Typeface.DEFAULT }
    private val fSemi by lazy { ResourcesCompat.getFont(this, R.font.sora_semibold) ?: Typeface.DEFAULT_BOLD }

    private var all = listOf<Channel>(); private var allV2 = listOf<Channel>(); private var shown = listOf<Channel>()
    private var rows = listOf<Row>(); private val inner = HashMap<Int, TileAdapter>()
    private var favs = setOf<String>(); private var heroTile: Tile? = null
    private val accents = HashMap<String, Int>()
    private var tab = 0; private var current = -1; private var retries = 0
    private var player: ExoPlayer? = null
    private var lastRefresh = 0L; private var v1Msg = "Loading channels…"

    // playback state: how this channel is being tried, so a refused stream can move on by itself
    private var playToken = 0
    private var routes = listOf<Pair<Int, Int>>(); private var routeIdx = 0          // (source, header profile)
    private var v1Sources = listOf<String>(); private var v1Drms = listOf<JSONObject?>()
    private var v2Servers = listOf<Pair<String, String>>(); private var serverIdx = 0  // (relay url, type)
    private var attempts = 0; private var refreshed = false; private var lastCode: Int? = null
    private var okLong = false; private var stalls = 0

    private lateinit var rowsView: RecyclerView; private lateinit var status: TextView
    private lateinit var layer: View; private lateinit var pv: PlayerView; private lateinit var spinner: ProgressBar
    private lateinit var segLive: TextView; private lateinit var segV2: TextView; private lateinit var segSites: TextView
    private val segs by lazy { listOf(segLive, segV2, segSites) }
    private lateinit var segIndicator: View; private val segPill = GradientDrawable()
    // left side rail (Google TV style): icons only, expands with labels while it holds focus
    private lateinit var rail: View; private lateinit var railScrim: View
    private var railF = 0f; private var railTarget = 0f; private var railAnim: ValueAnimator? = null
    private var lastRowsFocus: View? = null
    private val railCheck = Runnable { applyRail() }
    private lateinit var searchBtn: TextView; private lateinit var intro: AkIntroView
    private lateinit var aurora: AuroraView
    private lateinit var heroText: View; private lateinit var heroDot: View; private lateinit var heroBadge: TextView
    private lateinit var heroTitle: TextView; private lateinit var heroChips: LinearLayout
    private lateinit var heroOrb: View; private lateinit var heroLogo: ImageView; private lateinit var heroMono: TextView
    private lateinit var heroProg: TextView; private lateinit var heroProgress: ProgressBar
    private lateinit var heroDesc: TextView; private lateinit var heroNext: TextView
    private val orbGlass by lazy { GlassDrawable(dp) }
    private lateinit var infoCard: View; private lateinit var infoLogo: ImageView; private lateinit var infoName: TextView
    private lateinit var infoCat: TextView; private lateinit var playerScrim: View; private lateinit var playerClock: View
    private lateinit var infoMono: TextView; private lateinit var infoNow: TextView; private lateinit var infoProgress: ProgressBar
    private lateinit var infoDesc: TextView; private lateinit var infoNext: TextView; private lateinit var playStatus: TextView
    // channel guide
    private val FAV = "Favorites"
    private lateinit var home: ViewGroup; private var homeFocusMode = 0; private var lastHomeFocus: View? = null
    private lateinit var guide: ViewGroup; private lateinit var guideScrim: View; private lateinit var guideDivider: View
    private lateinit var catList: RecyclerView; private lateinit var chList: RecyclerView
    private lateinit var chCol: View; private lateinit var chHeader: TextView; private lateinit var channelSearch: EditText
    private var guideShown = false; private var shownCat = ""
    private var guideCats = listOf<String>(); private var catCounts = mapOf<String, Int>()
    private var guideCat = ""; private var guideChannels = listOf<Channel>(); private var categoryChannels = listOf<Channel>()
    private val infoGlass by lazy { GlassDrawable(dp, 24f).apply { base = Color.argb(150, 12, 14, 32) } }
    private val guideGlass by lazy { GlassDrawable(dp, 28f).apply { base = Color.argb(185, 10, 12, 28) } }
    // search
    private lateinit var searchLayer: View; private lateinit var searchQuery: TextView; private lateinit var searchKeys: LinearLayout
    private lateinit var searchResults: RecyclerView; private lateinit var searchCount: TextView; private lateinit var searchEmpty: TextView
    private var searchShown = false; private val searchBuf = StringBuilder(); private var results = listOf<Channel>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        rowsView = findViewById(R.id.rows); status = findViewById(R.id.status)
        layer = findViewById(R.id.playerLayer); pv = findViewById(R.id.playerView); spinner = findViewById(R.id.spinner)
        segLive = findViewById(R.id.segLive); segV2 = findViewById(R.id.segV2); segSites = findViewById(R.id.segSites)
        segIndicator = findViewById(R.id.segIndicator); searchBtn = findViewById(R.id.searchBtn)
        rail = findViewById(R.id.rail); railScrim = findViewById(R.id.railScrim)
        aurora = findViewById(R.id.aurora)
        heroText = findViewById(R.id.heroText); heroDot = findViewById(R.id.heroDot); heroBadge = findViewById(R.id.heroBadge)
        heroTitle = findViewById(R.id.heroTitle); heroChips = findViewById(R.id.heroChips)
        heroOrb = findViewById(R.id.heroOrb); heroLogo = findViewById(R.id.heroLogo); heroMono = findViewById(R.id.heroMono)
        heroProg = findViewById(R.id.heroProg); heroProgress = findViewById(R.id.heroProgress)
        heroDesc = findViewById(R.id.heroDesc); heroNext = findViewById(R.id.heroNext)
        infoCard = findViewById(R.id.infoCard); infoLogo = findViewById(R.id.infoLogo); infoName = findViewById(R.id.infoName)
        infoCat = findViewById(R.id.infoCat); playerScrim = findViewById(R.id.playerScrim); playerClock = findViewById(R.id.playerClock)
        infoMono = findViewById(R.id.infoMono); infoNow = findViewById(R.id.infoNow); infoProgress = findViewById(R.id.infoProgress)
        infoDesc = findViewById(R.id.infoDesc); infoNext = findViewById(R.id.infoNext); playStatus = findViewById(R.id.playStatus)
        searchLayer = findViewById(R.id.searchLayer); searchQuery = findViewById(R.id.searchQuery); searchKeys = findViewById(R.id.searchKeys)
        searchResults = findViewById(R.id.searchResults); searchCount = findViewById(R.id.searchCount); searchEmpty = findViewById(R.id.searchEmpty)
        intro = findViewById(R.id.splash)
        heroBackdrop = findViewById(R.id.heroBackdrop); heroPreview = findViewById(R.id.heroPreview); previewTex = findViewById(R.id.heroPreviewTex)
        heroPreview.clipToOutline = true
        heroPreview.background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(Color.BLACK) }
        heroPreview.elevation = 18 * dp

        // glass surfaces
        segPill.cornerRadius = 40 * dp; segIndicator.background = segPill
        heroOrb.background = orbGlass; heroOrb.outlineAmbientShadowColor = Accent.SIGNAL; heroOrb.outlineSpotShadowColor = Accent.SIGNAL
        heroOrb.elevation = 18 * dp
        infoCard.background = infoGlass
        findViewById<View>(R.id.infoLogoBox).background = GlassDrawable(dp, 16f)
        findViewById<TextView>(R.id.infoHint).text = SpannableStringBuilder().apply {
            key(this, "▲ ▼", "Channel"); append("     "); key(this, "▶", "All channels"); append("     ")
            key(this, "Hold OK", "Reload"); append("     "); key(this, "◀", "Switch player"); append("     "); key(this, "Back", "Exit")
        }
        val track = ColorStateList.valueOf(Color.argb(60, 255, 255, 255))
        heroProgress.progressBackgroundTintList = track; infoProgress.progressBackgroundTintList = track

        // search button + compact top bar for small screens
        val sbPill = GradientDrawable().apply { cornerRadius = 40 * dp; setColor(Color.TRANSPARENT) }
        searchBtn.background = sbPill
        searchBtn.setOnFocusChangeListener { _, f ->
            sbPill.setColor(if (f) Color.WHITE else Color.TRANSPARENT)
            searchBtn.setTextColor(if (f) Accent.ABYSS else Accent.DIM)
            searchBtn.compoundDrawableTintList = ColorStateList.valueOf(if (f) Accent.ABYSS else Accent.DIM)
            styleSegs()
        }
        searchBtn.setOnClickListener { openSearch() }
        fitTopBar()

        home = findViewById(R.id.home); homeFocusMode = home.descendantFocusability
        guide = findViewById(R.id.guide); guideScrim = findViewById(R.id.guideScrim); guideDivider = findViewById(R.id.guideDivider)
        catList = findViewById(R.id.catList); chList = findViewById(R.id.chList)
        chCol = findViewById(R.id.chCol); chHeader = findViewById(R.id.chHeader); channelSearch = findViewById(R.id.channelSearch)
        guide.background = guideGlass
        guide.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        listOf(catList to catAdapter, chList to chAdapter).forEach { (rv, ad) ->
            rv.layoutManager = LinearLayoutManager(this); rv.adapter = ad; rv.itemAnimator = null
            rv.overScrollMode = View.OVER_SCROLL_NEVER
        }
        searchResults.layoutManager = LinearLayoutManager(this); searchResults.adapter = searchAdapter
        searchResults.itemAnimator = null; searchResults.overScrollMode = View.OVER_SCROLL_NEVER
        searchQuery.background = GlassDrawable(dp, 16f).apply { base = Color.argb(34, 255, 255, 255) }
        buildKeys(); renderQuery()

        favs = prefs.getStringSet("favs", emptySet())!!.toSet()
        rowsView.layoutManager = SnapLayoutManager(this, RecyclerView.VERTICAL); rowsView.adapter = rowAdapter
        rowsView.itemAnimator = null; rowsView.setItemViewCacheSize(6); rowsView.setHasFixedSize(true)
        (rowsView.layoutManager as LinearLayoutManager).initialPrefetchItemCount = 3
        rowsView.overScrollMode = View.OVER_SCROLL_NEVER
        rowsView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            dimRows()
            if (new != null && isInside(new, rowsView)) lastRowsFocus = new
            val ni = segs.indexOfFirst { it === new }
            if (ni >= 0) {
                val fromSeg = segs.any { it === old } || old === searchBtn
                if (fromSeg) switchTab(ni)
                else if (ni != tab) segs[tab].post { if (segs[ni].isFocused) segs[tab].requestFocus() }   // focus fell onto the bar by accident
            }
        }
        rowsView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && tab != 2) aurora.resume() else aurora.pause()
            }
        })
        channelSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim().orEmpty()
                guideChannels = if (q.isEmpty()) categoryChannels else categoryChannels.filter {
                    it.name.contains(q, ignoreCase = true) || it.category.contains(q, ignoreCase = true)
                }
                chHeader.text = "$guideCat  ${guideChannels.size}"
                chAdapter.notifyDataSetChanged()
                chList.scrollToPosition(0)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        segs.forEachIndexed { i, tv ->
            tv.setOnClickListener { switchTab(i) }
            tv.setOnFocusChangeListener { _, _ -> styleSegs() }
        }
        segLive.post { moveIndicator(false) }
        styleSegs(); fillHero(null); loadChannels()

        // Entrance: the AK ribbons unfold, then the mark flies through and the home screen rises in behind it.
        intro.wordmark = fSemi
        home.alpha = 0f; home.translationY = 28 * dp
        intro.onReveal = {
            home.animate().alpha(1f).translationY(0f).setDuration(650).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        intro.onDone = { intro.visibility = View.GONE; rowsView.requestFocus() }
        Movies.diskDir = cacheDir
        ui.postDelayed({ loadMovies() }, 1500)   // warm up the Movies tab in the background
        intro.start()
    }

    // ---------- helpers ----------
    private fun key(sb: SpannableStringBuilder, k: String, label: String) {
        val s = sb.length; sb.append(k)
        sb.setSpan(ForegroundColorSpan(Accent.TEXT), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(TypefaceSpan(fSemi), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("  ").append(label)
    }
    private fun keyHint(k: String, label: String) = SpannableStringBuilder().also { key(it, k, label) }

    private fun chip(text: CharSequence) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(Accent.DIM); typeface = fRegular
        setPadding((14 * dp).toInt(), (7 * dp).toInt(), (14 * dp).toInt(), (7 * dp).toInt())
        background = GlassDrawable(dp)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (10 * dp).toInt() }
    }

    /** The side rail is icon-only until it gets focus, so it fits every screen; just drop the logo word on very short screens. */
    private fun fitTopBar() {
        if (resources.configuration.screenHeightDp < 400) findViewById<View>(R.id.akText).visibility = View.GONE
    }

    private fun styleSegs() {
        val segFocused = segs.any { it.isFocused }
        segPill.setColor(if (segFocused) Color.WHITE else Color.argb(40, 255, 255, 255))
        segs.forEachIndexed { i, tv ->
            val c = when { tv.isFocused -> Accent.ABYSS; tab == i -> Accent.TEXT; else -> Accent.DIM }
            tv.setTextColor(c); tv.compoundDrawableTintList = ColorStateList.valueOf(c)
        }
        // evaluated after the focus change settles, so moving between rail items never collapses and re-opens it
        ui.removeCallbacks(railCheck); ui.post(railCheck)
    }
    private fun indicatorW() = ((56 + (204 - 56) * railF) * dp).toInt()
    /** Opens the rail while any of its items has focus, and closes it when focus moves into the content. */
    private fun applyRail() {
        val want = if (searchBtn.isFocused || segs.any { it.isFocused }) 1f else 0f
        if (want == railTarget) return
        railTarget = want
        railAnim?.cancel()
        railAnim = ValueAnimator.ofFloat(railF, want).apply {
            duration = 260; interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                railF = it.animatedValue as Float
                val rl = rail.layoutParams; rl.width = ((84 + (232 - 84) * railF) * dp).toInt(); rail.layoutParams = rl
                railScrim.alpha = railF
                val il = segIndicator.layoutParams; il.width = indicatorW(); segIndicator.layoutParams = il
            }
            start()
        }
    }
    /** The selected-tab pill slides up and down the rail. */
    private fun moveIndicator(animate: Boolean) {
        val target = segs[tab]
        if (target.height == 0) { target.post { moveIndicator(false) }; return }
        val lp = segIndicator.layoutParams
        val y0 = segIndicator.translationY; val h0 = lp.height
        val y1 = target.top.toFloat(); val h1 = target.height
        segIndicator.translationX = target.left.toFloat()
        lp.width = indicatorW()
        if (!animate || h0 <= 0 || h0 != h1) { segIndicator.translationY = y1; lp.height = h1; segIndicator.layoutParams = lp; return }
        segIndicator.layoutParams = lp
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { segIndicator.translationY = y0 + (y1 - y0) * (it.animatedValue as Float) }
            start()
        }
    }

    private fun focusAnim(x: View, f: Boolean, color: Int) {
        x.outlineAmbientShadowColor = color; x.outlineSpotShadowColor = color
        val s = if (f) 1.06f else 1f
        x.animate().cancel()
        x.animate().setStartDelay(0).scaleX(s).scaleY(s).translationZ(if (f) 24 * dp else 0f)
            .setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
    }
    /**
     * Rows without focus step back so the active row reads first. Only the row's title steps back:
     * fading the whole row put it in an offscreen layer that clipped the tiles' glow into a hard dark band over the channels.
     */
    private fun dimRows() {
        val inRows = rowsView.hasFocus()
        for (i in 0 until rowsView.childCount) {
            val c = rowsView.getChildAt(i)
            val a = if (!inRows || c.hasFocus()) 1f else 0.45f
            for (id in intArrayOf(R.id.rowTitle, R.id.rowCount)) {
                val t = c.findViewById<View>(id) ?: continue
                if (t.alpha != a) t.animate().alpha(a).setDuration(220).start()
            }
        }
    }
    private fun focusRowAt(pos: Int) {
        val holder = rowsView.findViewHolderForAdapterPosition(pos) ?: return
        val rv = holder.itemView.findViewById<RecyclerView>(R.id.rowList) ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0)
        (lm.findViewByPosition(first) ?: rv.getChildAt(0))?.requestFocus()
    }
    private fun isInside(v: View, parent: View): Boolean {
        var p: ViewParent? = v.parent
        while (p != null) { if (p === parent) return true; p = p.parent }
        return false
    }
    private fun accentOf(key: String) = accents[key] ?: Accent.SIGNAL
    private fun extractAccent(key: String, d: android.graphics.drawable.Drawable, cb: (Int) -> Unit) {
        accents[key]?.let { cb(it); return }
        Accent.from(d) { c -> accents[key] = c; cb(c) }
    }
    private fun str(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k, "")
    private fun initials(n: String): String {
        val w = n.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return (if (w.size >= 2) "${w[0].first()}${w[1].first()}" else n.trim().take(2)).uppercase()
    }
    /** Channel logo, or a coloured monogram when the channel has no logo (all of V2). */
    private fun bindLogo(img: ImageView, mono: TextView, ch: Channel) {
        if (ch.logo.isBlank()) {
            img.setImageDrawable(null); img.visibility = View.GONE
            mono.visibility = View.VISIBLE; mono.text = initials(ch.name); mono.setTextColor(accentOf(ch.key))
        } else { mono.visibility = View.GONE; img.visibility = View.VISIBLE; img.load(ch.logo) }
    }

    /**
     * GET with quick retries: the API answers 502 now and then when its upstream blinks, and
     * that used to surface straight away as a failed channel.
     */
    private fun get(u: String, tries: Int = 3, ua: String = UA): String {
        var last: Exception? = null
        for (n in 1..tries) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 15000
                c.setRequestProperty("User-Agent", ua); c.setRequestProperty("Accept", "application/json")
                val code = c.responseCode
                if (code in 200..299) return c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                last = ApiException(code)
                if (code in 400..499 && code != 408 && code != 429) break      // asking again won't change a 4xx
            } catch (e: IOException) { last = e }
            if (n < tries) Thread.sleep(600L * n)
        }
        throw last ?: IOException("no response")
    }
    private fun why(e: Throwable): String = when {
        e is ApiException && e.code == 404 -> "no live stream for this channel right now"
        e is ApiException && e.code == 400 -> "unknown channel"
        e is ApiException && e.code >= 500 -> "the TG TV server is busy (HTTP ${e.code}), try again in a moment"
        e is ApiException -> "HTTP ${e.code}"
        e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException -> "no connection to the server"
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---------- data ----------
    private fun cacheFile(n: String) = File(filesDir, n)
    private fun readCache(n: String): String? = try { cacheFile(n).takeIf { it.exists() }?.readText() } catch (e: Exception) { null }
    private fun writeCache(n: String, s: String) { try { cacheFile(n).writeText(s) } catch (_: Exception) {} }

    private fun parseV1(json: String): List<Channel> {
        val arr = JSONObject(json).getJSONArray("channels")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = str(o, "id"); val name = str(o, "name").trim()
            if (id.isEmpty() || name.isEmpty()) null
            else Channel(id, name, str(o, "category").ifBlank { "Other" }, str(o, "logo"))
        }
    }
    private fun parseV2(json: String): List<Channel> {
        val arr = JSONObject(json).getJSONArray("channels")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = str(o, "id"); val name = str(o, "name").trim()
            if (id.isEmpty() || name.isEmpty()) null
            else {
                val first = name.first().uppercaseChar()
                Channel(id, name, str(o, "category").ifBlank { if (first.isLetter()) first.toString() else "#" }, str(o, "logo"), true)
            }
        }.sortedBy { it.name.lowercase() }
    }
    /** Channels without a logo to sample get a stable colour from their name, so the UI still reacts to them. */
    private fun seedAccents(list: List<Channel>) { for (c in list) if (c.logo.isBlank()) accents[c.key] = Accent.fromName(c.name) }

    private fun applyV1(list: List<Channel>) {
        if (list == all) return
        all = list; seedAccents(list); updateStatus()
        if (tab == 0) { refreshRows(); if (intro.visibility != View.VISIBLE && currentFocus == null) rowsView.requestFocus() }
        if (searchShown) updateResults()
    }
    private fun applyV2(list: List<Channel>) {
        if (list == allV2) return
        allV2 = list; seedAccents(list); updateStatus()
        if (tab == 1) { refreshRows(); if (intro.visibility != View.VISIBLE && currentFocus == null) rowsView.requestFocus() }
        if (searchShown) updateResults()
    }
    private fun updateStatus() {
        val msg = when { tab == 0 && all.isEmpty() -> v1Msg; tab == 1 && allV2.isEmpty() -> "Loading 24/7 channels…"
            tab == 2 && movieRows.isEmpty() -> if (moviesLoading) "Loading movies & series…" else "Movies didn't load. Check the connection and open the tab again."
            else -> "" }
        status.text = msg; status.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Shows the saved lists instantly, then re-reads both update links so new / changed channels arrive on their own. */
    private fun loadChannels() {
        lastRefresh = System.currentTimeMillis()
        readCache("v1.json")?.let { try { applyV1(parseV1(it)) } catch (_: Exception) {} }
        readCache("v2.json")?.let { try { applyV2(parseV2(it)) } catch (_: Exception) {} }
        v1Msg = "Loading channels…"; updateStatus()
        io.execute {
            try {
                val j = get(BASE); val list = parseV1(j)
                if (list.isEmpty()) throw IOException("empty channel list")
                writeCache("v1.json", j)
                runOnUiThread { applyV1(list) }
            } catch (e: Exception) {
                runOnUiThread {
                    if (all.isEmpty()) { v1Msg = "Channels didn't load (${why(e)}). Check the connection and reopen the app."; updateStatus() }
                }
            }
        }
        io.execute {
            try {
                val j = get(BASE_V2); val list = parseV2(j)
                if (list.isEmpty()) throw IOException("empty list")
                writeCache("v2.json", j)
                runOnUiThread { applyV2(list) }
            } catch (_: Exception) {}
        }
    }

    private fun pool() = if (tab == 1) allV2 else all
    private fun favTiles(): List<Tile> {
        val l = pool().filter { it.key in favs }.map { Tile.Ch(it) }
        return if (l.isEmpty()) listOf(Tile.Hint) else l
    }

    /** Channels played lately (newest first), limited to the current tab's list. */
    private fun recentRow(): List<Row> {
        val keys = prefs.getString("recent_ch", "")!!.split(",").filter { it.isNotEmpty() }
        val byKey = pool().associateBy { it.key }
        val l = keys.mapNotNull { byKey[it] }.map { Tile.Ch(it) }
        return if (l.isEmpty()) emptyList() else listOf(Row(RECENT, l))
    }
    private fun rememberChannel(c: Channel) {
        val keys = listOf(c.key) + prefs.getString("recent_ch", "")!!.split(",").filter { it.isNotEmpty() && it != c.key }
        prefs.edit().putString("recent_ch", keys.take(20).joinToString(",")).apply()
    }
    private fun continueRow(): List<Row> {
        val l = Movies.recent(prefs).map { Tile.Mv(it) }
        return if (l.isEmpty()) emptyList() else listOf(Row(CONTINUE, l))
    }
    /** Loads movie / series shelves in parallel and shows each one the moment it (and those above it) are ready. */
    private fun loadMovies() {
        if (moviesLoading || movieRows.isNotEmpty()) return
        moviesLoading = true; updateStatus()
        Thread {
            val n = Movies.SHELVES.size
            val res = arrayOfNulls<Row>(n); val done = BooleanArray(n)
            val pool = Executors.newFixedThreadPool(8)
            var published = 0
            val publish = {
                runOnUiThread {
                    synchronized(res) {
                        var k = published
                        while (k < n && done[k]) k++
                        if (k > published) {
                            published = k
                            val fresh = res.take(k).filterNotNull()
                            val before = movieRows.size; movieRows = fresh
                            if (tab == 2) {
                                if (before == 0) { refreshRows(); if (segs.none { it.isFocused } && !searchBtn.isFocused && !searchShown) rowsView.requestFocus() }
                                else {
                                    val base = continueRow().size
                                    rows = continueRow() + movieRows
                                    if (fresh.size > before) rowAdapter.notifyItemRangeInserted(base + before, fresh.size - before)
                                }
                            }
                            updateStatus()
                        }
                    }
                }
            }
            Movies.SHELVES.forEachIndexed { i, sh ->
                pool.execute {
                    try { val l = Movies.shelf(sh); if (l.isNotEmpty()) synchronized(res) { res[i] = Row(sh.title, l.map { Tile.Mv(it) }) } } catch (_: Exception) {}
                    synchronized(res) { done[i] = true }
                    publish()
                }
            }
            pool.shutdown(); pool.awaitTermination(40, java.util.concurrent.TimeUnit.SECONDS)
            runOnUiThread { moviesLoading = false; updateStatus() }
        }.start()
    }

    private fun refreshRows() {
        rows = when (tab) {
            0 -> listOf(Row(FAV, favTiles())) + recentRow() + all.groupBy { it.category }.toSortedMap().map { (k, v) -> Row(k, v.map { Tile.Ch(it) }) }
            1 -> listOf(Row(FAV, favTiles())) + recentRow() + allV2.groupBy { it.category }.toSortedMap().map { (k, v) -> Row(k, v.map { Tile.Ch(it) }) }
            else -> { loadMovies(); continueRow() + movieRows }
        }
        inner.clear()
        rowAdapter.notifyDataSetChanged()
        val first: Tile? = when (tab) {
            0 -> pool().firstOrNull()?.let { Tile.Ch(it) }
            1 -> pool().firstOrNull()?.let { Tile.Ch(it) }
            else -> rows.firstOrNull()?.tiles?.firstOrNull()
        }
        setHero(first, immediate = true)
        updateStatus()
    }

    private fun toggleFav(c: Channel) {
        val s = favs.toMutableSet(); val added = s.add(c.key); if (!added) s.remove(c.key)
        favs = s; prefs.edit().putStringSet("favs", s).apply()
        Toast.makeText(this, if (added) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
        for ((ri, ad) in inner) if (ri > 0) {
            val idx = rows.getOrNull(ri)?.tiles?.indexOfFirst { it is Tile.Ch && it.c.key == c.key } ?: -1
            if (idx >= 0) ad.notifyItemChanged(idx, "fav")
        }
        if (rows.firstOrNull()?.title == FAV) { rows[0].tiles = favTiles(); rowAdapter.notifyItemChanged(0) }
        (heroTile as? Tile.Ch)?.let { if (it.c.key == c.key) fillHero(it) }
    }

    private fun switchTab(t: Int) {
        if (t == tab) return
        val dir = if (t > tab) 1 else -1
        tab = t; styleSegs(); moveIndicator(true); stopPreview()
        val shift = 28 * dp * dir
        rowsView.animate().cancel()
        rowsView.animate().alpha(0f).translationY(-shift).setDuration(150).withEndAction {
            refreshRows(); rowsView.scrollToPosition(0)
            rowsView.translationY = shift
            rowsView.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator(1.8f)).start()
        }.start()
    }

    // ---------- hero ----------
    private val heroRunnable = Runnable { applyHero(heroTile) }
    private fun prefetch(url: String) {
        if (url.isEmpty()) return
        imageLoader.enqueue(ImageRequest.Builder(this).data(url).build())
    }
    private fun setHero(t: Tile?, immediate: Boolean = false) {
        heroTile = t; ui.removeCallbacks(heroRunnable)
        if (immediate) fillHero(t) else ui.postDelayed(heroRunnable, 130)   // debounce fast scrolling
    }
    private fun applyHero(t: Tile?) {
        heroText.animate().cancel()
        heroText.animate().alpha(0f).translationX(-10 * dp).setDuration(110).withEndAction {
            fillHero(t); heroText.translationX = 16 * dp
            heroText.animate().alpha(1f).translationX(0f).setDuration(300).setInterpolator(DecelerateInterpolator(2f)).start()
        }.start()
        heroOrb.scaleX = 0.94f; heroOrb.scaleY = 0.94f
        heroOrb.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(1.2f)).start()
    }
    private fun glowHero(c: Int, partner: Int) {
        aurora.setPalette(c, partner); orbGlass.accent = c
        heroOrb.outlineAmbientShadowColor = c; heroOrb.outlineSpotShadowColor = c
        heroProgress.progressTintList = ColorStateList.valueOf(c)
    }
    private fun showBackdrop(url: String?) {
        if (url.isNullOrEmpty()) { heroBackdrop.animate().alpha(0f).setDuration(350).start(); heroOrb.animate().alpha(1f).setDuration(250).start(); return }
        heroOrb.animate().alpha(0f).setDuration(200).start()
        heroBackdrop.load(url) {
            crossfade(180)
            listener(onSuccess = { _, _ -> if ((heroTile as? Tile.Mv)?.m?.background == url) heroBackdrop.animate().alpha(0.9f).setDuration(260).start() })
        }
    }
    private fun fillHero(t: Tile?) {
        heroChips.removeAllViews()
        if (t !is Tile.Mv) { showBackdrop(null); if (layer.visibility != View.VISIBLE) aurora.resume() }
        schedulePreview((t as? Tile.Ch)?.c)
        when (t) {
            is Tile.Ch -> {
                heroDot.visibility = View.VISIBLE; heroDot.backgroundTintList = ColorStateList.valueOf(Accent.LIVE)
                heroBadge.text = if (t.c.v2) "24/7 live" else "Live now"; heroTitle.text = t.c.name
                heroChips.addView(chip(if (t.c.v2) "V2 · ${t.c.category}" else t.c.category))
                heroChips.addView(chip(keyHint("OK", "Watch")))
                heroChips.addView(chip(keyHint("Hold OK", if (t.c.key in favs) "Remove from favorites" else "Add to favorites")))
                if (t.c.logo.isBlank()) {
                    heroLogo.visibility = View.GONE; heroMono.visibility = View.VISIBLE
                    heroMono.text = initials(t.c.name); heroMono.textSize = if (heroMono.text.length > 1) 62f else 84f
                    heroMono.setTextColor(accentOf(t.c.key))
                } else {
                    heroMono.visibility = View.GONE; heroLogo.visibility = View.VISIBLE
                    heroLogo.load(t.c.logo) {
                        allowHardware(false); crossfade(true)
                        listener(onSuccess = { _, r ->
                            extractAccent(t.c.key, r.drawable) { c -> if ((heroTile as? Tile.Ch)?.c?.key == t.c.key) glowHero(c, Accent.partner(c)) }
                        })
                    }
                }
                accents[t.c.key]?.let { glowHero(it, Accent.partner(it)) }
                scheduleHeroGuide(t.c)
            }
            is Tile.Mv -> {
                val red = 0xFFE50914.toInt()
                heroDot.visibility = View.VISIBLE; heroDot.backgroundTintList = ColorStateList.valueOf(red)
                heroBadge.text = if (t.m.isSeries) "Series" else "Movie"; heroTitle.text = t.m.name
                if (t.m.rating.isNotEmpty()) heroChips.addView(chip("★ ${t.m.rating}"))
                if (t.m.year.isNotEmpty()) heroChips.addView(chip(t.m.year))
                if (t.m.genres.isNotEmpty()) heroChips.addView(chip(t.m.genres.take(2).joinToString(" · ")))
                heroChips.addView(chip(keyHint("OK", "Details")))
                heroProg.visibility = View.GONE; heroProgress.visibility = View.GONE; heroNext.visibility = View.GONE
                heroDesc.text = t.m.description; heroDesc.visibility = if (t.m.description.isEmpty()) View.GONE else View.VISIBLE
                ui.removeCallbacks(heroGuideRunnable)
                glowHero(red, 0xFF5A0A12.toInt()); showBackdrop(t.m.background.ifEmpty { t.m.poster })
                aurora.pause()
            }
            else -> {
                heroDot.visibility = View.GONE; heroBadge.text = ""; heroTitle.text = "AK TV"
                heroMono.visibility = View.GONE; heroLogo.visibility = View.VISIBLE; heroLogo.setImageResource(R.drawable.ic_mark)
                glowHero(Accent.SIGNAL, Accent.INDIGO); scheduleHeroGuide(null)
            }
        }
    }

    // ---------- programme details ----------
    private fun fetchGuide(c: Channel, cb: (Guide?) -> Unit) {
        if (c.v2) { cb(null); return }
        Epg.cached(c.id)?.let { cb(it); return }
        io.execute {
            val g = try { Epg.parse(get("$GUIDE/${c.id}", 2)).also { Epg.put(c.id, it) } } catch (e: Exception) { null }
            runOnUiThread { cb(g) }
        }
    }
    private fun span(sb: SpannableStringBuilder, from: Int, color: Int) {
        sb.setSpan(ForegroundColorSpan(color), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(TypefaceSpan(fSemi), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    /** "NOW   Show title   9:30 PM – 10:00 PM" */
    private fun progLine(label: String, p: Programme, range: Boolean = true): CharSequence {
        val sb = SpannableStringBuilder()
        var s = sb.length; sb.append(label); span(sb, s, Accent.DIM)
        sb.append("   ")
        s = sb.length; sb.append(p.title); span(sb, s, Accent.TEXT)
        val r = if (range) Epg.range(p) else Epg.startOf(p)
        if (r.isNotBlank()) { sb.append("   "); s = sb.length; sb.append(r); sb.setSpan(ForegroundColorSpan(Accent.DIM), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return sb
    }
    private val heroGuideRunnable = Runnable {
        val c = (heroTile as? Tile.Ch)?.c ?: return@Runnable
        fetchGuide(c) { g -> if ((heroTile as? Tile.Ch)?.c?.key == c.key) applyHeroGuide(g) }
    }
    private fun scheduleHeroGuide(c: Channel?) {
        ui.removeCallbacks(heroGuideRunnable)
        if (c == null) { applyHeroGuide(null); return }
        if (c.v2) {
            applyHeroGuide(null)
            heroDesc.text = "Always-on channel with several servers; if one stalls the player switches by itself."
            heroDesc.visibility = View.VISIBLE; return
        }
        val g = Epg.cached(c.id)
        if (g != null) applyHeroGuide(g) else { applyHeroGuide(null); ui.postDelayed(heroGuideRunnable, 320) }
    }
    private fun applyHeroGuide(g: Guide?) {
        val n = g?.now; val nx = g?.next
        heroProg.visibility = if (n != null) View.VISIBLE else View.GONE
        heroProgress.visibility = heroProg.visibility
        if (n != null) { heroProg.text = progLine("NOW", n); heroProgress.progress = (Epg.progress(n) * 1000).toInt() }
        val d = n?.desc.orEmpty()
        heroDesc.text = d; heroDesc.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
        heroNext.visibility = if (nx != null) View.VISIBLE else View.GONE
        if (nx != null) heroNext.text = progLine("NEXT", nx, range = false)
    }

    // ---------- player overlay ----------
    private val hideInfo = Runnable {
        infoCard.animate().alpha(0f).translationY(14 * dp).setDuration(450).start()
        playerScrim.animate().alpha(0f).setDuration(450).start()
        playerClock.animate().alpha(0f).setDuration(450).start()
    }
    private fun showInfo() {
        ui.removeCallbacks(hideInfo)
        infoCard.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        playerScrim.animate().alpha(1f).setDuration(260).start()
        playerClock.animate().alpha(1f).setDuration(260).start()
        ui.postDelayed(hideInfo, 5000)
    }
    private fun bindInfo(ch: Channel) {
        infoName.text = ch.name
        infoCat.text = "${if (ch.v2) "V2 · 24/7" else ch.category}   ${current + 1} of ${shown.size}"
        bindLogo(infoLogo, infoMono, ch)
        val ac = accentOf(ch.key)
        infoGlass.accent = ac; guideGlass.accent = ac
        spinner.indeterminateTintList = ColorStateList.valueOf(ac)
        infoProgress.progressTintList = ColorStateList.valueOf(ac)
        infoNow.visibility = View.GONE; infoProgress.visibility = View.GONE; infoDesc.visibility = View.GONE; infoNext.visibility = View.GONE
        if (ch.v2) { infoNow.text = "24/7 live channel"; infoNow.visibility = View.VISIBLE }
        else fetchGuide(ch) { g -> if (shown.getOrNull(current)?.key == ch.key) fillInfoGuide(g) }
        showInfo()
    }
    private fun fillInfoGuide(g: Guide?) {
        val n = g?.now
        if (n == null) { infoNow.visibility = View.GONE; infoProgress.visibility = View.GONE; infoDesc.visibility = View.GONE; infoNext.visibility = View.GONE; return }
        infoNow.text = progLine("NOW", n); infoNow.visibility = View.VISIBLE
        infoProgress.progress = (Epg.progress(n) * 1000).toInt(); infoProgress.visibility = View.VISIBLE
        infoDesc.text = n.desc; infoDesc.visibility = if (n.desc.isEmpty()) View.GONE else View.VISIBLE
        val nx = g.next
        if (nx != null) { infoNext.text = progLine("NEXT", nx); infoNext.visibility = View.VISIBLE } else infoNext.visibility = View.GONE
    }
    private fun playMsg(msg: String?) {
        if (msg == null) playStatus.visibility = View.GONE else { playStatus.text = msg; playStatus.visibility = View.VISIBLE }
    }

    // ---------- playback ----------
    private fun hexToB64Url(hex: String): String {
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    // ---------- TG TV player, embedded right in the player screen (V1 channels) ----------
    private var gv: GeckoView? = null
    private var gs: GeckoSession? = null

    private fun geckoRuntime(): GeckoRuntime {
        BrowserActivity.runtime?.let { return it }
        val r = GeckoRuntime.create(applicationContext)
        r.settings.contentBlocking.setAntiTracking(ContentBlocking.AntiTracking.AD or ContentBlocking.AntiTracking.ANALYTIC)
        BrowserActivity.runtime = r
        return r
    }

    /** V1 channels play only in the site's own player page (/embed/{id}): no stream link, header route or ExoPlayer. */
    private fun startEmbed(ch: Channel) {
        ++playToken; disarmStall()
        pv.player = null; player?.release(); player = null          // no ExoPlayer for V1
        spinner.visibility = View.VISIBLE
        val url = "$SITE/embed/${ch.id}"
        val open = gs
        if (open != null) { open.loadUri(url); showInfo(); return }  // zapping reuses the same page view
        val view = GeckoView(this)
        val session = GeckoSession(GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP).build())
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null   // block pop-ups
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                spinner.visibility = View.GONE
                ui.postDelayed({ if (gs === session) playMsg(null) }, 3500)
            }
        }
        session.open(geckoRuntime()); view.setSession(session)
        (layer as ViewGroup).addView(view, 1, FrameLayout.LayoutParams(-1, -1))
        gv = view; gs = session
        session.loadUri(url)
        showInfo()
    }

    private fun releaseEmbed() {
        val v = gv; val s = gs
        gv = null; gs = null
        if (v != null) { try { v.releaseSession() } catch (_: Exception) {}; (v.parent as? ViewGroup)?.removeView(v) }
        if (s != null) { try { s.close() } catch (_: Exception) {} }
    }

    /** OK on the remote = click the middle of the page (the player's big play button). */
    private fun tapEmbed() {
        val v = gv ?: return
        val x = v.width / 2f; val y = v.height / 2f
        val t = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0); d.source = InputDevice.SOURCE_TOUCHSCREEN
        v.dispatchTouchEvent(d); d.recycle()
        v.postDelayed({
            val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0); u.source = InputDevice.SOURCE_TOUCHSCREEN
            v.dispatchTouchEvent(u); u.recycle()
        }, 60)
    }

    /** User picked a channel (or zapped): start fresh. */
    private fun play(index: Int) {
        if (shown.isEmpty()) return
        current = (index + shown.size) % shown.size
        val ch = shown[current]
        stopPreview(); rememberChannel(ch)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); layer.keepScreenOn = true
        if (layer.visibility != View.VISIBLE) { aurora.pause(); layer.alpha = 0f; layer.visibility = View.VISIBLE; layer.animate().alpha(1f).setDuration(250).start() }
        attempts = 0; refreshed = false; lastCode = null; serverIdx = 0; stalls = 0
        playMsg(null); bindInfo(ch)
        if (wantsTg(ch)) startEmbed(ch) else resolveAndStart(ch)
    }

    // ---------- two players for V1: native (ExoPlayer + ClearKey) and the TG web player ----------
    private fun wantsTg(ch: Channel) = !ch.v2 && prefs.getString("eng_${ch.key}", "native") == "tg"
    private fun setEngine(ch: Channel, tg: Boolean) {
        if (ch.v2) return
        val v = if (tg) "tg" else "native"
        if (prefs.getString("eng_${ch.key}", "native") != v) prefs.edit().putString("eng_${ch.key}", v).apply()
    }

    /** Native player could not play this V1 channel (refused, DRM, decoder, no answer): continue in the TG player. */
    private fun fallbackToTg(ch: Channel, why: String) {
        if (ch.v2) { fail("${ch.name}: $why"); return }
        setEngine(ch, true)
        playMsg("Native player: $why. Switching to TG player…")
        startEmbed(ch)
    }

    /** Left / Menu while a V1 channel plays: switch between the native player and the TG player (remembered per channel). */
    private fun switchEngine() {
        val ch = shown.getOrNull(current) ?: return
        if (ch.v2) { reloadStream(); return }
        retries = 0; attempts = 0; refreshed = false; stalls = 0; lastCode = null
        if (gs != null) { setEngine(ch, false); playMsg("Native player…"); resolveAndStart(ch) }
        else { setEngine(ch, true); playMsg("TG player…"); startEmbed(ch) }
    }

    /** Ask the site for the channel's current stream link(s) (they are short-lived) and start playing them. */
    private fun resolveAndStart(ch: Channel) {
        val token = ++playToken
        disarmStall(); releasePlayer(); spinner.visibility = View.VISIBLE
        io.execute {
            try {
                if (ch.v2) {
                    val sv = JSONObject(get("$BASE_V2/${ch.id}")).getJSONArray("servers")
                    val list = (0 until sv.length()).mapNotNull { sv.optJSONObject(it) }
                        .map { str(it, "playable") to str(it, "type").ifEmpty { "hls" } }.filter { it.first.isNotEmpty() }
                    if (list.isEmpty()) throw IOException("no live server")
                    runOnUiThread { if (token == playToken) { v2Servers = list; if (serverIdx >= list.size) serverIdx = 0; startV2() } }
                } else {
                    val urls = ArrayList<String>(); val drms = ArrayList<JSONObject?>()
                    try { TgServers.fetch(ch.id).forEach { if (it.url !in urls) { urls.add(it.url); drms.add(it.drm) } } } catch (_: Exception) {}
                    val pl = Playlist.find(this, ch.name)
                    if (pl != null && pl.url !in urls) { urls.add(pl.url); drms.add(pl.drm) }
                    try {
                        val o = JSONObject(get("$BASE/${ch.id}", ua = Streams.CHROME))
                        val src = o.getJSONArray("sources"); val drm = o.optJSONObject("drm")
                        (0 until src.length()).map { src.optString(it, "") }.filter { it.isNotBlank() && it !in urls }
                            .forEach { urls.add(it); drms.add(drm) }
                    } catch (e: Exception) { if (urls.isEmpty()) throw e }
                    if (urls.isEmpty()) throw IOException("no stream source")
                    runOnUiThread {
                        if (token == playToken) { v1Sources = urls; v1Drms = drms; routes = buildRoutes(ch, urls); routeIdx = 0; startV1() }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (token == playToken) {
                        if (!ch.v2) fallbackToTg(ch, why(e))
                        else fail("Couldn't open ${ch.name}: ${why(e)}")
                    }
                }
            }
        }
    }

    /** Every (source x header profile) to try for a V1 channel, starting with the profile that worked for it last time. */
    private fun buildRoutes(ch: Channel, urls: List<String>): List<Pair<Int, Int>> {
        val n = Streams.PROFILES.size
        val first = prefs.getInt("p_${ch.key}", -1)
        val jioOrder = listOf(6, 7, 0)
        val webOrder = listOf(1, 3, 5)
        val out = ArrayList<Pair<Int, Int>>()
        for ((i, u) in urls.withIndex()) {
            val base = if (Streams.isJio(u)) jioOrder else webOrder
            val order = (if (first in 0 until n) listOf(first) else emptyList()) + base.filter { it != first }
            order.forEach { out.add(i to it) }
        }
        return out.take(24)     // each source with its likely header profiles, 24 tries at most
    }
    private fun rememberRoute() {
        val ch = shown.getOrNull(current) ?: return
        if (ch.v2) return
        setEngine(ch, false)
        val r = routes.getOrNull(routeIdx) ?: return
        if (prefs.getInt("p_${ch.key}", 0) != r.second) prefs.edit().putInt("p_${ch.key}", r.second).apply()
    }

    // smoother live playback: a deeper buffer, a calmer live offset and gentle catch-up speed instead of stalls
    private fun liveCfg() = MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(12000)
        .setMinOffsetMs(6000).setMaxOffsetMs(40000).setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()   // speed nudging made HD sports judder

    private fun startV1() {
        if (routeIdx !in routes.indices) return
        val ch = shown.getOrNull(current) ?: return
        val (si, pi) = routes[routeIdx]
        val url = v1Sources[si]
        Streams.jarFor(pi)
        if (routeIdx > 0) playMsg("Trying another route (${routeIdx + 1} of ${routes.size})…")
        val data = Streams.factory(Streams.PROFILES[pi], url)
        val hls = url.substringBefore('?').lowercase().endsWith(".m3u8")
        val factory: MediaSource.Factory = if (hls) HlsMediaSource.Factory(data) else DashMediaSource.Factory(data)
        val drm = v1Drms.getOrNull(si)
        if (drm != null && drm.length() > 0) {
            val lic = clearKeyLicense(drm)
            if (lic == null) { nextSource(ch, "unsupported DRM"); return }
            val mgr = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(true).setPlayClearSamplesWithoutKeys(true)
                .build(LocalMediaDrmCallback(lic))
            factory.setDrmSessionManagerProvider { mgr }
        }
        val item = MediaItem.Builder().setUri(url).setLiveConfiguration(liveCfg()).build()
        attach(factory.createMediaSource(item))
    }

    /** 16-byte key / key id from hex (with or without dashes, 0x, spaces) or base64; null when it is not one. */
    private fun keyBytes(v: String): ByteArray? {
        val t = v.trim().removePrefix("0x").removePrefix("0X").replace("-", "").replace(":", "").replace(" ", "")
        if (t.length == 32 && t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
            return ByteArray(16) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return try {
            val b = Base64.decode(v.trim().replace('+', '-').replace('/', '_').trimEnd('='), Base64.URL_SAFE or Base64.NO_WRAP)
            if (b.size == 16) b else null
        } catch (_: Exception) { null }
    }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /**
     * ClearKey licence (JWK set) for the channel's `drm` object: {type, keyId, key}. Also accepts several keys
     * (comma separated, an array, or a `keys` list). Returns null when the DRM is not ClearKey or a key is unreadable.
     */
    private fun clearKeyLicense(drm: JSONObject): ByteArray? {
        val type = drm.optString("type", "")
        if (type.isNotEmpty() && !type.contains("clear", ignoreCase = true)) return null
        fun parts(o: Any?): List<String> = when (o) {
            is JSONArray -> (0 until o.length()).map { o.optString(it, "") }
            is String -> o.split(',', ';', '\n', ' ')
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }
        val pairs = ArrayList<Pair<String, String>>()
        pairs.addAll(parts(drm.opt("keyId") ?: drm.opt("kid") ?: drm.opt("key_id")).zip(parts(drm.opt("key") ?: drm.opt("k"))))
        drm.optJSONArray("keys")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("keyId", o.optString("kid", "")); val k = o.optString("key", o.optString("k", ""))
                if (id.isNotEmpty() && k.isNotEmpty()) pairs.add(id to k)
            }
        }
        if (pairs.isEmpty()) return null
        val out = JSONArray()
        for ((id, k) in pairs) {
            val idB = keyBytes(id) ?: return null; val kB = keyBytes(k) ?: return null
            out.put(JSONObject().put("kty", "oct").put("k", b64(kB)).put("kid", b64(idB)))
        }
        return JSONObject().put("keys", out).put("type", "temporary").toString().toByteArray()
    }

    private fun startV2() {
        if (serverIdx !in v2Servers.indices) return
        val (url, type) = v2Servers[serverIdx]
        Streams.jarFor(0)
        val http = DefaultHttpDataSource.Factory().setUserAgent(Streams.CHROME).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(15000)
        val dash = type.equals("dash", true) || url.contains(".mpd")
        val item = MediaItem.Builder().setUri(url).setLiveConfiguration(liveCfg())
            .setMimeType(if (dash) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8).build()
        val ms = if (dash) DashMediaSource.Factory(http).createMediaSource(item) else HlsMediaSource.Factory(http).createMediaSource(item)
        if (serverIdx > 0 || attempts > 0) playMsg("Server ${serverIdx + 1} of ${v2Servers.size}…")
        infoNext.text = "Server ${serverIdx + 1} of ${v2Servers.size}" + if (v2Servers.size > 1) "   ·   OK switches server" else ""
        infoNext.visibility = View.VISIBLE
        attach(ms)
    }

    private fun attach(ms: MediaSource) {
        releasePlayer()
        stopPreview()
        val rf = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            .forceEnableMediaCodecAsynchronousQueueing()   // smoother 1080p / 50fps decoding on TV boxes
        val lc = DefaultLoadControl.Builder().setBufferDurationsMs(25000, 60000, 3000, 6000)
            .setTargetBufferBytes(96 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true).build()
        val ts = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters()
                .setViewportSizeToPhysicalDisplaySize(this@MainActivity, true)   // never pick more than the TV can show
                .setExceedVideoConstraintsIfNecessary(false)
                .setExceedRendererCapabilitiesIfNecessary(false))               // skip HD tracks the decoder can't keep up with
        }
        val bw = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(this).setInitialBitrateEstimate(8_000_000L).build()
        val p = ExoPlayer.Builder(this, rf).setLoadControl(lc).setTrackSelector(ts).setBandwidthMeter(bw).build()
        p.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        p.setMediaSource(ms); p.playWhenReady = true; p.addListener(playerListener)
        pv.player = p; player = p; p.prepare()
        armStall(18000)
        showInfo()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(s: Int) {
            when (s) {
                Player.STATE_READY -> {
                    spinner.visibility = View.GONE; playMsg(null); disarmStall()
                    retries = 0; attempts = 0; refreshed = false; rememberRoute()
                }
                Player.STATE_BUFFERING -> { spinner.visibility = View.VISIBLE; armStall(15000) }
                Player.STATE_ENDED -> onStall()
            }
        }
        override fun onPlayerError(e: PlaybackException) { disarmStall(); onStreamError(e) }
    }

    // A stream that stops answering is moved on automatically: next route / server, then a fresh link, then a clear message.
    private val stallRunnable = Runnable { onStall() }
    private fun armStall(ms: Long) { ui.removeCallbacks(stallRunnable); ui.postDelayed(stallRunnable, ms) }
    private fun disarmStall() { ui.removeCallbacks(stallRunnable) }
    private fun onStall() {
        val ch = shown.getOrNull(current) ?: return
        if (layer.visibility != View.VISIBLE || player == null) return
        if (ch.v2) nextServer()
        else if (++stalls >= 2) fallbackToTg(ch, "no answer")
        else nextRoute(ch)
    }

    private val routeCodes = setOf(401, 403, 404, 410, 451)
    private fun httpCode(e: Throwable): Int? {
        var c: Throwable? = e; var n = 0
        while (c != null && n++ < 8) { if (c is HttpDataSource.InvalidResponseCodeException) return c.responseCode; c = c.cause }
        return null
    }
    private fun onStreamError(e: PlaybackException) {
        val ch = shown.getOrNull(current) ?: return
        val code = httpCode(e); lastCode = code
        if (ch.v2) { nextServer(); return }
        if (e.errorCode in 6000..6999 || e.errorCode in 4000..4999) { nextSource(ch, e.errorCodeName); return }   // DRM / decoder: other headers won't help, another server might
        if (code != null && code in routeCodes) { nextRoute(ch); return }
        if (retries++ < 3) { resolveAndStart(ch); return }          // fresh link, same as before
        fallbackToTg(ch, e.errorCodeName + (code?.let { " (HTTP $it)" } ?: ""))
    }

    /** Skip the remaining header tries of this source and move on to the next server. */
    private fun nextSource(ch: Channel, why: String) {
        val si = routes.getOrNull(routeIdx)?.first ?: -1
        val nx = (routeIdx + 1 until routes.size).firstOrNull { routes[it].first != si }
        if (nx == null) { fallbackToTg(ch, why); return }
        routeIdx = nx; attempts++
        playMsg("Trying another server…")
        val tk = playToken
        ui.post { if (tk == playToken) startV1() }
    }

    private fun nextRoute(ch: Channel) {
        attempts++
        val tk = playToken
        if (routeIdx + 1 < routes.size) {
            routeIdx++
            playMsg("Trying another route (${routeIdx + 1} of ${routes.size})…")
            ui.post { if (tk == playToken) startV1() }
        } else if (!refreshed && (lastCode == null || lastCode == 404 || lastCode == 410)) {
            refreshed = true; playMsg("Getting a fresh stream link…"); resolveAndStart(ch)
        } else {
            fallbackToTg(ch, "refused" + (lastCode?.let { " (HTTP $it)" } ?: ""))
        }
    }

    private fun nextServer() {
        val ch = shown.getOrNull(current) ?: return
        attempts++
        val n = v2Servers.size
        if (attempts > n * 2) { fail("No working server for ${ch.name} right now. Hold OK to try again."); return }
        if (attempts == n) { playMsg("Refreshing servers…"); resolveAndStart(ch); return }   // links are short-lived: ask again
        serverIdx = (serverIdx + 1) % n
        playMsg("Trying server ${serverIdx + 1} of $n…")
        val tk = playToken
        ui.post { if (tk == playToken) startV2() }
    }

    /** OK while a V2 channel plays: pick the next server by hand. */
    private fun switchServer() {
        if (v2Servers.size < 2) return
        serverIdx = (serverIdx + 1) % v2Servers.size
        spinner.visibility = View.VISIBLE; startV2()
    }

    /** Hold OK / Menu: drop the stream and fetch it again from scratch. */
    private fun reloadStream() {
        if (shown.getOrNull(current) == null) return
        retries = 0
        play(current)
        playMsg("Reloading stream…")
    }

    private fun fail(msg: String) {
        disarmStall(); spinner.visibility = View.GONE; playMsg(null)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun releasePlayer() { pv.player = null; player?.release(); player = null; releaseEmbed() }
    private fun closePlayer() {
        playToken++; disarmStall(); playMsg(null)
        releasePlayer(); retries = 0; ui.removeCallbacks(hideInfo); aurora.resume(); resetGuide()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); layer.keepScreenOn = false
        if (tab != 2) { val sel = heroTile; refreshRowsKeepFocus(); setHero(sel, immediate = true) }
        if (layer.visibility == View.VISIBLE) layer.animate().alpha(0f).setDuration(200).withEndAction { layer.visibility = View.GONE }.start()
    }

    private fun shortOk() {
        val ch = shown.getOrNull(current)
        if (ch?.v2 == true && v2Servers.size > 1 && infoCard.alpha > 0.5f) switchServer() else showInfo()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (intro.visibility == View.VISIBLE) { if (e.action == KeyEvent.ACTION_DOWN) intro.skip(); return true }
        if (searchShown) return searchKey(e) || super.dispatchKeyEvent(e)
        if (layer.visibility == View.VISIBLE && guideShown) return guideKey(e) || super.dispatchKeyEvent(e)
        if (layer.visibility == View.VISIBLE && gs != null && (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER)) {
            // embedded TG player: tap OK = click the player, hold OK = reload the channel
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) okLong = false
                else if (e.repeatCount == 8 && !okLong) { okLong = true; reloadStream() }
            } else if (e.action == KeyEvent.ACTION_UP && !okLong) { tapEmbed(); showInfo() }
            return true
        }
        if (layer.visibility == View.VISIBLE && (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER)) {
            // tap OK = info (or next server on V2), hold OK = reload the stream
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) okLong = false
                else if (e.repeatCount == 8 && !okLong) { okLong = true; reloadStream() }
            } else if (e.action == KeyEvent.ACTION_UP && !okLong) shortOk()
            return true
        }
        if (layer.visibility == View.VISIBLE && e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { closePlayer(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { openGuide(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { retries = 0; play(current - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { retries = 0; play(current + 1); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_LEFT -> { switchEngine(); return true }
        }
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_SEARCH) { openSearch(); return true }
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            val f = currentFocus
            if (f != null && isInside(f, rowsView)) {
                val next = FocusFinder.getInstance().findNextFocus(window.decorView as ViewGroup, f, View.FOCUS_DOWN)
                if (next == null || !isInside(next, rowsView)) {
                    val rowView = rowsView.findContainingItemView(f)
                    val pos = rowView?.let { rowsView.getChildAdapterPosition(it) } ?: -1
                    if (pos >= 0 && pos + 1 < rows.size) {
                        rowsView.scrollToPosition(pos + 1)
                        rowsView.post { focusRowAt(pos + 1) }
                    }
                    return true   // never jump to another tab or the top bar
                }
            }
        }
        // Going left out of the rows always lands on the current tab in the side rail, never switches tabs by accident.
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            val f = currentFocus
            if (f != null && isInside(f, rowsView)) {
                val next = FocusFinder.getInstance().findNextFocus(window.decorView as ViewGroup, f, View.FOCUS_LEFT)
                if (next != null && (next === searchBtn || segs.any { it === next })) { segs[tab].requestFocus(); return true }
            }
        }
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN) {
            val f = currentFocus
            val inRail = f != null && (f === searchBtn || segs.any { it === f })
            if (inRail && e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // back to the tile you came from
                val back = lastRowsFocus
                if (back != null && back.isAttachedToWindow && isInside(back, rowsView) && back.requestFocus()) return true
                if (rowsView.requestFocus()) return true
            }
            if (f != null && inRail && (e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                val dir = if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN
                val next = FocusFinder.getInstance().findNextFocus(window.decorView as ViewGroup, f, dir)
                if (next == null || !(next === searchBtn || segs.any { it === next })) return true   // stay inside the rail
            }
        }
        return super.dispatchKeyEvent(e)
    }
    override fun onStart() {
        super.onStart()
        if (tab == 2 && ::rowsView.isInitialized && rows.isNotEmpty()) refreshRowsKeepFocus()
        if (layer.visibility != View.VISIBLE) aurora.resume()
        if (all.isNotEmpty() && System.currentTimeMillis() - lastRefresh > 15 * 60_000L) loadChannels()
    }
    override fun onStop() {
        super.onStop(); stopPreview(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); playToken++; disarmStall(); releasePlayer(); layer.visibility = View.GONE; aurora.pause(); resetGuide()
        if (searchShown) hideSearch(false)
    }

    // ---------- search ----------
    private fun buildKeys() {
        val kw = (50 * dp).toInt(); val kh = (46 * dp).toInt(); val m = (3 * dp).toInt()
        for (r in listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ0123", "456789")) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (ch in r) row.addView(keyView(ch.toString(), kw, kh, m) { typeChar(ch.lowercaseChar()) })
            searchKeys.addView(row)
        }
        val wide = 2 * (kw + 2 * m) - 2 * m
        val last = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        last.addView(keyView("Space", wide, kh, m) { typeChar(' ') })
        last.addView(keyView("Delete", wide, kh, m) { backspace() })
        last.addView(keyView("Clear", wide, kh, m) { searchBuf.setLength(0); renderQuery(); updateResults() })
        searchKeys.addView(last)
    }
    private fun keyView(label: String, w: Int, h: Int, m: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 15f; typeface = fSemi; setTextColor(Accent.DIM)
        isFocusable = true; isClickable = true
        val g = GlassDrawable(dp, 12f).apply { base = Color.argb(26, 255, 255, 255) }
        background = g
        layoutParams = LinearLayout.LayoutParams(w, h).apply { setMargins(m, m, m, m) }
        setOnFocusChangeListener { v, f -> g.focused = f; setTextColor(if (f) Accent.TEXT else Accent.DIM); focusAnim(v, f, Accent.SIGNAL) }
        setOnClickListener { onClick() }
    }
    private fun typeChar(c: Char) { if (searchBuf.length < 40) { searchBuf.append(c); renderQuery(); updateResults() } }
    private fun backspace() { if (searchBuf.isNotEmpty()) { searchBuf.setLength(searchBuf.length - 1); renderQuery(); updateResults() } }
    private fun renderQuery() {
        if (searchBuf.isEmpty()) { searchQuery.text = "Type to search"; searchQuery.setTextColor(Accent.FAINT) }
        else { searchQuery.text = "$searchBuf|"; searchQuery.setTextColor(Accent.TEXT) }
    }
    private fun runSearch(q: String): List<Channel> {
        val toks = q.lowercase().split(' ').filter { it.isNotBlank() }
        if (toks.isEmpty()) return emptyList()
        fun score(c: Channel): Int {
            val n = c.name.lowercase()
            return when { n.startsWith(toks[0]) -> 0; n.contains(" " + toks[0]) -> 1; else -> 2 }
        }
        return (all + allV2).filter { c -> val hay = (c.name + " " + c.category).lowercase(); toks.all { hay.contains(it) } }
            .sortedWith(compareBy({ score(it) }, { it.v2 }, { it.name.lowercase() })).take(120)
    }
    private var movieResults = listOf<Tile>(); private var movieSearchToken = 0; private var movieSearching = false
    private val movieRow = Row("Movies & series", emptyList())
    private val movieSearchAdapter by lazy { TileAdapter(movieRow) }
    private val movieSearchList by lazy {
        RecyclerView(this).apply {
            layoutManager = SnapLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL)
            adapter = movieSearchAdapter; itemAnimator = null; overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false; visibility = View.GONE
            setPadding((4 * dp).toInt(), (10 * dp).toInt(), (80 * dp).toInt(), (16 * dp).toInt())
            val parent = searchResults.parent as ViewGroup
            parent.addView(this, parent.indexOfChild(searchResults), LinearLayout.LayoutParams(-1, -2))
        }
    }
    private val movieSearchRunnable = Runnable {
        val q = searchBuf.toString().trim(); val tok = ++movieSearchToken
        Thread {
            val r = try { Movies.search(q).filter { it.poster.isNotEmpty() }.distinctBy { it.imdb }.take(40) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (tok != movieSearchToken || !searchShown) return@runOnUiThread
                movieSearching = false
                movieResults = r.map { Tile.Mv(it) }; movieRow.tiles = movieResults
                movieSearchAdapter.notifyDataSetChanged(); movieSearchList.scrollToPosition(0)
                movieSearchList.visibility = if (movieResults.isEmpty()) View.GONE else View.VISIBLE
                renderCounts(q)
            }
        }.start()
    }
    private fun renderCounts(q: String) {
        val m = movieResults.size
        searchCount.text = if (q.isEmpty()) "Search ${all.size + allV2.size} channels, movies & series"
            else "${results.size} channel" + (if (results.size == 1) "" else "s") + "  ·  $m movie" + (if (m == 1) "" else "s") + " & series"
        searchEmpty.text = when {
            q.isEmpty() -> "Type a channel, movie or series name, like Star Sports, Inception or Friends."
            results.isEmpty() && m == 0 -> if (movieSearching) "Searching for \"$q\"…" else "Nothing matches \"$q\"."
            else -> ""
        }
        searchEmpty.visibility = if (searchEmpty.text.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun updateResults() {
        val q0 = searchBuf.toString().trim()
        ui.removeCallbacks(movieSearchRunnable); movieSearchToken++
        movieResults = emptyList(); movieRow.tiles = movieResults; movieSearchAdapter.notifyDataSetChanged()
        movieSearchList.visibility = View.GONE
        movieSearching = q0.length >= 2
        if (movieSearching) ui.postDelayed(movieSearchRunnable, 350)
        val q = q0
        results = if (q.isEmpty()) emptyList() else runSearch(q)
        searchAdapter.notifyDataSetChanged(); searchResults.scrollToPosition(0)
        renderCounts(q)
    }
    private fun openSearch() {
        if (searchShown || layer.visibility == View.VISIBLE || intro.visibility == View.VISIBLE) return
        searchShown = true; searchBuf.setLength(0); renderQuery(); updateResults()
        if (home.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
            lastHomeFocus = currentFocus; home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        searchLayer.animate().cancel()
        searchLayer.visibility = View.VISIBLE; searchLayer.alpha = 0f
        searchLayer.animate().alpha(1f).setDuration(220).start()
        searchKeys.getChildAt(0)?.let { (it as ViewGroup).getChildAt(0)?.requestFocus() }
    }
    private fun closeSearch() { hideSearch(true) }
    private fun hideSearch(animate: Boolean) {
        if (!searchShown) return
        searchShown = false
        searchLayer.animate().cancel()
        if (animate) searchLayer.animate().alpha(0f).setDuration(180).withEndAction { if (!searchShown) searchLayer.visibility = View.GONE }.start()
        else searchLayer.visibility = View.GONE
        if (home.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS && !guideShown) {
            home.descendantFocusability = homeFocusMode
            val back = lastHomeFocus; lastHomeFocus = null
            if (back?.takeIf { it.isAttachedToWindow }?.requestFocus() != true) searchBtn.requestFocus()
        }
    }
    private fun searchKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        val k = e.keyCode
        when {
            k == KeyEvent.KEYCODE_BACK -> { closeSearch(); return true }
            k in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> { typeChar('a' + (k - KeyEvent.KEYCODE_A)); return true }
            k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { typeChar('0' + (k - KeyEvent.KEYCODE_0)); return true }
            k == KeyEvent.KEYCODE_SPACE -> { typeChar(' '); return true }
            k == KeyEvent.KEYCODE_DEL -> { backspace(); return true }
        }
        return false
    }
    private fun playFromSearch(i: Int) {
        if (i !in results.indices) return
        shown = results; shownCat = "Search"; retries = 0
        closeSearch(); play(i)
    }

    private val searchAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = results.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_guide_ch, p, false)
            v.background = GlassDrawable(dp, 14f).apply { idleVisible = false }
            v.findViewById<View>(R.id.chLogoBox).background = GlassDrawable(dp, 12f)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView; val ch = results[i]; val glass = v.background as GlassDrawable
            val name = v.findViewById<TextView>(R.id.chName)
            name.text = ch.name
            v.findViewById<TextView>(R.id.chNum).text = (i + 1).toString()
            bindLogo(v.findViewById(R.id.chLogo), v.findViewById(R.id.chMono), ch)
            val sub = v.findViewById<TextView>(R.id.chNow)
            sub.text = if (ch.v2) "V2 · 24/7" else ch.category; sub.setTextColor(Accent.FAINT); sub.visibility = View.VISIBLE
            v.findViewById<View>(R.id.chFav).visibility = if (ch.key in favs) View.VISIBLE else View.GONE
            glass.accent = accentOf(ch.key); glass.snap(v.isFocused)
            val style = { f: Boolean -> glass.focused = f; name.setTextColor(if (f) Accent.TEXT else Accent.DIM) }
            name.setTextColor(if (v.isFocused) Accent.TEXT else Accent.DIM)
            v.setOnFocusChangeListener { _, f -> style(f) }
            v.setOnClickListener { playFromSearch(i) }
            v.setOnLongClickListener { toggleFav(ch); notifyItemChanged(i); true }
        }
    }

    // ---------- channel guide (Right opens · Left goes back · OK plays) ----------
    private fun guidePool() = if (shown.getOrNull(current)?.v2 == true) allV2 else all
    private fun channelsIn(cat: String) = if (cat == FAV) guidePool().filter { it.key in favs } else guidePool().filter { it.category == cat }
    private fun within(v: View?, rv: View) = v != null && (v === rv || isInside(v, rv))
    private fun itemOf(v: View, rv: RecyclerView): View { var x = v; while (x.parent !== rv) x = x.parent as View; return x }
    private val guideTimeout = Runnable { closeGuide() }
    private fun bumpGuide() { ui.removeCallbacks(guideTimeout); ui.postDelayed(guideTimeout, 15000) }

    private fun focusRow(rv: RecyclerView, i: Int) {
        val h = rv.findViewHolderForAdapterPosition(i)
        if (h != null && h.itemView.isShown) { h.itemView.requestFocus(); return }
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, (120 * dp).toInt())
        rv.post { rv.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() ?: rv.requestFocus() }
    }

    private fun openGuide() {
        val pool = guidePool()
        if (pool.isEmpty()) return
        val byCat = pool.groupBy { it.category }
        val favCount = pool.count { it.key in favs }
        guideCats = (if (favCount > 0) listOf(FAV) else emptyList()) + byCat.keys.sorted()
        catCounts = byCat.mapValues { it.value.size } + (FAV to favCount)
        if (home.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
            lastHomeFocus = currentFocus; home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        guideShown = true; guideCat = ""
        guide.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        chCol.visibility = View.GONE; guideDivider.visibility = View.GONE
        channelSearch.setText("")
        catAdapter.notifyDataSetChanged()
        ui.removeCallbacks(hideInfo); hideInfo.run()
        guide.animate().cancel(); guideScrim.animate().cancel()
        guide.visibility = View.VISIBLE; guide.alpha = 0f; guide.translationX = 60 * dp
        guide.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        guideScrim.visibility = View.VISIBLE; guideScrim.alpha = 0f
        guideScrim.animate().alpha(1f).setDuration(260).start()
        focusRow(catList, guideCats.indexOf(shownCat).coerceAtLeast(0))
        bumpGuide()
    }

    private fun openCategory(i: Int) {
        val cat = guideCats.getOrNull(i) ?: return
        val prev = guideCats.indexOf(guideCat)
        guideCat = cat; categoryChannels = channelsIn(cat)
        channelSearch.setText("")
        guideChannels = categoryChannels
        chHeader.text = "$cat  ${guideChannels.size}"
        chAdapter.notifyDataSetChanged()
        if (chCol.visibility != View.VISIBLE) {
            chCol.animate().cancel()
            chCol.visibility = View.VISIBLE; guideDivider.visibility = View.VISIBLE
            chCol.alpha = 0f; chCol.translationX = 30 * dp
            chCol.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        val playing = if (cat == shownCat) guideChannels.indexOfFirst { it.key == shown.getOrNull(current)?.key } else -1
        chList.scrollToPosition(0)
        focusRow(chList, playing.coerceAtLeast(0))
        if (prev >= 0) catAdapter.notifyItemChanged(prev)
        catAdapter.notifyItemChanged(i)
    }

    private fun backToCats() {
        val i = guideCats.indexOf(guideCat).coerceAtLeast(0)
        guideCat = ""
        focusRow(catList, i)
        catAdapter.notifyItemChanged(i)
        chCol.animate().cancel()
        chCol.animate().alpha(0f).translationX(30 * dp).setDuration(180)
            .withEndAction { if (guideCat.isEmpty()) { chCol.visibility = View.GONE; guideDivider.visibility = View.GONE } }.start()
    }

    private fun closeGuide() {
        if (!guideShown) return
        guideShown = false; guideCat = ""; ui.removeCallbacks(guideTimeout)
        guide.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        currentFocus?.clearFocus()
        guide.animate().alpha(0f).translationX(60 * dp).setDuration(200)
            .withEndAction { if (!guideShown) { guide.visibility = View.GONE; chCol.visibility = View.GONE; guideDivider.visibility = View.GONE } }.start()
        guideScrim.animate().alpha(0f).setDuration(200).withEndAction { if (!guideShown) guideScrim.visibility = View.GONE }.start()
    }

    /** Hard reset when the player itself closes: hide the guide and give focus back to the home screen. */
    private fun resetGuide() {
        guideShown = false; guideCat = ""; ui.removeCallbacks(guideTimeout)
        guide.animate().cancel(); guideScrim.animate().cancel()
        guide.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        guide.visibility = View.GONE; guideScrim.visibility = View.GONE; chCol.visibility = View.GONE; guideDivider.visibility = View.GONE
        if (home.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS && !searchShown) {
            home.descendantFocusability = homeFocusMode
            val back = lastHomeFocus; lastHomeFocus = null
            if (back?.takeIf { it.isAttachedToWindow }?.requestFocus() != true) rowsView.requestFocus()
        }
    }

    private fun playFromGuide(i: Int) {
        val list = guideChannels; val cat = guideCat
        if (i !in list.indices) return
        shown = list; shownCat = cat; retries = 0
        closeGuide(); play(i)
    }

    private fun guideKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        bumpGuide()
        val f = currentFocus
        val inCh = within(f, chList); val inCat = within(f, catList); val inSearch = f === channelSearch
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { closeGuide(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (inCh || inSearch) backToCats() else closeGuide(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (inCat && f != null && f !== catList) openCategory(catList.getChildAdapterPosition(itemOf(f, catList)))
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // the search box above the channel list is now reachable: Up from the first channel, Down to leave it
                if (inSearch) { if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) focusRow(chList, 0); return true }
                if (!inCh && !inCat) { focusRow(catList, 0); return true }
                if (inCh && e.keyCode == KeyEvent.KEYCODE_DPAD_UP && chCol.visibility == View.VISIBLE &&
                    chList.getChildAdapterPosition(itemOf(f!!, chList)) <= 0) { channelSearch.requestFocus(); return true }
                val list = if (inCh) chList else catList
                // stay inside the current column; never jump across or out of the panel
                val next = f!!.focusSearch(if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN)
                return next == null || !within(next, list)
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> return true
        }
        return false   // OK / long-press go to the focused row as normal
    }

    private val catAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = guideCats.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_guide_cat, p, false)
            v.background = GlassDrawable(dp, 14f).apply { idleVisible = false }
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView; val cat = guideCats[i]; val glass = v.background as GlassDrawable
            val name = v.findViewById<TextView>(R.id.catName)
            name.text = cat
            v.findViewById<TextView>(R.id.catCount).text = (catCounts[cat] ?: 0).toString()
            v.findViewById<View>(R.id.catMark).visibility = if (cat == shownCat) View.VISIBLE else View.INVISIBLE
            val open = cat == guideCat
            val style = { f: Boolean ->
                glass.focused = f; glass.idleVisible = open
                name.setTextColor(if (f || open) Accent.TEXT else Accent.DIM)
            }
            style(v.isFocused)
            v.setOnFocusChangeListener { _, f -> style(f) }
            v.setOnClickListener { openCategory(i) }
        }
    }

    private val chAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = guideChannels.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_guide_ch, p, false)
            v.background = GlassDrawable(dp, 14f).apply { idleVisible = false }
            v.findViewById<View>(R.id.chLogoBox).background = GlassDrawable(dp, 12f)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView; val ch = guideChannels[i]; val glass = v.background as GlassDrawable
            val name = v.findViewById<TextView>(R.id.chName)
            name.text = ch.name
            v.findViewById<TextView>(R.id.chNum).text = (i + 1).toString()
            bindLogo(v.findViewById(R.id.chLogo), v.findViewById(R.id.chMono), ch)
            val playing = shown.getOrNull(current)?.key == ch.key
            v.findViewById<View>(R.id.chNow).visibility = if (playing) View.VISIBLE else View.GONE
            v.findViewById<View>(R.id.chFav).visibility = if (ch.key in favs) View.VISIBLE else View.GONE
            glass.accent = accentOf(ch.key)
            val style = { f: Boolean ->
                glass.focused = f; glass.idleVisible = playing
                name.setTextColor(if (f || playing) Accent.TEXT else Accent.DIM)
            }
            style(v.isFocused)
            v.setOnFocusChangeListener { _, f -> style(f) }
            v.setOnClickListener { playFromGuide(i) }
            v.setOnLongClickListener { toggleFav(ch); notifyItemChanged(i); true }
        }
    }

    // ---------- live channel preview (hero window, muted) ----------
    private val previewRunnable = Runnable { startPreview() }
    private fun schedulePreview(c: Channel?) {
        stopPreview()
        if (c != null && tab != 2 && layer.visibility != View.VISIBLE && prefs.getBoolean("previews", true)) ui.postDelayed(previewRunnable, 1600)
    }
    private fun stopPreview() {
        ui.removeCallbacks(previewRunnable); previewToken++
        previewPlayer?.let { try { it.release() } catch (_: Exception) {} }; previewPlayer = null
        if (heroPreview.visibility == View.VISIBLE) heroPreview.animate().alpha(0f).setDuration(200).withEndAction { heroPreview.visibility = View.GONE }.start()
        if (heroTile !is Tile.Mv) heroOrb.animate().alpha(1f).setDuration(200).start()
    }
    private fun startPreview() {
        val c = (heroTile as? Tile.Ch)?.c ?: return
        if (layer.visibility == View.VISIBLE || searchShown) return
        val token = ++previewToken
        io.execute {
            try {
                var url: String; var type = "hls"; var drm: JSONObject? = null
                if (c.v2) {
                    val sv = JSONObject(get("$BASE_V2/${c.id}", 1)).getJSONArray("servers").getJSONObject(0)
                    url = str(sv, "playable"); type = str(sv, "type").ifEmpty { "hls" }
                } else {
                    val s0 = TgServers.fetch(c.id).firstOrNull() ?: return@execute
                    url = s0.url; drm = s0.drm
                }
                if (url.isEmpty()) return@execute
                runOnUiThread { if (token == previewToken) playPreview(url, type, drm, c.v2) }
            } catch (_: Exception) {}
        }
    }
    private fun playPreview(url: String, type: String, drm: JSONObject?, v2: Boolean) {
        try {
            val data: androidx.media3.datasource.DataSource.Factory = if (v2)
                DefaultHttpDataSource.Factory().setUserAgent(Streams.CHROME).setAllowCrossProtocolRedirects(true)
            else { val pi = if (Streams.isJio(url)) 6 else 1; Streams.jarFor(pi); Streams.factory(Streams.PROFILES[pi], url) }
            val dash = type.equals("dash", true) || url.substringBefore('?').lowercase().endsWith(".mpd") ||
                (!v2 && !url.substringBefore('?').lowercase().endsWith(".m3u8"))
            val f: MediaSource.Factory = if (dash) DashMediaSource.Factory(data) else HlsMediaSource.Factory(data)
            if (drm != null) clearKeyLicense(drm)?.let { lic ->
                val mgr = DefaultDrmSessionManager.Builder().setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(true).setPlayClearSamplesWithoutKeys(true).build(LocalMediaDrmCallback(lic))
                f.setDrmSessionManagerProvider { mgr }
            }
            val p = ExoPlayer.Builder(this).build()
            p.volume = 0f; p.setVideoTextureView(previewTex)
            p.setMediaSource(f.createMediaSource(MediaItem.Builder().setUri(url).setLiveConfiguration(liveCfg()).build()))
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(s: Int) {
                    if (s == Player.STATE_READY && previewPlayer === p) {
                        heroPreview.visibility = View.VISIBLE; heroPreview.scaleX = 0.92f; heroPreview.scaleY = 0.92f
                        heroPreview.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(DecelerateInterpolator(2f)).start()
                        heroOrb.animate().alpha(0f).setDuration(250).start()
                    }
                }
                override fun onPlayerError(e: PlaybackException) { if (previewPlayer === p) stopPreview() }
            })
            previewPlayer = p; p.playWhenReady = true; p.prepare()
        } catch (_: Exception) { stopPreview() }
    }

    /** Rebuilds the rows (e.g. a new "Recently watched" entry) without losing the scroll position. */
    private fun refreshRowsKeepFocus() {
        val lm = rowsView.layoutManager as LinearLayoutManager
        val pos = lm.findFirstVisibleItemPosition()
        val top = lm.findViewByPosition(pos)?.top ?: 0
        val old = rows.map { it.title }
        rows = when (tab) {
            0 -> listOf(Row(FAV, favTiles())) + recentRow() + all.groupBy { it.category }.toSortedMap().map { (k, v) -> Row(k, v.map { Tile.Ch(it) }) }
            1 -> listOf(Row(FAV, favTiles())) + recentRow() + allV2.groupBy { it.category }.toSortedMap().map { (k, v) -> Row(k, v.map { Tile.Ch(it) }) }
            else -> continueRow() + movieRows
        }
        inner.clear(); rowAdapter.notifyDataSetChanged()
        val shift = rows.size - old.size
        if (pos >= 0) lm.scrollToPositionWithOffset((pos + if (pos > 0) shift else 0).coerceAtLeast(0), top)
        rowsView.postDelayed({ if (layer.visibility != View.VISIBLE && !searchShown && currentFocus == null) rowsView.requestFocus() }, 80)
    }

    // ---------- adapters ----------
    private inner class TileAdapter(val row: Row) : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = row.tiles.size
        override fun getItemViewType(i: Int) = when (row.tiles[i]) { is Tile.Ch -> 0; is Tile.Mv -> 1; else -> 2 }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val l = when (t) { 0 -> R.layout.item_tile; 1 -> R.layout.item_poster; else -> R.layout.item_hint }
            val v = LayoutInflater.from(p.context).inflate(l, p, false)
            if (t != 1) v.background = GlassDrawable(dp, 20f)
            else { v.background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(Color.argb(28, 255, 255, 255)) }; v.clipToOutline = true }
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int, payloads: MutableList<Any>) {
            val t = row.tiles[i]
            if (payloads.isNotEmpty() && t is Tile.Ch) { h.itemView.findViewById<View>(R.id.fav).visibility = if (t.c.key in favs) View.VISIBLE else View.GONE; return }
            super.onBindViewHolder(h, i, payloads)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView
            when (val t = row.tiles[i]) {
                is Tile.Ch -> {
                    val glass = v.background as GlassDrawable
                    v.tag = t.c.key; glass.accent = accentOf(t.c.key); glass.snap(v.isFocused)
                    v.findViewById<TextView>(R.id.name).text = t.c.name
                    val logo = v.findViewById<ImageView>(R.id.logo); val mono = v.findViewById<TextView>(R.id.mono)
                    if (t.c.logo.isBlank()) {
                        logo.setImageDrawable(null); logo.visibility = View.GONE
                        val ac = accentOf(t.c.key)
                        mono.visibility = View.VISIBLE; mono.text = initials(t.c.name)
                        mono.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(ac, 150)) }
                    } else {
                        mono.visibility = View.GONE; logo.visibility = View.VISIBLE
                        logo.load(t.c.logo) {
                            allowHardware(false)
                            listener(onSuccess = { _, r ->
                                extractAccent(t.c.key, r.drawable) { c ->
                                    if (v.tag == t.c.key) { glass.accent = c; if (v.isFocused) focusAnim(v, true, c) }
                                }
                            })
                        }
                    }
                    v.findViewById<View>(R.id.fav).visibility = if (t.c.key in favs) View.VISIBLE else View.GONE
                    v.setOnFocusChangeListener { x, f ->
                        val c = accentOf(t.c.key); glass.accent = c; glass.focused = f
                        focusAnim(x, f, c); if (f) setHero(t)
                    }
                    v.setOnClickListener {
                        shown = row.tiles.filterIsInstance<Tile.Ch>().map { it.c }; shownCat = row.title
                        retries = 0; play(shown.indexOfFirst { it.key == t.c.key })
                    }
                    v.setOnLongClickListener { toggleFav(t.c); true }
                }
                is Tile.Mv -> {
                    val img = v.findViewById<ImageView>(R.id.poster)
                    v.findViewById<TextView>(R.id.posterName).text = t.m.name
                    img.load(t.m.poster) { crossfade(120); size(300, 450); placeholder(null) }
                    val rt = v.findViewById<TextView>(R.id.posterRating)
                    rt.text = if (t.m.rating.isNotEmpty()) "★ ${t.m.rating}" else ""; rt.visibility = if (t.m.rating.isEmpty()) View.GONE else View.VISIBLE
                    v.setOnFocusChangeListener { x, f ->
                        focusAnim(x, f, 0xFFE50914.toInt())
                        if (f) {
                            setHero(t)
                            val p = h.bindingAdapterPosition
                            for (d in 1..3) (row.tiles.getOrNull(p + d) as? Tile.Mv)?.let { prefetch(it.m.background) }
                        }
                    }
                    v.setOnClickListener {
                        stopPreview(); DetailActivity.open(this@MainActivity, t.m)
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    }
                }
                else -> {}
            }
        }
    }

    private val tilePool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 40); setMaxRecycledViews(1, 40) }
    private val rowAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_row, p, false))
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView
            val row = rows[i]
            val title = v.findViewById<TextView>(R.id.rowTitle); val count = v.findViewById<TextView>(R.id.rowCount)
            title.text = row.title
            val n = row.tiles.count { it is Tile.Ch || it is Tile.Mv }
            count.text = if (n > 0) n.toString() else ""
            val rv = v.findViewById<RecyclerView>(R.id.rowList)
            if (rv.layoutManager == null) {
                rv.layoutManager = SnapLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL)
                rv.itemAnimator = null
                rv.overScrollMode = View.OVER_SCROLL_NEVER
                rv.setHasFixedSize(true)
                rv.isNestedScrollingEnabled = false
                rv.setItemViewCacheSize(10)
                (rv.layoutManager as LinearLayoutManager).initialPrefetchItemCount = 8
            }
            rv.setRecycledViewPool(tilePool)
            val cur = rv.adapter as? TileAdapter
            val ad = if (cur != null && cur.row === row) cur else TileAdapter(row).also { rv.swapAdapter(it, true) }
            inner[i] = ad
            v.animate().cancel(); v.translationY = 0f; v.alpha = 1f
            val a = if (rowsView.hasFocus()) 0.45f else 1f
            title.alpha = a; count.alpha = a
        }
    }
}

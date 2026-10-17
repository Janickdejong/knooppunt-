package nl.janick.knooppunt

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MapActivity : Activity() {

    // Kleuren: papier en nachtblauw, zoals de andere apps.
    private val paper = Color.parseColor("#F5F2EC")
    private val ink = Color.parseColor("#1D2733")
    private val muted = Color.parseColor("#6B7280")
    private val accent = Color.parseColor("#2F7D6D")
    private val night = Color.parseColor("#0E1726")
    private val mint = Color.parseColor("#9ADBC8")
    private val warn = Color.parseColor("#8E2F22")
    private val navGreen = Color.parseColor("#1E8E4E")
    private val navGreenDark = Color.parseColor("#146B3A")

    private lateinit var prefs: SharedPreferences
    private lateinit var map: MapView
    private lateinit var overlay: NetOverlay
    private lateinit var cellDir: File

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val dataLock = Any()

    // Kaartgegevens; alleen aangeraakt op de achtergronddraad.
    private val builder = NetworkBuilder()
    private val requested = HashSet<String>()
    private var pending = 0
    private var batchTotal = 0
    private var batchDone = 0
    private var retries = 0
    private var bundleChecked = false
    private var styleMode = 0
    private var shownNight: Boolean? = null
    private lateinit var styleButton: TextView

    private var bundled = false

    // Route.
    private val routeIds = ArrayList<Long>()
    private var legs: List<Leg> = emptyList()
    private var routeNodes: List<KNode> = emptyList()

    // Navigatie.
    private var navigating = false
    private var navPts: List<GeoPoint> = emptyList()
    private var navCum = DoubleArray(0)
    private var navNodeIdx = IntArray(0)
    private var navProgress = 0
    private var navNext = -1
    private var wasOffRoute = false
    private var me: GeoPoint? = null
    private var centeredOnMe = false
    private var hadSavedCenter = false

    // Schermonderdelen.
    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var statusView: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var startButton: TextView
    private lateinit var navCard: LinearLayout
    private lateinit var navIcon: TurnArrowView
    private lateinit var navBig: TextView
    private lateinit var navLine: TextView
    private lateinit var navNodeBadge: TextView
    private lateinit var navNodeText: TextView
    private lateinit var navBar: LinearLayout
    private lateinit var etaMain: TextView
    private lateinit var etaSub: TextView
    private lateinit var speedView: TextView
    private lateinit var navMain: LinearLayout
    private lateinit var thenBar: LinearLayout
    private lateinit var thenIcon: TurnArrowView
    private lateinit var thenText: TextView
    private lateinit var muteButton: TextView
    private lateinit var searchButton: TextView
    private var trip: Place? = null
    private lateinit var zoomInButton: TextView
    private lateinit var zoomOutButton: TextView
    private lateinit var voice: Voice
    private val spoken = HashSet<String>()
    private var zoomOffset = 0.0
    private var ride: RideRecorder? = null
    private var pendingGpx: String? = null
    private lateinit var locateButton: TextView
    private var heading = -1f
    private var smoothHeading = 0f
    private var lastFix: GeoPoint? = null
    private var navZoom = 17.3
    private var avgSpeed = 0.0
    private var navFitted = false
    private var mainTrack: Track? = null
    private var guide: Track? = null
    private var guideTarget: GeoPoint? = null
    private var guideFetching = false
    private var guideAttempt = 0L

    private var locationManager: LocationManager? = null
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onLocation(location)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("knooppunt", Context.MODE_PRIVATE)
        cellDir = File(filesDir, "cells").apply { mkdirs() }

        Configuration.getInstance().apply {
            userAgentValue = "Knooppunt (nl.janick.knooppunt)"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(filesDir, "osmdroid/tiles")
            expirationExtendedDuration = 14L * 24 * 3600 * 1000
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(paper)

        // Kaart.
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // Grotere kaartletters, beter leesbaar op een telefoon.
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 6.0
            maxZoomLevel = 20.0
        }
        overlay = NetOverlay(resources.displayMetrics.density)
        overlay.onNodeTap = { node -> onNodeTapped(node) }
        map.overlays.add(overlay)
        root.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val savedLat = prefs.getFloat("lat", Float.NaN)
        val savedLon = prefs.getFloat("lon", Float.NaN)
        if (!savedLat.isNaN() && !savedLon.isNaN()) {
            hadSavedCenter = true
            map.controller.setZoom(prefs.getFloat("zoom", 14f).toDouble())
            map.controller.setCenter(GeoPoint(savedLat.toDouble(), savedLon.toDouble()))
        } else {
            map.controller.setZoom(8.0)
            map.controller.setCenter(GeoPoint(52.1, 5.3))
        }

        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                scheduleLoad()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                scheduleLoad()
                return false
            }
        })

        // Bovenkaart met de route.
        val topCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 16)
            elevation = dp(6).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        titleView = TextView(this).apply {
            textSize = 18f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
        }
        subView = TextView(this).apply {
            textSize = 14f
            setTextColor(muted)
        }
        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(accent)
            visibility = View.GONE
        }
        topCard.addView(titleView)
        topCard.addView(subView)
        topCard.addView(statusView)
        root.addView(topCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            setMargins(dp(12), dp(12), dp(12), 0)
        })

        // Navigatiebanner bovenin, zoals in een autonavigatie: groot pijltje, afstand en straat.
        navCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        navMain = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(navGreen, 18)
            elevation = dp(8).toFloat()
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val navTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        navIcon = TurnArrowView(this).apply { setColor(Color.WHITE) }
        navTop.addView(navIcon, LinearLayout.LayoutParams(dp(66), dp(66)))
        val navText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        navBig = TextView(this).apply {
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        navLine = TextView(this).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E6F4EA"))
            maxLines = 2
        }
        navText.addView(navBig)
        navText.addView(navLine)
        navTop.addView(navText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navMain.addView(navTop)
        val nodeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        navNodeBadge = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(2), mint)
            }
        }
        nodeRow.addView(navNodeBadge, LinearLayout.LayoutParams(dp(30), dp(30)))
        navNodeText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#CFEBDD"))
            setPadding(dp(10), 0, 0, 0)
        }
        nodeRow.addView(navNodeText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navMain.addView(nodeRow)
        navCard.addView(navMain, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // De bocht daarna, klein onder de banner.
        thenBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(navGreenDark, 14)
            elevation = dp(6).toFloat()
            setPadding(dp(14), dp(5), dp(12), dp(5))
            visibility = View.GONE
        }
        thenText = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        thenIcon = TurnArrowView(this).apply { setColor(Color.WHITE) }
        thenBar.addView(thenText)
        thenBar.addView(thenIcon, LinearLayout.LayoutParams(dp(30), dp(30)).apply { leftMargin = dp(6) })
        navCard.addView(thenBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
            leftMargin = dp(10)
        })
        root.addView(navCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            setMargins(dp(12), dp(12), dp(12), 0)
        })

        // Onderbalk tijdens het fietsen: resterende tijd, afstand, aankomst en stoppen.
        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(night, 18)
            elevation = dp(8).toFloat()
            setPadding(dp(18), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        val etaColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        etaMain = TextView(this).apply {
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8BE0A8"))
        }
        etaSub = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#93A3B8"))
        }
        etaColumn.addView(etaMain)
        etaColumn.addView(etaSub)
        navBar.addView(etaColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val stop = TextView(this).apply {
            text = "Stop"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#C0392B"), 16)
            setPadding(dp(24), dp(10), dp(24), dp(10))
            setOnClickListener { stopNavigation() }
        }
        navBar.addView(stop)
        root.addView(navBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(8), 0, dp(8), dp(8))
        })

        // Snelheid, links onderin.
        speedView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(2), Color.parseColor("#D9D5CD"))
            }
            elevation = dp(6).toFloat()
            text = "0"
            visibility = View.GONE
        }
        root.addView(speedView, FrameLayout.LayoutParams(dp(60), dp(60)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(dp(14), 0, 0, dp(100))
        })

        // Onderbalk met knoppen.
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(paper, 20)
            elevation = dp(8).toFloat()
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }
        startButton = TextView(this).apply {
            text = "Start navigatie"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(accent, 16)
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { startNavigation() }
        }
        bottomBar.addView(startButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(textButton("Terug") { undo() })
        row.addView(textButton("Wis") { clearRoute() })
        row.addView(textButton("Opslaan") { saveRoute() })
        row.addView(textButton("Routes") { openRoutes() })
        row.addView(textButton("Meer") { openMore() })
        bottomBar.addView(row)
        root.addView(bottomBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        // Knop om naar je eigen plek te gaan.
        locateButton = TextView(this).apply {
            text = "◎"
            textSize = 24f
            setTextColor(ink)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            elevation = dp(6).toFloat()
            setOnClickListener { goToMe() }
        }
        root.addView(locateButton, FrameLayout.LayoutParams(dp(52), dp(52)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, dp(14), dp(150))
        })

        // Ronde knoppen rechts: kaartstijl, geluid en in- of uitzoomen.
        fun roundButton(label: String, size: Float, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            elevation = dp(6).toFloat()
            setOnClickListener { onClick() }
        }
        styleButton = roundButton("Auto", 11f) {
            styleMode = (styleMode + 1) % 3
            prefs.edit().putInt("style", styleMode).apply()
            applyStyle()
        }
        searchButton = roundButton("\uD83D\uDD0D", 18f) { openSearch() }
        voice = Voice(this)
        voice.muted = prefs.getBoolean("muted", false)
        muteButton = roundButton(if (voice.muted) "\uD83D\uDD07" else "\uD83D\uDD0A", 18f) { toggleMute() }
        zoomInButton = roundButton("+", 26f) { nudgeZoom(0.7) }
        zoomOutButton = roundButton("\u2212", 26f) { nudgeZoom(-0.7) }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        for (b in listOf(searchButton, styleButton, muteButton, zoomInButton, zoomOutButton)) {
            side.addView(b, LinearLayout.LayoutParams(dp(46), dp(46)).apply { topMargin = dp(10) })
        }
        root.addView(side, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setMargins(0, 0, dp(12), 0)
        })
        styleMode = prefs.getInt("style", 0)
        applyStyle()

        setContentView(root)
        updateSummary()
        updateMode()

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
        }
        setStatus("Knooppunten openen\u2026")
        io.execute {
            val data = Bundled.load(this)
            ui.post {
                bundleChecked = true
                if (data != null) {
                    bundled = true
                    overlay.net = data
                    rebuildRoute()
                    map.invalidate()
                    setStatus("")
                } else {
                    setStatus("")
                }
                scheduleLoad()
            }
        }
    }

    private fun isNight(): Boolean = when (styleMode) {
        1 -> false
        2 -> true
        else -> {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            h >= 19 || h < 7
        }
    }

    private fun applyStyle() {
        val night = isNight()
        styleButton.text = when (styleMode) {
            1 -> "Dag"
            2 -> "Nacht"
            else -> "Auto"
        }
        overlay.night = night
        if (shownNight != night) {
            shownNight = night
            // Donkere kaart: kleuren omdraaien, iets dimmen en blauw-grijs tintje.
            map.overlayManager.tilesOverlay.setColorFilter(
                if (night) android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix(floatArrayOf(
                        -0.62f, 0f, 0f, 0f, 175f,
                        0f, -0.66f, 0f, 0f, 185f,
                        0f, 0f, -0.72f, 0f, 200f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                ) else null
            )
        }
        map.invalidate()
    }

    private fun textButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ink)
        gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, dp(12))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    // ---------------------------------------------------------------- levenscyclus

    override fun onResume() {
        super.onResume()
        map.onResume()
        startLocation()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        val c = map.mapCenter
        prefs.edit()
            .putFloat("lat", c.latitude.toFloat())
            .putFloat("lon", c.longitude.toFloat())
            .putFloat("zoom", map.zoomLevelDouble.toFloat())
            .apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        voice.shutdown()
        io.shutdown()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) startLocation()
    }

    // ---------------------------------------------------------------- locatie

    private fun startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 1000L, 2f, locationListener)
                    val last = lm.getLastKnownLocation(provider)
                    if (last != null && me == null) onLocation(last)
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun onLocation(location: Location) {
        val here = GeoPoint(location.latitude, location.longitude)
        me = here
        overlay.me = here
        if (location.hasBearing() && location.speed > 0.7f) {
            heading = location.bearing
        } else {
            // Geen richting van de GPS (langzaam of stilstaand): afleiden uit de verplaatsing.
            val last = lastFix
            if (last != null && last.distanceToAsDouble(here) > 4.0) heading = last.bearingTo(here).toFloat()
        }
        if (lastFix == null || lastFix!!.distanceToAsDouble(here) > 4.0) lastFix = here
        if (!centeredOnMe && !hadSavedCenter) {
            centeredOnMe = true
            map.controller.setZoom(14.0)
            map.controller.setCenter(here)
            scheduleLoad()
        }
        if (navigating) {
            if (location.hasSpeed() && location.speed > 1.5f) {
                avgSpeed = if (avgSpeed == 0.0) location.speed.toDouble() else avgSpeed * 0.9 + location.speed * 0.1
            }
            ride?.add(location)
            updateNavigation(here)
            updateSpeed(location)
            followMe(here)
        }
        map.invalidate()
    }

    private fun updateSpeed(location: Location) {
        if (!location.hasSpeed()) return
        val kmh = (location.speed * 3.6f).roundToInt()
        val text = "$kmh\nkm/u"
        val span = SpannableString(text)
        span.setSpan(RelativeSizeSpan(0.5f), text.indexOf('\n'), text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        speedView.text = span
    }

    /** De kaart draait mee met je rijrichting en jij staat lager in beeld, zoals in een autonavigatie. */
    private fun followMe(here: GeoPoint) {
        val active = guide ?: mainTrack
        var h = heading
        if (h < 0f) {
            // Nog niet bewogen: kijk naar de route, dan staat de kaart meteen goed.
            val a = active?.aim()
            if (a != null && a.distanceToAsDouble(here) > 8.0) h = here.bearingTo(a).toFloat()
        }
        // Inzoomen in wijken: dichter bij een bocht en bij lage snelheid, wat verder weg op rechte stukken.
        val turnDist = active?.turnAhead(250.0)?.first ?: 999.0
        val kmh = (avgSpeed * 3.6)
        var target = if (kmh < 12.0) 17.8 else if (kmh < 22.0) 17.4 else 17.0
        if (turnDist < 70.0) target = 18.4 else if (turnDist < 160.0) target = maxOf(target, 17.9)
        target += zoomOffset
        navZoom += (target - navZoom) * 0.18
        map.controller.setZoom(navZoom)
        if (h < 0f) {
            map.controller.animateTo(here)
            return
        }
        heading = h
        var d = heading - smoothHeading
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        smoothHeading = (smoothHeading + d * 0.4f + 360f) % 360f
        map.mapOrientation = -smoothHeading
        overlay.meHeading = smoothHeading

        val pj = map.projection
        val p0 = android.graphics.Point()
        val p1 = android.graphics.Point()
        pj.toPixels(here, p0)
        pj.toPixels(here.destinationPoint(100.0, 0.0), p1)
        val px = hypot((p1.x - p0.x).toDouble(), (p1.y - p0.y).toDouble())
        if (px > 0.5) {
            val meters = map.height * 0.22 * (100.0 / px)
            map.controller.animateTo(here.destinationPoint(meters, smoothHeading.toDouble()))
        } else {
            map.controller.animateTo(here)
        }
    }

    private fun goToMe() {
        val here = me
        if (here == null) {
            Toast.makeText(this, "Je locatie is nog niet gevonden", Toast.LENGTH_SHORT).show()
            return
        }
        if (map.zoomLevelDouble < 14.0) map.controller.setZoom(15.0)
        map.controller.animateTo(here)
    }

    // ---------------------------------------------------------------- kaartgegevens

    private fun scheduleLoad() {
        ui.removeCallbacks(loadRunnable)
        ui.postDelayed(loadRunnable, 700)
    }

    private val loadRunnable = Runnable { loadVisibleCells() }

    private fun setStatus(message: String) {
        statusView.text = message
        statusView.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun loadVisibleCells() {
        if (!bundleChecked) return
        if (map.zoomLevelDouble < NetOverlay.SHOW_FROM) {
            setStatus("Zoom verder in om de knooppunten te zien")
            return
        }
        // Met de meegeleverde gegevens hoeft er niets opgehaald te worden.
        if (bundled) {
            setStatus("")
            return
        }
        val bb = map.boundingBox
        val y0 = floor(bb.latSouth * CELLS).toInt()
        val y1 = floor(bb.latNorth * CELLS).toInt()
        val x0 = floor(bb.lonWest * CELLS).toInt()
        val x1 = floor(bb.lonEast * CELLS).toInt()
        if ((y1 - y0 + 1) * (x1 - x0 + 1) > 20) {
            setStatus("Zoom verder in om de knooppunten te laden")
            return
        }
        val todo = ArrayList<Pair<Int, Int>>()
        for (y in y0..y1) for (x in x0..x1) {
            if (requested.add("$y/$x")) todo.add(y to x)
        }
        if (todo.isEmpty()) {
            if (pending == 0) setStatus("")
            return
        }
        if (pending == 0) {
            batchTotal = 0
            batchDone = 0
        }
        pending += todo.size
        batchTotal += todo.size
        setStatus("Knooppunten laden… $batchDone/$batchTotal")
        for ((y, x) in todo) {
            io.execute { loadCell(y, x) }
        }
    }

    /** Draait op de achtergronddraad: eerst het opgeslagen vakje, anders ophalen. */
    private fun loadCell(y: Int, x: Int) {
        val file = Overpass.cellFile(cellDir, y, x)
        var text: String? = null
        if (file.exists()) {
            try {
                text = file.readText()
            } catch (_: Exception) {
            }
        }
        if (text == null) {
            text = Overpass.fetch(y / CELLS, x / CELLS, (y + 1) / CELLS, (x + 1) / CELLS)
            if (text != null) {
                try {
                    file.writeText(text)
                } catch (_: Exception) {
                }
            }
        }
        var network: Network? = null
        if (text != null) {
            try {
                val elements = JSONObject(text).optJSONArray("elements") ?: JSONArray()
                val newNodes = ArrayList<JSONObject>()
                val newRelations = ArrayList<JSONObject>()
                for (i in 0 until elements.length()) {
                    val el = elements.optJSONObject(i) ?: continue
                    when (el.optString("type")) {
                        "node" -> newNodes.add(el)
                        "relation" -> newRelations.add(el)
                    }
                }
                synchronized(dataLock) {
                    builder.add(newNodes, newRelations)
                    network = builder.snapshot()
                }
            } catch (_: Exception) {
                file.delete()
            }
        }
        val built = network
        ui.post {
            pending -= 1
            batchDone += 1
            if (built != null) {
                overlay.net = built
                retries = 0
                rebuildRoute()
                map.invalidate()
                setStatus(if (pending == 0) "" else "Knooppunten laden… $batchDone/$batchTotal")
            } else {
                requested.remove("$y/$x")
                if (retries < MAX_RETRIES) {
                    retries += 1
                    setStatus("De kaartserver is druk (" + Overpass.lastError + "), nieuwe poging $retries/$MAX_RETRIES\u2026")
                    ui.removeCallbacks(loadRunnable)
                    ui.postDelayed(loadRunnable, 6000)
                } else {
                    setStatus("Laden mislukt (" + Overpass.lastError + "). Beweeg de kaart om het opnieuw te proberen.")
                    retries = 0
                }
            }
        }
    }

    // ---------------------------------------------------------------- route

    private fun onNodeTapped(node: KNode) {
        map.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (routeIds.isNotEmpty() && routeIds.last() == node.id) {
            routeIds.removeAt(routeIds.size - 1)
        } else {
            routeIds.add(node.id)
        }
        rebuildRoute()
    }

    private fun undo() {
        if (routeIds.isNotEmpty()) routeIds.removeAt(routeIds.size - 1)
        rebuildRoute()
    }

    private fun clearRoute() {
        routeIds.clear()
        rebuildRoute()
    }

    private fun rebuildRoute() {
        val net = overlay.net
        overlay.routeIds = routeIds.toSet()
        if (net == null) {
            legs = emptyList()
            routeNodes = emptyList()
        } else {
            val found = routeIds.mapNotNull { net.nodes[it] }
            routeNodes = found
            legs = (0 until max(0, found.size - 1)).map { net.leg(found[it], found[it + 1]) }
        }
        overlay.legs = legs
        buildNavigationLine()
        updateSummary()
        updateMode()
        map.invalidate()
    }

    private fun totalLength(): Double = legs.sumOf { it.length }

    private fun formatKm(meters: Double): String =
        String.format(Locale("nl", "NL"), "%.1f km", meters / 1000.0)

    private fun formatDistance(meters: Double): String =
        if (meters < 1000) "${(meters / 10.0).roundToInt() * 10} m" else formatKm(meters)

    private fun updateSummary() {
        if (routeNodes.isEmpty()) {
            titleView.text = "Kies je eerste knooppunt"
            subView.text = "Tik op een nummer op de kaart"
            return
        }
        val refs = routeNodes.map { it.ref }
        val shown = if (refs.size > 8) refs.take(3) + "…" + refs.takeLast(3) else refs
        titleView.text = shown.joinToString(" → ")
        if (routeNodes.size == 1) {
            subView.text = "Tik op het volgende knooppunt"
            return
        }
        val meters = totalLength()
        val minutes = (meters / 1000.0 / 16.0 * 60.0).roundToInt()
        var sub = "${formatKm(meters)} · ${routeNodes.size} knooppunten · ± $minutes min"
        if (legs.any { !it.connected }) sub += "\nLet op: stippellijn = geen verbinding gevonden"
        subView.text = sub
    }

    private fun updateMode() {
        bottomBar.visibility = if (navigating) View.GONE else View.VISIBLE
        navCard.visibility = if (navigating) View.VISIBLE else View.GONE
        navBar.visibility = if (navigating) View.VISIBLE else View.GONE
        speedView.visibility = if (navigating) View.VISIBLE else View.GONE
        val navOnly = if (navigating) View.VISIBLE else View.GONE
        muteButton.visibility = navOnly
        searchButton.visibility = if (navigating) View.GONE else View.VISIBLE
        zoomInButton.visibility = navOnly
        zoomOutButton.visibility = navOnly
        findTopCard().visibility = if (navigating) View.GONE else View.VISIBLE
        startButton.visibility = if (!navigating && legs.isNotEmpty()) View.VISIBLE else View.GONE
        overlay.navigating = navigating
        val lp = locateButton.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = if (navigating) dp(100) else if (legs.isNotEmpty()) dp(150) else dp(100)
        locateButton.layoutParams = lp
    }

    private fun findTopCard(): View = titleView.parent as View

    // ---------------------------------------------------------------- navigatie

    private fun densify(pts: List<GeoPoint>, out: MutableList<GeoPoint>) {
        for (i in pts.indices) {
            if (i == 0) {
                if (out.isEmpty()) out.add(pts[0])
                continue
            }
            val a = pts[i - 1]
            val b = pts[i]
            val d = a.distanceToAsDouble(b)
            val steps = max(1, Math.ceil(d / 10.0).toInt())
            for (k in 1 until steps) {
                val f = k.toDouble() / steps
                out.add(GeoPoint(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f))
            }
            out.add(b)
        }
    }

    private fun buildNavigationLine() {
        val pts = ArrayList<GeoPoint>()
        val idx = IntArray(routeNodes.size)
        for ((i, leg) in legs.withIndex()) {
            densify(leg.pts, pts)
            idx[i + 1] = pts.size - 1
        }
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + pts[i - 1].distanceToAsDouble(pts[i])
        navPts = pts
        mainTrack = Track(pts, false)
        navCum = cum
        navNodeIdx = idx
        navProgress = 0
        navNext = -1
    }

    private fun startNavigation() {
        if (legs.isEmpty() || navPts.size < 2) return
        if (me == null) {
            Toast.makeText(this, "Wacht even tot je locatie is gevonden", Toast.LENGTH_SHORT).show()
            startLocation()
        }
        navigating = true
        spoken.clear()
        zoomOffset = 0.0
        ride = RideRecorder()
        applyStyle()
        clearGuide()
        navFitted = false
        heading = -1f
        smoothHeading = 0f
        avgSpeed = 0.0
        navProgress = 0
        navNext = -1
        wasOffRoute = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateMode()
        val here = me
        navZoom = 17.3
        lastFix = null
        map.controller.setZoom(navZoom)
        if (here != null) {
            map.controller.setCenter(here)
            updateNavigation(here)
            followMe(here)
        } else {
            map.controller.setCenter(routeNodes.first().pos)
            renderNav(TurnArrowView.POINTER, 0f, "GPS\u2026", "Wachten op je locatie", routeNodes.first().ref, "", false)
            etaMain.text = formatKm(totalLength())
            etaSub.text = "Route"
        }
        map.invalidate()
    }

    private fun stopNavigation() {
        voice.stop()
        val tripName = trip?.name
        val finished = ride
        ride = null
        navigating = false
        applyStyle()
        clearGuide()
        map.mapOrientation = 0f
        overlay.meHeading = Float.NaN
        overlay.nextId = -1L
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateMode()
        map.invalidate()
        if (trip != null) {
            trip = null
            rebuildRoute()
        }
        if (finished != null) finishRide(finished, tripName?.let { "Naar $it" })
    }

    private fun renderNav(mode: Int, angle: Float, big: String, line: String, nodeRef: String, nodeText: String, off: Boolean) {
        navIcon.set(mode, angle)
        navBig.text = big
        navBig.textSize = if (big.length > 8) 24f else 34f
        navLine.text = line
        navNodeBadge.text = nodeRef
        navNodeBadge.textSize = if (nodeRef.length > 2) 11f else 14f
        navNodeText.text = nodeText
        navMain.background = rounded(if (off) warn else navGreen, 18)
    }

    private fun turnWord(dir: Int) = if (dir > 0) "Rechtsaf" else "Linksaf"
    private fun turnMode(dir: Int) = if (dir > 0) TurnArrowView.RIGHT else TurnArrowView.LEFT

    private fun say(text: String) {
        voice.speak(text)
    }

    /** Bocht of rechtdoor: symbool, afstand en straatnaam; zegt het ook hardop. */
    private fun guidance(track: Track?, tag: String, toTarget: Double, straight: String): Triple<Int, String, String> {
        val turns = if (track != null) track.turnsAhead(400.0, 2) else emptyList()
        val t = turns.firstOrNull()
        if (track != null) for (x in turns) Streets.ensure(track.pointAfter(x.index))
        val name = if (t != null && track != null) Streets.get(track.pointAfter(t.index))?.takeIf { it.isNotEmpty() } else null

        val n = turns.getOrNull(1)
        if (t != null && n != null && n.dist - t.dist < 250.0) {
            thenText.text = "Daarna"
            thenIcon.set(turnMode(n.dir), 0f)
            thenBar.visibility = View.VISIBLE
        } else {
            thenBar.visibility = View.GONE
        }

        if (t == null) return Triple(TurnArrowView.STRAIGHT, formatDistance(toTarget), straight)
        val word = turnWord(t.dir)
        val key = tag + t.index
        val spokenName = if (name != null) " naar $name" else ""
        if (t.dist <= 45.0) {
            if (spoken.add(key + "b")) {
                spoken.add(key + "a")
                say("Nu " + word.lowercase() + spokenName)
            }
        } else if (t.dist <= 230.0 && spoken.add(key + "a")) {
            val m = (t.dist / 10.0).roundToInt() * 10
            say("Over $m meter " + word.lowercase() + spokenName)
        }
        val line = word + (if (name != null) " naar $name" else "")
        return Triple(turnMode(t.dir), if (t.dist < 25) "Nu" else formatDistance(t.dist), line)
    }

    private fun toggleMute() {
        voice.muted = !voice.muted
        if (voice.muted) voice.stop()
        prefs.edit().putBoolean("muted", voice.muted).apply()
        muteButton.text = if (voice.muted) "\uD83D\uDD07" else "\uD83D\uDD0A"
    }

    private fun nudgeZoom(delta: Double) {
        zoomOffset = (zoomOffset + delta).coerceIn(-2.5, 2.0)
        navZoom = (navZoom + delta).coerceIn(10.0, 20.0)
        map.controller.setZoom(navZoom)
    }

    private fun relativeBearing(here: GeoPoint, goal: GeoPoint): Float {
        val b = here.bearingTo(goal).toFloat()
        return if (heading >= 0f) b - heading else b
    }

    private fun updateEta(meters: Double) {
        val v = if (avgSpeed > 2.5) min(max(avgSpeed, 2.8), 8.0) else 16.0 / 3.6
        val secs = meters / v
        val minutes = ceil(secs / 60.0).toInt()
        etaMain.text = if (minutes >= 60) "${minutes / 60} u ${minutes % 60} min" else "$minutes min"
        val arrive = Calendar.getInstance().apply { add(Calendar.SECOND, secs.toInt()) }
        val clock = String.format(Locale("nl", "NL"), "%02d:%02d", arrive.get(Calendar.HOUR_OF_DAY), arrive.get(Calendar.MINUTE))
        etaSub.text = formatKm(meters) + " \u00B7 aankomst " + clock
    }

    private fun fitTo(a: GeoPoint, b: GeoPoint) {
        if (a.distanceToAsDouble(b) < 80.0) return
        val box = BoundingBox.fromGeoPoints(listOf(a, b))
        map.zoomToBoundingBox(box.increaseByScale(1.5f), true)
    }

    private fun updateNavigation(here: GeoPoint) {
        if (navPts.size < 2) return
        var best = -1
        var bestDist = Double.MAX_VALUE
        val from = max(0, navProgress - 2)
        val to = min(navPts.size - 1, navProgress + 150)
        for (i in from..to) {
            val d = here.distanceToAsDouble(navPts[i])
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        if (bestDist > OFF_ROUTE_METERS) {
            // Misschien een stuk overgeslagen of teruggereden: zoek op de hele route.
            for (i in navPts.indices) {
                val d = here.distanceToAsDouble(navPts[i])
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
        }
        val off = bestDist > OFF_ROUTE_METERS
        // Nog niet op de route begonnen: eerst naar het eerste knooppunt.
        val toStart = off && navProgress <= 0
        if (!off && best >= 0) navProgress = best

        var k = 1
        while (k < routeNodes.size && navNodeIdx[k] <= navProgress + 2) k++

        if (k != navNext) {
            if (navNext != -1) {
                map.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val reached = routeNodes[navNext].ref
                val tr = trip
                if (tr != null) {
                    if (k >= routeNodes.size) say("Je bent aangekomen bij ${tr.name}")
                } else if (k < routeNodes.size) say("Knooppunt $reached. Volgende knooppunt ${routeNodes[k].ref}") else say("Je bent aangekomen bij knooppunt $reached")
            }
            navNext = k
        }
        val justOff = off && !wasOffRoute
        if (justOff && !toStart) {
            map.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            say("Je bent van de route af")
        }
        if (!off && wasOffRoute && navProgress > 0) say("Je bent weer op de route")
        if (toStart && trip == null && spoken.add("start")) say("Ga eerst naar het startpunt, knooppunt ${routeNodes[0].ref}")
        wasOffRoute = off

        if (k >= routeNodes.size) {
            overlay.nextId = -1L
            renderNav(TurnArrowView.ARRIVED, 0f, "Aangekomen", "Je bent bij ${trip?.name ?: routeNodes.last().ref}", routeNodes.last().ref, "", false)
            etaMain.text = "Klaar"
            etaSub.text = formatKm(totalLength())
            return
        }

        val next = routeNodes[k]
        val remaining = navCum[navCum.size - 1] - navCum[navProgress]
        val toNext = navCum[navNodeIdx[k]] - navCum[navProgress]
        val afterNext = if (k + 1 < routeNodes.size) routeNodes[k + 1].ref else null
        var remainingAll = remaining

        if (toStart || off) {
            val goal = if (toStart) navPts[0] else navPts[best]
            val goalNode = if (toStart) routeNodes[0] else next
            ensureGuide(here, goal)
            val g = guide
            overlay.nextId = goalNode.id
            val what = if (toStart) "Startpunt" else "Terug naar de route"
            if (g != null) {
                val left = g.remaining() + g.locate(here)
                val m = guidance(g, "g", left, if (toStart) "Rechtdoor naar het startpunt" else "Rechtdoor terug naar de route")
                renderNav(m.first, 0f, m.second, m.third, goalNode.ref, "$what \u00B7 ${formatDistance(left)}", !toStart)
                remainingAll = if (toStart) left + navCum[navCum.size - 1] else left + remaining
            } else {
                thenBar.visibility = View.GONE
                val straight = here.distanceToAsDouble(goal)
                val line = if (guideFetching) "Route zoeken\u2026" else "Ga in deze richting"
                renderNav(TurnArrowView.POINTER, relativeBearing(here, goal), formatDistance(straight), line, goalNode.ref, "$what \u00B7 ${formatDistance(straight)}", !toStart)
                remainingAll = (if (toStart) navCum[navCum.size - 1] else remaining) + straight
                navFitted = true
            }
        } else {
            if (guide != null) {
                clearGuide()
            }
            overlay.nextId = next.id
            mainTrack?.progress = navProgress
            val m = guidance(mainTrack, "m", toNext, "Rechtdoor")
            val nodeText = if (trip != null) "${formatDistance(toNext)} naar ${trip?.name}" else
                "${formatDistance(toNext)} naar knooppunt ${next.ref}" +
                    (if (afterNext != null) " \u00B7 daarna $afterNext" else "")
            renderNav(m.first, 0f, m.second, m.third, next.ref, nodeText, false)
        }
        updateEta(remainingAll)
    }

    private fun clearGuide() {
        guide = null
        guideTarget = null
        overlay.guide = emptyList()
    }

    /** Haalt, als dat nodig is, een fietsroute op van je plek naar het doel. */
    private fun ensureGuide(here: GeoPoint, goal: GeoPoint) {
        val g = guide
        val now = System.currentTimeMillis()
        val stale = g == null ||
            (guideTarget?.distanceToAsDouble(goal) ?: 1e9) > 150.0 ||
            g.locate(here) > 60.0
        if (!stale || guideFetching || now - guideAttempt < 15000) return
        guideFetching = true
        guideAttempt = now
        io.execute {
            val pts = Router.route(here, goal)
            ui.post {
                guideFetching = false
                if (pts != null && navigating) {
                    guide = Track(pts)
                    guideTarget = goal
                    overlay.guide = pts
                    me?.let { updateNavigation(it) }
                    map.invalidate()
                }
            }
        }
    }

    // ---------------------------------------------------------------- opslaan en delen

    private fun routeName(): String {
        val refs = routeNodes.map { it.ref }
        val short = if (refs.size > 5) refs.take(2) + "…" + refs.takeLast(2) else refs
        return short.joinToString("-") + " (" + formatKm(totalLength()) + ")"
    }

    private fun savedRoutes(): JSONArray = try {
        JSONArray(prefs.getString("routes", "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun saveRoute() {
        if (routeNodes.size < 2) {
            Toast.makeText(this, "Kies eerst minstens twee knooppunten", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            setText(routeName())
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            selectAll()
        }
        val pad = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Route opslaan")
            .setView(pad)
            .setPositiveButton("Opslaan") { _, _ ->
                val list = savedRoutes()
                val obj = JSONObject()
                obj.put("name", input.text.toString().ifBlank { routeName() })
                obj.put("ids", JSONArray(routeNodes.map { it.id }))
                obj.put("lat", routeNodes.first().pos.latitude)
                obj.put("lon", routeNodes.first().pos.longitude)
                obj.put("date", SimpleDateFormat("d MMM yyyy", Locale("nl", "NL")).format(Date()))
                obj.put("refs", JSONArray(routeNodes.map { it.ref }))
                obj.put("meters", totalLength())
                obj.put("pts", routeShape())
                list.put(obj)
                prefs.edit().putString("routes", list.toString()).apply()
                Toast.makeText(this, "Route opgeslagen", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    /** De vorm van de route, afgevlakt tot ongeveer 120 punten, voor het miniatuur in de lijst. */
    private fun routeShape(): JSONArray {
        val all = ArrayList<GeoPoint>()
        for (leg in legs) all.addAll(leg.pts)
        val arr = JSONArray()
        if (all.isEmpty()) return arr
        val step = max(1, all.size / 120)
        var i = 0
        while (i < all.size) {
            arr.put(all[i].latitude)
            arr.put(all[i].longitude)
            i += step
        }
        arr.put(all.last().latitude)
        arr.put(all.last().longitude)
        return arr
    }

    private fun openRoutes() {
        val list = savedRoutes()
        if (list.length() == 0) {
            Toast.makeText(this, "Je hebt nog geen routes opgeslagen", Toast.LENGTH_SHORT).show()
            return
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Opgeslagen routes")
            .setView(ScrollView(this).apply { addView(column) })
            .setNegativeButton("Sluiten", null)
            .create()
        for (i in 0 until list.length()) {
            column.addView(routeRow(list.getJSONObject(i), i, dialog))
        }
        dialog.show()
    }

    private fun routeRow(o: JSONObject, index: Int, dialog: AlertDialog): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener {
                dialog.dismiss()
                loadRoute(o)
            }
        }
        val shape = o.optJSONArray("pts")
        if (shape != null && shape.length() >= 4) {
            val flat = DoubleArray(shape.length()) { shape.getDouble(it) }
            row.addView(RouteThumb(this, flat), LinearLayout.LayoutParams(dp(80), dp(80)).apply {
                rightMargin = dp(14)
            })
        }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(this).apply {
            this.text = o.optString("name")
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        val refs = o.optJSONArray("refs")
        if (refs != null && refs.length() > 0) {
            val all = (0 until refs.length()).map { refs.optString(it) }
            val shown = if (all.size > 8) all.take(3) + "\u2026" + all.takeLast(3) else all
            text.addView(TextView(this).apply {
                this.text = shown.joinToString(" \u2192 ")
                textSize = 14f
                alpha = 0.85f
            })
        }
        val meters = o.optDouble("meters", 0.0)
        val stats = ArrayList<String>()
        if (meters > 0) {
            stats.add(formatKm(meters))
            stats.add("\u00B1 ${(meters / 1000.0 / 16.0 * 60.0).roundToInt()} min")
        }
        stats.add(o.optString("date"))
        text.addView(TextView(this).apply {
            this.text = stats.joinToString(" \u00B7 ")
            textSize = 13f
            alpha = 0.65f
        })
        text.addView(TextView(this).apply {
            this.text = "Verwijderen"
            textSize = 13f
            alpha = 0.8f
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, dp(8), dp(16), dp(4))
            setOnClickListener { confirmDelete(o, index, dialog) }
        })
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun confirmDelete(o: JSONObject, index: Int, dialog: AlertDialog) {
        AlertDialog.Builder(this)
            .setTitle("Route verwijderen?")
            .setMessage(o.optString("name"))
            .setPositiveButton("Verwijderen") { _, _ ->
                val list = savedRoutes()
                val updated = JSONArray()
                for (i in 0 until list.length()) if (i != index) updated.put(list.getJSONObject(i))
                prefs.edit().putString("routes", updated.toString()).apply()
                dialog.dismiss()
                if (updated.length() > 0) openRoutes()
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun loadRoute(obj: JSONObject) {
        val ids = obj.optJSONArray("ids") ?: return
        routeIds.clear()
        for (i in 0 until ids.length()) routeIds.add(ids.getLong(i))
        val here = GeoPoint(obj.optDouble("lat"), obj.optDouble("lon"))
        map.controller.setZoom(14.0)
        map.controller.setCenter(here)
        rebuildRoute()
        scheduleLoad()
    }

    // ---------------------------------------------------------------- adressen en plekken

    private fun savedPlaces(): ArrayList<Place> {
        val out = ArrayList<Place>()
        try {
            val arr = JSONArray(prefs.getString("places", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Place(o.optString("name"), o.optString("address"), o.optDouble("lat"), o.optDouble("lon")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun writePlaces(list: List<Place>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(JSONObject().put("name", p.name).put("address", p.address).put("lat", p.lat).put("lon", p.lon))
        }
        prefs.edit().putString("places", arr.toString()).apply()
    }

    private fun isSaved(p: Place) = savedPlaces().any { Math.abs(it.lat - p.lat) < 1e-5 && Math.abs(it.lon - p.lon) < 1e-5 }

    private fun openSearch() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val input = EditText(this).apply {
            hint = "Adres of plaats, bijv. Kerkstraat 5 Doesburg"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        column.addView(input)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Naar adres fietsen")
            .setView(ScrollView(this).apply { addView(column) })
            .setPositiveButton("Zoeken", null)
            .setNegativeButton("Sluiten", null)
            .create()
        val places = savedPlaces()
        if (places.isNotEmpty()) {
            column.addView(TextView(this).apply {
                text = "Mijn plekken"
                textSize = 13f
                setTextColor(muted)
                setPadding(0, dp(16), 0, dp(4))
            })
            for (pl in places) {
                column.addView(TextView(this).apply {
                    text = pl.name + "\n" + pl.address.split(",").take(3).joinToString(",")
                    textSize = 15f
                    setTextColor(ink)
                    setPadding(0, dp(10), 0, dp(10))
                    setOnClickListener {
                        dialog.dismiss()
                        placeActions(pl)
                    }
                })
            }
        }
        dialog.show()
        val go = {
            val q = input.text.toString().trim()
            if (q.isNotEmpty()) {
                dialog.dismiss()
                searchAddress(q)
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { go() }
        input.setOnEditorActionListener { _, _, _ ->
            go()
            true
        }
    }

    private fun searchAddress(q: String) {
        Toast.makeText(this, "Zoeken\u2026", Toast.LENGTH_SHORT).show()
        Streets.search(q) { list ->
            ui.post {
                if (list.isEmpty()) {
                    Toast.makeText(this, "Niets gevonden. Probeer straat, huisnummer en plaats.", Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Kies het adres")
                        .setItems(list.map { it.address }.toTypedArray()) { _, i -> placeActions(list[i]) }
                        .setNegativeButton("Sluiten", null)
                        .show()
                }
            }
        }
    }

    private fun placeActions(p: Place) {
        map.controller.setZoom(16.0)
        map.controller.setCenter(GeoPoint(p.lat, p.lon))
        val saved = isSaved(p)
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setMessage(p.address)
            .setPositiveButton("Fietsen hierheen") { _, _ -> bikeTo(p) }
            .setNeutralButton(if (saved) "Verwijder plek" else "Bewaar als plek") { _, _ ->
                if (saved) {
                    writePlaces(savedPlaces().filter { !(Math.abs(it.lat - p.lat) < 1e-5 && Math.abs(it.lon - p.lon) < 1e-5) })
                } else {
                    askPlaceName(p)
                }
            }
            .setNegativeButton("Sluiten", null)
            .show()
    }

    private fun askPlaceName(p: Place) {
        val input = EditText(this).apply {
            setText(p.name)
            hint = "Naam, bijv. Oma of Marieke"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            selectAll()
        }
        val pad = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Plek bewaren")
            .setView(pad)
            .setPositiveButton("Bewaren") { _, _ ->
                val list = savedPlaces()
                list.add(Place(input.text.toString().ifBlank { p.name }, p.address, p.lat, p.lon))
                writePlaces(list)
                Toast.makeText(this, "Bewaard bij Mijn plekken", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    /** Fietsroute van je plek naar een adres, met dezelfde navigatie als de knooppuntroutes. */
    private fun bikeTo(p: Place) {
        val here = me
        if (here == null) {
            Toast.makeText(this, "Je locatie is nog niet gevonden", Toast.LENGTH_SHORT).show()
            startLocation()
            return
        }
        setStatus("Fietsroute zoeken\u2026")
        io.execute {
            val pts = Router.route(here, GeoPoint(p.lat, p.lon))
            ui.post {
                setStatus("")
                if (pts == null || pts.size < 2) {
                    Toast.makeText(this, "Er is nu geen route gevonden. Heb je internet?", Toast.LENGTH_LONG).show()
                } else {
                    var len = 0.0
                    for (i in 1 until pts.size) len += pts[i - 1].distanceToAsDouble(pts[i])
                    trip = p
                    routeNodes = listOf(KNode(-1L, "A", pts.first()), KNode(-2L, "\u2691", pts.last()))
                    legs = listOf(Leg(pts, len, true))
                    overlay.routeIds = emptySet()
                    overlay.legs = legs
                    buildNavigationLine()
                    startNavigation()
                }
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val minutes = (ms / 60000.0).roundToInt()
        return if (minutes >= 60) "${minutes / 60} u ${minutes % 60} min" else "$minutes min"
    }

    private fun kmh(ms: Double): String = String.format(Locale("nl", "NL"), "%.1f", ms * 3.6)

    private fun finishRide(r: RideRecorder, tripTitle: String?) {
        if (r.distance < 300.0) return
        val msg = formatKm(r.distance) + " in " + formatDuration(r.movingMs) +
            "\nGemiddeld " + kmh(r.avgSpeed()) + " km/u \u00B7 max " + kmh(r.maxSpeed) + " km/u"
        val name = tripTitle ?: routeName()
        AlertDialog.Builder(this)
            .setTitle("Rit klaar")
            .setMessage(msg)
            .setPositiveButton("Bewaren") { _, _ ->
                io.execute { Rides.save(applicationContext, r, name) }
                Toast.makeText(this, "Rit bewaard bij Meer \u203A Mijn ritten", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Weggooien", null)
            .show()
    }

    private fun openMore() {
        val items = arrayOf("Route als GPX bewaren", "Route offline opslaan", "Mijn ritten")
        AlertDialog.Builder(this)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> exportGpx()
                    1 -> downloadOffline()
                    else -> openRides()
                }
            }
            .setNegativeButton("Sluiten", null)
            .show()
    }

    private fun openRides() {
        val rides = Rides.list(this)
        if (rides.isEmpty()) {
            Toast.makeText(this, "Je hebt nog geen ritten bewaard", Toast.LENGTH_SHORT).show()
            return
        }
        val date = SimpleDateFormat("EEE d MMM, HH:mm", Locale("nl", "NL"))
        val labels = rides.map {
            val avg = if (it.moving > 0) it.distance / (it.moving / 1000.0) else 0.0
            date.format(Date(it.start)) + "\n" + formatKm(it.distance) + " \u00B7 " + formatDuration(it.moving) + " \u00B7 gem. " + kmh(avg) + " km/u"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Mijn ritten")
            .setItems(labels) { _, i -> rideActions(rides[i]) }
            .setNegativeButton("Sluiten", null)
            .show()
    }

    private fun rideActions(r: RideInfo) {
        AlertDialog.Builder(this)
            .setTitle(r.name)
            .setItems(arrayOf("Als GPX bewaren", "Verwijderen")) { _, i ->
                if (i == 0) {
                    pendingGpx = try {
                        Rides.gpx(r.file)
                    } catch (_: Exception) {
                        null
                    }
                    if (pendingGpx == null) {
                        Toast.makeText(this, "Deze rit kon niet worden gelezen", Toast.LENGTH_SHORT).show()
                    } else {
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_TITLE, "rit-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(r.start)) + ".gpx")
                        }
                        startActivityForResult(intent, REQ_GPX)
                    }
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("Deze rit verwijderen?")
                        .setPositiveButton("Verwijderen") { _, _ -> r.file.delete() }
                        .setNegativeButton("Annuleren", null)
                        .show()
                }
            }
            .setNegativeButton("Sluiten", null)
            .show()
    }

    private fun downloadOffline() {
        if (legs.isEmpty()) {
            Toast.makeText(this, "Kies eerst minstens twee knooppunten", Toast.LENGTH_SHORT).show()
            return
        }
        val pts = ArrayList<GeoPoint>()
        for (l in legs) pts.addAll(l.pts)
        val tiles = OfflineTiles.plan(pts)
        if (tiles.size > 6000) {
            Toast.makeText(this, "Deze route is te lang om in \u00E9\u00E9n keer offline te zetten", Toast.LENGTH_LONG).show()
            return
        }
        val mb = max(1, tiles.size * 20 / 1024)
        AlertDialog.Builder(this)
            .setTitle("Route offline opslaan")
            .setMessage("Dit haalt ongeveer ${tiles.size} kaartplaatjes (\u00B1 $mb MB) op, alleen langs je route. Doe dit met wifi of goed bereik.")
            .setPositiveButton("Downloaden") { _, _ ->
                setStatus("Offline kaart: 0/${tiles.size}")
                Thread {
                    OfflineTiles.download(map, tiles, { done, total ->
                        ui.post { setStatus("Offline kaart: $done/$total") }
                    }, { ok ->
                        ui.post {
                            setStatus("")
                            Toast.makeText(this, "Offline kaart klaar ($ok plaatjes)", Toast.LENGTH_LONG).show()
                        }
                    })
                }.start()
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun exportGpx() {
        pendingGpx = null
        if (legs.isEmpty()) {
            Toast.makeText(this, "Kies eerst minstens twee knooppunten", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "knooppunten-" + routeNodes.joinToString("-") { it.ref }.take(40) + ".gpx")
        }
        startActivityForResult(intent, REQ_GPX)
    }

    private fun buildGpx(): String {
        val us = Locale.US
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Knooppunt\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (n in routeNodes) {
            sb.append(String.format(us, "  <wpt lat=\"%.6f\" lon=\"%.6f\"><name>%s</name></wpt>\n", n.pos.latitude, n.pos.longitude, n.ref))
        }
        sb.append("  <trk>\n    <name>").append(routeName().replace("&", "&amp;").replace("<", "&lt;")).append("</name>\n    <trkseg>\n")
        for ((i, leg) in legs.withIndex()) {
            for ((j, p) in leg.pts.withIndex()) {
                if (i > 0 && j == 0) continue
                sb.append(String.format(us, "      <trkpt lat=\"%.6f\" lon=\"%.6f\"/>\n", p.latitude, p.longitude))
            }
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_GPX && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { it.write((pendingGpx ?: buildGpx()).toByteArray()) }
                pendingGpx = null
                Toast.makeText(this, "GPX opgeslagen", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Opslaan is niet gelukt", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (navigating) stopNavigation() else super.onBackPressed()
    }

    companion object {
        private const val REQ_LOCATION = 1
        private const val REQ_GPX = 2
        private const val OFF_ROUTE_METERS = 40.0
        private const val CELLS = 20.0
        private const val MAX_RETRIES = 5
    }
}

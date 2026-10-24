package com.d3dclone.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val STYLE_DAY = "https://tiles.openfreemap.org/styles/positron"
private const val STYLE_NIGHT = "https://tiles.openfreemap.org/styles/dark"

/**
 * Small translucent map (OpenFreeMap tiles through MapLibre): the route, heading-up, with the car
 * at the centre. The 3D road bends the way this map's route bends.
 */
@Composable
fun MiniMap(s: ViewerState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(ctx)
        MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx).textureMode(true)).apply { onCreate(null) }
    }
    val mapRef = remember { arrayOfNulls<MapLibreMap>(1) }
    val styleRef = remember { arrayOfNulls<Style>(1) }
    var ready by remember { androidx.compose.runtime.mutableStateOf(false) }
    var styleVer by remember { mutableIntStateOf(0) }

    remember(mapView) {
        mapView.getMapAsync { m ->
            mapRef[0] = m
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.setAllGesturesEnabled(false)
            ready = true
        }
        Unit
    }

    LaunchedEffect(ready, s.night) {
        val map = mapRef[0] ?: return@LaunchedEffect
        map.setStyle(Style.Builder().fromUri(if (s.night) STYLE_NIGHT else STYLE_DAY)) { style ->
            style.addSource(GeoJsonSource("route-done"))
            style.addLayer(LineLayer("route-done-line", "route-done").withProperties(
                PropertyFactory.lineColor("#9AA0A6"), PropertyFactory.lineWidth(6f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
            style.addSource(GeoJsonSource("route"))
            style.addLayer(LineLayer("route-casing", "route").withProperties(
                PropertyFactory.lineColor("#0B3D91"), PropertyFactory.lineWidth(10f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
            style.addLayer(LineLayer("route-line", "route").withProperties(
                PropertyFactory.lineColor(Expression.match(
                    Expression.toNumber(Expression.get("lvl")),
                    Expression.color(android.graphics.Color.parseColor("#29B6F6")),
                    Expression.stop(1, Expression.color(android.graphics.Color.parseColor("#F5D000"))),
                    Expression.stop(2, Expression.color(android.graphics.Color.parseColor("#FF8A00"))),
                    Expression.stop(3, Expression.color(android.graphics.Color.parseColor("#E53935"))))),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
            style.addSource(GeoJsonSource("dest"))
            style.addLayer(CircleLayer("dest-dot", "dest").withProperties(
                PropertyFactory.circleRadius(8f), PropertyFactory.circleColor("#E53935"),
                PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.5f)))
            styleRef[0] = style
            styleVer++
        }
    }

    // Route line: grey behind the car, blue ahead. Redrawn when the route changes or the car moved a few metres.
    val drawn = remember { doubleArrayOf(-1e9) }
    fun updateRoute(style: Style, sNow: Double) {
        val r = Course.route
        val todo = style.getSourceAs<GeoJsonSource>("route") ?: return
        val done = style.getSourceAs<GeoJsonSource>("route-done") ?: return
        val dest = style.getSourceAs<GeoJsonSource>("dest") ?: return
        if (r == null) {
            val e = FeatureCollection.fromFeatures(emptyList<Feature>())
            todo.setGeoJson(e); done.setGeoJson(e); dest.setGeoJson(e); return
        }
        val stride = (r.n / 2500).coerceAtLeast(1)
        val split = r.cum.indexOfFirst { it >= sNow }.let { if (it < 0) r.n - 1 else it }
        val here = r.pose(sNow)
        val dn = ArrayList<Point>(); val up = ArrayList<Point>()
        var i = 0
        while (i < split) { dn.add(Point.fromLngLat(r.lon[i], r.lat[i])); i += stride }
        dn.add(Point.fromLngLat(here[1], here[0]))
        if (dn.size >= 2) done.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(dn)))
        else done.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        // Ahead of the car: one feature per stretch, coloured by traffic level.
        val feats = ArrayList<Feature>()
        var cur = sNow.coerceIn(0.0, r.total)
        fun add(a: Double, b: Double, lvl: Int) {
            if (b - a < 1.0) return
            val pts = r.polyline(a, b).map { Point.fromLngLat(it[1], it[0]) }
            if (pts.size >= 2) feats.add(Feature.fromGeometry(LineString.fromLngLats(pts)).also { it.addNumberProperty("lvl", lvl) })
        }
        for (t in r.traffic.sortedBy { it[0] }) {
            val a = maxOf(t[0], cur); val b = minOf(t[1], r.total)
            if (b <= a) continue
            if (a > cur) add(cur, a, 0)
            add(a, b, t[2].toInt()); cur = b
        }
        if (cur < r.total) add(cur, r.total, 0)
        todo.setGeoJson(FeatureCollection.fromFeatures(feats))
        dest.setGeoJson(Feature.fromGeometry(Point.fromLngLat(r.lon[r.n - 1], r.lat[r.n - 1])))
    }
    LaunchedEffect(s.routeVer, s.trafficVer, styleVer) {
        val style = styleRef[0] ?: return@LaunchedEffect
        drawn[0] = s.routeS
        updateRoute(style, s.routeS.coerceAtLeast(0.0))
    }

    // Follow the car, heading-up.
    LaunchedEffect(ready) {
        while (true) {
            delay(450)
            val map = mapRef[0] ?: continue
            if (!s.mapValid) continue
            // top padding pushes the car (map centre) towards the bottom of the widget
            map.moveCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(s.mapLat, s.mapLon)).zoom(16.2).bearing(s.mapBrg.toDouble()).tilt(48.0)
                    .padding(0.0, mapView.height * 0.42, 0.0, 0.0).build()))
            if (Course.route != null && kotlin.math.abs(s.routeS - drawn[0]) > 5.0) {
                styleRef[0]?.let { drawn[0] = s.routeS; updateRoute(it, s.routeS.coerceAtLeast(0.0)) }
            }
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, mapView) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        // The composition may start after the activity: bring the map up to the current state.
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose { owner.lifecycle.removeObserver(obs); mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }

    Box(modifier.clip(RoundedCornerShape(18.dp)).alpha(0.66f)) {
        AndroidView({ mapView }, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height * 0.71f)
            val r = 11.dp.toPx()
            val p = Path().apply {
                moveTo(c.x, c.y - r * 1.2f); lineTo(c.x + r * 0.85f, c.y + r); lineTo(c.x, c.y + r * 0.45f); lineTo(c.x - r * 0.85f, c.y + r); close()
            }
            drawPath(p, Color.White)
            drawPath(p, Color(0xFF1565C0), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx()))
        }
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
private val Double.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())

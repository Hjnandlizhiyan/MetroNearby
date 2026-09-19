package com.metronearby.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.metronearby.R
import com.metronearby.domain.NetworkMapCatalog
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkMapScreen(
    selectedCityId: String,
    onCitySelect: (String) -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit
) {
    var mapView by remember { mutableStateOf<ZoomableNetworkMapView?>(null) }
    var showCityPicker by remember { mutableStateOf(false) }
    val selectedMap = NetworkMapCatalog.find(selectedCityId)
    LaunchedEffect(selectedMap.id) { mapView = null }

    if (showCityPicker) {
        NetworkMapCityDialog(
            selectedCityId = selectedMap.id,
            onSelect = {
                onCitySelect(it)
                showCityPicker = false
            },
            onDismiss = { showCityPicker = false }
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedMap.title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = { showCityPicker = true }) { Text("选城市") }
                    TextButton(onClick = { mapView?.resetView() }) { Text("看全图") }
                }
            )
        },
        bottomBar = bottomBar
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                key(selectedMap.id) {
                    AndroidView(
                        factory = { context ->
                            ZoomableNetworkMapView(context, mapResourceId(selectedMap.id)).also {
                                it.contentDescription = "可缩放的${selectedMap.cityName}地铁线网图"
                                mapView = it
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .88f),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 2.dp
                ) {
                    Text(
                        "双指缩放 · 拖动浏览 · 双击放大或复原",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Text(
                "${selectedMap.provinceName}轨道交通线网图 · 来源：${selectedMap.sourceLabel} · ${selectedMap.updatedAt}",
                modifier = Modifier.fillMaxWidth().padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NetworkMapCityDialog(
    selectedCityId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择城市线网图") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "以后增加其它省市地图时会出现在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                NetworkMapCatalog.available.forEach { map ->
                    TextButton(onClick = { onSelect(map.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            CityTag(map.cityName)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(map.title)
                                Text(map.provinceName, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (map.id == selectedCityId) Text("当前")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@DrawableRes
private fun mapResourceId(cityId: String): Int = when (cityId) {
    NetworkMapCatalog.DEFAULT_CITY_ID -> R.drawable.beijing_subway_network_map
    else -> R.drawable.beijing_subway_network_map
}

/**
 * 面向超大线网图的轻量查看器。初始只解码 1/4 预览；停止拖动或缩放后，
 * 再按当前视窗从原图分块解码，因此不会把 6564×6537 原图整张展开到内存。
 */
@Suppress("DEPRECATION") // InputStream 重载覆盖本项目 minSdk 24；新重载仅支持较新系统。
private class ZoomableNetworkMapView(
    context: Context,
    @param:DrawableRes private val resourceId: Int
) : View(context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var preview: Bitmap? = null
    private var detail: Bitmap? = null
    private var detailSource = Rect()
    private var imageWidth = 1
    private var imageHeight = 1
    private var centerX = .5f
    private var centerY = .5f
    private var zoom = 1f
    private var requestGeneration = 0
    @Volatile private var detached = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                changeZoom(detector.focusX, detector.focusY, zoom * detector.scaleFactor)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) = requestDetail()
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                val scale = drawingScale()
                centerX += distanceX / scale
                centerY += distanceY / scale
                clampCenter()
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                changeZoom(e.x, e.y, if (zoom < 2f) 3f else 1f)
                requestDetail()
                return true
            }
        })

    init {
        setBackgroundColor(AndroidColor.rgb(246, 247, 249))
        isFocusable = true
        loadPreview()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val base = preview ?: return
        val scale = drawingScale()
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-centerX, -centerY)
        canvas.drawBitmap(base, null, RectF(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat()), paint)
        detail?.let { bitmap ->
            canvas.drawBitmap(bitmap, null, RectF(detailSource), paint)
        }
        canvas.restore()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampCenter()
        requestDetail()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(
            event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
        )
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            requestDetail()
        }
        return true
    }

    fun resetView() {
        zoom = 1f
        centerX = imageWidth / 2f
        centerY = imageHeight / 2f
        clampCenter()
        invalidate()
        requestDetail()
    }

    private fun loadPreview() {
        executor.execute {
            val openedDecoder = resources.openRawResource(resourceId).use {
                BitmapRegionDecoder.newInstance(it, false)
            } ?: return@execute
            val options = BitmapFactory.Options().apply {
                inSampleSize = 4
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = resources.openRawResource(resourceId).use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@execute
            imageWidth = openedDecoder.width
            imageHeight = openedDecoder.height
            openedDecoder.recycle()
            centerX = imageWidth / 2f
            centerY = imageHeight / 2f
            preview = bitmap
            post {
                if (detached) return@post
                invalidate()
                requestDetail()
            }
        }
    }

    private fun changeZoom(focusX: Float, focusY: Float, requestedZoom: Float) {
        if (preview == null) return
        val oldScale = drawingScale()
        val sourceX = centerX + (focusX - width / 2f) / oldScale
        val sourceY = centerY + (focusY - height / 2f) / oldScale
        zoom = requestedZoom.coerceIn(1f, MAX_ZOOM)
        val newScale = drawingScale()
        centerX = sourceX - (focusX - width / 2f) / newScale
        centerY = sourceY - (focusY - height / 2f) / newScale
        clampCenter()
        invalidate()
    }

    private fun fitScale(): Float {
        if (width <= 0 || height <= 0) return 1f
        return min(width.toFloat() / imageWidth, height.toFloat() / imageHeight) * .98f
    }

    private fun drawingScale(): Float = max(.0001f, fitScale() * zoom)

    private fun clampCenter() {
        if (width <= 0 || height <= 0 || imageWidth <= 1 || imageHeight <= 1) return
        val scale = drawingScale()
        val halfW = min(imageWidth / 2f, width / (2f * scale))
        val halfH = min(imageHeight / 2f, height / (2f * scale))
        centerX = centerX.coerceIn(halfW, imageWidth - halfW)
        centerY = centerY.coerceIn(halfH, imageHeight - halfH)
    }

    private fun requestDetail() {
        if (detached || executor.isShutdown) return
        if (preview == null) return
        if (width <= 0 || height <= 0) return
        val scale = drawingScale()
        val marginX = width / scale * .12f
        val marginY = height / scale * .12f
        val source = Rect(
            (centerX - width / (2f * scale) - marginX).toInt().coerceAtLeast(0),
            (centerY - height / (2f * scale) - marginY).toInt().coerceAtLeast(0),
            (centerX + width / (2f * scale) + marginX).toInt().coerceAtMost(imageWidth),
            (centerY + height / (2f * scale) + marginY).toInt().coerceAtMost(imageHeight)
        )
        if (source.width() <= 0 || source.height() <= 0) return
        var sample = 1
        while (sample < 8 && scale * sample * 2 <= 1.15f) sample *= 2
        val generation = ++requestGeneration
        executor.execute {
            if (detached) return@execute
            val bitmap = runCatching {
                val regionDecoder = resources.openRawResource(resourceId).use {
                    BitmapRegionDecoder.newInstance(it, false)
                } ?: return@runCatching null
                try {
                    regionDecoder.decodeRegion(source, BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    })
                } finally {
                    regionDecoder.recycle()
                }
            }.getOrNull() ?: return@execute
            post {
                if (detached) {
                    bitmap.recycle()
                    return@post
                }
                if (generation == requestGeneration) {
                    detail?.takeIf { it !== bitmap }?.recycle()
                    detail = bitmap
                    detailSource = Rect(source)
                    invalidate()
                } else {
                    bitmap.recycle()
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        detached = true
        requestGeneration++
        executor.execute {
            preview?.recycle()
            preview = null
            detail?.recycle()
            detail = null
        }
        executor.shutdown()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MAX_ZOOM = 8f
    }
}

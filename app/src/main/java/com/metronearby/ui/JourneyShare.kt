package com.metronearby.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.metronearby.domain.OfflineRoutePlanner
import com.metronearby.domain.TravelGuide
import com.metronearby.domain.LineVisuals
import java.io.File

/** 仅分享缓存中的单张行程图片，无网络及外部存储权限。 */
object JourneyShare {
    fun createIntent(context: Context, route: OfflineRoutePlanner.RouteResult): Intent {
        val width = 1000
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 40, 50)
            textSize = 34f
        }
        val lines = listOf("Metro Nearby · 离线行程卡") + TravelGuide.cardLines(route)
        val layouts = lines.mapIndexed { index, text ->
            val p = TextPaint(paint).apply { if (index <= 1) { textSize = 42f; isFakeBoldText = true } }
            StaticLayout.Builder.obtain(text, 0, text.length, p, width-112)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).build()
        }
        val height = layouts.sumOf { it.height + 24 } + 140
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(247, 250, 252))
            val bar = android.graphics.Paint()
            route.legs.forEachIndexed { index, leg ->
                bar.color = LineVisuals.parseHexColor(leg.lineColor)?.toArgb() ?: Color.DKGRAY
                val segment = width.toFloat()/route.legs.size
                canvas.drawRect(index*segment, 0f, (index+1)*segment, 28f, bar)
            }
            var y=58f
            layouts.forEach { layout ->
                canvas.save()
                canvas.translate(56f, y)
                layout.draw(canvas)
                canvas.restore()
                y += layout.height+24
            }
            val directory = File(context.cacheDir, "journey_cards").apply { mkdirs() }
            val file = File.createTempFile("journey_", ".png", directory)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            val uri = FileProvider.getUriForFile(context, context.packageName+".journey-files", file)
            return Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("行程卡",uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } finally { bitmap.recycle() }
    }
}

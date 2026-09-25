package com.metronearby.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.metronearby.domain.LineVisuals

/** 每条所属线路占一段，换乘站保留全部线路颜色；外层卡片负责圆角裁切。 */
@Composable
internal fun LineColorStrip(colorHexes: List<String?>) {
    if (colorHexes.isEmpty()) return
    Row(Modifier.fillMaxWidth()) {
        colorHexes.forEach { hex ->
            val color = LineVisuals.parseHexColor(hex)?.let { Color(it.toArgb()) }
                ?: MaterialTheme.colorScheme.primary
            Box(Modifier.weight(1f).height(8.dp).background(color))
        }
    }
}
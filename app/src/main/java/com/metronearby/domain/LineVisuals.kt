package com.metronearby.domain

import kotlin.math.pow

/**
 * 线路视觉相关的纯逻辑：解析 "#A4343A" 这类线路标识色、从线路名里提取徽标要显示的字，
 * 以及按底色自动挑选可读的前景色。
 *
 * 刻意不依赖 android.graphics.Color，保持在纯 JVM 可测的范围内；
 * UI 层拿到 [LineColor.toArgb] 的结果后自行包装成 Compose 的 Color。
 */
object LineVisuals {

    private const val OPAQUE_ALPHA = 0xFF

    /**
     * 前景在底色上要求的最低对比度。
     *
     * 徽标与卡片色带上的文字都是加粗中大字，按 WCAG AA 的大字号门槛取 3:1；
     * 取 3:1 而非 4.5:1，是为了让「底色就用本线主题色、文字尽量用白色」这一口径
     * 尽量成立，只有白色真的压不住对比度的浅色（如 13 号线黄）才退回深色前景。
     */
    const val MIN_CONTRAST = 3.0

    val WHITE = LineColor(0xFF, 0xFF, 0xFF)
    val BLACK = LineColor(0x00, 0x00, 0x00)

    /** 已解析的线路色，分量取值范围均为 0..255。 */
    data class LineColor(val red: Int, val green: Int, val blue: Int, val alpha: Int = OPAQUE_ALPHA) {
        /** 打包成 ARGB int，可直接交给 Compose 的 Color(Int) 构造。 */
        fun toArgb(): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    /**
     * 解析线路标识色，支持 `#RGB`、`#RRGGBB`、`#AARRGGBB`（`#` 可省略）。
     * 输入为 null、空串或格式非法时返回 null，由调用方决定兜底色。
     */
    fun parseHexColor(hex: String?): LineColor? {
        val raw = hex?.trim()?.removePrefix("#") ?: return null
        if (raw.isEmpty() || raw.any { it.digitToIntOrNull(16) == null }) return null
        return when (raw.length) {
            3 -> {
                val r = raw[0].digitToInt(16) * 17
                val g = raw[1].digitToInt(16) * 17
                val b = raw[2].digitToInt(16) * 17
                LineColor(r, g, b)
            }
            6 -> LineColor(
                red = raw.substring(0, 2).toInt(16),
                green = raw.substring(2, 4).toInt(16),
                blue = raw.substring(4, 6).toInt(16)
            )
            8 -> LineColor(
                red = raw.substring(2, 4).toInt(16),
                green = raw.substring(4, 6).toInt(16),
                blue = raw.substring(6, 8).toInt(16),
                alpha = raw.substring(0, 2).toInt(16)
            )
            else -> null
        }
    }

    /**
     * 提取线路徽标文字：优先用线路名里第一段连续阿拉伯数字（"1号线" → "1"、
     * "10号线" → "10"、"北京1号线" → "1"），不含数字时退回首个非空白字符
     * （"八通线" → "八"），全为空白则返回 null。
     */
    fun badgeText(lineName: String?): String? {
        val trimmed = lineName?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val start = trimmed.indexOfFirst { it.isDigit() }
        if (start >= 0) {
            var end = start
            while (end < trimmed.length && trimmed[end].isDigit()) end++
            return trimmed.substring(start, end)
        }
        return trimmed.first().toString()
    }

    /**
     * WCAG 相对亮度，取值 0（纯黑）到 1（纯白）。用于判断底色偏亮还是偏暗。
     */
    fun relativeLuminance(color: LineColor): Double {
        fun channel(value: Int): Double {
            val ratio = value / 255.0
            return if (ratio <= 0.03928) ratio / 12.92 else ((ratio + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    /** 两色之间的 WCAG 对比度，范围 1..21。 */
    fun contrastRatio(first: LineColor, second: LineColor): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /**
     * 给定线路色底，返回其上的可读前景色。
     *
     * 默认用白色（与「深色线路色 + 白字」的视觉口径一致）；当白色对比度不足
     * [MIN_CONTRAST] 时退回黑色——否则 13 号线那种明黄底会几乎看不清字。
     */
    fun foregroundOn(background: LineColor): LineColor =
        if (contrastRatio(WHITE, background) >= MIN_CONTRAST) WHITE else BLACK

    /**
     * 把线路色整理成「画在浅色卡片底上的文字色」。
     *
     * 首班高亮是线路色文字 + 卡片浅色底，浅色线路（13 号线黄、7 号线浅橙等）
     * 直接用会糊掉，因此逐档压暗到与白色底达成 [MIN_CONTRAST]；本身够深的原样返回。
     */
    fun readableOnLightSurface(
        color: LineColor,
        surface: LineColor = WHITE,
        minContrast: Double = MIN_CONTRAST
    ): LineColor {
        if (contrastRatio(color, surface) >= minContrast) return color

        var red = color.red
        var green = color.green
        var blue = color.blue
        repeat(MAX_DARKEN_STEPS) {
            red = (red * DARKEN_FACTOR).toInt()
            green = (green * DARKEN_FACTOR).toInt()
            blue = (blue * DARKEN_FACTOR).toInt()
            val candidate = LineColor(red, green, blue, color.alpha)
            if (contrastRatio(candidate, surface) >= minContrast) return candidate
        }
        return LineColor(red, green, blue, color.alpha)
    }

    private const val DARKEN_FACTOR = 0.9
    private const val MAX_DARKEN_STEPS = 40
}

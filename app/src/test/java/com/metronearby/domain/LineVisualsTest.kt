package com.metronearby.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineVisualsTest {

    @Test
    fun `解析六位十六进制颜色`() {
        val color = LineVisuals.parseHexColor("#A4343A")
        assertEquals(LineVisuals.LineColor(0xA4, 0x34, 0x3A), color)
        assertEquals(0xFFA4343A.toInt(), color!!.toArgb())
    }

    @Test
    fun `hex 前缀可省略且大小写不敏感`() {
        assertEquals(LineVisuals.LineColor(0xA4, 0x34, 0x3A), LineVisuals.parseHexColor("a4343a"))
    }

    @Test
    fun `解析三位简写颜色`() {
        assertEquals(LineVisuals.LineColor(0xFF, 0x00, 0x00), LineVisuals.parseHexColor("#F00"))
        assertEquals(LineVisuals.LineColor(0xAA, 0xBB, 0xCC), LineVisuals.parseHexColor("#abc"))
    }

    @Test
    fun `解析带透明度颜色`() {
        val color = LineVisuals.parseHexColor("#80A4343A")
        assertEquals(0x80, color!!.alpha)
        assertEquals(0x80A4343A.toInt(), color.toArgb())
    }

    @Test
    fun `非法颜色返回 null`() {
        assertNull(LineVisuals.parseHexColor(null))
        assertNull(LineVisuals.parseHexColor(""))
        assertNull(LineVisuals.parseHexColor("   "))
        assertNull(LineVisuals.parseHexColor("#12345"))
        assertNull(LineVisuals.parseHexColor("#GGGGGG"))
    }

    @Test
    fun `提取线路徽标数字`() {
        assertEquals("1", LineVisuals.badgeText("1号线"))
        assertEquals("10", LineVisuals.badgeText("10号线"))
        assertEquals("13", LineVisuals.badgeText("  13号线 "))
    }

    @Test
    fun `数字不在开头时仍能提取`() {
        assertEquals("1", LineVisuals.badgeText("北京1号线"))
    }

    @Test
    fun `无数字线路名退回首字`() {
        assertEquals("八", LineVisuals.badgeText("八通线"))
    }

    @Test
    fun `空线路名返回 null`() {
        assertNull(LineVisuals.badgeText(null))
        assertNull(LineVisuals.badgeText("   "))
    }

    // ------------------------------------------------------------------
    // 前景色自适应：每条线用自己的主题色，但文字必须始终可读
    // ------------------------------------------------------------------

    @Test
    fun `深色线路色上用白字`() {
        // 1 号线红、2 号线蓝、15 号线紫都属于深色底，沿用「底色 + 白字」口径
        listOf("#A4343A", "#004B87", "#653279").forEach { hex ->
            val color = LineVisuals.parseHexColor(hex)!!
            assertEquals("$hex 应使用白字", LineVisuals.WHITE, LineVisuals.foregroundOn(color))
        }
    }

    @Test
    fun `浅色线路色上改用黑字`() {
        // 13 号线明黄、7 号线浅橙、19 号线浅粉：白字对比度不足，必须退回黑字
        listOf("#F4DA40", "#FFC56E", "#D3A3C9", "#CA9A8E").forEach { hex ->
            val color = LineVisuals.parseHexColor(hex)!!
            assertEquals("$hex 应使用黑字", LineVisuals.BLACK, LineVisuals.foregroundOn(color))
        }
    }

    @Test
    fun `自适应前景始终满足最低对比度`() {
        val samples = listOf(
            "#A4343A", "#004B87", "#D90627", "#008C95", "#AA0061", "#B58500",
            "#FFC56E", "#009B77", "#97D700", "#0092BC", "#FF8674", "#9C4F01",
            "#F4DA40", "#CA9A8E", "#653279", "#6BA539", "#00ABAB", "#D3A3C9",
            "#D0006F", "#D86018", "#A45A2A", "#D986BA", "#D22630", "#A192B2", "#0049A5"
        )
        samples.forEach { hex ->
            val color = LineVisuals.parseHexColor(hex)!!
            val foreground = LineVisuals.foregroundOn(color)
            val contrast = LineVisuals.contrastRatio(foreground, color)
            assertTrue(
                "$hex 的前景对比度应达到 ${LineVisuals.MIN_CONTRAST}，实际 $contrast",
                contrast >= LineVisuals.MIN_CONTRAST
            )
        }
    }

    @Test
    fun `对比度取值范围与对称性`() {
        assertEquals(21.0, LineVisuals.contrastRatio(LineVisuals.WHITE, LineVisuals.BLACK), 0.01)
        assertEquals(1.0, LineVisuals.contrastRatio(LineVisuals.WHITE, LineVisuals.WHITE), 0.01)
        val red = LineVisuals.parseHexColor("#A4343A")!!
        assertEquals(
            LineVisuals.contrastRatio(red, LineVisuals.WHITE),
            LineVisuals.contrastRatio(LineVisuals.WHITE, red),
            0.0001
        )
    }

    @Test
    fun `深色线路色作浅底文字时保持原样`() {
        val red = LineVisuals.parseHexColor("#A4343A")!!
        assertEquals(red, LineVisuals.readableOnLightSurface(red))
    }

    @Test
    fun `浅色线路色作浅底文字时被压暗到可读`() {
        val yellow = LineVisuals.parseHexColor("#F4DA40")!!
        val readable = LineVisuals.readableOnLightSurface(yellow)
        assertTrue(
            "压暗后应达到最低对比度",
            LineVisuals.contrastRatio(readable, LineVisuals.WHITE) >= LineVisuals.MIN_CONTRAST
        )
        assertTrue(
            "压暗后应比原色更暗",
            LineVisuals.relativeLuminance(readable) < LineVisuals.relativeLuminance(yellow)
        )
    }

    @Test
    fun `相对亮度范围符合黑白两端`() {
        assertEquals(0.0, LineVisuals.relativeLuminance(LineVisuals.BLACK), 0.0001)
        assertEquals(1.0, LineVisuals.relativeLuminance(LineVisuals.WHITE), 0.0001)
    }
}
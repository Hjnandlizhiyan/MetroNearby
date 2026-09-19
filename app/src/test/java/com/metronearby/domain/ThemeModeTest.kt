package com.metronearby.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {

    @Test
    fun `未设置过时默认跟随系统`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.DEFAULT)
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageKey(null))
    }

    @Test
    fun `按持久化键还原各模式`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageKey("system"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStorageKey("light"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorageKey("dark"))
    }

    @Test
    fun `容忍大小写与首尾空白`() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorageKey("  DARK "))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStorageKey("Light"))
    }

    @Test
    fun `未知或空值回落到默认`() {
        assertEquals(ThemeMode.DEFAULT, ThemeMode.fromStorageKey(""))
        assertEquals(ThemeMode.DEFAULT, ThemeMode.fromStorageKey("   "))
        assertEquals(ThemeMode.DEFAULT, ThemeMode.fromStorageKey("blue"))
    }

    @Test
    fun `跟随系统模式随系统明暗切换`() {
        assertTrue(ThemeMode.SYSTEM.resolveDarkTheme(systemInDarkTheme = true))
        assertFalse(ThemeMode.SYSTEM.resolveDarkTheme(systemInDarkTheme = false))
    }

    @Test
    fun `固定模式忽略系统明暗`() {
        assertFalse(ThemeMode.LIGHT.resolveDarkTheme(systemInDarkTheme = true))
        assertFalse(ThemeMode.LIGHT.resolveDarkTheme(systemInDarkTheme = false))
        assertTrue(ThemeMode.DARK.resolveDarkTheme(systemInDarkTheme = true))
        assertTrue(ThemeMode.DARK.resolveDarkTheme(systemInDarkTheme = false))
    }

    @Test
    fun `每个模式都有唯一且稳定的持久化键`() {
        val keys = ThemeMode.entries.map { it.storageKey }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(listOf("system", "light", "dark"), keys)

        // 键必须能原样还原，否则持久化后会读不回来
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromStorageKey(mode.storageKey))
        }
    }

    @Test
    fun `每个模式都有界面展示名`() {
        assertTrue(ThemeMode.entries.all { it.displayName.isNotBlank() })
    }
}
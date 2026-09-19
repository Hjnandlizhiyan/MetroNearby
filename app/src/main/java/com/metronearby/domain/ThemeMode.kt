package com.metronearby.domain

/**
 * 应用主题模式：跟随系统 / 固定浅色 / 固定深色。
 *
 * 「持久化字符串 ↔ 枚举」的解析与「最终是否深色」的判定都是纯逻辑，刻意留在
 * domain 层以便纯 JVM 单测覆盖；Android 侧的读写交给 SettingsStore 的实现。
 */
enum class ThemeMode(val storageKey: String, val displayName: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    /**
     * 结合系统当前是否处于深色，得出本模式最终是否使用深色主题。
     * 固定模式忽略系统取值，只有 [SYSTEM] 才跟随。
     */
    fun resolveDarkTheme(systemInDarkTheme: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDarkTheme
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** 缺省口径：用户没设置过时跟随系统。 */
        val DEFAULT: ThemeMode = SYSTEM

        /**
         * 从持久化字符串还原。
         *
         * 未知值、null、大小写不一致或带多余空白，一律回落到 [DEFAULT]，
         * 保证历史脏数据不会让 App 起不来。
         */
        fun fromStorageKey(key: String?): ThemeMode {
            val normalized = key?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.storageKey == normalized } ?: DEFAULT
        }
    }
}
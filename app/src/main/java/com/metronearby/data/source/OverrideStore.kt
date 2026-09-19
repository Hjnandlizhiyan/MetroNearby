package com.metronearby.data.source

import com.metronearby.data.MetroJson
import com.metronearby.data.model.UserOverrides
import java.io.File

/**
 * 用户修正数据的读写。生产环境指向 filesDir/user_overrides.json，
 * 单元测试指向临时文件。
 */
interface OverrideStore {
    fun load(): UserOverrides?
    fun save(overrides: UserOverrides)
}

class FileOverrideStore(private val file: File) : OverrideStore {

    override fun load(): UserOverrides? {
        if (!file.exists()) return null
        return runCatching { MetroJson.parseOverrides(file.readText()) }.getOrNull()
    }

    override fun save(overrides: UserOverrides) {
        file.parentFile?.mkdirs()
        file.writeText(MetroJson.encodeOverrides(overrides))
    }
}
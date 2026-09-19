package com.metronearby.data.source

import android.content.Context

/**
 * 从 Android assets 读取内置线路数据。
 */
class AssetMetroDataSource(private val context: Context) : MetroDataSource {

    override fun readText(path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
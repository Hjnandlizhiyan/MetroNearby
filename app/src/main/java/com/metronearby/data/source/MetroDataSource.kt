package com.metronearby.data.source

/**
 * 数据读取的抽象。生产环境从 Android assets 读，单元测试用内存实现，
 * 这样数据层可以在纯 JVM 下被测到。
 */
interface MetroDataSource {
    fun readText(path: String): String
}

class InMemoryMetroDataSource(private val files: Map<String, String>) : MetroDataSource {
    override fun readText(path: String): String =
        files[path] ?: throw IllegalArgumentException("数据文件不存在: $path")
}
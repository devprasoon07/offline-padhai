package com.devprasoon.offlinepadhai

import android.util.LruCache

object OcrCacheManager {
    private val cache = LruCache<String, String>(50)

    fun get(key: String): String? = cache.get(key)
    fun put(key: String, result: String) {
        if (key.isNotBlank()) cache.put(key, result)
    }
}

package com.abcccc.maimaiqueue

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object PlayerAvatarImageLoader {
    private const val MAX_AVATAR_BYTES = 1024 * 1024
    private const val CONNECT_TIMEOUT_MILLIS = 4_000
    private const val READ_TIMEOUT_MILLIS = 6_000
    private val memoryCache = LruCache<String, ImageBitmap>(32)

    fun cached(reference: String?): ImageBitmap? =
        normalizePlayerAvatarReference(reference)?.let(memoryCache::get)

    suspend fun load(reference: String?): ImageBitmap? {
        val normalized = normalizePlayerAvatarReference(reference) ?: return null
        memoryCache.get(normalized)?.let { return it }
        return withContext(Dispatchers.IO) {
            memoryCache.get(normalized) ?: download(normalized)?.also { bitmap ->
                memoryCache.put(normalized, bitmap)
            }
        }
    }

    private fun download(reference: String): ImageBitmap? {
        val connection = (URL(reference).openConnection() as? HttpURLConnection)
            ?: return null
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "image/webp,image/*;q=0.8")
            if (connection.responseCode !in 200..299) return null
            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_AVATAR_BYTES) return null
            val bytes = connection.inputStream.use(::readLimited) ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.width > 2_048 || bitmap.height > 2_048) {
                bitmap.recycle()
                return null
            }
            bitmap.asImageBitmap()
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_AVATAR_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

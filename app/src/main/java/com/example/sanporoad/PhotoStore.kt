package com.example.sanporoad

import android.content.Context
import android.net.Uri
import java.io.File

object PhotoStore {
    fun copyIntoAppStorage(context: Context, source: Uri, routeId: Long): String? = runCatching {
        val mime = context.contentResolver.getType(source).orEmpty()
        val ext = when {
            mime.contains("png", ignoreCase = true) -> "png"
            mime.contains("webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }
        val dir = File(context.filesDir, "walk_photos").apply { mkdirs() }
        val target = File(dir, "$routeId.$ext")
        context.contentResolver.openInputStream(source)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target.absolutePath
    }.getOrNull()

    fun localFile(path: String?): File? = path
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.takeIf { it.exists() && it.isFile }
}

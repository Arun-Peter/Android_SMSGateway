package com.r2r.smsgateway

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log

/**
 * Replies as small files in Download/SmsGateway/replies/, for callers whose `am` does not wait for
 * the broadcast result (Termux). Only written for requests with the right token; files older than
 * 10 minutes are removed.
 */
object ReplyFiles {
    const val FOLDER = "Download/SmsGateway/replies/"
    private const val KEEP_SECONDS = 600

    fun write(context: Context, name: String, json: String) {
        val resolver = context.contentResolver
        try {
            prune(context)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.json")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            Log.e(StatusReceiver.TAG, "could not write reply file $name", e)
        }
    }

    /** Removes this app's reply files older than [KEEP_SECONDS] (the caller normally deletes them sooner). */
    private fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() / 1000 - KEEP_SECONDS
        context.contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DATE_ADDED} < ?",
            arrayOf(FOLDER, cutoff.toString()),
        )
    }
}

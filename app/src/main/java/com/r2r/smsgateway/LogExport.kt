package com.r2r.smsgateway

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exports the message log with the carrier details to Download/SmsGateway/ as a CSV (opens in Excel). */
object LogExport {
    private const val FOLDER = "Download/SmsGateway/"
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) // so Excel reads UTF-8

    val HEADER = listOf(
        "Id", "To", "Sub", "Status", "Parts", "Queued", "Sent", "SentBy", "Delivered", "DeliveredBy", "Error",
        "Part", "CarrierRef", "PartSent", "PartSentBy", "PartDelivered", "PartDeliveredBy", "CarrierStatus", "Text",
    )

    /** One row per part, every time from its most authentic source (see [Timelines]) with that source named. */
    fun rows(messages: List<Message>, timelineOf: (Message) -> Timeline): List<List<String>> = messages.flatMap { m ->
        val t = timelineOf(m)
        val base = listOf(
            m.id, m.to, m.sub.toString(), m.state.name, m.parts.toString(), Message.stamp(m.queuedAt),
            time(t.sent), by(t.sent), time(t.delivered), by(t.delivered), m.error.orEmpty(),
        )
        t.parts.map { p ->
            base + listOf(
                (p.part + 1).toString(), p.ref?.toString().orEmpty(),
                time(p.sent), by(p.sent), time(p.delivered), by(p.delivered),
                p.tpStatus?.let { "0x%02X".format(Locale.US, it) }.orEmpty(),
            ) + m.text
        }
    }

    private fun time(s: Stamp?) = s?.let { Message.stamp(it.ms) }.orEmpty()
    private fun by(s: Stamp?) = s?.by?.label.orEmpty()

    fun csv(rows: List<List<String>>): String = buildString {
        (listOf(HEADER) + rows).forEach { row -> append(row.joinToString(",") { escape(it) }).append("\r\n") }
    }

    fun escape(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** Writes the whole log; returns the file name. */
    fun write(context: Context): String {
        val store = Store.of(context)
        val rows = rows(store.recent(Int.MAX_VALUE)) { store.timeline(it) }
        val name = "SmsGateway_log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IOException("could not create the file")
        try {
            (resolver.openOutputStream(uri) ?: throw IOException("could not write the file")).use { out ->
                out.write(BOM)
                out.write(csv(rows).toByteArray(Charsets.UTF_8))
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return FOLDER + name
    }
}

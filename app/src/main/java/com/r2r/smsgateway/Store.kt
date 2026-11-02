package com.r2r.smsgateway

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One SMS sent through the gateway and what is known about it. */
data class Message(
    val id: String,
    val to: String,
    val text: String,
    val sub: Int,              // SIM subscription id it was sent from
    val parts: Int,
    val sentMask: Long,        // bit n set = part n was sent
    val deliveredMask: Long,   // bit n set = part n was delivered
    val state: State,
    val error: String?,
    val queuedAt: Long,
    val sentAt: Long?,         // phone time all parts were sent (fallback when no part times are known)
    val deliveredAt: Long?,    // phone time the last delivery report arrived (same)
    val updatedAt: Long,
) {
    companion object {
        /** "2026-10-25 14:00:33 +05:30" in the phone's time zone. */
        fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.US).format(Date(ms))
    }
}

/** One delivery report received for one part of a message. */
data class Report(
    val id: String,
    val part: Int,
    val receivedAt: Long,        // when the phone received the report
    val outcome: Delivery,
    val format: String?,         // "3gpp" (GSM / LTE) or "3gpp2" (CDMA)
    val carrier: CarrierReport?, // decoded details; null if the report could not be decoded
)

/** The message log, kept in the app's private storage. Ids are matched ignoring case. */
class Store(context: Context) : SQLiteOpenHelper(context.applicationContext, "gateway.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages (
                id TEXT PRIMARY KEY, dest TEXT NOT NULL, body TEXT NOT NULL, sub INTEGER NOT NULL,
                parts INTEGER NOT NULL, sent_mask INTEGER NOT NULL DEFAULT 0, delivered_mask INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL, error TEXT, queued_at INTEGER NOT NULL, sent_at INTEGER, delivered_at INTEGER,
                updated_at INTEGER NOT NULL)"""
        )
        createReports(db)
        createPartSends(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createReports(db)
        } else if (oldVersion < 3) {
            // Version 2 kept the carrier times as text; convert them to exact instants.
            db.execSQL("ALTER TABLE reports ADD COLUMN centre_ms INTEGER")
            db.execSQL("ALTER TABLE reports ADD COLUMN delivery_ms INTEGER")
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.US)
            db.rawQuery("SELECT rowid, centre_time, delivery_time FROM reports", null).use { c ->
                while (c.moveToNext()) {
                    val centre = c.getString(1)?.let { runCatching { format.parse(it)?.time }.getOrNull() }
                    val delivery = c.getString(2)?.let { runCatching { format.parse(it)?.time }.getOrNull() }
                    db.update("reports", ContentValues().apply { put("centre_ms", centre); put("delivery_ms", delivery) },
                        "rowid = ?", arrayOf(c.getLong(0).toString()))
                }
            }
        }
        if (oldVersion < 3) createPartSends(db)
    }

    /** Every delivery report received (a part can get "still trying" reports before the final one). */
    private fun createReports(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE reports (
                msg_id TEXT NOT NULL, part INTEGER NOT NULL, received_at INTEGER NOT NULL, outcome TEXT NOT NULL,
                format TEXT, ref INTEGER, centre_ms INTEGER, delivery_ms INTEGER, tp_status INTEGER, recipient TEXT)"""
        )
    }

    /** The phone's "part sent" callbacks: the sent time of a part when the carrier reports none. */
    private fun createPartSends(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE part_sends (
                msg_id TEXT NOT NULL, part INTEGER NOT NULL, at INTEGER NOT NULL, ok INTEGER NOT NULL, error TEXT)"""
        )
    }

    fun exists(id: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM messages WHERE id = ? COLLATE NOCASE", arrayOf(id)).use { it.moveToFirst() }

    fun insert(m: Message) {
        writableDatabase.insertOrThrow("messages", null, ContentValues().apply {
            put("id", m.id); put("dest", m.to); put("body", m.text); put("sub", m.sub); put("parts", m.parts)
            put("state", m.state.name); put("queued_at", m.queuedAt); put("updated_at", m.updatedAt)
        })
    }

    fun get(id: String): Message? =
        readableDatabase.rawQuery("SELECT * FROM messages WHERE id = ? COLLATE NOCASE", arrayOf(id)).use { c ->
            if (c.moveToFirst()) read(c) else null
        }

    fun addPartSend(s: PartSend) {
        writableDatabase.insert("part_sends", null, ContentValues().apply {
            put("msg_id", s.id); put("part", s.part); put("at", s.at); put("ok", if (s.ok) 1 else 0); put("error", s.error)
        })
    }

    fun partSends(id: String): List<PartSend> =
        readableDatabase.rawQuery("SELECT * FROM part_sends WHERE msg_id = ? COLLATE NOCASE ORDER BY part, at", arrayOf(id)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val e = c.getColumnIndexOrThrow("error")
                    add(PartSend(
                        id = c.getString(c.getColumnIndexOrThrow("msg_id")), part = c.getInt(c.getColumnIndexOrThrow("part")),
                        at = c.getLong(c.getColumnIndexOrThrow("at")), ok = c.getInt(c.getColumnIndexOrThrow("ok")) == 1,
                        error = if (c.isNull(e)) null else c.getString(e),
                    ))
                }
            }
        }

    fun addReport(r: Report) {
        writableDatabase.insert("reports", null, ContentValues().apply {
            put("msg_id", r.id); put("part", r.part); put("received_at", r.receivedAt); put("outcome", r.outcome.name)
            put("format", r.format)
            r.carrier?.let {
                put("ref", it.reference); put("centre_ms", it.centreMs); put("delivery_ms", it.deliveryMs)
                put("tp_status", it.tpStatus); put("recipient", it.recipient)
            }
        })
    }

    /** Reports for one message, by part and arrival. */
    fun reports(id: String): List<Report> =
        readableDatabase.rawQuery(
            "SELECT * FROM reports WHERE msg_id = ? COLLATE NOCASE ORDER BY part, received_at", arrayOf(id),
        ).use { c -> buildList { while (c.moveToNext()) add(readReport(c)) } }

    private fun readReport(c: Cursor): Report {
        fun col(name: String) = c.getColumnIndexOrThrow(name)
        val decoded = !c.isNull(col("ref")) && !c.isNull(col("centre_ms")) && !c.isNull(col("delivery_ms"))
        val carrier = if (!decoded) null else CarrierReport(
            reference = c.getInt(col("ref")), recipient = c.getString(col("recipient")).orEmpty(),
            centreMs = c.getLong(col("centre_ms")), deliveryMs = c.getLong(col("delivery_ms")),
            tpStatus = c.getInt(col("tp_status")),
        )
        return Report(
            id = c.getString(col("msg_id")), part = c.getInt(col("part")), receivedAt = c.getLong(col("received_at")),
            outcome = Delivery.valueOf(c.getString(col("outcome"))),
            format = if (c.isNull(col("format"))) null else c.getString(col("format")), carrier = carrier,
        )
    }

    /** The timeline of one message (times from the best source, see [Timelines]). */
    fun timeline(m: Message): Timeline = Timelines.build(m, partSends(m.id), reports(m.id))

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM messages", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * Deletes the whole log (messages, delivery reports, sent callbacks). Reports that arrive later for a
     * deleted message are ignored. Returns how many messages were deleted.
     */
    fun clear(): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val n = db.delete("messages", null, null)
            db.delete("reports", null, null)
            db.delete("part_sends", null, null)
            db.setTransactionSuccessful()
            return n
        } finally {
            db.endTransaction()
        }
    }

    /** Newest first. */
    fun recent(limit: Int): List<Message> =
        readableDatabase.rawQuery("SELECT * FROM messages ORDER BY queued_at DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }

    /**
     * Applies a callback for one part and returns the message before and after, so the caller can
     * tell whether its state changed. Reports for a message that already failed are ignored.
     */
    fun update(id: String, change: (Message) -> Message): Pair<Message, Message>? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val before = get(id) ?: return null
            if (before.state == State.FAILED) return before to before
            val after = change(before).copy(updatedAt = System.currentTimeMillis())
            db.update("messages", ContentValues().apply {
                put("sent_mask", after.sentMask); put("delivered_mask", after.deliveredMask)
                put("state", after.state.name); put("error", after.error)
                put("sent_at", after.sentAt); put("delivered_at", after.deliveredAt); put("updated_at", after.updatedAt)
            }, "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
            return before to after
        } finally {
            db.endTransaction()
        }
    }

    private fun read(c: Cursor): Message {
        fun s(col: String) = c.getString(c.getColumnIndexOrThrow(col))
        fun l(col: String) = c.getLong(c.getColumnIndexOrThrow(col))
        fun ln(col: String) = c.getColumnIndexOrThrow(col).let { if (c.isNull(it)) null else c.getLong(it) }
        return Message(
            id = s("id"), to = s("dest"), text = s("body"), sub = l("sub").toInt(), parts = l("parts").toInt(),
            sentMask = l("sent_mask"), deliveredMask = l("delivered_mask"), state = State.valueOf(s("state")),
            error = c.getColumnIndexOrThrow("error").let { if (c.isNull(it)) null else c.getString(it) },
            queuedAt = l("queued_at"), sentAt = ln("sent_at"), deliveredAt = ln("delivered_at"), updatedAt = l("updated_at"),
        )
    }

    companion object {
        @Volatile private var instance: Store? = null
        fun of(context: Context): Store = instance ?: synchronized(this) { instance ?: Store(context).also { instance = it } }
    }
}

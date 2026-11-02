package com.r2r.smsgateway

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where a time comes from: the carrier's report (preferred) or the phone's own clock. */
enum class Source(val label: String) { CARRIER("carrier"), PHONE("phone") }

data class Stamp(val ms: Long, val by: Source)

/** The phone's "part sent" callback for one part (its only record of sending). */
data class PartSend(val id: String, val part: Int, val at: Long, val ok: Boolean, val error: String?)

data class PartTimes(
    val part: Int,              // 0-based
    val ref: Int?,              // carrier reference (TP-MR)
    val sent: Stamp?,
    val delivered: Stamp?,
    val failed: Stamp?,
    val error: String?,
    val tpStatus: Int?,
    val state: State,
)

data class Timeline(
    val parts: List<PartTimes>,
    val sent: Stamp?,
    val delivered: Stamp?,
    val failed: Stamp?,
    val lines: List<String>,    // "17:36:46  LONG02  QUEUED", in time order
)

/**
 * One time per event, from the most authentic source: the carrier's message-centre time for "sent" and
 * its delivery time for "delivered"; the phone's time of the callback / report when the carrier gave none.
 */
object Timelines {

    fun build(m: Message, sends: List<PartSend>, reports: List<Report>): Timeline {
        val parts = (0 until m.parts).map { p -> part(m, p, sends.filter { it.part == p }, reports.filter { it.part == p }) }
        val sent = if (parts.all { it.sent != null }) parts.mapNotNull { it.sent }.maxByOrNull { it.ms } else null
        val delivered = if (m.state == State.DELIVERED) {
            parts.mapNotNull { it.delivered }.maxByOrNull { it.ms } ?: m.deliveredAt?.let { Stamp(it, Source.PHONE) }
        } else null
        val failed = if (m.state == State.FAILED) {
            parts.mapNotNull { it.failed }.maxByOrNull { it.ms } ?: Stamp(m.updatedAt, Source.PHONE)
        } else null

        // (time, order for equal times, text)
        val events = mutableListOf(Triple(m.queuedAt, 0, "QUEUED"))
        val many = m.parts > 1
        if (many) parts.forEach { pt ->     // a single-part message needs no part lines
            val label = "part ${pt.part + 1}/${m.parts}"
            pt.sent?.let { events += Triple(it.ms, 1, "$label sent  (${it.by.label}${pt.ref?.let { r -> " ref $r" } ?: ""})") }
            pt.delivered?.let { events += Triple(it.ms, 3, "$label delivered  (${it.by.label})") }
            pt.failed?.let { events += Triple(it.ms, 5, "$label FAILED  (${pt.error ?: it.by.label})") }
        }
        sent?.let { s ->
            val ref = if (many) "" else parts.first().ref?.let { " ref $it" } ?: ""
            events += Triple(s.ms, 2, "SENT  (${s.by.label}$ref)")
        }
        delivered?.let { events += Triple(it.ms, 4, "DELIVERED  (${it.by.label})") }
        failed?.let { events += Triple(it.ms, 6, "FAILED  (${m.error ?: it.by.label})") }

        val lines = events.sortedWith(compareBy({ it.first }, { it.second }))
            .map { (ms, _, text) -> clean("${clock(ms)}  ${m.id}  $text") }
        return Timeline(parts, sent, delivered, failed, lines)
    }

    private fun part(m: Message, p: Int, sends: List<PartSend>, reports: List<Report>): PartTimes {
        val bit = 1L shl p
        val final = reports.lastOrNull { it.outcome != Delivery.PENDING }
        val carrier = (final ?: reports.lastOrNull())?.carrier
        val send = sends.lastOrNull()
        val sentOk = m.sentMask and bit != 0L
        val deliveredOk = m.deliveredMask and bit != 0L

        val sent = when {
            carrier != null -> Stamp(carrier.centreMs, Source.CARRIER)
            send?.ok == true -> Stamp(send.at, Source.PHONE)
            sentOk -> m.sentAt?.let { Stamp(it, Source.PHONE) } // logs from before part times were kept
            else -> null
        }
        val delivered = when {
            !deliveredOk -> null
            final?.outcome == Delivery.DELIVERED && final.carrier != null -> Stamp(final.carrier.deliveryMs, Source.CARRIER)
            final?.outcome == Delivery.DELIVERED -> Stamp(final.receivedAt, Source.PHONE)
            else -> m.deliveredAt?.let { Stamp(it, Source.PHONE) }
        }
        var error: String? = null
        val failed = when {
            send?.ok == false -> { error = send.error; Stamp(send.at, Source.PHONE) }
            final?.outcome == Delivery.FAILED -> {
                error = "not delivered" + (final.carrier?.let { " carrier status 0x%02X".format(Locale.US, it.tpStatus) } ?: "")
                final.carrier?.let { Stamp(it.deliveryMs, Source.CARRIER) } ?: Stamp(final.receivedAt, Source.PHONE)
            }
            else -> null
        }
        val state = when {
            failed != null -> State.FAILED
            delivered != null -> State.DELIVERED
            sent != null -> State.SENT
            else -> State.QUEUED
        }
        return PartTimes(p, carrier?.reference, sent, delivered, failed, error, carrier?.tpStatus, state)
    }

    private fun clock(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

    /** Timeline text is read by the shell script, which splits on ";" and stops at quotes, commas or braces. */
    private fun clean(s: String) = s.replace(Regex("[\";,{}]"), " ")
}

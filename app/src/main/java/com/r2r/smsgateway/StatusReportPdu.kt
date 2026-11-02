package com.r2r.smsgateway

import java.time.LocalDateTime
import java.time.ZoneOffset

/** What the carrier's delivery report (GSM / LTE "SMS-STATUS-REPORT") says about one part. */
data class CarrierReport(
    val reference: Int,       // TP-MR: 0–255, set by the phone per SIM; links the report to the sent part (wraps around)
    val recipient: String,    // TP-RA: the number the part was delivered to
    val centreMs: Long,       // TP-SCTS: when the carrier's message centre accepted the part (epoch ms)
    val deliveryMs: Long,     // TP-DT: when the part reached the recipient, or the final attempt if it failed (epoch ms)
    val tpStatus: Int,        // TP-ST: 0x00–0x1F delivered, 0x20–0x3F still trying, 0x40+ failed
)

/**
 * Decodes the raw delivery report (3GPP TS 23.040, SMS-STATUS-REPORT). Android only offers the status
 * itself, not the reference or the delivery time, so the fields are read from the PDU here.
 */
object StatusReportPdu {

    /** The PDU normally starts with the message-centre address; both layouts are tried. */
    fun parse(pdu: ByteArray?): CarrierReport? {
        if (pdu == null || pdu.isEmpty()) return null
        return parseAt(pdu, 1 + (pdu[0].toInt() and 0xff)) ?: parseAt(pdu, 0)
    }

    private fun parseAt(p: ByteArray, start: Int): CarrierReport? = try {
        var i = start
        fun u(k: Int) = p[k].toInt() and 0xff
        val firstOctet = u(i++)
        if (firstOctet and 0x03 != 0x02) null // not a status report at this offset
        else {
            val reference = u(i++)
            val digits = u(i++)
            val typeOfAddress = u(i++)
            val octets = (digits + 1) / 2
            val number = semiOctets(p, i, octets).take(digits); i += octets
            val centre = time(p, i); i += 7
            val delivery = time(p, i); i += 7
            val status = u(i)
            if (centre == null || delivery == null) null
            else CarrierReport(reference, (if (typeOfAddress and 0x70 == 0x10) "+" else "") + number, centre, delivery, status)
        }
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    /** Swapped-nibble BCD digits ("19 99" -> "9199"); the filler F is dropped. */
    private fun semiOctets(p: ByteArray, from: Int, count: Int): String = buildString {
        for (k in from until from + count) {
            val b = p[k].toInt() and 0xff
            listOf(b and 0x0f, b shr 4).forEach { if (it < 10) append(it) }
        }
    }

    /** 7-octet timestamp with its time zone -> epoch milliseconds; null if it is not a valid time. */
    private fun time(p: ByteArray, at: Int): Long? {
        fun d(k: Int): Int { val b = p[at + k].toInt() and 0xff; return (b and 0x0f) * 10 + (b shr 4) }
        val (yy, mo, dd, hh, mi, ss) = listOf(d(0), d(1), d(2), d(3), d(4), d(5))
        if (mo !in 1..12 || dd !in 1..31 || hh > 23 || mi > 59 || ss > 59) return null
        val tz = p[at + 6].toInt() and 0xff
        val quarters = (tz and 0x07) * 10 + (tz shr 4)
        val offsetSeconds = quarters * 15 * 60 * (if (tz and 0x08 != 0) -1 else 1)
        return runCatching {
            LocalDateTime.of(2000 + yy, mo, dd, hh, mi, ss).toInstant(ZoneOffset.ofTotalSeconds(offsetSeconds)).toEpochMilli()
        }.getOrNull()
    }

    private operator fun <T> List<T>.component6(): T = this[5]
}

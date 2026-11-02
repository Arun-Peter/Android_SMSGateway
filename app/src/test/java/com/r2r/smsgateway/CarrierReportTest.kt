package com.r2r.smsgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class CarrierReportTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    // SMS-STATUS-REPORT for +910000000000: reference 42, accepted 2026-10-25 16:13:38 +05:30,
    // delivered 16:13:39, status 0 (delivered).
    private val report = bytes(0x2A, 0x0C, 0x91, 0x19, 0x00, 0x00, 0x00, 0x00, 0x00) + // reference, recipient
        bytes(0x62, 0x01, 0x52, 0x61, 0x31, 0x83, 0x22) +                              // message-centre time
        bytes(0x62, 0x01, 0x52, 0x61, 0x31, 0x93, 0x22) +                              // delivery time
        bytes(0x00)                                                                     // status
    private val smsc = bytes(0x07, 0x91, 0x19, 0x00, 0x00, 0x00, 0x00, 0xF0)            // message-centre address

    @Test fun decodesReportWithMessageCentreAddress() {
        val r = StatusReportPdu.parse(smsc + bytes(0x06) + report)!!
        assertEquals(42, r.reference)
        assertEquals("+910000000000", r.recipient)
        assertEquals(ms("2026-10-25T16:13:38+05:30"), r.centreMs)
        assertEquals(ms("2026-10-25T16:13:39+05:30"), r.deliveryMs)
        assertEquals(0, r.tpStatus)
    }

    @Test fun decodesReportWithoutMessageCentreAddress() {
        assertEquals(42, StatusReportPdu.parse(bytes(0x06) + report)!!.reference)
    }

    @Test fun rejectsOtherPdus() {
        assertNull(StatusReportPdu.parse(null))
        assertNull(StatusReportPdu.parse(bytes(0x00, 0x04, 0x01)))   // not a status report
        assertNull(StatusReportPdu.parse(smsc + bytes(0x06, 0x2A)))  // cut short
    }

    @Test fun negativeTimeZone() {
        // Delivery-time zone is byte 22: 16 quarter-hours with the sign bit -> "-04:00".
        val r = report.copyOf().also { it[22] = 0x69.toByte() }
        assertEquals(ms("2026-10-25T16:13:39-04:00"), StatusReportPdu.parse(bytes(0x06) + r)!!.deliveryMs)
    }

    // ------------------------------------------------------------ timeline: one time per event, best source

    private fun msg(parts: Int, state: State, sentMask: Long, deliveredMask: Long, sentAt: Long? = null, deliveredAt: Long? = null) =
        Message("M1", "0000000000", "Hi, there", 3, parts, sentMask, deliveredMask, state, null, 1_000, sentAt, deliveredAt, 9_000)

    private fun carrier(ref: Int, centre: Long, delivery: Long) = CarrierReport(ref, "0000000000", centre, delivery, 0)

    @Test fun carrierTimesArePreferredOverPhoneTimes() {
        val m = msg(1, State.DELIVERED, 1, 1, sentAt = 2_000, deliveredAt = 5_000)
        val t = Timelines.build(
            m,
            sends = listOf(PartSend("M1", 0, 2_000, true, null)),
            reports = listOf(Report("M1", 0, 5_000, Delivery.DELIVERED, "3gpp", carrier(166, 1_500, 4_000))),
        )
        assertEquals(Stamp(1_500, Source.CARRIER), t.sent)
        assertEquals(Stamp(4_000, Source.CARRIER), t.delivered)
        assertEquals(listOf("QUEUED", "SENT  (carrier ref 166)", "DELIVERED  (carrier)"), t.lines.map { it.substringAfter("M1  ") })
    }

    @Test fun phoneTimesWhenTheCarrierGivesNone() {
        val m = msg(1, State.DELIVERED, 1, 1, sentAt = 2_000, deliveredAt = 5_000)
        val t = Timelines.build(
            m,
            sends = listOf(PartSend("M1", 0, 2_100, true, null)),
            reports = listOf(Report("M1", 0, 4_900, Delivery.DELIVERED, "3gpp2", null)),
        )
        assertEquals(Stamp(2_100, Source.PHONE), t.sent)
        assertEquals(Stamp(4_900, Source.PHONE), t.delivered)
    }

    @Test fun olderLogsFallBackToTheMessageTimes() {
        val t = Timelines.build(msg(2, State.DELIVERED, 3, 3, sentAt = 2_000, deliveredAt = 5_000), emptyList(), emptyList())
        assertEquals(Stamp(2_000, Source.PHONE), t.sent)
        assertEquals(Stamp(5_000, Source.PHONE), t.delivered)
    }

    @Test fun multipartLinesAreInTimeOrderAndMessageTimesAreTheLatestPart() {
        val m = msg(2, State.DELIVERED, 3, 3, sentAt = 9_000, deliveredAt = 9_000)
        val t = Timelines.build(
            m, emptyList(),
            listOf(
                Report("M1", 0, 8_000, Delivery.DELIVERED, "3gpp", carrier(169, 2_000, 3_000)),
                Report("M1", 1, 8_000, Delivery.DELIVERED, "3gpp", carrier(170, 4_000, 6_000)),
            ),
        )
        assertEquals(Stamp(4_000, Source.CARRIER), t.sent)
        assertEquals(Stamp(6_000, Source.CARRIER), t.delivered)
        assertEquals(
            listOf(
                "QUEUED", "part 1/2 sent  (carrier ref 169)", "part 1/2 delivered  (carrier)",
                "part 2/2 sent  (carrier ref 170)", "SENT  (carrier)",
                "part 2/2 delivered  (carrier)", "DELIVERED  (carrier)",
            ),
            t.lines.map { it.substringAfter("M1  ") },
        )
        assertTrue(t.lines.none { it.contains(',') || it.contains(';') || it.contains('"') })
    }

    @Test fun failedSendIsTimedByThePhone() {
        val m = msg(1, State.FAILED, 0, 0).copy(error = "part 1: no mobile service")
        val t = Timelines.build(m, listOf(PartSend("M1", 0, 3_000, false, "no mobile service")), emptyList())
        assertEquals(Stamp(3_000, Source.PHONE), t.failed)
        assertEquals("FAILED  (part 1: no mobile service)", t.lines.last().substringAfter("M1  "))
        assertNull(t.sent)
    }

    @Test fun csvHasOneRowPerPartWithSources() {
        val m = msg(2, State.DELIVERED, 3, 3, sentAt = 9_000, deliveredAt = 9_000)
        val reports = listOf(Report("M1", 0, 8_000, Delivery.DELIVERED, "3gpp", carrier(42, 2_000, 3_000)))
        val rows = LogExport.rows(listOf(m)) { Timelines.build(it, emptyList(), reports) }
        assertEquals(2, rows.size)
        val h = LogExport.HEADER
        assertEquals("42", rows[0][h.indexOf("CarrierRef")])
        assertEquals("carrier", rows[0][h.indexOf("PartDeliveredBy")])
        assertEquals("0x00", rows[0][h.indexOf("CarrierStatus")])
        assertEquals("", rows[1][h.indexOf("CarrierRef")])
        assertEquals("phone", rows[1][h.indexOf("PartDeliveredBy")])     // no report for part 2: phone fallback
        assertTrue(LogExport.csv(rows).lines()[1].endsWith(",\"Hi, there\""))
    }
}

package com.r2r.smsgateway

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log

/** Receives the phone's "part sent" and "part delivered" callbacks and updates the message log. */
class StatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Gateway.EXTRA_ID) ?: return
        val part = intent.getIntExtra(Gateway.EXTRA_PART, -1).takeIf { it >= 0 } ?: return
        val bit = 1L shl part
        val now = System.currentTimeMillis()
        val store = Store.of(context)

        val result = when (intent.action) {
            Gateway.ACTION_SENT -> {
                val code = resultCode
                val error = if (code == Activity.RESULT_OK) null else Logic.sendError(code, intent.getIntExtra("errorCode", 0))
                store.addPartSend(PartSend(id, part, now, ok = error == null, error = error))
                store.update(id) { m ->
                    if (code == Activity.RESULT_OK) {
                        val sentMask = m.sentMask or bit
                        val state = Logic.state(m.parts, sentMask, m.deliveredMask, failed = false)
                        m.copy(sentMask = sentMask, state = state, sentAt = if (state == State.SENT) now else m.sentAt)
                    } else {
                        m.copy(state = State.FAILED, error = "part ${part + 1}: $error")
                    }
                }
            }
            Gateway.ACTION_DELIVERED -> {
                val pdu = intent.getByteArrayExtra("pdu")
                val format = intent.getStringExtra("format")
                val status = pdu?.let { runCatching { SmsMessage.createFromPdu(it, format).status }.getOrNull() }
                val outcome = if (status == null) Delivery.DELIVERED else Logic.delivery(format, status)
                // Reference, message-centre time and true delivery time (GSM / LTE reports only).
                val carrier = if (format == "3gpp") StatusReportPdu.parse(pdu) else null
                store.addReport(Report(id, part, now, outcome, format, carrier))
                Log.i(
                    TAG,
                    "delivery report $id part $part: format=$format status=$status -> $outcome" +
                        (carrier?.let { " (ref ${it.reference}, centre ${Message.stamp(it.centreMs)}, delivered ${Message.stamp(it.deliveryMs)})" } ?: ""),
                )
                store.update(id) { m ->
                    when (outcome) {
                        Delivery.PENDING -> m // the carrier is still trying; a later report follows
                        Delivery.FAILED -> m.copy(
                            state = State.FAILED,
                            error = "part ${part + 1}: not delivered (carrier status 0x${Integer.toHexString(status ?: 0)})",
                        )
                        Delivery.DELIVERED -> {
                            val sentMask = m.sentMask or bit // a delivery report implies the part was sent
                            val deliveredMask = m.deliveredMask or bit
                            val state = Logic.state(m.parts, sentMask, deliveredMask, failed = false)
                            m.copy(
                                sentMask = sentMask, deliveredMask = deliveredMask, state = state,
                                sentAt = m.sentAt ?: now, deliveredAt = if (state == State.DELIVERED) now else m.deliveredAt,
                            )
                        }
                    }
                }
            }
            else -> null
        } ?: return

        val (before, after) = result
        if (before.state != after.state) {
            Log.i(TAG, "$id: ${before.state} -> ${after.state}" + (after.error?.let { " ($it)" } ?: ""))
            if (after.state == State.DELIVERED || after.state == State.FAILED) notify(context, after)
        }
    }

    /** Shown on the phone; KDE Connect mirrors it to the computer. */
    private fun notify(context: Context, m: Message) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Delivery reports", NotificationManager.IMPORTANCE_DEFAULT))
        val title = if (m.state == State.DELIVERED) "SMS delivered to ${m.to}" else "SMS to ${m.to} failed"
        val text = if (m.state == State.DELIVERED) "${m.id}: ${m.text.take(80)}" else "${m.id}: ${m.error}"
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_sms)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(m.id.hashCode(), notification) } // ignored if notifications are not allowed
    }

    companion object {
        const val TAG = "SmsGateway"
        private const val CHANNEL = "delivery"
    }
}

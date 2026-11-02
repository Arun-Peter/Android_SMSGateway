package com.r2r.smsgateway

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The access token that every command has to carry. */
object Token {
    private const val PREFS = "gateway"
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("token", null) ?: renew(context)
    }

    /** A new random token; scripts using the old one stop working. */
    fun renew(context: Context): String {
        val random = SecureRandom()
        val token = (1..24).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("token", token).apply()
        return token
    }

    fun matches(context: Context, given: String?): Boolean =
        given != null && MessageDigest.isEqual(given.toByteArray(), get(context).toByteArray())
}

/** Sends an SMS with a "sent" and a "delivered" callback for every part. */
object Gateway {
    const val ACTION_SENT = "com.r2r.smsgateway.PART_SENT"
    const val ACTION_DELIVERED = "com.r2r.smsgateway.PART_DELIVERED"
    const val EXTRA_ID = "id"
    const val EXTRA_PART = "part"

    class Rejected(message: String) : Exception(message)

    fun canSend(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Needed to list the SIMs and to pick one by slot. */
    fun canReadSims(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** The SIMs in the phone, or null without the phone permission. */
    fun sims(context: Context): List<SubscriptionInfo>? {
        if (!canReadSims(context)) return null
        @Suppress("MissingPermission")
        return context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
    }

    /**
     * The subscription to send from: [slot] (1 = SIM 1, 2 = SIM 2) or [sub] (Android's subscription id),
     * otherwise the phone's default SIM for SMS.
     */
    private fun chooseSub(context: Context, sub: Int, slot: Int): Int {
        if (sub >= 0 && slot > 0) throw Rejected("give either 'sub' or 'slot', not both")
        if (slot > 0) {
            val sims = sims(context) ?: throw Rejected("choosing a SIM by slot needs the Phone permission: open SMS Gateway and allow it")
            return sims.firstOrNull { it.simSlotIndex == slot - 1 }?.subscriptionId ?: throw Rejected("no SIM in slot $slot")
        }
        if (sub >= 0) {
            val sims = sims(context)
            if (sims != null && sims.none { it.subscriptionId == sub }) {
                throw Rejected("no active SIM with sub $sub (active: ${sims.joinToString { it.subscriptionId.toString() }})")
            }
            return sub
        }
        return SubscriptionManager.getDefaultSmsSubscriptionId()
    }

    /** Queues and sends the message; returns it as stored (state QUEUED). */
    fun send(context: Context, toInput: String?, text: String?, idInput: String?, subInput: Int, slot: Int): Message {
        if (!canSend(context)) throw Rejected("SMS permission not granted: open SMS Gateway on the phone and allow it")
        val to = Logic.cleanNumber(toInput) ?: throw Rejected("'to' must be a phone number")
        if (text.isNullOrEmpty()) throw Rejected("'text' is empty")
        val store = Store.of(context)
        val id = idInput?.trim()?.ifEmpty { null } ?: newId()
        if (!Logic.validId(id)) throw Rejected("'id' may only contain letters, digits, '_', '-' and '.' (max 64)")
        if (store.exists(id)) throw Rejected("id '$id' was already used")

        val sub = chooseSub(context, subInput, slot)
        val manager = manager(context, sub)
        val parts = manager.divideMessage(text)
        if (parts.size > Logic.MAX_PARTS) throw Rejected("text is too long (${parts.size} parts, max ${Logic.MAX_PARTS})")

        val now = System.currentTimeMillis()
        val message = Message(
            id = id, to = to, text = text, sub = sub, parts = parts.size, sentMask = 0, deliveredMask = 0,
            state = State.QUEUED, error = null, queuedAt = now, sentAt = null, deliveredAt = null, updatedAt = now,
        )
        store.insert(message)
        val sent = ArrayList(parts.indices.map { callback(context, ACTION_SENT, id, it) })
        val delivered = ArrayList(parts.indices.map { callback(context, ACTION_DELIVERED, id, it) })
        try {
            // Android also saves the message in the phone's SMS app (we are not the default SMS app).
            manager.sendMultipartTextMessage(to, null, parts, sent, delivered)
        } catch (e: Exception) {
            store.update(id) { it.copy(state = State.FAILED, error = "could not send: ${e.message ?: e.javaClass.simpleName}") }
            throw Rejected("could not send: ${e.message ?: e.javaClass.simpleName}")
        }
        return message
    }

    private fun manager(context: Context, sub: Int): SmsManager {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }
        if (sub < 0) return base
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base.createForSubscriptionId(sub)
        } else {
            @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(sub)
        }
    }

    /**
     * Callbacks address only this app's own receiver. They must be mutable: the SMS service adds the
     * carrier's delivery report ("pdu", "format") to the intent, which an immutable one would drop.
     */
    private fun callback(context: Context, action: String, id: String, part: Int): PendingIntent {
        val intent = Intent(context, StatusReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("smsgateway://${action.substringAfterLast('_').lowercase()}/${Uri.encode(id)}/$part"))
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_PART, part)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    private fun newId(): String = "M" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
}

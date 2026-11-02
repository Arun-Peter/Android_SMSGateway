package com.r2r.smsgateway

import android.telephony.SmsManager

/** Where a message is: QUEUED → SENT → DELIVERED, or FAILED at any point. */
enum class State { QUEUED, SENT, DELIVERED, FAILED }

/** What one delivery report from the carrier says about one part of a message. */
enum class Delivery { DELIVERED, PENDING, FAILED }

/** Pure rules, kept free of Android calls so they can be unit-tested. */
object Logic {
    /** Long texts are split into parts; each part gets its own sent and delivery report. */
    const val MAX_PARTS = 30

    fun allParts(parts: Int): Long = (1L shl parts) - 1

    /** A message is SENT / DELIVERED only when every part is; one failed part fails the message. */
    fun state(parts: Int, sentMask: Long, deliveredMask: Long, failed: Boolean): State {
        val all = allParts(parts)
        return when {
            failed -> State.FAILED
            deliveredMask and all == all -> State.DELIVERED
            sentMask and all == all -> State.SENT
            else -> State.QUEUED
        }
    }

    /**
     * Reads the status of a delivery report. GSM / LTE ("3gpp", TP-Status): 0x00–0x1F delivered,
     * 0x20–0x3F the carrier is still trying, 0x40 and above failed. CDMA ("3gpp2"): 0 means delivered.
     */
    fun delivery(format: String?, status: Int): Delivery = when {
        format == "3gpp2" -> if (status == 0) Delivery.DELIVERED else Delivery.FAILED
        status < 0x20 -> Delivery.DELIVERED
        status < 0x40 -> Delivery.PENDING
        else -> Delivery.FAILED
    }

    /** Why the phone could not send a part (result code of the "sent" callback). */
    fun sendError(resultCode: Int, errorCode: Int): String = when (resultCode) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure" + if (errorCode != 0) " (error $errorCode)" else ""
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off (airplane mode?)"
        SmsManager.RESULT_ERROR_NULL_PDU -> "message could not be encoded"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no mobile service"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "sending limit exceeded"
        SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "blocked by fixed dialing numbers"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED ->
            "short code not allowed"
        else -> "send error $resultCode" + if (errorCode != 0) " (error $errorCode)" else ""
    }

    /** "+91 00000-00000" -> "+910000000000"; null if it is not a phone number. */
    fun cleanNumber(input: String?): String? {
        val s = input?.trim()?.replace(Regex("[\\s().-]"), "") ?: return null
        return s.takeIf { Regex("\\+?[0-9]{3,20}").matches(it) }
    }

    /** Ids chosen by the caller: letters, digits, "_", "-", "." (up to 64). */
    fun validId(id: String): Boolean = Regex("[A-Za-z0-9_.-]{1,64}").matches(id)

    /**
     * The developer password for Clear log, kept only as a salted SHA-256 hash so it cannot be read from
     * the app. It guards against accidental clearing; it is not meant to resist a determined attacker.
     */
    private const val CLEAR_SALT = "smsgateway-clear-log-v1:"
    private const val CLEAR_HASH = "6ad8bf0109a4ca542287ad7e3d90b1d20ea3e1487402f8fa10537d0bd0ed12ba"

    fun isClearPassword(input: String): Boolean {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest((CLEAR_SALT + input).toByteArray())
        return java.security.MessageDigest.isEqual(digest.joinToString("") { "%02x".format(it) }.toByteArray(), CLEAR_HASH.toByteArray())
    }

    /** Accepts "send", "SEND" or "com.r2r.smsgateway.SEND". */
    fun command(action: String?): String? = action?.substringAfterLast('.')?.lowercase()?.ifBlank { null }
}

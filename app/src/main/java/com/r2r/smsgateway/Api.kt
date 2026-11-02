package com.r2r.smsgateway

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Commands from adb or Termux, e.g.
 *   am broadcast -n com.r2r.smsgateway/.Api -a send --es token T --es to 0000000000 --es text "hello" --es id MSG001
 *   am broadcast -n com.r2r.smsgateway/.Api -a status --es token T --es id MSG001
 * Optional for send: --ei slot 2 (SIM slot) or --ei sub 4 (subscription id).
 * The reply is JSON in the broadcast result, which `am` prints as: Broadcast completed: result=1, data="{...}"
 * Termux's `am` does not wait for that result, so with `--es reply <name>` the reply is also written to
 * Download/SmsGateway/replies/<name>.json (see [ReplyFiles]).
 */
class Api : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val tokenOk = Token.matches(context, intent.getStringExtra("token"))
        val reply = try {
            if (!tokenOk) throw Gateway.Rejected("wrong or missing token")
            handle(context, Logic.command(intent.action), intent)
        } catch (e: Gateway.Rejected) {
            error(e.message ?: "rejected")
        } catch (e: Exception) {
            Log.e(StatusReceiver.TAG, "command failed", e)
            error("${e.javaClass.simpleName}: ${e.message}")
        }
        setResult(if (reply.optBoolean("ok")) RESULT_OK_CODE else RESULT_ERROR_CODE, reply.toString(), null)
        // No files for requests without the right token, so nobody else can make the app write them.
        val replyName = intent.getStringExtra("reply")
        if (tokenOk && replyName != null && Logic.validId(replyName)) ReplyFiles.write(context, replyName, reply.toString())
    }

    private fun handle(context: Context, command: String?, intent: Intent): JSONObject {
        val store = Store.of(context)
        return when (command) {
            "send" -> {
                val m = Gateway.send(
                    context,
                    toInput = intent.getStringExtra("to"),
                    text = intent.getStringExtra("text"),
                    idInput = intent.getStringExtra("id"),
                    subInput = intent.getIntExtra("sub", -1),
                    slot = intent.getIntExtra("slot", 0),
                )
                ok().put("message", Json.message(m, store.timeline(m), detail = false))
            }
            "status" -> {
                val id = intent.getStringExtra("id") ?: throw Gateway.Rejected("'id' is missing")
                val m = store.get(id) ?: throw Gateway.Rejected("no message with id '$id'")
                ok().put("message", Json.message(m, store.timeline(m), detail = true))
            }
            "list" -> {
                val limit = intent.getIntExtra("limit", 20).coerceIn(1, 500)
                ok().put("messages", JSONArray(store.recent(limit).map { Json.message(it, store.timeline(it), detail = false) }))
            }
            "info" -> ok()
                .put("app", "SMS Gateway ${BuildConfig.VERSION_NAME}")
                .put("smsPermission", Gateway.canSend(context))
                .put("notifications", context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                .put("autostart", PhoneSetup.autostart(context) ?: JSONObject.NULL) // Xiaomi only; null = cannot be checked
                .put("batteryUnrestricted", PhoneSetup.batteryUnrestricted(context))
                .put("defaultSmsSub", SubscriptionManager.getDefaultSmsSubscriptionId())
                .put("sims", sims(context))
                // Keys differ from the SIM keys ("name"), because the shell script looks values up by key.
                .put("phoneName", PhoneSetup.deviceName(context))
                .put("manufacturer", android.os.Build.MANUFACTURER)
                .put("model", android.os.Build.MODEL)
                .put("device", android.os.Build.DEVICE)
                .put("android", android.os.Build.VERSION.RELEASE)
                .put("wifiIp", PhoneSetup.wifiIp(context) ?: JSONObject.NULL)
            else -> throw Gateway.Rejected("unknown command '${intent.action}' (use send, status, list or info)")
        }
    }

    /** SIMs as {sub, slot, name, carrier, smsDefault}; a note instead if the phone permission is missing. */
    private fun sims(context: Context): Any {
        val sims = Gateway.sims(context) ?: return "allow the Phone permission in SMS Gateway to list SIMs"
        val default = SubscriptionManager.getDefaultSmsSubscriptionId()
        return JSONArray(sims.sortedBy { it.simSlotIndex }.map {
            JSONObject()
                .put("sub", it.subscriptionId)
                .put("slot", it.simSlotIndex + 1)
                .put("name", it.displayName?.toString().orEmpty())
                .put("carrier", it.carrierName?.toString().orEmpty())
                .put("smsDefault", it.subscriptionId == default)
        })
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun error(text: String) = JSONObject().put("ok", false).put("error", text)

    companion object {
        // Distinct from 0, which is what `am` shows when no receiver answered at all.
        const val RESULT_OK_CODE = 1
        const val RESULT_ERROR_CODE = 2
    }
}

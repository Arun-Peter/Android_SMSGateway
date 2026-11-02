package com.r2r.smsgateway

import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON that commands reply with. Every time appears once, from its most authentic source, with
 * "…By": "carrier" or "phone". Keys are unique across the whole reply because the shell script finds
 * values by key name (hence "partSent" inside parts, not "sent").
 */
object Json {

    fun message(m: Message, t: Timeline, detail: Boolean): JSONObject {
        val o = JSONObject()
            .put("id", m.id)
            .put("to", m.to)
            .put("sub", m.sub)
            .put("status", m.state.name)
            .put("parts", m.parts)
            .put("partsSent", t.parts.count { it.sent != null })
            .put("partsDelivered", t.parts.count { it.delivered != null })
            .put("error", m.error ?: JSONObject.NULL)
            .put("queued", Message.stamp(m.queuedAt))
            .put("sent", time(t.sent)).put("sentBy", by(t.sent))
            .put("delivered", time(t.delivered)).put("deliveredBy", by(t.delivered))
            .put("failed", time(t.failed))
            .put("text", m.text)
        if (detail) {
            o.put("partsDetail", JSONArray(t.parts.map { p ->
                JSONObject()
                    .put("part", p.part + 1)
                    .put("partState", p.state.name)
                    .put("ref", p.ref ?: JSONObject.NULL)
                    .put("partSent", time(p.sent)).put("partSentBy", by(p.sent))
                    .put("partDelivered", time(p.delivered)).put("partDeliveredBy", by(p.delivered))
                    .put("partFailed", time(p.failed)).put("partError", p.error ?: JSONObject.NULL)
                    .put("tpStatus", p.tpStatus ?: JSONObject.NULL)
            }))
            o.put("timeline", t.lines.joinToString(";"))
        }
        return o
    }

    private fun time(s: Stamp?): Any = s?.let { Message.stamp(it.ms) } ?: JSONObject.NULL
    private fun by(s: Stamp?): Any = s?.by?.label ?: JSONObject.NULL
}

package com.fallbracelet.relay

import com.fallbracelet.ble.BraceletStatus
import com.fallbracelet.data.AppSettings
import com.fallbracelet.data.EventEntity
import com.fallbracelet.data.EventRelay
import com.fallbracelet.data.SettingsRepository
import org.json.JSONObject

data class LinkedCaregiver(val id: Long, val name: String, val linkedAt: Long)
data class Invite(val code: String, val expiresAt: Long)
data class Ack(val deviceAddress: String, val seq: Long, val by: String, val at: Long)
data class HeartbeatReply(val acks: List<Ack>, val caregivers: Int)

/** The wearer phone's side of the Pi relay: registration, event upload, heartbeats, invites. */
class WearerRelay(
    private val settings: SettingsRepository,
    private val current: () -> AppSettings,
) : EventRelay {

    override val configured: Boolean
        get() = current().relayUrl.isNotBlank() && current().wearerToken != null

    private fun client(): RelayClient {
        val s = current()
        return RelayClient(s.relayUrl, s.wearerToken ?: throw RelayException(401, "not registered with the relay"))
    }

    /** Registers this phone as a wearer the first time a relay address is set. */
    suspend fun ensureRegistered() {
        val s = current()
        if (s.relayUrl.isBlank() || s.wearerToken != null) return
        val r = RelayClient(s.relayUrl, null).post("/v1/wearers", JSONObject().put("name", s.wearerName.ifBlank { "Wearer" }))
        settings.setWearerToken(r.getString("token"))
    }

    suspend fun rename(name: String) {
        if (configured && name.isNotBlank()) client().put("/v1/wearers/me", JSONObject().put("name", name))
    }

    override suspend fun upload(event: EventEntity): Boolean = runCatching {
        client().post(
            "/v1/events",
            JSONObject()
                .put("deviceAddress", event.deviceAddress)
                .put("seq", event.seq)
                .put("type", event.type)
                .put("batteryPct", event.batteryPct)
                .put("occurredAt", event.occurredAt)
                .put("impactG", event.impactG.toDouble())
                .put("alarms24h", event.alarms24h)
                .put("cancelled24h", event.cancelled24h)
                .put("message", event.message),
        )
    }.isSuccess

    /** Tells caregivers this phone is alive; the reply has acknowledgements made since [acksSince]. */
    suspend fun heartbeat(status: BraceletStatus?, braceletConnected: Boolean, acksSince: Long): HeartbeatReply {
        val st = JSONObject().put("braceletConnected", braceletConnected)
        if (status != null) {
            st.put("state", status.state.name).put("batteryPct", status.batteryPct)
                .put("batteryMv", status.batteryMv).put("pendingEvents", status.pendingEvents)
        }
        val r = client().post("/v1/heartbeat", JSONObject().put("status", st).put("acksSince", acksSince))
        val acks = r.getJSONArray("acks")
        return HeartbeatReply(
            acks = List(acks.length()) { i ->
                val a = acks.getJSONObject(i)
                Ack(a.getString("device_address"), a.getLong("seq"), a.getString("acked_by"), a.getLong("acked_at"))
            },
            caregivers = r.optInt("caregivers"),
        )
    }

    suspend fun invite(): Invite {
        val r = client().post("/v1/invites")
        return Invite(r.getString("code"), r.getLong("expiresAt"))
    }

    suspend fun caregivers(): List<LinkedCaregiver> {
        val a = client().get("/v1/caregivers").getJSONArray("caregivers")
        return List(a.length()) { i ->
            val c = a.getJSONObject(i)
            LinkedCaregiver(c.getLong("id"), c.getString("name"), c.getLong("linkedAt"))
        }
    }

    suspend fun removeCaregiver(id: Long) {
        client().delete("/v1/caregivers/$id")
    }
}

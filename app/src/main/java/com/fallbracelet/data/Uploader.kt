package com.fallbracelet.data

/** Where stored events go so caregivers see them. Keyed by (deviceAddress, seq) on the far side. */
interface EventRelay {
    /** False until the relay (Firebase) is set up and signed in. */
    val configured: Boolean

    /** True once the relay has the event. Uploading the same (deviceAddress, seq) twice must be harmless. */
    suspend fun upload(event: EventEntity): Boolean
}

/** Placeholder until the Firebase relay exists: nothing is uploaded, events wait in the queue. */
object NoRelay : EventRelay {
    override val configured = false
    override suspend fun upload(event: EventEntity) = false
}

/** The events not yet accepted by the relay. */
interface UploadQueue {
    /** Oldest first. */
    suspend fun pendingUploads(): List<EventEntity>
    suspend fun markUploaded(id: Long, at: Long)
}

/** Sends queued events to the relay, oldest first, stopping at the first failure so order is kept. */
class Uploader(private val queue: UploadQueue, private val relay: EventRelay) {
    /** Returns how many events were uploaded. */
    suspend fun flush(now: () -> Long = System::currentTimeMillis): Int {
        if (!relay.configured) return 0
        var sent = 0
        for (e in queue.pendingUploads()) {
            if (!relay.upload(e)) break
            queue.markUploaded(e.id, now())
            sent++
        }
        return sent
    }
}

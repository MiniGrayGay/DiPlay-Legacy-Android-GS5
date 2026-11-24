package com.shilapi.xcertplay.network

/**
 * UDP the car sends to the iPhone during a wired session.
 *
 * The wired link has no kernel interface to the iPhone: the userspace lwIP bridge relays the
 * iPhone's UDP to loopback ports, and UDP to the iPhone must go back through that bridge. A
 * plain socket aimed at the iPhone's link-local address is dropped without an error, which left
 * the wired microphone uplink (calls, Siri) silent. The bridge installs itself here while it
 * runs; with no bridge installed the caller uses its socket, as on the wireless link.
 */
object WiredUdpUplink {
    fun interface Sender {
        /** Sends [payload] from the relay on [localPort] to the iPhone's [remotePort]. */
        fun sendUdp(localPort: Int, remotePort: Int, payload: ByteArray): Boolean
    }

    @Volatile private var sender: Sender? = null

    @Synchronized
    fun install(next: Sender) {
        sender = next
    }

    /** Clears [previous] only if it is still the installed sender. */
    @Synchronized
    fun clear(previous: Sender) {
        if (sender === previous) sender = null
    }

    fun current(): Sender? = sender

    /**
     * Sends one packet to the iPhone: through the wired bridge when one runs and the stream has a
     * relay port, else with [socketSend]. Returns false when the bridge could not send it.
     */
    fun deliver(
        relayPort: Int?,
        remotePort: Int,
        packet: ByteArray,
        bridge: Sender? = current(),
        socketSend: (ByteArray) -> Unit,
    ): Boolean {
        if (relayPort != null && relayPort > 0 && bridge != null) {
            return bridge.sendUdp(relayPort, remotePort, packet)
        }
        socketSend(packet)
        return true
    }
}

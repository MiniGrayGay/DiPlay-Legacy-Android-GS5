package com.shilapi.xcertplay.airplay

/**
 * UDP the car sends to the iPhone during a wired session.
 *
 * The wired link has no kernel interface to the iPhone: the userspace lwIP bridge relays the
 * iPhone's connections and UDP to loopback ports, so a wired session's peer address is 127.0.0.1.
 * A plain socket sending to that address and an iPhone port reaches nothing and reports no error.
 * That left the wired microphone uplink (calls, Siri) silent and the session clock unsynced. The
 * bridge installs itself here while it runs; with no bridge installed the caller uses its socket,
 * as on the wireless link and on the kernel TUN.
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

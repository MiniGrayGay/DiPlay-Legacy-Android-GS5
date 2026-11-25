package com.shilapi.xcertplay.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import com.shilapi.xcertplay.compat.realSdkInt
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Android 4.4 - 8 system-generated Wi-Fi Direct group owner for wireless CarPlay.
 *
 * [preferredChannel] (1-11) pins the group's operating channel through the hidden
 * `WifiP2pManager.setWifiP2pChannels`, which on 4.4 makes the supplicant disallow every other
 * frequency (`P2P_SET disallow_freq`). Auto clears that restriction instead, so one left behind by
 * a process that died does not keep a later group on its channel. A manual channel the radio
 * refuses falls back to Auto. The restriction is cleared again when the group closes.
 */
@android.annotation.SuppressLint("MissingPermission")
internal class LegacyWifiP2pGroupManager(
    context: Context,
    private val diagnostic: (String) -> Unit = {},
    preferredChannel: Int = WifiP2pChannels.AUTO,
) : WirelessHotspotManager {
    /** A manual 2.4 GHz channel, or 0 for Auto (which includes "Auto · 2.4 GHz" on this radio). */
    private val pinnedChannel = preferredChannel.takeIf { it in WifiP2pChannels.supportedChannels } ?: 0
    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: throw IllegalStateException("WifiP2pManager is unavailable")

    private var thread: HandlerThread? = null
    private var channel: WifiP2pManager.Channel? = null
    private var created = false
    @Volatile private var closed = false

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LegacyWifiP2pGroupManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0)
        checkPrerequisites()

        val worker = HandlerThread("xcertplay-wifi-p2p-legacy").apply { start() }
        thread = worker
        val p2pChannel = manager.initialize(
            appContext,
            worker.looper,
            WifiP2pManager.ChannelListener {
                diagnostic("Legacy Wi-Fi Direct channel disconnected")
            },
        )
        channel = p2pChannel

        try {
            requestGroupInfo(p2pChannel, 2_000)?.let { group ->
                diagnostic(
                    "Legacy Wi-Fi Direct removing retained group owner=${group.isGroupOwner} " +
                        "name=${group.networkName ?: "unknown"}",
                )
                removeGroupBlocking(p2pChannel, 4_000)
            }

            var operatingChannel = pinnedChannel
            setOperatingChannel(p2pChannel, operatingChannel)
            var groupCreated = false
            var lastCreateFailure: IOException? = null
            var createAttempts = 0
            while (!groupCreated && createAttempts < 3 && !closed) {
                createAttempts++
                val createLatch = CountDownLatch(1)
                val createFailure = AtomicReference<IOException?>()
                manager.createGroup(p2pChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        created = true
                        groupCreated = true
                        createLatch.countDown()
                    }

                    override fun onFailure(reason: Int) {
                        createFailure.set(
                            IOException("Legacy Wi-Fi Direct createGroup failed code=$reason"),
                        )
                        createLatch.countDown()
                    }
                })
                if (!await(createLatch, minOf(timeoutMillis, 10_000))) {
                    throw IOException("Legacy Wi-Fi Direct group creation timed out")
                }
                val failure = createFailure.get()
                if (failure == null) {
                    groupCreated = true
                } else {
                    lastCreateFailure = failure
                    if (failure.message?.contains("code=2") == true && createAttempts < 3 && !closed) {
                        diagnostic("Legacy Wi-Fi Direct createGroup busy (code=2); settling and retrying ($createAttempts/3)")
                        SystemClock.sleep(500)
                    } else if (operatingChannel != 0 && !closed) {
                        // The radio could not use the chosen channel (for example while its own Wi-Fi
                        // stays on another one): let the system choose rather than fail the connection.
                        diagnostic(
                            "Legacy Wi-Fi Direct channel $operatingChannel refused (${failure.message}); " +
                                "falling back to Auto",
                        )
                        operatingChannel = 0
                        setOperatingChannel(p2pChannel, 0)
                        createAttempts = 0
                    } else {
                        break
                    }
                }
            }
            if (!groupCreated) {
                lastCreateFailure?.let { throw it }
            }

            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            var lastReason = "group information unavailable"
            while (!closed && System.nanoTime() < deadline) {
                val group = requestGroupInfo(p2pChannel, 1_500)
                if (group == null) {
                    lastReason = "group information unavailable"
                    continue
                }
                if (!group.isGroupOwner) {
                    throw IOException("Legacy Wi-Fi Direct became a group client instead of owner")
                }

                val ssid = group.networkName?.takeIf { it.isNotBlank() }
                val passphrase = group.passphrase?.takeIf { it.isNotBlank() }
                val interfaceName = group.getInterface()?.takeIf { it.isNotBlank() }
                val connectionAddress = requestConnectionInfo(p2pChannel, 1_500)
                val networkInterface = interfaceName?.let {
                    runCatching { NetworkInterface.getByName(it) }.getOrNull()
                }
                val hostAddress = networkInterface?.let { iface ->
                    wirelessHostAddress(
                        addresses = Collections.list(iface.inetAddresses),
                        interfaceIndex = iface.index,
                        preferIpv4 = true,
                    )
                } ?: connectionAddress

                val frameworkFrequency = readFrameworkFrequency(group)
                val driverFrequency = interfaceName?.let { name ->
                    LegacyHotspotRadio.read(name, null).also { reading ->
                        diagnostic(
                            "Legacy Wi-Fi Direct radio iface=$name " +
                                "frequency=${reading.frequencyMHz ?: "unknown"} " +
                                "error=${reading.error ?: "none"}",
                        )
                    }.frequencyMHz
                }
                val frequency = frameworkFrequency ?: driverFrequency
                val channelNumber = frequency?.let(::wifiFrequencyMhzToChannel) ?: 0
                if (operatingChannel != 0 && channelNumber != 0 && channelNumber != operatingChannel) {
                    diagnostic("Legacy Wi-Fi Direct asked for channel $operatingChannel but the group is on $channelNumber")
                }

                if (ssid == null || passphrase == null || hostAddress == null) {
                    lastReason =
                        "incomplete group ssid=${ssid != null} passphrase=${passphrase != null} " +
                            "host=${hostAddress != null} iface=${interfaceName ?: "unknown"}"
                    Thread.sleep(150)
                    continue
                }

                val bandLabel = when (frequency) {
                    in 2412..2484 -> "2.4 GHz"
                    in 5160..5895 -> "5 GHz"
                    in 5955..7115 -> "6 GHz"
                    else -> "system selected"
                }
                diagnostic(
                    "Legacy Wi-Fi Direct ready iface=${interfaceName ?: "unknown"} " +
                        "band=$bandLabel channel=$channelNumber frequency=${frequency ?: "unknown"}",
                )

                return WirelessHotspotInfo(
                    ssid = ssid,
                    passphrase = passphrase,
                    security = Iap2WirelessSecurity.WPA_WPA2,
                    channel = channelNumber,
                    frequencyMHz = frequency,
                    bssid = networkInterface?.hardwareAddress
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString(":") { "%02x".format(it.toInt() and 0xff) }
                        ?: group.owner?.deviceAddress?.takeIf { it.isNotBlank() },
                    interfaceName = interfaceName,
                    hostAddress = hostAddress,
                    bandLabel = bandLabel,
                    backend = WirelessHotspotBackend.WIFI_P2P,
                )
            }
            throw IOException("Legacy Wi-Fi Direct timed out: $lastReason")
        } catch (error: Exception) {
            close()
            throw error
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        val activeChannel = channel
        if (created && activeChannel != null) {
            runCatching { removeGroupBlocking(activeChannel, 2_000) }
        }
        // Leave the supplicant free to use any channel again, for the car's own Wi-Fi Direct too.
        if (pinnedChannel != 0 && activeChannel != null) {
            runCatching { setOperatingChannel(activeChannel, 0) }
        }
        channel = null
        created = false
        activeChannel?.let { ch ->
            runCatching { ch.javaClass.getMethod("close").invoke(ch) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) thread?.quitSafely() else thread?.quit()
        thread = null
    }

    /**
     * Pins the operating channel ([operating] 1-11), or clears the pin with 0. The listen channel
     * is left as it is. Returns false when the hidden API is missing or the supplicant refuses.
     */
    private fun setOperatingChannel(channel: WifiP2pManager.Channel, operating: Int): Boolean {
        val done = CountDownLatch(1)
        val ok = AtomicReference<String?>(null)
        val invoked = runCatching {
            WifiP2pManager::class.java.getMethod(
                "setWifiP2pChannels",
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java,
            ).invoke(manager, channel, 0, operating, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    ok.set("ok")
                    done.countDown()
                }

                override fun onFailure(reason: Int) {
                    ok.set("failed code=$reason")
                    done.countDown()
                }
            })
        }
        val result = invoked.exceptionOrNull()?.let { "unavailable ${it.javaClass.simpleName}" }
            ?: if (await(done, CHANNEL_SET_TIMEOUT_MILLIS)) ok.get() else "timed out"
        diagnostic("Legacy Wi-Fi Direct operating channel ${if (operating == 0) "auto" else operating}: $result")
        return result == "ok"
    }

    private fun checkPrerequisites() {
        if (
            realSdkInt() >= 23 && appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IOException("Allow precise Location for DiPlay before using Wi-Fi Direct")
        }
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi?.isWifiEnabled == false) {
            throw IOException("Turn on Wi-Fi in the head unit settings before using Wi-Fi Direct")
        }
        // Issue #331: on units without Wi-Fi Direct support the group
        // creation fails with code=1 after several attempts; fail here
        // with an actionable message instead of retrying a mode that
        // can never work on this hardware.
        if (!appContext.packageManager.hasSystemFeature("android.hardware.wifi.direct")) {
            throw IOException(
                "Wi-Fi Direct is not supported on this head unit; use Existing Wi-Fi / Same LAN or a wired connection",
            )
        }
    }
    private fun requestGroupInfo(
        channel: WifiP2pManager.Channel,
        timeoutMillis: Long,
    ): WifiP2pGroup? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<WifiP2pGroup?>()
        manager.requestGroupInfo(channel) {
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, timeoutMillis)) return null
        return result.get()
    }

    private fun requestConnectionInfo(
        channel: WifiP2pManager.Channel,
        timeoutMillis: Long,
    ): InetAddress? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<WifiP2pInfo?>()
        manager.requestConnectionInfo(channel) {
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, timeoutMillis)) return null
        val info = result.get() ?: return null
        if (!info.groupFormed || !info.isGroupOwner) return null
        return info.groupOwnerAddress?.takeUnless(InetAddress::isAnyLocalAddress)
    }
    private fun removeGroupBlocking(
        channel: WifiP2pManager.Channel,
        timeoutMillis: Long,
    ) {
        val latch = CountDownLatch(1)
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = latch.countDown()
            override fun onFailure(reason: Int) {
                diagnostic("Legacy Wi-Fi Direct removeGroup failed code=$reason")
                latch.countDown()
            }
        })
        await(latch, timeoutMillis)
    }

    private fun readFrameworkFrequency(group: WifiP2pGroup): Int? = runCatching {
        (group.javaClass.getMethod("getFrequency").invoke(group) as? Number)
            ?.toInt()
            ?.takeIf { it > 0 }
    }.getOrNull()

    private fun await(latch: CountDownLatch, timeoutMillis: Long): Boolean = try {
        latch.await(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted while waiting for legacy Wi-Fi Direct", interrupted)
    }

    private companion object {
        const val CHANNEL_SET_TIMEOUT_MILLIS = 2_000L
    }
}

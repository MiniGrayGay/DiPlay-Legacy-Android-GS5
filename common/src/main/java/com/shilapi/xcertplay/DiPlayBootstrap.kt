package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.compat.BootSettle
import com.shilapi.xcertplay.compat.realSdkInt
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import java.security.MessageDigest

/** Installs the private beta's experimental identity. It has no remote fallback. */
internal object DiPlayBootstrap {
    @Volatile private var ready = false
    @Volatile private var usbBtRestoreTried = false
    @Volatile private var lastRestoreAttemptMillis = 0L
    private val usbBtRestorePending = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Issue #281 & #321: restore the USB Bluetooth dongle on app launch or resume.
     * Retries if previous attempt occurred before the USB bus finished enumeration.
     */
    @Synchronized fun restoreUsbBluetooth(context: Context, force: Boolean = false) {
        if (CarPlayBackgroundSession.hasSession()) {
            Log.i(TAG, "USB Bluetooth auto-restore skipped while CarPlay controller is retained")
            return
        }
        val now = System.currentTimeMillis()
        if (!force && usbBtRestoreTried && (now - lastRestoreAttemptMillis < 3000L)) return
        lastRestoreAttemptMillis = now
        usbBtRestoreTried = true
        if (!AirPlayPersistence.loadUsbBtAutoStart(context)) return
        val appContext = context.applicationContext
        // One waiting thread at a time: resumes during the boot window would otherwise pile up.
        if (!usbBtRestorePending.compareAndSet(false, true)) return
        Thread({
            runCatching {
                // Not while the head unit is still booting; see BootSettle.
                BootSettle.await { Log.i(TAG, "USB Bluetooth auto-restore: $it") }
                if (CarPlayBackgroundSession.hasSession()) return@runCatching
                val dongle = com.shilapi.xcertplay.bluetooth.CarPlayBluetoothManager.get(appContext).usbDongle
                    ?: return@runCatching
                val status = dongle.getStatus()
                if (status.isSupported && !status.isEnabled) {
                    dongle.enable()
                }
            }
            usbBtRestorePending.set(false)
        }, "diplay-usbbt-restore").apply { isDaemon = true; start() }
    }

    @Synchronized fun ensure(context: Context, mfiTarget: MfiTarget) {
        if (mfiTarget != MfiTarget.LOCAL) return
        if (ready) return
        val privateRoot = if (realSdkInt() >= Build.VERSION_CODES.LOLLIPOP) context.noBackupFilesDir else context.filesDir
        val target = File(privateRoot, LocalMfiAuthenticationClient.DIRECTORY)

        // Fast path: if target directory already exists and files match assets, skip expensive crypto validation
        if (matchesAssets(context, target)) {
            ready = true
            return
        }

        val staging = File(privateRoot, "offline-mfi-staging")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not prepare local authentication" }
        staging.setReadable(false, false); staging.setReadable(true, true)
        staging.setExecutable(false, false); staging.setExecutable(true, true)
        try {
            for (name in AUTH_FILES) {
                val file = File(staging, name)
                context.assets.open("offline-mfi/$name").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file.setReadable(false, false); file.setReadable(true, true)
                file.setWritable(false, false); file.setWritable(true, true)
            }

            // Validate the APK-bundled identity once in staging before installing.
            LocalMfiAuthenticationClient.load(staging)
            val previous = File(privateRoot, "offline-mfi-previous")
            previous.deleteRecursively()
            if (target.exists()) {
                check(target.renameTo(previous)) { "Could not rotate previous local authentication" }
            }
            try {
                check(staging.renameTo(target)) { "Could not install local authentication" }
                previous.deleteRecursively()
            } catch (error: Throwable) {
                if (!target.exists() && previous.exists()) previous.renameTo(target)
                throw error
            }
        } finally {
            staging.deleteRecursively()
        }
        ready = true
    }

    private fun matchesAssets(context: Context, target: File): Boolean {
        if (!target.isDirectory) return false
        return AUTH_FILES.all { name ->
            val file = File(target, name)
            if (!file.isFile || file.length() == 0L) return false
            try {
                context.assets.open("offline-mfi/$name").use { assetStream ->
                    val available = assetStream.available()
                    if (available > 0 && file.length() != available.toLong()) return false
                    file.inputStream().use { fileStream ->
                        val b1 = ByteArray(4096)
                        val b2 = ByteArray(4096)
                        while (true) {
                            val r1 = assetStream.read(b1)
                            val r2 = fileStream.read(b2)
                            if (r1 != r2) return false
                            if (r1 < 0) break
                            for (i in 0 until r1) {
                                if (b1[i] != b2[i]) return false
                            }
                        }
                    }
                }
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }

    private val AUTH_FILES = listOf("identity.pk8", "certificate.p7b")

    private const val TAG = "DiPlayBootstrap"
}

internal enum class DefaultConnectionMode(val key: String) {
    LAST_USED("last_used"), WIRELESS("wireless"), USB("usb");

    fun wireless(lastUsedWireless: Boolean): Boolean = when (this) {
        LAST_USED -> lastUsedWireless
        WIRELESS -> true
        USB -> false
    }

    companion object {
        fun fromKey(key: String?): DefaultConnectionMode = entries.firstOrNull { it.key == key } ?: LAST_USED
    }
}

internal object DiPlayPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "Your iPhone"
    fun savePhone(context: Context, address: String, name: String) {
        prefs(context).edit().putString("phone_address", address).putString("phone_name", name).apply()
    }
    fun defaultConnectionMode(context: Context) =
        DefaultConnectionMode.fromKey(prefs(context).getString("default_connection_mode", null))
    fun saveDefaultConnectionMode(context: Context, mode: DefaultConnectionMode) {
        prefs(context).edit().putString("default_connection_mode", mode.key).apply()
    }
    fun autoConnectWireless(context: Context) =
        defaultConnectionMode(context).wireless(AirPlayPersistence.loadWirelessEnabled(context))
    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", false)
    fun saveAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_connect", value).apply()
    }
}

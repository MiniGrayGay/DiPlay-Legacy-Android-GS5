package com.shilapi.xcertplay.orchestration

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context

/**
 * The car's Bluetooth page has one global "自动连接" switch. It is `BT_CANSYNC` in the
 * vendor backup store, and the Parrot module is told with `BluetoothAdapter.setBTAutoConn`
 * (`*PSCM`). While CarPlay is up the iPhone drops HFP to the address in `bluetoothIDs`;
 * if this switch is still on, the module treats that as a broken link and pages the phone
 * again. Turning the switch off for the session stops that page. The radio stays on.
 *
 * The previous value is remembered across process death so a crash does not leave the
 * driver's switch off.
 */
internal class GuangqiAutoConnect(
    private val backup: GuangqiAutoConnectBackup,
    private val memory: GuangqiAutoConnectMemory,
    private val log: (String) -> Unit,
    private val persist: () -> Unit = {},
) {
    /** Puts back a switch this process turned off and then died before restoring. */
    fun recoverAbandoned() {
        if (!memory.holding) return
        log("guangqiAutoConnect=recover abandoned hold previous=${memory.previousLabel()}")
        restore("process restart")
    }

    /**
     * Turns global auto-connect off. A second call in the same hold does not overwrite the
     * saved value with the 0 just written.
     */
    fun suspendForSession(reason: String) {
        if (!memory.holding) {
            val previous = backup.read()
            if (previous != null) {
                memory.remember(previous)
                if (previous != 0 && !backup.write(0)) {
                    log("guangqiAutoConnect=backup write 0 failed ($reason)")
                }
            } else {
                log("guangqiAutoConnect=backup unreadable; module command only ($reason)")
            }
            memory.holding = true
            persist()
        }
        val module = backup.setModule(false)
        log(
            "guangqiAutoConnect=off ($reason) previous=${memory.previousLabel()} " +
                "module=${if (module) "accepted" else "refused"}",
        )
    }

    fun restore(reason: String) {
        if (!memory.holding) return
        val previous = if (memory.hasPrevious) memory.previous else null
        memory.clear()
        persist()
        if (previous != null) {
            val module = backup.setModule(previous != 0)
            val written = backup.write(previous)
            log(
                "guangqiAutoConnect=restored $previous ($reason) " +
                    "module=${if (module) "accepted" else "refused"} backup=${if (written) "written" else "failed"}",
            )
        } else {
            log("guangqiAutoConnect=released without a saved value ($reason)")
        }
    }
}

internal interface GuangqiAutoConnectBackup {
    /** Null when the vendor backup store cannot be read. */
    fun read(): Int?
    fun write(value: Int): Boolean
    fun setModule(enabled: Boolean): Boolean
}

internal class GuangqiAutoConnectMemory {
    var holding: Boolean = false
    var hasPrevious: Boolean = false
    var previous: Int = 0

    fun remember(value: Int) {
        hasPrevious = true
        previous = value
    }

    fun clear() {
        holding = false
        hasPrevious = false
        previous = 0
    }

    fun previousLabel(): String = if (hasPrevious) previous.toString() else "unknown"
}

/** Vendor backup `BT_CANSYNC` plus the hidden `BluetoothAdapter.setBTAutoConn`. */
@SuppressLint("MissingPermission")
internal class GuangqiAutoConnectCarBackup(context: Context) : GuangqiAutoConnectBackup {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): GuangqiAutoConnectMemory = GuangqiAutoConnectMemory().also { stored ->
        stored.holding = prefs.getBoolean(KEY_HOLDING, false)
        stored.hasPrevious = prefs.getBoolean(KEY_HAS_PREVIOUS, false)
        stored.previous = prefs.getInt(KEY_PREVIOUS, 0)
    }

    fun save(memory: GuangqiAutoConnectMemory) {
        prefs.edit()
            .putBoolean(KEY_HOLDING, memory.holding)
            .putBoolean(KEY_HAS_PREVIOUS, memory.hasPrevious)
            .putInt(KEY_PREVIOUS, memory.previous)
            .commit()
    }

    override fun read(): Int? = runCatching {
        val backup = backupInstance()
        val index = backupIndex()
        backup.javaClass.getMethod("readInt", index.javaClass).invoke(backup, index) as Int
    }.getOrNull()?.takeIf { it >= 0 }

    override fun write(value: Int): Boolean = runCatching {
        val backup = backupInstance()
        val index = backupIndex()
        backup.javaClass.getMethod("writeInt", index.javaClass, Int::class.javaPrimitiveType)
            .invoke(backup, index, value) as Boolean
    }.getOrDefault(false)

    override fun setModule(enabled: Boolean): Boolean = runCatching {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        val method = adapter.javaClass.methods.firstOrNull {
            it.name == "setBTAutoConn" && it.parameterTypes.size == 1
        } ?: return false
        method.invoke(adapter, enabled) as? Boolean ?: true
    }.getOrDefault(false)

    private fun backupInstance(): Any {
        val type = Class.forName(BACKUP)
        return type.getMethod("getInstance").invoke(null)
            ?: throw IllegalStateException("SettingBackup.getInstance returned null")
    }

    private fun backupIndex(): Any {
        @Suppress("UNCHECKED_CAST")
        val type = Class.forName(INDEX) as Class<out Enum<*>>
        return java.lang.Enum.valueOf(type, "BT_CANSYNC")
    }

    private companion object {
        const val PREFS = "guangqi_auto_connect"
        const val KEY_HOLDING = "holding"
        const val KEY_HAS_PREVIOUS = "has_previous"
        const val KEY_PREVIOUS = "previous"
        const val BACKUP = "gaei.thundersoft.backup.SettingBackup"
        const val INDEX = "gaei.thundersoft.backup.SettingBackup\$BackupIndex"
    }
}

internal fun guangqiAutoConnect(context: Context, log: (String) -> Unit): GuangqiAutoConnect {
    val car = GuangqiAutoConnectCarBackup(context)
    val memory = car.load()
    return GuangqiAutoConnect(car, memory, log) { car.save(memory) }
}

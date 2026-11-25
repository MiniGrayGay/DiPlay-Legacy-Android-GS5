// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.orchestration;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Installs {@link GuangqiVolumeHook} in system_server. The car app's manifest declares this receiver
 * with {@code android:process="system"}; it does nothing in any other process. DiPlay sends
 * {@link GuangqiVolumeHook#ACTION_INSTALL} when it starts with the setting on, or when the driver
 * turns it on. There is no boot trigger: the hook takes keys only while DiPlay runs, and with the
 * setting off system_server never loads DiPlay's code.
 */
public final class GuangqiVolumeHookReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (!GuangqiVolumeHook.inSystemServer()) {
                Log.w("DiPlayVolumeHook", "not in system_server (" + GuangqiVolumeHook.processName() + "); ignored");
                return;
            }
            if (intent != null && GuangqiVolumeHook.ACTION_INSTALL.equals(intent.getAction())) {
                GuangqiVolumeHook.install(context, "DiPlay");
            }
        } catch (Throwable error) {
            Log.w("DiPlayVolumeHook", "receiver failed", error);
        }
    }
}

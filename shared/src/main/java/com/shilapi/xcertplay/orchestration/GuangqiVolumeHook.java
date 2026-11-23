// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.orchestration;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The Guangqi volume keys, taken inside system_server while DiPlay runs.
 *
 * <p>Every volume key on this head unit (wheel, panel, knob) ends in
 * {@code PhoneWindowManager.setVolume(step)}, which posts message 19 ({@code MSG_VOLUME_SET_DELAY},
 * {@code arg1} the signed step) to {@code PhoneWindowManager.mHandler}; that message unmutes and calls
 * {@code DspManager.adjustVolume}, whose callback makes SystemUI show the car's volume bar. This
 * class replaces that handler with one that, when {@link #decide} says so, sets the media or the
 * navigation volume itself with {@code DspService.setVolumeByFlag} (the call the car's own Settings
 * slider uses, which shows no bar) and tells DiPlay, which draws its own bar. Every other message,
 * and every volume message it does not take, goes to the original handler unchanged. Holding a key
 * repeats {@code setVolume(±1)} through the same message, so a held key is taken too.
 *
 * <p>The decision only reads fields. The DSP calls take {@code DspService.mLock}, which the car holds
 * for its whole navigation-to-media fade, so they run on this hook's own thread, as the car's own
 * {@code adjustVolume} hands them to the DSP's handler, and the window policy thread never waits.
 *
 * <p>It runs in system_server: {@link GuangqiVolumeHookReceiver} is declared with
 * {@code android:process="system"}, as the ROM's SettingsProvider is. It is written in Java so it
 * needs nothing from the Kotlin runtime, and kept in the main dex because that process never installs
 * the secondary dex files. Every entry catches {@link Throwable}: an exception that escaped here would
 * restart system_server. A reboot removes it; {@link #PROP_SWITCH} turns it off at once.
 */
public final class GuangqiVolumeHook {
    /** Raised when the hook's behaviour changes; system_server keeps an older one until a reboot. */
    public static final int VERSION = 1;

    public static final String ACTION_INSTALL = "com.shihab.diplay.action.INSTALL_VOLUME_HOOK";
    public static final String ACTION_CAR_VOLUME = "com.shihab.diplay.action.CAR_VOLUME";
    public static final String ACTION_HOOK_STATE = "com.shihab.diplay.action.VOLUME_HOOK_STATE";

    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_VALUE = "value";
    public static final String EXTRA_STEP = "step";
    public static final String EXTRA_REASON = "reason";
    public static final String EXTRA_VERSION = "version";
    public static final String EXTRA_INSTALLED = "installed";
    public static final String EXTRA_DETAIL = "detail";

    public static final int KIND_PASS = 0;
    public static final int KIND_MEDIA = 1;
    public static final int KIND_NAVI = 2;

    /** "1" while the driver's setting is on. persist.sys.* is a prefix the system uid may set. */
    public static final String PROP_SWITCH = "persist.sys.diplay.vol_hook";
    /** The DiPlay process the keys belong to; the hook checks it is still alive at every key. */
    public static final String PROP_PID = "sys.diplay.vol_pid";
    /** {@link SystemClock#uptimeMillis()} until which the keys set the navigation volume. */
    public static final String PROP_NAVI_UNTIL = "sys.diplay.navi_until";

    public static final int MAX_VOLUME = 30;
    static final int MSG_VOLUME_SET_DELAY = 19;
    static final int DSP_FLAG_MEDIA = 0;
    static final int DSP_FLAG_NAVI = 2;
    private static final String TAG = "DiPlayVolumeHook";

    private GuangqiVolumeHook() {
    }

    /**
     * What a volume key does. The car keeps it when the setting is off or DiPlay is gone, while
     * reversing, while the car itself is in its navigation mode, and outside the media policies
     * (calls and the stock voice assistant have their own volumes).
     */
    public static int decide(boolean switchOn, boolean diplayAlive, int rvcState, int naviState, int policy,
                             long now, long naviUntil) {
        if (!switchOn || !diplayAlive) return KIND_PASS;
        if (rvcState != 0) return KIND_PASS;
        if (naviState == 1) return KIND_PASS;
        if (!isMediaPolicy(policy)) return KIND_PASS;
        return now < naviUntil ? KIND_NAVI : KIND_MEDIA;
    }

    /**
     * {@code DspService.isMediaPolicy}. Its "none yet" (-1) is left to the car: {@code adjustVolume}
     * treats it as policy 0, but {@code setVolumeByFlag} would only store the value.
     */
    static boolean isMediaPolicy(int policy) {
        return policy == 0 || policy == 2 || policy == 4;
    }

    public static int nextVolume(int current, int step) {
        return Math.max(0, Math.min(MAX_VOLUME, current + step));
    }

    static String passReason(boolean switchOn, boolean diplayAlive, int rvcState, int naviState, int policy) {
        if (!switchOn) return "setting off";
        if (!diplayAlive) return "DiPlay not running";
        if (rvcState != 0) return "reversing (rvc " + rvcState + ")";
        if (naviState == 1) return "car in its navigation mode";
        if (!isMediaPolicy(policy)) return "car policy " + policy + " has its own volume";
        return "";
    }

    /** True only in system_server, the one process this class may change anything in. */
    public static boolean inSystemServer() {
        return "system_server".equals(processName());
    }

    public static String processName() {
        return cmdline("/proc/self/cmdline");
    }

    public static boolean switchOn() {
        return "1".equals(property(PROP_SWITCH, "0"));
    }

    /** Called by {@link GuangqiVolumeHookReceiver} in system_server. Never throws. */
    static void install(Context context, String trigger) {
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            final Targets targets = Targets.find(app);
            final Handler current = (Handler) targets.handlerField.get(targets.policy);
            // Swap on the policy's own thread, so no message is handled half-way through.
            current.post(new Runnable() {
                @Override
                public void run() {
                    swap(app, targets, trigger);
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "install failed", error);
            notifyState(app, false, "install failed (" + trigger + "): " + error);
        }
    }

    static void swap(Context app, Targets targets, String trigger) {
        try {
            Handler current = (Handler) targets.handlerField.get(targets.policy);
            Handler original = current;
            if (VolumeHandler.class.getName().equals(current.getClass().getName())) {
                // Ours, perhaps from an earlier DiPlay whose classes system_server still holds.
                int version = readInt(current, "version", 0);
                if (current instanceof VolumeHandler || version >= VERSION) {
                    notifyState(app, true, "already installed v" + version + " (" + trigger + ")");
                    return;
                }
                original = (Handler) readField(current, "original");
            }
            targets.handlerField.set(targets.policy, new VolumeHandler(original, targets));
            Log.i(TAG, "installed v" + VERSION + " on PhoneWindowManager.mHandler (" + trigger + ")");
            notifyState(app, true, "installed v" + VERSION + " on PhoneWindowManager.mHandler (" + trigger + ")");
        } catch (Throwable error) {
            Log.w(TAG, "swap failed", error);
            notifyState(app, false, "swap failed (" + trigger + "): " + error);
        }
    }

    private static void notifyState(Context app, boolean installed, String detail) {
        try {
            send(app, new Intent(ACTION_HOOK_STATE).setPackage(app.getPackageName())
                .putExtra(EXTRA_INSTALLED, installed)
                .putExtra(EXTRA_VERSION, VERSION)
                .putExtra(EXTRA_DETAIL, detail));
        } catch (Throwable error) {
            Log.w(TAG, "state broadcast failed", error);
        }
    }

    /** The objects the hook reads and calls, all checked before anything is replaced. */
    static final class Targets {
        final Context app;
        final Object policy;
        final Field handlerField;
        final Field rvcField;
        final Object dsp;
        final Field dspPolicy;
        final Field dspNaviState;
        final Method getVolumeByFlag;
        final Method setVolumeByFlag;
        final Method setUnmute;
        private Handler worker;

        private Targets(Context app, Object policy, Field handlerField, Field rvcField, Object dsp,
                        Field dspPolicy, Field dspNaviState, Method getVolumeByFlag, Method setVolumeByFlag,
                        Method setUnmute) {
            this.app = app;
            this.policy = policy;
            this.handlerField = handlerField;
            this.rvcField = rvcField;
            this.dsp = dsp;
            this.dspPolicy = dspPolicy;
            this.dspNaviState = dspNaviState;
            this.getVolumeByFlag = getVolumeByFlag;
            this.setVolumeByFlag = setVolumeByFlag;
            this.setUnmute = setUnmute;
        }

        static Targets find(Context app) throws Exception {
            Object wms = service("window");
            Object policy = field(wms.getClass(), "mPolicy").get(wms);
            if (policy == null) throw new IllegalStateException("no window policy");
            Field handlerField = field(policy.getClass(), "mHandler");
            if (!(handlerField.get(policy) instanceof Handler)) throw new IllegalStateException("no policy handler");
            Field rvcField = field(policy.getClass(), "mRvcAvmState");
            Object dsp = service("dsp");
            Field dspPolicy = field(dsp.getClass(), "mPolicy");
            Field dspNaviState = field(dsp.getClass(), "mNaviState");
            Method get = dsp.getClass().getMethod("getVolumeByFlag", int.class);
            Method set = dsp.getClass().getMethod("setVolumeByFlag", int.class, int.class);
            Method unmute = dsp.getClass().getMethod("setUnmute");
            return new Targets(app, policy, handlerField, rvcField, dsp, dspPolicy, dspNaviState, get, set, unmute);
        }

        /** Returns true when the key was handled here, false to give it to the car. */
        boolean handle(int step) throws Exception {
            if (step == 0) return false;
            boolean on = switchOn();
            boolean alive = diplayAlive(app.getPackageName(), property(PROP_PID, ""));
            int rvc = rvcState();
            int naviState = dspNaviState.getInt(dsp);
            int carPolicy = dspPolicy.getInt(dsp);
            long now = SystemClock.uptimeMillis();
            long until = parseLong(property(PROP_NAVI_UNTIL, "0"));
            int kind = decide(on, alive, rvc, naviState, carPolicy, now, until);
            if (kind == KIND_PASS) {
                if (on && alive) {
                    tell(KIND_PASS, -1, step, passReason(on, alive, rvc, naviState, carPolicy));
                }
                return false;
            }
            final int flag = kind == KIND_NAVI ? DSP_FLAG_NAVI : DSP_FLAG_MEDIA;
            final int taken = kind;
            final int signed = step;
            final String reason = kind == KIND_NAVI ? "guidance" : "media policy " + carPolicy;
            worker().post(new Runnable() {
                @Override
                public void run() {
                    apply(taken, flag, signed, reason);
                }
            });
            return true;
        }

        /** On the hook's thread: what the car's message 19 does, without its bar. */
        void apply(int kind, int flag, int step, String reason) {
            try {
                // The car unmutes before every step, also at the end of the range.
                setUnmute.invoke(dsp);
                int current = (Integer) getVolumeByFlag.invoke(dsp, flag);
                int next = nextVolume(current, step);
                setVolumeByFlag.invoke(dsp, next, flag);
                tell(kind, next, step, reason);
            } catch (Throwable error) {
                Log.w(TAG, "volume step failed", error);
                tell(KIND_PASS, -1, step, "DSP call failed: " + error);
            }
        }

        private synchronized Handler worker() {
            if (worker == null) {
                HandlerThread thread = new HandlerThread("diplay-volume-hook");
                thread.start();
                worker = new Handler(thread.getLooper());
            }
            return worker;
        }

        private int rvcState() throws Exception {
            Object rvc = rvcField.get(policy);
            if (rvc == null) return 0;
            return field(rvc.getClass(), "state").getInt(rvc);
        }

        private void tell(int kind, int value, int step, String reason) {
            try {
                send(app, new Intent(ACTION_CAR_VOLUME).setPackage(app.getPackageName())
                    .putExtra(EXTRA_KIND, kind)
                    .putExtra(EXTRA_VALUE, value)
                    .putExtra(EXTRA_STEP, step)
                    .putExtra(EXTRA_REASON, reason)
                    .putExtra(EXTRA_VERSION, VERSION));
            } catch (Throwable error) {
                Log.w(TAG, "volume broadcast failed", error);
            }
        }
    }

    /** Takes message 19 when {@link Targets#handle} says so; everything else goes on unchanged. */
    static final class VolumeHandler extends Handler {
        final Handler original;
        final int version = VERSION;
        private final Targets targets;

        VolumeHandler(Handler original, Targets targets) {
            super(original.getLooper());
            this.original = original;
            this.targets = targets;
        }

        @Override
        public void dispatchMessage(Message msg) {
            if (msg.what == MSG_VOLUME_SET_DELAY && msg.getCallback() == null) {
                try {
                    if (targets.handle(msg.arg1)) return;
                } catch (Throwable error) {
                    Log.w(TAG, "volume key left to the car", error);
                }
            }
            original.dispatchMessage(msg);
        }
    }

    /**
     * To this user only: a plain {@code sendBroadcast} from system_server logs a warning with a
     * stack trace at every call.
     */
    private static void send(Context app, Intent intent) {
        app.sendBroadcastAsUser(intent, Process.myUserHandle());
    }

    static boolean diplayAlive(String packageName, String pid) {
        long value = parseLong(pid);
        if (value <= 0) return false;
        return packageName.equals(cmdline("/proc/" + value + "/cmdline"));
    }

    static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String cmdline(String path) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(path);
            byte[] buffer = new byte[256];
            int read = in.read(buffer);
            if (read <= 0) return "";
            int end = 0;
            while (end < read && buffer[end] != 0) end++;
            return new String(buffer, 0, end, "UTF-8");
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static String property(String key, String fallback) {
        try {
            Object value = Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class, String.class).invoke(null, key, fallback);
            return value instanceof String ? (String) value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Object service(String name) throws Exception {
        Object service = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
            .invoke(null, name);
        if (!(service instanceof IBinder)) throw new IllegalStateException("no " + name + " service");
        return service;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Object readField(Object target, String name) throws Exception {
        return field(target.getClass(), name).get(target);
    }

    private static int readInt(Object target, String name, int fallback) {
        try {
            return field(target.getClass(), name).getInt(target);
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}

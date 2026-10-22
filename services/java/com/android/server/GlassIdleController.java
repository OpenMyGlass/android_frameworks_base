/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2026 OpenMyGlass
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.IProcessObserver;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.TimeUtils;

import com.android.internal.os.BackgroundThread;
import com.android.server.power.PowerManagerService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

/**
 * Glass device idle: a small Doze for when Glass is off the head.
 *
 * While idle, the alarms and partial wake locks of apps that are not exempt are
 * held back (AlarmManagerService.setDeviceIdle, PowerManagerService
 * .setDeviceIdleModeInternal) and run in short maintenance windows.  The states and
 * timeouts are those of Android 6.0's DeviceIdleController (frameworks/base commits
 * 8ad2af7e5b28f71e7bdfc1bb097937c6c1d22b18 and 88e98dfa59e68a860d8c3b462ec03bc0b06d6b5c),
 * kept in GlassIdlePolicy.  What differs for Glass:
 *
 * - Entry: screen off, not plugged in, and not known to be worn.  The wear state
 *   comes from ACTION_WEARER_STATE, which the Glass hub service sends; with no
 *   report, the inactive timeout is the long one.  Significant motion, when the
 *   sensor HAL has that sensor, leaves idle as in 6.0.
 * - Exempt: system uids (below FIRST_APPLICATION_UID), packages in
 *   config_glassIdleWhitelist, and apps with a process that is visible or runs a
 *   foreground service (the exemption of 6.0's power manager, which uses
 *   the uid process state, d23e0d6901935588f9472bd7073fea0009581e9b).  The
 *   exemption covers alarms and wake locks.  With the screen off (the only time
 *   Glass idles), foreground importance does not count: 4.4 has no TOP_SLEEPING
 *   state, so the top activity's process stays at foreground importance, and 4.4 also
 *   reports foreground importance for a process that is running a broadcast receiver
 *   or service code, or that a foreground client is bound to, which 6.0 does not
 *   exempt.  Visible and perceptible (a foreground service) count.  A process that
 *   runs a foreground service but is at foreground importance for one of those
 *   reasons is not exempt until it drops back (4.4 reports one importance per
 *   process).
 * - No network, sync or job restrictions, no new public API.
 *
 * Turned on by config_glassIdleEnabled; persist.glass.idle=0 turns it off at run
 * time.  "dumpsys glassidle" shows the state; "dumpsys glassidle help" lists the
 * test commands.  Only the plug state is read from the battery; nothing here
 * changes charging, the battery, thermal limits, voltages or frequencies.
 */
public final class GlassIdleController extends Binder {
    private static final String TAG = "GlassIdle";
    static final String SERVICE_NAME = "glassidle";

    /**
     * Sent by the Glass hub service; the sender needs DEVICE_POWER, a signature
     * permission, so the sender must be signed with the platform key.  Extra: EXTRA_WORN.
     */
    public static final String ACTION_WEARER_STATE =
            "com.android.server.glass.action.WEARER_STATE";
    public static final String EXTRA_WORN = "worn";

    private static final String ACTION_STEP_ALARM =
            "com.android.server.glass.action.IDLE_STEP";
    private static final String PROP_ENABLED = "persist.glass.idle";

    private final Context mContext;
    private final AlarmManagerService mAlarmService;
    private final PowerManagerService mPowerService;
    private final Handler mHandler;
    private final boolean mConfigEnabled;
    private final String[] mWhitelistPackages;

    // Guarded by this; changed on mHandler's thread only.
    private final GlassIdlePolicy mPolicy = GlassIdlePolicy.withDefaults();
    private boolean mSystemReady;
    private boolean mCommandEnabled = true;
    private boolean mScreenOn = true;
    private boolean mPlugged;
    private int mWorn = GlassIdlePolicy.WORN_UNKNOWN;
    private boolean mAppliedIdle;
    private int[] mWhitelistAppIds = new int[0];
    private int[] mExemptAppIds = new int[0];
    // Per pid: uid and importance, for the processes the activity manager reports.
    private final SparseIntArray mProcUids = new SparseIntArray();
    private final SparseIntArray mProcImportance = new SparseIntArray();
    private boolean mExemptUpdatePending;
    private AlarmManager mAlarmManager;
    private PendingIntent mStepIntent;
    private long mScheduledDeadline = -1;
    private SensorManager mSensorManager;
    private Sensor mMotionSensor;
    private boolean mMotionArmed;
    private int mMotionCount;
    private int mIdleCount;

    GlassIdleController(Context context, AlarmManagerService alarm, PowerManagerService power) {
        mContext = context;
        mAlarmService = alarm;
        mPowerService = power;
        mHandler = BackgroundThread.getHandler();
        mConfigEnabled = context.getResources().getBoolean(
                com.android.internal.R.bool.config_glassIdleEnabled);
        mWhitelistPackages = context.getResources().getStringArray(
                com.android.internal.R.array.config_glassIdleWhitelist);
    }

    /** Called once the activity manager is ready. */
    void systemReady() {
        if (!mConfigEnabled) {
            Slog.i(TAG, "Glass device idle is off (config_glassIdleEnabled)");
            return;
        }
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                systemReadyOnHandler();
            }
        });
    }

    private void systemReadyOnHandler() {
        synchronized (this) {
            mSystemReady = true;
            mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
            mStepIntent = PendingIntent.getBroadcast(mContext, 0,
                    new Intent(ACTION_STEP_ALARM).setPackage("android")
                            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY), 0);
            mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
            mMotionSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            mScreenOn = pm.isScreenOn();
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        mContext.registerReceiver(mReceiver, filter, null, mHandler);

        IntentFilter protectedFilter = new IntentFilter();
        protectedFilter.addAction(ACTION_STEP_ALARM);
        protectedFilter.addAction(ACTION_WEARER_STATE);
        mContext.registerReceiver(mReceiver, protectedFilter,
                android.Manifest.permission.DEVICE_POWER, mHandler);

        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme("package");
        mContext.registerReceiver(mPackageReceiver, packageFilter, null, mHandler);

        try {
            ActivityManagerNative.getDefault().registerProcessObserver(mProcessObserver);
            List<ActivityManager.RunningAppProcessInfo> procs =
                    ActivityManagerNative.getDefault().getRunningAppProcesses();
            synchronized (this) {
                if (procs != null) {
                    for (ActivityManager.RunningAppProcessInfo proc : procs) {
                        mProcUids.put(proc.pid, proc.uid);
                        mProcImportance.put(proc.pid, proc.importance);
                    }
                }
            }
        } catch (RemoteException e) {
            // Same process; cannot happen.
        }

        synchronized (this) {
            updateWhitelistLocked();
            updateLocked();
        }
        Slog.i(TAG, "Glass device idle ready; significant motion sensor: "
                + (mMotionSensor != null ? mMotionSensor.getName() : "none"));
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            synchronized (GlassIdleController.this) {
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    mScreenOn = true;
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    mScreenOn = false;
                } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                    // Read only: whether a power source is connected.
                    mPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                } else if (ACTION_WEARER_STATE.equals(action)) {
                    mWorn = intent.getBooleanExtra(EXTRA_WORN, true)
                            ? GlassIdlePolicy.WORN_YES : GlassIdlePolicy.WORN_NO;
                } else if (ACTION_STEP_ALARM.equals(action)) {
                    mScheduledDeadline = -1;
                    final long now = SystemClock.elapsedRealtime();
                    // Refresh the conditions first, so persist.glass.idle=0 also ends
                    // idle at the next step.
                    mPolicy.setConditions(isEnabledLocked(), mScreenOn, mPlugged, mWorn, now);
                    mPolicy.onAlarm(now);
                    applyLocked();
                    return;
                }
                if (Intent.ACTION_SCREEN_ON.equals(action)
                        || Intent.ACTION_SCREEN_OFF.equals(action)) {
                    // Whether foreground importance counts depends on the screen.
                    scheduleExemptUpdateLocked();
                }
                updateLocked();
            }
        }
    };

    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (GlassIdleController.this) {
                updateWhitelistLocked();
            }
        }
    };

    private final IProcessObserver mProcessObserver = new IProcessObserver.Stub() {
        @Override
        public void onForegroundActivitiesChanged(final int pid, final int uid,
                final boolean foreground) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    synchronized (GlassIdleController.this) {
                        // Only the importance matters (isExemptProcessLocked).
                        mProcUids.put(pid, uid);
                    }
                }
            });
        }

        @Override
        public void onImportanceChanged(final int pid, final int uid, final int importance) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    synchronized (GlassIdleController.this) {
                        mProcUids.put(pid, uid);
                        mProcImportance.put(pid, importance);
                        scheduleExemptUpdateLocked();
                    }
                }
            });
        }

        @Override
        public void onProcessDied(final int pid, final int uid) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    synchronized (GlassIdleController.this) {
                        mProcUids.delete(pid);
                        mProcImportance.delete(pid);
                        scheduleExemptUpdateLocked();
                    }
                }
            });
        }
    };

    private final TriggerEventListener mMotionListener = new TriggerEventListener() {
        @Override
        public void onTrigger(TriggerEvent event) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    synchronized (GlassIdleController.this) {
                        // The sensor disarms itself after one event.
                        mMotionArmed = false;
                        mMotionCount++;
                        if (mPolicy.onMotion(SystemClock.elapsedRealtime())) {
                            applyLocked();
                        } else {
                            armMotionLocked();
                        }
                    }
                }
            });
        }
    };

    /**
     * Foreground, visible or a foreground service (perceptible): 6.0 keeps these apps'
     * wake locks.  With the screen off, foreground importance does not count: in 4.4 it
     * also means the top activity behind the off screen (no TOP_SLEEPING), a running
     * broadcast receiver or service, or a binding from a foreground client.
     */
    private boolean isExemptProcessLocked(int index) {
        final int importance = mProcImportance.valueAt(index);
        if (importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
            return false;
        }
        if (!mScreenOn
                && importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            return false;
        }
        return true;
    }

    /** Coalesces bursts of process changes into one push to the alarm and power managers. */
    private void scheduleExemptUpdateLocked() {
        if (mExemptUpdatePending) {
            return;
        }
        mExemptUpdatePending = true;
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (GlassIdleController.this) {
                    mExemptUpdatePending = false;
                    updateExemptLocked();
                }
            }
        }, 1000);
    }

    private void updateWhitelistLocked() {
        final PackageManager pm = mContext.getPackageManager();
        int[] appIds = new int[mWhitelistPackages.length];
        int n = 0;
        for (String pkg : mWhitelistPackages) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                appIds[n++] = UserHandle.getAppId(ai.uid);
            } catch (PackageManager.NameNotFoundException e) {
                // Not installed (yet).
            }
        }
        mWhitelistAppIds = sortedUnique(appIds, n);
        updateExemptLocked();
    }

    private void updateExemptLocked() {
        final int np = mProcImportance.size();
        int[] all = new int[mWhitelistAppIds.length + np];
        System.arraycopy(mWhitelistAppIds, 0, all, 0, mWhitelistAppIds.length);
        int n = mWhitelistAppIds.length;
        for (int i = 0; i < np; i++) {
            if (!isExemptProcessLocked(i)) {
                continue;
            }
            final int appId = UserHandle.getAppId(mProcUids.get(mProcImportance.keyAt(i), -1));
            if (appId >= Process.FIRST_APPLICATION_UID) {
                all[n++] = appId;
            }
        }
        final int[] exempt = sortedUnique(all, n);
        if (Arrays.equals(exempt, mExemptAppIds)) {
            return;
        }
        mExemptAppIds = exempt;
        if (mSystemReady) {
            mPowerService.setDeviceIdleWhitelistInternal(exempt);
            mAlarmService.setDeviceIdleWhitelist(exempt);
        }
    }

    private static int[] sortedUnique(int[] values, int count) {
        int[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        int n = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (n == 0 || sorted[n - 1] != sorted[i]) {
                sorted[n++] = sorted[i];
            }
        }
        return Arrays.copyOf(sorted, n);
    }

    private boolean isEnabledLocked() {
        return mConfigEnabled && mCommandEnabled && SystemProperties.getBoolean(PROP_ENABLED, true);
    }

    /** Feeds the current conditions to the policy and applies the result. */
    private void updateLocked() {
        if (!mSystemReady) {
            return;
        }
        mPolicy.setConditions(isEnabledLocked(), mScreenOn, mPlugged, mWorn,
                SystemClock.elapsedRealtime());
        applyLocked();
    }

    private void applyLocked() {
        final boolean idle = mPolicy.isDeviceIdle();
        if (idle != mAppliedIdle) {
            mAppliedIdle = idle;
            if (idle) {
                mIdleCount++;
            }
            Slog.i(TAG, (idle ? "Entering" : "Leaving") + " device idle ("
                    + GlassIdlePolicy.stateToString(mPolicy.getState()) + ")");
            // Wake locks first: deferred alarms delivered on exit need them.
            mPowerService.setDeviceIdleModeInternal(idle);
            mAlarmService.setDeviceIdle(idle);
        }
        if (mPolicy.getState() == GlassIdlePolicy.STATE_ACTIVE) {
            disarmMotionLocked();
        } else {
            armMotionLocked();
        }
        scheduleLocked();
    }

    private void scheduleLocked() {
        final long deadline = mPolicy.getDeadline();
        if (deadline == mScheduledDeadline) {
            return;
        }
        mScheduledDeadline = deadline;
        if (deadline < 0) {
            mAlarmManager.cancel(mStepIntent);
        } else {
            // A system alarm without work source: it runs while idle.
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, mStepIntent);
        }
    }

    private void armMotionLocked() {
        if (mMotionSensor != null && !mMotionArmed) {
            mMotionArmed = mSensorManager.requestTriggerSensor(mMotionListener, mMotionSensor);
        }
    }

    private void disarmMotionLocked() {
        if (mMotionSensor != null && mMotionArmed) {
            mSensorManager.cancelTriggerSensor(mMotionListener, mMotionSensor);
            mMotionArmed = false;
        }
    }

    @Override
    protected void dump(FileDescriptor fd, final PrintWriter pw, final String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump glassidle from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
            return;
        }
        if (args != null && args.length > 0 && !"-a".equals(args[0])) {
            final int uid = Binder.getCallingUid();
            if (uid != Process.SHELL_UID && uid != 0 /* root */
                    && mContext.checkCallingOrSelfPermission(
                            android.Manifest.permission.DEVICE_POWER)
                            != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: glassidle commands need the shell, root or "
                        + "DEVICE_POWER");
                return;
            }
            final String cmd = args[0];
            if ("help".equals(cmd) || "-h".equals(cmd)) {
                pw.println("dumpsys glassidle [enable|disable|force-idle|step|unforce|help]");
                pw.println("  enable, disable: turn device idle on or off until reboot");
                pw.println("    (persist.glass.idle=0 turns it off across reboots)");
                pw.println("  force-idle: idle now, whatever the conditions");
                pw.println("  step: go to the next state now, whatever the conditions");
                pw.println("  unforce: back to the screen, plug and wear conditions");
                return;
            }
            if (!mConfigEnabled) {
                pw.println("Glass device idle is off (config_glassIdleEnabled)");
                return;
            }
            // Run on the handler thread, which owns the state; wait for it.
            final Object done = new Object();
            final boolean[] finished = new boolean[1];
            final String[] result = new String[1];
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    final StringBuilder out = new StringBuilder();
                    synchronized (GlassIdleController.this) {
                        runCommandLocked(cmd, out);
                    }
                    synchronized (done) {
                        result[0] = out.toString();
                        finished[0] = true;
                        done.notifyAll();
                    }
                }
            });
            synchronized (done) {
                final long end = SystemClock.uptimeMillis() + 5000;
                while (!finished[0]) {
                    final long wait = end - SystemClock.uptimeMillis();
                    if (wait <= 0) {
                        pw.println("Timed out; the command may still run");
                        return;
                    }
                    try {
                        done.wait(wait);
                    } catch (InterruptedException e) {
                        // Keep waiting.
                    }
                }
                pw.print(result[0]);
            }
            return;
        }
        synchronized (this) {
            dumpLocked(pw);
        }
    }

    private void runCommandLocked(String cmd, StringBuilder out) {
        if (!mSystemReady) {
            out.append("Not ready\n");
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if ("enable".equals(cmd)) {
            mCommandEnabled = true;
            updateLocked();
        } else if ("disable".equals(cmd)) {
            mCommandEnabled = false;
            mPolicy.unforce(now);
            updateLocked();
        } else if ("force-idle".equals(cmd)) {
            mPolicy.forceIdle(now);
            applyLocked();
        } else if ("step".equals(cmd)) {
            mPolicy.step(now);
            applyLocked();
        } else if ("unforce".equals(cmd)) {
            mPolicy.unforce(now);
            updateLocked();
        } else {
            out.append("Unknown command: ").append(cmd).append('\n');
            return;
        }
        out.append("Now ").append(GlassIdlePolicy.stateToString(mPolicy.getState()))
                .append('\n');
    }

    private void dumpLocked(PrintWriter pw) {
        pw.println("Glass device idle (dumpsys glassidle help for commands):");
        pw.print("  config enabled="); pw.print(mConfigEnabled);
        pw.print(" command enabled="); pw.print(mCommandEnabled);
        pw.print(" " + PROP_ENABLED + "="); pw.println(SystemProperties.get(PROP_ENABLED));
        if (!mConfigEnabled) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        pw.print("  state="); pw.print(GlassIdlePolicy.stateToString(mPolicy.getState()));
        if (mPolicy.isForced()) {
            pw.print(" (forced)");
        }
        pw.print(" applied idle="); pw.println(mAppliedIdle);
        pw.print("  screen on="); pw.print(mScreenOn);
        pw.print(" plugged="); pw.print(mPlugged);
        pw.print(" wear="); pw.println(GlassIdlePolicy.wornToString(mWorn));
        if (mPolicy.getDeadline() >= 0) {
            pw.print("  next step in ");
            TimeUtils.formatDuration(mPolicy.getDeadline() - now, pw);
            pw.println();
        }
        pw.print("  next idle period ");
        TimeUtils.formatDuration(mPolicy.getNextIdleTimeout(), pw);
        pw.println();
        pw.print("  motion sensor=");
        pw.print(mMotionSensor != null ? mMotionSensor.getName() : "none");
        pw.print(" armed="); pw.print(mMotionArmed);
        pw.print(" events="); pw.println(mMotionCount);
        pw.print("  idle periods since boot="); pw.println(mIdleCount);
        pw.print("  whitelist packages="); pw.println(Arrays.toString(mWhitelistPackages));
        pw.print("  whitelist app ids="); pw.println(Arrays.toString(mWhitelistAppIds));
        pw.print("  exempt app ids (whitelist and foreground)=");
        pw.println(Arrays.toString(mExemptAppIds));
    }
}

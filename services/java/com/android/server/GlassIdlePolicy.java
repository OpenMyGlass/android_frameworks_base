/*
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

/**
 * The state machine of the Glass device idle mode, in plain Java so that it can be
 * tested on a host (services/tests/glass).  It follows the states and default
 * timeouts of Android 6.0's DeviceIdleController (frameworks/base commits
 * 8ad2af7e5b28f71e7bdfc1bb097937c6c1d22b18 and 88e98dfa59e68a860d8c3b462ec03bc0b06d6b5c):
 * ACTIVE, then INACTIVE once the device may idle, then IDLE, alternating with
 * short MAINTENANCE windows whose spacing doubles up to a maximum.
 *
 * Glass differs from 6.0 in its entry condition: instead of waiting for the device
 * to be stationary, it uses whether Glass is worn when that is known.  The device
 * may idle when idle is enabled, the screen is off, it is not plugged in and it is
 * not known to be worn.  The inactive timeout is short when Glass is known to be
 * off the head and long (6.0's 30 minutes) when nothing reports the wear state.
 *
 * Not thread safe: the controller calls it from one handler thread.
 */
final class GlassIdlePolicy {
    static final int STATE_ACTIVE = 0;
    static final int STATE_INACTIVE = 1;
    static final int STATE_IDLE = 2;
    static final int STATE_MAINTENANCE = 3;

    static final int WORN_UNKNOWN = 0;
    static final int WORN_YES = 1;
    static final int WORN_NO = 2;

    /** An alarm may fire this much before its time and still count. */
    static final long ALARM_SLACK = 1000;

    final long mInactiveTimeoutUnknown;
    final long mInactiveTimeoutNotWorn;
    final long mIdleTimeout;
    final long mMaxIdleTimeout;
    final long mIdleFactor;
    final long mMaintenanceTimeout;

    private int mState = STATE_ACTIVE;
    private boolean mEnabled;
    private boolean mScreenOn = true;
    private boolean mPlugged;
    private int mWorn = WORN_UNKNOWN;
    private boolean mForced;
    private long mNextIdleTimeout;
    private long mDeadline = -1;

    GlassIdlePolicy(long inactiveTimeoutUnknown, long inactiveTimeoutNotWorn, long idleTimeout,
            long maxIdleTimeout, long idleFactor, long maintenanceTimeout) {
        mInactiveTimeoutUnknown = inactiveTimeoutUnknown;
        mInactiveTimeoutNotWorn = inactiveTimeoutNotWorn;
        mIdleTimeout = idleTimeout;
        mMaxIdleTimeout = maxIdleTimeout;
        mIdleFactor = idleFactor;
        mMaintenanceTimeout = maintenanceTimeout;
        mNextIdleTimeout = idleTimeout;
    }

    /** 6.0's defaults; 5 minutes off the head before idle. */
    static GlassIdlePolicy withDefaults() {
        final long minute = 60 * 1000;
        return new GlassIdlePolicy(30 * minute, 5 * minute, 60 * minute, 6 * 60 * minute, 2,
                5 * minute);
    }

    int getState() {
        return mState;
    }

    /** True while alarms and wake locks of apps that are not exempt are held. */
    boolean isDeviceIdle() {
        return mState == STATE_IDLE;
    }

    /** Elapsed time of the next step, or -1 if none is due. */
    long getDeadline() {
        return mDeadline;
    }

    boolean isForced() {
        return mForced;
    }

    int getWorn() {
        return mWorn;
    }

    long getNextIdleTimeout() {
        return mNextIdleTimeout;
    }

    boolean canIdle() {
        return mEnabled && !mScreenOn && !mPlugged && mWorn != WORN_YES;
    }

    /** Updates the conditions.  Returns true if the state changed. */
    boolean setConditions(boolean enabled, boolean screenOn, boolean plugged, int worn,
            long now) {
        final boolean wasNotWorn = mWorn == WORN_NO;
        mEnabled = enabled;
        mScreenOn = screenOn;
        mPlugged = plugged;
        mWorn = worn;
        if (mForced) {
            return false;
        }
        if (!canIdle()) {
            return becomeActive();
        }
        if (mState == STATE_ACTIVE) {
            return becomeInactive(now);
        }
        if (mState == STATE_INACTIVE && !wasNotWorn && mWorn == WORN_NO) {
            // Now known to be off the head: the shorter timeout applies from now.
            final long deadline = now + mInactiveTimeoutNotWorn;
            if (deadline < mDeadline) {
                mDeadline = deadline;
            }
        }
        return false;
    }

    /** Significant motion: start over, as 6.0 does.  Returns true if the state changed. */
    boolean onMotion(long now) {
        if (mForced || mState == STATE_ACTIVE) {
            return false;
        }
        final int oldState = mState;
        final long oldDeadline = mDeadline;
        mNextIdleTimeout = mIdleTimeout;
        becomeInactive(now);
        return oldState != mState || oldDeadline != mDeadline;
    }

    /** The step alarm fired.  Returns true if the state changed. */
    boolean onAlarm(long now) {
        if (mDeadline < 0 || now + ALARM_SLACK < mDeadline) {
            return false;
        }
        return stepLocked(now);
    }

    /** dumpsys "step": the next state now, whatever the conditions; stays forced. */
    boolean step(long now) {
        mForced = true;
        if (mState == STATE_ACTIVE) {
            mState = STATE_INACTIVE;
            mDeadline = now + inactiveTimeout();
            return true;
        }
        return stepLocked(now);
    }

    /** dumpsys "force-idle": idle now, whatever the conditions; stays forced. */
    boolean forceIdle(long now) {
        mForced = true;
        if (mState == STATE_IDLE) {
            return false;
        }
        mState = STATE_IDLE;
        mDeadline = now + mNextIdleTimeout;
        return true;
    }

    /** dumpsys "unforce": back to the conditions. */
    boolean unforce(long now) {
        if (!mForced) {
            return false;
        }
        mForced = false;
        final int oldState = mState;
        if (!canIdle()) {
            becomeActive();
        } else if (mState == STATE_ACTIVE) {
            becomeInactive(now);
        }
        return oldState != mState;
    }

    private boolean stepLocked(long now) {
        switch (mState) {
            case STATE_INACTIVE:
            case STATE_MAINTENANCE:
                mState = STATE_IDLE;
                mDeadline = now + mNextIdleTimeout;
                return true;
            case STATE_IDLE:
                mState = STATE_MAINTENANCE;
                mDeadline = now + mMaintenanceTimeout;
                mNextIdleTimeout = Math.min(mMaxIdleTimeout, mNextIdleTimeout * mIdleFactor);
                return true;
            default:
                return false;
        }
    }

    private long inactiveTimeout() {
        return mWorn == WORN_NO ? mInactiveTimeoutNotWorn : mInactiveTimeoutUnknown;
    }

    private boolean becomeActive() {
        mNextIdleTimeout = mIdleTimeout;
        mDeadline = -1;
        if (mState == STATE_ACTIVE) {
            return false;
        }
        mState = STATE_ACTIVE;
        return true;
    }

    private boolean becomeInactive(long now) {
        final boolean changed = mState != STATE_INACTIVE;
        mState = STATE_INACTIVE;
        mDeadline = now + inactiveTimeout();
        return changed;
    }

    static String stateToString(int state) {
        switch (state) {
            case STATE_ACTIVE: return "ACTIVE";
            case STATE_INACTIVE: return "INACTIVE";
            case STATE_IDLE: return "IDLE";
            case STATE_MAINTENANCE: return "MAINTENANCE";
            default: return Integer.toString(state);
        }
    }

    static String wornToString(int worn) {
        switch (worn) {
            case WORN_YES: return "worn";
            case WORN_NO: return "not worn";
            default: return "unknown";
        }
    }
}

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

// Host test, run by .github/workflows/glass-checks.yml (no Android classes, no JUnit).
// It is not an AOSP module: there is no Android.mk in this directory.
// sources: services/java/com/android/server/GlassIdlePolicy.java

package com.android.server;

public final class GlassIdlePolicyTest {
    private static final long MIN = 60 * 1000;
    private static int sChecks;

    private static void check(boolean condition, String what) {
        sChecks++;
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void checkState(GlassIdlePolicy p, int state, String what) {
        check(p.getState() == state, what + ": expected " + GlassIdlePolicy.stateToString(state)
                + ", got " + GlassIdlePolicy.stateToString(p.getState()));
    }

    /** Screen off, unplugged, wear unknown. */
    private static GlassIdlePolicy idleable(long now, int worn) {
        GlassIdlePolicy p = GlassIdlePolicy.withDefaults();
        p.setConditions(true, false, false, worn, now);
        return p;
    }

    private static void activeUntilAllowed() {
        GlassIdlePolicy p = GlassIdlePolicy.withDefaults();
        checkState(p, GlassIdlePolicy.STATE_ACTIVE, "initial");
        check(!p.setConditions(false, false, false, GlassIdlePolicy.WORN_NO, 0), "disabled");
        checkState(p, GlassIdlePolicy.STATE_ACTIVE, "disabled stays active");
        p.setConditions(true, true, false, GlassIdlePolicy.WORN_NO, 0);
        checkState(p, GlassIdlePolicy.STATE_ACTIVE, "screen on stays active");
        p.setConditions(true, false, true, GlassIdlePolicy.WORN_NO, 0);
        checkState(p, GlassIdlePolicy.STATE_ACTIVE, "plugged stays active");
        p.setConditions(true, false, false, GlassIdlePolicy.WORN_YES, 0);
        checkState(p, GlassIdlePolicy.STATE_ACTIVE, "worn stays active");
        check(p.getDeadline() == -1, "no deadline while active");
        check(p.setConditions(true, false, false, GlassIdlePolicy.WORN_UNKNOWN, 0),
                "unknown wear may idle");
        checkState(p, GlassIdlePolicy.STATE_INACTIVE, "inactive");
    }

    private static void timeoutsAndBackoff() {
        GlassIdlePolicy p = idleable(0, GlassIdlePolicy.WORN_UNKNOWN);
        check(p.getDeadline() == 30 * MIN, "30 min inactive with unknown wear");
        check(!p.onAlarm(10 * MIN), "early alarm ignored");
        checkState(p, GlassIdlePolicy.STATE_INACTIVE, "still inactive");
        check(p.onAlarm(30 * MIN - 500), "alarm within slack counts");
        checkState(p, GlassIdlePolicy.STATE_IDLE, "idle");
        check(p.isDeviceIdle(), "device idle");
        long t = p.getDeadline();
        check(t == 30 * MIN - 500 + 60 * MIN, "first idle 60 min");
        long[] idle = {120, 240, 360, 360};
        for (long expected : idle) {
            check(p.onAlarm(t), "to maintenance");
            checkState(p, GlassIdlePolicy.STATE_MAINTENANCE, "maintenance");
            check(!p.isDeviceIdle(), "maintenance is not idle");
            check(p.getDeadline() == t + 5 * MIN, "5 min window");
            t = p.getDeadline();
            check(p.onAlarm(t), "back to idle");
            checkState(p, GlassIdlePolicy.STATE_IDLE, "idle again");
            check(p.getDeadline() == t + expected * MIN, "idle " + expected + " min");
            t = p.getDeadline();
        }
    }

    private static void notWornIsShorter() {
        GlassIdlePolicy p = idleable(0, GlassIdlePolicy.WORN_NO);
        check(p.getDeadline() == 5 * MIN, "5 min when not worn");
        p = idleable(0, GlassIdlePolicy.WORN_UNKNOWN);
        p.setConditions(true, false, false, GlassIdlePolicy.WORN_NO, 2 * MIN);
        check(p.getDeadline() == 7 * MIN, "not-worn report shortens the countdown");
        p.setConditions(true, false, false, GlassIdlePolicy.WORN_NO, 3 * MIN);
        check(p.getDeadline() == 7 * MIN, "a repeated report does not push it back");
    }

    private static void exits() {
        GlassIdlePolicy p = idleable(0, GlassIdlePolicy.WORN_NO);
        p.onAlarm(5 * MIN);
        p.onAlarm(65 * MIN);
        checkState(p, GlassIdlePolicy.STATE_MAINTENANCE, "maintenance");
        check(p.getNextIdleTimeout() == 120 * MIN, "backoff grew");
        check(p.setConditions(true, true, false, GlassIdlePolicy.WORN_NO, 66 * MIN), "screen on");
        checkState(p, GlassIdlePolicy.STATE_ACTIVE, "screen on leaves idle");
        check(p.getNextIdleTimeout() == 60 * MIN, "backoff reset");

        p = idleable(0, GlassIdlePolicy.WORN_NO);
        p.onAlarm(5 * MIN);
        check(p.setConditions(true, false, true, GlassIdlePolicy.WORN_NO, 6 * MIN), "plug");
        checkState(p, GlassIdlePolicy.STATE_ACTIVE, "plugging in leaves idle");

        p = idleable(0, GlassIdlePolicy.WORN_NO);
        p.onAlarm(5 * MIN);
        check(p.setConditions(true, false, false, GlassIdlePolicy.WORN_YES, 6 * MIN), "worn");
        checkState(p, GlassIdlePolicy.STATE_ACTIVE, "putting Glass on leaves idle");

        p = idleable(0, GlassIdlePolicy.WORN_NO);
        p.onAlarm(5 * MIN);
        check(p.setConditions(false, false, false, GlassIdlePolicy.WORN_NO, 6 * MIN), "off");
        checkState(p, GlassIdlePolicy.STATE_ACTIVE, "disabling leaves idle");

        p = idleable(0, GlassIdlePolicy.WORN_UNKNOWN);
        p.onAlarm(30 * MIN);
        check(p.onMotion(31 * MIN), "motion");
        checkState(p, GlassIdlePolicy.STATE_INACTIVE, "motion restarts the countdown");
        check(p.getDeadline() == 61 * MIN, "new 30 min countdown");
        check(p.onMotion(40 * MIN), "motion while inactive moves the deadline");
        check(p.getDeadline() == 70 * MIN, "moved");
    }

    private static void forcing() {
        GlassIdlePolicy p = GlassIdlePolicy.withDefaults();
        p.setConditions(true, true, false, GlassIdlePolicy.WORN_YES, 0);
        check(p.forceIdle(0), "force-idle");
        check(p.isDeviceIdle() && p.isForced(), "forced idle");
        check(!p.setConditions(true, true, true, GlassIdlePolicy.WORN_YES, 1),
                "conditions ignored while forced");
        check(p.isDeviceIdle(), "still idle");
        check(p.unforce(2), "unforce");
        checkState(p, GlassIdlePolicy.STATE_ACTIVE, "back to the conditions");

        p = GlassIdlePolicy.withDefaults();
        check(p.step(0), "step 1");
        checkState(p, GlassIdlePolicy.STATE_INACTIVE, "step to inactive");
        check(p.step(1), "step 2");
        checkState(p, GlassIdlePolicy.STATE_IDLE, "step to idle");
        check(p.step(2), "step 3");
        checkState(p, GlassIdlePolicy.STATE_MAINTENANCE, "step to maintenance");
        check(p.unforce(3), "unforce after steps");
        checkState(p, GlassIdlePolicy.STATE_ACTIVE, "screen on by default");
        check(!p.unforce(4), "unforce twice");
    }

    public static void main(String[] args) {
        activeUntilAllowed();
        timeoutsAndBackoff();
        notWornIsShorter();
        exits();
        forcing();
        System.out.println("GlassIdlePolicyTest: " + sChecks + " checks passed");
    }
}

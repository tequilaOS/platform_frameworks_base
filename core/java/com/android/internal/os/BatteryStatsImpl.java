/*
 * Copyright (C) 2006-2007 The Android Open Source Project
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

package com.android.internal.os;

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.os.BatteryStats.Uid.NUM_PROCESS_STATE;
import static android.os.BatteryStatsManager.NUM_WIFI_STATES;
import static android.os.BatteryStatsManager.NUM_WIFI_SUPPL_STATES;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.usage.NetworkStatsManager;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.UidTraffic;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.usb.UsbManager;
import android.location.GnssSignalQuality;
import android.net.NetworkStats;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryConsumer;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.BluetoothBatteryStats;
import android.os.Build;
import android.os.Handler;
import android.os.IBatteryPropertiesRegistrar;
import android.os.Looper;
import android.os.Message;
import android.os.OsProtoEnums;
import android.os.Parcel;
import android.os.ParcelFormatException;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WakeLockStats;
import android.os.WorkSource;
import android.os.WorkSource.WorkChain;
import android.os.connectivity.CellularBatteryStats;
import android.os.connectivity.GpsBatteryStats;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.os.connectivity.WifiBatteryStats;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.Annotation.NetworkType;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.ModemActivityInfo;
import android.telephony.ServiceState;
import android.telephony.ServiceState.RegState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.IndentingPrintWriter;
import android.util.KeyValueListParser;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.LongSparseLongArray;
import android.util.MutableInt;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseDoubleArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.TimeUtils;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidActiveTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidClusterTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidFreqTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidUserSysTimeReader;
import com.android.internal.os.SystemServerCpuThreadReader.SystemServiceCpuThreadTimes;
import com.android.internal.power.MeasuredEnergyStats;
import com.android.internal.power.MeasuredEnergyStats.StandardPowerBucket;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.XmlUtils;
import com.android.net.module.util.NetworkCapabilitiesUtils;

import libcore.util.EmptyArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * All information we are collecting about things that can happen that impact
 * battery life.  All times are represented in microseconds except where indicated
 * otherwise.
 */
public class BatteryStatsImpl extends BatteryStats {
    private static final String TAG = "BatteryStatsImpl";
    private static final boolean DEBUG = false;
    public static final boolean DEBUG_ENERGY = false;
    private static final boolean DEBUG_ENERGY_CPU = DEBUG_ENERGY;
    private static final boolean DEBUG_BINDER_STATS = false;
    private static final boolean DEBUG_MEMORY = false;
    private static final boolean DEBUG_HISTORY = false;

    // TODO: remove "tcp" from network methods, since we measure total stats.

    // In-memory Parcel magic number, used to detect attempts to unmarshall bad data
    private static final int MAGIC = 0xBA757475; // 'BATSTATS'

    // Current on-disk Parcel version. Must be updated when the format of the parcelable changes
    public static final int VERSION = 211;

    // The maximum number of names wakelocks we will keep track of
    // per uid; once the limit is reached, we batch the remaining wakelocks
    // in to one common name.
    private static final int MAX_WAKELOCKS_PER_UID;

    static {
        if (ActivityManager.isLowRamDeviceStatic()) {
            MAX_WAKELOCKS_PER_UID = 40;
        } else {
            MAX_WAKELOCKS_PER_UID = 200;
        }
    }

    // Number of transmit power states the Wifi controller can be in.
    private static final int NUM_WIFI_TX_LEVELS = 1;

    // Number of transmit power states the Bluetooth controller can be in.
    private static final int NUM_BT_TX_LEVELS = 1;

    /**
     * Holding a wakelock costs more than just using the cpu.
     * Currently, we assign only half the cpu time to an app that is running but
     * not holding a wakelock. The apps holding wakelocks get the rest of the blame.
     * If no app is holding a wakelock, then the distribution is normal.
     */
    @VisibleForTesting
    public static final int WAKE_LOCK_WEIGHT = 50;

    public static final int RESET_REASON_CORRUPT_FILE = 1;
    public static final int RESET_REASON_ADB_COMMAND = 2;
    public static final int RESET_REASON_FULL_CHARGE = 3;
    public static final int RESET_REASON_MEASURED_ENERGY_BUCKETS_CHANGE = 4;

    protected Clock mClock;

    private final AtomicFile mStatsFile;
    public final AtomicFile mCheckinFile;
    public final AtomicFile mDailyFile;

    static final int MSG_REPORT_CPU_UPDATE_NEEDED = 1;
    static final int MSG_REPORT_POWER_CHANGE = 2;
    static final int MSG_REPORT_CHARGING = 3;
    static final int MSG_REPORT_RESET_STATS = 4;
    static final long DELAY_UPDATE_WAKELOCKS = 60 * 1000;

    private static final double MILLISECONDS_IN_HOUR = 3600 * 1000;
    private static final long MILLISECONDS_IN_YEAR = 365 * 24 * 3600 * 1000L;

    private static final LongCounter ZERO_LONG_COUNTER = new LongCounter() {
        @Override
        public long getCountLocked(int which) {
            return 0;
        }

        @Override
        public long getCountForProcessState(int procState) {
            return 0;
        }

        @Override
        public void logState(Printer pw, String prefix) {
            pw.println(prefix + "mCount=0");
        }
    };

    private static final LongCounter[] ZERO_LONG_COUNTER_ARRAY =
            new LongCounter[]{ZERO_LONG_COUNTER};

    private final KernelWakelockReader mKernelWakelockReader = new KernelWakelockReader();
    private final KernelWakelockStats mTmpWakelockStats = new KernelWakelockStats();

    @VisibleForTesting
    protected KernelCpuUidUserSysTimeReader mCpuUidUserSysTimeReader;
    @VisibleForTesting
    protected KernelCpuSpeedReader[] mKernelCpuSpeedReaders;
    @VisibleForTesting
    protected KernelCpuUidFreqTimeReader mCpuUidFreqTimeReader;
    @VisibleForTesting
    protected KernelCpuUidActiveTimeReader mCpuUidActiveTimeReader;
    @VisibleForTesting
    protected KernelCpuUidClusterTimeReader mCpuUidClusterTimeReader;
    @VisibleForTesting
    protected KernelSingleUidTimeReader mKernelSingleUidTimeReader;
    @VisibleForTesting
    protected SystemServerCpuThreadReader mSystemServerCpuThreadReader =
            SystemServerCpuThreadReader.create();

    private final KernelMemoryBandwidthStats mKernelMemoryBandwidthStats
            = new KernelMemoryBandwidthStats();
    private final LongSparseArray<SamplingTimer> mKernelMemoryStats = new LongSparseArray<>();

    public LongSparseArray<SamplingTimer> getKernelMemoryStats() {
        return mKernelMemoryStats;
    }

    private static final int[] SUPPORTED_PER_PROCESS_STATE_STANDARD_ENERGY_BUCKETS = {
            MeasuredEnergyStats.POWER_BUCKET_CPU,
            MeasuredEnergyStats.POWER_BUCKET_MOBILE_RADIO,
            MeasuredEnergyStats.POWER_BUCKET_WIFI,
            MeasuredEnergyStats.POWER_BUCKET_BLUETOOTH,
    };

    // TimeInState counters need NUM_PROCESS_STATE states in order to accommodate
    // Uid.PROCESS_STATE_NONEXISTENT, which is outside the range of legitimate proc states.
    private static final int PROC_STATE_TIME_COUNTER_STATE_COUNT = NUM_PROCESS_STATE + 1;

    @GuardedBy("this")
    public boolean mPerProcStateCpuTimesAvailable = true;

    @GuardedBy("this")
    private long mNumSingleUidCpuTimeReads;
    @GuardedBy("this")
    private long mCpuTimeReadsTrackingStartTimeMs = SystemClock.uptimeMillis();
    @GuardedBy("this")
    private int mNumUidsRemoved;
    @GuardedBy("this")
    private int mNumAllUidCpuTimeReads;

    /** Container for Resource Power Manager stats. Updated by updateRpmStatsLocked. */
    private RpmStats mTmpRpmStats = null;
    /** The soonest the RPM stats can be updated after it was last updated. */
    private static final long RPM_STATS_UPDATE_FREQ_MS = 1000;
    /** Last time that RPM stats were updated by updateRpmStatsLocked. */
    private long mLastRpmStatsUpdateTimeMs = -RPM_STATS_UPDATE_FREQ_MS;

    /** Container for Rail Energy Data stats. */
    private final RailStats mTmpRailStats = new RailStats();

    /**
     * Use a queue to delay removing UIDs from {@link KernelCpuUidUserSysTimeReader},
     * {@link KernelCpuUidActiveTimeReader}, {@link KernelCpuUidClusterTimeReader},
     * {@link KernelCpuUidFreqTimeReader} and from the Kernel.
     *
     * Isolated and invalid UID info must be removed to conserve memory. However, STATSD and
     * Batterystats both need to access UID cpu time. To resolve this race condition, only
     * Batterystats shall remove UIDs, and a delay {@link Constants#UID_REMOVE_DELAY_MS} is
     * implemented so that STATSD can capture those UID times before they are deleted.
     */
    @GuardedBy("this")
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected Queue<UidToRemove> mPendingRemovedUids = new LinkedList<>();

    @VisibleForTesting
    public final class UidToRemove {
        private final int mStartUid;
        private final int mEndUid;
        private final long mUidRemovalTimestamp;

        /** Remove just one UID */
        public UidToRemove(int uid, long timestamp) {
            this(uid, uid, timestamp);
        }

        /** Remove a range of UIDs, startUid must be smaller than endUid. */
        public UidToRemove(int startUid, int endUid, long timestamp) {
            mStartUid = startUid;
            mEndUid = endUid;
            mUidRemovalTimestamp = timestamp;
        }

        public long getUidRemovalTimestamp() {
            return mUidRemovalTimestamp;
        }

        @GuardedBy("BatteryStatsImpl.this")
        void removeLocked() {
            removeCpuStatsForUidRangeLocked(mStartUid, mEndUid);
        }
    }

    /**
     * Listener for the battery stats reset.
     */
    public interface BatteryResetListener {

        /**
         * Callback invoked immediately prior to resetting battery stats.
         * @param resetReason One of the RESET_REASON_* constants.
         */
        void prepareForBatteryStatsReset(int resetReason);
    }

    private BatteryResetListener mBatteryResetListener;

    public interface BatteryCallback {
        public void batteryNeedsCpuUpdate();
        public void batteryPowerChanged(boolean onBattery);
        public void batterySendBroadcast(Intent intent);
        public void batteryStatsReset();
    }

    public interface PlatformIdleStateCallback {
        public void fillLowPowerStats(RpmStats rpmStats);
        public String getSubsystemLowPowerStats();
    }

    /** interface to update rail information for power monitor */
    public interface MeasuredEnergyRetriever {
        /** Function to fill the map for the rail data stats
         * Used for power monitoring feature
         * @param railStats
         */
        void fillRailDataStats(RailStats railStats);
    }

    public static abstract class UserInfoProvider {
        private int[] userIds;
        protected abstract @Nullable int[] getUserIds();
        @VisibleForTesting
        public final void refreshUserIds() {
            userIds = getUserIds();
        }
        @VisibleForTesting
        public boolean exists(int userId) {
            return userIds != null ? ArrayUtils.contains(userIds, userId) : true;
        }
    }

    private final PlatformIdleStateCallback mPlatformIdleStateCallback;

    private final Runnable mDeferSetCharging = new Runnable() {
        @Override
        public void run() {
            synchronized (BatteryStatsImpl.this) {
                if (mOnBattery) {
                    // if the device gets unplugged in the time between this runnable being
                    // executed and the lock being taken, we don't want to set charging state
                    return;
                }
                boolean changed = setChargingLocked(true);
                if (changed) {
                    final long uptimeMs = mClock.uptimeMillis();
                    final long elapsedRealtimeMs = mClock.elapsedRealtime();
                    addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
                }
            }
        }
    };

    public final MeasuredEnergyRetriever mMeasuredEnergyRetriever;

    /**
     * This handler is running on {@link BackgroundThread}.
     */
    final class MyHandler extends Handler {
        public MyHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            BatteryCallback cb = mCallback;
            switch (msg.what) {
                case MSG_REPORT_CPU_UPDATE_NEEDED:
                    if (cb != null) {
                        cb.batteryNeedsCpuUpdate();
                    }
                    break;
                case MSG_REPORT_POWER_CHANGE:
                    if (cb != null) {
                        cb.batteryPowerChanged(msg.arg1 != 0);
                    }
                    break;
                case MSG_REPORT_CHARGING:
                    if (cb != null) {
                        final String action;
                        synchronized (BatteryStatsImpl.this) {
                            action = mCharging ? BatteryManager.ACTION_CHARGING
                                    : BatteryManager.ACTION_DISCHARGING;
                        }
                        Intent intent = new Intent(action);
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                        cb.batterySendBroadcast(intent);
                    }
                    break;
                case MSG_REPORT_RESET_STATS:
                    if (cb != null) {
                        cb.batteryStatsReset();
                    }
                }
        }
    }

    public void postBatteryNeedsCpuUpdateMsg() {
        mHandler.sendEmptyMessage(MSG_REPORT_CPU_UPDATE_NEEDED);
    }

    /**
     * Update per-freq cpu times for the supplied UID.
     */
    @GuardedBy("this")
    @SuppressWarnings("GuardedBy")    // errorprone false positive on getProcStateTimeCounter
    @VisibleForTesting
    public void updateProcStateCpuTimesLocked(int uid, long timestampMs) {
        if (!initKernelSingleUidTimeReaderLocked()) {
            return;
        }

        final Uid u = getUidStatsLocked(uid);

        mNumSingleUidCpuTimeReads++;

        LongArrayMultiStateCounter onBatteryCounter =
                u.getProcStateTimeCounter(timestampMs).getCounter();
        LongArrayMultiStateCounter onBatteryScreenOffCounter =
                u.getProcStateScreenOffTimeCounter(timestampMs).getCounter();

        mKernelSingleUidTimeReader.addDelta(uid, onBatteryCounter, timestampMs);
        mKernelSingleUidTimeReader.addDelta(uid, onBatteryScreenOffCounter, timestampMs);

        if (u.mChildUids != null) {
            LongArrayMultiStateCounter.LongArrayContainer deltaContainer =
                    getCpuTimeInFreqContainer();
            int childUidCount = u.mChildUids.size();
            for (int j = childUidCount - 1; j >= 0; --j) {
                LongArrayMultiStateCounter cpuTimeInFreqCounter =
                        u.mChildUids.valueAt(j).cpuTimeInFreqCounter;
                if (cpuTimeInFreqCounter != null) {
                    mKernelSingleUidTimeReader.addDelta(u.mChildUids.keyAt(j),
                            cpuTimeInFreqCounter, timestampMs, deltaContainer);
                    onBatteryCounter.addCounts(deltaContainer);
                    onBatteryScreenOffCounter.addCounts(deltaContainer);
                }
            }
        }
    }

    /**
     * Removes kernel CPU stats for removed UIDs, in the order they were added to the
     * mPendingRemovedUids queue.
     */
    @GuardedBy("this")
    @SuppressWarnings("GuardedBy")    // errorprone false positive on removeLocked
    public void clearPendingRemovedUidsLocked() {
        long cutOffTimeMs = mClock.elapsedRealtime() - mConstants.UID_REMOVE_DELAY_MS;
        while (!mPendingRemovedUids.isEmpty()
                && mPendingRemovedUids.peek().getUidRemovalTimestamp() < cutOffTimeMs) {
            mPendingRemovedUids.poll().removeLocked();
        }
    }

    /**
     * When the battery/screen state changes, we don't attribute the cpu times to any process
     * but we still need to take snapshots of all uids to get correct deltas later on.
     */
    @SuppressWarnings("GuardedBy")    // errorprone false positive on getProcStateTimeCounter
    public void updateCpuTimesForAllUids() {
        synchronized (BatteryStatsImpl.this) {
            if (!trackPerProcStateCpuTimes()) {
                return;
            }

            if(!initKernelSingleUidTimeReaderLocked()) {
                return;
            }

            // TODO(b/197162116): just get a list of UIDs
            final SparseArray<long[]> allUidCpuFreqTimesMs =
                    mCpuUidFreqTimeReader.getAllUidCpuFreqTimeMs();
            for (int i = allUidCpuFreqTimesMs.size() - 1; i >= 0; --i) {
                final int uid = allUidCpuFreqTimesMs.keyAt(i);
                final int parentUid = mapUid(uid);
                final Uid u = getAvailableUidStatsLocked(parentUid);
                if (u == null) {
                    continue;
                }

                final int procState = u.mProcessState;
                if (procState == Uid.PROCESS_STATE_NONEXISTENT) {
                    continue;
                }

                final long timestampMs = mClock.elapsedRealtime();
                final LongArrayMultiStateCounter onBatteryCounter =
                        u.getProcStateTimeCounter(timestampMs).getCounter();
                final LongArrayMultiStateCounter onBatteryScreenOffCounter =
                        u.getProcStateScreenOffTimeCounter(timestampMs).getCounter();

                if (uid == parentUid || Process.isSdkSandboxUid(uid)) {
                    mKernelSingleUidTimeReader.addDelta(parentUid, onBatteryCounter, timestampMs);
                    mKernelSingleUidTimeReader.addDelta(parentUid, onBatteryScreenOffCounter,
                            timestampMs);
                } else {
                    Uid.ChildUid childUid = u.getChildUid(uid);
                    if (childUid != null) {
                        final LongArrayMultiStateCounter counter = childUid.cpuTimeInFreqCounter;
                        if (counter != null) {
                            final LongArrayMultiStateCounter.LongArrayContainer deltaContainer =
                                    getCpuTimeInFreqContainer();
                            mKernelSingleUidTimeReader.addDelta(uid, counter, timestampMs,
                                    deltaContainer);
                            onBatteryCounter.addCounts(deltaContainer);
                            onBatteryScreenOffCounter.addCounts(deltaContainer);
                        }
                    }
                }
            }
        }
    }

    @VisibleForTesting
    public static long[] addCpuTimes(long[] timesA, long[] timesB) {
        if (timesA != null && timesB != null) {
            for (int i = timesA.length - 1; i >= 0; --i) {
                timesA[i] += timesB[i];
            }
            return timesA;
        }
        return timesA == null ? (timesB == null ? null : timesB) : timesA;
    }

    @GuardedBy("this")
    private boolean initKernelSingleUidTimeReaderLocked() {
        if (mKernelSingleUidTimeReader == null) {
            if (mPowerProfile == null) {
                return false;
            }
            if (mCpuFreqs == null) {
                mCpuFreqs = mCpuUidFreqTimeReader.readFreqs(mPowerProfile);
            }
            if (mCpuFreqs != null) {
                mKernelSingleUidTimeReader = new KernelSingleUidTimeReader(mCpuFreqs.length);
            } else {
                mPerProcStateCpuTimesAvailable = mCpuUidFreqTimeReader.allUidTimesAvailable();
                return false;
            }
        }
        mPerProcStateCpuTimesAvailable = mCpuUidFreqTimeReader.allUidTimesAvailable()
                && mKernelSingleUidTimeReader.singleUidCpuTimesAvailable();
        return true;
    }

    public interface ExternalStatsSync {
        int UPDATE_CPU = 0x01;
        int UPDATE_WIFI = 0x02;
        int UPDATE_RADIO = 0x04;
        int UPDATE_BT = 0x08;
        int UPDATE_RPM = 0x10;
        int UPDATE_DISPLAY = 0x20;
        int RESET = 0x40;

        int UPDATE_ALL =
                UPDATE_CPU | UPDATE_WIFI | UPDATE_RADIO | UPDATE_BT | UPDATE_RPM | UPDATE_DISPLAY;

        int UPDATE_ON_PROC_STATE_CHANGE = UPDATE_WIFI | UPDATE_RADIO | UPDATE_BT;

        int UPDATE_ON_RESET = UPDATE_ALL | RESET;

        @IntDef(flag = true, prefix = "UPDATE_", value = {
                UPDATE_CPU,
                UPDATE_WIFI,
                UPDATE_RADIO,
                UPDATE_BT,
                UPDATE_RPM,
                UPDATE_DISPLAY,
                UPDATE_ALL,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ExternalUpdateFlag {
        }

        Future<?> scheduleSync(String reason, int flags);
        Future<?> scheduleCpuSyncDueToRemovedUid(int uid);
        Future<?> scheduleCpuSyncDueToSettingChange();
        /**
         * Schedule a sync because of a screen state change.
         */
        Future<?> scheduleSyncDueToScreenStateChange(int flags, boolean onBattery,
                boolean onBatteryScreenOff, int screenState, int[] perDisplayScreenStates);
        Future<?> scheduleCpuSyncDueToWakelockChange(long delayMillis);
        void cancelCpuSyncDueToWakelockChange();
        Future<?> scheduleSyncDueToBatteryLevelChange(long delayMillis);
        /** Schedule removal of UIDs corresponding to a removed user */
        Future<?> scheduleCleanupDueToRemovedUser(int userId);
        /** Schedule a sync because of a process state change */
        void scheduleSyncDueToProcessStateChange(int flags, long delayMillis);
    }

    public Handler mHandler;
    private ExternalStatsSync mExternalSync = null;
    @VisibleForTesting
    protected UserInfoProvider mUserInfoProvider = null;

    private BatteryCallback mCallback;

    /**
     * Mapping isolated uids to the actual owning app uid.
     */
    final SparseIntArray mIsolatedUids = new SparseIntArray();
    /**
     * Internal reference count of isolated uids.
     */
    final SparseIntArray mIsolatedUidRefCounts = new SparseIntArray();

    /**
     * The statistics we have collected organized by uids.
     */
    final SparseArray<BatteryStatsImpl.Uid> mUidStats = new SparseArray<>();

    // A set of pools of currently active timers.  When a timer is queried, we will divide the
    // elapsed time by the number of active timers to arrive at that timer's share of the time.
    // In order to do this, we must refresh each timer whenever the number of active timers
    // changes.
    @VisibleForTesting
    @UnsupportedAppUsage
    protected ArrayList<StopwatchTimer> mPartialTimers = new ArrayList<>();
    @UnsupportedAppUsage
    final ArrayList<StopwatchTimer> mFullTimers = new ArrayList<>();
    @UnsupportedAppUsage
    final ArrayList<StopwatchTimer> mWindowTimers = new ArrayList<>();
    final ArrayList<StopwatchTimer> mDrawTimers = new ArrayList<>();
    final SparseArray<ArrayList<StopwatchTimer>> mSensorTimers = new SparseArray<>();
    final ArrayList<StopwatchTimer> mWifiRunningTimers = new ArrayList<>();
    final ArrayList<StopwatchTimer> mFullWifiLockTimers = new ArrayList<>();
    final ArrayList<StopwatchTimer> mWifiMulticastTimers = new ArrayList<>();
    final ArrayList<StopwatchTimer> mWifiScanTimers = new ArrayList<>();
    final SparseArray<ArrayList<StopwatchTimer>> mWifiBatchedScanTimers = new SparseArray<>();
    final ArrayList<StopwatchTimer> mAudioTurnedOnTimers = new ArrayList<>();
    final ArrayList<StopwatchTimer> mVideoTurnedOnTimers = new ArrayList<>();
    final ArrayList<StopwatchTimer> mFlashlightTurnedOnTimers = new ArrayList<>();
    final ArrayList<StopwatchTimer> mCameraTurnedOnTimers = new ArrayList<>();
    final ArrayList<StopwatchTimer> mBluetoothScanOnTimers = new ArrayList<>();

    // Last partial timers we use for distributing CPU usage.
    @VisibleForTesting
    protected ArrayList<StopwatchTimer> mLastPartialTimers = new ArrayList<>();

    // These are the objects that will want to do something when the device
    // is unplugged from power.
    protected final TimeBase mOnBatteryTimeBase = new TimeBase(true);

    // These are the objects that will want to do something when the device
    // is unplugged from power *and* the screen is off or doze.
    protected final TimeBase mOnBatteryScreenOffTimeBase = new TimeBase(true);

    // Set to true when we want to distribute CPU across wakelocks for the next
    // CPU update, even if we aren't currently running wake locks.
    boolean mDistributeWakelockCpu;

    private boolean mSystemReady;
    boolean mShuttingDown;

    final HistoryEventTracker mActiveEvents = new HistoryEventTracker();

    long mHistoryBaseTimeMs;
    protected boolean mHaveBatteryLevel = false;
    protected boolean mRecordingHistory = false;
    int mNumHistoryItems;

    private static final int HISTORY_TAG_INDEX_LIMIT = 0x7ffe;
    private static final int MAX_HISTORY_TAG_STRING_LENGTH = 1024;

    final HashMap<HistoryTag, Integer> mHistoryTagPool = new HashMap<>();
    private SparseArray<HistoryTag> mHistoryTags;
    final Parcel mHistoryBuffer = Parcel.obtain();
    final HistoryItem mHistoryLastWritten = new HistoryItem();
    final HistoryItem mHistoryLastLastWritten = new HistoryItem();
    final HistoryItem mHistoryAddTmp = new HistoryItem();
    int mNextHistoryTagIdx = 0;
    int mNumHistoryTagChars = 0;
    int mHistoryBufferLastPos = -1;
    int mActiveHistoryStates = 0xffffffff;
    int mActiveHistoryStates2 = 0xffffffff;
    long mLastHistoryElapsedRealtimeMs = 0;
    long mTrackRunningHistoryElapsedRealtimeMs = 0;
    long mTrackRunningHistoryUptimeMs = 0;

    @NonNull
    final BatteryStatsHistory mBatteryStatsHistory;

    final HistoryItem mHistoryCur = new HistoryItem();

    HistoryItem mHistory;
    HistoryItem mHistoryEnd;
    HistoryItem mHistoryLastEnd;
    HistoryItem mHistoryCache;

    // Used by computeHistoryStepDetails
    HistoryStepDetails mLastHistoryStepDetails = null;
    byte mLastHistoryStepLevel = 0;
    final HistoryStepDetails mCurHistoryStepDetails = new HistoryStepDetails();
    final HistoryStepDetails mReadHistoryStepDetails = new HistoryStepDetails();
    final HistoryStepDetails mTmpHistoryStepDetails = new HistoryStepDetails();

    /**
     * Total time (in milliseconds) spent executing in user code.
     */
    long mLastStepCpuUserTimeMs;
    long mCurStepCpuUserTimeMs;
    /**
     * Total time (in milliseconds) spent executing in kernel code.
     */
    long mLastStepCpuSystemTimeMs;
    long mCurStepCpuSystemTimeMs;
    /**
     * Times from /proc/stat (but measured in milliseconds).
     */
    long mLastStepStatUserTimeMs;
    long mLastStepStatSystemTimeMs;
    long mLastStepStatIOWaitTimeMs;
    long mLastStepStatIrqTimeMs;
    long mLastStepStatSoftIrqTimeMs;
    long mLastStepStatIdleTimeMs;
    long mCurStepStatUserTimeMs;
    long mCurStepStatSystemTimeMs;
    long mCurStepStatIOWaitTimeMs;
    long mCurStepStatIrqTimeMs;
    long mCurStepStatSoftIrqTimeMs;
    long mCurStepStatIdleTimeMs;

    private BatteryStatsHistoryIterator mBatteryStatsHistoryIterator;
    private HistoryItem mHistoryIterator;

    int mStartCount;

    /**
     * Set to true when a reset occurs, informing us that the next time BatteryExternalStatsWorker
     * gives us data, we mustn't process it since this data includes pre-reset-period data.
     */
    @GuardedBy("this")
    boolean mIgnoreNextExternalStats = false;

    long mStartClockTimeMs;
    String mStartPlatformVersion;
    String mEndPlatformVersion;

    long mUptimeUs;
    long mUptimeStartUs;
    long mRealtimeUs;
    long mRealtimeStartUs;

    int mWakeLockNesting;
    boolean mWakeLockImportant;
    public boolean mRecordAllHistory;
    boolean mNoAutoReset;

    /**
     * Overall screen state. For multidisplay devices, this represents the current highest screen
     * state of the displays.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected int mScreenState = Display.STATE_UNKNOWN;
    /**
     * Overall screen on timer. For multidisplay devices, this represents the time spent with at
     * least one display in the screen on state.
     */
    StopwatchTimer mScreenOnTimer;
    /**
     * Overall screen doze timer. For multidisplay devices, this represents the time spent with
     * screen doze being the highest screen state.
     */
    StopwatchTimer mScreenDozeTimer;
    /**
     * Overall screen brightness bin. For multidisplay devices, this represents the current
     * brightest screen.
     */
    int mScreenBrightnessBin = -1;
    /**
     * Overall screen brightness timers. For multidisplay devices, the {@link mScreenBrightnessBin}
     * timer will be active at any given time
     */
    final StopwatchTimer[] mScreenBrightnessTimer =
            new StopwatchTimer[NUM_SCREEN_BRIGHTNESS_BINS];

    boolean mPretendScreenOff;

    private static class DisplayBatteryStats {
        /**
         * Per display screen state.
         */
        public int screenState = Display.STATE_UNKNOWN;
        /**
         * Per display screen on timers.
         */
        public StopwatchTimer screenOnTimer;
        /**
         * Per display screen doze timers.
         */
        public StopwatchTimer screenDozeTimer;
        /**
         * Per display screen brightness bins.
         */
        public int screenBrightnessBin = -1;
        /**
         * Per display screen brightness timers.
         */
        public StopwatchTimer[] screenBrightnessTimers =
                new StopwatchTimer[NUM_SCREEN_BRIGHTNESS_BINS];
        /**
         * Per display screen state the last time {@link #updateDisplayMeasuredEnergyStatsLocked}
         * was called.
         */
        public int screenStateAtLastEnergyMeasurement = Display.STATE_UNKNOWN;

        DisplayBatteryStats(Clock clock, TimeBase timeBase) {
            screenOnTimer = new StopwatchTimer(clock, null, -1, null,
                    timeBase);
            screenDozeTimer = new StopwatchTimer(clock, null, -1, null,
                    timeBase);
            for (int i = 0; i < NUM_SCREEN_BRIGHTNESS_BINS; i++) {
                screenBrightnessTimers[i] = new StopwatchTimer(clock, null, -100 - i, null,
                        timeBase);
            }
        }

        /**
         * Reset display timers.
         */
        public void reset(long elapsedRealtimeUs) {
            screenOnTimer.reset(false, elapsedRealtimeUs);
            screenDozeTimer.reset(false, elapsedRealtimeUs);
            for (int i = 0; i < NUM_SCREEN_BRIGHTNESS_BINS; i++) {
                screenBrightnessTimers[i].reset(false, elapsedRealtimeUs);
            }
        }
    }

    DisplayBatteryStats[] mPerDisplayBatteryStats;

    private int mDisplayMismatchWtfCount = 0;

    boolean mInteractive;
    StopwatchTimer mInteractiveTimer;

    boolean mPowerSaveModeEnabled;
    StopwatchTimer mPowerSaveModeEnabledTimer;

    boolean mDeviceIdling;
    StopwatchTimer mDeviceIdlingTimer;

    boolean mDeviceLightIdling;
    StopwatchTimer mDeviceLightIdlingTimer;

    int mDeviceIdleMode;
    long mLastIdleTimeStartMs;
    long mLongestLightIdleTimeMs;
    long mLongestFullIdleTimeMs;
    StopwatchTimer mDeviceIdleModeLightTimer;
    StopwatchTimer mDeviceIdleModeFullTimer;

    boolean mPhoneOn;
    StopwatchTimer mPhoneOnTimer;

    int mAudioOnNesting;
    StopwatchTimer mAudioOnTimer;

    int mVideoOnNesting;
    StopwatchTimer mVideoOnTimer;

    int mFlashlightOnNesting;
    StopwatchTimer mFlashlightOnTimer;

    int mCameraOnNesting;
    StopwatchTimer mCameraOnTimer;

    private static final int USB_DATA_UNKNOWN = 0;
    private static final int USB_DATA_DISCONNECTED = 1;
    private static final int USB_DATA_CONNECTED = 2;
    int mUsbDataState = USB_DATA_UNKNOWN;

    int mGpsSignalQualityBin = -1;
    final StopwatchTimer[] mGpsSignalQualityTimer =
        new StopwatchTimer[GnssSignalQuality.NUM_GNSS_SIGNAL_QUALITY_LEVELS];

    int mPhoneSignalStrengthBin = -1;
    int mPhoneSignalStrengthBinRaw = -1;
    final StopwatchTimer[] mPhoneSignalStrengthsTimer =
            new StopwatchTimer[CellSignalStrength.getNumSignalStrengthLevels()];

    StopwatchTimer mPhoneSignalScanningTimer;

    int mPhoneDataConnectionType = -1;
    final StopwatchTimer[] mPhoneDataConnectionsTimer =
            new StopwatchTimer[NUM_DATA_CONNECTION_TYPES];

    @RadioAccessTechnology
    int mActiveRat = RADIO_ACCESS_TECHNOLOGY_OTHER;

    private static class RadioAccessTechnologyBatteryStats {
        /**
         * This RAT is currently being used.
         */
        private boolean mActive = false;
        /**
         * Current active frequency range for this RAT.
         */
        @ServiceState.FrequencyRange
        private int mFrequencyRange = ServiceState.FREQUENCY_RANGE_UNKNOWN;
        /**
         * Current signal strength for this RAT.
         */
        private int mSignalStrength = CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        /**
         * Timers for each combination of frequency range and signal strength.
         */
        public final StopwatchTimer[][] perStateTimers;
        /**
         * Counters tracking the time (in milliseconds) spent transmitting data in a given state.
         */
        @Nullable
        private LongSamplingCounter[][] mPerStateTxDurationMs = null;
        /**
         * Counters tracking the time (in milliseconds) spent receiving data in at given frequency.
         */
        @Nullable
        private LongSamplingCounter[] mPerFrequencyRxDurationMs = null;

        RadioAccessTechnologyBatteryStats(int freqCount, Clock clock, TimeBase timeBase) {
            perStateTimers =
                    new StopwatchTimer[freqCount][CellSignalStrength.NUM_SIGNAL_STRENGTH_BINS];
            for (int i = 0; i < freqCount; i++) {
                for (int j = 0; j < CellSignalStrength.NUM_SIGNAL_STRENGTH_BINS; j++) {
                    perStateTimers[i][j] = new StopwatchTimer(clock, null, -1, null, timeBase);
                }
            }
        }

        /**
         * Note this RAT is currently being used.
         */
        public void noteActive(boolean active, long elapsedRealtimeMs) {
            if (mActive == active) return;
            mActive = active;
            if (mActive) {
                perStateTimers[mFrequencyRange][mSignalStrength].startRunningLocked(
                        elapsedRealtimeMs);
            } else {
                perStateTimers[mFrequencyRange][mSignalStrength].stopRunningLocked(
                        elapsedRealtimeMs);
            }
        }

        /**
         * Note current frequency range has changed.
         */
        public void noteFrequencyRange(@ServiceState.FrequencyRange int frequencyRange,
                long elapsedRealtimeMs) {
            if (mFrequencyRange == frequencyRange) return;

            if (!mActive) {
                // RAT not in use, note the frequency change and move on.
                mFrequencyRange = frequencyRange;
                return;
            }
            perStateTimers[mFrequencyRange][mSignalStrength].stopRunningLocked(elapsedRealtimeMs);
            perStateTimers[frequencyRange][mSignalStrength].startRunningLocked(elapsedRealtimeMs);
            mFrequencyRange = frequencyRange;
        }

        /**
         * Note current signal strength has changed.
         */
        public void noteSignalStrength(int signalStrength, long elapsedRealtimeMs) {
            if (mSignalStrength == signalStrength) return;

            if (!mActive) {
                // RAT not in use, note the signal strength change and move on.
                mSignalStrength = signalStrength;
                return;
            }
            perStateTimers[mFrequencyRange][mSignalStrength].stopRunningLocked(elapsedRealtimeMs);
            perStateTimers[mFrequencyRange][signalStrength].startRunningLocked(elapsedRealtimeMs);
            mSignalStrength = signalStrength;
        }

        /**
         * Returns the duration in milliseconds spent in a given state since the last mark.
         */
        public long getTimeSinceMark(@ServiceState.FrequencyRange int frequencyRange,
                int signalStrength, long elapsedRealtimeMs) {
            return perStateTimers[frequencyRange][signalStrength].getTimeSinceMarkLocked(
                    elapsedRealtimeMs * 1000) / 1000;
        }

        /**
         * Set mark for all timers.
         */
        public void setMark(long elapsedRealtimeMs) {
            final int size = perStateTimers.length;
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < CellSignalStrength.NUM_SIGNAL_STRENGTH_BINS; j++) {
                    perStateTimers[i][j].setMark(elapsedRealtimeMs);
                }
            }
        }

        /**
         * Returns numbers of frequencies tracked for this RAT.
         */
        public int getFrequencyRangeCount() {
            return perStateTimers.length;
        }

        /**
         * Add TX time for a given state.
         */
        public void incrementTxDuration(@ServiceState.FrequencyRange int frequencyRange,
                int signalStrength, long durationMs) {
            getTxDurationCounter(frequencyRange, signalStrength, true).addCountLocked(durationMs);
        }

        /**
         * Add TX time for a given frequency.
         */
        public void incrementRxDuration(@ServiceState.FrequencyRange int frequencyRange,
                long durationMs) {
            getRxDurationCounter(frequencyRange, true).addCountLocked(durationMs);
        }

        /**
         * Reset radio access technology timers and counts.
         */
        public void reset(long elapsedRealtimeUs) {
            final int size = perStateTimers.length;
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < CellSignalStrength.NUM_SIGNAL_STRENGTH_BINS; j++) {
                    perStateTimers[i][j].reset(false, elapsedRealtimeUs);
                    if (mPerStateTxDurationMs == null) continue;
                    mPerStateTxDurationMs[i][j].reset(false, elapsedRealtimeUs);
                }
                if (mPerFrequencyRxDurationMs == null) continue;
                mPerFrequencyRxDurationMs[i].reset(false, elapsedRealtimeUs);
            }
        }

        /**
         * Write data to summary parcel
         */
        public void writeSummaryToParcel(Parcel out, long elapsedRealtimeUs) {
            final int freqCount = perStateTimers.length;
            out.writeInt(freqCount);
            out.writeInt(CellSignalStrength.NUM_SIGNAL_STRENGTH_BINS);
            for (int i = 0; i < freqCount; i++) {
                for (int j = 0; j < CellSignalStrength.NUM_SIGNAL_STRENGTH_BINS; j++) {
                    perStateTimers[i][j].writeSummaryFromParcelLocked(out, elapsedRealtimeUs);
                }
            }

            if (mPerStateTxDurationMs == null) {
                out.writeInt(0);
            } else {
                out.writeInt(1);
                for (int i = 0; i < freqCount; i++) {
                    for (int j = 0; j < CellSignalStrength.NUM_SIGNAL_STRENGTH_BINS; j++) {
                        mPerStateTxDurationMs[i][j].writeSummaryFromParcelLocked(out);
                    }
                }
            }

            if (mPerFrequencyRxDurationMs == null) {
                out.writeInt(0);
            } else {
                out.writeInt(1);
                for (int i = 0; i < freqCount; i++) {
                    mPerFrequencyRxDurationMs[i].writeSummaryFromParcelLocked(out);
                }
            }
        }

        /**
         * Read data from summary parcel
         */
        public void readSummaryFromParcel(Parcel in) {
            final int oldFreqCount = in.readInt();
            final int oldSignalStrengthCount = in.readInt();
            final int currFreqCount = perStateTimers.length;
            final int currSignalStrengthCount = CellSignalStrength.NUM_SIGNAL_STRENGTH_BINS;

            for (int freq = 0; freq < oldFreqCount; freq++) {
                for (int strength = 0; strength < oldSignalStrengthCount; strength++) {
                    if (freq >= currFreqCount || strength >= currSignalStrengthCount) {
                        // Mismatch with the summary parcel. Consume the data but don't use it.
                        final StopwatchTimer temp = new StopwatchTimer(null, null, -1, null,
                                new TimeBase());
                        // Consume perStateTimers data.
                        temp.readSummaryFromParcelLocked(in);
                    } else {
                        perStateTimers[freq][strength].readSummaryFromParcelLocked(in);
                    }
                }
            }

            if (in.readInt() == 1) {
                for (int freq = 0; freq < oldFreqCount; freq++) {
                    for (int strength = 0; strength < oldSignalStrengthCount; strength++) {
                        if (freq >= currFreqCount || strength >= currSignalStrengthCount) {
                            // Mismatch with the summary parcel. Consume the data but don't use it.
                            final StopwatchTimer temp = new StopwatchTimer(null, null, -1, null,
                                    new TimeBase());
                            // Consume mPerStateTxDurationMs data.
                            temp.readSummaryFromParcelLocked(in);
                        }
                        getTxDurationCounter(freq, strength, true).readSummaryFromParcelLocked(in);
                    }
                }
            }

            if (in.readInt() == 1) {
                for (int freq = 0; freq < oldFreqCount; freq++) {
                    if (freq >= currFreqCount) {
                        // Mismatch with the summary parcel. Consume the data but don't use it.
                        final StopwatchTimer
                                temp = new StopwatchTimer(null, null, -1, null, new TimeBase());
                        // Consume mPerFrequencyRxDurationMs data.
                        temp.readSummaryFromParcelLocked(in);
                        continue;
                    }
                    getRxDurationCounter(freq, true).readSummaryFromParcelLocked(in);
                }
            }
        }

        private LongSamplingCounter getTxDurationCounter(
                @ServiceState.FrequencyRange int frequencyRange, int signalStrength, boolean make) {
            if (mPerStateTxDurationMs == null) {
                if (!make) return null;

                final int freqCount = getFrequencyRangeCount();
                final int signalStrengthCount = perStateTimers[0].length;
                final TimeBase timeBase = perStateTimers[0][0].mTimeBase;
                mPerStateTxDurationMs = new LongSamplingCounter[freqCount][signalStrengthCount];
                for (int freq = 0; freq < freqCount; freq++) {
                    for (int strength = 0; strength < signalStrengthCount; strength++) {
                        mPerStateTxDurationMs[freq][strength] = new LongSamplingCounter(timeBase);
                    }
                }
            }
            if (frequencyRange < 0 || frequencyRange >= getFrequencyRangeCount()) {
                Slog.w(TAG, "Unexpected frequency range (" + frequencyRange
                        + ") requested in getTxDurationCounter");
                return null;
            }
            if (signalStrength < 0 || signalStrength >= perStateTimers[0].length) {
                Slog.w(TAG, "Unexpected signal strength (" + signalStrength
                        + ") requested in getTxDurationCounter");
                return null;
            }
            return mPerStateTxDurationMs[frequencyRange][signalStrength];
        }

        private LongSamplingCounter getRxDurationCounter(
                @ServiceState.FrequencyRange int frequencyRange, boolean make) {
            if (mPerFrequencyRxDurationMs == null) {
                if (!make) return null;

                final int freqCount = getFrequencyRangeCount();
                final TimeBase timeBase = perStateTimers[0][0].mTimeBase;
                mPerFrequencyRxDurationMs = new LongSamplingCounter[freqCount];
                for (int freq = 0; freq < freqCount; freq++) {
                    mPerFrequencyRxDurationMs[freq] = new LongSamplingCounter(timeBase);
                }
            }
            if (frequencyRange < 0 || frequencyRange >= getFrequencyRangeCount()) {
                Slog.w(TAG, "Unexpected frequency range (" + frequencyRange
                        + ") requested in getRxDurationCounter");
                return null;
            }
            return mPerFrequencyRxDurationMs[frequencyRange];
        }
    }

    /**
     * Number of frequency ranges, keep in sync with {@link ServiceState.FrequencyRange}
     */
    private static final int NR_FREQUENCY_COUNT = 5;

    RadioAccessTechnologyBatteryStats[] mPerRatBatteryStats =
            new RadioAccessTechnologyBatteryStats[RADIO_ACCESS_TECHNOLOGY_COUNT];

    @GuardedBy("this")
    private RadioAccessTechnologyBatteryStats getRatBatteryStatsLocked(
            @RadioAccessTechnology int rat) {
        RadioAccessTechnologyBatteryStats stats = mPerRatBatteryStats[rat];
        if (stats == null) {
            final int freqCount = rat == RADIO_ACCESS_TECHNOLOGY_NR ? NR_FREQUENCY_COUNT : 1;
            stats = new RadioAccessTechnologyBatteryStats(freqCount, mClock, mOnBatteryTimeBase);
            mPerRatBatteryStats[rat] = stats;
        }
        return stats;
    }

    final LongSamplingCounter[] mNetworkByteActivityCounters =
            new LongSamplingCounter[NUM_NETWORK_ACTIVITY_TYPES];

    final LongSamplingCounter[] mNetworkPacketActivityCounters =
            new LongSamplingCounter[NUM_NETWORK_ACTIVITY_TYPES];

    /**
     * The WiFi Overall wakelock timer
     * This timer tracks the actual aggregate time for which MC wakelocks are enabled
     * since addition of per UID timers would not result in an accurate value due to overlapp of
     * per uid wakelock timers
     */
    StopwatchTimer mWifiMulticastWakelockTimer;

    /**
     * The WiFi controller activity (time in tx, rx, idle, and power consumed) for the device.
     */
    ControllerActivityCounterImpl mWifiActivity;

    /**
     * The Bluetooth controller activity (time in tx, rx, idle, and power consumed) for the device.
     */
    ControllerActivityCounterImpl mBluetoothActivity;

    /**
     * The Modem controller activity (time in tx, rx, idle, and power consumed) for the device.
     */
    ControllerActivityCounterImpl mModemActivity;

    /**
     * Whether the device supports WiFi controller energy reporting. This is set to true on
     * the first WiFi energy report. See {@link #mWifiActivity}.
     */
    boolean mHasWifiReporting = false;

    /**
     * Whether the device supports Bluetooth controller energy reporting. This is set to true on
     * the first Bluetooth energy report. See {@link #mBluetoothActivity}.
     */
    boolean mHasBluetoothReporting = false;

    /**
     * Whether the device supports Modem controller energy reporting. This is set to true on
     * the first Modem energy report. See {@link #mModemActivity}.
     */
    boolean mHasModemReporting = false;

    boolean mWifiOn;
    StopwatchTimer mWifiOnTimer;

    boolean mGlobalWifiRunning;
    StopwatchTimer mGlobalWifiRunningTimer;

    int mWifiState = -1;
    final StopwatchTimer[] mWifiStateTimer = new StopwatchTimer[NUM_WIFI_STATES];

    int mWifiSupplState = -1;
    final StopwatchTimer[] mWifiSupplStateTimer = new StopwatchTimer[NUM_WIFI_SUPPL_STATES];

    int mWifiSignalStrengthBin = -1;
    final StopwatchTimer[] mWifiSignalStrengthsTimer =
            new StopwatchTimer[NUM_WIFI_SIGNAL_STRENGTH_BINS];

    StopwatchTimer mWifiActiveTimer;

    int mBluetoothScanNesting;
    StopwatchTimer mBluetoothScanTimer;

    int mMobileRadioPowerState = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
    long mMobileRadioActiveStartTimeMs;
    StopwatchTimer mMobileRadioActiveTimer;
    StopwatchTimer mMobileRadioActivePerAppTimer;
    LongSamplingCounter mMobileRadioActiveAdjustedTime;
    LongSamplingCounter mMobileRadioActiveUnknownTime;
    LongSamplingCounter mMobileRadioActiveUnknownCount;

    /**
     * The soonest the Mobile Radio stats can be updated due to a mobile radio power state change
     * after it was last updated.
     */
    @VisibleForTesting
    protected static final long MOBILE_RADIO_POWER_STATE_UPDATE_FREQ_MS = 1000 * 60 * 10;

    int mWifiRadioPowerState = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;

    @GuardedBy("this")
    @VisibleForTesting
    protected @Nullable MeasuredEnergyStats.Config mMeasuredEnergyStatsConfig;

    /**
     * Accumulated global (generally, device-wide total) charge consumption of various consumers
     * while on battery.
     * Its '<b>custom</b> power buckets' correspond to the
     * {@link android.hardware.power.stats.EnergyConsumer.ordinal}s of (custom) energy consumer
     * type {@link android.hardware.power.stats.EnergyConsumerType#OTHER}).
     *
     * If energy consumer data is completely unavailable this will be null.
     */
    @GuardedBy("this")
    @VisibleForTesting
    protected @Nullable MeasuredEnergyStats mGlobalMeasuredEnergyStats;
    /** Bluetooth Power calculator for attributing measured bluetooth charge consumption to uids */
    @Nullable BluetoothPowerCalculator mBluetoothPowerCalculator = null;
    /** Cpu Power calculator for attributing measured cpu charge consumption to uids */
    @Nullable CpuPowerCalculator mCpuPowerCalculator = null;
    /** Mobile Radio Power calculator for attributing measured radio charge consumption to uids */
    @Nullable
    MobileRadioPowerCalculator mMobileRadioPowerCalculator = null;
    /** Wifi Power calculator for attributing measured wifi charge consumption to uids */
    @Nullable WifiPowerCalculator mWifiPowerCalculator = null;

    /**
     * These provide time bases that discount the time the device is plugged
     * in to power.
     */
    boolean mOnBattery;
    @VisibleForTesting
    protected boolean mOnBatteryInternal;

    /**
     * External reporting of whether the device is actually charging.
     */
    boolean mCharging = true;
    int mLastChargingStateLevel;

    /*
     * These keep track of battery levels (1-100) at the last plug event and the last unplug event.
     */
    int mDischargeStartLevel;
    int mDischargeUnplugLevel;
    int mDischargePlugLevel;
    int mDischargeCurrentLevel;
    int mCurrentBatteryLevel;
    int mLowDischargeAmountSinceCharge;
    int mHighDischargeAmountSinceCharge;
    int mDischargeScreenOnUnplugLevel;
    int mDischargeScreenOffUnplugLevel;
    int mDischargeScreenDozeUnplugLevel;
    int mDischargeAmountScreenOn;
    int mDischargeAmountScreenOnSinceCharge;
    int mDischargeAmountScreenOff;
    int mDischargeAmountScreenOffSinceCharge;
    int mDischargeAmountScreenDoze;
    int mDischargeAmountScreenDozeSinceCharge;

    private LongSamplingCounter mDischargeScreenOffCounter;
    private LongSamplingCounter mDischargeScreenDozeCounter;
    private LongSamplingCounter mDischargeCounter;
    private LongSamplingCounter mDischargeLightDozeCounter;
    private LongSamplingCounter mDischargeDeepDozeCounter;

    static final int MAX_LEVEL_STEPS = 200;

    int mInitStepMode = 0;
    int mCurStepMode = 0;
    int mModStepMode = 0;

    int mLastDischargeStepLevel;
    int mMinDischargeStepLevel;
    final LevelStepTracker mDischargeStepTracker = new LevelStepTracker(MAX_LEVEL_STEPS);
    final LevelStepTracker mDailyDischargeStepTracker = new LevelStepTracker(MAX_LEVEL_STEPS*2);
    ArrayList<PackageChange> mDailyPackageChanges;

    int mLastChargeStepLevel;
    int mMaxChargeStepLevel;
    final LevelStepTracker mChargeStepTracker = new LevelStepTracker(MAX_LEVEL_STEPS);
    final LevelStepTracker mDailyChargeStepTracker = new LevelStepTracker(MAX_LEVEL_STEPS*2);

    static final int MAX_DAILY_ITEMS = 10;

    long mDailyStartTimeMs = 0;
    long mNextMinDailyDeadlineMs = 0;
    long mNextMaxDailyDeadlineMs = 0;

    final ArrayList<DailyItem> mDailyItems = new ArrayList<>();

    long mLastWriteTimeMs = 0; // Milliseconds

    private int mPhoneServiceState = -1;
    private int mPhoneServiceStateRaw = -1;
    private int mPhoneSimStateRaw = -1;

    private int mNumConnectivityChange;

    private int mBatteryVoltageMv = -1;
    private int mEstimatedBatteryCapacityMah = -1;

    private int mLastLearnedBatteryCapacityUah = -1;
    private int mMinLearnedBatteryCapacityUah = -1;
    private int mMaxLearnedBatteryCapacityUah = -1;

    private long mBatteryTimeToFullSeconds = -1;

    private boolean mCpuFreqsInitialized;
    private long[] mCpuFreqs;
    private LongArrayMultiStateCounter.LongArrayContainer mTmpCpuTimeInFreq;

    /**
     * Times spent by the system server threads handling incoming binder requests.
     */
    private LongSamplingCounterArray mBinderThreadCpuTimesUs;

    @VisibleForTesting
    protected PowerProfile mPowerProfile;

    @VisibleForTesting
    @GuardedBy("this")
    protected final Constants mConstants;

    /*
     * Holds a SamplingTimer associated with each Resource Power Manager state and voter,
     * recording their times when on-battery (regardless of screen state).
     */
    private final HashMap<String, SamplingTimer> mRpmStats = new HashMap<>();
    /** Times for each Resource Power Manager state and voter when screen-off and on-battery. */
    private final HashMap<String, SamplingTimer> mScreenOffRpmStats = new HashMap<>();

    @Override
    public Map<String, ? extends Timer> getRpmStats() {
        return mRpmStats;
    }

    // TODO: Note: screenOffRpmStats has been disabled via SCREEN_OFF_RPM_STATS_ENABLED.
    @Override
    public Map<String, ? extends Timer> getScreenOffRpmStats() {
        return mScreenOffRpmStats;
    }

    /*
     * Holds a SamplingTimer associated with each kernel wakelock name being tracked.
     */
    private final HashMap<String, SamplingTimer> mKernelWakelockStats = new HashMap<>();

    @UnsupportedAppUsage
    public Map<String, ? extends Timer> getKernelWakelockStats() {
        return mKernelWakelockStats;
    }

    @Override
    public WakeLockStats getWakeLockStats() {
        final long realtimeMs = mClock.elapsedRealtime();
        final long realtimeUs = realtimeMs * 1000;
        List<WakeLockStats.WakeLock> uidWakeLockStats = new ArrayList<>();
        for (int i = mUidStats.size() - 1; i >= 0; i--) {
            final Uid uid = mUidStats.valueAt(i);
            final ArrayMap<String, ? extends BatteryStats.Uid.Wakelock> wakelockStats =
                    uid.mWakelockStats.getMap();
            for (int j = wakelockStats.size() - 1; j >= 0; j--) {
                final String name = wakelockStats.keyAt(j);
                final Uid.Wakelock wakelock = (Uid.Wakelock) wakelockStats.valueAt(j);
                final DualTimer timer = wakelock.mTimerPartial;
                if (timer != null) {
                    final long totalTimeLockHeldMs =
                            timer.getTotalTimeLocked(realtimeUs, STATS_SINCE_CHARGED) / 1000;
                    if (totalTimeLockHeldMs != 0) {
                        uidWakeLockStats.add(
                                new WakeLockStats.WakeLock(uid.getUid(), name,
                                        timer.getCountLocked(STATS_SINCE_CHARGED),
                                        totalTimeLockHeldMs,
                                        timer.isRunningLocked()
                                                ? timer.getCurrentDurationMsLocked(realtimeMs)
                                                : 0));
                    }
                }
            }
        }
        return new WakeLockStats(uidWakeLockStats);
    }

    @Override
    @GuardedBy("this")
    public BluetoothBatteryStats getBluetoothBatteryStats() {
        final long elapsedRealtimeUs = mClock.elapsedRealtime() * 1000;
        ArrayList<BluetoothBatteryStats.UidStats> uidStats = new ArrayList<>();
        for (int i = mUidStats.size() - 1; i >= 0; i--) {
            final Uid uid = mUidStats.valueAt(i);
            final Timer scanTimer = uid.getBluetoothScanTimer();
            final long scanTimeMs =
                    scanTimer != null ? scanTimer.getTotalTimeLocked(
                            elapsedRealtimeUs, STATS_SINCE_CHARGED) / 1000 : 0;

            final Timer unoptimizedScanTimer = uid.getBluetoothUnoptimizedScanTimer();
            final long unoptimizedScanTimeMs =
                    unoptimizedScanTimer != null ? unoptimizedScanTimer.getTotalTimeLocked(
                            elapsedRealtimeUs, STATS_SINCE_CHARGED) / 1000 : 0;

            final Counter scanResultCounter = uid.getBluetoothScanResultCounter();
            final int scanResultCount =
                    scanResultCounter != null ? scanResultCounter.getCountLocked(
                            STATS_SINCE_CHARGED) : 0;

            final ControllerActivityCounter counter = uid.getBluetoothControllerActivity();
            final long rxTimeMs =  counter != null ? counter.getRxTimeCounter().getCountLocked(
                    STATS_SINCE_CHARGED) : 0;
            final long txTimeMs =  counter != null ? counter.getTxTimeCounters()[0].getCountLocked(
                    STATS_SINCE_CHARGED) : 0;

            if (scanTimeMs != 0 || unoptimizedScanTimeMs != 0 || scanResultCount != 0
                    || rxTimeMs != 0 || txTimeMs != 0) {
                uidStats.add(new BluetoothBatteryStats.UidStats(uid.getUid(),
                        scanTimeMs,
                        unoptimizedScanTimeMs,
                        scanResultCount,
                        rxTimeMs,
                        txTimeMs));
            }
        }

        return new BluetoothBatteryStats(uidStats);
    }

    String mLastWakeupReason = null;
    long mLastWakeupUptimeMs = 0;
    private final HashMap<String, SamplingTimer> mWakeupReasonStats = new HashMap<>();

    public Map<String, ? extends Timer> getWakeupReasonStats() {
        return mWakeupReasonStats;
    }

    @Override
    public long getUahDischarge(int which) {
        return mDischargeCounter.getCountLocked(which);
    }

    @Override
    public long getUahDischargeScreenOff(int which) {
        return mDischargeScreenOffCounter.getCountLocked(which);
    }

    @Override
    public long getUahDischargeScreenDoze(int which) {
        return mDischargeScreenDozeCounter.getCountLocked(which);
    }

    @Override
    public long getUahDischargeLightDoze(int which) {
        return mDischargeLightDozeCounter.getCountLocked(which);
    }

    @Override
    public long getUahDischargeDeepDoze(int which) {
        return mDischargeDeepDozeCounter.getCountLocked(which);
    }

    @Override
    public int getEstimatedBatteryCapacity() {
        return mEstimatedBatteryCapacityMah;
    }

    @Override
    public int getLearnedBatteryCapacity() {
        return mLastLearnedBatteryCapacityUah;
    }

    @Override
    public int getMinLearnedBatteryCapacity() {
        return mMinLearnedBatteryCapacityUah;
    }

    @Override
    public int getMaxLearnedBatteryCapacity() {
        return mMaxLearnedBatteryCapacityUah;
    }

    public BatteryStatsImpl() {
        this(Clock.SYSTEM_CLOCK);
    }

    public BatteryStatsImpl(Clock clock) {
        this(clock, (File) null);
    }

    public BatteryStatsImpl(Clock clock, File historyDirectory) {
        init(clock);
        mStartClockTimeMs = clock.currentTimeMillis();
        mCheckinFile = null;
        mDailyFile = null;
        if (historyDirectory == null) {
            mStatsFile = null;
            mBatteryStatsHistory = new BatteryStatsHistory(mHistoryBuffer);
        } else {
            mStatsFile = new AtomicFile(new File(historyDirectory, "batterystats.bin"));
            mBatteryStatsHistory = new BatteryStatsHistory(this, historyDirectory, mHistoryBuffer);
        }
        mHandler = null;
        mPlatformIdleStateCallback = null;
        mMeasuredEnergyRetriever = null;
        mUserInfoProvider = null;
        mConstants = new Constants(mHandler);
        clearHistoryLocked();
    }

    private void init(Clock clock) {
        mClock = clock;
        mCpuUidUserSysTimeReader = new KernelCpuUidUserSysTimeReader(true, clock);
        mCpuUidFreqTimeReader = new KernelCpuUidFreqTimeReader(true, clock);
        mCpuUidActiveTimeReader = new KernelCpuUidActiveTimeReader(true, clock);
        mCpuUidClusterTimeReader = new KernelCpuUidClusterTimeReader(true, clock);
    }

    /**
     * TimeBase observer.
     */
    public interface TimeBaseObs {
        void onTimeStarted(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs);
        void onTimeStopped(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs);

        /**
         * Reset the observer's state, returns true if the timer/counter is inactive
         * so it can be destroyed.
         * @param detachIfReset detach if true, no-op if false.
         * @return Returns true if the timer/counter is inactive and can be destroyed.
         */
        default boolean reset(boolean detachIfReset) {
            return reset(detachIfReset, SystemClock.elapsedRealtime() * 1000);
        }

        /**
         * @see #reset(boolean)
         * @param detachIfReset detach if true, no-op if false.
         * @param elapsedRealtimeUs the timestamp when this reset is actually reequested
         * @return Returns true if the timer/counter is inactive and can be destroyed.
         */
        boolean reset(boolean detachIfReset, long elapsedRealtimeUs);

        /**
         * Detach the observer from TimeBase.
         */
        void detach();
    }

    // methods are protected not private to be VisibleForTesting
    public static class TimeBase {
        protected final Collection<TimeBaseObs> mObservers;

        // All below time metrics are in microseconds.
        protected long mUptimeUs;
        protected long mRealtimeUs;

        protected boolean mRunning;

        protected long mPastUptimeUs;
        protected long mUptimeStartUs;
        protected long mPastRealtimeUs;
        protected long mRealtimeStartUs;
        protected long mUnpluggedUptimeUs;
        protected long mUnpluggedRealtimeUs;

        public void dump(PrintWriter pw, String prefix) {
            StringBuilder sb = new StringBuilder(128);
            pw.print(prefix); pw.print("mRunning="); pw.println(mRunning);
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("mUptime=");
                    formatTimeMs(sb, mUptimeUs / 1000);
            pw.println(sb.toString());
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("mRealtime=");
                    formatTimeMs(sb, mRealtimeUs / 1000);
            pw.println(sb.toString());
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("mPastUptime=");
                    formatTimeMs(sb, mPastUptimeUs / 1000); sb.append("mUptimeStart=");
                    formatTimeMs(sb, mUptimeStartUs / 1000);
                    sb.append("mUnpluggedUptime="); formatTimeMs(sb, mUnpluggedUptimeUs / 1000);
            pw.println(sb.toString());
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("mPastRealtime=");
                    formatTimeMs(sb, mPastRealtimeUs / 1000); sb.append("mRealtimeStart=");
                    formatTimeMs(sb, mRealtimeStartUs / 1000);
                    sb.append("mUnpluggedRealtime="); formatTimeMs(sb, mUnpluggedRealtimeUs / 1000);
            pw.println(sb.toString());
        }
        /**
         * The mObservers of TimeBase in BatteryStatsImpl object can contain up to 20k entries.
         * The mObservers of TimeBase in BatteryStatsImpl.Uid object only contains a few or tens of
         * entries.
         * mObservers must have good performance on add(), remove(), also be memory efficient.
         * This is why we provide isLongList parameter for long and short list user cases.
         * @param isLongList If true, use HashSet for mObservers list.
         *                   If false, use ArrayList for mObservers list.
        */
        public TimeBase(boolean isLongList) {
            mObservers = isLongList ? new HashSet<>() : new ArrayList<>();
        }

        public TimeBase() {
            this(false);
        }

        public void add(TimeBaseObs observer) {
            mObservers.add(observer);
        }

        public void remove(TimeBaseObs observer) {
            mObservers.remove(observer);
        }

        public boolean hasObserver(TimeBaseObs observer) {
            return mObservers.contains(observer);
        }

        public void init(long uptimeUs, long elapsedRealtimeUs) {
            mRealtimeUs = 0;
            mUptimeUs = 0;
            mPastUptimeUs = 0;
            mPastRealtimeUs = 0;
            mUptimeStartUs = uptimeUs;
            mRealtimeStartUs = elapsedRealtimeUs;
            mUnpluggedUptimeUs = getUptime(mUptimeStartUs);
            mUnpluggedRealtimeUs = getRealtime(mRealtimeStartUs);
        }

        public void reset(long uptimeUs, long elapsedRealtimeUs) {
            if (!mRunning) {
                mPastUptimeUs = 0;
                mPastRealtimeUs = 0;
            } else {
                mUptimeStartUs = uptimeUs;
                mRealtimeStartUs = elapsedRealtimeUs;
                // TODO: Since mUptimeStartUs was just reset and we are running, getUptime will
                // just return mPastUptimeUs. Also, are we sure we don't want to reset that?
                mUnpluggedUptimeUs = getUptime(uptimeUs);
                // TODO: likewise.
                mUnpluggedRealtimeUs = getRealtime(elapsedRealtimeUs);
            }
        }

        public long computeUptime(long curTimeUs, int which) {
            return mUptimeUs + getUptime(curTimeUs);
        }

        public long computeRealtime(long curTimeUs, int which) {
            return mRealtimeUs + getRealtime(curTimeUs);
        }

        public long getUptime(long curTimeUs) {
            long time = mPastUptimeUs;
            if (mRunning) {
                time += curTimeUs - mUptimeStartUs;
            }
            return time;
        }

        public long getRealtime(long curTimeUs) {
            long time = mPastRealtimeUs;
            if (mRunning) {
                time += curTimeUs - mRealtimeStartUs;
            }
            return time;
        }

        public long getUptimeStart() {
            return mUptimeStartUs;
        }

        public long getRealtimeStart() {
            return mRealtimeStartUs;
        }

        public boolean isRunning() {
            return mRunning;
        }

        public boolean setRunning(boolean running, long uptimeUs, long elapsedRealtimeUs) {
            if (mRunning != running) {
                mRunning = running;
                if (running) {
                    mUptimeStartUs = uptimeUs;
                    mRealtimeStartUs = elapsedRealtimeUs;
                    long batteryUptimeUs = mUnpluggedUptimeUs = getUptime(uptimeUs);
                    long batteryRealtimeUs = mUnpluggedRealtimeUs = getRealtime(elapsedRealtimeUs);
                    // Normally we do not use Iterator in framework code to avoid alloc/dealloc
                    // Iterator object, here is an exception because mObservers' type is Collection
                    // instead of list.
                    final Iterator<TimeBaseObs> iter = mObservers.iterator();
                    while (iter.hasNext()) {
                        iter.next().onTimeStarted(
                                elapsedRealtimeUs, batteryUptimeUs, batteryRealtimeUs);
                    }
                } else {
                    mPastUptimeUs += uptimeUs - mUptimeStartUs;
                    mPastRealtimeUs += elapsedRealtimeUs - mRealtimeStartUs;
                    long batteryUptimeUs = getUptime(uptimeUs);
                    long batteryRealtimeUs = getRealtime(elapsedRealtimeUs);
                    // Normally we do not use Iterator in framework code to avoid alloc/dealloc
                    // Iterator object, here is an exception because mObservers' type is Collection
                    // instead of list.
                    final Iterator<TimeBaseObs> iter = mObservers.iterator();
                    while (iter.hasNext()) {
                        iter.next().onTimeStopped(
                                elapsedRealtimeUs, batteryUptimeUs, batteryRealtimeUs);
                    }
                }
                return true;
            }
            return false;
        }

        public void readSummaryFromParcel(Parcel in) {
            mUptimeUs = in.readLong();
            mRealtimeUs = in.readLong();
        }

        public void writeSummaryToParcel(Parcel out, long uptimeUs, long elapsedRealtimeUs) {
            out.writeLong(computeUptime(uptimeUs, STATS_SINCE_CHARGED));
            out.writeLong(computeRealtime(elapsedRealtimeUs, STATS_SINCE_CHARGED));
        }

        public void readFromParcel(Parcel in) {
            mRunning = false;
            mUptimeUs = in.readLong();
            mPastUptimeUs = in.readLong();
            mUptimeStartUs = in.readLong();
            mRealtimeUs = in.readLong();
            mPastRealtimeUs = in.readLong();
            mRealtimeStartUs = in.readLong();
            mUnpluggedUptimeUs = in.readLong();
            mUnpluggedRealtimeUs = in.readLong();
        }

        public void writeToParcel(Parcel out, long uptimeUs, long elapsedRealtimeUs) {
            final long runningUptime = getUptime(uptimeUs);
            final long runningRealtime = getRealtime(elapsedRealtimeUs);
            out.writeLong(mUptimeUs);
            out.writeLong(runningUptime);
            out.writeLong(mUptimeStartUs);
            out.writeLong(mRealtimeUs);
            out.writeLong(runningRealtime);
            out.writeLong(mRealtimeStartUs);
            out.writeLong(mUnpluggedUptimeUs);
            out.writeLong(mUnpluggedRealtimeUs);
        }
    }

    /**
     * State for keeping track of counting information.
     */
    public static class Counter extends BatteryStats.Counter implements TimeBaseObs {
        @UnsupportedAppUsage
        final AtomicInteger mCount = new AtomicInteger();
        final TimeBase mTimeBase;

        public Counter(TimeBase timeBase, Parcel in) {
            mTimeBase = timeBase;
            mCount.set(in.readInt());
            timeBase.add(this);
        }

        public Counter(TimeBase timeBase) {
            mTimeBase = timeBase;
            timeBase.add(this);
        }

        public void writeToParcel(Parcel out) {
            out.writeInt(mCount.get());
        }

        @Override
        public void onTimeStarted(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
        }

        @Override
        public void onTimeStopped(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
        }

        /**
         * Writes a possibly null Counter to a Parcel.
         *
         * @param out the Parcel to be written to.
         * @param counter a Counter, or null.
         */
        public static void writeCounterToParcel(Parcel out, @Nullable Counter counter) {
            if (counter == null) {
                out.writeInt(0); // indicates null
                return;
            }
            out.writeInt(1); // indicates non-null

            counter.writeToParcel(out);
        }

        /**
         * Reads a Counter that was written using {@link #writeCounterToParcel(Parcel, Counter)}.
         * @param timeBase the timebase to assign to the Counter
         * @param in the parcel to read from
         * @return the Counter or null.
         */
        @Nullable
        public static Counter readCounterFromParcel(TimeBase timeBase, Parcel in) {
            if (in.readInt() == 0) {
                return null;
            }
            return new Counter(timeBase, in);
        }

        @Override
        public int getCountLocked(int which) {
            return mCount.get();
        }

        public void logState(Printer pw, String prefix) {
            pw.println(prefix + "mCount=" + mCount.get());
        }

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public void stepAtomic() {
            if (mTimeBase.isRunning()) {
                mCount.incrementAndGet();
            }
        }

        void addAtomic(int delta) {
            if (mTimeBase.isRunning()) {
                mCount.addAndGet(delta);
            }
        }

        /**
         * Clear state of this counter.
         */
        @Override
        public boolean reset(boolean detachIfReset, long elapsedRealtimeUs /* unused */) {
            mCount.set(0);
            if (detachIfReset) {
                detach();
            }
            return true;
        }

        @Override
        public void detach() {
            mTimeBase.remove(this);
        }

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public void writeSummaryFromParcelLocked(Parcel out) {
            out.writeInt(mCount.get());
        }

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public void readSummaryFromParcelLocked(Parcel in) {
            mCount.set(in.readInt());
        }
    }

    @VisibleForTesting
    public static class LongSamplingCounterArray extends LongCounterArray implements TimeBaseObs {
        final TimeBase mTimeBase;
        public long[] mCounts;

        private LongSamplingCounterArray(TimeBase timeBase, Parcel in) {
            mTimeBase = timeBase;
            mCounts = in.createLongArray();
            timeBase.add(this);
        }

        public LongSamplingCounterArray(TimeBase timeBase) {
            mTimeBase = timeBase;
            timeBase.add(this);
        }

        private void writeToParcel(Parcel out) {
            out.writeLongArray(mCounts);
        }

        @Override
        public void onTimeStarted(long elapsedRealTimeUs, long baseUptimeUs, long baseRealtimeUs) {
        }

        @Override
        public void onTimeStopped(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
        }

        @Override
        public long[] getCountsLocked(int which) {
            return mCounts == null ? null : Arrays.copyOf(mCounts, mCounts.length);
        }

        @Override
        public void logState(Printer pw, String prefix) {
            pw.println(prefix + "mCounts=" + Arrays.toString(mCounts));
        }

        public void addCountLocked(long[] counts) {
            addCountLocked(counts, mTimeBase.isRunning());
        }

        public void addCountLocked(long[] counts, boolean isRunning) {
            if (counts == null) {
                return;
            }
            if (isRunning) {
                if (mCounts == null) {
                    mCounts = new long[counts.length];
                }
                for (int i = 0; i < counts.length; ++i) {
                    mCounts[i] += counts[i];
                }
            }
        }

        public int getSize() {
            return mCounts == null ? 0 : mCounts.length;
        }

        /**
         * Clear state of this counter.
         */
        @Override
        public boolean reset(boolean detachIfReset, long elapsedRealtimeUs /* unused */) {
            if (mCounts != null) {
                Arrays.fill(mCounts, 0);
            }
            if (detachIfReset) {
                detach();
            }
            return true;
        }

        @Override
        public void detach() {
            mTimeBase.remove(this);
        }

        private void writeSummaryToParcelLocked(Parcel out) {
            out.writeLongArray(mCounts);
        }

        private void readSummaryFromParcelLocked(Parcel in) {
            mCounts = in.createLongArray();
        }

        public static void writeToParcel(Parcel out, LongSamplingCounterArray counterArray) {
            if (counterArray != null) {
                out.writeInt(1);
                counterArray.writeToParcel(out);
            } else {
                out.writeInt(0);
            }
        }

        public static LongSamplingCounterArray readFromParcel(Parcel in, TimeBase timeBase) {
            if (in.readInt() != 0) {
                return new LongSamplingCounterArray(timeBase, in);
            } else {
                return null;
            }
        }

        public static void writeSummaryToParcelLocked(Parcel out,
                LongSamplingCounterArray counterArray) {
            if (counterArray != null) {
                out.writeInt(1);
                counterArray.writeSummaryToParcelLocked(out);
            } else {
                out.writeInt(0);
            }
        }

        public static LongSamplingCounterArray readSummaryFromParcelLocked(Parcel in,
                TimeBase timeBase) {
            if (in.readInt() != 0) {
                final LongSamplingCounterArray counterArray
                        = new LongSamplingCounterArray(timeBase);
                counterArray.readSummaryFromParcelLocked(in);
                return counterArray;
            } else {
                return null;
            }
        }
    }

    private static class TimeMultiStateCounter extends LongCounter implements TimeBaseObs {
        private final TimeBase mTimeBase;
        private final LongMultiStateCounter mCounter;

        private TimeMultiStateCounter(TimeBase timeBase, int stateCount, long timestampMs) {
            this(timeBase, new LongMultiStateCounter(stateCount), timestampMs);
        }

        private TimeMultiStateCounter(TimeBase timeBase, LongMultiStateCounter counter,
                long timestampMs) {
            mTimeBase = timeBase;
            mCounter = counter;
            mCounter.setEnabled(mTimeBase.isRunning(), timestampMs);
            timeBase.add(this);
        }

        @Nullable
        private static TimeMultiStateCounter readFromParcel(Parcel in, TimeBase timeBase,
                int stateCount, long timestampMs) {
            LongMultiStateCounter counter = LongMultiStateCounter.CREATOR.createFromParcel(in);
            if (counter.getStateCount() != stateCount) {
                return null;
            }
            return new TimeMultiStateCounter(timeBase, counter, timestampMs);
        }

        private void writeToParcel(Parcel out) {
            mCounter.writeToParcel(out, 0);
        }

        @Override
        public void onTimeStarted(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
            mCounter.setEnabled(true, elapsedRealtimeUs / 1000);
        }

        @Override
        public void onTimeStopped(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
            mCounter.setEnabled(false, elapsedRealtimeUs / 1000);
        }

        public int getStateCount() {
            return mCounter.getStateCount();
        }

        private void setState(@BatteryConsumer.ProcessState int processState,
                long elapsedRealtimeMs) {
            mCounter.setState(processState, elapsedRealtimeMs);
        }

        private long update(long value, long timestampMs) {
            return mCounter.updateValue(value, timestampMs);
        }

        private void increment(long increment, long timestampMs) {
            mCounter.incrementValue(increment, timestampMs);
        }

        /**
         * Returns accumulated count for the specified state.
         */
        public long getCountForProcessState(@BatteryConsumer.ProcessState int procState) {
            return mCounter.getCount(procState);
        }

        public long getTotalCountLocked() {
            return mCounter.getTotalCount();
        }

        @Override
        public long getCountLocked(int statsType) {
            return getTotalCountLocked();
        }

        @Override
        public void logState(Printer pw, String prefix) {
            pw.println(prefix + "mCounter=" + mCounter);
        }

        /**
         * Clears state of this counter.
         */
        @Override
        public boolean reset(boolean detachIfReset, long elapsedRealtimeUs /* unused */) {
            mCounter.reset();
            if (detachIfReset) {
                detach();
            }
            return true;
        }

        @Override
        public void detach() {
            mTimeBase.remove(this);
        }
    }

    private static class TimeInFreqMultiStateCounter implements TimeBaseObs {
        private final TimeBase mTimeBase;
        private final LongArrayMultiStateCounter mCounter;

        private TimeInFreqMultiStateCounter(TimeBase timeBase, int stateCount, int cpuFreqCount,
                long timestampMs) {
            this(timeBase, new LongArrayMultiStateCounter(stateCount, cpuFreqCount), timestampMs);
        }

        private TimeInFreqMultiStateCounter(TimeBase timeBase, LongArrayMultiStateCounter counter,
                long timestampMs) {
            mTimeBase = timeBase;
            mCounter = counter;
            mCounter.setEnabled(mTimeBase.isRunning(), timestampMs);
            timeBase.add(this);
        }

        private void writeToParcel(Parcel out) {
            mCounter.writeToParcel(out, 0);
        }

        @Nullable
        private static TimeInFreqMultiStateCounter readFromParcel(Parcel in, TimeBase timeBase,
                int stateCount, int cpuFreqCount, long timestampMs) {
            // Read the object from the Parcel, whether it's usable or not
            LongArrayMultiStateCounter counter =
                    LongArrayMultiStateCounter.CREATOR.createFromParcel(in);
            if (counter.getStateCount() != stateCount
                    || counter.getArrayLength() != cpuFreqCount) {
                return null;
            }
            return new TimeInFreqMultiStateCounter(timeBase, counter, timestampMs);
        }

        @Override
        public void onTimeStarted(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
            mCounter.setEnabled(true, elapsedRealtimeUs / 1000);
        }

        @Override
        public void onTimeStopped(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
            mCounter.setEnabled(false, elapsedRealtimeUs / 1000);
        }

        public LongArrayMultiStateCounter getCounter() {
            return mCounter;
        }

        public int getStateCount() {
            return mCounter.getStateCount();
        }

        public void setTrackingEnabled(boolean enabled, long timestampMs) {
            mCounter.setEnabled(enabled && mTimeBase.isRunning(), timestampMs);
        }

        private void setState(int uidRunningState, long elapsedRealtimeMs) {
            mCounter.setState(uidRunningState, elapsedRealtimeMs);
        }

        /**
         * Returns accumulated counts for the specified state, or false if all counts are zero.
         */
        public boolean getCountsLocked(long[] counts, int procState) {
            if (counts.length != mCounter.getArrayLength()) {
                return false;
            }

            mCounter.getCounts(counts, procState);

            // Return counts only if at least one of the elements is non-zero.
            for (int i = counts.length - 1; i >= 0; --i) {
                if (counts[i] != 0) {
                    return true;
                }
            }
            return false;
        }

        public void logState(Printer pw, String prefix) {
            pw.println(prefix + "mCounter=" + mCounter);
        }

        /**
         * Clears state of this counter.
         */
        @Override
        public boolean reset(boolean detachIfReset, long elapsedRealtimeUs /* unused */) {
            mCounter.reset();
            if (detachIfReset) {
                detach();
            }
            return true;
        }

        @Override
        public void detach() {
            mTimeBase.remove(this);
        }
    }

    @VisibleForTesting
    public static class LongSamplingCounter extends LongCounter implements TimeBaseObs {
        final TimeBase mTimeBase;
        private long mCount;

        public LongSamplingCounter(TimeBase timeBase, Parcel in) {
            mTimeBase = timeBase;
            mCount = in.readLong();
            timeBase.add(this);
        }

        public LongSamplingCounter(TimeBase timeBase) {
            mTimeBase = timeBase;
            timeBase.add(this);
        }

        public void writeToParcel(Parcel out) {
            out.writeLong(mCount);
        }

        @Override
        public void onTimeStarted(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
        }

        @Override
        public void onTimeStopped(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
        }

        public long getCountLocked(int which) {
            return mCount;
        }

        @Override
        public long getCountForProcessState(int procState) {
            if (procState == BatteryConsumer.PROCESS_STATE_ANY) {
                return getCountLocked(STATS_SINCE_CHARGED);
            }
            return 0;
        }

        @Override
        public void logState(Printer pw, String prefix) {
            pw.println(prefix + "mCount=" + mCount);
        }

        public void addCountLocked(long count) {
            addCountLocked(count, mTimeBase.isRunning());
        }

        public void addCountLocked(long count, boolean isRunning) {
            if (isRunning) {
                mCount += count;
            }
        }

        /**
         * Clear state of this counter.
         */
        @Override
        public boolean reset(boolean detachIfReset, long elapsedRealtimeUs /* unused */) {
            mCount = 0;
            if (detachIfReset) {
                detach();
            }
            return true;
        }

        @Override
        public void detach() {
            mTimeBase.remove(this);
        }

        public void writeSummaryFromParcelLocked(Parcel out) {
            out.writeLong(mCount);
        }

        public void readSummaryFromParcelLocked(Parcel in) {
            mCount = in.readLong();
        }
    }

    /**
     * State for keeping track of timing information.
     */
    public static abstract class Timer extends BatteryStats.Timer implements TimeBaseObs {
        protected final Clock mClock;
        protected final int mType;
        protected final TimeBase mTimeBase;

        protected int mCount;

        // Times are in microseconds for better accuracy when dividing by the
        // lock count, and are in "battery realtime" units.

        /**
         * The total time we have accumulated since the start of the original
         * boot, to the last time something interesting happened in the
         * current run.
         */
        protected long mTotalTimeUs;

        /**
         * The total time this timer has been running until the latest mark has been set.
         * Subtract this from mTotalTimeUs to get the time spent running since the mark was set.
         */
        protected long mTimeBeforeMarkUs;

        /**
         * Constructs from a parcel.
         * @param type
         * @param timeBase
         * @param in
         */
        public Timer(Clock clock, int type, TimeBase timeBase, Parcel in) {
            mClock = clock;
            mType = type;
            mTimeBase = timeBase;

            mCount = in.readInt();
            mTotalTimeUs = in.readLong();
            mTimeBeforeMarkUs = in.readLong();
            timeBase.add(this);
            if (DEBUG) Log.i(TAG, "**** READ TIMER #" + mType + ": mTotalTime=" + mTotalTimeUs);
        }

        public Timer(Clock clock, int type, TimeBase timeBase) {
            mClock = clock;
            mType = type;
            mTimeBase = timeBase;
            timeBase.add(this);
        }

        public void writeToParcel(Parcel out, long elapsedRealtimeUs) {
            if (DEBUG) {
                Log.i(TAG, "**** WRITING TIMER #" + mType + ": mTotalTime="
                        + computeRunTimeLocked(mTimeBase.getRealtime(elapsedRealtimeUs),
                                elapsedRealtimeUs));
            }
            out.writeInt(computeCurrentCountLocked());
            out.writeLong(computeRunTimeLocked(mTimeBase.getRealtime(elapsedRealtimeUs),
                        elapsedRealtimeUs));
            out.writeLong(mTimeBeforeMarkUs);
        }

        protected abstract long computeRunTimeLocked(long curBatteryRealtime,
                long elapsedRealtimeUs);

        protected abstract int computeCurrentCountLocked();

        /**
         * Clear state of this timer.  Returns true if the timer is inactive
         * so can be completely dropped.
         */
        @Override
        public boolean reset(boolean detachIfReset) {
            return reset(detachIfReset, mClock.elapsedRealtime() * 1000);
        }

        @Override
        public boolean reset(boolean detachIfReset, long elapsedRealtimeUs /* unused */) {
            mTotalTimeUs = mTimeBeforeMarkUs = 0;
            mCount = 0;
            if (detachIfReset) {
                detach();
            }
            return true;
        }

        @Override
        public void detach() {
            mTimeBase.remove(this);
        }

        @Override
        public void onTimeStarted(long elapsedRealtimeUs, long timeBaseUptimeUs,
                long baseRealtimeUs) {
        }

        @Override
        public void onTimeStopped(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
            if (DEBUG && mType < 0) {
                Log.v(TAG, "plug #" + mType + ": realtime=" + baseRealtimeUs
                        + " old mTotalTime=" + mTotalTimeUs);
            }
            mTotalTimeUs = computeRunTimeLocked(baseRealtimeUs, elapsedRealtimeUs);
            mCount = computeCurrentCountLocked();
            if (DEBUG && mType < 0) {
                Log.v(TAG, "plug #" + mType + ": new mTotalTime=" + mTotalTimeUs);
            }
        }

        /**
         * Writes a possibly null Timer to a Parcel.
         *
         * @param out the Parcel to be written to.
         * @param timer a Timer, or null.
         */
        @UnsupportedAppUsage
        public static void writeTimerToParcel(Parcel out, Timer timer, long elapsedRealtimeUs) {
            if (timer == null) {
                out.writeInt(0); // indicates null
                return;
            }
            out.writeInt(1); // indicates non-null
            timer.writeToParcel(out, elapsedRealtimeUs);
        }

        @Override
        @UnsupportedAppUsage
        public long getTotalTimeLocked(long elapsedRealtimeUs, int which) {
            return computeRunTimeLocked(mTimeBase.getRealtime(elapsedRealtimeUs),
                    elapsedRealtimeUs);
        }

        @Override
        @UnsupportedAppUsage
        public int getCountLocked(int which) {
            return computeCurrentCountLocked();
        }

        @Override
        public long getTimeSinceMarkLocked(long elapsedRealtimeUs) {
            long val = computeRunTimeLocked(mTimeBase.getRealtime(elapsedRealtimeUs),
                    elapsedRealtimeUs);
            return val - mTimeBeforeMarkUs;
        }

        @Override
        public void logState(Printer pw, String prefix) {
            pw.println(prefix + "mCount=" + mCount);
            pw.println(prefix + "mTotalTime=" + mTotalTimeUs);
        }


        public void writeSummaryFromParcelLocked(Parcel out, long elapsedRealtimeUs) {
            long runTimeUs = computeRunTimeLocked(mTimeBase.getRealtime(elapsedRealtimeUs),
                    elapsedRealtimeUs);
            out.writeLong(runTimeUs);
            out.writeInt(computeCurrentCountLocked());
        }

        public void readSummaryFromParcelLocked(Parcel in) {
            // Multiply by 1000 for backwards compatibility
            mTotalTimeUs = in.readLong();
            mCount = in.readInt();
            // When reading the summary, we set the mark to be the latest information.
            mTimeBeforeMarkUs = mTotalTimeUs;
        }
    }

    /**
     * A counter meant to accept monotonically increasing values to its {@link #update(long, int)}
     * method. The state of the timer according to its {@link TimeBase} will determine how much
     * of the value is recorded.
     *
     * If the value being recorded resets, {@link #endSample()} can be called in order to
     * account for the change. If the value passed in to {@link #update(long, int)} decreased
     * between calls, the {@link #endSample()} is automatically called and the new value is
     * expected to increase monotonically from that point on.
     */
    public static class SamplingTimer extends Timer {

        /**
         * The most recent reported count from /proc/wakelocks.
         */
        int mCurrentReportedCount;

        /**
         * The reported count from /proc/wakelocks when unplug() was last
         * called.
         */
        int mUnpluggedReportedCount;

        /**
         * The most recent reported total_time from /proc/wakelocks.
         */
        long mCurrentReportedTotalTimeUs;


        /**
         * The reported total_time from /proc/wakelocks when unplug() was last
         * called.
         */
        long mUnpluggedReportedTotalTimeUs;

        /**
         * Whether we are currently in a discharge cycle.
         */
        boolean mTimeBaseRunning;

        /**
         * Whether we are currently recording reported values.
         */
        boolean mTrackingReportedValues;

        /*
         * A sequence counter, incremented once for each update of the stats.
         */
        int mUpdateVersion;

        @VisibleForTesting
        public SamplingTimer(Clock clock, TimeBase timeBase, Parcel in) {
            super(clock, 0, timeBase, in);
            mCurrentReportedCount = in.readInt();
            mUnpluggedReportedCount = in.readInt();
            mCurrentReportedTotalTimeUs = in.readLong();
            mUnpluggedReportedTotalTimeUs = in.readLong();
            mTrackingReportedValues = in.readInt() == 1;
            mTimeBaseRunning = timeBase.isRunning();
        }

        @VisibleForTesting
        public SamplingTimer(Clock clock, TimeBase timeBase) {
            super(clock, 0, timeBase);
            mTrackingReportedValues = false;
            mTimeBaseRunning = timeBase.isRunning();
        }

        /**
         * Ends the current sample, allowing subsequent values to {@link #update(long, int)} to
         * be less than the values used for a previous invocation.
         */
        public void endSample() {
            endSample(mClock.elapsedRealtime() * 1000);
        }

        /**
         * @see #endSample()
         */
        public void endSample(long elapsedRealtimeUs) {
            mTotalTimeUs = computeRunTimeLocked(0 /* unused by us */, elapsedRealtimeUs);
            mCount = computeCurrentCountLocked();
            mUnpluggedReportedTotalTimeUs = mCurrentReportedTotalTimeUs = 0;
            mUnpluggedReportedCount = mCurrentReportedCount = 0;
            mTrackingReportedValues = false;
        }

        public void setUpdateVersion(int version) {
            mUpdateVersion = version;
        }

        public int getUpdateVersion() {
            return mUpdateVersion;
        }

        /**
         * Updates the current recorded values. These are meant to be monotonically increasing
         * and cumulative. If you are dealing with deltas, use {@link #add(long, int)}.
         *
         * If the values being recorded have been reset, the monotonically increasing requirement
         * will be broken. In this case, {@link #endSample()} is automatically called and
         * the total value of totalTimeUs and count are recorded, starting a new monotonically
         * increasing sample.
         *
         * @param totalTimeUs total time of sample in microseconds.
         * @param count total number of times the event being sampled occurred.
         */
        public void updated(long totalTimeUs, int count) {
            update(totalTimeUs, count, mClock.elapsedRealtime() * 1000);
        }

        /**
         * @see #update(long, int)
         */
        public void update(long totalTimeUs, int count, long elapsedRealtimeUs) {
            if (mTimeBaseRunning && !mTrackingReportedValues) {
                // Updating the reported value for the first time.
                mUnpluggedReportedTotalTimeUs = totalTimeUs;
                mUnpluggedReportedCount = count;
            }

            mTrackingReportedValues = true;

            if (totalTimeUs < mCurrentReportedTotalTimeUs || count < mCurrentReportedCount) {
                endSample(elapsedRealtimeUs);
            }

            mCurrentReportedTotalTimeUs = totalTimeUs;
            mCurrentReportedCount = count;
        }

        /**
         * Adds deltaTime and deltaCount to the current sample.
         *
         * @param deltaTime additional time recorded since the last sampled event, in microseconds.
         * @param deltaCount additional number of times the event being sampled occurred.
         */
        public void add(long deltaTimeUs, int deltaCount) {
            add(deltaTimeUs, deltaCount, mClock.elapsedRealtime() * 1000);
        }

        /**
         * @see #add(long, int)
         */
        public void add(long deltaTimeUs, int deltaCount, long elapsedRealtimeUs) {
            update(mCurrentReportedTotalTimeUs + deltaTimeUs, mCurrentReportedCount + deltaCount,
                    elapsedRealtimeUs);
        }

        @Override
        public void onTimeStarted(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
            super.onTimeStarted(elapsedRealtimeUs, baseUptimeUs, baseRealtimeUs);
            if (mTrackingReportedValues) {
                mUnpluggedReportedTotalTimeUs = mCurrentReportedTotalTimeUs;
                mUnpluggedReportedCount = mCurrentReportedCount;
            }
            mTimeBaseRunning = true;
        }

        @Override
        public void onTimeStopped(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
            super.onTimeStopped(elapsedRealtimeUs, baseUptimeUs, baseRealtimeUs);
            mTimeBaseRunning = false;
        }

        @Override
        public void logState(Printer pw, String prefix) {
            super.logState(pw, prefix);
            pw.println(prefix + "mCurrentReportedCount=" + mCurrentReportedCount
                    + " mUnpluggedReportedCount=" + mUnpluggedReportedCount
                    + " mCurrentReportedTotalTime=" + mCurrentReportedTotalTimeUs
                    + " mUnpluggedReportedTotalTime=" + mUnpluggedReportedTotalTimeUs);
        }

        @Override
        protected long computeRunTimeLocked(long curBatteryRealtime, long elapsedRealtimeUs) {
            return mTotalTimeUs + (mTimeBaseRunning && mTrackingReportedValues
                    ? mCurrentReportedTotalTimeUs - mUnpluggedReportedTotalTimeUs : 0);
        }

        @Override
        protected int computeCurrentCountLocked() {
            return mCount + (mTimeBaseRunning && mTrackingReportedValues
                    ? mCurrentReportedCount - mUnpluggedReportedCount : 0);
        }

        @Override
        public void writeToParcel(Parcel out, long elapsedRealtimeUs) {
            super.writeToParcel(out, elapsedRealtimeUs);
            out.writeInt(mCurrentReportedCount);
            out.writeInt(mUnpluggedReportedCount);
            out.writeLong(mCurrentReportedTotalTimeUs);
            out.writeLong(mUnpluggedReportedTotalTimeUs);
            out.writeInt(mTrackingReportedValues ? 1 : 0);
        }

        @Override
        public boolean reset(boolean detachIfReset, long elapsedRealtimeUs) {
            super.reset(detachIfReset, elapsedRealtimeUs);
            mTrackingReportedValues = false;
            mUnpluggedReportedTotalTimeUs = 0;
            mUnpluggedReportedCount = 0;
            return true;
        }
    }

    /**
     * A timer that increments in batches.  It does not run for durations, but just jumps
     * for a pre-determined amount.
     */
    public static class BatchTimer extends Timer {
        final Uid mUid;

        /**
         * The last time at which we updated the timer.  This is in elapsed realtime microseconds.
         */
        long mLastAddedTimeUs;

        /**
         * The last duration that we added to the timer.  This is in microseconds.
         */
        long mLastAddedDurationUs;

        /**
         * Whether we are currently in a discharge cycle.
         */
        boolean mInDischarge;

        BatchTimer(Clock clock, Uid uid, int type, TimeBase timeBase, Parcel in) {
            super(clock, type, timeBase, in);
            mUid = uid;
            mLastAddedTimeUs = in.readLong();
            mLastAddedDurationUs = in.readLong();
            mInDischarge = timeBase.isRunning();
        }

        BatchTimer(Clock clock, Uid uid, int type, TimeBase timeBase) {
            super(clock, type, timeBase);
            mUid = uid;
            mInDischarge = timeBase.isRunning();
        }

        @Override
        public void writeToParcel(Parcel out, long elapsedRealtimeUs) {
            super.writeToParcel(out, elapsedRealtimeUs);
            out.writeLong(mLastAddedTimeUs);
            out.writeLong(mLastAddedDurationUs);
        }

        @Override
        public void onTimeStopped(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
            recomputeLastDuration(elapsedRealtimeUs, false);
            mInDischarge = false;
            super.onTimeStopped(elapsedRealtimeUs, baseUptimeUs, baseRealtimeUs);
        }

        @Override
        public void onTimeStarted(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
            recomputeLastDuration(elapsedRealtimeUs, false);
            mInDischarge = true;
            // If we are still within the last added duration, then re-added whatever remains.
            if (mLastAddedTimeUs == elapsedRealtimeUs) {
                mTotalTimeUs += mLastAddedDurationUs;
            }
            super.onTimeStarted(elapsedRealtimeUs, baseUptimeUs, baseRealtimeUs);
        }

        @Override
        public void logState(Printer pw, String prefix) {
            super.logState(pw, prefix);
            pw.println(prefix + "mLastAddedTime=" + mLastAddedTimeUs
                    + " mLastAddedDuration=" + mLastAddedDurationUs);
        }

        private long computeOverage(long curTimeUs) {
            if (mLastAddedTimeUs > 0) {
                return mLastAddedDurationUs - curTimeUs;
            }
            return 0;
        }

        private void recomputeLastDuration(long curTimeUs, boolean abort) {
            final long overage = computeOverage(curTimeUs);
            if (overage > 0) {
                // Aborting before the duration ran out -- roll back the remaining
                // duration.  Only do this if currently discharging; otherwise we didn't
                // actually add the time.
                if (mInDischarge) {
                    mTotalTimeUs -= overage;
                }
                if (abort) {
                    mLastAddedTimeUs = 0;
                } else {
                    mLastAddedTimeUs = curTimeUs;
                    mLastAddedDurationUs -= overage;
                }
            }
        }

        public void addDuration(BatteryStatsImpl stats, long durationMs) {
            addDuration(stats, durationMs, mClock.elapsedRealtime());
        }

        public void addDuration(BatteryStatsImpl stats, long durationMs, long elapsedRealtimeMs) {
            final long nowUs = elapsedRealtimeMs * 1000;
            recomputeLastDuration(nowUs, true);
            mLastAddedTimeUs = nowUs;
            mLastAddedDurationUs = durationMs * 1000;
            if (mInDischarge) {
                mTotalTimeUs += mLastAddedDurationUs;
                mCount++;
            }
        }

        public void abortLastDuration(BatteryStatsImpl stats) {
            abortLastDuration(stats, mClock.elapsedRealtime());
        }

        public void abortLastDuration(BatteryStatsImpl stats, long elapsedRealtimeMs) {
            final long nowUs = elapsedRealtimeMs * 1000;
            recomputeLastDuration(nowUs, true);
        }

        @Override
        protected int computeCurrentCountLocked() {
            return mCount;
        }

        @Override
        protected long computeRunTimeLocked(long curBatteryRealtimeUs, long elapsedRealtimeUs) {
            final long overage = computeOverage(elapsedRealtimeUs);
            if (overage > 0) {
                return mTotalTimeUs = overage;
            }
            return mTotalTimeUs;
        }

        @Override
        public boolean reset(boolean detachIfReset, long elapsedRealtimeUs) {
            recomputeLastDuration(elapsedRealtimeUs, true);
            boolean stillActive = mLastAddedTimeUs == elapsedRealtimeUs;
            super.reset(!stillActive && detachIfReset, elapsedRealtimeUs);
            return !stillActive;
        }
    }


    /**
     * A StopwatchTimer that also tracks the total and max individual
     * time spent active according to the given timebase.  Whereas
     * StopwatchTimer apportions the time amongst all in the pool,
     * the total and max durations are not apportioned.
     */
    public static class DurationTimer extends StopwatchTimer {
        /**
         * The time (in ms) that the timer was last acquired or the time base
         * last (re-)started. Increasing the nesting depth does not reset this time.
         *
         * -1 if the timer is currently not running or the time base is not running.
         *
         * If written to a parcel, the start time is reset, as is mNesting in the base class
         * StopwatchTimer.
         */
        long mStartTimeMs = -1;

        /**
         * The longest time period (in ms) that the timer has been active. Not pooled.
         */
        long mMaxDurationMs;

        /**
         * The time (in ms) that that the timer has been active since most recent
         * stopRunningLocked() or reset(). Not pooled.
         */
        long mCurrentDurationMs;

        /**
         * The total time (in ms) that that the timer has been active since most recent reset()
         * prior to the current startRunningLocked. This is the sum of all past currentDurations
         * (but not including the present currentDuration) since reset. Not pooled.
         */
        long mTotalDurationMs;

        public DurationTimer(Clock clock, Uid uid, int type, ArrayList<StopwatchTimer> timerPool,
                TimeBase timeBase, Parcel in) {
            super(clock, uid, type, timerPool, timeBase, in);
            mMaxDurationMs = in.readLong();
            mTotalDurationMs = in.readLong();
            mCurrentDurationMs = in.readLong();
        }

        public DurationTimer(Clock clock, Uid uid, int type, ArrayList<StopwatchTimer> timerPool,
                TimeBase timeBase) {
            super(clock, uid, type, timerPool, timeBase);
        }

        @Override
        public void writeToParcel(Parcel out, long elapsedRealtimeUs) {
            super.writeToParcel(out, elapsedRealtimeUs);
            out.writeLong(getMaxDurationMsLocked(elapsedRealtimeUs / 1000));
            out.writeLong(mTotalDurationMs);
            out.writeLong(getCurrentDurationMsLocked(elapsedRealtimeUs / 1000));
        }

        /**
         * Write the summary to the parcel.
         *
         * Since the time base is probably meaningless after we come back, reading
         * from this will have the effect of stopping the timer. So here all we write
         * is the max and total durations.
         */
        @Override
        public void writeSummaryFromParcelLocked(Parcel out, long elapsedRealtimeUs) {
            super.writeSummaryFromParcelLocked(out, elapsedRealtimeUs);
            out.writeLong(getMaxDurationMsLocked(elapsedRealtimeUs / 1000));
            out.writeLong(getTotalDurationMsLocked(elapsedRealtimeUs / 1000));
        }

        /**
         * Read the summary parcel.
         *
         * Has the side effect of stopping the timer.
         */
        @Override
        public void readSummaryFromParcelLocked(Parcel in) {
            super.readSummaryFromParcelLocked(in);
            mMaxDurationMs = in.readLong();
            mTotalDurationMs = in.readLong();
            mStartTimeMs = -1;
            mCurrentDurationMs = 0;
        }

        /**
         * The TimeBase time started (again).
         *
         * If the timer is also running, store the start time.
         */
        public void onTimeStarted(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
            super.onTimeStarted(elapsedRealtimeUs, baseUptimeUs, baseRealtimeUs);
            if (mNesting > 0) {
                mStartTimeMs = baseRealtimeUs / 1000;
            }
        }

        /**
         * The TimeBase stopped running.
         *
         * If the timer is running, add the duration into mCurrentDurationMs.
         */
        @Override
        public void onTimeStopped(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
            super.onTimeStopped(elapsedRealtimeUs, baseUptimeUs, baseRealtimeUs);
            if (mNesting > 0) {
                // baseRealtimeUs has already been converted to the timebase's realtime.
                mCurrentDurationMs += (baseRealtimeUs / 1000) - mStartTimeMs;
            }
            mStartTimeMs = -1;
        }

        @Override
        public void logState(Printer pw, String prefix) {
            super.logState(pw, prefix);
        }

        @Override
        public void startRunningLocked(long elapsedRealtimeMs) {
            super.startRunningLocked(elapsedRealtimeMs);
            if (mNesting == 1 && mTimeBase.isRunning()) {
                // Just started
                mStartTimeMs = mTimeBase.getRealtime(elapsedRealtimeMs * 1000) / 1000;
            }
        }

        /**
         * Decrements the mNesting ref-count on this timer.
         *
         * If it actually stopped (mNesting went to 0), then possibly update
         * mMaxDuration if the current duration was the longest ever.
         */
        @Override
        public void stopRunningLocked(long elapsedRealtimeMs) {
            if (mNesting == 1) {
                final long durationMs = getCurrentDurationMsLocked(elapsedRealtimeMs);
                mTotalDurationMs += durationMs;
                if (durationMs > mMaxDurationMs) {
                    mMaxDurationMs = durationMs;
                }
                mStartTimeMs = -1;
                mCurrentDurationMs = 0;
            }
            // super method decrements mNesting, which getCurrentDurationMsLocked relies on,
            // so call super.stopRunningLocked after calling getCurrentDurationMsLocked.
            super.stopRunningLocked(elapsedRealtimeMs);
        }

        @Override
        public boolean reset(boolean detachIfReset, long elapsedRealtimeUs) {
            boolean result = super.reset(detachIfReset, elapsedRealtimeUs);
            mMaxDurationMs = 0;
            mTotalDurationMs = 0;
            mCurrentDurationMs = 0;
            if (mNesting > 0) {
                mStartTimeMs = mTimeBase.getRealtime(elapsedRealtimeUs) / 1000;
            } else {
                mStartTimeMs = -1;
            }
            return result;
        }

        /**
         * Returns the max duration that this timer has ever seen.
         *
         * Note that this time is NOT split between the timers in the timer group that
         * this timer is attached to.  It is the TOTAL time.
         */
        @Override
        public long getMaxDurationMsLocked(long elapsedRealtimeMs) {
            if (mNesting > 0) {
                final long durationMs = getCurrentDurationMsLocked(elapsedRealtimeMs);
                if (durationMs > mMaxDurationMs) {
                    return durationMs;
                }
            }
            return mMaxDurationMs;
        }

        /**
         * Returns the time since the timer was started.
         * Returns 0 if the timer is not currently running.
         *
         * Note that this time is NOT split between the timers in the timer group that
         * this timer is attached to.  It is the TOTAL time.
         *
         * Note that if running timer is parceled and unparceled, this method will return
         * current duration value at the time of parceling even though timer may not be
         * currently running.
         */
        @Override
        public long getCurrentDurationMsLocked(long elapsedRealtimeMs) {
            long durationMs = mCurrentDurationMs;
            if (mNesting > 0 && mTimeBase.isRunning()) {
                durationMs += (mTimeBase.getRealtime(elapsedRealtimeMs * 1000) / 1000)
                        - mStartTimeMs;
            }
            return durationMs;
        }

        /**
         * Returns the total cumulative duration that this timer has been on since reset().
         * If mTimerPool == null, this should be the same
         * as getTotalTimeLocked(elapsedRealtimeMs*1000, STATS_SINCE_CHARGED)/1000.
         *
         * Note that this time is NOT split between the timers in the timer group that
         * this timer is attached to.  It is the TOTAL time. For this reason, if mTimerPool != null,
         * the result will not be equivalent to getTotalTimeLocked.
         */
        @Override
        public long getTotalDurationMsLocked(long elapsedRealtimeMs) {
            return mTotalDurationMs + getCurrentDurationMsLocked(elapsedRealtimeMs);
        }
    }

    /**
     * State for keeping track of timing information.
     */
    public static class StopwatchTimer extends Timer {
        final Uid mUid;
        final ArrayList<StopwatchTimer> mTimerPool;

        int mNesting;

        /**
         * The last time at which we updated the timer.  If mNesting is > 0,
         * subtract this from the current battery time to find the amount of
         * time we have been running since we last computed an update.
         */
        long mUpdateTimeUs;

        /**
         * The total time at which the timer was acquired, to determine if it
         * was actually held for an interesting duration. If time base was not running when timer
         * was acquired, will be -1.
         */
        long mAcquireTimeUs = -1;

        long mTimeoutUs;

        /**
         * For partial wake locks, keep track of whether we are in the list
         * to consume CPU cycles.
         */
        @VisibleForTesting
        public boolean mInList;

        public StopwatchTimer(Clock clock, Uid uid, int type, ArrayList<StopwatchTimer> timerPool,
                TimeBase timeBase, Parcel in) {
            super(clock, type, timeBase, in);
            mUid = uid;
            mTimerPool = timerPool;
            mUpdateTimeUs = in.readLong();
        }

        public StopwatchTimer(Clock clock, Uid uid, int type, ArrayList<StopwatchTimer> timerPool,
                TimeBase timeBase) {
            super(clock, type, timeBase);
            mUid = uid;
            mTimerPool = timerPool;
        }

        public void setTimeout(long timeoutUs) {
            mTimeoutUs = timeoutUs;
        }

        public void writeToParcel(Parcel out, long elapsedRealtimeUs) {
            super.writeToParcel(out, elapsedRealtimeUs);
            out.writeLong(mUpdateTimeUs);
        }

        public void onTimeStopped(long elapsedRealtimeUs, long baseUptimeUs, long baseRealtimeUs) {
            if (mNesting > 0) {
                if (DEBUG && mType < 0) {
                    Log.v(TAG, "old mUpdateTime=" + mUpdateTimeUs);
                }
                super.onTimeStopped(elapsedRealtimeUs, baseUptimeUs, baseRealtimeUs);
                mUpdateTimeUs = baseRealtimeUs;
                if (DEBUG && mType < 0) {
                    Log.v(TAG, "new mUpdateTime=" + mUpdateTimeUs);
                }
            }
        }

        public void logState(Printer pw, String prefix) {
            super.logState(pw, prefix);
            pw.println(prefix + "mNesting=" + mNesting + " mUpdateTime=" + mUpdateTimeUs
                    + " mAcquireTime=" + mAcquireTimeUs);
        }

        public void startRunningLocked(long elapsedRealtimeMs) {
            if (mNesting++ == 0) {
                final long batteryRealtimeUs = mTimeBase.getRealtime(elapsedRealtimeMs * 1000);
                mUpdateTimeUs = batteryRealtimeUs;
                if (mTimerPool != null) {
                    // Accumulate time to all currently active timers before adding
                    // this new one to the pool.
                    refreshTimersLocked(batteryRealtimeUs, mTimerPool, null);
                    // Add this timer to the active pool
                    mTimerPool.add(this);
                }
                if (mTimeBase.isRunning()) {
                    // Increment the count
                    mCount++;
                    mAcquireTimeUs = mTotalTimeUs;
                } else {
                    mAcquireTimeUs = -1;
                }
                if (DEBUG && mType < 0) {
                    Log.v(TAG, "start #" + mType + ": mUpdateTime=" + mUpdateTimeUs
                            + " mTotalTime=" + mTotalTimeUs + " mCount=" + mCount
                            + " mAcquireTime=" + mAcquireTimeUs);
                }
            }
        }

        public boolean isRunningLocked() {
            return mNesting > 0;
        }

        public void stopRunningLocked(long elapsedRealtimeMs) {
            // Ignore attempt to stop a timer that isn't running
            if (mNesting == 0) {
                return;
            }
            if (--mNesting == 0) {
                final long batteryRealtimeUs = mTimeBase.getRealtime(elapsedRealtimeMs * 1000);
                if (mTimerPool != null) {
                    // Accumulate time to all active counters, scaled by the total
                    // active in the pool, before taking this one out of the pool.
                    refreshTimersLocked(batteryRealtimeUs, mTimerPool, null);
                    // Remove this timer from the active pool
                    mTimerPool.remove(this);
                } else {
                    mNesting = 1;
                    mTotalTimeUs = computeRunTimeLocked(batteryRealtimeUs,
                            elapsedRealtimeMs * 1000);
                    mNesting = 0;
                }

                if (DEBUG && mType < 0) {
                    Log.v(TAG, "stop #" + mType + ": mUpdateTime=" + mUpdateTimeUs
                            + " mTotalTime=" + mTotalTimeUs + " mCount=" + mCount
                            + " mAcquireTime=" + mAcquireTimeUs);
                }

                if (mAcquireTimeUs >= 0 && mTotalTimeUs == mAcquireTimeUs) {
                    // If there was no change in the time, then discard this
                    // count.  A somewhat cheezy strategy, but hey.
                    mCount--;
                }
            }
        }

        public void stopAllRunningLocked(long elapsedRealtimeMs) {
            if (mNesting > 0) {
                mNesting = 1;
                stopRunningLocked(elapsedRealtimeMs);
            }
        }

        // Update the total time for all other running Timers with the same type as this Timer
        // due to a change in timer count
        private static long refreshTimersLocked(long batteryRealtimeUs,
                final ArrayList<StopwatchTimer> pool, StopwatchTimer self) {
            long selfTimeUs = 0;
            final int N = pool.size();
            for (int i=N-1; i>= 0; i--) {
                final StopwatchTimer t = pool.get(i);
                long heldTimeUs = batteryRealtimeUs - t.mUpdateTimeUs;
                if (heldTimeUs > 0) {
                    final long myTimeUs = heldTimeUs / N;
                    if (t == self) {
                        selfTimeUs = myTimeUs;
                    }
                    t.mTotalTimeUs += myTimeUs;
                }
                t.mUpdateTimeUs = batteryRealtimeUs;
            }
            return selfTimeUs;
        }

        @Override
        protected long computeRunTimeLocked(long curBatteryRealtimeUs, long elapsedRealtimeUs) {
            if (mTimeoutUs > 0 && curBatteryRealtimeUs > mUpdateTimeUs + mTimeoutUs) {
                curBatteryRealtimeUs = mUpdateTimeUs + mTimeoutUs;
            }
            return mTotalTimeUs + (mNesting > 0
                    ? (curBatteryRealtimeUs - mUpdateTimeUs)
                            / (mTimerPool != null ? mTimerPool.size() : 1)
                    : 0);
        }

        @Override
        protected int computeCurrentCountLocked() {
            return mCount;
        }

        @Override
        public boolean reset(boolean detachIfReset, long elapsedRealtimeUs) {
            boolean canDetach = mNesting <= 0;
            super.reset(canDetach && detachIfReset, elapsedRealtimeUs);
            if (mNesting > 0) {
                mUpdateTimeUs = mTimeBase.getRealtime(elapsedRealtimeUs);
            }
            // To ensure mCount isn't decreased to -1 if timer is stopped later.
            mAcquireTimeUs = -1;
            return canDetach;
        }

        @Override
        @UnsupportedAppUsage
        public void detach() {
            super.detach();
            if (mTimerPool != null) {
                mTimerPool.remove(this);
            }
        }

        @Override
        public void readSummaryFromParcelLocked(Parcel in) {
            super.readSummaryFromParcelLocked(in);
            mNesting = 0;
        }

        /**
         * Set the mark so that we can query later for the total time the timer has
         * accumulated since this point. The timer can be running or not.
         *
         * @param elapsedRealtimeMs the current elapsed realtime in milliseconds.
         */
        public void setMark(long elapsedRealtimeMs) {
            final long batteryRealtimeUs = mTimeBase.getRealtime(elapsedRealtimeMs * 1000);
            if (mNesting > 0) {
                // We are running.
                if (mTimerPool != null) {
                    refreshTimersLocked(batteryRealtimeUs, mTimerPool, this);
                } else {
                    mTotalTimeUs += batteryRealtimeUs - mUpdateTimeUs;
                    mUpdateTimeUs = batteryRealtimeUs;
                }
            }
            mTimeBeforeMarkUs = mTotalTimeUs;
        }
    }

    /**
     * State for keeping track of two DurationTimers with different TimeBases, presumably where one
     * TimeBase is effectively a subset of the other.
     */
    public static class DualTimer extends DurationTimer {
        // This class both is a DurationTimer and also holds a second DurationTimer.
        // The main timer (this) typically tracks the total time. It may be pooled (but since it's a
        // durationTimer, it also has the unpooled getTotalDurationMsLocked() for
        // STATS_SINCE_CHARGED).
        // mSubTimer typically tracks only part of the total time, such as background time, as
        // determined by a subTimeBase. It is NOT pooled.
        private final DurationTimer mSubTimer;

        /**
         * Creates a DualTimer to hold a main timer (this) and a mSubTimer.
         * The main timer (this) is based on the given timeBase and timerPool.
         * The mSubTimer is based on the given subTimeBase. The mSubTimer is not pooled, even if
         * the main timer is.
         */
        public DualTimer(Clock clock, Uid uid, int type, ArrayList<StopwatchTimer> timerPool,
                TimeBase timeBase, TimeBase subTimeBase, Parcel in) {
            super(clock, uid, type, timerPool, timeBase, in);
            mSubTimer = new DurationTimer(clock, uid, type, null, subTimeBase, in);
        }

        /**
         * Creates a DualTimer to hold a main timer (this) and a mSubTimer.
         * The main timer (this) is based on the given timeBase and timerPool.
         * The mSubTimer is based on the given subTimeBase. The mSubTimer is not pooled, even if
         * the main timer is.
         */
        public DualTimer(Clock clock, Uid uid, int type, ArrayList<StopwatchTimer> timerPool,
                TimeBase timeBase, TimeBase subTimeBase) {
            super(clock, uid, type, timerPool, timeBase);
            mSubTimer = new DurationTimer(clock, uid, type, null, subTimeBase);
        }

        /** Get the secondary timer. */
        @Override
        public DurationTimer getSubTimer() {
            return mSubTimer;
        }

        @Override
        public void startRunningLocked(long elapsedRealtimeMs) {
            super.startRunningLocked(elapsedRealtimeMs);
            mSubTimer.startRunningLocked(elapsedRealtimeMs);
        }

        @Override
        public void stopRunningLocked(long elapsedRealtimeMs) {
            super.stopRunningLocked(elapsedRealtimeMs);
            mSubTimer.stopRunningLocked(elapsedRealtimeMs);
        }

        @Override
        public void stopAllRunningLocked(long elapsedRealtimeMs) {
            super.stopAllRunningLocked(elapsedRealtimeMs);
            mSubTimer.stopAllRunningLocked(elapsedRealtimeMs);
        }

        @Override
        public boolean reset(boolean detachIfReset, long elapsedRealtimeUs) {
            boolean active = false;
            // Do not detach the subTimer explicitly since that'll be done by DualTimer.detach().
            active |= !mSubTimer.reset(false, elapsedRealtimeUs);
            active |= !super.reset(detachIfReset, elapsedRealtimeUs);
            return !active;
        }

        @Override
        public void detach() {
            mSubTimer.detach();
            super.detach();
        }

        @Override
        public void writeToParcel(Parcel out, long elapsedRealtimeUs) {
            super.writeToParcel(out, elapsedRealtimeUs);
            mSubTimer.writeToParcel(out, elapsedRealtimeUs);
        }

        @Override
        public void writeSummaryFromParcelLocked(Parcel out, long elapsedRealtimeUs) {
            super.writeSummaryFromParcelLocked(out, elapsedRealtimeUs);
            mSubTimer.writeSummaryFromParcelLocked(out, elapsedRealtimeUs);
        }

        @Override
        public void readSummaryFromParcelLocked(Parcel in) {
            super.readSummaryFromParcelLocked(in);
            mSubTimer.readSummaryFromParcelLocked(in);
        }
    }


    public abstract class OverflowArrayMap<T> {
        private static final String OVERFLOW_NAME = "*overflow*";

        final int mUid;
        final ArrayMap<String, T> mMap = new ArrayMap<>();
        T mCurOverflow;
        ArrayMap<String, MutableInt> mActiveOverflow;
        long mLastOverflowTimeMs;
        long mLastOverflowFinishTimeMs;
        long mLastClearTimeMs;
        long mLastCleanupTimeMs;

        public OverflowArrayMap(int uid) {
            mUid = uid;
        }

        public ArrayMap<String, T> getMap() {
            return mMap;
        }

        public void clear() {
            mLastClearTimeMs = SystemClock.elapsedRealtime();
            mMap.clear();
            mCurOverflow = null;
            mActiveOverflow = null;
        }

        public void add(String name, T obj) {
            if (name == null) {
                name = "";
            }
            mMap.put(name, obj);
            if (OVERFLOW_NAME.equals(name)) {
                mCurOverflow = obj;
            }
        }

        public void cleanup(long elapsedRealtimeMs) {
            mLastCleanupTimeMs = elapsedRealtimeMs;
            if (mActiveOverflow != null) {
                if (mActiveOverflow.size() == 0) {
                    mActiveOverflow = null;
                }
            }
            if (mActiveOverflow == null) {
                // There is no currently active overflow, so we should no longer have
                // an overflow entry.
                if (mMap.containsKey(OVERFLOW_NAME)) {
                    Slog.wtf(TAG, "Cleaning up with no active overflow, but have overflow entry "
                            + mMap.get(OVERFLOW_NAME));
                    mMap.remove(OVERFLOW_NAME);
                }
                mCurOverflow = null;
            } else {
                // There is currently active overflow, so we should still have an overflow entry.
                if (mCurOverflow == null || !mMap.containsKey(OVERFLOW_NAME)) {
                    Slog.wtf(TAG, "Cleaning up with active overflow, but no overflow entry: cur="
                            + mCurOverflow + " map=" + mMap.get(OVERFLOW_NAME));
                }
            }
        }

        public T startObject(String name, long elapsedRealtimeMs) {
            if (name == null) {
                name = "";
            }
            T obj = mMap.get(name);
            if (obj != null) {
                return obj;
            }

            // No object exists for the given name, but do we currently have it
            // running as part of the overflow?
            if (mActiveOverflow != null) {
                MutableInt over = mActiveOverflow.get(name);
                if (over != null) {
                    // We are already actively counting this name in the overflow object.
                    obj = mCurOverflow;
                    if (obj == null) {
                        // Shouldn't be here, but we'll try to recover.
                        Slog.wtf(TAG, "Have active overflow " + name + " but null overflow");
                        obj = mCurOverflow = instantiateObject();
                        mMap.put(OVERFLOW_NAME, obj);
                    }
                    over.value++;
                    return obj;
                }
            }

            // No object exists for given name nor in the overflow; we need to make
            // a new one.
            final int N = mMap.size();
            if (N >= MAX_WAKELOCKS_PER_UID) {
                // Went over the limit on number of objects to track; this one goes
                // in to the overflow.
                obj = mCurOverflow;
                if (obj == null) {
                    // Need to start overflow now...
                    obj = mCurOverflow = instantiateObject();
                    mMap.put(OVERFLOW_NAME, obj);
                }
                if (mActiveOverflow == null) {
                    mActiveOverflow = new ArrayMap<>();
                }
                mActiveOverflow.put(name, new MutableInt(1));
                mLastOverflowTimeMs = elapsedRealtimeMs;
                return obj;
            }

            // Normal case where we just need to make a new object.
            obj = instantiateObject();
            mMap.put(name, obj);
            return obj;
        }

        public T stopObject(String name, long elapsedRealtimeMs) {
            if (name == null) {
                name = "";
            }
            T obj = mMap.get(name);
            if (obj != null) {
                return obj;
            }

            // No object exists for the given name, but do we currently have it
            // running as part of the overflow?
            if (mActiveOverflow != null) {
                MutableInt over = mActiveOverflow.get(name);
                if (over != null) {
                    // We are already actively counting this name in the overflow object.
                    obj = mCurOverflow;
                    if (obj != null) {
                        over.value--;
                        if (over.value <= 0) {
                            mActiveOverflow.remove(name);
                            mLastOverflowFinishTimeMs = elapsedRealtimeMs;
                        }
                        return obj;
                    }
                }
            }

            // Huh, they are stopping an active operation but we can't find one!
            // That's not good.
            StringBuilder sb = new StringBuilder();
            sb.append("Unable to find object for ");
            sb.append(name);
            sb.append(" in uid ");
            sb.append(mUid);
            sb.append(" mapsize=");
            sb.append(mMap.size());
            sb.append(" activeoverflow=");
            sb.append(mActiveOverflow);
            sb.append(" curoverflow=");
            sb.append(mCurOverflow);
            long now = elapsedRealtimeMs;
            if (mLastOverflowTimeMs != 0) {
                sb.append(" lastOverflowTime=");
                TimeUtils.formatDuration(mLastOverflowTimeMs - now, sb);
            }
            if (mLastOverflowFinishTimeMs != 0) {
                sb.append(" lastOverflowFinishTime=");
                TimeUtils.formatDuration(mLastOverflowFinishTimeMs - now, sb);
            }
            if (mLastClearTimeMs != 0) {
                sb.append(" lastClearTime=");
                TimeUtils.formatDuration(mLastClearTimeMs - now, sb);
            }
            if (mLastCleanupTimeMs != 0) {
                sb.append(" lastCleanupTime=");
                TimeUtils.formatDuration(mLastCleanupTimeMs - now, sb);
            }
            Slog.wtf(TAG, sb.toString());
            return null;
        }

        public abstract T instantiateObject();
    }

    @SuppressWarnings("ParcelableCreator")
    public static class ControllerActivityCounterImpl extends ControllerActivityCounter
            implements Parcelable {
        private final Clock mClock;
        private final TimeBase mTimeBase;
        private int mNumTxStates;
        private int mProcessState;
        private TimeMultiStateCounter mIdleTimeMillis;
        private final LongSamplingCounter mScanTimeMillis;
        private final LongSamplingCounter mSleepTimeMillis;
        private TimeMultiStateCounter mRxTimeMillis;
        private TimeMultiStateCounter[] mTxTimeMillis;
        private final LongSamplingCounter mPowerDrainMaMs;
        private final LongSamplingCounter mMonitoredRailChargeConsumedMaMs;

        public ControllerActivityCounterImpl(Clock clock, TimeBase timeBase, int numTxStates) {
            mClock = clock;
            mTimeBase = timeBase;
            mNumTxStates = numTxStates;
            mScanTimeMillis = new LongSamplingCounter(timeBase);
            mSleepTimeMillis = new LongSamplingCounter(timeBase);
            mPowerDrainMaMs = new LongSamplingCounter(timeBase);
            mMonitoredRailChargeConsumedMaMs = new LongSamplingCounter(timeBase);
        }

        public ControllerActivityCounterImpl(Clock clock, TimeBase timeBase, int numTxStates,
                Parcel in) {
            mClock = clock;
            mTimeBase = timeBase;
            mNumTxStates = numTxStates;
            mIdleTimeMillis = readTimeMultiStateCounter(in, timeBase);
            mScanTimeMillis = new LongSamplingCounter(timeBase, in);
            mSleepTimeMillis = new LongSamplingCounter(timeBase, in);
            mRxTimeMillis = readTimeMultiStateCounter(in, timeBase);
            mTxTimeMillis = readTimeMultiStateCounters(in, timeBase, numTxStates);

            mPowerDrainMaMs = new LongSamplingCounter(timeBase, in);
            mMonitoredRailChargeConsumedMaMs = new LongSamplingCounter(timeBase, in);
        }

        public void readSummaryFromParcel(Parcel in) {
            mIdleTimeMillis = readTimeMultiStateCounter(in, mTimeBase);
            mScanTimeMillis.readSummaryFromParcelLocked(in);
            mSleepTimeMillis.readSummaryFromParcelLocked(in);
            mRxTimeMillis = readTimeMultiStateCounter(in, mTimeBase);
            mTxTimeMillis = readTimeMultiStateCounters(in, mTimeBase, mNumTxStates);

            mPowerDrainMaMs.readSummaryFromParcelLocked(in);
            mMonitoredRailChargeConsumedMaMs.readSummaryFromParcelLocked(in);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public void writeSummaryToParcel(Parcel dest) {
            writeTimeMultiStateCounter(dest, mIdleTimeMillis);
            mScanTimeMillis.writeSummaryFromParcelLocked(dest);
            mSleepTimeMillis.writeSummaryFromParcelLocked(dest);
            writeTimeMultiStateCounter(dest, mRxTimeMillis);
            writeTimeMultiStateCounters(dest, mTxTimeMillis);
            mPowerDrainMaMs.writeSummaryFromParcelLocked(dest);
            mMonitoredRailChargeConsumedMaMs.writeSummaryFromParcelLocked(dest);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            writeTimeMultiStateCounter(dest, mIdleTimeMillis);
            mScanTimeMillis.writeToParcel(dest);
            mSleepTimeMillis.writeToParcel(dest);
            writeTimeMultiStateCounter(dest, mRxTimeMillis);
            writeTimeMultiStateCounters(dest, mTxTimeMillis);
            mPowerDrainMaMs.writeToParcel(dest);
            mMonitoredRailChargeConsumedMaMs.writeToParcel(dest);
        }

        private TimeMultiStateCounter readTimeMultiStateCounter(Parcel in, TimeBase timeBase) {
            if (in.readBoolean()) {
                return TimeMultiStateCounter.readFromParcel(in, timeBase,
                        BatteryConsumer.PROCESS_STATE_COUNT, mClock.elapsedRealtime());
            }
            return null;
        }

        private void writeTimeMultiStateCounter(Parcel dest, TimeMultiStateCounter counter) {
            if (counter != null) {
                dest.writeBoolean(true);
                counter.writeToParcel(dest);
            } else {
                dest.writeBoolean(false);
            }
        }

        private TimeMultiStateCounter[] readTimeMultiStateCounters(Parcel in, TimeBase timeBase,
                int expectedNumCounters) {
            if (in.readBoolean()) {
                final int numCounters = in.readInt();
                boolean valid = (numCounters == expectedNumCounters);
                // Need to read counters out of the Parcel, even if all or some of them are
                // invalid.
                TimeMultiStateCounter[] counters = new TimeMultiStateCounter[numCounters];
                for (int i = 0; i < numCounters; i++) {
                    final TimeMultiStateCounter counter = TimeMultiStateCounter.readFromParcel(in,
                            timeBase, BatteryConsumer.PROCESS_STATE_COUNT,
                            mClock.elapsedRealtime());
                    if (counter != null) {
                        counters[i] = counter;
                    } else {
                        valid = false;
                    }
                }
                if (valid) {
                    return counters;
                }
            }
            return null;
        }

        private void writeTimeMultiStateCounters(Parcel dest, TimeMultiStateCounter[] counters) {
            if (counters != null) {
                dest.writeBoolean(true);
                dest.writeInt(counters.length);
                for (TimeMultiStateCounter counter : counters) {
                    counter.writeToParcel(dest);
                }
            } else {
                dest.writeBoolean(false);
            }
        }

        public void reset(boolean detachIfReset, long elapsedRealtimeUs) {
            resetIfNotNull(mIdleTimeMillis, detachIfReset, elapsedRealtimeUs);
            mScanTimeMillis.reset(detachIfReset, elapsedRealtimeUs);
            mSleepTimeMillis.reset(detachIfReset, elapsedRealtimeUs);
            resetIfNotNull(mRxTimeMillis, detachIfReset, elapsedRealtimeUs);
            resetIfNotNull(mTxTimeMillis, detachIfReset, elapsedRealtimeUs);
            mPowerDrainMaMs.reset(detachIfReset, elapsedRealtimeUs);
            mMonitoredRailChargeConsumedMaMs.reset(detachIfReset, elapsedRealtimeUs);
        }

        public void detach() {
            detachIfNotNull(mIdleTimeMillis);
            mScanTimeMillis.detach();
            mSleepTimeMillis.detach();
            detachIfNotNull(mRxTimeMillis);
            detachIfNotNull(mTxTimeMillis);
            mPowerDrainMaMs.detach();
            mMonitoredRailChargeConsumedMaMs.detach();
        }

        /**
         * @return a LongSamplingCounter, measuring time spent in the idle state in
         * milliseconds.
         */
        @Override
        public LongCounter getIdleTimeCounter() {
            if (mIdleTimeMillis == null) {
                return ZERO_LONG_COUNTER;
            }
            return mIdleTimeMillis;
        }

        private TimeMultiStateCounter getOrCreateIdleTimeCounter() {
            if (mIdleTimeMillis == null) {
                mIdleTimeMillis = createTimeMultiStateCounter();
            }
            return mIdleTimeMillis;
        }

        /**
         * @return a LongSamplingCounter, measuring time spent in the scan state in
         * milliseconds.
         */
        @Override
        public LongSamplingCounter getScanTimeCounter() {
            return mScanTimeMillis;
        }

        /**
         * @return a LongSamplingCounter, measuring time spent in the sleep state in
         * milliseconds.
         */
        @Override
        public LongSamplingCounter getSleepTimeCounter() {
            return mSleepTimeMillis;
        }

        /**
         * @return a LongSamplingCounter, measuring time spent in the receive state in
         * milliseconds.
         */
        @Override
        public LongCounter getRxTimeCounter() {
            if (mRxTimeMillis == null) {
                return ZERO_LONG_COUNTER;
            }
            return mRxTimeMillis;
        }

        private TimeMultiStateCounter getOrCreateRxTimeCounter() {
            if (mRxTimeMillis == null) {
                mRxTimeMillis = createTimeMultiStateCounter();
            }
            return mRxTimeMillis;
        }

        /**
         * @return a LongSamplingCounter[], measuring time spent in various transmit states in
         * milliseconds.
         */
        @Override
        public LongCounter[] getTxTimeCounters() {
            if (mTxTimeMillis == null) {
                return ZERO_LONG_COUNTER_ARRAY;
            }
            return mTxTimeMillis;
        }

        private TimeMultiStateCounter[] getOrCreateTxTimeCounters() {
            if (mTxTimeMillis == null) {
                mTxTimeMillis = new TimeMultiStateCounter[mNumTxStates];
                for (int i = 0; i < mNumTxStates; i++) {
                    mTxTimeMillis[i] = createTimeMultiStateCounter();
                }
            }
            return mTxTimeMillis;
        }

        private TimeMultiStateCounter createTimeMultiStateCounter() {
            final long timestampMs = mClock.elapsedRealtime();
            TimeMultiStateCounter counter = new TimeMultiStateCounter(mTimeBase,
                    BatteryConsumer.PROCESS_STATE_COUNT, timestampMs);
            counter.setState(mapUidProcessStateToBatteryConsumerProcessState(mProcessState),
                    timestampMs);
            counter.update(0, timestampMs);
            return counter;
        }

        /**
         * @return a LongSamplingCounter, measuring power use in milli-ampere milliseconds (mAmS).
         */
        @Override
        public LongSamplingCounter getPowerCounter() {
            return mPowerDrainMaMs;
        }

        /**
         * @return a LongSamplingCounter, measuring actual monitored rail energy consumed
         * milli-ampere milli-seconds (mAmS).
         */
        @Override
        public LongSamplingCounter getMonitoredRailChargeConsumedMaMs() {
            return mMonitoredRailChargeConsumedMaMs;
        }

        private void setState(int processState, long elapsedTimeMs) {
            mProcessState = processState;
            if (mIdleTimeMillis != null) {
                mIdleTimeMillis.setState(processState, elapsedTimeMs);
            }
            if (mRxTimeMillis != null) {
                mRxTimeMillis.setState(processState, elapsedTimeMs);
            }
            if (mTxTimeMillis != null) {
                for (int i = 0; i < mTxTimeMillis.length; i++) {
                    mTxTimeMillis[i].setState(processState, elapsedTimeMs);
                }
            }
        }
    }

    /** Get Resource Power Manager stats. Create a new one if it doesn't already exist. */
    public SamplingTimer getRpmTimerLocked(String name) {
        SamplingTimer rpmt = mRpmStats.get(name);
        if (rpmt == null) {
            rpmt = new SamplingTimer(mClock, mOnBatteryTimeBase);
            mRpmStats.put(name, rpmt);
        }
        return rpmt;
    }

    /** Get Screen-off Resource Power Manager stats. Create new one if it doesn't already exist. */
    public SamplingTimer getScreenOffRpmTimerLocked(String name) {
        SamplingTimer rpmt = mScreenOffRpmStats.get(name);
        if (rpmt == null) {
            rpmt = new SamplingTimer(mClock, mOnBatteryScreenOffTimeBase);
            mScreenOffRpmStats.put(name, rpmt);
        }
        return rpmt;
    }

    /*
     * Get the wakeup reason counter, and create a new one if one
     * doesn't already exist.
     */
    public SamplingTimer getWakeupReasonTimerLocked(String name) {
        SamplingTimer timer = mWakeupReasonStats.get(name);
        if (timer == null) {
            timer = new SamplingTimer(mClock, mOnBatteryTimeBase);
            mWakeupReasonStats.put(name, timer);
        }
        return timer;
    }

    /*
     * Get the KernelWakelockTimer associated with name, and create a new one if one
     * doesn't already exist.
     */
    public SamplingTimer getKernelWakelockTimerLocked(String name) {
        SamplingTimer kwlt = mKernelWakelockStats.get(name);
        if (kwlt == null) {
            kwlt = new SamplingTimer(mClock, mOnBatteryScreenOffTimeBase);
            mKernelWakelockStats.put(name, kwlt);
        }
        return kwlt;
    }

    public SamplingTimer getKernelMemoryTimerLocked(long bucket) {
        SamplingTimer kmt = mKernelMemoryStats.get(bucket);
        if (kmt == null) {
            kmt = new SamplingTimer(mClock, mOnBatteryTimeBase);
            mKernelMemoryStats.put(bucket, kmt);
        }
        return kmt;
    }

    /**
     * Returns the index for the specified tag. If this is the first time the tag is encountered
     * while writing the current history buffer, the method returns
     * <code>(index | TAG_FIRST_OCCURRENCE_FLAG)</code>
     */
    private int writeHistoryTag(HistoryTag tag) {
        if (tag.string == null) {
            Slog.wtfStack(TAG, "writeHistoryTag called with null name");
        }

        final int stringLength = tag.string.length();
        if (stringLength > MAX_HISTORY_TAG_STRING_LENGTH) {
            Slog.e(TAG, "Long battery history tag: " + tag.string);
            tag.string = tag.string.substring(0, MAX_HISTORY_TAG_STRING_LENGTH);
        }

        Integer idxObj = mHistoryTagPool.get(tag);
        int idx;
        if (idxObj != null) {
            idx = idxObj;
            if ((idx & TAG_FIRST_OCCURRENCE_FLAG) != 0) {
                mHistoryTagPool.put(tag, idx & ~TAG_FIRST_OCCURRENCE_FLAG);
            }
            return idx;
        } else if (mNextHistoryTagIdx < HISTORY_TAG_INDEX_LIMIT) {
            idx = mNextHistoryTagIdx;
            HistoryTag key = new HistoryTag();
            key.setTo(tag);
            tag.poolIdx = idx;
            mHistoryTagPool.put(key, idx);
            mNextHistoryTagIdx++;

            mNumHistoryTagChars += stringLength + 1;
            if (mHistoryTags != null) {
                mHistoryTags.put(idx, key);
            }
            return idx | TAG_FIRST_OCCURRENCE_FLAG;
        } else {
            // Tag pool overflow: include the tag itself in the parcel
            return HISTORY_TAG_INDEX_LIMIT | TAG_FIRST_OCCURRENCE_FLAG;
        }
    }

    /*
        The history delta format uses flags to denote further data in subsequent ints in the parcel.

        There is always the first token, which may contain the delta time, or an indicator of
        the length of the time (int or long) following this token.

        First token: always present,
        31              23              15               7             0
        █M|L|K|J|I|H|G|F█E|D|C|B|A|T|T|T█T|T|T|T|T|T|T|T█T|T|T|T|T|T|T|T█

        T: the delta time if it is <= 0x7fffd. Otherwise 0x7fffe indicates an int immediately
           follows containing the time, and 0x7ffff indicates a long immediately follows with the
           delta time.
        A: battery level changed and an int follows with battery data.
        B: state changed and an int follows with state change data.
        C: state2 has changed and an int follows with state2 change data.
        D: wakelock/wakereason has changed and an wakelock/wakereason struct follows.
        E: event data has changed and an event struct follows.
        F: battery charge in coulombs has changed and an int with the charge follows.
        G: state flag denoting that the mobile radio was active.
        H: state flag denoting that the wifi radio was active.
        I: state flag denoting that a wifi scan occurred.
        J: state flag denoting that a wifi full lock was held.
        K: state flag denoting that the gps was on.
        L: state flag denoting that a wakelock was held.
        M: state flag denoting that the cpu was running.

        Time int/long: if T in the first token is 0x7ffff or 0x7fffe, then an int or long follows
        with the time delta.

        Battery level int: if A in the first token is set,
        31              23              15               7             0
        █L|L|L|L|L|L|L|T█T|T|T|T|T|T|T|T█T|V|V|V|V|V|V|V█V|V|V|V|V|V|V|D█

        D: indicates that extra history details follow.
        V: the battery voltage.
        T: the battery temperature.
        L: the battery level (out of 100).

        State change int: if B in the first token is set,
        31              23              15               7             0
        █S|S|S|H|H|H|P|P█F|E|D|C|B| | |A█ | | | | | | | █ | | | | | | | █

        A: wifi multicast was on.
        B: battery was plugged in.
        C: screen was on.
        D: phone was scanning for signal.
        E: audio was on.
        F: a sensor was active.

        State2 change int: if C in the first token is set,
        31              23              15               7             0
        █M|L|K|J|I|H|H|G█F|E|D|C| | | | █ | | | | | | | █ |B|B|B|A|A|A|A█

        A: 4 bits indicating the wifi supplicant state: {@link BatteryStats#WIFI_SUPPL_STATE_NAMES}.
        B: 3 bits indicating the wifi signal strength: 0, 1, 2, 3, 4.
        C: a bluetooth scan was active.
        D: the camera was active.
        E: bluetooth was on.
        F: a phone call was active.
        G: the device was charging.
        H: 2 bits indicating the device-idle (doze) state: off, light, full
        I: the flashlight was on.
        J: wifi was on.
        K: wifi was running.
        L: video was playing.
        M: power save mode was on.

        Wakelock/wakereason struct: if D in the first token is set,
        TODO(adamlesinski): describe wakelock/wakereason struct.

        Event struct: if E in the first token is set,
        TODO(adamlesinski): describe the event struct.

        History step details struct: if D in the battery level int is set,
        TODO(adamlesinski): describe the history step details struct.

        Battery charge int: if F in the first token is set, an int representing the battery charge
        in coulombs follows.
     */

    // Part of initial delta int that specifies the time delta.
    static final int DELTA_TIME_MASK = 0x7ffff;
    static final int DELTA_TIME_LONG = 0x7ffff;   // The delta is a following long
    static final int DELTA_TIME_INT = 0x7fffe;    // The delta is a following int
    static final int DELTA_TIME_ABS = 0x7fffd;    // Following is an entire abs update.
    // Flag in delta int: a new battery level int follows.
    static final int DELTA_BATTERY_LEVEL_FLAG               = 0x00080000;
    // Flag in delta int: a new full state and battery status int follows.
    static final int DELTA_STATE_FLAG                       = 0x00100000;
    // Flag in delta int: a new full state2 int follows.
    static final int DELTA_STATE2_FLAG                      = 0x00200000;
    // Flag in delta int: contains a wakelock or wakeReason tag.
    static final int DELTA_WAKELOCK_FLAG                    = 0x00400000;
    // Flag in delta int: contains an event description.
    static final int DELTA_EVENT_FLAG                       = 0x00800000;
    // Flag in delta int: contains the battery charge count in uAh.
    static final int DELTA_BATTERY_CHARGE_FLAG              = 0x01000000;
    // These upper bits are the frequently changing state bits.
    static final int DELTA_STATE_MASK                       = 0xfe000000;

    // Flag in history tag index: indicates that this is the first occurrence of this tag,
    // therefore the tag value is written in the parcel
    static final int TAG_FIRST_OCCURRENCE_FLAG = 0x8000;

    // These are the pieces of battery state that are packed in to the upper bits of
    // the state int that have been packed in to the first delta int.  They must fit
    // in STATE_BATTERY_MASK.
    static final int STATE_BATTERY_MASK         = 0xff000000;
    static final int STATE_BATTERY_STATUS_MASK  = 0x00000007;
    static final int STATE_BATTERY_STATUS_SHIFT = 29;
    static final int STATE_BATTERY_HEALTH_MASK  = 0x00000007;
    static final int STATE_BATTERY_HEALTH_SHIFT = 26;
    static final int STATE_BATTERY_PLUG_MASK    = 0x00000003;
    static final int STATE_BATTERY_PLUG_SHIFT   = 24;

    // We use the low bit of the battery state int to indicate that we have full details
    // from a battery level change.
    static final int BATTERY_DELTA_LEVEL_FLAG   = 0x00000001;

    @GuardedBy("this")
    public void writeHistoryDelta(Parcel dest, HistoryItem cur, HistoryItem last) {
        if (last == null || cur.cmd != HistoryItem.CMD_UPDATE) {
            dest.writeInt(DELTA_TIME_ABS);
            cur.writeToParcel(dest, 0);
            return;
        }

        final long deltaTime = cur.time - last.time;
        final int lastBatteryLevelInt = buildBatteryLevelInt(last);
        final int lastStateInt = buildStateInt(last);

        int deltaTimeToken;
        if (deltaTime < 0 || deltaTime > Integer.MAX_VALUE) {
            deltaTimeToken = DELTA_TIME_LONG;
        } else if (deltaTime >= DELTA_TIME_ABS) {
            deltaTimeToken = DELTA_TIME_INT;
        } else {
            deltaTimeToken = (int)deltaTime;
        }
        int firstToken = deltaTimeToken | (cur.states&DELTA_STATE_MASK);
        final int includeStepDetails = mLastHistoryStepLevel > cur.batteryLevel
                ? BATTERY_DELTA_LEVEL_FLAG : 0;
        final boolean computeStepDetails = includeStepDetails != 0
                || mLastHistoryStepDetails == null;
        final int batteryLevelInt = buildBatteryLevelInt(cur) | includeStepDetails;
        final boolean batteryLevelIntChanged = batteryLevelInt != lastBatteryLevelInt;
        if (batteryLevelIntChanged) {
            firstToken |= DELTA_BATTERY_LEVEL_FLAG;
        }
        final int stateInt = buildStateInt(cur);
        final boolean stateIntChanged = stateInt != lastStateInt;
        if (stateIntChanged) {
            firstToken |= DELTA_STATE_FLAG;
        }
        final boolean state2IntChanged = cur.states2 != last.states2;
        if (state2IntChanged) {
            firstToken |= DELTA_STATE2_FLAG;
        }
        if (cur.wakelockTag != null || cur.wakeReasonTag != null) {
            firstToken |= DELTA_WAKELOCK_FLAG;
        }
        if (cur.eventCode != HistoryItem.EVENT_NONE) {
            firstToken |= DELTA_EVENT_FLAG;
        }

        final boolean batteryChargeChanged = cur.batteryChargeUah != last.batteryChargeUah;
        if (batteryChargeChanged) {
            firstToken |= DELTA_BATTERY_CHARGE_FLAG;
        }
        dest.writeInt(firstToken);
        if (DEBUG) Slog.i(TAG, "WRITE DELTA: firstToken=0x" + Integer.toHexString(firstToken)
                + " deltaTime=" + deltaTime);

        if (deltaTimeToken >= DELTA_TIME_INT) {
            if (deltaTimeToken == DELTA_TIME_INT) {
                if (DEBUG) Slog.i(TAG, "WRITE DELTA: int deltaTime=" + (int)deltaTime);
                dest.writeInt((int)deltaTime);
            } else {
                if (DEBUG) Slog.i(TAG, "WRITE DELTA: long deltaTime=" + deltaTime);
                dest.writeLong(deltaTime);
            }
        }
        if (batteryLevelIntChanged) {
            dest.writeInt(batteryLevelInt);
            if (DEBUG) Slog.i(TAG, "WRITE DELTA: batteryToken=0x"
                    + Integer.toHexString(batteryLevelInt)
                    + " batteryLevel=" + cur.batteryLevel
                    + " batteryTemp=" + cur.batteryTemperature
                    + " batteryVolt=" + (int)cur.batteryVoltage);
        }
        if (stateIntChanged) {
            dest.writeInt(stateInt);
            if (DEBUG) Slog.i(TAG, "WRITE DELTA: stateToken=0x"
                    + Integer.toHexString(stateInt)
                    + " batteryStatus=" + cur.batteryStatus
                    + " batteryHealth=" + cur.batteryHealth
                    + " batteryPlugType=" + cur.batteryPlugType
                    + " states=0x" + Integer.toHexString(cur.states));
        }
        if (state2IntChanged) {
            dest.writeInt(cur.states2);
            if (DEBUG) Slog.i(TAG, "WRITE DELTA: states2=0x"
                    + Integer.toHexString(cur.states2));
        }
        if (cur.wakelockTag != null || cur.wakeReasonTag != null) {
            int wakeLockIndex;
            int wakeReasonIndex;
            if (cur.wakelockTag != null) {
                wakeLockIndex = writeHistoryTag(cur.wakelockTag);
                if (DEBUG) Slog.i(TAG, "WRITE DELTA: wakelockTag=#" + cur.wakelockTag.poolIdx
                    + " " + cur.wakelockTag.uid + ":" + cur.wakelockTag.string);
            } else {
                wakeLockIndex = 0xffff;
            }
            if (cur.wakeReasonTag != null) {
                wakeReasonIndex = writeHistoryTag(cur.wakeReasonTag);
                if (DEBUG) Slog.i(TAG, "WRITE DELTA: wakeReasonTag=#" + cur.wakeReasonTag.poolIdx
                    + " " + cur.wakeReasonTag.uid + ":" + cur.wakeReasonTag.string);
            } else {
                wakeReasonIndex = 0xffff;
            }
            dest.writeInt((wakeReasonIndex<<16) | wakeLockIndex);
            if (cur.wakelockTag != null && (wakeLockIndex & TAG_FIRST_OCCURRENCE_FLAG) != 0) {
                cur.wakelockTag.writeToParcel(dest, 0);
                cur.tagsFirstOccurrence = true;
            }
            if (cur.wakeReasonTag != null && (wakeReasonIndex & TAG_FIRST_OCCURRENCE_FLAG) != 0) {
                cur.wakeReasonTag.writeToParcel(dest, 0);
                cur.tagsFirstOccurrence = true;
            }
        }
        if (cur.eventCode != HistoryItem.EVENT_NONE) {
            final int index = writeHistoryTag(cur.eventTag);
            final int codeAndIndex = (cur.eventCode & 0xffff) | (index << 16);
            dest.writeInt(codeAndIndex);
            if ((index & TAG_FIRST_OCCURRENCE_FLAG) != 0) {
                cur.eventTag.writeToParcel(dest, 0);
                cur.tagsFirstOccurrence = true;
            }
            if (DEBUG) Slog.i(TAG, "WRITE DELTA: event=" + cur.eventCode + " tag=#"
                    + cur.eventTag.poolIdx + " " + cur.eventTag.uid + ":"
                    + cur.eventTag.string);
        }
        if (computeStepDetails) {
            if (mPlatformIdleStateCallback != null) {
                mCurHistoryStepDetails.statSubsystemPowerState =
                        mPlatformIdleStateCallback.getSubsystemLowPowerStats();
                if (DEBUG) Slog.i(TAG, "WRITE SubsystemPowerState:" +
                        mCurHistoryStepDetails.statSubsystemPowerState);

            }
            computeHistoryStepDetails(mCurHistoryStepDetails, mLastHistoryStepDetails);
            if (includeStepDetails != 0) {
                mCurHistoryStepDetails.writeToParcel(dest);
            }
            cur.stepDetails = mCurHistoryStepDetails;
            mLastHistoryStepDetails = mCurHistoryStepDetails;
        } else {
            cur.stepDetails = null;
        }
        if (mLastHistoryStepLevel < cur.batteryLevel) {
            mLastHistoryStepDetails = null;
        }
        mLastHistoryStepLevel = cur.batteryLevel;

        if (batteryChargeChanged) {
            if (DEBUG) Slog.i(TAG, "WRITE DELTA: batteryChargeUah=" + cur.batteryChargeUah);
            dest.writeInt(cur.batteryChargeUah);
        }
        dest.writeDouble(cur.modemRailChargeMah);
        dest.writeDouble(cur.wifiRailChargeMah);
    }

    private int buildBatteryLevelInt(HistoryItem h) {
        return ((((int)h.batteryLevel)<<25)&0xfe000000)
                | ((((int)h.batteryTemperature)<<15)&0x01ff8000)
                | ((((int)h.batteryVoltage)<<1)&0x00007ffe);
    }

    private void readBatteryLevelInt(int batteryLevelInt, HistoryItem out) {
        out.batteryLevel = (byte)((batteryLevelInt & 0xfe000000) >>> 25);
        out.batteryTemperature = (short)((batteryLevelInt & 0x01ff8000) >>> 15);
        out.batteryVoltage = (char)((batteryLevelInt & 0x00007ffe) >>> 1);
    }

    private int buildStateInt(HistoryItem h) {
        int plugType = 0;
        if ((h.batteryPlugType&BatteryManager.BATTERY_PLUGGED_AC) != 0) {
            plugType = 1;
        } else if ((h.batteryPlugType&BatteryManager.BATTERY_PLUGGED_USB) != 0) {
            plugType = 2;
        } else if ((h.batteryPlugType&BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) {
            plugType = 3;
        }
        return ((h.batteryStatus&STATE_BATTERY_STATUS_MASK)<<STATE_BATTERY_STATUS_SHIFT)
                | ((h.batteryHealth&STATE_BATTERY_HEALTH_MASK)<<STATE_BATTERY_HEALTH_SHIFT)
                | ((plugType&STATE_BATTERY_PLUG_MASK)<<STATE_BATTERY_PLUG_SHIFT)
                | (h.states&(~STATE_BATTERY_MASK));
    }

    private void computeHistoryStepDetails(final HistoryStepDetails out,
            final HistoryStepDetails last) {
        final HistoryStepDetails tmp = last != null ? mTmpHistoryStepDetails : out;

        // Perform a CPU update right after we do this collection, so we have started
        // collecting good data for the next step.
        requestImmediateCpuUpdate();

        if (last == null) {
            // We are not generating a delta, so all we need to do is reset the stats
            // we will later be doing a delta from.
            final int NU = mUidStats.size();
            for (int i=0; i<NU; i++) {
                final BatteryStatsImpl.Uid uid = mUidStats.valueAt(i);
                uid.mLastStepUserTimeMs = uid.mCurStepUserTimeMs;
                uid.mLastStepSystemTimeMs = uid.mCurStepSystemTimeMs;
            }
            mLastStepCpuUserTimeMs = mCurStepCpuUserTimeMs;
            mLastStepCpuSystemTimeMs = mCurStepCpuSystemTimeMs;
            mLastStepStatUserTimeMs = mCurStepStatUserTimeMs;
            mLastStepStatSystemTimeMs = mCurStepStatSystemTimeMs;
            mLastStepStatIOWaitTimeMs = mCurStepStatIOWaitTimeMs;
            mLastStepStatIrqTimeMs = mCurStepStatIrqTimeMs;
            mLastStepStatSoftIrqTimeMs = mCurStepStatSoftIrqTimeMs;
            mLastStepStatIdleTimeMs = mCurStepStatIdleTimeMs;
            tmp.clear();
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "Step stats last: user=" + mLastStepCpuUserTimeMs + " sys="
                    + mLastStepStatSystemTimeMs + " io=" + mLastStepStatIOWaitTimeMs
                    + " irq=" + mLastStepStatIrqTimeMs + " sirq="
                    + mLastStepStatSoftIrqTimeMs + " idle=" + mLastStepStatIdleTimeMs);
            Slog.d(TAG, "Step stats cur: user=" + mCurStepCpuUserTimeMs + " sys="
                    + mCurStepStatSystemTimeMs + " io=" + mCurStepStatIOWaitTimeMs
                    + " irq=" + mCurStepStatIrqTimeMs + " sirq="
                    + mCurStepStatSoftIrqTimeMs + " idle=" + mCurStepStatIdleTimeMs);
        }
        out.userTime = (int) (mCurStepCpuUserTimeMs - mLastStepCpuUserTimeMs);
        out.systemTime = (int) (mCurStepCpuSystemTimeMs - mLastStepCpuSystemTimeMs);
        out.statUserTime = (int) (mCurStepStatUserTimeMs - mLastStepStatUserTimeMs);
        out.statSystemTime = (int) (mCurStepStatSystemTimeMs - mLastStepStatSystemTimeMs);
        out.statIOWaitTime = (int) (mCurStepStatIOWaitTimeMs - mLastStepStatIOWaitTimeMs);
        out.statIrqTime = (int) (mCurStepStatIrqTimeMs - mLastStepStatIrqTimeMs);
        out.statSoftIrqTime = (int) (mCurStepStatSoftIrqTimeMs - mLastStepStatSoftIrqTimeMs);
        out.statIdlTime = (int) (mCurStepStatIdleTimeMs - mLastStepStatIdleTimeMs);
        out.appCpuUid1 = out.appCpuUid2 = out.appCpuUid3 = -1;
        out.appCpuUTime1 = out.appCpuUTime2 = out.appCpuUTime3 = 0;
        out.appCpuSTime1 = out.appCpuSTime2 = out.appCpuSTime3 = 0;
        final int NU = mUidStats.size();
        for (int i=0; i<NU; i++) {
            final BatteryStatsImpl.Uid uid = mUidStats.valueAt(i);
            final int totalUTimeMs = (int) (uid.mCurStepUserTimeMs - uid.mLastStepUserTimeMs);
            final int totalSTimeMs = (int) (uid.mCurStepSystemTimeMs - uid.mLastStepSystemTimeMs);
            final int totalTimeMs = totalUTimeMs + totalSTimeMs;
            uid.mLastStepUserTimeMs = uid.mCurStepUserTimeMs;
            uid.mLastStepSystemTimeMs = uid.mCurStepSystemTimeMs;
            if (totalTimeMs <= (out.appCpuUTime3 + out.appCpuSTime3)) {
                continue;
            }
            if (totalTimeMs <= (out.appCpuUTime2 + out.appCpuSTime2)) {
                out.appCpuUid3 = uid.mUid;
                out.appCpuUTime3 = totalUTimeMs;
                out.appCpuSTime3 = totalSTimeMs;
            } else {
                out.appCpuUid3 = out.appCpuUid2;
                out.appCpuUTime3 = out.appCpuUTime2;
                out.appCpuSTime3 = out.appCpuSTime2;
                if (totalTimeMs <= (out.appCpuUTime1 + out.appCpuSTime1)) {
                    out.appCpuUid2 = uid.mUid;
                    out.appCpuUTime2 = totalUTimeMs;
                    out.appCpuSTime2 = totalSTimeMs;
                } else {
                    out.appCpuUid2 = out.appCpuUid1;
                    out.appCpuUTime2 = out.appCpuUTime1;
                    out.appCpuSTime2 = out.appCpuSTime1;
                    out.appCpuUid1 = uid.mUid;
                    out.appCpuUTime1 = totalUTimeMs;
                    out.appCpuSTime1 = totalSTimeMs;
                }
            }
        }
        mLastStepCpuUserTimeMs = mCurStepCpuUserTimeMs;
        mLastStepCpuSystemTimeMs = mCurStepCpuSystemTimeMs;
        mLastStepStatUserTimeMs = mCurStepStatUserTimeMs;
        mLastStepStatSystemTimeMs = mCurStepStatSystemTimeMs;
        mLastStepStatIOWaitTimeMs = mCurStepStatIOWaitTimeMs;
        mLastStepStatIrqTimeMs = mCurStepStatIrqTimeMs;
        mLastStepStatSoftIrqTimeMs = mCurStepStatSoftIrqTimeMs;
        mLastStepStatIdleTimeMs = mCurStepStatIdleTimeMs;
    }

    @GuardedBy("this")
    @Override
    public void commitCurrentHistoryBatchLocked() {
        mHistoryLastWritten.cmd = HistoryItem.CMD_NULL;
    }

    @GuardedBy("this")
    public void createFakeHistoryEvents(long numEvents) {
        final long elapsedRealtimeMs = mClock.elapsedRealtime();
        final long uptimeMs = mClock.uptimeMillis();
        for(long i = 0; i < numEvents; i++) {
            noteLongPartialWakelockStart("name1", "historyName1", 1000,
                    elapsedRealtimeMs, uptimeMs);
            noteLongPartialWakelockFinish("name1", "historyName1", 1000,
                    elapsedRealtimeMs, uptimeMs);
        }
    }

    @GuardedBy("this")
    void addHistoryBufferLocked(long elapsedRealtimeMs, long uptimeMs, HistoryItem cur) {
        if (!mHaveBatteryLevel || !mRecordingHistory) {
            return;
        }

        final long timeDiffMs = (mHistoryBaseTimeMs + elapsedRealtimeMs) - mHistoryLastWritten.time;
        final int diffStates = mHistoryLastWritten.states^(cur.states&mActiveHistoryStates);
        final int diffStates2 = mHistoryLastWritten.states2^(cur.states2&mActiveHistoryStates2);
        final int lastDiffStates = mHistoryLastWritten.states^mHistoryLastLastWritten.states;
        final int lastDiffStates2 = mHistoryLastWritten.states2^mHistoryLastLastWritten.states2;
        if (DEBUG) {
            Slog.i(TAG, "ADD: tdelta=" + timeDiffMs + " diff="
                    + Integer.toHexString(diffStates) + " lastDiff="
                    + Integer.toHexString(lastDiffStates) + " diff2="
                    + Integer.toHexString(diffStates2) + " lastDiff2="
                    + Integer.toHexString(lastDiffStates2));
        }

        mBatteryStatsHistory.recordTraceEvents(cur.eventCode, cur.eventTag);
        mBatteryStatsHistory.recordTraceCounters(mHistoryLastWritten.states,
                cur.states & mActiveHistoryStates, BatteryStats.HISTORY_STATE_DESCRIPTIONS);
        mBatteryStatsHistory.recordTraceCounters(mHistoryLastWritten.states2,
                cur.states2 & mActiveHistoryStates2, BatteryStats.HISTORY_STATE2_DESCRIPTIONS);
        if (mHistoryBufferLastPos >= 0 && mHistoryLastWritten.cmd == HistoryItem.CMD_UPDATE
                && timeDiffMs < 1000 && (diffStates & lastDiffStates) == 0
                && (diffStates2&lastDiffStates2) == 0
                && (!mHistoryLastWritten.tagsFirstOccurrence && !cur.tagsFirstOccurrence)
                && (mHistoryLastWritten.wakelockTag == null || cur.wakelockTag == null)
                && (mHistoryLastWritten.wakeReasonTag == null || cur.wakeReasonTag == null)
                && mHistoryLastWritten.stepDetails == null
                && (mHistoryLastWritten.eventCode == HistoryItem.EVENT_NONE
                        || cur.eventCode == HistoryItem.EVENT_NONE)
                && mHistoryLastWritten.batteryLevel == cur.batteryLevel
                && mHistoryLastWritten.batteryStatus == cur.batteryStatus
                && mHistoryLastWritten.batteryHealth == cur.batteryHealth
                && mHistoryLastWritten.batteryPlugType == cur.batteryPlugType
                && mHistoryLastWritten.batteryTemperature == cur.batteryTemperature
                && mHistoryLastWritten.batteryVoltage == cur.batteryVoltage) {
            // We can merge this new change in with the last one.  Merging is
            // allowed as long as only the states have changed, and within those states
            // as long as no bit has changed both between now and the last entry, as
            // well as the last entry and the one before it (so we capture any toggles).
            if (DEBUG) Slog.i(TAG, "ADD: rewinding back to " + mHistoryBufferLastPos);
            mHistoryBuffer.setDataSize(mHistoryBufferLastPos);
            mHistoryBuffer.setDataPosition(mHistoryBufferLastPos);
            mHistoryBufferLastPos = -1;
            elapsedRealtimeMs = mHistoryLastWritten.time - mHistoryBaseTimeMs;
            // If the last written history had a wakelock tag, we need to retain it.
            // Note that the condition above made sure that we aren't in a case where
            // both it and the current history item have a wakelock tag.
            if (mHistoryLastWritten.wakelockTag != null) {
                cur.wakelockTag = cur.localWakelockTag;
                cur.wakelockTag.setTo(mHistoryLastWritten.wakelockTag);
            }
            // If the last written history had a wake reason tag, we need to retain it.
            // Note that the condition above made sure that we aren't in a case where
            // both it and the current history item have a wakelock tag.
            if (mHistoryLastWritten.wakeReasonTag != null) {
                cur.wakeReasonTag = cur.localWakeReasonTag;
                cur.wakeReasonTag.setTo(mHistoryLastWritten.wakeReasonTag);
            }
            // If the last written history had an event, we need to retain it.
            // Note that the condition above made sure that we aren't in a case where
            // both it and the current history item have an event.
            if (mHistoryLastWritten.eventCode != HistoryItem.EVENT_NONE) {
                cur.eventCode = mHistoryLastWritten.eventCode;
                cur.eventTag = cur.localEventTag;
                cur.eventTag.setTo(mHistoryLastWritten.eventTag);
            }
            mHistoryLastWritten.setTo(mHistoryLastLastWritten);
        }
        final int dataSize = mHistoryBuffer.dataSize();

        if (dataSize >= mConstants.MAX_HISTORY_BUFFER) {
            //open a new history file.
            final long start = SystemClock.uptimeMillis();
            writeHistoryLocked(true);
            if (DEBUG) {
                Slog.d(TAG, "addHistoryBufferLocked writeHistoryLocked takes ms:"
                        + (SystemClock.uptimeMillis() - start));
            }
            mBatteryStatsHistory.startNextFile();
            mHistoryBuffer.setDataSize(0);
            mHistoryBuffer.setDataPosition(0);
            mHistoryBuffer.setDataCapacity(mConstants.MAX_HISTORY_BUFFER / 2);
            mHistoryBufferLastPos = -1;
            mHistoryLastWritten.clear();
            mHistoryLastLastWritten.clear();

            // Mark every entry in the pool with a flag indicating that the tag
            // has not yet been encountered while writing the current history buffer.
            for (Map.Entry<HistoryTag, Integer> entry: mHistoryTagPool.entrySet()) {
                entry.setValue(entry.getValue() | TAG_FIRST_OCCURRENCE_FLAG);
            }
            // Make a copy of mHistoryCur.
            HistoryItem copy = new HistoryItem();
            copy.setTo(cur);
            // startRecordingHistory will reset mHistoryCur.
            startRecordingHistory(elapsedRealtimeMs, uptimeMs, false);
            // Add the copy into history buffer.
            addHistoryBufferLocked(elapsedRealtimeMs, HistoryItem.CMD_UPDATE, copy);
            return;
        }

        if (dataSize == 0) {
            // The history is currently empty; we need it to start with a time stamp.
            cur.currentTime = mClock.currentTimeMillis();
            addHistoryBufferLocked(elapsedRealtimeMs, HistoryItem.CMD_RESET, cur);
        }
        addHistoryBufferLocked(elapsedRealtimeMs, HistoryItem.CMD_UPDATE, cur);
    }

    @GuardedBy("this")
    private void addHistoryBufferLocked(long elapsedRealtimeMs, byte cmd, HistoryItem cur) {
        if (mBatteryStatsHistoryIterator != null) {
            throw new IllegalStateException("Can't do this while iterating history!");
        }
        mHistoryBufferLastPos = mHistoryBuffer.dataPosition();
        mHistoryLastLastWritten.setTo(mHistoryLastWritten);
        final boolean hasTags = mHistoryLastWritten.tagsFirstOccurrence || cur.tagsFirstOccurrence;
        mHistoryLastWritten.setTo(mHistoryBaseTimeMs + elapsedRealtimeMs, cmd, cur);
        mHistoryLastWritten.tagsFirstOccurrence = hasTags;
        mHistoryLastWritten.states &= mActiveHistoryStates;
        mHistoryLastWritten.states2 &= mActiveHistoryStates2;
        writeHistoryDelta(mHistoryBuffer, mHistoryLastWritten, mHistoryLastLastWritten);
        mLastHistoryElapsedRealtimeMs = elapsedRealtimeMs;
        cur.wakelockTag = null;
        cur.wakeReasonTag = null;
        cur.eventCode = HistoryItem.EVENT_NONE;
        cur.eventTag = null;
        cur.tagsFirstOccurrence = false;
        if (DEBUG_HISTORY) Slog.i(TAG, "Writing history buffer: was " + mHistoryBufferLastPos
                + " now " + mHistoryBuffer.dataPosition()
                + " size is now " + mHistoryBuffer.dataSize());
    }

    int mChangedStates = 0;
    int mChangedStates2 = 0;

    @GuardedBy("this")
    void addHistoryRecordLocked(long elapsedRealtimeMs, long uptimeMs) {
        if (mTrackRunningHistoryElapsedRealtimeMs != 0) {
            final long diffElapsedMs = elapsedRealtimeMs - mTrackRunningHistoryElapsedRealtimeMs;
            final long diffUptimeMs = uptimeMs - mTrackRunningHistoryUptimeMs;
            if (diffUptimeMs < (diffElapsedMs - 20)) {
                final long wakeElapsedTimeMs = elapsedRealtimeMs - (diffElapsedMs - diffUptimeMs);
                mHistoryAddTmp.setTo(mHistoryLastWritten);
                mHistoryAddTmp.wakelockTag = null;
                mHistoryAddTmp.wakeReasonTag = null;
                mHistoryAddTmp.eventCode = HistoryItem.EVENT_NONE;
                mHistoryAddTmp.states &= ~HistoryItem.STATE_CPU_RUNNING_FLAG;
                addHistoryRecordInnerLocked(wakeElapsedTimeMs, uptimeMs, mHistoryAddTmp);
            }
        }
        mHistoryCur.states |= HistoryItem.STATE_CPU_RUNNING_FLAG;
        mTrackRunningHistoryElapsedRealtimeMs = elapsedRealtimeMs;
        mTrackRunningHistoryUptimeMs = uptimeMs;
        addHistoryRecordInnerLocked(elapsedRealtimeMs, uptimeMs, mHistoryCur);
    }

    @GuardedBy("this")
    void addHistoryRecordInnerLocked(long elapsedRealtimeMs, long uptimeMs, HistoryItem cur) {
        addHistoryBufferLocked(elapsedRealtimeMs, uptimeMs, cur);
    }

    @GuardedBy("this")
    public void addHistoryEventLocked(long elapsedRealtimeMs, long uptimeMs, int code,
            String name, int uid) {
        mHistoryCur.eventCode = code;
        mHistoryCur.eventTag = mHistoryCur.localEventTag;
        mHistoryCur.eventTag.string = name;
        mHistoryCur.eventTag.uid = uid;
        addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
    }

    @GuardedBy("this")
    void addHistoryRecordLocked(long elapsedRealtimeMs, long uptimeMs, byte cmd, HistoryItem cur) {
        HistoryItem rec = mHistoryCache;
        if (rec != null) {
            mHistoryCache = rec.next;
        } else {
            rec = new HistoryItem();
        }
        rec.setTo(mHistoryBaseTimeMs + elapsedRealtimeMs, cmd, cur);

        addHistoryRecordLocked(rec);
    }

    @GuardedBy("this")
    void addHistoryRecordLocked(HistoryItem rec) {
        mNumHistoryItems++;
        rec.next = null;
        mHistoryLastEnd = mHistoryEnd;
        if (mHistoryEnd != null) {
            mHistoryEnd.next = rec;
            mHistoryEnd = rec;
        } else {
            mHistory = mHistoryEnd = rec;
        }
    }

    @GuardedBy("this")
    void clearHistoryLocked() {
        if (DEBUG_HISTORY) Slog.i(TAG, "********** CLEARING HISTORY!");
        mHistoryBaseTimeMs = 0;
        mLastHistoryElapsedRealtimeMs = 0;
        mTrackRunningHistoryElapsedRealtimeMs = 0;
        mTrackRunningHistoryUptimeMs = 0;

        mHistoryBuffer.setDataSize(0);
        mHistoryBuffer.setDataPosition(0);
        mHistoryBuffer.setDataCapacity(mConstants.MAX_HISTORY_BUFFER / 2);
        mHistoryLastLastWritten.clear();
        mHistoryLastWritten.clear();
        mHistoryTagPool.clear();
        mNextHistoryTagIdx = 0;
        mNumHistoryTagChars = 0;
        mHistoryBufferLastPos = -1;
        mActiveHistoryStates = 0xffffffff;
        mActiveHistoryStates2 = 0xffffffff;
    }

    @GuardedBy("this")
    public void updateTimeBasesLocked(boolean unplugged, int screenState, long uptimeUs,
            long realtimeUs) {
        final boolean screenOff = !Display.isOnState(screenState);
        final boolean updateOnBatteryTimeBase = unplugged != mOnBatteryTimeBase.isRunning();
        final boolean updateOnBatteryScreenOffTimeBase =
                (unplugged && screenOff) != mOnBatteryScreenOffTimeBase.isRunning();

        if (updateOnBatteryScreenOffTimeBase || updateOnBatteryTimeBase) {
            if (updateOnBatteryScreenOffTimeBase) {
                updateKernelWakelocksLocked(realtimeUs);
                updateBatteryPropertiesLocked();
            }
            // This if{} is only necessary due to SCREEN_OFF_RPM_STATS_ENABLED, which exists because
            // updateRpmStatsLocked is too slow to run each screen change. When the speed is
            // improved, remove the surrounding if{}.
            if (SCREEN_OFF_RPM_STATS_ENABLED || updateOnBatteryTimeBase) {
                // if either OnBattery or OnBatteryScreenOfftimebase changes.
                updateRpmStatsLocked(realtimeUs);
            }
            if (DEBUG_ENERGY_CPU) {
                Slog.d(TAG, "Updating cpu time because screen is now "
                        + Display.stateToString(screenState)
                        + " and battery is " + (unplugged ? "on" : "off"));
            }

            mOnBatteryTimeBase.setRunning(unplugged, uptimeUs, realtimeUs);
            if (updateOnBatteryTimeBase) {
                for (int i = mUidStats.size() - 1; i >= 0; --i) {
                    mUidStats.valueAt(i).updateOnBatteryBgTimeBase(uptimeUs, realtimeUs);
                }
            }
            if (updateOnBatteryScreenOffTimeBase) {
                mOnBatteryScreenOffTimeBase.setRunning(unplugged && screenOff,
                        uptimeUs, realtimeUs);
                for (int i = mUidStats.size() - 1; i >= 0; --i) {
                    mUidStats.valueAt(i).updateOnBatteryScreenOffBgTimeBase(uptimeUs, realtimeUs);
                }
            }
        }
    }

    @GuardedBy("this")
    private void updateBatteryPropertiesLocked() {
        try {
            IBatteryPropertiesRegistrar registrar = IBatteryPropertiesRegistrar.Stub.asInterface(
                    ServiceManager.getService("batteryproperties"));
            if (registrar != null) {
                registrar.scheduleUpdate();
            }
        } catch (RemoteException e) {
            // Ignore.
        }
    }

    @GuardedBy("this")
    public void addIsolatedUidLocked(int isolatedUid, int appUid) {
        addIsolatedUidLocked(isolatedUid, appUid,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    @SuppressWarnings("GuardedBy")   // errorprone false positive on u.addIsolatedUid
    public void addIsolatedUidLocked(int isolatedUid, int appUid,
            long elapsedRealtimeMs, long uptimeMs) {
        mIsolatedUids.put(isolatedUid, appUid);
        mIsolatedUidRefCounts.put(isolatedUid, 1);
        final Uid u = getUidStatsLocked(appUid, elapsedRealtimeMs, uptimeMs);
        u.addIsolatedUid(isolatedUid);
    }

    /**
     * Schedules a read of the latest cpu times before removing the isolated UID.
     * @see #removeIsolatedUidLocked(int, int, int)
     */
    public void scheduleRemoveIsolatedUidLocked(int isolatedUid, int appUid) {
        int curUid = mIsolatedUids.get(isolatedUid, -1);
        if (curUid == appUid) {
            if (mExternalSync != null) {
                mExternalSync.scheduleCpuSyncDueToRemovedUid(isolatedUid);
            }
        }
    }

    /**
     * Isolated uid should only be removed after all wakelocks associated with the uid are stopped
     * and the cpu time-in-state has been read one last time for the uid.
     *
     * @see #scheduleRemoveIsolatedUidLocked(int, int)
     *
     * @return true if the isolated uid is actually removed.
     */
    @GuardedBy("this")
    public boolean maybeRemoveIsolatedUidLocked(int isolatedUid, long elapsedRealtimeMs,
            long uptimeMs) {
        final int refCount = mIsolatedUidRefCounts.get(isolatedUid, 0) - 1;
        if (refCount > 0) {
            // Isolated uid is still being tracked
            mIsolatedUidRefCounts.put(isolatedUid, refCount);
            return false;
        }

        final int idx = mIsolatedUids.indexOfKey(isolatedUid);
        if (idx >= 0) {
            final int ownerUid = mIsolatedUids.valueAt(idx);
            final Uid u = getUidStatsLocked(ownerUid, elapsedRealtimeMs, uptimeMs);
            u.removeIsolatedUid(isolatedUid);
            mIsolatedUids.removeAt(idx);
            mIsolatedUidRefCounts.delete(isolatedUid);
        } else {
            Slog.w(TAG, "Attempted to remove untracked isolated uid (" + isolatedUid + ")");
        }
        mPendingRemovedUids.add(new UidToRemove(isolatedUid, elapsedRealtimeMs));

        return true;
    }

    /**
     * Increment the ref count for an isolated uid.
     * call #maybeRemoveIsolatedUidLocked to decrement.
     */
    public void incrementIsolatedUidRefCount(int uid) {
        final int refCount = mIsolatedUidRefCounts.get(uid, 0);
        if (refCount <= 0) {
            // Uid is not mapped or referenced
            Slog.w(TAG,
                    "Attempted to increment ref counted of untracked isolated uid (" + uid + ")");
            return;
        }
        mIsolatedUidRefCounts.put(uid, refCount + 1);
    }

    private int mapUid(int uid) {
        if (Process.isSdkSandboxUid(uid)) {
            return Process.getAppUidForSdkSandboxUid(uid);
        }
        return mapIsolatedUid(uid);
    }

    private int mapIsolatedUid(int uid) {
        return mIsolatedUids.get(/*key=*/uid, /*valueIfKeyNotFound=*/uid);
    }

    @GuardedBy("this")
    public void noteEventLocked(int code, String name, int uid) {
        noteEventLocked(code, name, uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteEventLocked(int code, String name, int uid,
            long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        if (!mActiveEvents.updateState(code, name, uid, 0)) {
            return;
        }
        addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, code, name, uid);
    }

    @GuardedBy("this")
    public void noteCurrentTimeChangedLocked() {
        final long currentTime = mClock.currentTimeMillis();
        final long elapsedRealtime = mClock.elapsedRealtime();
        final long uptime = mClock.uptimeMillis();
        noteCurrentTimeChangedLocked(currentTime, elapsedRealtime, uptime);
    }

    @GuardedBy("this")
    public void noteCurrentTimeChangedLocked(long currentTimeMs,
            long elapsedRealtimeMs, long uptimeMs) {
        recordCurrentTimeChangeLocked(currentTimeMs, elapsedRealtimeMs, uptimeMs);
    }

    @GuardedBy("this")
    public void noteProcessStartLocked(String name, int uid) {
        noteProcessStartLocked(name, uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteProcessStartLocked(String name, int uid,
            long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        if (isOnBattery()) {
            Uid u = getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs);
            u.getProcessStatsLocked(name).incStartsLocked();
        }
        if (!mActiveEvents.updateState(HistoryItem.EVENT_PROC_START, name, uid, 0)) {
            return;
        }
        if (!mRecordAllHistory) {
            return;
        }
        addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, HistoryItem.EVENT_PROC_START, name, uid);
    }

    @GuardedBy("this")
    public void noteProcessCrashLocked(String name, int uid) {
        noteProcessCrashLocked(name, uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteProcessCrashLocked(String name, int uid,
            long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        if (isOnBattery()) {
            Uid u = getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs);
            u.getProcessStatsLocked(name).incNumCrashesLocked();
        }
    }

    @GuardedBy("this")
    public void noteProcessAnrLocked(String name, int uid) {
        noteProcessAnrLocked(name, uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteProcessAnrLocked(String name, int uid, long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        if (isOnBattery()) {
            Uid u = getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs);
            u.getProcessStatsLocked(name).incNumAnrsLocked();
        }
    }

    @GuardedBy("this")
    public void noteUidProcessStateLocked(int uid, int state) {
        noteUidProcessStateLocked(uid, state, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    @SuppressWarnings("GuardedBy")   // errorprone false positive on u.updateUidProcessStateLocked
    public void noteUidProcessStateLocked(int uid, int state,
            long elapsedRealtimeMs, long uptimeMs) {
        int parentUid = mapUid(uid);
        if (uid != parentUid) {
            if (Process.isIsolated(uid)) {
                // Isolated UIDs process state is already rolled up into parent, so no need to track
                // Otherwise the parent's process state will get downgraded incorrectly
                return;
            }
        }
        // TODO(b/155216561): It is possible for isolated uids to be in a higher
        // state than its parent uid. We should track the highest state within the union of host
        // and isolated uids rather than only the parent uid.
        FrameworkStatsLog.write(FrameworkStatsLog.UID_PROCESS_STATE_CHANGED, uid,
                ActivityManager.processStateAmToProto(state));
        getUidStatsLocked(parentUid, elapsedRealtimeMs, uptimeMs)
                .updateUidProcessStateLocked(state, elapsedRealtimeMs, uptimeMs);
    }

    @GuardedBy("this")
    public void noteProcessFinishLocked(String name, int uid) {
        noteProcessFinishLocked(name, uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteProcessFinishLocked(String name, int uid,
            long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        if (!mActiveEvents.updateState(HistoryItem.EVENT_PROC_FINISH, name, uid, 0)) {
            return;
        }
        if (!mRecordAllHistory) {
            return;
        }
        addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, HistoryItem.EVENT_PROC_FINISH,
                name, uid);
    }

    @GuardedBy("this")
    public void noteSyncStartLocked(String name, int uid) {
        noteSyncStartLocked(name, uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteSyncStartLocked(String name, int uid, long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteStartSyncLocked(name, elapsedRealtimeMs);
        if (!mActiveEvents.updateState(HistoryItem.EVENT_SYNC_START, name, uid, 0)) {
            return;
        }
        addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, HistoryItem.EVENT_SYNC_START, name, uid);
    }

    @GuardedBy("this")
    public void noteSyncFinishLocked(String name, int uid) {
        noteSyncFinishLocked(name, uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteSyncFinishLocked(String name, int uid, long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteStopSyncLocked(name, elapsedRealtimeMs);
        if (!mActiveEvents.updateState(HistoryItem.EVENT_SYNC_FINISH, name, uid, 0)) {
            return;
        }
        addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, HistoryItem.EVENT_SYNC_FINISH,
                name, uid);
    }

    @GuardedBy("this")
    public void noteJobStartLocked(String name, int uid) {
        noteJobStartLocked(name, uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteJobStartLocked(String name, int uid, long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteStartJobLocked(name, elapsedRealtimeMs);
        if (!mActiveEvents.updateState(HistoryItem.EVENT_JOB_START, name, uid, 0)) {
            return;
        }
        addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, HistoryItem.EVENT_JOB_START, name, uid);
    }

    @GuardedBy("this")
    public void noteJobFinishLocked(String name, int uid, int stopReason) {
        noteJobFinishLocked(name, uid, stopReason,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteJobFinishLocked(String name, int uid, int stopReason,
            long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteStopJobLocked(name, elapsedRealtimeMs, stopReason);
        if (!mActiveEvents.updateState(HistoryItem.EVENT_JOB_FINISH, name, uid, 0)) {
            return;
        }
        addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, HistoryItem.EVENT_JOB_FINISH, name, uid);
    }

    @GuardedBy("this")
    public void noteJobsDeferredLocked(int uid, int numDeferred, long sinceLast) {
        noteJobsDeferredLocked(uid, numDeferred, sinceLast,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteJobsDeferredLocked(int uid, int numDeferred, long sinceLast,
            long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteJobsDeferredLocked(numDeferred, sinceLast);
    }

    @GuardedBy("this")
    public void noteAlarmStartLocked(String name, WorkSource workSource, int uid) {
        noteAlarmStartLocked(name, workSource, uid,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteAlarmStartLocked(String name, WorkSource workSource, int uid,
            long elapsedRealtimeMs, long uptimeMs) {
        noteAlarmStartOrFinishLocked(HistoryItem.EVENT_ALARM_START, name, workSource, uid,
                elapsedRealtimeMs, uptimeMs);
    }

    @GuardedBy("this")
    public void noteAlarmFinishLocked(String name, WorkSource workSource, int uid) {
        noteAlarmFinishLocked(name, workSource, uid,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteAlarmFinishLocked(String name, WorkSource workSource, int uid,
            long elapsedRealtimeMs, long uptimeMs) {
        noteAlarmStartOrFinishLocked(HistoryItem.EVENT_ALARM_FINISH, name, workSource, uid,
                elapsedRealtimeMs, uptimeMs);
    }

    @GuardedBy("this")
    private void noteAlarmStartOrFinishLocked(int historyItem, String name, WorkSource workSource,
            int uid, long elapsedRealtimeMs, long uptimeMs) {
        if (!mRecordAllHistory) {
            return;
        }

        if (workSource != null) {
            for (int i = 0; i < workSource.size(); ++i) {
                uid = mapUid(workSource.getUid(i));
                if (mActiveEvents.updateState(historyItem, name, uid, 0)) {
                    addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, historyItem, name, uid);
                }
            }

            List<WorkChain> workChains = workSource.getWorkChains();
            if (workChains != null) {
                for (int i = 0; i < workChains.size(); ++i) {
                    uid = mapUid(workChains.get(i).getAttributionUid());
                    if (mActiveEvents.updateState(historyItem, name, uid, 0)) {
                        addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, historyItem, name, uid);
                    }
                }
            }
        } else {
            uid = mapUid(uid);

            if (mActiveEvents.updateState(historyItem, name, uid, 0)) {
                addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, historyItem, name, uid);
            }
        }
    }

    @GuardedBy("this")
    public void noteWakupAlarmLocked(String packageName, int uid, WorkSource workSource,
            String tag) {
        noteWakupAlarmLocked(packageName, uid, workSource, tag,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWakupAlarmLocked(String packageName, int uid, WorkSource workSource,
            String tag, long elapsedRealtimeMs, long uptimeMs) {
        if (workSource != null) {
            for (int i = 0; i < workSource.size(); ++i) {
                uid = workSource.getUid(i);
                final String workSourceName = workSource.getPackageName(i);

                if (isOnBattery()) {
                    BatteryStatsImpl.Uid.Pkg pkg = getPackageStatsLocked(uid,
                            workSourceName != null ? workSourceName : packageName,
                            elapsedRealtimeMs, uptimeMs);
                    pkg.noteWakeupAlarmLocked(tag);
                }
            }

            List<WorkChain> workChains = workSource.getWorkChains();
            if (workChains != null) {
                for (int i = 0; i < workChains.size(); ++i) {
                    final WorkChain wc = workChains.get(i);
                    uid = wc.getAttributionUid();

                    if (isOnBattery()) {
                        BatteryStatsImpl.Uid.Pkg pkg = getPackageStatsLocked(uid, packageName,
                                elapsedRealtimeMs, uptimeMs);
                        pkg.noteWakeupAlarmLocked(tag);
                    }
                }
            }
        } else {
            if (isOnBattery()) {
                BatteryStatsImpl.Uid.Pkg pkg = getPackageStatsLocked(uid, packageName,
                        elapsedRealtimeMs, uptimeMs);
                pkg.noteWakeupAlarmLocked(tag);
            }
        }
    }

    private void requestWakelockCpuUpdate() {
        mExternalSync.scheduleCpuSyncDueToWakelockChange(DELAY_UPDATE_WAKELOCKS);
    }

    private void requestImmediateCpuUpdate() {
        mExternalSync.scheduleCpuSyncDueToWakelockChange(0 /* delayMillis */);
    }

    @GuardedBy("this")
    public void setRecordAllHistoryLocked(boolean enabled) {
        mRecordAllHistory = enabled;
        if (!enabled) {
            // Clear out any existing state.
            mActiveEvents.removeEvents(HistoryItem.EVENT_WAKE_LOCK);
            mActiveEvents.removeEvents(HistoryItem.EVENT_ALARM);
            // Record the currently running processes as stopping, now that we are no
            // longer tracking them.
            HashMap<String, SparseIntArray> active = mActiveEvents.getStateForEvent(
                    HistoryItem.EVENT_PROC);
            if (active != null) {
                long mSecRealtime = mClock.elapsedRealtime();
                final long mSecUptime = mClock.uptimeMillis();
                for (HashMap.Entry<String, SparseIntArray> ent : active.entrySet()) {
                    SparseIntArray uids = ent.getValue();
                    for (int j=0; j<uids.size(); j++) {
                        addHistoryEventLocked(mSecRealtime, mSecUptime,
                                HistoryItem.EVENT_PROC_FINISH, ent.getKey(), uids.keyAt(j));
                    }
                }
            }
        } else {
            // Record the currently running processes as starting, now that we are tracking them.
            HashMap<String, SparseIntArray> active = mActiveEvents.getStateForEvent(
                    HistoryItem.EVENT_PROC);
            if (active != null) {
                long mSecRealtime = mClock.elapsedRealtime();
                final long mSecUptime = mClock.uptimeMillis();
                for (HashMap.Entry<String, SparseIntArray> ent : active.entrySet()) {
                    SparseIntArray uids = ent.getValue();
                    for (int j=0; j<uids.size(); j++) {
                        addHistoryEventLocked(mSecRealtime, mSecUptime,
                                HistoryItem.EVENT_PROC_START, ent.getKey(), uids.keyAt(j));
                    }
                }
            }
        }
    }

    public void setNoAutoReset(boolean enabled) {
        mNoAutoReset = enabled;
    }

    @GuardedBy("this")
    public void setPretendScreenOff(boolean pretendScreenOff) {
        if (mPretendScreenOff != pretendScreenOff) {
            mPretendScreenOff = pretendScreenOff;
            final int primaryScreenState = mPerDisplayBatteryStats[0].screenState;
            noteScreenStateLocked(0, primaryScreenState,
                    mClock.elapsedRealtime(), mClock.uptimeMillis(),
                    mClock.currentTimeMillis());
        }
    }

    private String mInitialAcquireWakeName;
    private int mInitialAcquireWakeUid = -1;

    @GuardedBy("this")
    public void noteStartWakeLocked(int uid, int pid, WorkChain wc, String name, String historyName,
            int type, boolean unimportantForLogging) {
        noteStartWakeLocked(uid, pid, wc, name, historyName, type, unimportantForLogging,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteStartWakeLocked(int uid, int pid, WorkChain wc, String name, String historyName,
            int type, boolean unimportantForLogging, long elapsedRealtimeMs, long uptimeMs) {
        final int mappedUid = mapUid(uid);
        if (type == WAKE_TYPE_PARTIAL) {
            // Only care about partial wake locks, since full wake locks
            // will be canceled when the user puts the screen to sleep.
            aggregateLastWakeupUptimeLocked(elapsedRealtimeMs, uptimeMs);
            if (historyName == null) {
                historyName = name;
            }
            if (mRecordAllHistory) {
                if (mActiveEvents.updateState(HistoryItem.EVENT_WAKE_LOCK_START, historyName,
                        mappedUid, 0)) {
                    addHistoryEventLocked(elapsedRealtimeMs, uptimeMs,
                            HistoryItem.EVENT_WAKE_LOCK_START, historyName, mappedUid);
                }
            }
            if (mWakeLockNesting == 0) {
                mHistoryCur.states |= HistoryItem.STATE_WAKE_LOCK_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Start wake lock to: "
                        + Integer.toHexString(mHistoryCur.states));
                mHistoryCur.wakelockTag = mHistoryCur.localWakelockTag;
                mHistoryCur.wakelockTag.string = mInitialAcquireWakeName = historyName;
                mHistoryCur.wakelockTag.uid = mInitialAcquireWakeUid = mappedUid;
                mWakeLockImportant = !unimportantForLogging;
                addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            } else if (!mWakeLockImportant && !unimportantForLogging
                    && mHistoryLastWritten.cmd == HistoryItem.CMD_UPDATE) {
                if (mHistoryLastWritten.wakelockTag != null) {
                    // We'll try to update the last tag.
                    mHistoryLastWritten.wakelockTag = null;
                    mHistoryCur.wakelockTag = mHistoryCur.localWakelockTag;
                    mHistoryCur.wakelockTag.string = mInitialAcquireWakeName = historyName;
                    mHistoryCur.wakelockTag.uid = mInitialAcquireWakeUid = mappedUid;
                    addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
                }
                mWakeLockImportant = true;
            }
            mWakeLockNesting++;
        }
        if (mappedUid >= 0) {
            if (mappedUid != uid) {
                // Prevent the isolated uid mapping from being removed while the wakelock is
                // being held.
                incrementIsolatedUidRefCount(uid);
            }
            if (mOnBatteryScreenOffTimeBase.isRunning()) {
                // We only update the cpu time when a wake lock is acquired if the screen is off.
                // If the screen is on, we don't distribute the power amongst partial wakelocks.
                if (DEBUG_ENERGY_CPU) {
                    Slog.d(TAG, "Updating cpu time because of +wake_lock");
                }
                requestWakelockCpuUpdate();
            }

            getUidStatsLocked(mappedUid, elapsedRealtimeMs, uptimeMs)
                    .noteStartWakeLocked(pid, name, type, elapsedRealtimeMs);

            if (wc != null) {
                FrameworkStatsLog.write(FrameworkStatsLog.WAKELOCK_STATE_CHANGED, wc.getUids(),
                        wc.getTags(), getPowerManagerWakeLockLevel(type), name,
                        FrameworkStatsLog.WAKELOCK_STATE_CHANGED__STATE__ACQUIRE);
            } else {
                FrameworkStatsLog.write_non_chained(FrameworkStatsLog.WAKELOCK_STATE_CHANGED,
                        mapIsolatedUid(uid), null, getPowerManagerWakeLockLevel(type), name,
                        FrameworkStatsLog.WAKELOCK_STATE_CHANGED__STATE__ACQUIRE);
            }
        }
    }

    @GuardedBy("this")
    public void noteStopWakeLocked(int uid, int pid, WorkChain wc, String name, String historyName,
            int type) {
        noteStopWakeLocked(uid, pid, wc, name, historyName, type,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteStopWakeLocked(int uid, int pid, WorkChain wc, String name, String historyName,
            int type, long elapsedRealtimeMs, long uptimeMs) {
        final int mappedUid = mapUid(uid);
        if (type == WAKE_TYPE_PARTIAL) {
            mWakeLockNesting--;
            if (mRecordAllHistory) {
                if (historyName == null) {
                    historyName = name;
                }
                if (mActiveEvents.updateState(HistoryItem.EVENT_WAKE_LOCK_FINISH, historyName,
                        mappedUid, 0)) {
                    addHistoryEventLocked(elapsedRealtimeMs, uptimeMs,
                            HistoryItem.EVENT_WAKE_LOCK_FINISH, historyName, mappedUid);
                }
            }
            if (mWakeLockNesting == 0) {
                mHistoryCur.states &= ~HistoryItem.STATE_WAKE_LOCK_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Stop wake lock to: "
                        + Integer.toHexString(mHistoryCur.states));
                mInitialAcquireWakeName = null;
                mInitialAcquireWakeUid = -1;
                addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            }
        }
        if (mappedUid >= 0) {
            if (mOnBatteryScreenOffTimeBase.isRunning()) {
                if (DEBUG_ENERGY_CPU) {
                    Slog.d(TAG, "Updating cpu time because of -wake_lock");
                }
                requestWakelockCpuUpdate();
            }

            getUidStatsLocked(mappedUid, elapsedRealtimeMs, uptimeMs)
                    .noteStopWakeLocked(pid, name, type, elapsedRealtimeMs);
            if (wc != null) {
                FrameworkStatsLog.write(FrameworkStatsLog.WAKELOCK_STATE_CHANGED, wc.getUids(),
                        wc.getTags(), getPowerManagerWakeLockLevel(type), name,
                        FrameworkStatsLog.WAKELOCK_STATE_CHANGED__STATE__RELEASE);
            } else {
                FrameworkStatsLog.write_non_chained(FrameworkStatsLog.WAKELOCK_STATE_CHANGED,
                        mapIsolatedUid(uid), null, getPowerManagerWakeLockLevel(type), name,
                        FrameworkStatsLog.WAKELOCK_STATE_CHANGED__STATE__RELEASE);
            }

            if (mappedUid != uid) {
                // Decrement the ref count for the isolated uid and delete the mapping if uneeded.
                maybeRemoveIsolatedUidLocked(uid, elapsedRealtimeMs, uptimeMs);
            }
        }
    }

    /**
     * Converts BatteryStats wakelock types back into PowerManager wakelock levels.
     * This is the inverse map of Notifier.getBatteryStatsWakeLockMonitorType().
     * These are estimations, since batterystats loses some of the original data.
     * TODO: Delete this. Instead, FrameworkStatsLog.write should be called from
     * PowerManager's Notifier.
     */
    private int getPowerManagerWakeLockLevel(int battertStatsWakelockType) {
        switch (battertStatsWakelockType) {
            // PowerManager.PARTIAL_WAKE_LOCK or PROXIMITY_SCREEN_OFF_WAKE_LOCK
            case BatteryStats.WAKE_TYPE_PARTIAL:
                return PowerManager.PARTIAL_WAKE_LOCK;

            // PowerManager.SCREEN_DIM_WAKE_LOCK or SCREEN_BRIGHT_WAKE_LOCK
            case BatteryStats.WAKE_TYPE_FULL:
                return PowerManager.FULL_WAKE_LOCK;

            case BatteryStats.WAKE_TYPE_DRAW:
                return PowerManager.DRAW_WAKE_LOCK;

            // It appears that nothing can ever make a Window and PowerManager lacks an equivalent.
            case BatteryStats.WAKE_TYPE_WINDOW:
                Slog.e(TAG, "Illegal window wakelock type observed in batterystats.");
                return -1;

            default:
                Slog.e(TAG, "Illegal wakelock type in batterystats: " + battertStatsWakelockType);
                return -1;
        }
    }

    @GuardedBy("this")
    public void noteStartWakeFromSourceLocked(WorkSource ws, int pid, String name,
            String historyName, int type, boolean unimportantForLogging) {
        noteStartWakeFromSourceLocked(ws, pid, name, historyName, type, unimportantForLogging,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteStartWakeFromSourceLocked(WorkSource ws, int pid, String name,
            String historyName, int type, boolean unimportantForLogging,
            long elapsedRealtimeMs, long uptimeMs) {
        final int N = ws.size();
        for (int i=0; i<N; i++) {
            noteStartWakeLocked(ws.getUid(i), pid, null, name, historyName, type,
                    unimportantForLogging, elapsedRealtimeMs, uptimeMs);
        }

        List<WorkChain> wcs = ws.getWorkChains();
        if (wcs != null) {
            for (int i = 0; i < wcs.size(); ++i) {
                final WorkChain wc = wcs.get(i);
                noteStartWakeLocked(wc.getAttributionUid(), pid, wc, name, historyName, type,
                        unimportantForLogging, elapsedRealtimeMs, uptimeMs);
            }
        }
    }

    @GuardedBy("this")
    public void noteChangeWakelockFromSourceLocked(WorkSource ws, int pid, String name,
            String historyName, int type, WorkSource newWs, int newPid, String newName,
            String newHistoryName, int newType, boolean newUnimportantForLogging) {
        noteChangeWakelockFromSourceLocked(ws, pid, name, historyName, type, newWs, newPid,
                newName, newHistoryName, newType, newUnimportantForLogging,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteChangeWakelockFromSourceLocked(WorkSource ws, int pid, String name,
            String historyName, int type, WorkSource newWs, int newPid, String newName,
            String newHistoryName, int newType, boolean newUnimportantForLogging,
            long elapsedRealtimeMs, long uptimeMs) {
        List<WorkChain>[] wcs = WorkSource.diffChains(ws, newWs);

        // For correct semantics, we start the need worksources first, so that we won't
        // make inappropriate history items as if all wake locks went away and new ones
        // appeared.  This is okay because tracking of wake locks allows nesting.
        //
        // First the starts :
        final int NN = newWs.size();
        for (int i=0; i<NN; i++) {
            noteStartWakeLocked(newWs.getUid(i), newPid, null, newName, newHistoryName, newType,
                    newUnimportantForLogging, elapsedRealtimeMs, uptimeMs);
        }
        if (wcs != null) {
            List<WorkChain> newChains = wcs[0];
            if (newChains != null) {
                for (int i = 0; i < newChains.size(); ++i) {
                    final WorkChain newChain = newChains.get(i);
                    noteStartWakeLocked(newChain.getAttributionUid(), newPid, newChain, newName,
                            newHistoryName, newType, newUnimportantForLogging, elapsedRealtimeMs,
                            uptimeMs);
                }
            }
        }

        // Then the stops :
        final int NO = ws.size();
        for (int i=0; i<NO; i++) {
            noteStopWakeLocked(ws.getUid(i), pid, null, name, historyName, type, elapsedRealtimeMs,
                    uptimeMs);
        }
        if (wcs != null) {
            List<WorkChain> goneChains = wcs[1];
            if (goneChains != null) {
                for (int i = 0; i < goneChains.size(); ++i) {
                    final WorkChain goneChain = goneChains.get(i);
                    noteStopWakeLocked(goneChain.getAttributionUid(), pid, goneChain, name,
                            historyName, type, elapsedRealtimeMs, uptimeMs);
                }
            }
        }
    }

    @GuardedBy("this")
    public void noteStopWakeFromSourceLocked(WorkSource ws, int pid, String name,
            String historyName, int type) {
        noteStopWakeFromSourceLocked(ws, pid, name, historyName, type,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteStopWakeFromSourceLocked(WorkSource ws, int pid, String name,
            String historyName, int type, long elapsedRealtimeMs, long uptimeMs) {
        final int N = ws.size();
        for (int i=0; i<N; i++) {
            noteStopWakeLocked(ws.getUid(i), pid, null, name, historyName, type, elapsedRealtimeMs,
                    uptimeMs);
        }

        List<WorkChain> wcs = ws.getWorkChains();
        if (wcs != null) {
            for (int i = 0; i < wcs.size(); ++i) {
                final WorkChain wc = wcs.get(i);
                noteStopWakeLocked(wc.getAttributionUid(), pid, wc, name, historyName, type,
                        elapsedRealtimeMs, uptimeMs);
            }
        }
    }

    @GuardedBy("this")
    public void noteLongPartialWakelockStart(String name, String historyName, int uid) {
        noteLongPartialWakelockStart(name, historyName, uid,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteLongPartialWakelockStart(String name, String historyName, int uid,
            long elapsedRealtimeMs, long uptimeMs) {
        noteLongPartialWakeLockStartInternal(name, historyName, uid, elapsedRealtimeMs, uptimeMs);
    }

    @GuardedBy("this")
    public void noteLongPartialWakelockStartFromSource(String name, String historyName,
            WorkSource workSource) {
        noteLongPartialWakelockStartFromSource(name, historyName, workSource,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteLongPartialWakelockStartFromSource(String name, String historyName,
            WorkSource workSource, long elapsedRealtimeMs, long uptimeMs) {
        final int N = workSource.size();
        for (int i = 0; i < N; ++i) {
            final int uid = mapUid(workSource.getUid(i));
            noteLongPartialWakeLockStartInternal(name, historyName, uid,
                    elapsedRealtimeMs, uptimeMs);
        }

        final List<WorkChain> workChains = workSource.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain workChain = workChains.get(i);
                final int uid = workChain.getAttributionUid();
                noteLongPartialWakeLockStartInternal(name, historyName, uid,
                        elapsedRealtimeMs, uptimeMs);
            }
        }
    }

    @GuardedBy("this")
    private void noteLongPartialWakeLockStartInternal(String name, String historyName, int uid,
            long elapsedRealtimeMs, long uptimeMs) {
        final int mappedUid = mapUid(uid);
        if (historyName == null) {
            historyName = name;
        }
        if (!mActiveEvents.updateState(HistoryItem.EVENT_LONG_WAKE_LOCK_START, historyName,
                mappedUid, 0)) {
            return;
        }
        addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, HistoryItem.EVENT_LONG_WAKE_LOCK_START,
                historyName, mappedUid);
        if (mappedUid != uid) {
            // Prevent the isolated uid mapping from being removed while the wakelock is
            // being held.
            incrementIsolatedUidRefCount(uid);
        }
    }

    @GuardedBy("this")
    public void noteLongPartialWakelockFinish(String name, String historyName, int uid) {
        noteLongPartialWakelockFinish(name, historyName, uid,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteLongPartialWakelockFinish(String name, String historyName, int uid,
            long elapsedRealtimeMs, long uptimeMs) {
        noteLongPartialWakeLockFinishInternal(name, historyName, uid, elapsedRealtimeMs, uptimeMs);
    }

    @GuardedBy("this")
    public void noteLongPartialWakelockFinishFromSource(String name, String historyName,
            WorkSource workSource) {
        noteLongPartialWakelockFinishFromSource(name, historyName, workSource,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteLongPartialWakelockFinishFromSource(String name, String historyName,
            WorkSource workSource, long elapsedRealtimeMs, long uptimeMs) {
        final int N = workSource.size();
        for (int i = 0; i < N; ++i) {
            final int uid = mapUid(workSource.getUid(i));
            noteLongPartialWakeLockFinishInternal(name, historyName, uid,
                    elapsedRealtimeMs, uptimeMs);
        }

        final List<WorkChain> workChains = workSource.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain workChain = workChains.get(i);
                final int uid = workChain.getAttributionUid();
                noteLongPartialWakeLockFinishInternal(name, historyName, uid,
                        elapsedRealtimeMs, uptimeMs);
            }
        }
    }

    @GuardedBy("this")
    private void noteLongPartialWakeLockFinishInternal(String name, String historyName, int uid,
            long elapsedRealtimeMs, long uptimeMs) {
        final int mappedUid = mapUid(uid);
        if (historyName == null) {
            historyName = name;
        }
        if (!mActiveEvents.updateState(HistoryItem.EVENT_LONG_WAKE_LOCK_FINISH, historyName,
                mappedUid, 0)) {
            return;
        }
        addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, HistoryItem.EVENT_LONG_WAKE_LOCK_FINISH,
                historyName, mappedUid);
        if (mappedUid != uid) {
            // Decrement the ref count for the isolated uid and delete the mapping if uneeded.
            maybeRemoveIsolatedUidLocked(uid, elapsedRealtimeMs, uptimeMs);
        }
    }

    @GuardedBy("this")
    void aggregateLastWakeupUptimeLocked(long elapsedRealtimeMs, long uptimeMs) {
        if (mLastWakeupReason != null) {
            long deltaUptimeMs = uptimeMs - mLastWakeupUptimeMs;
            SamplingTimer timer = getWakeupReasonTimerLocked(mLastWakeupReason);
            timer.add(deltaUptimeMs * 1000, 1, elapsedRealtimeMs); // time in in microseconds
            FrameworkStatsLog.write(FrameworkStatsLog.KERNEL_WAKEUP_REPORTED, mLastWakeupReason,
                    /* duration_usec */ deltaUptimeMs * 1000);
            mLastWakeupReason = null;
        }
    }

    @GuardedBy("this")
    public void noteWakeupReasonLocked(String reason) {
        noteWakeupReasonLocked(reason, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWakeupReasonLocked(String reason, long elapsedRealtimeMs, long uptimeMs) {
        if (DEBUG_HISTORY) Slog.v(TAG, "Wakeup reason \"" + reason +"\": "
                + Integer.toHexString(mHistoryCur.states));
        aggregateLastWakeupUptimeLocked(elapsedRealtimeMs, uptimeMs);
        mHistoryCur.wakeReasonTag = mHistoryCur.localWakeReasonTag;
        mHistoryCur.wakeReasonTag.string = reason;
        mHistoryCur.wakeReasonTag.uid = 0;
        mLastWakeupReason = reason;
        mLastWakeupUptimeMs = uptimeMs;
        addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
    }

    @GuardedBy("this")
    public boolean startAddingCpuLocked() {
        mExternalSync.cancelCpuSyncDueToWakelockChange();
        return mOnBatteryInternal;
    }

    @GuardedBy("this")
    public void finishAddingCpuLocked(int totalUTimeMs, int totalSTimeMs, int statUserTimeMs,
                                      int statSystemTimeMs, int statIOWaitTimeMs, int statIrqTimeMs,
                                      int statSoftIrqTimeMs, int statIdleTimeMs) {
        if (DEBUG) {
            Slog.d(TAG, "Adding cpu: tuser=" + totalUTimeMs + " tsys=" + totalSTimeMs
                    + " user=" + statUserTimeMs + " sys=" + statSystemTimeMs
                    + " io=" + statIOWaitTimeMs + " irq=" + statIrqTimeMs
                    + " sirq=" + statSoftIrqTimeMs + " idle=" + statIdleTimeMs);
        }
        mCurStepCpuUserTimeMs += totalUTimeMs;
        mCurStepCpuSystemTimeMs += totalSTimeMs;
        mCurStepStatUserTimeMs += statUserTimeMs;
        mCurStepStatSystemTimeMs += statSystemTimeMs;
        mCurStepStatIOWaitTimeMs += statIOWaitTimeMs;
        mCurStepStatIrqTimeMs += statIrqTimeMs;
        mCurStepStatSoftIrqTimeMs += statSoftIrqTimeMs;
        mCurStepStatIdleTimeMs += statIdleTimeMs;
    }

    public void noteProcessDiedLocked(int uid, int pid) {
        uid = mapUid(uid);
        Uid u = mUidStats.get(uid);
        if (u != null) {
            u.mPids.remove(pid);
        }
    }

    public long getProcessWakeTime(int uid, int pid, long realtimeMs) {
        uid = mapUid(uid);
        Uid u = mUidStats.get(uid);
        if (u != null) {
            Uid.Pid p = u.mPids.get(pid);
            if (p != null) {
                return p.mWakeSumMs + (p.mWakeNesting > 0 ? (realtimeMs - p.mWakeStartMs) : 0);
            }
        }
        return 0;
    }

    public void reportExcessiveCpuLocked(int uid, String proc, long overTimeMs, long usedTimeMs) {
        uid = mapUid(uid);
        Uid u = mUidStats.get(uid);
        if (u != null) {
            u.reportExcessiveCpuLocked(proc, overTimeMs, usedTimeMs);
        }
    }

    int mSensorNesting;

    @GuardedBy("this")
    public void noteStartSensorLocked(int uid, int sensor) {
        noteStartSensorLocked(uid, sensor, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteStartSensorLocked(int uid, int sensor, long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        if (mSensorNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_SENSOR_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Start sensor to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
        }
        mSensorNesting++;
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteStartSensor(sensor, elapsedRealtimeMs);
    }

    @GuardedBy("this")
    public void noteStopSensorLocked(int uid, int sensor) {
        noteStopSensorLocked(uid, sensor, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteStopSensorLocked(int uid, int sensor, long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        mSensorNesting--;
        if (mSensorNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_SENSOR_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Stop sensor to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
        }
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteStopSensor(sensor, elapsedRealtimeMs);
    }

    int mGpsNesting;

    @GuardedBy("this")
    public void noteGpsChangedLocked(WorkSource oldWs, WorkSource newWs) {
        noteGpsChangedLocked(oldWs, newWs, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteGpsChangedLocked(WorkSource oldWs, WorkSource newWs,
            long elapsedRealtimeMs, long uptimeMs) {
        for (int i = 0; i < newWs.size(); ++i) {
            noteStartGpsLocked(newWs.getUid(i), null, elapsedRealtimeMs, uptimeMs);
        }

        for (int i = 0; i < oldWs.size(); ++i) {
            noteStopGpsLocked((oldWs.getUid(i)), null, elapsedRealtimeMs, uptimeMs);
        }

        List<WorkChain>[] wcs = WorkSource.diffChains(oldWs, newWs);
        if (wcs != null) {
            if (wcs[0] != null) {
                final List<WorkChain> newChains = wcs[0];
                for (int i = 0; i < newChains.size(); ++i) {
                    noteStartGpsLocked(-1, newChains.get(i), elapsedRealtimeMs, uptimeMs);
                }
            }

            if (wcs[1] != null) {
                final List<WorkChain> goneChains = wcs[1];
                for (int i = 0; i < goneChains.size(); ++i) {
                    noteStopGpsLocked(-1, goneChains.get(i), elapsedRealtimeMs, uptimeMs);
                }
            }
        }
    }

    @GuardedBy("this")
    private void noteStartGpsLocked(int uid, WorkChain workChain,
            long elapsedRealtimeMs, long uptimeMs) {
        if (workChain != null) {
            uid = workChain.getAttributionUid();
        }
        final int mappedUid = mapUid(uid);
        if (mGpsNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_GPS_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Start GPS to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
        }
        mGpsNesting++;

        if (workChain == null) {
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.GPS_SCAN_STATE_CHANGED,
                    mapIsolatedUid(uid), null, FrameworkStatsLog.GPS_SCAN_STATE_CHANGED__STATE__ON);
        } else {
            FrameworkStatsLog.write(FrameworkStatsLog.GPS_SCAN_STATE_CHANGED,
                    workChain.getUids(), workChain.getTags(),
                    FrameworkStatsLog.GPS_SCAN_STATE_CHANGED__STATE__ON);
        }

        getUidStatsLocked(mappedUid, elapsedRealtimeMs, uptimeMs).noteStartGps(elapsedRealtimeMs);
    }

    @GuardedBy("this")
    private void noteStopGpsLocked(int uid, WorkChain workChain,
            long elapsedRealtimeMs, long uptimeMs) {
        if (workChain != null) {
            uid = workChain.getAttributionUid();
        }
        final int mappedUid = mapUid(uid);
        mGpsNesting--;
        if (mGpsNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_GPS_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Stop GPS to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            stopAllGpsSignalQualityTimersLocked(-1, elapsedRealtimeMs);
            mGpsSignalQualityBin = -1;
        }

        if (workChain == null) {
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.GPS_SCAN_STATE_CHANGED,
                    mapIsolatedUid(uid), null,
                    FrameworkStatsLog.GPS_SCAN_STATE_CHANGED__STATE__OFF);
        } else {
            FrameworkStatsLog.write(FrameworkStatsLog.GPS_SCAN_STATE_CHANGED, workChain.getUids(),
                    workChain.getTags(), FrameworkStatsLog.GPS_SCAN_STATE_CHANGED__STATE__OFF);
        }

        getUidStatsLocked(mappedUid, elapsedRealtimeMs, uptimeMs).noteStopGps(elapsedRealtimeMs);
    }

    @GuardedBy("this")
    public void noteGpsSignalQualityLocked(int signalLevel) {
        noteGpsSignalQualityLocked(signalLevel, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteGpsSignalQualityLocked(int signalLevel, long elapsedRealtimeMs, long uptimeMs) {
        if (mGpsNesting == 0) {
            return;
        }
        if (signalLevel < 0 || signalLevel >= mGpsSignalQualityTimer.length) {
            stopAllGpsSignalQualityTimersLocked(-1, elapsedRealtimeMs);
            return;
        }
        if (mGpsSignalQualityBin != signalLevel) {
            if (mGpsSignalQualityBin >= 0) {
                mGpsSignalQualityTimer[mGpsSignalQualityBin].stopRunningLocked(elapsedRealtimeMs);
            }
            if(!mGpsSignalQualityTimer[signalLevel].isRunningLocked()) {
                mGpsSignalQualityTimer[signalLevel].startRunningLocked(elapsedRealtimeMs);
            }
            mHistoryCur.states2 = (mHistoryCur.states2&~HistoryItem.STATE2_GPS_SIGNAL_QUALITY_MASK)
                    | (signalLevel << HistoryItem.STATE2_GPS_SIGNAL_QUALITY_SHIFT);
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mGpsSignalQualityBin = signalLevel;
        }
        return;
    }

    @GuardedBy("this")
    public void noteScreenStateLocked(int display, int state) {
        noteScreenStateLocked(display, state, mClock.elapsedRealtime(), mClock.uptimeMillis(),
                mClock.currentTimeMillis());
    }

    @GuardedBy("this")
    public void noteScreenStateLocked(int display, int displayState,
            long elapsedRealtimeMs, long uptimeMs, long currentTimeMs) {
        // Battery stats relies on there being 4 states. To accommodate this, new states beyond the
        // original 4 are mapped to one of the originals.
        if (displayState > MAX_TRACKED_SCREEN_STATE) {
            if (Display.isOnState(displayState)) {
                displayState = Display.STATE_ON;
            } else if (Display.isDozeState(displayState)) {
                if (Display.isSuspendedState(displayState)) {
                    displayState = Display.STATE_DOZE_SUSPEND;
                } else {
                    displayState = Display.STATE_DOZE;
                }
            } else if (Display.isOffState(displayState)) {
                displayState = Display.STATE_OFF;
            } else {
                Slog.wtf(TAG, "Unknown screen state (not mapped): " + displayState);
                displayState = Display.STATE_UNKNOWN;
            }
        }
        // As of this point, displayState should be mapped to one of:
        //  - Display.STATE_ON,
        //  - Display.STATE_DOZE
        //  - Display.STATE_DOZE_SUSPEND
        //  - Display.STATE_OFF
        //  - Display.STATE_UNKNOWN

        int state;
        int overallBin = mScreenBrightnessBin;
        int externalUpdateFlag = 0;
        boolean shouldScheduleSync = false;
        final int numDisplay = mPerDisplayBatteryStats.length;
        if (display < 0 || display >= numDisplay) {
            Slog.wtf(TAG, "Unexpected note screen state for display " + display + " (only "
                    + mPerDisplayBatteryStats.length + " displays exist...)");
            return;
        }
        final DisplayBatteryStats displayStats = mPerDisplayBatteryStats[display];
        final int oldDisplayState = displayStats.screenState;

        if (oldDisplayState == displayState) {
            // Nothing changed
            state = mScreenState;
        } else {
            displayStats.screenState = displayState;

            // Stop timer for previous display state.
            switch (oldDisplayState) {
                case Display.STATE_ON:
                    displayStats.screenOnTimer.stopRunningLocked(elapsedRealtimeMs);
                    final int bin = displayStats.screenBrightnessBin;
                    if (bin >= 0) {
                        displayStats.screenBrightnessTimers[bin].stopRunningLocked(
                                elapsedRealtimeMs);
                    }
                    overallBin = evaluateOverallScreenBrightnessBinLocked();
                    shouldScheduleSync = true;
                    break;
                case Display.STATE_DOZE:
                    // Transition from doze to doze suspend can be ignored.
                    if (displayState == Display.STATE_DOZE_SUSPEND) break;
                    displayStats.screenDozeTimer.stopRunningLocked(elapsedRealtimeMs);
                    shouldScheduleSync = true;
                    break;
                case Display.STATE_DOZE_SUSPEND:
                    // Transition from doze suspend to doze can be ignored.
                    if (displayState == Display.STATE_DOZE) break;
                    displayStats.screenDozeTimer.stopRunningLocked(elapsedRealtimeMs);
                    shouldScheduleSync = true;
                    break;
                case Display.STATE_OFF: // fallthrough
                case Display.STATE_UNKNOWN:
                    // Not tracked by timers.
                    break;
                default:
                    Slog.wtf(TAG,
                            "Attempted to stop timer for unexpected display state " + display);
            }

            // Start timer for new display state.
            switch (displayState) {
                case Display.STATE_ON:
                    displayStats.screenOnTimer.startRunningLocked(elapsedRealtimeMs);
                    final int bin = displayStats.screenBrightnessBin;
                    if (bin >= 0) {
                        displayStats.screenBrightnessTimers[bin].startRunningLocked(
                                elapsedRealtimeMs);
                    }
                    overallBin = evaluateOverallScreenBrightnessBinLocked();
                    shouldScheduleSync = true;
                    break;
                case Display.STATE_DOZE:
                    // Transition from doze suspend to doze can be ignored.
                    if (oldDisplayState == Display.STATE_DOZE_SUSPEND) break;
                    displayStats.screenDozeTimer.startRunningLocked(elapsedRealtimeMs);
                    shouldScheduleSync = true;
                    break;
                case Display.STATE_DOZE_SUSPEND:
                    // Transition from doze to doze suspend can be ignored.
                    if (oldDisplayState == Display.STATE_DOZE) break;
                    displayStats.screenDozeTimer.startRunningLocked(elapsedRealtimeMs);
                    shouldScheduleSync = true;
                    break;
                case Display.STATE_OFF: // fallthrough
                case Display.STATE_UNKNOWN:
                    // Not tracked by timers.
                    break;
                default:
                    Slog.wtf(TAG,
                            "Attempted to start timer for unexpected display state " + displayState
                                    + " for display " + display);
            }

            if (shouldScheduleSync
                    && mGlobalMeasuredEnergyStats != null
                    && mGlobalMeasuredEnergyStats.isStandardBucketSupported(
                    MeasuredEnergyStats.POWER_BUCKET_SCREEN_ON)) {
                // Display measured energy stats is available. Prepare to schedule an
                // external sync.
                externalUpdateFlag |= ExternalStatsSync.UPDATE_DISPLAY;
            }

            // Reevaluate most important display screen state.
            state = Display.STATE_UNKNOWN;
            for (int i = 0; i < numDisplay; i++) {
                final int tempState = mPerDisplayBatteryStats[i].screenState;
                if (tempState == Display.STATE_ON
                        || state == Display.STATE_ON) {
                    state = Display.STATE_ON;
                } else if (tempState == Display.STATE_DOZE
                        || state == Display.STATE_DOZE) {
                    state = Display.STATE_DOZE;
                } else if (tempState == Display.STATE_DOZE_SUSPEND
                        || state == Display.STATE_DOZE_SUSPEND) {
                    state = Display.STATE_DOZE_SUSPEND;
                } else if (tempState == Display.STATE_OFF
                        || state == Display.STATE_OFF) {
                    state = Display.STATE_OFF;
                }
            }
        }

        final boolean batteryRunning = mOnBatteryTimeBase.isRunning();
        final boolean batteryScreenOffRunning = mOnBatteryScreenOffTimeBase.isRunning();

        state = mPretendScreenOff ? Display.STATE_OFF : state;
        if (mScreenState != state) {
            recordDailyStatsIfNeededLocked(true, currentTimeMs);
            final int oldState = mScreenState;
            mScreenState = state;
            if (DEBUG) Slog.v(TAG, "Screen state: oldState=" + Display.stateToString(oldState)
                    + ", newState=" + Display.stateToString(state));

            if (state != Display.STATE_UNKNOWN) {
                int stepState = state-1;
                if ((stepState & STEP_LEVEL_MODE_SCREEN_STATE) == stepState) {
                    mModStepMode |= (mCurStepMode & STEP_LEVEL_MODE_SCREEN_STATE) ^ stepState;
                    mCurStepMode = (mCurStepMode & ~STEP_LEVEL_MODE_SCREEN_STATE) | stepState;
                } else {
                    Slog.wtf(TAG, "Unexpected screen state: " + state);
                }
            }

            boolean updateHistory = false;
            if (Display.isDozeState(state) && !Display.isDozeState(oldState)) {
                mHistoryCur.states |= HistoryItem.STATE_SCREEN_DOZE_FLAG;
                mScreenDozeTimer.startRunningLocked(elapsedRealtimeMs);
                updateHistory = true;
            } else if (Display.isDozeState(oldState) && !Display.isDozeState(state)) {
                mHistoryCur.states &= ~HistoryItem.STATE_SCREEN_DOZE_FLAG;
                mScreenDozeTimer.stopRunningLocked(elapsedRealtimeMs);
                updateHistory = true;
            }
            if (Display.isOnState(state)) {
                mHistoryCur.states |= HistoryItem.STATE_SCREEN_ON_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Screen on to: "
                        + Integer.toHexString(mHistoryCur.states));
                mScreenOnTimer.startRunningLocked(elapsedRealtimeMs);
                if (mScreenBrightnessBin >= 0) {
                    mScreenBrightnessTimer[mScreenBrightnessBin]
                            .startRunningLocked(elapsedRealtimeMs);
                }
                updateHistory = true;
            } else if (Display.isOnState(oldState)) {
                mHistoryCur.states &= ~HistoryItem.STATE_SCREEN_ON_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Screen off to: "
                        + Integer.toHexString(mHistoryCur.states));
                mScreenOnTimer.stopRunningLocked(elapsedRealtimeMs);
                if (mScreenBrightnessBin >= 0) {
                    mScreenBrightnessTimer[mScreenBrightnessBin]
                            .stopRunningLocked(elapsedRealtimeMs);
                }
                updateHistory = true;
            }
            if (updateHistory) {
                if (DEBUG_HISTORY) Slog.v(TAG, "Screen state to: "
                        + Display.stateToString(state));
                addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            }

            // Per screen state Cpu stats needed. Prepare to schedule an external sync.
            externalUpdateFlag |= ExternalStatsSync.UPDATE_CPU;
            shouldScheduleSync = true;

            if (Display.isOnState(state)) {
                updateTimeBasesLocked(mOnBatteryTimeBase.isRunning(), state,
                        uptimeMs * 1000, elapsedRealtimeMs * 1000);
                // Fake a wake lock, so we consider the device waked as long as the screen is on.
                noteStartWakeLocked(-1, -1, null, "screen", null, WAKE_TYPE_PARTIAL, false,
                        elapsedRealtimeMs, uptimeMs);
            } else if (Display.isOnState(oldState)) {
                noteStopWakeLocked(-1, -1, null, "screen", "screen", WAKE_TYPE_PARTIAL,
                        elapsedRealtimeMs, uptimeMs);
                updateTimeBasesLocked(mOnBatteryTimeBase.isRunning(), state,
                        uptimeMs * 1000, elapsedRealtimeMs * 1000);
            }
            // Update discharge amounts.
            if (mOnBatteryInternal) {
                updateDischargeScreenLevelsLocked(oldState, state);
            }
        }

        // Changing display states might have changed the screen used to determine the overall
        // brightness.
        maybeUpdateOverallScreenBrightness(overallBin, elapsedRealtimeMs, uptimeMs);

        if (shouldScheduleSync) {
            final int numDisplays = mPerDisplayBatteryStats.length;
            final int[] displayStates = new int[numDisplays];
            for (int i = 0; i < numDisplays; i++) {
                displayStates[i] = mPerDisplayBatteryStats[i].screenState;
            }
            mExternalSync.scheduleSyncDueToScreenStateChange(externalUpdateFlag,
                    batteryRunning, batteryScreenOffRunning, state, displayStates);
        }
    }

    @UnsupportedAppUsage
    @GuardedBy("this")
    public void noteScreenBrightnessLocked(int brightness) {
        noteScreenBrightnessLocked(0, brightness);
    }

    /**
     * Note screen brightness change for a display.
     */
    @GuardedBy("this")
    public void noteScreenBrightnessLocked(int display, int brightness) {
        noteScreenBrightnessLocked(display, brightness, mClock.elapsedRealtime(),
                mClock.uptimeMillis());
    }


    /**
     * Note screen brightness change for a display.
     */
    @GuardedBy("this")
    public void noteScreenBrightnessLocked(int display, int brightness, long elapsedRealtimeMs,
            long uptimeMs) {
        // Bin the brightness.
        int bin = brightness / (256/NUM_SCREEN_BRIGHTNESS_BINS);
        if (bin < 0) bin = 0;
        else if (bin >= NUM_SCREEN_BRIGHTNESS_BINS) bin = NUM_SCREEN_BRIGHTNESS_BINS-1;

        final int overallBin;

        final int numDisplays = mPerDisplayBatteryStats.length;
        if (display < 0 || display >= numDisplays) {
            Slog.wtf(TAG, "Unexpected note screen brightness for display " + display + " (only "
                    + mPerDisplayBatteryStats.length + " displays exist...)");
            return;
        }

        final DisplayBatteryStats displayStats = mPerDisplayBatteryStats[display];
        final int oldBin = displayStats.screenBrightnessBin;
        if (oldBin == bin) {
            // Nothing changed
            overallBin = mScreenBrightnessBin;
        } else {
            displayStats.screenBrightnessBin = bin;
            if (displayStats.screenState == Display.STATE_ON) {
                if (oldBin >= 0) {
                    displayStats.screenBrightnessTimers[oldBin].stopRunningLocked(
                            elapsedRealtimeMs);
                }
                displayStats.screenBrightnessTimers[bin].startRunningLocked(
                        elapsedRealtimeMs);
            }
            overallBin = evaluateOverallScreenBrightnessBinLocked();
        }

        maybeUpdateOverallScreenBrightness(overallBin, elapsedRealtimeMs, uptimeMs);
    }

    @GuardedBy("this")
    private int evaluateOverallScreenBrightnessBinLocked() {
        int overallBin = -1;
        final int numDisplays = getDisplayCount();
        for (int display = 0; display < numDisplays; display++) {
            final int displayBrightnessBin;
            if (mPerDisplayBatteryStats[display].screenState == Display.STATE_ON) {
                displayBrightnessBin = mPerDisplayBatteryStats[display].screenBrightnessBin;
            } else {
                displayBrightnessBin = -1;
            }
            if (displayBrightnessBin > overallBin) {
                overallBin = displayBrightnessBin;
            }
        }
        return overallBin;
    }

    @GuardedBy("this")
    private void maybeUpdateOverallScreenBrightness(int overallBin, long elapsedRealtimeMs,
            long uptimeMs) {
        if (mScreenBrightnessBin != overallBin) {
            if (overallBin >= 0) {
                mHistoryCur.states = (mHistoryCur.states & ~HistoryItem.STATE_BRIGHTNESS_MASK)
                        | (overallBin << HistoryItem.STATE_BRIGHTNESS_SHIFT);
                if (DEBUG_HISTORY) {
                    Slog.v(TAG, "Screen brightness " + overallBin + " to: "
                            + Integer.toHexString(mHistoryCur.states));
                }
                addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            }
            if (mScreenState == Display.STATE_ON) {
                if (mScreenBrightnessBin >= 0) {
                    mScreenBrightnessTimer[mScreenBrightnessBin]
                            .stopRunningLocked(elapsedRealtimeMs);
                }
                if (overallBin >= 0) {
                    mScreenBrightnessTimer[overallBin]
                            .startRunningLocked(elapsedRealtimeMs);
                }
            }
            mScreenBrightnessBin = overallBin;
        }
    }

    @UnsupportedAppUsage
    @GuardedBy("this")
    public void noteUserActivityLocked(int uid, @PowerManager.UserActivityEvent int event) {
        noteUserActivityLocked(uid, event, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteUserActivityLocked(int uid, @PowerManager.UserActivityEvent int event,
            long elapsedRealtimeMs, long uptimeMs) {
        if (mOnBatteryInternal) {
            uid = mapUid(uid);
            getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs).noteUserActivityLocked(event);
        }
    }

    @GuardedBy("this")
    public void noteWakeUpLocked(String reason, int reasonUid) {
        noteWakeUpLocked(reason, reasonUid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWakeUpLocked(String reason, int reasonUid,
            long elapsedRealtimeMs, long uptimeMs) {
        addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, HistoryItem.EVENT_SCREEN_WAKE_UP,
                reason, reasonUid);
    }

    @GuardedBy("this")
    public void noteInteractiveLocked(boolean interactive) {
        noteInteractiveLocked(interactive, mClock.elapsedRealtime());
    }

    @GuardedBy("this")
    public void noteInteractiveLocked(boolean interactive, long elapsedRealtimeMs) {
        if (mInteractive != interactive) {
            mInteractive = interactive;
            if (DEBUG) Slog.v(TAG, "Interactive: " + interactive);
            if (interactive) {
                mInteractiveTimer.startRunningLocked(elapsedRealtimeMs);
            } else {
                mInteractiveTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }
    }

    @GuardedBy("this")
    public void noteConnectivityChangedLocked(int type, String extra) {
        noteConnectivityChangedLocked(type, extra,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteConnectivityChangedLocked(int type, String extra,
            long elapsedRealtimeMs, long uptimeMs) {
        addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, HistoryItem.EVENT_CONNECTIVITY_CHANGED,
                extra, type);
        mNumConnectivityChange++;
    }

    @GuardedBy("this")
    private void noteMobileRadioApWakeupLocked(final long elapsedRealtimeMillis,
            final long uptimeMillis, int uid) {
        uid = mapUid(uid);
        addHistoryEventLocked(elapsedRealtimeMillis, uptimeMillis, HistoryItem.EVENT_WAKEUP_AP, "",
                uid);
        getUidStatsLocked(uid, elapsedRealtimeMillis, uptimeMillis).noteMobileRadioApWakeupLocked();
    }

    /**
     * Updates the radio power state and returns true if an external stats collection should occur.
     */
    @GuardedBy("this")
    public boolean noteMobileRadioPowerStateLocked(int powerState, long timestampNs, int uid) {
        return noteMobileRadioPowerStateLocked(powerState, timestampNs, uid,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public boolean noteMobileRadioPowerStateLocked(int powerState, long timestampNs, int uid,
            long elapsedRealtimeMs, long uptimeMs) {
        if (mMobileRadioPowerState != powerState) {
            long realElapsedRealtimeMs;
            final boolean active = isActiveRadioPowerState(powerState);
            if (active) {
                if (uid > 0) {
                    noteMobileRadioApWakeupLocked(elapsedRealtimeMs, uptimeMs, uid);
                }

                mMobileRadioActiveStartTimeMs = realElapsedRealtimeMs = timestampNs / (1000 * 1000);
                mHistoryCur.states |= HistoryItem.STATE_MOBILE_RADIO_ACTIVE_FLAG;
            } else {
                realElapsedRealtimeMs = timestampNs / (1000*1000);
                long lastUpdateTimeMs = mMobileRadioActiveStartTimeMs;
                if (realElapsedRealtimeMs < lastUpdateTimeMs) {
                    Slog.wtf(TAG, "Data connection inactive timestamp " + realElapsedRealtimeMs
                            + " is before start time " + lastUpdateTimeMs);
                    realElapsedRealtimeMs = elapsedRealtimeMs;
                } else if (realElapsedRealtimeMs < elapsedRealtimeMs) {
                    mMobileRadioActiveAdjustedTime.addCountLocked(elapsedRealtimeMs
                            - realElapsedRealtimeMs);
                }
                mHistoryCur.states &= ~HistoryItem.STATE_MOBILE_RADIO_ACTIVE_FLAG;
            }
            if (DEBUG_HISTORY) Slog.v(TAG, "Mobile network active " + active + " to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mMobileRadioPowerState = powerState;

            // Inform current RatBatteryStats that the modem active state might have changed.
            getRatBatteryStatsLocked(mActiveRat).noteActive(active, elapsedRealtimeMs);

            if (active) {
                mMobileRadioActiveTimer.startRunningLocked(elapsedRealtimeMs);
                mMobileRadioActivePerAppTimer.startRunningLocked(elapsedRealtimeMs);
            } else {
                mMobileRadioActiveTimer.stopRunningLocked(realElapsedRealtimeMs);
                mMobileRadioActivePerAppTimer.stopRunningLocked(realElapsedRealtimeMs);

                if (mLastModemActivityInfo != null) {
                    if (elapsedRealtimeMs < mLastModemActivityInfo.getTimestampMillis()
                            + MOBILE_RADIO_POWER_STATE_UPDATE_FREQ_MS) {
                        // Modem Activity info has been collected recently, don't bother
                        // triggering another update.
                        return false;
                    }
                }
                // Tell the caller to collect radio network/power stats.
                return true;
            }
        }
        return false;
    }

    private static boolean isActiveRadioPowerState(int powerState) {
        return powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_MEDIUM
                || powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH;
    }

    @GuardedBy("this")
    public void notePowerSaveModeLocked(boolean enabled) {
        notePowerSaveModeLocked(enabled, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    /**
     * Toggles the power save mode state.
     */
    @GuardedBy("this")
    public void notePowerSaveModeLockedInit(boolean enabled, long elapsedRealtimeMs,
            long uptimeMs) {
        if (mPowerSaveModeEnabled != enabled) {
            notePowerSaveModeLocked(enabled, elapsedRealtimeMs, uptimeMs);
        } else {
            // Log an initial value for BATTERY_SAVER_MODE_STATE_CHANGED in order to
            // allow the atom to read all future state changes.
            FrameworkStatsLog.write(FrameworkStatsLog.BATTERY_SAVER_MODE_STATE_CHANGED,
                    enabled
                        ? FrameworkStatsLog.BATTERY_SAVER_MODE_STATE_CHANGED__STATE__ON
                        : FrameworkStatsLog.BATTERY_SAVER_MODE_STATE_CHANGED__STATE__OFF);
        }
    }

    @GuardedBy("this")
    public void notePowerSaveModeLocked(boolean enabled, long elapsedRealtimeMs, long uptimeMs) {
        if (mPowerSaveModeEnabled != enabled) {
            int stepState = enabled ? STEP_LEVEL_MODE_POWER_SAVE : 0;
            mModStepMode |= (mCurStepMode&STEP_LEVEL_MODE_POWER_SAVE) ^ stepState;
            mCurStepMode = (mCurStepMode&~STEP_LEVEL_MODE_POWER_SAVE) | stepState;
            mPowerSaveModeEnabled = enabled;
            if (enabled) {
                mHistoryCur.states2 |= HistoryItem.STATE2_POWER_SAVE_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Power save mode enabled to: "
                        + Integer.toHexString(mHistoryCur.states2));
                mPowerSaveModeEnabledTimer.startRunningLocked(elapsedRealtimeMs);
            } else {
                mHistoryCur.states2 &= ~HistoryItem.STATE2_POWER_SAVE_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Power save mode disabled to: "
                        + Integer.toHexString(mHistoryCur.states2));
                mPowerSaveModeEnabledTimer.stopRunningLocked(elapsedRealtimeMs);
            }
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            FrameworkStatsLog.write(FrameworkStatsLog.BATTERY_SAVER_MODE_STATE_CHANGED,
                    enabled
                        ? FrameworkStatsLog.BATTERY_SAVER_MODE_STATE_CHANGED__STATE__ON
                        : FrameworkStatsLog.BATTERY_SAVER_MODE_STATE_CHANGED__STATE__OFF);
        }
    }

    @GuardedBy("this")
    public void noteDeviceIdleModeLocked(final int mode, String activeReason, int activeUid) {
        noteDeviceIdleModeLocked(mode, activeReason, activeUid,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteDeviceIdleModeLocked(final int mode, String activeReason, int activeUid,
            long elapsedRealtimeMs, long uptimeMs) {
        boolean nowIdling = mode == DEVICE_IDLE_MODE_DEEP;
        if (mDeviceIdling && !nowIdling && activeReason == null) {
            // We don't go out of general idling mode until explicitly taken out of
            // device idle through going active or significant motion.
            nowIdling = true;
        }
        boolean nowLightIdling = mode == DEVICE_IDLE_MODE_LIGHT;
        if (mDeviceLightIdling && !nowLightIdling && !nowIdling && activeReason == null) {
            // We don't go out of general light idling mode until explicitly taken out of
            // device idle through going active or significant motion.
            nowLightIdling = true;
        }
        if (activeReason != null && (mDeviceIdling || mDeviceLightIdling)) {
            addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, HistoryItem.EVENT_ACTIVE,
                    activeReason, activeUid);
        }
        if (mDeviceIdling != nowIdling || mDeviceLightIdling != nowLightIdling) {
            int statsmode;
            if (nowIdling)           statsmode = DEVICE_IDLE_MODE_DEEP;
            else if (nowLightIdling) statsmode = DEVICE_IDLE_MODE_LIGHT;
            else                     statsmode = DEVICE_IDLE_MODE_OFF;
            FrameworkStatsLog.write(FrameworkStatsLog.DEVICE_IDLING_MODE_STATE_CHANGED, statsmode);
        }
        if (mDeviceIdling != nowIdling) {
            mDeviceIdling = nowIdling;
            int stepState = nowIdling ? STEP_LEVEL_MODE_DEVICE_IDLE : 0;
            mModStepMode |= (mCurStepMode&STEP_LEVEL_MODE_DEVICE_IDLE) ^ stepState;
            mCurStepMode = (mCurStepMode&~STEP_LEVEL_MODE_DEVICE_IDLE) | stepState;
            if (nowIdling) {
                mDeviceIdlingTimer.startRunningLocked(elapsedRealtimeMs);
            } else {
                mDeviceIdlingTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }
        if (mDeviceLightIdling != nowLightIdling) {
            mDeviceLightIdling = nowLightIdling;
            if (nowLightIdling) {
                mDeviceLightIdlingTimer.startRunningLocked(elapsedRealtimeMs);
            } else {
                mDeviceLightIdlingTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }
        if (mDeviceIdleMode != mode) {
            mHistoryCur.states2 = (mHistoryCur.states2 & ~HistoryItem.STATE2_DEVICE_IDLE_MASK)
                    | (mode << HistoryItem.STATE2_DEVICE_IDLE_SHIFT);
            if (DEBUG_HISTORY) Slog.v(TAG, "Device idle mode changed to: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            long lastDuration = elapsedRealtimeMs - mLastIdleTimeStartMs;
            mLastIdleTimeStartMs = elapsedRealtimeMs;
            if (mDeviceIdleMode == DEVICE_IDLE_MODE_LIGHT) {
                if (lastDuration > mLongestLightIdleTimeMs) {
                    mLongestLightIdleTimeMs = lastDuration;
                }
                mDeviceIdleModeLightTimer.stopRunningLocked(elapsedRealtimeMs);
            } else if (mDeviceIdleMode == DEVICE_IDLE_MODE_DEEP) {
                if (lastDuration > mLongestFullIdleTimeMs) {
                    mLongestFullIdleTimeMs = lastDuration;
                }
                mDeviceIdleModeFullTimer.stopRunningLocked(elapsedRealtimeMs);
            }
            if (mode == DEVICE_IDLE_MODE_LIGHT) {
                mDeviceIdleModeLightTimer.startRunningLocked(elapsedRealtimeMs);
            } else if (mode == DEVICE_IDLE_MODE_DEEP) {
                mDeviceIdleModeFullTimer.startRunningLocked(elapsedRealtimeMs);
            }
            mDeviceIdleMode = mode;
            FrameworkStatsLog.write(FrameworkStatsLog.DEVICE_IDLE_MODE_STATE_CHANGED, mode);
        }
    }

    @GuardedBy("this")
    public void notePackageInstalledLocked(String pkgName, long versionCode) {
        notePackageInstalledLocked(pkgName, versionCode,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void notePackageInstalledLocked(String pkgName, long versionCode,
            long elapsedRealtimeMs, long uptimeMs) {
        // XXX need to figure out what to do with long version codes.
        addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, HistoryItem.EVENT_PACKAGE_INSTALLED,
                pkgName, (int)versionCode);
        PackageChange pc = new PackageChange();
        pc.mPackageName = pkgName;
        pc.mUpdate = true;
        pc.mVersionCode = versionCode;
        addPackageChange(pc);
    }

    @GuardedBy("this")
    public void notePackageUninstalledLocked(String pkgName) {
        notePackageUninstalledLocked(pkgName, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void notePackageUninstalledLocked(String pkgName,
            long elapsedRealtimeMs, long uptimeMs) {
        addHistoryEventLocked(elapsedRealtimeMs, uptimeMs,
                HistoryItem.EVENT_PACKAGE_UNINSTALLED, pkgName, 0);
        PackageChange pc = new PackageChange();
        pc.mPackageName = pkgName;
        pc.mUpdate = true;
        addPackageChange(pc);
    }

    private void addPackageChange(PackageChange pc) {
        if (mDailyPackageChanges == null) {
            mDailyPackageChanges = new ArrayList<>();
        }
        mDailyPackageChanges.add(pc);
    }

    @GuardedBy("this")
    void stopAllGpsSignalQualityTimersLocked(int except) {
        stopAllGpsSignalQualityTimersLocked(except, mClock.elapsedRealtime());
    }

    @GuardedBy("this")
    void stopAllGpsSignalQualityTimersLocked(int except, long elapsedRealtimeMs) {
        for (int i = 0; i < mGpsSignalQualityTimer.length; i++) {
            if (i == except) {
                continue;
            }
            while (mGpsSignalQualityTimer[i].isRunningLocked()) {
                mGpsSignalQualityTimer[i].stopRunningLocked(elapsedRealtimeMs);
            }
        }
    }

    @UnsupportedAppUsage
    @GuardedBy("this")
    public void notePhoneOnLocked() {
        notePhoneOnLocked(mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void notePhoneOnLocked(long elapsedRealtimeMs, long uptimeMs) {
        if (!mPhoneOn) {
            mHistoryCur.states2 |= HistoryItem.STATE2_PHONE_IN_CALL_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Phone on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mPhoneOn = true;
            mPhoneOnTimer.startRunningLocked(elapsedRealtimeMs);
            if (mConstants.PHONE_ON_EXTERNAL_STATS_COLLECTION) {
                scheduleSyncExternalStatsLocked("phone-on", ExternalStatsSync.UPDATE_RADIO);
            }
        }
    }

    @UnsupportedAppUsage
    @GuardedBy("this")
    public void notePhoneOffLocked() {
        notePhoneOffLocked(mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void notePhoneOffLocked(long elapsedRealtimeMs, long uptimeMs) {
        if (mPhoneOn) {
            mHistoryCur.states2 &= ~HistoryItem.STATE2_PHONE_IN_CALL_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Phone off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mPhoneOn = false;
            mPhoneOnTimer.stopRunningLocked(elapsedRealtimeMs);
            scheduleSyncExternalStatsLocked("phone-off", ExternalStatsSync.UPDATE_RADIO);
        }
    }

    @GuardedBy("this")
    private void registerUsbStateReceiver(Context context) {
        final IntentFilter usbStateFilter = new IntentFilter();
        usbStateFilter.addAction(UsbManager.ACTION_USB_STATE);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final boolean state = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
                synchronized (BatteryStatsImpl.this) {
                    noteUsbConnectionStateLocked(state, mClock.elapsedRealtime(),
                            mClock.uptimeMillis());
                }
            }
        }, usbStateFilter);
        synchronized (this) {
            if (mUsbDataState == USB_DATA_UNKNOWN) {
                final Intent usbState = context.registerReceiver(null, usbStateFilter);
                final boolean initState = usbState != null && usbState.getBooleanExtra(
                        UsbManager.USB_CONNECTED, false);
                noteUsbConnectionStateLocked(initState, mClock.elapsedRealtime(),
                        mClock.uptimeMillis());
            }
        }
    }

    @GuardedBy("this")
    private void noteUsbConnectionStateLocked(boolean connected, long elapsedRealtimeMs,
            long uptimeMs) {
        int newState = connected ? USB_DATA_CONNECTED : USB_DATA_DISCONNECTED;
        if (mUsbDataState != newState) {
            mUsbDataState = newState;
            if (connected) {
                mHistoryCur.states2 |= HistoryItem.STATE2_USB_DATA_LINK_FLAG;
            } else {
                mHistoryCur.states2 &= ~HistoryItem.STATE2_USB_DATA_LINK_FLAG;
            }
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
        }
    }

    @GuardedBy("this")
    void stopAllPhoneSignalStrengthTimersLocked(int except, long elapsedRealtimeMs) {
        for (int i = 0; i < CellSignalStrength.getNumSignalStrengthLevels(); i++) {
            if (i == except) {
                continue;
            }
            while (mPhoneSignalStrengthsTimer[i].isRunningLocked()) {
                mPhoneSignalStrengthsTimer[i].stopRunningLocked(elapsedRealtimeMs);
            }
        }
    }

    private int fixPhoneServiceState(int state, int signalBin) {
        if (mPhoneSimStateRaw == TelephonyManager.SIM_STATE_ABSENT) {
            // In this case we will always be STATE_OUT_OF_SERVICE, so need
            // to infer that we are scanning from other data.
            if (state == ServiceState.STATE_OUT_OF_SERVICE
                    && signalBin > CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
                state = ServiceState.STATE_IN_SERVICE;
            }
        }

        return state;
    }

    @GuardedBy("this")
    private void updateAllPhoneStateLocked(int state, int simState, int strengthBin,
            long elapsedRealtimeMs, long uptimeMs) {
        boolean scanning = false;
        boolean newHistory = false;

        mPhoneServiceStateRaw = state;
        mPhoneSimStateRaw = simState;
        mPhoneSignalStrengthBinRaw = strengthBin;

        if (simState == TelephonyManager.SIM_STATE_ABSENT) {
            // In this case we will always be STATE_OUT_OF_SERVICE, so need
            // to infer that we are scanning from other data.
            if (state == ServiceState.STATE_OUT_OF_SERVICE
                    && strengthBin > CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
                state = ServiceState.STATE_IN_SERVICE;
            }
        }

        // If the phone is powered off, stop all timers.
        if (state == ServiceState.STATE_POWER_OFF) {
            strengthBin = -1;

        // If we are in service, make sure the correct signal string timer is running.
        } else if (state == ServiceState.STATE_IN_SERVICE) {
            // Bin will be changed below.

        // If we're out of service, we are in the lowest signal strength
        // bin and have the scanning bit set.
        } else if (state == ServiceState.STATE_OUT_OF_SERVICE) {
            scanning = true;
            strengthBin = CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
            if (!mPhoneSignalScanningTimer.isRunningLocked()) {
                mHistoryCur.states |= HistoryItem.STATE_PHONE_SCANNING_FLAG;
                newHistory = true;
                if (DEBUG_HISTORY) Slog.v(TAG, "Phone started scanning to: "
                        + Integer.toHexString(mHistoryCur.states));
                mPhoneSignalScanningTimer.startRunningLocked(elapsedRealtimeMs);
                FrameworkStatsLog.write(FrameworkStatsLog.PHONE_SERVICE_STATE_CHANGED, state,
                        simState, strengthBin);
            }
        }

        if (!scanning) {
            // If we are no longer scanning, then stop the scanning timer.
            if (mPhoneSignalScanningTimer.isRunningLocked()) {
                mHistoryCur.states &= ~HistoryItem.STATE_PHONE_SCANNING_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Phone stopped scanning to: "
                        + Integer.toHexString(mHistoryCur.states));
                newHistory = true;
                mPhoneSignalScanningTimer.stopRunningLocked(elapsedRealtimeMs);
                FrameworkStatsLog.write(FrameworkStatsLog.PHONE_SERVICE_STATE_CHANGED, state,
                        simState, strengthBin);
            }
        }

        if (mPhoneServiceState != state) {
            mHistoryCur.states = (mHistoryCur.states&~HistoryItem.STATE_PHONE_STATE_MASK)
                    | (state << HistoryItem.STATE_PHONE_STATE_SHIFT);
            if (DEBUG_HISTORY) Slog.v(TAG, "Phone state " + state + " to: "
                    + Integer.toHexString(mHistoryCur.states));
            newHistory = true;
            mPhoneServiceState = state;
        }

        if (mPhoneSignalStrengthBin != strengthBin) {
            if (mPhoneSignalStrengthBin >= 0) {
                mPhoneSignalStrengthsTimer[mPhoneSignalStrengthBin].stopRunningLocked(
                        elapsedRealtimeMs);
            }
            if (strengthBin >= 0) {
                if (!mPhoneSignalStrengthsTimer[strengthBin].isRunningLocked()) {
                    mPhoneSignalStrengthsTimer[strengthBin].startRunningLocked(elapsedRealtimeMs);
                }
                mHistoryCur.states =
                        (mHistoryCur.states & ~HistoryItem.STATE_PHONE_SIGNAL_STRENGTH_MASK)
                        | (strengthBin << HistoryItem.STATE_PHONE_SIGNAL_STRENGTH_SHIFT);
                if (DEBUG_HISTORY) Slog.v(TAG, "Signal strength " + strengthBin + " to: "
                        + Integer.toHexString(mHistoryCur.states));
                newHistory = true;
                FrameworkStatsLog.write(
                        FrameworkStatsLog.PHONE_SIGNAL_STRENGTH_CHANGED, strengthBin);
            } else {
                stopAllPhoneSignalStrengthTimersLocked(-1, elapsedRealtimeMs);
            }
            mPhoneSignalStrengthBin = strengthBin;
        }

        if (newHistory) {
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Telephony stack updates the phone state.
     * @param state phone state from ServiceState.getState()
     */
    @GuardedBy("this")
    public void notePhoneStateLocked(int state, int simState) {
        notePhoneStateLocked(state, simState, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void notePhoneStateLocked(int state, int simState,
            long elapsedRealtimeMs, long uptimeMs) {
        updateAllPhoneStateLocked(state, simState, mPhoneSignalStrengthBinRaw,
                elapsedRealtimeMs, uptimeMs);
    }

    @UnsupportedAppUsage
    @GuardedBy("this")
    public void notePhoneSignalStrengthLocked(SignalStrength signalStrength) {
        notePhoneSignalStrengthLocked(signalStrength,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void notePhoneSignalStrengthLocked(SignalStrength signalStrength,
            long elapsedRealtimeMs, long uptimeMs) {
        final int overallSignalStrength = signalStrength.getLevel();
        final SparseIntArray perRatSignalStrength = new SparseIntArray(
                BatteryStats.RADIO_ACCESS_TECHNOLOGY_COUNT);

        // Extract signal strength level for each RAT.
        final List<CellSignalStrength> cellSignalStrengths =
                signalStrength.getCellSignalStrengths();
        final int size = cellSignalStrengths.size();
        for (int i = 0; i < size; i++) {
            CellSignalStrength cellSignalStrength = cellSignalStrengths.get(i);
            // Map each CellSignalStrength to a BatteryStats.RadioAccessTechnology
            final int ratType;
            final int level;
            if (cellSignalStrength instanceof CellSignalStrengthNr) {
                ratType = RADIO_ACCESS_TECHNOLOGY_NR;
                level = cellSignalStrength.getLevel();
            } else if (cellSignalStrength instanceof CellSignalStrengthLte) {
                ratType = RADIO_ACCESS_TECHNOLOGY_LTE;
                level = cellSignalStrength.getLevel();
            } else {
                ratType = RADIO_ACCESS_TECHNOLOGY_OTHER;
                level = cellSignalStrength.getLevel();
            }

            // According to SignalStrength#getCellSignalStrengths(), multiple of the same
            // cellSignalStrength can be present. Just take the highest level one for each RAT.
            if (perRatSignalStrength.get(ratType, -1) < level) {
                perRatSignalStrength.put(ratType, level);
            }
        }

        notePhoneSignalStrengthLocked(overallSignalStrength, perRatSignalStrength,
                elapsedRealtimeMs, uptimeMs);
    }

    /**
     * Note phone signal strength change, including per RAT signal strength.
     *
     * @param signalStrength overall signal strength {@see SignalStrength#getLevel()}
     * @param perRatSignalStrength signal strength of available RATs
     */
    @GuardedBy("this")
    public void notePhoneSignalStrengthLocked(int signalStrength,
            SparseIntArray perRatSignalStrength) {
        notePhoneSignalStrengthLocked(signalStrength, perRatSignalStrength,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    /**
     * Note phone signal strength change, including per RAT signal strength.
     *
     * @param signalStrength overall signal strength {@see SignalStrength#getLevel()}
     * @param perRatSignalStrength signal strength of available RATs
     */
    @GuardedBy("this")
    public void notePhoneSignalStrengthLocked(int signalStrength,
            SparseIntArray perRatSignalStrength,
            long elapsedRealtimeMs, long uptimeMs) {
        // Note each RAT's signal strength.
        final int size = perRatSignalStrength.size();
        for (int i = 0; i < size; i++) {
            final int rat = perRatSignalStrength.keyAt(i);
            final int ratSignalStrength = perRatSignalStrength.valueAt(i);
            getRatBatteryStatsLocked(rat).noteSignalStrength(ratSignalStrength, elapsedRealtimeMs);
        }
        updateAllPhoneStateLocked(mPhoneServiceStateRaw, mPhoneSimStateRaw, signalStrength,
                elapsedRealtimeMs, uptimeMs);
    }

    @UnsupportedAppUsage
    @GuardedBy("this")
    public void notePhoneDataConnectionStateLocked(@NetworkType int dataType, boolean hasData,
            @RegState int serviceType, @ServiceState.FrequencyRange int nrFrequency) {
        notePhoneDataConnectionStateLocked(dataType, hasData, serviceType, nrFrequency,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void notePhoneDataConnectionStateLocked(@NetworkType int dataType, boolean hasData,
            @RegState int serviceType, @ServiceState.FrequencyRange int nrFrequency,
            long elapsedRealtimeMs, long uptimeMs) {
        // BatteryStats uses 0 to represent no network type.
        // Telephony does not have a concept of no network type, and uses 0 to represent unknown.
        // Unknown is included in DATA_CONNECTION_OTHER.
        int bin = DATA_CONNECTION_OUT_OF_SERVICE;
        if (hasData) {
            if (dataType > 0 && dataType <= TelephonyManager.getAllNetworkTypes().length) {
                bin = dataType;
            } else {
                switch (serviceType) {
                    case ServiceState.STATE_OUT_OF_SERVICE:
                        bin = DATA_CONNECTION_OUT_OF_SERVICE;
                        break;
                    case ServiceState.STATE_EMERGENCY_ONLY:
                        bin = DATA_CONNECTION_EMERGENCY_SERVICE;
                        break;
                    default:
                        bin = DATA_CONNECTION_OTHER;
                        break;
                }
            }
        }

        final int newRat = mapNetworkTypeToRadioAccessTechnology(bin);
        if (newRat == RADIO_ACCESS_TECHNOLOGY_NR) {
            // Note possible frequency change for the NR RAT.
            getRatBatteryStatsLocked(newRat).noteFrequencyRange(nrFrequency, elapsedRealtimeMs);
        }

        if (DEBUG) Log.i(TAG, "Phone Data Connection -> " + dataType + " = " + hasData);
        if (mPhoneDataConnectionType != bin) {
            mHistoryCur.states = (mHistoryCur.states&~HistoryItem.STATE_DATA_CONNECTION_MASK)
                    | (bin << HistoryItem.STATE_DATA_CONNECTION_SHIFT);
            if (DEBUG_HISTORY) Slog.v(TAG, "Data connection " + bin + " to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            if (mPhoneDataConnectionType >= 0) {
                mPhoneDataConnectionsTimer[mPhoneDataConnectionType].stopRunningLocked(
                        elapsedRealtimeMs);
            }
            mPhoneDataConnectionType = bin;
            mPhoneDataConnectionsTimer[bin].startRunningLocked(elapsedRealtimeMs);

            if (mActiveRat != newRat) {
                getRatBatteryStatsLocked(mActiveRat).noteActive(false, elapsedRealtimeMs);
                mActiveRat = newRat;
            }
            final boolean modemActive = mMobileRadioActiveTimer.isRunningLocked();
            getRatBatteryStatsLocked(newRat).noteActive(modemActive, elapsedRealtimeMs);
        }
    }

    @RadioAccessTechnology
    private static int mapNetworkTypeToRadioAccessTechnology(@NetworkType int dataType) {
        switch (dataType) {
            case TelephonyManager.NETWORK_TYPE_NR:
                return RADIO_ACCESS_TECHNOLOGY_NR;
            case TelephonyManager.NETWORK_TYPE_LTE:
                return RADIO_ACCESS_TECHNOLOGY_LTE;
            case TelephonyManager.NETWORK_TYPE_UNKNOWN: //fallthrough
            case TelephonyManager.NETWORK_TYPE_GPRS: //fallthrough
            case TelephonyManager.NETWORK_TYPE_EDGE: //fallthrough
            case TelephonyManager.NETWORK_TYPE_UMTS: //fallthrough
            case TelephonyManager.NETWORK_TYPE_CDMA: //fallthrough
            case TelephonyManager.NETWORK_TYPE_EVDO_0: //fallthrough
            case TelephonyManager.NETWORK_TYPE_EVDO_A: //fallthrough
            case TelephonyManager.NETWORK_TYPE_1xRTT: //fallthrough
            case TelephonyManager.NETWORK_TYPE_HSDPA: //fallthrough
            case TelephonyManager.NETWORK_TYPE_HSUPA: //fallthrough
            case TelephonyManager.NETWORK_TYPE_HSPA: //fallthrough
            case TelephonyManager.NETWORK_TYPE_IDEN: //fallthrough
            case TelephonyManager.NETWORK_TYPE_EVDO_B: //fallthrough
            case TelephonyManager.NETWORK_TYPE_EHRPD: //fallthrough
            case TelephonyManager.NETWORK_TYPE_HSPAP: //fallthrough
            case TelephonyManager.NETWORK_TYPE_GSM: //fallthrough
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA: //fallthrough
            case TelephonyManager.NETWORK_TYPE_IWLAN: //fallthrough
                return RADIO_ACCESS_TECHNOLOGY_OTHER;
            default:
                Slog.w(TAG, "Unhandled NetworkType (" + dataType + "), mapping to OTHER");
                return RADIO_ACCESS_TECHNOLOGY_OTHER;
        }
    }

    @RadioAccessTechnology
    private static int mapRadioAccessNetworkTypeToRadioAccessTechnology(
            @AccessNetworkConstants.RadioAccessNetworkType int dataType) {
        switch (dataType) {
            case AccessNetworkConstants.AccessNetworkType.NGRAN:
                return RADIO_ACCESS_TECHNOLOGY_NR;
            case AccessNetworkConstants.AccessNetworkType.EUTRAN:
                return RADIO_ACCESS_TECHNOLOGY_LTE;
            case AccessNetworkConstants.AccessNetworkType.UNKNOWN: //fallthrough
            case AccessNetworkConstants.AccessNetworkType.GERAN: //fallthrough
            case AccessNetworkConstants.AccessNetworkType.UTRAN: //fallthrough
            case AccessNetworkConstants.AccessNetworkType.CDMA2000: //fallthrough
            case AccessNetworkConstants.AccessNetworkType.IWLAN:
                return RADIO_ACCESS_TECHNOLOGY_OTHER;
            default:
                Slog.w(TAG,
                        "Unhandled RadioAccessNetworkType (" + dataType + "), mapping to OTHER");
                return RADIO_ACCESS_TECHNOLOGY_OTHER;
        }
    }

    @GuardedBy("this")
    public void noteWifiOnLocked() {
        noteWifiOnLocked(mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWifiOnLocked(long elapsedRealtimeMs, long uptimeMs) {
        if (!mWifiOn) {
            mHistoryCur.states2 |= HistoryItem.STATE2_WIFI_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mWifiOn = true;
            mWifiOnTimer.startRunningLocked(elapsedRealtimeMs);
            scheduleSyncExternalStatsLocked("wifi-off", ExternalStatsSync.UPDATE_WIFI);
        }
    }

    @GuardedBy("this")
    public void noteWifiOffLocked() {
        noteWifiOffLocked(mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWifiOffLocked(long elapsedRealtimeMs, long uptimeMs) {
        if (mWifiOn) {
            mHistoryCur.states2 &= ~HistoryItem.STATE2_WIFI_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mWifiOn = false;
            mWifiOnTimer.stopRunningLocked(elapsedRealtimeMs);
            scheduleSyncExternalStatsLocked("wifi-on", ExternalStatsSync.UPDATE_WIFI);
        }
    }

    @UnsupportedAppUsage
    @GuardedBy("this")
    public void noteAudioOnLocked(int uid) {
        noteAudioOnLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteAudioOnLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        if (mAudioOnNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_AUDIO_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Audio on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mAudioOnTimer.startRunningLocked(elapsedRealtimeMs);
        }
        mAudioOnNesting++;
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteAudioTurnedOnLocked(elapsedRealtimeMs);
    }

    @UnsupportedAppUsage
    @GuardedBy("this")
    public void noteAudioOffLocked(int uid) {
        noteAudioOffLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteAudioOffLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        if (mAudioOnNesting == 0) {
            return;
        }
        uid = mapUid(uid);
        if (--mAudioOnNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_AUDIO_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Audio off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mAudioOnTimer.stopRunningLocked(elapsedRealtimeMs);
        }
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteAudioTurnedOffLocked(elapsedRealtimeMs);
    }

    @UnsupportedAppUsage
    @GuardedBy("this")
    public void noteVideoOnLocked(int uid) {
        noteVideoOnLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteVideoOnLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        if (mVideoOnNesting == 0) {
            mHistoryCur.states2 |= HistoryItem.STATE2_VIDEO_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Video on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mVideoOnTimer.startRunningLocked(elapsedRealtimeMs);
        }
        mVideoOnNesting++;
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteVideoTurnedOnLocked(elapsedRealtimeMs);
    }

    @UnsupportedAppUsage
    @GuardedBy("this")
    public void noteVideoOffLocked(int uid) {
        noteVideoOffLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteVideoOffLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        if (mVideoOnNesting == 0) {
            return;
        }
        uid = mapUid(uid);
        if (--mVideoOnNesting == 0) {
            mHistoryCur.states2 &= ~HistoryItem.STATE2_VIDEO_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Video off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mVideoOnTimer.stopRunningLocked(elapsedRealtimeMs);
        }
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteVideoTurnedOffLocked(elapsedRealtimeMs);
    }

    @GuardedBy("this")
    public void noteResetAudioLocked() {
        noteResetAudioLocked(mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteResetAudioLocked(long elapsedRealtimeMs, long uptimeMs) {
        if (mAudioOnNesting > 0) {
            mAudioOnNesting = 0;
            mHistoryCur.states &= ~HistoryItem.STATE_AUDIO_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Audio off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mAudioOnTimer.stopAllRunningLocked(elapsedRealtimeMs);
            for (int i=0; i<mUidStats.size(); i++) {
                BatteryStatsImpl.Uid uid = mUidStats.valueAt(i);
                uid.noteResetAudioLocked(elapsedRealtimeMs);
            }
        }
    }

    @GuardedBy("this")
    public void noteResetVideoLocked() {
        noteResetVideoLocked(mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteResetVideoLocked(long elapsedRealtimeMs, long uptimeMs) {
        if (mVideoOnNesting > 0) {
            mVideoOnNesting = 0;
            mHistoryCur.states2 &= ~HistoryItem.STATE2_VIDEO_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Video off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mVideoOnTimer.stopAllRunningLocked(elapsedRealtimeMs);
            for (int i=0; i<mUidStats.size(); i++) {
                BatteryStatsImpl.Uid uid = mUidStats.valueAt(i);
                uid.noteResetVideoLocked(elapsedRealtimeMs);
            }
        }
    }

    @GuardedBy("this")
    public void noteActivityResumedLocked(int uid) {
        noteActivityResumedLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteActivityResumedLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteActivityResumedLocked(elapsedRealtimeMs);
    }

    @GuardedBy("this")
    public void noteActivityPausedLocked(int uid) {
        noteActivityPausedLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteActivityPausedLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteActivityPausedLocked(elapsedRealtimeMs);
    }

    @GuardedBy("this")
    public void noteVibratorOnLocked(int uid, long durationMillis) {
        noteVibratorOnLocked(uid, durationMillis,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteVibratorOnLocked(int uid, long durationMillis,
            long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteVibratorOnLocked(durationMillis, elapsedRealtimeMs);
    }

    @GuardedBy("this")
    public void noteVibratorOffLocked(int uid) {
        noteVibratorOffLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteVibratorOffLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteVibratorOffLocked(elapsedRealtimeMs);
    }

    @GuardedBy("this")
    public void noteFlashlightOnLocked(int uid) {
        noteFlashlightOnLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteFlashlightOnLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        if (mFlashlightOnNesting++ == 0) {
            mHistoryCur.states2 |= HistoryItem.STATE2_FLASHLIGHT_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Flashlight on to: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mFlashlightOnTimer.startRunningLocked(elapsedRealtimeMs);
        }
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteFlashlightTurnedOnLocked(elapsedRealtimeMs);
    }

    @GuardedBy("this")
    public void noteFlashlightOffLocked(int uid) {
        noteFlashlightOffLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteFlashlightOffLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        if (mFlashlightOnNesting == 0) {
            return;
        }
        uid = mapUid(uid);
        if (--mFlashlightOnNesting == 0) {
            mHistoryCur.states2 &= ~HistoryItem.STATE2_FLASHLIGHT_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Flashlight off to: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mFlashlightOnTimer.stopRunningLocked(elapsedRealtimeMs);
        }
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteFlashlightTurnedOffLocked(elapsedRealtimeMs);
    }

    @GuardedBy("this")
    public void noteCameraOnLocked(int uid) {
        noteCameraOnLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteCameraOnLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        if (mCameraOnNesting++ == 0) {
            mHistoryCur.states2 |= HistoryItem.STATE2_CAMERA_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Camera on to: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mCameraOnTimer.startRunningLocked(elapsedRealtimeMs);
        }
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteCameraTurnedOnLocked(elapsedRealtimeMs);
    }

    @GuardedBy("this")
    public void noteCameraOffLocked(int uid) {
        noteCameraOffLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteCameraOffLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        if (mCameraOnNesting == 0) {
            return;
        }
        uid = mapUid(uid);
        if (--mCameraOnNesting == 0) {
            mHistoryCur.states2 &= ~HistoryItem.STATE2_CAMERA_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Camera off to: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mCameraOnTimer.stopRunningLocked(elapsedRealtimeMs);
        }
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteCameraTurnedOffLocked(elapsedRealtimeMs);
    }

    @GuardedBy("this")
    public void noteResetCameraLocked() {
        noteResetCameraLocked(mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteResetCameraLocked(long elapsedRealtimeMs, long uptimeMs) {
        if (mCameraOnNesting > 0) {
            mCameraOnNesting = 0;
            mHistoryCur.states2 &= ~HistoryItem.STATE2_CAMERA_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Camera off to: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mCameraOnTimer.stopAllRunningLocked(elapsedRealtimeMs);
            for (int i=0; i<mUidStats.size(); i++) {
                BatteryStatsImpl.Uid uid = mUidStats.valueAt(i);
                uid.noteResetCameraLocked(elapsedRealtimeMs);
            }
        }
    }

    @GuardedBy("this")
    public void noteResetFlashlightLocked() {
        noteResetFlashlightLocked(mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteResetFlashlightLocked(long elapsedRealtimeMs, long uptimeMs) {
        if (mFlashlightOnNesting > 0) {
            mFlashlightOnNesting = 0;
            mHistoryCur.states2 &= ~HistoryItem.STATE2_FLASHLIGHT_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Flashlight off to: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mFlashlightOnTimer.stopAllRunningLocked(elapsedRealtimeMs);
            for (int i=0; i<mUidStats.size(); i++) {
                BatteryStatsImpl.Uid uid = mUidStats.valueAt(i);
                uid.noteResetFlashlightLocked(elapsedRealtimeMs);
            }
        }
    }

    @GuardedBy("this")
    private void noteBluetoothScanStartedLocked(WorkChain workChain, int uid,
            boolean isUnoptimized, long elapsedRealtimeMs, long uptimeMs) {
        if (workChain != null) {
            uid = workChain.getAttributionUid();
        }
        uid = mapUid(uid);
        if (mBluetoothScanNesting == 0) {
            mHistoryCur.states2 |= HistoryItem.STATE2_BLUETOOTH_SCAN_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "BLE scan started for: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mBluetoothScanTimer.startRunningLocked(elapsedRealtimeMs);
        }
        mBluetoothScanNesting++;
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteBluetoothScanStartedLocked(elapsedRealtimeMs, isUnoptimized);
    }

    @GuardedBy("this")
    public void noteBluetoothScanStartedFromSourceLocked(WorkSource ws, boolean isUnoptimized) {
        noteBluetoothScanStartedFromSourceLocked(ws, isUnoptimized,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteBluetoothScanStartedFromSourceLocked(WorkSource ws, boolean isUnoptimized,
            long elapsedRealtimeMs, long uptimeMs) {
        final int N = ws.size();
        for (int i = 0; i < N; i++) {
            noteBluetoothScanStartedLocked(null, ws.getUid(i), isUnoptimized,
                    elapsedRealtimeMs, uptimeMs);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                noteBluetoothScanStartedLocked(workChains.get(i), -1, isUnoptimized,
                        elapsedRealtimeMs, uptimeMs);
            }
        }
    }

    @GuardedBy("this")
    private void noteBluetoothScanStoppedLocked(WorkChain workChain, int uid,
            boolean isUnoptimized, long elapsedRealtimeMs, long uptimeMs) {
        if (workChain != null) {
            uid = workChain.getAttributionUid();
        }
        uid = mapUid(uid);
        mBluetoothScanNesting--;
        if (mBluetoothScanNesting == 0) {
            mHistoryCur.states2 &= ~HistoryItem.STATE2_BLUETOOTH_SCAN_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "BLE scan stopped for: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mBluetoothScanTimer.stopRunningLocked(elapsedRealtimeMs);
        }
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteBluetoothScanStoppedLocked(elapsedRealtimeMs, isUnoptimized);
    }

    @GuardedBy("this")
    public void noteBluetoothScanStoppedFromSourceLocked(WorkSource ws, boolean isUnoptimized) {
        noteBluetoothScanStoppedFromSourceLocked(ws, isUnoptimized,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteBluetoothScanStoppedFromSourceLocked(WorkSource ws, boolean isUnoptimized,
            long elapsedRealtimeMs, long uptimeMs) {
        final int N = ws.size();
        for (int i = 0; i < N; i++) {
            noteBluetoothScanStoppedLocked(null, ws.getUid(i), isUnoptimized,
                    elapsedRealtimeMs, uptimeMs);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                noteBluetoothScanStoppedLocked(workChains.get(i), -1, isUnoptimized,
                        elapsedRealtimeMs, uptimeMs);
            }
        }
    }

    @GuardedBy("this")
    public void noteResetBluetoothScanLocked() {
        noteResetBluetoothScanLocked(mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteResetBluetoothScanLocked(long elapsedRealtimeMs, long uptimeMs) {
        if (mBluetoothScanNesting > 0) {
            mBluetoothScanNesting = 0;
            mHistoryCur.states2 &= ~HistoryItem.STATE2_BLUETOOTH_SCAN_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "BLE can stopped for: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mBluetoothScanTimer.stopAllRunningLocked(elapsedRealtimeMs);
            for (int i=0; i<mUidStats.size(); i++) {
                BatteryStatsImpl.Uid uid = mUidStats.valueAt(i);
                uid.noteResetBluetoothScanLocked(elapsedRealtimeMs);
            }
        }
    }

    @GuardedBy("this")
    public void noteBluetoothScanResultsFromSourceLocked(WorkSource ws, int numNewResults) {
        noteBluetoothScanResultsFromSourceLocked(ws, numNewResults,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteBluetoothScanResultsFromSourceLocked(WorkSource ws, int numNewResults,
            long elapsedRealtimeMs, long uptimeMs) {
        final int N = ws.size();
        for (int i = 0; i < N; i++) {
            int uid = mapUid(ws.getUid(i));
            getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                    .noteBluetoothScanResultsLocked(numNewResults);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain wc = workChains.get(i);
                int uid = mapUid(wc.getAttributionUid());
                getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                        .noteBluetoothScanResultsLocked(numNewResults);
            }
        }
    }

    @GuardedBy("this")
    private void noteWifiRadioApWakeupLocked(final long elapsedRealtimeMillis,
            final long uptimeMillis, int uid) {
        uid = mapUid(uid);
        addHistoryEventLocked(elapsedRealtimeMillis, uptimeMillis, HistoryItem.EVENT_WAKEUP_AP, "",
                uid);
        getUidStatsLocked(uid, elapsedRealtimeMillis, uptimeMillis).noteWifiRadioApWakeupLocked();
    }

    @GuardedBy("this")
    public void noteWifiRadioPowerState(int powerState, long timestampNs, int uid) {
        noteWifiRadioPowerState(powerState, timestampNs, uid,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWifiRadioPowerState(int powerState, long timestampNs, int uid,
            long elapsedRealtimeMs, long uptimeMs) {
        if (mWifiRadioPowerState != powerState) {
            final boolean active =
                    powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_MEDIUM
                            || powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH;
            if (active) {
                if (uid > 0) {
                    noteWifiRadioApWakeupLocked(elapsedRealtimeMs, uptimeMs, uid);
                }
                mHistoryCur.states |= HistoryItem.STATE_WIFI_RADIO_ACTIVE_FLAG;
                mWifiActiveTimer.startRunningLocked(elapsedRealtimeMs);
            } else {
                mHistoryCur.states &= ~HistoryItem.STATE_WIFI_RADIO_ACTIVE_FLAG;
                mWifiActiveTimer.stopRunningLocked(timestampNs / (1000 * 1000));
            }
            if (DEBUG_HISTORY) Slog.v(TAG, "Wifi network active " + active + " to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mWifiRadioPowerState = powerState;
        }
    }

    @GuardedBy("this")
    public void noteWifiRunningLocked(WorkSource ws) {
        noteWifiRunningLocked(ws, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWifiRunningLocked(WorkSource ws, long elapsedRealtimeMs, long uptimeMs) {
        if (!mGlobalWifiRunning) {
            mHistoryCur.states2 |= HistoryItem.STATE2_WIFI_RUNNING_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI running to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mGlobalWifiRunning = true;
            mGlobalWifiRunningTimer.startRunningLocked(elapsedRealtimeMs);
            int N = ws.size();
            for (int i=0; i<N; i++) {
                int uid = mapUid(ws.getUid(i));
                getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                        .noteWifiRunningLocked(elapsedRealtimeMs);
            }

            List<WorkChain> workChains = ws.getWorkChains();
            if (workChains != null) {
                for (int i = 0; i < workChains.size(); ++i) {
                    int uid = mapUid(workChains.get(i).getAttributionUid());
                    getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                            .noteWifiRunningLocked(elapsedRealtimeMs);
                }
            }

            scheduleSyncExternalStatsLocked("wifi-running", ExternalStatsSync.UPDATE_WIFI);
        } else {
            Log.w(TAG, "noteWifiRunningLocked -- called while WIFI running");
        }
    }

    @GuardedBy("this")
    public void noteWifiRunningChangedLocked(WorkSource oldWs, WorkSource newWs) {
        noteWifiRunningChangedLocked(oldWs, newWs,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWifiRunningChangedLocked(WorkSource oldWs, WorkSource newWs,
            long elapsedRealtimeMs, long uptimeMs) {
        if (mGlobalWifiRunning) {
            int N = oldWs.size();
            for (int i=0; i<N; i++) {
                int uid = mapUid(oldWs.getUid(i));
                getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                        .noteWifiStoppedLocked(elapsedRealtimeMs);
            }

            List<WorkChain> workChains = oldWs.getWorkChains();
            if (workChains != null) {
                for (int i = 0; i < workChains.size(); ++i) {
                    int uid = mapUid(workChains.get(i).getAttributionUid());
                    getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                            .noteWifiStoppedLocked(elapsedRealtimeMs);
                }
            }

            N = newWs.size();
            for (int i=0; i<N; i++) {
                int uid = mapUid(newWs.getUid(i));
                getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                        .noteWifiRunningLocked(elapsedRealtimeMs);
            }

            workChains = newWs.getWorkChains();
            if (workChains != null) {
                for (int i = 0; i < workChains.size(); ++i) {
                    int uid = mapUid(workChains.get(i).getAttributionUid());
                    getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                            .noteWifiRunningLocked(elapsedRealtimeMs);
                }
            }
        } else {
            Log.w(TAG, "noteWifiRunningChangedLocked -- called while WIFI not running");
        }
    }

    @GuardedBy("this")
    public void noteWifiStoppedLocked(WorkSource ws) {
        noteWifiStoppedLocked(ws, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWifiStoppedLocked(WorkSource ws, long elapsedRealtimeMs, long uptimeMs) {
        if (mGlobalWifiRunning) {
            mHistoryCur.states2 &= ~HistoryItem.STATE2_WIFI_RUNNING_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI stopped to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            mGlobalWifiRunning = false;
            mGlobalWifiRunningTimer.stopRunningLocked(elapsedRealtimeMs);
            int N = ws.size();
            for (int i=0; i<N; i++) {
                int uid = mapUid(ws.getUid(i));
                getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                        .noteWifiStoppedLocked(elapsedRealtimeMs);
            }

            List<WorkChain> workChains = ws.getWorkChains();
            if (workChains != null) {
                for (int i = 0; i < workChains.size(); ++i) {
                    int uid = mapUid(workChains.get(i).getAttributionUid());
                    getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                            .noteWifiStoppedLocked(elapsedRealtimeMs);
                }
            }

            scheduleSyncExternalStatsLocked("wifi-stopped", ExternalStatsSync.UPDATE_WIFI);
        } else {
            Log.w(TAG, "noteWifiStoppedLocked -- called while WIFI not running");
        }
    }

    @GuardedBy("this")
    public void noteWifiStateLocked(int wifiState, String accessPoint) {
        noteWifiStateLocked(wifiState, accessPoint, mClock.elapsedRealtime());
    }

    @GuardedBy("this")
    public void noteWifiStateLocked(int wifiState, String accessPoint, long elapsedRealtimeMs) {
        if (DEBUG) Log.i(TAG, "WiFi state -> " + wifiState);
        if (mWifiState != wifiState) {
            if (mWifiState >= 0) {
                mWifiStateTimer[mWifiState].stopRunningLocked(elapsedRealtimeMs);
            }
            mWifiState = wifiState;
            mWifiStateTimer[wifiState].startRunningLocked(elapsedRealtimeMs);
            scheduleSyncExternalStatsLocked("wifi-state", ExternalStatsSync.UPDATE_WIFI);
        }
    }

    @GuardedBy("this")
    public void noteWifiSupplicantStateChangedLocked(int supplState, boolean failedAuth) {
        noteWifiSupplicantStateChangedLocked(supplState, failedAuth,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWifiSupplicantStateChangedLocked(int supplState, boolean failedAuth,
            long elapsedRealtimeMs, long uptimeMs) {
        if (DEBUG) Log.i(TAG, "WiFi suppl state -> " + supplState);
        if (mWifiSupplState != supplState) {
            if (mWifiSupplState >= 0) {
                mWifiSupplStateTimer[mWifiSupplState].stopRunningLocked(elapsedRealtimeMs);
            }
            mWifiSupplState = supplState;
            mWifiSupplStateTimer[supplState].startRunningLocked(elapsedRealtimeMs);
            mHistoryCur.states2 =
                    (mHistoryCur.states2&~HistoryItem.STATE2_WIFI_SUPPL_STATE_MASK)
                    | (supplState << HistoryItem.STATE2_WIFI_SUPPL_STATE_SHIFT);
            if (DEBUG_HISTORY) Slog.v(TAG, "Wifi suppl state " + supplState + " to: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
        }
    }

    @GuardedBy("this")
    void stopAllWifiSignalStrengthTimersLocked(int except, long elapsedRealtimeMs) {
        for (int i = 0; i < NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            if (i == except) {
                continue;
            }
            while (mWifiSignalStrengthsTimer[i].isRunningLocked()) {
                mWifiSignalStrengthsTimer[i].stopRunningLocked(elapsedRealtimeMs);
            }
        }
    }

    @GuardedBy("this")
    public void noteWifiRssiChangedLocked(int newRssi) {
        noteWifiRssiChangedLocked(newRssi, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWifiRssiChangedLocked(int newRssi, long elapsedRealtimeMs, long uptimeMs) {
        int strengthBin = WifiManager.calculateSignalLevel(newRssi, NUM_WIFI_SIGNAL_STRENGTH_BINS);
        if (DEBUG) Log.i(TAG, "WiFi rssi -> " + newRssi + " bin=" + strengthBin);
        if (mWifiSignalStrengthBin != strengthBin) {
            if (mWifiSignalStrengthBin >= 0) {
                mWifiSignalStrengthsTimer[mWifiSignalStrengthBin].stopRunningLocked(
                        elapsedRealtimeMs);
            }
            if (strengthBin >= 0) {
                if (!mWifiSignalStrengthsTimer[strengthBin].isRunningLocked()) {
                    mWifiSignalStrengthsTimer[strengthBin].startRunningLocked(elapsedRealtimeMs);
                }
                mHistoryCur.states2 =
                        (mHistoryCur.states2&~HistoryItem.STATE2_WIFI_SIGNAL_STRENGTH_MASK)
                        | (strengthBin << HistoryItem.STATE2_WIFI_SIGNAL_STRENGTH_SHIFT);
                if (DEBUG_HISTORY) Slog.v(TAG, "Wifi signal strength " + strengthBin + " to: "
                        + Integer.toHexString(mHistoryCur.states2));
                addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            } else {
                stopAllWifiSignalStrengthTimersLocked(-1, elapsedRealtimeMs);
            }
            mWifiSignalStrengthBin = strengthBin;
        }
    }

    int mWifiFullLockNesting = 0;

    @UnsupportedAppUsage
    @GuardedBy("this")
    public void noteFullWifiLockAcquiredLocked(int uid) {
        noteFullWifiLockAcquiredLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteFullWifiLockAcquiredLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        if (mWifiFullLockNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_WIFI_FULL_LOCK_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI full lock on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
        }
        mWifiFullLockNesting++;
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteFullWifiLockAcquiredLocked(elapsedRealtimeMs);
    }

    @UnsupportedAppUsage
    @GuardedBy("this")
    public void noteFullWifiLockReleasedLocked(int uid) {
        noteFullWifiLockReleasedLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteFullWifiLockReleasedLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        mWifiFullLockNesting--;
        if (mWifiFullLockNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_WIFI_FULL_LOCK_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI full lock off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
        }
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteFullWifiLockReleasedLocked(elapsedRealtimeMs);
    }

    int mWifiScanNesting = 0;

    @GuardedBy("this")
    public void noteWifiScanStartedLocked(int uid) {
        noteWifiScanStartedLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWifiScanStartedLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        if (mWifiScanNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_WIFI_SCAN_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI scan started for: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
        }
        mWifiScanNesting++;
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteWifiScanStartedLocked(elapsedRealtimeMs);
    }

    @GuardedBy("this")
    public void noteWifiScanStoppedLocked(int uid) {
        noteWifiScanStoppedLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWifiScanStoppedLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        mWifiScanNesting--;
        if (mWifiScanNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_WIFI_SCAN_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI scan stopped for: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
        }
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteWifiScanStoppedLocked(elapsedRealtimeMs);
    }

    public void noteWifiBatchedScanStartedLocked(int uid, int csph) {
        noteWifiBatchedScanStartedLocked(uid, csph,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    public void noteWifiBatchedScanStartedLocked(int uid, int csph,
            long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteWifiBatchedScanStartedLocked(csph, elapsedRealtimeMs);
    }

    public void noteWifiBatchedScanStoppedLocked(int uid) {
        noteWifiBatchedScanStoppedLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    public void noteWifiBatchedScanStoppedLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteWifiBatchedScanStoppedLocked(elapsedRealtimeMs);
    }

    int mWifiMulticastNesting = 0;

    @GuardedBy("this")
    @UnsupportedAppUsage
    public void noteWifiMulticastEnabledLocked(int uid) {
        noteWifiMulticastEnabledLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWifiMulticastEnabledLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        if (mWifiMulticastNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_WIFI_MULTICAST_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI multicast on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);

            // Start Wifi Multicast overall timer
            if (!mWifiMulticastWakelockTimer.isRunningLocked()) {
                if (DEBUG_HISTORY) Slog.v(TAG, "WiFi Multicast Overall Timer Started");
                mWifiMulticastWakelockTimer.startRunningLocked(elapsedRealtimeMs);
            }
        }
        mWifiMulticastNesting++;
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteWifiMulticastEnabledLocked(elapsedRealtimeMs);
    }

    @UnsupportedAppUsage
    @GuardedBy("this")
    public void noteWifiMulticastDisabledLocked(int uid) {
        noteWifiMulticastDisabledLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWifiMulticastDisabledLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        mWifiMulticastNesting--;
        if (mWifiMulticastNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_WIFI_MULTICAST_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI multicast off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);

            // Stop Wifi Multicast overall timer
            if (mWifiMulticastWakelockTimer.isRunningLocked()) {
                if (DEBUG_HISTORY) Slog.v(TAG, "Multicast Overall Timer Stopped");
                mWifiMulticastWakelockTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }
        getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                .noteWifiMulticastDisabledLocked(elapsedRealtimeMs);
    }

    @GuardedBy("this")
    public void noteFullWifiLockAcquiredFromSourceLocked(WorkSource ws) {
        noteFullWifiLockAcquiredFromSourceLocked(ws,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteFullWifiLockAcquiredFromSourceLocked(WorkSource ws,
            long elapsedRealtimeMs, long uptimeMs) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            final int uid = mapUid(ws.getUid(i));
            noteFullWifiLockAcquiredLocked(uid, elapsedRealtimeMs, uptimeMs);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain workChain = workChains.get(i);
                final int uid = mapUid(workChain.getAttributionUid());
                noteFullWifiLockAcquiredLocked(uid, elapsedRealtimeMs, uptimeMs);
            }
        }
    }

    @GuardedBy("this")
    public void noteFullWifiLockReleasedFromSourceLocked(WorkSource ws) {
        noteFullWifiLockReleasedFromSourceLocked(ws,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteFullWifiLockReleasedFromSourceLocked(WorkSource ws,
            long elapsedRealtimeMs, long uptimeMs) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            final int uid = mapUid(ws.getUid(i));
            noteFullWifiLockReleasedLocked(uid, elapsedRealtimeMs, uptimeMs);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain workChain = workChains.get(i);
                final int uid = mapUid(workChain.getAttributionUid());
                noteFullWifiLockReleasedLocked(uid, elapsedRealtimeMs, uptimeMs);
            }
        }
    }

    @GuardedBy("this")
    public void noteWifiScanStartedFromSourceLocked(WorkSource ws) {
        noteWifiScanStartedFromSourceLocked(ws, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWifiScanStartedFromSourceLocked(WorkSource ws,
            long elapsedRealtimeMs, long uptimeMs) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            final int uid = mapUid(ws.getUid(i));
            noteWifiScanStartedLocked(uid, elapsedRealtimeMs, uptimeMs);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain workChain = workChains.get(i);
                final int uid = mapUid(workChain.getAttributionUid());
                noteWifiScanStartedLocked(uid, elapsedRealtimeMs, uptimeMs);
            }
        }
    }

    @GuardedBy("this")
    public void noteWifiScanStoppedFromSourceLocked(WorkSource ws) {
        noteWifiScanStoppedFromSourceLocked(ws, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWifiScanStoppedFromSourceLocked(WorkSource ws,
            long elapsedRealtimeMs, long uptimeMs) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            final int uid = mapUid(ws.getUid(i));
            noteWifiScanStoppedLocked(uid, elapsedRealtimeMs, uptimeMs);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain workChain = workChains.get(i);
                final int uid = mapUid(workChain.getAttributionUid());
                noteWifiScanStoppedLocked(uid, elapsedRealtimeMs, uptimeMs);
            }
        }
    }

    @GuardedBy("this")
    public void noteWifiBatchedScanStartedFromSourceLocked(WorkSource ws, int csph) {
        noteWifiBatchedScanStartedFromSourceLocked(ws, csph,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWifiBatchedScanStartedFromSourceLocked(WorkSource ws, int csph,
            long elapsedRealtimeMs, long uptimeMs) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            noteWifiBatchedScanStartedLocked(ws.getUid(i), csph, elapsedRealtimeMs, uptimeMs);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                noteWifiBatchedScanStartedLocked(workChains.get(i).getAttributionUid(), csph,
                        elapsedRealtimeMs, uptimeMs);
            }
        }
    }

    @GuardedBy("this")
    public void noteWifiBatchedScanStoppedFromSourceLocked(WorkSource ws) {
        noteWifiBatchedScanStoppedFromSourceLocked(ws,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    @GuardedBy("this")
    public void noteWifiBatchedScanStoppedFromSourceLocked(WorkSource ws,
            long elapsedRealtimeMs, long uptimeMs) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            noteWifiBatchedScanStoppedLocked(ws.getUid(i), elapsedRealtimeMs, uptimeMs);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                noteWifiBatchedScanStoppedLocked(workChains.get(i).getAttributionUid(),
                        elapsedRealtimeMs, uptimeMs);
            }
        }
    }

    private static String[] includeInStringArray(String[] array, String str) {
        if (ArrayUtils.indexOf(array, str) >= 0) {
            return array;
        }
        String[] newArray = new String[array.length+1];
        System.arraycopy(array, 0, newArray, 0, array.length);
        newArray[array.length] = str;
        return newArray;
    }

    private static String[] excludeFromStringArray(String[] array, String str) {
        int index = ArrayUtils.indexOf(array, str);
        if (index >= 0) {
            String[] newArray = new String[array.length-1];
            if (index > 0) {
                System.arraycopy(array, 0, newArray, 0, index);
            }
            if (index < array.length-1) {
                System.arraycopy(array, index+1, newArray, index, array.length-index-1);
            }
            return newArray;
        }
        return array;
    }

    /** @hide */
    public void noteNetworkInterfaceForTransports(String iface, int[] transportTypes) {
        if (TextUtils.isEmpty(iface)) return;
        final int displayTransport = NetworkCapabilitiesUtils.getDisplayTransport(transportTypes);

        synchronized (mModemNetworkLock) {
            if (displayTransport == TRANSPORT_CELLULAR) {
                mModemIfaces = includeInStringArray(mModemIfaces, iface);
                if (DEBUG) Slog.d(TAG, "Note mobile iface " + iface + ": " + mModemIfaces);
            } else {
                mModemIfaces = excludeFromStringArray(mModemIfaces, iface);
                if (DEBUG) Slog.d(TAG, "Note non-mobile iface " + iface + ": " + mModemIfaces);
            }
        }

        synchronized (mWifiNetworkLock) {
            if (displayTransport == TRANSPORT_WIFI) {
                mWifiIfaces = includeInStringArray(mWifiIfaces, iface);
                if (DEBUG) Slog.d(TAG, "Note wifi iface " + iface + ": " + mWifiIfaces);
            } else {
                mWifiIfaces = excludeFromStringArray(mWifiIfaces, iface);
                if (DEBUG) Slog.d(TAG, "Note non-wifi iface " + iface + ": " + mWifiIfaces);
            }
        }
    }

    /**
     * Records timing data related to an incoming Binder call in order to attribute
     * the power consumption to the calling app.
     */
    public void noteBinderCallStats(int workSourceUid, long incrementalCallCount,
            Collection<BinderCallsStats.CallStat> callStats) {
        noteBinderCallStats(workSourceUid, incrementalCallCount, callStats,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    public void noteBinderCallStats(int workSourceUid, long incrementalCallCount,
            Collection<BinderCallsStats.CallStat> callStats,
            long elapsedRealtimeMs, long uptimeMs) {
        synchronized (this) {
            getUidStatsLocked(workSourceUid, elapsedRealtimeMs, uptimeMs)
                    .noteBinderCallStatsLocked(incrementalCallCount, callStats);
        }
    }

    /**
     * Takes note of native IDs of threads taking incoming binder calls. The CPU time
     * of these threads is attributed to the apps making those binder calls.
     */
    public void noteBinderThreadNativeIds(int[] binderThreadNativeTids) {
        mSystemServerCpuThreadReader.setBinderThreadNativeTids(binderThreadNativeTids);
    }

    /**
     * Estimates the proportion of system server CPU activity handling incoming binder calls
     * that can be attributed to each app
     */
    @VisibleForTesting
    public void updateSystemServiceCallStats() {
        // Start off by computing the average duration of recorded binder calls,
        // regardless of which binder or transaction. We will use this as a fallback
        // for calls that were not sampled at all.
        int totalRecordedCallCount = 0;
        long totalRecordedCallTimeMicros = 0;
        for (int i = 0; i < mUidStats.size(); i++) {
            Uid uid = mUidStats.valueAt(i);
            ArraySet<BinderCallStats> binderCallStats = uid.mBinderCallStats;
            for (int j = binderCallStats.size() - 1; j >= 0; j--) {
                BinderCallStats stats = binderCallStats.valueAt(j);
                totalRecordedCallCount += stats.recordedCallCount;
                totalRecordedCallTimeMicros += stats.recordedCpuTimeMicros;
            }
        }

        long totalSystemServiceTimeMicros = 0;

        // For every UID, use recorded durations of sampled binder calls to estimate
        // the total time the system server spent handling requests from this UID.
        for (int i = 0; i < mUidStats.size(); i++) {
            Uid uid = mUidStats.valueAt(i);

            long totalTimeForUidUs = 0;
            int totalCallCountForUid = 0;
            ArraySet<BinderCallStats> binderCallStats = uid.mBinderCallStats;
            for (int j = binderCallStats.size() - 1; j >= 0; j--) {
                BinderCallStats stats = binderCallStats.valueAt(j);
                totalCallCountForUid += stats.callCount;
                if (stats.recordedCallCount > 0) {
                    totalTimeForUidUs +=
                            stats.callCount * stats.recordedCpuTimeMicros / stats.recordedCallCount;
                } else if (totalRecordedCallCount > 0) {
                    totalTimeForUidUs +=
                            stats.callCount * totalRecordedCallTimeMicros / totalRecordedCallCount;
                }
            }

            if (totalCallCountForUid < uid.mBinderCallCount && totalRecordedCallCount > 0) {
                // Estimate remaining calls, which were not tracked because of binder call
                // stats sampling
                totalTimeForUidUs +=
                        (uid.mBinderCallCount - totalCallCountForUid) * totalRecordedCallTimeMicros
                                / totalRecordedCallCount;
            }

            uid.mSystemServiceTimeUs = totalTimeForUidUs;
            totalSystemServiceTimeMicros += totalTimeForUidUs;
        }

        for (int i = 0; i < mUidStats.size(); i++) {
            Uid uid = mUidStats.valueAt(i);
            if (totalSystemServiceTimeMicros > 0) {
                uid.mProportionalSystemServiceUsage =
                        (double) uid.mSystemServiceTimeUs / totalSystemServiceTimeMicros;
            } else {
                uid.mProportionalSystemServiceUsage = 0;
            }
        }
    }

    public String[] getWifiIfaces() {
        synchronized (mWifiNetworkLock) {
            return mWifiIfaces;
        }
    }

    public String[] getMobileIfaces() {
        synchronized (mModemNetworkLock) {
            return mModemIfaces;
        }
    }

    @UnsupportedAppUsage
    @Override public long getScreenOnTime(long elapsedRealtimeUs, int which) {
        return mScreenOnTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override public int getScreenOnCount(int which) {
        return mScreenOnTimer.getCountLocked(which);
    }

    @Override public long getScreenDozeTime(long elapsedRealtimeUs, int which) {
        return mScreenDozeTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override public int getScreenDozeCount(int which) {
        return mScreenDozeTimer.getCountLocked(which);
    }

    @UnsupportedAppUsage
    @Override public long getScreenBrightnessTime(int brightnessBin,
            long elapsedRealtimeUs, int which) {
        return mScreenBrightnessTimer[brightnessBin].getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @Override public Timer getScreenBrightnessTimer(int brightnessBin) {
        return mScreenBrightnessTimer[brightnessBin];
    }

    @Override
    public int getDisplayCount() {
        return mPerDisplayBatteryStats.length;
    }

    @Override
    public long getDisplayScreenOnTime(int display, long elapsedRealtimeUs) {
        return mPerDisplayBatteryStats[display].screenOnTimer.getTotalTimeLocked(elapsedRealtimeUs,
                STATS_SINCE_CHARGED);
    }

    @Override
    public long getDisplayScreenDozeTime(int display, long elapsedRealtimeUs) {
        return mPerDisplayBatteryStats[display].screenDozeTimer.getTotalTimeLocked(
                elapsedRealtimeUs, STATS_SINCE_CHARGED);
    }

    @Override
    public long getDisplayScreenBrightnessTime(int display, int brightnessBin,
            long elapsedRealtimeUs) {
        final DisplayBatteryStats displayStats = mPerDisplayBatteryStats[display];
        return displayStats.screenBrightnessTimers[brightnessBin].getTotalTimeLocked(
                elapsedRealtimeUs, STATS_SINCE_CHARGED);
    }

    @Override public long getInteractiveTime(long elapsedRealtimeUs, int which) {
        return mInteractiveTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override public long getPowerSaveModeEnabledTime(long elapsedRealtimeUs, int which) {
        return mPowerSaveModeEnabledTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override public int getPowerSaveModeEnabledCount(int which) {
        return mPowerSaveModeEnabledTimer.getCountLocked(which);
    }

    @Override public long getDeviceIdleModeTime(int mode, long elapsedRealtimeUs,
            int which) {
        switch (mode) {
            case DEVICE_IDLE_MODE_LIGHT:
                return mDeviceIdleModeLightTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
            case DEVICE_IDLE_MODE_DEEP:
                return mDeviceIdleModeFullTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
        }
        return 0;
    }

    @Override public int getDeviceIdleModeCount(int mode, int which) {
        switch (mode) {
            case DEVICE_IDLE_MODE_LIGHT:
                return mDeviceIdleModeLightTimer.getCountLocked(which);
            case DEVICE_IDLE_MODE_DEEP:
                return mDeviceIdleModeFullTimer.getCountLocked(which);
        }
        return 0;
    }

    @Override public long getLongestDeviceIdleModeTime(int mode) {
        switch (mode) {
            case DEVICE_IDLE_MODE_LIGHT:
                return mLongestLightIdleTimeMs;
            case DEVICE_IDLE_MODE_DEEP:
                return mLongestFullIdleTimeMs;
        }
        return 0;
    }

    @Override public long getDeviceIdlingTime(int mode, long elapsedRealtimeUs, int which) {
        switch (mode) {
            case DEVICE_IDLE_MODE_LIGHT:
                return mDeviceLightIdlingTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
            case DEVICE_IDLE_MODE_DEEP:
                return mDeviceIdlingTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
        }
        return 0;
    }

    @Override public int getDeviceIdlingCount(int mode, int which) {
        switch (mode) {
            case DEVICE_IDLE_MODE_LIGHT:
                return mDeviceLightIdlingTimer.getCountLocked(which);
            case DEVICE_IDLE_MODE_DEEP:
                return mDeviceIdlingTimer.getCountLocked(which);
        }
        return 0;
    }

    @Override public int getNumConnectivityChange(int which) {
        return mNumConnectivityChange;
    }

    @Override public long getGpsSignalQualityTime(int strengthBin,
            long elapsedRealtimeUs, int which) {
        if (strengthBin < 0 || strengthBin >= mGpsSignalQualityTimer.length) {
            return 0;
        }
        return mGpsSignalQualityTimer[strengthBin].getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @Override public long getGpsBatteryDrainMaMs() {
        final double opVolt = mPowerProfile.getAveragePower(
            PowerProfile.POWER_GPS_OPERATING_VOLTAGE) / 1000.0;
        if (opVolt == 0) {
            return 0;
        }
        double energyUsedMaMs = 0.0;
        final int which = STATS_SINCE_CHARGED;
        final long rawRealtimeUs = SystemClock.elapsedRealtime() * 1000;
        for(int i=0; i < mGpsSignalQualityTimer.length; i++) {
            energyUsedMaMs
                    += mPowerProfile.getAveragePower(PowerProfile.POWER_GPS_SIGNAL_QUALITY_BASED, i)
                    * (getGpsSignalQualityTime(i, rawRealtimeUs, which) / 1000);
        }
        return (long) energyUsedMaMs;
    }

    @UnsupportedAppUsage
    @Override public long getPhoneOnTime(long elapsedRealtimeUs, int which) {
        return mPhoneOnTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override public int getPhoneOnCount(int which) {
        return mPhoneOnTimer.getCountLocked(which);
    }

    @UnsupportedAppUsage
    @Override public long getPhoneSignalStrengthTime(int strengthBin,
            long elapsedRealtimeUs, int which) {
        return mPhoneSignalStrengthsTimer[strengthBin].getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @UnsupportedAppUsage
    @Override public long getPhoneSignalScanningTime(
            long elapsedRealtimeUs, int which) {
        return mPhoneSignalScanningTimer.getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @Override public Timer getPhoneSignalScanningTimer() {
        return mPhoneSignalScanningTimer;
    }

    @UnsupportedAppUsage
    @Override public int getPhoneSignalStrengthCount(int strengthBin, int which) {
        return mPhoneSignalStrengthsTimer[strengthBin].getCountLocked(which);
    }

    @Override public Timer getPhoneSignalStrengthTimer(int strengthBin) {
        return mPhoneSignalStrengthsTimer[strengthBin];
    }

    @UnsupportedAppUsage
    @Override public long getPhoneDataConnectionTime(int dataType,
            long elapsedRealtimeUs, int which) {
        return mPhoneDataConnectionsTimer[dataType].getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @UnsupportedAppUsage
    @Override public int getPhoneDataConnectionCount(int dataType, int which) {
        return mPhoneDataConnectionsTimer[dataType].getCountLocked(which);
    }

    @Override public Timer getPhoneDataConnectionTimer(int dataType) {
        return mPhoneDataConnectionsTimer[dataType];
    }

    @Override public long getActiveRadioDurationMs(@RadioAccessTechnology int rat,
            @ServiceState.FrequencyRange int frequencyRange, int signalStrength,
            long elapsedRealtimeMs) {
        final RadioAccessTechnologyBatteryStats stats = mPerRatBatteryStats[rat];
        if (stats == null) return 0L;

        final int freqCount = stats.perStateTimers.length;
        if (frequencyRange < 0 || frequencyRange >= freqCount) return 0L;

        final StopwatchTimer[] strengthTimers = stats.perStateTimers[frequencyRange];
        final int strengthCount = strengthTimers.length;
        if (signalStrength < 0 || signalStrength >= strengthCount) return 0L;

        return stats.perStateTimers[frequencyRange][signalStrength].getTotalTimeLocked(
                elapsedRealtimeMs * 1000, STATS_SINCE_CHARGED) / 1000;
    }

    @Override
    public long getActiveTxRadioDurationMs(@RadioAccessTechnology int rat,
            @ServiceState.FrequencyRange int frequencyRange, int signalStrength,
            long elapsedRealtimeMs) {
        final RadioAccessTechnologyBatteryStats stats = mPerRatBatteryStats[rat];
        if (stats == null) return DURATION_UNAVAILABLE;

        final LongSamplingCounter counter = stats.getTxDurationCounter(frequencyRange,
                signalStrength, false);
        if (counter == null) return DURATION_UNAVAILABLE;

        return counter.getCountLocked(STATS_SINCE_CHARGED);
    }

    @Override
    public long getActiveRxRadioDurationMs(@RadioAccessTechnology int rat,
            @ServiceState.FrequencyRange int frequencyRange, long elapsedRealtimeMs) {
        final RadioAccessTechnologyBatteryStats stats = mPerRatBatteryStats[rat];
        if (stats == null) return DURATION_UNAVAILABLE;

        final LongSamplingCounter counter = stats.getRxDurationCounter(frequencyRange, false);
        if (counter == null) return DURATION_UNAVAILABLE;

        return counter.getCountLocked(STATS_SINCE_CHARGED);
    }

    @UnsupportedAppUsage
    @Override public long getMobileRadioActiveTime(long elapsedRealtimeUs, int which) {
        return mMobileRadioActiveTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override public int getMobileRadioActiveCount(int which) {
        return mMobileRadioActiveTimer.getCountLocked(which);
    }

    @Override public long getMobileRadioActiveAdjustedTime(int which) {
        return mMobileRadioActiveAdjustedTime.getCountLocked(which);
    }

    @Override public long getMobileRadioActiveUnknownTime(int which) {
        return mMobileRadioActiveUnknownTime.getCountLocked(which);
    }

    @Override public int getMobileRadioActiveUnknownCount(int which) {
        return (int)mMobileRadioActiveUnknownCount.getCountLocked(which);
    }

    @Override public long getWifiMulticastWakelockTime(
            long elapsedRealtimeUs, int which) {
        return mWifiMulticastWakelockTimer.getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @Override public int getWifiMulticastWakelockCount(int which) {
        return mWifiMulticastWakelockTimer.getCountLocked(which);
    }

    @UnsupportedAppUsage
    @Override public long getWifiOnTime(long elapsedRealtimeUs, int which) {
        return mWifiOnTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override public long getWifiActiveTime(long elapsedRealtimeUs, int which) {
        return mWifiActiveTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @UnsupportedAppUsage
    @Override public long getGlobalWifiRunningTime(long elapsedRealtimeUs, int which) {
        return mGlobalWifiRunningTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override public long getWifiStateTime(int wifiState,
            long elapsedRealtimeUs, int which) {
        return mWifiStateTimer[wifiState].getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @Override public int getWifiStateCount(int wifiState, int which) {
        return mWifiStateTimer[wifiState].getCountLocked(which);
    }

    @Override public Timer getWifiStateTimer(int wifiState) {
        return mWifiStateTimer[wifiState];
    }

    @Override public long getWifiSupplStateTime(int state,
            long elapsedRealtimeUs, int which) {
        return mWifiSupplStateTimer[state].getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @Override public int getWifiSupplStateCount(int state, int which) {
        return mWifiSupplStateTimer[state].getCountLocked(which);
    }

    @Override public Timer getWifiSupplStateTimer(int state) {
        return mWifiSupplStateTimer[state];
    }

    @Override public long getWifiSignalStrengthTime(int strengthBin,
            long elapsedRealtimeUs, int which) {
        return mWifiSignalStrengthsTimer[strengthBin].getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @Override public int getWifiSignalStrengthCount(int strengthBin, int which) {
        return mWifiSignalStrengthsTimer[strengthBin].getCountLocked(which);
    }

    @Override public Timer getWifiSignalStrengthTimer(int strengthBin) {
        return mWifiSignalStrengthsTimer[strengthBin];
    }

    @Override
    public ControllerActivityCounter getBluetoothControllerActivity() {
        return mBluetoothActivity;
    }

    @Override
    public ControllerActivityCounter getWifiControllerActivity() {
        return mWifiActivity;
    }

    @Override
    public ControllerActivityCounter getModemControllerActivity() {
        return mModemActivity;
    }

    @Override
    public boolean hasBluetoothActivityReporting() {
        return mHasBluetoothReporting;
    }

    @Override
    public boolean hasWifiActivityReporting() {
        return mHasWifiReporting;
    }

    @Override
    public boolean hasModemActivityReporting() {
        return mHasModemReporting;
    }

    @Override
    public long getFlashlightOnTime(long elapsedRealtimeUs, int which) {
        return mFlashlightOnTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override
    public long getFlashlightOnCount(int which) {
        return mFlashlightOnTimer.getCountLocked(which);
    }

    @Override
    public long getCameraOnTime(long elapsedRealtimeUs, int which) {
        return mCameraOnTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override
    public long getBluetoothScanTime(long elapsedRealtimeUs, int which) {
        return mBluetoothScanTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override
    @UnsupportedAppUsage
    public long getNetworkActivityBytes(int type, int which) {
        if (type >= 0 && type < mNetworkByteActivityCounters.length) {
            return mNetworkByteActivityCounters[type].getCountLocked(which);
        } else {
            return 0;
        }
    }

    @Override
    public long getNetworkActivityPackets(int type, int which) {
        if (type >= 0 && type < mNetworkPacketActivityCounters.length) {
            return mNetworkPacketActivityCounters[type].getCountLocked(which);
        } else {
            return 0;
        }
    }

    @GuardedBy("this")
    @Override
    public long getBluetoothMeasuredBatteryConsumptionUC() {
        return getPowerBucketConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_BLUETOOTH);
    }

    @GuardedBy("this")
    @Override
    public long getCpuMeasuredBatteryConsumptionUC() {
        return getPowerBucketConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_CPU);
    }

    @GuardedBy("this")
    @Override
    public long getGnssMeasuredBatteryConsumptionUC() {
        return getPowerBucketConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_GNSS);
    }

    @GuardedBy("this")
    @Override
    public long getMobileRadioMeasuredBatteryConsumptionUC() {
        return getPowerBucketConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_MOBILE_RADIO);
    }

    @GuardedBy("this")
    @Override
    public long getPhoneEnergyConsumptionUC() {
        return getPowerBucketConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_PHONE);
    }

    @GuardedBy("this")
    @Override
    public long getScreenOnMeasuredBatteryConsumptionUC() {
        return getPowerBucketConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_SCREEN_ON);
    }

    @GuardedBy("this")
    @Override
    public long getScreenDozeMeasuredBatteryConsumptionUC() {
        return getPowerBucketConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_SCREEN_DOZE);
    }

    @GuardedBy("this")
    @Override
    public long getWifiMeasuredBatteryConsumptionUC() {
        return getPowerBucketConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_WIFI);
    }

    /**
     * Returns the consumption (in microcoulombs) that the given standard power bucket consumed.
     * Will return {@link #POWER_DATA_UNAVAILABLE} if data is unavailable
     *
     * @param bucket standard power bucket of interest
     * @return charge (in microcoulombs) used for this power bucket
     */
    @GuardedBy("this")
    private long getPowerBucketConsumptionUC(@StandardPowerBucket int bucket) {
        if (mGlobalMeasuredEnergyStats == null) {
            return POWER_DATA_UNAVAILABLE;
        }
        return mGlobalMeasuredEnergyStats.getAccumulatedStandardBucketCharge(bucket);
    }

    @GuardedBy("this")
    @Override
    public @Nullable long[] getCustomConsumerMeasuredBatteryConsumptionUC() {
        if (mGlobalMeasuredEnergyStats == null) {
            return null;
        }
        return mGlobalMeasuredEnergyStats.getAccumulatedCustomBucketCharges();
    }

    /**
     * Returns the names of custom power components.
     */
    @GuardedBy("this")
    @Override
    public @NonNull String[] getCustomEnergyConsumerNames() {
        if (mMeasuredEnergyStatsConfig == null) {
            return new String[0];
        }
        final String[] names = mMeasuredEnergyStatsConfig.getCustomBucketNames();
        for (int i = 0; i < names.length; i++) {
            if (TextUtils.isEmpty(names[i])) {
                names[i] = "CUSTOM_" + BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + i;
            }
        }
        return names;
    }

    @GuardedBy("this")
    @Override public long getStartClockTime() {
        final long currentTimeMs = mClock.currentTimeMillis();
        if ((currentTimeMs > MILLISECONDS_IN_YEAR
                && mStartClockTimeMs < (currentTimeMs - MILLISECONDS_IN_YEAR))
                || (mStartClockTimeMs > currentTimeMs)) {
            // If the start clock time has changed by more than a year, then presumably
            // the previous time was completely bogus.  So we are going to figure out a
            // new time based on how much time has elapsed since we started counting.
            recordCurrentTimeChangeLocked(currentTimeMs, mClock.elapsedRealtime(),
                    mClock.uptimeMillis());
            return currentTimeMs - (mClock.elapsedRealtime() - (mRealtimeStartUs / 1000));
        }
        return mStartClockTimeMs;
    }

    @Override public String getStartPlatformVersion() {
        return mStartPlatformVersion;
    }

    @Override public String getEndPlatformVersion() {
        return mEndPlatformVersion;
    }

    @Override public int getParcelVersion() {
        return VERSION;
    }

    @Override public boolean getIsOnBattery() {
        return mOnBattery;
    }

    @Override public long getStatsStartRealtime() {
        return mRealtimeStartUs;
    }

    @UnsupportedAppUsage
    @Override public SparseArray<? extends BatteryStats.Uid> getUidStats() {
        return mUidStats;
    }

    private static <T extends TimeBaseObs> boolean resetIfNotNull(T t, boolean detachIfReset,
            long elapsedRealtimeUs) {
        if (t != null) {
            return t.reset(detachIfReset, elapsedRealtimeUs);
        }
        return true;
    }

    private static <T extends TimeBaseObs> boolean resetIfNotNull(T[] t, boolean detachIfReset,
            long elapsedRealtimeUs) {
        if (t != null) {
            boolean ret = true;
            for (int i = 0; i < t.length; i++) {
                ret &= resetIfNotNull(t[i], detachIfReset, elapsedRealtimeUs);
            }
            return ret;
        }
        return true;
    }

    private static <T extends TimeBaseObs> boolean resetIfNotNull(T[][] t, boolean detachIfReset,
            long elapsedRealtimeUs) {
        if (t != null) {
            boolean ret = true;
            for (int i = 0; i < t.length; i++) {
                ret &= resetIfNotNull(t[i], detachIfReset, elapsedRealtimeUs);
            }
            return ret;
        }
        return true;
    }

    private static boolean resetIfNotNull(ControllerActivityCounterImpl counter,
            boolean detachIfReset, long elapsedRealtimeUs) {
        if (counter != null) {
            counter.reset(detachIfReset, elapsedRealtimeUs);
        }
        return true;
    }

    private static <T extends TimeBaseObs> void detachIfNotNull(T t) {
        if (t != null) {
            t.detach();
        }
    }

    private static <T extends TimeBaseObs> void detachIfNotNull(T[] t) {
        if (t != null) {
            for (int i = 0; i < t.length; i++) {
                detachIfNotNull(t[i]);
            }
        }
    }

    private static <T extends TimeBaseObs> void detachIfNotNull(T[][] t) {
        if (t != null) {
            for (int i = 0; i < t.length; i++) {
                detachIfNotNull(t[i]);
            }
        }
    }

    private static void detachIfNotNull(ControllerActivityCounterImpl counter) {
        if (counter != null) {
            counter.detach();
        }
    }

    /**
     * Accumulates stats for a specific binder transaction.
     */
    @VisibleForTesting
    protected static class BinderCallStats {
        static final Comparator<BinderCallStats> COMPARATOR =
                Comparator.comparing(BinderCallStats::getClassName)
                        .thenComparing(BinderCallStats::getMethodName);

        public Class<? extends Binder> binderClass;
        public int transactionCode;
        public String methodName;

        public long callCount;
        public long recordedCallCount;
        public long recordedCpuTimeMicros;


        @Override
        public int hashCode() {
            return binderClass.hashCode() * 31 + transactionCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof BinderCallStats)) {
                return false;
            }
            BinderCallStats bcsk = (BinderCallStats) obj;
            return binderClass.equals(bcsk.binderClass) && transactionCode == bcsk.transactionCode;
        }

        public String getClassName() {
            return binderClass.getName();
        }

        public String getMethodName() {
            return methodName;
        }

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public void ensureMethodName(BinderTransactionNameResolver resolver) {
            if (methodName == null) {
                methodName = resolver.getMethodName(binderClass, transactionCode);
            }
        }

        @Override
        public String toString() {
            return "BinderCallStats{"
                    + binderClass
                    + " transaction=" + transactionCode
                    + " callCount=" + callCount
                    + " recordedCallCount=" + recordedCallCount
                    + " recorderCpuTimeMicros=" + recordedCpuTimeMicros
                    + "}";
        }
    }

    /**
     * The statistics associated with a particular uid.
     */
    public static class Uid extends BatteryStats.Uid {
        /**
         * BatteryStatsImpl that we are associated with.
         */
        protected BatteryStatsImpl mBsi;

        final int mUid;

        /** TimeBase for when uid is in background and device is on battery. */
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public final TimeBase mOnBatteryBackgroundTimeBase;
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public final TimeBase mOnBatteryScreenOffBackgroundTimeBase;

        boolean mWifiRunning;
        StopwatchTimer mWifiRunningTimer;

        boolean mFullWifiLockOut;
        StopwatchTimer mFullWifiLockTimer;

        boolean mWifiScanStarted;
        DualTimer mWifiScanTimer;

        static final int NO_BATCHED_SCAN_STARTED = -1;
        int mWifiBatchedScanBinStarted = NO_BATCHED_SCAN_STARTED;
        StopwatchTimer[] mWifiBatchedScanTimer;

        int mWifiMulticastWakelockCount;
        StopwatchTimer mWifiMulticastTimer;

        StopwatchTimer mAudioTurnedOnTimer;
        StopwatchTimer mVideoTurnedOnTimer;
        StopwatchTimer mFlashlightTurnedOnTimer;
        StopwatchTimer mCameraTurnedOnTimer;
        StopwatchTimer mForegroundActivityTimer;
        StopwatchTimer mForegroundServiceTimer;
        /** Total time spent by the uid holding any partial wakelocks. */
        DualTimer mAggregatedPartialWakelockTimer;
        DualTimer mBluetoothScanTimer;
        DualTimer mBluetoothUnoptimizedScanTimer;
        Counter mBluetoothScanResultCounter;
        Counter mBluetoothScanResultBgCounter;

        int mProcessState = Uid.PROCESS_STATE_NONEXISTENT;
        StopwatchTimer[] mProcessStateTimer;

        boolean mInForegroundService = false;

        BatchTimer mVibratorOnTimer;

        Counter[] mUserActivityCounters;

        LongSamplingCounter[] mNetworkByteActivityCounters;
        LongSamplingCounter[] mNetworkPacketActivityCounters;
        TimeMultiStateCounter mMobileRadioActiveTime;
        LongSamplingCounter mMobileRadioActiveCount;

        /**
         * How many times this UID woke up the Application Processor due to a Mobile radio packet.
         */
        private LongSamplingCounter mMobileRadioApWakeupCount;

        /**
         * How many times this UID woke up the Application Processor due to a Wifi packet.
         */
        private LongSamplingCounter mWifiRadioApWakeupCount;

        /**
         * The amount of time this uid has kept the WiFi controller in idle, tx, and rx mode.
         * Can be null if the UID has had no such activity.
         */
        private ControllerActivityCounterImpl mWifiControllerActivity;

        /**
         * The amount of time this uid has kept the Bluetooth controller in idle, tx, and rx mode.
         * Can be null if the UID has had no such activity.
         */
        private ControllerActivityCounterImpl mBluetoothControllerActivity;

        /**
         * The amount of time this uid has kept the Modem controller in idle, tx, and rx mode.
         * Can be null if the UID has had no such activity.
         */
        private ControllerActivityCounterImpl mModemControllerActivity;

        /**
         * The CPU times we had at the last history details update.
         */
        long mLastStepUserTimeMs;
        long mLastStepSystemTimeMs;
        long mCurStepUserTimeMs;
        long mCurStepSystemTimeMs;

        LongSamplingCounter mUserCpuTime;
        LongSamplingCounter mSystemCpuTime;
        LongSamplingCounter[][] mCpuClusterSpeedTimesUs;
        TimeMultiStateCounter mCpuActiveTimeMs;

        LongSamplingCounterArray mCpuFreqTimeMs;
        LongSamplingCounterArray mScreenOffCpuFreqTimeMs;
        LongSamplingCounterArray mCpuClusterTimesMs;

        TimeInFreqMultiStateCounter mProcStateTimeMs;
        TimeInFreqMultiStateCounter mProcStateScreenOffTimeMs;

        SparseArray<ChildUid> mChildUids;

        /**
         * The statistics we have collected for this uid's wake locks.
         */
        final OverflowArrayMap<Wakelock> mWakelockStats;

        /**
         * The statistics we have collected for this uid's syncs.
         */
        final OverflowArrayMap<DualTimer> mSyncStats;

        /**
         * The statistics we have collected for this uid's jobs.
         */
        final OverflowArrayMap<DualTimer> mJobStats;

        /**
         * Count of the jobs that have completed and the reasons why they completed.
         */
        final ArrayMap<String, SparseIntArray> mJobCompletions = new ArrayMap<>();

        /**
         * Count of app launch events that had associated deferred job counts or info about
         * last time a job was run.
         */
        Counter mJobsDeferredEventCount;

        /**
         * Count of deferred jobs that were pending when the app was launched or brought to
         * the foreground through a user interaction.
         */
        Counter mJobsDeferredCount;

        /**
         * Sum of time since the last time a job was run for this app before it was launched.
         */
        LongSamplingCounter mJobsFreshnessTimeMs;

        /**
         * Array of counts of instances where the time since the last job was run for the app
         * fell within one of the thresholds in {@link #JOB_FRESHNESS_BUCKETS}.
         */
        final Counter[] mJobsFreshnessBuckets;

        /**
         * The statistics we have collected for this uid's sensor activations.
         */
        final SparseArray<Sensor> mSensorStats = new SparseArray<>();

        /**
         * The statistics we have collected for this uid's processes.
         */
        final ArrayMap<String, Proc> mProcessStats = new ArrayMap<>();

        /**
         * The statistics we have collected for this uid's processes.
         */
        final ArrayMap<String, Pkg> mPackageStats = new ArrayMap<>();

        /**
         * The transient wake stats we have collected for this uid's pids.
         */
        final SparseArray<Pid> mPids = new SparseArray<>();

        /**
         * Grand total of system server binder calls made by this uid.
         */
        private long mBinderCallCount;

        /**
         * Detailed information about system server binder calls made by this uid.
         */
        private final ArraySet<BinderCallStats> mBinderCallStats = new ArraySet<>();

        /**
         * Measured charge consumption by this uid while on battery.
         * Its '<b>custom</b> power buckets' correspond to the
         * {@link android.hardware.power.stats.EnergyConsumer.ordinal}s of (custom) energy consumer
         * type {@link android.hardware.power.stats.EnergyConsumerType#OTHER}).
         *
         * Will be null if energy consumer data is completely unavailable (in which case
         * {@link #mGlobalMeasuredEnergyStats} will also be null) or if the power usage by this uid
         * is 0 for every bucket.
         */
        private MeasuredEnergyStats mUidMeasuredEnergyStats;

        /**
         * Estimated total time spent by the system server handling requests from this uid.
         */
        private long mSystemServiceTimeUs;

        /**
         * Estimated proportion of system server binder call CPU cost for this uid.
         */
        private double mProportionalSystemServiceUsage;

        public Uid(BatteryStatsImpl bsi, int uid) {
            this(bsi, uid, bsi.mClock.elapsedRealtime(), bsi.mClock.uptimeMillis());
        }

        public Uid(BatteryStatsImpl bsi, int uid, long elapsedRealtimeMs, long uptimeMs) {
            mBsi = bsi;
            mUid = uid;

            /* Observer list of TimeBase object in Uid is short */
            mOnBatteryBackgroundTimeBase = new TimeBase(false);
            mOnBatteryBackgroundTimeBase.init(uptimeMs * 1000, elapsedRealtimeMs * 1000);
            /* Observer list of TimeBase object in Uid is short */
            mOnBatteryScreenOffBackgroundTimeBase = new TimeBase(false);
            mOnBatteryScreenOffBackgroundTimeBase.init(uptimeMs * 1000, elapsedRealtimeMs * 1000);

            mUserCpuTime = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
            mSystemCpuTime = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
            mCpuClusterTimesMs = new LongSamplingCounterArray(mBsi.mOnBatteryTimeBase);

            mWakelockStats = mBsi.new OverflowArrayMap<Wakelock>(uid) {
                @Override public Wakelock instantiateObject() {
                    return new Wakelock(mBsi, Uid.this);
                }
            };
            mSyncStats = mBsi.new OverflowArrayMap<DualTimer>(uid) {
                @Override public DualTimer instantiateObject() {
                    return new DualTimer(mBsi.mClock, Uid.this, SYNC, null,
                            mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase);
                }
            };
            mJobStats = mBsi.new OverflowArrayMap<DualTimer>(uid) {
                @Override public DualTimer instantiateObject() {
                    return new DualTimer(mBsi.mClock, Uid.this, JOB, null,
                            mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase);
                }
            };

            mWifiRunningTimer = new StopwatchTimer(mBsi.mClock, this, WIFI_RUNNING,
                    mBsi.mWifiRunningTimers, mBsi.mOnBatteryTimeBase);
            mFullWifiLockTimer = new StopwatchTimer(mBsi.mClock, this, FULL_WIFI_LOCK,
                    mBsi.mFullWifiLockTimers, mBsi.mOnBatteryTimeBase);
            mWifiScanTimer = new DualTimer(mBsi.mClock, this, WIFI_SCAN,
                    mBsi.mWifiScanTimers, mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase);
            mWifiBatchedScanTimer = new StopwatchTimer[NUM_WIFI_BATCHED_SCAN_BINS];
            mWifiMulticastTimer = new StopwatchTimer(mBsi.mClock, this, WIFI_MULTICAST_ENABLED,
                    mBsi.mWifiMulticastTimers, mBsi.mOnBatteryTimeBase);
            mProcessStateTimer = new StopwatchTimer[NUM_PROCESS_STATE];
            mJobsDeferredEventCount = new Counter(mBsi.mOnBatteryTimeBase);
            mJobsDeferredCount = new Counter(mBsi.mOnBatteryTimeBase);
            mJobsFreshnessTimeMs = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
            mJobsFreshnessBuckets = new Counter[JOB_FRESHNESS_BUCKETS.length];
        }

        @GuardedBy("mBsi")
        @VisibleForTesting
        public void setProcessStateForTest(int procState, long elapsedTimeMs) {
            mProcessState = procState;
            getProcStateTimeCounter(elapsedTimeMs).setState(procState, elapsedTimeMs);
            getProcStateScreenOffTimeCounter(elapsedTimeMs).setState(procState, elapsedTimeMs);
            final int batteryConsumerProcessState =
                    mapUidProcessStateToBatteryConsumerProcessState(procState);
            getCpuActiveTimeCounter().setState(batteryConsumerProcessState, elapsedTimeMs);
            getMobileRadioActiveTimeCounter().setState(batteryConsumerProcessState, elapsedTimeMs);
            final ControllerActivityCounterImpl wifiControllerActivity =
                    getWifiControllerActivity();
            if (wifiControllerActivity != null) {
                wifiControllerActivity.setState(batteryConsumerProcessState, elapsedTimeMs);
            }
            final ControllerActivityCounterImpl bluetoothControllerActivity =
                    getBluetoothControllerActivity();
            if (bluetoothControllerActivity != null) {
                bluetoothControllerActivity.setState(batteryConsumerProcessState, elapsedTimeMs);
            }
            final MeasuredEnergyStats energyStats =
                    getOrCreateMeasuredEnergyStatsIfSupportedLocked();
            if (energyStats != null) {
                energyStats.setState(batteryConsumerProcessState, elapsedTimeMs);
            }
        }

        @Override
        public long[] getCpuFreqTimes(int which) {
            return nullIfAllZeros(mCpuFreqTimeMs, which);
        }

        @Override
        public long[] getScreenOffCpuFreqTimes(int which) {
            return nullIfAllZeros(mScreenOffCpuFreqTimeMs, which);
        }

        private TimeMultiStateCounter getCpuActiveTimeCounter() {
            if (mCpuActiveTimeMs == null) {
                final long timestampMs = mBsi.mClock.elapsedRealtime();
                mCpuActiveTimeMs = new TimeMultiStateCounter(mBsi.mOnBatteryTimeBase,
                        BatteryConsumer.PROCESS_STATE_COUNT, timestampMs);
                mCpuActiveTimeMs.setState(
                        mapUidProcessStateToBatteryConsumerProcessState(mProcessState),
                        timestampMs);
            }
            return mCpuActiveTimeMs;
        }

        @Override
        public long getCpuActiveTime() {
            if (mCpuActiveTimeMs == null) {
                return 0;
            }

            long activeTime = 0;
            for (int procState = 0; procState < BatteryConsumer.PROCESS_STATE_COUNT; procState++) {
                activeTime += mCpuActiveTimeMs.getCountForProcessState(procState);
            }
            return activeTime;
        }

        @Override
        public long getCpuActiveTime(int procState) {
            if (mCpuActiveTimeMs == null
                    || procState < 0 || procState >= BatteryConsumer.PROCESS_STATE_COUNT) {
                return 0;
            }

            return mCpuActiveTimeMs.getCountForProcessState(procState);
        }

        @Override
        public long[] getCpuClusterTimes() {
            return nullIfAllZeros(mCpuClusterTimesMs, STATS_SINCE_CHARGED);
        }

        @GuardedBy("mBsi")
        @Override
        public boolean getCpuFreqTimes(long[] timesInFreqMs, int procState) {
            if (procState < 0 || procState >= NUM_PROCESS_STATE) {
                return false;
            }
            if (mProcStateTimeMs == null) {
                return false;
            }
            if (!mBsi.mPerProcStateCpuTimesAvailable) {
                mProcStateTimeMs = null;
                return false;
            }
            return mProcStateTimeMs.getCountsLocked(timesInFreqMs, procState);
        }

        @GuardedBy("mBsi")
        @Override
        public boolean getScreenOffCpuFreqTimes(long[] timesInFreqMs, int procState) {
            if (procState < 0 || procState >= NUM_PROCESS_STATE) {
                return false;
            }
            if (mProcStateScreenOffTimeMs == null) {
                return false;
            }
            if (!mBsi.mPerProcStateCpuTimesAvailable) {
                mProcStateScreenOffTimeMs = null;
                return false;
            }
            return mProcStateScreenOffTimeMs.getCountsLocked(timesInFreqMs, procState);
        }

        public long getBinderCallCount() {
            return mBinderCallCount;
        }

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
        public ArraySet<BinderCallStats> getBinderCallStats() {
            return mBinderCallStats;
        }

        @Override
        public  double getProportionalSystemServiceUsage() {
            return mProportionalSystemServiceUsage;
        }

        @GuardedBy("mBsi")
        public void addIsolatedUid(int isolatedUid) {
            if (mChildUids == null) {
                mChildUids = new SparseArray<>();
            } else if (mChildUids.indexOfKey(isolatedUid) >= 0) {
                return;
            }
            mChildUids.put(isolatedUid, new ChildUid());
        }

        public void removeIsolatedUid(int isolatedUid) {
            final int idx = mChildUids == null ? -1 : mChildUids.indexOfKey(isolatedUid);
            if (idx < 0) {
                return;
            }
            mChildUids.remove(idx);
        }

        @GuardedBy("mBsi")
        ChildUid getChildUid(int childUid) {
            return mChildUids == null ? null : mChildUids.get(childUid);
        }

        private long[] nullIfAllZeros(LongSamplingCounterArray cpuTimesMs, int which) {
            if (cpuTimesMs == null) {
                return null;
            }
            final long[] counts = cpuTimesMs.getCountsLocked(which);
            if (counts == null) {
                return null;
            }
            // Return counts only if at least one of the elements is non-zero.
            for (int i = counts.length - 1; i >= 0; --i) {
                if (counts[i] != 0) {
                    return counts;
                }
            }
            return null;
        }

        @GuardedBy("mBsi")
        private void ensureMultiStateCounters(long timestampMs) {
            if (mProcStateTimeMs != null) {
                return;
            }

            mProcStateTimeMs =
                    new TimeInFreqMultiStateCounter(mBsi.mOnBatteryTimeBase,
                            PROC_STATE_TIME_COUNTER_STATE_COUNT, mBsi.getCpuFreqCount(),
                            timestampMs);
            mProcStateScreenOffTimeMs =
                    new TimeInFreqMultiStateCounter(mBsi.mOnBatteryScreenOffTimeBase,
                            PROC_STATE_TIME_COUNTER_STATE_COUNT, mBsi.getCpuFreqCount(),
                            timestampMs);
        }

        @GuardedBy("mBsi")
        private TimeInFreqMultiStateCounter getProcStateTimeCounter(long timestampMs) {
            ensureMultiStateCounters(timestampMs);
            return mProcStateTimeMs;
        }

        @GuardedBy("mBsi")
        private TimeInFreqMultiStateCounter getProcStateScreenOffTimeCounter(long timestampMs) {
            ensureMultiStateCounters(timestampMs);
            return mProcStateScreenOffTimeMs;
        }

        @Override
        public Timer getAggregatedPartialWakelockTimer() {
            return mAggregatedPartialWakelockTimer;
        }

        @Override
        @UnsupportedAppUsage
        public ArrayMap<String, ? extends BatteryStats.Uid.Wakelock> getWakelockStats() {
            return mWakelockStats.getMap();
        }

        @Override
        public Timer getMulticastWakelockStats() {
            return mWifiMulticastTimer;
        }

        @Override
        public ArrayMap<String, ? extends BatteryStats.Timer> getSyncStats() {
            return mSyncStats.getMap();
        }

        @Override
        public ArrayMap<String, ? extends BatteryStats.Timer> getJobStats() {
            return mJobStats.getMap();
        }

        @Override
        public ArrayMap<String, SparseIntArray> getJobCompletionStats() {
            return mJobCompletions;
        }

        @Override
        @UnsupportedAppUsage
        public SparseArray<? extends BatteryStats.Uid.Sensor> getSensorStats() {
            return mSensorStats;
        }

        @Override
        @UnsupportedAppUsage
        public ArrayMap<String, ? extends BatteryStats.Uid.Proc> getProcessStats() {
            return mProcessStats;
        }

        @Override
        public ArrayMap<String, ? extends BatteryStats.Uid.Pkg> getPackageStats() {
            return mPackageStats;
        }

        @Override
        @UnsupportedAppUsage
        public int getUid() {
            return mUid;
        }

        @Override
        public void noteWifiRunningLocked(long elapsedRealtimeMs) {
            if (!mWifiRunning) {
                mWifiRunning = true;
                if (mWifiRunningTimer == null) {
                    mWifiRunningTimer = new StopwatchTimer(mBsi.mClock, Uid.this, WIFI_RUNNING,
                            mBsi.mWifiRunningTimers, mBsi.mOnBatteryTimeBase);
                }
                mWifiRunningTimer.startRunningLocked(elapsedRealtimeMs);
            }
        }

        @Override
        public void noteWifiStoppedLocked(long elapsedRealtimeMs) {
            if (mWifiRunning) {
                mWifiRunning = false;
                mWifiRunningTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        @Override
        public void noteFullWifiLockAcquiredLocked(long elapsedRealtimeMs) {
            if (!mFullWifiLockOut) {
                mFullWifiLockOut = true;
                if (mFullWifiLockTimer == null) {
                    mFullWifiLockTimer = new StopwatchTimer(mBsi.mClock, Uid.this, FULL_WIFI_LOCK,
                            mBsi.mFullWifiLockTimers, mBsi.mOnBatteryTimeBase);
                }
                mFullWifiLockTimer.startRunningLocked(elapsedRealtimeMs);
            }
        }

        @Override
        public void noteFullWifiLockReleasedLocked(long elapsedRealtimeMs) {
            if (mFullWifiLockOut) {
                mFullWifiLockOut = false;
                mFullWifiLockTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        @Override
        public void noteWifiScanStartedLocked(long elapsedRealtimeMs) {
            if (!mWifiScanStarted) {
                mWifiScanStarted = true;
                if (mWifiScanTimer == null) {
                    mWifiScanTimer = new DualTimer(mBsi.mClock, Uid.this, WIFI_SCAN,
                            mBsi.mWifiScanTimers, mBsi.mOnBatteryTimeBase,
                            mOnBatteryBackgroundTimeBase);
                }
                mWifiScanTimer.startRunningLocked(elapsedRealtimeMs);
            }
        }

        @Override
        public void noteWifiScanStoppedLocked(long elapsedRealtimeMs) {
            if (mWifiScanStarted) {
                mWifiScanStarted = false;
                mWifiScanTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        @Override
        public void noteWifiBatchedScanStartedLocked(int csph, long elapsedRealtimeMs) {
            int bin = 0;
            while (csph > 8 && bin < NUM_WIFI_BATCHED_SCAN_BINS-1) {
                csph = csph >> 3;
                bin++;
            }

            if (mWifiBatchedScanBinStarted == bin) return;

            if (mWifiBatchedScanBinStarted != NO_BATCHED_SCAN_STARTED) {
                mWifiBatchedScanTimer[mWifiBatchedScanBinStarted].
                        stopRunningLocked(elapsedRealtimeMs);
            }
            mWifiBatchedScanBinStarted = bin;
            if (mWifiBatchedScanTimer[bin] == null) {
                makeWifiBatchedScanBin(bin, null);
            }
            mWifiBatchedScanTimer[bin].startRunningLocked(elapsedRealtimeMs);
        }

        @Override
        public void noteWifiBatchedScanStoppedLocked(long elapsedRealtimeMs) {
            if (mWifiBatchedScanBinStarted != NO_BATCHED_SCAN_STARTED) {
                mWifiBatchedScanTimer[mWifiBatchedScanBinStarted].
                        stopRunningLocked(elapsedRealtimeMs);
                mWifiBatchedScanBinStarted = NO_BATCHED_SCAN_STARTED;
            }
        }

        @Override
        public void noteWifiMulticastEnabledLocked(long elapsedRealtimeMs) {
            if (mWifiMulticastWakelockCount == 0) {
                if (mWifiMulticastTimer == null) {
                    mWifiMulticastTimer = new StopwatchTimer(mBsi.mClock, Uid.this,
                            WIFI_MULTICAST_ENABLED, mBsi.mWifiMulticastTimers, mBsi.mOnBatteryTimeBase);
                }
                mWifiMulticastTimer.startRunningLocked(elapsedRealtimeMs);
            }
            mWifiMulticastWakelockCount++;
        }

        @Override
        public void noteWifiMulticastDisabledLocked(long elapsedRealtimeMs) {
            if (mWifiMulticastWakelockCount == 0) {
                return;
            }

            mWifiMulticastWakelockCount--;
            if (mWifiMulticastWakelockCount == 0) {
                mWifiMulticastTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        @Override
        public ControllerActivityCounterImpl getWifiControllerActivity() {
            return mWifiControllerActivity;
        }

        @Override
        public ControllerActivityCounterImpl getBluetoothControllerActivity() {
            return mBluetoothControllerActivity;
        }

        @Override
        public ControllerActivityCounter getModemControllerActivity() {
            return mModemControllerActivity;
        }

        public ControllerActivityCounterImpl getOrCreateWifiControllerActivityLocked() {
            if (mWifiControllerActivity == null) {
                mWifiControllerActivity = new ControllerActivityCounterImpl(mBsi.mClock,
                        mBsi.mOnBatteryTimeBase, NUM_WIFI_TX_LEVELS);
            }
            return mWifiControllerActivity;
        }

        public ControllerActivityCounterImpl getOrCreateBluetoothControllerActivityLocked() {
            if (mBluetoothControllerActivity == null) {
                mBluetoothControllerActivity = new ControllerActivityCounterImpl(mBsi.mClock,
                        mBsi.mOnBatteryTimeBase, NUM_BT_TX_LEVELS);
            }
            return mBluetoothControllerActivity;
        }

        public ControllerActivityCounterImpl getOrCreateModemControllerActivityLocked() {
            if (mModemControllerActivity == null) {
                mModemControllerActivity = new ControllerActivityCounterImpl(mBsi.mClock,
                        mBsi.mOnBatteryTimeBase, ModemActivityInfo.getNumTxPowerLevels());
            }
            return mModemControllerActivity;
        }

        @GuardedBy("mBsi")
        private MeasuredEnergyStats getOrCreateMeasuredEnergyStatsLocked() {
            if (mUidMeasuredEnergyStats == null) {
                mUidMeasuredEnergyStats = new MeasuredEnergyStats(mBsi.mMeasuredEnergyStatsConfig);
            }
            return mUidMeasuredEnergyStats;
        }

        @GuardedBy("mBsi")
        private MeasuredEnergyStats getOrCreateMeasuredEnergyStatsIfSupportedLocked() {
            if (mUidMeasuredEnergyStats == null && mBsi.mMeasuredEnergyStatsConfig != null) {
                mUidMeasuredEnergyStats = new MeasuredEnergyStats(mBsi.mMeasuredEnergyStatsConfig);
            }
            return mUidMeasuredEnergyStats;
        }

        /** Adds the given charge to the given standard power bucket for this uid. */
        @GuardedBy("mBsi")
        private void addChargeToStandardBucketLocked(long chargeDeltaUC,
                @StandardPowerBucket int powerBucket, long timestampMs) {
            final MeasuredEnergyStats measuredEnergyStats =
                    getOrCreateMeasuredEnergyStatsLocked();
            measuredEnergyStats.updateStandardBucket(powerBucket, chargeDeltaUC, timestampMs);
        }

        /** Adds the given charge to the given custom power bucket for this uid. */
        @GuardedBy("mBsi")
        private void addChargeToCustomBucketLocked(long chargeDeltaUC, int powerBucket) {
            getOrCreateMeasuredEnergyStatsLocked().updateCustomBucket(powerBucket, chargeDeltaUC,
                    mBsi.mClock.elapsedRealtime());
        }

        /**
         * Returns the battery consumption (in microcoulomb) of this uid for a standard power bucket
         * of interest.
         * @param bucket standard power bucket of interest
         * @return consumption (in microcolombs) used by this uid for this power bucket
         */
        @GuardedBy("mBsi")
        public long getMeasuredBatteryConsumptionUC(@StandardPowerBucket int bucket) {
            if (mBsi.mGlobalMeasuredEnergyStats == null
                    || !mBsi.mGlobalMeasuredEnergyStats.isStandardBucketSupported(bucket)) {
                return POWER_DATA_UNAVAILABLE;
            }
            if (mUidMeasuredEnergyStats == null) {
                return 0L; // It is supported, but was never filled, so it must be 0
            }
            return mUidMeasuredEnergyStats.getAccumulatedStandardBucketCharge(bucket);
        }

        /**
         * Returns the battery consumption (in microcoulombs) of this uid for a standard power
         * bucket and a process state, such as Uid.PROCESS_STATE_TOP.
         */
        @GuardedBy("mBsi")
        public long getMeasuredBatteryConsumptionUC(@StandardPowerBucket int bucket,
                int processState) {
            if (mBsi.mGlobalMeasuredEnergyStats == null
                    || !mBsi.mGlobalMeasuredEnergyStats.isStandardBucketSupported(bucket)) {
                return POWER_DATA_UNAVAILABLE;
            }
            if (mUidMeasuredEnergyStats == null) {
                return 0L; // It is supported, but was never filled, so it must be 0
            }
            return mUidMeasuredEnergyStats.getAccumulatedStandardBucketCharge(bucket, processState);
        }

        @GuardedBy("mBsi")
        @Override
        public long[] getCustomConsumerMeasuredBatteryConsumptionUC() {
            if (mBsi.mGlobalMeasuredEnergyStats == null) {
                return null;
            }
            if (mUidMeasuredEnergyStats == null) {
                // Custom buckets may exist. But all values for this uid are 0 so we report all 0s.
                return new long[mBsi.mGlobalMeasuredEnergyStats.getNumberCustomPowerBuckets()];
            }
            return mUidMeasuredEnergyStats.getAccumulatedCustomBucketCharges();
        }

        @GuardedBy("mBsi")
        @Override
        public long getBluetoothMeasuredBatteryConsumptionUC() {
            return getMeasuredBatteryConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_BLUETOOTH);
        }

        @GuardedBy("mBsi")
        @Override
        public long getBluetoothMeasuredBatteryConsumptionUC(
                @BatteryConsumer.ProcessState int processState) {
            return getMeasuredBatteryConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_BLUETOOTH,
                    processState);
        }

        @GuardedBy("mBsi")
        @Override
        public long getCpuMeasuredBatteryConsumptionUC() {
            return getMeasuredBatteryConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_CPU);
        }

        @GuardedBy("mBsi")
        @Override
        public long getCpuMeasuredBatteryConsumptionUC(
                @BatteryConsumer.ProcessState int processState) {
            return getMeasuredBatteryConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_CPU,
                    processState);
        }

        @GuardedBy("mBsi")
        @Override
        public long getGnssMeasuredBatteryConsumptionUC() {
            return getMeasuredBatteryConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_GNSS);
        }

        @GuardedBy("mBsi")
        @Override
        public long getMobileRadioMeasuredBatteryConsumptionUC() {
            return getMeasuredBatteryConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_MOBILE_RADIO);
        }

        @GuardedBy("mBsi")
        @Override
        public long getMobileRadioMeasuredBatteryConsumptionUC(int processState) {
            return getMeasuredBatteryConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_MOBILE_RADIO,
                    processState);
        }

        @GuardedBy("mBsi")
        @Override
        public long getScreenOnMeasuredBatteryConsumptionUC() {
            return getMeasuredBatteryConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_SCREEN_ON);
        }

        @GuardedBy("mBsi")
        @Override
        public long getWifiMeasuredBatteryConsumptionUC() {
            return getMeasuredBatteryConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_WIFI);
        }

        @GuardedBy("mBsi")
        @Override
        public long getWifiMeasuredBatteryConsumptionUC(int processState) {
            return getMeasuredBatteryConsumptionUC(MeasuredEnergyStats.POWER_BUCKET_WIFI,
                    processState);
        }

        /**
         * Gets the minimum of the uid's foreground activity time and its PROCESS_STATE_TOP time
         * since last marked. Also sets the mark time for both these timers.
         *
         * @see CpuPowerCalculator
         *
         * @param doCalc if true, then calculate the minimum; else don't bother and return 0. Either
         *               way, the mark is set.
         */
        private long markProcessForegroundTimeUs(long elapsedRealtimeMs,
                boolean doCalc) {
            long fgTimeUs = 0;
            final StopwatchTimer fgTimer = mForegroundActivityTimer;
            if (fgTimer != null) {
                if (doCalc) fgTimeUs = fgTimer.getTimeSinceMarkLocked(elapsedRealtimeMs * 1000);
                fgTimer.setMark(elapsedRealtimeMs);
            }

            long topTimeUs = 0;
            final StopwatchTimer topTimer = mProcessStateTimer[PROCESS_STATE_TOP];
            if (topTimer != null) {
                if (doCalc) topTimeUs = topTimer.getTimeSinceMarkLocked(elapsedRealtimeMs * 1000);
                topTimer.setMark(elapsedRealtimeMs);
            }

            // Return the min of the two
            return (topTimeUs < fgTimeUs) ? topTimeUs : fgTimeUs;
        }


        /**
         * Gets the uid's time spent using the GNSS since last marked. Also sets the mark time for
         * the GNSS timer.
         */
        private long markGnssTimeUs(long elapsedRealtimeMs) {
            final Sensor sensor = mSensorStats.get(Sensor.GPS);
            if (sensor == null) {
                return 0;
            }

            final StopwatchTimer timer = sensor.mTimer;
            if (timer == null) {
                return 0;
            }

            final long gnssTimeUs = timer.getTimeSinceMarkLocked(elapsedRealtimeMs * 1000);
            timer.setMark(elapsedRealtimeMs);
            return gnssTimeUs;
        }

        public StopwatchTimer createAudioTurnedOnTimerLocked() {
            if (mAudioTurnedOnTimer == null) {
                mAudioTurnedOnTimer = new StopwatchTimer(mBsi.mClock, Uid.this, AUDIO_TURNED_ON,
                        mBsi.mAudioTurnedOnTimers, mBsi.mOnBatteryTimeBase);
            }
            return mAudioTurnedOnTimer;
        }

        public void noteAudioTurnedOnLocked(long elapsedRealtimeMs) {
            createAudioTurnedOnTimerLocked().startRunningLocked(elapsedRealtimeMs);
        }

        public void noteAudioTurnedOffLocked(long elapsedRealtimeMs) {
            if (mAudioTurnedOnTimer != null) {
                mAudioTurnedOnTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteResetAudioLocked(long elapsedRealtimeMs) {
            if (mAudioTurnedOnTimer != null) {
                mAudioTurnedOnTimer.stopAllRunningLocked(elapsedRealtimeMs);
            }
        }

        public StopwatchTimer createVideoTurnedOnTimerLocked() {
            if (mVideoTurnedOnTimer == null) {
                mVideoTurnedOnTimer = new StopwatchTimer(mBsi.mClock, Uid.this, VIDEO_TURNED_ON,
                        mBsi.mVideoTurnedOnTimers, mBsi.mOnBatteryTimeBase);
            }
            return mVideoTurnedOnTimer;
        }

        public void noteVideoTurnedOnLocked(long elapsedRealtimeMs) {
            createVideoTurnedOnTimerLocked().startRunningLocked(elapsedRealtimeMs);
        }

        public void noteVideoTurnedOffLocked(long elapsedRealtimeMs) {
            if (mVideoTurnedOnTimer != null) {
                mVideoTurnedOnTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteResetVideoLocked(long elapsedRealtimeMs) {
            if (mVideoTurnedOnTimer != null) {
                mVideoTurnedOnTimer.stopAllRunningLocked(elapsedRealtimeMs);
            }
        }

        public StopwatchTimer createFlashlightTurnedOnTimerLocked() {
            if (mFlashlightTurnedOnTimer == null) {
                mFlashlightTurnedOnTimer = new StopwatchTimer(mBsi.mClock, Uid.this,
                        FLASHLIGHT_TURNED_ON, mBsi.mFlashlightTurnedOnTimers, mBsi.mOnBatteryTimeBase);
            }
            return mFlashlightTurnedOnTimer;
        }

        public void noteFlashlightTurnedOnLocked(long elapsedRealtimeMs) {
            createFlashlightTurnedOnTimerLocked().startRunningLocked(elapsedRealtimeMs);
        }

        public void noteFlashlightTurnedOffLocked(long elapsedRealtimeMs) {
            if (mFlashlightTurnedOnTimer != null) {
                mFlashlightTurnedOnTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteResetFlashlightLocked(long elapsedRealtimeMs) {
            if (mFlashlightTurnedOnTimer != null) {
                mFlashlightTurnedOnTimer.stopAllRunningLocked(elapsedRealtimeMs);
            }
        }

        public StopwatchTimer createCameraTurnedOnTimerLocked() {
            if (mCameraTurnedOnTimer == null) {
                mCameraTurnedOnTimer = new StopwatchTimer(mBsi.mClock, Uid.this, CAMERA_TURNED_ON,
                        mBsi.mCameraTurnedOnTimers, mBsi.mOnBatteryTimeBase);
            }
            return mCameraTurnedOnTimer;
        }

        public void noteCameraTurnedOnLocked(long elapsedRealtimeMs) {
            createCameraTurnedOnTimerLocked().startRunningLocked(elapsedRealtimeMs);
        }

        public void noteCameraTurnedOffLocked(long elapsedRealtimeMs) {
            if (mCameraTurnedOnTimer != null) {
                mCameraTurnedOnTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteResetCameraLocked(long elapsedRealtimeMs) {
            if (mCameraTurnedOnTimer != null) {
                mCameraTurnedOnTimer.stopAllRunningLocked(elapsedRealtimeMs);
            }
        }

        public StopwatchTimer createForegroundActivityTimerLocked() {
            if (mForegroundActivityTimer == null) {
                mForegroundActivityTimer = new StopwatchTimer(mBsi.mClock, Uid.this,
                        FOREGROUND_ACTIVITY, null, mBsi.mOnBatteryTimeBase);
            }
            return mForegroundActivityTimer;
        }

        public StopwatchTimer createForegroundServiceTimerLocked() {
            if (mForegroundServiceTimer == null) {
                mForegroundServiceTimer = new StopwatchTimer(mBsi.mClock, Uid.this,
                        FOREGROUND_SERVICE, null, mBsi.mOnBatteryTimeBase);
            }
            return mForegroundServiceTimer;
        }

        public DualTimer createAggregatedPartialWakelockTimerLocked() {
            if (mAggregatedPartialWakelockTimer == null) {
                mAggregatedPartialWakelockTimer = new DualTimer(mBsi.mClock, this,
                        AGGREGATED_WAKE_TYPE_PARTIAL, null,
                        mBsi.mOnBatteryScreenOffTimeBase, mOnBatteryScreenOffBackgroundTimeBase);
            }
            return mAggregatedPartialWakelockTimer;
        }

        public DualTimer createBluetoothScanTimerLocked() {
            if (mBluetoothScanTimer == null) {
                mBluetoothScanTimer = new DualTimer(mBsi.mClock, Uid.this, BLUETOOTH_SCAN_ON,
                        mBsi.mBluetoothScanOnTimers, mBsi.mOnBatteryTimeBase,
                        mOnBatteryBackgroundTimeBase);
            }
            return mBluetoothScanTimer;
        }

        public DualTimer createBluetoothUnoptimizedScanTimerLocked() {
            if (mBluetoothUnoptimizedScanTimer == null) {
                mBluetoothUnoptimizedScanTimer = new DualTimer(mBsi.mClock, Uid.this,
                        BLUETOOTH_UNOPTIMIZED_SCAN_ON, null,
                        mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase);
            }
            return mBluetoothUnoptimizedScanTimer;
        }

        public void noteBluetoothScanStartedLocked(long elapsedRealtimeMs,
                boolean isUnoptimized) {
            createBluetoothScanTimerLocked().startRunningLocked(elapsedRealtimeMs);
            if (isUnoptimized) {
                createBluetoothUnoptimizedScanTimerLocked().startRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteBluetoothScanStoppedLocked(long elapsedRealtimeMs, boolean isUnoptimized) {
            if (mBluetoothScanTimer != null) {
                mBluetoothScanTimer.stopRunningLocked(elapsedRealtimeMs);
            }
            if (isUnoptimized && mBluetoothUnoptimizedScanTimer != null) {
                mBluetoothUnoptimizedScanTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteResetBluetoothScanLocked(long elapsedRealtimeMs) {
            if (mBluetoothScanTimer != null) {
                mBluetoothScanTimer.stopAllRunningLocked(elapsedRealtimeMs);
            }
            if (mBluetoothUnoptimizedScanTimer != null) {
                mBluetoothUnoptimizedScanTimer.stopAllRunningLocked(elapsedRealtimeMs);
            }
        }

        public Counter createBluetoothScanResultCounterLocked() {
            if (mBluetoothScanResultCounter == null) {
                mBluetoothScanResultCounter = new Counter(mBsi.mOnBatteryTimeBase);
            }
            return mBluetoothScanResultCounter;
        }

        public Counter createBluetoothScanResultBgCounterLocked() {
            if (mBluetoothScanResultBgCounter == null) {
                mBluetoothScanResultBgCounter = new Counter(mOnBatteryBackgroundTimeBase);
            }
            return mBluetoothScanResultBgCounter;
        }

        public void noteBluetoothScanResultsLocked(int numNewResults) {
            createBluetoothScanResultCounterLocked().addAtomic(numNewResults);
            // Uses background timebase, so the count will only be incremented if uid in background.
            createBluetoothScanResultBgCounterLocked().addAtomic(numNewResults);
        }

        @Override
        public void noteActivityResumedLocked(long elapsedRealtimeMs) {
            // We always start, since we want multiple foreground PIDs to nest
            createForegroundActivityTimerLocked().startRunningLocked(elapsedRealtimeMs);
        }

        @Override
        public void noteActivityPausedLocked(long elapsedRealtimeMs) {
            if (mForegroundActivityTimer != null) {
                mForegroundActivityTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteForegroundServiceResumedLocked(long elapsedRealtimeMs) {
            createForegroundServiceTimerLocked().startRunningLocked(elapsedRealtimeMs);
        }

        public void noteForegroundServicePausedLocked(long elapsedRealtimeMs) {
            if (mForegroundServiceTimer != null) {
                mForegroundServiceTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public BatchTimer createVibratorOnTimerLocked() {
            if (mVibratorOnTimer == null) {
                mVibratorOnTimer = new BatchTimer(mBsi.mClock, Uid.this, VIBRATOR_ON,
                        mBsi.mOnBatteryTimeBase);
            }
            return mVibratorOnTimer;
        }

        public void noteVibratorOnLocked(long durationMillis, long elapsedRealtimeMs) {
            createVibratorOnTimerLocked().addDuration(mBsi, durationMillis, elapsedRealtimeMs);
        }

        public void noteVibratorOffLocked(long elapsedRealtimeMs) {
            if (mVibratorOnTimer != null) {
                mVibratorOnTimer.abortLastDuration(mBsi, elapsedRealtimeMs);
            }
        }

        @Override
        @UnsupportedAppUsage
        public long getWifiRunningTime(long elapsedRealtimeUs, int which) {
            if (mWifiRunningTimer == null) {
                return 0;
            }
            return mWifiRunningTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
        }

        @Override
        public long getFullWifiLockTime(long elapsedRealtimeUs, int which) {
            if (mFullWifiLockTimer == null) {
                return 0;
            }
            return mFullWifiLockTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
        }

        @Override
        @UnsupportedAppUsage
        public long getWifiScanTime(long elapsedRealtimeUs, int which) {
            if (mWifiScanTimer == null) {
                return 0;
            }
            return mWifiScanTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
        }

        @Override
        public int getWifiScanCount(int which) {
            if (mWifiScanTimer == null) {
                return 0;
            }
            return mWifiScanTimer.getCountLocked(which);
        }

        @Override
        public Timer getWifiScanTimer() {
            return mWifiScanTimer;
        }

        @Override
        public int getWifiScanBackgroundCount(int which) {
            if (mWifiScanTimer == null || mWifiScanTimer.getSubTimer() == null) {
                return 0;
            }
            return mWifiScanTimer.getSubTimer().getCountLocked(which);
        }

        @Override
        public long getWifiScanActualTime(final long elapsedRealtimeUs) {
            if (mWifiScanTimer == null) {
                return 0;
            }
            final long elapsedRealtimeMs = (elapsedRealtimeUs + 500) / 1000;
            return mWifiScanTimer.getTotalDurationMsLocked(elapsedRealtimeMs) * 1000;
        }

        @Override
        public long getWifiScanBackgroundTime(final long elapsedRealtimeUs) {
            if (mWifiScanTimer == null || mWifiScanTimer.getSubTimer() == null) {
                return 0;
            }
            final long elapsedRealtimeMs = (elapsedRealtimeUs + 500) / 1000;
            return mWifiScanTimer.getSubTimer().getTotalDurationMsLocked(elapsedRealtimeMs) * 1000;
        }

        @Override
        public Timer getWifiScanBackgroundTimer() {
            if (mWifiScanTimer == null) {
                return null;
            }
            return mWifiScanTimer.getSubTimer();
        }

        @Override
        public long getWifiBatchedScanTime(int csphBin, long elapsedRealtimeUs, int which) {
            if (csphBin < 0 || csphBin >= NUM_WIFI_BATCHED_SCAN_BINS) return 0;
            if (mWifiBatchedScanTimer[csphBin] == null) {
                return 0;
            }
            return mWifiBatchedScanTimer[csphBin].getTotalTimeLocked(elapsedRealtimeUs, which);
        }

        @Override
        public int getWifiBatchedScanCount(int csphBin, int which) {
            if (csphBin < 0 || csphBin >= NUM_WIFI_BATCHED_SCAN_BINS) return 0;
            if (mWifiBatchedScanTimer[csphBin] == null) {
                return 0;
            }
            return mWifiBatchedScanTimer[csphBin].getCountLocked(which);
        }

        @Override
        public long getWifiMulticastTime(long elapsedRealtimeUs, int which) {
            if (mWifiMulticastTimer == null) {
                return 0;
            }
            return mWifiMulticastTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
        }

        @Override
        public Timer getAudioTurnedOnTimer() {
            return mAudioTurnedOnTimer;
        }

        @Override
        public Timer getVideoTurnedOnTimer() {
            return mVideoTurnedOnTimer;
        }

        @Override
        public Timer getFlashlightTurnedOnTimer() {
            return mFlashlightTurnedOnTimer;
        }

        @Override
        public Timer getCameraTurnedOnTimer() {
            return mCameraTurnedOnTimer;
        }

        @Override
        public Timer getForegroundActivityTimer() {
            return mForegroundActivityTimer;
        }

        @Override
        public Timer getForegroundServiceTimer() {
            return mForegroundServiceTimer;
        }

        @Override
        public Timer getBluetoothScanTimer() {
            return mBluetoothScanTimer;
        }

        @Override
        public Timer getBluetoothScanBackgroundTimer() {
            if (mBluetoothScanTimer == null) {
                return null;
            }
            return mBluetoothScanTimer.getSubTimer();
        }

        @Override
        public Timer getBluetoothUnoptimizedScanTimer() {
            return mBluetoothUnoptimizedScanTimer;
        }

        @Override
        public Timer getBluetoothUnoptimizedScanBackgroundTimer() {
            if (mBluetoothUnoptimizedScanTimer == null) {
                return null;
            }
            return mBluetoothUnoptimizedScanTimer.getSubTimer();
        }

        @Override
        public Counter getBluetoothScanResultCounter() {
            return mBluetoothScanResultCounter;
        }

        @Override
        public Counter getBluetoothScanResultBgCounter() {
            return mBluetoothScanResultBgCounter;
        }

        void makeProcessState(int i, Parcel in) {
            if (i < 0 || i >= NUM_PROCESS_STATE) return;

            detachIfNotNull(mProcessStateTimer[i]);
            if (in == null) {
                mProcessStateTimer[i] = new StopwatchTimer(mBsi.mClock, this, PROCESS_STATE, null,
                        mBsi.mOnBatteryTimeBase);
            } else {
                mProcessStateTimer[i] = new StopwatchTimer(mBsi.mClock, this, PROCESS_STATE, null,
                        mBsi.mOnBatteryTimeBase, in);
            }
        }

        @Override
        public long getProcessStateTime(int state, long elapsedRealtimeUs, int which) {
            if (state < 0 || state >= NUM_PROCESS_STATE) return 0;
            if (mProcessStateTimer[state] == null) {
                return 0;
            }
            return mProcessStateTimer[state].getTotalTimeLocked(elapsedRealtimeUs, which);
        }

        @Override
        public Timer getProcessStateTimer(int state) {
            if (state < 0 || state >= NUM_PROCESS_STATE) return null;
            return mProcessStateTimer[state];
        }

        @Override
        public Timer getVibratorOnTimer() {
            return mVibratorOnTimer;
        }

        @Override
        public void noteUserActivityLocked(@PowerManager.UserActivityEvent int event) {
            if (mUserActivityCounters == null) {
                initUserActivityLocked();
            }
            if (event >= 0 && event < NUM_USER_ACTIVITY_TYPES) {
                mUserActivityCounters[event].stepAtomic();
            } else {
                Slog.w(TAG, "Unknown user activity type " + event + " was specified.",
                        new Throwable());
            }
        }

        @Override
        public boolean hasUserActivity() {
            return mUserActivityCounters != null;
        }

        @Override
        public int getUserActivityCount(int type, int which) {
            if (mUserActivityCounters == null) {
                return 0;
            }
            return mUserActivityCounters[type].getCountLocked(which);
        }

        void makeWifiBatchedScanBin(int i, Parcel in) {
            if (i < 0 || i >= NUM_WIFI_BATCHED_SCAN_BINS) return;

            ArrayList<StopwatchTimer> collected = mBsi.mWifiBatchedScanTimers.get(i);
            if (collected == null) {
                collected = new ArrayList<StopwatchTimer>();
                mBsi.mWifiBatchedScanTimers.put(i, collected);
            }
            detachIfNotNull(mWifiBatchedScanTimer[i]);
            if (in == null) {
                mWifiBatchedScanTimer[i] = new StopwatchTimer(mBsi.mClock, this, WIFI_BATCHED_SCAN,
                        collected, mBsi.mOnBatteryTimeBase);
            } else {
                mWifiBatchedScanTimer[i] = new StopwatchTimer(mBsi.mClock, this, WIFI_BATCHED_SCAN,
                        collected, mBsi.mOnBatteryTimeBase, in);
            }
        }


        void initUserActivityLocked() {
            detachIfNotNull(mUserActivityCounters);
            mUserActivityCounters = new Counter[NUM_USER_ACTIVITY_TYPES];
            for (int i=0; i<NUM_USER_ACTIVITY_TYPES; i++) {
                mUserActivityCounters[i] = new Counter(mBsi.mOnBatteryTimeBase);
            }
        }

        void noteNetworkActivityLocked(int type, long deltaBytes, long deltaPackets) {
            ensureNetworkActivityLocked();
            if (type >= 0 && type < NUM_NETWORK_ACTIVITY_TYPES) {
                mNetworkByteActivityCounters[type].addCountLocked(deltaBytes);
                mNetworkPacketActivityCounters[type].addCountLocked(deltaPackets);
            } else {
                Slog.w(TAG, "Unknown network activity type " + type + " was specified.",
                        new Throwable());
            }
        }

        void noteMobileRadioActiveTimeLocked(long batteryUptimeDeltaUs, long elapsedTimeMs) {
            ensureNetworkActivityLocked();
            getMobileRadioActiveTimeCounter().increment(batteryUptimeDeltaUs, elapsedTimeMs);
            mMobileRadioActiveCount.addCountLocked(1);
        }

        private TimeMultiStateCounter getMobileRadioActiveTimeCounter() {
            if (mMobileRadioActiveTime == null) {
                final long timestampMs = mBsi.mClock.elapsedRealtime();
                mMobileRadioActiveTime = new TimeMultiStateCounter(
                        mBsi.mOnBatteryTimeBase, BatteryConsumer.PROCESS_STATE_COUNT, timestampMs);
                mMobileRadioActiveTime.setState(
                        mapUidProcessStateToBatteryConsumerProcessState(mProcessState),
                        timestampMs);
                mMobileRadioActiveTime.update(0, timestampMs);
            }
            return mMobileRadioActiveTime;
        }

        @Override
        public boolean hasNetworkActivity() {
            return mNetworkByteActivityCounters != null;
        }

        @Override
        public long getNetworkActivityBytes(int type, int which) {
            if (mNetworkByteActivityCounters != null && type >= 0
                    && type < mNetworkByteActivityCounters.length) {
                return mNetworkByteActivityCounters[type].getCountLocked(which);
            } else {
                return 0;
            }
        }

        @Override
        public long getNetworkActivityPackets(int type, int which) {
            if (mNetworkPacketActivityCounters != null && type >= 0
                    && type < mNetworkPacketActivityCounters.length) {
                return mNetworkPacketActivityCounters[type].getCountLocked(which);
            } else {
                return 0;
            }
        }

        @Override
        public long getMobileRadioActiveTime(int which) {
            return getMobileRadioActiveTimeInProcessState(BatteryConsumer.PROCESS_STATE_ANY);
        }

        @Override
        public long getMobileRadioActiveTimeInProcessState(
                @BatteryConsumer.ProcessState int processState) {
            if (mMobileRadioActiveTime == null) {
                return 0;
            }
            if (processState == BatteryConsumer.PROCESS_STATE_ANY) {
                return mMobileRadioActiveTime.getTotalCountLocked();
            } else {
                return mMobileRadioActiveTime.getCountForProcessState(processState);
            }
        }

        @Override
        public int getMobileRadioActiveCount(int which) {
            return mMobileRadioActiveCount != null
                    ? (int)mMobileRadioActiveCount.getCountLocked(which) : 0;
        }

        @Override
        public long getUserCpuTimeUs(int which) {
            return mUserCpuTime.getCountLocked(which);
        }

        @Override
        public long getSystemCpuTimeUs(int which) {
            return mSystemCpuTime.getCountLocked(which);
        }

        @Override
        public long getTimeAtCpuSpeed(int cluster, int step, int which) {
            if (mCpuClusterSpeedTimesUs != null) {
                if (cluster >= 0 && cluster < mCpuClusterSpeedTimesUs.length) {
                    final LongSamplingCounter[] cpuSpeedTimesUs = mCpuClusterSpeedTimesUs[cluster];
                    if (cpuSpeedTimesUs != null) {
                        if (step >= 0 && step < cpuSpeedTimesUs.length) {
                            final LongSamplingCounter c = cpuSpeedTimesUs[step];
                            if (c != null) {
                                return c.getCountLocked(which);
                            }
                        }
                    }
                }
            }
            return 0;
        }

        public void noteMobileRadioApWakeupLocked() {
            if (mMobileRadioApWakeupCount == null) {
                mMobileRadioApWakeupCount = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
            }
            mMobileRadioApWakeupCount.addCountLocked(1);
        }

        @Override
        public long getMobileRadioApWakeupCount(int which) {
            if (mMobileRadioApWakeupCount != null) {
                return mMobileRadioApWakeupCount.getCountLocked(which);
            }
            return 0;
        }

        public void noteWifiRadioApWakeupLocked() {
            if (mWifiRadioApWakeupCount == null) {
                mWifiRadioApWakeupCount = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
            }
            mWifiRadioApWakeupCount.addCountLocked(1);
        }

        @Override
        public long getWifiRadioApWakeupCount(int which) {
            if (mWifiRadioApWakeupCount != null) {
                return mWifiRadioApWakeupCount.getCountLocked(which);
            }
            return 0;
        }

        @Override
        public void getDeferredJobsCheckinLineLocked(StringBuilder sb, int which) {
            sb.setLength(0);
            final int deferredEventCount = mJobsDeferredEventCount.getCountLocked(which);
            if (deferredEventCount == 0) {
                return;
            }
            final int deferredCount = mJobsDeferredCount.getCountLocked(which);
            final long totalLatency = mJobsFreshnessTimeMs.getCountLocked(which);
            sb.append(deferredEventCount); sb.append(',');
            sb.append(deferredCount); sb.append(',');
            sb.append(totalLatency);
            for (int i = 0; i < JOB_FRESHNESS_BUCKETS.length; i++) {
                if (mJobsFreshnessBuckets[i] == null) {
                    sb.append(",0");
                } else {
                    sb.append(",");
                    sb.append(mJobsFreshnessBuckets[i].getCountLocked(which));
                }
            }
        }

        @Override
        public void getDeferredJobsLineLocked(StringBuilder sb, int which) {
            sb.setLength(0);
            final int deferredEventCount = mJobsDeferredEventCount.getCountLocked(which);
            if (deferredEventCount == 0) {
                return;
            }
            final int deferredCount = mJobsDeferredCount.getCountLocked(which);
            final long totalLatency = mJobsFreshnessTimeMs.getCountLocked(which);
            sb.append("times="); sb.append(deferredEventCount); sb.append(", ");
            sb.append("count="); sb.append(deferredCount); sb.append(", ");
            sb.append("totalLatencyMs="); sb.append(totalLatency); sb.append(", ");
            for (int i = 0; i < JOB_FRESHNESS_BUCKETS.length; i++) {
                sb.append("<"); sb.append(JOB_FRESHNESS_BUCKETS[i]); sb.append("ms=");
                if (mJobsFreshnessBuckets[i] == null) {
                    sb.append("0");
                } else {
                    sb.append(mJobsFreshnessBuckets[i].getCountLocked(which));
                }
                sb.append(" ");
            }
        }

        void ensureNetworkActivityLocked() {
            if (mNetworkByteActivityCounters != null) {
                return;
            }

            mNetworkByteActivityCounters = new LongSamplingCounter[NUM_NETWORK_ACTIVITY_TYPES];
            mNetworkPacketActivityCounters = new LongSamplingCounter[NUM_NETWORK_ACTIVITY_TYPES];
            for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
                mNetworkByteActivityCounters[i] = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
                mNetworkPacketActivityCounters[i] = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
            }
            mMobileRadioActiveCount = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
        }

        /**
         * Clear all stats for this uid.  Returns true if the uid is completely
         * inactive so can be dropped.
         */
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public boolean reset(long uptimeUs, long realtimeUs, int resetReason) {
            boolean active = false;

            mOnBatteryBackgroundTimeBase.init(uptimeUs, realtimeUs);
            mOnBatteryScreenOffBackgroundTimeBase.init(uptimeUs, realtimeUs);

            if (mWifiRunningTimer != null) {
                active |= !mWifiRunningTimer.reset(false, realtimeUs);
                active |= mWifiRunning;
            }
            if (mFullWifiLockTimer != null) {
                active |= !mFullWifiLockTimer.reset(false, realtimeUs);
                active |= mFullWifiLockOut;
            }
            if (mWifiScanTimer != null) {
                active |= !mWifiScanTimer.reset(false, realtimeUs);
                active |= mWifiScanStarted;
            }
            if (mWifiBatchedScanTimer != null) {
                for (int i = 0; i < NUM_WIFI_BATCHED_SCAN_BINS; i++) {
                    if (mWifiBatchedScanTimer[i] != null) {
                        active |= !mWifiBatchedScanTimer[i].reset(false, realtimeUs);
                    }
                }
                active |= (mWifiBatchedScanBinStarted != NO_BATCHED_SCAN_STARTED);
            }
            if (mWifiMulticastTimer != null) {
                active |= !mWifiMulticastTimer.reset(false, realtimeUs);
                active |= (mWifiMulticastWakelockCount > 0);
            }

            active |= !resetIfNotNull(mAudioTurnedOnTimer, false, realtimeUs);
            active |= !resetIfNotNull(mVideoTurnedOnTimer, false, realtimeUs);
            active |= !resetIfNotNull(mFlashlightTurnedOnTimer, false, realtimeUs);
            active |= !resetIfNotNull(mCameraTurnedOnTimer, false, realtimeUs);
            active |= !resetIfNotNull(mForegroundActivityTimer, false, realtimeUs);
            active |= !resetIfNotNull(mForegroundServiceTimer, false, realtimeUs);
            active |= !resetIfNotNull(mAggregatedPartialWakelockTimer, false, realtimeUs);
            active |= !resetIfNotNull(mBluetoothScanTimer, false, realtimeUs);
            active |= !resetIfNotNull(mBluetoothUnoptimizedScanTimer, false, realtimeUs);

            resetIfNotNull(mBluetoothScanResultCounter, false, realtimeUs);
            resetIfNotNull(mBluetoothScanResultBgCounter, false, realtimeUs);

            if (mProcessStateTimer != null) {
                for (int i = 0; i < NUM_PROCESS_STATE; i++) {
                    active |= !resetIfNotNull(mProcessStateTimer[i], false, realtimeUs);
                }
                active |= (mProcessState != Uid.PROCESS_STATE_NONEXISTENT);
            }
            if (mVibratorOnTimer != null) {
                if (mVibratorOnTimer.reset(false, realtimeUs)) {
                    mVibratorOnTimer.detach();
                    mVibratorOnTimer = null;
                } else {
                    active = true;
                }
            }

            resetIfNotNull(mUserActivityCounters, false, realtimeUs);

            resetIfNotNull(mNetworkByteActivityCounters, false, realtimeUs);
            resetIfNotNull(mNetworkPacketActivityCounters, false, realtimeUs);
            resetIfNotNull(mMobileRadioActiveTime, false, realtimeUs);
            resetIfNotNull(mMobileRadioActiveCount, false, realtimeUs);

            resetIfNotNull(mWifiControllerActivity, false, realtimeUs);
            resetIfNotNull(mBluetoothControllerActivity, false, realtimeUs);
            resetIfNotNull(mModemControllerActivity, false, realtimeUs);

            if (resetReason == RESET_REASON_MEASURED_ENERGY_BUCKETS_CHANGE) {
                mUidMeasuredEnergyStats = null;
            } else {
                MeasuredEnergyStats.resetIfNotNull(mUidMeasuredEnergyStats);
            }

            resetIfNotNull(mUserCpuTime, false, realtimeUs);
            resetIfNotNull(mSystemCpuTime, false, realtimeUs);

            resetIfNotNull(mCpuClusterSpeedTimesUs, false, realtimeUs);

            resetIfNotNull(mCpuFreqTimeMs, false, realtimeUs /* unused */);
            resetIfNotNull(mScreenOffCpuFreqTimeMs, false, realtimeUs /* unused */);


            resetIfNotNull(mCpuActiveTimeMs, false, realtimeUs /* unused */);
            resetIfNotNull(mCpuClusterTimesMs, false, realtimeUs /* unused */);

            resetIfNotNull(mProcStateTimeMs, false, realtimeUs /* unused */);

            resetIfNotNull(mProcStateScreenOffTimeMs, false, realtimeUs /* unused */);

            resetIfNotNull(mMobileRadioApWakeupCount, false, realtimeUs);

            resetIfNotNull(mWifiRadioApWakeupCount, false, realtimeUs);


            final ArrayMap<String, Wakelock> wakeStats = mWakelockStats.getMap();
            for (int iw=wakeStats.size()-1; iw>=0; iw--) {
                Wakelock wl = wakeStats.valueAt(iw);
                if (wl.reset(realtimeUs)) {
                    wakeStats.removeAt(iw);
                } else {
                    active = true;
                }
            }
            final long realtimeMs = realtimeUs / 1000;
            mWakelockStats.cleanup(realtimeMs);
            final ArrayMap<String, DualTimer> syncStats = mSyncStats.getMap();
            for (int is=syncStats.size()-1; is>=0; is--) {
                DualTimer timer = syncStats.valueAt(is);
                if (timer.reset(false, realtimeUs)) {
                    syncStats.removeAt(is);
                    timer.detach();
                } else {
                    active = true;
                }
            }
            mSyncStats.cleanup(realtimeMs);
            final ArrayMap<String, DualTimer> jobStats = mJobStats.getMap();
            for (int ij=jobStats.size()-1; ij>=0; ij--) {
                DualTimer timer = jobStats.valueAt(ij);
                if (timer.reset(false, realtimeUs)) {
                    jobStats.removeAt(ij);
                    timer.detach();
                } else {
                    active = true;
                }
            }
            mJobStats.cleanup(realtimeMs);
            mJobCompletions.clear();

            resetIfNotNull(mJobsDeferredEventCount, false, realtimeUs);
            resetIfNotNull(mJobsDeferredCount, false, realtimeUs);
            resetIfNotNull(mJobsFreshnessTimeMs, false, realtimeUs /* unused */);
            resetIfNotNull(mJobsFreshnessBuckets, false, realtimeUs);

            for (int ise = mSensorStats.size() - 1; ise >= 0; ise--) {
                Sensor s = mSensorStats.valueAt(ise);
                if (s.reset(realtimeUs)) {
                    mSensorStats.removeAt(ise);
                } else {
                    active = true;
                }
            }

            for (int ip = mProcessStats.size() - 1; ip >= 0; ip--) {
                Proc proc = mProcessStats.valueAt(ip);
                proc.detach();
            }
            mProcessStats.clear();

            for (int i = mPids.size() - 1; i >= 0; i--) {
                Pid pid = mPids.valueAt(i);
                if (pid.mWakeNesting > 0) {
                    active = true;
                } else {
                    mPids.removeAt(i);
                }
            }


            for(int i = mPackageStats.size() - 1; i >= 0; i--) {
                Pkg p = mPackageStats.valueAt(i);
                p.detach();
            }
            mPackageStats.clear();

            mBinderCallCount = 0;
            mBinderCallStats.clear();

            mProportionalSystemServiceUsage = 0;

            mLastStepUserTimeMs = mLastStepSystemTimeMs = 0;
            mCurStepUserTimeMs = mCurStepSystemTimeMs = 0;


            return !active;
        }

        /**
         * This method MUST be called whenever the Uid object is destructed, otherwise it is a
         * memory leak in {@link TimeBase#mObservers} list.
         * Typically the Uid object is destructed when it is removed from
         * {@link BatteryStatsImpl#mUidStats}
         */
        void detachFromTimeBase() {
            detachIfNotNull(mWifiRunningTimer);
            detachIfNotNull(mFullWifiLockTimer);
            detachIfNotNull(mWifiScanTimer);
            detachIfNotNull(mWifiBatchedScanTimer);
            detachIfNotNull(mWifiMulticastTimer);
            detachIfNotNull(mAudioTurnedOnTimer);
            detachIfNotNull(mVideoTurnedOnTimer);
            detachIfNotNull(mFlashlightTurnedOnTimer);

            detachIfNotNull(mCameraTurnedOnTimer);
            detachIfNotNull(mForegroundActivityTimer);
            detachIfNotNull(mForegroundServiceTimer);

            detachIfNotNull(mAggregatedPartialWakelockTimer);

            detachIfNotNull(mBluetoothScanTimer);
            detachIfNotNull(mBluetoothUnoptimizedScanTimer);
            detachIfNotNull(mBluetoothScanResultCounter);
            detachIfNotNull(mBluetoothScanResultBgCounter);

            detachIfNotNull(mProcessStateTimer);

            detachIfNotNull(mVibratorOnTimer);

            detachIfNotNull(mUserActivityCounters);

            detachIfNotNull(mNetworkByteActivityCounters);
            detachIfNotNull(mNetworkPacketActivityCounters);

            detachIfNotNull(mMobileRadioActiveTime);
            detachIfNotNull(mMobileRadioActiveCount);
            detachIfNotNull(mMobileRadioApWakeupCount);
            detachIfNotNull(mWifiRadioApWakeupCount);

            detachIfNotNull(mWifiControllerActivity);
            detachIfNotNull(mBluetoothControllerActivity);
            detachIfNotNull(mModemControllerActivity);

            mPids.clear();

            detachIfNotNull(mUserCpuTime);
            detachIfNotNull(mSystemCpuTime);

            detachIfNotNull(mCpuClusterSpeedTimesUs);

            detachIfNotNull(mCpuActiveTimeMs);
            detachIfNotNull(mCpuFreqTimeMs);

            detachIfNotNull(mScreenOffCpuFreqTimeMs);

            detachIfNotNull(mCpuClusterTimesMs);

            detachIfNotNull(mProcStateTimeMs);

            detachIfNotNull(mProcStateScreenOffTimeMs);

            final ArrayMap<String, Wakelock> wakeStats = mWakelockStats.getMap();
            for (int iw = wakeStats.size() - 1; iw >= 0; iw--) {
                Wakelock wl = wakeStats.valueAt(iw);
                wl.detachFromTimeBase();
            }
            final ArrayMap<String, DualTimer> syncStats = mSyncStats.getMap();
            for (int is = syncStats.size() - 1; is >= 0; is--) {
                DualTimer timer = syncStats.valueAt(is);
                detachIfNotNull(timer);
            }
            final ArrayMap<String, DualTimer> jobStats = mJobStats.getMap();
            for (int ij = jobStats.size() - 1; ij >= 0; ij--) {
                DualTimer timer = jobStats.valueAt(ij);
                detachIfNotNull(timer);
            }

            detachIfNotNull(mJobsDeferredEventCount);
            detachIfNotNull(mJobsDeferredCount);
            detachIfNotNull(mJobsFreshnessTimeMs);
            detachIfNotNull(mJobsFreshnessBuckets);


            for (int ise = mSensorStats.size() - 1; ise >= 0; ise--) {
                Sensor s = mSensorStats.valueAt(ise);
                s.detachFromTimeBase();
            }

            for (int ip= mProcessStats.size() - 1; ip >= 0; ip--) {
                Proc proc = mProcessStats.valueAt(ip);
                proc.detach();
            }
            mProcessStats.clear();

            for(int i = mPackageStats.size() - 1; i >= 0; i--) {
                Pkg p = mPackageStats.valueAt(i);
                p.detach();
            }
            mPackageStats.clear();
        }

        void writeJobCompletionsToParcelLocked(Parcel out) {
            int NJC = mJobCompletions.size();
            out.writeInt(NJC);
            for (int ijc=0; ijc<NJC; ijc++) {
                out.writeString(mJobCompletions.keyAt(ijc));
                SparseIntArray types = mJobCompletions.valueAt(ijc);
                int NT = types.size();
                out.writeInt(NT);
                for (int it=0; it<NT; it++) {
                    out.writeInt(types.keyAt(it));
                    out.writeInt(types.valueAt(it));
                }
            }
        }

        void writeToParcelLocked(Parcel out, long uptimeUs, long elapsedRealtimeUs) {
            mOnBatteryBackgroundTimeBase.writeToParcel(out, uptimeUs, elapsedRealtimeUs);
            mOnBatteryScreenOffBackgroundTimeBase.writeToParcel(out, uptimeUs, elapsedRealtimeUs);

            final ArrayMap<String, Wakelock> wakeStats = mWakelockStats.getMap();
            int NW = wakeStats.size();
            out.writeInt(NW);
            for (int iw=0; iw<NW; iw++) {
                out.writeString(wakeStats.keyAt(iw));
                Uid.Wakelock wakelock = wakeStats.valueAt(iw);
                wakelock.writeToParcelLocked(out, elapsedRealtimeUs);
            }

            final ArrayMap<String, DualTimer> syncStats = mSyncStats.getMap();
            int NS = syncStats.size();
            out.writeInt(NS);
            for (int is=0; is<NS; is++) {
                out.writeString(syncStats.keyAt(is));
                DualTimer timer = syncStats.valueAt(is);
                Timer.writeTimerToParcel(out, timer, elapsedRealtimeUs);
            }

            final ArrayMap<String, DualTimer> jobStats = mJobStats.getMap();
            int NJ = jobStats.size();
            out.writeInt(NJ);
            for (int ij=0; ij<NJ; ij++) {
                out.writeString(jobStats.keyAt(ij));
                DualTimer timer = jobStats.valueAt(ij);
                Timer.writeTimerToParcel(out, timer, elapsedRealtimeUs);
            }

            writeJobCompletionsToParcelLocked(out);

            mJobsDeferredEventCount.writeToParcel(out);
            mJobsDeferredCount.writeToParcel(out);
            mJobsFreshnessTimeMs.writeToParcel(out);
            for (int i = 0; i < JOB_FRESHNESS_BUCKETS.length; i++) {
                Counter.writeCounterToParcel(out, mJobsFreshnessBuckets[i]);
            }

            int NSE = mSensorStats.size();
            out.writeInt(NSE);
            for (int ise=0; ise<NSE; ise++) {
                out.writeInt(mSensorStats.keyAt(ise));
                Uid.Sensor sensor = mSensorStats.valueAt(ise);
                sensor.writeToParcelLocked(out, elapsedRealtimeUs);
            }

            int NP = mProcessStats.size();
            out.writeInt(NP);
            for (int ip=0; ip<NP; ip++) {
                out.writeString(mProcessStats.keyAt(ip));
                Uid.Proc proc = mProcessStats.valueAt(ip);
                proc.writeToParcelLocked(out);
            }

            out.writeInt(mPackageStats.size());
            for (Map.Entry<String, Uid.Pkg> pkgEntry : mPackageStats.entrySet()) {
                out.writeString(pkgEntry.getKey());
                Uid.Pkg pkg = pkgEntry.getValue();
                pkg.writeToParcelLocked(out);
            }

            if (mWifiRunningTimer != null) {
                out.writeInt(1);
                mWifiRunningTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mFullWifiLockTimer != null) {
                out.writeInt(1);
                mFullWifiLockTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mWifiScanTimer != null) {
                out.writeInt(1);
                mWifiScanTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            for (int i = 0; i < NUM_WIFI_BATCHED_SCAN_BINS; i++) {
                if (mWifiBatchedScanTimer[i] != null) {
                    out.writeInt(1);
                    mWifiBatchedScanTimer[i].writeToParcel(out, elapsedRealtimeUs);
                } else {
                    out.writeInt(0);
                }
            }
            if (mWifiMulticastTimer != null) {
                out.writeInt(1);
                mWifiMulticastTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }

            if (mAudioTurnedOnTimer != null) {
                out.writeInt(1);
                mAudioTurnedOnTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mVideoTurnedOnTimer != null) {
                out.writeInt(1);
                mVideoTurnedOnTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mFlashlightTurnedOnTimer != null) {
                out.writeInt(1);
                mFlashlightTurnedOnTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mCameraTurnedOnTimer != null) {
                out.writeInt(1);
                mCameraTurnedOnTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mForegroundActivityTimer != null) {
                out.writeInt(1);
                mForegroundActivityTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mForegroundServiceTimer != null) {
                out.writeInt(1);
                mForegroundServiceTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mAggregatedPartialWakelockTimer != null) {
                out.writeInt(1);
                mAggregatedPartialWakelockTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mBluetoothScanTimer != null) {
                out.writeInt(1);
                mBluetoothScanTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mBluetoothUnoptimizedScanTimer != null) {
                out.writeInt(1);
                mBluetoothUnoptimizedScanTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mBluetoothScanResultCounter != null) {
                out.writeInt(1);
                mBluetoothScanResultCounter.writeToParcel(out);
            } else {
                out.writeInt(0);
            }
            if (mBluetoothScanResultBgCounter != null) {
                out.writeInt(1);
                mBluetoothScanResultBgCounter.writeToParcel(out);
            } else {
                out.writeInt(0);
            }
            for (int i = 0; i < NUM_PROCESS_STATE; i++) {
                if (mProcessStateTimer[i] != null) {
                    out.writeInt(1);
                    mProcessStateTimer[i].writeToParcel(out, elapsedRealtimeUs);
                } else {
                    out.writeInt(0);
                }
            }
            if (mVibratorOnTimer != null) {
                out.writeInt(1);
                mVibratorOnTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mUserActivityCounters != null) {
                out.writeInt(1);
                for (int i=0; i<NUM_USER_ACTIVITY_TYPES; i++) {
                    mUserActivityCounters[i].writeToParcel(out);
                }
            } else {
                out.writeInt(0);
            }
            if (mNetworkByteActivityCounters != null) {
                out.writeInt(1);
                for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
                    mNetworkByteActivityCounters[i].writeToParcel(out);
                    mNetworkPacketActivityCounters[i].writeToParcel(out);
                }
                if (mMobileRadioActiveTime != null) {
                    out.writeBoolean(true);
                    mMobileRadioActiveTime.writeToParcel(out);
                } else {
                    out.writeBoolean(false);
                }
                mMobileRadioActiveCount.writeToParcel(out);
            } else {
                out.writeInt(0);
            }

            if (mWifiControllerActivity != null) {
                out.writeInt(1);
                mWifiControllerActivity.writeToParcel(out, 0);
            } else {
                out.writeInt(0);
            }

            if (mBluetoothControllerActivity != null) {
                out.writeInt(1);
                mBluetoothControllerActivity.writeToParcel(out, 0);
            } else {
                out.writeInt(0);
            }

            if (mModemControllerActivity != null) {
                out.writeInt(1);
                mModemControllerActivity.writeToParcel(out, 0);
            } else {
                out.writeInt(0);
            }

            if (mUidMeasuredEnergyStats != null) {
                out.writeInt(1);
                mUidMeasuredEnergyStats.writeToParcel(out);
            } else {
                out.writeInt(0);
            }

            mUserCpuTime.writeToParcel(out);
            mSystemCpuTime.writeToParcel(out);

            mBsi.writeCpuSpeedCountersToParcel(out, mCpuClusterSpeedTimesUs);

            LongSamplingCounterArray.writeToParcel(out, mCpuFreqTimeMs);
            LongSamplingCounterArray.writeToParcel(out, mScreenOffCpuFreqTimeMs);

            if (mCpuActiveTimeMs != null) {
                out.writeInt(mCpuActiveTimeMs.getStateCount());
                mCpuActiveTimeMs.writeToParcel(out);
            } else {
                out.writeInt(0);
            }

            mCpuClusterTimesMs.writeToParcel(out);

            if (mProcStateTimeMs != null) {
                out.writeInt(mProcStateTimeMs.getStateCount());
                mProcStateTimeMs.writeToParcel(out);
            } else {
                out.writeInt(0);
            }
            if (mProcStateScreenOffTimeMs != null) {
                out.writeInt(mProcStateScreenOffTimeMs.getStateCount());
                mProcStateScreenOffTimeMs.writeToParcel(out);
            } else {
                out.writeInt(0);
            }

            if (mMobileRadioApWakeupCount != null) {
                out.writeInt(1);
                mMobileRadioApWakeupCount.writeToParcel(out);
            } else {
                out.writeInt(0);
            }

            if (mWifiRadioApWakeupCount != null) {
                out.writeInt(1);
                mWifiRadioApWakeupCount.writeToParcel(out);
            } else {
                out.writeInt(0);
            }
            out.writeDouble(mProportionalSystemServiceUsage);
        }

        void readJobCompletionsFromParcelLocked(Parcel in) {
            int numJobCompletions = in.readInt();
            mJobCompletions.clear();
            for (int j = 0; j < numJobCompletions; j++) {
                String jobName = in.readString();
                int numTypes = in.readInt();
                if (numTypes > 0) {
                    SparseIntArray types = new SparseIntArray();
                    for (int k = 0; k < numTypes; k++) {
                        int type = in.readInt();
                        int count = in.readInt();
                        types.put(type, count);
                    }
                    mJobCompletions.put(jobName, types);
                }
            }
        }

        @GuardedBy("mBsi")
        void readFromParcelLocked(TimeBase timeBase, TimeBase screenOffTimeBase, Parcel in) {
            final long timestampMs = mBsi.mClock.elapsedRealtime();
            mOnBatteryBackgroundTimeBase.readFromParcel(in);
            mOnBatteryScreenOffBackgroundTimeBase.readFromParcel(in);

            int numWakelocks = in.readInt();
            mWakelockStats.clear();
            for (int j = 0; j < numWakelocks; j++) {
                String wakelockName = in.readString();
                Uid.Wakelock wakelock = new Wakelock(mBsi, this);
                wakelock.readFromParcelLocked(
                        timeBase, screenOffTimeBase, mOnBatteryScreenOffBackgroundTimeBase, in);
                mWakelockStats.add(wakelockName, wakelock);
            }

            int numSyncs = in.readInt();
            mSyncStats.clear();
            for (int j = 0; j < numSyncs; j++) {
                String syncName = in.readString();
                if (in.readInt() != 0) {
                    mSyncStats.add(syncName, new DualTimer(mBsi.mClock, Uid.this, SYNC, null,
                            mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase, in));
                }
            }

            int numJobs = in.readInt();
            mJobStats.clear();
            for (int j = 0; j < numJobs; j++) {
                String jobName = in.readString();
                if (in.readInt() != 0) {
                    mJobStats.add(jobName, new DualTimer(mBsi.mClock, Uid.this, JOB, null,
                            mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase, in));
                }
            }

            readJobCompletionsFromParcelLocked(in);

            mJobsDeferredEventCount = new Counter(mBsi.mOnBatteryTimeBase, in);
            mJobsDeferredCount = new Counter(mBsi.mOnBatteryTimeBase, in);
            mJobsFreshnessTimeMs = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);
            for (int i = 0; i < JOB_FRESHNESS_BUCKETS.length; i++) {
                mJobsFreshnessBuckets[i] = Counter.readCounterFromParcel(mBsi.mOnBatteryTimeBase,
                        in);
            }

            int numSensors = in.readInt();
            mSensorStats.clear();
            for (int k = 0; k < numSensors; k++) {
                int sensorNumber = in.readInt();
                Uid.Sensor sensor = new Sensor(mBsi, this, sensorNumber);
                sensor.readFromParcelLocked(mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase,
                        in);
                mSensorStats.put(sensorNumber, sensor);
            }

            int numProcs = in.readInt();
            mProcessStats.clear();
            for (int k = 0; k < numProcs; k++) {
                String processName = in.readString();
                Uid.Proc proc = new Proc(mBsi, processName);
                proc.readFromParcelLocked(in);
                mProcessStats.put(processName, proc);
            }

            int numPkgs = in.readInt();
            mPackageStats.clear();
            for (int l = 0; l < numPkgs; l++) {
                String packageName = in.readString();
                Uid.Pkg pkg = new Pkg(mBsi);
                pkg.readFromParcelLocked(in);
                mPackageStats.put(packageName, pkg);
            }

            mWifiRunning = false;
            if (in.readInt() != 0) {
                mWifiRunningTimer = new StopwatchTimer(mBsi.mClock, Uid.this, WIFI_RUNNING,
                        mBsi.mWifiRunningTimers, mBsi.mOnBatteryTimeBase, in);
            } else {
                mWifiRunningTimer = null;
            }
            mFullWifiLockOut = false;
            if (in.readInt() != 0) {
                mFullWifiLockTimer = new StopwatchTimer(mBsi.mClock, Uid.this, FULL_WIFI_LOCK,
                        mBsi.mFullWifiLockTimers, mBsi.mOnBatteryTimeBase, in);
            } else {
                mFullWifiLockTimer = null;
            }
            mWifiScanStarted = false;
            if (in.readInt() != 0) {
                mWifiScanTimer = new DualTimer(mBsi.mClock, Uid.this, WIFI_SCAN,
                        mBsi.mWifiScanTimers, mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase,
                        in);
            } else {
                mWifiScanTimer = null;
            }
            mWifiBatchedScanBinStarted = NO_BATCHED_SCAN_STARTED;
            for (int i = 0; i < NUM_WIFI_BATCHED_SCAN_BINS; i++) {
                if (in.readInt() != 0) {
                    makeWifiBatchedScanBin(i, in);
                } else {
                    mWifiBatchedScanTimer[i] = null;
                }
            }
            mWifiMulticastWakelockCount = 0;
            if (in.readInt() != 0) {
                mWifiMulticastTimer = new StopwatchTimer(mBsi.mClock, Uid.this,
                        WIFI_MULTICAST_ENABLED,
                        mBsi.mWifiMulticastTimers, mBsi.mOnBatteryTimeBase, in);
            } else {
                mWifiMulticastTimer = null;
            }
            if (in.readInt() != 0) {
                mAudioTurnedOnTimer = new StopwatchTimer(mBsi.mClock, Uid.this, AUDIO_TURNED_ON,
                        mBsi.mAudioTurnedOnTimers, mBsi.mOnBatteryTimeBase, in);
            } else {
                mAudioTurnedOnTimer = null;
            }
            if (in.readInt() != 0) {
                mVideoTurnedOnTimer = new StopwatchTimer(mBsi.mClock, Uid.this, VIDEO_TURNED_ON,
                        mBsi.mVideoTurnedOnTimers, mBsi.mOnBatteryTimeBase, in);
            } else {
                mVideoTurnedOnTimer = null;
            }
            if (in.readInt() != 0) {
                mFlashlightTurnedOnTimer = new StopwatchTimer(mBsi.mClock, Uid.this,
                        FLASHLIGHT_TURNED_ON, mBsi.mFlashlightTurnedOnTimers, mBsi.mOnBatteryTimeBase, in);
            } else {
                mFlashlightTurnedOnTimer = null;
            }
            if (in.readInt() != 0) {
                mCameraTurnedOnTimer = new StopwatchTimer(mBsi.mClock, Uid.this, CAMERA_TURNED_ON,
                        mBsi.mCameraTurnedOnTimers, mBsi.mOnBatteryTimeBase, in);
            } else {
                mCameraTurnedOnTimer = null;
            }
            if (in.readInt() != 0) {
                mForegroundActivityTimer = new StopwatchTimer(mBsi.mClock, Uid.this,
                        FOREGROUND_ACTIVITY, null, mBsi.mOnBatteryTimeBase, in);
            } else {
                mForegroundActivityTimer = null;
            }
            if (in.readInt() != 0) {
                mForegroundServiceTimer = new StopwatchTimer(mBsi.mClock, Uid.this,
                        FOREGROUND_SERVICE, null, mBsi.mOnBatteryTimeBase, in);
            } else {
                mForegroundServiceTimer = null;
            }
            if (in.readInt() != 0) {
                mAggregatedPartialWakelockTimer = new DualTimer(mBsi.mClock, this,
                        AGGREGATED_WAKE_TYPE_PARTIAL, null,
                        mBsi.mOnBatteryScreenOffTimeBase, mOnBatteryScreenOffBackgroundTimeBase,
                        in);
            } else {
                mAggregatedPartialWakelockTimer = null;
            }
            if (in.readInt() != 0) {
                mBluetoothScanTimer = new DualTimer(mBsi.mClock, Uid.this, BLUETOOTH_SCAN_ON,
                        mBsi.mBluetoothScanOnTimers, mBsi.mOnBatteryTimeBase,
                        mOnBatteryBackgroundTimeBase, in);
            } else {
                mBluetoothScanTimer = null;
            }
            if (in.readInt() != 0) {
                mBluetoothUnoptimizedScanTimer = new DualTimer(mBsi.mClock, Uid.this,
                        BLUETOOTH_UNOPTIMIZED_SCAN_ON, null,
                        mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase, in);
            } else {
                mBluetoothUnoptimizedScanTimer = null;
            }
            if (in.readInt() != 0) {
                mBluetoothScanResultCounter = new Counter(mBsi.mOnBatteryTimeBase, in);
            } else {
                mBluetoothScanResultCounter = null;
            }
            if (in.readInt() != 0) {
                mBluetoothScanResultBgCounter = new Counter(mOnBatteryBackgroundTimeBase, in);
            } else {
                mBluetoothScanResultBgCounter = null;
            }
            mProcessState = Uid.PROCESS_STATE_NONEXISTENT;
            for (int i = 0; i < NUM_PROCESS_STATE; i++) {
                if (in.readInt() != 0) {
                    makeProcessState(i, in);
                } else {
                    mProcessStateTimer[i] = null;
                }
            }
            if (in.readInt() != 0) {
                mVibratorOnTimer = new BatchTimer(mBsi.mClock, Uid.this, VIBRATOR_ON,
                        mBsi.mOnBatteryTimeBase, in);
            } else {
                mVibratorOnTimer = null;
            }
            if (in.readInt() != 0) {
                mUserActivityCounters = new Counter[NUM_USER_ACTIVITY_TYPES];
                for (int i=0; i<NUM_USER_ACTIVITY_TYPES; i++) {
                    mUserActivityCounters[i] = new Counter(mBsi.mOnBatteryTimeBase, in);
                }
            } else {
                mUserActivityCounters = null;
            }
            if (in.readInt() != 0) {
                mNetworkByteActivityCounters = new LongSamplingCounter[NUM_NETWORK_ACTIVITY_TYPES];
                mNetworkPacketActivityCounters
                        = new LongSamplingCounter[NUM_NETWORK_ACTIVITY_TYPES];
                for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
                    mNetworkByteActivityCounters[i]
                            = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);
                    mNetworkPacketActivityCounters[i]
                            = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);
                }
                if (in.readBoolean()) {
                    mMobileRadioActiveTime = TimeMultiStateCounter.readFromParcel(in,
                            mBsi.mOnBatteryTimeBase, BatteryConsumer.PROCESS_STATE_COUNT,
                            timestampMs);
                }

                mMobileRadioActiveCount = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);
            } else {
                mNetworkByteActivityCounters = null;
                mNetworkPacketActivityCounters = null;
            }

            if (in.readInt() != 0) {
                mWifiControllerActivity = new ControllerActivityCounterImpl(mBsi.mClock,
                        mBsi.mOnBatteryTimeBase, NUM_WIFI_TX_LEVELS, in);
            } else {
                mWifiControllerActivity = null;
            }

            if (in.readInt() != 0) {
                mBluetoothControllerActivity = new ControllerActivityCounterImpl(mBsi.mClock,
                        mBsi.mOnBatteryTimeBase, NUM_BT_TX_LEVELS, in);
            } else {
                mBluetoothControllerActivity = null;
            }

            if (in.readInt() != 0) {
                mModemControllerActivity = new ControllerActivityCounterImpl(mBsi.mClock,
                        mBsi.mOnBatteryTimeBase, ModemActivityInfo.getNumTxPowerLevels(), in);
            } else {
                mModemControllerActivity = null;
            }

            if (in.readInt() != 0) {
                mUidMeasuredEnergyStats = new MeasuredEnergyStats(mBsi.mMeasuredEnergyStatsConfig,
                        in);
            }

            mUserCpuTime = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);
            mSystemCpuTime = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);

            mCpuClusterSpeedTimesUs = mBsi.readCpuSpeedCountersFromParcel(in);

            mCpuFreqTimeMs = LongSamplingCounterArray.readFromParcel(in, mBsi.mOnBatteryTimeBase);
            mScreenOffCpuFreqTimeMs = LongSamplingCounterArray.readFromParcel(
                    in, mBsi.mOnBatteryScreenOffTimeBase);

            int stateCount = in.readInt();
            if (stateCount != 0) {
                mCpuActiveTimeMs = TimeMultiStateCounter.readFromParcel(in,
                        mBsi.mOnBatteryTimeBase, BatteryConsumer.PROCESS_STATE_COUNT,
                        timestampMs);
            }
            mCpuClusterTimesMs = new LongSamplingCounterArray(mBsi.mOnBatteryTimeBase, in);

            stateCount = in.readInt();
            if (stateCount != 0) {
                mProcStateTimeMs = TimeInFreqMultiStateCounter.readFromParcel(in,
                        mBsi.mOnBatteryTimeBase, PROC_STATE_TIME_COUNTER_STATE_COUNT,
                        mBsi.getCpuFreqCount(), mBsi.mClock.elapsedRealtime());
            } else {
                mProcStateTimeMs = null;
            }

            stateCount = in.readInt();
            if (stateCount != 0) {
                mProcStateScreenOffTimeMs = TimeInFreqMultiStateCounter.readFromParcel(in,
                        mBsi.mOnBatteryScreenOffTimeBase, PROC_STATE_TIME_COUNTER_STATE_COUNT,
                        mBsi.getCpuFreqCount(), mBsi.mClock.elapsedRealtime());
            } else {
                mProcStateScreenOffTimeMs = null;
            }

            if (in.readInt() != 0) {
                mMobileRadioApWakeupCount = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);
            } else {
                mMobileRadioApWakeupCount = null;
            }

            if (in.readInt() != 0) {
                mWifiRadioApWakeupCount = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);
            } else {
                mWifiRadioApWakeupCount = null;
            }

            mProportionalSystemServiceUsage = in.readDouble();
        }

        public void noteJobsDeferredLocked(int numDeferred, long sinceLast) {
            mJobsDeferredEventCount.addAtomic(1);
            mJobsDeferredCount.addAtomic(numDeferred);
            if (sinceLast != 0) {
                // Add the total time, which can be divided by the event count to get an average
                mJobsFreshnessTimeMs.addCountLocked(sinceLast);
                // Also keep track of how many times there were in these different buckets.
                for (int i = 0; i < JOB_FRESHNESS_BUCKETS.length; i++) {
                    if (sinceLast < JOB_FRESHNESS_BUCKETS[i]) {
                        if (mJobsFreshnessBuckets[i] == null) {
                            mJobsFreshnessBuckets[i] = new Counter(
                                    mBsi.mOnBatteryTimeBase);
                        }
                        mJobsFreshnessBuckets[i].addAtomic(1);
                        break;
                    }
                }
            }
        }

        // Reusable object used as a key to lookup values in mBinderCallStats
        private static BinderCallStats sTempBinderCallStats = new BinderCallStats();

        /**
         * Notes incoming binder call stats associated with this work source UID.
         */
        public void noteBinderCallStatsLocked(long incrementalCallCount,
                Collection<BinderCallsStats.CallStat> callStats) {
            if (DEBUG) {
                Slog.d(TAG, "noteBinderCalls() workSourceUid = [" + mUid + "], "
                        + " incrementalCallCount: " + incrementalCallCount + " callStats = ["
                        + new ArrayList<>(callStats) + "]");
            }
            mBinderCallCount += incrementalCallCount;
            for (BinderCallsStats.CallStat stat : callStats) {
                BinderCallStats bcs;
                sTempBinderCallStats.binderClass = stat.binderClass;
                sTempBinderCallStats.transactionCode = stat.transactionCode;
                int index = mBinderCallStats.indexOf(sTempBinderCallStats);
                if (index >= 0) {
                    bcs = mBinderCallStats.valueAt(index);
                } else {
                    bcs = new BinderCallStats();
                    bcs.binderClass = stat.binderClass;
                    bcs.transactionCode = stat.transactionCode;
                    mBinderCallStats.add(bcs);
                }

                bcs.callCount += stat.incrementalCallCount;
                bcs.recordedCallCount = stat.recordedCallCount;
                bcs.recordedCpuTimeMicros = stat.cpuTimeMicros;
            }
        }

        /**
         * The statistics associated with a particular wake lock.
         */
        public static class Wakelock extends BatteryStats.Uid.Wakelock {
            /**
             * BatteryStatsImpl that we are associated with.
             */
            protected BatteryStatsImpl mBsi;

            /**
             * BatteryStatsImpl that we are associated with.
             */
            protected Uid mUid;

            /**
             * How long (in ms) this uid has been keeping the device partially awake.
             * Tracks both the total time and the time while the app was in the background.
             */
            DualTimer mTimerPartial;

            /**
             * How long (in ms) this uid has been keeping the device fully awake.
             */
            StopwatchTimer mTimerFull;

            /**
             * How long (in ms) this uid has had a window keeping the device awake.
             */
            StopwatchTimer mTimerWindow;

            /**
             * How long (in ms) this uid has had a draw wake lock.
             */
            StopwatchTimer mTimerDraw;

            public Wakelock(BatteryStatsImpl bsi, Uid uid) {
                mBsi = bsi;
                mUid = uid;
            }

            /**
             * Reads a possibly null Timer from a Parcel.  The timer is associated with the
             * proper timer pool from the given BatteryStatsImpl object.
             *
             * @param in the Parcel to be read from.
             * return a new Timer, or null.
             */
            private StopwatchTimer readStopwatchTimerFromParcel(int type,
                    ArrayList<StopwatchTimer> pool, TimeBase timeBase, Parcel in) {
                if (in.readInt() == 0) {
                    return null;
                }

                return new StopwatchTimer(mBsi.mClock, mUid, type, pool, timeBase, in);
            }

            /**
             * Reads a possibly null Timer from a Parcel.  The timer is associated with the
             * proper timer pool from the given BatteryStatsImpl object.
             *
             * @param in the Parcel to be read from.
             * return a new Timer, or null.
             */
            private DualTimer readDualTimerFromParcel(int type, ArrayList<StopwatchTimer> pool,
                    TimeBase timeBase, TimeBase bgTimeBase, Parcel in) {
                if (in.readInt() == 0) {
                    return null;
                }

                return new DualTimer(mBsi.mClock, mUid, type, pool, timeBase, bgTimeBase, in);
            }

            boolean reset(long elapsedRealtimeUs) {
                boolean wlactive = false;

                wlactive |= !resetIfNotNull(mTimerFull, false, elapsedRealtimeUs);
                wlactive |= !resetIfNotNull(mTimerPartial, false, elapsedRealtimeUs);
                wlactive |= !resetIfNotNull(mTimerWindow, false, elapsedRealtimeUs);
                wlactive |= !resetIfNotNull(mTimerDraw, false, elapsedRealtimeUs);

                if (!wlactive) {
                    detachIfNotNull(mTimerFull);
                    mTimerFull = null;

                    detachIfNotNull(mTimerPartial);
                    mTimerPartial = null;

                    detachIfNotNull(mTimerWindow);
                    mTimerWindow = null;

                    detachIfNotNull(mTimerDraw);
                    mTimerDraw = null;
                }
                return !wlactive;
            }

            void readFromParcelLocked(TimeBase timeBase, TimeBase screenOffTimeBase,
                    TimeBase screenOffBgTimeBase, Parcel in) {
                mTimerPartial = readDualTimerFromParcel(WAKE_TYPE_PARTIAL,
                        mBsi.mPartialTimers, screenOffTimeBase, screenOffBgTimeBase, in);
                mTimerFull = readStopwatchTimerFromParcel(WAKE_TYPE_FULL,
                        mBsi.mFullTimers, timeBase, in);
                mTimerWindow = readStopwatchTimerFromParcel(WAKE_TYPE_WINDOW,
                        mBsi.mWindowTimers, timeBase, in);
                mTimerDraw = readStopwatchTimerFromParcel(WAKE_TYPE_DRAW,
                        mBsi.mDrawTimers, timeBase, in);
            }

            void writeToParcelLocked(Parcel out, long elapsedRealtimeUs) {
                Timer.writeTimerToParcel(out, mTimerPartial, elapsedRealtimeUs);
                Timer.writeTimerToParcel(out, mTimerFull, elapsedRealtimeUs);
                Timer.writeTimerToParcel(out, mTimerWindow, elapsedRealtimeUs);
                Timer.writeTimerToParcel(out, mTimerDraw, elapsedRealtimeUs);
            }

            @Override
            @UnsupportedAppUsage
            public Timer getWakeTime(int type) {
                switch (type) {
                case WAKE_TYPE_FULL: return mTimerFull;
                case WAKE_TYPE_PARTIAL: return mTimerPartial;
                case WAKE_TYPE_WINDOW: return mTimerWindow;
                case WAKE_TYPE_DRAW: return mTimerDraw;
                default: throw new IllegalArgumentException("type = " + type);
                }
            }

            public void detachFromTimeBase() {
                detachIfNotNull(mTimerPartial);
                detachIfNotNull(mTimerFull);
                detachIfNotNull(mTimerWindow);
                detachIfNotNull(mTimerDraw);
            }
        }

        public static class Sensor extends BatteryStats.Uid.Sensor {
            /**
             * BatteryStatsImpl that we are associated with.
             */
            protected BatteryStatsImpl mBsi;

            /**
             * Uid that we are associated with.
             */
            protected Uid mUid;

            final int mHandle;
            DualTimer mTimer;

            public Sensor(BatteryStatsImpl bsi, Uid uid, int handle) {
                mBsi = bsi;
                mUid = uid;
                mHandle = handle;
            }

            private DualTimer readTimersFromParcel(
                    TimeBase timeBase, TimeBase bgTimeBase, Parcel in) {
                if (in.readInt() == 0) {
                    return null;
                }

                ArrayList<StopwatchTimer> pool = mBsi.mSensorTimers.get(mHandle);
                if (pool == null) {
                    pool = new ArrayList<StopwatchTimer>();
                    mBsi.mSensorTimers.put(mHandle, pool);
                }
                return new DualTimer(mBsi.mClock, mUid, 0, pool, timeBase, bgTimeBase, in);
            }

            boolean reset(long elapsedRealtimeUs) {
                if (mTimer.reset(true, elapsedRealtimeUs)) {
                    mTimer = null;
                    return true;
                }
                return false;
            }

            void readFromParcelLocked(TimeBase timeBase, TimeBase bgTimeBase, Parcel in) {
                mTimer = readTimersFromParcel(timeBase, bgTimeBase, in);
            }

            void writeToParcelLocked(Parcel out, long elapsedRealtimeUs) {
                Timer.writeTimerToParcel(out, mTimer, elapsedRealtimeUs);
            }

            @Override
            @UnsupportedAppUsage
            public Timer getSensorTime() {
                return mTimer;
            }

            @Override
            public Timer getSensorBackgroundTime() {
                if (mTimer == null) {
                    return null;
                }
                return mTimer.getSubTimer();
            }

            @Override
            @UnsupportedAppUsage
            public int getHandle() {
                return mHandle;
            }

            public void  detachFromTimeBase() {
                detachIfNotNull(mTimer);
            }
        }

        /**
         * The statistics associated with a particular process.
         */
        public static class Proc extends BatteryStats.Uid.Proc implements TimeBaseObs {
            /**
             * BatteryStatsImpl that we are associated with.
             */
            protected BatteryStatsImpl mBsi;

            /**
             * The name of this process.
             */
            final String mName;

            /**
             * Remains true until removed from the stats.
             */
            boolean mActive = true;

            /**
             * Total time (in ms) spent executing in user code.
             */
            long mUserTimeMs;

            /**
             * Total time (in ms) spent executing in kernel code.
             */
            long mSystemTimeMs;

            /**
             * Amount of time (in ms) the process was running in the foreground.
             */
            long mForegroundTimeMs;

            /**
             * Number of times the process has been started.
             */
            int mStarts;

            /**
             * Number of times the process has crashed.
             */
            int mNumCrashes;

            /**
             * Number of times the process has had an ANR.
             */
            int mNumAnrs;

            ArrayList<ExcessivePower> mExcessivePower;

            public Proc(BatteryStatsImpl bsi, String name) {
                mBsi = bsi;
                mName = name;
                mBsi.mOnBatteryTimeBase.add(this);
            }

            public void onTimeStarted(long elapsedRealtimeUs, long baseUptimeUs,
                    long baseRealtimeUs) {
            }

            public void onTimeStopped(long elapsedRealtimeUs, long baseUptimeUs,
                    long baseRealtimeUs) {
            }

            @Override
            public boolean reset(boolean detachIfReset, long elapsedRealtimeUs) {
                if (detachIfReset) {
                    this.detach();
                }
                return true;
            }

            @Override
            public void detach() {
                mActive = false;
                mBsi.mOnBatteryTimeBase.remove(this);
            }

            public int countExcessivePowers() {
                return mExcessivePower != null ? mExcessivePower.size() : 0;
            }

            public ExcessivePower getExcessivePower(int i) {
                if (mExcessivePower != null) {
                    return mExcessivePower.get(i);
                }
                return null;
            }

            public void addExcessiveCpu(long overTimeMs, long usedTimeMs) {
                if (mExcessivePower == null) {
                    mExcessivePower = new ArrayList<ExcessivePower>();
                }
                ExcessivePower ew = new ExcessivePower();
                ew.type = ExcessivePower.TYPE_CPU;
                ew.overTime = overTimeMs;
                ew.usedTime = usedTimeMs;
                mExcessivePower.add(ew);
            }

            void writeExcessivePowerToParcelLocked(Parcel out) {
                if (mExcessivePower == null) {
                    out.writeInt(0);
                    return;
                }

                final int N = mExcessivePower.size();
                out.writeInt(N);
                for (int i=0; i<N; i++) {
                    ExcessivePower ew = mExcessivePower.get(i);
                    out.writeInt(ew.type);
                    out.writeLong(ew.overTime);
                    out.writeLong(ew.usedTime);
                }
            }

            void readExcessivePowerFromParcelLocked(Parcel in) {
                final int N = in.readInt();
                if (N == 0) {
                    mExcessivePower = null;
                    return;
                }

                if (N > 10000) {
                    throw new ParcelFormatException(
                            "File corrupt: too many excessive power entries " + N);
                }

                mExcessivePower = new ArrayList<>();
                for (int i=0; i<N; i++) {
                    ExcessivePower ew = new ExcessivePower();
                    ew.type = in.readInt();
                    ew.overTime = in.readLong();
                    ew.usedTime = in.readLong();
                    mExcessivePower.add(ew);
                }
            }

            void writeToParcelLocked(Parcel out) {
                out.writeLong(mUserTimeMs);
                out.writeLong(mSystemTimeMs);
                out.writeLong(mForegroundTimeMs);
                out.writeInt(mStarts);
                out.writeInt(mNumCrashes);
                out.writeInt(mNumAnrs);
                writeExcessivePowerToParcelLocked(out);
            }

            void readFromParcelLocked(Parcel in) {
                mUserTimeMs = in.readLong();
                mSystemTimeMs = in.readLong();
                mForegroundTimeMs = in.readLong();
                mStarts = in.readInt();
                mNumCrashes = in.readInt();
                mNumAnrs = in.readInt();
                readExcessivePowerFromParcelLocked(in);
            }

            @UnsupportedAppUsage
            public void addCpuTimeLocked(int utimeMs, int stimeMs) {
                addCpuTimeLocked(utimeMs, stimeMs, mBsi.mOnBatteryTimeBase.isRunning());
            }

            public void addCpuTimeLocked(int utimeMs, int stimeMs, boolean isRunning) {
                if (isRunning) {
                    mUserTimeMs += utimeMs;
                    mSystemTimeMs += stimeMs;
                }
            }

            @UnsupportedAppUsage
            public void addForegroundTimeLocked(long ttimeMs) {
                mForegroundTimeMs += ttimeMs;
            }

            @UnsupportedAppUsage
            public void incStartsLocked() {
                mStarts++;
            }

            public void incNumCrashesLocked() {
                mNumCrashes++;
            }

            public void incNumAnrsLocked() {
                mNumAnrs++;
            }

            @Override
            public boolean isActive() {
                return mActive;
            }

            @Override
            @UnsupportedAppUsage
            public long getUserTime(int which) {
                return mUserTimeMs;
            }

            @Override
            @UnsupportedAppUsage
            public long getSystemTime(int which) {
                return mSystemTimeMs;
            }

            @Override
            @UnsupportedAppUsage
            public long getForegroundTime(int which) {
                return mForegroundTimeMs;
            }

            @Override
            @UnsupportedAppUsage
            public int getStarts(int which) {
                return mStarts;
            }

            @Override
            public int getNumCrashes(int which) {
                return mNumCrashes;
            }

            @Override
            public int getNumAnrs(int which) {
                return mNumAnrs;
            }
        }

        /**
         * The statistics associated with a particular package.
         */
        public static class Pkg extends BatteryStats.Uid.Pkg implements TimeBaseObs {
            /**
             * BatteryStatsImpl that we are associated with.
             */
            protected BatteryStatsImpl mBsi;

            /**
             * Number of times wakeup alarms have occurred for this app.
             * On screen-off timebase starting in report v25.
             */
            ArrayMap<String, Counter> mWakeupAlarms = new ArrayMap<>();

            /**
             * The statics we have collected for this package's services.
             */
            final ArrayMap<String, Serv> mServiceStats = new ArrayMap<>();

            public Pkg(BatteryStatsImpl bsi) {
                mBsi = bsi;
                mBsi.mOnBatteryScreenOffTimeBase.add(this);
            }

            public void onTimeStarted(long elapsedRealtimeUs, long baseUptimeUs,
                    long baseRealtimeUs) {
            }

            public void onTimeStopped(long elapsedRealtimeUs, long baseUptimeUs,
                    long baseRealtimeUs) {
            }

            @Override
            public boolean reset(boolean detachIfReset, long elapsedRealtimeUs) {
                if (detachIfReset) {
                    this.detach();
                }
                return true;
            }

            @Override
            public void detach() {
                mBsi.mOnBatteryScreenOffTimeBase.remove(this);
                for (int j = mWakeupAlarms.size() - 1; j >= 0; j--) {
                    detachIfNotNull(mWakeupAlarms.valueAt(j));
                }
                for (int j = mServiceStats.size() - 1; j >= 0; j--) {
                    detachIfNotNull(mServiceStats.valueAt(j));
                }
            }

            void readFromParcelLocked(Parcel in) {
                int numWA = in.readInt();
                mWakeupAlarms.clear();
                for (int i=0; i<numWA; i++) {
                    String tag = in.readString();
                    mWakeupAlarms.put(tag, new Counter(mBsi.mOnBatteryScreenOffTimeBase, in));
                }

                int numServs = in.readInt();
                mServiceStats.clear();
                for (int m = 0; m < numServs; m++) {
                    String serviceName = in.readString();
                    Uid.Pkg.Serv serv = new Serv(mBsi);
                    mServiceStats.put(serviceName, serv);

                    serv.readFromParcelLocked(in);
                }
            }

            void writeToParcelLocked(Parcel out) {
                int numWA = mWakeupAlarms.size();
                out.writeInt(numWA);
                for (int i=0; i<numWA; i++) {
                    out.writeString(mWakeupAlarms.keyAt(i));
                    mWakeupAlarms.valueAt(i).writeToParcel(out);
                }

                final int NS = mServiceStats.size();
                out.writeInt(NS);
                for (int i=0; i<NS; i++) {
                    out.writeString(mServiceStats.keyAt(i));
                    Uid.Pkg.Serv serv = mServiceStats.valueAt(i);
                    serv.writeToParcelLocked(out);
                }
            }

            @Override
            public ArrayMap<String, ? extends BatteryStats.Counter> getWakeupAlarmStats() {
                return mWakeupAlarms;
            }

            public void noteWakeupAlarmLocked(String tag) {
                Counter c = mWakeupAlarms.get(tag);
                if (c == null) {
                    c = new Counter(mBsi.mOnBatteryScreenOffTimeBase);
                    mWakeupAlarms.put(tag, c);
                }
                c.stepAtomic();
            }

            @Override
            public ArrayMap<String, ? extends BatteryStats.Uid.Pkg.Serv> getServiceStats() {
                return mServiceStats;
            }

            /**
             * The statistics associated with a particular service.
             */
            public static class Serv extends BatteryStats.Uid.Pkg.Serv implements TimeBaseObs {
                /**
                 * BatteryStatsImpl that we are associated with.
                 */
                protected BatteryStatsImpl mBsi;

                /**
                 * The android package in which this service resides.
                 */
                protected Pkg mPkg;

                /**
                 * Total time (ms in battery uptime) the service has been left started.
                 */
                protected long mStartTimeMs;

                /**
                 * If service has been started and not yet stopped, this is
                 * when it was started.
                 */
                protected long mRunningSinceMs;

                /**
                 * True if we are currently running.
                 */
                protected boolean mRunning;

                /**
                 * Total number of times startService() has been called.
                 */
                protected int mStarts;

                /**
                 * Total time (ms in battery uptime) the service has been left launched.
                 */
                protected long mLaunchedTimeMs;

                /**
                 * If service has been launched and not yet exited, this is
                 * when it was launched (ms in battery uptime).
                 */
                protected long mLaunchedSinceMs;

                /**
                 * True if we are currently launched.
                 */
                protected boolean mLaunched;

                /**
                 * Total number times the service has been launched.
                 */
                protected int mLaunches;

                /**
                 * Construct a Serv. Also adds it to the on-battery time base as a listener.
                 */
                public Serv(BatteryStatsImpl bsi) {
                    mBsi = bsi;
                    mBsi.mOnBatteryTimeBase.add(this);
                }

                public void onTimeStarted(long elapsedRealtimeUs, long baseUptimeUs,
                        long baseRealtimeUs) {
                }

                public void onTimeStopped(long elapsedRealtimeUs, long baseUptimeUs,
                        long baseRealtimeUs) {
                }

                @Override
                public boolean reset(boolean detachIfReset, long elapsedRealtimeUs) {
                    if (detachIfReset) {
                        this.detach();
                    }
                    return true;
                }

                /**
                 * Remove this Serv as a listener from the time base.
                 Ms*/
                @Override
                public void detach() {
                    mBsi.mOnBatteryTimeBase.remove(this);
                }

                public void readFromParcelLocked(Parcel in) {
                    mStartTimeMs = in.readLong();
                    mRunningSinceMs = in.readLong();
                    mRunning = in.readInt() != 0;
                    mStarts = in.readInt();
                    mLaunchedTimeMs = in.readLong();
                    mLaunchedSinceMs = in.readLong();
                    mLaunched = in.readInt() != 0;
                    mLaunches = in.readInt();
                }

                public void writeToParcelLocked(Parcel out) {
                    out.writeLong(mStartTimeMs);
                    out.writeLong(mRunningSinceMs);
                    out.writeInt(mRunning ? 1 : 0);
                    out.writeInt(mStarts);
                    out.writeLong(mLaunchedTimeMs);
                    out.writeLong(mLaunchedSinceMs);
                    out.writeInt(mLaunched ? 1 : 0);
                    out.writeInt(mLaunches);
                }

                public long getLaunchTimeToNowLocked(long batteryUptimeMs) {
                    if (!mLaunched) return mLaunchedTimeMs;
                    return mLaunchedTimeMs + batteryUptimeMs - mLaunchedSinceMs;
                }

                public long getStartTimeToNowLocked(long batteryUptimeMs) {
                    if (!mRunning) return mStartTimeMs;
                    return mStartTimeMs + batteryUptimeMs - mRunningSinceMs;
                }

                @UnsupportedAppUsage
                public void startLaunchedLocked() {
                    startLaunchedLocked(mBsi.mClock.uptimeMillis());
                }

                public void startLaunchedLocked(long uptimeMs) {
                    if (!mLaunched) {
                        mLaunches++;
                        mLaunchedSinceMs = mBsi.getBatteryUptimeLocked(uptimeMs) / 1000;
                        mLaunched = true;
                    }
                }

                @UnsupportedAppUsage
                public void stopLaunchedLocked() {
                    stopLaunchedLocked(mBsi.mClock.uptimeMillis());
                }

                public void stopLaunchedLocked(long uptimeMs) {
                    if (mLaunched) {
                        long timeMs = mBsi.getBatteryUptimeLocked(uptimeMs) / 1000
                                - mLaunchedSinceMs;
                        if (timeMs > 0) {
                            mLaunchedTimeMs += timeMs;
                        } else {
                            mLaunches--;
                        }
                        mLaunched = false;
                    }
                }

                @UnsupportedAppUsage
                public void startRunningLocked() {
                    startRunningLocked(mBsi.mClock.uptimeMillis());
                }

                public void startRunningLocked(long uptimeMs) {
                    if (!mRunning) {
                        mStarts++;
                        mRunningSinceMs = mBsi.getBatteryUptimeLocked(uptimeMs) / 1000;
                        mRunning = true;
                    }
                }

                @UnsupportedAppUsage
                public void stopRunningLocked() {
                    stopRunningLocked(mBsi.mClock.uptimeMillis());
                }

                public void stopRunningLocked(long uptimeMs) {
                    if (mRunning) {
                        long timeMs = mBsi.getBatteryUptimeLocked(uptimeMs) / 1000
                                - mRunningSinceMs;
                        if (timeMs > 0) {
                            mStartTimeMs += timeMs;
                        } else {
                            mStarts--;
                        }
                        mRunning = false;
                    }
                }

                @UnsupportedAppUsage
                public BatteryStatsImpl getBatteryStats() {
                    return mBsi;
                }

                @Override
                public int getLaunches(int which) {
                    return mLaunches;
                }

                @Override
                public long getStartTime(long now, int which) {
                    return getStartTimeToNowLocked(now);
                }

                @Override
                public int getStarts(int which) {
                    return mStarts;
                }
            }

            final Serv newServiceStatsLocked() {
                return new Serv(mBsi);
            }
        }

        private class ChildUid {
            public final TimeMultiStateCounter cpuActiveCounter;
            public final LongArrayMultiStateCounter cpuTimeInFreqCounter;

            ChildUid() {
                final long timestampMs = mBsi.mClock.elapsedRealtime();
                cpuActiveCounter =
                        new TimeMultiStateCounter(mBsi.mOnBatteryTimeBase, 1, timestampMs);
                cpuActiveCounter.setState(0, timestampMs);

                if (mBsi.trackPerProcStateCpuTimes()) {
                    final int cpuFreqCount = mBsi.getCpuFreqCount();

                    cpuTimeInFreqCounter = new LongArrayMultiStateCounter(1, cpuFreqCount);

                    // Set initial values to all 0. This is a child UID and we want to include
                    // the entirety of its CPU time-in-freq stats into the parent's stats.
                    cpuTimeInFreqCounter.updateValues(
                            new LongArrayMultiStateCounter.LongArrayContainer(cpuFreqCount),
                            timestampMs);
                } else {
                    cpuTimeInFreqCounter = null;
                }
            }
        }

        /**
         * Retrieve the statistics object for a particular process, creating
         * if needed.
         */
        public Proc getProcessStatsLocked(String name) {
            Proc ps = mProcessStats.get(name);
            if (ps == null) {
                ps = new Proc(mBsi, name);
                mProcessStats.put(name, ps);
            }

            return ps;
        }

        @GuardedBy("mBsi")
        public void updateUidProcessStateLocked(int procState,
                long elapsedRealtimeMs, long uptimeMs) {
            int uidRunningState;
            // Make special note of Foreground Services
            final boolean userAwareService =
                    (ActivityManager.isForegroundService(procState));
            uidRunningState = BatteryStats.mapToInternalProcessState(procState);

            if (mProcessState == uidRunningState && userAwareService == mInForegroundService) {
                return;
            }

            if (mProcessState != uidRunningState) {
                if (mProcessState != Uid.PROCESS_STATE_NONEXISTENT) {
                    mProcessStateTimer[mProcessState].stopRunningLocked(elapsedRealtimeMs);
                }
                if (uidRunningState != Uid.PROCESS_STATE_NONEXISTENT) {
                    if (mProcessStateTimer[uidRunningState] == null) {
                        makeProcessState(uidRunningState, null);
                    }
                    mProcessStateTimer[uidRunningState].startRunningLocked(elapsedRealtimeMs);
                }

                if (mBsi.trackPerProcStateCpuTimes()) {
                    mBsi.updateProcStateCpuTimesLocked(mUid, elapsedRealtimeMs);

                    LongArrayMultiStateCounter onBatteryCounter =
                            getProcStateTimeCounter(elapsedRealtimeMs).getCounter();
                    LongArrayMultiStateCounter onBatteryScreenOffCounter =
                            getProcStateScreenOffTimeCounter(elapsedRealtimeMs).getCounter();

                    onBatteryCounter.setState(uidRunningState, elapsedRealtimeMs);
                    onBatteryScreenOffCounter.setState(uidRunningState, elapsedRealtimeMs);
                }

                final int prevBatteryConsumerProcessState =
                        mapUidProcessStateToBatteryConsumerProcessState(mProcessState);

                mProcessState = uidRunningState;

                updateOnBatteryBgTimeBase(uptimeMs * 1000, elapsedRealtimeMs * 1000);
                updateOnBatteryScreenOffBgTimeBase(uptimeMs * 1000, elapsedRealtimeMs * 1000);

                final int batteryConsumerProcessState =
                        mapUidProcessStateToBatteryConsumerProcessState(uidRunningState);
                getCpuActiveTimeCounter().setState(batteryConsumerProcessState, elapsedRealtimeMs);

                getMobileRadioActiveTimeCounter()
                        .setState(batteryConsumerProcessState, elapsedRealtimeMs);

                final ControllerActivityCounterImpl wifiControllerActivity =
                        getWifiControllerActivity();
                if (wifiControllerActivity != null) {
                    wifiControllerActivity.setState(batteryConsumerProcessState, elapsedRealtimeMs);
                }

                final ControllerActivityCounterImpl bluetoothControllerActivity =
                        getBluetoothControllerActivity();
                if (bluetoothControllerActivity != null) {
                    bluetoothControllerActivity.setState(batteryConsumerProcessState,
                            elapsedRealtimeMs);
                }

                final MeasuredEnergyStats energyStats =
                        getOrCreateMeasuredEnergyStatsIfSupportedLocked();
                if (energyStats != null) {
                    energyStats.setState(batteryConsumerProcessState, elapsedRealtimeMs);
                }
                maybeScheduleExternalStatsSync(prevBatteryConsumerProcessState,
                        batteryConsumerProcessState);
            }

            if (userAwareService != mInForegroundService) {
                if (userAwareService) {
                    noteForegroundServiceResumedLocked(elapsedRealtimeMs);
                } else {
                    noteForegroundServicePausedLocked(elapsedRealtimeMs);
                }
                mInForegroundService = userAwareService;
            }
        }

        @GuardedBy("mBsi")
        private void maybeScheduleExternalStatsSync(
                @BatteryConsumer.ProcessState int oldProcessState,
                @BatteryConsumer.ProcessState int newProcessState) {
            if (oldProcessState == newProcessState) {
                return;
            }
            // Transitions between BACKGROUND and such non-foreground states like cached
            // or nonexistent do not warrant doing a sync.  If some of the stats for those
            // proc states bleed into the PROCESS_STATE_BACKGROUND, that's ok.
            if ((oldProcessState == BatteryConsumer.PROCESS_STATE_UNSPECIFIED
                    && newProcessState == BatteryConsumer.PROCESS_STATE_BACKGROUND)
                    || (oldProcessState == BatteryConsumer.PROCESS_STATE_BACKGROUND
                    && newProcessState == BatteryConsumer.PROCESS_STATE_UNSPECIFIED)) {
                return;
            }

            int flags = ExternalStatsSync.UPDATE_ON_PROC_STATE_CHANGE;
            // Skip querying for inactive radio, where power usage is probably negligible.
            if (!BatteryStatsImpl.isActiveRadioPowerState(mBsi.mMobileRadioPowerState)) {
                flags &= ~ExternalStatsSync.UPDATE_RADIO;
            }

            mBsi.mExternalSync.scheduleSyncDueToProcessStateChange(flags,
                    mBsi.mConstants.PROC_STATE_CHANGE_COLLECTION_DELAY_MS);
        }

        /** Whether to consider Uid to be in the background for background timebase purposes. */
        public boolean isInBackground() {
            // Note that PROCESS_STATE_CACHED and Uid.PROCESS_STATE_NONEXISTENT is
            // also considered to be 'background' for our purposes, because it's not foreground.
            return mProcessState >= PROCESS_STATE_BACKGROUND;
        }

        public boolean updateOnBatteryBgTimeBase(long uptimeUs, long realtimeUs) {
            boolean on = mBsi.mOnBatteryTimeBase.isRunning() && isInBackground();
            return mOnBatteryBackgroundTimeBase.setRunning(on, uptimeUs, realtimeUs);
        }

        public boolean updateOnBatteryScreenOffBgTimeBase(long uptimeUs, long realtimeUs) {
            boolean on = mBsi.mOnBatteryScreenOffTimeBase.isRunning() && isInBackground();
            return mOnBatteryScreenOffBackgroundTimeBase.setRunning(on, uptimeUs, realtimeUs);
        }

        public SparseArray<? extends Pid> getPidStats() {
            return mPids;
        }

        public Pid getPidStatsLocked(int pid) {
            Pid p = mPids.get(pid);
            if (p == null) {
                p = new Pid();
                mPids.put(pid, p);
            }
            return p;
        }

        /**
         * Retrieve the statistics object for a particular service, creating
         * if needed.
         */
        public Pkg getPackageStatsLocked(String name) {
            Pkg ps = mPackageStats.get(name);
            if (ps == null) {
                ps = new Pkg(mBsi);
                mPackageStats.put(name, ps);
            }

            return ps;
        }

        /**
         * Retrieve the statistics object for a particular service, creating
         * if needed.
         */
        public Pkg.Serv getServiceStatsLocked(String pkg, String serv) {
            Pkg ps = getPackageStatsLocked(pkg);
            Pkg.Serv ss = ps.mServiceStats.get(serv);
            if (ss == null) {
                ss = ps.newServiceStatsLocked();
                ps.mServiceStats.put(serv, ss);
            }

            return ss;
        }

        public void readSyncSummaryFromParcelLocked(String name, Parcel in) {
            DualTimer timer = mSyncStats.instantiateObject();
            timer.readSummaryFromParcelLocked(in);
            mSyncStats.add(name, timer);
        }

        public void readJobSummaryFromParcelLocked(String name, Parcel in) {
            DualTimer timer = mJobStats.instantiateObject();
            timer.readSummaryFromParcelLocked(in);
            mJobStats.add(name, timer);
        }

        public void readWakeSummaryFromParcelLocked(String wlName, Parcel in) {
            Wakelock wl = new Wakelock(mBsi, this);
            mWakelockStats.add(wlName, wl);
            if (in.readInt() != 0) {
                getWakelockTimerLocked(wl, WAKE_TYPE_FULL).readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                getWakelockTimerLocked(wl, WAKE_TYPE_PARTIAL).readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                getWakelockTimerLocked(wl, WAKE_TYPE_WINDOW).readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                getWakelockTimerLocked(wl, WAKE_TYPE_DRAW).readSummaryFromParcelLocked(in);
            }
        }

        public DualTimer getSensorTimerLocked(int sensor, boolean create) {
            Sensor se = mSensorStats.get(sensor);
            if (se == null) {
                if (!create) {
                    return null;
                }
                se = new Sensor(mBsi, this, sensor);
                mSensorStats.put(sensor, se);
            }
            DualTimer t = se.mTimer;
            if (t != null) {
                return t;
            }
            ArrayList<StopwatchTimer> timers = mBsi.mSensorTimers.get(sensor);
            if (timers == null) {
                timers = new ArrayList<StopwatchTimer>();
                mBsi.mSensorTimers.put(sensor, timers);
            }
            t = new DualTimer(mBsi.mClock, this, BatteryStats.SENSOR, timers,
                    mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase);
            se.mTimer = t;
            return t;
        }

        public void noteStartSyncLocked(String name, long elapsedRealtimeMs) {
            DualTimer t = mSyncStats.startObject(name, elapsedRealtimeMs);
            if (t != null) {
                t.startRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteStopSyncLocked(String name, long elapsedRealtimeMs) {
            DualTimer t = mSyncStats.stopObject(name, elapsedRealtimeMs);
            if (t != null) {
                t.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteStartJobLocked(String name, long elapsedRealtimeMs) {
            DualTimer t = mJobStats.startObject(name, elapsedRealtimeMs);
            if (t != null) {
                t.startRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteStopJobLocked(String name, long elapsedRealtimeMs, int stopReason) {
            DualTimer t = mJobStats.stopObject(name, elapsedRealtimeMs);
            if (t != null) {
                t.stopRunningLocked(elapsedRealtimeMs);
            }
            if (mBsi.mOnBatteryTimeBase.isRunning()) {
                SparseIntArray types = mJobCompletions.get(name);
                if (types == null) {
                    types = new SparseIntArray();
                    mJobCompletions.put(name, types);
                }
                int last = types.get(stopReason, 0);
                types.put(stopReason, last + 1);
            }
        }

        public StopwatchTimer getWakelockTimerLocked(Wakelock wl, int type) {
            if (wl == null) {
                return null;
            }
            switch (type) {
                case WAKE_TYPE_PARTIAL: {
                    DualTimer t = wl.mTimerPartial;
                    if (t == null) {
                        t = new DualTimer(mBsi.mClock, this, WAKE_TYPE_PARTIAL,
                                mBsi.mPartialTimers, mBsi.mOnBatteryScreenOffTimeBase,
                                mOnBatteryScreenOffBackgroundTimeBase);
                        wl.mTimerPartial = t;
                    }
                    return t;
                }
                case WAKE_TYPE_FULL: {
                    StopwatchTimer t = wl.mTimerFull;
                    if (t == null) {
                        t = new StopwatchTimer(mBsi.mClock, this, WAKE_TYPE_FULL,
                                mBsi.mFullTimers, mBsi.mOnBatteryTimeBase);
                        wl.mTimerFull = t;
                    }
                    return t;
                }
                case WAKE_TYPE_WINDOW: {
                    StopwatchTimer t = wl.mTimerWindow;
                    if (t == null) {
                        t = new StopwatchTimer(mBsi.mClock, this, WAKE_TYPE_WINDOW,
                                mBsi.mWindowTimers, mBsi.mOnBatteryTimeBase);
                        wl.mTimerWindow = t;
                    }
                    return t;
                }
                case WAKE_TYPE_DRAW: {
                    StopwatchTimer t = wl.mTimerDraw;
                    if (t == null) {
                        t = new StopwatchTimer(mBsi.mClock, this, WAKE_TYPE_DRAW,
                                mBsi.mDrawTimers, mBsi.mOnBatteryTimeBase);
                        wl.mTimerDraw = t;
                    }
                    return t;
                }
                default:
                    throw new IllegalArgumentException("type=" + type);
            }
        }

        public void noteStartWakeLocked(int pid, String name, int type, long elapsedRealtimeMs) {
            Wakelock wl = mWakelockStats.startObject(name, elapsedRealtimeMs);
            if (wl != null) {
                getWakelockTimerLocked(wl, type).startRunningLocked(elapsedRealtimeMs);
            }
            if (type == WAKE_TYPE_PARTIAL) {
                createAggregatedPartialWakelockTimerLocked().startRunningLocked(elapsedRealtimeMs);
                if (pid >= 0) {
                    Pid p = getPidStatsLocked(pid);
                    if (p.mWakeNesting++ == 0) {
                        p.mWakeStartMs = elapsedRealtimeMs;
                    }
                }
            }
        }

        public void noteStopWakeLocked(int pid, String name, int type, long elapsedRealtimeMs) {
            Wakelock wl = mWakelockStats.stopObject(name, elapsedRealtimeMs);
            if (wl != null) {
                StopwatchTimer wlt = getWakelockTimerLocked(wl, type);
                wlt.stopRunningLocked(elapsedRealtimeMs);
            }
            if (type == WAKE_TYPE_PARTIAL) {
                if (mAggregatedPartialWakelockTimer != null) {
                    mAggregatedPartialWakelockTimer.stopRunningLocked(elapsedRealtimeMs);
                }
                if (pid >= 0) {
                    Pid p = mPids.get(pid);
                    if (p != null && p.mWakeNesting > 0) {
                        if (p.mWakeNesting-- == 1) {
                            p.mWakeSumMs += elapsedRealtimeMs - p.mWakeStartMs;
                            p.mWakeStartMs = 0;
                        }
                    }
                }
            }
        }

        public void reportExcessiveCpuLocked(String proc, long overTimeMs, long usedTimeMs) {
            Proc p = getProcessStatsLocked(proc);
            if (p != null) {
                p.addExcessiveCpu(overTimeMs, usedTimeMs);
            }
        }

        public void noteStartSensor(int sensor, long elapsedRealtimeMs) {
            DualTimer t = getSensorTimerLocked(sensor, /* create= */ true);
            t.startRunningLocked(elapsedRealtimeMs);
        }

        public void noteStopSensor(int sensor, long elapsedRealtimeMs) {
            // Don't create a timer if one doesn't already exist
            DualTimer t = getSensorTimerLocked(sensor, false);
            if (t != null) {
                t.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteStartGps(long elapsedRealtimeMs) {
            noteStartSensor(Sensor.GPS, elapsedRealtimeMs);
        }

        public void noteStopGps(long elapsedRealtimeMs) {
            noteStopSensor(Sensor.GPS, elapsedRealtimeMs);
        }

        public BatteryStatsImpl getBatteryStats() {
            return mBsi;
        }
    }

    @GuardedBy("this")
    @Override
    public long[] getCpuFreqs() {
        if (!mCpuFreqsInitialized) {
            mCpuFreqs = mCpuUidFreqTimeReader.readFreqs(mPowerProfile);
            mCpuFreqsInitialized = true;
        }
        return mCpuFreqs;
    }

    @GuardedBy("this")
    @Override
    public int getCpuFreqCount() {
        final long[] cpuFreqs = getCpuFreqs();
        return cpuFreqs != null ? cpuFreqs.length : 0;
    }

    @GuardedBy("this")
    private LongArrayMultiStateCounter.LongArrayContainer getCpuTimeInFreqContainer() {
        if (mTmpCpuTimeInFreq == null) {
            mTmpCpuTimeInFreq =
                    new LongArrayMultiStateCounter.LongArrayContainer(getCpuFreqCount());
        }
        return mTmpCpuTimeInFreq;
    }

    public BatteryStatsImpl(File systemDir, Handler handler, PlatformIdleStateCallback cb,
            MeasuredEnergyRetriever energyStatsCb, UserInfoProvider userInfoProvider) {
        this(Clock.SYSTEM_CLOCK, systemDir, handler, cb, energyStatsCb, userInfoProvider);
    }

    private BatteryStatsImpl(Clock clock, File systemDir, Handler handler,
            PlatformIdleStateCallback cb, MeasuredEnergyRetriever energyStatsCb,
            UserInfoProvider userInfoProvider) {
        init(clock);

        if (systemDir == null) {
            mStatsFile = null;
            mBatteryStatsHistory = new BatteryStatsHistory(mHistoryBuffer);
        } else {
            mStatsFile = new AtomicFile(new File(systemDir, "batterystats.bin"));
            mBatteryStatsHistory = new BatteryStatsHistory(this, systemDir, mHistoryBuffer);
        }
        mCheckinFile = new AtomicFile(new File(systemDir, "batterystats-checkin.bin"));
        mDailyFile = new AtomicFile(new File(systemDir, "batterystats-daily.xml"));
        mHandler = new MyHandler(handler.getLooper());
        mConstants = new Constants(mHandler);
        mStartCount++;
        initTimersAndCounters();
        mOnBattery = mOnBatteryInternal = false;
        long uptimeUs = mClock.uptimeMillis() * 1000;
        long realtimeUs = mClock.elapsedRealtime() * 1000;
        initTimes(uptimeUs, realtimeUs);
        mStartPlatformVersion = mEndPlatformVersion = Build.ID;
        initDischarge(realtimeUs);
        clearHistoryLocked();
        updateDailyDeadlineLocked();
        mPlatformIdleStateCallback = cb;
        mMeasuredEnergyRetriever = energyStatsCb;
        mUserInfoProvider = userInfoProvider;

        // Notify statsd that the system is initially not in doze.
        mDeviceIdleMode = DEVICE_IDLE_MODE_OFF;
        FrameworkStatsLog.write(FrameworkStatsLog.DEVICE_IDLE_MODE_STATE_CHANGED, mDeviceIdleMode);
    }

    @VisibleForTesting
    protected void initTimersAndCounters() {
        mScreenOnTimer = new StopwatchTimer(mClock, null, -1, null, mOnBatteryTimeBase);
        mScreenDozeTimer = new StopwatchTimer(mClock, null, -1, null, mOnBatteryTimeBase);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i] = new StopwatchTimer(mClock, null, -100 - i, null,
                    mOnBatteryTimeBase);
        }

        mPerDisplayBatteryStats = new DisplayBatteryStats[1];
        mPerDisplayBatteryStats[0] = new DisplayBatteryStats(mClock, mOnBatteryTimeBase);

        mInteractiveTimer = new StopwatchTimer(mClock, null, -10, null, mOnBatteryTimeBase);
        mPowerSaveModeEnabledTimer = new StopwatchTimer(mClock, null, -2, null,
                mOnBatteryTimeBase);
        mDeviceIdleModeLightTimer = new StopwatchTimer(mClock, null, -11, null,
                mOnBatteryTimeBase);
        mDeviceIdleModeFullTimer = new StopwatchTimer(mClock, null, -14, null, mOnBatteryTimeBase);
        mDeviceLightIdlingTimer = new StopwatchTimer(mClock, null, -15, null, mOnBatteryTimeBase);
        mDeviceIdlingTimer = new StopwatchTimer(mClock, null, -12, null, mOnBatteryTimeBase);
        mPhoneOnTimer = new StopwatchTimer(mClock, null, -3, null, mOnBatteryTimeBase);
        for (int i = 0; i < CellSignalStrength.getNumSignalStrengthLevels(); i++) {
            mPhoneSignalStrengthsTimer[i] = new StopwatchTimer(mClock, null, -200 - i, null,
                    mOnBatteryTimeBase);
        }
        mPhoneSignalScanningTimer = new StopwatchTimer(mClock, null, -200 + 1, null,
                mOnBatteryTimeBase);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i] = new StopwatchTimer(mClock, null, -300 - i, null,
                    mOnBatteryTimeBase);
        }
        for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
            mNetworkByteActivityCounters[i] = new LongSamplingCounter(mOnBatteryTimeBase);
            mNetworkPacketActivityCounters[i] = new LongSamplingCounter(mOnBatteryTimeBase);
        }
        mWifiActivity = new ControllerActivityCounterImpl(mClock, mOnBatteryTimeBase,
                NUM_WIFI_TX_LEVELS);
        mBluetoothActivity = new ControllerActivityCounterImpl(mClock, mOnBatteryTimeBase,
                NUM_BT_TX_LEVELS);
        mModemActivity = new ControllerActivityCounterImpl(mClock, mOnBatteryTimeBase,
                ModemActivityInfo.getNumTxPowerLevels());
        mMobileRadioActiveTimer = new StopwatchTimer(mClock, null, -400, null, mOnBatteryTimeBase);
        mMobileRadioActivePerAppTimer = new StopwatchTimer(mClock, null, -401, null,
                mOnBatteryTimeBase);
        mMobileRadioActiveAdjustedTime = new LongSamplingCounter(mOnBatteryTimeBase);
        mMobileRadioActiveUnknownTime = new LongSamplingCounter(mOnBatteryTimeBase);
        mMobileRadioActiveUnknownCount = new LongSamplingCounter(mOnBatteryTimeBase);
        mWifiMulticastWakelockTimer = new StopwatchTimer(mClock, null,
                WIFI_AGGREGATE_MULTICAST_ENABLED, null, mOnBatteryTimeBase);
        mWifiOnTimer = new StopwatchTimer(mClock, null, -4, null, mOnBatteryTimeBase);
        mGlobalWifiRunningTimer = new StopwatchTimer(mClock, null, -5, null, mOnBatteryTimeBase);
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            mWifiStateTimer[i] = new StopwatchTimer(mClock, null, -600 - i, null,
                    mOnBatteryTimeBase);
        }
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            mWifiSupplStateTimer[i] = new StopwatchTimer(mClock, null, -700 - i, null,
                    mOnBatteryTimeBase);
        }
        for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            mWifiSignalStrengthsTimer[i] = new StopwatchTimer(mClock, null, -800 - i, null,
                    mOnBatteryTimeBase);
        }
        mWifiActiveTimer = new StopwatchTimer(mClock, null, -900, null, mOnBatteryTimeBase);
        for (int i=0; i< mGpsSignalQualityTimer.length; i++) {
            mGpsSignalQualityTimer[i] = new StopwatchTimer(mClock, null, -1000 - i, null,
                    mOnBatteryTimeBase);
        }
        mAudioOnTimer = new StopwatchTimer(mClock, null, -7, null, mOnBatteryTimeBase);
        mVideoOnTimer = new StopwatchTimer(mClock, null, -8, null, mOnBatteryTimeBase);
        mFlashlightOnTimer = new StopwatchTimer(mClock, null, -9, null, mOnBatteryTimeBase);
        mCameraOnTimer = new StopwatchTimer(mClock, null, -13, null, mOnBatteryTimeBase);
        mBluetoothScanTimer = new StopwatchTimer(mClock, null, -14, null, mOnBatteryTimeBase);
        mDischargeScreenOffCounter = new LongSamplingCounter(mOnBatteryScreenOffTimeBase);
        mDischargeScreenDozeCounter = new LongSamplingCounter(mOnBatteryTimeBase);
        mDischargeLightDozeCounter = new LongSamplingCounter(mOnBatteryTimeBase);
        mDischargeDeepDozeCounter = new LongSamplingCounter(mOnBatteryTimeBase);
        mDischargeCounter = new LongSamplingCounter(mOnBatteryTimeBase);
        mDischargeStartLevel = 0;
        mDischargeUnplugLevel = 0;
        mDischargePlugLevel = -1;
        mDischargeCurrentLevel = 0;
        mCurrentBatteryLevel = 0;
    }

    @UnsupportedAppUsage
    public BatteryStatsImpl(Parcel p) {
        this(Clock.SYSTEM_CLOCK, p);
    }

    public BatteryStatsImpl(Clock clock, Parcel p) {
        init(clock);
        mStatsFile = null;
        mCheckinFile = null;
        mDailyFile = null;
        mHandler = null;
        mExternalSync = null;
        mConstants = new Constants(mHandler);
        clearHistoryLocked();
        mBatteryStatsHistory = new BatteryStatsHistory(mHistoryBuffer);
        readFromParcel(p);
        mPlatformIdleStateCallback = null;
        mMeasuredEnergyRetriever = null;
    }

    public void setPowerProfileLocked(PowerProfile profile) {
        mPowerProfile = profile;

        // We need to initialize the KernelCpuSpeedReaders to read from
        // the first cpu of each core. Once we have the PowerProfile, we have access to this
        // information.
        final int numClusters = mPowerProfile.getNumCpuClusters();
        mKernelCpuSpeedReaders = new KernelCpuSpeedReader[numClusters];
        int firstCpuOfCluster = 0;
        for (int i = 0; i < numClusters; i++) {
            final int numSpeedSteps = mPowerProfile.getNumSpeedStepsInCpuCluster(i);
            mKernelCpuSpeedReaders[i] = new KernelCpuSpeedReader(firstCpuOfCluster,
                    numSpeedSteps);
            firstCpuOfCluster += mPowerProfile.getNumCoresInCpuCluster(i);
        }

        if (mEstimatedBatteryCapacityMah == -1) {
            // Initialize the estimated battery capacity to a known preset one.
            mEstimatedBatteryCapacityMah = (int) mPowerProfile.getBatteryCapacity();
        }

        setDisplayCountLocked(mPowerProfile.getNumDisplays());
    }

    PowerProfile getPowerProfile() {
        return mPowerProfile;
    }

    /**
     * Starts tracking CPU time-in-state for threads of the system server process,
     * keeping a separate account of threads receiving incoming binder calls.
     */
    public void startTrackingSystemServerCpuTime() {
        mSystemServerCpuThreadReader.startTrackingThreadCpuTime();
    }

    public SystemServiceCpuThreadTimes getSystemServiceCpuThreadTimes() {
        return mSystemServerCpuThreadReader.readAbsolute();
    }

    public void setCallback(BatteryCallback cb) {
        mCallback = cb;
    }

    public void setRadioScanningTimeoutLocked(long timeoutUs) {
        if (mPhoneSignalScanningTimer != null) {
            mPhoneSignalScanningTimer.setTimeout(timeoutUs);
        }
    }

    public void setExternalStatsSyncLocked(ExternalStatsSync sync) {
        mExternalSync = sync;
    }

    /**
     * Initialize and set multi display timers and states.
     */
    public void setDisplayCountLocked(int numDisplays) {
        mPerDisplayBatteryStats = new DisplayBatteryStats[numDisplays];
        for (int i = 0; i < numDisplays; i++) {
            mPerDisplayBatteryStats[i] = new DisplayBatteryStats(mClock, mOnBatteryTimeBase);
        }
    }

    public void updateDailyDeadlineLocked() {
        // Get the current time.
        long currentTimeMs = mDailyStartTimeMs = mClock.currentTimeMillis();
        Calendar calDeadline = Calendar.getInstance();
        calDeadline.setTimeInMillis(currentTimeMs);

        // Move time up to the next day, ranging from 1am to 3pm.
        calDeadline.set(Calendar.DAY_OF_YEAR, calDeadline.get(Calendar.DAY_OF_YEAR) + 1);
        calDeadline.set(Calendar.MILLISECOND, 0);
        calDeadline.set(Calendar.SECOND, 0);
        calDeadline.set(Calendar.MINUTE, 0);
        calDeadline.set(Calendar.HOUR_OF_DAY, 1);
        mNextMinDailyDeadlineMs = calDeadline.getTimeInMillis();
        calDeadline.set(Calendar.HOUR_OF_DAY, 3);
        mNextMaxDailyDeadlineMs = calDeadline.getTimeInMillis();
    }

    public void recordDailyStatsIfNeededLocked(boolean settled, long currentTimeMs) {
        if (currentTimeMs >= mNextMaxDailyDeadlineMs) {
            recordDailyStatsLocked();
        } else if (settled && currentTimeMs >= mNextMinDailyDeadlineMs) {
            recordDailyStatsLocked();
        } else if (currentTimeMs < (mDailyStartTimeMs - (1000 * 60 * 60 * 24))) {
            recordDailyStatsLocked();
        }
    }

    public void recordDailyStatsLocked() {
        DailyItem item = new DailyItem();
        item.mStartTime = mDailyStartTimeMs;
        item.mEndTime = mClock.currentTimeMillis();
        boolean hasData = false;
        if (mDailyDischargeStepTracker.mNumStepDurations > 0) {
            hasData = true;
            item.mDischargeSteps = new LevelStepTracker(
                    mDailyDischargeStepTracker.mNumStepDurations,
                    mDailyDischargeStepTracker.mStepDurations);
        }
        if (mDailyChargeStepTracker.mNumStepDurations > 0) {
            hasData = true;
            item.mChargeSteps = new LevelStepTracker(
                    mDailyChargeStepTracker.mNumStepDurations,
                    mDailyChargeStepTracker.mStepDurations);
        }
        if (mDailyPackageChanges != null) {
            hasData = true;
            item.mPackageChanges = mDailyPackageChanges;
            mDailyPackageChanges = null;
        }
        mDailyDischargeStepTracker.init();
        mDailyChargeStepTracker.init();
        updateDailyDeadlineLocked();

        if (hasData) {
            final long startTimeMs = SystemClock.uptimeMillis();
            mDailyItems.add(item);
            while (mDailyItems.size() > MAX_DAILY_ITEMS) {
                mDailyItems.remove(0);
            }
            final ByteArrayOutputStream memStream = new ByteArrayOutputStream();
            try {
                TypedXmlSerializer out = Xml.resolveSerializer(memStream);
                writeDailyItemsLocked(out);
                final long initialTimeMs = SystemClock.uptimeMillis() - startTimeMs;
                BackgroundThread.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (mCheckinFile) {
                            final long startTimeMs2 = SystemClock.uptimeMillis();
                            FileOutputStream stream = null;
                            try {
                                stream = mDailyFile.startWrite();
                                memStream.writeTo(stream);
                                stream.flush();
                                mDailyFile.finishWrite(stream);
                                com.android.internal.logging.EventLogTags.writeCommitSysConfigFile(
                                        "batterystats-daily",
                                        initialTimeMs + SystemClock.uptimeMillis() - startTimeMs2);
                            } catch (IOException e) {
                                Slog.w("BatteryStats",
                                        "Error writing battery daily items", e);
                                mDailyFile.failWrite(stream);
                            }
                        }
                    }
                });
            } catch (IOException e) {
            }
        }
    }

    private void writeDailyItemsLocked(TypedXmlSerializer out) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        out.startDocument(null, true);
        out.startTag(null, "daily-items");
        for (int i=0; i<mDailyItems.size(); i++) {
            final DailyItem dit = mDailyItems.get(i);
            out.startTag(null, "item");
            out.attributeLong(null, "start", dit.mStartTime);
            out.attributeLong(null, "end", dit.mEndTime);
            writeDailyLevelSteps(out, "dis", dit.mDischargeSteps, sb);
            writeDailyLevelSteps(out, "chg", dit.mChargeSteps, sb);
            if (dit.mPackageChanges != null) {
                for (int j=0; j<dit.mPackageChanges.size(); j++) {
                    PackageChange pc = dit.mPackageChanges.get(j);
                    if (pc.mUpdate) {
                        out.startTag(null, "upd");
                        out.attribute(null, "pkg", pc.mPackageName);
                        out.attributeLong(null, "ver", pc.mVersionCode);
                        out.endTag(null, "upd");
                    } else {
                        out.startTag(null, "rem");
                        out.attribute(null, "pkg", pc.mPackageName);
                        out.endTag(null, "rem");
                    }
                }
            }
            out.endTag(null, "item");
        }
        out.endTag(null, "daily-items");
        out.endDocument();
    }

    private void writeDailyLevelSteps(TypedXmlSerializer out, String tag, LevelStepTracker steps,
            StringBuilder tmpBuilder) throws IOException {
        if (steps != null) {
            out.startTag(null, tag);
            out.attributeInt(null, "n", steps.mNumStepDurations);
            for (int i=0; i<steps.mNumStepDurations; i++) {
                out.startTag(null, "s");
                tmpBuilder.setLength(0);
                steps.encodeEntryAt(i, tmpBuilder);
                out.attribute(null, "v", tmpBuilder.toString());
                out.endTag(null, "s");
            }
            out.endTag(null, tag);
        }
    }

    @GuardedBy("this")
    public void readDailyStatsLocked() {
        Slog.d(TAG, "Reading daily items from " + mDailyFile.getBaseFile());
        mDailyItems.clear();
        FileInputStream stream;
        try {
            stream = mDailyFile.openRead();
        } catch (FileNotFoundException e) {
            return;
        }
        try {
            TypedXmlPullParser parser = Xml.resolvePullParser(stream);
            readDailyItemsLocked(parser);
        } catch (IOException e) {
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
            }
        }
    }

    private void readDailyItemsLocked(TypedXmlPullParser parser) {
        try {
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                ;
            }

            if (type != XmlPullParser.START_TAG) {
                throw new IllegalStateException("no start tag found");
            }

            int outerDepth = parser.getDepth();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (tagName.equals("item")) {
                    readDailyItemTagLocked(parser);
                } else {
                    Slog.w(TAG, "Unknown element under <daily-items>: "
                            + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }

        } catch (IllegalStateException e) {
            Slog.w(TAG, "Failed parsing daily " + e);
        } catch (NullPointerException e) {
            Slog.w(TAG, "Failed parsing daily " + e);
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Failed parsing daily " + e);
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "Failed parsing daily " + e);
        } catch (IOException e) {
            Slog.w(TAG, "Failed parsing daily " + e);
        } catch (IndexOutOfBoundsException e) {
            Slog.w(TAG, "Failed parsing daily " + e);
        }
    }

    void readDailyItemTagLocked(TypedXmlPullParser parser) throws NumberFormatException,
            XmlPullParserException, IOException {
        DailyItem dit = new DailyItem();
        dit.mStartTime = parser.getAttributeLong(null, "start", 0);
        dit.mEndTime = parser.getAttributeLong(null, "end", 0);
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("dis")) {
                readDailyItemTagDetailsLocked(parser, dit, false, "dis");
            } else if (tagName.equals("chg")) {
                readDailyItemTagDetailsLocked(parser, dit, true, "chg");
            } else if (tagName.equals("upd")) {
                if (dit.mPackageChanges == null) {
                    dit.mPackageChanges = new ArrayList<>();
                }
                PackageChange pc = new PackageChange();
                pc.mUpdate = true;
                pc.mPackageName = parser.getAttributeValue(null, "pkg");
                pc.mVersionCode = parser.getAttributeLong(null, "ver", 0);
                dit.mPackageChanges.add(pc);
                XmlUtils.skipCurrentTag(parser);
            } else if (tagName.equals("rem")) {
                if (dit.mPackageChanges == null) {
                    dit.mPackageChanges = new ArrayList<>();
                }
                PackageChange pc = new PackageChange();
                pc.mUpdate = false;
                pc.mPackageName = parser.getAttributeValue(null, "pkg");
                dit.mPackageChanges.add(pc);
                XmlUtils.skipCurrentTag(parser);
            } else {
                Slog.w(TAG, "Unknown element under <item>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
        mDailyItems.add(dit);
    }

    void readDailyItemTagDetailsLocked(TypedXmlPullParser parser, DailyItem dit, boolean isCharge,
            String tag)
            throws NumberFormatException, XmlPullParserException, IOException {
        final int num = parser.getAttributeInt(null, "n", -1);
        if (num == -1) {
            Slog.w(TAG, "Missing 'n' attribute at " + parser.getPositionDescription());
            XmlUtils.skipCurrentTag(parser);
            return;
        }
        LevelStepTracker steps = new LevelStepTracker(num);
        if (isCharge) {
            dit.mChargeSteps = steps;
        } else {
            dit.mDischargeSteps = steps;
        }
        int i = 0;
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if ("s".equals(tagName)) {
                if (i < num) {
                    String valueAttr = parser.getAttributeValue(null, "v");
                    if (valueAttr != null) {
                        steps.decodeEntryAt(i, valueAttr);
                        i++;
                    }
                }
            } else {
                Slog.w(TAG, "Unknown element under <" + tag + ">: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
        steps.mNumStepDurations = i;
    }

    @Override
    public DailyItem getDailyItemLocked(int daysAgo) {
        int index = mDailyItems.size()-1-daysAgo;
        return index >= 0 ? mDailyItems.get(index) : null;
    }

    @Override
    public long getCurrentDailyStartTime() {
        return mDailyStartTimeMs;
    }

    @Override
    public long getNextMinDailyDeadline() {
        return mNextMinDailyDeadlineMs;
    }

    @Override
    public long getNextMaxDailyDeadline() {
        return mNextMaxDailyDeadlineMs;
    }

    @GuardedBy("this")
    public int getHistoryTotalSize() {
        return mConstants.MAX_HISTORY_BUFFER * mConstants.MAX_HISTORY_FILES;
    }

    public int getHistoryUsedSize() {
        return mBatteryStatsHistory.getHistoryUsedSize();
    }

    @Override
    @UnsupportedAppUsage
    public boolean startIteratingHistoryLocked() {
        mBatteryStatsHistoryIterator = createBatteryStatsHistoryIterator();
        return true;
    }

    /**
     * Creates an iterator for battery stats history.
     */
    @VisibleForTesting
    public BatteryStatsHistoryIterator createBatteryStatsHistoryIterator() {
        return new BatteryStatsHistoryIterator(mBatteryStatsHistory);
    }

    @Override
    public int getHistoryStringPoolSize() {
        return mHistoryTagPool.size();
    }

    @Override
    public int getHistoryStringPoolBytes() {
        return mNumHistoryTagChars;
    }

    @Override
    public String getHistoryTagPoolString(int index) {
        ensureHistoryTagArray();
        HistoryTag historyTag = mHistoryTags.get(index);
        return historyTag != null ? historyTag.string : null;
    }

    @Override
    public int getHistoryTagPoolUid(int index) {
        ensureHistoryTagArray();
        HistoryTag historyTag = mHistoryTags.get(index);
        return historyTag != null ? historyTag.uid : Process.INVALID_UID;
    }

    private void ensureHistoryTagArray() {
        if (mHistoryTags != null) {
            return;
        }

        mHistoryTags = new SparseArray<>(mHistoryTagPool.size());
        for (Map.Entry<HistoryTag, Integer> entry: mHistoryTagPool.entrySet()) {
            mHistoryTags.put(entry.getValue() & ~TAG_FIRST_OCCURRENCE_FLAG, entry.getKey());
        }
    }

    @Override
    @UnsupportedAppUsage
    public boolean getNextHistoryLocked(HistoryItem out) {
        return mBatteryStatsHistoryIterator.next(out);
    }

    @Override
    public void finishIteratingHistoryLocked() {
        mBatteryStatsHistoryIterator = null;
    }

    @Override
    public long getHistoryBaseTime() {
        return mHistoryBaseTimeMs;
    }

    @Override
    public int getStartCount() {
        return mStartCount;
    }

    @UnsupportedAppUsage
    public boolean isOnBattery() {
        return mOnBattery;
    }

    public boolean isCharging() {
        return mCharging;
    }

    void initTimes(long uptimeUs, long realtimeUs) {
        mStartClockTimeMs = mClock.currentTimeMillis();
        mOnBatteryTimeBase.init(uptimeUs, realtimeUs);
        mOnBatteryScreenOffTimeBase.init(uptimeUs, realtimeUs);
        mRealtimeUs = 0;
        mUptimeUs = 0;
        mRealtimeStartUs = realtimeUs;
        mUptimeStartUs = uptimeUs;
    }

    void initDischarge(long elapsedRealtimeUs) {
        mLowDischargeAmountSinceCharge = 0;
        mHighDischargeAmountSinceCharge = 0;
        mDischargeAmountScreenOn = 0;
        mDischargeAmountScreenOnSinceCharge = 0;
        mDischargeAmountScreenOff = 0;
        mDischargeAmountScreenOffSinceCharge = 0;
        mDischargeAmountScreenDoze = 0;
        mDischargeAmountScreenDozeSinceCharge = 0;
        mDischargeStepTracker.init();
        mChargeStepTracker.init();
        mDischargeScreenOffCounter.reset(false, elapsedRealtimeUs);
        mDischargeScreenDozeCounter.reset(false, elapsedRealtimeUs);
        mDischargeLightDozeCounter.reset(false, elapsedRealtimeUs);
        mDischargeDeepDozeCounter.reset(false, elapsedRealtimeUs);
        mDischargeCounter.reset(false, elapsedRealtimeUs);
    }

    public void setBatteryResetListener(BatteryResetListener batteryResetListener) {
        mBatteryResetListener = batteryResetListener;
    }

    @GuardedBy("this")
    public void resetAllStatsCmdLocked() {
        final long mSecUptime = mClock.uptimeMillis();
        long uptimeUs = mSecUptime * 1000;
        long mSecRealtime = mClock.elapsedRealtime();
        long realtimeUs = mSecRealtime * 1000;
        resetAllStatsLocked(mSecUptime, mSecRealtime, RESET_REASON_ADB_COMMAND);
        mDischargeStartLevel = mHistoryCur.batteryLevel;
        pullPendingStateUpdatesLocked();
        addHistoryRecordLocked(mSecRealtime, mSecUptime);
        mDischargeCurrentLevel = mDischargeUnplugLevel = mDischargePlugLevel
                = mCurrentBatteryLevel = mHistoryCur.batteryLevel;
        mOnBatteryTimeBase.reset(uptimeUs, realtimeUs);
        mOnBatteryScreenOffTimeBase.reset(uptimeUs, realtimeUs);
        if ((mHistoryCur.states&HistoryItem.STATE_BATTERY_PLUGGED_FLAG) == 0) {
            if (Display.isOnState(mScreenState)) {
                mDischargeScreenOnUnplugLevel = mHistoryCur.batteryLevel;
                mDischargeScreenDozeUnplugLevel = 0;
                mDischargeScreenOffUnplugLevel = 0;
            } else if (Display.isDozeState(mScreenState)) {
                mDischargeScreenOnUnplugLevel = 0;
                mDischargeScreenDozeUnplugLevel = mHistoryCur.batteryLevel;
                mDischargeScreenOffUnplugLevel = 0;
            } else {
                mDischargeScreenOnUnplugLevel = 0;
                mDischargeScreenDozeUnplugLevel = 0;
                mDischargeScreenOffUnplugLevel = mHistoryCur.batteryLevel;
            }
            mDischargeAmountScreenOn = 0;
            mDischargeAmountScreenOff = 0;
            mDischargeAmountScreenDoze = 0;
        }
        initActiveHistoryEventsLocked(mSecRealtime, mSecUptime);
    }

    @GuardedBy("this")
    private void resetAllStatsLocked(long uptimeMillis, long elapsedRealtimeMillis,
            int resetReason) {
        if (mBatteryResetListener != null) {
            mBatteryResetListener.prepareForBatteryStatsReset(resetReason);
        }

        final long uptimeUs = uptimeMillis * 1000;
        final long elapsedRealtimeUs = elapsedRealtimeMillis * 1000;
        mStartCount = 0;
        initTimes(uptimeUs, elapsedRealtimeUs);
        mScreenOnTimer.reset(false, elapsedRealtimeUs);
        mScreenDozeTimer.reset(false, elapsedRealtimeUs);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i].reset(false, elapsedRealtimeUs);
        }

        final int numDisplays = mPerDisplayBatteryStats.length;
        for (int i = 0; i < numDisplays; i++) {
            mPerDisplayBatteryStats[i].reset(elapsedRealtimeUs);
        }

        if (mPowerProfile != null) {
            mEstimatedBatteryCapacityMah = (int) mPowerProfile.getBatteryCapacity();
        } else {
            mEstimatedBatteryCapacityMah = -1;
        }
        mLastLearnedBatteryCapacityUah = -1;
        mMinLearnedBatteryCapacityUah = -1;
        mMaxLearnedBatteryCapacityUah = -1;
        mInteractiveTimer.reset(false, elapsedRealtimeUs);
        mPowerSaveModeEnabledTimer.reset(false, elapsedRealtimeUs);
        mLastIdleTimeStartMs = elapsedRealtimeMillis;
        mLongestLightIdleTimeMs = 0;
        mLongestFullIdleTimeMs = 0;
        mDeviceIdleModeLightTimer.reset(false, elapsedRealtimeUs);
        mDeviceIdleModeFullTimer.reset(false, elapsedRealtimeUs);
        mDeviceLightIdlingTimer.reset(false, elapsedRealtimeUs);
        mDeviceIdlingTimer.reset(false, elapsedRealtimeUs);
        mPhoneOnTimer.reset(false, elapsedRealtimeUs);
        mAudioOnTimer.reset(false, elapsedRealtimeUs);
        mVideoOnTimer.reset(false, elapsedRealtimeUs);
        mFlashlightOnTimer.reset(false, elapsedRealtimeUs);
        mCameraOnTimer.reset(false, elapsedRealtimeUs);
        mBluetoothScanTimer.reset(false, elapsedRealtimeUs);
        for (int i = 0; i < CellSignalStrength.getNumSignalStrengthLevels(); i++) {
            mPhoneSignalStrengthsTimer[i].reset(false, elapsedRealtimeUs);
        }
        mPhoneSignalScanningTimer.reset(false, elapsedRealtimeUs);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i].reset(false, elapsedRealtimeUs);
        }
        for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
            mNetworkByteActivityCounters[i].reset(false, elapsedRealtimeUs);
            mNetworkPacketActivityCounters[i].reset(false, elapsedRealtimeUs);
        }
        for (int i = 0; i < RADIO_ACCESS_TECHNOLOGY_COUNT; i++) {
            final RadioAccessTechnologyBatteryStats stats = mPerRatBatteryStats[i];
            if (stats == null) continue;
            stats.reset(elapsedRealtimeUs);
        }
        mMobileRadioActiveTimer.reset(false, elapsedRealtimeUs);
        mMobileRadioActivePerAppTimer.reset(false, elapsedRealtimeUs);
        mMobileRadioActiveAdjustedTime.reset(false, elapsedRealtimeUs);
        mMobileRadioActiveUnknownTime.reset(false, elapsedRealtimeUs);
        mMobileRadioActiveUnknownCount.reset(false, elapsedRealtimeUs);
        mWifiOnTimer.reset(false, elapsedRealtimeUs);
        mGlobalWifiRunningTimer.reset(false, elapsedRealtimeUs);
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            mWifiStateTimer[i].reset(false, elapsedRealtimeUs);
        }
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            mWifiSupplStateTimer[i].reset(false, elapsedRealtimeUs);
        }
        for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            mWifiSignalStrengthsTimer[i].reset(false, elapsedRealtimeUs);
        }
        mWifiMulticastWakelockTimer.reset(false, elapsedRealtimeUs);
        mWifiActiveTimer.reset(false, elapsedRealtimeUs);
        mWifiActivity.reset(false, elapsedRealtimeUs);
        for (int i=0; i< mGpsSignalQualityTimer.length; i++) {
            mGpsSignalQualityTimer[i].reset(false, elapsedRealtimeUs);
        }
        mBluetoothActivity.reset(false, elapsedRealtimeUs);
        mModemActivity.reset(false, elapsedRealtimeUs);
        mNumConnectivityChange = 0;

        for (int i=0; i<mUidStats.size(); i++) {
            if (mUidStats.valueAt(i).reset(uptimeUs, elapsedRealtimeUs, resetReason)) {
                mUidStats.valueAt(i).detachFromTimeBase();
                mUidStats.remove(mUidStats.keyAt(i));
                i--;
            }
        }

        if (mRpmStats.size() > 0) {
            for (SamplingTimer timer : mRpmStats.values()) {
                mOnBatteryTimeBase.remove(timer);
            }
            mRpmStats.clear();
        }
        if (mScreenOffRpmStats.size() > 0) {
            for (SamplingTimer timer : mScreenOffRpmStats.values()) {
                mOnBatteryScreenOffTimeBase.remove(timer);
            }
            mScreenOffRpmStats.clear();
        }

        if (mKernelWakelockStats.size() > 0) {
            for (SamplingTimer timer : mKernelWakelockStats.values()) {
                mOnBatteryScreenOffTimeBase.remove(timer);
            }
            mKernelWakelockStats.clear();
        }

        if (mKernelMemoryStats.size() > 0) {
            for (int i = 0; i < mKernelMemoryStats.size(); i++) {
                mOnBatteryTimeBase.remove(mKernelMemoryStats.valueAt(i));
            }
            mKernelMemoryStats.clear();
        }

        if (mWakeupReasonStats.size() > 0) {
            for (SamplingTimer timer : mWakeupReasonStats.values()) {
                mOnBatteryTimeBase.remove(timer);
            }
            mWakeupReasonStats.clear();
        }

        mTmpRailStats.reset();

        MeasuredEnergyStats.resetIfNotNull(mGlobalMeasuredEnergyStats);

        resetIfNotNull(mBinderThreadCpuTimesUs, false, elapsedRealtimeUs);

        mLastHistoryStepDetails = null;
        mLastStepCpuUserTimeMs = mLastStepCpuSystemTimeMs = 0;
        mCurStepCpuUserTimeMs = mCurStepCpuSystemTimeMs = 0;
        mLastStepCpuUserTimeMs = mCurStepCpuUserTimeMs = 0;
        mLastStepCpuSystemTimeMs = mCurStepCpuSystemTimeMs = 0;
        mLastStepStatUserTimeMs = mCurStepStatUserTimeMs = 0;
        mLastStepStatSystemTimeMs = mCurStepStatSystemTimeMs = 0;
        mLastStepStatIOWaitTimeMs = mCurStepStatIOWaitTimeMs = 0;
        mLastStepStatIrqTimeMs = mCurStepStatIrqTimeMs = 0;
        mLastStepStatSoftIrqTimeMs = mCurStepStatSoftIrqTimeMs = 0;
        mLastStepStatIdleTimeMs = mCurStepStatIdleTimeMs = 0;

        mNumAllUidCpuTimeReads = 0;
        mNumUidsRemoved = 0;

        initDischarge(elapsedRealtimeUs);

        clearHistoryLocked();
        if (mBatteryStatsHistory != null) {
            mBatteryStatsHistory.resetAllFiles();
        }

        // Flush external data, gathering snapshots, but don't process it since it is pre-reset data
        mIgnoreNextExternalStats = true;
        mExternalSync.scheduleSync("reset", ExternalStatsSync.UPDATE_ON_RESET);

        mHandler.sendEmptyMessage(MSG_REPORT_RESET_STATS);
    }

    @GuardedBy("this")
    private void initActiveHistoryEventsLocked(long elapsedRealtimeMs, long uptimeMs) {
        for (int i=0; i<HistoryItem.EVENT_COUNT; i++) {
            if (!mRecordAllHistory && i == HistoryItem.EVENT_PROC) {
                // Not recording process starts/stops.
                continue;
            }
            HashMap<String, SparseIntArray> active = mActiveEvents.getStateForEvent(i);
            if (active == null) {
                continue;
            }
            for (HashMap.Entry<String, SparseIntArray> ent : active.entrySet()) {
                SparseIntArray uids = ent.getValue();
                for (int j=0; j<uids.size(); j++) {
                    addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, i, ent.getKey(),
                            uids.keyAt(j));
                }
            }
        }
    }

    @GuardedBy("this")
    void updateDischargeScreenLevelsLocked(int oldState, int newState) {
        updateOldDischargeScreenLevelLocked(oldState);
        updateNewDischargeScreenLevelLocked(newState);
    }

    @GuardedBy("this")
    private void updateOldDischargeScreenLevelLocked(int state) {
        if (Display.isOnState(state)) {
            int diff = mDischargeScreenOnUnplugLevel - mDischargeCurrentLevel;
            if (diff > 0) {
                mDischargeAmountScreenOn += diff;
                mDischargeAmountScreenOnSinceCharge += diff;
            }
        } else if (Display.isDozeState(state)) {
            int diff = mDischargeScreenDozeUnplugLevel - mDischargeCurrentLevel;
            if (diff > 0) {
                mDischargeAmountScreenDoze += diff;
                mDischargeAmountScreenDozeSinceCharge += diff;
            }
        } else if (Display.isOffState(state)) {
            int diff = mDischargeScreenOffUnplugLevel - mDischargeCurrentLevel;
            if (diff > 0) {
                mDischargeAmountScreenOff += diff;
                mDischargeAmountScreenOffSinceCharge += diff;
            }
        }
    }

    @GuardedBy("this")
    private void updateNewDischargeScreenLevelLocked(int state) {
        if (Display.isOnState(state)) {
            mDischargeScreenOnUnplugLevel = mDischargeCurrentLevel;
            mDischargeScreenOffUnplugLevel = 0;
            mDischargeScreenDozeUnplugLevel = 0;
        } else if (Display.isDozeState(state)) {
            mDischargeScreenOnUnplugLevel = 0;
            mDischargeScreenDozeUnplugLevel = mDischargeCurrentLevel;
            mDischargeScreenOffUnplugLevel = 0;
        } else if (Display.isOffState(state)) {
            mDischargeScreenOnUnplugLevel = 0;
            mDischargeScreenDozeUnplugLevel = 0;
            mDischargeScreenOffUnplugLevel = mDischargeCurrentLevel;
        }
    }

    @GuardedBy("this")
    public void pullPendingStateUpdatesLocked() {
        if (mOnBatteryInternal) {
            updateDischargeScreenLevelsLocked(mScreenState, mScreenState);
        }
    }

    private final Object mWifiNetworkLock = new Object();

    @GuardedBy("mWifiNetworkLock")
    private String[] mWifiIfaces = EmptyArray.STRING;

    @GuardedBy("mWifiNetworkLock")
    private NetworkStats mLastWifiNetworkStats = new NetworkStats(0, -1);

    private final Object mModemNetworkLock = new Object();

    @GuardedBy("mModemNetworkLock")
    private String[] mModemIfaces = EmptyArray.STRING;

    @GuardedBy("mModemNetworkLock")
    private NetworkStats mLastModemNetworkStats = new NetworkStats(0, -1);

    @VisibleForTesting
    protected NetworkStats readMobileNetworkStatsLocked(
            @NonNull NetworkStatsManager networkStatsManager) {
        return networkStatsManager.getMobileUidStats();
    }

    @VisibleForTesting
    protected NetworkStats readWifiNetworkStatsLocked(
            @NonNull NetworkStatsManager networkStatsManager) {
        return networkStatsManager.getWifiUidStats();
    }

    /**
     * Distribute WiFi energy info and network traffic to apps.
     * @param info The energy information from the WiFi controller.
     */
    @GuardedBy("this")
    public void updateWifiState(@Nullable final WifiActivityEnergyInfo info,
            final long consumedChargeUC, long elapsedRealtimeMs, long uptimeMs,
            @NonNull NetworkStatsManager networkStatsManager) {
        if (DEBUG_ENERGY) {
            synchronized (mWifiNetworkLock) {
                Slog.d(TAG, "Updating wifi stats: " + Arrays.toString(mWifiIfaces));
            }
        }

        // Grab a separate lock to acquire the network stats, which may do I/O.
        NetworkStats delta = null;
        synchronized (mWifiNetworkLock) {
            final NetworkStats latestStats = readWifiNetworkStatsLocked(networkStatsManager);
            if (latestStats != null) {
                delta = latestStats.subtract(mLastWifiNetworkStats);
                mLastWifiNetworkStats = latestStats;
            }
        }

        synchronized (this) {
            if (!mOnBatteryInternal || mIgnoreNextExternalStats) {
                if (mIgnoreNextExternalStats) {
                    // TODO: Strictly speaking, we should re-mark all 5 timers for each uid (and the
                    //  global one) here like we do for display. But I'm not sure it's worth the
                    //  complicated code for a codepath that shouldn't ever actually happen in real
                    //  life.
                }
                return;
            }

            final SparseDoubleArray uidEstimatedConsumptionMah =
                    (mGlobalMeasuredEnergyStats != null
                            && mWifiPowerCalculator != null && consumedChargeUC > 0) ?
                            new SparseDoubleArray() : null;
            double totalEstimatedConsumptionMah = 0;

            SparseLongArray rxPackets = new SparseLongArray();
            SparseLongArray txPackets = new SparseLongArray();
            SparseLongArray rxTimesMs = new SparseLongArray();
            SparseLongArray txTimesMs = new SparseLongArray();
            long totalTxPackets = 0;
            long totalRxPackets = 0;
            if (delta != null) {
                for (NetworkStats.Entry entry : delta) {
                    if (DEBUG_ENERGY) {
                        Slog.d(TAG, "Wifi uid " + entry.getUid()
                                + ": delta rx=" + entry.getRxBytes()
                                + " tx=" + entry.getTxBytes()
                                + " rxPackets=" + entry.getRxPackets()
                                + " txPackets=" + entry.getTxPackets());
                    }

                    if (entry.getRxBytes() == 0 && entry.getTxBytes() == 0) {
                        // Skip the lookup below since there is no work to do.
                        continue;
                    }

                    final int uid = mapUid(entry.getUid());
                    final Uid u = getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs);
                    if (entry.getRxBytes() != 0) {
                        u.noteNetworkActivityLocked(NETWORK_WIFI_RX_DATA, entry.getRxBytes(),
                                entry.getRxPackets());
                        if (entry.getSet() == NetworkStats.SET_DEFAULT) { // Background transfers
                            u.noteNetworkActivityLocked(NETWORK_WIFI_BG_RX_DATA, entry.getRxBytes(),
                                    entry.getRxPackets());
                        }
                        mNetworkByteActivityCounters[NETWORK_WIFI_RX_DATA].addCountLocked(
                                entry.getRxBytes());
                        mNetworkPacketActivityCounters[NETWORK_WIFI_RX_DATA].addCountLocked(
                                entry.getRxPackets());

                        rxPackets.incrementValue(uid, entry.getRxPackets());

                        // Sum the total number of packets so that the Rx Power can
                        // be evenly distributed amongst the apps.
                        totalRxPackets += entry.getRxPackets();
                    }

                    if (entry.getTxBytes() != 0) {
                        u.noteNetworkActivityLocked(NETWORK_WIFI_TX_DATA, entry.getTxBytes(),
                                entry.getTxPackets());
                        if (entry.getSet() == NetworkStats.SET_DEFAULT) { // Background transfers
                            u.noteNetworkActivityLocked(NETWORK_WIFI_BG_TX_DATA, entry.getTxBytes(),
                                    entry.getTxPackets());
                        }
                        mNetworkByteActivityCounters[NETWORK_WIFI_TX_DATA].addCountLocked(
                                entry.getTxBytes());
                        mNetworkPacketActivityCounters[NETWORK_WIFI_TX_DATA].addCountLocked(
                                entry.getTxPackets());

                        txPackets.incrementValue(uid, entry.getTxPackets());

                        // Sum the total number of packets so that the Tx Power can
                        // be evenly distributed amongst the apps.
                        totalTxPackets += entry.getTxPackets();
                    }

                    // Calculate consumed energy for this uid. Only do so if WifiReporting isn't
                    // enabled (if it is, we'll do it later instead using info).
                    if (uidEstimatedConsumptionMah != null && info == null && !mHasWifiReporting) {
                        final long uidRunningMs = u.mWifiRunningTimer
                                .getTimeSinceMarkLocked(elapsedRealtimeMs * 1000) / 1000;
                        if (uidRunningMs > 0) u.mWifiRunningTimer.setMark(elapsedRealtimeMs);

                        final long uidScanMs = u.mWifiScanTimer
                                .getTimeSinceMarkLocked(elapsedRealtimeMs * 1000) / 1000;
                        if (uidScanMs > 0) u.mWifiScanTimer.setMark(elapsedRealtimeMs);

                        long uidBatchScanMs = 0;
                        for (int bn = 0; bn < BatteryStats.Uid.NUM_WIFI_BATCHED_SCAN_BINS; bn++) {
                            if (u.mWifiBatchedScanTimer[bn] != null) {
                                long bnMs = u.mWifiBatchedScanTimer[bn]
                                        .getTimeSinceMarkLocked(elapsedRealtimeMs * 1000) / 1000;
                                if (bnMs > 0) {
                                    u.mWifiBatchedScanTimer[bn].setMark(elapsedRealtimeMs);
                                }
                                uidBatchScanMs += bnMs;
                            }
                        }

                        uidEstimatedConsumptionMah.incrementValue(u.getUid(),
                                mWifiPowerCalculator.calcPowerWithoutControllerDataMah(
                                        entry.getRxPackets(), entry.getTxPackets(),
                                        uidRunningMs, uidScanMs, uidBatchScanMs));
                    }
                }
                delta = null;
            }

            if (info != null) {
                mHasWifiReporting = true;

                // Measured in mAms
                final long txTimeMs = info.getControllerTxDurationMillis();
                final long rxTimeMs = info.getControllerRxDurationMillis();
                final long scanTimeMs = info.getControllerScanDurationMillis();
                final long idleTimeMs = info.getControllerIdleDurationMillis();
                final long totalTimeMs = txTimeMs + rxTimeMs + idleTimeMs;

                long leftOverRxTimeMs = rxTimeMs;
                long leftOverTxTimeMs = txTimeMs;

                if (DEBUG_ENERGY) {
                    Slog.d(TAG, "------ BEGIN WiFi power blaming ------");
                    Slog.d(TAG, "  Tx Time:    " + txTimeMs + " ms");
                    Slog.d(TAG, "  Rx Time:    " + rxTimeMs + " ms");
                    Slog.d(TAG, "  Idle Time:  " + idleTimeMs + " ms");
                    Slog.d(TAG, "  Total Time: " + totalTimeMs + " ms");
                    Slog.d(TAG, "  Scan Time:  " + scanTimeMs + " ms");
                }

                long totalWifiLockTimeMs = 0;
                long totalScanTimeMs = 0;

                // On the first pass, collect some totals so that we can normalize power
                // calculations if we need to.
                final int uidStatsSize = mUidStats.size();
                for (int i = 0; i < uidStatsSize; i++) {
                    final Uid uid = mUidStats.valueAt(i);

                    // Sum the total scan power for all apps.
                    totalScanTimeMs += uid.mWifiScanTimer.getTimeSinceMarkLocked(
                            elapsedRealtimeMs * 1000) / 1000;

                    // Sum the total time holding wifi lock for all apps.
                    totalWifiLockTimeMs += uid.mFullWifiLockTimer.getTimeSinceMarkLocked(
                            elapsedRealtimeMs * 1000) / 1000;
                }

                if (DEBUG_ENERGY && totalScanTimeMs > rxTimeMs) {
                    Slog.d(TAG,
                            "  !Estimated scan time > Actual rx time (" + totalScanTimeMs + " ms > "
                                    + rxTimeMs + " ms). Normalizing scan time.");
                }
                if (DEBUG_ENERGY && totalScanTimeMs > txTimeMs) {
                    Slog.d(TAG,
                            "  !Estimated scan time > Actual tx time (" + totalScanTimeMs + " ms > "
                                    + txTimeMs + " ms). Normalizing scan time.");
                }

                // Actually assign and distribute power usage to apps.
                for (int i = 0; i < uidStatsSize; i++) {
                    final Uid uid = mUidStats.valueAt(i);

                    final long scanTimeSinceMarkMs = uid.mWifiScanTimer.getTimeSinceMarkLocked(
                            elapsedRealtimeMs * 1000) / 1000;
                    long scanRxTimeSinceMarkMs = scanTimeSinceMarkMs; // not final
                    long scanTxTimeSinceMarkMs = scanTimeSinceMarkMs; // not final
                    if (scanTimeSinceMarkMs > 0) {
                        // Set the new mark so that next time we get new data since this point.
                        uid.mWifiScanTimer.setMark(elapsedRealtimeMs);

                        // Our total scan time is more than the reported Tx/Rx time.
                        // This is possible because the cost of a scan is approximate.
                        // Let's normalize the result so that we evenly blame each app
                        // scanning.
                        //
                        // This means that we may have apps that transmitted/received packets not be
                        // blamed for this, but this is fine as scans are relatively more expensive.
                        if (totalScanTimeMs > rxTimeMs) {
                            scanRxTimeSinceMarkMs = (rxTimeMs * scanRxTimeSinceMarkMs) /
                                    totalScanTimeMs;
                        }
                        if (totalScanTimeMs > txTimeMs) {
                            scanTxTimeSinceMarkMs = (txTimeMs * scanTxTimeSinceMarkMs) /
                                    totalScanTimeMs;
                        }

                        if (DEBUG_ENERGY) {
                            Slog.d(TAG, "  ScanTime for UID " + uid.getUid() + ": Rx:"
                                    + scanRxTimeSinceMarkMs + " ms  Tx:"
                                    + scanTxTimeSinceMarkMs + " ms)");
                        }

                        rxTimesMs.incrementValue(uid.getUid(), scanRxTimeSinceMarkMs);
                        txTimesMs.incrementValue(uid.getUid(), scanTxTimeSinceMarkMs);

                        leftOverRxTimeMs -= scanRxTimeSinceMarkMs;
                        leftOverTxTimeMs -= scanTxTimeSinceMarkMs;
                    }

                    // Distribute evenly the power consumed while Idle to each app holding a WiFi
                    // lock.
                    long myIdleTimeMs = 0;
                    final long wifiLockTimeSinceMarkMs =
                            uid.mFullWifiLockTimer.getTimeSinceMarkLocked(
                                    elapsedRealtimeMs * 1000) / 1000;
                    if (wifiLockTimeSinceMarkMs > 0) {
                        // Set the new mark so that next time we get new data since this point.
                        uid.mFullWifiLockTimer.setMark(elapsedRealtimeMs);

                        myIdleTimeMs = (wifiLockTimeSinceMarkMs * idleTimeMs) / totalWifiLockTimeMs;
                        if (DEBUG_ENERGY) {
                            Slog.d(TAG, "  IdleTime for UID " + uid.getUid() + ": "
                                    + myIdleTimeMs + " ms");
                        }
                        uid.getOrCreateWifiControllerActivityLocked().getOrCreateIdleTimeCounter()
                                .increment(myIdleTimeMs, elapsedRealtimeMs);
                    }

                    if (uidEstimatedConsumptionMah != null) {
                        double uidEstMah = mWifiPowerCalculator.calcPowerFromControllerDataMah(
                                scanRxTimeSinceMarkMs, scanTxTimeSinceMarkMs, myIdleTimeMs);
                        uidEstimatedConsumptionMah.incrementValue(uid.getUid(), uidEstMah);
                    }
                }

                if (DEBUG_ENERGY) {
                    Slog.d(TAG, "  New RxPower: " + leftOverRxTimeMs + " ms");
                    Slog.d(TAG, "  New TxPower: " + leftOverTxTimeMs + " ms");
                }

                // Distribute the remaining Tx power appropriately between all apps that transmitted
                // packets.
                for (int i = 0; i < txPackets.size(); i++) {
                    final int uid = txPackets.keyAt(i);
                    final long myTxTimeMs = (txPackets.valueAt(i) * leftOverTxTimeMs)
                            / totalTxPackets;
                    txTimesMs.incrementValue(uid, myTxTimeMs);
                }

                // Distribute the remaining Rx power appropriately between all apps that received
                // packets.
                for (int i = 0; i < rxPackets.size(); i++) {
                    final int uid = rxPackets.keyAt(i);
                    final long myRxTimeMs = (rxPackets.valueAt(i) * leftOverRxTimeMs)
                            / totalRxPackets;
                    rxTimesMs.incrementValue(uid, myRxTimeMs);
                }

                for (int i = 0; i < txTimesMs.size(); i++) {
                    final int uid = txTimesMs.keyAt(i);
                    final long myTxTimeMs = txTimesMs.valueAt(i);
                    if (DEBUG_ENERGY) {
                        Slog.d(TAG, "  TxTime for UID " + uid + ": " + myTxTimeMs + " ms");
                    }
                    getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                            .getOrCreateWifiControllerActivityLocked()
                            .getOrCreateTxTimeCounters()[0]
                            .increment(myTxTimeMs, elapsedRealtimeMs);
                    if (uidEstimatedConsumptionMah != null) {
                        uidEstimatedConsumptionMah.incrementValue(uid,
                                mWifiPowerCalculator.calcPowerFromControllerDataMah(
                                        0, myTxTimeMs, 0));
                    }
                }

                for (int i = 0; i < rxTimesMs.size(); i++) {
                    final int uid = rxTimesMs.keyAt(i);
                    final long myRxTimeMs = rxTimesMs.valueAt(i);
                    if (DEBUG_ENERGY) {
                        Slog.d(TAG, "  RxTime for UID " + uid + ": " + myRxTimeMs + " ms");
                    }

                    getUidStatsLocked(rxTimesMs.keyAt(i), elapsedRealtimeMs, uptimeMs)
                            .getOrCreateWifiControllerActivityLocked()
                            .getOrCreateRxTimeCounter()
                            .increment(myRxTimeMs, elapsedRealtimeMs);
                    if (uidEstimatedConsumptionMah != null) {
                        uidEstimatedConsumptionMah.incrementValue(uid,
                                mWifiPowerCalculator.calcPowerFromControllerDataMah(
                                        myRxTimeMs, 0, 0));
                    }
                }

                // Any left over power use will be picked up by the WiFi category in BatteryStatsHelper.

                // Update WiFi controller stats.
                mWifiActivity.getOrCreateRxTimeCounter().increment(
                        info.getControllerRxDurationMillis(), elapsedRealtimeMs);
                mWifiActivity.getOrCreateTxTimeCounters()[0].increment(
                        info.getControllerTxDurationMillis(), elapsedRealtimeMs);
                mWifiActivity.getScanTimeCounter().addCountLocked(
                        info.getControllerScanDurationMillis());
                mWifiActivity.getOrCreateIdleTimeCounter().increment(
                        info.getControllerIdleDurationMillis(), elapsedRealtimeMs);

                // POWER_WIFI_CONTROLLER_OPERATING_VOLTAGE is measured in mV, so convert to V.
                final double opVolt = mPowerProfile.getAveragePower(
                        PowerProfile.POWER_WIFI_CONTROLLER_OPERATING_VOLTAGE) / 1000.0;
                double controllerMaMs = 0;
                if (opVolt != 0) {
                    // We store the power drain as mAms.
                    controllerMaMs = info.getControllerEnergyUsedMicroJoules() / opVolt;
                    mWifiActivity.getPowerCounter().addCountLocked((long) controllerMaMs);
                }
                // Converting uWs to mAms.
                // Conversion: (uWs * (1000ms / 1s) * (1mW / 1000uW)) / mV = mAms
                long monitoredRailChargeConsumedMaMs =
                        (long) (mTmpRailStats.getWifiTotalEnergyUseduWs() / opVolt);
                mWifiActivity.getMonitoredRailChargeConsumedMaMs().addCountLocked(
                        monitoredRailChargeConsumedMaMs);
                mHistoryCur.wifiRailChargeMah +=
                        (monitoredRailChargeConsumedMaMs / MILLISECONDS_IN_HOUR);
                addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
                mTmpRailStats.resetWifiTotalEnergyUsed();

                if (uidEstimatedConsumptionMah != null) {
                    totalEstimatedConsumptionMah = Math.max(controllerMaMs / MILLISECONDS_IN_HOUR,
                            mWifiPowerCalculator.calcPowerFromControllerDataMah(
                                    rxTimeMs, txTimeMs, idleTimeMs));
                }
            }

            // Update the MeasuredEnergyStats information.
            if (uidEstimatedConsumptionMah != null) {
                mGlobalMeasuredEnergyStats.updateStandardBucket(
                        MeasuredEnergyStats.POWER_BUCKET_WIFI, consumedChargeUC);

                // Now calculate the consumption for each uid, according to its proportional usage.
                if (!mHasWifiReporting) {
                    final long globalTimeMs = mGlobalWifiRunningTimer
                            .getTimeSinceMarkLocked(elapsedRealtimeMs * 1000) / 1000;
                    mGlobalWifiRunningTimer.setMark(elapsedRealtimeMs);
                    totalEstimatedConsumptionMah = mWifiPowerCalculator
                            .calcGlobalPowerWithoutControllerDataMah(globalTimeMs);
                }
                distributeEnergyToUidsLocked(MeasuredEnergyStats.POWER_BUCKET_WIFI,
                        consumedChargeUC, uidEstimatedConsumptionMah, totalEstimatedConsumptionMah,
                        elapsedRealtimeMs);
            }
        }
    }

    private ModemActivityInfo mLastModemActivityInfo = null;

    /**
     * Distribute Cell radio energy info and network traffic to apps.
     */
    public void noteModemControllerActivity(@Nullable final ModemActivityInfo activityInfo,
            final long consumedChargeUC, long elapsedRealtimeMs, long uptimeMs,
            @NonNull NetworkStatsManager networkStatsManager) {
        if (DEBUG_ENERGY) {
            Slog.d(TAG, "Updating mobile radio stats with " + activityInfo);
        }
        ModemActivityInfo deltaInfo = mLastModemActivityInfo == null ? activityInfo
                : mLastModemActivityInfo.getDelta(activityInfo);
        mLastModemActivityInfo = activityInfo;

        // Add modem tx power to history.
        addModemTxPowerToHistory(deltaInfo, elapsedRealtimeMs, uptimeMs);

        // Grab a separate lock to acquire the network stats, which may do I/O.
        NetworkStats delta = null;
        synchronized (mModemNetworkLock) {
            final NetworkStats latestStats = readMobileNetworkStatsLocked(networkStatsManager);
            if (latestStats != null) {
                delta = latestStats.subtract(mLastModemNetworkStats);
                mLastModemNetworkStats = latestStats;
            }
        }

        synchronized (this) {
            final long totalRadioDurationMs =
                    mMobileRadioActiveTimer.getTimeSinceMarkLocked(
                            elapsedRealtimeMs * 1000) / 1000;
            mMobileRadioActiveTimer.setMark(elapsedRealtimeMs);
            final long phoneOnDurationMs = Math.min(totalRadioDurationMs,
                    mPhoneOnTimer.getTimeSinceMarkLocked(elapsedRealtimeMs * 1000) / 1000);
            mPhoneOnTimer.setMark(elapsedRealtimeMs);

            if (!mOnBatteryInternal || mIgnoreNextExternalStats) {
                return;
            }

            final SparseDoubleArray uidEstimatedConsumptionMah;
            final long dataConsumedChargeUC;
            if (consumedChargeUC > 0 && mMobileRadioPowerCalculator != null
                    && mGlobalMeasuredEnergyStats != null) {
                // Crudely attribute power consumption. Added (totalRadioDurationMs / 2) to the
                // numerator for long rounding.
                final long phoneConsumedChargeUC =
                        (consumedChargeUC * phoneOnDurationMs + totalRadioDurationMs / 2)
                                / totalRadioDurationMs;
                dataConsumedChargeUC = consumedChargeUC - phoneConsumedChargeUC;
                mGlobalMeasuredEnergyStats.updateStandardBucket(
                        MeasuredEnergyStats.POWER_BUCKET_PHONE, phoneConsumedChargeUC);
                mGlobalMeasuredEnergyStats.updateStandardBucket(
                        MeasuredEnergyStats.POWER_BUCKET_MOBILE_RADIO, dataConsumedChargeUC);
                uidEstimatedConsumptionMah = new SparseDoubleArray();
            } else {
                uidEstimatedConsumptionMah = null;
                dataConsumedChargeUC = POWER_DATA_UNAVAILABLE;
            }

            if (deltaInfo != null) {
                mHasModemReporting = true;
                mModemActivity.getOrCreateIdleTimeCounter()
                        .increment(deltaInfo.getIdleTimeMillis(), elapsedRealtimeMs);
                mModemActivity.getSleepTimeCounter().addCountLocked(
                        deltaInfo.getSleepTimeMillis());
                mModemActivity.getOrCreateRxTimeCounter()
                        .increment(deltaInfo.getReceiveTimeMillis(), elapsedRealtimeMs);
                for (int lvl = 0; lvl < ModemActivityInfo.getNumTxPowerLevels(); lvl++) {
                    mModemActivity.getOrCreateTxTimeCounters()[lvl]
                            .increment(deltaInfo.getTransmitDurationMillisAtPowerLevel(lvl),
                                    elapsedRealtimeMs);
                }

                // POWER_MODEM_CONTROLLER_OPERATING_VOLTAGE is measured in mV, so convert to V.
                final double opVolt = mPowerProfile.getAveragePower(
                    PowerProfile.POWER_MODEM_CONTROLLER_OPERATING_VOLTAGE) / 1000.0;
                if (opVolt != 0) {
                    double energyUsed =
                            deltaInfo.getSleepTimeMillis() *
                            mPowerProfile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_SLEEP)
                            + deltaInfo.getIdleTimeMillis() *
                            mPowerProfile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_IDLE)
                            + deltaInfo.getReceiveTimeMillis() *
                            mPowerProfile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_RX);
                    for (int i = 0; i < Math.min(ModemActivityInfo.getNumTxPowerLevels(),
                            CellSignalStrength.getNumSignalStrengthLevels()); i++) {
                        energyUsed += deltaInfo.getTransmitDurationMillisAtPowerLevel(i)
                                * mPowerProfile.getAveragePower(
                                        PowerProfile.POWER_MODEM_CONTROLLER_TX, i);
                    }

                    // We store the power drain as mAms.
                    mModemActivity.getPowerCounter().addCountLocked((long) energyUsed);
                    // Converting uWs to mAms.
                    // Conversion: (uWs * (1000ms / 1s) * (1mW / 1000uW)) / mV = mAms
                    long monitoredRailChargeConsumedMaMs =
                            (long) (mTmpRailStats.getCellularTotalEnergyUseduWs() / opVolt);
                    mModemActivity.getMonitoredRailChargeConsumedMaMs().addCountLocked(
                            monitoredRailChargeConsumedMaMs);
                    mHistoryCur.modemRailChargeMah +=
                            (monitoredRailChargeConsumedMaMs / MILLISECONDS_IN_HOUR);
                    addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
                    mTmpRailStats.resetCellularTotalEnergyUsed();
                }

                incrementPerRatDataLocked(deltaInfo, elapsedRealtimeMs);
            }
            long totalAppRadioTimeUs = mMobileRadioActivePerAppTimer.getTimeSinceMarkLocked(
                    elapsedRealtimeMs * 1000);
            mMobileRadioActivePerAppTimer.setMark(elapsedRealtimeMs);

            long totalRxPackets = 0;
            long totalTxPackets = 0;
            if (delta != null) {
                for (NetworkStats.Entry entry : delta) {
                    if (entry.getRxPackets() == 0 && entry.getTxPackets() == 0) {
                        continue;
                    }

                    if (DEBUG_ENERGY) {
                        Slog.d(TAG, "Mobile uid " + entry.getUid() + ": delta rx="
                                + entry.getRxBytes() + " tx=" + entry.getTxBytes()
                                + " rxPackets=" + entry.getRxPackets()
                                + " txPackets=" + entry.getTxPackets());
                    }

                    totalRxPackets += entry.getRxPackets();
                    totalTxPackets += entry.getTxPackets();

                    final Uid u = getUidStatsLocked(
                            mapUid(entry.getUid()), elapsedRealtimeMs, uptimeMs);
                    u.noteNetworkActivityLocked(NETWORK_MOBILE_RX_DATA, entry.getRxBytes(),
                            entry.getRxPackets());
                    u.noteNetworkActivityLocked(NETWORK_MOBILE_TX_DATA, entry.getTxBytes(),
                            entry.getTxPackets());
                    if (entry.getSet() == NetworkStats.SET_DEFAULT) { // Background transfers
                        u.noteNetworkActivityLocked(NETWORK_MOBILE_BG_RX_DATA,
                                entry.getRxBytes(), entry.getRxPackets());
                        u.noteNetworkActivityLocked(NETWORK_MOBILE_BG_TX_DATA,
                                entry.getTxBytes(), entry.getTxPackets());
                    }

                    mNetworkByteActivityCounters[NETWORK_MOBILE_RX_DATA].addCountLocked(
                            entry.getRxBytes());
                    mNetworkByteActivityCounters[NETWORK_MOBILE_TX_DATA].addCountLocked(
                            entry.getTxBytes());
                    mNetworkPacketActivityCounters[NETWORK_MOBILE_RX_DATA].addCountLocked(
                            entry.getRxPackets());
                    mNetworkPacketActivityCounters[NETWORK_MOBILE_TX_DATA].addCountLocked(
                            entry.getTxPackets());
                }

                // Now distribute proportional blame to the apps that did networking.
                long totalPackets = totalRxPackets + totalTxPackets;
                if (totalPackets > 0) {
                    for (NetworkStats.Entry entry : delta) {
                        if (entry.getRxPackets() == 0 && entry.getTxPackets() == 0) {
                            continue;
                        }

                        final Uid u = getUidStatsLocked(mapUid(entry.getUid()),
                                elapsedRealtimeMs, uptimeMs);

                        // Distribute total radio active time in to this app.
                        final long appPackets = entry.getRxPackets() + entry.getTxPackets();
                        final long appRadioTimeUs =
                                (totalAppRadioTimeUs * appPackets) / totalPackets;
                        u.noteMobileRadioActiveTimeLocked(appRadioTimeUs, elapsedRealtimeMs);

                        // Distribute measured mobile radio charge consumption based on app radio
                        // active time
                        if (uidEstimatedConsumptionMah != null) {
                            uidEstimatedConsumptionMah.incrementValue(u.getUid(),
                                    mMobileRadioPowerCalculator.calcPowerFromRadioActiveDurationMah(
                                            appRadioTimeUs / 1000));
                        }

                        // Remove this app from the totals, so that we don't lose any time
                        // due to rounding.
                        totalAppRadioTimeUs -= appRadioTimeUs;
                        totalPackets -= appPackets;

                        if (deltaInfo != null) {
                            ControllerActivityCounterImpl activityCounter =
                                    u.getOrCreateModemControllerActivityLocked();
                            if (totalRxPackets > 0 && entry.getRxPackets() > 0) {
                                final long rxMs = (entry.getRxPackets()
                                    * deltaInfo.getReceiveTimeMillis()) / totalRxPackets;
                                activityCounter.getOrCreateRxTimeCounter()
                                        .increment(rxMs, elapsedRealtimeMs);
                            }

                            if (totalTxPackets > 0 && entry.getTxPackets() > 0) {
                                for (int lvl = 0; lvl < ModemActivityInfo.getNumTxPowerLevels();
                                        lvl++) {
                                    long txMs = entry.getTxPackets()
                                            * deltaInfo.getTransmitDurationMillisAtPowerLevel(lvl);
                                    txMs /= totalTxPackets;
                                    activityCounter.getOrCreateTxTimeCounters()[lvl]
                                            .increment(txMs, elapsedRealtimeMs);
                                }
                            }
                        }
                    }
                }

                if (totalAppRadioTimeUs > 0) {
                    // Whoops, there is some radio time we can't blame on an app!
                    mMobileRadioActiveUnknownTime.addCountLocked(totalAppRadioTimeUs);
                    mMobileRadioActiveUnknownCount.addCountLocked(1);
                }


                // Update the MeasuredEnergyStats information.
                if (uidEstimatedConsumptionMah != null) {
                    double totalEstimatedConsumptionMah = 0.0;
                    totalEstimatedConsumptionMah +=
                            mMobileRadioPowerCalculator.calcPowerFromRadioActiveDurationMah(
                                    totalRadioDurationMs);

                    // Estimate idle power consumption at each signal strength level
                    final int numSignalStrengthLevels = mPhoneSignalStrengthsTimer.length;
                    for (int strengthLevel = 0; strengthLevel < numSignalStrengthLevels;
                            strengthLevel++) {
                        final long strengthLevelDurationMs =
                                mPhoneSignalStrengthsTimer[strengthLevel].getTimeSinceMarkLocked(
                                        elapsedRealtimeMs * 1000) / 1000;
                        mPhoneSignalStrengthsTimer[strengthLevel].setMark(elapsedRealtimeMs);

                        totalEstimatedConsumptionMah +=
                                mMobileRadioPowerCalculator.calcIdlePowerAtSignalStrengthMah(
                                        strengthLevelDurationMs, strengthLevel);
                    }

                    // Estimate total active radio power consumption since last mark.
                    final long scanTimeMs = mPhoneSignalScanningTimer.getTimeSinceMarkLocked(
                            elapsedRealtimeMs * 1000) / 1000;
                    mPhoneSignalScanningTimer.setMark(elapsedRealtimeMs);
                    totalEstimatedConsumptionMah +=
                            mMobileRadioPowerCalculator.calcScanTimePowerMah(scanTimeMs);

                    distributeEnergyToUidsLocked(MeasuredEnergyStats.POWER_BUCKET_MOBILE_RADIO,
                            dataConsumedChargeUC, uidEstimatedConsumptionMah,
                            totalEstimatedConsumptionMah, elapsedRealtimeMs);
                }

                delta = null;
            }
        }
    }

    @GuardedBy("this")
    private void incrementPerRatDataLocked(ModemActivityInfo deltaInfo, long elapsedRealtimeMs) {
        final int infoSize = deltaInfo.getSpecificInfoLength();
        if (infoSize == 1 && deltaInfo.getSpecificInfoRat(0)
                == AccessNetworkConstants.AccessNetworkType.UNKNOWN
                && deltaInfo.getSpecificInfoFrequencyRange(0)
                == ServiceState.FREQUENCY_RANGE_UNKNOWN) {
            // Specific info data unavailable. Proportionally smear Rx and Tx times across each RAT.
            final int levelCount = CellSignalStrength.getNumSignalStrengthLevels();
            long[] perSignalStrengthActiveTimeMs = new long[levelCount];
            long totalActiveTimeMs = 0;

            for (int rat = 0; rat < RADIO_ACCESS_TECHNOLOGY_COUNT; rat++) {
                final RadioAccessTechnologyBatteryStats ratStats = mPerRatBatteryStats[rat];
                if (ratStats == null) continue;

                final int freqCount = ratStats.getFrequencyRangeCount();
                for (int freq = 0; freq < freqCount; freq++) {
                    for (int level = 0; level < levelCount; level++) {
                        final long durationMs = ratStats.getTimeSinceMark(freq, level,
                                elapsedRealtimeMs);
                        perSignalStrengthActiveTimeMs[level] += durationMs;
                        totalActiveTimeMs += durationMs;
                    }
                }
            }
            if (totalActiveTimeMs != 0) {
                // Smear the provided Tx/Rx durations across each RAT, frequency, and signal
                // strength.
                for (int rat = 0; rat < RADIO_ACCESS_TECHNOLOGY_COUNT; rat++) {
                    final RadioAccessTechnologyBatteryStats ratStats = mPerRatBatteryStats[rat];
                    if (ratStats == null) continue;

                    final int freqCount = ratStats.getFrequencyRangeCount();
                    for (int freq = 0; freq < freqCount; freq++) {
                        long frequencyDurationMs = 0;
                        for (int level = 0; level < levelCount; level++) {
                            final long durationMs = ratStats.getTimeSinceMark(freq, level,
                                    elapsedRealtimeMs);
                            final long totalLvlDurationMs =
                                    perSignalStrengthActiveTimeMs[level];
                            if (totalLvlDurationMs == 0) continue;
                            final long totalTxLvlDurations =
                                    deltaInfo.getTransmitDurationMillisAtPowerLevel(level);
                            // Smear HAL provided Tx power level duration based on active modem
                            // duration in a given state. (Add totalLvlDurationMs / 2 before
                            // the integer division with totalLvlDurationMs for rounding.)
                            final long proportionalTxDurationMs =
                                    (durationMs * totalTxLvlDurations
                                            + (totalLvlDurationMs / 2)) / totalLvlDurationMs;
                            ratStats.incrementTxDuration(freq, level, proportionalTxDurationMs);
                            frequencyDurationMs += durationMs;
                        }
                        final long totalRxDuration = deltaInfo.getReceiveTimeMillis();
                        // Smear HAL provided Rx power duration based on active modem
                        // duration in a given state.  (Add totalActiveTimeMs / 2 before the
                        // integer division with totalActiveTimeMs for rounding.)
                        final long proportionalRxDurationMs =
                                (frequencyDurationMs * totalRxDuration + (totalActiveTimeMs
                                        / 2)) / totalActiveTimeMs;
                        ratStats.incrementRxDuration(freq, proportionalRxDurationMs);
                    }

                }
            }
        } else {
            // Specific data available.
            for (int index = 0; index < infoSize; index++) {
                final int rat = deltaInfo.getSpecificInfoRat(index);
                final int freq = deltaInfo.getSpecificInfoFrequencyRange(index);

                // Map RadioAccessNetworkType to course grain RadioAccessTechnology.
                final int ratBucket = mapRadioAccessNetworkTypeToRadioAccessTechnology(rat);
                final RadioAccessTechnologyBatteryStats ratStats = getRatBatteryStatsLocked(
                        ratBucket);

                final long rxTimeMs = deltaInfo.getReceiveTimeMillis(rat, freq);
                final int[] txTimesMs = deltaInfo.getTransmitTimeMillis(rat, freq);

                ratStats.incrementRxDuration(freq, rxTimeMs);
                final int numTxLvl = txTimesMs.length;
                for (int lvl = 0; lvl < numTxLvl; lvl++) {
                    ratStats.incrementTxDuration(freq, lvl, txTimesMs[lvl]);
                }
            }
        }

        for (int rat = 0; rat < RADIO_ACCESS_TECHNOLOGY_COUNT; rat++) {
            final RadioAccessTechnologyBatteryStats ratStats = mPerRatBatteryStats[rat];
            if (ratStats == null) continue;
            ratStats.setMark(elapsedRealtimeMs);
        }
    }

    /**
     * Add modem tx power to history
     * Device is said to be in high cellular transmit power when it has spent most of the transmit
     * time at the highest power level.
     * @param activityInfo
     */
    private synchronized void addModemTxPowerToHistory(final ModemActivityInfo activityInfo,
            long elapsedRealtimeMs, long uptimeMs) {
        if (activityInfo == null) {
            return;
        }
        int levelMaxTimeSpent = 0;
        for (int i = 1; i < ModemActivityInfo.getNumTxPowerLevels(); i++) {
            if (activityInfo.getTransmitDurationMillisAtPowerLevel(i)
                    > activityInfo.getTransmitDurationMillisAtPowerLevel(levelMaxTimeSpent)) {
                levelMaxTimeSpent = i;
            }
        }
        if (levelMaxTimeSpent == ModemActivityInfo.getNumTxPowerLevels() - 1) {
            mHistoryCur.states2 |= HistoryItem.STATE2_CELLULAR_HIGH_TX_POWER_FLAG;
            addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
        }
    }

    private final class BluetoothActivityInfoCache {
        long idleTimeMs;
        long rxTimeMs;
        long txTimeMs;
        long energy;

        SparseLongArray uidRxBytes = new SparseLongArray();
        SparseLongArray uidTxBytes = new SparseLongArray();

        void set(BluetoothActivityEnergyInfo info) {
            idleTimeMs = info.getControllerIdleTimeMillis();
            rxTimeMs = info.getControllerRxTimeMillis();
            txTimeMs = info.getControllerTxTimeMillis();
            energy = info.getControllerEnergyUsed();
            if (!info.getUidTraffic().isEmpty()) {
                for (UidTraffic traffic : info.getUidTraffic()) {
                    uidRxBytes.incrementValue(traffic.getUid(), traffic.getRxBytes());
                    uidTxBytes.incrementValue(traffic.getUid(), traffic.getTxBytes());
                }
            }
        }

        void reset() {
            idleTimeMs = 0;
            rxTimeMs = 0;
            txTimeMs = 0;
            energy = 0;
            uidRxBytes.clear();
            uidTxBytes.clear();
        }
    }

    private final BluetoothActivityInfoCache mLastBluetoothActivityInfo
            = new BluetoothActivityInfoCache();

    /**
     * Distribute Bluetooth energy info and network traffic to apps.
     *
     * @param info The accumulated energy information from the bluetooth controller.
     */
    @GuardedBy("this")
    public void updateBluetoothStateLocked(@Nullable final BluetoothActivityEnergyInfo info,
            final long consumedChargeUC, long elapsedRealtimeMs, long uptimeMs) {
        if (DEBUG_ENERGY) {
            Slog.d(TAG, "Updating bluetooth stats: " + info);
        }

        if (info == null) {
            return;
        }
        if (!mOnBatteryInternal || mIgnoreNextExternalStats) {
            mLastBluetoothActivityInfo.set(info);
            return;
        }

        mHasBluetoothReporting = true;

        if (info.getControllerRxTimeMillis() < mLastBluetoothActivityInfo.rxTimeMs
                || info.getControllerTxTimeMillis() < mLastBluetoothActivityInfo.txTimeMs
                || info.getControllerIdleTimeMillis() < mLastBluetoothActivityInfo.idleTimeMs
                || info.getControllerEnergyUsed() < mLastBluetoothActivityInfo.energy) {
            // A drop in accumulated Bluetooth stats is a sign of a Bluetooth crash.
            // Reset the preserved previous snapshot in order to restart accumulating deltas.
            mLastBluetoothActivityInfo.reset();
        }

        final long rxTimeMs =
                info.getControllerRxTimeMillis() - mLastBluetoothActivityInfo.rxTimeMs;
        final long txTimeMs =
                info.getControllerTxTimeMillis() - mLastBluetoothActivityInfo.txTimeMs;
        final long idleTimeMs =
                info.getControllerIdleTimeMillis() - mLastBluetoothActivityInfo.idleTimeMs;

        if (DEBUG_ENERGY) {
            Slog.d(TAG, "------ BEGIN BLE power blaming ------");
            Slog.d(TAG, "  Tx Time:    " + txTimeMs + " ms");
            Slog.d(TAG, "  Rx Time:    " + rxTimeMs + " ms");
            Slog.d(TAG, "  Idle Time:  " + idleTimeMs + " ms");
        }

        final SparseDoubleArray uidEstimatedConsumptionMah =
                (mGlobalMeasuredEnergyStats != null
                        && mBluetoothPowerCalculator != null && consumedChargeUC > 0) ?
                        new SparseDoubleArray() : null;

        long totalScanTimeMs = 0;

        final int uidCount = mUidStats.size();
        for (int i = 0; i < uidCount; i++) {
            final Uid u = mUidStats.valueAt(i);
            if (u.mBluetoothScanTimer == null) {
                continue;
            }

            totalScanTimeMs += u.mBluetoothScanTimer.getTimeSinceMarkLocked(
                    elapsedRealtimeMs * 1000) / 1000;
        }

        final boolean normalizeScanRxTime = (totalScanTimeMs > rxTimeMs);
        final boolean normalizeScanTxTime = (totalScanTimeMs > txTimeMs);

        if (DEBUG_ENERGY) {
            Slog.d(TAG, "Normalizing scan power for RX=" + normalizeScanRxTime
                    + " TX=" + normalizeScanTxTime);
        }

        long leftOverRxTimeMs = rxTimeMs;
        long leftOverTxTimeMs = txTimeMs;

        final SparseLongArray rxTimesMs = new SparseLongArray(uidCount);
        final SparseLongArray txTimesMs = new SparseLongArray(uidCount);

        for (int i = 0; i < uidCount; i++) {
            final Uid u = mUidStats.valueAt(i);
            if (u.mBluetoothScanTimer == null) {
                continue;
            }

            long scanTimeSinceMarkMs = u.mBluetoothScanTimer.getTimeSinceMarkLocked(
                    elapsedRealtimeMs * 1000) / 1000;
            if (scanTimeSinceMarkMs > 0) {
                // Set the new mark so that next time we get new data since this point.
                u.mBluetoothScanTimer.setMark(elapsedRealtimeMs);

                long scanTimeRxSinceMarkMs = scanTimeSinceMarkMs;
                long scanTimeTxSinceMarkMs = scanTimeSinceMarkMs;

                if (normalizeScanRxTime) {
                    // Scan time is longer than the total rx time in the controller,
                    // so distribute the scan time proportionately. This means regular traffic
                    // will not blamed, but scans are more expensive anyways.
                    scanTimeRxSinceMarkMs = (rxTimeMs * scanTimeRxSinceMarkMs) / totalScanTimeMs;
                }

                if (normalizeScanTxTime) {
                    // Scan time is longer than the total tx time in the controller,
                    // so distribute the scan time proportionately. This means regular traffic
                    // will not blamed, but scans are more expensive anyways.
                    scanTimeTxSinceMarkMs = (txTimeMs * scanTimeTxSinceMarkMs) / totalScanTimeMs;
                }

                rxTimesMs.incrementValue(u.getUid(), scanTimeRxSinceMarkMs);
                txTimesMs.incrementValue(u.getUid(), scanTimeTxSinceMarkMs);

                if (uidEstimatedConsumptionMah != null) {
                    uidEstimatedConsumptionMah.incrementValue(u.getUid(),
                            mBluetoothPowerCalculator.calculatePowerMah(
                                    scanTimeRxSinceMarkMs, scanTimeTxSinceMarkMs, 0));
                }

                leftOverRxTimeMs -= scanTimeRxSinceMarkMs;
                leftOverTxTimeMs -= scanTimeTxSinceMarkMs;
            }
        }

        if (DEBUG_ENERGY) {
            Slog.d(TAG, "Left over time for traffic RX=" + leftOverRxTimeMs + " TX="
                    + leftOverTxTimeMs);
        }

        //
        // Now distribute blame to apps that did bluetooth traffic.
        //

        long totalTxBytes = 0;
        long totalRxBytes = 0;

        final List<UidTraffic> uidTraffic = info.getUidTraffic();
        final int numUids = uidTraffic.size();
        for (int i = 0; i < numUids; i++) {
            final UidTraffic traffic = uidTraffic.get(i);
            final long rxBytes = traffic.getRxBytes() - mLastBluetoothActivityInfo.uidRxBytes.get(
                    traffic.getUid());
            final long txBytes = traffic.getTxBytes() - mLastBluetoothActivityInfo.uidTxBytes.get(
                    traffic.getUid());

            // Add to the global counters.
            mNetworkByteActivityCounters[NETWORK_BT_RX_DATA].addCountLocked(rxBytes);
            mNetworkByteActivityCounters[NETWORK_BT_TX_DATA].addCountLocked(txBytes);

            // Add to the UID counters.
            final Uid u = getUidStatsLocked(mapUid(traffic.getUid()), elapsedRealtimeMs, uptimeMs);
            u.noteNetworkActivityLocked(NETWORK_BT_RX_DATA, rxBytes, 0);
            u.noteNetworkActivityLocked(NETWORK_BT_TX_DATA, txBytes, 0);

            // Calculate the total traffic.
            totalRxBytes += rxBytes;
            totalTxBytes += txBytes;
        }

        if ((totalTxBytes != 0 || totalRxBytes != 0) && (leftOverRxTimeMs != 0
                || leftOverTxTimeMs != 0)) {
            for (int i = 0; i < numUids; i++) {
                final UidTraffic traffic = uidTraffic.get(i);
                final int uid = traffic.getUid();
                final long rxBytes =
                        traffic.getRxBytes() - mLastBluetoothActivityInfo.uidRxBytes.get(uid);
                final long txBytes =
                        traffic.getTxBytes() - mLastBluetoothActivityInfo.uidTxBytes.get(uid);

                final Uid u = getUidStatsLocked(mapUid(uid), elapsedRealtimeMs, uptimeMs);
                final ControllerActivityCounterImpl counter =
                        u.getOrCreateBluetoothControllerActivityLocked();

                if (totalRxBytes > 0 && rxBytes > 0) {
                    final long timeRxMs = (leftOverRxTimeMs * rxBytes) / totalRxBytes;
                    rxTimesMs.incrementValue(uid, timeRxMs);
                }

                if (totalTxBytes > 0 && txBytes > 0) {
                    final long timeTxMs = (leftOverTxTimeMs * txBytes) / totalTxBytes;
                    txTimesMs.incrementValue(uid, timeTxMs);
                }
            }

            for (int i = 0; i < txTimesMs.size(); i++) {
                final int uid = txTimesMs.keyAt(i);
                final long myTxTimeMs = txTimesMs.valueAt(i);
                if (DEBUG_ENERGY) {
                    Slog.d(TAG, "  TxTime for UID " + uid + ": " + myTxTimeMs + " ms");
                }
                getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs)
                        .getOrCreateBluetoothControllerActivityLocked()
                        .getOrCreateTxTimeCounters()[0]
                        .increment(myTxTimeMs, elapsedRealtimeMs);
                if (uidEstimatedConsumptionMah != null) {
                    uidEstimatedConsumptionMah.incrementValue(uid,
                            mBluetoothPowerCalculator.calculatePowerMah(0, myTxTimeMs, 0));
                }
            }

            for (int i = 0; i < rxTimesMs.size(); i++) {
                final int uid = rxTimesMs.keyAt(i);
                final long myRxTimeMs = rxTimesMs.valueAt(i);
                if (DEBUG_ENERGY) {
                    Slog.d(TAG, "  RxTime for UID " + uid + ": " + myRxTimeMs + " ms");
                }

                getUidStatsLocked(rxTimesMs.keyAt(i), elapsedRealtimeMs, uptimeMs)
                        .getOrCreateBluetoothControllerActivityLocked()
                        .getOrCreateRxTimeCounter()
                        .increment(myRxTimeMs, elapsedRealtimeMs);
                if (uidEstimatedConsumptionMah != null) {
                    uidEstimatedConsumptionMah.incrementValue(uid,
                            mBluetoothPowerCalculator.calculatePowerMah(myRxTimeMs, 0, 0));
                }
            }
        }

        mBluetoothActivity.getOrCreateRxTimeCounter().increment(rxTimeMs, elapsedRealtimeMs);
        mBluetoothActivity.getOrCreateTxTimeCounters()[0].increment(txTimeMs, elapsedRealtimeMs);
        mBluetoothActivity.getOrCreateIdleTimeCounter().increment(idleTimeMs, elapsedRealtimeMs);

        // POWER_BLUETOOTH_CONTROLLER_OPERATING_VOLTAGE is measured in mV, so convert to V.
        final double opVolt = mPowerProfile.getAveragePower(
                PowerProfile.POWER_BLUETOOTH_CONTROLLER_OPERATING_VOLTAGE) / 1000.0;
        double controllerMaMs = 0;
        if (opVolt != 0) {
            controllerMaMs = (info.getControllerEnergyUsed() - mLastBluetoothActivityInfo.energy)
                    / opVolt;
            // We store the power drain as mAms.
            mBluetoothActivity.getPowerCounter().addCountLocked((long) controllerMaMs);
        }

        // Update the MeasuredEnergyStats information.
        if (uidEstimatedConsumptionMah != null) {
            mGlobalMeasuredEnergyStats.updateStandardBucket(
                    MeasuredEnergyStats.POWER_BUCKET_BLUETOOTH, consumedChargeUC);

            double totalEstimatedMah
                    = mBluetoothPowerCalculator.calculatePowerMah(rxTimeMs, txTimeMs, idleTimeMs);
            totalEstimatedMah = Math.max(totalEstimatedMah, controllerMaMs / MILLISECONDS_IN_HOUR);
            distributeEnergyToUidsLocked(MeasuredEnergyStats.POWER_BUCKET_BLUETOOTH,
                    consumedChargeUC, uidEstimatedConsumptionMah, totalEstimatedMah,
                    elapsedRealtimeMs);
        }

        mLastBluetoothActivityInfo.set(info);
    }
    /**
     * Read Resource Power Manager (RPM) state and voter times.
     * If RPM stats were fetched more recently than RPM_STATS_UPDATE_FREQ_MS ago, uses the old data
     * instead of fetching it anew.
     *
     * Note: This should be called without synchronizing this BatteryStatsImpl object
     */
    public void fillLowPowerStats() {
        if (mPlatformIdleStateCallback == null) return;

        RpmStats rpmStats = new RpmStats();
        long now = SystemClock.elapsedRealtime();
        if (now - mLastRpmStatsUpdateTimeMs >= RPM_STATS_UPDATE_FREQ_MS) {
            mPlatformIdleStateCallback.fillLowPowerStats(rpmStats);
            synchronized (this) {
                mTmpRpmStats = rpmStats;
                mLastRpmStatsUpdateTimeMs = now;
            }
        }
    }

    /**
     * Record Resource Power Manager (RPM) state and voter times.
     * TODO(b/185252376): Remove this logging. PowerStatsService logs the same data more
     * efficiently.
     */
    public void updateRpmStatsLocked(long elapsedRealtimeUs) {
        if (mTmpRpmStats == null) return;

        for (Map.Entry<String, RpmStats.PowerStatePlatformSleepState> pstate
                : mTmpRpmStats.mPlatformLowPowerStats.entrySet()) {

            // Update values for this platform state.
            final String pName = pstate.getKey();
            final long pTimeUs = pstate.getValue().mTimeMs * 1000;
            final int pCount = pstate.getValue().mCount;
            getRpmTimerLocked(pName).update(pTimeUs, pCount, elapsedRealtimeUs);
            if (SCREEN_OFF_RPM_STATS_ENABLED) {
                getScreenOffRpmTimerLocked(pName).update(pTimeUs, pCount, elapsedRealtimeUs);
            }

            // Update values for each voter of this platform state.
            for (Map.Entry<String, RpmStats.PowerStateElement> voter
                    : pstate.getValue().mVoters.entrySet()) {
                final String vName = pName + "." + voter.getKey();
                final long vTimeUs = voter.getValue().mTimeMs * 1000;
                final int vCount = voter.getValue().mCount;
                getRpmTimerLocked(vName).update(vTimeUs, vCount, elapsedRealtimeUs);
                if (SCREEN_OFF_RPM_STATS_ENABLED) {
                    getScreenOffRpmTimerLocked(vName).update(vTimeUs, vCount, elapsedRealtimeUs);
                }
            }
        }

        for (Map.Entry<String, RpmStats.PowerStateSubsystem> subsys
                : mTmpRpmStats.mSubsystemLowPowerStats.entrySet()) {

            final String subsysName = subsys.getKey();
            for (Map.Entry<String, RpmStats.PowerStateElement> sstate
                    : subsys.getValue().mStates.entrySet()) {
                final String name = subsysName + "." + sstate.getKey();
                final long timeUs = sstate.getValue().mTimeMs * 1000;
                final int count = sstate.getValue().mCount;
                getRpmTimerLocked(name).update(timeUs, count, elapsedRealtimeUs);
                if (SCREEN_OFF_RPM_STATS_ENABLED) {
                    getScreenOffRpmTimerLocked(name).update(timeUs, count, elapsedRealtimeUs);
                }
            }
        }
    }

    /**
     * Accumulate Cpu charge consumption and distribute it to the correct state and the apps.
     * Only call if device is on battery.
     *
     * @param clusterChargeUC amount of charge (microcoulombs) consumed by each Cpu Cluster
     * @param accumulator collection of calculated uid cpu power consumption to smear
     *                    clusterChargeUC against.
     */
    @GuardedBy("this")
    @SuppressWarnings("GuardedBy") // errorprone false positive on u.addChargeToStandardBucketLocked
    private void updateCpuMeasuredEnergyStatsLocked(@NonNull long[] clusterChargeUC,
            @NonNull CpuDeltaPowerAccumulator accumulator) {
        if (DEBUG_ENERGY) {
            Slog.d(TAG,
                    "Updating cpu cluster stats: " + clusterChargeUC.toString());
        }
        if (mGlobalMeasuredEnergyStats == null) {
            return;
        }

        final int numClusters = clusterChargeUC.length;
        long totalCpuChargeUC = 0;
        for (int i = 0; i < numClusters; i++) {
            totalCpuChargeUC += clusterChargeUC[i];
        }
        if (totalCpuChargeUC <= 0) return;

        final long timestampMs = mClock.elapsedRealtime();

        mGlobalMeasuredEnergyStats.updateStandardBucket(MeasuredEnergyStats.POWER_BUCKET_CPU,
                totalCpuChargeUC, timestampMs);

        // Calculate the measured microcoulombs/calculated milliamp-hour charge ratio for each
        // cluster to normalize  each uid's estimated power usage against actual power usage for
        // a given cluster.
        final double[] clusterChargeRatio = new double[numClusters];
        for (int cluster = 0; cluster < numClusters; cluster++) {

            final double totalClusterChargeMah = accumulator.totalClusterChargesMah[cluster];
            if (totalClusterChargeMah <= 0.0) {
                // This cluster did not have any work on it, since last update.
                // Avoid dividing by zero.
                clusterChargeRatio[cluster] = 0.0;
            } else {
                clusterChargeRatio[cluster] =
                        clusterChargeUC[cluster] / accumulator.totalClusterChargesMah[cluster];
            }
        }

        // Assign and distribute power usage to apps based on their calculated cpu cluster charge.
        final long uidChargeArraySize = accumulator.perUidCpuClusterChargesMah.size();
        for (int i = 0; i < uidChargeArraySize; i++) {
            final Uid uid = accumulator.perUidCpuClusterChargesMah.keyAt(i);
            final double[] uidClusterChargesMah = accumulator.perUidCpuClusterChargesMah.valueAt(i);

            // Iterate each cpu cluster and sum the proportional measured cpu cluster charge to
            // get the total cpu charge consumed by a uid.
            long uidCpuChargeUC = 0;
            for (int cluster = 0; cluster < numClusters; cluster++) {
                final double uidClusterChargeMah = uidClusterChargesMah[cluster];

                // Proportionally allocate the measured cpu cluster charge to a uid using the
                // measured charge/calculated charge ratio. Add 0.5 to round the proportional
                // charge double to the nearest long value.
                final long uidClusterChargeUC =
                        (long) (uidClusterChargeMah * clusterChargeRatio[cluster]
                                + 0.5);

                uidCpuChargeUC += uidClusterChargeUC;
            }

            if (uidCpuChargeUC < 0) {
                Slog.wtf(TAG,
                        "Unexpected proportional measured charge (" + uidCpuChargeUC + ") for uid "
                                + uid.mUid);
                continue;
            }

            uid.addChargeToStandardBucketLocked(uidCpuChargeUC,
                    MeasuredEnergyStats.POWER_BUCKET_CPU, timestampMs);
        }
    }

    /**
     * Accumulate Display charge consumption and distribute it to the correct state and the apps.
     *
     * NOTE: The algorithm used makes the strong assumption that app foreground activity time
     * is always 0 when the screen is not "ON" and whenever the rail energy is 0 (if supported).
     * To the extent that those assumptions are violated, the algorithm will err.
     *
     * @param chargesUC amount of charge (microcoulombs) used by each Display since this was last
     *                 called.
     * @param screenStates each screen state at the time this data collection was scheduled
     */
    @GuardedBy("this")
    public void updateDisplayMeasuredEnergyStatsLocked(long[] chargesUC, int[] screenStates,
            long elapsedRealtimeMs) {
        if (DEBUG_ENERGY) Slog.d(TAG, "Updating display stats: " + Arrays.toString(chargesUC));
        if (mGlobalMeasuredEnergyStats == null) {
            return;
        }

        final int numDisplays;
        if (mPerDisplayBatteryStats.length == screenStates.length) {
            numDisplays = screenStates.length;
        } else {
            // if this point is reached, it will be reached every display state change.
            // Rate limit the wtf logging to once every 100 display updates.
            if (mDisplayMismatchWtfCount++ % 100 == 0) {
                Slog.wtf(TAG, "Mismatch between PowerProfile reported display count ("
                        + mPerDisplayBatteryStats.length
                        + ") and PowerStatsHal reported display count (" + screenStates.length
                        + ")");
            }
            // Keep the show going, use the shorter of the two.
            numDisplays = mPerDisplayBatteryStats.length < screenStates.length
                    ? mPerDisplayBatteryStats.length : screenStates.length;
        }

        final int[] oldScreenStates = new int[numDisplays];
        for (int i = 0; i < numDisplays; i++) {
            final int screenState = screenStates[i];
            oldScreenStates[i] = mPerDisplayBatteryStats[i].screenStateAtLastEnergyMeasurement;
            mPerDisplayBatteryStats[i].screenStateAtLastEnergyMeasurement = screenState;
        }

        if (!mOnBatteryInternal) {
            // There's nothing further to update.
            return;
        }
        if (mIgnoreNextExternalStats) {
            // Although under ordinary resets we won't get here, and typically a new sync will
            // happen right after the reset, strictly speaking we need to set all mark times to now.
            final int uidStatsSize = mUidStats.size();
            for (int i = 0; i < uidStatsSize; i++) {
                final Uid uid = mUidStats.valueAt(i);
                uid.markProcessForegroundTimeUs(elapsedRealtimeMs, false);
            }
            return;
        }

        long totalScreenOnChargeUC = 0;
        for (int i = 0; i < numDisplays; i++) {
            final long chargeUC = chargesUC[i];
            if (chargeUC <= 0) {
                // There's nothing further to update.
                continue;
            }

            final @StandardPowerBucket int powerBucket =
                    MeasuredEnergyStats.getDisplayPowerBucket(oldScreenStates[i]);
            mGlobalMeasuredEnergyStats.updateStandardBucket(powerBucket, chargeUC);
            if (powerBucket == MeasuredEnergyStats.POWER_BUCKET_SCREEN_ON) {
                totalScreenOnChargeUC += chargeUC;
            }
        }

        // Now we blame individual apps, but only if the display was ON.
        if (totalScreenOnChargeUC <= 0) {
            return;
        }
        // TODO(b/175726779): Consider unifying the code with the non-rail display power blaming.

        // NOTE: fg time is NOT pooled. If two uids are both somehow in fg, then that time is
        // 'double counted' and will simply exceed the realtime that elapsed.
        // TODO(b/175726779): collect per display uid visibility for display power attribution.

        // Collect total time since mark so that we can normalize power.
        final SparseDoubleArray fgTimeUsArray = new SparseDoubleArray();
        final long elapsedRealtimeUs = elapsedRealtimeMs * 1000;
        // TODO(b/175726779): Update and optimize the algorithm (e.g. avoid iterating over ALL uids)
        final int uidStatsSize = mUidStats.size();
        for (int i = 0; i < uidStatsSize; i++) {
            final Uid uid = mUidStats.valueAt(i);
            final long fgTimeUs = uid.markProcessForegroundTimeUs(elapsedRealtimeMs, true);
            if (fgTimeUs == 0) continue;
            fgTimeUsArray.put(uid.getUid(), (double) fgTimeUs);
        }
        distributeEnergyToUidsLocked(MeasuredEnergyStats.POWER_BUCKET_SCREEN_ON,
                totalScreenOnChargeUC, fgTimeUsArray, 0, elapsedRealtimeMs);
    }

    /**
     * Accumulate GNSS charge consumption and distribute it to the correct state and the apps.
     *
     * @param chargeUC amount of charge (microcoulombs) used by GNSS since this was last called.
     */
    @GuardedBy("this")
    public void updateGnssMeasuredEnergyStatsLocked(long chargeUC, long elapsedRealtimeMs) {
        if (DEBUG_ENERGY) Slog.d(TAG, "Updating gnss stats: " + chargeUC);
        if (mGlobalMeasuredEnergyStats == null) {
            return;
        }

        if (!mOnBatteryInternal || chargeUC <= 0) {
            // There's nothing further to update.
            return;
        }
        if (mIgnoreNextExternalStats) {
            // Although under ordinary resets we won't get here, and typically a new sync will
            // happen right after the reset, strictly speaking we need to set all mark times to now.
            final int uidStatsSize = mUidStats.size();
            for (int i = 0; i < uidStatsSize; i++) {
                final Uid uid = mUidStats.valueAt(i);
                uid.markGnssTimeUs(elapsedRealtimeMs);
            }
            return;
        }

        mGlobalMeasuredEnergyStats.updateStandardBucket(MeasuredEnergyStats.POWER_BUCKET_GNSS,
                chargeUC);

        // Collect the per uid time since mark so that we can normalize power.
        final SparseDoubleArray gnssTimeUsArray = new SparseDoubleArray();
        // TODO(b/175726779): Update and optimize the algorithm (e.g. avoid iterating over ALL uids)
        final int uidStatsSize = mUidStats.size();
        for (int i = 0; i < uidStatsSize; i++) {
            final Uid uid = mUidStats.valueAt(i);
            final long gnssTimeUs = uid.markGnssTimeUs(elapsedRealtimeMs);
            if (gnssTimeUs == 0) continue;
            gnssTimeUsArray.put(uid.getUid(), (double) gnssTimeUs);
        }
        distributeEnergyToUidsLocked(MeasuredEnergyStats.POWER_BUCKET_GNSS, chargeUC,
                gnssTimeUsArray, 0, elapsedRealtimeMs);
    }

    /**
     * Accumulate Custom power bucket charge, globally and for each app.
     *
     * @param totalChargeUC charge (microcoulombs) used for this bucket since this was last called.
     * @param uidCharges map of uid->charge (microcoulombs) for this bucket since last called.
     *                    Data inside uidCharges will not be modified (treated immutable).
     *                    Uids not already known to BatteryStats will be ignored.
     */
    @GuardedBy("this")
    @SuppressWarnings("GuardedBy") // errorprone false positive on u.addChargeToCustomBucketLocked
    public void updateCustomMeasuredEnergyStatsLocked(int customPowerBucket,
            long totalChargeUC, @Nullable SparseLongArray uidCharges) {
        if (DEBUG_ENERGY) {
            Slog.d(TAG, "Updating attributed measured charge stats for custom bucket "
                    + customPowerBucket
                    + " with total charge " + totalChargeUC
                    + " and uid charges " + String.valueOf(uidCharges));
        }
        if (mGlobalMeasuredEnergyStats == null) return;
        if (!mOnBatteryInternal || mIgnoreNextExternalStats || totalChargeUC <= 0) return;

        mGlobalMeasuredEnergyStats.updateCustomBucket(customPowerBucket, totalChargeUC,
                mClock.elapsedRealtime());

        if (uidCharges == null) return;
        final int numUids = uidCharges.size();
        for (int i = 0; i < numUids; i++) {
            final int uidInt = mapUid(uidCharges.keyAt(i));
            final long uidChargeUC = uidCharges.valueAt(i);
            if (uidChargeUC == 0) continue;

            final Uid uidObj = getAvailableUidStatsLocked(uidInt);
            if (uidObj != null) {
                uidObj.addChargeToCustomBucketLocked(uidChargeUC, customPowerBucket);
            } else {
                // Ignore any uid not already known to BatteryStats, rather than creating a new Uid.
                // Otherwise we could end up reviving dead Uids. Note that the CPU data is updated
                // first, so any uid that has used any CPU should already be known to BatteryStats.
                // Recently removed uids (especially common for isolated uids) can reach this path
                // and are ignored.
                if (!Process.isIsolated(uidInt)) {
                    Slog.w(TAG, "Received measured charge " + totalChargeUC + " for custom bucket "
                            + customPowerBucket + " for non-existent uid " + uidInt);
                }
            }
        }
    }

    /**
     * Attributes energy (for the given bucket) to each uid according to the following formula:
     *     blamedEnergy[uid] = totalEnergy * ratioNumerators[uid] / ratioDenominator;
     * <p>Does nothing if ratioDenominator is 0.
     *
     * <p>Here, ratioDenominator = max(sumOfAllRatioNumerators, minRatioDenominator),
     * so if given minRatioDenominator <= 0, then sumOfAllRatioNumerators will be used implicitly.
     *
     * <p>Note that ratioNumerators and minRatioDenominator must use the same units, but need not
     * use the same units as totalConsumedChargeUC (which must be in microcoulombs).
     *
     * <p>A consequence of minRatioDenominator is that the sum over all uids might be less than
     * totalConsumedChargeUC. This is intentional; the remainder is purposefully unnaccounted rather
     * than incorrectly blamed on uids, and implies unknown (non-uid) sources of drain.
     *
     * <p>All uids in ratioNumerators must exist in mUidStats already.
     */
    @GuardedBy("this")
    @SuppressWarnings("GuardedBy") // errorprone false positive on u.addChargeToStandardBucketLocked
    private void distributeEnergyToUidsLocked(@StandardPowerBucket int bucket,
            long totalConsumedChargeUC, SparseDoubleArray ratioNumerators,
            double minRatioDenominator, long timestampMs) {

        // If the sum of all app usage was greater than the total, use that instead:
        double sumRatioNumerators = 0;
        for (int i = ratioNumerators.size() - 1; i >= 0; i--) {
            sumRatioNumerators += ratioNumerators.valueAt(i);
        }
        final double ratioDenominator = Math.max(sumRatioNumerators, minRatioDenominator);
        if (ratioDenominator <= 0) return;

        for (int i = ratioNumerators.size() - 1; i >= 0; i--) {
            final Uid uid = getAvailableUidStatsLocked(ratioNumerators.keyAt(i));
            final double ratioNumerator = ratioNumerators.valueAt(i);
            final long uidActualUC
                    = (long) (totalConsumedChargeUC * ratioNumerator / ratioDenominator + 0.5);
            uid.addChargeToStandardBucketLocked(uidActualUC, bucket, timestampMs);
        }
    }

    /**
     * Read and record Rail Energy data.
     */
    public void updateRailStatsLocked() {
        if (mMeasuredEnergyRetriever == null || !mTmpRailStats.isRailStatsAvailable()) {
            return;
        }
        mMeasuredEnergyRetriever.fillRailDataStats(mTmpRailStats);
    }

    /** Informs that external stats data has been completely flushed. */
    public void informThatAllExternalStatsAreFlushed() {
        synchronized (this) {
            // Any data from the pre-reset era is flushed, so we can henceforth process future data.
            mIgnoreNextExternalStats = false;
        }
    }

    /**
     * Read and distribute kernel wake lock use across apps.
     */
    public void updateKernelWakelocksLocked() {
        updateKernelWakelocksLocked(mClock.elapsedRealtime() * 1000);
    }

    /**
     * @see #updateKernelWakelocksLocked()
     */
    public void updateKernelWakelocksLocked(long elapsedRealtimeUs) {
        final KernelWakelockStats wakelockStats = mKernelWakelockReader.readKernelWakelockStats(
                mTmpWakelockStats);
        if (wakelockStats == null) {
            // Not crashing might make board bringup easier.
            Slog.w(TAG, "Couldn't get kernel wake lock stats");
            return;
        }

        for (Map.Entry<String, KernelWakelockStats.Entry> ent : wakelockStats.entrySet()) {
            String name = ent.getKey();
            KernelWakelockStats.Entry kws = ent.getValue();

            SamplingTimer kwlt = mKernelWakelockStats.get(name);
            if (kwlt == null) {
                kwlt = new SamplingTimer(mClock, mOnBatteryScreenOffTimeBase);
                mKernelWakelockStats.put(name, kwlt);
            }

            kwlt.update(kws.mTotalTime, kws.mCount, elapsedRealtimeUs);
            kwlt.setUpdateVersion(kws.mVersion);
        }

        int numWakelocksSetStale = 0;
        // Set timers to stale if they didn't appear in /d/wakeup_sources (or /proc/wakelocks)
        // this time.
        for (Map.Entry<String, SamplingTimer> ent : mKernelWakelockStats.entrySet()) {
            SamplingTimer st = ent.getValue();
            if (st.getUpdateVersion() != wakelockStats.kernelWakelockVersion) {
                st.endSample(elapsedRealtimeUs);
                numWakelocksSetStale++;
            }
        }

        // Record whether we've seen a non-zero time (for debugging b/22716723).
        if (wakelockStats.isEmpty()) {
            Slog.wtf(TAG, "All kernel wakelocks had time of zero");
        }

        if (numWakelocksSetStale == mKernelWakelockStats.size()) {
            Slog.wtf(TAG, "All kernel wakelocks were set stale. new version=" +
                    wakelockStats.kernelWakelockVersion);
        }
    }

    // We use an anonymous class to access these variables,
    // so they can't live on the stack or they'd have to be
    // final MutableLong objects (more allocations).
    // Used in updateCpuTimeLocked().
    long mTempTotalCpuUserTimeUs;
    long mTempTotalCpuSystemTimeUs;
    long[][] mWakeLockAllocationsUs;

    /**
     * Reads the newest memory stats from the kernel.
     */
    public void updateKernelMemoryBandwidthLocked() {
        updateKernelMemoryBandwidthLocked(mClock.elapsedRealtime() * 1000);
    }

    public void updateKernelMemoryBandwidthLocked(long elapsedRealtimeUs) {
        mKernelMemoryBandwidthStats.updateStats();
        LongSparseLongArray bandwidthEntries = mKernelMemoryBandwidthStats.getBandwidthEntries();
        final int bandwidthEntryCount = bandwidthEntries.size();
        int index;
        for (int i = 0; i < bandwidthEntryCount; i++) {
            SamplingTimer timer;
            if ((index = mKernelMemoryStats.indexOfKey(bandwidthEntries.keyAt(i))) >= 0) {
                timer = mKernelMemoryStats.valueAt(index);
            } else {
                timer = new SamplingTimer(mClock, mOnBatteryTimeBase);
                mKernelMemoryStats.put(bandwidthEntries.keyAt(i), timer);
            }
            timer.update(bandwidthEntries.valueAt(i), 1, elapsedRealtimeUs);
            if (DEBUG_MEMORY) {
                Slog.d(TAG, String.format("Added entry %d and updated timer to: "
                        + "mUnpluggedReportedTotalTimeUs %d size %d", bandwidthEntries.keyAt(i),
                        mKernelMemoryStats.get(
                                bandwidthEntries.keyAt(i)).mUnpluggedReportedTotalTimeUs,
                        mKernelMemoryStats.size()));
            }
        }
    }

    public boolean isOnBatteryLocked() {
        return mOnBatteryTimeBase.isRunning();
    }

    public boolean isOnBatteryScreenOffLocked() {
        return mOnBatteryScreenOffTimeBase.isRunning();
    }

    /**
     * Object for calculating and accumulating the estimated cpu power used while reading the
     * various cpu kernel files.
     */
    @VisibleForTesting
    public static class CpuDeltaPowerAccumulator {
        // Keeps track of total charge used per cluster.
        public final double[] totalClusterChargesMah;
        // Keeps track of charge used per cluster per uid.
        public final ArrayMap<Uid, double[]> perUidCpuClusterChargesMah;

        private final CpuPowerCalculator mCalculator;
        private Uid mCachedUid = null;
        private double[] mUidClusterCache = null;

        CpuDeltaPowerAccumulator(CpuPowerCalculator calculator, int nClusters) {
            mCalculator = calculator;
            totalClusterChargesMah = new double[nClusters];
            perUidCpuClusterChargesMah = new ArrayMap<>();
        }

        /** Add per cpu cluster durations to the currently cached uid. */
        public void addCpuClusterDurationsMs(Uid uid, long[] durationsMs) {
            final double[] uidChargesMah = getOrCreateUidCpuClusterCharges(uid);
            for (int cluster = 0; cluster < durationsMs.length; cluster++) {
                final double estimatedDeltaMah = mCalculator.calculatePerCpuClusterPowerMah(cluster,
                        durationsMs[cluster]);
                uidChargesMah[cluster] += estimatedDeltaMah;
                totalClusterChargesMah[cluster] += estimatedDeltaMah;
            }
        }

        /** Add per speed per cpu cluster durations to the currently cached uid. */
        public void addCpuClusterSpeedDurationsMs(Uid uid, int cluster, int speed,
                long durationsMs) {
            final double[] uidChargesMah = getOrCreateUidCpuClusterCharges(uid);
            final double estimatedDeltaMah = mCalculator.calculatePerCpuFreqPowerMah(cluster, speed,
                    durationsMs);
            uidChargesMah[cluster] += estimatedDeltaMah;
            totalClusterChargesMah[cluster] += estimatedDeltaMah;
        }

        private double[] getOrCreateUidCpuClusterCharges(Uid uid) {
            // Repeated additions on the same uid is very likely.
            // Skip a lookup if getting the same uid as the last get.
            if (uid == mCachedUid) return mUidClusterCache;

            double[] uidChargesMah = perUidCpuClusterChargesMah.get(uid);
            if (uidChargesMah == null) {
                uidChargesMah = new double[totalClusterChargesMah.length];
                perUidCpuClusterChargesMah.put(uid, uidChargesMah);
            }
            mCachedUid = uid;
            mUidClusterCache = uidChargesMah;
            return uidChargesMah;
        }
    }

    /**
     * Read and distribute CPU usage across apps. If their are partial wakelocks being held
     * and we are on battery with screen off, we give more of the cpu time to those apps holding
     * wakelocks. If the screen is on, we just assign the actual cpu time an app used.
     * It's possible this will be invoked after the internal battery/screen states are updated, so
     * passing the appropriate battery/screen states to try attribute the cpu times to correct
     * buckets.
     */
    @GuardedBy("this")
    public void updateCpuTimeLocked(boolean onBattery, boolean onBatteryScreenOff,
            long[] measuredCpuClusterChargeUC) {
        if (mPowerProfile == null) {
            return;
        }

        if (DEBUG_ENERGY_CPU) {
            Slog.d(TAG, "!Cpu updating!");
        }

        if (mCpuFreqs == null) {
            mCpuFreqs = mCpuUidFreqTimeReader.readFreqs(mPowerProfile);
        }

        // Calculate the wakelocks we have to distribute amongst. The system is excluded as it is
        // usually holding the wakelock on behalf of an app.
        // And Only distribute cpu power to wakelocks if the screen is off and we're on battery.
        ArrayList<StopwatchTimer> partialTimersToConsider = null;
        if (onBatteryScreenOff) {
            partialTimersToConsider = new ArrayList<>();
            for (int i = mPartialTimers.size() - 1; i >= 0; --i) {
                final StopwatchTimer timer = mPartialTimers.get(i);
                // Since the collection and blaming of wakelocks can be scheduled to run after
                // some delay, the mPartialTimers list may have new entries. We can't blame
                // the newly added timer for past cpu time, so we only consider timers that
                // were present for one round of collection. Once a timer has gone through
                // a round of collection, its mInList field is set to true.
                if (timer.mInList && timer.mUid != null && timer.mUid.mUid != Process.SYSTEM_UID) {
                    partialTimersToConsider.add(timer);
                }
            }
        }
        markPartialTimersAsEligible();

        // When the battery is not on, we don't attribute the cpu times to any timers but we still
        // need to take the snapshots.
        if (!onBattery) {
            mCpuUidUserSysTimeReader.readDelta(false, null);
            mCpuUidFreqTimeReader.readDelta(false, null);
            mNumAllUidCpuTimeReads += 2;
            if (mConstants.TRACK_CPU_ACTIVE_CLUSTER_TIME) {
                mCpuUidActiveTimeReader.readDelta(false, null);
                mCpuUidClusterTimeReader.readDelta(false, null);
                mNumAllUidCpuTimeReads += 2;
            }
            for (int cluster = mKernelCpuSpeedReaders.length - 1; cluster >= 0; --cluster) {
                mKernelCpuSpeedReaders[cluster].readDelta();
            }
            mSystemServerCpuThreadReader.readDelta();
            return;
        }

        mUserInfoProvider.refreshUserIds();
        final SparseLongArray updatedUids = mCpuUidFreqTimeReader.perClusterTimesAvailable()
                ? null : new SparseLongArray();

        final CpuDeltaPowerAccumulator powerAccumulator;
        if (mGlobalMeasuredEnergyStats != null
                && mGlobalMeasuredEnergyStats.isStandardBucketSupported(
                MeasuredEnergyStats.POWER_BUCKET_CPU) && mCpuPowerCalculator != null) {
            if (measuredCpuClusterChargeUC == null) {
                Slog.wtf(TAG,
                        "POWER_BUCKET_CPU supported but no measured Cpu Cluster charge reported "
                                + "on updateCpuTimeLocked!");
                powerAccumulator = null;
            } else {
                // Cpu Measured Energy is supported, create an object to accumulate the estimated
                // charge consumption since the last cpu update
                final int numClusters = mPowerProfile.getNumCpuClusters();
                powerAccumulator = new CpuDeltaPowerAccumulator(mCpuPowerCalculator, numClusters);
            }
        } else {
            powerAccumulator = null;
        }

        readKernelUidCpuTimesLocked(partialTimersToConsider, updatedUids, onBattery);
        // updatedUids=null means /proc/uid_time_in_state provides snapshots of per-cluster cpu
        // freqs, so no need to approximate these values.
        if (updatedUids != null) {
            updateClusterSpeedTimes(updatedUids, onBattery, powerAccumulator);
        }
        readKernelUidCpuFreqTimesLocked(partialTimersToConsider, onBattery, onBatteryScreenOff,
                powerAccumulator);
        mNumAllUidCpuTimeReads += 2;
        if (mConstants.TRACK_CPU_ACTIVE_CLUSTER_TIME) {
            // Cpu Active times do not get any info ony how to attribute measured Cpu Cluster
            // charge, so not need to provide the powerAccumulator
            readKernelUidCpuActiveTimesLocked(onBattery);
            readKernelUidCpuClusterTimesLocked(onBattery, powerAccumulator);
            mNumAllUidCpuTimeReads += 2;
        }

        updateSystemServerThreadStats();

        if (powerAccumulator != null) {
            updateCpuMeasuredEnergyStatsLocked(measuredCpuClusterChargeUC, powerAccumulator);
        }
    }

    /**
     * Estimates the proportion of the System Server CPU activity (per cluster per speed)
     * spent on handling incoming binder calls.
     */
    @VisibleForTesting
    public void updateSystemServerThreadStats() {
        // There are some simplifying assumptions made in this algorithm
        // 1) We assume that if a thread handles incoming binder calls, all of its activity
        //    is spent doing that.  Most incoming calls are handled by threads allocated
        //    by the native layer in the binder thread pool, so this assumption is reasonable.
        // 2) We use the aggregate CPU time spent in different threads as a proxy for the CPU
        //    cost. In reality, in multi-core CPUs, the CPU cost may not be linearly
        //    affected by additional threads.

        SystemServerCpuThreadReader.SystemServiceCpuThreadTimes systemServiceCpuThreadTimes =
                    mSystemServerCpuThreadReader.readDelta();
        if (systemServiceCpuThreadTimes == null) {
            return;
        }

        if (mBinderThreadCpuTimesUs == null) {
            mBinderThreadCpuTimesUs = new LongSamplingCounterArray(mOnBatteryTimeBase);
        }
        mBinderThreadCpuTimesUs.addCountLocked(systemServiceCpuThreadTimes.binderThreadCpuTimesUs);

        if (DEBUG_BINDER_STATS) {
            Slog.d(TAG, "System server threads per CPU cluster (incoming binder threads)");
            long binderThreadTimeMs = 0;
            int cpuIndex = 0;
            final long[] binderThreadCpuTimesUs = mBinderThreadCpuTimesUs.getCountsLocked(
                    BatteryStats.STATS_SINCE_CHARGED);
            int index = 0;
            int numCpuClusters = mPowerProfile.getNumCpuClusters();
            for (int cluster = 0; cluster < numCpuClusters; cluster++) {
                StringBuilder sb = new StringBuilder();
                sb.append("cpu").append(cpuIndex).append(": [");
                int numSpeeds = mPowerProfile.getNumSpeedStepsInCpuCluster(cluster);
                for (int speed = 0; speed < numSpeeds; speed++) {
                    if (speed != 0) {
                        sb.append(", ");
                    }
                    long binderCountMs = binderThreadCpuTimesUs[index] / 1000;
                    sb.append(TextUtils.formatSimple("%10d", binderCountMs));

                    binderThreadTimeMs += binderCountMs;
                    index++;
                }
                cpuIndex += mPowerProfile.getNumCoresInCpuCluster(cluster);
                Slog.d(TAG, sb.toString());
            }
        }
    }

    /**
     * Mark the current partial timers as gone through a collection so that they will be
     * considered in the next cpu times distribution to wakelock holders.
     */
    @VisibleForTesting
    public void markPartialTimersAsEligible() {
        if (ArrayUtils.referenceEquals(mPartialTimers, mLastPartialTimers)) {
            // No difference, so each timer is now considered for the next collection.
            for (int i = mPartialTimers.size() - 1; i >= 0; --i) {
                mPartialTimers.get(i).mInList = true;
            }
        } else {
            // The lists are different, meaning we added (or removed a timer) since the last
            // collection.
            for (int i = mLastPartialTimers.size() - 1; i >= 0; --i) {
                mLastPartialTimers.get(i).mInList = false;
            }
            mLastPartialTimers.clear();

            // Mark the current timers as gone through a collection.
            final int numPartialTimers = mPartialTimers.size();
            for (int i = 0; i < numPartialTimers; ++i) {
                final StopwatchTimer timer = mPartialTimers.get(i);
                timer.mInList = true;
                mLastPartialTimers.add(timer);
            }
        }
    }

    /**
     * Take snapshot of cpu times (aggregated over all uids) at different frequencies and
     * calculate cpu times spent by each uid at different frequencies. Will also add estimated
     * power consumptions, if powerAccumulator data structure is provided.
     *
     * @param updatedUids  The uids for which times spent at different frequencies are calculated.
     * @param onBattery whether or not this is onBattery
     * @param powerAccumulator object to accumulate the estimated cluster charge consumption.
     */
    @VisibleForTesting
    public void updateClusterSpeedTimes(@NonNull SparseLongArray updatedUids, boolean onBattery,
            @Nullable CpuDeltaPowerAccumulator powerAccumulator) {
        long totalCpuClustersTimeMs = 0;
        // Read the time spent for each cluster at various cpu frequencies.
        final long[][] clusterSpeedTimesMs = new long[mKernelCpuSpeedReaders.length][];
        for (int cluster = 0; cluster < mKernelCpuSpeedReaders.length; cluster++) {
            clusterSpeedTimesMs[cluster] = mKernelCpuSpeedReaders[cluster].readDelta();
            if (clusterSpeedTimesMs[cluster] != null) {
                for (int speed = clusterSpeedTimesMs[cluster].length - 1; speed >= 0; --speed) {
                    totalCpuClustersTimeMs += clusterSpeedTimesMs[cluster][speed];
                }
            }
        }
        if (totalCpuClustersTimeMs != 0) {
            // We have cpu times per freq aggregated over all uids but we need the times per uid.
            // So, we distribute total time spent by an uid to different cpu freqs based on the
            // amount of time cpu was running at that freq.
            final int updatedUidsCount = updatedUids.size();
            final long elapsedRealtimeMs = mClock.elapsedRealtime();
            final long uptimeMs = mClock.uptimeMillis();
            for (int i = 0; i < updatedUidsCount; ++i) {
                final Uid u = getUidStatsLocked(updatedUids.keyAt(i), elapsedRealtimeMs, uptimeMs);
                final long appCpuTimeUs = updatedUids.valueAt(i);
                // Add the cpu speeds to this UID.
                final int numClusters = mPowerProfile.getNumCpuClusters();
                if (u.mCpuClusterSpeedTimesUs == null ||
                        u.mCpuClusterSpeedTimesUs.length != numClusters) {
                    u.mCpuClusterSpeedTimesUs = new LongSamplingCounter[numClusters][];
                }

                for (int cluster = 0; cluster < clusterSpeedTimesMs.length; cluster++) {
                    final int speedsInCluster = clusterSpeedTimesMs[cluster].length;
                    if (u.mCpuClusterSpeedTimesUs[cluster] == null || speedsInCluster !=
                            u.mCpuClusterSpeedTimesUs[cluster].length) {
                        u.mCpuClusterSpeedTimesUs[cluster]
                                = new LongSamplingCounter[speedsInCluster];
                    }

                    final LongSamplingCounter[] cpuSpeeds = u.mCpuClusterSpeedTimesUs[cluster];
                    for (int speed = 0; speed < speedsInCluster; speed++) {
                        if (cpuSpeeds[speed] == null) {
                            cpuSpeeds[speed] = new LongSamplingCounter(mOnBatteryTimeBase);
                        }
                        final long deltaSpeedCount = appCpuTimeUs
                                * clusterSpeedTimesMs[cluster][speed]
                                / totalCpuClustersTimeMs;
                        cpuSpeeds[speed].addCountLocked(deltaSpeedCount, onBattery);

                        if (powerAccumulator != null) {
                            powerAccumulator.addCpuClusterSpeedDurationsMs(u, cluster,
                                    speed, deltaSpeedCount);
                        }
                    }
                }
            }
        }
    }

    /**
     * Take a snapshot of the cpu times spent by each uid and update the corresponding counters.
     * If {@param partialTimers} is not null and empty, then we assign a portion of cpu times to
     * wakelock holders.
     *
     * @param partialTimers The wakelock holders among which the cpu times will be distributed.
     * @param updatedUids If not null, then the uids found in the snapshot will be added to this.
     */
    @VisibleForTesting
    public void readKernelUidCpuTimesLocked(@Nullable ArrayList<StopwatchTimer> partialTimers,
            @Nullable SparseLongArray updatedUids, boolean onBattery) {
        mTempTotalCpuUserTimeUs = mTempTotalCpuSystemTimeUs = 0;
        final int numWakelocks = partialTimers == null ? 0 : partialTimers.size();
        final long startTimeMs = mClock.uptimeMillis();
        final long elapsedRealtimeMs = mClock.elapsedRealtime();

        mCpuUidUserSysTimeReader.readDelta(false, (uid, timesUs) -> {
            long userTimeUs = timesUs[0], systemTimeUs = timesUs[1];

            uid = mapUid(uid);
            if (Process.isIsolated(uid)) {
                // This could happen if the isolated uid mapping was removed before that process
                // was actually killed.
                if (DEBUG) Slog.d(TAG, "Got readings for an isolated uid: " + uid);
                return;
            }
            if (!mUserInfoProvider.exists(UserHandle.getUserId(uid))) {
                if (DEBUG) Slog.d(TAG, "Got readings for an invalid user's uid " + uid);
                return;
            }
            final Uid u = getUidStatsLocked(uid, elapsedRealtimeMs, startTimeMs);

            // Accumulate the total system and user time.
            mTempTotalCpuUserTimeUs += userTimeUs;
            mTempTotalCpuSystemTimeUs += systemTimeUs;

            StringBuilder sb = null;
            if (DEBUG_ENERGY_CPU) {
                sb = new StringBuilder();
                sb.append("  got time for uid=").append(u.mUid).append(": u=");
                TimeUtils.formatDuration(userTimeUs / 1000, sb);
                sb.append(" s=");
                TimeUtils.formatDuration(systemTimeUs / 1000, sb);
                sb.append("\n");
            }

            if (numWakelocks > 0) {
                // We have wakelocks being held, so only give a portion of the
                // time to the process. The rest will be distributed among wakelock
                // holders.
                userTimeUs = (userTimeUs * WAKE_LOCK_WEIGHT) / 100;
                systemTimeUs = (systemTimeUs * WAKE_LOCK_WEIGHT) / 100;
            }

            if (sb != null) {
                sb.append("  adding to uid=").append(u.mUid).append(": u=");
                TimeUtils.formatDuration(userTimeUs / 1000, sb);
                sb.append(" s=");
                TimeUtils.formatDuration(systemTimeUs / 1000, sb);
                Slog.d(TAG, sb.toString());
            }

            u.mUserCpuTime.addCountLocked(userTimeUs, onBattery);
            u.mSystemCpuTime.addCountLocked(systemTimeUs, onBattery);
            if (updatedUids != null) {
                updatedUids.put(u.getUid(), userTimeUs + systemTimeUs);
            }
        });

        final long elapsedTimeMs = mClock.uptimeMillis() - startTimeMs;
        if (DEBUG_ENERGY_CPU || elapsedTimeMs >= 100) {
            Slog.d(TAG, "Reading cpu stats took " + elapsedTimeMs + "ms");
        }

        if (numWakelocks > 0) {
            // Distribute a portion of the total cpu time to wakelock holders.
            mTempTotalCpuUserTimeUs = (mTempTotalCpuUserTimeUs * (100 - WAKE_LOCK_WEIGHT)) / 100;
            mTempTotalCpuSystemTimeUs =
                    (mTempTotalCpuSystemTimeUs * (100 - WAKE_LOCK_WEIGHT)) / 100;

            for (int i = 0; i < numWakelocks; ++i) {
                final StopwatchTimer timer = partialTimers.get(i);
                final int userTimeUs = (int) (mTempTotalCpuUserTimeUs / (numWakelocks - i));
                final int systemTimeUs = (int) (mTempTotalCpuSystemTimeUs / (numWakelocks - i));

                if (DEBUG_ENERGY_CPU) {
                    final StringBuilder sb = new StringBuilder();
                    sb.append("  Distributing wakelock uid=").append(timer.mUid.mUid)
                            .append(": u=");
                    TimeUtils.formatDuration(userTimeUs / 1000, sb);
                    sb.append(" s=");
                    TimeUtils.formatDuration(systemTimeUs / 1000, sb);
                    Slog.d(TAG, sb.toString());
                }

                timer.mUid.mUserCpuTime.addCountLocked(userTimeUs, onBattery);
                timer.mUid.mSystemCpuTime.addCountLocked(systemTimeUs, onBattery);
                if (updatedUids != null) {
                    final int uid = timer.mUid.getUid();
                    updatedUids.put(uid, updatedUids.get(uid, 0) + userTimeUs + systemTimeUs);
                }

                final Uid.Proc proc = timer.mUid.getProcessStatsLocked("*wakelock*");
                proc.addCpuTimeLocked(userTimeUs / 1000, systemTimeUs / 1000, onBattery);

                mTempTotalCpuUserTimeUs -= userTimeUs;
                mTempTotalCpuSystemTimeUs -= systemTimeUs;
            }
        }
    }

    /**
     * Take a snapshot of the cpu times spent by each uid in each freq and update the
     * corresponding counters.  Will also add estimated power consumptions, if powerAccumulator
     * data structure is provided.
     *
     * @param partialTimers The wakelock holders among which the cpu freq times will be distributed.
     * @param onBattery whether or not this is onBattery
     * @param onBatteryScreenOff whether or not this is onBattery with the screen off.
     * @param powerAccumulator object to accumulate the estimated cluster charge consumption.
     */
    @VisibleForTesting
    public void readKernelUidCpuFreqTimesLocked(@Nullable ArrayList<StopwatchTimer> partialTimers,
            boolean onBattery, boolean onBatteryScreenOff,
            @Nullable CpuDeltaPowerAccumulator powerAccumulator) {
        final boolean perClusterTimesAvailable =
                mCpuUidFreqTimeReader.perClusterTimesAvailable();
        final int numWakelocks = partialTimers == null ? 0 : partialTimers.size();
        final int numClusters = mPowerProfile.getNumCpuClusters();
        mWakeLockAllocationsUs = null;
        final long startTimeMs = mClock.uptimeMillis();
        final long elapsedRealtimeMs = mClock.elapsedRealtime();
        // If power is being accumulated for attribution, data needs to be read immediately.
        final boolean forceRead = powerAccumulator != null;
        mCpuUidFreqTimeReader.readDelta(forceRead, (uid, cpuFreqTimeMs) -> {
            uid = mapUid(uid);
            if (Process.isIsolated(uid)) {
                if (DEBUG) Slog.d(TAG, "Got freq readings for an isolated uid: " + uid);
                return;
            }
            if (!mUserInfoProvider.exists(UserHandle.getUserId(uid))) {
                if (DEBUG) Slog.d(TAG, "Got freq readings for an invalid user's uid " + uid);
                return;
            }
            final Uid u = getUidStatsLocked(uid, elapsedRealtimeMs, startTimeMs);
            if (u.mCpuFreqTimeMs == null || u.mCpuFreqTimeMs.getSize() != cpuFreqTimeMs.length) {
                detachIfNotNull(u.mCpuFreqTimeMs);
                u.mCpuFreqTimeMs = new LongSamplingCounterArray(mOnBatteryTimeBase);
            }
            u.mCpuFreqTimeMs.addCountLocked(cpuFreqTimeMs, onBattery);
            if (u.mScreenOffCpuFreqTimeMs == null ||
                    u.mScreenOffCpuFreqTimeMs.getSize() != cpuFreqTimeMs.length) {
                detachIfNotNull(u.mScreenOffCpuFreqTimeMs);
                u.mScreenOffCpuFreqTimeMs = new LongSamplingCounterArray(
                        mOnBatteryScreenOffTimeBase);
            }
            u.mScreenOffCpuFreqTimeMs.addCountLocked(cpuFreqTimeMs, onBatteryScreenOff);

            if (perClusterTimesAvailable) {
                if (u.mCpuClusterSpeedTimesUs == null ||
                        u.mCpuClusterSpeedTimesUs.length != numClusters) {
                    detachIfNotNull(u.mCpuClusterSpeedTimesUs);
                    u.mCpuClusterSpeedTimesUs = new LongSamplingCounter[numClusters][];
                }
                if (numWakelocks > 0 && mWakeLockAllocationsUs == null) {
                    mWakeLockAllocationsUs = new long[numClusters][];
                }

                int freqIndex = 0;
                for (int cluster = 0; cluster < numClusters; ++cluster) {
                    final int speedsInCluster = mPowerProfile.getNumSpeedStepsInCpuCluster(cluster);
                    if (u.mCpuClusterSpeedTimesUs[cluster] == null ||
                            u.mCpuClusterSpeedTimesUs[cluster].length != speedsInCluster) {
                        detachIfNotNull(u.mCpuClusterSpeedTimesUs[cluster]);
                        u.mCpuClusterSpeedTimesUs[cluster]
                                = new LongSamplingCounter[speedsInCluster];
                    }
                    if (numWakelocks > 0 && mWakeLockAllocationsUs[cluster] == null) {
                        mWakeLockAllocationsUs[cluster] = new long[speedsInCluster];
                    }
                    final LongSamplingCounter[] cpuTimesUs = u.mCpuClusterSpeedTimesUs[cluster];
                    for (int speed = 0; speed < speedsInCluster; ++speed) {
                        if (cpuTimesUs[speed] == null) {
                            cpuTimesUs[speed] = new LongSamplingCounter(mOnBatteryTimeBase);
                        }
                        final long appAllocationUs;
                        if (mWakeLockAllocationsUs != null) {
                            appAllocationUs =
                                    (cpuFreqTimeMs[freqIndex] * 1000 * WAKE_LOCK_WEIGHT) / 100;
                            mWakeLockAllocationsUs[cluster][speed] +=
                                    (cpuFreqTimeMs[freqIndex] * 1000 - appAllocationUs);
                        } else {
                            appAllocationUs = cpuFreqTimeMs[freqIndex] * 1000;
                        }
                        cpuTimesUs[speed].addCountLocked(appAllocationUs, onBattery);

                        if (powerAccumulator != null) {
                            powerAccumulator.addCpuClusterSpeedDurationsMs(u, cluster,
                                    speed, appAllocationUs / 1000);
                        }
                        freqIndex++;
                    }
                }
            }
        });

        final long elapsedTimeMs = mClock.uptimeMillis() - startTimeMs;
        if (DEBUG_ENERGY_CPU || elapsedTimeMs >= 100) {
            Slog.d(TAG, "Reading cpu freq times took " + elapsedTimeMs + "ms");
        }

        if (mWakeLockAllocationsUs != null) {
            for (int i = 0; i < numWakelocks; ++i) {
                final Uid u = partialTimers.get(i).mUid;
                if (u.mCpuClusterSpeedTimesUs == null ||
                        u.mCpuClusterSpeedTimesUs.length != numClusters) {
                    detachIfNotNull(u.mCpuClusterSpeedTimesUs);
                    u.mCpuClusterSpeedTimesUs = new LongSamplingCounter[numClusters][];
                }

                for (int cluster = 0; cluster < numClusters; ++cluster) {
                    final int speedsInCluster = mPowerProfile.getNumSpeedStepsInCpuCluster(cluster);
                    if (u.mCpuClusterSpeedTimesUs[cluster] == null ||
                            u.mCpuClusterSpeedTimesUs[cluster].length != speedsInCluster) {
                        detachIfNotNull(u.mCpuClusterSpeedTimesUs[cluster]);
                        u.mCpuClusterSpeedTimesUs[cluster]
                                = new LongSamplingCounter[speedsInCluster];
                    }
                    final LongSamplingCounter[] cpuTimeUs = u.mCpuClusterSpeedTimesUs[cluster];
                    for (int speed = 0; speed < speedsInCluster; ++speed) {
                        if (cpuTimeUs[speed] == null) {
                            cpuTimeUs[speed] = new LongSamplingCounter(mOnBatteryTimeBase);
                        }
                        final long allocationUs =
                                mWakeLockAllocationsUs[cluster][speed] / (numWakelocks - i);
                        cpuTimeUs[speed].addCountLocked(allocationUs, onBattery);
                        mWakeLockAllocationsUs[cluster][speed] -= allocationUs;

                        if (powerAccumulator != null) {
                            powerAccumulator.addCpuClusterSpeedDurationsMs(u, cluster,
                                    speed, allocationUs / 1000);
                        }
                    }
                }
            }
        }
    }

    /**
     * Take a snapshot of the cpu active times spent by each uid and update the corresponding
     * counters.
     */
    @VisibleForTesting
    public void readKernelUidCpuActiveTimesLocked(boolean onBattery) {
        final long startTimeMs = mClock.uptimeMillis();
        final long elapsedRealtimeMs = mClock.elapsedRealtime();
        mCpuUidActiveTimeReader.readAbsolute((uid, cpuActiveTimesMs) -> {
            final int parentUid = mapUid(uid);
            if (Process.isIsolated(parentUid)) {
                if (DEBUG) Slog.w(TAG, "Got active times for an isolated uid: " + uid);
                return;
            }
            if (!mUserInfoProvider.exists(UserHandle.getUserId(uid))) {
                if (DEBUG) Slog.w(TAG, "Got active times for an invalid user's uid " + uid);
                return;
            }
            final Uid u = getUidStatsLocked(parentUid, elapsedRealtimeMs, startTimeMs);
            if (parentUid == uid) {
                u.getCpuActiveTimeCounter().update(cpuActiveTimesMs, elapsedRealtimeMs);
            } else {
                final SparseArray<Uid.ChildUid> childUids = u.mChildUids;
                if (childUids == null) {
                    return;
                }

                Uid.ChildUid childUid = childUids.get(uid);
                if (childUid != null) {
                    final long delta =
                            childUid.cpuActiveCounter.update(cpuActiveTimesMs, elapsedRealtimeMs);
                    u.getCpuActiveTimeCounter().increment(delta, elapsedRealtimeMs);
                }
            }
        });

        final long elapsedTimeMs = mClock.uptimeMillis() - startTimeMs;
        if (DEBUG_ENERGY_CPU || elapsedTimeMs >= 100) {
            Slog.d(TAG, "Reading cpu active times took " + elapsedTimeMs + "ms");
        }
    }

    /**
     * Take a snapshot of the cpu cluster times spent by each uid and update the corresponding
     * counters. Will also add estimated power consumptions, if powerAccumulator data structure
     * is provided.
     *
     * @param onBattery whether or not this is onBattery
     * @param powerAccumulator object to accumulate the estimated cluster charge consumption.
     */
    @VisibleForTesting
    public void readKernelUidCpuClusterTimesLocked(boolean onBattery,
            @Nullable CpuDeltaPowerAccumulator powerAccumulator) {
        final long startTimeMs = mClock.uptimeMillis();
        final long elapsedRealtimeMs = mClock.elapsedRealtime();
        // If power is being accumulated for attribution, data needs to be read immediately.
        final boolean forceRead = powerAccumulator != null;
        mCpuUidClusterTimeReader.readDelta(forceRead, (uid, cpuClusterTimesMs) -> {
            uid = mapUid(uid);
            if (Process.isIsolated(uid)) {
                if (DEBUG) Slog.w(TAG, "Got cluster times for an isolated uid: " + uid);
                return;
            }
            if (!mUserInfoProvider.exists(UserHandle.getUserId(uid))) {
                if (DEBUG) Slog.w(TAG, "Got cluster times for an invalid user's uid " + uid);
                return;
            }
            final Uid u = getUidStatsLocked(uid, elapsedRealtimeMs, startTimeMs);
            u.mCpuClusterTimesMs.addCountLocked(cpuClusterTimesMs, onBattery);

            if (powerAccumulator != null) {
                powerAccumulator.addCpuClusterDurationsMs(u, cpuClusterTimesMs);
            }
        });

        final long elapsedTimeMs = mClock.uptimeMillis() - startTimeMs;
        if (DEBUG_ENERGY_CPU || elapsedTimeMs >= 100) {
            Slog.d(TAG, "Reading cpu cluster times took " + elapsedTimeMs + "ms");
        }
    }

    boolean setChargingLocked(boolean charging) {
        // if the device is no longer charging, remove the callback
        // if the device is now charging, it means that this is either called
        // 1. directly when level >= 90
        // 2. or from within the runnable that we deferred
        // For 1. if we have an existing callback, remove it, since we will immediately send a
        // ACTION_CHARGING
        // For 2. we remove existing callback so we don't send multiple ACTION_CHARGING
        mHandler.removeCallbacks(mDeferSetCharging);
        if (mCharging != charging) {
            mCharging = charging;
            if (charging) {
                mHistoryCur.states2 |= HistoryItem.STATE2_CHARGING_FLAG;
            } else {
                mHistoryCur.states2 &= ~HistoryItem.STATE2_CHARGING_FLAG;
            }
            mHandler.sendEmptyMessage(MSG_REPORT_CHARGING);
            return true;
        }
        return false;
    }

    /**
     * Notifies BatteryStatsImpl that the system server is ready.
     */
    public void onSystemReady() {
        mSystemReady = true;
    }

    @GuardedBy("this")
    protected void setOnBatteryLocked(final long mSecRealtime, final long mSecUptime,
            final boolean onBattery, final int oldStatus, final int level, final int chargeUah) {
        boolean doWrite = false;
        Message m = mHandler.obtainMessage(MSG_REPORT_POWER_CHANGE);
        m.arg1 = onBattery ? 1 : 0;
        mHandler.sendMessage(m);

        final long uptimeUs = mSecUptime * 1000;
        final long realtimeUs = mSecRealtime * 1000;
        final int screenState = mScreenState;
        if (onBattery) {
            // We will reset our status if we are unplugging after the
            // battery was last full, or the level is at 100, or
            // we have gone through a significant charge (from a very low
            // level to a now very high level).
            // Also, we will reset the stats if battery got partially charged
            // and discharged repeatedly without ever reaching the full charge.
            // This reset is done in order to prevent stats sessions from going on forever.
            // Exceedingly long battery sessions would lead to an overflow of
            // data structures such as mWakeupReasonStats.
            boolean reset = false;
            if (!mNoAutoReset && mSystemReady
                    && (oldStatus == BatteryManager.BATTERY_STATUS_FULL
                    || level >= 90
                    || (mDischargeCurrentLevel < 20 && level >= 80)
                    || getHighDischargeAmountSinceCharge() >= 200)) {
                Slog.i(TAG, "Resetting battery stats: level=" + level + " status=" + oldStatus
                        + " dischargeLevel=" + mDischargeCurrentLevel
                        + " lowAmount=" + getLowDischargeAmountSinceCharge()
                        + " highAmount=" + getHighDischargeAmountSinceCharge());
                // Before we write, collect a snapshot of the final aggregated
                // stats to be reported in the next checkin.  Only do this if we have
                // a sufficient amount of data to make it interesting.
                if (getLowDischargeAmountSinceCharge() >= 20) {
                    final long startTimeMs = SystemClock.uptimeMillis();
                    final Parcel parcel = Parcel.obtain();
                    writeSummaryToParcel(parcel, true);
                    final long initialTimeMs = SystemClock.uptimeMillis() - startTimeMs;
                    BackgroundThread.getHandler().post(new Runnable() {
                        @Override public void run() {
                            synchronized (mCheckinFile) {
                                final long startTimeMs2 = SystemClock.uptimeMillis();
                                FileOutputStream stream = null;
                                try {
                                    stream = mCheckinFile.startWrite();
                                    stream.write(parcel.marshall());
                                    stream.flush();
                                    mCheckinFile.finishWrite(stream);
                                    com.android.internal.logging.EventLogTags.writeCommitSysConfigFile(
                                            "batterystats-checkin", initialTimeMs
                                            + SystemClock.uptimeMillis() - startTimeMs2);
                                } catch (IOException e) {
                                    Slog.w("BatteryStats",
                                            "Error writing checkin battery statistics", e);
                                    mCheckinFile.failWrite(stream);
                                } finally {
                                    parcel.recycle();
                                }
                            }
                        }
                    });
                }
                doWrite = true;
                resetAllStatsLocked(mSecUptime, mSecRealtime, RESET_REASON_FULL_CHARGE);
                if (chargeUah > 0 && level > 0) {
                    // Only use the reported coulomb charge value if it is supported and reported.
                    mEstimatedBatteryCapacityMah = (int) ((chargeUah / 1000) / (level / 100.0));
                }
                mDischargeStartLevel = level;
                reset = true;
                mDischargeStepTracker.init();
            }
            if (mCharging) {
                setChargingLocked(false);
            }
            mLastChargingStateLevel = level;
            mOnBattery = mOnBatteryInternal = true;
            mLastDischargeStepLevel = level;
            mMinDischargeStepLevel = level;
            mDischargeStepTracker.clearTime();
            mDailyDischargeStepTracker.clearTime();
            mInitStepMode = mCurStepMode;
            mModStepMode = 0;
            pullPendingStateUpdatesLocked();
            mHistoryCur.batteryLevel = (byte)level;
            mHistoryCur.states &= ~HistoryItem.STATE_BATTERY_PLUGGED_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Battery unplugged to: "
                    + Integer.toHexString(mHistoryCur.states));
            if (reset) {
                mRecordingHistory = true;
                startRecordingHistory(mSecRealtime, mSecUptime, reset);
            }
            addHistoryRecordLocked(mSecRealtime, mSecUptime);
            mDischargeCurrentLevel = mDischargeUnplugLevel = level;
            if (Display.isOnState(screenState)) {
                mDischargeScreenOnUnplugLevel = level;
                mDischargeScreenDozeUnplugLevel = 0;
                mDischargeScreenOffUnplugLevel = 0;
            } else if (Display.isDozeState(screenState)) {
                mDischargeScreenOnUnplugLevel = 0;
                mDischargeScreenDozeUnplugLevel = level;
                mDischargeScreenOffUnplugLevel = 0;
            } else {
                mDischargeScreenOnUnplugLevel = 0;
                mDischargeScreenDozeUnplugLevel = 0;
                mDischargeScreenOffUnplugLevel = level;
            }
            mDischargeAmountScreenOn = 0;
            mDischargeAmountScreenDoze = 0;
            mDischargeAmountScreenOff = 0;
            updateTimeBasesLocked(true, screenState, uptimeUs, realtimeUs);
        } else {
            mLastChargingStateLevel = level;
            mOnBattery = mOnBatteryInternal = false;
            pullPendingStateUpdatesLocked();
            mHistoryCur.batteryLevel = (byte)level;
            mHistoryCur.states |= HistoryItem.STATE_BATTERY_PLUGGED_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Battery plugged to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(mSecRealtime, mSecUptime);
            mDischargeCurrentLevel = mDischargePlugLevel = level;
            if (level < mDischargeUnplugLevel) {
                mLowDischargeAmountSinceCharge += mDischargeUnplugLevel-level-1;
                mHighDischargeAmountSinceCharge += mDischargeUnplugLevel-level;
            }
            updateDischargeScreenLevelsLocked(screenState, screenState);
            updateTimeBasesLocked(false, screenState, uptimeUs, realtimeUs);
            mChargeStepTracker.init();
            mLastChargeStepLevel = level;
            mMaxChargeStepLevel = level;
            mInitStepMode = mCurStepMode;
            mModStepMode = 0;
        }
        if (doWrite || (mLastWriteTimeMs + (60 * 1000)) < mSecRealtime) {
            if (mStatsFile != null && mBatteryStatsHistory.getActiveFile() != null) {
                writeAsyncLocked();
            }
        }
    }

    @GuardedBy("this")
    private void startRecordingHistory(final long elapsedRealtimeMs, final long uptimeMs,
            boolean reset) {
        mRecordingHistory = true;
        mHistoryCur.currentTime = mClock.currentTimeMillis();
        addHistoryBufferLocked(elapsedRealtimeMs,
                reset ? HistoryItem.CMD_RESET : HistoryItem.CMD_CURRENT_TIME,
                mHistoryCur);
        mHistoryCur.currentTime = 0;
        if (reset) {
            initActiveHistoryEventsLocked(elapsedRealtimeMs, uptimeMs);
        }
    }

    @GuardedBy("this")
    private void recordCurrentTimeChangeLocked(final long currentTimeMs,
            final long elapsedRealtimeMs, final long uptimeMs) {
        if (mRecordingHistory) {
            mHistoryCur.currentTime = currentTimeMs;
            addHistoryBufferLocked(elapsedRealtimeMs, HistoryItem.CMD_CURRENT_TIME, mHistoryCur);
            mHistoryCur.currentTime = 0;
        }
    }

    @GuardedBy("this")
    private void recordShutdownLocked(final long currentTimeMs, final long elapsedRealtimeMs) {
        if (mRecordingHistory) {
            mHistoryCur.currentTime = currentTimeMs;
            addHistoryBufferLocked(elapsedRealtimeMs, HistoryItem.CMD_SHUTDOWN, mHistoryCur);
            mHistoryCur.currentTime = 0;
        }
    }

    private void scheduleSyncExternalStatsLocked(String reason, int updateFlags) {
        if (mExternalSync != null) {
            mExternalSync.scheduleSync(reason, updateFlags);
        }
    }

    // This should probably be exposed in the API, though it's not critical
    public static final int BATTERY_PLUGGED_NONE = OsProtoEnums.BATTERY_PLUGGED_NONE; // = 0

    @GuardedBy("this")
    public void setBatteryStateLocked(final int status, final int health, final int plugType,
            final int level, /* not final */ int temp, final int voltageMv, final int chargeUah,
            final int chargeFullUah, final long chargeTimeToFullSeconds) {
        setBatteryStateLocked(status, health, plugType, level, temp, voltageMv, chargeUah,
                chargeFullUah, chargeTimeToFullSeconds,
                mClock.elapsedRealtime(), mClock.uptimeMillis(), mClock.currentTimeMillis());
    }

    @GuardedBy("this")
    public void setBatteryStateLocked(final int status, final int health, final int plugType,
            final int level, /* not final */ int temp, final int voltageMv, final int chargeUah,
            final int chargeFullUah, final long chargeTimeToFullSeconds,
            final long elapsedRealtimeMs, final long uptimeMs, final long currentTimeMs) {
        // Temperature is encoded without the signed bit, so clamp any negative temperatures to 0.
        temp = Math.max(0, temp);

        reportChangesToStatsLog(mHaveBatteryLevel ? mHistoryCur : null,
                status, plugType, level);

        final boolean onBattery = isOnBattery(plugType, status);
        if (!mHaveBatteryLevel) {
            mHaveBatteryLevel = true;
            // We start out assuming that the device is plugged in (not
            // on battery).  If our first report is now that we are indeed
            // plugged in, then twiddle our state to correctly reflect that
            // since we won't be going through the full setOnBattery().
            if (onBattery == mOnBattery) {
                if (onBattery) {
                    mHistoryCur.states &= ~HistoryItem.STATE_BATTERY_PLUGGED_FLAG;
                } else {
                    mHistoryCur.states |= HistoryItem.STATE_BATTERY_PLUGGED_FLAG;
                }
            }
            // Always start out assuming charging, that will be updated later.
            mHistoryCur.states2 |= HistoryItem.STATE2_CHARGING_FLAG;
            mHistoryCur.batteryStatus = (byte)status;
            mHistoryCur.batteryLevel = (byte)level;
            mHistoryCur.batteryChargeUah = chargeUah;
            mMaxChargeStepLevel = mMinDischargeStepLevel =
                    mLastChargeStepLevel = mLastDischargeStepLevel = level;
            mLastChargingStateLevel = level;
        } else if (mCurrentBatteryLevel != level || mOnBattery != onBattery) {
            recordDailyStatsIfNeededLocked(level >= 100 && onBattery, currentTimeMs);
        }
        int oldStatus = mHistoryCur.batteryStatus;
        if (onBattery) {
            mDischargeCurrentLevel = level;
            if (!mRecordingHistory) {
                mRecordingHistory = true;
                startRecordingHistory(elapsedRealtimeMs, uptimeMs, true);
            }
        } else if (level < 96 &&
                status != BatteryManager.BATTERY_STATUS_UNKNOWN) {
            if (!mRecordingHistory) {
                mRecordingHistory = true;
                startRecordingHistory(elapsedRealtimeMs, uptimeMs, true);
            }
        }
        mBatteryVoltageMv = voltageMv;
        mCurrentBatteryLevel = level;
        if (mDischargePlugLevel < 0) {
            mDischargePlugLevel = level;
        }

        if (onBattery != mOnBattery) {
            mHistoryCur.batteryLevel = (byte)level;
            mHistoryCur.batteryStatus = (byte)status;
            mHistoryCur.batteryHealth = (byte)health;
            mHistoryCur.batteryPlugType = (byte)plugType;
            mHistoryCur.batteryTemperature = (short)temp;
            mHistoryCur.batteryVoltage = (char) voltageMv;
            if (chargeUah < mHistoryCur.batteryChargeUah) {
                // Only record discharges
                final long chargeDiff = mHistoryCur.batteryChargeUah - chargeUah;
                mDischargeCounter.addCountLocked(chargeDiff);
                mDischargeScreenOffCounter.addCountLocked(chargeDiff);
                if (Display.isDozeState(mScreenState)) {
                    mDischargeScreenDozeCounter.addCountLocked(chargeDiff);
                }
                if (mDeviceIdleMode == DEVICE_IDLE_MODE_LIGHT) {
                    mDischargeLightDozeCounter.addCountLocked(chargeDiff);
                } else if (mDeviceIdleMode == DEVICE_IDLE_MODE_DEEP) {
                    mDischargeDeepDozeCounter.addCountLocked(chargeDiff);
                }
            }
            mHistoryCur.batteryChargeUah = chargeUah;
            setOnBatteryLocked(elapsedRealtimeMs, uptimeMs, onBattery, oldStatus, level, chargeUah);
        } else {
            boolean changed = false;
            if (mHistoryCur.batteryLevel != level) {
                mHistoryCur.batteryLevel = (byte)level;
                changed = true;

                // TODO(adamlesinski): Schedule the creation of a HistoryStepDetails record
                // which will pull external stats.
                mExternalSync.scheduleSyncDueToBatteryLevelChange(
                        mConstants.BATTERY_LEVEL_COLLECTION_DELAY_MS);
            }
            if (mHistoryCur.batteryStatus != status) {
                mHistoryCur.batteryStatus = (byte)status;
                changed = true;
            }
            if (mHistoryCur.batteryHealth != health) {
                mHistoryCur.batteryHealth = (byte)health;
                changed = true;
            }
            if (mHistoryCur.batteryPlugType != plugType) {
                mHistoryCur.batteryPlugType = (byte)plugType;
                changed = true;
            }
            if (temp >= (mHistoryCur.batteryTemperature+10)
                    || temp <= (mHistoryCur.batteryTemperature-10)) {
                mHistoryCur.batteryTemperature = (short)temp;
                changed = true;
            }
            if (voltageMv > (mHistoryCur.batteryVoltage + 20)
                    || voltageMv < (mHistoryCur.batteryVoltage - 20)) {
                mHistoryCur.batteryVoltage = (char) voltageMv;
                changed = true;
            }
            if (chargeUah >= (mHistoryCur.batteryChargeUah + 10)
                    || chargeUah <= (mHistoryCur.batteryChargeUah - 10)) {
                if (chargeUah < mHistoryCur.batteryChargeUah) {
                    // Only record discharges
                    final long chargeDiff = mHistoryCur.batteryChargeUah - chargeUah;
                    mDischargeCounter.addCountLocked(chargeDiff);
                    mDischargeScreenOffCounter.addCountLocked(chargeDiff);
                    if (Display.isDozeState(mScreenState)) {
                        mDischargeScreenDozeCounter.addCountLocked(chargeDiff);
                    }
                    if (mDeviceIdleMode == DEVICE_IDLE_MODE_LIGHT) {
                        mDischargeLightDozeCounter.addCountLocked(chargeDiff);
                    } else if (mDeviceIdleMode == DEVICE_IDLE_MODE_DEEP) {
                        mDischargeDeepDozeCounter.addCountLocked(chargeDiff);
                    }
                }
                mHistoryCur.batteryChargeUah = chargeUah;
                changed = true;
            }
            long modeBits = (((long)mInitStepMode) << STEP_LEVEL_INITIAL_MODE_SHIFT)
                    | (((long)mModStepMode) << STEP_LEVEL_MODIFIED_MODE_SHIFT)
                    | (((long)(level&0xff)) << STEP_LEVEL_LEVEL_SHIFT);
            if (onBattery) {
                changed |= setChargingLocked(false);
                if (mLastDischargeStepLevel != level && mMinDischargeStepLevel > level) {
                    mDischargeStepTracker.addLevelSteps(mLastDischargeStepLevel - level,
                            modeBits, elapsedRealtimeMs);
                    mDailyDischargeStepTracker.addLevelSteps(mLastDischargeStepLevel - level,
                            modeBits, elapsedRealtimeMs);
                    mLastDischargeStepLevel = level;
                    mMinDischargeStepLevel = level;
                    mInitStepMode = mCurStepMode;
                    mModStepMode = 0;
                }
            } else {
                if (level >= 90) {
                    // If the battery level is at least 90%, always consider the device to be
                    // charging even if it happens to go down a level.
                    changed |= setChargingLocked(true);
                } else if (!mCharging) {
                    if (mLastChargeStepLevel < level) {
                        // We have not reported that we are charging, but the level has gone up,
                        // but we would like to not have tons of activity from charging-constraint
                        // jobs, so instead of reporting ACTION_CHARGING immediately, we defer it.
                        if (!mHandler.hasCallbacks(mDeferSetCharging)) {
                            mHandler.postDelayed(
                                    mDeferSetCharging,
                                    mConstants.BATTERY_CHARGED_DELAY_MS);
                        }
                    } else if (mLastChargeStepLevel > level) {
                        // if we had deferred a runnable due to charge level increasing, but then
                        // later the charge level drops (could be due to thermal issues), we don't
                        // want to trigger the deferred runnable, so remove it here
                        mHandler.removeCallbacks(mDeferSetCharging);
                    }
                } else {
                    if (mLastChargeStepLevel > level) {
                        // We had reported that the device was charging, but here we are with
                        // power connected and the level going down.  Looks like the current
                        // power supplied isn't enough, so consider the device to now be
                        // discharging.
                        changed |= setChargingLocked(false);
                    }
                }
                if (mLastChargeStepLevel != level && mMaxChargeStepLevel < level) {
                    mChargeStepTracker.addLevelSteps(level - mLastChargeStepLevel,
                            modeBits, elapsedRealtimeMs);
                    mDailyChargeStepTracker.addLevelSteps(level - mLastChargeStepLevel,
                            modeBits, elapsedRealtimeMs);
                    mMaxChargeStepLevel = level;
                    mInitStepMode = mCurStepMode;
                    mModStepMode = 0;
                }
                mLastChargeStepLevel = level;
            }
            if (changed) {
                addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
            }
        }
        if (!onBattery &&
                (status == BatteryManager.BATTERY_STATUS_FULL ||
                        status == BatteryManager.BATTERY_STATUS_UNKNOWN)) {
            // We don't record history while we are plugged in and fully charged
            // (or when battery is not present).  The next time we are
            // unplugged, history will be cleared.
            mRecordingHistory = DEBUG;
        }

        mLastLearnedBatteryCapacityUah = chargeFullUah;
        if (mMinLearnedBatteryCapacityUah == -1) {
            mMinLearnedBatteryCapacityUah = chargeFullUah;
        } else {
            mMinLearnedBatteryCapacityUah = Math.min(mMinLearnedBatteryCapacityUah, chargeFullUah);
        }
        mMaxLearnedBatteryCapacityUah = Math.max(mMaxLearnedBatteryCapacityUah, chargeFullUah);

        mBatteryTimeToFullSeconds = chargeTimeToFullSeconds;
    }

    public static boolean isOnBattery(int plugType, int status) {
        return plugType == BATTERY_PLUGGED_NONE && status != BatteryManager.BATTERY_STATUS_UNKNOWN;
    }

    // Inform StatsLog of setBatteryState changes.
    // If this is the first reporting, pass in recentPast == null.
    private void reportChangesToStatsLog(HistoryItem recentPast,
            final int status, final int plugType, final int level) {

        if (recentPast == null || recentPast.batteryStatus != status) {
            FrameworkStatsLog.write(FrameworkStatsLog.CHARGING_STATE_CHANGED, status);
        }
        if (recentPast == null || recentPast.batteryPlugType != plugType) {
            FrameworkStatsLog.write(FrameworkStatsLog.PLUGGED_STATE_CHANGED, plugType);
        }
        if (recentPast == null || recentPast.batteryLevel != level) {
            FrameworkStatsLog.write(FrameworkStatsLog.BATTERY_LEVEL_CHANGED, level);
        }
    }

    @UnsupportedAppUsage
    public long getAwakeTimeBattery() {
        // This previously evaluated to mOnBatteryTimeBase.getUptime(getBatteryUptimeLocked());
        // for over a decade, but surely that was a mistake.
        return getBatteryUptimeLocked(mClock.uptimeMillis());
    }

    @UnsupportedAppUsage
    public long getAwakeTimePlugged() {
        return (mClock.uptimeMillis() * 1000) - getAwakeTimeBattery();
    }

    @Override
    public long computeUptime(long curTimeUs, int which) {
        return mUptimeUs + (curTimeUs - mUptimeStartUs);
    }

    @Override
    public long computeRealtime(long curTimeUs, int which) {
        return mRealtimeUs + (curTimeUs - mRealtimeStartUs);
    }

    @Override
    @UnsupportedAppUsage
    public long computeBatteryUptime(long curTimeUs, int which) {
        return mOnBatteryTimeBase.computeUptime(curTimeUs, which);
    }

    @Override
    @UnsupportedAppUsage
    public long computeBatteryRealtime(long curTimeUs, int which) {
        return mOnBatteryTimeBase.computeRealtime(curTimeUs, which);
    }

    @Override
    public long computeBatteryScreenOffUptime(long curTimeUs, int which) {
        return mOnBatteryScreenOffTimeBase.computeUptime(curTimeUs, which);
    }

    @Override
    public long computeBatteryScreenOffRealtime(long curTimeUs, int which) {
        return mOnBatteryScreenOffTimeBase.computeRealtime(curTimeUs, which);
    }

    private long computeTimePerLevel(long[] steps, int numSteps) {
        // For now we'll do a simple average across all steps.
        if (numSteps <= 0) {
            return -1;
        }
        long total = 0;
        for (int i=0; i<numSteps; i++) {
            total += steps[i] & STEP_LEVEL_TIME_MASK;
        }
        return total / numSteps;
        /*
        long[] buckets = new long[numSteps];
        int numBuckets = 0;
        int numToAverage = 4;
        int i = 0;
        while (i < numSteps) {
            long totalTime = 0;
            int num = 0;
            for (int j=0; j<numToAverage && (i+j)<numSteps; j++) {
                totalTime += steps[i+j] & STEP_LEVEL_TIME_MASK;
                num++;
            }
            buckets[numBuckets] = totalTime / num;
            numBuckets++;
            numToAverage *= 2;
            i += num;
        }
        if (numBuckets < 1) {
            return -1;
        }
        long averageTime = buckets[numBuckets-1];
        for (i=numBuckets-2; i>=0; i--) {
            averageTime = (averageTime + buckets[i]) / 2;
        }
        return averageTime;
        */
    }

    @Override
    @UnsupportedAppUsage
    public long computeBatteryTimeRemaining(long curTime) {
        if (!mOnBattery) {
            return -1;
        }
        /* Simple implementation just looks at the average discharge per level across the
           entire sample period.
        int discharge = (getLowDischargeAmountSinceCharge()+getHighDischargeAmountSinceCharge())/2;
        if (discharge < 2) {
            return -1;
        }
        long duration = computeBatteryRealtime(curTime, STATS_SINCE_CHARGED);
        if (duration < 1000*1000) {
            return -1;
        }
        long usPerLevel = duration/discharge;
        return usPerLevel * mCurrentBatteryLevel;
        */
        if (mDischargeStepTracker.mNumStepDurations < 1) {
            return -1;
        }
        long msPerLevel = mDischargeStepTracker.computeTimePerLevel();
        if (msPerLevel <= 0) {
            return -1;
        }
        return (msPerLevel * mCurrentBatteryLevel) * 1000;
    }

    @Override
    public LevelStepTracker getDischargeLevelStepTracker() {
        return mDischargeStepTracker;
    }

    @Override
    public LevelStepTracker getDailyDischargeLevelStepTracker() {
        return mDailyDischargeStepTracker;
    }

    @Override
    public long computeChargeTimeRemaining(long curTime) {
        if (mOnBattery) {
            // Not yet working.
            return -1;
        }
        if (mBatteryTimeToFullSeconds >= 0) {
            return mBatteryTimeToFullSeconds * (1000 * 1000); // s to us
        }
        // Else use algorithmic approach
        if (mChargeStepTracker.mNumStepDurations < 1) {
            return -1;
        }
        long msPerLevel = mChargeStepTracker.computeTimePerLevel();
        if (msPerLevel <= 0) {
            return -1;
        }
        return (msPerLevel * (100 - mCurrentBatteryLevel)) * 1000;
    }

    /*@hide */
    public CellularBatteryStats getCellularBatteryStats() {
        final int which = STATS_SINCE_CHARGED;
        final long rawRealTimeUs = SystemClock.elapsedRealtime() * 1000;
        final ControllerActivityCounter counter = getModemControllerActivity();
        final long sleepTimeMs = counter.getSleepTimeCounter().getCountLocked(which);
        final long idleTimeMs = counter.getIdleTimeCounter().getCountLocked(which);
        final long rxTimeMs = counter.getRxTimeCounter().getCountLocked(which);
        final long energyConsumedMaMs = counter.getPowerCounter().getCountLocked(which);
        final long monitoredRailChargeConsumedMaMs =
                counter.getMonitoredRailChargeConsumedMaMs().getCountLocked(which);
        long[] timeInRatMs = new long[BatteryStats.NUM_DATA_CONNECTION_TYPES];
        for (int i = 0; i < timeInRatMs.length; i++) {
            timeInRatMs[i] = getPhoneDataConnectionTime(i, rawRealTimeUs, which) / 1000;
        }
        long[] timeInRxSignalStrengthLevelMs =
                new long[CellSignalStrength.getNumSignalStrengthLevels()];
        for (int i = 0; i < timeInRxSignalStrengthLevelMs.length; i++) {
            timeInRxSignalStrengthLevelMs[i] =
                getPhoneSignalStrengthTime(i, rawRealTimeUs, which) / 1000;
        }
        long[] txTimeMs = new long[Math.min(ModemActivityInfo.getNumTxPowerLevels(),
            counter.getTxTimeCounters().length)];
        long totalTxTimeMs = 0;
        for (int i = 0; i < txTimeMs.length; i++) {
            txTimeMs[i] = counter.getTxTimeCounters()[i].getCountLocked(which);
            totalTxTimeMs += txTimeMs[i];
        }

        return new CellularBatteryStats(computeBatteryRealtime(rawRealTimeUs, which) / 1000,
                getMobileRadioActiveTime(rawRealTimeUs, which) / 1000,
                getNetworkActivityPackets(NETWORK_MOBILE_TX_DATA, which),
                getNetworkActivityBytes(NETWORK_MOBILE_TX_DATA, which),
                getNetworkActivityPackets(NETWORK_MOBILE_RX_DATA, which),
                getNetworkActivityBytes(NETWORK_MOBILE_RX_DATA, which),
                sleepTimeMs, idleTimeMs, rxTimeMs, energyConsumedMaMs, timeInRatMs,
                timeInRxSignalStrengthLevelMs, txTimeMs,
                monitoredRailChargeConsumedMaMs);
    }

    /*@hide */
    public WifiBatteryStats getWifiBatteryStats() {
        final int which = STATS_SINCE_CHARGED;
        final long rawRealTimeUs = SystemClock.elapsedRealtime() * 1000;
        final ControllerActivityCounter counter = getWifiControllerActivity();
        final long idleTimeMs = counter.getIdleTimeCounter().getCountLocked(which);
        final long scanTimeMs = counter.getScanTimeCounter().getCountLocked(which);
        final long rxTimeMs = counter.getRxTimeCounter().getCountLocked(which);
        final long txTimeMs = counter.getTxTimeCounters()[0].getCountLocked(which);
        final long totalControllerActivityTimeMs
                = computeBatteryRealtime(SystemClock.elapsedRealtime() * 1000, which) / 1000;
        final long sleepTimeMs
                = totalControllerActivityTimeMs - (idleTimeMs + rxTimeMs + txTimeMs);
        final long energyConsumedMaMs = counter.getPowerCounter().getCountLocked(which);
        final long monitoredRailChargeConsumedMaMs =
                counter.getMonitoredRailChargeConsumedMaMs().getCountLocked(which);
        long numAppScanRequest = 0;
        for (int i = 0; i < mUidStats.size(); i++) {
            numAppScanRequest += mUidStats.valueAt(i).mWifiScanTimer.getCountLocked(which);
        }
        long[] timeInStateMs = new long[NUM_WIFI_STATES];
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            timeInStateMs[i] = getWifiStateTime(i, rawRealTimeUs, which) / 1000;
        }
        long[] timeInSupplStateMs = new long[NUM_WIFI_SUPPL_STATES];
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            timeInSupplStateMs[i] = getWifiSupplStateTime(i, rawRealTimeUs, which) / 1000;
        }
        long[] timeSignalStrengthTimeMs = new long[NUM_WIFI_SIGNAL_STRENGTH_BINS];
        for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            timeSignalStrengthTimeMs[i] = getWifiSignalStrengthTime(i, rawRealTimeUs, which) / 1000;
        }
        return new WifiBatteryStats(
                computeBatteryRealtime(rawRealTimeUs, which) / 1000,
                getWifiActiveTime(rawRealTimeUs, which) / 1000,
                getNetworkActivityPackets(NETWORK_WIFI_TX_DATA, which),
                getNetworkActivityBytes(NETWORK_WIFI_TX_DATA, which),
                getNetworkActivityPackets(NETWORK_WIFI_RX_DATA, which),
                getNetworkActivityBytes(NETWORK_WIFI_RX_DATA, which),
                sleepTimeMs, scanTimeMs, idleTimeMs, rxTimeMs, txTimeMs, energyConsumedMaMs,
                numAppScanRequest, timeInStateMs, timeSignalStrengthTimeMs, timeInSupplStateMs,
                monitoredRailChargeConsumedMaMs);
    }

    /*@hide */
    public GpsBatteryStats getGpsBatteryStats() {
        GpsBatteryStats s = new GpsBatteryStats();
        final int which = STATS_SINCE_CHARGED;
        final long rawRealTimeUs = SystemClock.elapsedRealtime() * 1000;
        s.setLoggingDurationMs(computeBatteryRealtime(rawRealTimeUs, which) / 1000);
        s.setEnergyConsumedMaMs(getGpsBatteryDrainMaMs());
        long[] time = new long[mGpsSignalQualityTimer.length];
        for (int i=0; i<time.length; i++) {
            time[i] = getGpsSignalQualityTime(i, rawRealTimeUs, which) / 1000;
        }
        s.setTimeInGpsSignalQualityLevel(time);
        return s;
    }

    @Override
    public LevelStepTracker getChargeLevelStepTracker() {
        return mChargeStepTracker;
    }

    @Override
    public LevelStepTracker getDailyChargeLevelStepTracker() {
        return mDailyChargeStepTracker;
    }

    @Override
    public ArrayList<PackageChange> getDailyPackageChanges() {
        return mDailyPackageChanges;
    }

    /**
     * @return battery uptime in microseconds
     */
    protected long getBatteryUptimeLocked() {
        return getBatteryUptimeLocked(mClock.uptimeMillis());
    }

    /**
     * @return battery uptime in microseconds
     */
    protected long getBatteryUptimeLocked(long uptimeMs) {
        return mOnBatteryTimeBase.getUptime(uptimeMs * 1000);
    }

    @Override
    public long getBatteryUptime(long curTimeUs) {
        return mOnBatteryTimeBase.getUptime(curTimeUs);
    }

    @Override
    @UnsupportedAppUsage
    public long getBatteryRealtime(long curTimeUs) {
        return mOnBatteryTimeBase.getRealtime(curTimeUs);
    }

    @Override
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int getDischargeStartLevel() {
        synchronized(this) {
            return getDischargeStartLevelLocked();
        }
    }

    public int getDischargeStartLevelLocked() {
            return mDischargeUnplugLevel;
    }

    @Override
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int getDischargeCurrentLevel() {
        synchronized(this) {
            return getDischargeCurrentLevelLocked();
        }
    }

    public int getDischargeCurrentLevelLocked() {
        return mDischargeCurrentLevel;
    }

    @Override
    public int getLowDischargeAmountSinceCharge() {
        synchronized(this) {
            int val = mLowDischargeAmountSinceCharge;
            if (mOnBattery && mDischargeCurrentLevel < mDischargeUnplugLevel) {
                val += mDischargeUnplugLevel-mDischargeCurrentLevel-1;
            }
            return val;
        }
    }

    @Override
    public int getHighDischargeAmountSinceCharge() {
        synchronized(this) {
            int val = mHighDischargeAmountSinceCharge;
            if (mOnBattery && mDischargeCurrentLevel < mDischargeUnplugLevel) {
                val += mDischargeUnplugLevel-mDischargeCurrentLevel;
            }
            return val;
        }
    }

    @Override
    @UnsupportedAppUsage
    public int getDischargeAmount(int which) {
        int dischargeAmount = which == STATS_SINCE_CHARGED
                ? getHighDischargeAmountSinceCharge()
                : (getDischargeStartLevel() - getDischargeCurrentLevel());
        if (dischargeAmount < 0) {
            dischargeAmount = 0;
        }
        return dischargeAmount;
    }

    @Override
    @UnsupportedAppUsage
    public int getDischargeAmountScreenOn() {
        synchronized(this) {
            int val = mDischargeAmountScreenOn;
            if (mOnBattery && Display.isOnState(mScreenState)
                    && mDischargeCurrentLevel < mDischargeScreenOnUnplugLevel) {
                val += mDischargeScreenOnUnplugLevel-mDischargeCurrentLevel;
            }
            return val;
        }
    }

    @Override
    public int getDischargeAmountScreenOnSinceCharge() {
        synchronized(this) {
            int val = mDischargeAmountScreenOnSinceCharge;
            if (mOnBattery && Display.isOnState(mScreenState)
                    && mDischargeCurrentLevel < mDischargeScreenOnUnplugLevel) {
                val += mDischargeScreenOnUnplugLevel-mDischargeCurrentLevel;
            }
            return val;
        }
    }

    @Override
    @UnsupportedAppUsage
    public int getDischargeAmountScreenOff() {
        synchronized(this) {
            int val = mDischargeAmountScreenOff;
            if (mOnBattery && Display.isOffState(mScreenState)
                    && mDischargeCurrentLevel < mDischargeScreenOffUnplugLevel) {
                val += mDischargeScreenOffUnplugLevel-mDischargeCurrentLevel;
            }
            // For backward compatibility, doze discharge is counted into screen off.
            return val + getDischargeAmountScreenDoze();
        }
    }

    @Override
    public int getDischargeAmountScreenOffSinceCharge() {
        synchronized(this) {
            int val = mDischargeAmountScreenOffSinceCharge;
            if (mOnBattery && Display.isOffState(mScreenState)
                    && mDischargeCurrentLevel < mDischargeScreenOffUnplugLevel) {
                val += mDischargeScreenOffUnplugLevel-mDischargeCurrentLevel;
            }
            // For backward compatibility, doze discharge is counted into screen off.
            return val + getDischargeAmountScreenDozeSinceCharge();
        }
    }

    @Override
    public int getDischargeAmountScreenDoze() {
        synchronized(this) {
            int val = mDischargeAmountScreenDoze;
            if (mOnBattery && Display.isDozeState(mScreenState)
                    && mDischargeCurrentLevel < mDischargeScreenDozeUnplugLevel) {
                val += mDischargeScreenDozeUnplugLevel-mDischargeCurrentLevel;
            }
            return val;
        }
    }

    @Override
    public int getDischargeAmountScreenDozeSinceCharge() {
        synchronized(this) {
            int val = mDischargeAmountScreenDozeSinceCharge;
            if (mOnBattery && Display.isDozeState(mScreenState)
                    && mDischargeCurrentLevel < mDischargeScreenDozeUnplugLevel) {
                val += mDischargeScreenDozeUnplugLevel-mDischargeCurrentLevel;
            }
            return val;
        }
    }


    /**
     * Estimates the time spent by the system server handling incoming binder requests.
     */
    @Override
    public long[] getSystemServiceTimeAtCpuSpeeds() {
        if (mBinderThreadCpuTimesUs == null) {
            return null;
        }

        return mBinderThreadCpuTimesUs.getCountsLocked(BatteryStats.STATS_SINCE_CHARGED);
    }

    /**
     * Retrieve the statistics object for a particular uid, creating if needed.
     */
    @UnsupportedAppUsage
    public Uid getUidStatsLocked(int uid) {
        return getUidStatsLocked(uid, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    public Uid getUidStatsLocked(int uid, long elapsedRealtimeMs, long uptimeMs) {
        Uid u = mUidStats.get(uid);
        if (u == null) {
            if (Process.isSdkSandboxUid(uid)) {
                Log.wtf(TAG, "Tracking an SDK Sandbox UID");
            }
            u = new Uid(this, uid, elapsedRealtimeMs, uptimeMs);
            mUidStats.put(uid, u);
        }
        return u;
    }

    /**
     * Retrieve the statistics object for a particular uid. Returns null if the object is not
     * available.
     */
    public Uid getAvailableUidStatsLocked(int uid) {
        Uid u = mUidStats.get(uid);
        return u;
    }

    @GuardedBy("this")
    public void onCleanupUserLocked(int userId, long elapsedRealtimeMs) {
        final int firstUidForUser = UserHandle.getUid(userId, 0);
        final int lastUidForUser = UserHandle.getUid(userId, UserHandle.PER_USER_RANGE - 1);
        mPendingRemovedUids.add(
                new UidToRemove(firstUidForUser, lastUidForUser, elapsedRealtimeMs));
    }

    @GuardedBy("this")
    public void onUserRemovedLocked(int userId) {
        if (mExternalSync != null) {
            // Clear out the removed user's UIDs after a short delay. The delay is needed
            // because at the point that this method is called, some activities are still
            // being wrapped up by those UIDs
            mExternalSync.scheduleCleanupDueToRemovedUser(userId);
        }
    }

    /**
     * Removes battery stats for UIDs corresponding to a removed user.
     */
    @GuardedBy("this")
    public void clearRemovedUserUidsLocked(int userId) {
        final int firstUidForUser = UserHandle.getUid(userId, 0);
        final int lastUidForUser = UserHandle.getUid(userId, UserHandle.PER_USER_RANGE - 1);
        mUidStats.put(firstUidForUser, null);
        mUidStats.put(lastUidForUser, null);
        final int firstIndex = mUidStats.indexOfKey(firstUidForUser);
        final int lastIndex = mUidStats.indexOfKey(lastUidForUser);
        for (int i = firstIndex; i <= lastIndex; i++) {
            final Uid uid = mUidStats.valueAt(i);
            if (uid != null) {
                uid.detachFromTimeBase();
            }
        }
        mUidStats.removeAtRange(firstIndex, lastIndex - firstIndex + 1);
        removeCpuStatsForUidRangeLocked(firstUidForUser, lastUidForUser);
    }

    /**
     * Remove the statistics object for a particular uid.
     */
    @UnsupportedAppUsage
    @GuardedBy("this")
    public void removeUidStatsLocked(int uid) {
        removeUidStatsLocked(uid, mClock.elapsedRealtime());
    }

    /**
     * @see #removeUidStatsLocked(int)
     */
    @GuardedBy("this")
    public void removeUidStatsLocked(int uid, long elapsedRealtimeMs) {
        final Uid u = mUidStats.get(uid);
        if (u != null) {
            u.detachFromTimeBase();
        }
        mUidStats.remove(uid);
        mPendingRemovedUids.add(new UidToRemove(uid, elapsedRealtimeMs));
    }

    /**
     * Removes the data for the deleted UIDs from the underlying kernel eBPF tables.
     */
    @GuardedBy("this")
    private void removeCpuStatsForUidRangeLocked(int startUid, int endUid) {
        if (startUid == endUid) {
            mCpuUidUserSysTimeReader.removeUid(startUid);
            mCpuUidFreqTimeReader.removeUid(startUid);
            if (mConstants.TRACK_CPU_ACTIVE_CLUSTER_TIME) {
                mCpuUidActiveTimeReader.removeUid(startUid);
                mCpuUidClusterTimeReader.removeUid(startUid);
            }
            if (mKernelSingleUidTimeReader != null) {
                mKernelSingleUidTimeReader.removeUid(startUid);
            }
            mNumUidsRemoved++;
        } else if (startUid < endUid) {
            mCpuUidFreqTimeReader.removeUidsInRange(startUid, endUid);
            mCpuUidUserSysTimeReader.removeUidsInRange(startUid, endUid);
            if (mConstants.TRACK_CPU_ACTIVE_CLUSTER_TIME) {
                mCpuUidActiveTimeReader.removeUidsInRange(startUid, endUid);
                mCpuUidClusterTimeReader.removeUidsInRange(startUid, endUid);
            }
            if (mKernelSingleUidTimeReader != null) {
                mKernelSingleUidTimeReader.removeUidsInRange(startUid, endUid);
            }
            // Treat as one. We don't know how many uids there are in between.
            mNumUidsRemoved++;
        } else {
            Slog.w(TAG, "End UID " + endUid + " is smaller than start UID " + startUid);
        }
    }

    /**
     * Retrieve the statistics object for a particular process, creating
     * if needed.
     */
    @UnsupportedAppUsage
    public Uid.Proc getProcessStatsLocked(int uid, String name) {
        return getProcessStatsLocked(uid, name, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    /**
     * @see #getProcessStatsLocked(int, String)
     */
    public Uid.Proc getProcessStatsLocked(int uid, String name,
            long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        Uid u = getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs);
        return u.getProcessStatsLocked(name);
    }

    /**
     * Retrieve the statistics object for a particular process, creating
     * if needed.
     */
    @UnsupportedAppUsage
    public Uid.Pkg getPackageStatsLocked(int uid, String pkg) {
        return getPackageStatsLocked(uid, pkg, mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    /**
     * @see getPackageStatsLocked(int, String)
     */
    public Uid.Pkg getPackageStatsLocked(int uid, String pkg,
            long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        Uid u = getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs);
        return u.getPackageStatsLocked(pkg);
    }

    /**
     * Retrieve the statistics object for a particular service, creating
     * if needed.
     */
    @UnsupportedAppUsage
    public Uid.Pkg.Serv getServiceStatsLocked(int uid, String pkg, String name) {
        return getServiceStatsLocked(uid, pkg, name,
                mClock.elapsedRealtime(), mClock.uptimeMillis());
    }

    public Uid.Pkg.Serv getServiceStatsLocked(int uid, String pkg, String name,
            long elapsedRealtimeMs, long uptimeMs) {
        uid = mapUid(uid);
        Uid u = getUidStatsLocked(uid, elapsedRealtimeMs, uptimeMs);
        return u.getServiceStatsLocked(pkg, name);
    }

    @GuardedBy("this")
    public void shutdownLocked() {
        recordShutdownLocked(mClock.currentTimeMillis(), mClock.elapsedRealtime());
        writeSyncLocked();
        mShuttingDown = true;
    }

    @GuardedBy("this")
    @Override
    public boolean isProcessStateDataAvailable() {
        return trackPerProcStateCpuTimes();
    }

    @GuardedBy("this")
    public boolean trackPerProcStateCpuTimes() {
        return mCpuUidFreqTimeReader.isFastCpuTimesReader();
    }

    @GuardedBy("this")
    public void systemServicesReady(Context context) {
        mConstants.startObserving(context.getContentResolver());
        registerUsbStateReceiver(context);
    }

    /**
     * Initialize the measured charge stats data structures.
     *
     * @param supportedStandardBuckets boolean array indicating which {@link StandardPowerBucket}s
     *                                 are currently supported. If null, none are supported
     *                                 (regardless of customBucketNames).
     * @param customBucketNames        names of custom (OTHER) EnergyConsumers on this device
     */
    @GuardedBy("this")
    public void initMeasuredEnergyStatsLocked(@Nullable boolean[] supportedStandardBuckets,
            String[] customBucketNames) {
        final int numDisplays = mPerDisplayBatteryStats.length;
        for (int i = 0; i < numDisplays; i++) {
            final int screenState = mPerDisplayBatteryStats[i].screenState;
            mPerDisplayBatteryStats[i].screenStateAtLastEnergyMeasurement = screenState;
        }

        final boolean compatibleConfig;
        if (supportedStandardBuckets != null) {
            final MeasuredEnergyStats.Config config = new MeasuredEnergyStats.Config(
                    supportedStandardBuckets, customBucketNames,
                    SUPPORTED_PER_PROCESS_STATE_STANDARD_ENERGY_BUCKETS,
                    getBatteryConsumerProcessStateNames());

            if (mMeasuredEnergyStatsConfig == null) {
                compatibleConfig = true;
            } else {
                compatibleConfig = mMeasuredEnergyStatsConfig.isCompatible(config);
            }

            mMeasuredEnergyStatsConfig = config;
            mGlobalMeasuredEnergyStats = new MeasuredEnergyStats(config);

            if (supportedStandardBuckets[MeasuredEnergyStats.POWER_BUCKET_BLUETOOTH]) {
                mBluetoothPowerCalculator = new BluetoothPowerCalculator(mPowerProfile);
            }
            if (supportedStandardBuckets[MeasuredEnergyStats.POWER_BUCKET_CPU]) {
                mCpuPowerCalculator = new CpuPowerCalculator(mPowerProfile);
            }
            if (supportedStandardBuckets[MeasuredEnergyStats.POWER_BUCKET_MOBILE_RADIO]) {
                mMobileRadioPowerCalculator = new MobileRadioPowerCalculator(mPowerProfile);
            }
            if (supportedStandardBuckets[MeasuredEnergyStats.POWER_BUCKET_WIFI]) {
                mWifiPowerCalculator = new WifiPowerCalculator(mPowerProfile);
            }
        } else {
            compatibleConfig = (mMeasuredEnergyStatsConfig == null);
            // Measured energy no longer supported, wipe out the existing data.
            mMeasuredEnergyStatsConfig = null;
            mGlobalMeasuredEnergyStats = null;
        }

        if (!compatibleConfig) {
            // Supported power buckets changed since last boot.
            // Existing data is no longer reliable.
            resetAllStatsLocked(SystemClock.uptimeMillis(), SystemClock.elapsedRealtime(),
                    RESET_REASON_MEASURED_ENERGY_BUCKETS_CHANGE);
        }
    }

    @NonNull
    private static String[] getBatteryConsumerProcessStateNames() {
        String[] procStateNames = new String[BatteryConsumer.PROCESS_STATE_COUNT];
        for (int procState = 0; procState < BatteryConsumer.PROCESS_STATE_COUNT; procState++) {
            procStateNames[procState] = BatteryConsumer.processStateToString(procState);
        }
        return procStateNames;
    }

    /** Get the last known Battery voltage (in millivolts), returns -1 if unknown */
    @GuardedBy("this")
    public int getBatteryVoltageMvLocked() {
        return mBatteryVoltageMv;
    }

    @VisibleForTesting
    public final class Constants extends ContentObserver {
        public static final String KEY_TRACK_CPU_ACTIVE_CLUSTER_TIME
                = "track_cpu_active_cluster_time";
        public static final String KEY_PROC_STATE_CPU_TIMES_READ_DELAY_MS
                = "proc_state_cpu_times_read_delay_ms";
        public static final String KEY_KERNEL_UID_READERS_THROTTLE_TIME
                = "kernel_uid_readers_throttle_time";
        public static final String KEY_UID_REMOVE_DELAY_MS
                = "uid_remove_delay_ms";
        public static final String KEY_EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS
                = "external_stats_collection_rate_limit_ms";
        public static final String KEY_BATTERY_LEVEL_COLLECTION_DELAY_MS
                = "battery_level_collection_delay_ms";
        public static final String KEY_PROC_STATE_CHANGE_COLLECTION_DELAY_MS =
                "procstate_change_collection_delay_ms";
        public static final String KEY_MAX_HISTORY_FILES = "max_history_files";
        public static final String KEY_MAX_HISTORY_BUFFER_KB = "max_history_buffer_kb";
        public static final String KEY_BATTERY_CHARGED_DELAY_MS =
                "battery_charged_delay_ms";
        public static final String KEY_PHONE_ON_EXTERNAL_STATS_COLLECTION =
                "phone_on_external_stats_collection";

        private static final boolean DEFAULT_TRACK_CPU_ACTIVE_CLUSTER_TIME = true;
        private static final long DEFAULT_KERNEL_UID_READERS_THROTTLE_TIME = 1_000;
        private static final long DEFAULT_UID_REMOVE_DELAY_MS = 5L * 60L * 1000L;
        private static final long DEFAULT_EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS = 600_000;
        private static final long DEFAULT_BATTERY_LEVEL_COLLECTION_DELAY_MS = 300_000;
        private static final long DEFAULT_PROC_STATE_CHANGE_COLLECTION_DELAY_MS = 60_000;
        private static final int DEFAULT_MAX_HISTORY_FILES = 32;
        private static final int DEFAULT_MAX_HISTORY_BUFFER_KB = 128; /*Kilo Bytes*/
        private static final int DEFAULT_MAX_HISTORY_FILES_LOW_RAM_DEVICE = 64;
        private static final int DEFAULT_MAX_HISTORY_BUFFER_LOW_RAM_DEVICE_KB = 64; /*Kilo Bytes*/
        private static final int DEFAULT_BATTERY_CHARGED_DELAY_MS = 900000; /* 15 min */
        private static final boolean DEFAULT_PHONE_ON_EXTERNAL_STATS_COLLECTION = true;

        public boolean TRACK_CPU_ACTIVE_CLUSTER_TIME = DEFAULT_TRACK_CPU_ACTIVE_CLUSTER_TIME;
        /* Do not set default value for KERNEL_UID_READERS_THROTTLE_TIME. Need to trigger an
         * update when startObserving. */
        public long KERNEL_UID_READERS_THROTTLE_TIME;
        public long UID_REMOVE_DELAY_MS = DEFAULT_UID_REMOVE_DELAY_MS;
        public long EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS
                = DEFAULT_EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS;
        public long BATTERY_LEVEL_COLLECTION_DELAY_MS
                = DEFAULT_BATTERY_LEVEL_COLLECTION_DELAY_MS;
        public long PROC_STATE_CHANGE_COLLECTION_DELAY_MS =
                DEFAULT_PROC_STATE_CHANGE_COLLECTION_DELAY_MS;
        public int MAX_HISTORY_FILES;
        public int MAX_HISTORY_BUFFER; /*Bytes*/
        public int BATTERY_CHARGED_DELAY_MS = DEFAULT_BATTERY_CHARGED_DELAY_MS;
        public boolean PHONE_ON_EXTERNAL_STATS_COLLECTION =
                DEFAULT_PHONE_ON_EXTERNAL_STATS_COLLECTION;

        private ContentResolver mResolver;
        private final KeyValueListParser mParser = new KeyValueListParser(',');

        public Constants(Handler handler) {
            super(handler);
            if (ActivityManager.isLowRamDeviceStatic()) {
                MAX_HISTORY_FILES = DEFAULT_MAX_HISTORY_FILES_LOW_RAM_DEVICE;
                MAX_HISTORY_BUFFER = DEFAULT_MAX_HISTORY_BUFFER_LOW_RAM_DEVICE_KB * 1024;
            } else {
                MAX_HISTORY_FILES = DEFAULT_MAX_HISTORY_FILES;
                MAX_HISTORY_BUFFER = DEFAULT_MAX_HISTORY_BUFFER_KB * 1024;
            }
        }

        public void startObserving(ContentResolver resolver) {
            mResolver = resolver;
            mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BATTERY_STATS_CONSTANTS),
                    false /* notifyForDescendants */, this);
            mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BATTERY_CHARGING_STATE_UPDATE_DELAY),
                    false /* notifyForDescendants */, this);
            updateConstants();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(
                    Settings.Global.getUriFor(
                            Settings.Global.BATTERY_CHARGING_STATE_UPDATE_DELAY))) {
                synchronized (BatteryStatsImpl.this) {
                    updateBatteryChargedDelayMsLocked();
                }
                return;
            }
            updateConstants();
        }

        private void updateConstants() {
            synchronized (BatteryStatsImpl.this) {
                try {
                    mParser.setString(Settings.Global.getString(mResolver,
                            Settings.Global.BATTERY_STATS_CONSTANTS));
                } catch (IllegalArgumentException e) {
                    // Failed to parse the settings string, log this and move on
                    // with defaults.
                    Slog.e(TAG, "Bad batterystats settings", e);
                }

                TRACK_CPU_ACTIVE_CLUSTER_TIME = mParser.getBoolean(
                        KEY_TRACK_CPU_ACTIVE_CLUSTER_TIME, DEFAULT_TRACK_CPU_ACTIVE_CLUSTER_TIME);
                updateKernelUidReadersThrottleTime(KERNEL_UID_READERS_THROTTLE_TIME,
                        mParser.getLong(KEY_KERNEL_UID_READERS_THROTTLE_TIME,
                                DEFAULT_KERNEL_UID_READERS_THROTTLE_TIME));
                updateUidRemoveDelay(
                        mParser.getLong(KEY_UID_REMOVE_DELAY_MS, DEFAULT_UID_REMOVE_DELAY_MS));
                EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS = mParser.getLong(
                        KEY_EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS,
                        DEFAULT_EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS);
                BATTERY_LEVEL_COLLECTION_DELAY_MS = mParser.getLong(
                        KEY_BATTERY_LEVEL_COLLECTION_DELAY_MS,
                        DEFAULT_BATTERY_LEVEL_COLLECTION_DELAY_MS);
                PROC_STATE_CHANGE_COLLECTION_DELAY_MS = mParser.getLong(
                        KEY_PROC_STATE_CHANGE_COLLECTION_DELAY_MS,
                        DEFAULT_PROC_STATE_CHANGE_COLLECTION_DELAY_MS);

                MAX_HISTORY_FILES = mParser.getInt(KEY_MAX_HISTORY_FILES,
                        ActivityManager.isLowRamDeviceStatic() ?
                                DEFAULT_MAX_HISTORY_FILES_LOW_RAM_DEVICE
                        : DEFAULT_MAX_HISTORY_FILES);
                MAX_HISTORY_BUFFER = mParser.getInt(KEY_MAX_HISTORY_BUFFER_KB,
                        ActivityManager.isLowRamDeviceStatic() ?
                                DEFAULT_MAX_HISTORY_BUFFER_LOW_RAM_DEVICE_KB
                                : DEFAULT_MAX_HISTORY_BUFFER_KB)
                        * 1024;

                PHONE_ON_EXTERNAL_STATS_COLLECTION = mParser.getBoolean(
                        KEY_PHONE_ON_EXTERNAL_STATS_COLLECTION,
                        DEFAULT_PHONE_ON_EXTERNAL_STATS_COLLECTION);

                updateBatteryChargedDelayMsLocked();
            }
        }

        private void updateBatteryChargedDelayMsLocked() {
            // a negative value indicates that we should ignore this override
            final int delay = Settings.Global.getInt(mResolver,
                    Settings.Global.BATTERY_CHARGING_STATE_UPDATE_DELAY,
                    -1);

            BATTERY_CHARGED_DELAY_MS = delay >= 0 ? delay : mParser.getInt(
                    KEY_BATTERY_CHARGED_DELAY_MS,
                    DEFAULT_BATTERY_CHARGED_DELAY_MS);

            if (mHandler.hasCallbacks(mDeferSetCharging)) {
                mHandler.removeCallbacks(mDeferSetCharging);
                mHandler.postDelayed(mDeferSetCharging, BATTERY_CHARGED_DELAY_MS);
            }
        }

        private void updateKernelUidReadersThrottleTime(long oldTimeMs, long newTimeMs) {
            KERNEL_UID_READERS_THROTTLE_TIME = newTimeMs;
            if (oldTimeMs != newTimeMs) {
                mCpuUidUserSysTimeReader.setThrottle(KERNEL_UID_READERS_THROTTLE_TIME);
                mCpuUidFreqTimeReader.setThrottle(KERNEL_UID_READERS_THROTTLE_TIME);
                mCpuUidActiveTimeReader.setThrottle(KERNEL_UID_READERS_THROTTLE_TIME);
                mCpuUidClusterTimeReader
                        .setThrottle(KERNEL_UID_READERS_THROTTLE_TIME);
            }
        }

        @GuardedBy("BatteryStatsImpl.this")
        private void updateUidRemoveDelay(long newTimeMs) {
            UID_REMOVE_DELAY_MS = newTimeMs;
            clearPendingRemovedUidsLocked();
        }

        public void dumpLocked(PrintWriter pw) {
            pw.print(KEY_TRACK_CPU_ACTIVE_CLUSTER_TIME); pw.print("=");
            pw.println(TRACK_CPU_ACTIVE_CLUSTER_TIME);
            pw.print(KEY_KERNEL_UID_READERS_THROTTLE_TIME); pw.print("=");
            pw.println(KERNEL_UID_READERS_THROTTLE_TIME);
            pw.print(KEY_EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS); pw.print("=");
            pw.println(EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS);
            pw.print(KEY_BATTERY_LEVEL_COLLECTION_DELAY_MS); pw.print("=");
            pw.println(BATTERY_LEVEL_COLLECTION_DELAY_MS);
            pw.print(KEY_PROC_STATE_CHANGE_COLLECTION_DELAY_MS); pw.print("=");
            pw.println(PROC_STATE_CHANGE_COLLECTION_DELAY_MS);
            pw.print(KEY_MAX_HISTORY_FILES); pw.print("=");
            pw.println(MAX_HISTORY_FILES);
            pw.print(KEY_MAX_HISTORY_BUFFER_KB); pw.print("=");
            pw.println(MAX_HISTORY_BUFFER/1024);
            pw.print(KEY_BATTERY_CHARGED_DELAY_MS); pw.print("=");
            pw.println(BATTERY_CHARGED_DELAY_MS);
            pw.print(KEY_PHONE_ON_EXTERNAL_STATS_COLLECTION); pw.print("=");
            pw.println(PHONE_ON_EXTERNAL_STATS_COLLECTION);
        }
    }

    public long getExternalStatsCollectionRateLimitMs() {
        synchronized (this) {
            return mConstants.EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS;
        }
    }

    @GuardedBy("this")
    public void dumpConstantsLocked(PrintWriter pw) {
        final IndentingPrintWriter iPw = new IndentingPrintWriter(pw, "    ");
        iPw.println("BatteryStats constants:");
        iPw.increaseIndent();
        mConstants.dumpLocked(iPw);
        iPw.decreaseIndent();
    }

    @GuardedBy("this")
    public void dumpCpuStatsLocked(PrintWriter pw) {
        int size = mUidStats.size();
        pw.println("Per UID CPU user & system time in ms:");
        for (int i = 0; i < size; i++) {
            int u = mUidStats.keyAt(i);
            Uid uid = mUidStats.get(u);
            pw.print("  "); pw.print(u); pw.print(": ");
            pw.print(uid.getUserCpuTimeUs(STATS_SINCE_CHARGED) / 1000); pw.print(" ");
            pw.println(uid.getSystemCpuTimeUs(STATS_SINCE_CHARGED) / 1000);
        }

        pw.println("Per UID CPU active time in ms:");
        for (int i = 0; i < size; i++) {
            int u = mUidStats.keyAt(i);
            Uid uid = mUidStats.get(u);
            if (uid.getCpuActiveTime() > 0) {
                pw.print("  "); pw.print(u); pw.print(": "); pw.println(uid.getCpuActiveTime());
            }
        }
        pw.println("Per UID CPU cluster time in ms:");
        for (int i = 0; i < size; i++) {
            int u = mUidStats.keyAt(i);
            long[] times = mUidStats.get(u).getCpuClusterTimes();
            if (times != null) {
                pw.print("  "); pw.print(u); pw.print(": "); pw.println(Arrays.toString(times));
            }
        }
        pw.println("Per UID CPU frequency time in ms:");
        for (int i = 0; i < size; i++) {
            int u = mUidStats.keyAt(i);
            long[] times = mUidStats.get(u).getCpuFreqTimes(STATS_SINCE_CHARGED);
            if (times != null) {
                pw.print("  "); pw.print(u); pw.print(": "); pw.println(Arrays.toString(times));
            }
        }

        updateSystemServiceCallStats();
        if (mBinderThreadCpuTimesUs != null) {
            pw.println("Per UID System server binder time in ms:");
            long[] systemServiceTimeAtCpuSpeeds = getSystemServiceTimeAtCpuSpeeds();
            for (int i = 0; i < size; i++) {
                int u = mUidStats.keyAt(i);
                Uid uid = mUidStats.get(u);
                double proportionalSystemServiceUsage = uid.getProportionalSystemServiceUsage();
                long timeUs = 0;
                for (int j = systemServiceTimeAtCpuSpeeds.length - 1; j >= 0; j--) {
                    timeUs += systemServiceTimeAtCpuSpeeds[j] * proportionalSystemServiceUsage;
                }

                pw.print("  ");
                pw.print(u);
                pw.print(": ");
                pw.println(timeUs / 1000);
            }
        }
    }

    /**
     * Dump measured charge stats
     */
    @GuardedBy("this")
    public void dumpMeasuredEnergyStatsLocked(PrintWriter pw) {
        pw.printf("On battery measured charge stats (microcoulombs) \n");
        if (mGlobalMeasuredEnergyStats == null) {
            pw.printf("    Not supported on this device.\n");
            return;
        }

        dumpMeasuredEnergyStatsLocked(pw, "global usage", mGlobalMeasuredEnergyStats);

        int size = mUidStats.size();
        for (int i = 0; i < size; i++) {
            final int u = mUidStats.keyAt(i);
            final Uid uid = mUidStats.get(u);
            final String name = "uid " + uid.mUid;
            dumpMeasuredEnergyStatsLocked(pw, name, uid.mUidMeasuredEnergyStats);
        }
    }

    /** Dump measured charge stats for the given uid */
    @GuardedBy("this")
    private void dumpMeasuredEnergyStatsLocked(PrintWriter pw, String name,
            MeasuredEnergyStats stats) {
        if (stats == null) return;
        final IndentingPrintWriter iPw = new IndentingPrintWriter(pw, "    ");
        iPw.increaseIndent();
        iPw.printf("%s:\n", name);
        iPw.increaseIndent();
        stats.dump(iPw);
        iPw.decreaseIndent();
    }

    /**
     * Dump Power Profile
     */
    @GuardedBy("this")
    public void dumpPowerProfileLocked(PrintWriter pw) {
        final IndentingPrintWriter iPw = new IndentingPrintWriter(pw, "    ");
        iPw.printf("Power Profile: \n");
        iPw.increaseIndent();
        mPowerProfile.dump(iPw);
        iPw.decreaseIndent();
    }

    final ReentrantLock mWriteLock = new ReentrantLock();

    @GuardedBy("this")
    public void writeAsyncLocked() {
        writeStatsLocked(false);
        writeHistoryLocked(false);
    }

    @GuardedBy("this")
    public void writeSyncLocked() {
        writeStatsLocked(true);
        writeHistoryLocked(true);
    }

    @GuardedBy("this")
    void writeStatsLocked(boolean sync) {
        if (mStatsFile == null) {
            Slog.w(TAG,
                    "writeStatsLocked: no file associated with this instance");
            return;
        }

        if (mShuttingDown) {
            return;
        }

        final Parcel p = Parcel.obtain();
        final long start = SystemClock.uptimeMillis();
        writeSummaryToParcel(p, false/*history is in separate file*/);
        if (DEBUG) {
            Slog.d(TAG, "writeSummaryToParcel duration ms:"
                    + (SystemClock.uptimeMillis() - start) + " bytes:" + p.dataSize());
        }
        mLastWriteTimeMs = mClock.elapsedRealtime();
        writeParcelToFileLocked(p, mStatsFile, sync);
    }

    void writeHistoryLocked(boolean sync) {
        if (mBatteryStatsHistory.getActiveFile() == null) {
            Slog.w(TAG,
                    "writeHistoryLocked: no history file associated with this instance");
            return;
        }

        if (mShuttingDown) {
            return;
        }

        Parcel p = Parcel.obtain();
        final long start = SystemClock.uptimeMillis();
        writeHistoryBuffer(p, true);
        if (DEBUG) {
            Slog.d(TAG, "writeHistoryBuffer duration ms:"
                    + (SystemClock.uptimeMillis() - start) + " bytes:" + p.dataSize());
        }
        writeParcelToFileLocked(p, mBatteryStatsHistory.getActiveFile(), sync);
    }

    void writeParcelToFileLocked(Parcel p, AtomicFile file, boolean sync) {
        if (sync) {
            commitPendingDataToDisk(p, file);
        } else {
            BackgroundThread.getHandler().post(new Runnable() {
                @Override public void run() {
                    commitPendingDataToDisk(p, file);
                }
            });
        }
    }

    private void commitPendingDataToDisk(Parcel p, AtomicFile file) {
        mWriteLock.lock();
        FileOutputStream fos = null;
        try {
            final long startTimeMs = SystemClock.uptimeMillis();
            fos = file.startWrite();
            fos.write(p.marshall());
            fos.flush();
            file.finishWrite(fos);
            if (DEBUG) {
                Slog.d(TAG, "commitPendingDataToDisk file:" + file.getBaseFile().getPath()
                        + " duration ms:" + (SystemClock.uptimeMillis() - startTimeMs)
                        + " bytes:" + p.dataSize());
            }
            com.android.internal.logging.EventLogTags.writeCommitSysConfigFile(
                    "batterystats", SystemClock.uptimeMillis() - startTimeMs);
        } catch (IOException e) {
            Slog.w(TAG, "Error writing battery statistics", e);
            file.failWrite(fos);
        } finally {
            p.recycle();
            mWriteLock.unlock();
        }
    }

    @UnsupportedAppUsage
    @GuardedBy("this")
    public void readLocked() {
        if (mDailyFile != null) {
            readDailyStatsLocked();
        }

        if (mStatsFile == null) {
            Slog.w(TAG, "readLocked: no file associated with this instance");
            return;
        }

        final AtomicFile activeHistoryFile = mBatteryStatsHistory.getActiveFile();
        if (activeHistoryFile == null) {
            Slog.w(TAG,
                    "readLocked: no history file associated with this instance");
            return;
        }

        mUidStats.clear();

        Parcel stats = Parcel.obtain();
        try {
            final long start = SystemClock.uptimeMillis();
            if (mStatsFile.exists()) {
                byte[] raw = mStatsFile.readFully();
                stats.unmarshall(raw, 0, raw.length);
                stats.setDataPosition(0);
                readSummaryFromParcel(stats);
                if (DEBUG) {
                    Slog.d(TAG, "readLocked stats file:" + mStatsFile.getBaseFile().getPath()
                            + " bytes:" + raw.length + " takes ms:" + (SystemClock.uptimeMillis()
                            - start));
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error reading battery statistics", e);
            resetAllStatsLocked(SystemClock.uptimeMillis(), SystemClock.elapsedRealtime(),
                    RESET_REASON_CORRUPT_FILE);
        } finally {
            stats.recycle();
        }

        Parcel history = Parcel.obtain();
        try {
            final long start = SystemClock.uptimeMillis();
            if (activeHistoryFile.exists()) {
                byte[] raw = activeHistoryFile.readFully();
                if (raw.length > 0) {
                    history.unmarshall(raw, 0, raw.length);
                    history.setDataPosition(0);
                    readHistoryBuffer(history);
                }
                if (DEBUG) {
                    Slog.d(TAG, "readLocked history file::"
                            + activeHistoryFile.getBaseFile().getPath()
                            + " bytes:" + raw.length + " takes ms:" + (SystemClock.uptimeMillis()
                            - start));
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error reading battery history", e);
            clearHistoryLocked();
            mBatteryStatsHistory.resetAllFiles();
        } finally {
            history.recycle();
        }

        mEndPlatformVersion = Build.ID;

        if (mHistoryBuffer.dataPosition() > 0
                || mBatteryStatsHistory.getFilesNumbers().size() > 1) {
            mRecordingHistory = true;
            final long elapsedRealtimeMs = mClock.elapsedRealtime();
            final long uptimeMs = mClock.uptimeMillis();
            addHistoryBufferLocked(elapsedRealtimeMs, HistoryItem.CMD_START, mHistoryCur);
            startRecordingHistory(elapsedRealtimeMs, uptimeMs, false);
        }

        recordDailyStatsIfNeededLocked(false, mClock.currentTimeMillis());
    }

    public int describeContents() {
        return 0;
    }

    @GuardedBy("this")
    void  readHistoryBuffer(Parcel in) throws ParcelFormatException {
        final int version = in.readInt();
        if (version != VERSION) {
            Slog.w("BatteryStats", "readHistoryBuffer: version got " + version
                    + ", expected " + VERSION + "; erasing old stats");
            return;
        }

        final long historyBaseTime = in.readLong();

        mHistoryBuffer.setDataSize(0);
        mHistoryBuffer.setDataPosition(0);

        int bufSize = in.readInt();
        int curPos = in.dataPosition();
        if (bufSize >= (mConstants.MAX_HISTORY_BUFFER*100)) {
            throw new ParcelFormatException("File corrupt: history data buffer too large " +
                    bufSize);
        } else if ((bufSize&~3) != bufSize) {
            throw new ParcelFormatException("File corrupt: history data buffer not aligned " +
                    bufSize);
        } else {
            if (DEBUG_HISTORY) Slog.i(TAG, "***************** READING NEW HISTORY: " + bufSize
                    + " bytes at " + curPos);
            mHistoryBuffer.appendFrom(in, curPos, bufSize);
            in.setDataPosition(curPos + bufSize);
        }

        if (DEBUG_HISTORY) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("****************** OLD mHistoryBaseTimeMs: ");
            TimeUtils.formatDuration(mHistoryBaseTimeMs, sb);
            Slog.i(TAG, sb.toString());
        }
        mHistoryBaseTimeMs = historyBaseTime;
        if (DEBUG_HISTORY) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("****************** NEW mHistoryBaseTimeMs: ");
            TimeUtils.formatDuration(mHistoryBaseTimeMs, sb);
            Slog.i(TAG, sb.toString());
        }

        // We are just arbitrarily going to insert 1 minute from the sample of
        // the last run until samples in this run.
        if (mHistoryBaseTimeMs > 0) {
            long oldnow = mClock.elapsedRealtime();
            mHistoryBaseTimeMs = mHistoryBaseTimeMs - oldnow + 1;
            if (DEBUG_HISTORY) {
                StringBuilder sb = new StringBuilder(128);
                sb.append("****************** ADJUSTED mHistoryBaseTimeMs: ");
                TimeUtils.formatDuration(mHistoryBaseTimeMs, sb);
                Slog.i(TAG, sb.toString());
            }
        }
    }

    void writeHistoryBuffer(Parcel out, boolean inclData) {
        if (DEBUG_HISTORY) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("****************** WRITING mHistoryBaseTimeMs: ");
            TimeUtils.formatDuration(mHistoryBaseTimeMs, sb);
            sb.append(" mLastHistoryElapsedRealtimeMs: ");
            TimeUtils.formatDuration(mLastHistoryElapsedRealtimeMs, sb);
            Slog.i(TAG, sb.toString());
        }
        out.writeInt(VERSION);
        out.writeLong(mHistoryBaseTimeMs + mLastHistoryElapsedRealtimeMs);
        if (!inclData) {
            out.writeInt(0);
            out.writeInt(0);
            return;
        }

        out.writeInt(mHistoryBuffer.dataSize());
        if (DEBUG_HISTORY) Slog.i(TAG, "***************** WRITING HISTORY: "
                + mHistoryBuffer.dataSize() + " bytes at " + out.dataPosition());
        out.appendFrom(mHistoryBuffer, 0, mHistoryBuffer.dataSize());
    }

    @GuardedBy("this")
    public void readSummaryFromParcel(Parcel in) throws ParcelFormatException {
        final int version = in.readInt();

        if (version != VERSION) {
            Slog.w("BatteryStats", "readFromParcel: version got " + version
                + ", expected " + VERSION + "; erasing old stats");
            return;
        }

        boolean inclHistory = in.readBoolean();
        if (inclHistory) {
            readHistoryBuffer(in);
            mBatteryStatsHistory.readFromParcel(in);
        }

        mHistoryTagPool.clear();
        mNextHistoryTagIdx = 0;
        mNumHistoryTagChars = 0;

        int numTags = in.readInt();
        for (int i=0; i<numTags; i++) {
            int idx = in.readInt();
            String str = in.readString();
            int uid = in.readInt();
            HistoryTag tag = new HistoryTag();
            tag.string = str;
            tag.uid = uid;
            tag.poolIdx = idx;
            mHistoryTagPool.put(tag, idx);
            if (idx >= mNextHistoryTagIdx) {
                mNextHistoryTagIdx = idx+1;
            }
            mNumHistoryTagChars += tag.string.length() + 1;
        }

        mStartCount = in.readInt();
        mUptimeUs = in.readLong();
        mRealtimeUs = in.readLong();
        mStartClockTimeMs = in.readLong();
        mStartPlatformVersion = in.readString();
        mEndPlatformVersion = in.readString();
        mOnBatteryTimeBase.readSummaryFromParcel(in);
        mOnBatteryScreenOffTimeBase.readSummaryFromParcel(in);
        mDischargeUnplugLevel = in.readInt();
        mDischargePlugLevel = in.readInt();
        mDischargeCurrentLevel = in.readInt();
        mCurrentBatteryLevel = in.readInt();
        mEstimatedBatteryCapacityMah = in.readInt();
        mLastLearnedBatteryCapacityUah = in.readInt();
        mMinLearnedBatteryCapacityUah = in.readInt();
        mMaxLearnedBatteryCapacityUah = in.readInt();
        mLowDischargeAmountSinceCharge = in.readInt();
        mHighDischargeAmountSinceCharge = in.readInt();
        mDischargeAmountScreenOnSinceCharge = in.readInt();
        mDischargeAmountScreenOffSinceCharge = in.readInt();
        mDischargeAmountScreenDozeSinceCharge = in.readInt();
        mDischargeStepTracker.readFromParcel(in);
        mChargeStepTracker.readFromParcel(in);
        mDailyDischargeStepTracker.readFromParcel(in);
        mDailyChargeStepTracker.readFromParcel(in);
        mDischargeCounter.readSummaryFromParcelLocked(in);
        mDischargeScreenOffCounter.readSummaryFromParcelLocked(in);
        mDischargeScreenDozeCounter.readSummaryFromParcelLocked(in);
        mDischargeLightDozeCounter.readSummaryFromParcelLocked(in);
        mDischargeDeepDozeCounter.readSummaryFromParcelLocked(in);
        int NPKG = in.readInt();
        if (NPKG > 0) {
            mDailyPackageChanges = new ArrayList<>(NPKG);
            while (NPKG > 0) {
                NPKG--;
                PackageChange pc = new PackageChange();
                pc.mPackageName = in.readString();
                pc.mUpdate = in.readInt() != 0;
                pc.mVersionCode = in.readLong();
                mDailyPackageChanges.add(pc);
            }
        } else {
            mDailyPackageChanges = null;
        }
        mDailyStartTimeMs = in.readLong();
        mNextMinDailyDeadlineMs = in.readLong();
        mNextMaxDailyDeadlineMs = in.readLong();
        mBatteryTimeToFullSeconds = in.readLong();

        final MeasuredEnergyStats.Config config = MeasuredEnergyStats.Config.createFromParcel(in);
        final MeasuredEnergyStats measuredEnergyStats =
                MeasuredEnergyStats.createAndReadSummaryFromParcel(mMeasuredEnergyStatsConfig, in);
        if (config != null && Arrays.equals(config.getStateNames(),
                getBatteryConsumerProcessStateNames())) {
            /**
             * WARNING: Supported buckets may have changed across boots. Bucket mismatch is handled
             *          later when {@link #initMeasuredEnergyStatsLocked} is called.
             */
            mMeasuredEnergyStatsConfig = config;
            mGlobalMeasuredEnergyStats = measuredEnergyStats;
        }

        mStartCount++;

        mScreenState = Display.STATE_UNKNOWN;
        mScreenOnTimer.readSummaryFromParcelLocked(in);
        mScreenDozeTimer.readSummaryFromParcelLocked(in);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i].readSummaryFromParcelLocked(in);
        }
        mInteractive = false;
        mInteractiveTimer.readSummaryFromParcelLocked(in);
        mPhoneOn = false;
        mPowerSaveModeEnabledTimer.readSummaryFromParcelLocked(in);
        mLongestLightIdleTimeMs = in.readLong();
        mLongestFullIdleTimeMs = in.readLong();
        mDeviceIdleModeLightTimer.readSummaryFromParcelLocked(in);
        mDeviceIdleModeFullTimer.readSummaryFromParcelLocked(in);
        mDeviceLightIdlingTimer.readSummaryFromParcelLocked(in);
        mDeviceIdlingTimer.readSummaryFromParcelLocked(in);
        mPhoneOnTimer.readSummaryFromParcelLocked(in);
        for (int i = 0; i < CellSignalStrength.getNumSignalStrengthLevels(); i++) {
            mPhoneSignalStrengthsTimer[i].readSummaryFromParcelLocked(in);
        }
        mPhoneSignalScanningTimer.readSummaryFromParcelLocked(in);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i].readSummaryFromParcelLocked(in);
        }
        for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
            mNetworkByteActivityCounters[i].readSummaryFromParcelLocked(in);
            mNetworkPacketActivityCounters[i].readSummaryFromParcelLocked(in);
        }

        final int numRat = in.readInt();
        for (int i = 0; i < numRat; i++) {
            if (in.readInt() == 0) continue;
            getRatBatteryStatsLocked(i).readSummaryFromParcel(in);
        }

        mMobileRadioPowerState = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
        mMobileRadioActiveTimer.readSummaryFromParcelLocked(in);
        mMobileRadioActivePerAppTimer.readSummaryFromParcelLocked(in);
        mMobileRadioActiveAdjustedTime.readSummaryFromParcelLocked(in);
        mMobileRadioActiveUnknownTime.readSummaryFromParcelLocked(in);
        mMobileRadioActiveUnknownCount.readSummaryFromParcelLocked(in);
        mWifiMulticastWakelockTimer.readSummaryFromParcelLocked(in);
        mWifiRadioPowerState = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
        mWifiOn = false;
        mWifiOnTimer.readSummaryFromParcelLocked(in);
        mGlobalWifiRunning = false;
        mGlobalWifiRunningTimer.readSummaryFromParcelLocked(in);
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            mWifiStateTimer[i].readSummaryFromParcelLocked(in);
        }
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            mWifiSupplStateTimer[i].readSummaryFromParcelLocked(in);
        }
        for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            mWifiSignalStrengthsTimer[i].readSummaryFromParcelLocked(in);
        }
        mWifiActiveTimer.readSummaryFromParcelLocked(in);
        mWifiActivity.readSummaryFromParcel(in);
        for (int i=0; i<mGpsSignalQualityTimer.length; i++) {
            mGpsSignalQualityTimer[i].readSummaryFromParcelLocked(in);
        }
        mBluetoothActivity.readSummaryFromParcel(in);
        mModemActivity.readSummaryFromParcel(in);
        mHasWifiReporting = in.readInt() != 0;
        mHasBluetoothReporting = in.readInt() != 0;
        mHasModemReporting = in.readInt() != 0;

        mNumConnectivityChange = in.readInt();
        mFlashlightOnNesting = 0;
        mFlashlightOnTimer.readSummaryFromParcelLocked(in);
        mCameraOnNesting = 0;
        mCameraOnTimer.readSummaryFromParcelLocked(in);
        mBluetoothScanNesting = 0;
        mBluetoothScanTimer.readSummaryFromParcelLocked(in);

        int NRPMS = in.readInt();
        if (NRPMS > 10000) {
            throw new ParcelFormatException("File corrupt: too many rpm stats " + NRPMS);
        }
        for (int irpm = 0; irpm < NRPMS; irpm++) {
            if (in.readInt() != 0) {
                String rpmName = in.readString();
                getRpmTimerLocked(rpmName).readSummaryFromParcelLocked(in);
            }
        }
        int NSORPMS = in.readInt();
        if (NSORPMS > 10000) {
            throw new ParcelFormatException("File corrupt: too many screen-off rpm stats " + NSORPMS);
        }
        for (int irpm = 0; irpm < NSORPMS; irpm++) {
            if (in.readInt() != 0) {
                String rpmName = in.readString();
                getScreenOffRpmTimerLocked(rpmName).readSummaryFromParcelLocked(in);
            }
        }
        int NKW = in.readInt();
        if (NKW > 10000) {
            throw new ParcelFormatException("File corrupt: too many kernel wake locks " + NKW);
        }
        for (int ikw = 0; ikw < NKW; ikw++) {
            if (in.readInt() != 0) {
                String kwltName = in.readString();
                getKernelWakelockTimerLocked(kwltName).readSummaryFromParcelLocked(in);
            }
        }

        int NWR = in.readInt();
        if (NWR > 10000) {
            throw new ParcelFormatException("File corrupt: too many wakeup reasons " + NWR);
        }
        for (int iwr = 0; iwr < NWR; iwr++) {
            if (in.readInt() != 0) {
                String reasonName = in.readString();
                getWakeupReasonTimerLocked(reasonName).readSummaryFromParcelLocked(in);
            }
        }

        int NMS = in.readInt();
        for (int ims = 0; ims < NMS; ims++) {
            if (in.readInt() != 0) {
                long kmstName = in.readLong();
                getKernelMemoryTimerLocked(kmstName).readSummaryFromParcelLocked(in);
            }
        }

        final int NU = in.readInt();
        if (NU > 10000) {
            throw new ParcelFormatException("File corrupt: too many uids " + NU);
        }
        final long elapsedRealtimeMs = mClock.elapsedRealtime();
        final long uptimeMs = mClock.uptimeMillis();
        for (int iu = 0; iu < NU; iu++) {
            int uid = in.readInt();
            Uid u = new Uid(this, uid, elapsedRealtimeMs, uptimeMs);
            mUidStats.put(uid, u);

            u.mOnBatteryBackgroundTimeBase.readSummaryFromParcel(in);
            u.mOnBatteryScreenOffBackgroundTimeBase.readSummaryFromParcel(in);

            u.mWifiRunning = false;
            if (in.readInt() != 0) {
                u.mWifiRunningTimer.readSummaryFromParcelLocked(in);
            }
            u.mFullWifiLockOut = false;
            if (in.readInt() != 0) {
                u.mFullWifiLockTimer.readSummaryFromParcelLocked(in);
            }
            u.mWifiScanStarted = false;
            if (in.readInt() != 0) {
                u.mWifiScanTimer.readSummaryFromParcelLocked(in);
            }
            u.mWifiBatchedScanBinStarted = Uid.NO_BATCHED_SCAN_STARTED;
            for (int i = 0; i < Uid.NUM_WIFI_BATCHED_SCAN_BINS; i++) {
                if (in.readInt() != 0) {
                    u.makeWifiBatchedScanBin(i, null);
                    u.mWifiBatchedScanTimer[i].readSummaryFromParcelLocked(in);
                }
            }
            u.mWifiMulticastWakelockCount = 0;
            if (in.readInt() != 0) {
                u.mWifiMulticastTimer.readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createAudioTurnedOnTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createVideoTurnedOnTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createFlashlightTurnedOnTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createCameraTurnedOnTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createForegroundActivityTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createForegroundServiceTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createAggregatedPartialWakelockTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createBluetoothScanTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createBluetoothUnoptimizedScanTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createBluetoothScanResultCounterLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createBluetoothScanResultBgCounterLocked().readSummaryFromParcelLocked(in);
            }
            u.mProcessState = Uid.PROCESS_STATE_NONEXISTENT;
            for (int i = 0; i < NUM_PROCESS_STATE; i++) {
                if (in.readInt() != 0) {
                    u.makeProcessState(i, null);
                    u.mProcessStateTimer[i].readSummaryFromParcelLocked(in);
                }
            }
            if (in.readInt() != 0) {
                u.createVibratorOnTimerLocked().readSummaryFromParcelLocked(in);
            }

            if (in.readInt() != 0) {
                if (u.mUserActivityCounters == null) {
                    u.initUserActivityLocked();
                }
                for (int i=0; i<Uid.NUM_USER_ACTIVITY_TYPES; i++) {
                    u.mUserActivityCounters[i].readSummaryFromParcelLocked(in);
                }
            }

            if (in.readInt() != 0) {
                u.ensureNetworkActivityLocked();
                for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
                    u.mNetworkByteActivityCounters[i].readSummaryFromParcelLocked(in);
                    u.mNetworkPacketActivityCounters[i].readSummaryFromParcelLocked(in);
                }
                if (in.readBoolean()) {
                    u.mMobileRadioActiveTime = TimeMultiStateCounter.readFromParcel(in,
                            mOnBatteryTimeBase, BatteryConsumer.PROCESS_STATE_COUNT,
                            elapsedRealtimeMs);
                }
                u.mMobileRadioActiveCount.readSummaryFromParcelLocked(in);
            }

            u.mUserCpuTime.readSummaryFromParcelLocked(in);
            u.mSystemCpuTime.readSummaryFromParcelLocked(in);

            if (in.readInt() != 0) {
                final int numClusters = in.readInt();
                if (mPowerProfile != null && mPowerProfile.getNumCpuClusters() != numClusters) {
                    throw new ParcelFormatException("Incompatible cpu cluster arrangement");
                }
                detachIfNotNull(u.mCpuClusterSpeedTimesUs);
                u.mCpuClusterSpeedTimesUs = new LongSamplingCounter[numClusters][];
                for (int cluster = 0; cluster < numClusters; cluster++) {
                    if (in.readInt() != 0) {
                        final int NSB = in.readInt();
                        if (mPowerProfile != null &&
                                mPowerProfile.getNumSpeedStepsInCpuCluster(cluster) != NSB) {
                            throw new ParcelFormatException("File corrupt: too many speed bins " +
                                    NSB);
                        }

                        u.mCpuClusterSpeedTimesUs[cluster] = new LongSamplingCounter[NSB];
                        for (int speed = 0; speed < NSB; speed++) {
                            if (in.readInt() != 0) {
                                u.mCpuClusterSpeedTimesUs[cluster][speed] = new LongSamplingCounter(
                                        mOnBatteryTimeBase);
                                u.mCpuClusterSpeedTimesUs[cluster][speed].readSummaryFromParcelLocked(in);
                            }
                        }
                    } else {
                        u.mCpuClusterSpeedTimesUs[cluster] = null;
                    }
                }
            } else {
                detachIfNotNull(u.mCpuClusterSpeedTimesUs);
                u.mCpuClusterSpeedTimesUs = null;
            }

            detachIfNotNull(u.mCpuFreqTimeMs);
            u.mCpuFreqTimeMs = LongSamplingCounterArray.readSummaryFromParcelLocked(
                    in, mOnBatteryTimeBase);
            detachIfNotNull(u.mScreenOffCpuFreqTimeMs);
            u.mScreenOffCpuFreqTimeMs = LongSamplingCounterArray.readSummaryFromParcelLocked(
                    in, mOnBatteryScreenOffTimeBase);

            int stateCount = in.readInt();
            if (stateCount != 0) {
                u.mCpuActiveTimeMs = TimeMultiStateCounter.readFromParcel(in,
                        mOnBatteryTimeBase, BatteryConsumer.PROCESS_STATE_COUNT,
                        mClock.elapsedRealtime());
            }
            u.mCpuClusterTimesMs.readSummaryFromParcelLocked(in);

            detachIfNotNull(u.mProcStateTimeMs);
            u.mProcStateTimeMs = null;

            stateCount = in.readInt();
            if (stateCount != 0) {
                detachIfNotNull(u.mProcStateTimeMs);
                u.mProcStateTimeMs = TimeInFreqMultiStateCounter.readFromParcel(in,
                        mOnBatteryTimeBase, PROC_STATE_TIME_COUNTER_STATE_COUNT,
                        getCpuFreqCount(), mClock.elapsedRealtime());
            }

            detachIfNotNull(u.mProcStateScreenOffTimeMs);
            u.mProcStateScreenOffTimeMs = null;

            stateCount = in.readInt();
            if (stateCount != 0) {
                detachIfNotNull(u.mProcStateScreenOffTimeMs);
                u.mProcStateScreenOffTimeMs = TimeInFreqMultiStateCounter.readFromParcel(in,
                        mOnBatteryScreenOffTimeBase, PROC_STATE_TIME_COUNTER_STATE_COUNT,
                        getCpuFreqCount(), mClock.elapsedRealtime());
            }

            if (in.readInt() != 0) {
                detachIfNotNull(u.mMobileRadioApWakeupCount);
                u.mMobileRadioApWakeupCount = new LongSamplingCounter(mOnBatteryTimeBase);
                u.mMobileRadioApWakeupCount.readSummaryFromParcelLocked(in);
            } else {
                detachIfNotNull(u.mMobileRadioApWakeupCount);
                u.mMobileRadioApWakeupCount = null;
            }

            if (in.readInt() != 0) {
                detachIfNotNull(u.mWifiRadioApWakeupCount);
                u.mWifiRadioApWakeupCount = new LongSamplingCounter(mOnBatteryTimeBase);
                u.mWifiRadioApWakeupCount.readSummaryFromParcelLocked(in);
            } else {
                detachIfNotNull(u.mWifiRadioApWakeupCount);
                u.mWifiRadioApWakeupCount = null;
            }

            u.mUidMeasuredEnergyStats = MeasuredEnergyStats.createAndReadSummaryFromParcel(
                    mMeasuredEnergyStatsConfig, in);

            int NW = in.readInt();
            if (NW > (MAX_WAKELOCKS_PER_UID+1)) {
                throw new ParcelFormatException("File corrupt: too many wake locks " + NW);
            }
            for (int iw = 0; iw < NW; iw++) {
                String wlName = in.readString();
                u.readWakeSummaryFromParcelLocked(wlName, in);
            }

            int NS = in.readInt();
            if (NS > (MAX_WAKELOCKS_PER_UID+1)) {
                throw new ParcelFormatException("File corrupt: too many syncs " + NS);
            }
            for (int is = 0; is < NS; is++) {
                String name = in.readString();
                u.readSyncSummaryFromParcelLocked(name, in);
            }

            int NJ = in.readInt();
            if (NJ > (MAX_WAKELOCKS_PER_UID+1)) {
                throw new ParcelFormatException("File corrupt: too many job timers " + NJ);
            }
            for (int ij = 0; ij < NJ; ij++) {
                String name = in.readString();
                u.readJobSummaryFromParcelLocked(name, in);
            }

            u.readJobCompletionsFromParcelLocked(in);

            u.mJobsDeferredEventCount.readSummaryFromParcelLocked(in);
            u.mJobsDeferredCount.readSummaryFromParcelLocked(in);
            u.mJobsFreshnessTimeMs.readSummaryFromParcelLocked(in);
            detachIfNotNull(u.mJobsFreshnessBuckets);
            for (int i = 0; i < JOB_FRESHNESS_BUCKETS.length; i++) {
                if (in.readInt() != 0) {
                    u.mJobsFreshnessBuckets[i] = new Counter(u.mBsi.mOnBatteryTimeBase);
                    u.mJobsFreshnessBuckets[i].readSummaryFromParcelLocked(in);
                }
            }

            int NP = in.readInt();
            if (NP > 1000) {
                throw new ParcelFormatException("File corrupt: too many sensors " + NP);
            }
            for (int is = 0; is < NP; is++) {
                int seNumber = in.readInt();
                if (in.readInt() != 0) {
                    u.getSensorTimerLocked(seNumber, true).readSummaryFromParcelLocked(in);
                }
            }

            NP = in.readInt();
            if (NP > 1000) {
                throw new ParcelFormatException("File corrupt: too many processes " + NP);
            }
            for (int ip = 0; ip < NP; ip++) {
                String procName = in.readString();
                Uid.Proc p = u.getProcessStatsLocked(procName);
                p.mUserTimeMs = in.readLong();
                p.mSystemTimeMs = in.readLong();
                p.mForegroundTimeMs = in.readLong();
                p.mStarts = in.readInt();
                p.mNumCrashes = in.readInt();
                p.mNumAnrs = in.readInt();
                p.readExcessivePowerFromParcelLocked(in);
            }

            NP = in.readInt();
            if (NP > 10000) {
                throw new ParcelFormatException("File corrupt: too many packages " + NP);
            }
            for (int ip = 0; ip < NP; ip++) {
                String pkgName = in.readString();
                detachIfNotNull(u.mPackageStats.get(pkgName));
                Uid.Pkg p = u.getPackageStatsLocked(pkgName);
                final int NWA = in.readInt();
                if (NWA > 10000) {
                    throw new ParcelFormatException("File corrupt: too many wakeup alarms " + NWA);
                }
                p.mWakeupAlarms.clear();
                for (int iwa = 0; iwa < NWA; iwa++) {
                    String tag = in.readString();
                    Counter c = new Counter(mOnBatteryScreenOffTimeBase);
                    c.readSummaryFromParcelLocked(in);
                    p.mWakeupAlarms.put(tag, c);
                }
                NS = in.readInt();
                if (NS > 10000) {
                    throw new ParcelFormatException("File corrupt: too many services " + NS);
                }
                for (int is = 0; is < NS; is++) {
                    String servName = in.readString();
                    Uid.Pkg.Serv s = u.getServiceStatsLocked(pkgName, servName);
                    s.mStartTimeMs = in.readLong();
                    s.mStarts = in.readInt();
                    s.mLaunches = in.readInt();
                }
            }
        }

        mBinderThreadCpuTimesUs =
                LongSamplingCounterArray.readSummaryFromParcelLocked(in, mOnBatteryTimeBase);
    }

    /**
     * Writes a summary of the statistics to a Parcel, in a format suitable to be written to
     * disk.  This format does not allow a lossless round-trip.
     *
     * @param out the Parcel to be written to.
     */
    @GuardedBy("this")
    public void writeSummaryToParcel(Parcel out, boolean inclHistory) {
        pullPendingStateUpdatesLocked();

        // Pull the clock time.  This may update the time and make a new history entry
        // if we had originally pulled a time before the RTC was set.
        getStartClockTime();

        final long nowUptime = mClock.uptimeMillis() * 1000;
        final long nowRealtime = mClock.elapsedRealtime() * 1000;

        out.writeInt(VERSION);

        out.writeBoolean(inclHistory);
        if (inclHistory) {
            writeHistoryBuffer(out, true);
            mBatteryStatsHistory.writeToParcel(out);
        }

        out.writeInt(mHistoryTagPool.size());
        for (HashMap.Entry<HistoryTag, Integer> ent : mHistoryTagPool.entrySet()) {
            HistoryTag tag = ent.getKey();
            out.writeInt(ent.getValue());
            out.writeString(tag.string);
            out.writeInt(tag.uid);
        }

        out.writeInt(mStartCount);
        out.writeLong(computeUptime(nowUptime, STATS_SINCE_CHARGED));
        out.writeLong(computeRealtime(nowRealtime, STATS_SINCE_CHARGED));
        out.writeLong(mStartClockTimeMs);
        out.writeString(mStartPlatformVersion);
        out.writeString(mEndPlatformVersion);
        mOnBatteryTimeBase.writeSummaryToParcel(out, nowUptime, nowRealtime);
        mOnBatteryScreenOffTimeBase.writeSummaryToParcel(out, nowUptime, nowRealtime);
        out.writeInt(mDischargeUnplugLevel);
        out.writeInt(mDischargePlugLevel);
        out.writeInt(mDischargeCurrentLevel);
        out.writeInt(mCurrentBatteryLevel);
        out.writeInt(mEstimatedBatteryCapacityMah);
        out.writeInt(mLastLearnedBatteryCapacityUah);
        out.writeInt(mMinLearnedBatteryCapacityUah);
        out.writeInt(mMaxLearnedBatteryCapacityUah);
        out.writeInt(getLowDischargeAmountSinceCharge());
        out.writeInt(getHighDischargeAmountSinceCharge());
        out.writeInt(getDischargeAmountScreenOnSinceCharge());
        out.writeInt(getDischargeAmountScreenOffSinceCharge());
        out.writeInt(getDischargeAmountScreenDozeSinceCharge());
        mDischargeStepTracker.writeToParcel(out);
        mChargeStepTracker.writeToParcel(out);
        mDailyDischargeStepTracker.writeToParcel(out);
        mDailyChargeStepTracker.writeToParcel(out);
        mDischargeCounter.writeSummaryFromParcelLocked(out);
        mDischargeScreenOffCounter.writeSummaryFromParcelLocked(out);
        mDischargeScreenDozeCounter.writeSummaryFromParcelLocked(out);
        mDischargeLightDozeCounter.writeSummaryFromParcelLocked(out);
        mDischargeDeepDozeCounter.writeSummaryFromParcelLocked(out);
        if (mDailyPackageChanges != null) {
            final int NPKG = mDailyPackageChanges.size();
            out.writeInt(NPKG);
            for (int i=0; i<NPKG; i++) {
                PackageChange pc = mDailyPackageChanges.get(i);
                out.writeString(pc.mPackageName);
                out.writeInt(pc.mUpdate ? 1 : 0);
                out.writeLong(pc.mVersionCode);
            }
        } else {
            out.writeInt(0);
        }
        out.writeLong(mDailyStartTimeMs);
        out.writeLong(mNextMinDailyDeadlineMs);
        out.writeLong(mNextMaxDailyDeadlineMs);
        out.writeLong(mBatteryTimeToFullSeconds);

        MeasuredEnergyStats.Config.writeToParcel(mMeasuredEnergyStatsConfig, out);
        MeasuredEnergyStats.writeSummaryToParcel(mGlobalMeasuredEnergyStats, out);

        mScreenOnTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        mScreenDozeTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i].writeSummaryFromParcelLocked(out, nowRealtime);
        }
        mInteractiveTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        mPowerSaveModeEnabledTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        out.writeLong(mLongestLightIdleTimeMs);
        out.writeLong(mLongestFullIdleTimeMs);
        mDeviceIdleModeLightTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        mDeviceIdleModeFullTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        mDeviceLightIdlingTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        mDeviceIdlingTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        mPhoneOnTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        for (int i = 0; i < CellSignalStrength.getNumSignalStrengthLevels(); i++) {
            mPhoneSignalStrengthsTimer[i].writeSummaryFromParcelLocked(out, nowRealtime);
        }
        mPhoneSignalScanningTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i].writeSummaryFromParcelLocked(out, nowRealtime);
        }
        for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
            mNetworkByteActivityCounters[i].writeSummaryFromParcelLocked(out);
            mNetworkPacketActivityCounters[i].writeSummaryFromParcelLocked(out);
        }
        final int numRat = mPerRatBatteryStats.length;
        out.writeInt(numRat);
        for (int i = 0; i < numRat; i++) {
            final RadioAccessTechnologyBatteryStats ratStat = mPerRatBatteryStats[i];
            if (ratStat == null) {
                out.writeInt(0);
                continue;
            }
            out.writeInt(1);
            ratStat.writeSummaryToParcel(out, nowRealtime);
        }
        mMobileRadioActiveTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        mMobileRadioActivePerAppTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        mMobileRadioActiveAdjustedTime.writeSummaryFromParcelLocked(out);
        mMobileRadioActiveUnknownTime.writeSummaryFromParcelLocked(out);
        mMobileRadioActiveUnknownCount.writeSummaryFromParcelLocked(out);
        mWifiMulticastWakelockTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        mWifiOnTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        mGlobalWifiRunningTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            mWifiStateTimer[i].writeSummaryFromParcelLocked(out, nowRealtime);
        }
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            mWifiSupplStateTimer[i].writeSummaryFromParcelLocked(out, nowRealtime);
        }
        for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            mWifiSignalStrengthsTimer[i].writeSummaryFromParcelLocked(out, nowRealtime);
        }
        mWifiActiveTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        mWifiActivity.writeSummaryToParcel(out);
        for (int i=0; i< mGpsSignalQualityTimer.length; i++) {
            mGpsSignalQualityTimer[i].writeSummaryFromParcelLocked(out, nowRealtime);
        }
        mBluetoothActivity.writeSummaryToParcel(out);
        mModemActivity.writeSummaryToParcel(out);
        out.writeInt(mHasWifiReporting ? 1 : 0);
        out.writeInt(mHasBluetoothReporting ? 1 : 0);
        out.writeInt(mHasModemReporting ? 1 : 0);

        out.writeInt(mNumConnectivityChange);
        mFlashlightOnTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        mCameraOnTimer.writeSummaryFromParcelLocked(out, nowRealtime);
        mBluetoothScanTimer.writeSummaryFromParcelLocked(out, nowRealtime);

        out.writeInt(mRpmStats.size());
        for (Map.Entry<String, SamplingTimer> ent : mRpmStats.entrySet()) {
            Timer rpmt = ent.getValue();
            if (rpmt != null) {
                out.writeInt(1);
                out.writeString(ent.getKey());
                rpmt.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
        }
        out.writeInt(mScreenOffRpmStats.size());
        for (Map.Entry<String, SamplingTimer> ent : mScreenOffRpmStats.entrySet()) {
            Timer rpmt = ent.getValue();
            if (rpmt != null) {
                out.writeInt(1);
                out.writeString(ent.getKey());
                rpmt.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
        }

        out.writeInt(mKernelWakelockStats.size());
        for (Map.Entry<String, SamplingTimer> ent : mKernelWakelockStats.entrySet()) {
            Timer kwlt = ent.getValue();
            if (kwlt != null) {
                out.writeInt(1);
                out.writeString(ent.getKey());
                kwlt.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
        }

        out.writeInt(mWakeupReasonStats.size());
        for (Map.Entry<String, SamplingTimer> ent : mWakeupReasonStats.entrySet()) {
            SamplingTimer timer = ent.getValue();
            if (timer != null) {
                out.writeInt(1);
                out.writeString(ent.getKey());
                timer.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
        }

        out.writeInt(mKernelMemoryStats.size());
        for (int i = 0; i < mKernelMemoryStats.size(); i++) {
            Timer kmt = mKernelMemoryStats.valueAt(i);
            if (kmt != null) {
                out.writeInt(1);
                out.writeLong(mKernelMemoryStats.keyAt(i));
                kmt.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
        }

        final int NU = mUidStats.size();
        out.writeInt(NU);
        for (int iu = 0; iu < NU; iu++) {
            out.writeInt(mUidStats.keyAt(iu));
            Uid u = mUidStats.valueAt(iu);

            u.mOnBatteryBackgroundTimeBase.writeSummaryToParcel(out, nowUptime, nowRealtime);
            u.mOnBatteryScreenOffBackgroundTimeBase.writeSummaryToParcel(out, nowUptime,
                    nowRealtime);

            if (u.mWifiRunningTimer != null) {
                out.writeInt(1);
                u.mWifiRunningTimer.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
            if (u.mFullWifiLockTimer != null) {
                out.writeInt(1);
                u.mFullWifiLockTimer.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
            if (u.mWifiScanTimer != null) {
                out.writeInt(1);
                u.mWifiScanTimer.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
            for (int i = 0; i < Uid.NUM_WIFI_BATCHED_SCAN_BINS; i++) {
                if (u.mWifiBatchedScanTimer[i] != null) {
                    out.writeInt(1);
                    u.mWifiBatchedScanTimer[i].writeSummaryFromParcelLocked(out, nowRealtime);
                } else {
                    out.writeInt(0);
                }
            }
            if (u.mWifiMulticastTimer != null) {
                out.writeInt(1);
                u.mWifiMulticastTimer.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
            if (u.mAudioTurnedOnTimer != null) {
                out.writeInt(1);
                u.mAudioTurnedOnTimer.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
            if (u.mVideoTurnedOnTimer != null) {
                out.writeInt(1);
                u.mVideoTurnedOnTimer.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
            if (u.mFlashlightTurnedOnTimer != null) {
                out.writeInt(1);
                u.mFlashlightTurnedOnTimer.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
            if (u.mCameraTurnedOnTimer != null) {
                out.writeInt(1);
                u.mCameraTurnedOnTimer.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
            if (u.mForegroundActivityTimer != null) {
                out.writeInt(1);
                u.mForegroundActivityTimer.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
            if (u.mForegroundServiceTimer != null) {
                out.writeInt(1);
                u.mForegroundServiceTimer.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
            if (u.mAggregatedPartialWakelockTimer != null) {
                out.writeInt(1);
                u.mAggregatedPartialWakelockTimer.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
            if (u.mBluetoothScanTimer != null) {
                out.writeInt(1);
                u.mBluetoothScanTimer.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
            if (u.mBluetoothUnoptimizedScanTimer != null) {
                out.writeInt(1);
                u.mBluetoothUnoptimizedScanTimer.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }
            if (u.mBluetoothScanResultCounter != null) {
                out.writeInt(1);
                u.mBluetoothScanResultCounter.writeSummaryFromParcelLocked(out);
            } else {
                out.writeInt(0);
            }
            if (u.mBluetoothScanResultBgCounter != null) {
                out.writeInt(1);
                u.mBluetoothScanResultBgCounter.writeSummaryFromParcelLocked(out);
            } else {
                out.writeInt(0);
            }
            for (int i = 0; i < NUM_PROCESS_STATE; i++) {
                if (u.mProcessStateTimer[i] != null) {
                    out.writeInt(1);
                    u.mProcessStateTimer[i].writeSummaryFromParcelLocked(out, nowRealtime);
                } else {
                    out.writeInt(0);
                }
            }
            if (u.mVibratorOnTimer != null) {
                out.writeInt(1);
                u.mVibratorOnTimer.writeSummaryFromParcelLocked(out, nowRealtime);
            } else {
                out.writeInt(0);
            }

            if (u.mUserActivityCounters == null) {
                out.writeInt(0);
            } else {
                out.writeInt(1);
                for (int i=0; i<Uid.NUM_USER_ACTIVITY_TYPES; i++) {
                    u.mUserActivityCounters[i].writeSummaryFromParcelLocked(out);
                }
            }

            if (u.mNetworkByteActivityCounters == null) {
                out.writeInt(0);
            } else {
                out.writeInt(1);
                for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
                    u.mNetworkByteActivityCounters[i].writeSummaryFromParcelLocked(out);
                    u.mNetworkPacketActivityCounters[i].writeSummaryFromParcelLocked(out);
                }
                if (u.mMobileRadioActiveTime != null) {
                    out.writeBoolean(true);
                    u.mMobileRadioActiveTime.writeToParcel(out);
                } else {
                    out.writeBoolean(false);
                }
                u.mMobileRadioActiveCount.writeSummaryFromParcelLocked(out);
            }

            u.mUserCpuTime.writeSummaryFromParcelLocked(out);
            u.mSystemCpuTime.writeSummaryFromParcelLocked(out);

            if (u.mCpuClusterSpeedTimesUs != null) {
                out.writeInt(1);
                out.writeInt(u.mCpuClusterSpeedTimesUs.length);
                for (LongSamplingCounter[] cpuSpeeds : u.mCpuClusterSpeedTimesUs) {
                    if (cpuSpeeds != null) {
                        out.writeInt(1);
                        out.writeInt(cpuSpeeds.length);
                        for (LongSamplingCounter c : cpuSpeeds) {
                            if (c != null) {
                                out.writeInt(1);
                                c.writeSummaryFromParcelLocked(out);
                            } else {
                                out.writeInt(0);
                            }
                        }
                    } else {
                        out.writeInt(0);
                    }
                }
            } else {
                out.writeInt(0);
            }

            LongSamplingCounterArray.writeSummaryToParcelLocked(out, u.mCpuFreqTimeMs);
            LongSamplingCounterArray.writeSummaryToParcelLocked(out, u.mScreenOffCpuFreqTimeMs);

            if (u.mCpuActiveTimeMs != null) {
                out.writeInt(u.mCpuActiveTimeMs.getStateCount());
                u.mCpuActiveTimeMs.writeToParcel(out);
            } else {
                out.writeInt(0);
            }

            u.mCpuClusterTimesMs.writeSummaryToParcelLocked(out);

            if (u.mProcStateTimeMs != null) {
                out.writeInt(u.mProcStateTimeMs.getStateCount());
                u.mProcStateTimeMs.writeToParcel(out);
            } else {
                out.writeInt(0);
            }

            if (u.mProcStateScreenOffTimeMs != null) {
                out.writeInt(u.mProcStateScreenOffTimeMs.getStateCount());
                u.mProcStateScreenOffTimeMs.writeToParcel(out);
            } else {
                out.writeInt(0);
            }

            if (u.mMobileRadioApWakeupCount != null) {
                out.writeInt(1);
                u.mMobileRadioApWakeupCount.writeSummaryFromParcelLocked(out);
            } else {
                out.writeInt(0);
            }

            if (u.mWifiRadioApWakeupCount != null) {
                out.writeInt(1);
                u.mWifiRadioApWakeupCount.writeSummaryFromParcelLocked(out);
            } else {
                out.writeInt(0);
            }

            MeasuredEnergyStats.writeSummaryToParcel(u.mUidMeasuredEnergyStats, out);

            final ArrayMap<String, Uid.Wakelock> wakeStats = u.mWakelockStats.getMap();
            int NW = wakeStats.size();
            out.writeInt(NW);
            for (int iw=0; iw<NW; iw++) {
                out.writeString(wakeStats.keyAt(iw));
                Uid.Wakelock wl = wakeStats.valueAt(iw);
                if (wl.mTimerFull != null) {
                    out.writeInt(1);
                    wl.mTimerFull.writeSummaryFromParcelLocked(out, nowRealtime);
                } else {
                    out.writeInt(0);
                }
                if (wl.mTimerPartial != null) {
                    out.writeInt(1);
                    wl.mTimerPartial.writeSummaryFromParcelLocked(out, nowRealtime);
                } else {
                    out.writeInt(0);
                }
                if (wl.mTimerWindow != null) {
                    out.writeInt(1);
                    wl.mTimerWindow.writeSummaryFromParcelLocked(out, nowRealtime);
                } else {
                    out.writeInt(0);
                }
                if (wl.mTimerDraw != null) {
                    out.writeInt(1);
                    wl.mTimerDraw.writeSummaryFromParcelLocked(out, nowRealtime);
                } else {
                    out.writeInt(0);
                }
            }

            final ArrayMap<String, DualTimer> syncStats = u.mSyncStats.getMap();
            int NS = syncStats.size();
            out.writeInt(NS);
            for (int is=0; is<NS; is++) {
                out.writeString(syncStats.keyAt(is));
                syncStats.valueAt(is).writeSummaryFromParcelLocked(out, nowRealtime);
            }

            final ArrayMap<String, DualTimer> jobStats = u.mJobStats.getMap();
            int NJ = jobStats.size();
            out.writeInt(NJ);
            for (int ij=0; ij<NJ; ij++) {
                out.writeString(jobStats.keyAt(ij));
                jobStats.valueAt(ij).writeSummaryFromParcelLocked(out, nowRealtime);
            }

            u.writeJobCompletionsToParcelLocked(out);

            u.mJobsDeferredEventCount.writeSummaryFromParcelLocked(out);
            u.mJobsDeferredCount.writeSummaryFromParcelLocked(out);
            u.mJobsFreshnessTimeMs.writeSummaryFromParcelLocked(out);
            for (int i = 0; i < JOB_FRESHNESS_BUCKETS.length; i++) {
                if (u.mJobsFreshnessBuckets[i] != null) {
                    out.writeInt(1);
                    u.mJobsFreshnessBuckets[i].writeSummaryFromParcelLocked(out);
                } else {
                    out.writeInt(0);
                }
            }

            int NSE = u.mSensorStats.size();
            out.writeInt(NSE);
            for (int ise=0; ise<NSE; ise++) {
                out.writeInt(u.mSensorStats.keyAt(ise));
                Uid.Sensor se = u.mSensorStats.valueAt(ise);
                if (se.mTimer != null) {
                    out.writeInt(1);
                    se.mTimer.writeSummaryFromParcelLocked(out, nowRealtime);
                } else {
                    out.writeInt(0);
                }
            }

            int NP = u.mProcessStats.size();
            out.writeInt(NP);
            for (int ip=0; ip<NP; ip++) {
                out.writeString(u.mProcessStats.keyAt(ip));
                Uid.Proc ps = u.mProcessStats.valueAt(ip);
                out.writeLong(ps.mUserTimeMs);
                out.writeLong(ps.mSystemTimeMs);
                out.writeLong(ps.mForegroundTimeMs);
                out.writeInt(ps.mStarts);
                out.writeInt(ps.mNumCrashes);
                out.writeInt(ps.mNumAnrs);
                ps.writeExcessivePowerToParcelLocked(out);
            }

            NP = u.mPackageStats.size();
            out.writeInt(NP);
            if (NP > 0) {
                for (Map.Entry<String, BatteryStatsImpl.Uid.Pkg> ent
                    : u.mPackageStats.entrySet()) {
                    out.writeString(ent.getKey());
                    Uid.Pkg ps = ent.getValue();
                    final int NWA = ps.mWakeupAlarms.size();
                    out.writeInt(NWA);
                    for (int iwa=0; iwa<NWA; iwa++) {
                        out.writeString(ps.mWakeupAlarms.keyAt(iwa));
                        ps.mWakeupAlarms.valueAt(iwa).writeSummaryFromParcelLocked(out);
                    }
                    NS = ps.mServiceStats.size();
                    out.writeInt(NS);
                    for (int is=0; is<NS; is++) {
                        out.writeString(ps.mServiceStats.keyAt(is));
                        BatteryStatsImpl.Uid.Pkg.Serv ss = ps.mServiceStats.valueAt(is);
                        long time = ss.getStartTimeToNowLocked(
                                mOnBatteryTimeBase.getUptime(nowUptime) / 1000);
                        out.writeLong(time);
                        out.writeInt(ss.mStarts);
                        out.writeInt(ss.mLaunches);
                    }
                }
            }
        }

        LongSamplingCounterArray.writeSummaryToParcelLocked(out, mBinderThreadCpuTimesUs);
    }

    @GuardedBy("this")
    public void readFromParcel(Parcel in) {
        readFromParcelLocked(in);
    }

    @GuardedBy("this")
    @SuppressWarnings("GuardedBy")  // errorprone false positive on u.readFromParcelLocked
    void readFromParcelLocked(Parcel in) {
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new ParcelFormatException("Bad magic number: #" + Integer.toHexString(magic));
        }

        readHistoryBuffer(in);
        mBatteryStatsHistory.readFromParcel(in);

        mStartCount = in.readInt();
        mStartClockTimeMs = in.readLong();
        mStartPlatformVersion = in.readString();
        mEndPlatformVersion = in.readString();
        mUptimeUs = in.readLong();
        mUptimeStartUs = in.readLong();
        mRealtimeUs = in.readLong();
        mRealtimeStartUs = in.readLong();
        mOnBattery = in.readInt() != 0;
        mEstimatedBatteryCapacityMah = in.readInt();
        mLastLearnedBatteryCapacityUah = in.readInt();
        mMinLearnedBatteryCapacityUah = in.readInt();
        mMaxLearnedBatteryCapacityUah = in.readInt();
        mOnBatteryInternal = false; // we are no longer really running.
        mOnBatteryTimeBase.readFromParcel(in);
        mOnBatteryScreenOffTimeBase.readFromParcel(in);

        mScreenState = Display.STATE_UNKNOWN;
        mScreenOnTimer = new StopwatchTimer(mClock, null, -1, null, mOnBatteryTimeBase, in);
        mScreenDozeTimer = new StopwatchTimer(mClock, null, -1, null, mOnBatteryTimeBase, in);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i] = new StopwatchTimer(mClock, null, -100 - i, null,
                    mOnBatteryTimeBase, in);
        }
        mInteractive = false;
        mInteractiveTimer = new StopwatchTimer(mClock, null, -10, null, mOnBatteryTimeBase, in);
        mPhoneOn = false;
        mPowerSaveModeEnabledTimer = new StopwatchTimer(mClock, null, -2, null,
                mOnBatteryTimeBase, in);
        mLongestLightIdleTimeMs = in.readLong();
        mLongestFullIdleTimeMs = in.readLong();
        mDeviceIdleModeLightTimer = new StopwatchTimer(mClock, null, -14, null,
                mOnBatteryTimeBase, in);
        mDeviceIdleModeFullTimer = new StopwatchTimer(mClock, null, -11, null,
                mOnBatteryTimeBase, in);
        mDeviceLightIdlingTimer = new StopwatchTimer(mClock, null, -15, null,
                mOnBatteryTimeBase, in);
        mDeviceIdlingTimer = new StopwatchTimer(mClock, null, -12, null, mOnBatteryTimeBase, in);
        mPhoneOnTimer = new StopwatchTimer(mClock, null, -3, null, mOnBatteryTimeBase, in);
        for (int i = 0; i < CellSignalStrength.getNumSignalStrengthLevels(); i++) {
            mPhoneSignalStrengthsTimer[i] = new StopwatchTimer(mClock, null, -200 - i,
                    null, mOnBatteryTimeBase, in);
        }
        mPhoneSignalScanningTimer = new StopwatchTimer(mClock, null, -200 + 1, null,
                mOnBatteryTimeBase, in);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i] = new StopwatchTimer(mClock, null, -300 - i,
                    null, mOnBatteryTimeBase, in);
        }
        for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
            mNetworkByteActivityCounters[i] = new LongSamplingCounter(mOnBatteryTimeBase, in);
            mNetworkPacketActivityCounters[i] = new LongSamplingCounter(mOnBatteryTimeBase, in);
        }
        mMobileRadioPowerState = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
        mMobileRadioActiveTimer = new StopwatchTimer(mClock, null, -400, null,
                mOnBatteryTimeBase, in);
        mMobileRadioActivePerAppTimer = new StopwatchTimer(mClock, null, -401, null,
                mOnBatteryTimeBase, in);
        mMobileRadioActiveAdjustedTime = new LongSamplingCounter(mOnBatteryTimeBase, in);
        mMobileRadioActiveUnknownTime = new LongSamplingCounter(mOnBatteryTimeBase, in);
        mMobileRadioActiveUnknownCount = new LongSamplingCounter(mOnBatteryTimeBase, in);
        mWifiMulticastWakelockTimer = new StopwatchTimer(mClock, null, -4, null,
                mOnBatteryTimeBase, in);
        mWifiRadioPowerState = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
        mWifiOn = false;
        mWifiOnTimer = new StopwatchTimer(mClock, null, -4, null, mOnBatteryTimeBase, in);
        mGlobalWifiRunning = false;
        mGlobalWifiRunningTimer = new StopwatchTimer(mClock, null, -5, null,
                mOnBatteryTimeBase, in);
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            mWifiStateTimer[i] = new StopwatchTimer(mClock, null, -600 - i,
                    null, mOnBatteryTimeBase, in);
        }
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            mWifiSupplStateTimer[i] = new StopwatchTimer(mClock, null, -700 - i,
                    null, mOnBatteryTimeBase, in);
        }
        for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            mWifiSignalStrengthsTimer[i] = new StopwatchTimer(mClock, null, -800 - i,
                    null, mOnBatteryTimeBase, in);
        }
        mWifiActiveTimer = new StopwatchTimer(mClock, null, -900, null,
            mOnBatteryTimeBase, in);
        mWifiActivity = new ControllerActivityCounterImpl(mClock, mOnBatteryTimeBase,
                NUM_WIFI_TX_LEVELS, in);
        for (int i=0; i<mGpsSignalQualityTimer.length; i++) {
            mGpsSignalQualityTimer[i] = new StopwatchTimer(mClock, null, -1000 - i,
                null, mOnBatteryTimeBase, in);
        }
        mBluetoothActivity = new ControllerActivityCounterImpl(mClock, mOnBatteryTimeBase,
                NUM_BT_TX_LEVELS, in);
        mModemActivity = new ControllerActivityCounterImpl(mClock, mOnBatteryTimeBase,
                ModemActivityInfo.getNumTxPowerLevels(), in);
        mHasWifiReporting = in.readInt() != 0;
        mHasBluetoothReporting = in.readInt() != 0;
        mHasModemReporting = in.readInt() != 0;

        mNumConnectivityChange = in.readInt();
        mAudioOnNesting = 0;
        // TODO: It's likely a mistake that mAudioOnTimer/mVideoOnTimer don't write/read to parcel!
        mAudioOnTimer = new StopwatchTimer(mClock, null, -7, null, mOnBatteryTimeBase);
        mVideoOnNesting = 0;
        mVideoOnTimer = new StopwatchTimer(mClock, null, -8, null, mOnBatteryTimeBase);
        mFlashlightOnNesting = 0;
        mFlashlightOnTimer = new StopwatchTimer(mClock, null, -9, null, mOnBatteryTimeBase, in);
        mCameraOnNesting = 0;
        mCameraOnTimer = new StopwatchTimer(mClock, null, -13, null, mOnBatteryTimeBase, in);
        mBluetoothScanNesting = 0;
        mBluetoothScanTimer = new StopwatchTimer(mClock, null, -14, null, mOnBatteryTimeBase, in);
        mDischargeUnplugLevel = in.readInt();
        mDischargePlugLevel = in.readInt();
        mDischargeCurrentLevel = in.readInt();
        mCurrentBatteryLevel = in.readInt();
        mLowDischargeAmountSinceCharge = in.readInt();
        mHighDischargeAmountSinceCharge = in.readInt();
        mDischargeAmountScreenOn = in.readInt();
        mDischargeAmountScreenOnSinceCharge = in.readInt();
        mDischargeAmountScreenOff = in.readInt();
        mDischargeAmountScreenOffSinceCharge = in.readInt();
        mDischargeAmountScreenDoze = in.readInt();
        mDischargeAmountScreenDozeSinceCharge = in.readInt();
        mDischargeStepTracker.readFromParcel(in);
        mChargeStepTracker.readFromParcel(in);
        mDischargeCounter = new LongSamplingCounter(mOnBatteryTimeBase, in);
        mDischargeScreenOffCounter = new LongSamplingCounter(mOnBatteryScreenOffTimeBase, in);
        mDischargeScreenDozeCounter = new LongSamplingCounter(mOnBatteryTimeBase, in);
        mDischargeLightDozeCounter = new LongSamplingCounter(mOnBatteryTimeBase, in);
        mDischargeDeepDozeCounter = new LongSamplingCounter(mOnBatteryTimeBase, in);
        mLastWriteTimeMs = in.readLong();
        mBatteryTimeToFullSeconds = in.readLong();


        final MeasuredEnergyStats.Config config = MeasuredEnergyStats.Config.createFromParcel(in);
        final MeasuredEnergyStats measuredEnergyStats =
                MeasuredEnergyStats.createFromParcel(mMeasuredEnergyStatsConfig, in);
        if (config != null && Arrays.equals(config.getStateNames(),
                getBatteryConsumerProcessStateNames())) {
            mMeasuredEnergyStatsConfig = config;
            mGlobalMeasuredEnergyStats = measuredEnergyStats;
        }

        mRpmStats.clear();
        int NRPMS = in.readInt();
        for (int irpm = 0; irpm < NRPMS; irpm++) {
            if (in.readInt() != 0) {
                String rpmName = in.readString();
                SamplingTimer rpmt = new SamplingTimer(mClock, mOnBatteryTimeBase, in);
                mRpmStats.put(rpmName, rpmt);
            }
        }
        mScreenOffRpmStats.clear();
        int NSORPMS = in.readInt();
        for (int irpm = 0; irpm < NSORPMS; irpm++) {
            if (in.readInt() != 0) {
                String rpmName = in.readString();
                SamplingTimer rpmt = new SamplingTimer(mClock, mOnBatteryScreenOffTimeBase, in);
                mScreenOffRpmStats.put(rpmName, rpmt);
            }
        }

        mKernelWakelockStats.clear();
        int NKW = in.readInt();
        for (int ikw = 0; ikw < NKW; ikw++) {
            if (in.readInt() != 0) {
                String wakelockName = in.readString();
                SamplingTimer kwlt = new SamplingTimer(mClock, mOnBatteryScreenOffTimeBase, in);
                mKernelWakelockStats.put(wakelockName, kwlt);
            }
        }

        mWakeupReasonStats.clear();
        int NWR = in.readInt();
        for (int iwr = 0; iwr < NWR; iwr++) {
            if (in.readInt() != 0) {
                String reasonName = in.readString();
                SamplingTimer timer = new SamplingTimer(mClock, mOnBatteryTimeBase, in);
                mWakeupReasonStats.put(reasonName, timer);
            }
        }

        mKernelMemoryStats.clear();
        int nmt = in.readInt();
        for (int imt = 0; imt < nmt; imt++) {
            if (in.readInt() != 0) {
                Long bucket = in.readLong();
                SamplingTimer kmt = new SamplingTimer(mClock, mOnBatteryTimeBase, in);
                mKernelMemoryStats.put(bucket, kmt);
            }
        }

        mPartialTimers.clear();
        mFullTimers.clear();
        mWindowTimers.clear();
        mWifiRunningTimers.clear();
        mFullWifiLockTimers.clear();
        mWifiScanTimers.clear();
        mWifiBatchedScanTimers.clear();
        mWifiMulticastTimers.clear();
        mAudioTurnedOnTimers.clear();
        mVideoTurnedOnTimers.clear();
        mFlashlightTurnedOnTimers.clear();
        mCameraTurnedOnTimers.clear();

        int numUids = in.readInt();
        mUidStats.clear();
        final long elapsedRealtimeMs = mClock.elapsedRealtime();
        final long uptimeMs = mClock.uptimeMillis();
        for (int i = 0; i < numUids; i++) {
            int uid = in.readInt();
            Uid u = new Uid(this, uid, elapsedRealtimeMs, uptimeMs);
            u.readFromParcelLocked(mOnBatteryTimeBase, mOnBatteryScreenOffTimeBase,
                    in);
            mUidStats.append(uid, u);
        }

        mBinderThreadCpuTimesUs = LongSamplingCounterArray.readFromParcel(in, mOnBatteryTimeBase);
    }

    @GuardedBy("this")
    public void writeToParcel(Parcel out, int flags) {
        writeToParcelLocked(out, true, flags);
    }

    @GuardedBy("this")
    public void writeToParcelWithoutUids(Parcel out, int flags) {
        writeToParcelLocked(out, false, flags);
    }

    @SuppressWarnings("unused")
    @GuardedBy("this")
    void writeToParcelLocked(Parcel out, boolean inclUids, int flags) {
        // Need to update with current kernel wake lock counts.
        pullPendingStateUpdatesLocked();

        updateSystemServiceCallStats();

        // Pull the clock time.  This may update the time and make a new history entry
        // if we had originally pulled a time before the RTC was set.
        getStartClockTime();

        final long uSecUptime = mClock.uptimeMillis() * 1000;
        final long uSecRealtime = mClock.elapsedRealtime() * 1000;
        final long batteryRealtime = mOnBatteryTimeBase.getRealtime(uSecRealtime);
        final long batteryScreenOffRealtime = mOnBatteryScreenOffTimeBase.getRealtime(uSecRealtime);

        out.writeInt(MAGIC);

        writeHistoryBuffer(out, true);
        mBatteryStatsHistory.writeToParcel(out);

        out.writeInt(mStartCount);
        out.writeLong(mStartClockTimeMs);
        out.writeString(mStartPlatformVersion);
        out.writeString(mEndPlatformVersion);
        out.writeLong(mUptimeUs);
        out.writeLong(mUptimeStartUs);
        out.writeLong(mRealtimeUs);
        out.writeLong(mRealtimeStartUs);
        out.writeInt(mOnBattery ? 1 : 0);
        out.writeInt(mEstimatedBatteryCapacityMah);
        out.writeInt(mLastLearnedBatteryCapacityUah);
        out.writeInt(mMinLearnedBatteryCapacityUah);
        out.writeInt(mMaxLearnedBatteryCapacityUah);
        mOnBatteryTimeBase.writeToParcel(out, uSecUptime, uSecRealtime);
        mOnBatteryScreenOffTimeBase.writeToParcel(out, uSecUptime, uSecRealtime);

        mScreenOnTimer.writeToParcel(out, uSecRealtime);
        mScreenDozeTimer.writeToParcel(out, uSecRealtime);
        for (int i = 0; i < NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i].writeToParcel(out, uSecRealtime);
        }
        mInteractiveTimer.writeToParcel(out, uSecRealtime);
        mPowerSaveModeEnabledTimer.writeToParcel(out, uSecRealtime);
        out.writeLong(mLongestLightIdleTimeMs);
        out.writeLong(mLongestFullIdleTimeMs);
        mDeviceIdleModeLightTimer.writeToParcel(out, uSecRealtime);
        mDeviceIdleModeFullTimer.writeToParcel(out, uSecRealtime);
        mDeviceLightIdlingTimer.writeToParcel(out, uSecRealtime);
        mDeviceIdlingTimer.writeToParcel(out, uSecRealtime);
        mPhoneOnTimer.writeToParcel(out, uSecRealtime);
        for (int i = 0; i < CellSignalStrength.getNumSignalStrengthLevels(); i++) {
            mPhoneSignalStrengthsTimer[i].writeToParcel(out, uSecRealtime);
        }
        mPhoneSignalScanningTimer.writeToParcel(out, uSecRealtime);
        for (int i = 0; i < NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i].writeToParcel(out, uSecRealtime);
        }
        for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
            mNetworkByteActivityCounters[i].writeToParcel(out);
            mNetworkPacketActivityCounters[i].writeToParcel(out);
        }
        mMobileRadioActiveTimer.writeToParcel(out, uSecRealtime);
        mMobileRadioActivePerAppTimer.writeToParcel(out, uSecRealtime);
        mMobileRadioActiveAdjustedTime.writeToParcel(out);
        mMobileRadioActiveUnknownTime.writeToParcel(out);
        mMobileRadioActiveUnknownCount.writeToParcel(out);
        mWifiMulticastWakelockTimer.writeToParcel(out, uSecRealtime);
        mWifiOnTimer.writeToParcel(out, uSecRealtime);
        mGlobalWifiRunningTimer.writeToParcel(out, uSecRealtime);
        for (int i = 0; i < NUM_WIFI_STATES; i++) {
            mWifiStateTimer[i].writeToParcel(out, uSecRealtime);
        }
        for (int i = 0; i < NUM_WIFI_SUPPL_STATES; i++) {
            mWifiSupplStateTimer[i].writeToParcel(out, uSecRealtime);
        }
        for (int i = 0; i < NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            mWifiSignalStrengthsTimer[i].writeToParcel(out, uSecRealtime);
        }
        mWifiActiveTimer.writeToParcel(out, uSecRealtime);
        mWifiActivity.writeToParcel(out, 0);
        for (int i = 0; i < mGpsSignalQualityTimer.length; i++) {
            mGpsSignalQualityTimer[i].writeToParcel(out, uSecRealtime);
        }
        mBluetoothActivity.writeToParcel(out, 0);
        mModemActivity.writeToParcel(out, 0);
        out.writeInt(mHasWifiReporting ? 1 : 0);
        out.writeInt(mHasBluetoothReporting ? 1 : 0);
        out.writeInt(mHasModemReporting ? 1 : 0);

        out.writeInt(mNumConnectivityChange);
        mFlashlightOnTimer.writeToParcel(out, uSecRealtime);
        mCameraOnTimer.writeToParcel(out, uSecRealtime);
        mBluetoothScanTimer.writeToParcel(out, uSecRealtime);
        out.writeInt(mDischargeUnplugLevel);
        out.writeInt(mDischargePlugLevel);
        out.writeInt(mDischargeCurrentLevel);
        out.writeInt(mCurrentBatteryLevel);
        out.writeInt(mLowDischargeAmountSinceCharge);
        out.writeInt(mHighDischargeAmountSinceCharge);
        out.writeInt(mDischargeAmountScreenOn);
        out.writeInt(mDischargeAmountScreenOnSinceCharge);
        out.writeInt(mDischargeAmountScreenOff);
        out.writeInt(mDischargeAmountScreenOffSinceCharge);
        out.writeInt(mDischargeAmountScreenDoze);
        out.writeInt(mDischargeAmountScreenDozeSinceCharge);
        mDischargeStepTracker.writeToParcel(out);
        mChargeStepTracker.writeToParcel(out);
        mDischargeCounter.writeToParcel(out);
        mDischargeScreenOffCounter.writeToParcel(out);
        mDischargeScreenDozeCounter.writeToParcel(out);
        mDischargeLightDozeCounter.writeToParcel(out);
        mDischargeDeepDozeCounter.writeToParcel(out);
        out.writeLong(mLastWriteTimeMs);
        out.writeLong(mBatteryTimeToFullSeconds);

        MeasuredEnergyStats.Config.writeToParcel(mMeasuredEnergyStatsConfig, out);

        if (mGlobalMeasuredEnergyStats != null) {
            out.writeInt(1);
            mGlobalMeasuredEnergyStats.writeToParcel(out);
        } else {
            out.writeInt(0);
        }

        out.writeInt(mRpmStats.size());
        for (Map.Entry<String, SamplingTimer> ent : mRpmStats.entrySet()) {
            SamplingTimer rpmt = ent.getValue();
            if (rpmt != null) {
                out.writeInt(1);
                out.writeString(ent.getKey());
                rpmt.writeToParcel(out, uSecRealtime);
            } else {
                out.writeInt(0);
            }
        }
        out.writeInt(mScreenOffRpmStats.size());
        for (Map.Entry<String, SamplingTimer> ent : mScreenOffRpmStats.entrySet()) {
            SamplingTimer rpmt = ent.getValue();
            if (rpmt != null) {
                out.writeInt(1);
                out.writeString(ent.getKey());
                rpmt.writeToParcel(out, uSecRealtime);
            } else {
                out.writeInt(0);
            }
        }

        if (inclUids) {
            out.writeInt(mKernelWakelockStats.size());
            for (Map.Entry<String, SamplingTimer> ent : mKernelWakelockStats.entrySet()) {
                SamplingTimer kwlt = ent.getValue();
                if (kwlt != null) {
                    out.writeInt(1);
                    out.writeString(ent.getKey());
                    kwlt.writeToParcel(out, uSecRealtime);
                } else {
                    out.writeInt(0);
                }
            }
            out.writeInt(mWakeupReasonStats.size());
            for (Map.Entry<String, SamplingTimer> ent : mWakeupReasonStats.entrySet()) {
                SamplingTimer timer = ent.getValue();
                if (timer != null) {
                    out.writeInt(1);
                    out.writeString(ent.getKey());
                    timer.writeToParcel(out, uSecRealtime);
                } else {
                    out.writeInt(0);
                }
            }
        } else {
            out.writeInt(0);
            out.writeInt(0);
        }

        out.writeInt(mKernelMemoryStats.size());
        for (int i = 0; i < mKernelMemoryStats.size(); i++) {
            SamplingTimer kmt = mKernelMemoryStats.valueAt(i);
            if (kmt != null) {
                out.writeInt(1);
                out.writeLong(mKernelMemoryStats.keyAt(i));
                kmt.writeToParcel(out, uSecRealtime);
            } else {
                out.writeInt(0);
            }
        }

        if (inclUids) {
            int size = mUidStats.size();
            out.writeInt(size);
            for (int i = 0; i < size; i++) {
                out.writeInt(mUidStats.keyAt(i));
                Uid uid = mUidStats.valueAt(i);

                uid.writeToParcelLocked(out, uSecUptime, uSecRealtime);
            }
        } else {
            out.writeInt(0);
        }
        LongSamplingCounterArray.writeToParcel(out, mBinderThreadCpuTimesUs);
    }

    private void writeCpuSpeedCountersToParcel(Parcel out, LongSamplingCounter[][] counters) {
        if (counters == null) {
            out.writeInt(0);
            return;
        }

        out.writeInt(1);
        out.writeInt(counters.length);
        for (int i = 0; i < counters.length; i++) {
            LongSamplingCounter[] counterArray = counters[i];
            if (counterArray == null) {
                out.writeInt(0);
                continue;
            }

            out.writeInt(1);
            out.writeInt(counterArray.length);
            for (int j = 0; j < counterArray.length; j++) {
                LongSamplingCounter c = counterArray[j];
                if (c != null) {
                    out.writeInt(1);
                    c.writeToParcel(out);
                } else {
                    out.writeInt(0);
                }
            }
        }
    }

    private LongSamplingCounter[][] readCpuSpeedCountersFromParcel(Parcel in) {
        LongSamplingCounter[][] counters;
        if (in.readInt() != 0) {
            int numCpuClusters = in.readInt();
            if (mPowerProfile != null
                    && mPowerProfile.getNumCpuClusters() != numCpuClusters) {
                throw new ParcelFormatException("Incompatible number of cpu clusters");
            }

            counters = new LongSamplingCounter[numCpuClusters][];
            for (int cluster = 0; cluster < numCpuClusters; cluster++) {
                if (in.readInt() != 0) {
                    int numSpeeds = in.readInt();
                    if (mPowerProfile != null
                            && mPowerProfile.getNumSpeedStepsInCpuCluster(cluster) != numSpeeds) {
                        throw new ParcelFormatException("Incompatible number of cpu speeds");
                    }

                    final LongSamplingCounter[] cpuSpeeds = new LongSamplingCounter[numSpeeds];
                    counters[cluster] = cpuSpeeds;
                    for (int speed = 0; speed < numSpeeds; speed++) {
                        if (in.readInt() != 0) {
                            cpuSpeeds[speed] = new LongSamplingCounter(mOnBatteryTimeBase, in);
                        }
                    }
                } else {
                    counters[cluster] = null;
                }
            }
        } else {
            counters = null;
        }

        return counters;
    }

    @UnsupportedAppUsage
    public static final Parcelable.Creator<BatteryStatsImpl> CREATOR =
        new Parcelable.Creator<BatteryStatsImpl>() {
        public BatteryStatsImpl createFromParcel(Parcel in) {
            return new BatteryStatsImpl(in);
        }

        public BatteryStatsImpl[] newArray(int size) {
            return new BatteryStatsImpl[size];
        }
    };

    @GuardedBy("this")
    public void prepareForDumpLocked() {
        // Need to retrieve current kernel wake lock stats before printing.
        pullPendingStateUpdatesLocked();

        // Pull the clock time.  This may update the time and make a new history entry
        // if we had originally pulled a time before the RTC was set.
        getStartClockTime();

        updateSystemServiceCallStats();
    }

    @GuardedBy("this")
    public void dumpLocked(Context context, PrintWriter pw, int flags, int reqUid, long histStart) {
        if (DEBUG) {
            pw.println("mOnBatteryTimeBase:");
            mOnBatteryTimeBase.dump(pw, "  ");
            pw.println("mOnBatteryScreenOffTimeBase:");
            mOnBatteryScreenOffTimeBase.dump(pw, "  ");
            Printer pr = new PrintWriterPrinter(pw);
            pr.println("*** Screen on timer:");
            mScreenOnTimer.logState(pr, "  ");
            pr.println("*** Screen doze timer:");
            mScreenDozeTimer.logState(pr, "  ");
            for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
                pr.println("*** Screen brightness #" + i + ":");
                mScreenBrightnessTimer[i].logState(pr, "  ");
            }
            pr.println("*** Interactive timer:");
            mInteractiveTimer.logState(pr, "  ");
            pr.println("*** Power save mode timer:");
            mPowerSaveModeEnabledTimer.logState(pr, "  ");
            pr.println("*** Device idle mode light timer:");
            mDeviceIdleModeLightTimer.logState(pr, "  ");
            pr.println("*** Device idle mode full timer:");
            mDeviceIdleModeFullTimer.logState(pr, "  ");
            pr.println("*** Device light idling timer:");
            mDeviceLightIdlingTimer.logState(pr, "  ");
            pr.println("*** Device idling timer:");
            mDeviceIdlingTimer.logState(pr, "  ");
            pr.println("*** Phone timer:");
            mPhoneOnTimer.logState(pr, "  ");
            for (int i = 0; i < CellSignalStrength.getNumSignalStrengthLevels(); i++) {
                pr.println("*** Phone signal strength #" + i + ":");
                mPhoneSignalStrengthsTimer[i].logState(pr, "  ");
            }
            pr.println("*** Signal scanning :");
            mPhoneSignalScanningTimer.logState(pr, "  ");
            for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
                pr.println("*** Data connection type #" + i + ":");
                mPhoneDataConnectionsTimer[i].logState(pr, "  ");
            }
            pr.println("*** mMobileRadioPowerState=" + mMobileRadioPowerState);
            pr.println("*** Mobile network active timer:");
            mMobileRadioActiveTimer.logState(pr, "  ");
            pr.println("*** Mobile network active adjusted timer:");
            mMobileRadioActiveAdjustedTime.logState(pr, "  ");
            pr.println("*** Wifi Multicast WakeLock Timer:");
            mWifiMulticastWakelockTimer.logState(pr, "  ");
            pr.println("*** mWifiRadioPowerState=" + mWifiRadioPowerState);
            pr.println("*** Wifi timer:");
            mWifiOnTimer.logState(pr, "  ");
            pr.println("*** WifiRunning timer:");
            mGlobalWifiRunningTimer.logState(pr, "  ");
            for (int i=0; i<NUM_WIFI_STATES; i++) {
                pr.println("*** Wifi state #" + i + ":");
                mWifiStateTimer[i].logState(pr, "  ");
            }
            for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
                pr.println("*** Wifi suppl state #" + i + ":");
                mWifiSupplStateTimer[i].logState(pr, "  ");
            }
            for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
                pr.println("*** Wifi signal strength #" + i + ":");
                mWifiSignalStrengthsTimer[i].logState(pr, "  ");
            }
            for (int i=0; i<mGpsSignalQualityTimer.length; i++) {
                pr.println("*** GPS signal quality #" + i + ":");
                mGpsSignalQualityTimer[i].logState(pr, "  ");
            }
            pr.println("*** Flashlight timer:");
            mFlashlightOnTimer.logState(pr, "  ");
            pr.println("*** Camera timer:");
            mCameraOnTimer.logState(pr, "  ");
        }
        super.dumpLocked(context, pw, flags, reqUid, histStart);

        pw.print("Per process state tracking available: ");
        pw.println(trackPerProcStateCpuTimes());
        pw.print("Total cpu time reads: ");
        pw.println(mNumSingleUidCpuTimeReads);
        pw.print("Batching Duration (min): ");
        pw.println((mClock.uptimeMillis() - mCpuTimeReadsTrackingStartTimeMs) / (60 * 1000));
        pw.print("All UID cpu time reads since the later of device start or stats reset: ");
        pw.println(mNumAllUidCpuTimeReads);
        pw.print("UIDs removed since the later of device start or stats reset: ");
        pw.println(mNumUidsRemoved);

        pw.println("Currently mapped isolated uids:");
        final int numIsolatedUids = mIsolatedUids.size();
        for (int i = 0; i < numIsolatedUids; i++) {
            final int isolatedUid = mIsolatedUids.keyAt(i);
            final int ownerUid = mIsolatedUids.valueAt(i);
            final int refCount = mIsolatedUidRefCounts.get(isolatedUid);
            pw.println("  " + isolatedUid + "->" + ownerUid + " (ref count = " + refCount + ")");
        }

        pw.println();
        dumpConstantsLocked(pw);

        pw.println();
        dumpMeasuredEnergyStatsLocked(pw);
    }
}

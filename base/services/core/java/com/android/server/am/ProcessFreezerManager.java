
/* Copyright (c) 2024 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.server.am;

import static android.os.Process.THREAD_PRIORITY_TOP_APP_BOOST;
import static com.android.server.cpu.CpuAvailabilityMonitoringConfig.CPUSET_ALL;
import static com.android.server.cpu.CpuAvailabilityMonitoringConfig.CPUSET_BACKGROUND;

import com.android.server.am.ProcessRecord;
import com.android.server.am.ProcessList;
import com.android.server.ServiceThread;
import com.android.server.LocalServices;
import com.android.server.cpu.CpuMonitorInternal;
import com.android.server.cpu.CpuAvailabilityMonitoringConfig;
import com.android.server.cpu.CpuAvailabilityInfo;

import android.os.Trace;
import android.os.IBinder;
import android.os.Process;
import android.os.Handler;
import android.util.Slog;
import android.util.ArrayMap;
import android.util.SparseArray;
import android.util.BoostFramework;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public class ProcessFreezerManager {
    private static ProcessFreezerManager mInstance;
    private static String TAG = "ProcessFreezerManager";
    private static BoostFramework mPerf = new BoostFramework();
    private static boolean ALREADY_READ_PROPERTIES = false;
    private static final long DEFAULT_LAUNCH_TIMEOUT = 2000;
    private static final long DEFAULT_DELAY_UNFREEZER_TIMEOUT = 1000;
    private static final int DEFAULT_CPU_USAGE_THRESHOLD = 60;
    private static final int DEFAULT_FREEZE_ADJ_THRESHOLD = ProcessList.PERCEPTIBLE_APP_ADJ + 1;
    private static final int REPORT_UNFREEZE_SERVICE_MSG = 0;
    private static final int FROZEN_AND_UPDATE_PROCESS_MSG = 1;
    private static final int REPORT_UNFREEZE_PROCESS_MSG = 2;

    public static final int FIRST_LAUNCH_FREEZE = 0;
    public static final int WARM_LAUNCH_FREEZE = 1;
    public static final int COLD_LAUNCH_FREEZE = 2;

    public static final int COMPLETE_LAUNCH_UNFREEZE = 0;
    public static final int INTERRUPT_LAUNCH_UNFREEZE = 1;
    public static final int TIMEOUT_LAUNCH_UNFREEZE = 2;
    public static final int REMOVE_PROCESS_UNFREEZE = 3;
    public static final int CROSS_LAUNCH_UNFREEZE = 4;
    public static final int DEPEND_LAUNCH_UNFREEZE = 5;

    private Object mPhenotypeFlagLock = new Object();
    private Object mFreezeFlagLock = new Object();
    private Object mCpuHighLoadLock = new Object();
    private final CpuLoadMonitor mCpuLoadMonitor = new CpuLoadMonitor();
    private final Handler mFreezerManagerHandler;
    private volatile boolean mCpuHighLoadFlag = false;
    private static volatile int mFreezeAdjThreshold = DEFAULT_FREEZE_ADJ_THRESHOLD;
    private static volatile long mLaunchTimeout = DEFAULT_LAUNCH_TIMEOUT;
    private static volatile int mCpuUsageThreshold = DEFAULT_CPU_USAGE_THRESHOLD;
    private static volatile boolean mCpuLoadMonitorBG = true;
    private static volatile long mDelayUnfreezeTimeout = DEFAULT_DELAY_UNFREEZER_TIMEOUT;
    private static volatile boolean mUseDebug = false;
    private static volatile boolean mUseFreezerManager = false;
    private static volatile boolean mUseCpuLoadMonitor = false;

    private final Freezer mFreezer;

    public class CpuLoadMonitor {
        private CpuMonitorInternal mCpuMonitorService = null;
        private int mCpuAvalabilityPercentThreshold = 100 - DEFAULT_CPU_USAGE_THRESHOLD;
        private int mCpuSet = CPUSET_BACKGROUND;
        public class CpuAvailabilityCallback implements CpuMonitorInternal.CpuAvailabilityCallback {
            @Override
            public void onAvailabilityChanged(CpuAvailabilityInfo info) {
                int currentCpuAvalabilityPercent = info.latestAvgAvailabilityPercent;
                boolean isHighLoad =
                    currentCpuAvalabilityPercent < mCpuAvalabilityPercentThreshold ? true : false;
                if (mUseDebug) {
                    if (isHighLoad) {
                        Slog.d(TAG,
                                "Current CPU usage is " + (100 - currentCpuAvalabilityPercent) +
                                " % and convert to high load");
                    } else {
                        Slog.d(TAG,
                                "Current CPU usage is " + (100 - currentCpuAvalabilityPercent) +
                                " % and convert to low load");
                    }
                }
                setCpuHighLoadFlagLocked(isHighLoad);
            }

            @Override
            public void onMonitoringIntervalChanged(long intervalMilliseconds){
                if (mUseDebug) {
                    Slog.d(TAG, "CPU load monitor interval convert to "+ intervalMilliseconds);
                }
            }
        }

        public void setCpuUsageThreshold(int cpuUsageThreshold) {
            int cpuAvalabilityPercentThreshold = 100 - cpuUsageThreshold;
            if (cpuAvalabilityPercentThreshold >= 0 && cpuAvalabilityPercentThreshold <= 100) {
                mCpuAvalabilityPercentThreshold = cpuAvalabilityPercentThreshold;
            } else {
                Slog.d(TAG,
                        cpuUsageThreshold + " is an invalid CPU usage threshold. The default " +
                        DEFAULT_CPU_USAGE_THRESHOLD + " will be used");
            }
        }

        /**
         * Set monitor which CPU load group
         * @param useBgCPU CPU load group
         * false: use CPUSET_ALL
         * true : use CPUSET_BACKGROUND
        */
        public void setCpuSet(boolean useBgCPU) {
            if (useBgCPU) {
                mCpuSet = CPUSET_BACKGROUND;
                Slog.d(TAG, "Monitor the BG CPU load");
            } else {
                mCpuSet = CPUSET_ALL;
                Slog.d(TAG, "Monitor the all CPU load");
            }
        }

        public void startCpuLoadMonitorOnce() {
            if (mCpuMonitorService != null) {
                return;
            }
            CpuAvailabilityCallback callback = new CpuAvailabilityCallback();
            CpuAvailabilityMonitoringConfig config =
                new CpuAvailabilityMonitoringConfig.Builder(mCpuSet).addThreshold(
                        mCpuAvalabilityPercentThreshold).build();
            mCpuMonitorService = LocalServices.getService(CpuMonitorInternal.class);
            if (mCpuMonitorService != null) {
                mCpuMonitorService.addCpuAvailabilityCallback(
                            /* executor= */ null, config, callback);
                Slog.d(TAG, "Already get CPU monitor service and add callback");
            }
        }
    }

    private void setCpuHighLoadFlagLocked(boolean isHighLoad) {
        synchronized (mCpuHighLoadLock) {
            mCpuHighLoadFlag = isHighLoad;
        }
    }

    private boolean getCpuHighLoadFlagLocked() {
        synchronized (mCpuHighLoadLock) {
            return mCpuHighLoadFlag;
        }
    }

    public static ProcessFreezerManager getInstance() {
        if (!ALREADY_READ_PROPERTIES) {
            ALREADY_READ_PROPERTIES = true;
            mUseFreezerManager = Boolean.valueOf(mPerf.perfGetProp(
                    "ro.vendor.perf.freezer_manager.enable", "false"));
            mUseCpuLoadMonitor = Boolean.valueOf(mPerf.perfGetProp(
                    "ro.vendor.perf.freezer_manager.enable_cpu_load_monitor", "false"));
            mCpuUsageThreshold = Integer.valueOf(mPerf.perfGetProp(
                    "ro.vendor.perf.freezer_manager.cpu_load_monitor_usage_threshold",
                    String.valueOf(DEFAULT_CPU_USAGE_THRESHOLD)));
            mCpuLoadMonitorBG = Boolean.valueOf(mPerf.perfGetProp(
                    "ro.vendor.perf.freezer_manager.cpu_load_monitor_cpuset_bg", "true"));
            mFreezeAdjThreshold = Integer.valueOf(mPerf.perfGetProp(
                    "ro.vendor.perf.freezer_manager.freeze_adj_threshold",
                    String.valueOf(DEFAULT_FREEZE_ADJ_THRESHOLD)));
            mLaunchTimeout = Integer.valueOf(mPerf.perfGetProp(
                    "ro.vendor.perf.freezer_manager.launch_timeout_threshold",
                    String.valueOf(DEFAULT_LAUNCH_TIMEOUT)));
            mDelayUnfreezeTimeout = Integer.valueOf(mPerf.perfGetProp(
                    "ro.vendor.perf.freezer_manager.delay_unfreeze_threshold",
                    String.valueOf(DEFAULT_DELAY_UNFREEZER_TIMEOUT)));
            mUseDebug = Boolean.valueOf(mPerf.perfGetProp(
                    "ro.vendor.perf.freezer_manager.enable_debug", "false"));

            Slog.d(TAG, "---- freezer manager settings ----");
            Slog.d(TAG, "use_freezer_manager=" + mUseFreezerManager);
            Slog.d(TAG, "enable_cpu_load_monitor=" + mUseCpuLoadMonitor);
            Slog.d(TAG, "cpu_load_monitor_usage_threshold=" + mCpuUsageThreshold);
            Slog.d(TAG, "cpu_load_monitor_cpuset_bg=" + mCpuLoadMonitorBG);
            Slog.d(TAG, "freeze_adj_threshold=" + mFreezeAdjThreshold);
            Slog.d(TAG, "launch_timeout_threshold=" + mLaunchTimeout);
            Slog.d(TAG, "delay_unfreeze_threshold=" + mDelayUnfreezeTimeout);
            Slog.d(TAG, "enable_debug=" + mUseDebug);
        }

        if (!mUseFreezerManager) {
            return null;
        }
        if (mInstance == null) {
            synchronized (ProcessFreezerManager.class) {
                if (mInstance == null) {
                    mInstance = new ProcessFreezerManager();
                }
            }
        }
        return mInstance;
    }

    private static final String getFreezeReason(int freezeReason) {
        switch (freezeReason) {
            case FIRST_LAUNCH_FREEZE:
                return "First launch";
            case WARM_LAUNCH_FREEZE:
                return "Warm launch";
            case COLD_LAUNCH_FREEZE:
                return "Cold launch";
            default:
                return "Unknown";
        }
    }

    private static final String getUnfreezeReason(int unfreezeReason) {
        switch (unfreezeReason) {
            case COMPLETE_LAUNCH_UNFREEZE:
                return "Complete launch";
            case INTERRUPT_LAUNCH_UNFREEZE:
                return "Interrupt launch";
            case TIMEOUT_LAUNCH_UNFREEZE:
                return "Launch timeout";
            case REMOVE_PROCESS_UNFREEZE:
                return "Remove main process";
            case CROSS_LAUNCH_UNFREEZE:
                return "Cross launch process";
            case DEPEND_LAUNCH_UNFREEZE:
                return "Dependent launch";
            default:
                return "Unknown";
        }
    }

    private static ServiceThread createAndStartFreezeThread() {
        final ServiceThread freezerManagerThread = new ServiceThread(
                "FreezerManagerThread", THREAD_PRIORITY_TOP_APP_BOOST, true /* allowIo */);
        freezerManagerThread.start();
        return freezerManagerThread;
    }

    private ProcessFreezerManager() {
        if (mUseCpuLoadMonitor) {
            mCpuLoadMonitor.setCpuUsageThreshold(mCpuUsageThreshold);
            mCpuLoadMonitor.setCpuSet(mCpuLoadMonitorBG);
        }

        mFreezer = new Freezer();

        mFreezerManagerHandler = new Handler(createAndStartFreezeThread().getLooper(), msg -> {
            switch (msg.what) {
                case REPORT_UNFREEZE_SERVICE_MSG: {
                    final int unfreezeReason = msg.arg1;
                    final ProcessRecord app = (ProcessRecord)msg.obj;
                    if (!checkInFreezeProcessLocked(app)) {
                        Slog.d(TAG, "skip unfreeze service: skip reason: " + app.processName +
                                " has been removed from freeze list");
                        break;
                    }
                    if (mUseDebug) {
                        String unfreezeReasonStr = getUnfreezeReason(unfreezeReason);
                        Slog.d(TAG, "= start unfreeze service: " + app.processName +
                                ", reason: " + unfreezeReasonStr);
                        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                                "start unfreeze service: " + app.processName +
                                ", reason: " + unfreezeReasonStr);
                    }

                    unFreezeProcess(app);
                    removeProcessFromListLocked(app);

                    if (mUseDebug) {
                        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    }
                } break;
                case FROZEN_AND_UPDATE_PROCESS_MSG: {
                    final int freezeReason = msg.arg1;
                    final String packageName = (String)msg.obj;
                    if (mUseDebug) {
                        String freezeReasonStr = getFreezeReason(freezeReason);
                        Slog.d(TAG,
                                "# start freeze processes which adj >= " + mFreezeAdjThreshold +
                                " for " + packageName + ", reason: " + freezeReasonStr);
                        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                                "start freeze processes which adj >= " + mFreezeAdjThreshold +
                                " for " + packageName + ", reason: " + freezeReasonStr);
                    }

                    synchronized (mFreezeFlagLock) {
                        final SparseArray<ProcessRecord> needFreezeProcesses =
                                getFreezeProcessesLocked(packageName);
                        if (needFreezeProcesses != null) {
                            List<ProcessRecord> pidsToRemove = new ArrayList<>();
                            for (int i = 0; i < needFreezeProcesses.size(); i++) {
                                int pid = needFreezeProcesses.keyAt(i);
                                ProcessRecord app = needFreezeProcesses.valueAt(i);
                                if (!freezeProcess(app)) {
                                    pidsToRemove.add(app);
                                }
                            }
                            removeProcessFromListLocked(packageName, pidsToRemove);
                            if (mUseDebug) {
                                Slog.d(TAG, "# number of processes to freeze is " +
                                        needFreezeProcesses.size() + " for " + packageName);
                            }
                        } else {
                            Slog.d(TAG, "freeze object is null for " + packageName);
                        }
                    } // end of synchronized (mFreezeFlagLock)

                    if (mUseDebug) {
                        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    }
                } break;
                case REPORT_UNFREEZE_PROCESS_MSG: {
                    final int unfreezeReason = msg.arg1;
                    final String packageName = (String)msg.obj;
                    if (!packageContainKey(packageName)) {
                        Slog.e(TAG, "Alread triggered unfreeze for " + packageName);
                        break;
                    }

                    if (mUseDebug) {
                        String unfreezeReasonStr = getUnfreezeReason(unfreezeReason);
                        Slog.d(TAG, "= start unfreeze processes for " + packageName +
                                ", reason: " + unfreezeReasonStr);
                        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                                "start unfreeze processes for " + packageName +
                                ", reason: " + unfreezeReasonStr);
                    }

                    synchronized (mFreezeFlagLock) {
                        final SparseArray<ProcessRecord> needUnfreezeProcesses =
                                getUnfreezeProcessesLocked(packageName);
                        if (needUnfreezeProcesses != null) {
                            for (int i = 0; i < needUnfreezeProcesses.size(); i++) {
                                int pid = needUnfreezeProcesses.keyAt(i);
                                ProcessRecord app = needUnfreezeProcesses.valueAt(i);
                                unFreezeProcess(app);
                            }
                            if (mUseDebug) {
                                Slog.d(TAG, "= number of processes to unfreeze is " +
                                        needUnfreezeProcesses.size() + " for " + packageName);
                            }
                            removePackageLocked(packageName);
                            removeFreezeRecordLocked(packageName);
                        } else {
                            Slog.d(TAG, "unfreeze object is null for " + packageName);
                        }
                    } // end of synchronized (mFreezeFlagLock)

                    if (mUseDebug) {
                        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    }
                } break;
                default:
                    return true;
            }
            return true;
        });
    }

    final PidMap mPidsSelfLocked = new PidMap();
    static final class PidMap {
        private final SparseArray<ProcessRecord> mPidMap = new SparseArray<>();

        ProcessRecord get(int pid) {
            return mPidMap.get(pid);
        }

        int size() {
            return mPidMap.size();
        }

        ProcessRecord valueAt(int index) {
            return mPidMap.valueAt(index);
        }

        int keyAt(int index) {
            return mPidMap.keyAt(index);
        }

        int indexOfKey(int key) {
            return mPidMap.indexOfKey(key);
        }

        void doAddInternal(int pid, ProcessRecord app) {
            mPidMap.put(pid, app);
        }

        boolean doRemoveInternal(int pid, ProcessRecord app) {
            final ProcessRecord existingApp = mPidMap.get(pid);
            if (existingApp != null && existingApp.getStartSeq() == app.getStartSeq()) {
                mPidMap.remove(pid);
                return true;
            }
            return false;
        }
    }

    void addPidLocked(ProcessRecord app) {
        synchronized (mPidsSelfLocked) {
            mPidsSelfLocked.doAddInternal(app.getPid(), app);
        }
    }

    boolean removePidLocked(int pid, ProcessRecord app) {
        synchronized (mPidsSelfLocked) {
            return mPidsSelfLocked.doRemoveInternal(pid, app);
        }
    }

    ProcessRecord findProcessByNameLocked(String processName) {
        synchronized (mPidsSelfLocked) {
            for ( int i = 0; i < mPidsSelfLocked.size(); i++) {
                ProcessRecord foundProcess = mPidsSelfLocked.valueAt(i);
                if (foundProcess.processName.equals(processName)) {
                    return foundProcess;
                }
            }
        }
        return null;
    }

    private SparseArray<ProcessRecord> findNeedFreezeProcessesLocked(String processName) {
        SparseArray<ProcessRecord> needFreezeProcesses = new SparseArray<>();
        synchronized (mPidsSelfLocked) {
            for (int i = 0; i < mPidsSelfLocked.size(); i++) {
                final ProcessRecord app = mPidsSelfLocked.valueAt(i);
                final ProcessStateRecord state = app.mState;
                if (state.getCurAdj() >= ProcessList.FOREGROUND_APP_ADJ) {
                    String appPackageName = app.info.packageName;
                    if (processName.equals(appPackageName)) {
                        continue;
                    }
                    needFreezeProcesses.put(app.getPid(), app);
                }
            }
            return needFreezeProcesses;
        }
    }

    final PackageMap mPackagesSelfLocked = new PackageMap();
    static final class PackageMap {
        // key  : application package name
        // value: list of processes to freeze
        private final Map<String, SparseArray<ProcessRecord>>  mPackageMap = new HashMap<>();

        SparseArray<ProcessRecord> get(String processName) {
            return mPackageMap.get(processName);
        }

        boolean contains(String processName) {
            return mPackageMap.containsKey(processName);
        }

        int size() {
            return mPackageMap.size();
        }

        ArrayList<String> getAllKeys() {
            ArrayList<String> packageNameList = new ArrayList<String>();
            for (String packageName : mPackageMap.keySet()) {
                packageNameList.add(packageName);
            }
            return packageNameList;
        }

        void put(String processName, SparseArray<ProcessRecord> pidList) {
            mPackageMap.put(processName, pidList);
        }

        boolean remove(String processName) {
            if (mPackageMap.containsKey(processName)) {
                mPackageMap.remove(processName);
                return true;
            }
            return false;
        }

        void clear() {
            mPackageMap.clear();
        }
    }

    private boolean checkInFreezeProcessLocked(ProcessRecord app) {
        int pid = app.getPid();
        synchronized (mPackagesSelfLocked) {
            for (String packageName : mPackagesSelfLocked.mPackageMap.keySet()) {
                SparseArray<ProcessRecord> freezeList = mPackagesSelfLocked.get(packageName);
                if (freezeList.get(pid) != null) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean isBoundClient(ProcessRecord app, String processName, boolean equal) {
        final ProcessServiceRecord psr = app.mServices;
        int sevicesNum = psr.numberOfRunningServices();
        boolean isBound = false;
        for (int i = sevicesNum - 1; i >= 0; i--) {
            final ServiceRecord sr = psr.getRunningServiceAt(i);
            if (sr == null) {
                continue;
            }

            ArrayMap<IBinder, ArrayList<ConnectionRecord>> conns = sr.getConnections();
            for (int conni = conns.size() - 1; conni >= 0; conni--) {
                ArrayList<ConnectionRecord> c = conns.valueAt(conni);
                for (int con = 0; con < c.size(); con++) {
                    ConnectionRecord cr = c.get(con);
                    if (equal) {
                        if (cr.clientPackageName.equals(processName)) {
                            isBound = true;
                            if (mUseDebug) {
                                Slog.d(TAG,
                                        "Immediately unfreeze service " + app.processName +
                                        ". Reason: depend on service(" + sr.processName +
                                        ") when launch " + processName);
                            }
                            return isBound;
                        }
                    } else {
                        isBound = true;
                        if (mUseDebug) {
                            Slog.d(TAG,
                                    "  " + app.processName + " has been bound client (" +
                                    cr.clientPackageName + ").");
                            continue;
                        }
                        return isBound;
                    }
                }
            } // end of for (int conni = conns.size() - 1; ...
        } // end of for (int i = sevicesNum - 1; ...
        return isBound;
    }

    public boolean checkNeedFreezeProcessLocked(ProcessRecord app) {
        int pid = app.getPid();
        boolean isInList = false;
        synchronized (mPackagesSelfLocked) {
            for (String packageName : mPackagesSelfLocked.mPackageMap.keySet()) {
                SparseArray<ProcessRecord> freezeList = mPackagesSelfLocked.get(packageName);
                if (freezeList.get(pid) == null) {
                    continue;
                }
                if (isBoundClient(app, packageName, true)) {
                    isInList = true;
                }
            }
            return isInList;
        }
    }

    private void removeProcessFromListLocked(ProcessRecord app) {
        int pid = app.getPid();
        synchronized (mPackagesSelfLocked) {
            for (String packageName : mPackagesSelfLocked.mPackageMap.keySet()) {
                SparseArray<ProcessRecord> freezeList = mPackagesSelfLocked.get(packageName);
                if (freezeList.get(pid) != null) {
                    freezeList.remove(pid);
                }
            }
        }
    }

    private void removeProcessFromListLocked(String processName, List<ProcessRecord> pidsToRemove) {
        synchronized (mPackagesSelfLocked) {
            SparseArray<ProcessRecord> freezeList = mPackagesSelfLocked.get(processName);
            for (ProcessRecord process : pidsToRemove) {
                freezeList.remove(process.getPid());
            }
        }
    }

    private boolean packageContainKey(String processName) {
        synchronized (mPackagesSelfLocked) {
            return mPackagesSelfLocked.contains(processName);
        }
    }

    private SparseArray<ProcessRecord> getFreezeProcessesLocked(String processName) {
        synchronized (mPackagesSelfLocked) {
            if (mPackagesSelfLocked.contains(processName)) {
                return mPackagesSelfLocked.get(processName);
            }
            return null;
        }
    }

    private SparseArray<ProcessRecord> getUnfreezeProcessesLocked(String processName) {
        synchronized (mPackagesSelfLocked) {
            if (mPackagesSelfLocked.contains(processName)) {
                return mPackagesSelfLocked.get(processName);
            }
            return null;
        }
    }

    private int getPackageSizeLocked() {
        synchronized (mPackagesSelfLocked) {
            return mPackagesSelfLocked.size();
        }
    }

    private void addPackageLocked(String processName, SparseArray<ProcessRecord> pidList) {
        synchronized (mPackagesSelfLocked) {
            mPackagesSelfLocked.put(processName, pidList);
        }
    }

    private boolean removePackageLocked(String processName) {
        synchronized (mPackagesSelfLocked) {
            SparseArray<ProcessRecord> freezeList = mPackagesSelfLocked.get(processName);
            freezeList.clear();
            return mPackagesSelfLocked.remove(processName);
        }
    }

    private ArrayList<String> getPackageNameListLocked() {
        synchronized (mPackagesSelfLocked) {
            return mPackagesSelfLocked.getAllKeys();
        }
    }

    private void clearPackageLocked() {
        synchronized (mPackagesSelfLocked) {
            mPackagesSelfLocked.clear();
        }
    }

    private final Map<String, Integer>  mProcessFreezeRecordLocked = new HashMap<>();
    private int getFreezeRecordLocked(String processName) {
        synchronized (mProcessFreezeRecordLocked) {
            if (mProcessFreezeRecordLocked.containsKey(processName)){
                return mProcessFreezeRecordLocked.get(processName);
            }
            return -1;
        }
    }

    private void addFreezeRecordLocked(String processName, int freezeReason) {
        synchronized (mProcessFreezeRecordLocked) {
            mProcessFreezeRecordLocked.put(processName, freezeReason);
        }
    }

    private void removeFreezeRecordLocked(String processName) {
        synchronized (mProcessFreezeRecordLocked) {
            if (mProcessFreezeRecordLocked.containsKey(processName)){
                mProcessFreezeRecordLocked.remove(processName);
            }
        }
    }

    private void unFreezeProcess(ProcessRecord app) {
        final ProcessCachedOptimizerRecord opt = app.mOptRecord;
        final ProcessStateRecord state = app.mState;
        int pid = app.getPid();
        int uid = app.uid;
        String processName = app.processName;
        String logInfo = String.format("app info: uid=%d, pid=%d, adj=%d, frozen=%b, proc name=%s",
                uid, pid, state.getCurAdj(), opt.isFrozen(), processName);
        // skip default frozen process and killed process (pid==0)
        if (opt.isFrozen() || pid == 0){
            if (mUseDebug) {
                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "skip unfreeze: " + logInfo);
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                if (opt.isFrozen()) {
                    Slog.d(TAG,
                            " *skip unfreeze: skip reason: process is frozen by default freezer. "
                            + logInfo);
                }
                if (pid == 0) {
                    Slog.d(TAG," *skip unfreeze: skip reason: process is dead. " + logInfo);
                }
            }
            return;
        }

        if (mUseDebug) {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, logInfo);
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "unfreeze binder: " + logInfo);
        }

        try {
            int rc = mFreezer.freezeBinder(pid, false, 2 /* timeout_ms */);
            if (rc != 0) {
                Slog.w(TAG, " *unable to unfreeze binder: " +  logInfo + " " + rc );
            } else {
                if (mUseDebug) {
                    Slog.d(TAG,"  unfreeze binder:  " + logInfo);
                }
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, " *unable to unfreeze binder for " + pid + ": " + e);
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    "unable to unfreeze binder: " + logInfo);
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }

        if (mUseDebug) {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER); // end of unfreeze binder
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "unfreeze process: " + logInfo);
        }

        try{
            mFreezer.setProcessFrozen(pid, uid, false);
            if (mUseDebug) {
                Slog.d(TAG, "  unfreeze process: " +  logInfo);
            }
        } catch (Exception e) {
            Slog.w(TAG, " *unable to unfreeze process: " + logInfo + " " + e);
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    "unable to unfreeze process: " + logInfo);
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }

        if (mUseDebug) {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER); // end of unfreeze process
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER); // end of app info
        }
    }

    private boolean freezeProcess(ProcessRecord app) {
        final ProcessCachedOptimizerRecord opt = app.mOptRecord;
        final ProcessStateRecord state = app.mState;
        final ProcessServiceRecord psr = app.mServices;
        int pid = app.getPid();
        int uid = app.uid;
        int sevicesNum = psr.numberOfRunningServices();
        String processName = app.processName;
        String logInfo = String.format(
                "app info: uid=%d, pid=%d, adj=%d, frozen=%b, services=%d, proc name=%s",
                uid, pid, state.getCurAdj(), opt.isFrozen(), sevicesNum, processName);
        boolean freezeBinderSuccess = false;
        boolean freezeProcessSuccess = false;
        // skip freeze process that is frozen by system freezer
        if (opt.isFrozen() || pid == 0) {
            if (mUseDebug) {
                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                        "skip frozen process: "+ logInfo);
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                if (opt.isFrozen()) {
                    Slog.d(TAG,
                            " *skip freeze: skip reason: process is frozen by default freezer. " +
                            logInfo);
                }
                if (pid == 0) {
                    Slog.d(TAG," *skip freeze: skip reason: process is dead. " + logInfo);
                }
            }
            return false;
        }

        if (state.getCurAdj() < mFreezeAdjThreshold) {
            if (mUseDebug) {
                Slog.d(TAG," *skip freeze: skip reason: process's adj < " +
                        mFreezeAdjThreshold + ". " + logInfo);
            }
            return false;
        }

        if (state.getCurAdj() <= ProcessList.PERCEPTIBLE_APP_ADJ) {
            boolean hasBoundClient = isBoundClient(app, app.processName, false);
            if (hasBoundClient) {
                if (mUseDebug) {
                    Slog.d(TAG," *skip freeze: skip reason: process's service has bound client. " +
                            logInfo);
                    Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                            "skip freeze: skip reason: process's service has bound client. " +
                            logInfo);
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                }
                return false;
            }
        }

        if (mUseDebug) {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, logInfo);
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "freeze binder: " + logInfo);
        }

        try {
            int rc = mFreezer.freezeBinder(pid, true, 2 /* timeout_ms */);
            if (rc != 0){
                Slog.w(TAG, " *unable to freeze binder for " + pid + ": " + rc);
            } else {
                freezeBinderSuccess = true;
                if (mUseDebug) {
                    Slog.d(TAG,"  freeze binder : " + logInfo);
                }
            }
        } catch (RuntimeException e) {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    "unable to freeze binder: " + logInfo);
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            Slog.w(TAG, "  unbale to freeze binder: " + logInfo);
        }

        if (mUseDebug) {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER); // end of freeze binder
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "freeze process: " + logInfo);
        }

        try {
            if (freezeBinderSuccess) {
                mFreezer.setProcessFrozen(pid, uid, true);
                if (mUseDebug) {
                    Slog.d(TAG, "  freeze process: " + logInfo);
                }
                freezeProcessSuccess = true;
            } else {
                Slog.d(TAG,
                        " *skip freeze process: skip reason: unable to freeze process's binder. " +
                        logInfo);
            }
        } catch (RuntimeException e) {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    "unable to freeze process: " + logInfo);
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            Slog.w(TAG, "  unbale to freeze process: " + logInfo);
        }

        if (mUseDebug) {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER); // end of freeze process
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER); // end of app info
        }
        return freezeProcessSuccess;
    }

    public boolean isMainProcess(String packageName) {
        return !packageName.contains(":");
    }

    public void startFreeze(String packageName, int freezeReason) {
        if (mUseCpuLoadMonitor) {
            mCpuLoadMonitor.startCpuLoadMonitorOnce();
        }
        startFreezeInternal(packageName, freezeReason);
    }

    private void startFreezeInternal(String packageName, int freezeReason) {
        if (!isMainProcess(packageName)) {
            return;
        }

        if (packageContainKey(packageName)) {
            // make sure that already triggered freeze.
            Slog.d(TAG, "Already triggered freeze for " + packageName);
            return;
        }

        if (mUseCpuLoadMonitor && !getCpuHighLoadFlagLocked()) {
            if (mUseDebug) {
                Slog.d(TAG, "Skip freeze: skip reason: CPU load is low when launching " +
                        packageName);
            }
            return;
        }
        // Avoid cross launch
        startUnfreezeAll();
        SparseArray<ProcessRecord> needFreezeProcesses = findNeedFreezeProcessesLocked(packageName);
        if (needFreezeProcesses.size() == 0) {
            if (mUseDebug) {
                Slog.d(TAG,
                        "skip freeze: skip reason: No proper processes to freeze for " +
                        packageName);
            }
            return;
        }
        addFreezeRecordLocked(packageName, freezeReason);
        addPackageLocked(packageName, needFreezeProcesses);
        mFreezerManagerHandler.sendMessage(mFreezerManagerHandler.obtainMessage(
                FROZEN_AND_UPDATE_PROCESS_MSG, freezeReason, 0 /* unused */, packageName));
        startTimeoutUnfreeze(packageName);
    }

    private void startTimeoutUnfreeze(String packageName){
        // add a timeout unfreeze mechanism
        mFreezerManagerHandler.sendMessageDelayed(mFreezerManagerHandler.obtainMessage(
                REPORT_UNFREEZE_PROCESS_MSG, TIMEOUT_LAUNCH_UNFREEZE, 0 /* unused */, packageName),
                mLaunchTimeout);
    }

    private void removeTimeoutUnfreeze(String packageName){
        // remove timeout unfreeze mechanism
        mFreezerManagerHandler.removeMessages(REPORT_UNFREEZE_PROCESS_MSG, packageName);
    }

    private void startUnfreezeAll() {
        ArrayList<String> packageNameList = getPackageNameListLocked();
        for (String packageName : packageNameList) {
            startUnfreezeInternal(packageName, CROSS_LAUNCH_UNFREEZE);
        }
    }

    // unfreeze process that the application depends on when it launchs.
    public void startUnfreezeService(ProcessRecord app, int unfreezeReason) {
        mFreezerManagerHandler.sendMessage(mFreezerManagerHandler.obtainMessage(
                REPORT_UNFREEZE_SERVICE_MSG, unfreezeReason, 0 /* unused */, app));
    }

    public void startUnfreeze(String packageName, int unfreezeReason) {
        startUnfreezeInternal(packageName, unfreezeReason);
    }

    private void startUnfreezeInternal(String packageName, int unfreezeReason) {
        if (!packageContainKey(packageName)) {
            return;
        }

        removeTimeoutUnfreeze(packageName);
        if (unfreezeReason == COMPLETE_LAUNCH_UNFREEZE) {
            int freezeReason = getFreezeRecordLocked(packageName);
            if (freezeReason == WARM_LAUNCH_FREEZE) {
                mFreezerManagerHandler.sendMessage(mFreezerManagerHandler.obtainMessage(
                        REPORT_UNFREEZE_PROCESS_MSG, unfreezeReason, 0 /* unused */, packageName));
            } else {
                mFreezerManagerHandler.sendMessageDelayed(mFreezerManagerHandler.obtainMessage(
                        REPORT_UNFREEZE_PROCESS_MSG, unfreezeReason, 0 /* unused */, packageName),
                        mDelayUnfreezeTimeout);
            }
        } else {
            mFreezerManagerHandler.sendMessage(mFreezerManagerHandler.obtainMessage(
                    REPORT_UNFREEZE_PROCESS_MSG, unfreezeReason, 0 /* unused */, packageName));
        }
    }

    public boolean useFreezerManager() {
        synchronized(mPhenotypeFlagLock) {
            return mUseFreezerManager;
        }
    }
}

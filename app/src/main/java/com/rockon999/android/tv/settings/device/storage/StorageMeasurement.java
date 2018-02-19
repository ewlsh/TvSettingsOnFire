/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.rockon999.android.tv.settings.device.storage;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.PackageManager;
import android.content.pm.PackageStats;
import android.os.Environment;
import android.os.Environment.UserEnvironment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageVolume;
import android.util.Log;
import android.util.SparseLongArray;

import com.android.internal.app.IMediaContainerService;
import com.rockon999.android.tv.settings.device.storage.util.StorageUtil.StorageMeasurer;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * Utility for measuring the disk usage of internal storage or a physical
 * {@link StorageVolume}. Connects with a remote {@link IMediaContainerService}
 * and delivers results to {@link MeasurementReceiver}.
 * <p>
 * Pulled from Android Settings App.
 */
public class StorageMeasurement {
    private static final String TAG = "StorageMeasurement";

    private static final boolean LOCAL_LOGV = true;
    private static final boolean LOGV = LOCAL_LOGV && Log.isLoggable(TAG, Log.VERBOSE);

    /**
     * Media types to measure on external storage.
     */
    private static final Set<String> sMeasureMediaTypes = new HashSet<>(10);

    static {
        sMeasureMediaTypes.add(Environment.DIRECTORY_DCIM);
        sMeasureMediaTypes.add(Environment.DIRECTORY_MOVIES);
        sMeasureMediaTypes.add(Environment.DIRECTORY_PICTURES);
        sMeasureMediaTypes.add(Environment.DIRECTORY_MUSIC);
        sMeasureMediaTypes.add(Environment.DIRECTORY_ALARMS);
        sMeasureMediaTypes.add(Environment.DIRECTORY_NOTIFICATIONS);
        sMeasureMediaTypes.add(Environment.DIRECTORY_RINGTONES);
        sMeasureMediaTypes.add(Environment.DIRECTORY_PODCASTS);
        sMeasureMediaTypes.add(Environment.DIRECTORY_DOWNLOADS);
        sMeasureMediaTypes.add(Environment.DIRECTORY_ANDROID); // todo remove this?
    }

    private static final HashMap<StorageVolume, StorageMeasurement> sInstances = new HashMap<>();

    /**
     * Obtain shared instance of {@link StorageMeasurement} for given physical
     * {@link StorageVolume}, or internal storage if {@code null}.
     */
    public static StorageMeasurement getInstance(Context context, StorageVolume volume) {
        synchronized (sInstances) {
            StorageMeasurement value = sInstances.get(volume);
            if (value == null) {
                value = new StorageMeasurement(context.getApplicationContext(), volume);
                sInstances.put(volume, value);
            }
            return value;
        }
    }

    public static class MeasurementDetails {
        public long totalSize;
        public long availSize;

        /**
         * Total apps disk usage.
         * <p>
         * When measuring internal storage, this value includes the code size of
         * all apps (regardless of install status for current user), and
         * internal disk used by the current user's apps. When the device
         * emulates external storage, this value also includes emulated storage
         * used by the current user's apps.
         * <p>
         * When measuring a physical {@link StorageVolume}, this value includes
         * usage by all apps on that volume.
         */
        public long appsSize;

        /**
         * Total cache disk usage by apps.
         */
        public long cacheSize;

        /**
         * Total media disk usage, categorized by types such as
         * {@link Environment#DIRECTORY_MUSIC}.
         * <p>
         * When measuring internal storage, this reflects media on emulated
         * storage for the current user.
         * <p>
         * When measuring a physical {@link StorageVolume}, this reflects media
         * on that volume.
         */
        public HashMap<String, Long> mediaSize = new HashMap<String, Long>();

        /**
         * Misc external disk usage for the current user, unaccounted in
         * {@link #mediaSize}.
         */
        public long miscSize;

        /**
         * Total disk usage for users, which is only meaningful for emulated
         * internal storage. Key is {@link UserHandle}.
         */
        public SparseLongArray usersSize = new SparseLongArray();
    }

    public interface MeasurementReceiver {
        public void updateApproximate(StorageMeasurement meas, long totalSize, long availSize);

        public void updateDetails(StorageMeasurement meas, MeasurementDetails details);
    }

    private volatile WeakReference<MeasurementReceiver> mReceiver;

    /**
     * Physical volume being measured, or {@code null} for internal.
     */
    private final StorageVolume mVolume;

    private final boolean mIsInternal;
    private final boolean mIsPrimary;

    private final MeasurementHandler mHandler;

    private long mTotalSize;
    private long mAvailSize;

    private List<FileInfo> mFileInfoForMisc;

    @SuppressLint("NewApi")
    private StorageMeasurement(Context context, StorageVolume volume) {
        mVolume = volume;
        mIsInternal = volume == null;
        mIsPrimary = volume != null && volume.isPrimary();

        // Start the thread that will measure the disk usage.
        final HandlerThread handlerThread = new HandlerThread("MemoryMeasurement");
        handlerThread.start();
        mHandler = new MeasurementHandler(context, handlerThread.getLooper());
    }

    public void setReceiver(MeasurementReceiver receiver) {
        if (mReceiver == null || mReceiver.get() == null) {
            mReceiver = new WeakReference<MeasurementReceiver>(receiver);
        }
    }

    public void measure() {
        if (!mHandler.hasMessages(MeasurementHandler.MSG_MEASURE)) {
            mHandler.sendEmptyMessage(MeasurementHandler.MSG_MEASURE);
        }
    }

    public void cleanUp() {
        mReceiver = null;
        mHandler.removeMessages(MeasurementHandler.MSG_MEASURE);
    }

    public void invalidate() {
        mHandler.sendEmptyMessage(MeasurementHandler.MSG_INVALIDATE);
    }

    private void sendInternalApproximateUpdate() {
        MeasurementReceiver receiver = (mReceiver != null) ? mReceiver.get() : null;
        if (receiver == null) {
            return;
        }
        receiver.updateApproximate(this, mTotalSize, mAvailSize);
    }

    private void sendExactUpdate(MeasurementDetails details) {
        MeasurementReceiver receiver = (mReceiver != null) ? mReceiver.get() : null;
        if (receiver == null) {
            if (LOGV) {
                Log.i(TAG, "measurements dropped because receiver is null! wasted effort");
            }
            return;
        }
        receiver.updateDetails(this, details);
    }

    private static class StatsObserver extends IPackageStatsObserver.Stub {
        private final boolean mIsInternal;
        private final MeasurementDetails mDetails;

        private final Message mFinished;

        private int mRemaining;

        public StatsObserver(boolean isInternal, MeasurementDetails details,
                             Message finished, int remaining) {
            mIsInternal = isInternal;
            mDetails = details;

            mFinished = finished;
            mRemaining = remaining;
        }

        @Override
        public void onGetStatsCompleted(PackageStats stats, boolean succeeded) {
            synchronized (mDetails) {
                if (succeeded) {
                    addStatsLocked(stats);
                }
                if (--mRemaining == 0) {
                    mFinished.sendToTarget();
                }
            }
        }

        private void addStatsLocked(PackageStats stats) {
            if (mIsInternal) {
                long codeSize = stats.codeSize;
                long dataSize = stats.dataSize;
                long cacheSize = stats.cacheSize;
                if (Environment.isExternalStorageEmulated()) {
                    // Include emulated storage when measuring internal. OBB is
                    // shared on emulated storage, so treat as code.
                    codeSize += stats.externalCodeSize + stats.externalObbSize;
                    dataSize += stats.externalDataSize + stats.externalMediaSize;
                    cacheSize += stats.externalCacheSize;
                }

                // Count code and data for current user
                // REMOVED (Fire TV devices are single user devices, so this should be irrelevant)
                // if (stats.userHandle == mCurrentUser) {
                mDetails.appsSize += codeSize;
                mDetails.appsSize += dataSize;
                //}

                // User summary only includes data (code is only counted once
                // for the current user)
                addValue(mDetails.usersSize, stats.userHandle, dataSize);

                // Include cache for all users
                mDetails.cacheSize += cacheSize;

            } else {
                // Physical storage; only count external sizes
                mDetails.appsSize += stats.externalCodeSize + stats.externalDataSize
                        + stats.externalMediaSize + stats.externalObbSize;
                mDetails.cacheSize += stats.externalCacheSize;
            }
        }
    }

    private class MeasurementHandler extends Handler {
        public static final int MSG_MEASURE = 1;
        public static final int MSG_COMPLETED = 4;
        public static final int MSG_INVALIDATE = 5;

        private MeasurementDetails mCached;

        private final WeakReference<Context> mContext;

        public MeasurementHandler(Context context, Looper looper) {
            super(looper);
            mContext = new WeakReference<Context>(context);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_MEASURE: {
                    if (mCached != null) {
                        sendExactUpdate(mCached);
                        break;
                    }

                    measureApproximateStorage();
                    measureExactStorage();
                    break;
                }
                case MSG_COMPLETED: {
                    mCached = (MeasurementDetails) msg.obj;
                    sendExactUpdate(mCached);
                    break;
                }
                case MSG_INVALIDATE: {
                    mCached = null;
                    break;
                }
            }
        }

        @SuppressLint("NewApi")
        private void measureApproximateStorage() {
            final String path = mVolume != null ? mVolume.getPath()
                    : Environment.getDataDirectory().getPath();
            try {
                StatFs statFs = new StatFs(path);
                long blockSize = statFs.getBlockSizeLong();
                mTotalSize = (statFs.getBlockCountLong() * blockSize);
                mAvailSize = (statFs.getAvailableBlocksLong() * blockSize);
            } catch (Exception e) {
                Log.w(TAG, "Problem in container service", e);
            }

            sendInternalApproximateUpdate();
        }

        @SuppressLint("NewApi")
        private void measureExactStorage() {
            final Context context = mContext != null ? mContext.get() : null;
            if (context == null) {
                return;
            }

            final MeasurementDetails details = new MeasurementDetails();
            final Message finished = obtainMessage(MSG_COMPLETED, details);

            details.totalSize = mTotalSize;
            details.availSize = mAvailSize;

            final UserManager userManager = (UserManager) context.getSystemService(
                    Context.USER_SERVICE);

            List<UserHandle> users = Collections.emptyList();

            if (userManager != null) {
                users = userManager.getUserProfiles();
            }


            // Measure media types for emulated storage, or for primary physical
            // external volume
            final boolean measureMedia = (mIsInternal && Environment.isExternalStorageEmulated())
                    || mIsPrimary;
            if (measureMedia) {
                for (String type : sMeasureMediaTypes) {
                    final File path = Environment.getExternalStoragePublicDirectory(type);
                    final long size = getDirectorySize(path);
                    details.mediaSize.put(type, size);
                }
            }

            // Measure misc files not counted under media
            if (mIsInternal || mIsPrimary) {
                final File path = mIsInternal ? Environment.getExternalStorageDirectory()
                        : mVolume.getPathFile();
                details.miscSize = measureMisc(path);
            }

            // Measure total emulated storage of all users; internal apps data
            // will be spliced in later
            for (UserHandle user : users) {
                final UserEnvironment userEnv = new UserEnvironment(user.getIdentifier());
                final long size = getDirectorySize(userEnv.getExternalStorageDirectory());
                addValue(details.usersSize, user.getIdentifier(), size);
            }

            // Measure all apps for all users
            final PackageManager pm = context.getPackageManager();
            if (mIsInternal || mIsPrimary) {
                @SuppressLint("WrongConstant") final List<ApplicationInfo> apps = pm.getInstalledApplications(
                        PackageManager.GET_UNINSTALLED_PACKAGES
                                | PackageManager.GET_DISABLED_COMPONENTS);

                final int count = users.size() * apps.size();
                final StatsObserver observer = new StatsObserver(
                        mIsInternal, details, finished, count);

                for (UserHandle user : users) {
                    for (ApplicationInfo app : apps) {
                        pm.getPackageSizeInfo(app.packageName, user.getIdentifier(), observer);
                    }
                }

            } else {
                finished.sendToTarget();
            }
        }
    }

    private static long getDirectorySize(File path) {
        try {
            StorageMeasurer measurer = StorageMeasurer.createAndCalculate(path);
            return measurer.getSize();
        } catch (Exception e) {
            Log.w(TAG, "Could not read memory from default container service for " + path, e);
            return 0;
        }
    }

    private long measureMisc(File dir) {
        mFileInfoForMisc = new ArrayList<FileInfo>();

        final File[] files = dir.listFiles();
        if (files == null) return 0;

        // Get sizes of all top level nodes except the ones already computed
        long counter = 0;
        long miscSize = 0;

        for (File file : files) {
            final String path = file.getAbsolutePath();
            final String name = file.getName();
            if (sMeasureMediaTypes.contains(name)) {
                continue;
            }

            if (file.isFile()) {
                final long fileSize = file.length();
                mFileInfoForMisc.add(new FileInfo(path, fileSize, counter++));
                miscSize += fileSize;
            } else if (file.isDirectory()) {
                final long dirSize = getDirectorySize(file);
                mFileInfoForMisc.add(new FileInfo(path, dirSize, counter++));
                miscSize += dirSize;
            } else {
                // Non directory, non file: not listed
            }
        }

        // sort the list of FileInfo objects collected above in descending order of their sizes
        Collections.sort(mFileInfoForMisc);

        return miscSize;
    }

    static class FileInfo implements Comparable<FileInfo> {
        final String mFileName;
        final long mSize;
        final long mId;

        FileInfo(String fileName, long size, long id) {
            mFileName = fileName;
            mSize = size;
            mId = id;
        }

        @Override
        public int compareTo(FileInfo that) {
            if (this == that || mSize == that.mSize) return 0;
            else return (mSize < that.mSize) ? 1 : -1; // for descending sort
        }

        @Override
        public String toString() {
            return mFileName + " : " + mSize + ", id:" + mId;
        }
    }

    private static void addValue(SparseLongArray array, int key, long value) {
        array.put(key, array.get(key) + value);
    }
}

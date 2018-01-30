/*
 * Copyright (c) 2016, The CyanogenMod Project
 * Copyright (c) 2018, The LineageOS Project
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

package org.lineageos.providers.datausage;

import android.app.ActivityManager;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.net.NetworkTemplate;
import static android.net.NetworkTemplate.buildTemplateMobileAll;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import static android.net.TrafficStats.UID_REMOVED;

import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.net.NetworkStats;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.content.pm.UserInfo;

import android.util.SparseArray;
import com.google.gson.Gson;

import lineageos.providers.DataUsageContract;
import lineageos.providers.CMSettings;


/**
 * IntentService, launched by the AlarmManager at Boot Time from (BootReceiver) used
 * to collect per app cellular usage networking statistics and generate warning messages
 * to the user when an App consumes too much BW, giving the user an option to disable
 * Warning Message generation or to disable Network Access for the offending App
 */

public class DataUsageService extends IntentService {
    private final static String TAG = DataUsageService.class.getSimpleName();
    private final static String TAB_MOBILE = "mobile";
    private Context mContext;
    private final static boolean DEBUG = false;

    // Service worker tasks will run on the background thread, create a Handler to
    // communicate with UI Thread, if needed
    private Handler mUiHandler = new Handler();

    private INetworkStatsService mStatsService;
    private INetworkStatsSession mStatsSession;
    private NetworkTemplate mTemplate;
    private SubscriptionManager mSubscriptionManager;
    private List<SubscriptionInfo> mSubInfoList;
    private Map<Integer,String> mMobileTagMap;
    private UserManager mUserManager;
    private List<UserHandle> mProfiles;
    private long mLargest;
    private int mCurrentUserId;
    private UidDetailProvider mUidDetailProvider;
    SparseArray<AppItem> mKnownItems;
    private NotificationManager mNotificationManager;

    // quick way to generate warnings
    // TODO - set to false before releasing
    private static final boolean FAST_MODE = false;

    // specifies minimum number of samples to collect before running algorithm
    // 1 hours worth of active traffic to establish a baseline
    private static final int MIN_SLOW_SAMPLE_COUNT = FAST_MODE ? 5 : 60;
    // 5 min worth of active traffic
    private static final int MIN_FAST_SAMPLE_COUNT = FAST_MODE ? 1 : 5;

    // specifies percentage by which fast average must exceed slow avg to trigger a warning
    // one standard deviation - or should it be 34%, since we are only looking at above and not
    // below. And how many standard deviations should it be?
    private static final int WARNING_PERCENTAGE = FAST_MODE ? 10 : 68;

    // specifies the number of samples to keep in the database for postprocessing and
    // algorithm evaluation
    private final static int MAX_EXTRA_SAMPLE_COUNT = 1000;

    // specifies maximum bw that is still considered as idle - to discard pings, etc...
    private static final long MAX_IDLE_BW = 5 * 1024;
    // specifies the sample period in msec
    public static final long SAMPLE_PERIOD = 60000;
    public static final long START_DELAY = 60000;

    // notification ID to use by the DataUsageService for updates to notifications
    public static final int DATA_USAGE_SERVICE_NOTIFICATION_ID = 102030;

    public static final String HIDE_ACTION      =
            "org.lineageos.providers.datausage.hide_action";
    public static final String DISABLE_ACTION   =
            "org.lineageos.providers.datausage.disable_action";
    public static final int DATA_USAGE_BROADCAST_REQUEST_CODE   = 0x102040; // TODO - ???
    public static final String DATA_USAGE_NOTIFICATION_UID   =
            "org.lineageos.providers.datausage.notification_uid";
    public static final String DATA_USAGE_NOTIFICATION_TITLE =
            "org.lineageos.providers.datausage.notification_title";

    public DataUsageService() {
        super(TAG);
    }

    @android.support.annotation.Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * When periodic alarm is generated, via AlarmManager, the Intent is delivered here
     * @param intent
     */
    @Override
    protected void onHandleIntent(Intent intent) {
        mContext = this;

        // initialize various networking managers/interfaces/sessions/etc...
        mStatsService = INetworkStatsService.Stub.asInterface(
                ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
        mStatsSession = null;
        try {
            mStatsSession = mStatsService.openSession();
            mSubscriptionManager = SubscriptionManager.from(mContext);
            mSubInfoList = mSubscriptionManager.getActiveSubscriptionInfoList();
            mMobileTagMap = initMobileTabTag(mSubInfoList);
            mTemplate = buildTemplateMobileAll(
                    getActiveSubscriberId(mContext, getSubId(TAB_MOBILE + "1")));
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException: " + e.getMessage());
        }

        mUserManager = (UserManager)mContext.getSystemService(Context.USER_SERVICE);
        mProfiles = mUserManager.getUserProfiles();
        mCurrentUserId = ActivityManager.getCurrentUser();
        mUidDetailProvider = new UidDetailProvider(mContext);
        mKnownItems = new SparseArray<AppItem>();
        mNotificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);

        // run the actual dataUsage collection and processing
        dataUsageUpdate();
    }

    private static String getActiveSubscriberId(Context context, int subId) {
        final TelephonyManager tele = TelephonyManager.from(context);
        String retVal = tele.getSubscriberId(subId);
        return retVal;
    }


    private int getSubId(String currentTab) {
        if (mMobileTagMap != null) {
            Set<Integer> set = mMobileTagMap.keySet();
            for (Integer subId : set) {
                if (mMobileTagMap.get(subId).equals(currentTab)) {
                    return subId;
                }
            }
        }
        return -1;
    }

    private Map<Integer, String> initMobileTabTag(List<SubscriptionInfo> subInfoList) {
        Map<Integer, String> map = null;
        if (subInfoList != null) {
            String mobileTag;
            map = new HashMap<Integer, String>();
            for (SubscriptionInfo subInfo : subInfoList) {
                mobileTag = TAB_MOBILE + String.valueOf(subInfo.getSubscriptionId());
                map.put(subInfo.getSubscriptionId(), mobileTag);
            }
        }
        return map;
    }

    /**
     * Accumulate data usage of a network stats entry for the item mapped by the collapse key.
     * Creates the item, if needed
     *
     */
    private void accumulate(int collapseKey, NetworkStats.Entry entry, int itemCategory) {
        int uid = entry.uid;
        AppItem item = mKnownItems.get(collapseKey);
        if (item == null) {
            item = new AppItem(collapseKey);
            item.category = itemCategory;
            mKnownItems.put(item.key, item);
        }
        item.addUid(uid);
        item.total += entry.rxBytes + entry.txBytes;
        if (mLargest < item.total) {
            mLargest = item.total;
        }

    }

    private void clearStats() {
        for(int i = 0; i < mKnownItems.size(); i++) {
            int key = mKnownItems.keyAt(i);
            AppItem appItem = mKnownItems.get(key);
            appItem.total = 0;
        }
    }

    private class DataUsageExtraInfo {
        ArrayList<Long> samples;
    }
    private String mAppWarnExtra;

    private void dataUsageUpdate() {
        long startTime = 0;
        long endTime = System.currentTimeMillis();
        mLargest = 0;

        clearStats();

        NetworkStats networkStats = null;
        try {
            if (mStatsSession != null) {
                networkStats = mStatsSession.getSummaryForAllUid(mTemplate, startTime, endTime,
                        false);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException: " + e.getMessage());
        }

        // collect network stats for all app consuming bw
        if (networkStats != null) {
            int size = networkStats.size();
            NetworkStats.Entry entry = null;

            for(int i = 0; i < size; i++) {
                entry = networkStats.getValues(i, entry);
                int collapseKey;
                int category;
                int uid = entry.uid;
                int userId = UserHandle.getUserId(uid);
                if (UserHandle.isApp(uid)) {
                    if (mProfiles.contains(new UserHandle(userId))) {
                        if (userId != mCurrentUserId) {
                            // add to a managed user item
                            int managedKey = UidDetailProvider.buildKeyForUser(userId);
                            accumulate(managedKey, entry, AppItem.CATEGORY_USER);
                        }
                        collapseKey = uid;
                        category = AppItem.CATEGORY_APP;
                    } else {
                        // if it is a removed user, add it to the removed users' key
                        UserInfo userInfo = mUserManager.getUserInfo(userId);
                        if (userInfo == null) {
                            collapseKey = UID_REMOVED;
                            category = AppItem.CATEGORY_APP;
                        } else {
                            collapseKey = UidDetailProvider.buildKeyForUser(userId);
                            category = AppItem.CATEGORY_USER;
                        }
                    }
                    accumulate(collapseKey, entry, category);
                }
            }
        }
        boolean appWarnActive = false;
        long appWarnBytes = 0;
        long appWarnUid;
        int appWarnSlowSamples;
        int appWarnFastSamples;
        long appWarnSlowAvg;
        long appWarnFastAvg;
        String appWarnExtra = "";

        // lookup Apps in the DB that have warning enabled
        Cursor cursor = getContentResolver().query(
                DataUsageContract.CONTENT_URI,
                null,       // projection - return all
                DataUsageContract.ENABLE + " = ? ",
                new String [] { "1" },
                null
        );
        if (cursor == null) {
            return;
        }

        while(cursor.moveToNext()) {
            appWarnUid = cursor.getInt(DataUsageContract.COLUMN_OF_UID);
            appWarnActive = cursor.getInt(DataUsageContract.COLUMN_OF_ACTIVE) > 0;
            appWarnBytes = cursor.getLong(DataUsageContract.COLUMN_OF_BYTES);
            appWarnSlowSamples = cursor.getInt(DataUsageContract.COLUMN_OF_SLOW_SAMPLES);
            appWarnSlowAvg = cursor.getLong(DataUsageContract.COLUMN_OF_SLOW_AVG);
            appWarnFastSamples = cursor.getInt(DataUsageContract.COLUMN_OF_FAST_SAMPLES);
            appWarnFastAvg = cursor.getLong(DataUsageContract.COLUMN_OF_FAST_AVG);
            mAppWarnExtra = cursor.getString(DataUsageContract.COLUMN_OF_EXTRA);

            AppItem appItem = mKnownItems.get((int)appWarnUid);

            if (appItem != null) {
                final UidDetail detail = mUidDetailProvider.getUidDetail(appItem.key, true);
                long bytesDelta = appWarnBytes == 0 ? 0 : appItem.total - appWarnBytes;
                if (DEBUG) {
                    Log.v(TAG, detail.label.toString() +
                            " cur:" + appItem.total +
                            " prev:" + appWarnBytes +
                            " SlowSamples:" + appWarnSlowSamples +
                            " SlowAvg:" + appWarnSlowAvg +
                            " FastSamples:" + appWarnFastSamples +
                            " FastAvg:" + appWarnFastAvg
                    );
                }
                if (bytesDelta > MAX_IDLE_BW) {
                    // enough BW consumed during this sample - evaluate algorithm
                    if (appWarnSlowSamples < MIN_SLOW_SAMPLE_COUNT) {
                        // not enough samples acquired for the slow average, keep accumulating
                        // samples
                        appWarnSlowAvg = computeAvg(appWarnSlowAvg, appWarnSlowSamples,
                                MIN_SLOW_SAMPLE_COUNT, bytesDelta);
                        appWarnSlowSamples++;

                        // fast average requires fewer samples than slow average, so at this point
                        // we may have accumulated enough or not, need to check
                        if (appWarnFastSamples < MIN_FAST_SAMPLE_COUNT) {
                            // not enough fast samples
                            appWarnFastAvg = computeAvg(appWarnFastAvg, appWarnFastSamples,
                                    MIN_FAST_SAMPLE_COUNT, bytesDelta);
                            appWarnFastSamples++;
                        } else {
                            // enough fast samples
                            appWarnFastAvg = computeAvg(appWarnFastAvg, appWarnFastSamples,
                                    MIN_FAST_SAMPLE_COUNT, bytesDelta);
                        }

                        updateDb(appItem.key,
                                appWarnSlowAvg, appWarnSlowSamples,
                                appWarnFastAvg, appWarnFastSamples,
                                0, appItem.total);
                    } else {
                        // enough samples acquired for the average, evaluate warning algorithm
                        float avgExceedPercent = appWarnFastAvg-appWarnSlowAvg;
                        avgExceedPercent /= appWarnSlowAvg;
                        avgExceedPercent *= 100;

                        if ((appWarnFastAvg > appWarnSlowAvg) && (avgExceedPercent >
                                WARNING_PERCENTAGE)) {
                            genNotification(appItem.key, detail.label.toString(), !appWarnActive);
                            if (!appWarnActive) {
                                appWarnActive = true;
                            }
                        } else {
                            appWarnActive = false;
                        }
                        appWarnSlowAvg = computeAvg(appWarnSlowAvg, appWarnSlowSamples,
                                MIN_SLOW_SAMPLE_COUNT, bytesDelta);
                        appWarnFastAvg = computeAvg(appWarnFastAvg, appWarnFastSamples,
                                MIN_FAST_SAMPLE_COUNT, bytesDelta);
                        updateDb(
                                appItem.key,
                                appWarnSlowAvg, appWarnSlowSamples,
                                appWarnFastAvg, appWarnFastSamples,
                                appWarnActive ? 1 : 0, appItem.total
                        );

                    }
                } else {
                    // not enough BW consumed during this sample - simply update bytes
                    updateDb(appItem.key, appItem.total);
                }
            }
        }
        cursor.close();
    }

    long computeAvg(long avg, int samples, int min_samples, long delta) {
        float temp;

        if (samples < min_samples) {
            temp = avg * samples;
            temp += delta;
            temp /= (samples + 1);
            return (long)temp;
        } else {
            temp = avg * (samples - 1);
            temp += delta;
            temp /= samples;
            return (long)temp;
        }
    }


    private void updateDb(int uid, long bytes) {
        ContentValues values = new ContentValues();

        values.put(DataUsageContract.BYTES, bytes);
        getContentResolver().update(
                DataUsageContract.CONTENT_URI,
                values,
                DataUsageContract.UID + " = ? ",
                new String[]{String.valueOf(uid)}
        );
    }

    private void updateDb(
            int uid, long slowAvg, int slowSamples, long fastAvg, int fastSamples,
            int active, long bytes
    ) {
        ContentValues values = new ContentValues();
        String extraInfo = genExtraInfo(bytes);
        values.put(DataUsageContract.SLOW_AVG, slowAvg);
        values.put(DataUsageContract.SLOW_SAMPLES, slowSamples);
        values.put(DataUsageContract.FAST_AVG, fastAvg);
        values.put(DataUsageContract.FAST_SAMPLES, fastSamples);
        values.put(DataUsageContract.ACTIVE, active);
        values.put(DataUsageContract.BYTES, bytes);
        values.put(DataUsageContract.EXTRA, extraInfo);

        getContentResolver().update(
                DataUsageContract.CONTENT_URI,
                values,
                DataUsageContract.UID + " = ? ",
                new String[]{String.valueOf(uid)}
        );
    }


    /**
     * In debug mode, generate extra samples inforamation that can be used to analyze
     * algorithm manually
     */
    private String genExtraInfo(long bytes) {
        if (!DEBUG) {
            return "";
        }

        Gson gson = new Gson();
        DataUsageExtraInfo extraInfo;

        if (mAppWarnExtra == null || mAppWarnExtra == "") {
            extraInfo = null;
        } else {
            try {
                extraInfo = gson.fromJson(mAppWarnExtra, DataUsageExtraInfo.class);
            } catch (Exception e) {
                extraInfo = null;
            }
        }

        if (extraInfo == null) {
            extraInfo = new DataUsageExtraInfo();
            extraInfo.samples = new ArrayList<Long>();
        }

        if (extraInfo.samples.size() == MAX_EXTRA_SAMPLE_COUNT) {
            extraInfo.samples.remove(0);
        }
        extraInfo.samples.add(bytes);
        String extraInfoJson = gson.toJson(extraInfo);
        return extraInfoJson;
    }



    private void genNotification(long uid, String appTitle, boolean firstTime) {
        Intent hideIntent = new Intent();
        hideIntent.setAction(HIDE_ACTION);
        hideIntent.putExtra(DATA_USAGE_NOTIFICATION_UID, uid);
        hideIntent.putExtra(DATA_USAGE_NOTIFICATION_TITLE, appTitle);
        PendingIntent hidePendingIntent = PendingIntent.getBroadcast(
                mContext, DATA_USAGE_BROADCAST_REQUEST_CODE, hideIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent disableIntent = new Intent();
        disableIntent.setAction(DISABLE_ACTION);
        disableIntent.putExtra(DATA_USAGE_NOTIFICATION_UID, uid);
        disableIntent.putExtra(DATA_USAGE_NOTIFICATION_TITLE, appTitle);
        PendingIntent disablePendingIntent = PendingIntent.getBroadcast(
                mContext, DATA_USAGE_BROADCAST_REQUEST_CODE, disableIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent dataUsageIntent = new Intent();
        dataUsageIntent.setAction(lineageos.providers.CMSettings.ACTION_DATA_USAGE);
        dataUsageIntent.addCategory(Intent.CATEGORY_DEFAULT);
        dataUsageIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        dataUsageIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        dataUsageIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

        PendingIntent dataUsagePendingIntent = PendingIntent.getActivity(mContext, 0,
                dataUsageIntent, 0);

        // NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext)
        Notification.Builder builder = new Notification.Builder(mContext)
                .setSmallIcon(R.drawable.data_usage_48dp)
                .setContentTitle(getResources().getString(R.string.data_usage_notify_title))
                .setAutoCancel(true)        // remove notification when clicked on
                .setContentText(appTitle)   // non-expanded view message
                .setColor(mContext.getColor(R.color.data_usage_notification_icon_color))
                .setStyle(new Notification.BigTextStyle()
                        .bigText(getResources().getString(R.string.data_usage_notify_big_text, appTitle)));

        if (firstTime) {
            builder.addAction(
                    // R.drawable.data_warning_disable,
                    // android.R.drawable.stat_sys_data_bluetooth,
                    R.drawable.data_usage_disable_24dp,
                    getResources().getString(R.string.data_usage_disable_long),
                    disablePendingIntent);
        } else {
            builder.addAction(
                    // R.drawable.data_warning_disable,
                    // android.R.drawable.stat_sys_data_bluetooth,
                    R.drawable.data_usage_disable_24dp,
                    getResources().getString(R.string.data_usage_disable_short),
                    disablePendingIntent);
            builder.addAction(
                    // R.drawable.data_warning_hide,
                    // android.R.drawable.stat_sys_download_done,
                    R.drawable.data_usage_hide_24dp,
                    getResources().getString(R.string.data_usage_hide),
                    hidePendingIntent)
            ;
        }

        builder.setContentIntent(dataUsagePendingIntent);
        mNotificationManager.notify(DATA_USAGE_SERVICE_NOTIFICATION_ID, builder.build());
    }
}

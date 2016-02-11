/*
 * Copyright (c) 2016, The CyanogenMod Project
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

package org.cyanogenmod.providers.datausage;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.UserInfo;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import cyanogenmod.providers.DataUsageContract;


public final class DataUsageUtils {

    private static final String TAG = DataUsageUtils.class.getSimpleName();
    private static final int DATAUSAGE_SERVICE_ALARM_ID = 0x102030;
    private static boolean DEBUG = false;

    public static final String PREF_FILE = "data_usage_service";
    public static final String PREF_ENABLE_DATA_USAGE_NOTIFY = "enable_data_usage_notify";

    /**
     * Returns a label for the user, in the form of "User: user name" or "Work profile".
     */
    public static String getUserLabel(Context context, UserInfo info) {
        String name = info != null ? info.name : null;
        if (info.isManagedProfile()) {
            // We use predefined values for managed profiles
            return context.getString(R.string.managed_user_title);
        } else if (info.isGuest()) {
            name = context.getString(R.string.user_guest);
        }
        if (name == null && info != null) {
            name = Integer.toString(info.id);
        } else if (info == null) {
            name = context.getString(R.string.unknown);
        }
        return context.getResources().getString(R.string.running_process_item_user_label, name);
    }

    public static void addApp(Context context, int uid, String label) {
        if (DEBUG) {
            Log.v(TAG, "addApp: uid:" + uid + " label:" + label);
        }

        ContentValues values = new ContentValues();

        values.put(DataUsageContract.UID, uid);
        values.put(DataUsageContract.LABEL, label);

        context.getContentResolver().insert(
                DataUsageContract.CONTENT_URI,
                values
        );
    }

    public static void removeApp(Context context, int uid) {
        if (DEBUG) {
            Log.v(TAG, "removeApp: uid:" + uid);
        }
        context.getContentResolver().delete(
                DataUsageContract.CONTENT_URI,
                DataUsageContract.UID + " = ? ",
                new String [] { String.valueOf(uid)}
        );
    }

    public static void enableApp(Context context, int uid, boolean enable) {
        enableApp(context, uid, enable, null);
    }

    public static void enableApp(Context context, int uid, boolean enable, String label) {
        if (DEBUG) {
            Log.v(TAG, "enableApp: uid:" + uid + " enable:" + enable + ((label == null) ? "" :
                    (" label:" + label)));
        }
        ContentValues values = new ContentValues();

        values.put(DataUsageContract.ENABLE, enable);
        if (label != null) {
            values.put(DataUsageContract.LABEL, label);
        }
        context.getContentResolver().update(
                DataUsageContract.CONTENT_URI,
                values,
                DataUsageContract.UID + " = ? ",
                new String [] { String.valueOf(uid)}
        );
    }

    public static boolean getAppEnable(Context context, int uid) {
        boolean appEnable = false;
        Cursor cursor = context.getContentResolver().query(
                DataUsageContract.CONTENT_URI,
                null,
                DataUsageContract.UID + " = ? ",
                new String [] { String.valueOf(uid) },
                null
        );
        if (cursor != null && cursor.getCount() > 0 && cursor.moveToFirst()) {
            int enableValue = cursor.getInt(DataUsageContract.COLUMN_OF_ENABLE);
            if (enableValue == 1) {
                appEnable = true;
            }
        }

        if (cursor != null) {
            cursor.close();
        }

        if (DEBUG) {
            Log.v(TAG, "getAppEnable: uid:" + uid + " enable:" + appEnable);
        }

        return appEnable;
    }

    public static void enableDataUsageService(Context context, boolean enable) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_ENABLE_DATA_USAGE_NOTIFY, enable).apply();
        // start DataUsage service, but only if on qualified mobile network
        // if the service is not started now, it will be started when the network state changes
        // and the connected network is qualified for the DataUsage service
        if (isDataUsageQualified(context)) {
            startDataUsageService(context, enable);
        }
    }

    public static void startDataUsageService(Context context, boolean enable) {
        Intent dataUsageServiceIntent = new Intent(context, DataUsageService.class);
        PendingIntent alarmIntent = PendingIntent.getService(
                context, DATAUSAGE_SERVICE_ALARM_ID, dataUsageServiceIntent, 0);
        AlarmManager alarmManager = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);

        if (enable) {
            alarmManager.setRepeating(
                    AlarmManager.ELAPSED_REALTIME,
                    DataUsageService.START_DELAY,
                    DataUsageService.SAMPLE_PERIOD,
                    alarmIntent
            );
        } else {
            alarmManager.cancel(alarmIntent);
        }
        if (DEBUG) {
            Log.v(TAG, "enableDataUsageService: enable:" + enable);
        }
    }

    public static void startDataUsageServiceIfEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        boolean enable = prefs.getBoolean(PREF_ENABLE_DATA_USAGE_NOTIFY, false);
        if (enable) {
            startDataUsageService(context, true);
        }
        if (DEBUG) {
            Log.v(TAG, "startDataUsageServiceIfEnabled: enable: " + enable);
        }
    }

    // determine if the currently connected network qualified for the DataUsage service
    public static boolean isDataUsageQualified(Context context) {
        // only perform DataUsage collection for metered networks
        ConnectivityManager connectivityManager =
                (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        if (activeNetworkInfo != null) {
            boolean isConnected = activeNetworkInfo.isConnected();
            boolean isMobile = activeNetworkInfo.getType() == ConnectivityManager.TYPE_MOBILE;
            boolean qualified = isConnected && isMobile && connectivityManager.isActiveNetworkMetered();
            return qualified;
        } else {
            return false;
        }
    }
}

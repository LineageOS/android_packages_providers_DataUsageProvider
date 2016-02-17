/*
 * Copyright (C) 2016 The Android Open Source Project
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

import org.cyanogenmod.providers.datausage.R;

import cyanogenmod.providers.DataUsageContract;


public final class DataUsageUtils {

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


    private static final String TAG = DataUsageUtils.class.getSimpleName();
    private static final int DATAUSAGE_SERVICE_ALARM_ID = 0x102030;
    private static boolean DEBUG = true;

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

    public static void enbApp(Context context, int uid, boolean enb) {
        enbApp(context, uid, enb, null);
    }

    public static void enbApp(Context context, int uid, boolean enb, String label) {
        if (DEBUG) {
            Log.v(TAG, "enbApp: uid:" + uid + " enb:" + enb + ((label == null) ? "" : (" label:" +
                    label)));
        }
        ContentValues values = new ContentValues();

        values.put(DataUsageContract.ENB, enb);
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

    public static boolean getAppEnb(Context context, int uid) {
        boolean appEnb = false;
        Cursor cursor = context.getContentResolver().query(
                DataUsageContract.CONTENT_URI,
                null,
                DataUsageContract.UID + " = ? ",
                new String [] { String.valueOf(uid) },
                null
        );
        if (cursor != null && cursor.getCount() > 0 && cursor.moveToFirst()) {
            int enbValue = cursor.getInt(DataUsageContract.COLUMN_OF_ENB);
            if (enbValue == 1) {
                appEnb = true;
            }
        }

        if (cursor != null) {
            cursor.close();
        }

        if (DEBUG) {
            Log.v(TAG, "getAppEnb: uid:" + uid + " enb:" + appEnb);
        }

        return appEnb;
    }

    public static final String PREF_FILE = "data_usage_service";
    public static final String PREF_ENB_DATA_USAGE_NOTIFY = "enb_data_usage_notify";


    public static void enbDataUsageService(Context context, boolean enb) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_ENB_DATA_USAGE_NOTIFY, enb).apply();
        // start DataUsage service, but only if on qualified mobile network
        // if the service is not started now, it will be started when the network state changes
        // and the connected network is qualified for the DataUsage service
        if (isDataUsageQualified(context)) {
            startDataUsageService(context, enb);
        }
    }

    public static void startDataUsageService(Context context, boolean enb) {
        Intent dataUsageServiceIntent = new Intent(context, DataUsageService.class);
        PendingIntent alarmIntent = PendingIntent.getService(
                context, DATAUSAGE_SERVICE_ALARM_ID, dataUsageServiceIntent, 0);
        AlarmManager alarmManager = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);

        if (enb) {
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
            Log.v(TAG, "enbDataUsageService: enb:" + enb);
        }
    }

    public static void startDataUsageServiceIfEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        boolean enb = prefs.getBoolean(PREF_ENB_DATA_USAGE_NOTIFY, false);
        if (enb) {
            startDataUsageService(context, true);
        }
        if (DEBUG) {
            Log.v(TAG, "startDataUsageServiceIfEnabled: enb: " + enb);
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

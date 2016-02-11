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

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import android.net.NetworkPolicyManager;
import static android.net.NetworkPolicyManager.POLICY_REJECT_ON_DATA;

import cyanogenmod.providers.DataUsageContract;


/**
 * This class implements the receiver that will handle clicks on the buttons
 * in the Data Usage Notification
 * Disable - disables the wireless network traffic for the specified uid
 * Hide - disables data usage checks for the specified uid
 */

public class DataUsageNotificationReceiver extends BroadcastReceiver {
    private static final String TAG = DataUsageNotificationReceiver.class.getSimpleName();
    private static final boolean DEBUG = true;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        int uid = 0;
        String title;
        if (intent.hasExtra(DataUsageService.DATA_USAGE_NOTIFICATION_UID)) {
            // Settings app uses long, but NetworkPolicyManager uses int
            // I guess UIDs are limited to 32 bits, so casting should not cause a problem
            uid = (int)intent.getLongExtra(DataUsageService.DATA_USAGE_NOTIFICATION_UID, 0);
        }
        if (intent.hasExtra(DataUsageService.DATA_USAGE_NOTIFICATION_TITLE)) {
            title = intent.getStringExtra(DataUsageService.DATA_USAGE_NOTIFICATION_TITLE);
        } else {
            title = "";
        }

        if (uid == 0) {
            Log.e(TAG, "Invalid UID:" + uid + " for Action:" + action);
            return;
        }

        if (DataUsageService.HIDE_ACTION.equals(action)) {
            Toast.makeText(context, context.getString(R.string.data_usage_hide_message, title),
                    Toast
                            .LENGTH_LONG)
                    .show();

            ContentValues values = new ContentValues();
            values.put(DataUsageContract.ENABLE, 0);
            values.put(DataUsageContract.ACTIVE, 0);
            values.put(DataUsageContract.BYTES, 0);

            DataUsageUtils.enableApp(context, uid, false);

        } else if (DataUsageService.DISABLE_ACTION.equals(action)) {
            Toast.makeText(context, context.getString(R.string.data_usage_disable_message, title),
                    Toast.LENGTH_LONG).show();
            NetworkPolicyManager policyManager = NetworkPolicyManager.from(context);
            policyManager.addUidPolicy(uid, POLICY_REJECT_ON_DATA);
        }

        // cancel the notification
        NotificationManager notificationManager = (NotificationManager)context.getSystemService
                (Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(DataUsageService.DATA_USAGE_SERVICE_NOTIFICATION_ID);
    }
}

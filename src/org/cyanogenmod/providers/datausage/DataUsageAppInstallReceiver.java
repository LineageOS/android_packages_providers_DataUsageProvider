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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * This class implements the receiver that will handle app installs & uninstalls
 * when an app is installed, add an entry in the datausage table
 * when an app is removed, remove the entry from the datausage table
 */

public class DataUsageAppInstallReceiver extends BroadcastReceiver {
    private static final String TAG = DataUsageAppInstallReceiver.class.getSimpleName();
    private static final boolean DEBUG = false;

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (DEBUG) {
            Log.v(TAG, "AppInstallReceiver: onReceive");
        }

        Intent appInstallServiceIntent = new Intent(context, DataUsageAppInstallService.class);
        appInstallServiceIntent.setAction(intent.getAction());
        if (intent.hasExtra(Intent.EXTRA_UID)) {
            appInstallServiceIntent.putExtra(
                    Intent.EXTRA_UID,
                    intent.getIntExtra(Intent.EXTRA_UID, 0));
        }
        context.startService(appInstallServiceIntent);
    }
}

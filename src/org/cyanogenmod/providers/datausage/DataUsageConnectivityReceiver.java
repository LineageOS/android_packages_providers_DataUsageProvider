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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;


public class DataUsageConnectivityReceiver extends BroadcastReceiver {
    private static final String TAG = DataUsageConnectivityReceiver.class.getSimpleName();
    private static final boolean DEBUG = true;
    @Override
    public void onReceive(Context context, Intent intent) {
        // datausage service is only run for metered mobile connections
        boolean qualified = DataUsageUtils.isDataUsageQualified(context);
        if (DEBUG) {
            Log.v(TAG, "onReceived: qualified: " + qualified);
        }
        if (qualified) {
            // start DataUsage service, but only if enabled
            DataUsageUtils.startDataUsageServiceIfEnabled(context);
        } else {
            // stop DataUsage service
            DataUsageUtils.enbDataUsageService(context, false);
        }
    }
}

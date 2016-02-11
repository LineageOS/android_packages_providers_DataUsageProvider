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

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = BootReceiver.class.getSimpleName();
    private static final boolean DEBUG = true;

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean qualified = DataUsageUtils.isDataUsageQualified(context);

        // start DataUsage service once the device boots up, but only if
        // on qualified network and the service itself is enabled from the Settings->DataUsage
        if (qualified) {
            DataUsageUtils.startDataUsageServiceIfEnabled(context);
        }
    }
}

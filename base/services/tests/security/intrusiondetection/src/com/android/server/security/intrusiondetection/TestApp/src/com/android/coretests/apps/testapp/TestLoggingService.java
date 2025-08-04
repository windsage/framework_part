/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.coretests.apps.testapp;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

public class TestLoggingService extends Service {
    private static final String TAG = "TestLoggingService";
    private LocalIntrusionDetectionEventTransport mLocalIntrusionDetectionEventTransport;

    @Override
    public void onCreate() {
        super.onCreate();

        Context context = getApplicationContext();
        mLocalIntrusionDetectionEventTransport = new LocalIntrusionDetectionEventTransport(context);
    }

    // Binder given to clients.
    @Override
    public IBinder onBind(Intent intent) {
        return mLocalIntrusionDetectionEventTransport.getBinder();
    }
}

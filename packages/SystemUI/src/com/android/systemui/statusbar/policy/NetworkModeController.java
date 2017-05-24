/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Slog;
import android.widget.CompoundButton;
import android.net.wifi.WifiManager;

public class NetworkModeController extends BroadcastReceiver
        implements CompoundButton.OnCheckedChangeListener {
    private static final String TAG = "NetworkModeController";

    private Context mContext;
    private CompoundButton mCheckBox;

    private boolean mNetworkMode;
    WifiManager wifiManager;

    public NetworkModeController(Context context, CompoundButton checkbox) {
        mContext = context;        
        mNetworkMode = getNetworkMode();
        wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
System.out.println("**************************NetworkModeController mNetworkMode = " + getNetworkMode());
        mCheckBox = checkbox;
        checkbox.setChecked(mNetworkMode);
        checkbox.setOnCheckedChangeListener(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        context.registerReceiver(this, filter);

    }

    public void release() {
        mContext.unregisterReceiver(this);
    }

    public void onCheckedChanged(CompoundButton view, boolean checked) {
        if (checked != mNetworkMode) {
            mNetworkMode = checked;
            wifiManager.setWifiEnabled(checked);
            unsafe(checked);
        }
    }

    public void onReceive(Context context, Intent intent) {
    		Slog.d(TAG, "onReceive intent action = " + intent.getAction());
        if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())) {
            final boolean enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;
            if (enabled != mNetworkMode) {
                mNetworkMode = enabled;
                mCheckBox.setChecked(enabled);
            }
        }
    }

    private boolean getNetworkMode() {
        ContentResolver cr = mContext.getContentResolver();
        return 0 != Settings.Secure.getInt(cr, Settings.Secure.WIFI_ON, 0);
    }

    // TODO: Fix this racy API by adding something better to TelephonyManager or
    // ConnectivityService.
    private void unsafe(final boolean enabled) {    		
        AsyncTask.execute(new Runnable() {
                public void run() {
                		//wifiManager.setWifiEnabled(enabled);
                    Settings.Secure.putInt(
                            mContext.getContentResolver(),
                            Settings.Secure.WIFI_ON,
                            enabled ? 1 : 0);                            
                    /*Intent intent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
                    intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                    intent.putExtra(WifiManager.EXTRA_WIFI_STATE, wifiManager.getWifiState());
                    mContext.sendBroadcast(intent);*/
                }
            });
    }
}


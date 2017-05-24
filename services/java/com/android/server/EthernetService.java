/*$_FOR_ROCKCHIP_RBOX_$*/
//$_rbox_$_modify_$_chenzhi_20120309: add for ethernet
package com.android.server;

import android.app.AlarmManager;
import android.app.PendingIntent;

import android.content.BroadcastReceiver;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ethernet.IEthernetManager;
import android.net.EthernetDataTracker;
import android.net.NetworkStateTracker;
import android.net.DhcpInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.io.FileDescriptor;
import java.io.PrintWriter;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import com.android.internal.app.IBatteryStats;
import com.android.server.am.BatteryStatsService;
//import android.os.UEventObserver;


public class EthernetService extends IEthernetManager.Stub {

    private static final String TAG = "EthernetService";
    private static final boolean DEBUG = true;
    // private static final boolean DEBUG = false;
    private static void LOG(String msg) {
        if ( DEBUG ) {
            Log.d(TAG, msg);
        }
    }

    private Context mContext;

    private final EthernetDataTracker mEthernetDataTracker;
    
    EthernetService(Context context) {
        
        LOG("EthernetService() : Entered.");
       
        mContext = context;
        mEthernetDataTracker = EthernetDataTracker.getInstance();
    }

    public int getEthernetConnectState() {
        // enforceAccessPermission();       // 暂时不引入 permission 机构.
        LOG("getEthernetEnabledState() : Entered.");
        return mEthernetDataTracker.ethCurrentState;
    }

    private String ReadFromFile(File file) {
        if((file != null) && file.exists()) {
            try {
                FileInputStream fin= new FileInputStream(file);
                BufferedReader reader= new BufferedReader(new InputStreamReader(fin));
                String flag = reader.readLine();
                fin.close();
                return flag;
            }
            catch(IOException e) {
                e.printStackTrace();
            }
        }

        return null;
    }

    public int getEthernetIfaceState() {
        // enforceAccessPermission();       // 暂时不引入 permission 机构.
        LOG("getEthernetIfaceState()");
        String ifc = mEthernetDataTracker.getEthIfaceName();
		if(ifc == "")
			return mEthernetDataTracker.ETHER_IFACE_STATE_DOWN;
        File file = new File("/sys/class/net/"+ifc+"/flags");
		if(!file.exists())
			return mEthernetDataTracker.ETHER_IFACE_STATE_DOWN;
        String flags = ReadFromFile(file);
        LOG("flags="+flags);
        String flags_no_0x = flags.substring(2);
        int flags_int = Integer.parseInt(flags_no_0x, 16);
        if ((flags_int & 0x1)>0) {
            LOG("state=up");
            return mEthernetDataTracker.ETHER_IFACE_STATE_UP;
        } else {
            LOG("state=down");
            return mEthernetDataTracker.ETHER_IFACE_STATE_DOWN;
        }
    }

/*
0: no carrier (RJ45 unplug)
1: carrier exist (RJ45 plugin)
*/
    public int getEthernetCarrierState() {
        LOG("getEthernetCarrierState()");
        int state = getEthernetIfaceState();
        String ifc = mEthernetDataTracker.getEthIfaceName();
        if((ifc != "") && state == mEthernetDataTracker.ETHER_IFACE_STATE_UP) {
            File file = new File("/sys/class/net/"+ifc+"/carrier");
            String carrier = ReadFromFile(file);
            LOG("carrier="+carrier);
            int carrier_int = Integer.parseInt(carrier);
            return carrier_int;
        } else {
            return 0;
        }
    }

    public boolean setEthernetEnabled(boolean enable) {
        // enforceChangePermission();
        
        LOG("setEthernetEnabled() : enable="+enable);
        if ( enable ) {
            mEthernetDataTracker.enableEthIface();
        }
        else {
            mEthernetDataTracker.disableEthIface();
        }
            
        return true;
    }

    public String getEthernetIfaceName() {
        return mEthernetDataTracker.getEthIfaceName();
    }
}

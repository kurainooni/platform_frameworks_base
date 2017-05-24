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

package android.net;

import android.content.Context;
import android.content.ContentResolver;
import android.net.NetworkInfo.DetailedState;
import android.os.Handler;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.database.ContentObserver;
import android.provider.Settings;
import java.net.Inet4Address;
import java.net.InetAddress;
import android.text.TextUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import android.content.Intent;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

/**
 * This class tracks the data connection associated with Ethernet
 * This is a singleton class and an instance will be created by
 * ConnectivityService.
 * @hide
 */
public class EthernetDataTracker implements NetworkStateTracker {
    private static final String NETWORKTYPE = "ETHERNET";
    private static final String TAG = "Ethernet";
	private static final boolean DBG = true;

    private AtomicBoolean mTeardownRequested = new AtomicBoolean(false);
    private AtomicBoolean mPrivateDnsRouteSet = new AtomicBoolean(false);
    private AtomicInteger mDefaultGatewayAddr = new AtomicInteger(0);
    private AtomicBoolean mDefaultRouteSet = new AtomicBoolean(false);

    private static boolean mLinkUp;
    private LinkProperties mLinkProperties;
    private LinkCapabilities mLinkCapabilities;
    private NetworkInfo mNetworkInfo;
    private InterfaceObserver mInterfaceObserver;

    /* For sending events to connectivity service handler */
    private Handler mCsHandler;
    private Context mContext;

	private INetworkManagementService mNwService;

    private static EthernetDataTracker sInstance;
    private static String sIfaceMatch = "";
    private static String mIface = "";
	public static final String ETHERNET_STATE_CHANGED_ACTION = "android.net.ethernet.ETHERNET_STATE_CHANGED";
	public static final String EXTRA_ETHERNET_STATE = "ethernet_state";
	public int ethCurrentState = ETHER_STATE_DISCONNECTED;
	public static final int ETHER_STATE_DISCONNECTED=0;
	public static final int ETHER_STATE_CONNECTING=1;
	public static final int ETHER_STATE_CONNECTED=2;
	public static final int ETHER_IFACE_STATE_DOWN = 0;
	public static final int ETHER_IFACE_STATE_UP = 1;

	private static final int EVENT_INTERFACE_LINK_STATE_CHANGED = 0;
	private static final int EVENT_INTERFACE_LINK_STATE_CHANGED_DELAY_MS = 2000;
	
	private WakeLock mWakeLock;

	private String ipAddress;
	private String netmask;
	private String gateway;
	private String dns1;
	private String dns2;
	private int prefixLength;
	private Handler mHandler;
	
	private boolean dhcpDone;
	private boolean isEthernetContentObserverRegister;

	private void sendEthStateChangedBroadcast(int curState) {
		ethCurrentState=curState;
		final Intent intent = new Intent(ETHERNET_STATE_CHANGED_ACTION);
		intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);  //  
		intent.putExtra(EXTRA_ETHERNET_STATE, curState);
		mContext.sendStickyBroadcast(intent);
	}   
	
	    private void acquireWakeLock(Context context) {
			if (mWakeLock == null) {
				PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
				mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"USB Ether");
				mWakeLock.acquire();
				Log.d(TAG,"acquireWakeLock USB Ether");
			}
		}
		private void releaseWakeLock() {
			if (mWakeLock != null) {
				mWakeLock.release();
				mWakeLock = null;
				Log.d(TAG,"releaseWakeLock USB Ether");
			}
		}
		

    private class InterfaceObserver extends INetworkManagementEventObserver.Stub {
        private EthernetDataTracker mTracker;

        InterfaceObserver(EthernetDataTracker tracker) {
            super();
            mTracker = tracker;
        }

        public void interfaceStatusChanged(String iface, boolean up) {
            Log.d(TAG, "Interface status changed: " + iface + (up ? "up" : "down"));
        }

        public void interfaceLinkStateChanged(String iface, boolean up) {
            if (mIface.equals(iface) && mLinkUp != up) {
                Log.d(TAG, "Interface " + iface + " link " + (up ? "up" : "down"));
                mLinkUp = up;

                // use DHCP
                if (up) {
					mHandler.removeMessages(EVENT_INTERFACE_LINK_STATE_CHANGED);
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_INTERFACE_LINK_STATE_CHANGED, 1, 0), EVENT_INTERFACE_LINK_STATE_CHANGED_DELAY_MS);
                } else {
                	mHandler.removeMessages(EVENT_INTERFACE_LINK_STATE_CHANGED);
					mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_INTERFACE_LINK_STATE_CHANGED, 0, 0), EVENT_INTERFACE_LINK_STATE_CHANGED_DELAY_MS);
                }
            }
        }

        public void interfaceAdded(String iface) {
            mTracker.interfaceAdded(iface);
        }

        public void interfaceRemoved(String iface) {
            mTracker.interfaceRemoved(iface);
        }

        public void limitReached(String limitName, String iface) {
            // Ignored.
        }
    }

	private ContentObserver mEthernetEnableObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
        	if(DBG) Log.d(TAG, "onChange");
			if(Settings.Secure.getInt(mContext.getContentResolver(), Settings.Secure.ETHERNET_ON, 0) == 1) {
				handleEnable();
			} else {
				handleDisable();
			}
        }
    };

	/*private ContentObserver mEthernetStaticIPObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
			handleDisable();
			handleEnable();
        }
    };*/

	private void registerEthernetContentObserver() {
		if(!isEthernetContentObserverRegister) {
			if(DBG) Log.d(TAG, "registerEthernetContentObserver");
			mContext.getContentResolver().registerContentObserver(
	                Settings.Secure.getUriFor(Settings.Secure.ETHERNET_ON), false,
	                mEthernetEnableObserver);
			//mContext.getContentResolver().registerContentObserver(
	        //        Settings.System.getUriFor(Settings.System.ETHERNET_USE_STATIC_IP), false,
	        //        mEthernetStaticIPObserver);		
			isEthernetContentObserverRegister = true;
		}
	}

	private void unregisterEthernetContentObserver() {
		if(isEthernetContentObserverRegister) {
			if(DBG) Log.d(TAG, "unregisterEthernetContentObserver");
			mContext.getContentResolver().unregisterContentObserver(mEthernetEnableObserver);
			//mContext.getContentResolver().unregisterContentObserver(mEthernetStaticIPObserver);	
			isEthernetContentObserverRegister = false;
		}
	}

	private boolean isUsingStaticIp() {
		return Settings.System.getInt(mContext.getContentResolver(), Settings.System.ETHERNET_USE_STATIC_IP, 0) == 1 ? true : false;
	}

	private boolean isEthernetEnabled() {
		return Settings.Secure.getInt(mContext.getContentResolver(), Settings.Secure.ETHERNET_ON, 0) == 1 ? true : false;
	}

    private EthernetDataTracker() {
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_ETHERNET, 0, NETWORKTYPE, "");
        mLinkProperties = new LinkProperties();
        mLinkCapabilities = new LinkCapabilities();
        mLinkUp = false;
		dhcpDone = false;
		isEthernetContentObserverRegister = false;

        mNetworkInfo.setIsAvailable(false);
        setTeardownRequested(false);

		IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNwService = INetworkManagementService.Stub.asInterface(b);

        HandlerThread handlerThread = new HandlerThread("EthernetDataTrackerThread");
        handlerThread.start();
        mHandler = new EthernetDataTrackerHandler(handlerThread.getLooper(), this);		
    }

	private class EthernetDataTrackerHandler extends Handler {
		private EthernetDataTracker mDataTracker;
		
		public EthernetDataTrackerHandler(Looper looper, EthernetDataTracker tracker) {
            super(looper);
			mDataTracker = tracker;
        }
		
		@Override
        public void handleMessage(Message msg) {
        	switch (msg.what) {
				case EVENT_INTERFACE_LINK_STATE_CHANGED:
					if(msg.arg1 == 1) {
						mDataTracker.reconnect();
						mDataTracker.mNetworkInfo.setIsAvailable(true);
					} else {
						if(DBG) Log.d(TAG, "handleMessage: EVENT_INTERFACE_LINK_STATE_CHANGED: down");
						EthernetDataTracker temp=EthernetDataTracker.getInstance();
						temp.sendEthStateChangedBroadcast(ETHER_STATE_DISCONNECTED);
	                    NetworkUtils.stopDhcp(mIface);
						mDataTracker.dhcpDone = false;
	                    mDataTracker.mNetworkInfo.setIsAvailable(false);
	                    mDataTracker.mNetworkInfo.setDetailedState(DetailedState.DISCONNECTED,
	                                                           null, null);
					}
					break;
        	}
		}
	}

	private void handleEnable() {
		if(DBG) Log.d(TAG, "handleEnable");

		if(mIface.isEmpty() || !isEthernetEnabled()) {
			if(DBG) Log.d(TAG, "handleEnable: isEthernetEnabled = false, mIface = " + mIface + ", skip");
			return;
		}
		
		sendEthStateChangedBroadcast(ETHER_STATE_CONNECTING);
        mNetworkInfo.setIsAvailable(true);
        Message msg = mCsHandler.obtainMessage(EVENT_CONFIGURATION_CHANGED, mNetworkInfo);
        msg.sendToTarget();

        runDhcp();		
		if(DBG) Log.d(TAG, "handleEnable Exit");
	}

	private void handleDisable() {
		if(DBG) Log.d(TAG, "handleDisable");

		if(mIface.isEmpty() || (!dhcpDone && !isEthernetEnabled())) {
			if(DBG) Log.d(TAG, "handleDisable: isEthernetEnabled = false, mIface = " + mIface + ", dhcpDone = " + dhcpDone + ", skip");
			//mIface = "";
			return;
		}
		
		sendEthStateChangedBroadcast(ETHER_STATE_DISCONNECTED);

        NetworkUtils.stopDhcp(mIface);
		dhcpDone = false;		

        try {
            mNwService.clearInterfaceAddresses(mIface);
            Log.d(TAG, "clearInterfaceAddresses succeeded");
        } catch (RemoteException re) {
            Log.e(TAG, "clearInterfaceAddresses configuration failed: " + re);
        } catch (IllegalStateException e) {
            Log.e(TAG, "clearInterfaceAddresses configuration failed: " + e);
        }

        mLinkProperties.clear();
        mNetworkInfo.setIsAvailable(false);
        mNetworkInfo.setDetailedState(DetailedState.DISCONNECTED, null, null);

        Message msg = mCsHandler.obtainMessage(EVENT_CONFIGURATION_CHANGED, mNetworkInfo);
        msg.sendToTarget();

        msg = mCsHandler.obtainMessage(EVENT_STATE_CHANGED, mNetworkInfo);
        msg.sendToTarget();

        //mIface = "";		
		if(DBG) Log.d(TAG, "handleDisable Exit");
	}

    private void interfaceAdded(String iface) {
        if (!iface.matches(sIfaceMatch))
            return;

		registerEthernetContentObserver();

        Log.d(TAG, "Adding " + iface);
		acquireWakeLock(mContext);

        synchronized(mIface) {
            if(!mIface.isEmpty())
                return;
            mIface = iface;
        }
		
		handleEnable();
    }

    private void interfaceRemoved(String iface) {
        if (!iface.equals(mIface))
            return;

        Log.d(TAG, "Removing " + iface);
		releaseWakeLock();

		mHandler.removeMessages(EVENT_INTERFACE_LINK_STATE_CHANGED);
		handleDisable();

		mIface = "";
    }

	private void getStaticIpInfo() {
		ipAddress = Settings.System.getString(mContext.getContentResolver(), Settings.System.ETHERNET_STATIC_IP);
		netmask = Settings.System.getString(mContext.getContentResolver(), Settings.System.ETHERNET_STATIC_NETMASK);
		gateway = Settings.System.getString(mContext.getContentResolver(), Settings.System.ETHERNET_STATIC_GATEWAY);
		dns1 = Settings.System.getString(mContext.getContentResolver(), Settings.System.ETHERNET_STATIC_DNS1);
		dns2 = Settings.System.getString(mContext.getContentResolver(), Settings.System.ETHERNET_STATIC_DNS2);

		prefixLength = 24;
		try {
			InetAddress mask = NetworkUtils.numericToInetAddress(netmask);
			prefixLength = NetworkUtils.netmaskIntToPrefixLength(NetworkUtils.inetAddressToInt(mask));
		} catch (Exception e) {
			Log.e(TAG, "netmask to prefixLength exception: " + e);
		}

		if(DBG) Log.d(TAG, "ipAddress = " + ipAddress + ", netmask = " + netmask + ", gateway = " + gateway +
			                         ", dns1 = " + dns1 + ", dns2 = " + dns2 + ", prefixLength = " + prefixLength);
	}

    private LinkAddress makeLinkAddress() {
        if (TextUtils.isEmpty(ipAddress)) {
            Log.e(TAG, "makeLinkAddress with empty ipAddress");
            return null;
        }
        return new LinkAddress(NetworkUtils.numericToInetAddress(ipAddress), prefixLength);
    }

    private LinkProperties makeLinkProperties() {
        LinkProperties p = new LinkProperties();
        p.addLinkAddress(makeLinkAddress());
        p.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(gateway)));
        if (TextUtils.isEmpty(dns1) == false) {
            p.addDns(NetworkUtils.numericToInetAddress(dns1));
        } else {
            Log.d(TAG, "makeLinkProperties with empty dns1!");
        }
        if (TextUtils.isEmpty(dns2) == false) {
            p.addDns(NetworkUtils.numericToInetAddress(dns2));
        } else {
            Log.d(TAG, "makeLinkProperties with empty dns2!");
        }
        return p;
    }	

    private void runDhcp() {
		if(DBG) Log.d(TAG, "runDhcp: dhcpDone = " + dhcpDone + ", mIface = " + mIface + ", isEthernetEnabled() = " + isEthernetEnabled());
		if(!mIface.isEmpty() /*&& !dhcpDone */&& isEthernetEnabled())
		{
			dhcpDone = true;
			registerEthernetContentObserver(); // register here when boot with plugged ethernet
        	Thread dhcpThread = new Thread(new Runnable() {
        	    public void run() {
        	        DhcpInfoInternal dhcpInfoInternal = new DhcpInfoInternal();
					if(!isUsingStaticIp()) {
						if(DBG) Log.d(TAG, "isUsingStaticIp = false");
	        	        if (!NetworkUtils.runDhcp(mIface, dhcpInfoInternal)) {
							if(ethCurrentState!=ETHER_STATE_DISCONNECTED)
								sendEthStateChangedBroadcast(ETHER_STATE_DISCONNECTED);
	        	            Log.e(TAG, "DHCP request error:" + NetworkUtils.getDhcpError());
							dhcpDone = false;
	        	            return;
	        	        }
	        	        mLinkProperties = dhcpInfoInternal.makeLinkProperties();
					} else {
						if(DBG) Log.d(TAG, "isUsingStaticIp = true");
						getStaticIpInfo();
		                InterfaceConfiguration ifcg = new InterfaceConfiguration();
		                ifcg.setLinkAddress(makeLinkAddress());
		                ifcg.setInterfaceUp();
						if(DBG) Log.d(TAG, "ifcg = " + ifcg);
		                try {
		                    mNwService.setInterfaceConfig(mIface, ifcg);
		                    Log.d(TAG, "Static IP configuration succeeded");
		                } catch (RemoteException re) {
		                    Log.e(TAG, "Static IP configuration failed: " + re);
		                } catch (IllegalStateException e) {
		                    Log.e(TAG, "Static IP configuration failed: " + e);
		                }
						 mLinkProperties = makeLinkProperties();
					}
        	        mLinkProperties.setInterfaceName(mIface);
					if(DBG) Log.d(TAG, "mLinkProperties = " + mLinkProperties);

        	        mNetworkInfo.setDetailedState(DetailedState.CONNECTED, null, null);
        	        Message msg = mCsHandler.obtainMessage(EVENT_STATE_CHANGED, mNetworkInfo);
        	        msg.sendToTarget();
					sendEthStateChangedBroadcast(ETHER_STATE_CONNECTED);
					acquireWakeLock(mContext);
        	    }
        	});
        	dhcpThread.start();
		}
    }

    public static synchronized EthernetDataTracker getInstance() {
        if (sInstance == null) sInstance = new EthernetDataTracker();
        return sInstance;
    }

    public Object Clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    public void setTeardownRequested(boolean isRequested) {
        mTeardownRequested.set(isRequested);
    }

    public boolean isTeardownRequested() {
        return mTeardownRequested.get();
    }

    /**
     * Begin monitoring connectivity
     */
    public void startMonitoring(Context context, Handler target) {
        mContext = context;
        mCsHandler = target;

        // register for notifications from NetworkManagement Service
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);

        mInterfaceObserver = new InterfaceObserver(this);

        // enable and try to connect to an ethernet interface that
        // already exists
        sIfaceMatch = context.getResources().getString(
            com.android.internal.R.string.config_ethernet_iface_regex);
        try {
            final String[] ifaces = service.listInterfaces();
            for (String iface : ifaces) {
                if (iface.matches(sIfaceMatch)) {
                    mIface = iface;
                    InterfaceConfiguration config = service.getInterfaceConfig(iface);
                    mLinkUp = config.isActive();
                    reconnect();
                    break;
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Could not get list of interfaces " + e);
        }

        try {
            service.registerObserver(mInterfaceObserver);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not register InterfaceObserver " + e);
        }
    }

    /**
     * Disable connectivity to a network
     * TODO: do away with return value after making MobileDataStateTracker async
     */
    public boolean teardown() {
        mTeardownRequested.set(true);
        NetworkUtils.stopDhcp(mIface);
		dhcpDone = false;
        return true;
    }

    /**
     * Re-enable connectivity to a network after a {@link #teardown()}.
     */
    public boolean reconnect() {
        mTeardownRequested.set(false);
        runDhcp();
        return true;
    }

    /**
     * Turn the wireless radio off for a network.
     * @param turnOn {@code true} to turn the radio on, {@code false}
     */
    public boolean setRadio(boolean turnOn) {
        return true;
    }

    /**
     * @return true - If are we currently tethered with another device.
     */
    public synchronized boolean isAvailable() {
        return mNetworkInfo.isAvailable();
    }

    /**
     * Tells the underlying networking system that the caller wants to
     * begin using the named feature. The interpretation of {@code feature}
     * is completely up to each networking implementation.
     * @param feature the name of the feature to be used
     * @param callingPid the process ID of the process that is issuing this request
     * @param callingUid the user ID of the process that is issuing this request
     * @return an integer value representing the outcome of the request.
     * The interpretation of this value is specific to each networking
     * implementation+feature combination, except that the value {@code -1}
     * always indicates failure.
     * TODO: needs to go away
     */
    public int startUsingNetworkFeature(String feature, int callingPid, int callingUid) {
        return -1;
    }

    /**
     * Tells the underlying networking system that the caller is finished
     * using the named feature. The interpretation of {@code feature}
     * is completely up to each networking implementation.
     * @param feature the name of the feature that is no longer needed.
     * @param callingPid the process ID of the process that is issuing this request
     * @param callingUid the user ID of the process that is issuing this request
     * @return an integer value representing the outcome of the request.
     * The interpretation of this value is specific to each networking
     * implementation+feature combination, except that the value {@code -1}
     * always indicates failure.
     * TODO: needs to go away
     */
    public int stopUsingNetworkFeature(String feature, int callingPid, int callingUid) {
        return -1;
    }

    @Override
    public void setUserDataEnable(boolean enabled) {
        Log.w(TAG, "ignoring setUserDataEnable(" + enabled + ")");
    }

    @Override
    public void setPolicyDataEnable(boolean enabled) {
        Log.w(TAG, "ignoring setPolicyDataEnable(" + enabled + ")");
    }

    /**
     * Check if private DNS route is set for the network
     */
    public boolean isPrivateDnsRouteSet() {
        return mPrivateDnsRouteSet.get();
    }

    /**
     * Set a flag indicating private DNS route is set
     */
    public void privateDnsRouteSet(boolean enabled) {
        mPrivateDnsRouteSet.set(enabled);
    }

    /**
     * Fetch NetworkInfo for the network
     */
    public synchronized NetworkInfo getNetworkInfo() {
        return mNetworkInfo;
    }

    /**
     * Fetch LinkProperties for the network
     */
    public synchronized LinkProperties getLinkProperties() {
        return new LinkProperties(mLinkProperties);
    }

   /**
     * A capability is an Integer/String pair, the capabilities
     * are defined in the class LinkSocket#Key.
     *
     * @return a copy of this connections capabilities, may be empty but never null.
     */
    public LinkCapabilities getLinkCapabilities() {
        return new LinkCapabilities(mLinkCapabilities);
    }

    /**
     * Fetch default gateway address for the network
     */
    public int getDefaultGatewayAddr() {
        return mDefaultGatewayAddr.get();
    }

    /**
     * Check if default route is set
     */
    public boolean isDefaultRouteSet() {
        return mDefaultRouteSet.get();
    }

    /**
     * Set a flag indicating default route is set for the network
     */
    public void defaultRouteSet(boolean enabled) {
        mDefaultRouteSet.set(enabled);
    }

    /**
     * Return the system properties name associated with the tcp buffer sizes
     * for this network.
     */
    public String getTcpBufferSizesPropName() {
        return "net.tcp.buffersize.wifi";
    }

    public void setDependencyMet(boolean met) {
        // not supported on this network
    }

	public int enableEthIface() {
		registerEthernetContentObserver();
		Settings.Secure.putInt(mContext.getContentResolver(), Settings.Secure.ETHERNET_ON, 1);
        return 0;
	}

	public int disableEthIface() {
		Settings.Secure.putInt(mContext.getContentResolver(), Settings.Secure.ETHERNET_ON, 0);
        return 0;
	}

	public String getEthIfaceName() {
		return mIface;
	}

	
}

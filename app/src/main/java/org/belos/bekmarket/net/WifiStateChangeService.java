package org.belos.belmarket.net;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import org.apache.commons.net.util.SubnetUtils;
import org.belos.belmarket.FDroidApp;
import org.belos.belmarket.Preferences;
import org.belos.belmarket.Utils;
import org.belos.belmarket.data.Repo;
import org.belos.belmarket.localrepo.LocalRepoKeyStore;
import org.belos.belmarket.localrepo.LocalRepoManager;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.cert.Certificate;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Handle state changes to the device's wifi, storing the required bits.
 * The {@link Intent} that starts it either has no extras included,
 * which is how it can be triggered by code, or it came in from the system
 * via {@link  org.belos.belmarket.receiver.WifiStateChangeReceiver}, in
 * which case an instance of {@link NetworkInfo} is included.
 *
 * The work is done in a {@link Thread} so that new incoming {@code Intents}
 * are not blocked by processing. A new {@code Intent} immediately nullifies
 * the current state because it means that something about the wifi has
 * changed.  Having the {@code Thread} also makes it easy to kill work
 * that is in progress.
 */
public class WifiStateChangeService extends IntentService {
    private static final String TAG = "WifiStateChangeService";

    public static final String BROADCAST = "org.belos.belmarket.action.WIFI_CHANGE";

    private WifiManager wifiManager;
    private static WifiInfoThread wifiInfoThread;

    public WifiStateChangeService() {
        super("WifiStateChangeService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST);
        if (intent == null) {
            Utils.debugLog(TAG, "received null Intent, ignoring");
            return;
        }
        Utils.debugLog(TAG, "WiFi change service started, clearing info about wifi state until we have figured it out again.");
        NetworkInfo ni = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
        wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
        int wifiState = wifiManager.getWifiState();
        if (ni == null || ni.isConnected()) {
            Utils.debugLog(TAG, "ni == " + ni + "  wifiState == " + printWifiState(wifiState));
            if (wifiState == WifiManager.WIFI_STATE_ENABLED
                    || wifiState == WifiManager.WIFI_STATE_DISABLING  // might be switching to hotspot
                    || wifiState == WifiManager.WIFI_STATE_DISABLED   // might be hotspot
                    || wifiState == WifiManager.WIFI_STATE_UNKNOWN) { // might be hotspot
                if (wifiInfoThread != null) {
                    wifiInfoThread.interrupt();
                }
                wifiInfoThread = new WifiInfoThread();
                wifiInfoThread.start();
            }
        }
    }

    public class WifiInfoThread extends Thread {
        private static final String TAG = "WifiInfoThread";

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST);
            try {
                FDroidApp.initWifiSettings();
                Utils.debugLog(TAG, "Checking wifi state (in background thread).");
                WifiInfo wifiInfo = null;

                int wifiState = wifiManager.getWifiState();

                while (FDroidApp.ipAddressString == null) {
                    if (isInterrupted()) { // can be canceled by a change via WifiStateChangeReceiver
                        return;
                    }
                    if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
                        wifiInfo = wifiManager.getConnectionInfo();
                        FDroidApp.ipAddressString = formatIpAddress(wifiInfo.getIpAddress());
                        DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
                        if (dhcpInfo != null) {
                            String netmask = formatIpAddress(dhcpInfo.netmask);
                            if (!TextUtils.isEmpty(FDroidApp.ipAddressString) && netmask != null) {
                                try {
                                    FDroidApp.subnetInfo = new SubnetUtils(FDroidApp.ipAddressString, netmask).getInfo();
                                } catch (IllegalArgumentException e) {
                                    // catch this mystery error: "java.lang.IllegalArgumentException: Could not parse [null/24]"
                                    e.printStackTrace();
                                }
                            }
                        }
                    } else if (wifiState == WifiManager.WIFI_STATE_DISABLED
                            || wifiState == WifiManager.WIFI_STATE_DISABLING) {
                        // try once to see if its a hotspot
                        setIpInfoFromNetworkInterface();
                        if (FDroidApp.ipAddressString == null) {
                            return;
                        }
                    } else { // a hotspot can be active during WIFI_STATE_UNKNOWN
                        setIpInfoFromNetworkInterface();
                    }

                    if (FDroidApp.ipAddressString == null) {
                        Thread.sleep(1000);
                        Utils.debugLog(TAG, "waiting for an IP address...");
                    }
                }
                if (isInterrupted()) { // can be canceled by a change via WifiStateChangeReceiver
                    return;
                }

                if (wifiInfo != null) {
                    String ssid = wifiInfo.getSSID();
                    Utils.debugLog(TAG, "Have wifi info, connected to " + ssid);
                    if (ssid != null) {
                        FDroidApp.ssid = ssid.replaceAll("^\"(.*)\"$", "$1");
                    }
                    String bssid = wifiInfo.getBSSID();
                    if (bssid != null) {
                        FDroidApp.bssid = bssid;
                    }
                }

                String scheme;
                if (Preferences.get().isLocalRepoHttpsEnabled()) {
                    scheme = "https";
                } else {
                    scheme = "http";
                }
                Repo repo = new Repo();
                repo.name = Preferences.get().getLocalRepoName();
                repo.address = String.format(Locale.ENGLISH, "%s://%s:%d/fdroid/repo",
                        scheme, FDroidApp.ipAddressString, FDroidApp.port);

                if (isInterrupted()) { // can be canceled by a change via WifiStateChangeReceiver
                    return;
                }

                Context context = WifiStateChangeService.this.getApplicationContext();
                LocalRepoManager lrm = LocalRepoManager.get(context);
                lrm.writeIndexPage(Utils.getSharingUri(FDroidApp.repo).toString());

                if (isInterrupted()) { // can be canceled by a change via WifiStateChangeReceiver
                    return;
                }

                // the fingerprint for the local repo's signing key
                LocalRepoKeyStore localRepoKeyStore = LocalRepoKeyStore.get(context);
                Certificate localCert = localRepoKeyStore.getCertificate();
                repo.fingerprint = Utils.calcFingerprint(localCert);

                FDroidApp.repo = repo;

                /*
                 * Once the IP address is known we need to generate a self
                 * signed certificate to use for HTTPS that has a CN field set
                 * to the ipAddressString. This must be run in the background
                 * because if this is the first time the singleton is run, it
                 * can take a while to instantiate.
                 */
                if (Preferences.get().isLocalRepoHttpsEnabled()) {
                    localRepoKeyStore.setupHTTPSCertificate();
                }

            } catch (LocalRepoKeyStore.InitException e) {
                Log.e(TAG, "Unable to configure a fingerprint or HTTPS for the local repo", e);
            } catch (InterruptedException e) {
                Utils.debugLog(TAG, "interrupted");
                return;
            }
            Intent intent = new Intent(BROADCAST);
            LocalBroadcastManager.getInstance(WifiStateChangeService.this).sendBroadcast(intent);
        }
    }

    private void setIpInfoFromNetworkInterface() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            if (networkInterfaces == null) {
                return;
            }
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface netIf = networkInterfaces.nextElement();

                for (Enumeration<InetAddress> inetAddresses = netIf.getInetAddresses(); inetAddresses.hasMoreElements();) {
                    InetAddress inetAddress = inetAddresses.nextElement();
                    if (inetAddress.isLoopbackAddress() || inetAddress instanceof Inet6Address) {
                        continue;
                    }
                    if (netIf.getDisplayName().contains("wlan0")
                            || netIf.getDisplayName().contains("eth0")
                            || netIf.getDisplayName().contains("ap0")) {
                        FDroidApp.ipAddressString = inetAddress.getHostAddress();
                        for (InterfaceAddress address : netIf.getInterfaceAddresses()) {
                            short networkPrefixLength = address.getNetworkPrefixLength();
                            if (networkPrefixLength > 32) {
                                // something is giving a "/64" netmask, IPv6?
                                // java.lang.IllegalArgumentException: Value [64] not in range [0,32]
                                continue;
                            }
                            if (inetAddress.equals(address.getAddress()) && !TextUtils.isEmpty(FDroidApp.ipAddressString)) {
                                String cidr = String.format(Locale.ENGLISH, "%s/%d",
                                        FDroidApp.ipAddressString, networkPrefixLength);
                                FDroidApp.subnetInfo = new SubnetUtils(cidr).getInfo();
                                break;
                            }
                        }
                    }
                }
            }
        } catch (SocketException e) {
            Log.e(TAG, "Could not get ip address", e);
        }
    }

    static String formatIpAddress(int ipAddress) {
        if (ipAddress == 0) {
            return null;
        }
        return String.format(Locale.ENGLISH, "%d.%d.%d.%d",
                ipAddress & 0xff,
                ipAddress >> 8 & 0xff,
                ipAddress >> 16 & 0xff,
                ipAddress >> 24 & 0xff);
    }

    private String printWifiState(int wifiState) {
        switch (wifiState) {
            case WifiManager.WIFI_STATE_DISABLED:
                return "WIFI_STATE_DISABLED";
            case WifiManager.WIFI_STATE_DISABLING:
                return "WIFI_STATE_DISABLING";
            case WifiManager.WIFI_STATE_ENABLING:
                return "WIFI_STATE_ENABLING";
            case WifiManager.WIFI_STATE_ENABLED:
                return "WIFI_STATE_ENABLED";
            case WifiManager.WIFI_STATE_UNKNOWN:
                return "WIFI_STATE_UNKNOWN";
        }
        return null;
    }
}

package com.tpn.adbautoenable;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class NetworkUtils {
    private static final String TAG = "ADBAutoEnable";

    /**
     * Gets the live IPv4 address of the active network interface.
     * Safe for API level 21+.
     */
    public static String getLiveDeviceIP(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork != null) {
                    LinkProperties linkProperties = cm.getLinkProperties(activeNetwork);
                    if (linkProperties != null) {
                        for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                            InetAddress address = linkAddress.getAddress();
                            if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                                return address.getHostAddress();
                            }
                        }
                    }
                }
            }

            // NetworkInterface enumeration fallback
            List<NetworkInterface> networkInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface networkInterface : networkInterfaces) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;

                List<InetAddress> addresses = Collections.list(networkInterface.getInetAddresses());
                for (InetAddress address : addresses) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to resolve live IP address", e);
        }
        return "127.0.0.1";
    }

    /**
     * Checks whether the device has an active Wi-Fi, Ethernet, or VPN connection.
     * Excludes raw cellular data to keep the service bound to LAN/VPN environments.
     * Safe for API level 21+.
     */
    public static boolean isNetworkConnected(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            Network network = cm.getActiveNetwork();
            if (network != null) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                return capabilities != null &&
                        (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking network status", e);
            return false;
        }
    }

    /**
     * Returns the first non-loopback IPv4 address bound to a named interface
     * (for example {@code wlan0} or {@code eth1}).
     * Useful on devices that keep Ethernet and Wi-Fi up simultaneously
     * (observed on Sony BRAVIA Android 14), where the ConnectivityManager
     * "active" network may not be the interface that advertises TLS ADB.
     */
    public static String getIpv4ForInterface(String interfaceName) {
        if (interfaceName == null || interfaceName.isEmpty()) return null;
        try {
            NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
            if (networkInterface == null || !networkInterface.isUp()) return null;
            for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address.getHostAddress();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve IPv4 for interface " + interfaceName, e);
        }
        return null;
    }

    public static SharedPreferences getDeviceProtectedPrefs(Context context, String prefsName) {
        Context storageContext = context.isDeviceProtectedStorage()
                ? context
                : context.createDeviceProtectedStorageContext();
        return storageContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    }
}
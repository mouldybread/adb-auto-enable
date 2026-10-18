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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class NetworkUtils {
    private static final String TAG = "ADBAutoEnable";

    /** Local Wi-Fi/Ethernet addresses, IPv4 first; never the VPN default route. */
    @SuppressWarnings("deprecation")
    public static List<String> getPhysicalAddresses(Context context) {
        Set<String> ipv4 = new LinkedHashSet<>();
        Set<String> ipv6 = new LinkedHashSet<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network network : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue;
                    LinkProperties links = cm.getLinkProperties(network);
                    if (links == null) continue;
                    for (LinkAddress link : links.getLinkAddresses()) add(link.getAddress(), ipv4, ipv6);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "LAN network lookup failed; trying LAN interfaces", e);
        }
        if (ipv4.isEmpty() && ipv6.isEmpty()) {
            // Conservative fallback: do not accidentally select cellular, bridges or VPNs.
            try {
                java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                if (interfaces != null) {
                    for (NetworkInterface iface : Collections.list(interfaces)) {
                        String name = iface.getName();
                        if (!iface.isUp() || iface.isLoopback() || iface.isPointToPoint()) continue;
                        if (!(name.startsWith("wlan") || name.startsWith("eth")
                                || name.startsWith("en") || name.startsWith("wifi"))) continue;
                        for (InetAddress address : Collections.list(iface.getInetAddresses())) {
                            add(address, ipv4, ipv6);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "LAN interface lookup failed", e);
            }
        }
        List<String> result = new ArrayList<>(ipv4);
        result.addAll(ipv6);
        return result;
    }

    private static void add(InetAddress address, Set<String> ipv4, Set<String> ipv6) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isMulticastAddress()) return;
        String host = address.getHostAddress();
        if (host == null) return;
        if (address instanceof Inet4Address) ipv4.add(host);
        else ipv6.add(host);
    }

    /** Compatibility entry point used by both device and web UI. */
    public static String getLiveDeviceIP(Context context) {
        List<String> addresses = getPhysicalAddresses(context);
        return addresses.isEmpty() ? "127.0.0.1" : addresses.get(0);
    }

    public static boolean isLocalPhysicalAddress(Context context, String host) {
        return LocalAdbAddresses.matches(host, getPhysicalAddresses(context));
    }

    public static List<String> getAdbCandidateHosts(Context context, String resolvedHost) {
        return LocalAdbAddresses.candidates(resolvedHost, getPhysicalAddresses(context));
    }

    /** LAN readiness does not require Internet access or a validated default route. */
    public static boolean isNetworkConnected(Context context) {
        return !getPhysicalAddresses(context).isEmpty();
    }

    public static SharedPreferences getDeviceProtectedPrefs(Context context, String prefsName) {
        Context storageContext = context.isDeviceProtectedStorage()
                ? context : context.createDeviceProtectedStorageContext();
        return storageContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    }
}

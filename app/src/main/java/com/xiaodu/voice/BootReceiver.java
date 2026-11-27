package com.xiaodu.voice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启。
 * 设备侧没有稳定桌面（Termux 方案需要 Termux:Boot，已卸载），
 * 改由 App 自己接 BOOT_COMPLETED 拉起。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "XzBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? "" : String.valueOf(intent.getAction());
        Log.i(TAG, "boot action: " + a);
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            return;
        }
        try {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
        }
    }
}

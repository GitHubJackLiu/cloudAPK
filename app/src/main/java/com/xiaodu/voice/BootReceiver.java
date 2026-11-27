package com.xiaodu.voice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启 + OTA 覆盖安装后自启。
 *
 * 设备侧没有稳定桌面（Termux 方案需要 Termux:Boot，已卸载），
 * 改由 App 自己接 BOOT_COMPLETED 拉起。
 *
 * 另外接 MY_PACKAGE_REPLACED：OTA 用 pm install -r 覆盖安装时，
 * 系统会杀掉发起安装的旧进程（codePath changed），进程内任何拉起
 * 代码都会随之消亡（setsid/am start 都试过，实测无效）。
 * 但系统会在安装完成、新代码就绪后主动发这个广播并临时拉起进程，
 * 这里趁机 startActivity 把界面切回来 —— 与发起进程完全解耦，可靠。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "XzBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? "" : String.valueOf(intent.getAction());
        Log.i(TAG, "boot action: " + a);
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a);
        // MY_PACKAGE_REPLACED 只发给被替换的包自己，不用担心被别的 App 触发
        boolean replaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
        if (!boot && !replaced) return;
        try {
            // 安装刚完成时有短暂竞态，稍等再起更稳
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Intent i = new Intent(context, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(i);
                    Log.i(TAG, "started after " + a);
                } catch (Throwable t) {
                    Log.e(TAG, "start failed", t);
                }
            }, replaced ? 500 : 0);
        } catch (Throwable t) {
            Log.e(TAG, "schedule failed", t);
        }
    }
}

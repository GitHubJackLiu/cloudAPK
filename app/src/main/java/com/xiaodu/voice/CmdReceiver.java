package com.xiaodu.voice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 调试用命令接收器。
 *
 * 为什么需要它：这台设备无法可靠注入按键或手势
 * （input keyevent 的 app_process 被 OOM 杀掉，input swipe 的长按不触发
 * WebView 的 touchstart），所以只能通过 adb 广播驱动 App 做事。
 *
 * 用法：
 *   adb shell am broadcast -a com.xiaodu.voice.AEC    跑 AEC 通路矩阵
 *   adb shell am broadcast -a com.xiaodu.voice.PANEL   打开 AEC 实验面板
 */
public class CmdReceiver extends BroadcastReceiver {
    private static final String TAG = "XzCmd";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        Log.i(TAG, "cmd received: " + a);
        if (a == null) return;
        try {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.setAction(a);
            context.startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
        }
    }
}

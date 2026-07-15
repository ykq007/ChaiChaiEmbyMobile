package dev.chaichai.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class AudioFocusReleaseReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        AudioFocusCompetitorActivity.release();
    }
}

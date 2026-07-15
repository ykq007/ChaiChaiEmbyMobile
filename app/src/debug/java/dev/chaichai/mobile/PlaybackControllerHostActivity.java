package dev.chaichai.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;

/** Minimal foreground host for verifying Android 15+ audio-focus behavior. */
public final class PlaybackControllerHostActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        View view = new View(this);
        view.setBackgroundColor(Color.BLACK);
        setContentView(view);
    }
}

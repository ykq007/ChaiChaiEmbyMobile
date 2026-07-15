package dev.chaichai.mobile;

import android.app.Activity;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;

/** A separate-UID test component that behaves like another media app taking audio focus. */
public final class AudioFocusCompetitorActivity extends Activity {
    public static final String EXTRA_GAIN = "gain";
    static AudioManager audio;
    static AudioFocusRequest request;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        release();
        audio = getSystemService(AudioManager.class);
        request = new AudioFocusRequest.Builder(
                getIntent().getIntExtra(EXTRA_GAIN, AudioManager.AUDIOFOCUS_GAIN))
                .setOnAudioFocusChangeListener(change -> { })
                .build();
        audio.requestAudioFocus(request);
        finish();
    }

    static void release() {
        if (audio != null && request != null) audio.abandonAudioFocusRequest(request);
        request = null;
        audio = null;
    }
}

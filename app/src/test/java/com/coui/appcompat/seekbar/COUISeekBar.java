package com.coui.appcompat.seekbar;

import android.content.Context;

public class COUISeekBar extends COUISeekBarCore {
    public interface Listener {
        void onStart(COUISeekBar bar);
        void onProgress(COUISeekBar bar, int value);
        void onStop(COUISeekBar bar);
    }

    public COUISeekBar(Context context) {}
    public void setOnSeekBarChangeListener(Listener listener) {}
}

class COUISeekBarCore {
    public interface CoreListener {
        void onProgress(int value, COUISeekBarCore bar, boolean fromUser);
    }

    public void setOnSeekBarChangeListener(CoreListener listener) {}
    public void setMax(int max) {}
    public void setProgress(int value) {}
}

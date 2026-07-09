package com.lyricsync.app.util;

import android.widget.SeekBar;

public final class SeekBars {
    private SeekBars() {}

    public interface ProgressListener {
        void onChanged(int progress);
    }

    public static void bind(SeekBar seekBar, int progress, ProgressListener listener) {
        seekBar.setProgress(progress);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                listener.onChanged(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
}

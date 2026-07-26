package com.lyricsync.app.util;

import android.widget.SeekBar;

public final class SeekBars {
    private SeekBars() {}

    public interface ProgressListener {
        /**
         * @param fromUser true when the user dragged the bar. Programmatic setProgress()
         *                 calls also land here, so anything with side effects the user
         *                 would notice (haptics, sounds) must check this flag.
         */
        void onChanged(int progress, boolean fromUser);
    }

    public static void bind(SeekBar seekBar, int progress, ProgressListener listener) {
        seekBar.setProgress(progress);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                listener.onChanged(progress, fromUser);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
}

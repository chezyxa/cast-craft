/*
 * Cast Craft - AirPlay screen mirroring receiver for Android TV / Google TV.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.castcraft.airplay;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/**
 * "Real" AirPlay video (YouTube and other apps): the iPhone sends a URL, the TV
 * plays it itself and the phone becomes the remote. UxPlay's lib fetches the
 * HLS playlists from the phone and serves them on http://localhost:port/, which
 * ExoPlayer plays here.
 *
 * Calls come from native threads; the player lives on the main thread.
 */
public final class HlsPlayer {
    private static final String TAG = "CastCraftHls";

    public interface Listener {
        void onHlsStarted();
        void onHlsStopped();
    }

    private final Context context;
    private final PlayerView view;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;

    // Snapshot for the phone's playback-info requests (read from native threads).
    private volatile double duration;
    private volatile double position = -1;
    private volatile double seekStart;
    private volatile double seekDuration;
    private volatile float rate;
    private volatile boolean ended;
    private volatile boolean active;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            updateSnapshot();
            if (player != null) main.postDelayed(this, 250);
        }
    };

    public HlsPlayer(Context context, PlayerView view, Listener listener) {
        this.context = context;
        this.view = view;
        this.listener = listener;
    }

    public boolean isActive() {
        return active;
    }

    public void play(String url, float startSeconds) {
        Log.i(TAG, "play " + url + " from " + startSeconds + " s");
        active = true;
        ended = false;
        position = -1;
        duration = 0;
        main.post(() -> {
            release();
            player = new ExoPlayer.Builder(context).build();
            view.setPlayer(player);
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED) {
                        ended = true;
                        updateSnapshot();
                    }
                }

                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    Log.e(TAG, "playback error", error);
                    ended = true;
                }
            });
            MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(url));
            if (url.contains(".m3u8")) {
                item.setMimeType(MimeTypes.APPLICATION_M3U8);
            }
            player.setMediaItem(item.build(), (long) (startSeconds * 1000));
            player.setPlayWhenReady(true);
            player.prepare();
            main.post(poller);
            listener.onHlsStarted();
        });
    }

    public void seek(float seconds) {
        main.post(() -> {
            if (player != null) player.seekTo((long) (seconds * 1000));
        });
    }

    public void setRate(float r) {
        main.post(() -> {
            if (player == null) return;
            if (r == 0f) {
                player.pause();
            } else {
                player.play();
            }
        });
    }

    /** Pauses for a playlist change and returns the current position in seconds. */
    public float pauseForPlaylistRemove() {
        main.post(() -> {
            if (player != null) player.pause();
        });
        double p = position;
        return p < 0 ? 0f : (float) p;
    }

    public void stop() {
        if (!active) return;
        active = false;
        main.post(() -> {
            release();
            listener.onHlsStopped();
        });
    }

    /**
     * {duration, position, seekStart, seekDuration, rate, finished(1/0)} in seconds,
     * as UxPlay's video_get_playback_info reports them.
     */
    public double[] playbackInfo() {
        return new double[]{duration, position, seekStart, seekDuration, rate, ended ? 1 : 0};
    }

    private void updateSnapshot() {
        ExoPlayer p = player;
        if (p == null) return;
        long dur = p.getDuration();
        long pos = p.getCurrentPosition();
        int state = p.getPlaybackState();
        if (state == Player.STATE_IDLE || dur == C.TIME_UNSET) {
            position = -1;
            duration = 0;
        } else {
            duration = dur / 1000.0;
            position = pos / 1000.0;
        }
        rate = p.isPlaying() ? 1f : 0f;
        seekStart = 0;
        seekDuration = duration;
    }

    private void release() {
        main.removeCallbacks(poller);
        if (player != null) {
            view.setPlayer(null);
            player.release();
            player = null;
        }
    }
}

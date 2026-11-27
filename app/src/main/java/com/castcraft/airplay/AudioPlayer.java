/*
 * Cast Craft - AirPlay screen mirroring receiver for Android TV / Google TV.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.castcraft.airplay;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Plays AirPlay audio: AAC-ELD (screen mirroring), AAC-LC, ALAC (if the device
 * has a decoder) or raw PCM. Audio is played as it arrives.
 */
public final class AudioPlayer {
    private static final String TAG = "CastCraftAudio";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;

    /** AirPlay compression types (ct). */
    static final int CT_PCM = 1;
    static final int CT_ALAC = 2;
    static final int CT_AAC_LC = 4;
    static final int CT_AAC_ELD = 8;

    // codec_data from UxPlay (renderers/audio_renderer.c)
    private static final byte[] AAC_ELD_CSD = {(byte) 0xf8, (byte) 0xe8, 0x50, 0x00};
    private static final byte[] AAC_LC_CSD = {0x12, 0x10};
    private static final byte[] ALAC_CSD = hex(
            "00000024616c616300000000000001600010280a0e0200ff00000000000000000000ac44");

    private static final byte[] RECONFIGURE = new byte[0];

    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(100);
    private final Object lock = new Object();
    private Thread thread;
    private volatile boolean running;
    private volatile int compressionType;
    private volatile float volume = 1f;

    // decoder thread state
    private MediaCodec codec;
    private AudioTrack track;
    private int activeType;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    public void configure(int ct, int samplesPerFrame) {
        Log.i(TAG, "audio format ct=" + ct + " spf=" + samplesPerFrame);
        compressionType = ct;
        queue.clear();
        queue.offer(RECONFIGURE);
        synchronized (lock) {
            if (!running) {
                running = true;
                thread = new Thread(this::loop, "CastCraftAudio");
                thread.start();
            }
        }
    }

    public void submit(byte[] data, long ptsUs) {
        if (!running) return;
        if (!queue.offer(data)) {
            queue.clear(); // too far behind: drop backlog to keep latency low
            queue.offer(data);
        }
    }

    public void flush() {
        queue.clear();
    }

    /** AirPlay volume: -30..0 dB, -144 = mute. */
    public void setAirPlayVolume(float db) {
        float v;
        if (db <= -30f) {
            v = 0f;
        } else if (db >= 0f) {
            v = 1f;
        } else {
            v = (float) Math.pow(10.0, db / 20.0);
        }
        volume = v;
        AudioTrack t = track;
        if (t != null) {
            try {
                t.setVolume(v);
            } catch (IllegalStateException ignored) {
            }
        }
    }

    public void stop() {
        Thread t;
        synchronized (lock) {
            running = false;
            t = thread;
            thread = null;
        }
        if (t != null) {
            t.interrupt();
            if (t != Thread.currentThread()) {
                try {
                    t.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        queue.clear();
    }

    private void loop() {
        try {
            while (running) {
                byte[] data = queue.poll(20, TimeUnit.MILLISECONDS);
                if (data == RECONFIGURE) {
                    setUp(compressionType);
                } else if (data != null) {
                    play(data);
                }
                drain();
            }
        } catch (InterruptedException ignored) {
        } finally {
            tearDown();
        }
    }

    private void setUp(int ct) {
        tearDown();
        activeType = ct;
        String mime;
        byte[] csd;
        switch (ct) {
            case CT_AAC_ELD:
                mime = MediaFormat.MIMETYPE_AUDIO_AAC;
                csd = AAC_ELD_CSD;
                break;
            case CT_AAC_LC:
                mime = MediaFormat.MIMETYPE_AUDIO_AAC;
                csd = AAC_LC_CSD;
                break;
            case CT_ALAC:
                mime = "audio/alac";
                csd = ALAC_CSD;
                break;
            default:
                mime = null;
                csd = null;
                break;
        }
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf * 2, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.setVolume(volume);
            track.play();
        } catch (Exception e) {
            Log.e(TAG, "cannot create AudioTrack", e);
            track = null;
            return;
        }
        if (mime == null) return; // PCM
        try {
            MediaFormat format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, CHANNELS);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
            if (ct == CT_AAC_ELD) {
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, 39); // AOT_ER_AAC_ELD
            }
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
            Log.i(TAG, "audio decoder " + codec.getName() + " for " + mime);
        } catch (Exception e) {
            Log.e(TAG, "no decoder for " + mime + ", audio disabled", e);
            releaseCodec();
        }
    }

    private void play(byte[] data) {
        if (track == null) return;
        if (activeType == CT_PCM) {
            track.write(data, 0, data.length);
            return;
        }
        if (codec == null) return;
        try {
            int index = codec.dequeueInputBuffer(20_000);
            if (index < 0) return;
            ByteBuffer buf = codec.getInputBuffer(index);
            if (buf == null) return;
            buf.clear();
            if (data.length > buf.capacity()) return;
            buf.put(data);
            codec.queueInputBuffer(index, 0, data.length, 0, 0);
        } catch (IllegalStateException e) {
            Log.e(TAG, "audio decoder error", e);
            releaseCodec();
        }
    }

    private void drain() {
        if (codec == null || track == null) return;
        try {
            while (true) {
                int index = codec.dequeueOutputBuffer(info, 0);
                if (index >= 0) {
                    ByteBuffer out = codec.getOutputBuffer(index);
                    if (out != null && info.size > 0) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        track.write(out, info.size, AudioTrack.WRITE_BLOCKING);
                    }
                    codec.releaseOutputBuffer(index, false);
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                }
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "audio decoder error", e);
            releaseCodec();
        }
    }

    private void releaseCodec() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
            codec = null;
        }
    }

    private void tearDown() {
        releaseCodec();
        if (track != null) {
            try {
                track.stop();
            } catch (Exception ignored) {
            }
            track.release();
            track = null;
        }
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}

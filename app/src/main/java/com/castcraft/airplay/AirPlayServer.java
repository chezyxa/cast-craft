/*
 * Cast Craft - AirPlay screen mirroring receiver for Android TV / Google TV.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.castcraft.airplay;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Java side of the native AirPlay server (UxPlay's lib, see app/src/main/cpp).
 * Native code calls the methods below from its own threads.
 */
public final class AirPlayServer {
    private static final String TAG = "CastCraft";

    static {
        System.loadLibrary("castcraft");
    }

    /** Events for the UI. Called from native threads. */
    public interface Listener {
        void onClientConnected(String name, String model);
        void onMirroringStarted();
        void onMirroringStopped();
        void onVideoSizeChanged(int width, int height);
        void onServerRestartRequested();
    }

    private final NsdManager nsdManager;
    private final Map<String, NsdManager.RegistrationListener> registrations = new HashMap<>();
    private final VideoDecoder video;
    private final AudioPlayer audio;
    private final HlsPlayer hls;
    private final Listener listener;
    private volatile boolean mirroring;

    public AirPlayServer(Context context, VideoDecoder video, AudioPlayer audio, HlsPlayer hls, Listener listener) {
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.video = video;
        this.audio = audio;
        this.hls = hls;
        this.listener = listener;
    }

    public boolean start(String name, String mac, String keyFile, int width, int height, int fps, String lang) {
        return nativeStart(name, mac, keyFile, width, height, fps, lang);
    }

    public void stop() {
        nativeStop();
        synchronized (registrations) {
            for (NsdManager.RegistrationListener l : registrations.values()) {
                try {
                    nsdManager.unregisterService(l);
                } catch (IllegalArgumentException ignored) {
                }
            }
            registrations.clear();
        }
        endMirroring();
        hls.stop();
    }

    private void endMirroring() {
        video.stop();
        audio.stop();
        if (mirroring) {
            mirroring = false;
            listener.onMirroringStopped();
        }
    }

    // ------------------------------------------------------------------ natives

    private native boolean nativeStart(String name, String mac, String keyFile, int width, int height, int fps,
                                       String lang);

    private native void nativeStop();

    private native void nativeResetConnections();

    // ------------------------------------------------------- called from native

    @SuppressWarnings("unused")
    private boolean registerService(String type, String name, int port, String[] keys, String[] values) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(type);
        info.setPort(port);
        for (int i = 0; i < keys.length; i++) {
            try {
                info.setAttribute(keys[i], values[i]);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "TXT attribute " + keys[i] + " rejected", e);
            }
        }
        NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "registered " + serviceInfo.getServiceName() + " " + type + " port " + port);
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "registration of " + type + " failed: " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "unregistered " + type);
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "unregistration of " + type + " failed: " + errorCode);
            }
        };
        synchronized (registrations) {
            NsdManager.RegistrationListener old = registrations.remove(type);
            if (old != null) {
                try {
                    nsdManager.unregisterService(old);
                } catch (IllegalArgumentException ignored) {
                }
            }
            try {
                nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l);
            } catch (RuntimeException e) {
                Log.e(TAG, "registerService " + type, e);
                return false;
            }
            registrations.put(type, l);
        }
        return true;
    }

    @SuppressWarnings("unused")
    private void unregisterService(String type) {
        synchronized (registrations) {
            NsdManager.RegistrationListener l = registrations.remove(type);
            if (l != null) {
                try {
                    nsdManager.unregisterService(l);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    @SuppressWarnings("unused")
    private void onClientRequest(String name, String model) {
        listener.onClientConnected(name, model);
    }

    @SuppressWarnings("unused")
    private void onVideoSize(int width, int height) {
        Log.i(TAG, "video size " + width + "x" + height);
    }

    @SuppressWarnings("unused")
    private void onVideoData(byte[] data, long ntpTimeNs) {
        if (!mirroring) {
            mirroring = true;
            listener.onMirroringStarted();
        }
        video.submit(data, ntpTimeNs / 1000);
    }

    @SuppressWarnings("unused")
    private void onVideoFlush() {
        video.flush();
    }

    @SuppressWarnings("unused")
    private void onAudioFormat(int compressionType, int samplesPerFrame) {
        audio.configure(compressionType, samplesPerFrame);
    }

    @SuppressWarnings("unused")
    private void onAudioData(byte[] data, long ntpTimeNs) {
        audio.submit(data, ntpTimeNs / 1000);
    }

    @SuppressWarnings("unused")
    private void onAudioFlush() {
        audio.flush();
    }

    @SuppressWarnings("unused")
    private void onAudioVolume(float volumeDb) {
        audio.setAirPlayVolume(volumeDb);
    }

    @SuppressWarnings("unused")
    private void onConnectionOpened() {
    }

    @SuppressWarnings("unused")
    private void onConnectionClosed(int remaining) {
        if (remaining <= 0) {
            endMirroring();
            hls.stop();
        }
    }

    @SuppressWarnings("unused")
    private void onConnectionReset(int reason) {
        // Client vanished (e.g. lost Wi-Fi). UxPlay restarts its server in this case.
        endMirroring();
        hls.stop();
        listener.onServerRestartRequested();
    }

    // reset types, see raop.h reset_type_t
    private static final int RESET_NOHOLD = 0;
    private static final int RESET_RTP_SHUTDOWN = 1;
    private static final int RESET_HLS_SHUTDOWN = 2;
    private static final int RESET_HLS_EOS = 3;
    private static final int RESET_ON_VIDEO_PLAY = 4;
    private static final int RESET_RTP_TO_HLS_TEARDOWN = 5;

    @SuppressWarnings("unused")
    private void onVideoReset(int type) {
        switch (type) {
            case RESET_ON_VIDEO_PLAY:
                break;
            case RESET_RTP_SHUTDOWN:
            case RESET_RTP_TO_HLS_TEARDOWN:
                endMirroring();
                break;
            case RESET_NOHOLD:
            case RESET_HLS_SHUTDOWN:
            case RESET_HLS_EOS:
            default:
                endMirroring();
                hls.stop();
                break;
        }
    }

    // ------------------------------------------------- AirPlay video (HLS / URL)

    @SuppressWarnings("unused")
    private void onHlsPlay(String url, float startSeconds) {
        endMirroring();
        hls.play(url, startSeconds);
    }

    @SuppressWarnings("unused")
    private void onHlsSeek(float seconds) {
        hls.seek(seconds);
    }

    @SuppressWarnings("unused")
    private void onHlsRate(float rate) {
        hls.setRate(rate);
    }

    @SuppressWarnings("unused")
    private void onHlsStop() {
        hls.stop();
    }

    @SuppressWarnings("unused")
    private double[] getHlsPlaybackInfo() {
        return hls.playbackInfo();
    }

    @SuppressWarnings("unused")
    private float onHlsPlaylistRemove() {
        return hls.pauseForPlaylistRemove();
    }
}

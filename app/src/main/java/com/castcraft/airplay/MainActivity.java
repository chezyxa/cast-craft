/*
 * Cast Craft - AirPlay screen mirroring receiver for Android TV / Google TV.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.castcraft.airplay;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.net.Inet4Address;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements AirPlayServer.Listener, VideoDecoder.SizeListener {
    private static final String TAG = "CastCraft";
    private static final String PREF_MAC = "mac";

    private final ExecutorService serverExecutor = Executors.newSingleThreadExecutor();

    private FrameLayout root;
    private SurfaceView surfaceView;
    private View idleView;
    private TextView titleView;
    private TextView statusView;

    private VideoDecoder video;
    private AudioPlayer audio;
    private AirPlayServer server;
    private String serverName;
    private int videoWidth;
    private int videoHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        LinearLayout idle = new LinearLayout(this);
        idle.setOrientation(LinearLayout.VERTICAL);
        idle.setGravity(Gravity.CENTER);
        idle.setBackgroundColor(0xFF101418);
        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        titleView.setGravity(Gravity.CENTER);
        statusView = new TextView(this);
        statusView.setTextColor(0xFFB0BEC5);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        statusView.setGravity(Gravity.CENTER);
        statusView.setLineSpacing(0, 1.3f);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        statusView.setPadding(pad, pad, pad, pad);
        idle.addView(titleView);
        idle.addView(statusView);
        idleView = idle;
        root.addView(idleView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        video = new VideoDecoder(this);
        audio = new AudioPlayer();
        server = new AirPlayServer(this, video, audio, this);
        serverName = deviceName();

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                video.setSurface(holder.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                video.setSurface(null);
            }
        });
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                applyAspect();
            }
        });
        showIdle(getString(R.string.status_starting));
    }

    @Override
    protected void onStart() {
        super.onStart();
        hideSystemUi();
        startServer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        serverExecutor.execute(server::stop);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        serverExecutor.shutdown();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void startServer() {
        final String mac = macAddress();
        final String keyFile = new File(getFilesDir(), "airplay.pem").getAbsolutePath();
        final int[] size = displaySize();
        serverExecutor.execute(() -> {
            server.stop();
            boolean ok = server.start(serverName, mac, keyFile, size[0], size[1], 60);
            Log.i(TAG, "server start: " + ok + " name=" + serverName + " mac=" + mac);
            runOnUiThread(() -> showIdle(ok ? readyText() : getString(R.string.status_failed)));
        });
    }

    // --------------------------------------------------------- server events

    @Override
    public void onClientConnected(String name, String model) {
        runOnUiThread(() -> showIdle(getString(R.string.status_connecting, name)));
    }

    @Override
    public void onMirroringStarted() {
        runOnUiThread(() -> idleView.setVisibility(View.GONE));
    }

    @Override
    public void onMirroringStopped() {
        runOnUiThread(() -> showIdle(readyText()));
    }

    @Override
    public void onVideoSizeChanged(int width, int height) {
        runOnUiThread(() -> {
            videoWidth = width;
            videoHeight = height;
            applyAspect();
        });
    }

    @Override
    public void onServerRestartRequested() {
        runOnUiThread(() -> {
            if (!isFinishing()) startServer();
        });
    }

    // ------------------------------------------------------------------- UI

    private void showIdle(String status) {
        titleView.setText(serverName);
        statusView.setText(status);
        idleView.setVisibility(View.VISIBLE);
    }

    private String readyText() {
        String ip = ipAddress();
        String text = getString(R.string.status_ready, serverName);
        if (ip != null) text += "\n" + getString(R.string.status_ip, ip);
        return text;
    }

    /** Letterbox the video inside the screen, keeping its aspect ratio. */
    private void applyAspect() {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0 || videoWidth <= 0 || videoHeight <= 0) return;
        int vw = w;
        int vh = (int) ((long) w * videoHeight / videoWidth);
        if (vh > h) {
            vh = h;
            vw = (int) ((long) h * videoWidth / videoHeight);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp.width != vw || lp.height != vh) {
            lp.width = vw;
            lp.height = vh;
            lp.gravity = Gravity.CENTER;
            surfaceView.setLayoutParams(lp);
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // ---------------------------------------------------------------- device

    private String deviceName() {
        String name = null;
        try {
            name = Settings.Global.getString(getContentResolver(), "device_name");
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) {
            name = Build.MODEL;
        }
        // mDNS instance names: keep it short and without characters that break the RAOP name
        name = name.replace('@', ' ').replace('.', ' ').trim();
        if (name.length() > 40) name = name.substring(0, 40).trim();
        return name.isEmpty() ? "Google TV" : name;
    }

    /** Stable random locally administered MAC used as the AirPlay device id. */
    private String macAddress() {
        SharedPreferences prefs = getSharedPreferences("castcraft", Context.MODE_PRIVATE);
        String mac = prefs.getString(PREF_MAC, null);
        if (mac == null) {
            byte[] b = new byte[6];
            new SecureRandom().nextBytes(b);
            b[0] = (byte) ((b[0] & 0xFC) | 0x02);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02x", b[i] & 0xff));
            }
            mac = sb.toString();
            prefs.edit().putString(PREF_MAC, mac).apply();
        }
        return mac;
    }

    /** Resolution requested from the iPhone: the TV's, capped at 1080p (H.264). */
    private int[] displaySize() {
        int w = 1920, h = 1080;
        try {
            Display d = getWindowManager().getDefaultDisplay();
            Display.Mode mode = d.getMode();
            int pw = Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight());
            int ph = Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight());
            if (pw > 0 && ph > 0 && pw < 1920) {
                w = pw;
                h = ph;
            }
        } catch (Exception ignored) {
        }
        return new int[]{w, h};
    }

    private String ipAddress() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n != null ? cm.getLinkProperties(n) : null;
            if (lp == null) return null;
            for (LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address) {
                    return a.getAddress().getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}

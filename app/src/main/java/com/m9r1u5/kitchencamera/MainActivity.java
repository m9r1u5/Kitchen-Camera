package com.m9r1u5.kitchencamera;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends AppCompatActivity {

    private Prefs prefs;
    private Onvif onvif;
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView status;
    private View topBar, controls;
    private Button btnQuality, btnSound;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private boolean fullscreen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = new Prefs(this);
        onvif = new Onvif(msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());

        playerView = findViewById(R.id.player);
        status = findViewById(R.id.status);
        topBar = findViewById(R.id.topBar);
        controls = findViewById(R.id.controls);
        btnQuality = findViewById(R.id.btnQuality);
        btnSound = findViewById(R.id.btnSound);

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        btnQuality.setOnClickListener(v -> {
            prefs.setHd(!prefs.hd());
            updateButtons();
            startStream();
        });

        btnSound.setOnClickListener(v -> {
            prefs.setMuted(!prefs.muted());
            if (player != null) player.setVolume(prefs.muted() ? 0f : 1f);
            updateButtons();
        });

        findViewById(R.id.btnSnapshot).setOnClickListener(v -> takeSnapshot());
        findViewById(R.id.btnFullscreen).setOnClickListener(v -> setFullscreen(true));
        playerView.setOnClickListener(v -> { if (fullscreen) setFullscreen(false); });

        bindMove(findViewById(R.id.btnUp), 0f, 1f);
        bindMove(findViewById(R.id.btnDown), 0f, -1f);
        bindMove(findViewById(R.id.btnLeft), -1f, 0f);
        bindMove(findViewById(R.id.btnRight), 1f, 0f);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (fullscreen) {
                    setFullscreen(false);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (!prefs.isConfigured()) {
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        running = true;
        onvif.configure(prefs.ip(), prefs.onvifPort(), prefs.user(), prefs.pass());
        updateButtons();
        startStream();
    }

    @Override
    protected void onStop() {
        super.onStop();
        running = false;
        handler.removeCallbacksAndMessages(null);
        onvif.stop();
        releasePlayer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        onvif.shutdown();
    }

    // ---------- live video ----------

    private void startStream() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (!running) return;
        if (!prefs.isConfigured()) {
            showStatus("Tap ⚙ to enter your camera details");
            return;
        }

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(500, 2000, 250, 250)
                .build();
        player = new ExoPlayer.Builder(this).setLoadControl(loadControl).build();
        player.setVolume(prefs.muted() ? 0f : 1f);
        playerView.setPlayer(player);

        RtspMediaSource source = new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(prefs.rtspUrl()));

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) hideStatus();
                else if (state == Player.STATE_BUFFERING) showStatus("Connecting…");
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                showStatus("Can't reach the camera.\nMake sure you're on your home Wi-Fi.\nRetrying…");
                handler.postDelayed(MainActivity.this::startStream, 4000);
            }
        });

        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);
        showStatus("Connecting…");
    }

    private void releasePlayer() {
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        status.setVisibility(View.GONE);
    }

    private void updateButtons() {
        btnQuality.setText(prefs.hd() ? "HD" : "SD");
        btnSound.setText(prefs.muted() ? "🔇" : "🔊");
    }

    // ---------- pan / tilt ----------

    @SuppressLint("ClickableViewAccessibility")
    private void bindMove(Button b, float x, float y) {
        b.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    onvif.move(x, y);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    onvif.stop();
                    return true;
                default:
                    return false;
            }
        });
    }

    // ---------- snapshot ----------

    private void takeSnapshot() {
        View surface = playerView.getVideoSurfaceView();
        if (!(surface instanceof TextureView) || player == null
                || player.getPlaybackState() != Player.STATE_READY) {
            Toast.makeText(this, "No live picture yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap bmp = ((TextureView) surface).getBitmap();
        if (bmp == null) {
            Toast.makeText(this, "Couldn't grab the picture", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = "Kitchen_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kitchen Camera");
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            Toast.makeText(this, "Couldn't save the picture", Toast.LENGTH_SHORT).show();
            return;
        }
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new Exception("no output");
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out);
            Toast.makeText(this, "Saved to Pictures/Kitchen Camera", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't save the picture", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- fullscreen ----------

    private void setFullscreen(boolean fs) {
        fullscreen = fs;
        topBar.setVisibility(fs ? View.GONE : View.VISIBLE);
        controls.setVisibility(fs ? View.GONE : View.VISIBLE);
        setRequestedOrientation(fs
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        WindowInsetsControllerCompat bars =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (fs) {
            bars.hide(WindowInsetsCompat.Type.systemBars());
            bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            Toast.makeText(this, "Tap the picture to exit full screen", Toast.LENGTH_SHORT).show();
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars());
        }
    }
}

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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final long CONNECT_TIMEOUT_MS = 15000;
    private static final long RETRY_DELAY_MS = 4000;

    private Prefs prefs;
    private Onvif onvif;
    private LibVLC libVlc;
    private MediaPlayer player;
    private VLCVideoLayout videoLayout;
    private TextView status;
    private View topBar, controls;
    private Button btnQuality, btnSound;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private boolean playing = false;
    private boolean viewsAttached = false;
    private boolean fullscreen = false;

    private final Runnable retry = this::startStream;
    private final Runnable connectTimeout = () -> {
        if (running && !playing) {
            showStatus("Can't reach the camera.\nMake sure you're on your home Wi-Fi\nand the login details are right.\nRetrying…");
            handler.postDelayed(retry, RETRY_DELAY_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = new Prefs(this);
        onvif = new Onvif(msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());

        ArrayList<String> args = new ArrayList<>();
        args.add("--network-caching=400");
        args.add("--no-drop-late-frames");
        args.add("--no-skip-frames");
        libVlc = new LibVLC(this, args);
        player = new MediaPlayer(libVlc);
        player.setEventListener(this::onPlayerEvent);

        videoLayout = findViewById(R.id.player);
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
            applyVolume();
            updateButtons();
        });

        findViewById(R.id.btnSnapshot).setOnClickListener(v -> takeSnapshot());
        findViewById(R.id.btnFullscreen).setOnClickListener(v -> setFullscreen(true));
        videoLayout.setOnClickListener(v -> { if (fullscreen) setFullscreen(false); });

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
        if (!viewsAttached) {
            // Texture view so snapshots can read the picture.
            player.attachViews(videoLayout, null, false, true);
            viewsAttached = true;
        }
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
        player.stop();
        if (viewsAttached) {
            player.detachViews();
            viewsAttached = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        onvif.shutdown();
        player.release();
        libVlc.release();
    }

    // ---------- live video ----------

    private void startStream() {
        handler.removeCallbacksAndMessages(null);
        playing = false;
        player.stop();
        if (!running) return;
        if (!prefs.isConfigured()) {
            showStatus("Tap ⚙ to enter your camera details");
            return;
        }

        Media media = new Media(libVlc, Uri.parse(prefs.rtspUrl()));
        media.addOption(":rtsp-user=" + prefs.user());
        media.addOption(":rtsp-pwd=" + prefs.pass());
        media.addOption(":network-caching=400");
        media.setHWDecoderEnabled(true, false);
        player.setMedia(media);
        media.release();
        player.play();
        applyVolume();

        showStatus("Connecting…");
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    private void onPlayerEvent(MediaPlayer.Event event) {
        switch (event.type) {
            case MediaPlayer.Event.Playing:
                applyVolume();
                break;
            case MediaPlayer.Event.Vout:
                if (event.getVoutCount() > 0) {
                    playing = true;
                    handler.removeCallbacks(connectTimeout);
                    hideStatus();
                }
                break;
            case MediaPlayer.Event.EncounteredError:
            case MediaPlayer.Event.EndReached:
                if (running) {
                    playing = false;
                    handler.removeCallbacks(connectTimeout);
                    showStatus("Lost the camera.\nMake sure you're on your home Wi-Fi.\nRetrying…");
                    handler.removeCallbacks(retry);
                    handler.postDelayed(retry, RETRY_DELAY_MS);
                }
                break;
            default:
                break;
        }
    }

    private void applyVolume() {
        player.setVolume(prefs.muted() ? 0 : 100);
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

    private static TextureView findTexture(View v) {
        if (v instanceof TextureView) return (TextureView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextureView t = findTexture(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    private void takeSnapshot() {
        TextureView tv = findTexture(videoLayout);
        Bitmap bmp = (playing && tv != null && tv.isAvailable()) ? tv.getBitmap() : null;
        if (bmp == null) {
            Toast.makeText(this, "No live picture yet", Toast.LENGTH_SHORT).show();
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

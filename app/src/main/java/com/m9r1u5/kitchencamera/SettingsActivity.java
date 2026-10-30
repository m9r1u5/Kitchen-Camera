package com.m9r1u5.kitchencamera;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private static final String RELEASES =
            "https://github.com/m9r1u5/kitchen-camera/releases/latest";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Prefs prefs = new Prefs(this);
        EditText ip = findViewById(R.id.ip);
        EditText user = findViewById(R.id.user);
        EditText pass = findViewById(R.id.pass);
        EditText rtspPort = findViewById(R.id.rtspPort);
        EditText onvifPort = findViewById(R.id.onvifPort);
        CheckBox show = findViewById(R.id.showPass);
        TextView version = findViewById(R.id.version);

        ip.setText(prefs.ip());
        user.setText(prefs.user());
        pass.setText(prefs.pass());
        rtspPort.setText(String.valueOf(prefs.rtspPort()));
        onvifPort.setText(String.valueOf(prefs.onvifPort()));

        try {
            version.setText("Version " + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {
        }

        show.setOnCheckedChangeListener((b, checked) -> {
            pass.setInputType(InputType.TYPE_CLASS_TEXT | (checked
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            pass.setSelection(pass.getText().length());
        });

        findViewById(R.id.btnSave).setOnClickListener(v -> {
            String ipText = ip.getText().toString().trim();
            String userText = user.getText().toString().trim();
            String passText = pass.getText().toString();
            if (ipText.isEmpty() || userText.isEmpty() || passText.isEmpty()) {
                Toast.makeText(this, "Fill in IP address, username and password", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.save(ipText, userText, passText,
                    parse(rtspPort.getText().toString(), 554),
                    parse(onvifPort.getText().toString(), 2020));
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            finish();
        });

        findViewById(R.id.btnUpdate).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES))));
    }

    private static int parse(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}

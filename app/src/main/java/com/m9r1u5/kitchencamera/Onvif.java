package com.m9r1u5.kitchencamera;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal ONVIF client for pan/tilt on Tapo cameras (port 2020). */
public class Onvif {

    public interface Listener {
        void onError(String message);
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final Listener listener;

    private volatile String host = "";
    private volatile int port = 2020;
    private volatile String user = "";
    private volatile String pass = "";
    private String profileToken;
    private boolean continuousSupported = true;
    private boolean moving = false;

    public Onvif(Listener listener) {
        this.listener = listener;
    }

    public void configure(String host, int port, String user, String pass) {
        worker.execute(() -> {
            this.host = host;
            this.port = port;
            this.user = user;
            this.pass = pass;
            profileToken = null;
            continuousSupported = true;
            moving = false;
        });
    }

    /** Start moving. x: -1 left .. 1 right, y: -1 down .. 1 up. */
    public void move(float x, float y) {
        worker.execute(() -> {
            try {
                String token = token();
                if (continuousSupported) {
                    try {
                        call(body("<tptz:ContinuousMove><tptz:ProfileToken>" + esc(token)
                                + "</tptz:ProfileToken><tptz:Velocity><tt:PanTilt x=\"" + num(x * 0.6f)
                                + "\" y=\"" + num(y * 0.6f) + "\"/></tptz:Velocity></tptz:ContinuousMove>"));
                        moving = true;
                        return;
                    } catch (IOException e) {
                        continuousSupported = false;
                    }
                }
                call(body("<tptz:RelativeMove><tptz:ProfileToken>" + esc(token)
                        + "</tptz:ProfileToken><tptz:Translation><tt:PanTilt x=\"" + num(x * 0.1f)
                        + "\" y=\"" + num(y * 0.1f) + "\"/></tptz:Translation></tptz:RelativeMove>"));
            } catch (Exception e) {
                error("Pan/tilt failed: " + e.getMessage());
            }
        });
    }

    public void stop() {
        worker.execute(() -> {
            if (!moving) return;
            moving = false;
            try {
                call(body("<tptz:Stop><tptz:ProfileToken>" + esc(token())
                        + "</tptz:ProfileToken><tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>false</tptz:Zoom></tptz:Stop>"));
            } catch (Exception e) {
                error("Stop failed: " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    // ---- internals (worker thread only) ----

    private String token() throws Exception {
        if (profileToken != null) return profileToken;
        String resp = call(body("<trt:GetProfiles/>"));
        Matcher m = Pattern.compile("Profiles[^>]*token=\"([^\"]+)\"").matcher(resp);
        profileToken = m.find() ? m.group(1) : "profile_1";
        return profileToken;
    }

    private String body(String inner) throws Exception {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String created = f.format(new Date());
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(nonce);
        sha.update(created.getBytes(StandardCharsets.UTF_8));
        sha.update(pass.getBytes(StandardCharsets.UTF_8));
        String digest = Base64.encodeToString(sha.digest(), Base64.NO_WRAP);
        String nonce64 = Base64.encodeToString(nonce, Base64.NO_WRAP);

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\""
                + " xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\""
                + " xmlns:tt=\"http://www.onvif.org/ver10/schema\">"
                + "<s:Header><Security s:mustUnderstand=\"1\" xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">"
                + "<UsernameToken><Username>" + esc(user) + "</Username>"
                + "<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">" + digest + "</Password>"
                + "<Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">" + nonce64 + "</Nonce>"
                + "<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" + created + "</Created>"
                + "</UsernameToken></Security></s:Header>"
                + "<s:Body>" + inner + "</s:Body></s:Envelope>";
    }

    private String call(String xml) throws IOException {
        if (host.isEmpty()) throw new IOException("camera not set up");
        URL url = new URL("http://" + host + ":" + port + "/onvif/service");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(5000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
            byte[] data = xml.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(data);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String resp = in == null ? "" : read(in);
            if (code >= 400 || resp.contains("Fault>")) {
                throw new IOException("camera said no (" + code + ")");
            }
            return resp;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static String num(float v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void error(String msg) {
        main.post(() -> listener.onError(msg));
    }
}

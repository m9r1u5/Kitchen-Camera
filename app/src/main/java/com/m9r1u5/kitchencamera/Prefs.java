package com.m9r1u5.kitchencamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** Camera settings stored privately on the phone. */
public class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("camera", Context.MODE_PRIVATE);
    }

    public String ip() { return sp.getString("ip", ""); }
    public String user() { return sp.getString("user", ""); }
    public String pass() { return sp.getString("pass", ""); }
    public int rtspPort() { return sp.getInt("rtspPort", 554); }
    public int onvifPort() { return sp.getInt("onvifPort", 2020); }
    public boolean hd() { return sp.getBoolean("hd", true); }
    public boolean muted() { return sp.getBoolean("muted", true); }

    public void setHd(boolean v) { sp.edit().putBoolean("hd", v).apply(); }
    public void setMuted(boolean v) { sp.edit().putBoolean("muted", v).apply(); }

    public void save(String ip, String user, String pass, int rtspPort, int onvifPort) {
        sp.edit()
                .putString("ip", ip.trim())
                .putString("user", user.trim())
                .putString("pass", pass)
                .putInt("rtspPort", rtspPort)
                .putInt("onvifPort", onvifPort)
                .apply();
    }

    public boolean isConfigured() {
        return !ip().isEmpty() && !user().isEmpty() && !pass().isEmpty();
    }

    /** Stream address without the login (login is sent separately to the player). */
    public String rtspUrl() {
        return "rtsp://" + ip() + ":" + rtspPort() + "/" + (hd() ? "stream1" : "stream2");
    }
}

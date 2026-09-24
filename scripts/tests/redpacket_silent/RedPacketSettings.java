package h.Hchat.hooks.items.payment.core;

public final class RedPacketSettings {
    public static final String KEY_GRAB_MODE = "mode";
    public static final String KEY_FAKE_PACKET_RECEIVE_ENABLE = "fake";
    public static final int DEFAULT_GRAB_MODE = 1;
    public boolean silentEnabled = true;
    public boolean fakeEnabled;
    public int getInt(String key, int fallback) { return fallback; }
    public boolean getBoolean(String key, boolean fallback) {
        return KEY_FAKE_PACKET_RECEIVE_ENABLE.equals(key) ? fakeEnabled : fallback;
    }
    public boolean isSilentGrabEnabled() { return silentEnabled; }
}

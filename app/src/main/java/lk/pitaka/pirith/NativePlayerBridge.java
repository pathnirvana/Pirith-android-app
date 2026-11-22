package lk.pitaka.pirith;

import android.webkit.JavascriptInterface;

/** window.NativePlayer in the web view: passes player commands (JSON) to MainActivity on the main thread. */
public class NativePlayerBridge {
    private final MainActivity activity;

    NativePlayerBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void command(final String json) {
        activity.runOnUiThread(() -> activity.onCommand(json));
    }
}

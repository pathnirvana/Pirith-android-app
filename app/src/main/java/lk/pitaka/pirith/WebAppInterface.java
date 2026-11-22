package lk.pitaka.pirith;

import android.app.Activity;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;

/** window.Android in the web view: things other than audio (audio is NativePlayerBridge). */
public class WebAppInterface {
    private final Activity activity;

    WebAppInterface(Activity activity) {
        this.activity = activity;
    }

    /** colour behind the status bar, so it matches the header in light and dark mode */
    @JavascriptInterface
    public void setStatusBarColor(final String colorHex) {
        activity.runOnUiThread(() -> {
            try {
                int color = Color.parseColor(colorHex);
                activity.findViewById(R.id.main).setBackgroundColor(color);

                double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
                View decorView = activity.getWindow().getDecorView();
                int flags = decorView.getSystemUiVisibility();
                if (luminance > 0.5) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;   // light background, dark icons
                else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;                   // dark background, light icons
                decorView.setSystemUiVisibility(flags);
            } catch (Exception e) {
                Log.e("Pirith", "Failed to set status bar color: " + e.getMessage());
            }
        });
    }
}

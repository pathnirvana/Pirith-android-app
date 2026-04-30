package lk.pitaka.pirith;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

// NOT used - just testing
public class WebAppInterface {
    Context mContext;
    /** Instantiate the interface and set the context */
    WebAppInterface(Context c) {
        mContext = c;
    }
    /** Show a toast from the web page */
    @JavascriptInterface
    public void showToast(String toast) {
        Toast.makeText(mContext, toast, Toast.LENGTH_SHORT).show();
    }
    @JavascriptInterface
    public void updateMediaState(final String title, final String artist, final boolean isPlaying) {
        if (mContext instanceof MainActivity) {
            ((Activity) mContext).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ((MainActivity) mContext).updateNativeMediaState(title, artist, isPlaying, 0, 0);
                }
            });
        }
    }

    @JavascriptInterface
    public void updateMediaStateFull(final String title, final String artist, final boolean isPlaying, final double positionMs, final double durationMs) {
        if (mContext instanceof MainActivity) {
            ((Activity) mContext).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ((MainActivity) mContext).updateNativeMediaState(title, artist, isPlaying, (long) positionMs, (long) durationMs);
                }
            });
        }
    }

    @JavascriptInterface
    public void setStatusBarColor(final String colorHex) {
        if (mContext instanceof Activity) {
            ((Activity) mContext).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Activity activity = (Activity) mContext;
                        int color = android.graphics.Color.parseColor(colorHex);
                        activity.findViewById(R.id.main).setBackgroundColor(color);

                        double luminance = (0.299 * android.graphics.Color.red(color) +
                                0.587 * android.graphics.Color.green(color) +
                                0.114 * android.graphics.Color.blue(color)) / 255;

                        View decorView = activity.getWindow().getDecorView();
                        int flags = decorView.getSystemUiVisibility();

                        if (luminance > 0.5) {
                            // Light background -> Dark icons
                            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        } else {
                            // Dark background -> Light icons
                            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        }
                        decorView.setSystemUiVisibility(flags);

                    } catch (Exception e) {
                        Log.e("LOG_TAG", "Failed to set status bar color: " + e.getMessage());
                    }
                }
            });
        }
    }
}

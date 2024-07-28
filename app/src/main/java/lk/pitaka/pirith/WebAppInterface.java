package lk.pitaka.pirith;

import android.content.Context;
import android.media.session.MediaSession;
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
}

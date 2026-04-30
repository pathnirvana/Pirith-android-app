package lk.pitaka.pirith;

import android.content.Context;
import android.content.Intent;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v4.media.MediaMetadataCompat;
import androidx.core.app.NotificationCompat;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.os.Build;
import android.graphics.BitmapFactory;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends AppCompatActivity {

    private MediaSessionCompat mediaSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // my code below
        WebViewClient client = new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                try {
                    java.net.URL givenUrl = new java.net.URL(url);
                    if (!givenUrl.toString().contains("pages/") && !givenUrl.toString().contains("index.html")) {
                        Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                        startActivity(intent);
                        return true;
                    }
                } catch (java.net.MalformedURLException e) {
                }
                return false; // open index/static pages links in the webview itself
            }
        };

        WebView myWebView = findViewById(R.id.mainWebView);
        android.webkit.WebSettings webSettings = myWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true); // for localStorage
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);
        webSettings.setLayoutAlgorithm(android.webkit.WebSettings.LayoutAlgorithm.SINGLE_COLUMN);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        myWebView.addJavascriptInterface(new WebAppInterface(this), "Android");
        myWebView.setWebViewClient(client);

        String webviewLoadUrl = "file:///android_asset/index.html";
        Log.e("LOG_TAG", "webview Url : " + webviewLoadUrl);
        myWebView.loadUrl(webviewLoadUrl);

        setupMediaSession();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        WebView myWebView = findViewById(R.id.mainWebView);
        // Check if the key event was the Back button and if there's history
        if ((keyCode == KeyEvent.KEYCODE_BACK) && myWebView.canGoBack()) {
            myWebView.goBack();
            return true;
        }
        // If it wasn't the Back key or there's no web page history, bubble up to the
        // default
        // system behavior (probably exit the activity)
        return super.onKeyDown(keyCode, event);
    }

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, "PirithMediaSession");

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                WebView myWebView = findViewById(R.id.mainWebView);
                if (myWebView != null)
                    myWebView.post(() -> myWebView
                            .evaluateJavascript("if(window.vueMediaControl) window.vueMediaControl('play');", null));
            }

            @Override
            public void onPause() {
                WebView myWebView = findViewById(R.id.mainWebView);
                if (myWebView != null)
                    myWebView.post(() -> myWebView
                            .evaluateJavascript("if(window.vueMediaControl) window.vueMediaControl('pause');", null));
            }

            @Override
            public void onSeekTo(long pos) {
                WebView myWebView = findViewById(R.id.mainWebView);
                if (myWebView != null) {
                    double seekSecs = pos / 1000.0;
                    myWebView.post(() -> myWebView.evaluateJavascript(
                            "if(window.vueMediaControl) window.vueMediaControl('seekTo', " + seekSecs + ");", null));
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaSession != null) {
            mediaSession.release();
        }
    }

    public void updateNativeMediaState(String title, String artist, boolean isPlaying, long position, long duration) {
        if (mediaSession == null)
            return;

        int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_SEEK_TO;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, position, 1.0f)
                .build());

        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build());

        mediaSession.setActive(true);

        showMediaNotification(title, artist, isPlaying);
    }

    private void showMediaNotification(String title, String artist, boolean isPlaying) {
        String CHANNEL_ID = "media_playback_channel";
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Media Playback",
                    NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(artist)
                .setOngoing(isPlaying)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0));

        if (isPlaying) {
            builder.addAction(android.R.drawable.ic_media_pause, "Pause",
                    androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                            PlaybackStateCompat.ACTION_PAUSE));
        } else {
            builder.addAction(android.R.drawable.ic_media_play, "Play",
                    androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                            PlaybackStateCompat.ACTION_PLAY));
        }

        notificationManager.notify(1, builder.build());
    }
}

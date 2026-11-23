package lk.pitaka.pirith;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows the pirith web app (app/src/main/assets, copied there by dev/sync-apps.sh of the pirith
 * repo) in a WebView and connects it to PlaybackService.
 *
 * Bridge - documented in the pirith repo at src/player/native.js:
 *   web  -> native   window.NativePlayer.command(json)   { cmd: setQueue | play | pause | seek | setRate | next | previous | sleepIn, ... }
 *   native -> web    window.__pirithNative({ type: state | ended | queueEnd, ... })   positions in seconds
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "Pirith";

    private WebView webView;
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private final ArrayDeque<Runnable> waitingForController = new ArrayDeque<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable sleepTask;
    private int lastIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.mainWebView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true); // playlists and listening counts are kept in localStorage
        // the app is loaded from file:// (kept so that existing users keep their saved playlists);
        // these two let its scripts and data files load from there
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        webView.addJavascriptInterface(new WebAppInterface(this), "Android");
        webView.addJavascriptInterface(new NativePlayerBridge(this), "NativePlayer");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file:///android_asset/")) return false; // the app itself
                try { // everything else (pitaka.lk, tipitaka.lk, youtube ...) opens in the browser
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    Log.w(TAG, "cannot open " + url, e);
                }
                return true;
            }
        });
        webView.loadUrl("file:///android_asset/index.html");

        connectPlayer();
    }

    // ---------------------------------------------------------------- player connection

    private void connectPlayer() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
            } catch (Exception e) {
                Log.e(TAG, "could not connect to the playback service", e);
                return;
            }
            // a freshly loaded web view has no queue: do not leave an old one playing unseen
            if (controller.getMediaItemCount() > 0) {
                controller.stop();
                controller.clearMediaItems();
            }
            controller.addListener(playerListener);
            while (!waitingForController.isEmpty()) waitingForController.poll().run();
        }, MoreExecutors.directExecutor());
    }

    private void withController(Runnable action) {
        if (controller != null) action.run();
        else waitingForController.add(action);
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(MediaItem mediaItem, int reason) {
            // AUTO = the previous pirith played to its end (as opposed to skipped)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && lastIndex >= 0) emit("ended", lastIndex);
            lastIndex = controller.getCurrentMediaItemIndex();
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_ENDED) {
                emit("ended", controller.getCurrentMediaItemIndex());
                emit("queueEnd", -1);
            }
        }

        @Override
        public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
            if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAYBACK_PARAMETERS_CHANGED, Player.EVENT_TIMELINE_CHANGED)) {
                emitState();
            }
        }
    };

    // ---------------------------------------------------------------- native -> web

    private void emitState() {
        if (controller == null) return;
        try {
            long duration = controller.getDuration();
            JSONObject o = new JSONObject();
            o.put("type", "state");
            o.put("playing", controller.isPlaying());
            o.put("index", controller.getCurrentMediaItemIndex());
            o.put("position", controller.getCurrentPosition() / 1000.0);
            o.put("duration", duration == C.TIME_UNSET ? 0 : duration / 1000.0);
            o.put("rate", controller.getPlaybackParameters().speed);
            send(o);
        } catch (JSONException ignored) {
        }
    }

    private void emit(String type, int index) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            if (index >= 0) o.put("index", index);
            send(o);
        } catch (JSONException ignored) {
        }
    }

    private void send(JSONObject event) {
        webView.evaluateJavascript("window.__pirithNative&&window.__pirithNative(" + event + ")", null);
    }

    // ---------------------------------------------------------------- web -> native

    /** called by NativePlayerBridge on the main thread */
    void onCommand(String json) {
        withController(() -> {
            try {
                run(new JSONObject(json));
            } catch (JSONException e) {
                Log.e(TAG, "bad command " + json, e);
            }
        });
    }

    private void run(JSONObject msg) throws JSONException {
        switch (msg.getString("cmd")) {
            case "setQueue": setQueue(msg); break;
            case "play":
                askNotificationPermission();
                if (controller.getPlaybackState() == Player.STATE_IDLE) controller.prepare();
                controller.play();
                break;
            case "pause": controller.pause(); break;
            case "seek": controller.seekTo((long) (msg.getDouble("position") * 1000)); break;
            case "setRate": controller.setPlaybackSpeed((float) msg.getDouble("rate")); break;
            // PlaybackService takes both to another pirith, past the repeats of this one
            case "next": controller.seekToNextMediaItem(); break;
            case "previous": controller.seekToPreviousMediaItem(); break;
            case "sleepIn": sleepIn(msg.optDouble("minutes", 0)); break;
            default: Log.w(TAG, "unknown command " + msg);
        }
    }

    private void setQueue(JSONObject msg) throws JSONException {
        JSONArray tracks = msg.getJSONArray("tracks");
        List<MediaItem> items = new ArrayList<>();
        for (int i = 0; i < tracks.length(); i++) items.add(toMediaItem(tracks.getJSONObject(i)));
        if (items.isEmpty()) return;
        int index = Math.min(msg.optInt("index", 0), items.size() - 1);

        MediaItem current = controller.getCurrentMediaItem();
        boolean keep = msg.optBoolean("keep", false) && current != null
                && current.mediaId.equals(items.get(index).mediaId);
        if (keep) {
            // the playlist was edited while playing: swap everything around the current pirith, leave it playing
            int cur = controller.getCurrentMediaItemIndex();
            int count = controller.getMediaItemCount();
            if (cur + 1 < count) controller.removeMediaItems(cur + 1, count);
            if (cur > 0) controller.removeMediaItems(0, cur);
            if (index + 1 < items.size()) controller.addMediaItems(1, items.subList(index + 1, items.size()));
            if (index > 0) controller.addMediaItems(0, items.subList(0, index));
            lastIndex = index;
            emitState();
            return;
        }
        lastIndex = index;
        controller.setMediaItems(items, index, (long) (msg.optDouble("position", 0) * 1000));
        controller.prepare();
        if (msg.optBoolean("play", true)) {
            askNotificationPermission();
            controller.play();
        }
    }

    /** url and artwork are paths inside assets/, e.g. "audio/ariyadhamma/09-rathana.m4a" */
    private MediaItem toMediaItem(JSONObject t) throws JSONException {
        String url = t.getString("url");
        Uri uri = Uri.parse("asset:///" + url);
        MediaMetadata.Builder meta = new MediaMetadata.Builder()
                .setTitle(t.optString("title"))
                .setArtist(t.optString("artist"))
                .setAlbumTitle(t.optString("album"));
        String artwork = t.optString("artwork");
        if (!artwork.isEmpty()) meta.setArtworkUri(Uri.parse("asset:///" + artwork));
        return new MediaItem.Builder()
                .setMediaId(url)
                .setUri(uri)
                .setRequestMetadata(new MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(meta.build())
                .build();
    }

    private void sleepIn(double minutes) {
        if (sleepTask != null) handler.removeCallbacks(sleepTask);
        sleepTask = null;
        if (minutes <= 0) return;
        sleepTask = () -> { if (controller != null) controller.pause(); };
        handler.postDelayed(sleepTask, (long) (minutes * 60000));
    }

    /** Android 13+: without this the playback notification is hidden (playback itself still works) */
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    // ---------------------------------------------------------------- activity

    @Override
    protected void onResume() {
        super.onResume();
        emitState(); // the web view may have missed updates while the screen was off
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (sleepTask != null) handler.removeCallbacks(sleepTask);
        if (controller != null) controller.removeListener(playerListener);
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        controller = null;
        super.onDestroy();
    }
}

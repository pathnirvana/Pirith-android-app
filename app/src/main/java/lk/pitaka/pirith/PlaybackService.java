package lk.pitaka.pirith;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays the pirith audio. Runs as a media foreground service, so playback continues with the
 * screen off and the queue moves to the next pirith without help from the web view.
 *
 * Media3 provides, without code here: the media notification and lock screen controls
 * (play / pause / previous / next / seek bar), headset and bluetooth buttons, audio focus
 * (pauses for phone calls and other players), pause when headphones are unplugged, and the
 * wake lock that keeps the CPU awake while playing.
 *
 * MainActivity talks to this service through a MediaController.
 */
public class PlaybackService extends MediaSessionService {
    private MediaSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        ExoPlayer player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(), /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();

        // tapping the notification returns to the app
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent sessionActivity = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        session = new MediaSession.Builder(this, new PirithSkipper(player))
                .setSessionActivity(sessionActivity)
                .setCallback(new MediaSession.Callback() {
                    // items sent by a controller can arrive without their uri: restore it from the request metadata
                    @NonNull
                    @Override
                    public ListenableFuture<List<MediaItem>> onAddMediaItems(@NonNull MediaSession mediaSession,
                            @NonNull MediaSession.ControllerInfo controller, @NonNull List<MediaItem> mediaItems) {
                        List<MediaItem> resolved = new ArrayList<>();
                        for (MediaItem item : mediaItems) {
                            if (item.localConfiguration == null && item.requestMetadata.mediaUri != null) {
                                item = item.buildUpon().setUri(item.requestMetadata.mediaUri).build();
                            }
                            resolved.add(item);
                        }
                        return Futures.immediateFuture(resolved);
                    }
                })
                .build();
    }

    /**
     * A pirith that is repeated is in the queue once for every repeat. Next / previous (notification,
     * lock screen, headset, the app) go to another pirith: the repeats of one pirith are skipped together.
     */
    private static class PirithSkipper extends ForwardingPlayer {
        PirithSkipper(Player player) { super(player); }

        private String idAt(int i) { return getMediaItemAt(i).mediaId; }

        private void toNext() {
            int cur = getCurrentMediaItemIndex(), count = getMediaItemCount();
            if (cur < 0 || cur >= count) return;
            for (int i = cur + 1; i < count; i++) {
                if (!idAt(i).equals(idAt(cur))) { seekTo(i, 0); return; }
            }
        }

        private void toPrevious() {
            int cur = getCurrentMediaItemIndex();
            if (cur < 0 || cur >= getMediaItemCount()) return;
            int i = cur;
            while (i >= 0 && idAt(i).equals(idAt(cur))) i--;
            if (i < 0) { seekTo(0); return; } // the first pirith: start it again
            while (i > 0 && idAt(i - 1).equals(idAt(i))) i--;
            seekTo(i, 0);
        }

        @Override public void seekToNext() { toNext(); }
        @Override public void seekToNextMediaItem() { toNext(); }
        @Override public void seekToPrevious() { toPrevious(); }
        @Override public void seekToPreviousMediaItem() { toPrevious(); }
    }

    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    /** the app was swiped away from recents: the playlist lives in the web view, so stop with it */
    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        Player player = session.getPlayer();
        player.stop();
        player.clearMediaItems();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.getPlayer().release();
            session.release();
            session = null;
        }
        super.onDestroy();
    }
}

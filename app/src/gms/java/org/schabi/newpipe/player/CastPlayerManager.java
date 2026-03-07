package org.schabi.newpipe.player;

import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.mediarouter.app.MediaRouteButton;

import androidx.media3.cast.CastPlayer;
import androidx.media3.cast.SessionAvailabilityListener;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;

import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.StreamTypeUtil;

import java.util.List;

/**
 * Manages the Google Cast (Chromecast) player lifecycle and media loading.
 * This is the GMS (Google Play Services) flavor implementation.
 */
public final class CastPlayerManager {
    private static final String TAG = CastPlayerManager.class.getSimpleName();
    private static final boolean DEBUG = Player.DEBUG;

    @Nullable
    private CastPlayer castPlayer;
    @Nullable
    private CastContext castContext;

    public CastPlayerManager(@NonNull final Context context) {
        try {
            castContext = CastContext.getSharedInstance(context);
            castPlayer = new CastPlayer(castContext);
        } catch (final Exception e) {
            Log.e(TAG, "Failed to initialize CastContext", e);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Lifecycle
    //////////////////////////////////////////////////////////////////////////*/

    public void initPlayer(
            @NonNull final androidx.media3.common.Player.Listener listener,
            @NonNull final CastSessionCallback sessionCallback) {
        if (castPlayer != null) {
            castPlayer.addListener(listener);
            castPlayer.setSessionAvailabilityListener(new SessionAvailabilityListener() {
                @Override
                public void onCastSessionAvailable() {
                    sessionCallback.onCastSessionAvailable();
                }

                @Override
                public void onCastSessionUnavailable() {
                    sessionCallback.onCastSessionUnavailable();
                }
            });
        }
    }

    public void destroyPlayer(
            @NonNull final androidx.media3.common.Player.Listener listener) {
        if (castPlayer != null) {
            castPlayer.removeListener(listener);
            castPlayer.setSessionAvailabilityListener(null);
        }
    }

    public void release() {
        if (castPlayer != null) {
            castPlayer.release();
            castPlayer = null;
        }
        castContext = null;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // State
    //////////////////////////////////////////////////////////////////////////*/

    @Nullable
    public CastPlayer getCastPlayer() {
        return castPlayer;
    }

    public boolean isCastSessionAvailable() {
        return castPlayer != null && castPlayer.isCastSessionAvailable();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Media loading
    //////////////////////////////////////////////////////////////////////////*/

    public void loadMedia(@NonNull final Context context,
                          @NonNull final StreamInfo info,
                          final long positionMs,
                          final boolean playWhenReady) {
        if (castPlayer == null) {
            return;
        }

        final List<VideoStream> sortedStreams = ListHelper.getSortedStreamVideosList(
                context, info.getVideoStreams(), null, false, false);
        final List<AudioStream> audioStreams = info.getAudioStreams();
        final int audioIndex = isNullOrEmpty(audioStreams) ? -1
                : ListHelper.getAudioFormatIndex(context, audioStreams, null);

        final CastStreamSelector.CastStreamSelection selection =
                CastStreamSelector.selectCastStream(
                        info.getHlsUrl(),
                        info.getDashMpdUrl(),
                        StreamTypeUtil.isLiveStream(info.getStreamType()),
                        sortedStreams,
                        audioStreams,
                        audioIndex);

        if (DEBUG && selection != null) {
            Log.d(TAG, "Cast: selected stream url=" + selection.url
                    + " mimeType=" + selection.mimeType);
        }

        if (selection != null) {
            final androidx.media3.common.MediaItem mediaItem =
                    StreamInfoTag.of(info)
                            .asMediaItem()
                            .buildUpon()
                            .setUri(Uri.parse(selection.url))
                            .setMimeType(selection.mimeType)
                            .build();

            castPlayer.setMediaItem(mediaItem, positionMs);
            castPlayer.setPlayWhenReady(playWhenReady);
            castPlayer.prepare();
        } else {
            Log.e(TAG, "Cast: no castable stream found for " + info.getUrl());
            ErrorUtil.createNotification(context, new ErrorInfo(
                    new Exception("No castable stream found"),
                    UserAction.PLAY_STREAM,
                    "No castable stream found for: " + info.getName(),
                    info.getServiceId(),
                    info.getUrl()));
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // UI
    //////////////////////////////////////////////////////////////////////////*/

    public static void setupMediaRouteButton(@NonNull final Context context,
                                             @NonNull final MediaRouteButton button) {
        CastButtonFactory.setUpMediaRouteButton(context, button);
    }
}

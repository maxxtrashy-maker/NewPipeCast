package org.schabi.newpipe.player;

import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.media3.cast.CastPlayer;
import androidx.media3.cast.SessionAvailabilityListener;
import androidx.media3.common.MimeTypes;
import com.google.android.gms.cast.framework.CastContext;

import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.StreamTypeUtil;

import java.util.List;

/**
 * Manages the Google Cast (Chromecast) player lifecycle and media loading.
 * Extracted from {@link Player} to separate Cast-specific concerns.
 */
@SuppressWarnings("WeakerAccess") // package-visible methods for testing
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

    /**
     * Registers a playback listener and a session availability listener on the CastPlayer.
     * Should be called when the player is initialized.
     *
     * @param listener        the playback event listener
     * @param sessionListener the Cast session availability listener
     */
    public void initPlayer(
            @NonNull final androidx.media3.common.Player.Listener listener,
            @NonNull final SessionAvailabilityListener sessionListener) {
        if (castPlayer != null) {
            castPlayer.addListener(listener);
            castPlayer.setSessionAvailabilityListener(sessionListener);
        }
    }

    /**
     * Unregisters the playback listener and session listener from the CastPlayer.
     * Should be called when the player is being destroyed.
     *
     * @param listener the playback event listener to remove
     */
    public void destroyPlayer(
            @NonNull final androidx.media3.common.Player.Listener listener) {
        if (castPlayer != null) {
            castPlayer.removeListener(listener);
            castPlayer.setSessionAvailabilityListener(null);
        }
    }

    /**
     * Releases the CastPlayer and clears references. Should be called on final cleanup.
     */
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
    // Stream selection (package-visible for testing)
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * Holds the result of Cast stream selection.
     */
    static final class CastStreamSelection {
        final String url;
        final String mimeType;

        CastStreamSelection(@NonNull final String url, @Nullable final String mimeType) {
            this.url = url;
            this.mimeType = mimeType;
        }
    }

    /**
     * Selects the best Cast-compatible stream from the given parameters.
     * This method is pure (no side effects) and testable without Android context.
     *
     * @param hlsUrl         HLS manifest URL (may be null)
     * @param dashMpdUrl     DASH MPD URL (may be null)
     * @param isLive         whether the stream is live
     * @param videoStreams    sorted video streams (highest quality first)
     * @param audioStreams    audio streams
     * @param audioIndex     preferred audio stream index (-1 if none)
     * @return the selected stream, or null if none found
     */
    @Nullable
    static CastStreamSelection selectCastStream(
            @Nullable final String hlsUrl,
            @Nullable final String dashMpdUrl,
            final boolean isLive,
            @NonNull final List<VideoStream> videoStreams,
            @Nullable final List<AudioStream> audioStreams,
            final int audioIndex) {

        // 1. HLS manifest (adaptive, best quality)
        if (!isNullOrEmpty(hlsUrl)) {
            return new CastStreamSelection(hlsUrl, MimeTypes.APPLICATION_M3U8);
        }

        // 2. DASH manifest (live only)
        if (isLive && !isNullOrEmpty(dashMpdUrl)) {
            return new CastStreamSelection(dashMpdUrl, MimeTypes.APPLICATION_MPD);
        }

        // 3. Progressive video+audio
        for (final VideoStream stream : videoStreams) {
            if (!stream.isVideoOnly()
                    && stream.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP
                    && stream.isUrl()) {
                return new CastStreamSelection(stream.getContent(),
                        stream.getFormat() != null
                                ? stream.getFormat().getMimeType() : null);
            }
        }

        // 4. Any non-video-only stream with URL
        for (final VideoStream stream : videoStreams) {
            if (!stream.isVideoOnly() && stream.isUrl()) {
                return new CastStreamSelection(stream.getContent(),
                        stream.getFormat() != null
                                ? stream.getFormat().getMimeType() : null);
            }
        }

        // 5. Audio-only progressive
        if (!isNullOrEmpty(audioStreams)) {
            final int idx = audioIndex >= 0 ? audioIndex : 0;
            if (idx < audioStreams.size()) {
                final AudioStream audio = audioStreams.get(idx);
                if (audio.isUrl()
                        && audio.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP) {
                    return new CastStreamSelection(audio.getContent(),
                            audio.getFormat() != null
                                    ? audio.getFormat().getMimeType() : null);
                }
            }
        }

        return null;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Media loading
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * Selects the best Cast-compatible stream and loads it to the Chromecast.
     * <p>
     * Stream selection follows this priority:
     * <ol>
     *   <li>HLS manifest URL (adaptive streaming, best quality on Cast)</li>
     *   <li>DASH manifest URL (live streams only)</li>
     *   <li>Progressive HTTP video streams with audio</li>
     *   <li>Any non-video-only stream accessible by URL</li>
     *   <li>Audio-only progressive streams (music, podcasts)</li>
     * </ol>
     *
     * @param context       the application context
     * @param info          the stream info containing available streams
     * @param positionMs    the playback position to seek to
     * @param playWhenReady whether to start playback immediately
     */
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

        final CastStreamSelection selection = selectCastStream(
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
}

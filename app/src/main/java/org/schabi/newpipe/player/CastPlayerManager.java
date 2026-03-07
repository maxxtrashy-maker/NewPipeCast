package org.schabi.newpipe.player;

import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.ext.cast.CastPlayer;
import com.google.android.exoplayer2.ext.cast.SessionAvailabilityListener;
import com.google.android.exoplayer2.util.MimeTypes;
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
            @NonNull final com.google.android.exoplayer2.Player.Listener listener,
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
            @NonNull final com.google.android.exoplayer2.Player.Listener listener) {
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

        String url = null;
        String mimeType = null;

        // 1. Try HLS manifest URL (works for both live and VOD on Chromecast)
        //    HLS adaptive streaming lets the Chromecast pick the best resolution
        //    (up to 1080p) instead of being limited to low-res progressive streams.
        if (!isNullOrEmpty(info.getHlsUrl())) {
            url = info.getHlsUrl();
            mimeType = MimeTypes.APPLICATION_M3U8;
            if (DEBUG) {
                Log.d(TAG, "Cast: using HLS manifest URL");
            }
        }

        // 2. For live streams, also try DASH manifest
        if (url == null && StreamTypeUtil.isLiveStream(info.getStreamType())
                && !isNullOrEmpty(info.getDashMpdUrl())) {
            url = info.getDashMpdUrl();
            mimeType = MimeTypes.APPLICATION_MPD;
            if (DEBUG) {
                Log.d(TAG, "Cast: using DASH manifest URL (live)");
            }
        }

        // 3. Try progressive video streams with audio
        if (url == null) {
            final List<VideoStream> sortedStreams = ListHelper.getSortedStreamVideosList(
                    context, info.getVideoStreams(), null, false, false);
            for (final VideoStream stream : sortedStreams) {
                if (!stream.isVideoOnly()
                        && stream.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP
                        && stream.isUrl()) {
                    url = stream.getContent();
                    if (stream.getFormat() != null) {
                        mimeType = stream.getFormat().getMimeType();
                    }
                    if (DEBUG) {
                        Log.d(TAG, "Cast: selected video stream "
                                + stream.getResolution() + " " + mimeType);
                    }
                    break;
                }
            }
        }

        // 4. Try any non-video-only stream regardless of delivery method
        if (url == null) {
            final List<VideoStream> sortedStreams = ListHelper.getSortedStreamVideosList(
                    context, info.getVideoStreams(), null, false, false);
            for (final VideoStream stream : sortedStreams) {
                if (!stream.isVideoOnly() && stream.isUrl()) {
                    url = stream.getContent();
                    if (stream.getFormat() != null) {
                        mimeType = stream.getFormat().getMimeType();
                    }
                    if (DEBUG) {
                        Log.d(TAG, "Cast: fallback video stream "
                                + stream.getResolution() + " " + mimeType);
                    }
                    break;
                }
            }
        }

        // 5. Try audio-only streams (music, podcasts, or when no video+audio exists)
        if (url == null && !isNullOrEmpty(info.getAudioStreams())) {
            final List<AudioStream> audioStreams = info.getAudioStreams();
            final int audioIndex = ListHelper.getAudioFormatIndex(
                    context, audioStreams, null);
            final int idx = audioIndex >= 0 ? audioIndex : 0;
            if (idx < audioStreams.size()) {
                final AudioStream audio = audioStreams.get(idx);
                if (audio.isUrl()
                        && audio.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP) {
                    url = audio.getContent();
                    if (audio.getFormat() != null) {
                        mimeType = audio.getFormat().getMimeType();
                    }
                    if (DEBUG) {
                        Log.d(TAG, "Cast: using audio-only stream " + mimeType);
                    }
                }
            }
        }

        if (url != null) {
            final com.google.android.exoplayer2.MediaItem mediaItem =
                    StreamInfoTag.of(info)
                            .asMediaItem()
                            .buildUpon()
                            .setUri(Uri.parse(url))
                            .setMimeType(mimeType)
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

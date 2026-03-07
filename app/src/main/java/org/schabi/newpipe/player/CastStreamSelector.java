package org.schabi.newpipe.player;

import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.media3.common.MimeTypes;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.List;

/**
 * Pure stream-selection logic for Google Cast, with no dependency on Google Play Services.
 * Kept in the {@code main} source set so that it can be unit-tested in all flavors.
 */
public final class CastStreamSelector {

    private CastStreamSelector() {
    }

    /**
     * Holds the result of Cast stream selection.
     */
    public static final class CastStreamSelection {
        public final String url;
        public final String mimeType;

        public CastStreamSelection(@NonNull final String url, @Nullable final String mimeType) {
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
    public static CastStreamSelection selectCastStream(
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
}

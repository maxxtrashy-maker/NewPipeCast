package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.Collections;
import java.util.List;

public class CastStreamSelectionTest {

    // region HLS priority

    @Test
    public void hlsUrlIsSelectedFirst() {
        final CastStreamSelector.CastStreamSelection result =
                CastStreamSelector.selectCastStream(
                        "https://example.com/hls.m3u8",
                        null, false,
                        List.of(progressiveVideoStream("720p")),
                        null, -1);

        assertNotNull(result);
        assertEquals("https://example.com/hls.m3u8", result.url);
        assertEquals("application/x-mpegURL", result.mimeType);
    }

    @Test
    public void hlsUrlIsSelectedOverProgressiveEvenForVod() {
        final CastStreamSelector.CastStreamSelection result =
                CastStreamSelector.selectCastStream(
                        "https://example.com/hls.m3u8",
                        null, false,
                        List.of(progressiveVideoStream("1080p")),
                        null, -1);

        assertNotNull(result);
        assertEquals("https://example.com/hls.m3u8", result.url);
    }

    // endregion

    // region DASH for live

    @Test
    public void dashUrlIsSelectedForLiveWhenNoHls() {
        final CastStreamSelector.CastStreamSelection result =
                CastStreamSelector.selectCastStream(
                        null,
                        "https://example.com/dash.mpd",
                        true,
                        Collections.emptyList(),
                        null, -1);

        assertNotNull(result);
        assertEquals("https://example.com/dash.mpd", result.url);
        assertEquals("application/dash+xml", result.mimeType);
    }

    @Test
    public void dashUrlIsNotUsedForVod() {
        final CastStreamSelector.CastStreamSelection result =
                CastStreamSelector.selectCastStream(
                        null,
                        "https://example.com/dash.mpd",
                        false,
                        Collections.emptyList(),
                        null, -1);

        assertNull(result);
    }

    // endregion

    // region Progressive video streams

    @Test
    public void progressiveVideoWithAudioIsSelected() {
        final CastStreamSelector.CastStreamSelection result =
                CastStreamSelector.selectCastStream(
                        null, null, false,
                        List.of(progressiveVideoStream("720p")),
                        null, -1);

        assertNotNull(result);
        assertEquals("https://example.com/video-720p.mp4", result.url);
        assertEquals("video/mp4", result.mimeType);
    }

    @Test
    public void videoOnlyStreamsAreSkipped() {
        final CastStreamSelector.CastStreamSelection result =
                CastStreamSelector.selectCastStream(
                        null, null, false,
                        List.of(videoOnlyStream("1080p"),
                                progressiveVideoStream("360p")),
                        null, -1);

        assertNotNull(result);
        assertEquals("https://example.com/video-360p.mp4", result.url);
    }

    @Test
    public void nonProgressiveStreamsAreSkippedInStep3ButUsedInStep4() {
        final CastStreamSelector.CastStreamSelection result =
                CastStreamSelector.selectCastStream(
                        null, null, false,
                        List.of(dashVideoStream("720p")),
                        null, -1);

        assertNotNull(result);
        assertEquals("https://example.com/video-720p.mp4", result.url);
    }

    @Test
    public void firstMatchingStreamIsSelected() {
        final CastStreamSelector.CastStreamSelection result =
                CastStreamSelector.selectCastStream(
                        null, null, false,
                        List.of(progressiveVideoStream("720p"),
                                progressiveVideoStream("360p")),
                        null, -1);

        assertNotNull(result);
        assertEquals("https://example.com/video-720p.mp4", result.url);
    }

    // endregion

    // region Audio-only fallback

    @Test
    public void audioOnlyIsUsedWhenNoVideoAvailable() {
        final CastStreamSelector.CastStreamSelection result =
                CastStreamSelector.selectCastStream(
                        null, null, false,
                        Collections.emptyList(),
                        List.of(progressiveAudioStream()),
                        0);

        assertNotNull(result);
        assertEquals("https://example.com/audio.m4a", result.url);
        assertEquals("audio/mp4", result.mimeType);
    }

    @Test
    public void audioOnlyIsNotUsedWhenVideoExists() {
        final CastStreamSelector.CastStreamSelection result =
                CastStreamSelector.selectCastStream(
                        null, null, false,
                        List.of(progressiveVideoStream("360p")),
                        List.of(progressiveAudioStream()),
                        0);

        assertNotNull(result);
        assertEquals("https://example.com/video-360p.mp4", result.url);
    }

    // endregion

    // region No stream found

    @Test
    public void returnsNullWhenNoStreamsAvailable() {
        final CastStreamSelector.CastStreamSelection result =
                CastStreamSelector.selectCastStream(
                        null, null, false,
                        Collections.emptyList(),
                        null, -1);

        assertNull(result);
    }

    @Test
    public void returnsNullWhenOnlyVideoOnlyStreams() {
        final CastStreamSelector.CastStreamSelection result =
                CastStreamSelector.selectCastStream(
                        null, null, false,
                        List.of(videoOnlyStream("1080p"),
                                videoOnlyStream("720p")),
                        null, -1);

        assertNull(result);
    }

    // endregion

    // region Priority order

    @Test
    public void hlsTakesPriorityOverDashForLive() {
        final CastStreamSelector.CastStreamSelection result =
                CastStreamSelector.selectCastStream(
                        "https://example.com/hls.m3u8",
                        "https://example.com/dash.mpd",
                        true,
                        Collections.emptyList(),
                        null, -1);

        assertNotNull(result);
        assertEquals("https://example.com/hls.m3u8", result.url);
    }

    @Test
    public void hlsTakesPriorityOverProgressiveVideo() {
        final CastStreamSelector.CastStreamSelection result =
                CastStreamSelector.selectCastStream(
                        "https://example.com/hls.m3u8",
                        null, false,
                        List.of(progressiveVideoStream("1080p")),
                        null, -1);

        assertNotNull(result);
        assertEquals("https://example.com/hls.m3u8", result.url);
    }

    // endregion

    // region Helpers

    @NonNull
    private static VideoStream progressiveVideoStream(@NonNull final String resolution) {
        return new VideoStream.Builder()
                .setId("video-" + resolution)
                .setContent("https://example.com/video-" + resolution + ".mp4", true)
                .setIsVideoOnly(false)
                .setResolution(resolution)
                .setMediaFormat(MediaFormat.MPEG_4)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .build();
    }

    @NonNull
    private static VideoStream videoOnlyStream(@NonNull final String resolution) {
        return new VideoStream.Builder()
                .setId("video-only-" + resolution)
                .setContent("https://example.com/video-only-" + resolution + ".mp4", true)
                .setIsVideoOnly(true)
                .setResolution(resolution)
                .setMediaFormat(MediaFormat.MPEG_4)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .build();
    }

    @NonNull
    private static VideoStream dashVideoStream(@NonNull final String resolution) {
        return new VideoStream.Builder()
                .setId("dash-video-" + resolution)
                .setContent("https://example.com/video-" + resolution + ".mp4", true)
                .setIsVideoOnly(false)
                .setResolution(resolution)
                .setMediaFormat(MediaFormat.MPEG_4)
                .setDeliveryMethod(DeliveryMethod.DASH)
                .build();
    }

    @NonNull
    private static AudioStream progressiveAudioStream() {
        return new AudioStream.Builder()
                .setId("audio-m4a")
                .setContent("https://example.com/audio.m4a", true)
                .setMediaFormat(MediaFormat.M4A)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setAverageBitrate(128)
                .build();
    }

    // endregion
}

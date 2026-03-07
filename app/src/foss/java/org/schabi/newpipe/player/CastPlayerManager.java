package org.schabi.newpipe.player;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.mediarouter.app.MediaRouteButton;

import org.schabi.newpipe.extractor.stream.StreamInfo;

/**
 * No-op Cast player manager for the FOSS (no Google Play Services) flavor.
 * All methods are safe to call but do nothing.
 */
public final class CastPlayerManager {

    @SuppressWarnings("unused")
    public CastPlayerManager(@NonNull final Context context) {
        // no-op: Google Play Services not available
    }

    public void initPlayer(
            @NonNull final androidx.media3.common.Player.Listener listener,
            @NonNull final CastSessionCallback sessionCallback) {
        // no-op
    }

    public void destroyPlayer(
            @NonNull final androidx.media3.common.Player.Listener listener) {
        // no-op
    }

    public void release() {
        // no-op
    }

    @Nullable
    public androidx.media3.common.Player getCastPlayer() {
        return null;
    }

    public boolean isCastSessionAvailable() {
        return false;
    }

    public void loadMedia(@NonNull final Context context,
                          @NonNull final StreamInfo info,
                          final long positionMs,
                          final boolean playWhenReady) {
        // no-op
    }

    public static void setupMediaRouteButton(@NonNull final Context context,
                                             @NonNull final MediaRouteButton button) {
        button.setVisibility(View.GONE);
    }
}

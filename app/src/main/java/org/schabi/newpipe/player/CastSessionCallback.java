package org.schabi.newpipe.player;

/**
 * Callback interface for Cast session availability changes.
 * Replaces direct dependency on {@code SessionAvailabilityListener} from media3-cast
 * so that {@link Player} does not depend on Google Play Services.
 */
public interface CastSessionCallback {
    void onCastSessionAvailable();
    void onCastSessionUnavailable();
}

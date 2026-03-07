package org.schabi.newpipe.player.mediasession;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Compatibility bridge between Media3 {@link Player} and {@link MediaSessionCompat}.
 * Replaces the removed {@code com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector}
 * for the Media3 migration.
 */
public class MediaSessionConnector {

    /**
     * Interface for preparing playback.
     */
    public interface PlaybackPreparer {
        long getSupportedPrepareActions();

        void onPrepare(boolean playWhenReady);

        void onPrepareFromMediaId(String mediaId, boolean playWhenReady, Bundle extras);

        void onPrepareFromSearch(String query, boolean playWhenReady, Bundle extras);

        void onPrepareFromUri(android.net.Uri uri, boolean playWhenReady, Bundle extras);

        boolean onCommand(Player player, String command, Bundle extras, ResultReceiver cb);
    }

    /**
     * Interface for navigating the play queue.
     */
    public interface QueueNavigator {
        long getSupportedQueueNavigatorActions(@Nullable Player player);

        void onTimelineChanged(@NonNull Player player);

        void onCurrentMediaItemIndexChanged(@NonNull Player player);

        long getActiveQueueItemId(@Nullable Player player);

        void onSkipToPrevious(@NonNull Player player);

        void onSkipToQueueItem(@NonNull Player player, long id);

        void onSkipToNext(@NonNull Player player);

        boolean onCommand(@NonNull Player player, @NonNull String command,
                          @Nullable Bundle extras, @Nullable ResultReceiver cb);
    }

    /**
     * Interface for custom media session actions.
     */
    public interface CustomActionProvider {
        void onCustomAction(@NonNull Player player, @NonNull String action,
                            @Nullable Bundle extras);

        @Nullable
        PlaybackStateCompat.CustomAction getCustomAction(@NonNull Player player);
    }

    private final MediaSessionCompat mediaSession;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable private Player player;
    @Nullable private PlaybackPreparer playbackPreparer;
    @Nullable private QueueNavigator queueNavigator;
    @Nullable private CustomActionProvider[] customActionProviders;
    @Nullable private Function<Player, MediaMetadataCompat> mediaMetadataProvider;
    @Nullable private BiFunction<Player, android.content.Intent, Boolean> mediaButtonEventHandler;
    private boolean metadataDeduplicationEnabled;

    @Nullable private MediaMetadataCompat lastMetadata;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(final int playbackState) {
            updatePlaybackState();
        }

        @Override
        public void onPlayWhenReadyChanged(final boolean playWhenReady, final int reason) {
            updatePlaybackState();
        }

        @Override
        public void onIsPlayingChanged(final boolean isPlaying) {
            updatePlaybackState();
        }

        @Override
        public void onPositionDiscontinuity(
                @NonNull final Player.PositionInfo oldPosition,
                @NonNull final Player.PositionInfo newPosition,
                final int reason) {
            updatePlaybackState();
        }

        @Override
        public void onTimelineChanged(
                @NonNull final androidx.media3.common.Timeline timeline,
                final int reason) {
            if (queueNavigator != null && player != null) {
                queueNavigator.onTimelineChanged(player);
            }
            updatePlaybackState();
        }

        @Override
        public void onMediaItemTransition(
                @Nullable final androidx.media3.common.MediaItem mediaItem,
                final int reason) {
            if (queueNavigator != null && player != null) {
                queueNavigator.onCurrentMediaItemIndexChanged(player);
            }
            invalidateMediaSessionMetadata();
            updatePlaybackState();
        }
    };

    public MediaSessionConnector(@NonNull final MediaSessionCompat mediaSession) {
        this.mediaSession = mediaSession;
        mediaSession.setCallback(new MediaSessionCallback(), handler);
    }

    public void setPlayer(@Nullable final Player newPlayer) {
        if (this.player != null) {
            this.player.removeListener(playerListener);
        }
        this.player = newPlayer;
        if (newPlayer != null) {
            newPlayer.addListener(playerListener);
        }
        invalidateMediaSessionMetadata();
        updatePlaybackState();
    }

    public void setPlaybackPreparer(@Nullable final PlaybackPreparer preparer) {
        this.playbackPreparer = preparer;
    }

    public void setQueueNavigator(@Nullable final QueueNavigator navigator) {
        this.queueNavigator = navigator;
    }

    public void setCustomActionProviders(
            @Nullable final CustomActionProvider... providers) {
        this.customActionProviders = providers;
        updatePlaybackState();
    }

    public void setMetadataDeduplicationEnabled(final boolean enabled) {
        this.metadataDeduplicationEnabled = enabled;
    }

    public void setMediaMetadataProvider(
            @Nullable final Function<Player, MediaMetadataCompat> provider) {
        this.mediaMetadataProvider = provider;
    }

    public void setMediaButtonEventHandler(
            @Nullable final BiFunction<Player, android.content.Intent, Boolean> eventHandler) {
        this.mediaButtonEventHandler = eventHandler;
    }

    public void setCustomErrorMessage(@Nullable final String message, final int code) {
        if (message != null) {
            final PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_ERROR, 0, 1.0f)
                    .setErrorMessage(code, message);
            mediaSession.setPlaybackState(builder.build());
        } else {
            updatePlaybackState();
        }
    }

    public void setCustomErrorMessage(@Nullable final String message) {
        if (message != null) {
            setCustomErrorMessage(message, PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR);
        } else {
            updatePlaybackState();
        }
    }

    public void invalidateMediaSessionMetadata() {
        if (mediaMetadataProvider != null && player != null) {
            final MediaMetadataCompat metadata = mediaMetadataProvider.apply(player);
            if (!metadataDeduplicationEnabled || !metadata.equals(lastMetadata)) {
                mediaSession.setMetadata(metadata);
                lastMetadata = metadata;
            }
        }
    }

    private void updatePlaybackState() {
        if (player == null) {
            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_NONE, 0, 0)
                    .build());
            return;
        }

        final PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder();

        final int sessionPlaybackState = mapPlaybackState(
                player.getPlaybackState(), player.getPlayWhenReady());
        builder.setState(sessionPlaybackState, player.getCurrentPosition(),
                player.getPlaybackParameters().speed,
                SystemClock.elapsedRealtime());
        builder.setBufferedPosition(player.getBufferedPosition());

        long actions = PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_STOP
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED
                | PlaybackStateCompat.ACTION_SET_REPEAT_MODE
                | PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE;

        if (queueNavigator != null) {
            actions |= queueNavigator.getSupportedQueueNavigatorActions(player);
        }
        if (playbackPreparer != null) {
            actions |= playbackPreparer.getSupportedPrepareActions();
        }
        builder.setActions(actions);

        // Add custom actions
        if (customActionProviders != null) {
            for (final CustomActionProvider provider : customActionProviders) {
                final PlaybackStateCompat.CustomAction customAction =
                        provider.getCustomAction(player);
                if (customAction != null) {
                    builder.addCustomAction(customAction);
                }
            }
        }

        if (queueNavigator != null) {
            builder.setActiveQueueItemId(queueNavigator.getActiveQueueItemId(player));
        }

        mediaSession.setPlaybackState(builder.build());
    }

    private static int mapPlaybackState(final int exoState, final boolean playWhenReady) {
        switch (exoState) {
            case Player.STATE_BUFFERING:
                return playWhenReady
                        ? PlaybackStateCompat.STATE_BUFFERING
                        : PlaybackStateCompat.STATE_PAUSED;
            case Player.STATE_READY:
                return playWhenReady
                        ? PlaybackStateCompat.STATE_PLAYING
                        : PlaybackStateCompat.STATE_PAUSED;
            case Player.STATE_ENDED:
                return PlaybackStateCompat.STATE_STOPPED;
            case Player.STATE_IDLE:
            default:
                return PlaybackStateCompat.STATE_NONE;
        }
    }

    private final class MediaSessionCallback extends MediaSessionCompat.Callback {
        @Override
        public boolean onMediaButtonEvent(@NonNull final android.content.Intent intent) {
            if (mediaButtonEventHandler != null && player != null) {
                if (mediaButtonEventHandler.apply(player, intent)) {
                    return true;
                }
            }
            return super.onMediaButtonEvent(intent);
        }

        @Override
        public void onPlay() {
            if (player != null) {
                player.play();
            }
        }

        @Override
        public void onPause() {
            if (player != null) {
                player.pause();
            }
        }

        @Override
        public void onStop() {
            if (player != null) {
                player.stop();
            }
        }

        @Override
        public void onSeekTo(final long pos) {
            if (player != null) {
                player.seekTo(pos);
            }
        }

        @Override
        public void onSkipToNext() {
            if (queueNavigator != null && player != null) {
                queueNavigator.onSkipToNext(player);
            }
        }

        @Override
        public void onSkipToPrevious() {
            if (queueNavigator != null && player != null) {
                queueNavigator.onSkipToPrevious(player);
            }
        }

        @Override
        public void onSkipToQueueItem(final long id) {
            if (queueNavigator != null && player != null) {
                queueNavigator.onSkipToQueueItem(player, id);
            }
        }

        @Override
        public void onPrepare() {
            if (playbackPreparer != null) {
                playbackPreparer.onPrepare(true);
            }
        }

        @Override
        public void onPrepareFromMediaId(final String mediaId, final Bundle extras) {
            if (playbackPreparer != null) {
                playbackPreparer.onPrepareFromMediaId(mediaId, true, extras);
            }
        }

        @Override
        public void onPrepareFromSearch(final String query, final Bundle extras) {
            if (playbackPreparer != null) {
                playbackPreparer.onPrepareFromSearch(query, true, extras);
            }
        }

        @Override
        public void onPrepareFromUri(final android.net.Uri uri, final Bundle extras) {
            if (playbackPreparer != null) {
                playbackPreparer.onPrepareFromUri(uri, true, extras);
            }
        }

        @Override
        public void onSetRepeatMode(final int repeatMode) {
            if (player != null) {
                player.setRepeatMode(repeatMode);
            }
        }

        @Override
        public void onSetShuffleMode(final int shuffleMode) {
            if (player != null) {
                player.setShuffleModeEnabled(
                        shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE);
            }
        }

        @Override
        public void onSetPlaybackSpeed(final float speed) {
            if (player != null) {
                player.setPlaybackParameters(
                        player.getPlaybackParameters().withSpeed(speed));
            }
        }

        @Override
        public void onCustomAction(final String action, final Bundle extras) {
            if (customActionProviders != null && player != null) {
                for (final CustomActionProvider provider : customActionProviders) {
                    provider.onCustomAction(player, action, extras);
                }
            }
        }
    }
}

package com.mastifdogo.devotion.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.mastifdogo.devotion.MainActivity
import java.io.File

/**
 * Owns the ExoPlayer and its media session, so audio keeps playing with the app in the background
 * or the screen off, with the standard media notification and lock-screen controls. The app's UI
 * talks to it through a MediaController (see MainViewModel).
 *
 * Media3 makes the service a foreground service (type mediaPlayback) while it plays and takes it
 * out of the foreground when playback pauses or ends.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        val player = ExoPlayer.Builder(this)
            // true: request audio focus, pause for calls, duck or pause for other apps.
            .setAudioAttributes(attributes, true)
            // Pause when headphones are unplugged.
            .setHandleAudioBecomingNoisy(true)
            // Audio is always a downloaded file: keep the CPU awake while playing, no Wi-Fi lock.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        // Tapping the notification opens the app on the entry that is playing.
        val open = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_PLAYING, true)
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(ResolveUris(filesDir))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** The app was swiped away: keep going only if something is actually playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        val playing = player != null &&
            player.playWhenReady &&
            player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_ENDED &&
            player.playbackState != Player.STATE_IDLE
        if (!playing) stopSelf()
    }

    override fun onDestroy() {
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * Media items lose their URI on the way from a controller to the session; the app therefore
     * puts the file URI into the request metadata and it is restored here.
     *
     * The service is open to other apps' media controllers (the lock screen, a watch, a car), so
     * what a controller sends is not trusted: only audio this app downloaded, a file under
     * [filesDir], is ever played. Anything else (a web address, another app's file) is dropped.
     */
    private class ResolveUris(private val filesDir: File) : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapNotNull { item ->
                val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
                if (uri == null || !isOwnFile(uri)) null else item.buildUpon().setUri(uri).build()
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        private fun isOwnFile(uri: Uri): Boolean {
            if (uri.scheme != "file") return false
            val path = uri.path ?: return false
            return try {
                File(path).canonicalFile.toPath().startsWith(filesDir.canonicalFile.toPath())
            } catch (e: Exception) {
                false
            }
        }
    }

    companion object {
        /** Extra on the launch intent from the media notification: open the playing entry. */
        const val EXTRA_OPEN_PLAYING = "com.mastifdogo.devotion.OPEN_PLAYING"
    }
}

package com.caydey.ffshare

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.caydey.ffshare.extensions.parcelable
import com.caydey.ffshare.extensions.parcelableArrayList
import com.caydey.ffshare.utils.MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE
import com.caydey.ffshare.utils.MediaCompressor
import com.caydey.ffshare.utils.Settings
import com.caydey.ffshare.utils.Utils
import timber.log.Timber


class HandleMediaActivity : AppCompatActivity() {
    // by lazy means load when variable is used, lazy-loading helps performance
    // also without it there is a null error for applicationContext
    private val mediaCompressor: MediaCompressor by lazy { MediaCompressor(applicationContext) }
    private val utils: Utils by lazy { Utils(applicationContext) }
    private val settings: Settings by lazy { Settings(applicationContext) }
    // read once: switching the setting during a running conversion must not cancel it at the next onStop
    private var keepAlive = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_handle_media)
        keepAlive = settings.keepAlive

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // With "keep converting with the screen off" a foreground service holds a wake lock. Short
        // conversions keep the screen on as before (the share dialog appears at the end); after ten
        // minutes the screen may time out (battery, no burned-in OLED after a run of several days) -
        // but only if the service really runs, otherwise the phone would fall asleep mid-conversion
        if (keepAlive) {
            window.decorView.postDelayed({
                if (mediaCompressor.keepAliveRunning) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }, SCREEN_ON_FOR_MS)
        }

        // back key: ask before throwing away a conversion that has been running for a while
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                mediaCompressor.confirmCancel(this@HandleMediaActivity) { finish() }
            }
        })

        if (utils.isReadPermissionGranted) {
            onMediaReceive()
        } else {
            Timber.d("Requesting read permissions")
            utils.requestReadPermissions(this)
        }
    }

    override fun finish() {
        mediaCompressor.cancelAllOperations()
        scheduleCacheCleanup()
        super.finish()
    }

    override fun onStop() {
        // without keep-alive the conversion ends when the screen is left (behaviour of the original app)
        if (!keepAlive) {
            mediaCompressor.cancelAllOperations()
        }
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // first time running app user is requested to allow app to read external storage,
        // after clicking "allow" the app will continue handling media it was shared
        if (requestCode == MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE) {
            Timber.d("Read permissions granted, continuing...")
            onMediaReceive()
        }
    }

    private fun onMediaReceive() {
        // "intent" variable is the shared item
        val receivedMedia =
            when (intent.action) {
                Intent.ACTION_SEND -> intent.parcelable<Uri>(Intent.EXTRA_STREAM)?.let { arrayListOf(it) } ?: ArrayList()
                Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayList<Uri>(Intent.EXTRA_STREAM) ?: ArrayList()
                else -> ArrayList<Uri>()
            }

        // unable to get file from intent
        if (receivedMedia.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_no_uri_intent), Toast.LENGTH_LONG).show()
            Timber.d("No files found in shared intent")
            finish()
        } else if (MediaCompressor.busy) {
            // a second conversion would halve the speed of the first one and share its wake lock
            Toast.makeText(this, getString(R.string.busy_toast), Toast.LENGTH_LONG).show()
            Timber.d("Another conversion is running")
            finish()
        } else {
            requestNotificationPermissionOnce()
            if (settings.audioTrack == Settings.AUDIO_TRACK_ASK || settings.subtitles == "ask") {
                askForTracks(receivedMedia)
            } else {
                startConversion(receivedMedia, null, null)
            }
        }
    }

    private fun startConversion(media: ArrayList<Uri>, audioTracks: List<Int>?, subtitleTracks: List<Int>?) {
        if (MediaCompressor.busy) { // another share started while the track dialog was open
            Toast.makeText(this, getString(R.string.busy_toast), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // callback
        mediaCompressor.compressFiles(this, media, audioTracks, subtitleTracks) { compressedMedia ->
            if (compressedMedia.isNotEmpty() && !isFinishing && !isDestroyed) {
                try {
                    shareMedia(compressedMedia)
                } catch (e: Exception) {
                    Timber.w(e, "Could not open the share dialog")
                }
            }
            finish()
        }
    }

    // Lists the audio and/or subtitle tracks of the (first) shared video and lets the user tick the ones
    // to keep, audio first. The choice applies to every file of this share: the episodes of one DVD have
    // the same track layout.
    private fun askForTracks(media: ArrayList<Uri>) {
        Thread({
            val tracks = try {
                mediaCompressor.describeTracks(media)
            } catch (e: Exception) {
                Timber.w(e, "Reading the tracks failed")
                MediaCompressor.TrackLabels(emptyList(), emptyList())
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val askAudio = settings.audioTrack == Settings.AUDIO_TRACK_ASK && tracks.audio.size >= 2
                // one subtitle track is worth asking about too: keep it or not
                val askSubtitles = settings.subtitles == "ask" && tracks.subtitles.isNotEmpty()
                // "ask" without subtitle tracks in the first file: none for the whole batch
                val noSubtitles = if (settings.subtitles == "ask") emptyList<Int>() else null
                val chooseSubtitles = { audioSelection: List<Int>? ->
                    if (askSubtitles) {
                        showTrackDialog(media, tracks.subtitles, R.string.subtitle_tracks_title, R.string.subtitle_tracks_title_batch,
                            settings.lastSubtitleTrackSelection, R.string.audio_tracks_convert) { selection ->
                            settings.lastSubtitleTrackSelection = selection
                            startConversion(media, audioSelection, selection)
                        }
                    } else {
                        startConversion(media, audioSelection, noSubtitles)
                    }
                }
                if (askAudio) {
                    showTrackDialog(media, tracks.audio, R.string.audio_tracks_title, R.string.audio_tracks_title_batch,
                        settings.lastAudioTrackSelection,
                        if (askSubtitles) R.string.tracks_next else R.string.audio_tracks_convert) { selection ->
                        settings.lastAudioTrackSelection = selection
                        chooseSubtitles(selection)
                    }
                } else {
                    chooseSubtitles(null) // nothing to choose from, or audio is not asked for
                }
            }
        }, "ffshare-tracks").start()
    }

    private fun showTrackDialog(media: ArrayList<Uri>, tracks: List<String>, titleId: Int, batchTitleId: Int,
                                lastSelection: List<Int>?, buttonId: Int, onSelected: (List<Int>) -> Unit) {
        // pre-tick last time's choice, otherwise all tracks
        val last = lastSelection?.filter { it in tracks.indices }
        val checked = BooleanArray(tracks.size) { index -> last.isNullOrEmpty() || index in last }
        val items = Array<CharSequence>(tracks.size) { tracks[it] }
        val title = if (media.size > 1) getString(batchTitleId, media.size) else getString(titleId)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(items, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(buttonId) { _, _ -> onSelected(tracks.indices.filter { checked[it] }) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    // Android 13+: the progress notification of long conversions needs the permission (asked once)
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33 || !keepAlive || settings.notificationPermissionAsked) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        settings.notificationPermissionAsked = true
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private fun shareMedia(mediaUris: ArrayList<Uri>) {
        val shareIntent = Intent()

        // temp permissions for other app to view file
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // add compressed media files
        if (mediaUris.size == 1) {
            Timber.d("Creating share intent for single item")
            shareIntent.action = Intent.ACTION_SEND
            shareIntent.putExtra(Intent.EXTRA_STREAM, mediaUris[0])
        } else {
            Timber.d("Creating share intent for multiple items")
            shareIntent.action = Intent.ACTION_SEND_MULTIPLE
            shareIntent.putExtra(Intent.EXTRA_STREAM, mediaUris)
        }

        // set mime for each file
        mediaUris.forEach { mediaUri ->
            shareIntent.setDataAndType(mediaUri, contentResolver.getType(mediaUri))
        }

        val chooser = Intent.createChooser(shareIntent, "media")
        startActivity(chooser)
    }

    private fun scheduleCacheCleanup() {
        Timber.d("Scheduling cleanup alarm")
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(applicationContext, CacheCleanUpReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)

        // every 12 hours clear cache
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime(),
            AlarmManager.INTERVAL_HALF_DAY,
            pendingIntent
        )
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1001
        private const val SCREEN_ON_FOR_MS = 10 * 60 * 1000L
    }
}

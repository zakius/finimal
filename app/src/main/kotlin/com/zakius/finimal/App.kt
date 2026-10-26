package com.zakius.finimal

import android.app.Application
import android.content.Context
import android.app.UiModeManager
import android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
import android.content.pm.PackageManager
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.serviceLoaderEnabled
import androidx.work.Configuration
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.io.File
import okhttp3.Dispatcher
import kotlin.concurrent.thread
import org.jellyfin.sdk.JellyfinOptions

/** The Android app as the shared code's [Platform]; the shared singletons are in [App]. */
class AndroidApp : Application(), Platform, SingletonImageLoader.Factory, Configuration.Provider {
    @android.annotation.SuppressLint("MissingSuperCall") // called on the fonts thread
    override fun onCreate() {
        // Android's own Application.onCreate is empty. ASUS's applies its FlipFont theme and rebuilds the system font
        // map, theme or not: 31 ms of main thread at each cold start on a Zenfone 10. Here it runs beside the rest of
        // start-up; Activity.onCreate makes the same call under the same lock, so the activity waits for it there.
        // With the ICU warm-up below: main-thread CPU to the first frame 93 → 62 ms, cold start 146 → 138 ms (2026-10-26).
        thread(name = "fonts") { superOnCreate() }
        instance = this
        appContext = this
        App.platform = this
        Media.onAppStart(this)
        thread(name = "warm-up") {
            // ICU's locale-key data, which the first line break would load in the first frame (10 ms). Not by laying
            // out text: that would race the font swap above.
            java.util.Locale.forLanguageTag(java.util.Locale.getDefault().toLanguageTag() + "-u-lb-normal").displayName
            if (App.prefs.loggedIn) ListSnapshots.prefetchLibraries()
            App.server // the first list load needs the API client right after the first frame
        }
    }

    private fun superOnCreate() = super.onCreate()

    /**
     * Start-up work the first frame doesn't need, run once per process after MainActivity's first frame. A process
     * started without the activity (a download or housekeeping job) skips it: those jobs need none of it.
     */
    fun afterFirstFrame() {
        if (startedUp) return
        startedUp = true
        thread(name = "after-first-frame") {
            Media.afterFirstFrame(this)
            warmDecoders()
        }
    }
    private var startedUp = false

    // WorkManager starts when first used, not from its start-up provider before onCreate; see AndroidManifest.xml.
    override val workManagerConfiguration get() = Configuration.Builder().build()

    // Shared connections, own dispatcher: a screen of thumbnails must not queue behind or ahead of API calls (5 per host each).
    // No ServiceLoader scan of the APK on the first image: the one fetcher used is added here.
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .serviceLoaderEnabled(false)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { App.http.newBuilder().dispatcher(Dispatcher()).build() })) }
        .build()

    /** The first codec-list query in a process takes up to a few hundred ms; Media3 caches it, so the first play skips that. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun warmDecoders() = listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1, MimeTypes.AUDIO_AAC)
        .forEach { runCatching { MediaCodecUtil.getDecoderInfos(it, false, false) } }

    override val versionName: String get() = BuildConfig.VERSION_NAME
    override val appFiles: File get() = filesDir
    override val appCache: File get() = cacheDir
    override val android get() = true
    override val store by lazy { PrefsStore(getSharedPreferences("finimal", Context.MODE_PRIVATE)) }
    /** Android TV, or a box with no touchscreen (an STB on a plain Android launcher). Some boxes claim one; the setting overrides. */
    override val autoTv by lazy {
        getSystemService(UiModeManager::class.java).currentModeType == UI_MODE_TYPE_TELEVISION ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
    override val scope get() = ProcessLifecycleOwner.get().lifecycleScope
    override val transcodeHeight get() = HardwareDecoders.h264Height
    override val downloads: Downloads get() = Media
    override fun configure(options: JellyfinOptions.Builder) { options.context = this }
    override fun scheduleHousekeeping() { Housekeeping.enqueue(this) }
    override fun askNotifications() { activity?.askNotifications() }
    override fun decoderSummary() = HardwareDecoders.summary()
    override fun setAudioDelay(ms: Int) = AudioDelay.set(ms)

    /** The activity on screen, for the notification permission prompt. */
    var activity: MainActivity? = null

    companion object {
        lateinit var instance: AndroidApp
            private set
    }
}

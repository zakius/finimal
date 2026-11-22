package com.zakius.finimal

import android.app.Application
import android.content.Context
import android.app.UiModeManager
import android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.serviceLoaderEnabled
import coil3.size.Size
import androidx.profileinstaller.ProfileInstaller
import androidx.work.Configuration
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Dispatcher
import okhttp3.OkHttp
import okio.Path.Companion.toOkioPath
import kotlin.concurrent.thread
import org.jellyfin.sdk.JellyfinOptions
import org.jellyfin.sdk.model.api.BaseItemDto

class AndroidApp : Application(), Platform, SingletonImageLoader.Factory, Configuration.Provider {
    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onCreate() {
        // Stock Android's Application.onCreate is empty, but ASUS's applies its FlipFont theme and rebuilds the system
        // font map, which costs 31 ms of main thread on every cold start on a Zenfone 10. Overlap it on ASUS only.
        if (Build.MANUFACTURER.equals("asus", ignoreCase = true)) {
            thread(name = "fonts") { superOnCreate() }
        } else {
            superOnCreate()
        }
        instance = this
        appContext = this
        App.platform = this
        Media.attach(this)
        thread(name = "warm-up") {
            // Load ICU's locale-key data now; otherwise the first line break loads it during the first frame (10 ms).
            // This avoids laying out text, which would race the font swap above.
            val lineBreakTag = java.util.Locale.getDefault().toLanguageTag() + "-u-lb-normal"
            java.util.Locale.forLanguageTag(lineBreakTag).displayName
            iconTypeface
            if (App.prefs.loggedIn) ListSnapshots.prefetchFastLibraries()
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
            App.server
            SingletonImageLoader.get(this)
            Media.afterFirstFrame(this)
            warmDecoders()
            HardwareDecoders.summary()
            HardwareDecoders.h264Height
            // Android 15+ compiles assets/art-profile/baseline.prof at install time; older versions use ProfileInstaller.
            if (Build.VERSION.SDK_INT < 35) runCatching { ProfileInstaller.writeProfile(this) }
        }
    }
    private var startedUp = false

    // WorkManager initializes on first use instead of from its start-up provider before onCreate; see AndroidManifest.xml.
    override val workManagerConfiguration get() = Configuration.Builder().build()

    // Images share the API client's connections but get their own higher-concurrency dispatcher so a grid of
    // posters loads in parallel without queuing behind API calls. RGB_565 halves poster RAM & GPU upload cost.
    // The only fetcher is added by hand, which skips a ServiceLoader scan of the APK on the first image.
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .serviceLoaderEnabled(false)
        .allowRgb565(true)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(cacheDir, "images").toOkioPath())
                .maxSizeBytes(200L * 1024 * 1024)
                .build()
        }
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = {
                App.http.newBuilder().dispatcher(Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 16
                }).build()
            }))
        }
        .build()

    /** The first codec-list query in a process takes up to a few hundred ms; Media3 caches it, so the first play skips that. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun warmDecoders() {
        val mimeTypes = listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1, MimeTypes.AUDIO_AAC)
        for (mimeType in mimeTypes) {
            runCatching { MediaCodecUtil.getDecoderInfos(mimeType, false, false) }
        }
    }

    override val versionName: String get() = BuildConfig.VERSION_NAME
    override val appFiles: File get() = filesDir
    override val appCache: File get() = cacheDir
    override val android get() = true
    override val store by lazy { PrefsStore(getSharedPreferences("finimal", Context.MODE_PRIVATE)) }
    /**
     * Android TV, or a box without a touchscreen (a set-top box on a plain Android launcher). Some boxes claim a
     * touchscreen they lack, which is why the user can override this in Settings.
     */
    override val autoTv by lazy {
        getSystemService(UiModeManager::class.java).currentModeType == UI_MODE_TYPE_TELEVISION ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
    override val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    override val transcodeHeight get() = HardwareDecoders.h264Height
    override val downloads: Downloads get() = Media
    override fun configure(options: JellyfinOptions.Builder) { options.context = this }
    override fun beforeHttp() { OkHttp.initialize(this) }
    override fun scheduleHousekeeping() { Housekeeping.enqueue(this) }
    override fun askNotifications() { activity?.askNotifications() }
    override fun decoderSummary() = HardwareDecoders.summary()
    override fun setAudioDelay(ms: Int) = AudioDelay.set(ms)
    override fun bufferBytes() = Media.bufferedBytes()
    override fun evictBuffer() = Media.evict()
    override fun clearBuffer() = Media.clearBuffer()
    override val isMetered: Boolean
        get() = runCatching { getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true }.getOrDefault(false)
    override fun prefetchImages(urls: List<String>, diskOnly: Boolean) {
        val loader = SingletonImageLoader.get(this)
        val networkPolicy = if (diskOnly) CachePolicy.DISABLED else CachePolicy.ENABLED
        for (url in urls) {
            loader.enqueue(
                ImageRequest.Builder(this)
                    .data(url)
                    .size(Size.ORIGINAL)
                    .networkCachePolicy(networkPolicy)
                    .build()
            )
        }
    }
    override suspend fun prefetchSubtitles(item: BaseItemDto) {
        Media.fetchSubtitles(item, transcode = !playsDirectly(item))
    }

    var activity: MainActivity? = null

    companion object {
        lateinit var instance: AndroidApp
            private set
    }
}

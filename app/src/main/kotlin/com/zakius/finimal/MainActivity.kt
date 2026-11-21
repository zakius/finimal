package com.zakius.finimal

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    // A denial is fine, since downloads run without the notification. Android stops asking after two denials.
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var openDownloadsFlag = false
    private var openDownloadsState: MutableState<Boolean>? = null
    private var fastLibrariesView: LibrariesFastView? = null
    private var fastLoginView: LoginFastView? = null
    private var composeMounted = false

    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun mountCompose(initialStack: List<Screen>? = null) {
        if (composeMounted) return
        composeMounted = true
        fastLibrariesView?.dispose()
        fastLibrariesView = null
        fastLoginView?.dispose()
        fastLoginView = null
        val state = mutableStateOf(openDownloadsFlag).also { openDownloadsState = it }
        setContent {
            Theme {
                Root(state.value, {
                    openDownloadsFlag = false
                    state.value = false
                }, initialStack = initialStack) { id, onBack, onPlay ->
                    PlayerScreen(id, onBack, onPlay)
                }
            }
        }
    }

    private fun showFastLibraries() {
        fastLoginView?.dispose()
        fastLoginView = null
        val view = LibrariesFastView(
            this,
            onNavigate = { target -> mountCompose(listOf(Screen.Libraries, target)) },
            onSessionExpired = { mountCompose(listOf(Screen.Login)) },
        )
        fastLibrariesView = view
        setContentView(view)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidApp.instance.activity = this
        // Remove the splash as soon as the first frame is ready, skipping Android's fade-out (about 130 ms).
        if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { it.remove() }
        enableEdgeToEdge()
        // A recreated activity restores its back stack, so don't push Downloads again.
        if (savedInstanceState == null) openDownloadsFlag = intent.getBooleanExtra(EXTRA_DOWNLOADS, false)

        val count = if (savedInstanceState == null) App.prefs.accountCount else -1
        when {
            savedInstanceState == null && !openDownloadsFlag && App.prefs.loggedIn && count <= 1 -> showFastLibraries()
            savedInstanceState == null && !App.prefs.loggedIn && count == 0 -> {
                val login = LoginFastView(this) {
                    showFastLibraries()
                    fastLibrariesView?.startRefresh()
                }
                fastLoginView = login
                setContentView(login)
            }
            else -> mountCompose()
        }

        val decor = window.decorView
        decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                decor.post { // A listener can't be removed from inside onDraw.
                    decor.viewTreeObserver.removeOnDrawListener(this)
                    AndroidApp.instance.afterFirstFrame()
                    fastLibrariesView?.startRefresh()
                }
            }
        })
    }

    override fun onDestroy() {
        if (AndroidApp.instance.activity === this) AndroidApp.instance.activity = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_DOWNLOADS, false)) {
            if (!composeMounted && fastLibrariesView != null) {
                mountCompose(listOf(Screen.Libraries, Screen.Downloads))
            } else {
                openDownloadsFlag = true
                openDownloadsState?.value = true
            }
        }
    }

    companion object { const val EXTRA_DOWNLOADS = "downloads" }
}


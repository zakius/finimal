package com.zakius.finimal

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import finimal.core.defaultPortFor
import finimal.core.isValidPort
import finimal.core.serverCandidates
import finimal.core.splitServerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import kotlin.math.ceil
import kotlin.math.roundToInt

private fun View.applySafeInsets(overscanH: Int, overscanV: Int) {
    setPadding(overscanH, overscanV, overscanH, overscanV)
    if (Build.VERSION.SDK_INT >= 30) {
        setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or
                    WindowInsets.Type.ime()
            )
            v.setPadding(
                bars.left + overscanH,
                bars.top + overscanV,
                bars.right + overscanH,
                bars.bottom + overscanV,
            )
            insets
        }
    } else {
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(
                bars.left + overscanH,
                bars.top + overscanV,
                bars.right + overscanH,
                bars.bottom + overscanV,
            )
            insets
        }
    }
}

class LibrariesFastView(
    context: Context,
    private val onNavigate: (Screen) -> Unit,
    private val onSessionExpired: () -> Unit,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).roundToInt()

    private val colors = FastColors.resolve(context)
    private val mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val contentHost: FrameLayout
    private val listContainer: LinearLayout
    private val scrollView: ScrollView
    private val avatarLetter: TextView
    private var errorBar: LinearLayout? = null
    private var emptyView: TextView? = null
    private var spinner: ProgressBar? = null
    private var fullErrorView: LinearLayout? = null

    private var entries: List<LibraryEntry>? = null
    private var refreshJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var disposed = false

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.surface)

        val tv = App.tv
        applySafeInsets(if (tv) dp(48) else 0, if (tv) dp(27) else 0)

        val topBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(4), 0)
        }
        val titleView = TextView(context).apply {
            text = "finimal"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(colors.onSurface)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        topBar.addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        topBar.addView(iconActionButton(AppIcons.SEARCH, "Search") { onNavigate(Screen.Library(null, "Search")) })
        topBar.addView(iconActionButton(AppIcons.DOWNLOAD, "Downloads") { onNavigate(Screen.Downloads) })
        topBar.addView(iconActionButton(AppIcons.SETTINGS, "Settings") { onNavigate(Screen.Settings) })

        val avatarBtn = FrameLayout(context).apply {
            contentDescription = "Accounts"
            isClickable = true
            isFocusable = true
            applyFocusHighlight(colors.focusOverlay)
            setOnClickListener { onNavigate(Screen.Accounts) }
        }
        avatarBtn.addView(
            AvatarCircleDrawView(context, dp(16).toFloat(), colors.primaryContainer),
            FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER),
        )
        val initial = App.prefs.currentUserName().orEmpty().firstOrNull()?.uppercase() ?: "?"
        avatarLetter = TextView(context).apply {
            text = initial
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = mediumTypeface
            setTextColor(colors.onPrimaryContainer)
            gravity = Gravity.CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        avatarBtn.addView(avatarLetter, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        topBar.addView(avatarBtn, LayoutParams(dp(48), dp(48)))

        val moreBtn = iconActionButton(AppIcons.MORE_VERT, "More options") {}
        moreBtn.setOnClickListener { anchor ->
            PopupMenu(context, anchor).apply {
                menu.add("Refresh").setOnMenuItemClickListener {
                    startRefresh()
                    true
                }
                show()
            }
        }
        topBar.addView(moreBtn)

        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(64)))

        contentHost = FrameLayout(context)
        listContainer = LinearLayout(context).apply { orientation = VERTICAL }
        scrollView = ScrollView(context).apply {
            isFillViewport = false
            addView(listContainer, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        contentHost.addView(scrollView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(contentHost, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val key = listOf(App.prefs.baseUrl, App.prefs.userId)
        val cached = ListSnapshots.takePrefetchedFastLibraries(key) ?: ListSnapshots.readFastLibraryEntries(key)
        if (cached != null) {
            renderEntries(cached)
        } else {
            showSpinner()
        }
    }

    private fun iconActionButton(glyph: String, label: String, onClick: () -> Unit): FrameLayout = FrameLayout(context).apply {
        layoutParams = LayoutParams(dp(48), dp(48))
        contentDescription = label
        isClickable = true
        isFocusable = true
        applyFocusHighlight(colors.focusOverlay)
        setOnClickListener { onClick() }
        addView(
            GlyphDrawView(context, glyph, dp(24).toFloat(), colors.onSurfaceVariant),
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER),
        )
    }

    private fun actionTextButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = mediumTypeface
        setTextColor(colors.primary)
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
        isClickable = true
        isFocusable = true
        applyFocusHighlight(colors.focusOverlay)
        setOnClickListener { onClick() }
    }

    private fun View.applyFocusHighlight(highlightColor: Int) {
        setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundColor(if (hasFocus) highlightColor else Color.TRANSPARENT)
        }
    }

    private fun renderEntries(list: List<LibraryEntry>) {
        entries = list
        spinner?.visibility = GONE
        fullErrorView?.visibility = GONE
        if (list.isEmpty()) {
            scrollView.visibility = GONE
            val tv = emptyView ?: TextView(context).apply {
                text = "No movie or TV libraries on this server"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(colors.onSurface)
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
                contentHost.addView(this, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                emptyView = this
            }
            tv.visibility = VISIBLE
            return
        }
        emptyView?.visibility = GONE
        scrollView.visibility = VISIBLE
        listContainer.removeAllViews()
        val pad = dp(20)
        val dividerH = maxOf(1, dp(1))
        val tilePx = dp(16).toFloat()
        for (entry in list) {
            val row = TextView(context).apply {
                text = entry.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(colors.onSurface)
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
                applyFocusHighlight(colors.focusOverlay)
                setOnClickListener { onNavigate(Screen.Library(entry.id, entry.name)) }
            }
            listContainer.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            listContainer.addView(
                DividerDrawView(context, tilePx, colors.outlineVariant),
                LayoutParams(LayoutParams.MATCH_PARENT, dividerH),
            )
        }
    }

    private fun showSpinner() {
        scrollView.visibility = GONE
        emptyView?.visibility = GONE
        fullErrorView?.visibility = GONE
        val bar = spinner ?: ProgressBar(context).apply {
            contentHost.addView(this, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            spinner = this
        }
        bar.visibility = VISIBLE
    }

    private fun showRefreshError(e: Throwable) {
        if (unreachable(e)) registerRetryOnNewNetwork()
        val bar = errorBar ?: LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colors.surfaceVariant)
            setPadding(dp(16), 0, 0, 0)
            val msg = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(colors.onSurface)
            }
            addView(msg, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(actionTextButton("Retry") { startRefresh() })
            this@LibrariesFastView.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            errorBar = this
        }
        (bar.getChildAt(0) as TextView).text = "${loadError(e)}. Showing the last list."
        bar.visibility = VISIBLE
    }

    private fun showFullError(e: Throwable) {
        spinner?.visibility = GONE
        scrollView.visibility = GONE
        emptyView?.visibility = GONE
        if (unreachable(e)) registerRetryOnNewNetwork()
        val panel = fullErrorView ?: LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            contentHost.addView(this, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            fullErrorView = this
        }
        panel.removeAllViews()
        panel.addView(TextView(context).apply {
            text = loadError(e)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(colors.onSurface)
            gravity = Gravity.CENTER
        })
        panel.addView(TextView(context).apply {
            text = e.message ?: e.javaClass.simpleName
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colors.onSurface)
            gravity = Gravity.CENTER
        })
        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            addView(actionTextButton("Retry") {
                showSpinner()
                startRefresh()
            })
            addView(actionTextButton("Downloads") { onNavigate(Screen.Downloads) })
        }
        panel.addView(buttons)
        panel.visibility = VISIBLE
    }

    private fun registerRetryOnNewNetwork() {
        if (networkCallback != null || disposed) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val initial = cm.activeNetwork
        val main = Handler(Looper.getMainLooper())
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network != initial) main.post { if (!disposed) startRefresh() }
            }
        }
        networkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    fun startRefresh() {
        if (disposed) return
        refreshJob?.cancel()
        errorBar?.visibility = GONE
        unregisterNetworkCallback()
        refreshJob = App.platform.scope.launch {
            val key = listOf(App.prefs.baseUrl, App.prefs.userId)
            val currentEntries = entries
            if (currentEntries != null) {
                val placeholders = currentEntries.map { e ->
                    org.jellyfin.sdk.model.api.BaseItemDto(
                        id = java.util.UUID.fromString(e.id),
                        type = org.jellyfin.sdk.model.api.BaseItemKind.COLLECTION_FOLDER,
                        name = e.name,
                    )
                }
                seedLibrariesLastGood(placeholders, fromNetwork = false)
                AppCache.prefetchLibrariesFromDisk(currentEntries.map { it.id })
            } else {
                val disk = withContext(Dispatchers.IO) { ListSnapshots.read(key) }
                if (disk != null && !disposed) {
                    seedLibrariesLastGood(disk, fromNetwork = false)
                    renderEntries(disk.map { LibraryEntry(it.id.toString(), it.name.orEmpty()) })
                    AppCache.prefetchLibrariesFromDisk(disk.map { it.id.toString() })
                }
            }
            val uid = App.prefs.userId
            if (uid != null && App.prefs.currentUserName().isNullOrEmpty()) {
                launch {
                    val name = runCatching { App.server.userName() }.getOrNull()
                    if (!name.isNullOrEmpty()) {
                        App.prefs.setName(uid, name)
                        if (!disposed) avatarLetter.text = name.first().uppercase()
                    }
                }
            }
            val pushed = App.server.flush()
            val result = if (pushed != null && unreachable(pushed)) {
                Result.failure(pushed)
            } else {
                runCatching { App.server.libraries() }.onFailure { e ->
                    if (e is InvalidStatusException && e.status == 401) App.server.expire()
                }
            }
            if (disposed) return@launch
            if (App.sessionExpired) {
                onSessionExpired()
                return@launch
            }
            result.onSuccess { list ->
                val prev = AppCache.getList(key)?.items
                seedLibrariesLastGood(list, fromNetwork = true)
                launch(Dispatchers.IO) { ListSnapshots.writeIfChanged(key, list, prev) }
                val mapped = list.map { LibraryEntry(it.id.toString(), it.name.orEmpty()) }
                if (mapped != entries) renderEntries(mapped)
                val ids = mapped.map { it.id }
                AppCache.prefetchLibrariesFromDisk(ids)
                AppCache.prefetchLibrariesNetwork(ids)
            }.onFailure { e ->
                if (entries != null) showRefreshError(e) else showFullError(e)
            }
        }
    }

    fun dispose() {
        disposed = true
        refreshJob?.cancel()
        unregisterNetworkCallback()
    }

    override fun onDetachedFromWindow() {
        dispose()
        super.onDetachedFromWindow()
    }
}

class LoginFastView(
    context: Context,
    private val onLoggedIn: () -> Unit,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).roundToInt()

    private val colors = FastColors.resolve(context)
    private val mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val urlField: EditText
    private val portField: EditText
    private val portBox: OutlinedGlyphBox
    private val portErrorText: TextView
    private val userField: EditText
    private val passField: EditText
    private val showPassBtn: TextView
    private val errorTitle: TextView
    private val errorRaw: TextView
    private val submitBtn: GlyphButtonView

    private var portEdited = false
    private var showPass = false
    private var busy = false
    private var loginJob: Job? = null
    private var disposed = false

    init {
        setBackgroundColor(colors.surface)
        val tv = App.tv
        applySafeInsets(if (tv) dp(48) else 0, if (tv) dp(27) else 0)

        val saved = splitServerAddress(App.prefs.baseUrl)
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val outer = FrameLayout(context)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(24)
            setPadding(pad, pad, pad, pad)
        }

        col.addView(TextView(context).apply {
            text = "finimal"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(colors.onSurface)
            setPadding(0, 0, 0, dp(12))
        })

        if (App.sessionExpired) {
            col.addView(TextView(context).apply {
                text = "The server ended this session. Log in again; unsynced progress is kept."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(colors.onSurface)
                setPadding(0, 0, 0, dp(12))
            })
        }

        val serverRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        urlField = makeEditText(saved.first, "jellyfin.example.com", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_NEXT)
        val urlBox = OutlinedGlyphBox(context, dp(16).toFloat(), colors.outline, colors.primary, colors.error).apply {
            addView(labeledField("Server", urlField))
        }
        serverRow.addView(urlBox, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })

        portField = makeEditText(saved.second, "", InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_NEXT).apply {
            filters = arrayOf(InputFilter.LengthFilter(5))
        }
        portBox = OutlinedGlyphBox(context, dp(16).toFloat(), colors.outline, colors.primary, colors.error).apply {
            addView(labeledField("Port", portField))
        }
        serverRow.addView(portBox, LinearLayout.LayoutParams(dp(112), LayoutParams.WRAP_CONTENT))
        col.addView(serverRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        portErrorText = TextView(context).apply {
            text = "The port is a number from 1 to 65535, or empty for the default of https or http."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colors.error)
            visibility = GONE
        }
        col.addView(portErrorText, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        userField = makeEditText("", "", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT).apply {
            if (Build.VERSION.SDK_INT >= 26) setAutofillHints(View.AUTOFILL_HINT_USERNAME)
        }
        val userBox = OutlinedGlyphBox(context, dp(16).toFloat(), colors.outline, colors.primary, colors.error).apply {
            addView(labeledField("Username", userField))
        }
        col.addView(userBox, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        passField = makeEditText("", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE).apply {
            transformationMethod = PasswordTransformationMethod.getInstance()
            if (Build.VERSION.SDK_INT >= 26) setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submit()
                    true
                } else false
            }
        }
        showPassBtn = TextView(context).apply {
            text = "Show"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = mediumTypeface
            setTextColor(colors.primary)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            setOnFocusChangeListener { v, focused ->
                v.setBackgroundColor(if (focused) colors.focusOverlay else Color.TRANSPARENT)
            }
            setOnClickListener {
                showPass = !showPass
                val sel = passField.selectionEnd
                passField.transformationMethod = if (showPass) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
                if (sel >= 0) passField.setSelection(sel)
                text = if (showPass) "Hide" else "Show"
            }
        }
        val passRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(labeledField("Password", passField), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(showPassBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        val passBox = OutlinedGlyphBox(context, dp(16).toFloat(), colors.outline, colors.primary, colors.error).apply {
            addView(passRow)
        }
        col.addView(passBox, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        errorTitle = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(colors.error)
            visibility = GONE
        }
        col.addView(errorTitle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        errorRaw = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colors.error)
            visibility = GONE
        }
        col.addView(errorRaw, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        submitBtn = GlyphButtonView(context, "Log in", mediumTypeface, colors.primary, colors.onPrimary, colors.disabledBg, colors.disabledFg, colors.focusOverlay) {
            submit()
        }
        col.addView(submitBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)))

        fun bindBox(edit: EditText, box: OutlinedGlyphBox) {
            edit.setOnFocusChangeListener { _, focused -> box.setFieldFocused(focused) }
        }
        bindBox(urlField, urlBox)
        bindBox(portField, portBox)
        bindBox(userField, userBox)
        bindBox(passField, passBox)

        var settingPort = false
        urlField.addTextChangedListener(SimpleWatcher { text ->
            clearError()
            if (!portEdited) {
                val guess = defaultPortFor(text)
                if (guess != null) {
                    settingPort = true
                    portField.setText(guess)
                    settingPort = false
                }
            }
            updateState()
        })
        portField.addTextChangedListener(SimpleWatcher {
            if (!settingPort) portEdited = true
            clearError()
            updateState()
        })
        userField.addTextChangedListener(SimpleWatcher { clearError(); updateState() })
        passField.addTextChangedListener(SimpleWatcher { clearError(); updateState() })

        val maxW = minOf(resources.displayMetrics.widthPixels, dp(560))
        outer.addView(col, LayoutParams(maxW, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        scroll.addView(outer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        updateState()
    }

    private fun makeEditText(initial: String, hintText: String, inputTypeFlags: Int, imeAction: Int): EditText = EditText(context).apply {
        setText(initial)
        if (hintText.isNotEmpty()) hint = hintText
        setHintTextColor((0x99 shl 24) or (colors.onSurfaceVariant and 0x00FFFFFF))
        setTextColor(colors.onSurface)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = null
        isSingleLine = true
        inputType = inputTypeFlags
        imeOptions = imeAction
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun labeledField(label: String, edit: EditText): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(6), dp(14), dp(4))
        addView(TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colors.onSurfaceVariant)
        })
        addView(edit, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun clearError() {
        errorTitle.visibility = GONE
        errorRaw.visibility = GONE
    }

    private fun updateState() {
        val portOk = isValidPort(portField.text.toString().trim())
        portBox.setFieldError(!portOk)
        portErrorText.visibility = if (portOk) GONE else VISIBLE
        val canSubmit = !busy && portOk && urlField.text.isNotBlank() && userField.text.isNotBlank()
        submitBtn.setButtonState(enabled = canSubmit, label = if (busy) "Logging in…" else "Log in")
        urlField.isEnabled = !busy
        portField.isEnabled = !busy
        userField.isEnabled = !busy
        passField.isEnabled = !busy
    }

    private fun submit() {
        val url = urlField.text.toString()
        val port = portField.text.toString()
        val user = userField.text.toString()
        val pass = passField.text.toString()
        if (busy || !isValidPort(port.trim()) || url.isBlank() || user.isBlank()) return
        busy = true
        clearError()
        updateState()
        loginJob = App.platform.scope.launch {
            runCatching { App.server.login(url, port, user, pass) }
                .onSuccess {
                    if (!disposed) onLoggedIn()
                }
                .onFailure { e ->
                    if (!disposed) {
                        busy = false
                        updateState()
                        if (e is InvalidStatusException && e.status == 401) {
                            errorTitle.text = "Wrong username or password"
                            errorTitle.visibility = VISIBLE
                            errorRaw.visibility = GONE
                        } else {
                            val where = if (unreachable(e)) " at ${serverCandidates(url, port).joinToString(" or ")}" else ""
                            errorTitle.text = loadError(e) + where
                            errorTitle.visibility = VISIBLE
                            errorRaw.text = e.message ?: e.javaClass.simpleName
                            errorRaw.visibility = VISIBLE
                        }
                    }
                }
        }
    }

    fun dispose() {
        disposed = true
        loginJob?.cancel()
    }

    override fun onDetachedFromWindow() {
        dispose()
        super.onDetachedFromWindow()
    }
}

private class SimpleWatcher(private val onChanged: (String) -> Unit) : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: Editable?) = onChanged(s?.toString().orEmpty())
}

private class GlyphDrawView(context: Context, private val glyph: String, private val sizePx: Float, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = iconTypeface
        textSize = sizePx
        this.color = color
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        canvas.drawText(glyph, (width - sizePx) / 2f, (height + sizePx) / 2f, paint)
    }
}

private class AvatarCircleDrawView(context: Context, private val halfPx: Float, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = iconTypeface
        textSize = halfPx
        this.color = color
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val x0 = (width - halfPx * 2f) / 2f
        val yTop = (height - halfPx * 2f) / 2f
        canvas.drawText("\uE008\uE009", x0, yTop + halfPx, paint)
        canvas.drawText("\uE00A\uE00B", x0, yTop + halfPx * 2f, paint)
    }
}

private class DividerDrawView(context: Context, private val tilePx: Float, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = iconTypeface
        textSize = tilePx
        this.color = color
    }
    private var cachedWidth = -1
    private var lineText = ""
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        if (width <= 0) return
        if (width != cachedWidth) {
            cachedWidth = width
            val count = ceil(width / tilePx).toInt() + 1
            lineText = AppIcons.HLINE.repeat(count)
        }
        canvas.drawText(lineText, 0f, tilePx, paint)
    }
}

private class OutlinedGlyphBox(
    context: Context,
    private val tilePx: Float,
    private val normalColor: Int,
    private val focusedColor: Int,
    private val errorColor: Int,
) : FrameLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = iconTypeface
        textSize = tilePx
        color = normalColor
    }
    private var isFieldFocused = false
    private var isFieldError = false
    private var hCount = 0
    private var hText = ""

    init { setWillNotDraw(false) }

    fun setFieldFocused(focused: Boolean) {
        if (isFieldFocused != focused) {
            isFieldFocused = focused
            updateColor()
        }
    }

    fun setFieldError(error: Boolean) {
        if (isFieldError != error) {
            isFieldError = error
            updateColor()
        }
    }

    private fun updateColor() {
        paint.color = when {
            isFieldError -> errorColor
            isFieldFocused -> focusedColor
            else -> normalColor
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val neededH = ceil(w / tilePx).toInt() + 1
        if (neededH != hCount) {
            hCount = neededH
            hText = AppIcons.HLINE.repeat(neededH)
        }
        val stroke = (tilePx / 16f).coerceAtLeast(1f)
        canvas.drawText(hText, 0f, tilePx, paint)
        canvas.drawText(hText, 0f, h - stroke + tilePx, paint)
        var y = tilePx
        val rightX = w - stroke
        while (y < h + tilePx) {
            canvas.drawText(AppIcons.VLINE, 0f, y, paint)
            canvas.drawText(AppIcons.VLINE, rightX, y, paint)
            y += tilePx
        }
    }
}

private class GlyphButtonView(
    context: Context,
    initialLabel: String,
    mediumTypeface: Typeface,
    private val enabledBg: Int,
    private val enabledFg: Int,
    private val disabledBg: Int,
    private val disabledFg: Int,
    private val focusOverlay: Int,
    onClick: () -> Unit,
) : FrameLayout(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = iconTypeface
        color = disabledBg
    }
    private val labelView: TextView

    init {
        setWillNotDraw(false)
        val d = resources.displayMetrics.density
        val padH = (24 * d).roundToInt()
        isClickable = true
        isFocusable = true
        setOnFocusChangeListener { v, focused ->
            v.setBackgroundColor(if (focused) focusOverlay else Color.TRANSPARENT)
        }
        setOnClickListener { if (isEnabled) onClick() }
        labelView = TextView(context).apply {
            text = initialLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = mediumTypeface
            setTextColor(disabledFg)
            gravity = Gravity.CENTER
            setPadding(padH, 0, padH, 0)
        }
        addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }

    fun setButtonState(enabled: Boolean, label: String) {
        isEnabled = enabled
        bgPaint.color = if (enabled) enabledBg else disabledBg
        labelView.setTextColor(if (enabled) enabledFg else disabledFg)
        labelView.text = label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        bgPaint.textSize = r
        canvas.drawText("\uE008", 0f, r, bgPaint)
        canvas.drawText("\uE00A", 0f, h, bgPaint)
        val rightCapX = (w - r).coerceAtLeast(r)
        canvas.drawText("\uE009", rightCapX, r, bgPaint)
        canvas.drawText("\uE00B", rightCapX, h, bgPaint)
        val fillStart = (r - 1f).coerceAtLeast(0f)
        val fillEnd = (rightCapX + 1f).coerceAtMost(w)
        if (fillEnd > fillStart) {
            val step = (r - 2f).coerceAtLeast(1f)
            var x = fillStart
            while (x + r < fillEnd) {
                canvas.drawText(AppIcons.BLOCK, x, r, bgPaint)
                canvas.drawText(AppIcons.BLOCK, x, h, bgPaint)
                x += step
            }
            val lastX = (fillEnd - r).coerceAtLeast(fillStart)
            canvas.drawText(AppIcons.BLOCK, lastX, r, bgPaint)
            canvas.drawText(AppIcons.BLOCK, lastX, h, bgPaint)
        }
    }
}

private class FastColors(
    val surface: Int,
    val onSurface: Int,
    val primary: Int,
    val onPrimary: Int,
    val onSurfaceVariant: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val outline: Int,
    val outlineVariant: Int,
    val surfaceVariant: Int,
    val error: Int,
    val disabledBg: Int,
    val disabledFg: Int,
    val focusOverlay: Int,
) {
    companion object {
        fun resolve(context: Context): FastColors {
            val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val focus = if (dark) 0x59FFFFFF else 0x59000000
            val err = if (dark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()
            fun c(id: Int) = context.resources.getColor(id, context.theme)
            fun withAlpha(color: Int, alpha: Int) = (alpha shl 24) or (color and 0x00FFFFFF)
            fun blendOver(fg: Int, alpha: Int, bg: Int): Int {
                val inv = 255 - alpha
                val r = (((fg shr 16) and 0xFF) * alpha + ((bg shr 16) and 0xFF) * inv + 127) / 255
                val g = (((fg shr 8) and 0xFF) * alpha + ((bg shr 8) and 0xFF) * inv + 127) / 255
                val b = ((fg and 0xFF) * alpha + (bg and 0xFF) * inv + 127) / 255
                return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return when {
                Build.VERSION.SDK_INT >= 34 -> {
                    val surf = c(if (dark) android.R.color.system_surface_dark else android.R.color.system_surface_light)
                    val onSurf = c(if (dark) android.R.color.system_on_surface_dark else android.R.color.system_on_surface_light)
                    val onPrim = if (dark) c(android.R.color.system_accent1_800) else Color.WHITE
                    if (dark) FastColors(
                        surface = surf,
                        onSurface = onSurf,
                        primary = c(android.R.color.system_primary_dark),
                        onPrimary = onPrim,
                        onSurfaceVariant = c(android.R.color.system_on_surface_variant_dark),
                        primaryContainer = c(android.R.color.system_primary_container_dark),
                        onPrimaryContainer = c(android.R.color.system_accent1_100),
                        outline = c(android.R.color.system_outline_dark),
                        outlineVariant = c(android.R.color.system_outline_variant_dark),
                        surfaceVariant = c(android.R.color.system_surface_variant_dark),
                        error = err,
                        disabledBg = blendOver(onSurf, 0x1F, surf),
                        disabledFg = withAlpha(onSurf, 0x61),
                        focusOverlay = focus,
                    ) else FastColors(
                        surface = surf,
                        onSurface = onSurf,
                        primary = c(android.R.color.system_primary_light),
                        onPrimary = onPrim,
                        onSurfaceVariant = c(android.R.color.system_on_surface_variant_light),
                        primaryContainer = c(android.R.color.system_primary_container_light),
                        onPrimaryContainer = c(android.R.color.system_accent1_900),
                        outline = c(android.R.color.system_outline_light),
                        outlineVariant = c(android.R.color.system_outline_variant_light),
                        surfaceVariant = c(android.R.color.system_surface_variant_light),
                        error = err,
                        disabledBg = blendOver(onSurf, 0x1F, surf),
                        disabledFg = withAlpha(onSurf, 0x61),
                        focusOverlay = focus,
                    )
                }
                Build.VERSION.SDK_INT >= 31 -> {
                    val surf = c(if (dark) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_10)
                    val onSurf = c(if (dark) android.R.color.system_neutral1_100 else android.R.color.system_neutral1_900)
                    val onPrim = if (dark) c(android.R.color.system_accent1_800) else Color.WHITE
                    if (dark) FastColors(
                        surface = surf,
                        onSurface = onSurf,
                        primary = c(android.R.color.system_accent1_200),
                        onPrimary = onPrim,
                        onSurfaceVariant = c(android.R.color.system_neutral2_200),
                        primaryContainer = c(android.R.color.system_accent1_700),
                        onPrimaryContainer = c(android.R.color.system_accent1_100),
                        outline = c(android.R.color.system_neutral2_400),
                        outlineVariant = c(android.R.color.system_neutral2_700),
                        surfaceVariant = c(android.R.color.system_neutral2_700),
                        error = err,
                        disabledBg = blendOver(onSurf, 0x1F, surf),
                        disabledFg = withAlpha(onSurf, 0x61),
                        focusOverlay = focus,
                    ) else FastColors(
                        surface = surf,
                        onSurface = onSurf,
                        primary = c(android.R.color.system_accent1_600),
                        onPrimary = onPrim,
                        onSurfaceVariant = c(android.R.color.system_neutral2_700),
                        primaryContainer = c(android.R.color.system_accent1_100),
                        onPrimaryContainer = c(android.R.color.system_accent1_900),
                        outline = c(android.R.color.system_neutral2_500),
                        outlineVariant = c(android.R.color.system_neutral2_200),
                        surfaceVariant = c(android.R.color.system_neutral2_100),
                        error = err,
                        disabledBg = blendOver(onSurf, 0x1F, surf),
                        disabledFg = withAlpha(onSurf, 0x61),
                        focusOverlay = focus,
                    )
                }
                dark -> {
                    val surf = 0xFF141218.toInt()
                    val onSurf = 0xFFE6E0E9.toInt()
                    FastColors(
                        surface = surf,
                        onSurface = onSurf,
                        primary = 0xFFD0BCFF.toInt(),
                        onPrimary = 0xFF381E72.toInt(),
                        onSurfaceVariant = 0xFFCAC4D0.toInt(),
                        primaryContainer = 0xFF4F378B.toInt(),
                        onPrimaryContainer = 0xFFEADDFF.toInt(),
                        outline = 0xFF938F99.toInt(),
                        outlineVariant = 0xFF49454F.toInt(),
                        surfaceVariant = 0xFF49454F.toInt(),
                        error = err,
                        disabledBg = blendOver(onSurf, 0x1F, surf),
                        disabledFg = withAlpha(onSurf, 0x61),
                        focusOverlay = focus,
                    )
                }
                else -> {
                    val surf = 0xFFFEF7FF.toInt()
                    val onSurf = 0xFF1D1B20.toInt()
                    FastColors(
                        surface = surf,
                        onSurface = onSurf,
                        primary = 0xFF6750A4.toInt(),
                        onPrimary = Color.WHITE,
                        onSurfaceVariant = 0xFF49454F.toInt(),
                        primaryContainer = 0xFFEADDFF.toInt(),
                        onPrimaryContainer = 0xFF21005D.toInt(),
                        outline = 0xFF79747E.toInt(),
                        outlineVariant = 0xFFCAC4D0.toInt(),
                        surfaceVariant = 0xFFE7E0EC.toInt(),
                        error = err,
                        disabledBg = blendOver(onSurf, 0x1F, surf),
                        disabledFg = withAlpha(onSurf, 0x61),
                        focusOverlay = focus,
                    )
                }
            }
        }
    }
}

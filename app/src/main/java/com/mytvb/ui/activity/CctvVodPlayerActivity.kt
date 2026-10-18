package com.mytvb.ui.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.BaseActivity
import com.mytvb.core.ui.base.ChoiceDialog
import com.mytvb.core.ui.system.ViewUtils
import com.mytvb.databinding.ActivityCctvVodPlayerBinding
import com.mytvb.feature.cctv.vod.CctvEpisode
import com.mytvb.feature.cctv.vod.CctvVodApi
import com.mytvb.feature.cctv.vod.CctvVodProgressStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import java.util.Locale

/**
 * 央视点播播放页：**WebView 载入央视自己的详情页负责解码播放，交互层由我们自己实现**。
 *
 * 为什么这样组合（2026-10-18 定稿）：
 * - 央视网页端的高码率转码源（720P）Android 解码器解不出来（硬解/软解/同步异步/Surface/Texture
 *   全部试过，一律花屏），只有官方播放器能正常播，所以解码必须交给 WebView；
 * - 但官方播放器的控件只认鼠标/触摸，遥控器按任何键都没反应（实测），TV 盒子没法用，
 *   所以播放控制与菜单（播放/暂停、快进快退、画质、选集）由我们自己的控制条承担，
 *   再通过 `evaluateJavascript` 驱动官方播放器。
 */
class CctvVodPlayerActivity : BaseActivity<ActivityCctvVodPlayerBinding>() {

    private val okHttpClient: OkHttpClient by inject()
    private val api: CctvVodApi by lazy { CctvVodApi(okHttpClient) }

    private val handler = Handler(Looper.getMainLooper())
    private var pageUrl = ""
    private var albumId = ""
    private var episodeMode = 0
    private var episodes: List<CctvEpisode> = emptyList()
    private var episodesJob: Job? = null

    private var durationMs = 0L
    private var positionMs = 0L
    private var exitTime = 0L
    /** 本次触摸序列是否始于左上返回按钮（是则不旁听单点手势切换控制条）。 */
    private var touchOnBackButton = false
    /** 用户意图是否为暂停。页面侧会自己恢复播放（注入脚本重跑 / 换流后 autoplay），按此意图压回去。 */
    private var userPaused = false
    /** 最近一次页面回传的暂停状态，用于推导用户意图。 */
    private var pagePaused = false
    private var lastPauseCorrectAt = 0L
    /** 最近一次处理返回键的时间：老设备走 KeyEvent、Android 13+ 走 OnBackInvoked 通道，用于去重。 */
    private var lastBackHandledAt = 0L
    private var playbackSession = 0
    /** 用户正在拖动进度条：此时不要用回传的播放位置覆盖进度，否则手指会被"拽回去"。 */
    private var seekingByUser = false
    /** 待续播位置（毫秒）：页面里视频可播后一次性 seek 过去。 */
    private var pendingResumeMs = 0L
    private var resumeApplied = false
    /** 本集播完后是否已触发连播（页面会反复回传 ended，需防重入）。 */
    private var autoNextTriggered = false
    private var currentEpisodeIndex = 0
    private var episodeTitle = ""
    private var saveTickCounter = 0
    /** 上一轮回传的位置，用于识别"官方页面自己连播了"。 */
    private var lastPositionMs = 0L
    /** 当前画质在官方控制条菜单里的下标（0=超清 / 1=高清 / 2=标清 / 3=流畅）。 */
    private var currentQualityIndex = 0
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val progressTicker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    private val hideControllerRunnable = Runnable { hideController() }

    private val gestureDetector by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            /** 双击：播放 / 暂停（视频播放器的通用交互）。 */
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (binding.controller.visibility == View.VISIBLE) {
                    val rect = android.graphics.Rect()
                    if (binding.controller.getGlobalVisibleRect(rect) &&
                        rect.contains(e.x.toInt(), e.y.toInt())
                    ) {
                        return false
                    }
                }
                showController()
                togglePlayPause()
                return true
            }

            /** 单击：切换控制条显隐（双击不会走到这里，GestureDetector 已区分）。 */
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (binding.controller.visibility == View.VISIBLE) {
                    val rect = android.graphics.Rect()
                    if (binding.controller.getGlobalVisibleRect(rect) &&
                        rect.contains(e.x.toInt(), e.y.toInt())
                    ) {
                        return false
                    }
                    hideController()
                } else {
                    showController()
                }
                return true
            }
        })
    }

    override fun getViewBinding(): ActivityCctvVodPlayerBinding {
        return ActivityCctvVodPlayerBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageUrl = intent.getStringExtra(EXTRA_PAGE_URL).orEmpty()
        albumId = intent.getStringExtra(EXTRA_ALBUM_ID).orEmpty()
        episodeMode = intent.getIntExtra(EXTRA_EPISODE_MODE, 0)
        currentEpisodeIndex = intent.getIntExtra(EXTRA_EPISODE_INDEX, 0)
        episodeTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.textTitle.text = episodeTitle
        pendingResumeMs = CctvVodProgressStore.load(this, albumId, currentEpisodeIndex)
        binding.playerRoot.requestFocus()
        setupButtons()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBackKey()
        })
        ViewUtils.keepScreenOn(this, true)
        configureWebView()
        if (pageUrl.isBlank()) {
            showError(getString(R.string.cctv_vod_stream_failed))
            return
        }
        AppLog.i(TAG, "load page=$pageUrl")
        showLoading(true)
        binding.webPlayer.loadUrl(pageUrl)
        // 预加载分集列表：既让选集菜单秒开，也是识别"官方自己连播到第几集"的唯一依据
        preloadEpisodes()
        scheduleControllerHide()
    }

    private fun preloadEpisodes() {
        if (albumId.isBlank() || episodes.isNotEmpty() || episodesJob?.isActive == true) return
        episodesJob = lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { api.fetchEpisodes(albumId, episodeMode) }
            if (isFinishing || isDestroyed) return@launch
            if (list.isNotEmpty()) {
                episodes = list
                AppLog.i(TAG, "episodesPreloaded size=${list.size}")
            }
        }
    }

    private fun setupButtons() {
        binding.buttonBack.touchPressFeedback = true
        binding.buttonBack.setOnClickListener { finish() }
        binding.buttonPlay.setOnClickListener { togglePlayPause() }
        binding.buttonQuality.setOnClickListener { showQualityDialog() }
        binding.buttonEpisode.setOnClickListener { showEpisodeDialog() }
        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // 拖动过程中先本地预览时间，松手才真正跳转
                binding.textPosition.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {
                seekingByUser = true
                handler.removeCallbacks(hideControllerRunnable)
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {
                seekingByUser = false
                js("seekTo", seekBar.progress.toLong())
                scheduleControllerHide()
            }
        })
        val cancelAutoHide = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) handler.removeCallbacks(hideControllerRunnable)
        }
        binding.buttonQuality.onFocusChangeListener = cancelAutoHide
        binding.buttonEpisode.onFocusChangeListener = cancelAutoHide
        binding.seekBar.onFocusChangeListener = cancelAutoHide
        binding.buttonPlay.onFocusChangeListener = cancelAutoHide
        binding.buttonBack.onFocusChangeListener = cancelAutoHide
        updateQualityButton(getString(R.string.cctv_quality))
    }

    /** 控制条上的播放/暂停图标随实际状态切换（暂停时显示播放三角）。 */
    private fun updatePlayPauseIcon(paused: Boolean) {
        val res = if (paused) R.drawable.ic_play else R.drawable.ic_pause
        if (binding.buttonPlay.tag != res) {
            binding.buttonPlay.tag = res
            binding.buttonPlay.setImageResource(res)
        }
    }

    /** 右下角大暂停图标：与普通视频播放器的暂停指示同款，暂停且已就绪时显示。 */
    private fun updatePauseIndicator(paused: Boolean, ready: Int) {
        binding.imagePauseIndicator.visibility =
            if (paused && ready >= 2) View.VISIBLE else View.GONE
    }

    override fun onStart() {
        super.onStart()
        handler.post(progressTicker)
    }

    override fun onStop() {
        handler.removeCallbacks(progressTicker)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        binding.webPlayer.onResume()
        ViewUtils.keepScreenOn(this, true)
        // 暂停状态下从后台回来不要自动续播
        if (!userPaused) js("play")
    }

    override fun onPause() {
        persistProgress()
        binding.webPlayer.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        persistProgress()
        handler.removeCallbacksAndMessages(null)
        episodesJob?.cancel()
        playbackSession++
        hideCustomView()
        binding.webPlayer.apply {
            stopLoading()
            loadUrl("about:blank")
            removeJavascriptInterface(BRIDGE_NAME)
            webChromeClient = null
            webViewClient = WebViewClient()
            destroy()
        }
        ViewUtils.keepScreenOn(this, false)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webPlayer, true)

        binding.webPlayer.keepScreenOn = true
        binding.webPlayer.setBackgroundColor(Color.BLACK)
        // 按键由宿主统一接管（遥控器操作官方控件无效），WebView 不抢焦点
        binding.webPlayer.isFocusable = false
        binding.webPlayer.isFocusableInTouchMode = false
        binding.webPlayer.addJavascriptInterface(Bridge(), BRIDGE_NAME)
        binding.webPlayer.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                AppLog.i(TAG, "pageStarted url=$url")
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                AppLog.i(TAG, "pageFinished url=$url")
                injectPlayerModeRepeatedly()
                super.onPageFinished(view, url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    AppLog.e(TAG, "webError code=${error.errorCode} desc=${error.description}")
                    showError(getString(R.string.cctv_vod_load_failed))
                }
                super.onReceivedError(view, request, error)
            }
        }
        binding.webPlayer.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                binding.playerRoot.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                binding.webPlayer.visibility = View.GONE
            }

            override fun onHideCustomView() = hideCustomView()

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                AppLog.i(TAG, "webConsole ${consoleMessage.message()}")
                return true
            }
        }
        binding.webPlayer.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = false
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = USER_AGENT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        binding.playerRoot.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        binding.webPlayer.visibility = View.VISIBLE
    }

    private fun injectPlayerModeRepeatedly() {
        val session = playbackSession
        repeat(INJECT_ATTEMPTS) { index ->
            handler.postDelayed({
                if (session != playbackSession || isFinishing || isDestroyed) return@postDelayed
                binding.webPlayer.evaluateJavascript(PLAYER_MODE_SCRIPT) { result ->
                    AppLog.i(TAG, "inject attempt=${index + 1} result=$result")
                }
            }, index * INJECT_INTERVAL_MS)
        }
    }

    /** 向页面里的官方播放器下发一条命令。 */
    private fun js(command: String, arg: Long = 0L) {
        if (isFinishing || isDestroyed) return
        val script = "(function(){try{var cmd='%s';var arg=%d;%s}catch(e){return 'err '+e;}})();"
            .format(command, arg, JS_HELPERS)
        binding.webPlayer.evaluateJavascript(script) { result ->
            AppLog.i(TAG, "js $command($arg) -> $result")
        }
    }

    // ---------------------------------------------------------------- 播放控制

    private fun togglePlayPause() {
        // 页面侧读到的 paused 可能与本地不同步，翻转交给页面自己做；这里只记录意图
        userPaused = !pagePaused
        js("toggle")
    }

    private fun seekBy(deltaMs: Long) {
        js("seek", deltaMs)
    }

    // ---------------------------------------------------------------- 控制条

    private fun updateProgress() {
        if (durationMs > 0) {
            binding.seekBar.max = durationMs.toInt()
            if (!seekingByUser) {
                binding.seekBar.progress = positionMs.coerceIn(0, durationMs).toInt()
                binding.textPosition.text = formatTime(positionMs)
            }
            binding.textDuration.text = formatTime(durationMs)
        } else {
            if (!seekingByUser) {
                binding.seekBar.progress = 0
                binding.textPosition.text = formatTime(0)
            }
            binding.textDuration.text = formatTime(0)
        }
    }

    private fun formatTime(millis: Long): String {
        val safe = (millis / 1000L).coerceAtLeast(0L)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val seconds = safe % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun showController() {
        binding.controller.visibility = View.VISIBLE
        binding.buttonBack.visibility = View.VISIBLE
        scheduleControllerHide()
    }

    private fun hideController() {
        handler.removeCallbacks(hideControllerRunnable)
        binding.controller.visibility = View.GONE
        binding.buttonBack.visibility = View.GONE
        if (!binding.playerRoot.isFocused) {
            binding.playerRoot.requestFocus()
        }
    }

    private fun scheduleControllerHide() {
        handler.removeCallbacks(hideControllerRunnable)
        handler.postDelayed(hideControllerRunnable, CONTROLLER_HIDE_DELAY_MS)
    }

    private fun updateQualityButton(text: String) {
        binding.buttonQuality.text = text
    }

    // ---------------------------------------------------------------- 菜单

    /** 画质菜单：项的顺序与官方控制条里的列表一致（超清/高清/标清/流畅）。 */
    private fun showQualityDialog() {
        showController()
        val options = QUALITY_LABELS
        ChoiceDialog.show(
            context = this,
            title = getString(R.string.cctv_quality),
            options = options,
            selectedIndex = currentQualityIndex
        ) { index ->
            val label = options.getOrNull(index) ?: return@show
            currentQualityIndex = index
            updateQualityButton(label)
            js("quality", index.toLong())
        }
    }

    private fun showEpisodeDialog() {
        showController()
        if (albumId.isBlank()) return
        if (episodes.isNotEmpty()) {
            displayEpisodePicker()
            return
        }
        AppToast.show(this, R.string.cctv_vod_episodes_loading)
        episodesJob?.cancel()
        episodesJob = lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { api.fetchEpisodes(albumId, episodeMode) }
            if (isFinishing || isDestroyed) return@launch
            if (list.isEmpty()) {
                AppToast.show(this@CctvVodPlayerActivity, R.string.cctv_vod_stream_failed)
                return@launch
            }
            episodes = list
            displayEpisodePicker()
        }
    }

    private fun displayEpisodePicker() {
        val options = episodes.map { episode ->
            episode.title.ifBlank { getString(R.string.cctv_vod_episode_title_format, episode.index) }
        }
        val selected = episodes.indexOfFirst { it.pageUrl == pageUrl }.coerceAtLeast(0)
        ChoiceDialog.show(
            context = this,
            title = getString(R.string.choose_episode),
            options = options,
            selectedIndex = selected
        ) { index ->
            val episode = episodes.getOrNull(index) ?: return@show
            if (episode.pageUrl == pageUrl) return@show
            switchEpisode(episode)
        }
    }

    private fun switchEpisode(episode: CctvEpisode) {
        AppLog.i(TAG, "switchEpisode index=${episode.index} page=${episode.pageUrl}")
        // 切走前先把当前进度落盘
        persistProgress()
        pageUrl = episode.pageUrl
        currentEpisodeIndex = episode.index
        episodeTitle = episode.title
        binding.textTitle.text = episodeTitle
        durationMs = 0L
        positionMs = 0L
        currentQualityIndex = 0
        updateQualityButton(QUALITY_LABELS.first())
        showLoading(true)
        playbackSession++
        // 新一集：读它自己的本地进度并续播
        pendingResumeMs = CctvVodProgressStore.load(this, albumId, currentEpisodeIndex)
        resumeApplied = false
        autoNextTriggered = false
        userPaused = false
        pagePaused = false
        saveTickCounter = 0
        lastPositionMs = 0L
        binding.webPlayer.loadUrl(pageUrl)
    }

    /** 本集播完：有下一集就自动连播，没有（最后一集）就停在结束画面。 */
    private fun playNextEpisode() {
        if (autoNextTriggered) return
        autoNextTriggered = true
        val next = episodes.firstOrNull { it.index == currentEpisodeIndex + 1 }
        if (next == null) {
            AppLog.i(TAG, "allEpisodesFinished index=$currentEpisodeIndex")
            AppToast.show(this, R.string.cctv_vod_all_episodes_finished)
            showController()
            return
        }
        AppLog.i(TAG, "autoNext -> index=${next.index}")
        AppToast.show(this, getString(R.string.cctv_vod_playing_next, next.index))
        switchEpisode(next)
    }

    /** 把当前进度写入本地（临近片尾会清掉记录）。 */
    private fun persistProgress() {
        if (albumId.isBlank() || currentEpisodeIndex <= 0) return
        CctvVodProgressStore.save(
            context = this,
            albumId = albumId,
            episodeIndex = currentEpisodeIndex,
            positionMs = positionMs,
            durationMs = durationMs
        )
    }

    // ---------------------------------------------------------------- 状态回传

    private inner class Bridge {
        @JavascriptInterface
        fun onVideoState(state: String) {
            handler.post {
                val ready = Regex("\"readyState\":(\\d+)").find(state)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val paused = state.contains("\"paused\":true")
                val ended = state.contains("\"ended\":true")
                val cur = Regex("\"currentTime\":([\\d.]+)").find(state)
                    ?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                val dur = Regex("\"duration\":([\\d.]+)").find(state)
                    ?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                if (dur > 0) durationMs = (dur * 1000).toLong()
                positionMs = (cur * 1000).toLong()
                pagePaused = paused
                updatePlayPauseIcon(paused)
                updatePauseIndicator(paused, ready)
                AppLog.i(TAG, "state ready=$ready paused=$paused ended=$ended pos=${positionMs / 1000}s dur=${durationMs / 1000}s")
                // 官方页面自己会连播（播完自动换下一集），而且是页面内换流：URL 与
                // document.title 都不变，页面层没有可观测的变化。只能按行为特征判断：
                // 上一轮位置已接近片尾、这一轮却从头开始 ⇒ 已经切到下一集。
                // 不跟着推进集号的话，下一集的进度会被记到上一集头上。
                val nearEndLastRound = durationMs > 0 && lastPositionMs >= durationMs - NEAR_END_MS
                val restartedNow = positionMs < RESTART_MS && lastPositionMs > 60_000L
                if (nearEndLastRound && restartedNow) {
                    val next = episodes.firstOrNull { it.index == currentEpisodeIndex + 1 }
                    if (next != null) {
                        AppLog.i(TAG, "pageSelfAdvanced -> index=${next.index}")
                        persistProgress()
                        currentEpisodeIndex = next.index
                        episodeTitle = next.title
                        binding.textTitle.text = episodeTitle
                        pageUrl = next.pageUrl
                        pendingResumeMs = 0L
                        resumeApplied = true
                        autoNextTriggered = false
                        saveTickCounter = 0
                    }
                }
                lastPositionMs = positionMs

                if (ready >= 2 && !paused) showLoading(false)

                // 视频可播后按本地记录续播一次
                if (!resumeApplied && ready >= 2 && durationMs > 0) {
                    resumeApplied = true
                    if (pendingResumeMs > 0) {
                        AppLog.i(TAG, "resume from $pendingResumeMs")
                        js("seekTo", pendingResumeMs)
                    }
                }

                // 每约 5 秒落一次进度（回传本身是 1 秒一次）
                saveTickCounter++
                if (saveTickCounter >= 5) {
                    saveTickCounter = 0
                    if (!seekingByUser) persistProgress()
                }

                if (ended) playNextEpisode()

                // 页面自己把播放恢复起来了（注入脚本重跑 / 换流后 autoplay）：
                // 用户意图是暂停就压回去，否则表现为"暂停一会自己又播了"。
                if (userPaused && !paused && !ended && ready >= 2) {
                    val now = System.currentTimeMillis()
                    if (now - lastPauseCorrectAt > PAUSE_CORRECT_INTERVAL_MS) {
                        lastPauseCorrectAt = now
                        AppLog.i(TAG, "pageResumedWhilePaused -> pause again")
                        js("pause")
                    }
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.loading.visibility = if (show) View.VISIBLE else View.GONE
        binding.textLoading.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        showLoading(false)
        binding.textError.text = message
        binding.textError.visibility = View.VISIBLE
        showController()
    }

    // ---------------------------------------------------------------- 按键

    /** 焦点是否落在某个可操作控件上（进度条 / 画质 / 选集 / 返回）。 */
    private fun isActionButtonFocused(): Boolean =
        binding.buttonQuality.isFocused || binding.buttonEpisode.isFocused || binding.buttonBack.isFocused

    private fun isSeekBarFocused(): Boolean = binding.seekBar.isFocused

    private fun moveButtonFocus(delta: Int): Boolean {
        val visible = listOf(binding.buttonBack, binding.buttonPlay, binding.seekBar, binding.buttonQuality, binding.buttonEpisode)
            .filter { it.visibility == View.VISIBLE }
        if (visible.isEmpty()) return false
        val current = visible.indexOfFirst { it.isFocused }
        val target = if (current < 0) 0 else (current + delta).coerceIn(0, visible.lastIndex)
        return visible[target].requestFocus()
    }

    /**
     * BACK 与各方向键在这里统一处理：官方播放器控件不响应遥控器，所以不能把按键放给 WebView。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            AppLog.i(
                TAG,
                "key code=${event.keyCode} focus=${currentFocus?.let { it.javaClass.simpleName + "#" + it.id }} " +
                    "btnFocus=${isActionButtonFocused()} seekFocus=${isSeekBarFocused()} ctrl=${binding.controller.visibility == View.VISIBLE}"
            )
        }
        val controllerVisible = binding.controller.visibility == View.VISIBLE
        val buttonFocused = isActionButtonFocused()

        // 返回键（遥控器 BACK / 键盘 ESC）在 ACTION_DOWN 就处理：部分遥控器长按只发 DOWN，
        // 等 ACTION_UP 会"按了没反应"；两态都吞掉，避免事件继续下传造成重复处理。
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handleBackKey()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when {
                    buttonFocused -> {
                        if (event.repeatCount == 0) {
                            when {
                                binding.buttonBack.isFocused -> binding.buttonBack.performClick()
                                binding.buttonQuality.isFocused -> binding.buttonQuality.performClick()
                                binding.buttonEpisode.isFocused -> binding.buttonEpisode.performClick()
                            }
                        }
                        true
                    }
                    else -> {
                        // 进度条上按确定不做事（拖拽才是它的交互），播放/暂停交给播放区语义
                        if (event.repeatCount == 0) {
                            showController()
                            togglePlayPause()
                        }
                        true
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // 进度条上的左右键也走 ±10s（不要触发 SeekBar 自身的 1 步进）
                if (buttonFocused) moveButtonFocus(-1) else { showController(); seekBy(-SEEK_STEP_MS) }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (buttonFocused) moveButtonFocus(1) else { showController(); seekBy(SEEK_STEP_MS) }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                // 上键逐级上移：播放区 → 播放按钮 → 进度条 → 画质/选集 → 左上返回按钮
                when {
                    !controllerVisible -> {
                        showController()
                        binding.buttonPlay.requestFocus()
                    }
                    binding.buttonBack.isFocused -> binding.buttonPlay.requestFocus()
                    binding.buttonPlay.isFocused -> binding.seekBar.requestFocus()
                    binding.seekBar.isFocused -> {
                        val target = binding.buttonQuality.takeIf { it.visibility == View.VISIBLE }
                            ?: binding.buttonEpisode
                        target.requestFocus()
                    }
                    binding.buttonQuality.isFocused || binding.buttonEpisode.isFocused ->
                        binding.buttonBack.requestFocus()
                    else -> binding.buttonPlay.requestFocus()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (buttonFocused || isSeekBarFocused()) {
                    binding.playerRoot.requestFocus()
                    true
                } else if (controllerVisible) {
                    hideController()
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event.repeatCount == 0) showEpisodeDialog()
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    /**
     * 触摸：单击画面切换我们自己的控制条显隐。
     *
     * **不能吞掉事件**：早先的实现消费了 ACTION_UP，WebView 只收到 ACTION_DOWN、
     * 一直等不到抬起，被判定成长按，弹出了 video.js 的上下文菜单（里面是播放器版本号
     * `core/wrap`）。这里改为把事件原样转发给 WebView，只用自己的 GestureDetector 旁听单点。
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // 左上返回按钮自己处理点击（点它不该顺带切换控制条显隐），
        // 以 DOWN 落点决定整条手势序列是否旁听，避免只喂半条序列给 GestureDetector。
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOnBackButton = isInsideBackButton(event.x, event.y)
        }
        if (!touchOnBackButton) {
            gestureDetector.onTouchEvent(event)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isInsideBackButton(x: Float, y: Float): Boolean {
        if (binding.buttonBack.visibility != View.VISIBLE) return false
        val rect = android.graphics.Rect()
        return binding.buttonBack.getGlobalVisibleRect(rect) && rect.contains(x.toInt(), y.toInt())
    }

    /**
     * 返回键的关闭优先级：全屏画面 → 播放 UI（控制条）→ 退出播放页。
     * 只有前面都关干净了，再按一次才提示"再按一次退出"。
     */
    private fun handleBackKey() {
        val now = System.currentTimeMillis()
        // KeyEvent 与 OnBackInvoked 两条通道在个别设备上会各派发一次，去重防连跳两级
        if (now - lastBackHandledAt < BACK_DEDUPE_MS) return
        lastBackHandledAt = now
        val controllerVisible = binding.controller.visibility == View.VISIBLE
        AppLog.i(
            TAG,
            "backPressed controllerVisible=$controllerVisible customView=${customView != null}"
        )
        if (customView != null) {
            hideCustomView()
            return
        }
        if (controllerVisible) {
            hideController()
            return
        }
        if (now - exitTime <= EXIT_INTERVAL_MS) {
            finish()
        } else {
            exitTime = now
            AppToast.show(applicationContext, R.string.activity_exit_player_hint)
        }
    }

    companion object {
        const val EXTRA_PAGE_URL = "cctv_vod_page_url"
        const val EXTRA_TITLE = "cctv_vod_title"
        const val EXTRA_GUID = "cctv_vod_episode_guid"
        const val EXTRA_ALBUM_ID = "cctv_vod_album_id"
        const val EXTRA_EPISODE_MODE = "cctv_vod_episode_mode"
        const val EXTRA_EPISODE_INDEX = "cctv_vod_episode_index"

        private const val TAG = "CctvVodPlayer"
        private const val BRIDGE_NAME = "MyBiliCctvVodBridge"
        private const val INJECT_ATTEMPTS = 12
        private const val INJECT_INTERVAL_MS = 1_500L
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val CONTROLLER_HIDE_DELAY_MS = 5_000L
        private const val SEEK_STEP_MS = 10_000L
        private const val EXIT_INTERVAL_MS = 2_000L
        /** 返回键去重窗口：KeyEvent 与 OnBackInvoked 双通道各自派发时的间隔远小于此值。 */
        private const val BACK_DEDUPE_MS = 300L
        /** 暂停纠偏的最小间隔：页面顽固自播时避免每 500ms 回传都压一次。 */
        private const val PAUSE_CORRECT_INTERVAL_MS = 2_000L
        /** 距片尾多近算"看完"（与进度存储保持一致）。 */
        private const val NEAR_END_MS = 60_000L
        /** 位置小于这个值视为"从头开始"。 */
        private const val RESTART_MS = 15_000L

        /** 与官方控制条里画质菜单项的顺序一致。 */
        private val QUALITY_LABELS = listOf("超清", "高清", "标清", "流畅")

        /**
         * 必须用桌面 Chrome UA：央视页面按 UA 分发播放器，带 "Android" 会被判成移动端，
         * 拿到的是没有完整控制条的移动版（实测 `#_video_player` 根本不存在）。
         */
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        /**
         * 页面里执行的命令实现（与 [js] 拼接）。
         *
         * 播放/暂停优先用 video.js 的播放器实例（页面是 video.js + P2P，直接操作 DOM 的
         * video 元素状态可能与它不同步，表现为"按了没反应"），实例拿不到才退回 DOM 操作。
         * quality 切换后官方播放器会换流并从头播，这里记下切换前的位置、等新流可用后恢复。
         */
        private const val JS_HELPERS = """
            function pl(){try{var ps=(window.videojs&&videojs.getPlayers)?videojs.getPlayers():{};if(ps&&ps['_video_player'])return ps['_video_player'];}catch(e){}return null;}
            function markPaused(p){window.__mybiliUserPaused=!!p;}
            var P=pl();
            if(cmd==='toggle'){
              if(P){if(P.paused()){P.play();markPaused(false);}else{P.pause();markPaused(true);}return 'ok';}
              var v0=document.getElementsByTagName('video')[0];if(!v0)return 'novideo';
              if(v0.paused){v0.play();markPaused(false);}else{v0.pause();markPaused(true);}return 'ok';
            }
            var v=document.getElementsByTagName('video')[0];
            if(!v)return 'novideo';
            if(cmd==='play'){if(P){P.play();}else{v.play();}markPaused(false);return 'ok';}
            if(cmd==='pause'){if(P){P.pause();}else{v.pause();}markPaused(true);return 'ok';}
            if(cmd==='seek'){var d=v.duration||0;var t=v.currentTime+arg/1000;if(t<0)t=0;if(d>0&&t>d)t=d;v.currentTime=t;return 'ok';}
            if(cmd==='seekTo'){var d0=v.duration||0;var t0=arg/1000;if(t0<0)t0=0;if(d0>0&&t0>d0)t0=d0;v.currentTime=t0;return 'ok';}
            if(cmd==='quality'){
              var li=document.querySelectorAll('#_video_player .vjs-playback-quality li');
              if(!li||!li[arg])return 'noitems';
              var resumeAt=v.currentTime;var wasPaused=v.paused;
              li[arg].click();
              var tries=0;
              var timer=setInterval(function(){
                tries++;
                var nv=document.getElementsByTagName('video')[0];
                if(nv&&nv.readyState>=1){
                  if(Math.abs((nv.currentTime||0)-resumeAt)>2){try{nv.currentTime=resumeAt;}catch(e){}}
                  // 原本暂停就保持暂停（新流带 autoplay，不压回去会自己播起来）
                  if(wasPaused){try{nv.pause();}catch(e){}}else{try{nv.play();}catch(e){}}
                  clearInterval(timer);
                }
                if(tries>25)clearInterval(timer);
              },300);
              return 'ok';
            }
            return 'unknown';
        """

        /**
         * 页面收敛：只铺满外层容器，**不要动 video 元素的 position/尺寸/z-index**
         * （官方控制条与 video 是同级兄弟，把 video 拉全屏会盖掉控制条），
         * 播放器本体靠 video.js 的 vjs-fill 撑满。
         */
        private val PLAYER_MODE_SCRIPT = """
            (function() {
              try {
                if (!document.documentElement || !document.body) return 'not-ready';
                document.documentElement.style.background = '#000';
                document.body.style.background = '#000';
                document.body.style.margin = '0';
                document.body.style.padding = '0';
                document.body.style.overflow = 'hidden';

                function hardFix(el) {
                  if (!el) return false;
                  el.style.setProperty('position', 'fixed', 'important');
                  el.style.setProperty('left', '0', 'important');
                  el.style.setProperty('top', '0', 'important');
                  el.style.setProperty('right', 'auto', 'important');
                  el.style.setProperty('bottom', 'auto', 'important');
                  el.style.setProperty('width', '100%', 'important');
                  el.style.setProperty('height', '100%', 'important');
                  el.style.setProperty('max-width', 'none', 'important');
                  el.style.setProperty('max-height', 'none', 'important');
                  el.style.setProperty('margin', '0', 'important');
                  el.style.setProperty('padding', '0', 'important');
                  el.style.setProperty('background', '#000', 'important');
                  el.style.setProperty('z-index', '2147483647', 'important');
                  el.style.setProperty('overflow', 'hidden', 'important');
                  return true;
                }
                ['#videolist1', '.title19600', '.title18847', '.jingxuantanceng', '.headernew', '.nav', '.page_bottom', '.footer', '.column_wrapper', '.context-menu']
                  .forEach(function(sel) {
                    var el = document.querySelector(sel);
                    if (el) el.style.setProperty('display', 'none', 'important');
                  });
                // 官方控制条对遥控器无效（只认鼠标/触摸），而我们自己那层功能等价且遥控器可用，
                // 两套并存会重复显示时间与画质入口，这里把官方的收起。
                // 注意用 visibility 而非 display：画质菜单项仍需可被 JS click 命中。
                ['#_video_player .vjs-control-bar', '#_video_player .vjs-big-play-button', '#_video_player .vjs-play-control-mini', '#_video_player .vjs-picture-in-picture-control']
                  .forEach(function(sel) {
                    var el = document.querySelector(sel);
                    if (el) {
                      el.style.setProperty('visibility', 'hidden', 'important');
                      el.style.setProperty('opacity', '0', 'important');
                      el.style.setProperty('pointer-events', 'none', 'important');
                    }
                  });
                ['.allcontent', '.video19600', '.playingVideo', '.video', '.video_left']
                  .forEach(function(sel) {
                    var el = document.querySelector(sel);
                    if (el) hardFix(el);
                  });
                ['#video', '#_video', '#_video_player'].forEach(function(sel) {
                  var el = document.querySelector(sel);
                  if (el) {
                    el.style.setProperty('width', '100%', 'important');
                    el.style.setProperty('height', '100%', 'important');
                  }
                });

                var videos = Array.prototype.slice.call(document.getElementsByTagName('video'));
                videos.forEach(function(video) {
                  video.autoplay = true;
                  video.muted = false;
                  // 本脚本每 1.5s 重跑一次（共 12 次），用户暂停期间不能把它抢播回去
                  if (!window.__mybiliUserPaused) {
                    video.play && video.play().catch(function(e) { console.log('cctv vod play rejected ' + e); });
                  }
                });

                if (!window.__mybiliVodTimer) {
                  window.__mybiliVodTimer = setInterval(function() {
                    try {
                      var v = document.getElementsByTagName('video')[0];
                      if (window.MyBiliCctvVodBridge && v) {
                        window.MyBiliCctvVodBridge.onVideoState(JSON.stringify({
                          readyState: v.readyState || 0,
                          paused: !!v.paused,
                          currentTime: v.currentTime || 0,
                          duration: v.duration || 0,
                          ended: !!v.ended,
                          title: document.title
                        }));
                      }
                    } catch (e) {}
                  }, 1000);
                }
                return 'ok videoCount=' + videos.length;
              } catch (error) {
                console.log('cctv vod inject error ' + error);
                return 'error ' + error;
              }
            })();
        """.trimIndent()

        fun intentForEpisode(
            context: Context,
            episode: CctvEpisode,
            title: String,
            albumId: String,
            episodeMode: Int
        ): Intent = Intent(context, CctvVodPlayerActivity::class.java)
            .putExtra(EXTRA_PAGE_URL, episode.pageUrl)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_GUID, episode.guid)
            .putExtra(EXTRA_ALBUM_ID, albumId)
            .putExtra(EXTRA_EPISODE_MODE, episodeMode)
            .putExtra(EXTRA_EPISODE_INDEX, episode.index)
    }
}

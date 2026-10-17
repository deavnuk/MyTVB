@file:Suppress("SpellCheckingInspection")

package com.mytvb.network

import com.mytvb.core.common.json.GsonHolder
import android.content.Context
import com.mytvb.model.BaseResponse
import com.mytvb.model.user.UserDetailInfoModel
import com.mytvb.network.api.ApiService
import com.mytvb.network.http.NetworkClientFactory
import com.mytvb.network.response.Base2Response
import com.mytvb.network.response.BaseBaseResponse
import com.mytvb.network.security.AppSignUtils
import com.mytvb.network.security.BiliSecurityCoordinator
import com.mytvb.network.session.AuthContext
import com.mytvb.network.session.NetworkSessionStore
import com.mytvb.network.session.SessionState
import com.mytvb.network.ua.DesktopUserAgentStore
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.network.cookie.CookieManager
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.koin.mp.KoinPlatform
import retrofit2.Retrofit

object NetworkManager {

    private const val TAG = "NetworkManager"
    private const val API_BASE = "https://api.bilibili.com/"
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    private const val KEY_CURRENT_UA = "currentUA"
    private const val AUTH_INVALID_CODE = -101
    private const val KEY_REFRESH_TOKEN = "bili_refresh_token"
    private const val KEY_APP_ACCESS_TOKEN = "app_access_token"
    private const val KEY_APP_REFRESH_TOKEN = "app_refresh_token"
    private const val KEY_APP_TOKEN_EXPIRES_AT = "app_token_expires_at"
    private const val KEY_HTTP_CACHE_SCHEMA = "http_cache_schema"
    private const val KEY_SESSION_HEALTH_CHECK_TS = "session_health_check_ts"
    private const val SESSION_HEALTH_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    /** 修改 HTTP 协商缓存结构时递增此值，可以在用户下次冷启动时一次性清空旧缓存。*/
    private const val HTTP_CACHE_SCHEMA = 1

    private var appContext: Context? = null
    private val sessionInitLock = Any()
    private val coreInitLock = Any()

    @Volatile
    private var sessionInitialized = false

    @Volatile
    private var coreInitialized = false

    private val userAgentStore = DesktopUserAgentStore(
        defaultUserAgent = DEFAULT_UA,
        preferenceKey = KEY_CURRENT_UA
    )
    private val sessionStore = NetworkSessionStore(
        authInvalidCode = AUTH_INVALID_CODE,
        hasSessionCookie = { internalCookieManager.hasSessionCookie() }
    )

    private val currentUserAgentValue: String
        get() = userAgentStore.getCurrentUserAgent()

    private val internalCookieManager: CookieManager by lazy { CookieManager() }

    private val internalOkHttpClient: OkHttpClient by lazy {
        val settings: AppSettingsDataStore? = runCatching {
            KoinPlatform.getKoin().get<AppSettingsDataStore>()
        }.getOrNull()
        // 显式不挂 okhttp3.Cache：
        // - HeaderInterceptor 给所有请求强制 Cache-Control: max-age=0，本身就要 revalidate；
        // - B 站推荐/动态/搜索这些主力 API 不返回 ETag/Last-Modified，服务器只会回 200，
        //   永远命中不了 304，cache 实际只写不读；
        // - 但每次冷启动都要付 100~200ms 打开 DiskLruCache journal（debug.txt 实测 175ms
        //   contention，落在 IO 协程上拉长 getRecommendList 的首包时延）。
        // 真正需要 disk 缓存的是图片，那边已经走 Coil 的独立 DiskCache，不依赖这里。
        NetworkClientFactory.createOkHttpClient(
            cookieManager = internalCookieManager,
            userAgentProvider = { currentUserAgentValue },
            acceptLanguageProvider = { getAcceptLanguage() },
            cacheDir = null,
            ipv4OnlyEnabled = { settings?.getCachedString("ipv4_only") != "关" },
            deviceBuvidProvider = { internalCookieManager.getCookieValue("buvid3").orEmpty() }
        )
    }

    private val noCookieOkHttpClient: OkHttpClient by lazy {
        internalOkHttpClient.newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .build()
    }

    private val gson by lazy {
        GsonHolder.CONFIGURED
    }

    private val retrofit: Retrofit by lazy {
        NetworkClientFactory.createRetrofit(
            baseUrl = API_BASE,
            client = internalOkHttpClient,
            gson = gson
        )
    }

    private val noCookieRetrofit: Retrofit by lazy {
        NetworkClientFactory.createRetrofit(
            baseUrl = API_BASE,
            client = noCookieOkHttpClient,
            gson = gson
        )
    }

    val apiService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }

    val noCookieApiService: ApiService by lazy {
        noCookieRetrofit.create(ApiService::class.java)
    }

    private val securityCoordinator: BiliSecurityCoordinator by lazy {
        BiliSecurityCoordinator(
            tag = TAG,
            apiService = apiService,
            okHttpClient = internalOkHttpClient,
            cookieManager = internalCookieManager,
            userAgentProvider = { currentUserAgentValue },
            refreshUserAgent = ::refreshUserAgent,
            syncUserSession = ::syncUserSession,
            refreshTokenProvider = { getRefreshToken() },
            refreshTokenSaver = { token -> saveRefreshToken(token) },
            updateWbiKeys = { img, sub -> sessionStore.setWbiInfo(img, sub) }
        )
    }

    fun initSession(context: Context, syncWebViewCookies: Boolean = true) {
        if (sessionInitialized) return
        synchronized(sessionInitLock) {
            if (sessionInitialized) return
            val startMs = android.os.SystemClock.elapsedRealtime()
            val applicationContext = context.applicationContext
            appContext = applicationContext
            internalCookieManager.init(applicationContext, syncWebViewCookies)
            userAgentStore.init(applicationContext)
            sessionStore.initPersistence(
                applicationContext.getSharedPreferences("network_session_store", Context.MODE_PRIVATE)
            )
            // cookie 在上一步已从磁盘恢复，这里统一对齐一次会话状态
            sessionStore.refreshSessionState()
            sessionInitialized = true
            AppLog.i(TAG, "initSession elapsed=${android.os.SystemClock.elapsedRealtime() - startMs}ms")
        }
    }

    fun init(context: Context, syncWebViewCookies: Boolean = true) {
        initSession(context, syncWebViewCookies)
        if (coreInitialized) return
        synchronized(coreInitLock) {
            if (coreInitialized) return
            val startMs = android.os.SystemClock.elapsedRealtime()
            val applicationContext = context.applicationContext
            appContext = applicationContext
            // 在后台一次性构造 OkHttp / Retrofit / ApiService。
            // 这样首个真实请求不会和 UI 主线程争 SynchronizedLazyImpl 锁。
            internalOkHttpClient
            retrofit
            apiService
            BiliClient.init(internalOkHttpClient)
            // 旧版本（< 这次重构前）的 OkHttp HTTP cache 现已不再使用，残留目录可能占几十 MB。
            // 异步清理，不阻塞主线程：deleteRecursively 在低端 TV 上同步执行 50~200ms。
            scheduleHttpCacheCleanup(applicationContext)
            coreInitialized = true
            AppLog.i(TAG, "initNetworkCore elapsed=${android.os.SystemClock.elapsedRealtime() - startMs}ms")
        }
    }

    private fun scheduleHttpCacheCleanup(applicationContext: Context) {
        Thread({
            val sp = applicationContext.getSharedPreferences(
                "network_http_cache_meta",
                Context.MODE_PRIVATE
            )
            if (sp.getInt(KEY_HTTP_CACHE_SCHEMA, 0) >= HTTP_CACHE_SCHEMA) return@Thread
            runCatching {
                java.io.File(applicationContext.cacheDir, "http_cache").deleteRecursively()
            }
            sp.edit().putInt(KEY_HTTP_CACHE_SCHEMA, HTTP_CACHE_SCHEMA).apply()
        }, "http-cache-cleanup").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }.start()
    }

    /**
     * 触发 lazy 字段初始化：OkHttp client、Gson、Retrofit、ApiService。
     * 实际上 [init] 里已经主线程同步触发过；这里保留是为了向下兼容老代码。
     */
    fun warmUp() {
        internalOkHttpClient
        gson
        retrofit
        apiService
        BiliClient.init(internalOkHttpClient)
    }

    // 已删除 preheatApiHosts()：实测两个 appScope.launch 并发启动时 preheat 抢不过 preload，
    // 收益为 0；如果将来想真正预连，需要让 preheat 早于 preloadFirstPage 调用且独占初始 connection。

    fun syncCookiesFromWebView() {
        internalCookieManager.syncFromWebView()
    }

    fun setWbiInfo(imgKey: String, subKey: String) {
        sessionStore.setWbiInfo(imgKey, subKey)
    }

    fun getWbiKeys(): Pair<String, String> {
        return sessionStore.getWbiKeys()
    }

    fun areWbiKeysStale(): Boolean {
        return sessionStore.areWbiKeysStale()
    }

    fun getCookieManager(): CookieManager = internalCookieManager

    fun getCsrfToken(): String {
        return internalCookieManager.getCsrfToken()
    }

    fun isLoggedIn(): Boolean {
        return internalCookieManager.hasSessionCookie()
    }

    /** 会话单一状态源（cookie + userInfo 组合），UI 订阅用 */
    val sessionState: StateFlow<SessionState>
        get() = sessionStore.sessionState

    fun currentSessionState(): SessionState {
        return sessionStore.sessionState.value
    }

    fun clearUserSession(clearCookies: Boolean = true, reason: String = "unknown") {
        val hadSession = sessionStore.getUserInfo() != null || internalCookieManager.hasSessionCookie()
        sessionStore.clearUserSession()
        resetSessionLifecycleState(clearCookies = clearCookies, reason = reason)
        // cookie 可能刚被清掉，重新对齐 CookieOnly/LoggedOut 区分
        sessionStore.refreshSessionState()
        if (hadSession) {
            // 会话从有到无，统一在此广播；调用方不再各自手动 dispatch
            notifySessionChanged()
        }
    }

    private fun softClearUserSession(reason: String) {
        val hadUserInfo = sessionStore.getUserInfo() != null
        sessionStore.softClearUserSession()
        securityCoordinator.resetRuntimeState()
        AppLog.w(TAG, "softClearUserSession: reason=$reason")
        if (hadUserInfo) {
            // 服务端 -101 失效也会走这里；只有状态真正发生跳变才广播，防并行请求事件风暴
            notifySessionChanged()
        }
    }

    suspend fun tryRecoverExpiredSession(): Boolean {
        if (!internalCookieManager.hasSessionCookie()) {
            AppLog.w(TAG, "tryRecoverExpiredSession: no SESSDATA, cannot recover")
            hardClearAndNotify("recovery_no_sessdata")
            return false
        }
        if (getRefreshToken().isNullOrBlank()) {
            AppLog.w(TAG, "tryRecoverExpiredSession: no refresh_token, cannot recover")
            hardClearAndNotify("recovery_no_refresh_token")
            return false
        }
        return try {
            securityCoordinator.forceCookieRefresh()
            val navResponse = apiService.getUserDetailInfo()
            // nav 对无效 cookie 返回 code=0 的游客数据（isLogin=false），只认 isLogin
            // 才算恢复成功，否则游客资料会把本地会话误标成已登录
            if (navResponse.isSuccess && navResponse.data?.isLogin == true) {
                sessionStore.updateUserSession(navResponse.data)
                AppLog.i(TAG, "tryRecoverExpiredSession: session recovered successfully")
                notifySessionChanged()
                true
            } else if (navResponse.code == -101) {
                AppLog.w(TAG, "tryRecoverExpiredSession: still -101 after refresh, session truly expired")
                hardClearAndNotify("recovery_still_expired")
                false
            } else {
                AppLog.w(TAG, "tryRecoverExpiredSession: nav check failed after refresh, code=${navResponse.code}")
                hardClearAndNotify("recovery_nav_failed")
                false
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "tryRecoverExpiredSession: exception during recovery", e)
            hardClearAndNotify("recovery_exception")
            false
        }
    }

    private fun hardClearAndNotify(reason: String) {
        // 广播已收敛到 clearUserSession 内部（跳变守卫），此处不再重复 notify
        clearUserSession(clearCookies = true, reason = reason)
    }

    /** 登录态健康检查结果，UI 据此决定是否提示重新登录。 */
    enum class SessionHealthResult {
        /** cookie 有效，顺带刷新了本地用户资料 */
        LOGGED_IN,
        /** cookie 曾失效，已用 refresh_token 恢复 */
        RECOVERED,
        /** 确认失效且恢复失败，已全清会话并广播（该弹提示） */
        EXPIRED_CLEARED,
        /** 本地无 SESSDATA，本来就是未登录态 */
        SKIP_NO_COOKIE,
        /** 24h 内已查过，本次跳过 */
        SKIP_THROTTLED,
        /** 网络异常无法判定，不动会话（下次启动再查） */
        NETWORK_ERROR
    }

    /**
     * 主动校验本地 SESSDATA 在服务端是否仍有效。
     *
     * 背景：timeline 等降级型接口在 cookie 失效时返回 code=0 的兜底数据而非 -101，
     * -101 被动检测永远不触发，失效会话会静默拿到降级池；本检查在冷启动后主动调
     * nav（带 cookie 的游客响应是 code=0 + isLogin=false，必须看 isLogin 而非 code）
     * 弥补这一盲区。确认失效时先走 refresh 恢复，恢复失败由 [tryRecoverExpiredSession]
     * 内部全清（含 cookie）并广播 UserSessionChanged。
     */
    suspend fun checkSessionHealth(force: Boolean = false): SessionHealthResult {
        if (!internalCookieManager.hasSessionCookie()) {
            return SessionHealthResult.SKIP_NO_COOKIE
        }
        val store = runCatching {
            KoinPlatform.getKoin().get<AppSettingsDataStore>()
        }.getOrNull() ?: return SessionHealthResult.NETWORK_ERROR
        val now = System.currentTimeMillis()
        if (!force && store.getCachedLong(KEY_SESSION_HEALTH_CHECK_TS) > 0L &&
            now - store.getCachedLong(KEY_SESSION_HEALTH_CHECK_TS) < SESSION_HEALTH_CHECK_INTERVAL_MS
        ) {
            return SessionHealthResult.SKIP_THROTTLED
        }
        return try {
            val nav = apiService.getUserDetailInfo()
            if (nav.isSuccess && nav.data?.isLogin == true) {
                sessionStore.updateUserSession(nav.data)
                store.putLongAsync(KEY_SESSION_HEALTH_CHECK_TS, now)
                AppLog.i(TAG, "checkSessionHealth: valid session mid=${nav.data?.mid}")
                SessionHealthResult.LOGGED_IN
            } else {
                AppLog.w(
                    TAG,
                    "checkSessionHealth: cookie rejected (isLogin=false), attempting recovery"
                )
                val recovered = tryRecoverExpiredSession()
                store.putLongAsync(KEY_SESSION_HEALTH_CHECK_TS, now)
                if (recovered) SessionHealthResult.RECOVERED else SessionHealthResult.EXPIRED_CLEARED
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // 网络失败不落检查时间戳，下次启动还有机会查
            AppLog.w(TAG, "checkSessionHealth: network error, keep session (${e.message})")
            SessionHealthResult.NETWORK_ERROR
        }
    }

    private fun notifySessionChanged() {
        runCatching {
            KoinPlatform.getKoin().get<com.mytvb.event.AppEventHub>()
        }.getOrNull()
            ?.dispatch(com.mytvb.event.AppEventHub.Event.UserSessionChanged)
    }

    private fun resetSessionLifecycleState(clearCookies: Boolean, reason: String) {
        if (clearCookies) {
            internalCookieManager.clearCookies()
            clearRefreshToken()
            clearAppToken()
        }
        securityCoordinator.resetRuntimeState()
    }

    private fun getRefreshToken(): String? {
        return runCatching {
            KoinPlatform.getKoin().get<AppSettingsDataStore>().getCachedString(KEY_REFRESH_TOKEN)
                ?.let(com.mytvb.network.security.SessionStorageCipher::decrypt)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun saveRefreshToken(token: String) {
        runCatching {
            val stored = com.mytvb.network.security.SessionStorageCipher.encrypt(token) ?: token
            KoinPlatform.getKoin().get<AppSettingsDataStore>().putStringAsync(KEY_REFRESH_TOKEN, stored)
        }.onFailure {
            AppLog.e(TAG, "saveRefreshToken failed: ${it.message}")
        }
    }

    private fun clearRefreshToken() {
        runCatching {
            KoinPlatform.getKoin().get<AppSettingsDataStore>().putStringAsync(KEY_REFRESH_TOKEN, null)
        }
    }

    fun clearWebRefreshToken() {
        clearRefreshToken()
    }

    /**
     * app 端 access_token（TV 扫码登录的 token_info 下发），过期或缺失返回空串。
     * 供 app 侧接口（x/v2/feed/index 等）作为 access_key 使用。
     */
    fun getAppAccessToken(): String {
        return runCatching {
            val store = KoinPlatform.getKoin().get<AppSettingsDataStore>()
            val expiresAt = store.getCachedLong(KEY_APP_TOKEN_EXPIRES_AT)
            if (expiresAt in 1 until System.currentTimeMillis()) {
                AppLog.i(TAG, "app access_token expired at $expiresAt, ignore")
                ""
            } else {
                store.getCachedString(KEY_APP_ACCESS_TOKEN)
                    ?.let(com.mytvb.network.security.SessionStorageCipher::decrypt)
                    .orEmpty()
            }
        }.getOrNull().orEmpty()
    }

    fun saveAppToken(accessToken: String, refreshToken: String, expiresInSec: Long) {
        runCatching {
            val store = KoinPlatform.getKoin().get<AppSettingsDataStore>()
            // 留 1 天提前量，避免拿临界有效 token 打请求
            val usableMs = (expiresInSec - 24L * 60 * 60) * 1000
            store.putStringAsync(
                KEY_APP_ACCESS_TOKEN,
                com.mytvb.network.security.SessionStorageCipher.encrypt(accessToken) ?: accessToken
            )
            store.putStringAsync(
                KEY_APP_REFRESH_TOKEN,
                com.mytvb.network.security.SessionStorageCipher.encrypt(refreshToken) ?: refreshToken
            )
            store.putLongAsync(
                KEY_APP_TOKEN_EXPIRES_AT,
                if (usableMs > 0) System.currentTimeMillis() + usableMs else 0L
            )
        }.onFailure {
            AppLog.e(TAG, "saveAppToken failed: ${it.message}")
        }
    }

    private fun clearAppToken() {
        runCatching {
            val store = KoinPlatform.getKoin().get<AppSettingsDataStore>()
            store.putStringAsync(KEY_APP_ACCESS_TOKEN, null)
            store.putStringAsync(KEY_APP_REFRESH_TOKEN, null)
            store.putLongAsync(KEY_APP_TOKEN_EXPIRES_AT, 0L)
        }
    }

    suspend fun activateAfterLogin() {
        securityCoordinator.activateAfterLogin()
        // 登录 cookie 已写入（扫码流程），立即从 LoggedOut 对齐到 CookieOnly，
        // 不必等首次 nav 请求返回
        sessionStore.refreshSessionState()
    }

    suspend fun ensureWebFingerprintCookies() {
        securityCoordinator.ensureWebFingerprintCookies()
    }

    fun saveLoginRefreshToken(token: String) {
        saveRefreshToken(token)
    }

    fun getOkHttpClient(): OkHttpClient = internalOkHttpClient

    /** 当前登录用户 mid（cookie DedeUserID）；未登录返回 null。 */
    fun currentMid(): Long? =
        internalCookieManager.getCookieValue("DedeUserID")
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it > 0L }

    /** 是否持有有效登录会话（SESSDATA）。 */
    fun hasLoginSession(): Boolean = internalCookieManager.hasSessionCookie()

    fun getCurrentUserAgent(): String = currentUserAgentValue

    fun getAcceptLanguage(): String {
        return userAgentStore.getAcceptLanguage()
    }

    fun refreshUserAgent(): String {
        val newUserAgent = userAgentStore.refreshUserAgent(appContext)
        return newUserAgent
    }

    suspend fun ensureHealthyForPlay() {
        securityCoordinator.ensureHealthyForPlay()
    }

    suspend fun forceCookieRefresh() {
        securityCoordinator.forceCookieRefresh()
    }

    suspend fun prewarmWebSession(forceUaRefresh: Boolean = false): Boolean {
        return securityCoordinator.prewarmWebSession(forceUaRefresh)
    }

    fun buildPiliWebHeaders(targetUrl: String, includeCookie: Boolean = true): Map<String, String> {
        return securityCoordinator.buildPiliWebHeaders(targetUrl, includeCookie)
    }

    suspend fun ensureWbiKeys() {
        securityCoordinator.ensureWbiKeys()
    }

    fun getUserInfo(): UserDetailInfoModel? {
        return sessionStore.getUserInfo()
    }

    fun updateUserSession(info: UserDetailInfoModel?) {
        val wasActive = sessionStore.isSessionActive()
        sessionStore.updateUserSession(info)
        if (!wasActive && sessionStore.isSessionActive()) {
            // 登录成功（扫码后首次拉到用户信息）也会经此通知，登录页不再手动 dispatch
            notifySessionChanged()
        }
    }

    fun syncUserSession(
        response: BaseResponse<UserDetailInfoModel>,
        source: String,
        context: AuthContext = AuthContext.FOREGROUND
    ): UserDetailInfoModel? {
        val wasActive = sessionStore.isSessionActive()
        val info = sessionStore.syncUserSession(response, context) {
            softClearUserSession(reason = "$source code=${response.code}")
        }
        if (info != null && !wasActive) {
            notifySessionChanged()
        }
        return info
    }

    fun handleAuthFailureCode(code: Int, source: String) {
        sessionStore.handleAuthFailureCode(code) {
            softClearUserSession(reason = "$source code=$code")
        }
    }

    fun <T> syncAuthState(
        response: BaseResponse<T>,
        source: String,
        context: AuthContext = AuthContext.FOREGROUND
    ): BaseResponse<T> {
        return sessionStore.syncAuthState(response, context) {
            softClearUserSession(reason = "$source code=${response.code}")
        }
    }

    fun syncAuthState(
        response: BaseBaseResponse,
        source: String,
        context: AuthContext = AuthContext.FOREGROUND
    ): BaseBaseResponse {
        return sessionStore.syncAuthState(response, context) {
            softClearUserSession(reason = "$source code=${response.code}")
        }
    }

    fun <T> syncAuthState(
        response: Base2Response<T>,
        source: String,
        context: AuthContext = AuthContext.FOREGROUND
    ): Base2Response<T> {
        return sessionStore.syncAuthState(response, context) {
            softClearUserSession(reason = "$source code=${response.code}")
        }
    }

    suspend fun postFormJson(
        url: String,
        form: Map<String, String>,
        extraHeaders: Map<String, String>? = null
    ): JSONObject {
        return securityCoordinator.postFormJson(url, form, extraHeaders)
    }

    suspend fun requestJson(
        url: String,
        extraHeaders: Map<String, String>? = null
    ): JSONObject {
        return securityCoordinator.requestJson(url, extraHeaders)
    }
}

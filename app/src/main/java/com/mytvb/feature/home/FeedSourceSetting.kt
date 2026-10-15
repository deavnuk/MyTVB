package com.mytvb.feature.home

import com.mytvb.core.common.settings.AppSettingsDataStore
import org.koin.mp.KoinPlatform

/**
 * 首页推荐 / 热门的数据来源设置（存储值中文，永不改动，显示走 SettingLabels 三语映射）。
 * APP 端 = app.bilibili.com 接口（推荐 x/v2/feed/index 需 access_token，热门 x/v2/show/popular 免登录）；
 * 网页端 = api.bilibili.com web 接口。推荐在 APP 端但缺 token 时自动回退网页端。
 */
object FeedSourceSetting {
    const val KEY_HOME_FEED_SOURCE = "home_feed_source"
    const val KEY_HOT_FEED_SOURCE = "hot_feed_source"
    const val VALUE_APP = "APP端"
    const val VALUE_WEB = "网页端"
    const val DEFAULT = VALUE_APP

    val OPTIONS = arrayOf(VALUE_APP, VALUE_WEB)

    fun isAppSource(key: String): Boolean {
        val stored = runCatching {
            KoinPlatform.getKoin().get<AppSettingsDataStore>().getCachedString(key)
        }.getOrNull()
        return stored?.let { it == VALUE_APP } ?: (DEFAULT == VALUE_APP)
    }
}

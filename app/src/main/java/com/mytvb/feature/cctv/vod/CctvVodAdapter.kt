package com.mytvb.feature.cctv.vod

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.log.VideoCardPerfLogger
import com.mytvb.core.ui.base.BaseVideoAdapter
import com.mytvb.core.ui.focus.VideoCardFocusHelper
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.ui.video.VideoCardViews
import com.mytvb.core.ui.video.VideoLightCardFactory
import com.mytvb.ui.adapter.VideoAdapter

/** 央视片库卡片列表（复用全 App 统一卡片：封面 + 标题 + 副标题）。 */
class CctvVodAdapter(
    private val onItemClick: (CctvAlbum) -> Unit
) : BaseVideoAdapter<CctvAlbum, CctvVodAdapter.ViewHolder>() {

    override fun itemKey(item: CctvAlbum): String = item.id

    /** 卡片顶行再往上时的落点（回到筛选行）。 */
    fun bindTopEdgeUp(callback: () -> Boolean) {
        onTopEdgeUp = callback
    }

    /** 焦点落到卡片时的回调，交由 [com.mytvb.core.ui.focus.tv.TvListFocusController] 记锚点/触发加载更多。 */
    fun setOnItemFocusListeners(
        onFocused: (Int) -> Unit,
        onFocusedWithView: (View, Int) -> Unit
    ) {
        onItemFocused = onFocused
        onItemFocusedWithView = onFocusedWithView
    }

    override fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val views = VideoCardPerfLogger.measureInflate("CctvVodAdapter.light") {
            VideoLightCardFactory.create(parent, source = "CctvVodAdapter.light")
        }
        return ViewHolder(views)
    }

    override fun onBindContentViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let(holder::bind)
    }

    inner class ViewHolder(
        private val views: VideoCardViews
    ) : RecyclerView.ViewHolder(views.root) {

        init {
            views.imageView.clipToOutline = true
            views.imageView.outlineProvider =
                VideoAdapter.VideoViewHolder.coverOutlineProviderFor(views.imageView.resources)
            views.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    getItem(position)?.let(onItemClick)
                }
            }
            views.root.onFocusChangeListener = View.OnFocusChangeListener { view, hasFocus ->
                if (!hasFocus) return@OnFocusChangeListener
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@OnFocusChangeListener
                onItemFocused?.invoke(position)
                onItemFocusedWithView?.invoke(view, position)
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = views.root,
                onTopEdgeUp = { onTopEdgeUp?.invoke() == true }
            )        }

        fun bind(album: CctvAlbum) {
            views.textLayer.setTitle(album.title, lines = 2)
            views.textLayer.setOwner(
                ownerText = buildSubtitle(album),
                showAvatar = false,
                show = true
            )
            views.textLayer.clearHistoryTrailing()
            views.progressBar.visibility = View.GONE
            views.coverMetaOverlay.visibility = View.GONE
            views.imageView.clearColorFilter()
            ImageLoader.load(
                imageView = views.imageView,
                url = album.coverUrl,
                placeholder = R.drawable.default_video,
                error = R.drawable.default_video
            )
        }

        private fun buildSubtitle(album: CctvAlbum): String {
            val context = views.root.context
            val episodeText = if (album.episodeCount > 0) {
                context.getString(R.string.cctv_vod_episode_count_format, album.episodeCount)
            } else {
                context.getString(R.string.cctv_vod_episode_no_count)
            }
            return listOf(album.year, album.genre, episodeText)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        }
    }
}

package com.mytvb.feature.cctv.vod

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.databinding.DialogCctvVodDetailBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 影视详情浮层：剧集信息 + 分集列表，点某一集直接交给播放器。
 * 分集来自央视详情页 HTML（服务端渲染的全集列表）。
 */
class CctvVodDetailDialog(
    context: Context,
    private val album: CctvAlbum,
    private val api: CctvVodApi,
    private val onPlayEpisode: (CctvEpisode, String) -> Unit
) : AppCompatDialog(context, R.style.DialogTheme) {

    private lateinit var binding: DialogCctvVodDetailBinding
    private val episodeAdapter = EpisodeAdapter(::handleEpisodeClick)
    private var loadJob: Job? = null
    // 弹窗生命周期自带的作用域：Fragment 传进来的 context 可能不是 LifecycleOwner
    // （被 ContextThemeWrapper 包装），不能像 Fragment 那样直接用宿主 lifecycleScope。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var dismissed = false
    private var loadStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogCctvVodDetailBinding.inflate(layoutInflater)
        DialogWindowFit.overlay(
            dialog = this,
            content = binding.root,
            widthPx = context.resources.getDimensionPixelSize(R.dimen.px1400),
            heightPx = context.resources.getDimensionPixelSize(R.dimen.px900),
            tag = "cctv_vod_detail"
        )
        bindAlbum()
        setOnShowListener {
            AppLog.i(TAG, "onShow isShowing=$isShowing")
            if (!loadStarted) {
                loadStarted = true
                bindEpisodes()
            }
        }
    }

    private fun bindAlbum() {
        binding.textTitle.text = album.title
        binding.textMeta.text = buildMeta()
        binding.textBrief.text = album.brief.ifBlank { album.actors }
        ImageLoader.load(
            imageView = binding.imageCover,
            url = album.coverUrl,
            placeholder = R.drawable.default_video,
            error = R.drawable.default_video
        )
        binding.recyclerView.layoutManager = GridLayoutManager(context, EPISODE_SPAN)
        binding.recyclerView.adapter = episodeAdapter
        binding.recyclerView.itemAnimator = null
    }

    private fun buildMeta(): String {
        val episodeText = if (album.episodeCount > 0) {
            context.getString(R.string.cctv_vod_episode_count_format, album.episodeCount)
        } else {
            context.getString(R.string.cctv_vod_episode_no_count)
        }
        return listOf(album.year, album.area, album.genre, episodeText)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    private fun bindEpisodes() {
        AppLog.i(TAG, "bindEpisodes start album=${album.id} mode=${album.episodeMode}")
        loadJob = scope.launch {
            val episodes = withContext(Dispatchers.IO) {
                api.fetchEpisodes(album.id, album.episodeMode)
            }
            AppLog.i(TAG, "episodeLoad done size=${episodes.size} dismissed=$dismissed")
            if (dismissed) return@launch
            onEpisodesReady(episodes)
        }
    }

    private fun onEpisodesReady(episodes: List<CctvEpisode>) {
        if (dismissed) return
        if (episodes.isEmpty()) {
            binding.textEpisodeHint.text = context.getString(R.string.cctv_vod_load_failed)
            return
        }
        binding.textEpisodeHint.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        episodeAdapter.submit(episodes)
        binding.recyclerView.post {
            if (dismissed) return@post
            binding.recyclerView.findViewHolderForAdapterPosition(0)
                ?.itemView?.requestFocus()
        }
    }

    private fun handleEpisodeClick(episode: CctvEpisode) {
        val title = episode.title.ifBlank {
            context.getString(R.string.cctv_vod_episode_title_format, episode.index)
        }
        dismiss()
        onPlayEpisode(episode, title)
    }

    override fun dismiss() {
        dismissed = true
        loadJob?.cancel()
        loadJob = null
        scope.cancel()
        super.dismiss()
    }

    private class EpisodeAdapter(
        private val onClick: (CctvEpisode) -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

        private val items = ArrayList<CctvEpisode>()

        fun submit(list: List<CctvEpisode>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(createItemView(parent))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], position)
        }

        private fun createItemView(parent: ViewGroup): AppCompatTextView {
            val context = parent.context
            val margin = context.resources.getDimensionPixelSize(R.dimen.px5)
            return AppCompatTextView(context).apply {
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    context.resources.getDimension(R.dimen.px30)
                )
                setTextColor(episodeTextColors(context))
                setBackgroundResource(R.drawable.cell_background)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.resources.getDimensionPixelSize(R.dimen.px80)
                ).apply {
                    marginStart = margin
                    marginEnd = margin
                    topMargin = margin
                    bottomMargin = margin
                }
            }
        }

        inner class ViewHolder(
            private val view: AppCompatTextView
        ) : RecyclerView.ViewHolder(view) {

            fun bind(episode: CctvEpisode, position: Int) {
                view.text = episode.index.toString().padStart(2, '0')
                view.contentDescription = episode.title
                view.setOnClickListener {
                    val current = bindingAdapterPosition
                    if (current != RecyclerView.NO_POSITION) {
                        onClick(items[current])
                    }
                }
            }
        }
    }

    companion object {
        private const val EPISODE_SPAN = 6
        private const val TAG = "CctvVodDetailDialog"

        fun show(
            context: Context,
            album: CctvAlbum,
            api: CctvVodApi,
            onPlayEpisode: (CctvEpisode, String) -> Unit
        ) {
            CctvVodDetailDialog(context, album, api, onPlayEpisode).show()
        }
    }
}

/** 集号文字色：选中=强调色，聚焦=主文字色，其余=次要文字色。 */
private fun episodeTextColors(context: Context): ColorStateList {
    val accent = ContextCompat.getColor(context, R.color.colorAccent)
    val primary = resolveThemeColor(context, android.R.attr.textColorPrimary)
    val secondary = resolveThemeColor(context, android.R.attr.textColorSecondary)
    return ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_selected),
            intArrayOf(android.R.attr.state_focused),
            intArrayOf()
        ),
        intArrayOf(accent, primary, secondary)
    )
}

private fun resolveThemeColor(context: Context, attr: Int): Int {
    val value = TypedValue()
    if (!context.theme.resolveAttribute(attr, value, true)) return android.graphics.Color.WHITE
    return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
}

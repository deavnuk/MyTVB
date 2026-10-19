package com.mytvb.feature.cctv.vod

import android.content.Intent
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.base.BaseFragment
import com.mytvb.core.ui.base.ChoiceDialog
import com.mytvb.core.ui.base.OnBackPressedHandler
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.core.ui.focus.tv.GridTvFocusStrategy
import com.mytvb.core.ui.focus.tv.TvListFocusController
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.databinding.FragmentCctvVodBinding
import com.mytvb.ui.activity.CctvVodPlayerActivity
import com.mytvb.ui.fragment.main.MainNavigationViewModel
import com.mytvb.ui.fragment.main.MainTabFocusTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import java.util.Calendar

/**
 * CCTV 点播片库：分类切换 + 类型/地区/年份/首字母筛选 + 卡片网格 + 触底翻页。
 * 点卡片进详情弹窗选剧集，选中的剧集交给 [CctvVodPlayerActivity] 播放。
 */
class CctvVodFragment : BaseFragment<FragmentCctvVodBinding>(),
    MainTabFocusTarget, OnBackPressedHandler {

    private val okHttpClient: OkHttpClient by inject()
    private val api: CctvVodApi by lazy { CctvVodApi(okHttpClient) }
    // 片库卡片是竖版海报（4:5），列数比全 App 默认的 16:9 网格多一档，
    // 保持与央视片库页 6 列一致的观感（超宽屏同比例放宽到 12 列）
    private val spanCount by lazy { resources.adaptiveSpanCount(base = 6, wide = 12) }
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()

    private lateinit var adapter: CctvVodAdapter
    private var tvFocusController: TvListFocusController? = null

    private var categoryIndex = 0
    private var filterGenre = ""
    private var filterArea = ""
    private var filterYear = ""
    private var filterLetter = ""

    private var page = 1
    private var loading = false
    private var endReached = false
    private var loadJob: Job? = null

    /** 最近一次进播放页的专辑 id：从播放页返回时把焦点放回那张卡片。 */
    private var lastLaunchedAlbumId: String? = null

    private val categoryChips = mutableListOf<AppCompatTextView>()
    private val filterChips = mutableListOf<AppCompatTextView>()

    private val currentCategory: CctvVodCategory
        get() = CctvVodCategories.ALL[categoryIndex]

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentCctvVodBinding = FragmentCctvVodBinding.inflate(inflater, container, false)

    override fun useLightBaseContainer(): Boolean = true

    override fun initView() {
        adapter = CctvVodAdapter(onItemClick = ::openAlbumDetail)
        adapter.bindTopEdgeUp(::focusFilterRow)
        binding.recyclerView.layoutManager = WrapContentGridLayoutManager(requireContext(), spanCount)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        adapter.setShowLoadMore(false)
        binding.recyclerView.addItemDecoration(
            GridSpacingItemDecoration(
                spanCount,
                resources.getDimensionPixelSize(R.dimen.px20),
                true
            )
        )
        adapter.setOnItemFocusListeners(
            onFocused = { position ->
                if (position >= adapter.contentCount() - LOAD_MORE_THRESHOLD) {
                    loadPage()
                }
            },
            onFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
            }
        )
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lastVisible = (recyclerView.layoutManager as? LinearLayoutManager)
                    ?.findLastVisibleItemPosition() ?: return
                if (lastVisible >= adapter.contentCount() - LOAD_MORE_THRESHOLD) {
                    loadPage()
                }
            }
        })
        installTvFocusController()
        buildCategoryRow()
        buildFilterRow()
    }

    private fun installTvFocusController() {
        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerView,
            adapter = adapter,
            strategy = GridTvFocusStrategy { spanCount },
            canLoadMore = { !endReached },
            loadMore = { loadPage() },
            debugName = "cctv_vod"
        )
    }

    override fun initData() {
        reload()
    }

    /** 重选本 tab / 菜单键：回顶并聚焦第一张卡片（与分区页一致的刷新语义）。 */
    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    if (isHidden) return@collectLatest
                    when (event) {
                        is MainNavigationViewModel.Event.MainTabReselected ->
                            if (event.index == TAB_INDEX) scrollToTopAndFocus()
                        MainNavigationViewModel.Event.MenuPressed -> scrollToTopAndFocus()
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun scrollToTopAndFocus() {
        binding.recyclerView.stopScroll()
        binding.recyclerView.scrollToPosition(0)
        binding.recyclerView.post {
            if (isAdded) focusPrimaryCard()
        }
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        tvFocusController?.release()
        tvFocusController = null
        super.onDestroyView()
    }

    // ---------------------------------------------------------------- 分类 / 筛选

    private fun buildCategoryRow() {
        binding.layoutCategories.removeAllViews()
        categoryChips.clear()
        CctvVodCategories.ALL.forEachIndexed { index, category ->
            val chip = createChip(category.title) { switchCategory(index) }
            chip.setOnKeyListener { _, keyCode, event ->
                handleChipKey(keyCode, event, categoryRow = true)
            }
            categoryChips.add(chip)
            binding.layoutCategories.addView(chip)
        }
        updateCategorySelection()
    }

    private fun buildFilterRow() {
        binding.layoutFilters.removeAllViews()
        filterChips.clear()
        val today = Calendar.getInstance().get(Calendar.YEAR)
        val specs = listOf(
            FilterSpec(R.string.cctv_vod_filter_genre, CctvVodFilters.GENRES,
                { filterGenre }, { filterGenre = it }),
            FilterSpec(R.string.cctv_vod_filter_area, CctvVodFilters.AREAS,
                { filterArea }, { filterArea = it }),
            FilterSpec(R.string.cctv_vod_filter_year, CctvVodFilters.years(today),
                { filterYear }, { filterYear = it }),
            FilterSpec(R.string.cctv_vod_filter_letter, CctvVodFilters.LETTERS,
                { filterLetter }, { filterLetter = it })
        )
        specs.forEach { spec ->
            val chip = createChip(getString(spec.labelRes)) {
                showFilterDialog(spec.labelRes, spec.options, spec.current(), spec.apply)
            }
            chip.setOnKeyListener { _, keyCode, event ->
                handleChipKey(keyCode, event, categoryRow = false)
            }
            filterChips.add(chip)
            binding.layoutFilters.addView(chip)
        }
        updateFilterLabels()
    }

    private fun createChip(text: String, onClick: () -> Unit): AppCompatTextView {
        return AppCompatTextView(requireContext()).apply {
            this.text = text
            setTextColor(chipTextColors())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px28))
            setPadding(
                resources.getDimensionPixelSize(R.dimen.px20),
                resources.getDimensionPixelSize(R.dimen.px10),
                resources.getDimensionPixelSize(R.dimen.px20),
                resources.getDimensionPixelSize(R.dimen.px10)
            )
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.tab_item_round_background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = resources.getDimensionPixelSize(R.dimen.px12)
                topMargin = resources.getDimensionPixelSize(R.dimen.px8)
                bottomMargin = resources.getDimensionPixelSize(R.dimen.px8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun chipTextColors(): ColorStateList {
        val context = requireContext()
        val accent = ContextCompat.getColor(context, R.color.colorAccent)
        val primary = resolveThemeColor(android.R.attr.textColorPrimary)
        val secondary = resolveThemeColor(android.R.attr.textColorSecondary)
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_selected),
                intArrayOf(android.R.attr.state_focused),
                intArrayOf()
            ),
            intArrayOf(accent, primary, secondary)
        )
    }

    private fun resolveThemeColor(attr: Int): Int {
        val value = TypedValue()
        if (!requireContext().theme.resolveAttribute(attr, value, true)) return 0
        return if (value.resourceId != 0) ContextCompat.getColor(requireContext(), value.resourceId)
        else value.data
    }

    private fun handleChipKey(keyCode: Int, event: KeyEvent, categoryRow: Boolean): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN ->
                if (categoryRow) focusFilterRow() else focusPrimaryCard()
            KeyEvent.KEYCODE_DPAD_UP ->
                if (categoryRow) true else focusCategoryRow()
            else -> false
        }
    }

    private fun updateCategorySelection() {
        categoryChips.forEachIndexed { index, chip -> chip.isSelected = index == categoryIndex }
    }

    private fun updateFilterLabels() {
        val labels = listOf(
            getString(R.string.cctv_vod_filter_genre) to filterGenre,
            getString(R.string.cctv_vod_filter_area) to filterArea,
            getString(R.string.cctv_vod_filter_year) to filterYear,
            getString(R.string.cctv_vod_filter_letter) to filterLetter
        )
        labels.forEachIndexed { index, (label, value) ->
            val chip = filterChips.getOrNull(index) ?: return@forEachIndexed
            chip.text = if (value.isBlank()) label else "$label：$value"
            chip.isSelected = value.isNotBlank()
        }
    }

    private fun switchCategory(index: Int) {
        if (index == categoryIndex) return
        categoryIndex = index
        // 各分类的筛选选项不通用（"谍战"之于动画片必然无结果），切分类时一并清空筛选
        filterGenre = ""
        filterArea = ""
        filterYear = ""
        filterLetter = ""
        updateCategorySelection()
        updateFilterLabels()
        reload()
    }

    private fun showFilterDialog(
        labelRes: Int,
        options: List<String>,
        current: String,
        apply: (String) -> Unit
    ) {
        val selectedIndex = options.indexOf(current).coerceAtLeast(0)
        ChoiceDialog.show(
            context = requireContext(),
            title = getString(R.string.cctv_vod_filter_title_format, getString(labelRes)),
            options = options,
            selectedIndex = selectedIndex
        ) { index ->
            val picked = options.getOrNull(index) ?: return@show
            apply(CctvVodFilters.valueOf(picked))
            updateFilterLabels()
            reload()
        }
    }

    // ---------------------------------------------------------------- 数据加载

    private fun reload() {
        loadJob?.cancel()
        loadJob = null
        loading = false
        page = 1
        endReached = false
        adapter.clear()
        adapter.notifyDataSetChanged()
        binding.textEmpty.visibility = View.GONE
        binding.recyclerView.scrollToPosition(0)
        loadPage()
    }

    private fun loadPage() {
        if (loading || endReached || !isAdded) return
        loading = true
        if (adapter.contentCount() == 0) {
            binding.loadingProgress.visibility = View.VISIBLE
        }
        val targetPage = page
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val albums = withContext(Dispatchers.IO) {
                api.fetchAlbums(
                    category = currentCategory,
                    genre = filterGenre,
                    area = filterArea,
                    year = filterYear,
                    letter = filterLetter,
                    page = targetPage
                )
            }
            if (!isAdded || view == null) return@launch
            loading = false
            binding.loadingProgress.visibility = View.GONE
            if (albums.isEmpty()) {
                endReached = true
            } else {
                adapter.addData(albums)
                page = targetPage + 1
                tvFocusController?.onDataChanged()
            }
            binding.textEmpty.visibility =
                if (adapter.contentCount() == 0 && endReached) View.VISIBLE else View.GONE
        }
    }

    private fun openAlbumDetail(album: CctvAlbum) {
        CctvVodDetailDialog.show(
            context = requireContext(),
            album = album,
            api = api,
            onPlayEpisode = { episode, title ->
                lastLaunchedAlbumId = album.id
                startActivity(
                    CctvVodPlayerActivity.intentForEpisode(
                        context = requireContext(),
                        episode = episode,
                        title = title,
                        albumId = album.id,
                        episodeMode = album.episodeMode
                    )
                )
            }
        )
    }

    // ---------------------------------------------------------------- 焦点

    private fun focusCategoryRow(): Boolean {
        val target = categoryChips.getOrNull(categoryIndex) ?: return false
        return target.requestFocus()
    }

    private fun focusFilterRow(): Boolean {
        val target = filterChips.firstOrNull { it.isSelected }
            ?: filterChips.firstOrNull()
            ?: return focusPrimaryCard()
        return target.requestFocus()
    }

    private fun focusPrimaryCard(): Boolean {
        if (adapter.contentCount() == 0) return false
        binding.recyclerView.scrollToPosition(0)
        val layoutManager = binding.recyclerView.layoutManager ?: return false
        val visible = layoutManager.findViewByPosition(0)
        if (visible != null) return visible.requestFocus()
        binding.recyclerView.post {
            if (!isAdded) return@post
            binding.recyclerView.layoutManager?.findViewByPosition(0)?.requestFocus()
        }
        return true
    }

    /**
     * 从播放页返回时把焦点放回进入前那张卡片：否则焦点会落在侧边栏或列表首项，
     * 用户要继续挑下一部得重新找位置。
     */
    override fun onResume() {
        super.onResume()
        val albumId = lastLaunchedAlbumId ?: return
        lastLaunchedAlbumId = null
        if (view == null) return
        binding.recyclerView.post {
            if (!isAdded) return@post
            val position = (0 until adapter.contentCount())
                .firstOrNull { adapter.getItem(it)?.id == albumId }
            val layoutManager = binding.recyclerView.layoutManager
            if (position == null || layoutManager == null) {
                AppLog.w(TAG, "restoreFocus albumId=$albumId notFound position=$position")
                return@post
            }
            layoutManager.scrollToPosition(position)
            binding.recyclerView.post {
                if (!isAdded) return@post
                val focused = layoutManager.findViewByPosition(position)?.requestFocus() ?: false
                AppLog.i(TAG, "restoreFocus albumId=$albumId position=$position focused=$focused")
            }
        }
    }

    override fun focusEntryFromMainTab(): Boolean = focusPrimaryCard() || focusCategoryRow()

    override fun focusEntryFromMainTab(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        return focusPrimaryCard()
    }

    override fun onBackPressed(): Boolean {
        val focused = view?.findFocus() ?: return false
        if (isInsideRecyclerView(focused)) {
            return focusCategoryRow()
        }
        if (categoryChips.any { it === focused } || filterChips.any { it === focused }) {
            return mainActivity?.focusSidebarCurrentTab() == true
        }
        return false
    }

    private fun isInsideRecyclerView(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === binding.recyclerView) return true
            current = current.parent as? View
        }
        return false
    }

    private fun px(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val LOAD_MORE_THRESHOLD = 4
        private const val TAB_INDEX = 5
        private const val TAG = "CctvVodFragment"

        fun newInstance(): CctvVodFragment = CctvVodFragment()
    }
}

private data class FilterSpec(
    val labelRes: Int,
    val options: List<String>,
    val current: () -> String,
    val apply: (String) -> Unit
)

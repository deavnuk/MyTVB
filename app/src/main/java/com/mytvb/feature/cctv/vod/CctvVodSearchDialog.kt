package com.mytvb.feature.cctv.vod

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatDialog
import com.mytvb.R
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.databinding.DialogCctvVodSearchBinding
import com.mytvb.feature.search.view.KeyboardView

/**
 * 片库搜索弹窗：关键词输入走双通道——系统输入法打中文（EditText），
 * 或复用搜索页的 [KeyboardView] 自绘键盘敲拼音（配合标题拼音匹配）。
 * 关键词每变化一次就回调宿主实时过滤列表；确认/返回 = 关闭弹窗保留结果。
 */
class CctvVodSearchDialog(
    context: Context,
    val initialQuery: String,
    private val onQueryChanged: (String) -> Unit
) : AppCompatDialog(context, R.style.DialogTheme), KeyboardView.KeySelectListener {

    private lateinit var binding: DialogCctvVodSearchBinding
    private var query = initialQuery

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogCctvVodSearchBinding.inflate(layoutInflater)
        DialogWindowFit.overlay(
            dialog = this,
            content = binding.root,
            widthPx = context.resources.getDimensionPixelSize(R.dimen.px1100),
            // T9 键为双行文字（"2/ABC"），每键需要 ~85dip 高：弹窗矮了键位文字会被裁半。
            // 给足设计高度，低分屏由 DialogWindowFit 钳到 92% 屏高兜底
            heightPx = context.resources.getDimensionPixelSize(R.dimen.px1100),
            tag = "cctv_vod_search"
        )
        // 先填初始词再挂监听，避免重复触发回调
        if (initialQuery.isNotEmpty()) {
            binding.editQuery.setText(initialQuery)
            binding.editQuery.setSelection(initialQuery.length)
        }
        binding.viewKeyboard.setKeySelectListener(this)
        // 自绘键盘输入同步进输入框；输入框自身的文本变化（系统输入法/删除）统一走监听。
        // showSoftInputOnFocus=false：聚焦不自动弹系统键盘，想打中文走「输入法」按钮
        binding.editQuery.showSoftInputOnFocus = false
        binding.editQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val next = s?.toString().orEmpty()
                if (next == query) return
                query = next
                onQueryChanged(query.trim())
            }
        })
        binding.editQuery.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                dismiss()
                true
            } else {
                false
            }
        }
        binding.buttonIme.setOnClickListener {
            binding.editQuery.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(binding.editQuery, 0)
        }
        binding.buttonConfirm.setOnClickListener { dismiss() }
        binding.buttonClear.setOnClickListener {
            binding.editQuery.setText("")
            binding.viewKeyboard.requestFocus()
        }
        setOnShowListener {
            // 键盘是懒构建的（requestPrimaryFocus 内部 ensure），不调它按键不渲染
            binding.viewKeyboard.requestPrimaryFocus()
            binding.editQuery.requestFocus()
        }
        // 项目开了 enableOnBackInvokedCallback：BACK 走 OnBackInvoked 通道，
        // 不注册到 ComponentDialog 自带 dispatcher 的话弹窗关不掉（OwnerDetailDialog 同款坑）
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                dismiss()
            }
        })
    }

    override fun onInsertText(text: String) {
        binding.editQuery.append(text)
    }

    override fun onDelete() {
        val text = binding.editQuery.text?.toString().orEmpty()
        if (text.isEmpty()) return
        binding.editQuery.setText(text.dropLast(1))
        binding.editQuery.setSelection(text.length - 1)
    }

    override fun onClear() {
        binding.editQuery.setText("")
        binding.viewKeyboard.requestFocus()
    }

    override fun onSearch() = dismiss()

    companion object {
        fun show(context: Context, initialQuery: String, onQueryChanged: (String) -> Unit) {
            CctvVodSearchDialog(context, initialQuery, onQueryChanged).show()
        }
    }
}

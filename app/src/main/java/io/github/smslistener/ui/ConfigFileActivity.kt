package io.github.smslistener.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.TextView
import io.github.smslistener.data.AppStore
import io.github.smslistener.data.ConfigFileManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Select one public document; the manager owns its permissions, I/O and configuration commits. */
class ConfigFileActivity : Activity() {
    private lateinit var store: AppStore
    private lateinit var manager: ConfigFileManager
    private lateinit var statusView: TextView
    private val controls = mutableListOf<View>()
    private var pendingRequest = 0
    private var busy = false
    private var disposed = false
    private var messageDialog: AlertDialog? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { if (!disposed && !isFinishing) refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppStore(this)
        manager = ConfigFileManager(applicationContext)
        pendingRequest = savedInstanceState?.getInt(KEY_PENDING_REQUEST)
            ?.takeIf { it == REQUEST_CREATE || it == REQUEST_OPEN } ?: 0
        val ui = UiKit(this)
        val content = EditorPage.create(this, "配置文件")
        val stateCard = ui.card(content)
        ui.section(stateCard, "同一个配置文件")
        statusView = ui.label("", 13, UiKit.SECONDARY).apply { tag = "config_file_status" }
        stateCard.addView(statusView)
        ui.gap(stateCard, 14)
        stateCard.addView(ui.label(
            "配置修改会写入同一个文件。覆盖安装继续引用；卸载后文件保留，重装首次重新选择它即可自动加载。" +
                "请选择 Documents 等公共目录，不放 Android/data。", 13, UiKit.SECONDARY))

        val actions = ui.card(content)
        val create = ui.button("创建配置文件") { chooseCreate() }.apply { tag = "create_config_file" }
        val reference = ui.button("引用已有配置文件") { chooseExisting() }.apply { tag = "reference_config_file" }
        controls += create
        controls += reference
        actions.addView(create)
        ui.gap(actions, 12)
        actions.addView(reference)
        ui.gap(actions, 14)
        actions.addView(ui.label("创建会写入当前完整配置。引用已有文件会使用其中的规则和转发目标，并替换当前配置。", 12, UiKit.SECONDARY))
        ui.gap(content, 8)
        content.addView(ui.label("文件可能含转发密钥，请妥善保管。系统权限和自定义音乐访问权限需在重装后重新确认。", 12, UiKit.MUTED))
        refresh()
    }

    override fun onStart() {
        super.onStart()
        store.observe(listener)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // opened schedules I/O; preference notifications refresh the eventual result on the UI thread.
        runCatching { ConfigFileManager.opened(applicationContext) }
            .onFailure { message("操作未完成", ConfigFileManager.messageFor(it)) }
        refresh()
    }

    override fun onStop() {
        store.removeObserver(listener)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_PENDING_REQUEST, pendingRequest)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        disposed = true
        messageDialog?.dismiss()
        messageDialog = null
        // In-flight document work belongs to the manager and must finish even after rotation.
        super.onDestroy()
    }

    private fun refresh() {
        if (!::statusView.isInitialized || disposed || isDestroyed) return
        val status = manager.status()
        statusView.text = buildString {
            if (status.uri == null) append("未引用配置文件")
            else {
                append("文件：")
                append(status.name.ifBlank { "已选择的配置文件" })
                append("\n状态：")
                append(when {
                    status.error.isNotBlank() -> "错误"
                    status.pending -> "待写入"
                    status.lastSuccess > 0L -> "已同步"
                    else -> "待同步"
                })
            }
            if (status.pending && status.error.isNotBlank()) append("\n最新修改待写入")
            if (status.error.isNotBlank()) append("\n").append(status.error)
            if (status.lastSuccess > 0L) {
                append("\n最近同步：")
                append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(status.lastSuccess)))
            }
            if (busy) append("\n正在处理，请稍候…")
        }
        controls.forEach { it.isEnabled = !busy && pendingRequest == 0 }
    }

    private fun chooseCreate() = launchPicker(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "application/json"
        putExtra(Intent.EXTRA_TITLE, "短信哨兵.json")
        addFlags(DOCUMENT_FLAGS)
    }, REQUEST_CREATE)

    private fun chooseExisting() = launchPicker(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/*", "application/octet-stream"))
        addFlags(DOCUMENT_FLAGS)
    }, REQUEST_OPEN)

    private fun launchPicker(intent: Intent, request: Int) {
        if (busy || pendingRequest != 0 || disposed || isFinishing) return
        pendingRequest = request
        refresh()
        try {
            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents"))
            startActivityForResult(intent, request)
        } catch (_: RuntimeException) {
            pendingRequest = 0
            refresh()
            message("文件选择器未打开", "请启用系统文件管理器后重试。")
        }
    }

    @Suppress("DEPRECATION")
    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (disposed || pendingRequest == 0 || requestCode != pendingRequest) return
        // Consume once before dispatching I/O, including cancellation and malformed results.
        pendingRequest = 0
        refresh()
        if (resultCode != RESULT_OK) return
        val uri = data?.data
        if (uri == null) {
            message("未选择文件", "请重新选择配置文件。")
            return
        }
        when (requestCode) {
            REQUEST_CREATE -> io({ manager.create(uri) }) {
                message("配置文件已创建", "后续配置修改会自动写入这个文件。")
            }
            REQUEST_OPEN -> io({ manager.reference(uri) }) { result ->
                message("已引用配置文件", "文件中的规则和转发目标已加载。" +
                    if (result.alarmStopped) "" else "\n提醒尚未停止，请在首页手动停止。")
            }
        }
    }

    private fun <T> io(task: () -> T, success: (T) -> Unit) {
        if (busy || disposed || isFinishing) return
        busy = true
        refresh()
        try {
            ConfigFileManager.runIo {
                val result = runCatching(task)
                runOnUiThread {
                    if (!disposed && !isFinishing && !isDestroyed) {
                        busy = false
                        refresh()
                        result.fold(success) { message("操作未完成", ConfigFileManager.messageFor(it)) }
                    }
                }
            }
        } catch (error: RuntimeException) {
            busy = false
            refresh()
            message("操作未完成", ConfigFileManager.messageFor(error))
        }
    }

    private fun message(title: String, detail: String) {
        if (disposed || isFinishing || isDestroyed) return
        messageDialog?.dismiss()
        messageDialog = AlertDialog.Builder(this).setTitle(title).setMessage(detail)
            .setPositiveButton("知道了", null).show()
    }

    companion object {
        const val REQUEST_CREATE = 5301
        const val REQUEST_OPEN = 5302
        private const val KEY_PENDING_REQUEST = "config_file_pending_request"
        private const val DOCUMENT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    }
}

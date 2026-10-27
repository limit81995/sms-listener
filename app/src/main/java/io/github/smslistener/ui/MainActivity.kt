package io.github.smslistener.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.smslistener.BuildConfig
import io.github.smslistener.R
import io.github.smslistener.action.ActionExecutor
import io.github.smslistener.alarm.AlarmController
import io.github.smslistener.alarm.AlarmNotifications
import io.github.smslistener.alarm.AlarmService
import io.github.smslistener.alarm.AlarmSpec
import io.github.smslistener.core.AlarmRule
import io.github.smslistener.core.ForwardTarget
import io.github.smslistener.core.ForwardType
import io.github.smslistener.core.RuleMatch
import io.github.smslistener.core.RuleMatcher
import io.github.smslistener.core.SmsSubject
import io.github.smslistener.data.AppStore
import io.github.smslistener.data.ConfigFileManager
import io.github.smslistener.monitor.MonitorService
import io.github.smslistener.ui.SentinelIcon.Kind
import io.github.smslistener.ui.UiKit.Companion.ACCENT
import io.github.smslistener.ui.UiKit.Companion.AMBER
import io.github.smslistener.ui.UiKit.Companion.BACKGROUND
import io.github.smslistener.ui.UiKit.Companion.DARK
import io.github.smslistener.ui.UiKit.Companion.INK
import io.github.smslistener.ui.UiKit.Companion.LINE
import io.github.smslistener.ui.UiKit.Companion.MINT
import io.github.smslistener.ui.UiKit.Companion.MUTED
import io.github.smslistener.ui.UiKit.Companion.RED
import io.github.smslistener.ui.UiKit.Companion.RED_SOFT
import io.github.smslistener.ui.UiKit.Companion.SECONDARY
import io.github.smslistener.ui.UiKit.Companion.SOFT

class MainActivity : Activity() {
    private lateinit var store: AppStore
    private lateinit var ui: UiKit
    private lateinit var root: LinearLayout
    private lateinit var pageTitle: TextView
    private lateinit var pageSubtitle: TextView
    private lateinit var pageHost: FrameLayout
    private lateinit var alarmBanner: LinearLayout
    private lateinit var feedback: TextView

    private lateinit var heroTitle: TextView
    private lateinit var heroDescription: TextView
    private lateinit var heroStatus: TextView
    private lateinit var lastEvent: TextView
    private lateinit var statsLabel: TextView
    private lateinit var ruleListHost: LinearLayout
    private lateinit var ruleCountLabel: TextView
    private lateinit var configFileCard: LinearLayout
    private lateinit var configFileText: TextView

    private val navButtons = mutableListOf<LinearLayout>()
    private val pages = mutableListOf<ScrollView>()
    private var selectedTab = 0
    private var restoring = true
    private var testStarting = false
    private val handler = Handler(Looper.getMainLooper())
    private val preferencesChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        refreshStatus()
        if (key == "state_v2" || key == null) renderRules()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppStore(this)
        ui = UiKit(this)
        AlarmNotifications(this)

        // Monitoring is always on while the app is installed; the switch is gone by design.
        val state = store.state()
        if (!state.enabled) store.setEnabled(true)
        MonitorService.start(this)

        buildScreen()
        restoring = false
        selectTab(intent?.getIntExtra(EXTRA_TAB, -1)?.takeIf { it in 0..2 }
            ?: savedInstanceState?.getInt("tab") ?: 0, animate = false)
        pages.forEachIndexed { index, scroll ->
            scroll.post { scroll.scrollTo(0, savedInstanceState?.getInt("scroll$index") ?: 0) }
        }
        refreshStatus()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.getIntExtra(EXTRA_TAB, -1)?.takeIf { it in 0..2 }
            ?.let { selectTab(it, animate = false) }
    }

    override fun onStart() { super.onStart(); store.observe(preferencesChanged) }
    override fun onResume() { super.onResume(); refreshStatus(); renderRules(); ConfigFileManager.opened(this) }
    override fun onStop() { store.removeObserver(preferencesChanged); super.onStop() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", selectedTab)
        pages.forEachIndexed { index, scroll -> outState.putInt("scroll$index", scroll.scrollY) }
        super.onSaveInstanceState(outState)
    }

    private fun buildScreen() = with(ui) {
        root = column().apply { setBackgroundColor(BACKGROUND); isFocusableInTouchMode = true }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val header = row().apply { setPadding(dp(24), dp(18), dp(24), dp(14)) }
        val heading = column()
        pageTitle = label("短信哨兵", 27, bold = true)
        pageSubtitle = label("让重要的消息，被及时听见。", 12, SECONDARY)
        heading.addView(pageTitle); gap(heading, 7); heading.addView(pageSubtitle)
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(icon(Kind.SHIELD).apply {
            background = rounded(SOFT, 16); setPadding(dp(11), dp(11), dp(11), dp(11))
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(header)

        alarmBanner = row().apply {
            id = R.id.active_alarm_banner
            setPadding(dp(14), dp(12), dp(12), dp(12)); background = rounded(RED_SOFT, 16)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        alarmBanner.addView(icon(Kind.BELL, RED), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        alarmBanner.addView(label("正在报警", 15, RED, true), LinearLayout.LayoutParams(0, -2, 1f))
        alarmBanner.addView(button("停止报警", RED) {
            AlarmController.stop(this@MainActivity); message("正在停止报警")
        }.apply { id = R.id.stop_alarm; minHeight = dp(44); minimumHeight = dp(44) }, LinearLayout.LayoutParams(-2, -2))
        root.addView(alarmBanner, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(20), 0, dp(20), dp(10)) })
        alarmBanner.visibility = View.GONE

        pageHost = FrameLayout(this@MainActivity)
        root.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        buildHome(newPage()); buildRules(newPage()); buildSettings(newPage())

        feedback = label("", 13, Color.WHITE).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12)); background = rounded(DARK, 14)
            visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(feedback, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(20), 0, dp(20), dp(8)) })

        val nav = row().apply { setPadding(dp(8), dp(4), dp(8), dp(6)); setBackgroundColor(Color.WHITE) }
        val tabs = listOf(
            Triple("首页", Kind.SHIELD, R.id.nav_home),
            Triple("触发规则", Kind.RULES, R.id.nav_rules),
            Triple("设置", Kind.TOOLS, R.id.nav_settings),
        )
        tabs.forEachIndexed { index, (title, kind, id) ->
            val item = column().apply {
                this.id = id
                gravity = Gravity.CENTER; minimumHeight = dp(58)
                background = ripple(Color.WHITE, 16)
                isFocusable = true
                setOnClickListener { selectTab(index) }
            }
            item.addView(icon(kind, MUTED, 21)); gap(item, 5)
            item.addView(label(title, 11, MUTED, true).apply { gravity = Gravity.CENTER })
            navButtons += item
            nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        divider(root); root.addView(nav)
        setContentView(root)
    }

    private fun newPage(): LinearLayout = with(ui) {
        val content = column().apply { setPadding(dp(20), 0, dp(20), dp(20)) }
        val scroll = ScrollView(this@MainActivity).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false; clipToPadding = false; addView(content)
        }
        pageHost.addView(scroll, FrameLayout.LayoutParams(-1, -1)); pages += scroll
        content
    }

    private fun buildHome(parent: LinearLayout) = with(ui) {
        val hero = card(parent, 22)
        hero.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(DARK, Color.rgb(30, 83, 71)))
            .apply { cornerRadius = dp(24).toFloat() }
        val top = row()
        top.addView(label("短信规则守护", 12, MINT), LinearLayout.LayoutParams(0, -2, 1f))
        heroStatus = badge("运行中", MINT, Color.rgb(42, 85, 72))
        top.addView(heroStatus); hero.addView(top); gap(hero, 20)
        heroTitle = label("正在守护你的消息", 26, Color.WHITE, true).apply { id = R.id.monitor_status }
        hero.addView(heroTitle); gap(hero, 10)
        heroDescription = label("监听已开启，触发规则时会按你的设置提醒。", 14, Color.rgb(200, 220, 212))
        hero.addView(heroDescription); gap(hero, 18)
        statsLabel = label("", 12, Color.rgb(170, 200, 190))
        hero.addView(statsLabel)

        configFileCard = card(parent)
        section(configFileCard, "配置文件")
        configFileText = label("", 13, SECONDARY)
        configFileCard.addView(configFileText); gap(configFileCard, 12)
        configFileCard.addView(button("配置文件", SOFT, ACCENT) {
            startActivity(Intent(this@MainActivity, ConfigFileActivity::class.java))
        }.apply { tag = "home_config_file" })

        val activity = card(parent)
        val line = row()
        line.addView(label("最新动态", 17, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(label("查看全部", 12, ACCENT, true))
        activity.addView(line); gap(activity, 12)
        lastEvent = label("尚无运行记录", 13, SECONDARY).apply { maxLines = 3 }
        activity.addView(lastEvent); gap(activity, 14)
        activity.addView(button("打开运行日志", SOFT, ACCENT) {
            startActivity(Intent(this@MainActivity, LogsActivity::class.java))
        }.apply { textSize = 14f; minHeight = dp(46); minimumHeight = dp(46) })
        activity.isFocusable = true
        activity.setOnClickListener { startActivity(Intent(this@MainActivity, LogsActivity::class.java)) }

        actionRow(card(parent, 16), "运行准备检查", "权限与后台设置", Kind.CHECK) {
            startActivity(Intent(this@MainActivity, PermissionsActivity::class.java))
        }
        gap(parent, 8)
        parent.addView(label("仅在本机处理 · 无需账号", 11, MUTED).apply { gravity = Gravity.CENTER })
    }

    private fun buildRules(parent: LinearLayout) = with(ui) {
        val summary = card(parent)
        val line = row()
        line.addView(label("触发规则", 18, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        ruleCountLabel = label("0 条", 12, SECONDARY)
        line.addView(ruleCountLabel)
        summary.addView(line); gap(summary, 8)
        summary.addView(label("每条规则独立判断条件，命中后执行各自的结果动作。左滑或长按规则可以删除。", 12, SECONDARY))
        gap(summary, 16)
        summary.addView(button("+ 新建规则", ACCENT, Color.WHITE) { openEditor(null) }.apply { id = R.id.new_rule })

        ruleListHost = column().apply { id = R.id.rule_list }
        parent.addView(ruleListHost, LinearLayout.LayoutParams(-1, -2))
        gap(parent, 10)
        parent.addView(label("提示：命中多条规则时，所有动作会同时触发。", 11, MUTED))
    }

    private fun buildSettings(parent: LinearLayout) = with(ui) {
        val entries = card(parent)
        actionRow(entries, "配置文件", "引用同一个文件，修改自动保存，重装后继续使用", Kind.COPY) {
            startActivity(Intent(this@MainActivity, ConfigFileActivity::class.java))
        }
        divider(entries)
        actionRow(entries, "转发规则", "维护 HTTP(S) 与飞书目标，规则里直接选择", Kind.COPY) {
            startActivityForResult(Intent(this@MainActivity, ForwardLibraryActivity::class.java), REQUEST_FORWARD)
        }
        divider(entries)
        actionRow(entries, "运行准备检查", "权限与后台设置", Kind.CHECK) {
            startActivity(Intent(this@MainActivity, PermissionsActivity::class.java))
        }
        divider(entries)
        actionRow(entries, "测试功能", "振动、响铃、闪光灯与匹配检查", Kind.PLAY) {
            startActivity(Intent(this@MainActivity, TestsActivity::class.java))
        }
        divider(entries)
        actionRow(entries, "运行日志", "最近 200 条记录，支持复制与清空", Kind.INFO) {
            startActivity(Intent(this@MainActivity, LogsActivity::class.java))
        }

        gap(parent, 8)
        parent.addView(label("短信哨兵 " + BuildConfig.VERSION_NAME + "\n" + "本机处理 · 日志不保存短信正文和号码", 11, MUTED)
            .apply { gravity = Gravity.CENTER })
    }
private fun selectTab(index: Int, animate: Boolean = true) {
        if (index !in pages.indices) return
        if (animate) hideKeyboard()
        selectedTab = index
        pages.forEachIndexed { i, view ->
            view.animate().cancel(); view.alpha = 1f; view.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            pages[index].alpha = 0f; pages[index].animate().alpha(1f).setDuration(150).start()
        }
        val titles = listOf("短信哨兵", "触发规则", "设置")
        val subtitles = listOf(
            "让重要的消息，被及时听见。",
            "每条规则，都有自己的触发与动作。",
            "转发目标、权限、测试与日志。",
        )
        pageTitle.text = titles[index]
        pageSubtitle.text = subtitles[index]
        val kinds = listOf(Kind.SHIELD, Kind.RULES, Kind.TOOLS)
        navButtons.forEachIndexed { i, item ->
            item.isSelected = i == index
            item.background = ui.ripple(if (i == index) SOFT else Color.WHITE, 16)
            val color = if (i == index) ACCENT else MUTED
            (item.getChildAt(0) as android.widget.ImageView).setImageDrawable(SentinelIcon(kinds[i], color))
            (item.getChildAt(2) as TextView).setTextColor(color)
            item.contentDescription = titles[i] + if (i == index) "，已选中" else ""
        }
        if (index == 1) renderRules()
    }

    // region rules

    private fun renderRules() = with(ui) {
        if (!::ruleListHost.isInitialized) return
        val state = store.state()
        ruleListHost.removeAllViews()
        ruleCountLabel.text = "${state.rules.size} 条 · ${state.rules.count { it.enabled }} 条启用"
        if (state.rules.isEmpty()) {
            val empty = card(ruleListHost, 22)
            empty.addView(icon(Kind.RULES, MUTED, 28))
            gap(empty, 12)
            empty.addView(label("还没有规则", 16, bold = true)); gap(empty, 6)
            empty.addView(label("点击上方“新建规则”，设置条件与命中后的动作。重装后可从配置文件恢复。", 13, SECONDARY))
            gap(empty, 14)
            empty.addView(button("引用已有配置文件", SOFT, ACCENT) {
                startActivity(Intent(this@MainActivity, ConfigFileActivity::class.java))
            }.apply { tag = "use_config_file" })
            return
        }
        state.rules.forEach { rule ->
            val swipe = SwipeRevealRow(this@MainActivity, "删除") { confirmDeleteRule(rule) }
            swipe.setRow(ruleCard(rule))
            ruleListHost.addView(swipe, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun ruleCard(rule: AlarmRule): View = with(ui) {
        val state = store.state()
        val card = column(16).apply {
            background = rounded(Color.WHITE, border = LINE)
            isFocusable = true
            contentDescription = "${rule.name}，${rule.summary()}，${rule.actions.joinToString { it.type.label }}"
            setOnClickListener { openEditor(rule.id) }
            setOnLongClickListener { confirmDeleteRule(rule); true }
        }
        val top = row()
        top.addView(label(rule.name.ifBlank { "未命名规则" }, 16, if (rule.enabled) INK else MUTED, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(this@MainActivity).apply {
            isChecked = rule.enabled
            contentDescription = "启用${rule.name}"
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, 0xFF7E8C8A.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ACCENT, 0xFFD5DEDC.toInt()))
            setOnCheckedChangeListener { _, checked ->
                store.saveRule(rule.copy(enabled = checked))
                store.log((if (checked) "启用规则：" else "停用规则：") + rule.name)
                renderRules(); refreshStatus()
            }
        }
        top.addView(toggle)
        card.addView(top)
        if (rule.description.isNotBlank()) {
            gap(card, 7); card.addView(label(rule.description, 12, SECONDARY))
        }
        gap(card, 10)
        card.addView(label(rule.summary(), 12, MUTED))
        gap(card, 10)
        val chips = row()
        rule.actions.take(4).forEach { action ->
            val available = state.forwardsFor(action).size
            chips.addView(badge(action.type.label + if (action.type.isRemote) " ${available}" else "", ACCENT, SOFT)
                .apply { textSize = 10f }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        if (rule.actions.size > 4) chips.addView(label("+${rule.actions.size - 4}", 10, MUTED))
        card.addView(chips)
        return card
    }

    private fun confirmDeleteRule(rule: AlarmRule) {
        AlertDialog.Builder(this).setTitle("删除规则？")
            .setMessage("「${rule.name.ifBlank { "未命名规则" }}」删除后不再触发，此操作无法撤销。")
            .setPositiveButton("删除") { _, _ ->
                store.deleteRule(rule.id)
                store.log("删除规则：${rule.name}")
                renderRules(); refreshStatus(); message("规则已删除")
            }
            .setNegativeButton("取消", null).show()
    }

    private fun openEditor(id: String?) {
        val intent = Intent(this, RuleEditActivity::class.java)
        if (id != null) intent.putExtra(RuleEditActivity.EXTRA_ID, id)
        startActivityForResult(intent, REQUEST_EDIT)
    }

    @Deprecated("Platform Activity callback; AndroidX is not a dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                REQUEST_EDIT -> { renderRules(); refreshStatus() }
                REQUEST_FORWARD -> renderRules()
            }
        }
    }

    // endregion


    private fun refreshStatus() {
        if (!::heroTitle.isInitialized || restoring) return
        val state = store.state()
        val active = state.activeRules()
        val fileError = ConfigFileManager(this).status().error
        configFileCard.visibility = if ((state.rules.isEmpty() && state.forwards.isEmpty()) || fileError.isNotBlank()) View.VISIBLE else View.GONE
        configFileText.text = if (fileError.isNotBlank()) "配置文件需要处理：" + fileError
            else "首次使用可创建规则；重新安装后，引用原来的配置文件即可使用。"
        alarmBanner.visibility = if (AlarmService.active) View.VISIBLE else View.GONE
        if (AlarmService.active) testStarting = false

        heroStatus.text = if (AlarmService.active) "报警中" else "运行中"
        heroTitle.text = if (AlarmService.active) "正在提醒，请留意" else "正在守护你的消息"
        heroDescription.text = if (AlarmService.active) {
            "点击上方按钮可随时停止。"
        } else "监听已开启，触发规则时会按你的设置提醒。"
        statsLabel.text = "${active.size} 条规则生效 · ${state.forwards.size} 个转发目标"
        lastEvent.text = store.logs().substringBefore("\n\n").ifBlank { "尚无运行记录" }

    }

    private val hideFeedback = Runnable { if (::feedback.isInitialized) feedback.visibility = View.GONE }
    private fun message(value: String) {
        if (!::feedback.isInitialized) return
        feedback.text = value; feedback.visibility = View.VISIBLE
        handler.removeCallbacks(hideFeedback); handler.postDelayed(hideFeedback, 4000)
    }
    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0); root.requestFocus()
    }

    private companion object {
        const val REQUEST_EDIT = 1001
        const val REQUEST_FORWARD = 1002
        const val EXTRA_TAB = "tab"
    }
}

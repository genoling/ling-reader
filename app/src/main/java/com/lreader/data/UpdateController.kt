package com.lreader.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lreader.speech.TtsInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 版本更新的**唯一入口**：检查 / 下载 / 安装 + 相关界面状态。
 *
 * 以前这套逻辑散在设置页里，现在抽出来共用，两个入口都只是它的调用方：
 *  - 启动时的「发现新版本」弹窗（见 [com.lreader.ui.update.UpdatePromptDialog]）；
 *  - 设置页「关于」区块里的检查更新 / 下载安装。
 *
 * 进程级单例（[get]）：两处界面看到的是同一份状态 ——
 * 弹窗里点了「立即更新」，设置页再打开也直接显示下载进度，不会重复下载。
 *
 * 自动检查的三条约束（都在 [checkOnLaunch] 里判断）：
 *  1. 用户关掉自动检查（「以后不提醒」）→ 不再自动查；
 *  2. 24 小时节流 —— 成功查过一次，24 小时内不再查；
 *  3. 首日静默 —— 装好 24 小时内即使有新版也不弹，先让用户熟悉产品。
 *
 * 「本次不更新」只记当前这个版本号（[SettingsStore.ignoredVersion]），
 * 下个 / 下下个新版本仍会正常提示。
 */
class UpdateController private constructor(private val context: Context) {

    companion object {
        /** 自动检查间隔：启动时查，但 24 小时内只查一次 */
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

        /** 新装静默期：装好后这段时间内不弹更新 */
        const val FIRST_DAY_MS = 24 * 60 * 60 * 1000L

        @Volatile
        private var instance: UpdateController? = null

        fun get(context: Context): UpdateController = instance ?: synchronized(this) {
            instance ?: UpdateController(context.applicationContext).also { instance = it }
        }

        /** 「上次检查时间」的展示文本（0 = 从未检查） */
        fun checkTimeText(at: Long): String =
            if (at <= 0L) "从未检查"
            else SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(at))
    }

    private val repo = UpdateRepository(context)
    private val settings = SettingsStore.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 当前安装包的版本信息（进程内不变，构造时读一次） */
    val versionName: String = repo.currentVersionName()
    val versionCode: Long = repo.currentVersionCode()

    // ---- 界面状态（Compose state：设置页与启动弹窗共用）----
    /** 正在检查 */
    var checking by mutableStateOf(false)
        private set

    /** 至少成功检查过一次（区分「未检查」与「已是最新」） */
    var checked by mutableStateOf(false)
        private set

    /** 检查到的可更新版本；null = 没有新版 / 还没查过 */
    var newRelease by mutableStateOf<ReleaseInfo?>(null)
        private set

    var downloading by mutableStateOf(false)
        private set

    var progress by mutableStateOf(0f)
        private set

    var speed by mutableStateOf(0L)
        private set

    /** 检查 / 下载的失败提示（重新开始或成功后清空） */
    var error by mutableStateOf<String?>(null)
        private set

    /** 启动弹窗要展示的新版本；null = 不弹 */
    var prompt by mutableStateOf<ReleaseInfo?>(null)
        private set

    /** 自动检查开关（与 [SettingsStore.autoCheckEnabled] 同步） */
    var autoCheckEnabled by mutableStateOf(settings.autoCheckEnabled)
        private set

    /** 上次成功检查时间（与 [SettingsStore.lastCheckAt] 同步，供界面显示） */
    var lastCheckAt by mutableStateOf(settings.lastCheckAt)
        private set

    /**
     * 启动时的自动检查：受「开关 / 24 小时节流 / 首日静默」约束，
     * 命中新版本时写 [prompt]，由界面决定何时弹（阅读页里会先挂起）。
     */
    fun checkOnLaunch() {
        if (checking || downloading) return
        if (!settings.autoCheckEnabled) return

        val now = System.currentTimeMillis()
        // 新装首日：先让用户用起来，别第一天就推更新
        if (now - settings.ensureFirstInstallAt() < FIRST_DAY_MS) return
        // 24 小时节流：成功查过就等够间隔，避免每次启动都发请求
        val last = settings.lastCheckAt
        if (last > 0L && now - last < CHECK_INTERVAL_MS) return

        runCheck(isLaunchCheck = true)
    }

    /** 手动检查（设置页）：不受节流限制，点了就查 */
    fun check() {
        if (checking || downloading) return
        runCheck(isLaunchCheck = false)
    }

    /** 下载并拉起系统安装界面（弹窗 / 设置页共用） */
    fun downloadAndInstall() {
        val info = newRelease ?: return
        if (downloading) return
        downloading = true
        progress = 0f
        speed = 0L
        error = null
        scope.launch {
            val file = repo.download(info) { p, sp ->
                progress = p
                speed = sp
            }
            downloading = false
            if (file == null) {
                error = if (repo.wasCancelled()) "已取消下载" else "下载失败，请换网络后重试"
                return@launch
            }
            // 装好就不必再弹旧版本的提示；系统安装界面会盖在最上面
            progress = 1f
            prompt = null
            TtsInstaller.install(context, file).onFailure {
                error = "无法打开安装界面：${it.message ?: ""}"
            }
        }
    }

    /** 取消正在下载的更新包（状态栏通知 / 界面按钮） */
    fun cancelDownload() = repo.cancelDownload()

    /** 启动弹窗「本次不更新」：只忽略这一个版本，更高版本仍会提示 */
    fun skipThisVersion() {
        prompt?.let { settings.ignoredVersion = it.version }
        prompt = null
    }

    /** 启动弹窗「以后不提醒」：永久关闭自动检查，设置页可重新打开 */
    fun disableAutoCheck() {
        setAutoCheck(false)
        prompt = null
    }

    /** 关掉弹窗但不做任何记忆（返回键 / 点空白处） */
    fun hidePrompt() {
        prompt = null
    }

    fun setAutoCheck(on: Boolean) {
        autoCheckEnabled = on
        settings.autoCheckEnabled = on
    }

    // ------------------------------------------------------------------ 内部

    private fun runCheck(isLaunchCheck: Boolean) {
        checking = true
        error = null
        scope.launch {
            val result = repo.check()
            checking = false
            result
                .onSuccess { info ->
                    checked = true
                    val now = System.currentTimeMillis()
                    lastCheckAt = now
                    settings.lastCheckAt = now
                    val newer = info.takeIf { UpdateRepository.isNewer(it.version, versionName) }
                    newRelease = newer
                    if (isLaunchCheck && newer != null && settings.ignoredVersion != newer.version) {
                        prompt = newer
                    }
                }
                .onFailure {
                    // 启动检查失败保持静默：不写 lastCheckAt，下次启动再试；设置页里才提示原因
                    if (!isLaunchCheck) {
                        checked = true
                        error = it.message ?: "检查更新失败"
                    }
                }
        }
    }
}

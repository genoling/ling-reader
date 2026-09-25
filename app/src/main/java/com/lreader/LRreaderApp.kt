package com.lreader

import android.app.Application
import com.lreader.data.SettingsStore
import com.lreader.dict.DictManager
import com.lreader.speech.SpeechManager
import com.lreader.speech.TtsCatalog
import com.lreader.translate.TranslationEngines
import com.lreader.ui.theme.AppThemeState

class LRreaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TranslationEngines.init(this)
        // 语音引擎 APK 与词典共用同一套下载管理（进度 / 取消 / 校验 / 删除），先把清单登记进去
        DictManager.get(this).register(TtsCatalog.ENGINES)
        val settings = SettingsStore(this)
        // 启动时套用用户上次选择的界面配色
        AppThemeState.apply(settings.themePreset)
        // 启动即初始化发音引擎。阅读页首次加载要先解压 115MB 词典（dict.ensureReady），
        // 若等到那时才 init，用户第一次点词时引擎还没就绪 —— 表现为「首次点击不发音」。
        SpeechManager.get(this).apply {
            accent = if (settings.accent == "UK") SpeechManager.Accent.UK else SpeechManager.Accent.US
            rate = settings.speechRate
            // 用户指定的引擎 / 强制在线发音（两台设备选同一引擎，音色即一致）
            preferOnline = settings.preferOnlineSpeech
            enginePackage = settings.ttsEngine.ifBlank { null }
            init()
        }
    }
}

package com.lreader

import android.app.Application
import com.lreader.data.SettingsStore
import com.lreader.speech.SpeechManager
import com.lreader.translate.TranslationEngines
import com.lreader.ui.theme.AppThemeState

class LRreaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TranslationEngines.init(this)
        val settings = SettingsStore(this)
        // 启动时套用用户上次选择的界面配色
        AppThemeState.apply(settings.themePreset)
        // 启动即初始化发音引擎。阅读页首次加载要先解压 115MB 词典（dict.ensureReady），
        // 若等到那时才 init，用户第一次点词时引擎还没就绪 —— 表现为「首次点击不发音」。
        SpeechManager.get(this).apply {
            accent = if (settings.accent == "UK") SpeechManager.Accent.UK else SpeechManager.Accent.US
            rate = settings.speechRate
            init()
        }
    }
}

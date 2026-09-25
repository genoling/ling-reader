package com.lreader.speech

import com.lreader.dict.DictResource

/**
 * 第三方（开源）语音引擎资源清单。
 *
 * LingReader 只能通过系统 TTS 框架发声，所以「更好的发音」= 装一个第三方 TTS 引擎 App。
 * 这里选用 **sherpa-onnx 官方 Android TTS 引擎**（Apache-2.0，离线神经网络语音）：
 * 每个 APK 内绑定一个语音模型（一包一模型），装完即出现在系统的语音引擎列表里，
 * 回到 设置 → 发音 → 语音引擎 选中即可。
 *
 * 附件体积较大（50~330MB），故**不放进本仓库**，直接从 hf-mirror（Hugging Face 国内镜像）下载：
 * 仓库里只保留这份清单，APK 由用户按需下载，APK 安装包体积不受影响。
 *
 * 注意：Android 不允许 App 静默安装其它 App —— 下载完成后拉起系统安装器，用户点「安装」确认。
 */
object TtsCatalog {

    private const val VERSION = "1.13.8"

    /** 只提供 arm64-v8a（近几年的手机/平板基本都是；32 位设备请自行去官网找 armeabi-v7a） */
    private const val ARCH = "arm64-v8a"

    private const val BASE =
        "https://hf-mirror.com/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-engine-new/$VERSION/"

    private fun engine(
        id: String,
        name: String,
        description: String,
        localName: String,
        remoteName: String,
        sizeBytes: Long
    ) = DictResource(
        id = id,
        name = name,
        description = description,
        fileName = localName,
        remoteFile = localName,
        sizeBytes = sizeBytes,
        assetName = null,
        removable = true,
        absoluteUrl = BASE + "sherpa-onnx-$VERSION-$ARCH-eng-tts-engine-$remoteName.apk"
    )

    /** 体积从轻到重排序，UI 里按此顺序展示 */
    val ENGINES: List<DictResource> = listOf(
        engine(
            id = "tts_kitten_nano",
            name = "轻量英语引擎（Kitten Nano）",
            description = "音质比系统自带引擎自然，体积最小、响应最快。",
            localName = "tts-kitten-nano.apk",
            remoteName = "kitten-nano-en-v0_8-int8",
            sizeBytes = 50_969_260L
        ),
        engine(
            id = "tts_piper_amy",
            name = "自然女声（Piper Amy）",
            description = "美音女声，离线神经网络合成，综合推荐。",
            localName = "tts-piper-amy.apk",
            remoteName = "vits-piper-en_US-amy-medium",
            sizeBytes = 85_667_491L
        ),
        engine(
            id = "tts_piper_ryan",
            name = "自然男声（Piper Ryan）",
            description = "美音男声，采样质量更高一档。",
            localName = "tts-piper-ryan.apk",
            remoteName = "vits-piper-en_US-ryan-high",
            sizeBytes = 132_922_682L
        ),
        engine(
            id = "tts_kokoro",
            name = "高保真英语（Kokoro）",
            description = "目前开源英语里音质最好的一档，体积也最大。",
            localName = "tts-kokoro.apk",
            remoteName = "kokoro-en-v0_19",
            sizeBytes = 332_099_071L
        )
    )

    fun byId(id: String): DictResource? = ENGINES.firstOrNull { it.id == id }
}

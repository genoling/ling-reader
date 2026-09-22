package com.lreader.dict

/**
 * 词典资源描述。
 *
 * @param id            内部标识（设置页与状态表用它做 key）
 * @param name          展示名
 * @param description   一句话说明（设置页副标题）
 * @param fileName      释放/下载到 `filesDir` 后的文件名（**数据库文件名**）
 * @param remoteFile    下载源里的文件名（Release 附件名）
 * @param sizeBytes     预期字节数，用于展示与下载完整性校验（0 = 不校验）
 * @param assetName     APK `assets/` 内的同名后备文件；非 null 表示「随包内置」，首次使用时释放
 * @param removable     是否允许在设置页删除（内置资源删了会让分级高亮永久失效，故禁止）
 */
data class DictResource(
    val id: String,
    val name: String,
    val description: String,
    val fileName: String,
    val remoteFile: String,
    val sizeBytes: Long,
    val assetName: String? = null,
    val removable: Boolean = true
)

/**
 * 词典资源清单 + 下载源。
 *
 * **下载源**默认指向本仓库的 GitHub Release：
 * `https://github.com/genoling/ling-reader/releases/download/dict-v1/<附件名>`
 * 用户可在 设置 → 本地词典 → 下载源 里改成镜像 / 自建服务器（只需一个以 `/` 结尾的前缀）。
 *
 * 发布资源（维护者操作）：
 * ```powershell
 * gh release create dict-v1 dict-assets/dict_en_zh.min.db -t "词典资源 v1" -n "英汉词典（供 App 按需下载）"
 * ```
 */
object DictCatalog {

    const val REPO = "genoling/ling-reader"
    const val RELEASE_TAG = "dict-v1"

    /** 默认下载源前缀（必须以 `/` 结尾，后面直接拼附件名） */
    const val DEFAULT_SOURCE =
        "https://github.com/$REPO/releases/download/$RELEASE_TAG/"

    const val ID_MAIN = "en_zh"
    const val ID_LEVELS = "levels"

    /**
     * 主词典：**不随包内置**，用户按需下载（APK 体积因此从 130MB+ 降到 10MB 级）。
     * `sizeBytes` 取自构建产物 `dict_en_zh.min.db` 的实际大小（120,348,672 B）。
     */
    val MAIN = DictResource(
        id = ID_MAIN,
        name = "21世纪大英汉词典",
        description = "33.2 万词条，含音标与中文释义；离线查询、无次数限制。",
        fileName = "dict_en_zh.db",
        remoteFile = "dict_en_zh.min.db",
        sizeBytes = 120_348_672L,
        assetName = null,
        removable = true
    )

    /**
     * 分级词库：体积小（4.6MB）且是「分级高亮」的核心依赖，
     * 保持随包内置（首次使用时释放到 filesDir），后置为可下载资源也只差改 `assetName = null`。
     */
    val LEVELS = DictResource(
        id = ID_LEVELS,
        name = "分级词库",
        description = "3.9 万词条 / 10 个级别（高中·四六级·考研·雅思·托福·GRE 等），随安装包内置。",
        fileName = "levels.db",
        remoteFile = "levels.db",
        sizeBytes = 4_800_512L,
        assetName = "levels.db",
        removable = false
    )

    val resources: List<DictResource> = listOf(MAIN, LEVELS)

    fun byId(id: String): DictResource? = resources.firstOrNull { it.id == id }

    /** 规范化下载源：空串走默认；自动补末尾 `/` */
    fun sourceOf(override: String?): String {
        val o = override?.trim().orEmpty()
        if (o.isEmpty()) return DEFAULT_SOURCE
        return if (o.endsWith("/")) o else "$o/"
    }

    fun urlOf(res: DictResource, override: String?): String = sourceOf(override) + res.remoteFile

    /** 人类可读体积，如 `115 MB` / `4.6 MB` / `512 KB` */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

package io.github.haohaoo3o.tracklab.ui.map

/**
 * 高德 Key 提供端口（docs/CONTRACTS.md §5——纯 Kotlin，JVM 直测）。
 *
 * **Key 只能从构建配置注入**（注入链路）：
 * `local.properties`|环境变量 `AMAP_API_KEY` → `manifestPlaceholders["AMAP_API_KEY"]` →
 * Manifest meta-data [META_DATA_API_KEY]。本端口只允许从该 meta-data 读取——
 * 缺失/留空编译仍成功，应用进入 NEEDS_KEY 显示 `error_amap_key_missing` 配置提示。
 * 真实 Key 绝不写进仓库、源码或本文件。
 */
interface KeyProvider {

    /** 读取注入的高德 Key；缺失或空白返回 null。 */
    fun apiKey(): String?

    /** 是否有可用 Key（空白视为缺失）。 */
    fun hasKey(): Boolean = !apiKey().isNullOrBlank()

    companion object {

        /** 注入链路终点的 meta-data 名（与 AndroidManifest.xml 钉死一致）。 */
        const val META_DATA_API_KEY = "com.amap.api.v2.apikey"
    }
}

/**
 * 从 meta-data 读取 Key 的实现。[reader] 为注入的读取函数
 * （生产：`PackageManager.getApplicationInfo(...).metaData?.getString(key)`，由 AppContainer 注入；
 * 测试：lambda 假件——本类零 Android 依赖，纯 JVM 可执行）。
 *
 * 读取结果做 trim；null / 空串 / 纯空白一律视为【无 Key】（NEEDS_KEY 态，不崩溃）。
 */
class MetaDataKeyProvider(
    private val reader: (String) -> String?,
) : KeyProvider {

    override fun apiKey(): String? =
        reader(KeyProvider.META_DATA_API_KEY)?.trim()?.takeIf { it.isNotEmpty() }
}

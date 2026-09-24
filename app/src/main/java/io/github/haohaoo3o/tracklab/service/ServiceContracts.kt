package io.github.haohaoo3o.tracklab.service

/**
 * 服务层契约常量（全文见 docs/CONTRACTS.md §7）。
 * channel / prefs 键名 / FLAG_IMMUTABLE / START_NOT_STICKY 均以【值契约】形式钉死，
 * 不 import android.* —— 保证纯 JVM 测试（PlaybackStateMachineTest 等）可直接引用。
 */
object ServiceContracts {

    /** NotificationChannel id（onCreate 创建，minSdk 26）。*/
    const val NOTIFICATION_CHANNEL_ID = "tracklab_playback"

    /** 通知 id。 */
    const val NOTIFICATION_ID = 42

    /** SharedPreferences 文件名（契约见 docs/CONTRACTS.md §5、§7.2）。 */
    /** 回放快照文件："playback_state"（MODE_PRIVATE），仅 service 层 PrefsSnapshotStore 访问。 */
    const val PREFS_PLAYBACK = "playback_state"

    /** UI/隐私状态文件："tracklab_state"（MODE_PRIVATE），ConsentStore 访问。 */
    const val PREFS_UI = "tracklab_state"

    /** 快照字符串键（playback_state 内）。 */
    const val KEY_SNAPSHOT = "playback_snapshot"

    /** 隐私同意布尔键（tracklab_state 内）。SmokeTest 预置/断言同文件同键。*/
    const val KEY_CONSENT = "consent_agreed"

    /** 通知动作 PendingIntent 必带 FLAG_IMMUTABLE（值 = android.app.PendingIntent.FLAG_IMMUTABLE）。*/
    const val FLAG_IMMUTABLE = 0x04000000

    /** 对照值（禁止用于通知动作）。 */
    const val FLAG_MUTABLE = 0x02000000

    /** onStartCommand 返回 START_NOT_STICKY（值 = android.app.Service.START_NOT_STICKY）。*/
    const val START_NOT_STICKY_VALUE = 2

    /** Q+ startForeground 传 FOREGROUND_SERVICE_TYPE_LOCATION（值 = ServiceInfo 同名常量 = 8）。*/
    const val FOREGROUND_SERVICE_TYPE_LOCATION = 8

    /** 快照写时机 = 每次状态迁移 + 每 25 样本节流；Stop/完成即 clear。*/
    const val SNAPSHOT_THROTTLE_SAMPLES = 25

    /** 快照编码版本前缀。编码 = "v1|<state>|<sampleIndex>|<distanceM>|<elapsedMs>"（| 分隔，
     *  distanceM 用 Double.toString、elapsedMs 用十进制 Long；解析失败抛 IllegalArgumentException）。
     *  fromSnapshot 语义：恢复累计值且状态一律置 PAUSED（不自动续跑）。 */
    const val SNAPSHOT_FORMAT_VERSION = "v1"
}

// TrackLab 根构建文件。版本钉死见 docs/CONTRACTS.md §2：
// AGP 8.7.2、Kotlin 2.0.21（与本机 ~/.gradle/caches 对齐）。禁用 Compose/Hilt/Guava/Play Services 等（§2）。
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

# TrackLab 混淆规则（release 变体当前 isMinifyEnabled=false，见 app/build.gradle.kts）。
# 若后续开启混淆，高德 SDK 需保留（官方要求）：
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**

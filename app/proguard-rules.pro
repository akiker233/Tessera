# 保留 SQLCipher 原生依赖相关类
-keep class net.zetetic.** { *; }
-keep class net.sqlcipher.** { *; }
-dontwarn net.zetetic.**
-dontwarn net.sqlcipher.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
# Google Drive 客户端（google-http-client-gson）用 Gson 反射解析模型类，
# R8 裁剪字段/getter 后会在运行时静默解析出空值
-keep class com.google.api.services.** { *; }
-dontwarn com.google.api.services.**
# ML Kit 捆绑版条码扫描：组件经反射发现，R8 全量模式下工厂变 null，
# 相机帧分析器里 BarcodeScanning.getClient 必现 NPE（release 真机复现）
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-dontwarn com.google.mlkit.**

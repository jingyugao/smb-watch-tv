# jcifs-ng 通过配置字符串反射加载类，整体保留
-keep class jcifs.** { *; }
-dontwarn jcifs.**

# jcifs-ng 的加密依赖
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# jcifs-ng 引用但 Android 上不存在的可选依赖
-dontwarn javax.servlet.**
-dontwarn org.slf4j.**
-dontwarn java.beans.**

# 保留行号便于解读线上崩溃堆栈
-keepattributes SourceFile,LineNumberTable

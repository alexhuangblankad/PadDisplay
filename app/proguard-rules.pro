# PadDisplay ProGuard 规则
#
# 本版本的 release 未启用混淆（isMinifyEnabled = false）。
# 如果后续开启混淆，注意以下必须保留的项。

# Shizuku UserService：由 Shizuku 通过反射按类名实例化，不能被混淆/移除
-keep class com.paddisplay.app.system.PadDisplayUserService { *; }

# AIDL 生成的 Stub / Proxy 不能混淆
-keep class com.paddisplay.app.IPadDisplayService { *; }
-keep class com.paddisplay.app.IPadDisplayService$* { *; }

# 反射调用的隐藏 API 包装（方法名靠字符串匹配，混淆后无法工作）
-keep class com.paddisplay.app.system.Reflect { *; }
-keep class com.paddisplay.app.system.AidlCodec { *; }

# 保留注解
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions

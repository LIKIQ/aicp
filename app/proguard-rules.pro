# app/proguard-rules.pro
# AICP R8 规则
#
# 需要保留的只有两处反射入口：
# 1. kotlinx.serialization 生成的 serializer（@Serializable 类的伴生对象）
# 2. Room 生成的 _Impl 类（Room 自身带 consumer rules，这里只兜底）
# OkHttp 5 自带 consumer proguard 规则，不需要手写。

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
	*** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
	kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
	static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
	static **$* *;
}
-keepclassmembers class <2>$<3> {
	kotlinx.serialization.KSerializer serializer(...);
}

# --- Room 兜底 ---
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

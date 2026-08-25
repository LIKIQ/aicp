// app/src/test/java/com/kiq/aicp/domain/SettingsFieldsProbe.kt
// 测试专用：反射拿 data class 的实例字段名。
//
// 配置码那两个测试都要"AicpSettings 到底有哪些设置项"这份名单，
// 用来盯住"加了字段却忘了同步映射"这类静默漏项，所以抽出来共用一份。
//
// 三个过滤缺一不可，尤其是静态字段：
// 带 companion object 的类会生成一个名叫 Companion 的静态字段，它既不是 synthetic
// 也不含 $，两边类都有 companion 时会各多一个、正好抵消，让"数量相等"的断言侥幸通过
// —— 那种通过比失败更糟，因为它看起来是绿的。

package com.kiq.aicp.domain

import java.lang.reflect.Modifier

internal fun instanceFieldsOf(clazz: Class<*>): Set<String> = clazz.declaredFields
	.filterNot { it.isSynthetic }
	.filterNot { it.name.contains('$') }
	.filterNot { Modifier.isStatic(it.modifiers) }
	.map { it.name }
	.toSet()

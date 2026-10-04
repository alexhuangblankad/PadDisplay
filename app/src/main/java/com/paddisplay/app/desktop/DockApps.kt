package com.paddisplay.app.desktop

import com.paddisplay.app.system.SystemDisplayService.LaunchableApp

/** Choose from this device's installed apps. No Google dependencies or fabricated shortcuts. */
object DockApps {
    fun select(apps: List<LaunchableApp>, favorites: Set<String>, limit: Int = 8): List<LaunchableApp> {
        val available = apps.filter { it.packageName != "com.paddisplay.app" }.distinctBy { it.packageName }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
        val selected = available.filter { it.packageName in favorites }.toMutableList()
        val defaults = available.filter { it.packageName != "com.android.stk" }
        if (selected.size < limit) {
            listOf(listOf("浏览器", "browser"), listOf("文件", "文档", "filemanager", "file manager"),
                listOf("设置", "settings"), listOf("相册", "图库", "gallery", "photos"),
                listOf("wps", "笔记", "便签", "notes"), listOf("moonlight", "uu远程", "远程")).forEach { terms ->
                defaults.firstOrNull { app -> app !in selected && terms.any { term ->
                    "${app.label} ${app.packageName}".contains(term, ignoreCase = true)
                } }?.let { selected += it }
            }
        }
        selected += defaults.filter { it !in selected }
        return selected.take(limit.coerceAtLeast(0))
    }
}

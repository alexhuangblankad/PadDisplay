package com.paddisplay.app.system

/** A single standard user preference, with readback and no desktop-feature overrides. */
class MirrorPreference(private val shell: (String) -> String) {
    fun read(user: Int): String {
        require(user >= 0)
        return shell("/system/bin/settings --user $user get secure mirror_built_in_display").trim().also {
            check(it in setOf("0", "1", "null")) { "镜像偏好读取失败：$it" }
        }
    }
    fun disable(user: Int, original: String) {
        require(original == "1") { "只处理已明确启用的镜像偏好" }
        check(read(user) == original) { "镜像偏好已变化，未覆盖" }
        shell("/system/bin/settings --user $user put secure mirror_built_in_display 0")
        check(read(user) == "0") { "系统拒绝关闭镜像或已强制改回，未获得扩展能力" }
    }
    fun restore(user: Int, original: String) {
        require(original == "1")
        val current = read(user)
        if (current == original) return
        check(current == "0") { "镜像偏好被其它操作更改，保留恢复记录" }
        shell("/system/bin/settings --user $user put secure mirror_built_in_display $original")
        check(read(user) == original) { "原镜像偏好恢复失败" }
    }
}

package com.paddisplay.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.paddisplay.app.display.DisplayRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "paddisplay_settings")

/**
 * 配置持久化（任务书第 14 节），用 DataStore Preferences。
 *
 * 保存项与任务书一致：
 * preferredExternalDisplayMode / preferredRefreshRate / autoNativeResolution /
 * autoInternalScreenOff / autoRestoreInternalScreen / launchMoonlight /
 * lastKnownInternalDisplay
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        /** 用户选定的外接屏尺寸覆盖，格式 "3840x2160"，空串表示“自动/最佳”。 */
        val PREFERRED_EXTERNAL_SIZE = stringPreferencesKey("preferredExternalDisplayMode")

        /** 用户选定的刷新率，0 表示未指定。 */
        val PREFERRED_REFRESH_RATE = intPreferencesKey("preferredRefreshRate")

        /** 记录上次选定的 modeId，便于精确回切。 */
        val PREFERRED_MODE_ID = intPreferencesKey("preferredModeId")

        val AUTO_NATIVE_RESOLUTION = booleanPreferencesKey("autoNativeResolution")
        val AUTO_INTERNAL_SCREEN_OFF = booleanPreferencesKey("autoInternalScreenOff")
        val AUTO_RESTORE_INTERNAL_SCREEN = booleanPreferencesKey("autoRestoreInternalScreen")
        val LAUNCH_MOONLIGHT = booleanPreferencesKey("launchMoonlight")

        /** 上次确认的内屏 displayId（-1 表示未知）。 */
        val LAST_KNOWN_INTERNAL_DISPLAY = intPreferencesKey("lastKnownInternalDisplay")

        /** 上次确认的外接屏 displayId。 */
        val LAST_KNOWN_EXTERNAL_DISPLAY = intPreferencesKey("lastKnownExternalDisplay")

        /** 用户手动指定的屏幕角色： "0=INTERNAL,2=EXTERNAL"。 */
        val ROLE_OVERRIDES = stringPreferencesKey("roleOverrides")

        /** 是否使用逻辑尺寸覆盖通道（Mode 切换失败时启用）。 */
        val USE_FORCED_SIZE_FALLBACK = booleanPreferencesKey("useForcedSizeFallback")

        /**
         * 内屏当前是否被我们关掉了。
         * 持久化很关键：App 进程被系统杀掉后，重启时要据此自动点亮内屏，
         * 否则用户会陷入黑屏。
         */
        val INTERNAL_SCREEN_OFF = booleanPreferencesKey("internalScreenOff")
    }

    val autoNativeResolution: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AUTO_NATIVE_RESOLUTION] ?: false }

    val autoInternalScreenOff: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AUTO_INTERNAL_SCREEN_OFF] ?: false }

    val autoRestoreInternalScreen: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AUTO_RESTORE_INTERNAL_SCREEN] ?: true }

    val launchMoonlight: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.LAUNCH_MOONLIGHT] ?: false }

    val useForcedSizeFallback: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.USE_FORCED_SIZE_FALLBACK] ?: false }

    val preferredExternalSize: Flow<String> =
        context.dataStore.data.map { it[Keys.PREFERRED_EXTERNAL_SIZE] ?: "" }

    val preferredRefreshRate: Flow<Int> =
        context.dataStore.data.map { it[Keys.PREFERRED_REFRESH_RATE] ?: 0 }

    val preferredModeId: Flow<Int> =
        context.dataStore.data.map { it[Keys.PREFERRED_MODE_ID] ?: -1 }

    val lastKnownInternalDisplay: Flow<Int> =
        context.dataStore.data.map { it[Keys.LAST_KNOWN_INTERNAL_DISPLAY] ?: -1 }

    val lastKnownExternalDisplay: Flow<Int> =
        context.dataStore.data.map { it[Keys.LAST_KNOWN_EXTERNAL_DISPLAY] ?: -1 }

    suspend fun setAutoNativeResolution(value: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_NATIVE_RESOLUTION] = value }

    suspend fun setAutoInternalScreenOff(value: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_INTERNAL_SCREEN_OFF] = value }

    suspend fun setAutoRestoreInternalScreen(value: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_RESTORE_INTERNAL_SCREEN] = value }

    suspend fun setLaunchMoonlight(value: Boolean) =
        context.dataStore.edit { it[Keys.LAUNCH_MOONLIGHT] = value }

    suspend fun setUseForcedSizeFallback(value: Boolean) =
        context.dataStore.edit { it[Keys.USE_FORCED_SIZE_FALLBACK] = value }

    suspend fun setPreferredExternalSize(value: String) =
        context.dataStore.edit { it[Keys.PREFERRED_EXTERNAL_SIZE] = value }

    suspend fun setPreferredRefreshRate(value: Int) =
        context.dataStore.edit { it[Keys.PREFERRED_REFRESH_RATE] = value }

    suspend fun setPreferredModeId(value: Int) =
        context.dataStore.edit { it[Keys.PREFERRED_MODE_ID] = value }

    suspend fun setLastKnownInternalDisplay(displayId: Int) =
        context.dataStore.edit { it[Keys.LAST_KNOWN_INTERNAL_DISPLAY] = displayId }

    suspend fun setLastKnownExternalDisplay(displayId: Int) =
        context.dataStore.edit { it[Keys.LAST_KNOWN_EXTERNAL_DISPLAY] = displayId }

    // ------------------------------------------------------------------
    // 内屏关闭状态（failsafe 用）
    // ------------------------------------------------------------------

    val internalScreenOff: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.INTERNAL_SCREEN_OFF] ?: false }

    suspend fun setInternalScreenOff(off: Boolean) =
        context.dataStore.edit { it[Keys.INTERNAL_SCREEN_OFF] = off }

    // ------------------------------------------------------------------
    // 屏幕角色手动覆盖
    // ------------------------------------------------------------------

    suspend fun setRoleOverride(displayId: Int, role: DisplayRole?) {
        context.dataStore.edit { prefs ->
            val map = parseOverrides(prefs[Keys.ROLE_OVERRIDES])
            if (role == null) map.remove(displayId) else map[displayId] = role
            prefs[Keys.ROLE_OVERRIDES] = map.entries.joinToString(",") { "${it.key}=${it.value.name}" }
        }
    }

    val roleOverrides: Flow<Map<Int, DisplayRole>> =
        context.dataStore.data.map { parseOverrides(it[Keys.ROLE_OVERRIDES]) }

    private fun parseOverrides(raw: String?): MutableMap<Int, DisplayRole> {
        val map = mutableMapOf<Int, DisplayRole>()
        raw?.split(",")?.forEach { part ->
            val kv = part.split("=")
            if (kv.size == 2) {
                val id = kv[0].trim().toIntOrNull()
                val role = runCatching { DisplayRole.valueOf(kv[1].trim()) }.getOrNull()
                if (id != null && role != null) map[id] = role
            }
        }
        return map
    }
}

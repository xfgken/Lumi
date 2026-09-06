package com.xfgken.Lumi.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 用户设置存储（DataStore Preferences）。
 */
class SettingsStore(private val context: Context) {

    /** 代理模式：0=全局代理 1=规则代理(默认) 2=直连模式 */
    val proxyMode: Flow<Int> = context.dataStore.data.map { it[KEY_PROXY_MODE] ?: 1 }

    /** 中国流量：true=直连(默认) false=代理 */
    val chinaDirect: Flow<Boolean> = context.dataStore.data.map { it[KEY_CHINA_DIRECT] ?: true }

    /** 国外流量：true=代理(默认) false=直连 */
    val abroadProxy: Flow<Boolean> = context.dataStore.data.map { it[KEY_ABROAD_PROXY] ?: true }

    /** 主题：1=浅色(默认) 2=深色；0=跟随系统（仅兼容旧数据，启动按浅色处理） */
    val themeMode: Flow<Int> = context.dataStore.data.map { it[KEY_THEME] ?: 1 }

    suspend fun setProxyMode(mode: Int) = context.dataStore.edit { it[KEY_PROXY_MODE] = mode }
    suspend fun setChinaDirect(direct: Boolean) = context.dataStore.edit { it[KEY_CHINA_DIRECT] = direct }
    suspend fun setAbroadProxy(proxy: Boolean) = context.dataStore.edit { it[KEY_ABROAD_PROXY] = proxy }
    suspend fun setThemeMode(mode: Int) = context.dataStore.edit { it[KEY_THEME] = mode }

    companion object {
        private val KEY_PROXY_MODE = intPreferencesKey("proxy_mode")
        private val KEY_CHINA_DIRECT = booleanPreferencesKey("china_direct")
        private val KEY_ABROAD_PROXY = booleanPreferencesKey("abroad_proxy")
        private val KEY_THEME = intPreferencesKey("theme_mode")
    }
}
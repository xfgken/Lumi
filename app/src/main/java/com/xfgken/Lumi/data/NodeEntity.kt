package com.xfgken.Lumi.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 节点配置表。
 * password / obfsPassword 以 KeystoreCipher 加密密文存储。
 */
@Entity(tableName = "nodes")
data class NodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val server: String,
    val port: Int,
    val passwordEnc: String,
    val sni: String,
    val insecure: Boolean,
    val obfsType: String,
    val obfsPasswordEnc: String,
    val upMbps: Int,
    val downMbps: Int,
    val pingMs: Int,
    val remark: String,
    val isSelected: Boolean
)
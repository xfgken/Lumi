package com.xfgken.Lumi.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 运行日志表（文档第十六条：日志记录保存）。
 * 为控制体积仅保留最近 2000 条，由 Repository 定期裁剪。
 */
@Entity(
    tableName = "logs",
    indices = [Index(value = ["timeMs"])]
)
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timeMs: Long,
    val level: String,
    val message: String
)
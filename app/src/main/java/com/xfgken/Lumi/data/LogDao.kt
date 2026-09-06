package com.xfgken.Lumi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {

    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<LogEntity>>

    @Insert
    suspend fun insertAll(logs: List<LogEntity>)

    @Query("DELETE FROM logs")
    suspend fun clearAll()

    @Query("DELETE FROM logs WHERE id NOT IN (SELECT id FROM logs ORDER BY id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}
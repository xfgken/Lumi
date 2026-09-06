package com.xfgken.Lumi.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NodeDao {

    @Query("SELECT * FROM nodes ORDER BY id")
    fun observeAll(): Flow<List<NodeEntity>>

    @Query("SELECT * FROM nodes ORDER BY id")
    suspend fun getAll(): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): NodeEntity?

    @Insert
    suspend fun insert(node: NodeEntity): Long

    @Update
    suspend fun update(node: NodeEntity)

    @Delete
    suspend fun delete(node: NodeEntity)

    /** 清除所有选中标记 */
    @Query("UPDATE nodes SET isSelected = 0")
    suspend fun clearSelection()

    @Query("SELECT * FROM nodes WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelected(): NodeEntity?

    @Query("SELECT COUNT(*) FROM nodes")
    suspend fun count(): Int
}
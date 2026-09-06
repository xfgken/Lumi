package com.xfgken.Lumi.data

import android.content.Context
import com.xfgken.Lumi.model.NodeConfig
import com.xfgken.Lumi.utils.KeystoreCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 节点仓库：负责 Entity <-> Model 转换，敏感字段加解密。
 * 所有方法应在 IO 协程中调用。
 */
class NodeRepository(context: Context) {

    private val db = AppDatabase.get(context)
    private val dao = db.nodeDao()
    private val logDao = db.logDao()

    fun observeNodes(): Flow<List<NodeConfig>> =
        dao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun getAll(): List<NodeConfig> = dao.getAll().map { it.toModel() }

    suspend fun getSelected(): NodeConfig? = dao.getSelected()?.toModel()

    suspend fun getById(id: Long): NodeConfig? = dao.getById(id)?.toModel()

    /** 新增节点；若为第一个节点自动选中 */
    suspend fun insert(node: NodeConfig): Long {
        val first = dao.count() == 0
        val entity = node.toEntity().copy(isSelected = if (first) true else node.isSelected)
        return dao.insert(entity)
    }

    suspend fun update(node: NodeConfig) {
        val entity = node.toEntity()
        dao.update(entity)
    }

    suspend fun delete(node: NodeConfig) {
        dao.delete(node.toEntity())
    }

    /** 切换选中节点 */
    suspend fun select(nodeId: Long) {
        dao.clearSelection()
        dao.getById(nodeId)?.let {
            dao.update(it.copy(isSelected = true))
        }
    }

    /** 解析导入链接并入库，返回新节点 id；解析失败抛异常 */
    suspend fun importUri(uri: String): Long {
        val node = com.xfgken.Lumi.core.Hysteria2UriParser.parse(uri)
        return insert(node)
    }

    // ---- 日志 ----

    fun observeLogs(limit: Int = 500): Flow<List<LogEntity>> = logDao.observeRecent(limit)

    suspend fun addLog(level: String, message: String) {
        logDao.insertAll(listOf(LogEntity(timeMs = System.currentTimeMillis(), level = level, message = message)))
        logDao.trim(2000)
    }

    suspend fun clearLogs() = logDao.clearAll()

    // ---- 转换 ----

    private fun NodeConfig.toEntity() = NodeEntity(
        id = id,
        name = name,
        server = server,
        port = port,
        passwordEnc = KeystoreCipher.encrypt(password),
        sni = sni,
        insecure = insecure,
        obfsType = obfsType,
        obfsPasswordEnc = KeystoreCipher.encrypt(obfsPassword),
        upMbps = upMbps,
        downMbps = downMbps,
        pingMs = pingMs,
        remark = remark,
        isSelected = isSelected
    )

    private fun NodeEntity.toModel() = NodeConfig(
        id = id,
        name = name,
        server = server,
        port = port,
        password = KeystoreCipher.decrypt(passwordEnc),
        sni = sni,
        insecure = insecure,
        obfsType = obfsType,
        obfsPassword = KeystoreCipher.decrypt(obfsPasswordEnc),
        upMbps = upMbps,
        downMbps = downMbps,
        pingMs = pingMs,
        remark = remark,
        isSelected = isSelected
    )
}
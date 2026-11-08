package com.ai.assistance.quro.core.tools.novel

import android.content.Context
import com.ai.assistance.quro.core.novel.NovelRepository
import com.ai.assistance.quro.core.novel.asset.AssetType
import com.ai.assistance.quro.core.novel.asset.CharacterAsset
import com.ai.assistance.quro.core.novel.asset.FactionAsset
import com.ai.assistance.quro.core.novel.asset.ItemAsset
import com.ai.assistance.quro.core.novel.asset.LocationAsset
import com.ai.assistance.quro.core.novel.asset.NovelAssetRepository
import com.ai.assistance.quro.core.novel.asset.ReferenceAsset
import com.ai.assistance.quro.core.novel.asset.SettingAsset
import com.ai.assistance.quro.core.novel.graph.GraphStore
import com.ai.assistance.quro.core.novel.graph.hook.NovelHookStatus
import com.ai.assistance.quro.core.novel.graph.memory.NovelMemoryLink
import com.ai.assistance.quro.core.novel.graph.memory.NovelTextMemory
import com.ai.assistance.quro.core.novel.graph.memory.NovelTextMemoryCache
import com.ai.assistance.quro.data.model.AgentToolDefinition
import com.ai.assistance.quro.data.model.AgentToolParam
import com.ai.assistance.quro.tools.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * 写作域 · 记忆双轨工具族（P1-4，ZorvAI → OpenMinis 基座移植）。
 *
 * | 工具 | 轨 | 职责 |
 * |---|---|---|
 * | `novel_memory_text` | 文本轨 | 取十节快照（5 分钟 TTL 缓存，可强制刷新） |
 * | `novel_memory_link` | 图谱轨 | 在两段记忆之间建边（相关/因果/伏笔/回收/同一人） |
 * | `novel_memory_graph` | 图谱轨 | 导出记忆图谱（节点 + 边 + 五表计数），供自查与展示 |
 *
 * 图谱轨的增删查（`novel_memory_save` / `novel_memory_query` / `novel_memory`）在
 * NovelGraphTools.kt；本文件只管「文本轨快照」与「图谱的边与视图」。
 *
 * 契约与基座 `tools/NovelTools.kt` 完全一致：每个工具一个 object，
 * `definition(): AgentToolDefinition` + `execute(argsJson, sessionId, context): ToolExecutionResult`。
 * 三个工具的 execute 都不使用 sessionId：写作域数据（作品/资料/图谱五张表）落在
 * Android filesDir（`filesDir/novel/`），不像章节正文那样走 /var/minis 挂载树；
 * 参数保留是为与基座调度器 `executeTool` 的调用形参列表逐字节对齐。
 */

/**
 * 文本轨快照的取数层：把 Phase 1 结构域、3b 资料域、3c 图谱域的数据装配成
 * [NovelTextMemory.Input]（纯数据），构建交给 [NovelTextMemory]（纯函数，可 JVM 单测）。
 */
internal object NovelTextMemoryProvider {

    /** 章节状态 key 的中文化（快照要给人读；Phase 1 枚举只有 key）。 */
    private fun statusLabel(key: String): String = when (key) {
        "unwritten" -> "未写"
        "draft" -> "草稿"
        "polishing" -> "打磨中"
        "completed" -> "已写完"
        else -> key
    }

    fun input(context: Context, workId: String): NovelTextMemory.Input? {
        val work = NovelRepository.fromContext(context).loadWorks().firstOrNull { it.id == workId }
            ?: return null
        val assets = NovelAssetRepository.fromContext(context)
        val hooks = GraphStore.fromContext(context).listHooks(workId)

        val volumes = work.volumes.sortedWith(compareBy({ it.order }, { it.createdAt })).map { vol ->
            NovelTextMemory.VolumeBlock(
                title = vol.title,
                chapters = work.chaptersOfVolume(vol.id).map { ch ->
                    NovelTextMemory.ChapterLine(
                        order = ch.order,
                        title = ch.title,
                        wordCount = ch.wordCount,
                        status = statusLabel(ch.status.key),
                    )
                },
            )
        }
        val knownVolumeIds = work.volumes.map { it.id }.toSet()
        val loose = work.chapters
            .filter { !knownVolumeIds.contains(it.volumeId) }
            .sortedWith(compareBy({ it.order }, { it.createdAt }))
            .map { ch ->
                NovelTextMemory.ChapterLine(
                    order = ch.order,
                    title = ch.title,
                    wordCount = ch.wordCount,
                    status = statusLabel(ch.status.key),
                )
            }

        val maxOrder = work.chapters.maxOfOrNull { it.order } ?: 0
        val characters = assets.list(workId, AssetType.CHARACTER).filterIsInstance<CharacterAsset>()
            .map { c ->
                NovelTextMemory.CharacterLine(
                    name = c.name,
                    role = c.role,
                    description = c.description,
                    personality = c.personality,
                    background = c.background,
                    tags = c.tags,
                )
            }
        val settings = assets.list(workId, AssetType.SETTING).filterIsInstance<SettingAsset>()
            .map { s ->
                NovelTextMemory.AssetLine(
                    name = s.name,
                    category = s.category,
                    description = s.description,
                    extras = listOf("正文" to s.content) + s.constraints.map { "约束" to it },
                )
            }
        val locations = assets.list(workId, AssetType.LOCATION).filterIsInstance<LocationAsset>()
            .map { l ->
                NovelTextMemory.AssetLine(
                    name = l.name,
                    category = l.category,
                    description = l.description,
                    extras = listOf("规则" to l.rules, "特征" to l.features),
                )
            }
        val factions = assets.list(workId, AssetType.FACTION).filterIsInstance<FactionAsset>()
            .map { f ->
                NovelTextMemory.AssetLine(
                    name = f.name,
                    category = f.category,
                    description = f.description,
                    extras = listOf(
                        "首领" to f.leader,
                        "地盘" to f.territory,
                        "成员" to f.members.joinToString("、"),
                    ),
                )
            }
        val items = assets.list(workId, AssetType.ITEM).filterIsInstance<ItemAsset>()
            .map { i ->
                NovelTextMemory.AssetLine(
                    name = i.name,
                    category = i.category,
                    description = i.description,
                    extras = listOf("品阶" to i.grade, "持有者" to i.owner, "效果" to i.effect),
                    key = i.isKey,
                )
            }
        val references = assets.list(workId, AssetType.REFERENCE).filterIsInstance<ReferenceAsset>()
            .map { r -> NovelTextMemory.ReferenceLine(title = r.name, source = r.source) }

        val hookLines = hooks.map { h ->
            val planted = h.plantedAt
            val idle = if (planted != null && maxOrder > 0) (maxOrder - planted.order).coerceAtLeast(0) else 0
            NovelTextMemory.HookLine(
                title = h.title,
                description = h.description,
                resolved = h.status == NovelHookStatus.CLOSED,
                idleChapters = idle,
            )
        }

        return NovelTextMemory.Input(
            work = NovelTextMemory.WorkInfo(
                id = work.id,
                title = work.title,
                genre = work.genre,
                totalWordCount = work.totalWordCount(),
                chapterCount = work.chapters.size,
                description = "",
            ),
            volumes = volumes,
            looseChapters = loose,
            characters = characters,
            settings = settings,
            locations = locations,
            factions = factions,
            items = items,
            hooks = hookLines,
            references = references,
            updatedAt = System.currentTimeMillis(),
        )
    }
}

/** 读布尔参数：合法（布尔或 "true"/"false" 字符串）返回值；未提供或无法识别返回 null（由调用方显式报错）。 */
private fun JSONObject.optFlag(key: String): Boolean? = when (val v = opt(key)) {
    is Boolean -> v
    is String -> when (v.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
    else -> null
}

/** 作品不存在时的可自纠正提示（列出现有作品；对齐旧线 gWorkNotFound 的信息量）。 */
private fun availableWorksHint(repo: NovelRepository): String {
    val works = repo.loadWorks()
    val avail = if (works.isEmpty()) {
        "当前没有任何作品，可先用 novel_create_work 创建。"
    } else {
        "现有作品：" + works.joinToString("；") { "《${it.title}》(${it.id})" }
    }
    return "先用 novel_list_works 确认 work_id。$avail"
}

/**
 * novel_memory_text：取「十节记忆快照」（P1-4 文本轨）。
 *
 * 快照把作品当前状态压成一页文本（作品信息 / 卷章结构 / 最近章节 / 角色 / 设定 / 地点 /
 * 势力 / 道具 / 伏笔 / 参考资料），供写章前一次性回灌上下文；**5 分钟 TTL 缓存**，
 * 期间重复取用零成本。
 */
object NovelMemoryTextTool {
    const val NAME = "novel_memory_text"

    private const val TEXT_LIMIT = 8000

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description =
            "取十节记忆快照（文本轨）：作品信息 / 卷章结构 / 最近章节 / 角色 / 设定 / 地点 / 势力 / 道具 / 伏笔 / 参考资料，" +
                "一次调用拿到全书现状（结果有 5 分钟缓存）。" +
                "何时用：写新章前一次性对齐世界观与进度；用户问「这本书现在什么情况」。" +
                "细粒度检索用 novel_memory_query，图谱视图用 novel_memory_graph。",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "work_id" to AgentToolParam("string", "作品 id（novel_list_works 获取）"),
            "force_refresh" to AgentToolParam("boolean", "强制重建快照（跳过 5 分钟缓存），默认 false"),
        ),
        required = listOf("tool_title", "work_id"),
        propertyOrdering = listOf("tool_title", "work_id", "force_refresh"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        return try {
            val args = JSONObject(argsJson)
            val workId = args.optString("work_id", "").trim()
            if (workId.isBlank()) {
                return ToolExecutionResult(
                    "Error: work_id 不能为空（用 novel_list_works 获取作品 id）",
                    false,
                    toolTitle = toolTitle,
                )
            }
            val forceRefresh = args.optFlag("force_refresh")
                ?: if (!args.has("force_refresh")) {
                    false
                } else {
                    return ToolExecutionResult(
                        "Error: force_refresh 需为布尔值 true/false",
                        false,
                        toolTitle = toolTitle,
                    )
                }
            val repo = NovelRepository.fromContext(context)
            val work = repo.loadWorks().firstOrNull { it.id == workId }
                ?: return ToolExecutionResult(
                    "Error: 作品不存在：$workId。${availableWorksHint(repo)}",
                    false,
                    toolTitle = toolTitle,
                )
            val cache = NovelTextMemoryCache.shared
            if (forceRefresh) cache.invalidate(workId)
            val wasFresh = cache.isFresh(workId)
            val snapshot = cache.get(workId) {
                val input = NovelTextMemoryProvider.input(context, workId)
                    ?: NovelTextMemory.Input(work = NovelTextMemory.WorkInfo(id = workId, title = work.title))
                NovelTextMemory.build(input)
            }
            val truncated = snapshot.length > TEXT_LIMIT
            val sb = StringBuilder()
            sb.append("十节记忆快照 · 《${work.title}》（${snapshot.length} 字符，")
            sb.append(if (wasFresh && !forceRefresh) "命中 5 分钟缓存" else "已重建")
            sb.append("；缓存 ${NovelTextMemory.CACHE_TTL_MS / 60000} 分钟）\n\n")
            sb.append(if (truncated) snapshot.take(TEXT_LIMIT) else snapshot)
            if (truncated) {
                sb.append("\n\n…（快照超过 $TEXT_LIMIT 字符已截断；细粒度内容改用 novel_memory_query 检索。）")
            }
            ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message ?: "快照生成失败"}", false, toolTitle = toolTitle)
        }
    }
}

/**
 * novel_memory_link：在两段记忆之间建边（图谱轨）。
 */
object NovelMemoryLinkTool {
    const val NAME = "novel_memory_link"

    private val LINK_TYPES = listOf("related", "cause", "foreshadow", "resolve", "same_person")

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description =
            "连接两段记忆（建一条图谱边）：把「玉佩抵押」和「中元节赎回」这类有关系的记忆连起来，" +
                "之后检索命中一端会顺着边带出另一端。" +
                "何时用：发现两条记忆存在因果/伏笔/同一人关系；用户说「把这两条记一起」。" +
                "记忆 id 用 novel_memory_query 或 novel_memory 获取。",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "work_id" to AgentToolParam("string", "作品 id（novel_list_works 获取）"),
            "source_id" to AgentToolParam("string", "起点记忆 id"),
            "target_id" to AgentToolParam("string", "终点记忆 id"),
            "type" to AgentToolParam("string", "关系类型（可选，默认 related）", enumValues = LINK_TYPES),
            "weight" to AgentToolParam("number", "边强度 0.0~1.0（可选，默认 1.0；越界值由存储层收敛到 0.0~1.0）"),
            "description" to AgentToolParam("string", "边的说明（可选）"),
        ),
        required = listOf("tool_title", "work_id", "source_id", "target_id"),
        propertyOrdering = listOf("tool_title", "work_id", "source_id", "target_id", "type", "weight", "description"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        return try {
            val args = JSONObject(argsJson)
            val workId = args.optString("work_id", "").trim()
            val sourceId = args.optString("source_id", "").trim()
            val targetId = args.optString("target_id", "").trim()
            if (workId.isBlank()) {
                return ToolExecutionResult(
                    "Error: work_id 不能为空（用 novel_list_works 获取作品 id）",
                    false,
                    toolTitle = toolTitle,
                )
            }
            if (sourceId.isBlank() || targetId.isBlank()) {
                return ToolExecutionResult(
                    "Error: source_id / target_id 不能为空（用 novel_memory_query 取记忆 id）",
                    false,
                    toolTitle = toolTitle,
                )
            }
            if (sourceId == targetId) {
                return ToolExecutionResult(
                    "Error: 不能把一段记忆连到自己（两端需是不同记忆）",
                    false,
                    toolTitle = toolTitle,
                )
            }
            val type = args.optString("type", NovelMemoryLink.TYPE_RELATED).trim()
                .ifBlank { NovelMemoryLink.TYPE_RELATED }
            if (type !in LINK_TYPES) {
                return ToolExecutionResult(
                    "Error: 未知关系类型 '$type'（可用：${LINK_TYPES.joinToString("/")}）",
                    false,
                    toolTitle = toolTitle,
                )
            }
            val weight = args.optDouble("weight", 1.0).let { if (it.isNaN()) 1.0 else it }
            val description = args.optString("description", "").trim()
            val store = GraphStore.fromContext(context).memoryStore
            val link = store.addLink(
                workId = workId,
                sourceId = sourceId,
                targetId = targetId,
                type = type,
                weight = weight,
                description = description,
            ) ?: return ToolExecutionResult(
                "Error: 建边失败：两端记忆必须都已存在且不同（source_id / target_id 需来自 novel_memory_query 的结果）",
                false,
                toolTitle = toolTitle,
            )
            val fromTitle = store.getMemory(workId, link.sourceId)?.title?.trim()?.ifBlank { link.sourceId } ?: link.sourceId
            val toTitle = store.getMemory(workId, link.targetId)?.title?.trim()?.ifBlank { link.targetId } ?: link.targetId
            val sb = StringBuilder()
            sb.append("已连接两段记忆（边 #${link.id}，${link.type}，强度 ${link.weight}）：\n")
            sb.append("《$fromTitle》 → 《$toTitle》")
            if (link.description.isNotBlank()) sb.append("\n说明：${link.description}")
            ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message ?: "建边失败"}", false, toolTitle = toolTitle)
        }
    }
}

/**
 * novel_memory_graph：记忆图谱视图（节点 + 边 + 五表计数）。
 */
object NovelMemoryGraphTool {
    const val NAME = "novel_memory_graph"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description =
            "记忆图谱视图：返回全部记忆节点与它们之间的边（含五张表的计数：节点/标签/关系/边/属性）。" +
                "何时用：判断记忆库的结构是否健康（有没有孤立节点、标签是否散乱）；给用户展示记忆网络。" +
                "条目检索用 novel_memory_query，整书快照用 novel_memory_text。",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "work_id" to AgentToolParam("string", "作品 id（novel_list_works 获取）"),
            "node_limit" to AgentToolParam("integer", "最多返回节点数，默认 200"),
            "edge_limit" to AgentToolParam("integer", "最多返回边数，默认 400"),
        ),
        required = listOf("tool_title", "work_id"),
        propertyOrdering = listOf("tool_title", "work_id", "node_limit", "edge_limit"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        return try {
            val args = JSONObject(argsJson)
            val workId = args.optString("work_id", "").trim()
            if (workId.isBlank()) {
                return ToolExecutionResult(
                    "Error: work_id 不能为空（用 novel_list_works 获取作品 id）",
                    false,
                    toolTitle = toolTitle,
                )
            }
            val nodeLimit = args.optInt("node_limit", 200).coerceIn(1, 1000)
            val edgeLimit = args.optInt("edge_limit", 400).coerceIn(1, 2000)
            val store = GraphStore.fromContext(context).memoryStore
            val graph = store.graphJson(workId = workId, nodeLimit = nodeLimit, edgeLimit = edgeLimit)
            val nodes = graph.optJSONArray("nodes") ?: JSONArray()
            val edges = graph.optJSONArray("edges") ?: JSONArray()
            if (nodes.length() == 0) {
                return ToolExecutionResult(
                    "记忆图谱还是空的（用 novel_memory_save 存第一条记忆）。",
                    true,
                    toolTitle = toolTitle,
                )
            }
            val sb = StringBuilder()
            sb.append("记忆图谱：${graph.optInt("nodeCount")} 节点 / ${graph.optInt("edgeCount")} 边")
            sb.append("（标签 ${graph.optInt("tagCount")} 个 / 属性 ${graph.optInt("propertyCount")} 条）")
            if (graph.optBoolean("nodesTruncated")) sb.append("；节点超过 $nodeLimit 已截断")
            if (graph.optBoolean("edgesTruncated")) sb.append("；边超过 $edgeLimit 已截断")
            sb.append("\n\n节点（${nodes.length()}）：\n")
            for (i in 0 until nodes.length()) {
                val n = nodes.getJSONObject(i)
                val tags = n.optJSONArray("tags")
                val tagText = if (tags != null && tags.length() > 0) {
                    (0 until tags.length()).map { tags.optString(it, "") }.filter { it.isNotBlank() }.joinToString("、")
                } else {
                    ""
                }
                sb.append("  · 《${n.optString("title", "")}》 kind=${n.optString("kind", "")} 重要度=${n.optDouble("importance", 0.0)}")
                if (tagText.isNotEmpty()) sb.append(" 标签：$tagText")
                sb.append("  [id=${n.optString("id", "")}]\n")
            }
            sb.append("\n边（${edges.length()}）：\n")
            for (i in 0 until edges.length()) {
                val e = edges.getJSONObject(i)
                sb.append("  · ${e.optString("source", "")} → ${e.optString("target", "")}")
                sb.append("  type=${e.optString("type", "")}  weight=${e.optDouble("weight", 0.0)}")
                sb.append("  [id=${e.optLong("id", 0L)}]\n")
            }
            ToolExecutionResult(sb.toString().trimEnd(), true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message ?: "图谱导出失败"}", false, toolTitle = toolTitle)
        }
    }
}

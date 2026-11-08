package com.ai.assistance.quro.ui.novel

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.R
import com.ai.assistance.quro.core.novel.NovelRepository
import com.ai.assistance.quro.core.novel.graph.GraphStore
import com.ai.assistance.quro.core.novel.graph.NovelRelationship
import com.ai.assistance.quro.ui.settings.SettingsRow
import com.ai.assistance.quro.ui.settings.SettingsScaffold
import com.ai.assistance.quro.ui.settings.SettingsSection

/**
 * 小说关系图（Phase 6 关系图 v2，减量移植第 5 块）。
 *
 * ## 两级结构
 * 1. **作品列表**：`NovelRepository.loadWorks()` 的每本书一行，进入后看该书的关系图。
 *    沿用设置页 [SettingsSection]/[SettingsRow] 的 iOS 分组列表 —— 不发明新视觉语言（§0.3）。
 * 2. **关系图画布**：[RelationshipGraphCanvas]，数据来自
 *    `GraphStore.listRelationships(workId)`（人物名为天然主键，端点隐含人物）。
 *
 * ## 人物节点从哪来
 * 数据层存的是「关系」（fromName→toName），没有独立的人物表（人物档案在
 * assets 资料域）。节点 = 关系中出现的全部人名（并集），角色类别从类型文本推断。
 *
 * ## 空态
 * 无作品 → 引导去写章工具里建作品；有作品但无关系 → 画布给出单节点提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelRelationshipGraphScreen(
    onBack: () -> Unit,
    initialWorkId: String? = null,
) {
    val context = LocalContext.current
    val repository = remember(context) { NovelRepository.fromContext(context) }

    var works by remember { mutableStateOf(repository.loadWorks()) }
    var workId by remember { mutableStateOf(initialWorkId ?: works.firstOrNull()?.id) }

    // 选中作品后进入画布；返回列表把 workId 清空。
    if (workId == null) {
        SettingsScaffold(title = stringResource(R.string.novel_graph_title), onBack = onBack) {
            if (works.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(NovelRhythm.screenHorizontalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(48.dp))
                    Text(
                        text = stringResource(R.string.novel_graph_no_works_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = NovelTextPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.novel_graph_no_works_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = NovelTextSecondary,
                    )
                }
            } else {
                SettingsSection(
                    header = stringResource(R.string.novel_graph_pick_work),
                    footer = stringResource(R.string.novel_graph_pick_work_footer),
                ) {
                    works.forEachIndexed { index, work ->
                        SettingsRow(
                            title = work.title.ifBlank { work.id },
                            subtitle = novelWorkSubtitle(context, work.id, work.chapters.size),
                            onClick = { workId = work.id },
                            showDivider = index != works.lastIndex,
                        )
                    }
                }
            }
        }
        return
    }

    val currentId = workId.orEmpty()
    NovelGraphCanvasScreen(
        workId = currentId,
        onBack = { workId = null },
    )
}

/** 关系图画布所在的第二级页面（自己的 TopAppBar，带作品名与统计）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelGraphCanvasScreen(
    workId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val graphStore = remember(context) { GraphStore.fromContext(context) }

    var relationships by remember(workId) { mutableStateOf(graphStore.listRelationships(workId)) }
    var selectedId by remember(workId) { mutableStateOf<String?>(null) }

    val workTitle = remember(workId, relationships) {
        NovelRepository.fromContext(context).loadWorks().firstOrNull { it.id == workId }?.title ?: workId
    }

    val (nodes, edges) = remember(relationships) { buildGraph(relationships) }
    val title = stringResource(R.string.novel_graph_title)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = workTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.novel_graph_settings_back),
                        )
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = NovelPageBackground,
                ),
            )
        },
        containerColor = NovelPageBackground,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            RelationshipGraphCanvas(
                nodes = nodes,
                edges = edges,
                selectedId = selectedId,
                onSelect = { selectedId = it },
                modifier = Modifier.fillMaxSize(),
            )

            // 顶部统计条：一本书几个人物、几条关系（§5.7 数据卡思路，简化为一行脚注）。
            Text(
                text = stringResource(
                    R.string.novel_graph_stats,
                    nodes.size,
                    edges.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = NovelTextSecondary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NovelCardSurface.copy(alpha = 0.9f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )

            // 选中节点后的详情面板（底部卡片，仍用分组列表语言）。
            val selectedNode = nodes.firstOrNull { it.id == selectedId }
            if (selectedNode != null) {
                val related = remember(selectedNode, relationships) {
                    relationships.filter {
                        it.fromName == selectedNode.label || it.toName == selectedNode.label
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(NovelRhythm.screenHorizontalPadding)
                        .clip(RoundedCornerShape(NovelRhythm.cardCornerRadius))
                        .background(NovelCardSurface)
                        .padding(12.dp),
                ) {
                    Text(
                        text = selectedNode.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = NovelTextPrimary,
                    )
                    if (selectedNode.category.isNotBlank()) {
                        Text(
                            text = selectedNode.category,
                            style = MaterialTheme.typography.labelSmall,
                            color = NovelTextSecondary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    if (related.isEmpty()) {
                        Text(
                            text = stringResource(R.string.novel_graph_node_no_relations),
                            style = MaterialTheme.typography.bodySmall,
                            color = NovelTextSecondary,
                        )
                    } else {
                        related.take(8).forEach { rel ->
                            val other = if (rel.fromName == selectedNode.label) rel.toName else rel.fromName
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .height(8.dp)
                                        .padding(end = 8.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .height(8.dp)
                                            .fillMaxWidth(0f)
                                            .background(relationshipEdgeColor(rel.type)),
                                    )
                                }
                                Text(
                                    text = stringResource(
                                        R.string.novel_graph_relation_item,
                                        other,
                                        rel.type,
                                        rel.strength,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NovelTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (related.size > 8) {
                            Text(
                                text = stringResource(R.string.novel_graph_relation_more, related.size - 8),
                                style = MaterialTheme.typography.labelSmall,
                                color = NovelTextSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 把关系表装配成图：节点 = 关系端点人名并集；边去重（同向只留一条，双向合并）。
 */
private fun buildGraph(relationships: List<NovelRelationship>): Pair<List<GraphNode>, List<GraphEdge>> {
    val names = LinkedHashSet<String>()
    relationships.forEach {
        if (it.fromName.isNotBlank()) names.add(it.fromName)
        if (it.toName.isNotBlank()) names.add(it.toName)
    }
    val degree = HashMap<String, Int>(names.size)
    relationships.forEach {
        degree[it.fromName] = (degree[it.fromName] ?: 0) + 1
        degree[it.toName] = (degree[it.toName] ?: 0) + 1
    }
    val nodes = names.map { name ->
        GraphNode(
            id = name,
            label = name,
            category = relationships.firstOrNull { it.fromName == name || it.toName == name }?.type.orEmpty(),
            degree = degree[name] ?: 0,
        )
    }
    // 边去重：多人名可能重复登记同一条关系（工具层不强制幂等），按 (from,to) 取首条。
    val seen = HashSet<Pair<String, String>>()
    val edges = ArrayList<GraphEdge>(relationships.size)
    relationships.forEach { rel ->
        if (rel.fromName.isBlank() || rel.toName.isBlank()) return@forEach
        val key = rel.fromName to rel.toName
        if (seen.add(key)) {
            edges.add(
                GraphEdge(
                    fromId = rel.fromName,
                    toId = rel.toName,
                    label = rel.type,
                    strength = rel.strength.coerceIn(1, 5),
                    bidirectional = false,
                ),
            )
        }
    }
    return nodes to edges
}

/** 作品行副标题：章节数 + 关系数。 */
private fun novelWorkSubtitle(context: Context, workId: String, chapterCount: Int): String {
    val relCount = try {
        GraphStore.fromContext(context).listRelationships(workId).size
    } catch (_: Exception) {
        0
    }
    return context.getString(R.string.novel_graph_work_subtitle, chapterCount, relCount)
}

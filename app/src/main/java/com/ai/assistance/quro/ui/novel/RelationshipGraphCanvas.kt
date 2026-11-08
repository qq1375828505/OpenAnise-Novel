package com.ai.assistance.quro.ui.novel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.R

/**
 * 关系图画布（UI_SPEC §5.8 + §8.5）。
 *
 * ## 呈现
 * - 画布底色 = [NovelPageBackground]；节点 = [NovelCardSurface] 圆 + 1dp [NovelOutline] 描边，
 *   直径 [NovelGraphTokens.nodeDiameter]；标签 [NovelType.subhead] 置于节点下方。
 * - 边：强度 1-5 → 线宽 1-4dp；类别 → [relationshipEdgeColor] 取色；不透明度 60%。
 *   选中节点时其邻边升至 100% 并加 6dp 模糊光晕。
 *
 * ## 交互
 * - 拖拽平移（世界坐标），双指缩放（0.4x–3x），单击空白取消选中、单击节点选中。
 *
 * ## 实现取舍
 * 字符串标签与边标签走**布局层**（`Box` + `Modifier.offset` + `Text`），Canvas 只画几何
 * （线 / 圆）。理由：`DrawScope.drawText(textLayoutResult = …)` 的签名在不同 Compose
 * 版本间有过变动，而本仓已在 ChatMiscViews / ChatScreen 大量使用 offset 定位布局层文本
 * —— 走有先例的 API 面，把编译风险压到最低（本地无法编译，CI 是唯一编译器）。
 *
 * ## 禁令遵循（§0.3）
 * 绘制块里不读 Composable 状态：颜色/密度在进入 Canvas 前一次性取值。
 * 帧循环只在布局未 [GraphLayoutEngine.settled] 时续跑。
 */
@Composable
fun RelationshipGraphCanvas(
    nodes: List<GraphNode>,
    edges: List<GraphEdge>,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    onSelect: (String?) -> Unit = {},
) {
    if (nodes.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(NovelPageBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.novel_graph_canvas_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = NovelTextSecondary,
                modifier = Modifier.padding(NovelRhythm.screenHorizontalPadding),
            )
        }
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        // 布局引擎按画布尺寸创建；尺寸变化（旋横屏 / 键盘）时重建。
        val engine = remember(nodes, edges, widthPx, heightPx) {
            GraphLayoutEngine(
                nodes = nodes,
                edges = edges,
                boundsWidth = widthPx,
                boundsHeight = heightPx,
            )
        }

        var pan by remember(engine) { mutableStateOf(Offset.Zero) }
        var zoom by remember(engine) { mutableFloatStateOf(1f) }
        // 每帧递增：Canvas 读取它即可建立重组依赖，实现「布局未静止就重绘」。
        var layoutVersion by remember(engine) { mutableIntStateOf(0) }

        // 帧循环：静止即退出协程，不再消耗帧。
        LaunchedEffect(engine) {
            while (true) {
                withFrameNanos { }
                engine.step()
                if (engine.settled) break
                layoutVersion++
            }
        }

        // 主题与令牌一次性取值（§0.3：绘制块内不读 Composable 状态）。
        val density = LocalDensity.current
        val pageBg = NovelPageBackground
        val cardSurface = NovelCardSurface
        val outline = NovelOutline
        val labelColor = NovelTextPrimary
        val edgeLabelColor = NovelTextSecondary
        val accent = NovelAccent

        val nodeDiameterPx = with(density) { NovelGraphTokens.nodeDiameter.toPx() }
        // 标签预算：算成 px 与 dp 两份——dp 给 widthIn 约束，px 给 offset 居中。
        val labelMaxWidthDp = with(density) { (nodeDiameterPx * 1.9f).toDp() }
        val labelMaxWidthPx = with(density) { labelMaxWidthDp.toPx() }
        val labelGapPx = with(density) { NovelGraphTokens.nodeLabelGap.toPx() }
        val strokePx = with(density) { NovelGraphTokens.nodeStrokeWidth.toPx() }
        val glowPx = with(density) { NovelGraphTokens.edgeHighlightGlow.toPx() }

        fun worldToScreen(p: GraphLayoutEngine.Position): Offset {
            val cx = widthPx / 2f
            val cy = heightPx / 2f
            return Offset(
                x = cx + (p.x - cx) * zoom + pan.x,
                y = cy + (p.y - cy) * zoom + pan.y,
            )
        }

        fun screenToWorld(s: Offset): Offset {
            val cx = widthPx / 2f
            val cy = heightPx / 2f
            return Offset(
                x = cx + (s.x - pan.x - cx) / zoom,
                y = cy + (s.y - pan.y - cy) / zoom,
            )
        }

        val neighbours: Set<String> = selectedId?.let { sel ->
            edges.filter { it.fromId == sel || it.toId == sel }
                .map { if (it.fromId == sel) it.toId else it.fromId }
                .toSet()
        } ?: emptySet()

        // 选中态派生量：供绘制层与布局层共用（都只读快照，不读状态）。
        val activeEdges = edges.filter { e ->
            selectedId != null && (e.fromId == selectedId || e.toId == selectedId)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(pageBg)
                .pointerInput(engine) {
                    detectTransformGestures { centroid, panDelta, zoomChange, _ ->
                        when {
                            // 双指 → 缩放（以双指中心为锚点）。
                            zoomChange != 1f -> {
                                zoom = (zoom * zoomChange).coerceIn(0.4f, 3f)
                                pan += centroid - (centroid - pan) * zoomChange
                            }
                            else -> pan += panDelta
                        }
                    }
                }
                .pointerInput(engine) {
                    detectTapGestures(
                        onTap = { offset ->
                            val w = screenToWorld(offset)
                            val hitRadius = nodeDiameterPx / zoom / 2f
                            val hit = nodes.lastOrNull { n ->
                                val p = engine.positions.getValue(n.id)
                                val dx = p.x - w.x
                                val dy = p.y - w.y
                                dx * dx + dy * dy <= hitRadius * hitRadius
                            }
                            onSelect(hit?.id)
                        },
                    )
                },
        ) {
            // ── 几何层：边 + 节点圆环 ──
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 读取布局版本：未静止时每帧变化 → 画布每帧重绘；静止后引用稳定 → 停止重绘。
                @Suppress("UNUSED_VARIABLE")
                val layoutTick = layoutVersion

                val selected = selectedId

                // ── 边 ──
                edges.forEach { e ->
                    val a = worldToScreen(engine.positions.getValue(e.fromId))
                    val b = worldToScreen(engine.positions.getValue(e.toId))
                    val active = selected != null &&
                        (e.fromId == selected || e.toId == selected)

                    val baseColor =
                        if (e.label.isBlank()) outline else relationshipEdgeColor(e.label)
                    val color = when {
                        active -> baseColor.copy(alpha = NovelGraphTokens.EDGE_HIGHLIGHT_ALPHA)
                        selected != null ->
                            baseColor.copy(alpha = NovelGraphTokens.EDGE_BASE_ALPHA * 0.35f)
                        else -> baseColor.copy(alpha = NovelGraphTokens.EDGE_BASE_ALPHA)
                    }
                    val wPx = with(density) { NovelGraphTokens.edgeWidth(e.strength).toPx() }

                    // 选中节点的邻边：先铺一层宽光晕，再画实线（§5.8 6dp 模糊光晕用双层近似）。
                    if (active) {
                        drawLine(
                            color = color.copy(alpha = 0.22f),
                            start = a,
                            end = b,
                            strokeWidth = wPx + glowPx * 2,
                            cap = StrokeCap.Round,
                        )
                    }
                    drawLine(
                        color = color,
                        start = a,
                        end = b,
                        strokeWidth = wPx,
                        cap = StrokeCap.Round,
                    )
                }

                // ── 节点 ──
                // 度数高的后画（视觉上在上层）；sortedBy 是稳定排序，帧间不抖动。
                nodes.sortedBy { it.degree }.forEach { n ->
                    val c = worldToScreen(engine.positions.getValue(n.id))
                    val r = nodeDiameterPx / 2f * zoom
                    val isSelected = n.id == selected
                    val isNeighbour = neighbours.contains(n.id)
                    val dimmed = selected != null && !isSelected && !isNeighbour
                    val fill = cardSurface.copy(alpha = if (dimmed) 0.45f else 1f)
                    val ringColor = relationshipEdgeColor(n.category.ifBlank { "其他" })
                    val ringAlpha = when {
                        isSelected -> 1f
                        isNeighbour -> 0.85f
                        dimmed -> 0.25f
                        else -> 0.5f
                    }

                    // 外圈角色色（弱）→ 卡片圆 → 1dp 描边。
                    drawCircle(
                        color = ringColor.copy(alpha = ringAlpha * 0.28f),
                        radius = r * 1.16f,
                        center = c,
                    )
                    drawCircle(color = fill, radius = r, center = c)
                    drawCircle(
                        color = ringColor.copy(alpha = ringAlpha),
                        radius = r,
                        center = c,
                        style = Stroke(width = strokePx),
                    )
                    if (isSelected) {
                        drawCircle(
                            color = accent,
                            radius = r * 1.06f,
                            center = c,
                            style = Stroke(width = strokePx * 2),
                        )
                    }
                }
            }

            // ── 标签层：人名与关系名走布局层 Text（见类注释的取舍） ──
            nodes.sortedBy { it.degree }.forEach { n ->
                val c = worldToScreen(engine.positions.getValue(n.id))
                val isSelected = n.id == selectedId
                val dimmed = selectedId != null && !isSelected && !neighbours.contains(n.id)
                if (n.label.isNotBlank()) {
                    Text(
                        text = n.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = labelColor.copy(alpha = if (dimmed) 0.5f else 1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    (c.x - labelMaxWidthPx / 2f).toInt(),
                                    (c.y + nodeDiameterPx / 2f * zoom + labelGapPx).toInt(),
                                )
                            }
                            .widthIn(max = labelMaxWidthDp),
                    )
                }
            }

            // 边标签：仅「无选中」或「属于选中邻边」时画，避免整屏糊满。
            val labelledEdges = if (selectedId == null) edges else activeEdges
            labelledEdges.forEach { e ->
                if (e.label.isBlank()) return@forEach
                val a = worldToScreen(engine.positions.getValue(e.fromId))
                val b = worldToScreen(engine.positions.getValue(e.toId))
                Text(
                    text = e.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = edgeLabelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .offset {
                            androidx.compose.ui.unit.IntOffset(
                                ((a.x + b.x) / 2f - 40f).toInt(),
                                ((a.y + b.y) / 2f - 8f).toInt(),
                            )
                        }
                        .widthIn(max = 96.dp),
                )
            }

            // 底部提示（不参与画布变换，走普通布局层）。
            if (nodes.size < 2) {
                Text(
                    text = stringResource(R.string.novel_graph_canvas_single),
                    style = MaterialTheme.typography.bodySmall,
                    color = NovelTextSecondary,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(NovelRhythm.screenHorizontalPadding),
                )
            }
        }
    }
}

package com.ai.assistance.quro.ui.novel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
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
 * - 拖拄节点可唤醒布局（[GraphLayoutEngine.wake]），松手后弹回（spring）。
 *
 * ## 禁令遵循（§0.3）
 * 绘制块里不读 Composable 状态：颜色/密度/明暗在进入 Canvas 前一次性取值。
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
            GraphLayoutEngine(nodes = nodes, edges = edges, boundsWidth = widthPx, boundsHeight = heightPx)
        }

        var pan by remember(engine) { mutableStateOf(Offset.Zero) }
        var zoom by remember(engine) { mutableFloatStateOf(1f) }
        var draggingNode by remember(engine) { mutableStateOf<String?>(null) }
        var frame by remember(engine) { mutableStateOf(0L) }

        // 帧循环：未静止才续跑。
        LaunchedEffect(engine) {
            while (true) {
                withFrameNanos { frame = it }
                if (engine.settled) break
                engine.step()
            }
        }

        // 主题与令牌一次性取值（§0.3：绘制块内不读 Composable 状态）。
        val density = LocalDensity.current
        val measurer: TextMeasurer = rememberTextMeasurer()
        val pageBg = NovelPageBackground
        val cardSurface = NovelCardSurface
        val outline = NovelOutline
        val labelColor = NovelTextPrimary
        val edgeLabelColor = NovelTextSecondary
        val accent = NovelAccent
        val shape = MaterialTheme.shapes.extraLarge

        // 节点标签排版（subhead），超出宽度的省略。
        val labelStyle = TextStyle(
            fontSize = NovelType.subhead,
            color = labelColor,
        )
        val nodeDiameterPx = with(density) { NovelGraphTokens.nodeDiameter.toPx() }
        val labelMaxWidthPx = nodeDiameterPx * 1.9f
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
                                pan += centroid - (centroid - pan) * (zoomChange)
                            }
                            else -> pan += panDelta
                        }
                    }
                }
                .pointerInput(engine) {
                    detectTapGestures(
                        onTap = { offset ->
                            val w = screenToWorld(offset)
                            val hit = nodes.lastOrNull { n ->
                                val p = engine.positions.getValue(n.id)
                                val dx = p.x - w.x
                                val dy = p.y - w.y
                                dx * dx + dy * dy <= (nodeDiameterPx / zoom) * (nodeDiameterPx / zoom) / 4f
                            }
                            onSelect(hit?.id)
                        },
                    )
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // frame 读取：未静止时每帧重绘。
                @Suppress("UNUSED_EXPRESSION") frame

                val selected = selectedId
                val neighbours = selected?.let { sel ->
                    edges.filter { it.fromId == sel || it.toId == sel }
                        .map { if (it.fromId == sel) it.toId else it.fromId }
                        .toSet()
                } ?: emptySet()

                // ── 边 ──
                edges.forEach { e ->
                    val pa = engine.positions.getValue(e.fromId)
                    val pb = engine.positions.getValue(e.toId)
                    val a = worldToScreen(pa)
                    val b = worldToScreen(pb)
                    val active = selected != null &&
                        (e.fromId == selected || e.toId == selected)

                    val baseColor = if (e.label.isBlank()) outline else relationshipEdgeColor(e.label)
                    val color = when {
                        active -> baseColor.copy(alpha = NovelGraphTokens.EDGE_HIGHLIGHT_ALPHA)
                        selected != null -> baseColor.copy(alpha = NovelGraphTokens.EDGE_BASE_ALPHA * 0.35f)
                        else -> baseColor.copy(alpha = NovelGraphTokens.EDGE_BASE_ALPHA)
                    }
                    val wPx = with(density) { NovelGraphTokens.edgeWidth(e.strength).toPx() }

                    // 选中节点的邻边：加一层宽光晕再画实线（§5.8）。
                    if (active) {
                        drawLine(
                            color = color.copy(alpha = 0.22f),
                            start = a,
                            end = b,
                            strokeWidth = wPx + glowPx * 2,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                    }
                    drawLine(
                        color = color,
                        start = a,
                        end = b,
                        strokeWidth = wPx,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )

                    // 边标签：只在「无选中」或「该边属于选中邻边」时画，避免整屏糊满。
                    if (e.label.isNotBlank() && (selected == null || active)) {
                        val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                        val measured: TextLayoutResult = measurer.measure(
                            text = e.label,
                            style = TextStyle(fontSize = NovelType.caption1, color = edgeLabelColor),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            constraints = androidx.compose.ui.unit.Constraints(
                                maxWidth = labelMaxWidthPx.toInt(),
                            ),
                        )
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(mid.x - measured.size.width / 2f, mid.y - measured.size.height / 2f),
                        )
                    }
                }

                // ── 节点 ──
                nodes.forEach { n ->
                    val p = engine.positions.getValue(n.id)
                    val c = worldToScreen(p)
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

                    // 姓名标签（节点下方；取首字或短名截断）。
                    val name = n.label
                    if (name.isNotBlank()) {
                        val measured = measurer.measure(
                            text = name,
                            style = labelStyle.copy(
                                color = labelColor.copy(alpha = if (dimmed) 0.5f else 1f),
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            constraints = androidx.compose.ui.unit.Constraints(
                                maxWidth = labelMaxWidthPx.toInt(),
                            ),
                        )
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(
                                c.x - measured.size.width / 2f,
                                c.y + r + labelGapPx,
                            ),
                        )
                    }
                }
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

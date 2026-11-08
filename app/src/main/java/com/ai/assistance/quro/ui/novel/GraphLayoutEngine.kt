package com.ai.assistance.quro.ui.novel

import kotlin.math.sqrt

/**
 * 关系图力导向布局引擎（UI_SPEC §8.5「Compose Canvas + 斥力物理」）。
 *
 * ## 设计
 * - 纯 Kotlin，零 Compose / 零 Android 依赖 → 可单元测试、可在后台线程推进。
 * - 每帧推进一次 [step]，状态全部在 [state] 里，Composable 只负责读坐标绘制。
 * - 固定迭代上限 + 阻尼保证收敛；[settled] 置位后调用方即可停帧循环省电。
 *
 * ## 力模型（Fruchterman-Reingold 变体，去掉 cooling schedule）
 * - 斥力：所有节点对之间 ~ k²/d（库仑），防止叠一起。
 * - 引力：有边相连的节点 ~ d²/k（弹簧），把关系拉近。
 * - 向心：所有节点受一个弱 ~ r 的向心力，防孤立节点飞出屏幕。
 * - 阻尼：每帧速度乘 [NovelGraphTokens.SIM_DAMPING]，指数衰减到静止。
 *
 * ## 坐标
 * 使用「世界坐标」，随平移/缩放在画布上变换；初始位置按圆环均布，避免
 * 全部堆在原点导致第一帧斥力爆炸。单位为 dp（画布绘制时直接用）。
 */
class GraphLayoutEngine(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    private val boundsWidth: Float,
    private val boundsHeight: Float,
) {
    /** 布局中的节点位置（与 [nodes] 同序同 id）。 */
    class Position(var x: Float, var y: Float, var vx: Float = 0f, var vy: Float = 0f)

    val positions: MutableMap<String, Position> = LinkedHashMap(nodes.size)

    private val byId: Map<String, GraphNode> = nodes.associateBy { it.id }

    /** 理想间距 k：随节点数开方放大，节点多时自动铺开。 */
    private val idealDistance: Float = sqrt((boundsWidth * boundsHeight) / (nodes.size.coerceAtLeast(1))).coerceIn(120f, 420f)

    private var iterations = 0

    /** 布局是否已静止（位移与速度都低于阈值）——调用方可停帧。 */
    var settled: Boolean = false
        private set

    init {
        require(nodes.size == nodes.map { it.id }.distinct().size) { "节点 id 必须唯一" }
        require(edges.all { byId.containsKey(it.fromId) && byId.containsKey(it.toId) }) {
            "边的端点必须存在于节点表"
        }
        seedPositions()
    }

    /** 圆环均布初始位置，避免第一帧斥力爆炸。 */
    private fun seedPositions() {
        val cx = boundsWidth / 2f
        val cy = boundsHeight / 2f
        val radius = minOf(boundsWidth, boundsHeight) / 2f * 0.72f
        nodes.forEachIndexed { i, n ->
            val a = (2.0 * Math.PI * i / nodes.size) - Math.PI / 2
            positions[n.id] = Position(
                x = (cx + radius * kotlin.math.cos(a)).toFloat(),
                y = (cy + radius * kotlin.math.sin(a)).toFloat(),
            )
        }
    }

    /** 推进一帧（dt 固定，见 [NovelGraphTokens.SIM_MS_PER_FRAME]）。 */
    fun step() {
        if (settled || nodes.size < 2) {
            if (nodes.size < 2) settle()
            return
        }
        iterations++
        // 每帧只做一次累加再统一施加，避免顺序偏差（同步更新）。
        val fx = HashMap<String, Float>(nodes.size)
        val fy = HashMap<String, Float>(nodes.size)
        nodes.forEach { fx[it.id] = 0f; fy[it.id] = 0f }

        repulse(fx, fy)
        attract(fx, fy)
        center(fx, fy)

        var maxMove = 0f
        nodes.forEach { n ->
            val p = positions.getValue(n.id)
            val ax = fx.getValue(n.id)
            val ay = fy.getValue(n.id)
            p.vx = (p.vx + ax) * NovelGraphTokens.SIM_DAMPING
            p.vy = (p.vy + ay) * NovelGraphTokens.SIM_DAMPING
            val nx = p.x + p.vx
            val ny = p.y + p.vy
            val dx = nx - p.x
            val dy = ny - p.y
            if (dx * dx + dy * dy > maxMove * maxMove) maxMove = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            p.x = nx
            p.y = ny
        }
        if ((maxMove < 0.35f && nodes.all { n ->
                val p = positions.getValue(n.id)
                p.vx * p.vx + p.vy * p.vy < 0.5f
            }) || iterations >= NovelGraphTokens.SIM_MAX_ITERATIONS) {
            settle()
        }
    }

    private fun repulse(fx: MutableMap<String, Float>, fy: MutableMap<String, Float>) {
        val k2 = idealDistance * idealDistance
        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val a = nodes[i]
                val b = nodes[j]
                val pa = positions.getValue(a.id)
                val pb = positions.getValue(b.id)
                var dx = pa.x - pb.x
                var dy = pa.y - pb.y
                var d2 = dx * dx + dy * dy
                if (d2 < 0.01f) {
                    // 完全重合时给一个确定性的微小偏移，防止 NaN。
                    dx = 0.6f
                    dy = 0.6f
                    d2 = dx * dx + dy * dy
                }
                val f = k2 / d2
                val d = sqrt(d2.toDouble()).toFloat()
                val ux = dx / d
                val uy = dy / d
                fx[a.id] = fx.getValue(a.id) + f * ux
                fy[a.id] = fy.getValue(a.id) + f * uy
                fx[b.id] = fx.getValue(b.id) - f * ux
                fy[b.id] = fy.getValue(b.id) - f * uy
            }
        }
    }

    private fun attract(fx: MutableMap<String, Float>, fy: MutableMap<String, Float>) {
        edges.forEach { e ->
            // 双向边只算一次（避免同一对节点被拉两倍）；单向按 from→to 拉。
            if (e.bidirectional && e.fromId > e.toId) return@forEach
            val a = e.fromId
            val b = e.toId
            val pa = positions.getValue(a)
            val pb = positions.getValue(b)
            val dx = pb.x - pa.x
            val dy = pb.y - pa.y
            val d = sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(0.01f)
            val f = (d * d) / idealDistance
            val ux = dx / d
            val uy = dy / d
            fx[a] = fx.getValue(a) + f * ux
            fy[a] = fy.getValue(a) + f * uy
            fx[b] = fx.getValue(b) - f * ux
            fy[b] = fy.getValue(b) - f * uy
        }
    }

    private fun center(fx: MutableMap<String, Float>, fy: MutableMap<String, Float>) {
        val cx = boundsWidth / 2f
        val cy = boundsHeight / 2f
        nodes.forEach { n ->
            val p = positions.getValue(n.id)
            fx[n.id] = fx.getValue(n.id) + (cx - p.x) * 0.012f
            fy[n.id] = fy.getValue(n.id) + (cy - p.y) * 0.012f
        }
    }

    private fun settle() {
        settled = true
        positions.values.forEach { it.vx = 0f; it.vy = 0f }
    }

    /** 复位到初始圆环（例如切换作品时重新布局）。 */
    fun reset() {
        settled = false
        iterations = 0
        seedPositions()
    }

    /** 强制再跑若干帧（拖拄节点后唤醒布局）。 */
    fun wake() {
        settled = false
    }
}

/** 图中的节点。人物名为天然主键（数据层约定，改名 = 剧情事件）。 */
data class GraphNode(
    val id: String,
    val label: String,
    /** 人物/势力/地点/物品/能力/情节线/设定/参考，决定节点角色色。 */
    val category: String = "",
    /** 该节点的关联边数，用于决定视觉权重。 */
    val degree: Int = 0,
)

/**
 * 图中的边。[fromId]/[toId] 引用 [GraphNode.id]。
 * @param strength 强度 1-5（数据层 `strength` 字段）
 * @param label 关系类型（亲人/恋人/师徒/盟友/竞争/仇敌/上下级/其他）
 */
data class GraphEdge(
    val fromId: String,
    val toId: String,
    val label: String = "",
    val strength: Int = 3,
    /** 数据层允许关系表出现双向记录；画上去只画一条线即可。 */
    val bidirectional: Boolean = false,
)

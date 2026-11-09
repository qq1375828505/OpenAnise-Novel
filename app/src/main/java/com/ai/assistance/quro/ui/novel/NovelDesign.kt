package com.ai.assistance.quro.ui.novel

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.ui.theme.LocalChatPalette

/**
 * OpenAnise-Novel 设计令牌单一真相源。
 *
 * ## 契约（docs/design/OpenAnise-Novel_UI_SPEC.md §1 方法论）
 * - 本文件是 Kotlin 侧唯一的节奏/颜色来源；**禁止在 Composable 里写裸 dp/sp 数值**。
 * - 颜色值不自己发明：从 [MaterialTheme] 的 [androidx.compose.material3.ColorScheme] 取，
 *   因为基座 [com.ai.assistance.quro.ui.theme.MinisTheme] 已经把 iOS 分组表面（page/card/outline）
 *   与去饱和强调色映射进了 Material 色板 —— 数值与 `design_tokens.json` 逐项对齐。
 * - 修改任何常量前，先改 `docs/design/design_tokens.json` 与 UI_SPEC，再改这里，三者必须一致。
 * - 语义命名（`cardSurface` 而非 `white`）；值变了名字不必改。
 *
 * ## 关系图专属（§5.8）
 * 节点直径、边宽映射、描边、邻边高亮不透明度、光晕半径都在这里，画布代码只读令牌。
 */

// ─── 分组表面（iOS grouped 语义，来自 MaterialTheme 的 Base 层） ─────────────────

/** 页面底：iOS systemGroupedBackground（亮 #F2F2F7 / 暗 #000）。 */
val NovelPageBackground: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background

/** 分组卡片面：iOS secondarySystemGroupedBackground（亮 #FFFFFF / 暗 #1C1C1E）。 */
val NovelCardSurface: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerLow

/** 卡片内次级块。 */
val NovelCardElevated: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh

/** 行分隔线 iOS separator（亮 #D1D1D6 / 暗 #38383A）。 */
val NovelSeparator: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant

/** 卡片外描边 / 输入框描边。 */
val NovelOutline: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outline

// ─── 文本三级 ─────────────────────────────────────────────────────────────────

/** 正文与行标题（亮 15.6:1 / 暗 14.2:1）。 */
val NovelTextPrimary: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface

/** 描述与元信息（亮 10.2:1 / 暗 8.1:1）。 */
val NovelTextSecondary: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

/** 占位符 / 分组脚注。 */
val NovelTextTertiary: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outline

// ─── 强调与状态 ────────────────────────────────────────────────────────────────

/** 主色：选中态、链接、分组标题（亮 #528AD2 / 暗 #6A94CE）。 */
val NovelAccent: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary

/**
 * 伏笔已回收、章节完成（design_tokens.json §2.3 success：亮 #34C759 / 暗 #30D158）。
 * 不挪用 Material 的 tertiary——那在本基座是蓝系（#46617A），会把「完成」读成「信息」。
 */
val NovelSuccess: Color
    @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFF30D158) else Color(0xFF34C759)

/** 字数偏低、爽点不足（§2.3 warning：#FF9F0A 亮暗同值）。 */
val NovelWarning: Color
    @Composable @ReadOnlyComposable get() = Color(0xFFFF9F0A)

/** 删除、失败、冲突（§2.3 danger：亮 #FF3B30 / 暗 #FF453A）。 */
val NovelDanger: Color
    @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFFFF453A) else Color(0xFFFF3B30)

private val isDark: Boolean
    @Composable @ReadOnlyComposable get() = LocalChatPalette.current.isDark

/**
 * 关系类别调色板（design_tokens.json §color.categoryPalette）。
 * 八类等饱和度等明度，不因类别而异；亮暗同一组（画布底色随主题切换已足够区分）。
 */
object NovelCategoryPalette {
    val character = Color(0xFF4C8DF6)
    val faction = Color(0xFFF59E42)
    val location = Color(0xFF3FB984)
    val item = Color(0xFFA78BFA)
    val ability = Color(0xFFF2626B)
    val plotline = Color(0xFF5B7CFA)
    val setting = Color(0xFF2BB8C4)
    val reference = Color(0xFF8A8A8E)
}

/**
 * 关系类型 → 调色板。类型是自由文本（工具 schema 里预设八类 + 「其他」），
 * 未命中时落到 [NovelCategoryPalette.reference]。
 */
fun relationshipEdgeColor(type: String): Color {
    val t = type.trim()
    return when {
        t.isEmpty() -> NovelCategoryPalette.reference
        t.contains("亲人") || t.contains("家") || t.contains("血") -> NovelCategoryPalette.character
        t.contains("恋") || t.contains("情") || t.contains("伴") -> NovelCategoryPalette.plotline
        t.contains("师") || t.contains("徒") || t.contains("传承") -> NovelCategoryPalette.setting
        t.contains("盟") || t.contains("友") || t.contains("同") -> NovelCategoryPalette.location
        t.contains("竞") || t.contains("对") || t.contains("敌") -> NovelCategoryPalette.ability
        t.contains("仇") || t.contains("恨") -> NovelCategoryPalette.faction
        t.contains("上级") || t.contains("下级") || t.contains("上下") || t.contains("麾") -> NovelCategoryPalette.item
        else -> NovelCategoryPalette.reference
    }
}

// ─── 节奏（design_tokens.json §rhythm） ────────────────────────────────────────

object NovelRhythm {
    val screenHorizontalPadding: Dp = 16.dp
    val cardCornerRadius: Dp = 12.dp
    val firstSectionTopGap: Dp = 16.dp
    val sectionTopGap: Dp = 24.dp
    val headerToCardGap: Dp = 8.dp
    val cardToFooterGap: Dp = 8.dp
    val interRowGap: Dp = 8.dp
    val rowMinHeight: Dp = 44.dp
    val rowIconSize: Dp = 29.dp
    val rowIconRadius: Dp = 6.5.dp
    val dividerInset: Dp = 16.dp
    val tabBarHeight: Dp = 49.dp
    val sheetCornerRadius: Dp = 10.dp
    val touchTargetMin: Dp = 44.dp
}

// ─── 字阶（design_tokens.json §typography） ────────────────────────────────────

object NovelType {
    val largeTitle = 34.sp
    val title1 = 28.sp
    val title2 = 22.sp
    val title3 = 20.sp
    val headline = 17.sp
    val body = 17.sp
    val reading = 19.sp
    val callout = 16.sp
    val subhead = 15.sp
    val footnote = 13.sp
    val caption1 = 12.sp
}

// ─── 关系图专属（UI_SPEC §5.8） ────────────────────────────────────────────────

object NovelGraphTokens {
    /** 节点圆形直径（§5.8）。 */
    val nodeDiameter: Dp = 56.dp

    /** 节点 1px 描边宽度。 */
    val nodeStrokeWidth: Dp = 1.dp

    /** 标签置于节点下方，与节点底边的间距。 */
    val nodeLabelGap: Dp = 4.dp

    /** 选中节点邻边不透明度 60% → 100%。 */
    const val EDGE_BASE_ALPHA: Float = 0.6f
    const val EDGE_HIGHLIGHT_ALPHA: Float = 1f

    /** 选中节点邻边光晕半径 6dp（模糊光晕，绘制层取 Dp）。 */
    val edgeHighlightGlow: Dp = 6.dp

    /** 强度 1..5 → 线宽 1..4dp（§5.8 映射，强度越窄越弱）。 */
    fun edgeWidth(strength: Int): Dp = when (strength.coerceIn(1, 5)) {
        1 -> 1.dp
        2 -> 2.dp
        3 -> 2.dp
        4 -> 3.dp
        else -> 4.dp
    }

    /** 力导向仿真步长（ms）——一次 withFrameNanos 推进一帧。 */
    const val SIM_MS_PER_FRAME: Long = 16L

    /** 仿真阻尼，越低越快静止。 */
    const val SIM_DAMPING: Float = 0.86f

    /** 固定迭代上限：防小屏几十节点时无限紊流。 */
    const val SIM_MAX_ITERATIONS: Int = 600
}

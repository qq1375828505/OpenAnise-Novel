package com.ai.assistance.quro.tools

import android.content.Context
import com.ai.assistance.quro.data.model.AgentToolDefinition
import com.ai.assistance.quro.data.model.AgentToolParam
import com.ai.assistance.quro.data.model.AgentSchemaNode
import org.json.JSONObject
import java.io.File

/**
 * Anise Write 小说审读工具集（`novel_review`）—— 基于 OpenMinis 原生工具契约。
 *
 * 注册/分发范式与 [NovelTools] 完全一致：一个 object + `definition()` +
 * `execute(argsJson, sessionId, context): ToolExecutionResult`，单一复合工具用 `action` 枚举分发。
 *
 * 移植来源（旧线 ZorvAI 时代，提交 c170c5eb）：
 * `app/src/main/java/com/ai/assistance/quro/core/tools/novel/NovelReviewTools.kt` 的四个工具 ——
 * `novel_humanize`（去 AI 味）、`novel_title_candidates`（标题候选）、
 * `novel_shuangdian_report`（爽点报告）、`novel_water_report`（水文报告），
 * 连同其私有词表（HUMANIZE_CLICHES / HUMANIZE_TRANSITIONS / SHUANGDIAN_LEXICON /
 * CLIFFHANGER_LEXICON / WATER_FILLER）与全部阈值原样移植。
 *
 * 与旧线的四点显式差异（细节见迁移简报「API 适配逐条说明」）：
 *  1. **契约层**：`QuroTool`（name/description/parametersJson/run，同步返回 String）
 *     → `AgentToolDefinition` + `execute(...): ToolExecutionResult`，`readOnly`/错误码 `GErr` 一族随之消失；
 *  2. **数据层**：`NovelRepository.fromContext(context)`（`work_id`/`chapter_id` + JSON 索引）
 *     → 纯文件项目目录，`project_path` + PRootKernel.resolveSessionHostPath，章节 = `03_正文/` 下的 .txt；
 *  3. **输出**：旧线返回 JSON 字符串 → 新基座工具一律返回人读文本（同 NovelTools），原 JSON 键名保留为可见小标题；
 *  4. **LLM 类工具**：旧线 `novel_title_candidates` 在工具内直连 `QuroLlmClient` 调模型；
 *     新基座工具执行体拿不到 provider（模型就是 agent 本身），故 `action=title` 改为
 *     「汇集上下文 + 下达显式命名契约」，由当前会话模型现场生成 —— 工具本身零 token。
 */
object NovelReviewTools {

    const val NAME = "novel_review"

    private val ACTIONS = listOf("humanize", "title", "shuangdian", "water")

    private const val DEFAULT_LAST_N = 5
    private const val MAX_LAST_N = 20
    private const val DEFAULT_TITLE_COUNT = 8
    private const val MAX_TITLE_COUNT = 15

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "小说审读工具（规则版，零 token 秒回，只报不改）：" +
            "action=humanize 去 AI 味体检（套话词表命中、公式化过渡词密度、重复段首、重复句，输出 aiScore 与逐条建议）；" +
            "action=title 标题候选（汇集项目章节/规则/关键词上下文并下达命名契约，由你（当前模型）现场生成 N 个候选，每行一条「候选 — 理由」）；" +
            "action=shuangdian 爽点节奏报告（逐章爽点信号词密度、对话占比、章尾悬念钩；指定 chapter 只统计一章，否则统计最近 last_n 章）；" +
            "action=water 注水检测（重复句、重复段首、注水套话密度、章长异常、对话占比过低）。" +
            "章节取自项目 ${NovelTools.TEXT_DIR}/ 下的纯文本 .txt（第NNN章：标题.txt），项目根目录默认 ${NovelTools.DEFAULT_PROJECT}。" +
            "本工具只做确定性统计；润色改写走 novel_polish，语义级「爽不爽 / 是不是废话 / 怎么改」请派 review 子代理（delegate_task）。",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "action" to AgentToolParam("string", "Operation to perform", enumValues = ACTIONS),
            "project_path" to AgentToolParam("string", "小说项目根目录（默认 ${NovelTools.DEFAULT_PROJECT}）。必须是 /var/minis/ 下的路径。除 humanize 只传 text 外，其余 action 都需要。"),
            "chapter" to AgentToolParam("string", "章节号或文件名（如 第005章 或 第005章：躺平第一日.txt，.txt 可省略）。humanize=只体检该章；shuangdian/water=只统计该章；缺省时 shuangdian/water 统计最近 last_n 章。"),
            "text" to AgentToolParam("string", "要体检的任意文本（仅 action=humanize；与 chapter 二选一，两者都传时以 chapter 为准）。"),
            "last_n" to AgentToolParam("integer", "action=shuangdian/water 且未指定 chapter 时生效：统计编号最大的最近 N 个已写章节，默认 $DEFAULT_LAST_N，上限 $MAX_LAST_N。"),
            "for_chapter" to AgentToolParam("string", "仅 action=title：章节号或文件名，为某一章起标题（缺省 = 起书名）。"),
            "count" to AgentToolParam("integer", "仅 action=title：候选数量，默认 $DEFAULT_TITLE_COUNT，上限 $MAX_TITLE_COUNT。"),
            "keywords" to AgentToolParam(
                "array",
                "仅 action=title：用户强调的关键词/意象（可选），起名时必须融入。",
                schema = AgentSchemaNode.Arr(items = AgentSchemaNode.Str()),
            ),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "project_path", "chapter", "text", "last_n", "for_chapter", "count", "keywords"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val action = args.optString("action", "").trim().lowercase()
        val toolTitle = args.optString("tool_title", NAME)

        return when (action) {
            "humanize" -> humanize(args, sessionId, context, toolTitle)
            "title" -> titleBrief(args, sessionId, context, toolTitle)
            "shuangdian" -> chapterReport(args, sessionId, context, toolTitle, ReportMode.SHUANGDIAN)
            "water" -> chapterReport(args, sessionId, context, toolTitle, ReportMode.WATER)
            else -> ToolExecutionResult(
                "Error: Unknown action '$action'. Valid: ${ACTIONS.joinToString("/")}",
                false,
                toolTitle = toolTitle,
            )
        }
    }

    private enum class ReportMode { SHUANGDIAN, WATER }

    // ─────────────────────────── action=humanize（去 AI 味） ───────────────────────────

    private fun humanize(
        args: JSONObject,
        sessionId: String,
        context: Context,
        toolTitle: String,
    ): ToolExecutionResult {
        val chapterArg = args.optString("chapter", "").trim()
        val text = args.optString("text", "").trim()
        val stats = if (chapterArg.isNotBlank()) {
            val projectDir = resolveProjectDir(args, sessionId, context)
                ?: return ToolExecutionResult(
                    "Error: Cannot resolve project path: ${NovelEnhanceSupport.projectPathArg(args)}",
                    false,
                    toolTitle = toolTitle,
                )
            val file = NovelEnhanceSupport.findChapterFile(projectDir, chapterArg)
                ?: return ToolExecutionResult(
                    "Error: 找不到章节 '$chapterArg'。用 novel_write action=list 查看项目内全部章节。",
                    false,
                    toolTitle = toolTitle,
                )
            val content = NovelEnhanceSupport.readTextOrNull(file)
                ?: return ToolExecutionResult("Error: 读取章节失败：${file.name}", false, toolTitle = toolTitle)
            if (content.isBlank()) {
                return ToolExecutionResult(
                    "Error: ${file.name} 还没有正文。先用 novel_write 写完再体检。",
                    false,
                    toolTitle = toolTitle,
                )
            }
            analyze(content, file.nameWithoutExtension)
        } else if (text.isNotBlank()) {
            analyze(text, "(临时文本)")
        } else {
            return ToolExecutionResult(
                "Error: humanize 需要 chapter（体检项目内某一章）或 text（体检任意文本）之一，" +
                    "例如 {\"action\":\"humanize\",\"chapter\":\"第005章\"} 或 {\"action\":\"humanize\",\"text\":\"…\"}。",
                false,
                toolTitle = toolTitle,
            )
        }

        val clicheTotal = stats.clicheHits.values.sum()
        val transitionTotal = stats.transitionHits.values.sum()
        val densityPer1000 = if (stats.chars == 0) 0.0 else transitionTotal * 1000.0 / stats.chars

        // (类型, 明细, 建议)
        val findings = mutableListOf<Triple<String, String, String>>()
        if (clicheTotal > 0) {
            findings.add(
                Triple(
                    "套话",
                    "共 $clicheTotal 处：" + hitsToText(stats.clicheHits),
                    "套话换成具体动作/感官细节，或直接删除",
                )
            )
        }
        if (densityPer1000 > HUMANIZE_TRANSITION_DENSITY_THRESHOLD) {
            findings.add(
                Triple(
                    "公式化过渡",
                    "共 $transitionTotal 处，密度 ${"%.1f".format(densityPer1000)}/千字" +
                        "（阈值 $HUMANIZE_TRANSITION_DENSITY_THRESHOLD）：" + hitsToText(stats.transitionHits),
                    "删掉一半过渡词，让动作顺序自己带节奏",
                )
            )
        }
        if (stats.repeatedParagraphStarts.isNotEmpty()) {
            findings.add(
                Triple(
                    "重复段首",
                    "${stats.repeatedParagraphStarts.size} 组：" + hitsToText(stats.repeatedParagraphStarts),
                    "段首句式打散：换主语/换句式/合并段落",
                )
            )
        }
        if (stats.duplicateSentences.isNotEmpty()) {
            findings.add(
                Triple(
                    "重复句",
                    "${stats.duplicateSentences.size} 句：" +
                        stats.duplicateSentences.joinToString("；") { it.take(30) },
                    "重复句删一处或改写其一",
                )
            )
        }
        val aiScore = (clicheTotal * 2 + transitionTotal + findings.size * 3).coerceAtMost(100)

        val sb = StringBuilder()
        sb.append("【去 AI 味体检】${stats.label}｜${stats.chars} 字符\n")
        sb.append("aiScore：$aiScore（越高越像 AI；套话×2 + 过渡词×1 + 问题类别×3，封顶 100）\n")
        if (findings.isEmpty()) {
            sb.append("✅ 未检出明显 AI 腔（词表/密度/段式均正常）。\n")
        } else {
            findings.forEach { (type, detail, suggestion) ->
                sb.append("- [$type] $detail\n")
                sb.append("  建议：$suggestion\n")
            }
        }
        sb.append("只报不改：改写请用 novel_polish（action=prepare → action=apply）；语义级判断请派 review 子代理（delegate_task）。")
        return ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
    }

    // ─────────────────────────── action=title（标题候选） ───────────────────────────

    private fun titleBrief(
        args: JSONObject,
        sessionId: String,
        context: Context,
        toolTitle: String,
    ): ToolExecutionResult {
        val projectDir = resolveProjectDir(args, sessionId, context)
            ?: return ToolExecutionResult(
                "Error: Cannot resolve project path: ${NovelEnhanceSupport.projectPathArg(args)}",
                false,
                toolTitle = toolTitle,
            )
        if (!projectDir.isDirectory) {
            return ToolExecutionResult(
                "Error: Project directory not found: ${NovelEnhanceSupport.projectPathArg(args)}",
                false,
                toolTitle = toolTitle,
            )
        }
        val chapterArg = args.optString("for_chapter", "").trim()
        val chapterFile = if (chapterArg.isNotBlank()) {
            NovelEnhanceSupport.findChapterFile(projectDir, chapterArg)
                ?: return ToolExecutionResult(
                    "Error: 找不到章节 '$chapterArg'。用 novel_write action=list 查看项目内全部章节。",
                    false,
                    toolTitle = toolTitle,
                )
        } else {
            null
        }
        val chapterContent = chapterFile?.let { NovelEnhanceSupport.readTextOrNull(it) }
        val chapterChars = chapterContent?.length
        val count = args.optInt("count", DEFAULT_TITLE_COUNT).coerceIn(MIN_TITLE_COUNT, MAX_TITLE_COUNT)
        val keywords = NovelEnhanceSupport.stringList(args, "keywords")
        val chapters = NovelEnhanceSupport.chapterFiles(projectDir)
        val ruleFiles = NovelEnhanceSupport.listRuleFiles(projectDir)

        val sb = StringBuilder()
        sb.append("【标题候选任务单】项目：${projectDir.name}\n")
        if (chapterFile != null) {
            val sizeDesc = when {
                chapterContent == null -> "正文读取失败"
                chapterChars == 0 -> "正文为空"
                else -> "$chapterChars 字符"
            }
            sb.append("类型：章节标题（${chapterFile.nameWithoutExtension}，$sizeDesc）\n")
        } else {
            sb.append("类型：书名\n")
        }
        sb.append("候选数量：$count\n")
        if (keywords.isNotEmpty()) sb.append("必须融入的关键词/意象：${keywords.joinToString("、")}\n")
        if (chapters.isNotEmpty()) {
            sb.append("项目内章节（调性参考，取前 $TITLE_CONTEXT_CHAPTERS 章）：" +
                chapters.take(TITLE_CONTEXT_CHAPTERS).joinToString("；") { it.nameWithoutExtension } + "\n")
        }
        if (ruleFiles.isNotEmpty()) {
            sb.append("项目内规则/资料文件（可按需用 novel_write action=rule 读取）：" +
                ruleFiles.joinToString("、") + "\n")
        }
        sb.append("\n【命名规则】\n")
        sb.append("- 书名：题材词前置、冲突/反差/金手指外显、10 字内、符合流派调性。\n")
        sb.append("- 章节标题：短、有钩子、剧透一半。\n")
        sb.append("- 每行一条，格式：候选 — 一句话理由；不要编号、不要解释、不要额外段落。\n")
        sb.append("\n【输出契约】直接在回复中给出恰好 $count 行候选（每行一条）。" +
            "本工具不调用模型：只负责汇集上下文与契约，候选由你（当前会话模型）现场生成，不要转述本任务单。\n")
        sb.append("挑中后的改名不在本工具范围（项目是纯文件目录：改章节文件名即改名，操作前先备份）。")
        return ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
    }

    // ─────────────────────────── action=shuangdian / action=water（逐章报告） ───────────────────────────

    private fun chapterReport(
        args: JSONObject,
        sessionId: String,
        context: Context,
        toolTitle: String,
        mode: ReportMode,
    ): ToolExecutionResult {
        val projectDir = resolveProjectDir(args, sessionId, context)
            ?: return ToolExecutionResult(
                "Error: Cannot resolve project path: ${NovelEnhanceSupport.projectPathArg(args)}",
                false,
                toolTitle = toolTitle,
            )
        if (!projectDir.isDirectory) {
            return ToolExecutionResult(
                "Error: Project directory not found: ${NovelEnhanceSupport.projectPathArg(args)}",
                false,
                toolTitle = toolTitle,
            )
        }
        val chapterArg = args.optString("chapter", "").trim()
        val targets: List<File>
        var meanChars = 0.0
        if (chapterArg.isNotBlank()) {
            val file = NovelEnhanceSupport.findChapterFile(projectDir, chapterArg)
                ?: return ToolExecutionResult(
                    "Error: 找不到章节 '$chapterArg'。用 novel_write action=list 查看项目内全部章节。",
                    false,
                    toolTitle = toolTitle,
                )
            targets = listOf(file)
        } else {
            val lastN = args.optInt("last_n", DEFAULT_LAST_N).coerceIn(1, MAX_LAST_N)
            val written = NovelEnhanceSupport.recentWrittenChapters(projectDir, lastN)
            if (written.isEmpty()) {
                return ToolExecutionResult(
                    "Error: 项目内还没有已写章节（正文为空的章节不计）。先写正文再出报告。",
                    false,
                    toolTitle = toolTitle,
                )
            }
            targets = written
            // 「章长异常」的均值口径：旧线用全部已写章节的 wordCount 均值，此处同（字符口径）。
            meanChars = written.map { NovelEnhanceSupport.readTextOrNull(it)?.length ?: 0 }.average()
        }

        val rows = ArrayList<Pair<String, ChapterStats>>(targets.size)
        for (f in targets) {
            val content = NovelEnhanceSupport.readTextOrNull(f)
                ?: return ToolExecutionResult("Error: 读取章节失败：${f.name}", false, toolTitle = toolTitle)
            if (content.isBlank()) {
                return ToolExecutionResult(
                    "Error: ${f.name} 还没有正文，无法统计。",
                    false,
                    toolTitle = toolTitle,
                )
            }
            rows.add(f.nameWithoutExtension to analyze(content, f.nameWithoutExtension))
        }

        return when (mode) {
            ReportMode.SHUANGDIAN -> shuangdianReport(rows, toolTitle)
            ReportMode.WATER -> waterReport(rows, meanChars, toolTitle)
        }
    }

    private fun shuangdianReport(rows: List<Pair<String, ChapterStats>>, toolTitle: String): ToolExecutionResult {
        val sb = StringBuilder()
        var denseChapters = 0
        var hookChapters = 0
        for ((label, s) in rows) {
            val hits = s.shuangdianHits.values.sum()
            val density = if (s.chars == 0) 0.0 else hits * 1000.0 / s.chars
            val dialogueRatio = if (s.totalLines == 0) 0.0 else s.dialogueLines.toDouble() / s.totalLines
            if (density >= SHUANGDIAN_DENSITY_THRESHOLD) denseChapters++
            if (s.hasCliffhanger) hookChapters++
            sb.append("$label ｜${s.chars} 字符｜爽点词 $hits（${"%.1f".format(density)}/千字）" +
                "｜对话占比 ${"%.0f%%".format(dialogueRatio * 100)}" +
                "｜章尾钩：${if (s.hasCliffhanger) "有" else "无"}" +
                "｜命中：${hitsToText(s.shuangdianHits)}\n")
        }
        val total = rows.size
        sb.append("\n小结（共 $total 章）：$denseChapters 章爽点密度达标（≥${SHUANGDIAN_DENSITY_THRESHOLD}/千字），" +
            "$hookChapters 章有章尾钩。\n")
        if (denseChapters == 0) {
            sb.append("⚠️ 连续无达标爽点——建议下一章安排一次打脸/突破/反转。\n")
        }
        if (hookChapters < total / 2) {
            sb.append("⚠️ 章尾钩不足一半——每章结尾留半句悬念是番茄基操。\n")
        }
        sb.append("本报告只是词表节奏仪表盘；语义级「爽不爽」判断请派 review 子代理（delegate_task）。")
        return ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
    }

    private fun waterReport(
        rows: List<Pair<String, ChapterStats>>,
        meanChars: Double,
        toolTitle: String,
    ): ToolExecutionResult {
        val sb = StringBuilder()
        var waterFlags = 0
        for ((label, s) in rows) {
            val signals = mutableListOf<String>()
            if (s.duplicateSentences.isNotEmpty()) signals.add("重复句×${s.duplicateSentences.size}")
            if (s.repeatedParagraphStarts.isNotEmpty()) signals.add("重复段首×${s.repeatedParagraphStarts.size}")
            val fillerTotal = s.waterHits.values.sum()
            val fillerDensity = if (s.chars == 0) 0.0 else fillerTotal * 1000.0 / s.chars
            if (fillerDensity > WATER_FILLER_DENSITY_THRESHOLD) {
                signals.add("套话密度${"%.1f".format(fillerDensity)}/千字")
            }
            if (meanChars > 0 && s.chars > meanChars * WATER_LONG_CHAPTER_FACTOR) {
                signals.add("章长异常（本次均值 ${meanChars.toInt()} 字符）")
            }
            if (s.totalLines > 0 && s.dialogueLines.toDouble() / s.totalLines < WATER_MIN_DIALOGUE_RATIO) {
                signals.add("对话占比<${"%.0f%%".format(WATER_MIN_DIALOGUE_RATIO * 100)}")
            }
            if (signals.isNotEmpty()) waterFlags++
            sb.append("$label ｜${s.chars} 字符｜")
            if (signals.isEmpty()) {
                sb.append("OK\n")
            } else {
                sb.append("信号：" + signals.joinToString("；") + "\n")
            }
            if (s.duplicateSentences.isNotEmpty()) {
                sb.append("    重复句：" + s.duplicateSentences.joinToString("；") { it.take(30) } + "\n")
            }
        }
        sb.append("\n小结（共 ${rows.size} 章）：$waterFlags 章有注水信号。\n")
        if (waterFlags > rows.size / 2) {
            sb.append("⚠️ 过半章节有信号——考虑用 novel_polish 精修（prepare → apply）或删并场景。\n")
        }
        sb.append("本报告只是确定性信号检测；语义级「是不是废话」判断请派 review 子代理（delegate_task）。")
        return ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
    }

    // ─────────────────────────── 公共小助手 ───────────────────────────

    /**
     * 解析项目根目录并校验它是目录；任一不满足返回 null（调用方输出带路径的显式错误）。
     * 解析顺序与 NovelTools.execute 一致：resolveSessionHostPath → isDirectory。
     */
    private fun resolveProjectDir(
        args: JSONObject,
        sessionId: String,
        context: Context,
    ): File? {
        val dir = NovelEnhanceSupport.resolveProjectDir(args, sessionId, context) ?: return null
        return dir.takeIf { it.isDirectory }
    }

    private const val MIN_TITLE_COUNT = 3
    private const val TITLE_CONTEXT_CHAPTERS = 10
    private const val HUMANIZE_TRANSITION_DENSITY_THRESHOLD = 3.0
    private const val SHUANGDIAN_DENSITY_THRESHOLD = 2.0
    private const val WATER_FILLER_DENSITY_THRESHOLD = 2.0
    private const val WATER_LONG_CHAPTER_FACTOR = 1.6
    private const val WATER_MIN_DIALOGUE_RATIO = 0.15
}

// ─────────────────────────── 词表与纯文本统计（旧线原样移植） ───────────────────────────

/** AI 高频套话词表（命中即提示；按小说语境人工取舍，工具只报不改）。 */
private val HUMANIZE_CLICHES = listOf(
    "嘴角勾起", "眼底闪过", "眼中闪过", "一抹", "空气中弥漫", "浑身上下", "浑身一震",
    "深吸一口气", "心中一动", "目光一凝", "不由得", "顿了顿", "似笑非笑",
    "瞬间", "仿佛", "似乎", "宛如", "犹如", "顷刻间", "刹那间",
    "无可挑剔", "无与伦比", "刻骨铭心", "历历在目", "一言难尽",
)

/** 公式化过渡词（密度过高 = AI 腔）。 */
private val HUMANIZE_TRANSITIONS = listOf("然而", "紧接着", "与此同时", "只见", "显然", "事实上", "值得注意的是", "可以说")

/** 爽点信号词（节奏仪表盘用；是否真爽由 review 子代理语义判断）。 */
private val SHUANGDIAN_LEXICON = listOf(
    "打脸", "突破", "暴涨", "晋升", "反转", "震惊", "慑服", "认怂", "跪", "一鸣惊人",
    "扮猪吃虎", "扬眉吐气", "当众", "翻身", "碾压", "秒杀", "身份揭露", "真实身份",
    "奖励", "系统提示", "升职", "翻盘", "逆天", "屠杀", "一拳", "秒了",
)

/** 悬念/钩子词（章尾 100 字内命中 = 有钩）。 */
private val CLIFFHANGER_LEXICON = listOf("突然", "却", "岂料", "谁知", "下一刻", "轰", "？", "！", "瞳孔", "骤变", "炸开")

/** 注水套话词表。 */
private val WATER_FILLER = listOf(
    "显然", "事实上", "不得不说", "值得注意的是", "总的来说", "可以预见",
    "无论如何", "毫无疑问", "可以说", "众所周知",
)

/** 章节文本统计（爽点/注水报告共用；aiScore 公式见 humanize）。 */
private data class ChapterStats(
    val label: String,
    val chars: Int,
    val dialogueLines: Int,
    val totalLines: Int,
    val clicheHits: Map<String, Int>,
    val shuangdianHits: Map<String, Int>,
    val waterHits: Map<String, Int>,
    val duplicateSentences: List<String>,
    val repeatedParagraphStarts: Map<String, Int>,
    val hasCliffhanger: Boolean,
    val transitionHits: Map<String, Int>,
)

/** 纯文本统计（零 IO）：词表命中、重复句、重复段首、章尾钩、对话行占比。 */
private fun analyze(content: String, label: String): ChapterStats {
    val paragraphs = content.split('\n').map { it.trim() }.filter { it.isNotBlank() }
    val dialogueLines = paragraphs.count { it.startsWith('「') || it.startsWith('"') || it.startsWith('“') }
    val sentences = content.split('。', '！', '？').map { it.trim() }.filter { it.length > 10 }
    val seen = HashMap<String, Int>()
    sentences.forEach { seen[it] = (seen[it] ?: 0) + 1 }
    val duplicates = seen.filter { it.value >= 2 }.keys.take(5)
    val starts = paragraphs.filter { it.length >= 6 }.groupingBy { it.take(6) }.eachCount()
    val repeatedStarts = starts.filter { it.value >= 3 }
    val tail = content.takeLast(100)
    return ChapterStats(
        label = label,
        chars = content.length,
        dialogueLines = dialogueLines,
        totalLines = paragraphs.size,
        clicheHits = countHits(content, HUMANIZE_CLICHES),
        shuangdianHits = countHits(content, SHUANGDIAN_LEXICON),
        waterHits = countHits(content, WATER_FILLER),
        duplicateSentences = duplicates,
        repeatedParagraphStarts = repeatedStarts,
        hasCliffhanger = CLIFFHANGER_LEXICON.any { tail.contains(it) },
        transitionHits = countHits(content, HUMANIZE_TRANSITIONS),
    )
}

/** 词表命中计数（重叠不重复计：与旧线 indexOf 推进口径一致）。 */
private fun countHits(content: String, words: List<String>): Map<String, Int> = buildMap {
    words.forEach { w ->
        var idx = content.indexOf(w)
        var n = 0
        while (idx >= 0) {
            n++
            idx = content.indexOf(w, idx + w.length)
        }
        if (n > 0) put(w, n)
    }
}

/** 命中表 → "词×n" 紧凑串（空则 "无"）。 */
private fun hitsToText(hits: Map<String, Int>): String =
    if (hits.isEmpty()) "无" else hits.entries.sortedByDescending { it.value }.joinToString("、") { "${it.key}×${it.value}" }

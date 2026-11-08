package com.ai.assistance.quro.tools

import android.content.Context
import com.ai.assistance.quro.data.model.AgentToolDefinition
import com.ai.assistance.quro.data.model.AgentToolParam
import com.ai.assistance.quro.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

/**
 * Anise Write 小说润色工具（`novel_polish`）—— 基于 OpenMinis 原生工具契约。
 *
 * 注册/分发范式与 [NovelTools] 完全一致：一个 object + `definition()` +
 * `execute(argsJson, sessionId, context): ToolExecutionResult`，单一复合工具用 `action` 枚举分发。
 *
 * 移植来源（旧线 ZorvAI 时代，提交 c170c5eb）：
 * `core/tools/novel/NovelWriteTools.kt` 的 `novel_polish`（NovelPolishTool）。
 *
 * ## 架构差异与显式契约（重中之重，写工具前必读）
 * 旧线 `novel_polish` 在工具内部直连 `QuroLlmClient` 调模型：一次调用 = 读原文 → 调模型 → 落盘。
 * **新基座的工具执行体拿不到 provider**（全树 grep 过：provider 只被 ChatViewModel / offload 持有，
 * 工具侧没有 LLM 入口；模型就是 agent 本身）。因此本工具拆成两步，把「模型该干的活」交还给 agent：
 *
 *  - `action=prepare`：读原稿 + 精修规则 + 档位说明，产出【润色任务单】（含铁律与输出契约），
 *    零 token、幂等、可反复调用；
 *  - agent 按任务单在现场完成润色（它就是模型），把结果用 `file_write` 落成一个文件；
 *  - `action=apply`：读该文件 → **先把原稿备份到 `05_草稿/`**（备份失败即中止，原稿不动，
 *    可逆性保证同旧线）→ 覆盖写入章节 → 汇报字数变化与备份路径。
 *
 * 这不是降级而是职责归位：旧线工具内 LLM 调用的参数拼装、温度、maxTokens、防重、格式解析逻辑
 * （NovelWriteSupport.callLlm / stripCodeFences / startsWithOverlap）随数据层一并废弃，
 * 保留的是数据流（读原文 → 产润色结果 → 备份 → 覆盖 → 汇报）与全部安全约束。
 */
object NovelPolishTools {

    const val NAME = "novel_polish"

    private val ACTIONS = listOf("prepare", "apply")

    /** 润色档位（照旧线 NovelPolishTool.LEVELS）。 */
    private val LEVELS = listOf("light", "standard", "deep")

    /** 档位说明（照旧线 NovelPolishTool.LEVEL_DESC 原样搬运）。 */
    private val LEVEL_DESC: Map<String, String> = mapOf(
        "light" to "轻度：只改错别字、语病与明显 AI 腔词汇；句式与结构不动",
        "standard" to "标准：改 AI 腔与翻译腔、调句式长短节奏、补感官细节；情节与信息不变",
        "deep" to "深度：在标准基础上，重写表达吃力的段落（重设句式、替换套话、增强现场感）；仍不得改动情节与信息",
    )

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "小说润色工具（agent 执行契约，分两步）：" +
            "action=prepare 读原稿与精修规则，产出【润色任务单】（铁律/输出契约/原文），由你（当前模型）现场润色；" +
            "action=apply 把润色结果写入章节——落盘前先把原稿备份到 ${NovelTools.BACKUP_DIR}/，备份失败则中止、原稿不动。" +
            "润色目标：去 AI 腔与翻译腔、调句式长短节奏、补感官细节；不改变情节、人物、设定与任何信息。" +
            "只读体检（不改文）用 novel_review action=humanize；套话词表口径同它。",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "action" to AgentToolParam("string", "Operation to perform", enumValues = ACTIONS),
            "project_path" to AgentToolParam("string", "小说项目根目录（默认 ${NovelTools.DEFAULT_PROJECT}）。必须是 /var/minis/ 下的路径。"),
            "chapter" to AgentToolParam("string", "章节号或文件名（如 第005章 或 第005章：躺平第一日.txt，.txt 可省略）。prepare/apply 必填。"),
            "level" to AgentToolParam("string", "润色档位（仅 prepare）：light=轻度 / standard=标准 / deep=深度，默认 standard。", enumValues = LEVELS),
            "focus" to AgentToolParam("string", "聚焦维度（仅 prepare，可选），如「对话」「节奏」「描写」「开头」；缺省为全篇均衡润色。"),
            "rule_name" to AgentToolParam("string", "要注入的精修规则文件名（仅 prepare，可选），如 规则.1（精修流程）.md；在 ${NovelTools.RULE_DIR}/ 或其子目录中查找。缺省则不注入规则，并在任务单里列出项目内可用规则文件。"),
            "content_file" to AgentToolParam("string", "润色结果文件的完整路径（仅 apply 必填；先用 file_write 写好）。带【润色后正文】标记时自动截取标记之后的部分，纯正文则整篇采用。"),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "project_path", "chapter", "level", "focus", "rule_name", "content_file"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val action = args.optString("action", "").trim().lowercase()
        val toolTitle = args.optString("tool_title", NAME)

        return when (action) {
            "prepare" -> prepare(args, sessionId, context, toolTitle)
            "apply" -> applyPolish(args, sessionId, context, toolTitle)
            else -> ToolExecutionResult(
                "Error: Unknown action '$action'. Valid: ${ACTIONS.joinToString("/")}",
                false,
                toolTitle = toolTitle,
            )
        }
    }

    // ─────────────────────────── action=prepare（产出润色任务单） ───────────────────────────

    private fun prepare(
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
        val chapterArg = args.optString("chapter", "").trim()
        if (chapterArg.isBlank()) {
            return ToolExecutionResult(
                "Error: prepare 需要 chapter（章节号或文件名）。用 novel_write action=list 查看项目内全部章节。",
                false,
                toolTitle = toolTitle,
            )
        }
        val chapter = NovelEnhanceSupport.findChapterFile(projectDir, chapterArg)
            ?: return ToolExecutionResult(
                "Error: 找不到章节 '$chapterArg'。用 novel_write action=list 查看项目内全部章节。",
                false,
                toolTitle = toolTitle,
            )
        val original = NovelEnhanceSupport.readTextOrNull(chapter)
            ?: return ToolExecutionResult("Error: 读取章节失败：${chapter.name}", false, toolTitle = toolTitle)
        if (original.isBlank()) {
            return ToolExecutionResult(
                "Error: ${chapter.name} 还没有正文，无可润色内容。先用 novel_write 写完再润色。",
                false,
                toolTitle = toolTitle,
            )
        }
        val level = args.optString("level", "standard").trim().ifBlank { "standard" }
        if (level !in LEVELS) {
            return ToolExecutionResult(
                "Error: level 只支持 ${LEVELS.joinToString(" / ")}，收到：$level",
                false,
                toolTitle = toolTitle,
            )
        }
        val focus = args.optString("focus", "").trim()
        val ruleName = args.optString("rule_name", "").trim()

        // 规则注入：显式指定才注入；未指定则列出项目内可用规则，不替模型猜默认规则。
        val ruleLabel: String
        val ruleText: String
        if (ruleName.isNotBlank()) {
            val ruleFile = NovelEnhanceSupport.findRuleFile(projectDir, ruleName)
                ?: return ToolExecutionResult(
                    "Error: 找不到规则 '$ruleName'。候选位置：${NovelTools.RULE_DIR}/ 及其子目录 " +
                        NovelEnhanceSupport.RULE_SUBDIRS.joinToString("、") + "；" +
                        "可先用 novel_write action=list 或本工具省略 rule_name 查看可用清单。",
                    false,
                    toolTitle = toolTitle,
                )
            ruleText = NovelEnhanceSupport.readTextOrNull(ruleFile)
                ?: return ToolExecutionResult("Error: 读取规则失败：${ruleFile.name}", false, toolTitle = toolTitle)
            ruleLabel = ruleFile.name
        } else {
            ruleText = ""
            val available = NovelEnhanceSupport.listRuleFiles(projectDir)
            ruleLabel = if (available.isEmpty()) {
                "未注入（未传 rule_name，且 ${NovelTools.RULE_DIR}/ 下没有可用规则文件）"
            } else {
                "未注入（未传 rule_name）。项目内可用规则文件：" + available.joinToString("、")
            }
        }

        val sb = StringBuilder()
        sb.append("【润色任务单】${chapter.nameWithoutExtension}｜原稿 ${NovelEnhanceSupport.countWords(original)} 字\n")
        sb.append("档位：$level — ${LEVEL_DESC.getValue(level)}\n")
        if (focus.isNotBlank()) sb.append("聚焦维度：$focus（该维度重点改，其余保持）\n")
        sb.append("\n【精修规则】$ruleLabel\n")
        if (ruleText.isNotBlank()) {
            sb.append(ruleText.trim()).append('\n')
        }
        sb.append("\n【铁律】\n")
        sb.append("- 不改变情节、人物、设定与任何信息；只改表达。\n")
        sb.append("- 以网文语言规范为目标：去 AI 腔套话与翻译腔、调句式长短节奏、补感官细节；套话词表口径见 novel_review action=humanize。\n")
        sb.append("- 修改说明不超过 10 条，每条一行：位置线索 + 改了什么 + 为什么。\n")
        sb.append("\n【输出契约（严格遵守，两个标记都要出现）】\n")
        sb.append("【修改说明】\n")
        sb.append("- <每条一行>\n")
        sb.append("【润色后正文】\n")
        sb.append("<完整润色后全文>\n")
        sb.append("\n【下一步】把完整输出（含两个标记）用 file_write 写到一个文件，然后调用 $NAME action=apply：" +
            "chapter=${chapter.nameWithoutExtension}，content_file=<该文件路径>。" +
            "apply 会先把原稿备份到 ${NovelTools.BACKUP_DIR}/ 再覆盖写入，备份失败则中止、原稿不动。\n")
        sb.append("\n【原稿】\n")
        sb.append(original)
        if (!original.endsWith("\n")) sb.append('\n')
        return ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
    }

    // ─────────────────────────── action=apply（备份后覆盖写入） ───────────────────────────

    private fun applyPolish(
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
        val chapterArg = args.optString("chapter", "").trim()
        if (chapterArg.isBlank()) {
            return ToolExecutionResult(
                "Error: apply 需要 chapter（章节号或文件名）。",
                false,
                toolTitle = toolTitle,
            )
        }
        val chapter = NovelEnhanceSupport.findChapterFile(projectDir, chapterArg)
            ?: return ToolExecutionResult(
                "Error: 找不到章节 '$chapterArg'。用 novel_write action=list 查看项目内全部章节。",
                false,
                toolTitle = toolTitle,
            )
        val contentFileArg = args.optString("content_file", "").trim()
        if (contentFileArg.isBlank()) {
            return ToolExecutionResult(
                "Error: apply 需要 content_file（润色结果文件路径：先用 file_write 把润色输出写好，再传路径）。",
                false,
                toolTitle = toolTitle,
            )
        }
        val contentFile = PRootKernel.resolveSessionHostPath(sessionId, contentFileArg, context)
            ?: return ToolExecutionResult("Error: Cannot resolve content_file: $contentFileArg", false, toolTitle = toolTitle)
        if (!contentFile.isFile) {
            return ToolExecutionResult("Error: content_file 不存在: $contentFileArg", false, toolTitle = toolTitle)
        }
        val raw = NovelEnhanceSupport.readTextOrNull(contentFile)
            ?: return ToolExecutionResult("Error: 读取 content_file 失败: $contentFileArg", false, toolTitle = toolTitle)
        val original = NovelEnhanceSupport.readTextOrNull(chapter)
            ?: return ToolExecutionResult("Error: 读取原稿失败：${chapter.name}", false, toolTitle = toolTitle)
        if (original.isBlank()) {
            return ToolExecutionResult(
                "Error: 原稿为空，apply 只覆盖已有正文的章节（防止把空章节写成半截状态）。",
                false,
                toolTitle = toolTitle,
            )
        }

        // 契约解析：带【润色后正文】标记则截取其后为正文、其前为修改说明；纯正文则整篇采用。
        val parsed = parsePolishOutput(raw)
        val polished = parsed?.polished ?: raw.trim()
        val changes = parsed?.changes
        if (polished.isBlank()) {
            return ToolExecutionResult(
                "Error: 润色结果为空（带标记时检查【润色后正文】之后是否有内容），未写入。",
                false,
                toolTitle = toolTitle,
            )
        }

        // 可逆性保证：先备份原稿，备份失败则中止（不写半截状态）。
        val backupDir = File(projectDir, NovelTools.BACKUP_DIR)
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .format(java.time.LocalDateTime.now())
        val backupName = "${chapter.nameWithoutExtension}_润色前_${stamp}.txt"
        val backupFile = File(backupDir, backupName)
        val backupOk = runCatching {
            backupDir.mkdirs()
            backupFile.writeText(original)
        }.isSuccess
        if (!backupOk) {
            return ToolExecutionResult(
                "Error: 原稿备份写入失败（${backupFile.absolutePath}），已中止润色，原稿未动。",
                false,
                toolTitle = toolTitle,
            )
        }
        val writeOk = runCatching { chapter.writeText(polished) }.isSuccess
        if (!writeOk) {
            return ToolExecutionResult(
                "Error: 润色结果写入章节失败：${chapter.name}。原稿备份在 ${NovelTools.BACKUP_DIR}/$backupName，" +
                    "可用 novel_write action=write 将该备份写回（覆盖前先确认）。",
                false,
                toolTitle = toolTitle,
            )
        }

        val sb = StringBuilder()
        sb.append("【润色完成】${chapter.nameWithoutExtension}｜${NovelEnhanceSupport.countWords(original)} 字 → " +
            "${NovelEnhanceSupport.countWords(polished)} 字")
        if (polished == original) {
            sb.append("｜注意：润色结果与原文一致（无变化）")
        }
        sb.append('\n')
        sb.append("【原稿备份】${NovelTools.BACKUP_DIR}/$backupName\n")
        sb.append("【修改说明】\n")
        sb.append(changes ?: "（content_file 未按契约带【修改说明】标记，本次无修改说明）")
        sb.append('\n')
        return ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
    }

    /**
     * 解析润色输出：`【修改说明】…【润色后正文】…`（照旧线 NovelPolishTool.parsePolishOutput 移植）。
     * 无标记返回 null（调用方按「整篇即正文」处理）；标记后内容为空也返回 null（按失败重试，不半截采用）。
     */
    private fun parsePolishOutput(raw: String): PolishParse? {
        val marker = "【润色后正文】"
        val idx = raw.indexOf(marker)
        if (idx < 0) return null
        val polished = raw.substring(idx + marker.length).trim()
        if (polished.isBlank()) return null
        var changes = raw.substring(0, idx).trim()
        changes = changes.removePrefix("【修改说明】").trim()
        if (changes.isBlank()) changes = "（模型未给出修改说明）"
        return PolishParse(changes, polished)
    }

    private data class PolishParse(val changes: String, val polished: String)

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
}

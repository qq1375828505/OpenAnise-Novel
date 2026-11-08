package com.ai.assistance.quro.tools

import android.content.Context
import com.ai.assistance.quro.sandbox.PRootKernel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Anise Write 写作增强块的公共文件访问助手（内部对象）。
 *
 * 移植来源与范式：
 *  - 「共享支撑对象」范式照旧线 `core/tools/novel/NovelWriteTools.kt` 的 `internal object NovelWriteSupport`
 *    （纯函数 + IO 助手，供同文件多个工具复用）；
 *  - 数据层从旧线 `NovelRepository`（`filesDir/novel/` + JSON 索引）换成 OpenMinis 的纯文件项目目录
 *    （`/var/minis/mounts/<作品>/`，章节为 `03_正文/第NNN章：标题.txt`）。
 *
 * 口径与 [NovelTools] 保持一致：同一批 public const val（DEFAULT_PROJECT / TEXT_DIR / RULE_DIR / BACKUP_DIR）、
 * 同一种按文件名数字的自然排序、同一种章节指认规则。因 NovelTools 的 `txtFiles` / `resolveChapter` /
 * `readRule` 均为 private，此处按同一规则复刻（约 30 行），**不改动新基座已交付文件**（铁律 5）。
 * 若后续愿意把 NovelTools 的这几个助手改成 internal，可删除本对象的对应函数改调它。
 */
internal object NovelEnhanceSupport {

    /** 规则目录下的子目录（口径同 NovelTools.readRule 的 subDirs）。 */
    val RULE_SUBDIRS = listOf("写文规则", "文章精修规则", "禁则", "逻辑判断")

    /** 项目根目录参数（缺省/空串都落到 NovelTools.DEFAULT_PROJECT）。 */
    fun projectPathArg(args: JSONObject): String =
        args.optString("project_path", NovelTools.DEFAULT_PROJECT).ifBlank { NovelTools.DEFAULT_PROJECT }

    /**
     * 把 `/var/minis/...` 项目路径解析成宿主 File（同 NovelTools.execute 的第一跳）。
     * 返回 null = 该 session 没有对应挂载映射（调用方按显式错误上报，不猜路径）。
     */
    fun resolveProjectDir(args: JSONObject, sessionId: String, context: Context): File? =
        PRootKernel.resolveSessionHostPath(sessionId, projectPathArg(args), context)

    /** 读文本；IO 失败返回 null（调用方转成显式错误，不静默兜底成空串）。 */
    fun readTextOrNull(file: File): String? = runCatching { file.readText() }.getOrNull()

    /** 文件名中的第一个数字（无则 99999，保证排在最后）；同 NovelTools.naturalKey。 */
    private fun naturalKey(name: String): Pair<Int, String> {
        val m = Regex("(\\d+)").find(name)
        return (m?.groupValues?.get(1)?.toIntOrNull() ?: 99999) to name
    }

    /** `03_正文/` 下的章节 .txt，按（编号, 文件名）两键自然排序；同 NovelTools.txtFiles。 */
    fun chapterFiles(projectDir: File): List<File> {
        val dir = File(projectDir, NovelTools.TEXT_DIR)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedWith(compareBy({ naturalKey(it.name).first }, { naturalKey(it.name).second }))
            ?: emptyList()
    }

    /**
     * 指认章节文件：先按文件名精确匹配（可省略 `.txt`），再按文件名中的数字匹配
     * （`第005章` / `5` / `第005章：躺平第一日.txt` 均可；数字口径同 NovelTools.resolveChapter）。
     *
     * 比 NovelTools.resolveChapter 多支持「精确文件名」这一档：审读/润色工具的正文都是长文本，
     * 模型经常会带着完整文件名回来。
     */
    fun findChapterFile(projectDir: File, chapterArg: String): File? {
        val files = chapterFiles(projectDir)
        if (files.isEmpty()) return null
        files.firstOrNull { it.name == chapterArg || it.nameWithoutExtension == chapterArg }
            ?.let { return it }
        val num = Regex("(\\d+)").find(chapterArg)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return files.firstOrNull { f ->
            Regex("(\\d+)").find(f.name)?.groupValues?.get(1)?.toIntOrNull() == num
        }
    }

    /**
     * 已写章节（正文非空）中编号最大的 [lastN] 个，按章节正序返回。
     * 旧线靠 `wordCount > 0` 元数据判定「已写」；新基座没有索引，改为读正文非空判定（同 NovelTools.stats 的读法）。
     */
    fun recentWrittenChapters(projectDir: File, lastN: Int): List<File> =
        chapterFiles(projectDir)
            .filter { f -> (readTextOrNull(f) ?: "").isNotBlank() }
            .sortedByDescending { f -> naturalKey(f.name).first }
            .take(lastN)
            .reversed()

    /** 规则文件候选位置：`01_项目中枢/` 及其四个子目录（口径同 NovelTools.readRule）。 */
    fun ruleCandidates(projectDir: File, ruleName: String): List<File> {
        val ruleDir = File(projectDir, NovelTools.RULE_DIR)
        val candidates = mutableListOf(File(ruleDir, ruleName))
        for (sub in RULE_SUBDIRS) candidates.add(File(ruleDir, "$sub/$ruleName"))
        return candidates
    }

    fun findRuleFile(projectDir: File, ruleName: String): File? =
        ruleCandidates(projectDir, ruleName).firstOrNull { it.isFile }

    /**
     * 规则目录下一层（含四个子目录）的规则文件名清单，最多 [limit] 条。
     * 供 novel_polish 的 action=prepare 在未传 rule_name 时，明确告知「可注入哪些规则」，
     * 由模型自己选一个再次调用 —— 不替模型猜一个默认规则文件。
     */
    fun listRuleFiles(projectDir: File, limit: Int = 20): List<String> {
        val ruleDir = File(projectDir, NovelTools.RULE_DIR)
        val root = ruleDir.listFiles() ?: return emptyList()
        val out = mutableListOf<String>()
        for (f in root.sortedBy { it.name }) {
            if (f.isFile && isRuleTextFile(f)) out.add(f.name)
            if (f.isDirectory && f.name in RULE_SUBDIRS) {
                val subs = f.listFiles() ?: continue
                for (g in subs.sortedBy { it.name }) {
                    if (g.isFile && isRuleTextFile(g)) out.add("${f.name}/${g.name}")
                }
            }
            if (out.size >= limit) return out
        }
        return out
    }

    private fun isRuleTextFile(f: File): Boolean = f.name.endsWith(".md") || f.name.endsWith(".txt")

    /**
     * 取「数组 / 分隔符字符串」两态参数为字符串列表。
     * 新基座 `AgentToolDefinition.coerceNestedJsonStrings` 会把 JSON-in-string 的数组解码成真数组
     * （ChatViewModel 工具执行前统一调用），但仍兼容直接传字符串的来稿 —— 输入归一化，不是兜底。
     */
    fun stringList(args: JSONObject, key: String): List<String> {
        val raw = args.opt(key) ?: return emptyList()
        return when (raw) {
            is JSONArray -> (0 until raw.length())
                .map { raw.optString(it, "").trim() }
                .filter { it.isNotBlank() }
            is String -> raw.split('，', ',', '、', ';', '；', ' ', '\n', '\t')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    /**
     * 中文网文字数统计：CJK 字符（含全角标点）每字计 1，连续拉丁字母/数字串计 1 词，空白不计。
     * 原样移植自旧线 `core/novel/NovelModels.kt` 的 `countWords`（该数据层文件未随本项目移植，避免重写整层）。
     *
     * 注意：NovelTools.stats 用的是 `content.length`（字符数口径）。润色汇报要的是「字数」，
     * 两个口径不同属预期：stats 报字符、polish 报字数，各自的输出里都写明了单位。
     */
    fun countWords(text: String): Int {
        var count = 0
        var inLatinWord = false
        for (ch in text) {
            when {
                ch.isWhitespace() -> inLatinWord = false
                ch.code < 0x2E80 -> {
                    // 拉丁字母 / 数字 / 半角符号：连续串只计 1 词
                    if (!inLatinWord) {
                        count++
                        inLatinWord = true
                    }
                }
                else -> count++ // CJK 字符与全角标点
            }
        }
        return count
    }
}

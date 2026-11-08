# 「25 流派 + 6 子代理技能包」OpenMinis SKILL.md 适配方案

> 调研日期：2026-11-08 ｜ 调研人：技能包移植子代理（只读调研 + 本报告，未 push、未改远端）
> 参考检出：`/tmp/ska/refold`（`c170c5e`，feature/penai 历史）
> OpenMinis 上游：`/tmp/ska/openminis`（`1a71df0`，Merge PR #495）
> 目标仓库：`/tmp/ska/anise`（fix-compile，`022e480`）
> 结论全部基于实际读到的源码/文档；证据路径见文末「引用证据」清单。

---

## 0. 摘要（先说结论）

- **资产清点**：待移植资产共 **37 个文件 ≈ 98.5 KB + 25 个流派正文**。penai 套件 27 个文件（25 流派 `SKILL.md` 共约 **126 KB**、`README.md` 9,489 B、`manifest.json` 13,821 B）+ `assets/prompts/` 10 个文件（6 子代理提示词、2 机制契约、persona、style_tokens，合计 72,152 B）。同提交里的 `assets/skills/zorv/` 82 个文件是旧基座自带通用技能集，**不在本次范围**。
- **机制差异核心**：OpenMinis 的 `SKILL.md` frontmatter **只解析 `name` / `description` / `version` 三个字段**（`SkillRepository.kt:127-131`），旧资产的 `trigger` 及子代理的 `title/abilityWords/requires-tools/output-contract/risk/zero-write` **全部被忽略**；且 `writeSkillMd()` 重写磁盘文件时只写这三个字段（`SkillRepository.kt` `writeSkillMd`），**留在文件里的未知字段必然丢失**，必须在文件层完成合并。
- **两个必改的技术点**：
  1. **中文技能名会导致 id 为空串**：`slugify(name)` 只保留 `[a-z0-9]`（`SkillRepository.kt:1633`），「都市赘婿·隐忍爆发」归一化后是 `""`，`add()` 直接拒绝（`SkillRepository.kt:245`）。25 个流派名全是中文 → **必须自定义 ASCII id**（建议 `genre-<slug>`，如 `genre-urban-zhuixu`）走 `insertDb + writeSkillMd` 直写，或修 `slugify`。
  2. **`description` 被 200 字符截断**（`MAX_SKILL_DESC_LENGTH`，`SkillRepository.kt:390-392`）：25 个流派 description 现长 153–209 字符，**6 个超限被截断**；trigger 词（67–95 字符）必须并入 description，合并后 237–304 字符，**超限更多**。触发能力完全依赖 description（规范原话："this is the primary triggering mechanism"），必须重写压缩到 ≤195 字符。
- **行为模式变化（最大产品风险）**：旧机制 = 技能正文直接注入系统提示词（强制遵守）；新机制 = 只有 name+description 组成的 `<available_skills>` XML 常驻上下文（上限 20 个），正文要靠 AI 自己 `read /var/minis/skills/<id>/SKILL.md`（读时计数 `recordSkillUse`）。对「写作规约必须被遵守」的场景，存在 AI 不读文件的风险，需要应用层补偿（见 §7 风险 R4）。
- **建议先做批次 1（基础设施 + 决策落地）**：id 策略、默认关闭、会话级选用（`setSessionOverride`）这三件事定了，25 个流派脚本化改造（批次 2）只是机械劳动。
- **子代理 6 角色的最大障碍不是格式而是依赖消亡**：`requires-tools` 里点的 `novel_get_outline / novel_build_context / novel_delegate_subagent` 等工具在 fix-compile **已全部不存在**（现存 novel 工具只有 `novel_memory_text/link/graph` 三个）。新基座用原生 `subagent_task` 工具 + SubAgents 名册（`subagents.<id>.instructions` 配置路径）取代了旧的 `novel_delegate_subagent` 调度器。6 个角色的提示词**可用但其输入/输出契约（JSON 信封、`insufficient_input` 等）必须重写**。

---

## 1. 调研方法与证据来源

| 步骤 | 操作 | 结果 |
| --- | --- | --- |
| 1 | `gitm clone` 镜像 blobless 克隆 OpenAnise-Novel 全历史到 `/tmp/ska/refold`，cone sparse 检出 `c170c5e` 的 `app/src/main/assets/skills/penai` 与 `app/src/main/assets/prompts` | 成功；两种路径下 109 个 skill 文件全列举（penai 27 + zorv 82），prompts 10 |
| 2 | `gitm clone` OpenMinis（公开仓库无 token）到 `/tmp/ska/openminis`，grep `SKILL.md` + 读 SkillRepository.kt / SkillStore.swift / README / scripts | 183 个文件命中，机制全部落实 |
| 3 | clone fix-compile 到 `/tmp/ska/anise`，grep 旧机制残留、novel 工具、skill UI | 旧资产与旧加载器**零残留**；skill UI/仓库已同源 |
| 4 | `diff` 两仓库 `SkillRepository.kt` | **仅 2 行差异：package 名与 NetworkHttp 包名**——OpenMinis skill 机制在 Anise 中原样可用 |

> 注意：镜像（ghproxy.net）对 partial clone 的 lazy blob fetch 支持不好（`invalid index-pack output`），OpenMinis 最终走全量 clone；Anise 侧主会话已有 `/tmp/repos/anise-full` 全量克隆可交叉验证。

---

## 2. 资产清点表（37 个文件）

性质图例：**流派** = 25 个番茄流派写作规约；**子代理** = 6 个创作角色提示词；**公共** = README/manifest/机制契约/persona/文风。
改造量图例：**零改动** = 原样进新机制即可；**小改 front matter** = 只动 frontmatter；**需重写** = 正文也要改。

### 2.1 流派技能（`assets/skills/penai/`，25 个，单文件 4.7–5.7 KB，73–78 行）

| # | 路径（`<相对>/SKILL.md`） | 体裁 | manifest size | 新机制改造量 | 明细 |
| --- | --- | --- | --- | --- | --- |
| 1 | `urban_zhuixu/` | 都市赘婿·隐忍爆发 | 5,475 | **小改 front matter** | desc 207 字符超 200 限，须压缩并并入 trigger |
| 2 | `urban_shenyi/` | 都市神医 | 5,160 | 小改 front matter | desc 178，触发词 86 字符，合并后需压 |
| 3 | `urban_zhanshen/` | 都市战神 | 5,142 | 小改 front matter | desc 186 |
| 4 | `fantasy_qiandao/` | 玄幻签到 | 4,765 | 小改 front matter | desc 168 |
| 5 | `fantasy_renwu/` | 玄幻系统 | 4,804 | 小改 front matter | desc 174 |
| 6 | `fantasy_wudi/` | 玄幻无敌 | 4,917 | 小改 front matter | desc 153（最短） |
| 7 | `chuanyue_zhongtian/` | 穿越种田 | 5,100 | 小改 front matter | **desc 209 超限** |
| 8 | `chuanyue_niandai/` | 穿越年代 | 5,129 | 小改 front matter | desc 184 |
| 9 | `xuanyi_lingyi/` | 悬疑灵异 | 5,095 | 小改 front matter | desc 167 |
| 10 | `xuanyi_daomu/` | 盗墓探险 | 5,060 | 小改 front matter | desc 161 |
| 11 | `guize_guaitan/` | 规则怪谈 | 5,444 | 小改 front matter | desc 185 |
| 12 | `xiancheng_zhenxing/` | 县城振兴 | 5,564 | 小改 front matter | desc 200（压线） |
| 13 | `xiuxian_zc/` | 修仙职场 | 5,320 | 小改 front matter | desc 183 |
| 14 | `female_gaojian/` | 无CP大女主 | 5,404 | 小改 front matter | **desc 208 超限** |
| 15 | `qingxu_fafeng/` | 情绪发疯 | 5,456 | 小改 front matter | desc 174 |
| 16 | `changsheng_jiazu/` | 长生家族 | 5,515 | 小改 front matter | desc 186 |
| 17 | `tianming_fanpai/` | 天命反派 | 5,529 | 小改 front matter | desc 192 |
| 18 | `edu_nvpei/` | 恶毒女配 | 5,541 | 小改 front matter | desc 192 |
| 19 | `dushi_shouye/` | 都市高武 | 5,626 | 小改 front matter | **desc 208 超限** |
| 20 | `niandai_qinqing/` | 年代重生 | 5,601 | 小改 front matter | **desc 204 超限** |
| 21 | `zhiye_xt/` | 职业系统 | 5,320 | 小改 front matter | desc 198 |
| 22 | `nvxing_xingzhen/` | 女性悬疑刑侦 | 5,181 | 小改 front matter | desc 179 |
| 23 | `moyu_bailan/` | 摸鱼摆烂 | 5,182 | 小改 front matter | desc 177 |
| 24 | `zhibo_nixi/` | 直播短视频 | 5,243 | 小改 front matter | desc 175 |
| 25 | `honghuang_bianji/` | 洪荒神话 | 5,535 | 小改 front matter | desc 168 |

共性结论（写脚本前已逐文件验证）：
- frontmatter 三字段**全部单行**（`name` / `description` / `trigger`），结构完全一致 → **可脚本化批处理**；
- 正文结构一致：`# 流派名` + 引言块（套件/分类/流派 ID/核心卖点）+ `## 1 流派定位` … `## 8 质检清单` 共 8 章；
- 正文含「番茄原文规约」逐字 blockquote，是保真度核心，**正文原则上不动**；
- 正文 73–78 行 ≈ 4.6–5.6 KB，远小于规范正文上限 5k 词 → **无需拆 references**。

### 2.2 子代理与公共资产（`assets/prompts/`，10 个文件）

| 路径 | 字节 | 性质 | 新机制改造量 | 关键障碍 |
| --- | --- | --- | --- | --- |
| `subagents/outline.md` | 7,589 | 子代理·大纲 | **需重写** | 输入契约依赖 `novel_delegate_subagent` + `novel_get_outline/novel_build_context`（均不存在）；输出 JSON 信封与 `subagent_task` 原生 result 协议冲突 |
| `subagents/character.md` | 7,208 | 子代理·人物 | 需重写 | 同上（`novel_materials_get/novel_relationships` 不存在） |
| `subagents/foreshadow.md` | 6,277 | 子代理·伏笔 | 需重写 | 同上（`novel_hooks_status` 不存在） |
| `subagents/polish.md` | 6,645 | 子代理·润色 | 需重写 | 同上（`novel_get_chapter` 不存在）；但「去 AI 味」能力与 style_tokens 天然互补 |
| `subagents/review.md` | 7,018 | 子代理·审读 | 需重写 | 同上（`novel_chapter_stats` 不存在） |
| `subagents/consistency.md` | 6,526 | 子代理·一致性 | 需重写 | 同上（`novel_consistency_check/novel_query_previous` 不存在） |
| `subagents/DELEGATION.md` | 13,136 | 公共·委派契约 | **改写为 references/** | 冻结的是 `novel_delegate_subagent` 信封 schema；新基座用 `subagent_task` 工具（`TaskArgs`：task/wait 等），字段语义不同 |
| `subagents/CONVERGENCE.md` | 6,834 | 公共·收敛协议 | 小改即可入 references | 内容是主 AI 编排规则（权威源优先级/冲突仲裁/3 轮收敛），与工具无关，价值保留；路径 `filesDir/novel/...` 需核对是否仍存在 |
| `penai-novel/persona.md` | 4,948 | 公共·人格 | **需决策归属** | 无 frontmatter，纯人格 system prompt；新基座人格/系统提示词在 `ChatViewModel` 组装，不是 skill 加载链。放 skill（用户可关，人格可被关掉不合适）还是内置常量，**要用户拍板** |
| `penai-novel/style_tokens.md` | 9,035 | 公共·文风令牌 | 小改（作 references） | 无 frontmatter；禁词表/句式规范，是 polish 角色与写作正文的直接依赖，建议作为 novel-writing 技能的 `references/style-tokens.md` |
| `skills/penai/README.md` | 9,489 | 公共·套件文档 | **零移植（并入正文）** | OpenMinis 规范明确禁止 README/CHANGELOG 类杂物（`SkillStore.swift:392-394`「What NOT to Include」）。套件索引信息并入各 SKILL.md 引言块 |
| `skills/penai/manifest.json` | 13,821 | 公共·登记清单 | **零移植（转译为生成脚本中间产物）** | 新机制没有清单概念：skills.db `skills` 表即权威清单（id/name/description/version/is_enabled/use_count…）。`signature`(HMAC) / `genre_id` / `category` / `suite` 字段在新 `Skill` data class 中**无对应列**（`SkillRepository.kt:176-190`） |

---

## 3. 新旧机制差异表（逐字段）

### 3.1 加载与存储层

| 维度 | 旧 ZorvAI（`QuroSkill`） | OpenMinis（`SkillRepository`） | 对移植的影响 |
| --- | --- | --- | --- |
| 资产位置 | `app/src/main/assets/skills/<suite>/<name>.md`（平铺） | `filesDir/minis-global/skills/<id>/SKILL.md`（每技能一目录） | 目录结构从平铺变一技能一目录；`<slug>/SKILL.md` 的旧形态恰好兼容 importFromArchive（zip 内一层目录） |
| Linux shell 路径 | 无（SharedPreferences） | `/var/minis/skills/<id>/SKILL.md`（rootfs 挂载，agent 可读） | 正文才能被 file_read 触发 |
| 元数据库 | SharedPreferences + `QuroSkill` 记录 | SQLite `skills.db`：`skills` 表 + `session_skill_overrides` 表 | 清单/schema 全部重建，manifest.json 作废 |
| id 生成 | `penai_` + sha256(slug)[:12] | `slugify(name)` 仅 `[a-z0-9]`，**空则拒绝** | **中文名必须配 ASCII id**（本方案最大改造点） |
| 签名 | HMAC-SHA256 `SkillSigner` 盐 `zorv-ai-builtin-skill-sign-v1` | 无签名机制（仅磁盘签名 `SkillDiskSignature` 用于变更检测） | 防篡改诉求由「用户可改」开放模型取代 |
| 开关模型 | 全局 `enabled`（默认 false）+ 作品选用 | 全局 `is_enabled` + **会话覆盖** `session_skill_overrides`（`setSessionOverride`） | 会话覆盖比旧「按作品选用」更细；作品→流派映射需应用层桥接 |

### 3.2 SKILL.md 字段级对照

| 旧字段（流派） | 新机制行为 | 处置 |
| --- | --- | --- |
| `name: <中文流派名>`（单行） | 解析；但它是**id 生成原料**且是 UI 显示名 | 保留中文显示名；id 由播种器另给（`genre-<slug>`） |
| `description: <单行>` | 解析；注入 XML，**>200 字符截断**；官方称「主要触发机制」 | **必改**：压到 ≤195 字符并吸收 trigger 语义 |
| `trigger: <逗号分隔>` | **完全忽略**，且 `writeSkillMd` 重写文件时丢失 | **删除该字段**，把 3–5 个最关键的触发词并进 description |
| （无） | `version` 默认 `1.0.0` | 显式写 `version: 1.0.0` 便于日后升级 |
| 正文 8 章 | 全文作为 `body`，agent 读文件后进上下文 | 原样保留；引言块（套件/分类/流派 ID）建议保留（给 agent 的元数据） |

### 3.3 子代理 frontmatter 字段级对照（6 个角色）

| 旧字段 | 新机制行为 | 处置 |
| --- | --- | --- |
| `name: outline` | 解析 → id `outline`（ASCII 安全） | 保留（或加前缀 `novel-` 防撞名） |
| `title: 大纲代理` | 忽略 | 并入 description 或正文 H1 |
| `description`（≤100 字 L1 摘要） | 解析 + 200 截断 | 保留并扩写成「何时派发此角色」 |
| `trigger` | 忽略 | 并入 description |
| `abilityWords` | 忽略 | 删（描述写清楚即可） |
| `requires-tools: novel_*` | 忽略，**且这些工具已不存在** | 删；正文改为「需要用现有工具时列 novel_memory_*」 |
| `version: 1.0.0` | 解析 | 保留 |
| `output-contract: 1.0` | 忽略 | 正文里改述为对 `subagent_task` 返回结构的约定 |
| `risk: low` | 忽略 | 删（正文说明只读约束即可） |
| `zero-write: true` | 忽略（加载器断言作废） | 正文保留「零写」纪律段落 |

### 3.4 触发与注入链路差异（产品语义变化）

| 维度 | 旧 | 新 |
| --- | --- | --- |
| 常驻内容 | 无（关闭时零成本） | `<available_skills>` XML：name + 截断 description + path，每技能 ~30 词 |
| 数量上限 | 不限（用户全开自担） | **20 个**（`MAX_SKILLS_IN_PROMPT`）；超出按 bundled>7 天内更新>useCount 截断并提示「N more skills not shown」 |
| 正文何时进上下文 | enabled 即注入系统提示词（主动、强制） | agent 主动 `read` SKILL.md 之后（被动、可能漏读） |
| 使用统计 | 无 | `recordSkillUse`（use_count，>1000 归一化）反哺优先级 |

---

## 4. 改造方案（含完整样例）

### 4.1 总体策略

1. **格式层**：25 个流派 + 6 个子代理统一改造成「每技能一目录 + `SKILL.md` + 可选 `references/`」。流派技能保持单文件；novel-writing 子代理技能用 `references/` 收纳 6 角色与两份契约、`style_tokens`。
2. **id 层**：播种器给每个技能显式 ASCII id（genre 技能 `genre-<slug>`；子代理技能 `novel-outline` 等），绕开 `slugify` 中文空串问题，同时避免与 `skill-creator` 及用户导入技能撞名。
3. **字段层**：trigger → description 合并并压到 ≤195 字符；未知字段一律删除（因为 `writeSkillMd` 会重写文件，留也无用）；补 `version: 1.0.0`。
4. **打包投放层**：`app/src/main/assets/skills-novel/` 放 zip（每技能一个 zip，如 `genre-urban-zhuixu.zip`，zip 内一层目录 `<id>/SKILL.md`——`importFromArchive` 明确支持「根目录或一层目录」，`SkillRepository.kt:549-553`），或直接放平铺 md + 播种器读文本走 `insertDb/writeSkillMd`。**推荐后者**（不需解压流，播种逻辑与 OpenMinis 的 `installBundledSkills()` 同构，先例即 `SkillRepository.kt:1238-1270`）。
5. **默认关闭 + 会话选用**：播种后立即 `setEnabled(id, false)`（对齐旧「默认全关、按作品显式选用」）；作品/会话选定流派时 `setEnabled` 或 `setSessionOverride(sessionId, id, true)`（UI 已就绪：`SessionSkillsSheet.kt`）。

### 4.2 完整改造样例 ①：流派技能 `urban_zhuixu`（都市赘婿）

**改造前**（`c170c5e` 原文 frontmatter）：

```markdown
---
name: 都市赘婿·隐忍爆发
description: 番茄都市赘婿流派写作规约。前期隐忍被瞧不起，后期身份揭露打脸，情绪压抑→爆发。当用户创作或续写都市赘婿、隐忍流、身份揭露打脸类小说时使用。
trigger: 都市赘婿, 隐忍爆发, 打脸, 身份揭露, 情绪反差, 护短, 赘婿, urban_zhuixu
---
```

**改造后**（新机制，正文 8 章原样保留）：

```markdown
---
name: 都市赘婿·隐忍爆发
version: 1.0.0
description: 番茄都市赘婿流派规约：前期隐忍被羞辱，后期身份揭露集中打脸。写/续写赘婿、隐忍流、身份揭露打脸、护短、情绪压抑爆发类都市小说时必读。
---

# 都市赘婿·隐忍爆发

> 流派套件：tomato-genre ｜ 分类：urban ｜ 流派 ID：urban_zhuixu
> 核心卖点：前期隐忍被瞧不起，后期身份揭露打脸，情绪压抑→爆发

## 1. 流派定位
……（原文 8 章逐字保留）
```

改造要点：
- `trigger` 删除，`打脸/身份揭露/赘婿/护短` 四个最高频触发语义并入 description；
- description 从 207 字符压到约 130 字符（含语义「写/续写…必读」给弱模型明确指令）；
- 播种时 id 固定为 `genre-urban-zhuixu`（genre_id 迁移到 id，同时引言块保留 `流派 ID：urban_zhuixu` 双保险，供未来 `novel_apply_genre` 类工具按 slug 解析）；
- `·` 不是 ASCII，留在 `name` 只影响显示，不影响 id（id 播种器显式给）。

### 4.3 完整改造样例 ②：子代理 `outline.md` → novel-writing 的 references

**改造前**（frontmatter 10 字段 + 委派信封正文，依赖 `novel_delegate_subagent`）。
**改造后**：不作为独立技能导入（6 个角色共用 1 个父技能 `novel-writing`），文件落位 `references/subagent-outline.md`，frontmatter **全部删除**（references 不需要 frontmatter，父技能描述负责触发），正文头部改写派发入口：

```markdown
# 大纲子代理（outline）

> 本文件是 novel-writing 技能的参考资料。主 AI 需要展开卷/章/节细纲时，
> 用 `subagent_task` 工具派发本任务（在 task 描述中粘贴下方「任务说明」段），
> 子代理返回结构化细纲文本后由主 AI 落库。本角色只读，不写任何作品数据。

## 任务说明（派发时粘贴）
你是结构工程师，唯一职责是把创作需求展开为可执行的卷/章/节三级细纲……
（旧「角色定位/输入契约」改为：现有工具能拿到什么就给什么：novel_memory_text
可查正文与记忆；缺材料时在返回里声明「缺少：xxx」，禁止脑补设定）

## 输出格式（对 subagent_task result 的约定）
1. 细纲 Markdown 正文（卷/章/节三级，爽点/反转/伏笔挂载点标注）
2. 结尾附「confidence: 高/中/低」一行与「缺少的材料」清单
```

要点：`requires-tools` 作废后，正文改为「按现有能力自适应」；JSON 信封改为主 AI 可直接消费的 Markdown + 尾部元信息行，与 `subagent_task` 的 result 语义对齐。

### 4.4 完整改造样例 ③：契约文件 `DELEGATION.md` / `CONVERGENCE.md`

`DELEGATION.md` 的 §1（资产格式与加载契约，描述的是旧 zorv 加载器）**整节删除**，保留并改写 §2 起的「信封字段 → 现有能力映射」表；与 `CONVERGENCE.md`（收敛协议：权威源 P0–P4、冲突仲裁、3 轮收敛）一起放进 `references/`。`CONVERGENCE.md` 的 `filesDir/novel/chapters/` 等路径需逐条核对新仓库是否仍存在（见 §7 R6）。

### 4.5 播种器（新增代码，示意）

```kotlin
// 参照 SkillRepository.installBundledSkills()（SkillRepository.kt:1238-1270）新增：
private fun seedBuiltinNovelSkills() {
    val guardKey = "builtin_novel_skills_v1"      // 新开一次性守卫 key，勿复用基座 key
    if (prefs.getBoolean(guardKey, false)) return
    for (packed in NOVEL_SKILL_PACK) {           // data class (id, name, description, version, assetPath)
        if (_skills.value.any { it.id == packed.id }) continue   // 幂等：用户改过就不覆盖
        val md = context.assets.open(packed.assetPath).bufferedReader().use { it.readText() }
        val skill = Skill(id = packed.id, name = packed.name, description = packed.description,
                          version = packed.version, importSource = ImportSource.BUNDLED)
        insertDb(skill); writeSkillMd(skill.copy(body = md))      // 复用手写通道，绕开 slugify
        setEnabled(packed.id, false)                             // 默认关闭（对齐旧机制）
    }
    prefs.edit().putBoolean(guardKey, true).apply()
}
```

说明：
- `ImportSource.BUNDLED` 会让它在 `skillPromptFragment` 的优先级 1——但默认 `is_enabled=false`，不进 fragment；一旦用户/作品启用，它比靠 useCount 排名的更稳。
- 若日后想让技能随 GitHub `MinisSkills` 仓库分发（README 指向的官方技能库），此播种器可保持兼容（importFromContent 对已存在 id 是 upsert）。

---

## 5. 分批实施计划

| 批次 | 内容 | 文件数 | 预估工作量 | 前置依赖 |
| --- | --- | --- | --- | --- |
| **B0 决策批** | §7 的 D1–D5 人工决策（id 策略、默认开关与选用入口、6 子代理形态、persona 归属、正文注入补偿） | 0（开会） | 0.5 天 | — |
| **B1 基础设施批** | `assets/skills-novel/` 目录 + 播种器 `seedBuiltinNovelSkills()` + 守卫 key + 单测（幂等/中文 id/默认关） | 约 5 个 Kotlin + 1 组测试 | 1 天 | B0 |
| **B2 流派批次** | 25 个 `SKILL.md` 脚本化改造：frontmatter 重写（desc 压缩 + trigger 合并 + version）、正文零改、校验（保真 diff 正文不变） | 25 文件 + 1 脚本 | 1.5 天（脚本 0.5 + 人工验收 1） | B1 的 id 表 |
| **B3 子代理批次** | `novel-writing` 父技能 SKILL.md（6 角色索引 + subagent_task 派发总则）+ `references/` 8 个文件（6 角色 + 2 契约改写）+ style_tokens | 9 文件 | 2.5 天（契约重写最重） | B1、D3 |
| **B4 联动批** | 作品 `genre` 字段 → 流派技能 id 映射、作品创建/切换时 `setSessionOverride`、ChatViewModel 注入点核对 | 约 3–5 个 Kotlin | 2 天 | B2 |
| **B5 验证批** | 首启播种→UI 开关流程、prompt fragment 检查（20 上限/200 截断/XML 转义）、备份往返（BackupExporter 已引 SkillRepository）、subagent_task 派发冒烟 | 测试为主 | 1.5 天 | B2–B4 |

合计约 **9 人天**（不含 B0 决策等待）。

---

## 6. 引用证据（源码路径与片段）

OpenMinis 上游（`/tmp/ska/openminis`）与 Anise fix-compile（路径同构，仅 package 前缀从 `com.openminis.app` 变为 `com.ai.assistance.quro`；两仓库 `SkillRepository.kt` diff 仅 2 行）：

1. **skill 形态定义**：`README.md:69-81`——"A **skill** is a folder with a `SKILL.md` file — instructions, and optionally scripts, references and assets… the body and bundled resources load only when the skill is actually used."
2. **架构注释**：`SkillRepository.kt:35-42`——"Metadata in `skills.db`… SKILL.md in `minis-global/skills/<id>/SKILL.md`… Session overrides in `session_skill_overrides` table… Prompt fragment generation for system prompt injection."
3. **frontmatter 只解析三字段**：`SkillRepository.kt:127-131`（`when (key) { "name" -> …; "description" -> …; "version" -> … }`）；`name` 空则拒绝 `SkillRepository.kt:134-135`。iOS 同款：`src/ios/Agent/Session/SkillStore.swift:435-449`（`ParsedSkillMD` 仅 name/description/version，CRLF/BOM 归一）。
4. **规范全文（含「不要 README」禁令）**：`SkillRepository.kt:1686-1779` 内嵌 `SKILL_CREATOR_CONTENT`（"Anatomy of a Skill… name + description required… Keep under 500 lines… **Do not create extraneous files: README.md, INSTALLATION_GUIDE.md, CHANGELOG.md**"）；iOS 对应 `SkillStore.swift:345-394`。
5. **id 生成与 XML 转义**：`SkillRepository.kt:1633-1645`——`slugify` = `name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')`；`escapeXml` 仅转 `& < >`（description 里的中文引号无需转义）。
6. **落盘与清单**：`writeSkillMd` 只写 name/description/version 三行 + body；`insertDb` INSERT OR REPLACE；`skills` 表列（id/name/description/version/import_source/source_url/is_enabled/installed_at/updated_at/use_count）：`SkillRepository.kt` 数据库 helper `onCreate`。磁盘目录 `File(context.filesDir, "minis-global/skills")`：`SkillRepository.kt:203-204`。
7. **注入链路与配额**：`skillPromptFragment`：`SkillRepository.kt:343-417`——上限 `MAX_SKILLS_IN_PROMPT = 20`、`MAX_SKILL_DESC_LENGTH = 200`（`:49-50`）、三级优先级 bundled > 7 天内更新 > useCount、超出提示 "N more skills not shown"。
8. **导入通道**：`importFromContent`（492-527，同 id upsert）；`importFromArchive`（536-581，"SKILL.md at the root or one directory deep"，兄弟文件解压进 `skillsDir/<id>/`）；`importFromGitHub`（623）。
9. **bundled 播种先例**：`installBundledSkills()` 1238-1270（只装 `skill-creator` v2.0.0，硬编码内容）；构建期装 assets 的先例：`scripts/gen_debug_skill.sh:36`、`scripts/gen_debug_skill_android.sh:27-31`（`cp SKILL.md → src/debug/assets/…`，注释说明为何不能用 `src/main/assets`：release 也会打包——**我们的流派技能要进 release，必须放 `src/main/assets` 且不涉调试信息**）。
10. **会话开关**：`setEnabled` 292-297、`isEnabledForSession` 301-310、`setSessionOverride` 312-317；UI 已就绪（Anise `ui/chat/SessionSkillsSheet.kt`、`ui/settings/SkillsManagementScreen.kt`、`MinisSkillsBrowserScreen.kt`、`SkillRowItem.kt`、`SkillSourceUrl.kt` 五个文件均在 fix-compile）。
11. **Anise 注入点**：`ChatViewModel.kt:16484-16490`（`skillRepository?.skillPromptFragment(activeSessionId)`）；初始化 `MinisApp.kt:584`（注释 `[T-android-safemode-lateinit-crash-147]`：SkillRepository 构造失败会连锁崩溃，播种器必须 try-catch）。
12. **旧资产形态**（`/tmp/ska/refold` @ `c170c5e`）：`assets/skills/penai/README.md` §3 自述「对齐基座 `skills/zorv/*.md`，单文件 Markdown + YAML frontmatter + manifest.json」；`manifest.json` 25 项含 `genre_id/category/signature`；`prompts/subagents/DELEGATION.md:1-55` 自述「frontmatter 字段是 manifest 条目与 cluster-skills frontmatter 的并集…播种为 `QuroSkill`…调度只走 `novel_delegate_subagent`」。
13. **依赖消亡证据**（Anise fix-compile）：`grep '"novel_get_outline"'` 等零命中；现存 novel 工具仅 `NovelMemoryGraphTools.kt` 三个：`novel_memory_text`（:210）、`novel_memory_link`（:287）、`novel_memory_graph`（:380）。原生子代理体系在位：`subagent_task`（`SubAgentDefinition.TOOL_NAME`）、`SubAgentsCollection`（`subagents.<id>.instructions` 等可写路径）、`AgentToolSwitch.kt:51`。
14. **OpenMinis 官方技能库**：`README.md:81` 指向 `github.com/OpenMinis/MinisSkills`——可作为移植后对外分发的参照（非必须）。
15. **iOS 侧路径同构**：`/var/minis/skills/<id>/SKILL.md`（`SkillRepository.kt:532` 与 iOS `SkillStore.swift` 多处一致；iOS 测试夹具 `src/ios/Debug/TestData/test-data-health.json` 含一个真实示例技能的完整 frontmatter：仅 name+description）。

---

## 7. 风险点与待用户决策项

### 7.1 技术风险

- **R1 中文名 id 空串（最高）**：不改播种通道则 25 个流派全部无法入库。 mitigations：播种器显式 id（本报告方案）或修 `slugify` 兼容中文（会同时改变用户导入路径的行为，波及 iOS 同步 id 一致性，谨慎）。
- **R2 description 200 字符截断导致触发失灵**：desc+trigger 合并后现有 25 个全部超限（237–304 字符）。不压缩的后果是 `<available_skills>` 里流派语义被「…」截断，AI 判定「何时该读」的信息不足。**这不是可选项，是必改项**。
- **R3 25 个全开 > 20 上限**：若默认开启，超出部分按「7 天更新 + useCount」淘汰并显示 "N more skills not shown"，流派选用体验不稳。→ 默认关闭（`setEnabled(false)`）+ 会话级 override，与旧规划「不挤占提示词预算」一致。
- **R4 触发模式变化导致规约不生效（产品级）**：旧机制正文直接进系统提示词；新机制靠 AI 自觉 read。写作场景对「流派规约被遵守」的期望是强制性的。缓解选项：(a) 应用层在作品绑定流派时，把流派 SKILL.md 正文拼进系统提示词（牺牲上下文，回到旧语义）；(b) 接受按需读，靠 Calibration 在 description 写强指令（「写/续写…时必读」）；(c) 混合：正文前 60 行（定位+原文规约）入提示词，其余按需读。**需决策 D4**。
- **R5 子代理契约断裂**：JSON 信封/`insufficient_input`/`novel_delegate_subagent` 派发模型与 `subagent_task` 不兼容；6 个角色的 `requires-tools` 全部指向不存在的工具。重写时必须防止「新工具也没有」的过度承诺（现有 novel 工具只 3 个 memory 系列）。
- **R6 CONVERGENCE 的路径假设**：`filesDir/novel/chapters/`、`materials/`、`novel_hooks_status` 域等权威源路径需在 fix-compile 里逐个核对存在性（本次只确认了 `NovelRepository`/`ChapterWorkflowModels.genre`/`NovelTextMemory` 在位），未知项已标注不确定。
- **R7 播种器崩溃传导**：`MinisApp.onCreate` 里 SkillRepository 构造失败有 147 号事故史（注释明写「app permanently broken」）；播种逻辑必须整体 try-catch 且不得抛。
- **R8 备份/同步的 id 一致性**：若同 id 在 iOS 端由用户手工导入同名技能，CloudSync 可能产生重复条目；自定义 id 前缀（`genre-`/`novel-`）已降低概率。iOS 端 slugify 行为未读（`SkillStore.swift` 的 id 生成不在本次细读范围）——**不确定项，标出**。
- **R9 文件重写丢字段**：任何一次 `update()`/`renameSkill()` 都会用 `writeSkillMd` 重写磁盘文件，frontmatter 只剩三字段。所以「改造」必须一次性在源文件完成，不能指望运行时保留元数据。

### 7.2 需用户拍板的决策（B0）

- **D1 id 方案**：(a) 播种器显式 `genre-<slug>`，UI 显示仍中文名（推荐，最小破坏）；(b) 修 `slugify` 兼容中文，显示/路径全中文；(c) frontmatter `name` 直接改 ASCII（UI 列表变英文）。
- **D2 默认开关与选用入口**：默认关（推荐）vs 默认开；「按作品选用」的新入口做在哪（作品创建页 / 会话技能面板 SessionSkillsSheet / 两者）。
- **D3 6 子代理形态**：(a) 单父技能 `novel-writing` + references 收纳 6 角色（推荐，符合 SKILL.md 单一触发哲学）；(b) 6 个独立技能（触发面广但 prompt 常驻 6 份索引）；(c) 回到基座名册 `subagents.<id>.instructions`（不用 skill 机制，但与「移植到 SKILL.md」目标相悖）。
- **D4 流派规约的强制力**：见 R4 的 (a)/(b)/(c) 三选。
- **D5 persona 归属**：`persona.md` 作为应用内置人格常量（推荐，人格不该被用户当技能关掉）vs 打包成技能。
- **D6 style_tokens 归属**：novel-writing 的 `references/style-tokens.md`（推荐）vs 独立技能。

---

## 8. 附：移交说明

- 本报告只读调研产出；未修改 `/sdcard/Ai/Anise Write` 仓库任何代码（仅新增本文件）。
- 三份克隆位于 `/tmp/ska/{refold,openminis,anise}`（父会话另有 `/tmp/repos/` 空间，未触碰）。
- 上游如需同步（`MinisSkills` 仓库或 OpenMinis master 变更 SkillRepository.kt），先重跑本盘点脚本再决定是否调整方案。

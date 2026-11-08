#!/usr/bin/env python3
"""kt-selfcheck.py — 本地无编译器时的 Kotlin 提交前自检（针对本仓易犯错误）。

用法：python3 scripts/kt-selfcheck.py <文件或目录...>

覆盖的失败模式（全部来自 2026-11-08 关系图 v2 CI 三轮失败的经验）：
  1. by remember 委托缺 runtime.getValue / setValue import（静态字面量检测不到）
  2. 括号 / 圆括号不配平
  3. Compose 里 @Suppress 写在非常规位置
  4. 疑似无本仓先例的 DrawScope.drawText(textLayoutResult = ...) 用法
  5. dp 字面量与 Dp.toPx() 出现在 offset { } lambda 内（应是 px 常量）
  6. 裸硬编码中文进 UI（应用走 stringResource）
  7. **数据类字段访问但该类型未 import**（第 3 轮失败根因：`work.title` 链条全 unresolved，
     只因 `NovelWork` 没 import；且报错点会偏移到 lambda 内部，极具迷惑性）

第 7 条的判定：文件里出现 `X.<field>` 链且 `X` 的类型名未出现在 import 区时告警。
已知局限：对同名局部变量/参数会误报，所以只对「明显是本仓数据类」的名字告警
（可通过 --allow-model 后缀放宽）。
不替代编译，只把 CI 往返成本（每轮 8-10 分钟）压下来。
"""
import re
import sys
from pathlib import Path

DELEGATE_RE = re.compile(r'\b(?:var|val)\s+\w+\s+by\s+remember')
BACKGROUND_HINTS = ('.dp"', '.sp"', 'Dp.toPx', '.toDp()')


def check(path: Path) -> list[str]:
    errs: list[str] = []
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()

    # 2. 括号配平
    for o, c, name in (("{", "}", "花括号"), ("(", ")", "圆括号"), ("[", "]", "方括号")):
        no, nc = text.count(o), text.count(c)
        if no != nc:
            errs.append(f"{name}不配平: {o}={no} {c}={nc}")

    # 1. 委托 import 联动
    if DELEGATE_RE.search(text):
        for need in ("androidx.compose.runtime.getValue", "androidx.compose.runtime.setValue"):
            if f"import {need}" not in text:
                errs.append(f"用了属性委托 by remember 但缺 import {need}")

    # 3. 非常规 @Suppress 位置（表达式/语句前缀）
    for i, ln in enumerate(lines, 1):
        st = ln.strip()
        if st.startswith("@Suppress(") and not re.match(
            r"@Suppress\(\"[A-Z_]+\"\)\s*(val|var|fun|class|object|@Composable)", st
        ):
            # 允许修饰声明；修饰裸表达式时给出提示
            if re.match(r"@Suppress\(\"[A-Z_]+\"\)\s*[a-zA-Z_]", st) and not re.match(
                r"@Suppress\(\"[A-Z_]+\"\)\s*(val|var|fun|class|object|@)", st
            ):
                errs.append(f"L{i}: @Suppress 修饰裸表达式，非常规写法，警惕: {st[:70]}")

    # 4. 无先例的 DrawScope.drawText 用法（跳过注释）
    for i, ln in enumerate(lines, 1):
        if ln.lstrip().startswith(("//", "*", "/*")):
            continue
        if "drawText(" in ln and "textLayoutResult" in ln:
            errs.append(
                f"L{i}: 使用 drawText(textLayoutResult=…) —— 本仓无先例、签名跨版本有变动，"
                "建议改用布局层 Text + Modifier.offset"
            )

    # 5. offset lambda 内做 Dp 换算
    in_offset = False
    depth = 0
    for i, ln in enumerate(lines, 1):
        st = ln.strip()
        if ".offset {" in st or ".offset{" in st:
            in_offset, depth = True, st.count("{") - st.count("}")
            continue
        if in_offset:
            depth += ln.count("{") - ln.count("}")
            if any(h in ln for h in BACKGROUND_HINTS):
                errs.append(f"L{i}: offset lambda 内出现 Dp 换算，应先在外部算成 px 常量: {st[:70]}")
            if depth <= 0 and "}" in ln:
                in_offset = False

    # 6. 裸中文进 UI（排除：注释、异常消息、工具 schema 描述文本——那些不是 UI 文案）
    UI_SKIP_CTX = re.compile(
        r'\b(require|check|checkNotNull|error|throw|requireNotNull|description|'
        r'optString|put\(|append|hint|message|content)\s*[\(=:]'
    )
    for i, ln in enumerate(lines, 1):
        if ln.lstrip().startswith(("//", "*", "/*")):
            continue
        ctx = "\n".join(lines[max(0, i - 3):i + 2])
        if UI_SKIP_CTX.search(ctx):
            continue
        for m in re.finditer(r'"(?:[一-鿿][^"]*)"', ln):
            s = m.group(0)
            if len(s) > 6 and "stringResource" not in ln and "getString" not in ln:
                errs.append(f"L{i}: 疑似硬编码 UI 中文，应抽取到 strings.xml: {s[:40]}")

    # 7. 数据类字段访问但类型未 import（第 3 轮 CI 失败的形态）
    #    `work.title` 链条 unresolved 时 Kotlin 把报错推到外层 lambda，迷惑性极强。
    #    判定：出现 `x.field`（x 小写、field 属数据类高频字段）且文件里没有
    #    任何 import 收尾以「该字段所属的典型类型名」——这是启发式，宁可漏不要吵。
    # 只收高度特异的字段名（通用名如 title/name/id/genre 一律不收，噪音 > 价值）
    MODEL_FIELDS = {
        "chapters": "NovelWork",
        "volumes": "NovelWork",
        "fromName": "NovelRelationship",
        "toName": "NovelRelationship",
        "strength": "NovelRelationship",
        "workId": "NovelRelationship|NovelMemory|NovelHook",
        "chapterRef": "NovelMemory",
    }
    imports = {ln[len("import "):].strip() for ln in lines if ln.startswith("import ")}
    imported_simple_names = {i.rsplit(".", 1)[-1] for i in imports}
    pkg_m = re.search(r'^package\s+([\w.]+)', text, re.M)
    own_pkg = pkg_m.group(1) if pkg_m else ""
    # 本文件内定义的所有属性名（任一类里的 val x: T）：同名访问不算未 import
    local_field_names = set(re.findall(r'\bval\s+(\w+)\s*[:=]', text))
    # 同包（或父包）的兄弟类型无需 import。用「import 区是否出现过同包/父包前缀」近似。
    pkg_prefixes = [own_pkg] + [".".join(own_pkg.split(".")[:i]) for i in range(1, len(own_pkg.split(".")))]
    same_package_imported = any(
        i.rsplit(".", 1)[0] in pkg_prefixes for i in imports if "." in i
    ) if own_pkg else False
    for field, types in MODEL_FIELDS.items():
        if not re.search(r'\.' + re.escape(field) + r'\b', text):
            continue
        if field in local_field_names:
            continue  # 本文件有同名属性，非外部数据类
        # 若本仓同包类型已被 import 过，说明作者在用同包兄弟类型——放行
        if same_package_imported and field in {"strength", "workId", "fromName", "toName"}:
            continue
        candidates = [t.strip() for t in types.split("|")]
        # 任一候选类型出现在 import 区（按简单名或 FQN 尾部）即放行
        if any(c in imported_simple_names for c in candidates):
            continue
        errs.append(
            f"访问了 `.{field}`（可能是 {types} 的字段）但这些类型都未 import —— "
            "数据类未导入时整条字段链 unresolved，且报错点偏移到外层 lambda"
        )

    return errs


def main() -> int:
    targets = [Path(p) for p in sys.argv[1:]]
    files: list[Path] = []
    for t in targets:
        files.extend(t.rglob("*.kt") if t.is_dir() else [t])

    total = 0
    for f in files:
        errs = check(f)
        if errs:
            total += len(errs)
            print(f"✗ {f}")
            for e in errs:
                print(f"    - {e}")
        else:
            print(f"✓ {f}")

    print(f"\n共检查 {len(files)} 个文件，问题 {total} 处")
    return 1 if total else 0


if __name__ == "__main__":
    raise SystemExit(main())

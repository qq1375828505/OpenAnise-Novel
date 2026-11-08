#!/usr/bin/env bash
# ============================================================
# rename-to-openanise-novel.sh
# 在 Minis 沙箱的「迁移工作树」里执行：产品身份 Minis → OpenAnise-Novel
#
# 用法（在 Minis 沙箱内）：
#   sh "/var/minis/mounts/Ai/Anise Write/rename-to-openanise-novel.sh"            # 试运行，只报告
#   sh "/var/minis/mounts/Ai/Anise Write/rename-to-openanise-novel.sh" --apply     # 实改（默认范围）
#   sh "/var/minis/mounts/Ai/Anise Write/rename-to-openanise-novel.sh" --apply --tier2  # 连默认/中文用户文案一起改
#
# 工作树路径默认 /var/minis/workspace/anise-v2/minis-base，可用第一个参数覆盖。
# 可重复执行（幂等）。绝不触碰：.git / assets / keystore 等密钥材料 / minis:// 协议 /
# MinisApp·Theme.Minis 等代码标识符 / 包名 com.ai.assistance.quro / GPL 归属声明。
# ============================================================
set -u
ROOT="/var/minis/workspace/anise-v2/minis-base"
APPLY=0; TIER2=0
for a in "$@"; do
  case "$a" in
    --apply) APPLY=1 ;;
    --tier2) TIER2=1 ;;
    *) [ -d "$a" ] && ROOT="$a" ;;
  esac
done
[ -d "$ROOT" ] || { echo "❌ 工作树不存在: $ROOT"; exit 1; }
echo "工作树: $ROOT"
echo "模式  : $([ "$APPLY" = 1 ] && echo 实改 || echo 试运行)  |  Tier2(默认+中文文案): $([ "$TIER2" = 1 ] && echo 开 || echo 关)"
echo "------------------------------------------------------------"
python3 - "$ROOT" "$APPLY" "$TIER2" <<'PY'
import os, sys
ROOT, APPLY, TIER2 = sys.argv[1], sys.argv[2] == "1", sys.argv[3] == "1"
NEW = "OpenAnise-Novel"

def skip(p):
    pl = p.lower()
    return ("/.git/" in p or "/assets/" in pl or "keystore" in pl or ".jks" in pl
            or ".p12" in pl or ".pem" in pl or "signing" in pl
            or "/gradle/wrapper/" in pl)

def read(p):
    try:
        return open(p, encoding="utf-8").read()
    except Exception:
        return None

def edit(p, old, new):
    s = read(p)
    if s is None or old not in s:
        return 0
    if APPLY:
        open(p, "w", encoding="utf-8").write(s.replace(old, new))
    return s.count(old)

report = []

# ---- Tier 1a：所有语言包的启动器名字 ----
files = []
for dp, _dn, fn in os.walk(os.path.join(ROOT, "app/src/main/res")):
    for f in fn:
        if f == "strings.xml":
            p = os.path.join(dp, f)
            if edit(p, '<string name="app_name">Minis</string>',
                    f'<string name="app_name">{NEW}</string>'):
                files.append(os.path.relpath(p, ROOT))
report.append(("T1a app_name（启动器显示名）", len(files), files))

# ---- Tier 1b：Gradle 工程名（影响 APK/模块文件名）----
n = edit(os.path.join(ROOT, "settings.gradle.kts"),
         'rootProject.name = "Minis"', f'rootProject.name = "{NEW}"')
report.append(("T1b rootProject.name", n, ["settings.gradle.kts"] if n else []))

# ---- Tier 1c：两处 Anise Write 自称注释 ----
t1c = [
    ("app/src/main/java/com/ai/assistance/quro/tools/NovelTools.kt",
     "Anise Write 小说写作工具集 —— 基于 OpenMinis 原生工具契约。",
     f"{NEW} 小说写作工具集 —— 基于 OpenMinis 原生工具契约。"),
    ("app/src/main/java/com/ai/assistance/quro/tools/AgentTools.kt",
     "（从 Anise Write 移植",
     f"（从 Anise Write 迁移，现名 {NEW}"),
]
for rel, old, new in t1c:
    n = edit(os.path.join(ROOT, rel), old, new)
    report.append(("T1c 注释统一", n, [rel] if n else []))

# ---- Tier 2（可选）：默认(英)+中文/繁中 的用户可见文案 ----
t2 = []
if TIER2:
    for loc in ("values", "values-zh", "values-zh-rTW"):
        p = os.path.join(ROOT, "app/src/main/res", loc, "strings.xml")
        s = read(p)
        if s is None:
            continue
        lines, changed = s.splitlines(), 0
        for i, l in enumerate(lines):
            if "Minis" in l and "OpenMinis" not in l:
                lines[i] = l.replace("Minis", NEW)
                changed += 1
        if changed and APPLY:
            open(p, "w", encoding="utf-8").write("\n".join(lines) + "\n")
        if changed:
            t2.append(f"{loc}/strings.xml ({changed} 行)")
report.append(("T2 默认+中文用户文案", len(t2), t2))

for name, n, detail in report:
    print(f"  {name:26} {n} 处")
    for d in detail:
        print(f"      - {d}")

# ---- 改后验证 ----
def cnt(pat):
    t = 0
    for dp, _dn, fn in os.walk(ROOT):
        for f in fn:
            p = os.path.join(dp, f)
            if skip(p):
                continue
            s = read(p)
            if s:
                t += s.count(pat)
    return t

print("\n== 验证 ==")
print(f'  app_name={NEW} : {cnt(chr(60)+"string name=" + chr(34) + "app_name" + chr(34) + chr(62) + NEW + chr(60)+"/string"+chr(62))} (期望 23)')
print(f'  残留 app_name=Minis        : {cnt(chr(60)+"string name=" + chr(34) + "app_name" + chr(34) + chr(62) + "Minis" + chr(60)+"/string"+chr(62))}')
print(f'  残留 "Anise Write" 自称     : {cnt("Anise Write")}')
print(f'  minis:// 协议（应保持不动）: {cnt("minis://")}')
print(f'  包名 quro（应保持不动）     : {cnt("com.ai.assistance.quro")}')
if APPLY:
    print("\n✅ 已实改。下一步：git add -A && git commit -m '[Rename] 产品身份 Minis → OpenAnise-Novel' && git push")
else:
    print("\n（试运行结束，未改任何文件。加 --apply 实改，加 --tier2 连同用户文案一起改）")
PY

<div align="center">

# OpenAnise-Novel

> 📌 **Project status / start here if you are an AI agent: [PROGRESS.md](./PROGRESS.md)** (done ✓ / pending ⏳ / cancelled ❌ — read it before scanning the whole repo)

### On-device AI writing agent for Chinese web novels · turning "a story idea" into deliverable chapters

*Seven-stage chapter workflow, composite writing tools, 25 genre presets, 6 sub-agents and a character-relationship graph — all on your phone.*

[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](./LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android--API%2026%2B-3DDC84.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-7F52FF.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202025.09.00-4285F4.svg)](https://developer.android.com/compose)
[![Base](https://img.shields.io/badge/base-OpenMinis-8A8A8A.svg)](https://github.com/OpenMinis/OpenMinis)
[![CI](https://img.shields.io/github/actions/workflow/status/qq1375828505/OpenAnise-Novel/.github/workflows/build.yml?branch=main)](https://github.com/qq1375828505/OpenAnise-Novel/actions)

</div>

---

## What this is

OpenAnise-Novel is a **web-novel writing agent** built on top of **[OpenMinis](https://github.com/OpenMinis/OpenMinis)**, an on-device Android AI Agent base (GPL-3.0).

- **The base provides**: on-device inference (MNN / llama.cpp), a proot terminal, a script engine, the tool runtime, the plugin / ACI framework, memory and knowledge base, and the general chat UI.
- **OpenAnise-Novel adds**: a seven-stage domain workflow for writing one chapter, composite writing tools, 25 Chinese web-novel genre presets, 6 writing sub-agents, and a foreshadowing / relationship / memory graph triple.

**Engineering rules** (inherited from the base): **no silent fallbacks** (parse failure throws — never returns fake data), **resumable runs** (every stage's state is on disk, so killing the process loses nothing), **explicit contracts** (stage inputs/outputs are encoded on data classes, not on convention).

> Formerly code-named **PenAi**, renamed **Anise Write** on 2026-10-22 and **OpenAnise-Novel** on 2026-11-07 (app name, repo name and docs updated together; the Android package name and internal module ids stay the same to preserve upgrade compatibility). The base was re-based from **ZorvAI** onto **OpenMinis** on 2026-11-07.

## Core capabilities

### Seven-stage chapter workflow

```
Requirements → Research → Outline → Drafting → Self-review → Polish → Delivery
```

Every stage emits a strongly typed object and persists it. When a stage fails, **only that stage** is marked for re-run — the rest of the checkpoint recovers as usual, so one bad step never loses the whole run.

### Writing tools (6 registered tools / 13 actions)

Composite tools use the "one tool name + `action` enum dispatch" pattern (same idea as the base's `browser_use`), so the tool surface the model sees stays stable:

| Registered tool | actions | Coverage |
|---|---|---|
| `novel_write` | list / read / write / search / stats / rule / backup | chapter listing and read/write, keyword search, status stats, spec read, chapter backup |
| `novel_review` | humanize / title / shuangdian / water | AI-flavour diagnostics, title candidates, "double hook" dashboard, word-count padding report |
| `novel_polish` | prepare / apply | polish dry-run, then apply (prepare first, revertible) |
| `novel_memory_text` | — | writing memory read/write (one of the dual tracks) |
| `novel_memory_link` | — | memory link creation |
| `novel_memory_graph` | — | memory graph traversal |

All tools go through the base's central registry (`AgentTools.Builder.add(...)` plus a `coreNames` allow-list). The AI can call them on its own, and the user can trigger them manually.

### 25 Chinese web-novel genre presets

Built-in specs for 25 genres (urban power-fantasy, xuanhuan, mystery, ancient romance, campus, infinite-flow, sci-fi apocalypse, slice-of-life …). One tap applies a preset to the current work and trims the context budget to the platform's chapter rules. Presets ship as an asset pack in the repo (`assets/skills/penai/`) and need no network.

### 6 writing sub-agents + delegation protocol

Outline, characters, foreshadowing, polish, review and continuity each get their own prompt asset. Delegation uses one envelope protocol: session id, target route, round cap, previous-round results replayed, **zero-write assertion** (sub-agents may not modify files), and visible failures with a list of usable sub-agents.

### The graph triple

- **Foreshadowing**: a planted → advanced → recovered state machine, queryable across the whole book
- **Character relationships**: typed and weighted edges, renderable as a graph (Compose Canvas + repulsion physics)
- **Memory store**: cross-session writing memory that backs the whole-book continuity check

---

## Architecture

```
┌──────────────── OpenMinis base (GPL-3.0, upstream can be re-synced) ────────────────┐
│ On-device inference MNN/llama.cpp · proot terminal · script engine · tool runtime   │
│ Plugin/ACI framework · memory & knowledge base · general chat & settings UI          │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                ▲ isomorphic extension (tool domain + skill pack, no base fork)
┌────────────────────────── OpenAnise-Novel delta layer ─────────────────────────────────────┐
│ core/novel/           domain contracts: works/volumes/chapters · assets · graphs · workflow │
│ tools/novel/          composite writing tools (novel_write/review/polish + memory triple)    │
│ assets/skills/penai/  25 genre presets + manifest                                            │
│ assets/prompts/subagents/ 6 sub-agent prompts + delegation protocol                          │
│ ui/novel/             writing UI + relationship graph v2 Canvas (native Compose)            │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Directory quick tour

| Path | Contents |
|---|---|
| `app/src/main/java/com/ai/assistance/quro/core/novel/` | domain contract layer: work/volume/chapter models, asset repositories, graph store, workflow models and codecs (pure Kotlin, no Android deps, JVM-unit-testable) |
| `app/src/main/java/com/ai/assistance/quro/tools/` | composite tool implementations and registry (`NovelTools` / `NovelReviewTools` / `NovelPolishTools`) |
| `app/src/main/assets/skills/penai/` | 25 genre preset asset pack |
| `app/src/main/assets/prompts/subagents/` | sub-agent prompts and the delegation protocol (`DELEGATION.md`) |
| `app/src/main/java/com/ai/assistance/quro/ui/novel/` | writing UI and design tokens (`NovelDesign` is the single source of numeric truth) |
| `docs/plan-v1.1/` | the OpenAnise-Novel refactor plan (target shape, trim list, UI route decision) |
| `docs/progress/` | recovery plans, stage notes and handoff docs |

> 📌 **Project record**: [PROJECT_RECORD.md](PROJECT_RECORD.md) — identity / architecture / feature inventory / key decisions / open items / red lines at a glance.

## Progress

| Phase | Content | Status |
|---|---|---|
| Phase 3a | structure domain: works / volumes / chapters / outline | ✅ |
| Phase 3b | asset domain: eight asset classes + RAG search | ✅ |
| Phase 3c | graph domain: foreshadowing / relations / memory + continuity check | ✅ |
| Phase 4a | writing domain: genres, title candidates, AI-flavour removal, word-count / hook dashboard, sub-agent delegation | ✅ |
| Phase 5 | contract alignment: workflow model shares one source with Phase 1/3a/3b (no second truth for chapters/outline/assets) | ✅ |
| Phase 6a | writing workbench: chapter / asset library / relationship graph / stats tabs + grouped-list component library + plain-text editor | ✅ |
| Phase 6b | relationship graph v2: character-card nodes + faction clustering + type-coloured edges + force-directed layout + tap-to-highlight | ✅ |
| Phase 6c | finish the Minis component set (buttons / bottom sheets / swipe row actions / menus / dialogs), chapter row swipe, graph node dragging | ⏳ pending |
| Phase 6.5 | model groups / writing profiles: pick a model per task (group management UI + sub-agent binding + delegation intent fields, pending real-device verification, see `docs/design/ModelGroup_SPEC.md`) | 🚧 in progress |
| Phase 7b | signed packaging and release | ⏳ pending |

> **UI route** (frozen in the plan): keep the upstream native Kotlin + Jetpack Compose; the writing UI is ours, no Flutter port — only novelIDE's information architecture and interaction patterns are referenced. The rich-text editor is a separate `:novel-editor` module (WebView-backed, degradable to plain text); the relationship graph is our own Compose Canvas renderer.
>
> Visual spec: [`docs/design/OpenAnise-Novel_UI_SPEC.md`](docs/design/OpenAnise-Novel_UI_SPEC.md) — **fully follows the Minis interface layout** (grouped lists, inset dividers, desaturated iOS blue accent, tinted rounded icons), with numbers pixel-measured from iOS reference screenshots, all collected in one source of truth `NovelDesign`, plus a **Minis component → this project's implementation** mapping table. Minis = OpenMinis (GPL-3.0): only the layout and numbers are taken; the implementation is written independently, with no OpenMinis code or assets copied.

## Building from source

### Requirements

- **Android 8.0+ (API 26+)** device
- **Dev machine**: JDK **17+**, Android SDK (compileSdk 36 / minSdk 26), Gradle (use the repo's wrapper `./gradlew`); NDK is needed to compile the offline engine's native libs

### Build

```bash
# 1. Clone the repo
git clone https://github.com/qq1375828505/OpenAnise-Novel
cd OpenAnise-Novel

# 2. Prepare the environment
#    - Install JDK 17+, set sdk.dir=/path/to/Android/Sdk in local.properties
#    - Install the NDK if you need the offline engine's native libs

# 3. Build the debug package (produces full + fdroid flavours)
./gradlew assembleDebug
#    output: app/build/outputs/apk/*/debug/app-*-debug.apk

# 4. Build the release package (bring your own signing config, aligned with scripts/sign.sh)
./gradlew :app:assembleFullRelease
#    output: app/build/outputs/apk/full/release/app-full-release.apk

# Clean
./gradlew clean
```

> The `full` flavour ships prebuilt native libraries (ncnn / sherpa / proot) and a source-compiled offline engine (`:mnn` + `:llama`); the `fdroid` flavour ships no prebuilt binaries. Releases go through `scripts/sign.sh` (idempotent verify / sign) and the tag-triggered release workflow.

### CI

The `build` workflow runs on every push: `:app:assembleDebug` (github + store flavours) → uploads the APK artifact; a `v*` tag triggers the signed release flow. The unit-test step is **currently paused**: the upstream test sources (the `com/openminis/app` package under `app/src/test`) do not compile under Kotlin 2.1.20 — see the comment in the workflow; it will be restored once adapted.

## Documentation index

| Document | Contents |
|---|---|
| [`docs/plan-v1.1/plan.md`](docs/plan-v1.1/plan.md) | the refactor plan: target shape, capability trim list, UI route and layering decisions |
| [`docs/design/OpenAnise-Novel_UI_SPEC.md`](docs/design/OpenAnise-Novel_UI_SPEC.md) | mobile UI spec (**fully follows the Minis layout**; component mapping, single source of truth, measured contrast, licence boundaries) |
| [`docs/design/ModelGroup_SPEC.md`](docs/design/ModelGroup_SPEC.md) | model group / writing profile spec (pick per task: three-way intent, pin-first, explicit fallback) |
| [`docs/progress/ACCEPTANCE_CHECKLIST.md`](docs/progress/ACCEPTANCE_CHECKLIST.md) | real-device acceptance checklist (Phase 8/9 prerequisite, tick item by item) |
| [`docs/architecture/`](docs/architecture/) | base architecture docs (terminal, browser, …) |

## Licence and attribution

- OpenAnise-Novel is a derivative work of **OpenMinis** (GPL-3.0): the base code comes from OpenMinis, so the whole project is released under **GPL-3.0** (see [`LICENSE`](./LICENSE)). This repository is the Corresponding Source for the combined work.
- **ZorvAI** (Apache-2.0) was the base before 2026-11-07. Because Apache-2.0 permits relicensing a combined work under GPL-3.0, its copyright and attribution notices are preserved unchanged — see the first section of [`NOTICE`](./NOTICE).
- **NOTICE** keeps the full attribution list for upstream (OpenMinis / ZorvAI) and every third-party component (GeckoView / MIT / Apache-2.0 / GPL+LGPL engine libs, …). GPL-3.0 does not forbid bundling components under permissive licences; as long as those licences allow redistribution, their notices are preserved verbatim.
- The **visual paradigm** references OpenMinis's iOS-side implementation (`src/ios/`): **only the layout and numbers** are taken (grouped lists, inset dividers, desaturated iOS blue, tinted rounded icons). The implementation is written independently and **no OpenMinis code or assets were copied**; those numbers live in `NovelDesign` (with `design_tokens.json` as the numeric source of truth).
- Domain assets come from **novelIDE** (MIT): only its **information architecture and interaction patterns** are referenced; **no Flutter code was ported**. If code is introduced later, the MIT notice must be kept per MIT's terms.

## Contributing

Issues and PRs are welcome. Please follow: no binary artifacts or prebuilt native libs in `main` (the `fdroid` flavour must stay "no prebuilt binaries in the repo"), no signing credentials (`keystore.properties` / `*.jks` / `*.p12` never get committed), and commit messages in the `[T-xxx] module: description` form.

## Keywords

web-novel writing AI · AI novel writing · Android AI Agent · on-device LLM · chapter workflow · character graph · foreshadowing management · Jetpack Compose · local LLM

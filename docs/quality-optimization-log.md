# 质量优化流水账（第一～第四批）

> 记录 2026-11 起对生成质量与工程稳定性的连续四批优化，便于回溯「为什么这么改」与验收口径。
> 每批都按「审计 → 补丁 → 本地 core 测试 → GitHub Actions 编译出 APK → 真机抽检」推进。

## 第一批：质量修复 A–G
- 去时代锁：EraDetector 无命中回退中性（不再默认汉代），AssetsLogic/AiAssistant/AppGraph/ViewModels 默认值改 neutral。
- 分镜字段贯通：AiShot 扩展 → persistShots 全字段落库 → ChineseAudioInjector 5 参 → 队列注入 visual/scene/carry/duration。
- 自动开场帧（i2i）：RenderRuntime.synthesizeOpeningFrame，失败回落不阻断。
- Agnes 2.5 协议：keyframe 首尾帧优先、负向词折正向；默认视频模型/分辨率纠正（720×1280）。
- 成片音轨兜底：归一化 concat 带音频，失败回退纯视频。
- 审核落库：setAssetQuality + 渲染/首帧仅接受 audit_state=approved。
- 时长接线：numFramesForDuration（8n+1），FidelityGate 用真实提交时长比对。
- 交付：commit f9f51a9 + 测试竞态修复 05a2b08；CI 产出 debug APK。

## 第二批：P0 四项
- 自动关键帧阶段：分镜落库后逐镜合成首帧，尾帧接力下一镜首帧（人工覆盖优先，失败不写 null）。
- 审核 fail-closed：无图/无 Key/取图失败/解码失败一律 pending 或 rejected，不再默认通过；渲染侧只认 approved；FidelityGate/StoryboardGate 增加 requireCatalog 严格模式。
- 成片层：分级编码（libx264→mpeg4）、xfade/acrossfade 转场、SRT sidecar 字幕、BGM amix + loudnorm、缺音轨自动补静音、统一输出路径与数字镜序、主线程合成改 IO。
- 时长接线收尾：设置页「时长帧数」留空=按分镜时长（不再固定 121 帧 5.04s）。
- 工程：测试 helper 由 Thread+join 改 runBlocking+withTimeout（原先断言失败不冒泡=假绿）；retryFrom 死代码修复；视频提交防重复计费（maxRetries=1）。
- 交付：commit 0fcb580 + 20b9ae6；core 306 tests 全绿；CI 出 APK。

## 第三批：分镜与成片体验
- 场/段结构：Shot 增加 scene_no/scene_title/screen_side/eyeline，编剧 prompt 要求场次与 180° 轴线；Room v9 迁移。
- 轴线/视线校验 + 节奏软提示：axis_jump/eyeline_mismatch/scene_single_shot/hook_weak/ending_weak/dialogue_pace。
- 节奏曲线：ShotDesigner.rhythmCurve（逐镜强度、场次、景别），分镜页节奏条可视化。
- 故事板可视化：分镜页按场分组、首帧缩略图、节奏条、编辑对话框扩展场次/站位/视线。
- BGM 选曲端到端：项目级 bgm_path/bgm_volume，选曲拷贝到 filesDir/bgm；成片预览底部字幕叠加。
- 交付：commit 587aa92 + f1d3fab（补测试假实现）；core 310 tests 全绿；CI 出 APK。

## 第四批：编辑与成片体验 + 工程稳定性
- 分镜页场次结构操作：组头菜单「合并到上一场 / 拆分为新场…」、单镜「移到上一场/下一场」；场次重编号连续 1..N，写库两段式（场次号/标题走局部 UPDATE，移镜重排 shot_no 才整行 upsert），写后读回校验。
- 单镜单字段 AI 重写：新增 core ShotFieldRewriter（action/narration/visual/scene_context/carry_over，台词不交给 AI）；编辑框四字段「AI 重写」+ 补充要求弹窗 + 结果回填；不合格输出不落库。
- 已通过校验镜头允许重新生成：二次确认后 force 重生成，不再直接拒绝。
- 成片页：BGM 音量滑杆（350ms 防抖保存）+ MediaPlayer 试听；预览字幕开关与小/中/大字号。
- 渲染页：逐镜「预览」弹窗（VideoView，本地绝对路径/file/content/http）+ 文件大小与时间；失败原因完整弹窗（SelectionContainer 可复制，BLOCKED 显示 blocked_reason）；顶部「已完成/失败/待处理/共 n」+ 进度条。
- 工程：app 单测 forkEvery=1 + maxParallelForks=1 + 失败全栈（隔离 flaky 的跨类协程异常污染）；CI 上传 app/schemas 为 room-schemas artifact（补齐 9.json 基准）。
- 验证：core 317 tests / 0 failures（含 ShotFieldRewriterTest 7 例）；app 层由 CI 编译验证。

## 回滚
各批均为独立 commit；回滚单批用 `git revert <sha>`，或 `git checkout <上一批 sha> -- <path>` 取回单文件。数据库仅第三批升到 v9（带 8→9 迁移），回滚 APK 前请先备份数据。

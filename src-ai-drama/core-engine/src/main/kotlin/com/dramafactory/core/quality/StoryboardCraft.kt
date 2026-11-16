package com.dramafactory.core.quality

/**
 * 第八批：分镜「画面专业化」自检 + 兜底画面模板。
 *
 * 背景：旧管线只要求 action ≤30 字纯动作、visual 30–60 字，
 * 模型实际产出多为剧情摘要（"跪坐床沿，神情痴迷"），没有景别/机位/构图/光线，
 * 用户反馈「空泛、没画面感、不像专业分镜」。
 *
 * 本对象做两件事：
 * 1) audit：把「动作混入镜头语言、情绪未外化、画面缺景别/光线/构图、承接写剧情意图」
 *    变成软提示码。App 落 warn: 只提示不阻断，同时可触发一次定向润色。
 * 2) fallbackVisual：导演调用失败/漏镜时给出确定性的可拍摄画面模板，
 *    保证 visual_prompt 永不为空（分镜页展示与视频提示词都依赖它）。
 */
object StoryboardCraft {

    data class Issue(val shotNo: Int, val code: String, val message: String)

    private val CAMERA_WORDS = listOf(
        "镜头", "景别", "特写", "近景", "中景", "全景", "远景", "大特写", "中近景",
        "推近", "推镜", "推入", "拉开", "拉远", "横移", "跟拍", "跟移", "摇镜",
        "俯拍", "仰拍", "平视", "机位", "构图", "景深", "前景", "背景", "三分线", "过肩",
    )

    private val EMOTION_WORDS = listOf(
        "神情复杂", "表情复杂", "内心", "心情", "情绪", "五味杂陈", "若有所思",
        "似乎", "仿佛", "气氛", "氛围", "感伤", "感慨",
    )

    private val VISIBLE_WORDS = listOf(
        "眼", "目光", "视线", "嘴角", "眉", "手指", "指尖", "指节", "掌心", "手腕",
        "肩", "呼吸", "喉结", "后退", "握紧", "攥", "颤抖", "低头", "抬头", "眨眼",
        "皱眉", "别过", "侧过", "垂眸", "抬眼", "沉默", "停顿", "转身", "点头", "摇头",
    )

    private val LENS_WORDS = listOf("特写", "近景", "中景", "全景", "远景", "长焦", "广角", "微距", "中近景")
    private val LIGHT_WORDS = listOf(
        "光", "灯", "影", "色温", "逆光", "侧光", "顶光", "底光", "冷", "暖", "明暗", "反差", "亮",
    )
    private val COMPOSITION_WORDS = listOf(
        "构图", "居左", "居右", "居中", "画面", "前景", "背景", "景深", "三分", "对称",
        "框架", "纵深", "过肩", "占画幅",
    )
    private val CARRY_INTENT_WORDS = listOf("准备", "打算", "决定", "随后", "之后", "即将", "想要", "欲")

    /** 全量自检；只产出软提示，不抛异常、不阻断渲染。 */
    fun audit(shots: List<AiStoryboardDirector.Shot>): List<Issue> {
        val out = mutableListOf<Issue>()
        for (shot in shots) {
            val action = shot.action.trim()
            if (action.isNotEmpty()) {
                if (CAMERA_WORDS.any { action.contains(it) }) {
                    out += Issue(shot.shotNo, "action_camera",
                        "动作里混入镜头语言：镜头语言应写进画面描述，动作只写谁做什么")
                }
                if (EMOTION_WORDS.any { action.contains(it) } && VISIBLE_WORDS.none { action.contains(it) }) {
                    out += Issue(shot.shotNo, "action_vague",
                        "动作空泛：情绪要外化成可见行为（指节、呼吸、视线），不写内心/氛围词")
                }
                if (action.length < 8) {
                    out += Issue(shot.shotNo, "action_thin",
                        "动作太短：补一个道具、光影或环境细节，让摄影师有东西可拍")
                }
            }
            val visual = shot.visualPrompt?.trim().orEmpty()
            if (visual.isEmpty()) {
                out += Issue(shot.shotNo, "visual_missing", "缺少画面描述：至少写景别+机位+构图+光线")
            } else {
                if (visual.length < 40) {
                    out += Issue(shot.shotNo, "visual_thin",
                        "画面描述过短：按「景别机位→构图调度→光线→运镜→材质细节」写满 70–130 字")
                }
                if (LENS_WORDS.none { visual.contains(it) }) {
                    out += Issue(shot.shotNo, "visual_no_size", "画面缺景别：写清特写/近景/中景/全景")
                }
                if (LIGHT_WORDS.none { visual.contains(it) }) {
                    out += Issue(shot.shotNo, "visual_no_light", "画面缺光线：补主光源方向、色温与明暗对比")
                }
                if (COMPOSITION_WORDS.none { visual.contains(it) }) {
                    out += Issue(shot.shotNo, "visual_no_composition", "画面缺构图：补主体位置、前景背景层次与景深")
                }
            }
            val scene = shot.sceneContext?.trim().orEmpty()
            if (scene.isNotEmpty() && scene.length < 12) {
                out += Issue(shot.shotNo, "scene_thin", "场景信息太薄：补时间、空间布局与主光源方向")
            }
            val carry = shot.carryOver?.trim().orEmpty()
            if (carry.isNotEmpty() && CARRY_INTENT_WORDS.any { carry.contains(it) }) {
                out += Issue(shot.shotNo, "carry_intent",
                    "承接写成了剧情意图：应写本镜最后一帧的人物位置/姿势/手中物/环境状态")
            }
        }
        return out
    }

    /**
     * 导演漏镜/调用失败时的兜底画面：只使用该镜已有事实（动作、场景），
     * 不虚构剧本没有的天气与光源。
     */
    fun fallbackVisual(shot: AiStoryboardDirector.Shot): String {
        val action = shot.action.trim()
        val size = when {
            action.contains("大特写") || action.contains("极特写") -> "大特写"
            action.contains("特写") -> "特写"
            action.contains("近景") -> "近景"
            action.contains("全景") || action.contains("远景") -> "全景"
            else -> "中景"
        }
        val scene = shot.sceneContext?.trim().orEmpty()
        val sceneClause = if (scene.isNotEmpty()) "背景保留$scene 的空间层次，" else "背景保留空间纵深，"
        val lightClause = if (scene.isNotEmpty()) "主光遵循场景既有光源，明暗对比适中，" else "环境光均匀铺开，明暗对比适中，"
        return size + "，平视，主体居中占画幅二分之一，" + sceneClause + lightClause +
            "中景深；镜头静止，轻微手持呼吸感；保留环境材质的自然反光。"
    }
}

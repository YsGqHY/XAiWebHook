package kim.hhhhhy.x.webhook.action.codex

/**
 * 降智判定与建议生成。
 *
 * 判定口径（全部可通过 YAML 覆盖）：
 * 1. 每个模型取 baselineEffort（默认 high）作为「日常档位」基准；
 * 2. 基准档位 IQ 达到 targetIq（默认 90）且该模型无降智预警时，视为智商正常；
 * 3. 否则按 strategy 选出建议档位：highest 取该模型最高档位，cheapest 取达标且综合成本最低的档位；
 * 4. 若该模型全部档位都无法达到 targetIq，则判定为全档位降智。
 */
internal class CodexRadarAdvisor(private val options: CodexRadarAdviceOptions) {

    fun build(snapshot: RadarSnapshot, generatedAtText: String): RadarReport {
        val tiersByModel = snapshot.tiers.groupBy { it.model }
        val orderedModels = options.modelOrder.filter { tiersByModel.containsKey(it) } +
            tiersByModel.keys.filter { it !in options.modelOrder }.sorted()

        val advices = orderedModels.map { model ->
            advise(
                model = model,
                tiers = tiersByModel.getValue(model).sortedBy { effortRank(it.effort) },
                alerts = snapshot.alerts.filter { it.model == model }
            )
        }

        val grades = snapshot.tiers.associate { it.key to grade(it.iq) }
        return RadarReport(
            advices = advices,
            tiersByModel = orderedModels.associateWith { model ->
                tiersByModel.getValue(model).sortedByDescending { it.iq }
            },
            alerts = snapshot.alerts,
            recommendations = snapshot.recommendations,
            sourceUpdatedAt = snapshot.sourceUpdatedAt,
            generatedAtText = generatedAtText,
            grades = grades
        )
    }

    fun grade(iq: Double): RadarGrade = when {
        iq >= options.excellentIq -> RadarGrade.EXCELLENT
        iq >= options.normalIq -> RadarGrade.NORMAL
        iq >= options.watchIq -> RadarGrade.WATCH
        else -> RadarGrade.LOW
    }

    fun effortLabel(effort: String): String =
        options.effortLabels[effort.lowercase()] ?: effort

    /** 档位的 OpenAI 官方名；未配置映射时回退到接口原值。 */
    fun officialEffortLabel(effort: String): String =
        options.officialEffortLabels[effort.lowercase()] ?: effort.lowercase()

    fun modelLabel(model: String): String =
        options.modelLabels[model.lowercase()] ?: model

    private fun effortRank(effort: String): Int {
        val index = options.effortOrder.indexOf(effort.lowercase())
        return if (index >= 0) index else options.effortOrder.size
    }

    private fun advise(
        model: String,
        tiers: List<RadarTier>,
        alerts: List<RadarDegradationAlert>
    ): RadarModelAdvice {
        val label = modelLabel(model)
        if (tiers.isEmpty()) {
            return RadarModelAdvice(
                model = model,
                modelLabel = label,
                verdict = RadarVerdict.UNKNOWN,
                baseline = null,
                target = null,
                alerts = alerts,
                advice = options.unknownTemplate.replace("{model}", label)
            )
        }

        // 基准档位缺失时退化为最高 IQ 档位，保证仍能给出结论
        val baseline = tiers.firstOrNull { it.effort == options.baselineEffort }
            ?: tiers.maxByOrNull { it.iq }
        val best = tiers.maxByOrNull { it.iq }
        val baselineHealthy = (baseline?.iq ?: 0.0) >= options.targetIq
        val baselineAlerted = alerts.any { alert ->
            baseline != null && alert.effort == baseline.effort
        }

        if (baselineHealthy && !baselineAlerted) {
            return RadarModelAdvice(
                model = model,
                modelLabel = label,
                verdict = RadarVerdict.NORMAL,
                baseline = baseline,
                target = baseline,
                alerts = alerts,
                advice = options.normalTemplate.replace("{model}", label)
            )
        }

        val qualified = tiers.filter { it.iq >= options.targetIq && it.effort != baseline?.effort }
        if (qualified.isEmpty()) {
            // 全档位不达标：给出该模型现存最优档位，并标注全面降智
            val fallback = best
            return RadarModelAdvice(
                model = model,
                modelLabel = label,
                verdict = RadarVerdict.DEGRADED,
                baseline = baseline,
                target = fallback,
                alerts = alerts,
                advice = options.degradedTemplate
                    .replace("{model}", label)
                    .replace("{effort_official}", officialEffortLabel(fallback?.effort.orEmpty()))
                    .replace("{effort}", effortLabel(fallback?.effort.orEmpty()))
            )
        }

        val target = when (options.strategy) {
            CodexRadarStrategy.CHEAPEST -> qualified.minByOrNull { cost(it) }
            CodexRadarStrategy.HIGHEST -> qualified.maxByOrNull { effortRank(it.effort) }
        } ?: qualified.first()

        return RadarModelAdvice(
            model = model,
            modelLabel = label,
            verdict = RadarVerdict.SWITCH,
            baseline = baseline,
            target = target,
            alerts = alerts,
            advice = options.switchTemplate
                .replace("{model}", label)
                .replace("{effort_official}", officialEffortLabel(target.effort))
                .replace("{effort}", effortLabel(target.effort))
        )
    }

    /** 综合成本排序键：优先用站点指数，缺失时用价格与耗时兜底。 */
    private fun cost(tier: RadarTier): Double {
        tier.combinedCostIndex?.let { return it }
        val price = tier.priceUsd ?: 0.0
        val minutes = tier.minutes ?: 0.0
        return price * (1.0 + minutes / 60.0)
    }
}

internal enum class CodexRadarStrategy {
    /** 取达标且综合成本最低的档位。 */
    CHEAPEST,

    /** 取该模型可用的最高思考档位。 */
    HIGHEST;

    companion object {
        fun parse(value: String?): CodexRadarStrategy = when (value?.trim()?.lowercase()) {
            "cheapest", "value" -> CHEAPEST
            else -> HIGHEST
        }
    }
}

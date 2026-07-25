package com.badwatch.core.eval

import com.badwatch.core.classifier.ShotClassifier
import com.badwatch.core.model.CaptureSession
import com.badwatch.core.model.LabeledSwing
import com.badwatch.core.model.ShotType
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Measures a classifier against labelled swings.
 *
 * Until now the rule-based classifier's accuracy was simply unknown, and documented as such.
 * That was honest but not useful: "uncalibrated" gives Phase 2 nothing to beat and gives a
 * player no idea whether to believe the stroke labels at all. Now that drills produce
 * ground truth, the same corpus that will train the model can score the heuristics first.
 *
 * A classical trap this avoids: reporting only accuracy. With an unbalanced corpus — and a
 * drill-collected corpus is always unbalanced, because smashes are more fun to hit than
 * backhand drives — accuracy is dominated by the largest class. Per-class recall and the
 * full confusion matrix are what actually say whether a stroke is recognised.
 */
class ClassifierEvaluator(
    private val classifier: ShotClassifier = ShotClassifier()
) {

    fun evaluate(captures: List<CaptureSession>): ClassifierEvaluation {
        val swings = captures.flatMap { it.swings }.filterNot { it.discarded }
        return evaluateSwings(swings)
    }

    fun evaluateSwings(swings: List<LabeledSwing>): ClassifierEvaluation {
        if (swings.isEmpty()) return ClassifierEvaluation.EMPTY

        // Rows are the label the player said they were hitting; columns are what the
        // classifier produced. NOT_DETECTED is a genuine outcome, not an error to hide:
        // a stroke the detector never sees is invisible in a session, which matters as much
        // as one it mislabels.
        val matrix = mutableMapOf<Pair<ShotType, String>, Int>()

        swings.forEach { swing ->
            val predicted = classifier.classify(swing.samples)?.type?.name ?: NOT_DETECTED
            val key = swing.label to predicted
            matrix[key] = (matrix[key] ?: 0) + 1
        }

        val labels = swings.map { it.label }.distinct().sortedBy { it.name }
        val perClass = labels.map { label ->
            val actual = swings.count { it.label == label }
            val correct = matrix[label to label.name] ?: 0
            // Predicted-as-this-label across every true class, for precision.
            val predictedAsThis = matrix.entries
                .filter { it.key.second == label.name }
                .sumOf { it.value }

            ClassScore(
                label = label.name,
                support = actual,
                truePositives = correct,
                predictedTotal = predictedAsThis,
                recall = if (actual == 0) 0f else correct.toFloat() / actual,
                precision = if (predictedAsThis == 0) 0f else correct.toFloat() / predictedAsThis,
                notDetected = matrix[label to NOT_DETECTED] ?: 0
            )
        }

        val total = swings.size
        val correct = perClass.sumOf { it.truePositives }
        val undetected = perClass.sumOf { it.notDetected }

        return ClassifierEvaluation(
            swingCount = total,
            correct = correct,
            notDetected = undetected,
            accuracy = correct.toFloat() / total,
            // Macro averages weight every stroke equally, so a rare-but-broken class cannot
            // hide behind a common one.
            macroRecall = perClass.map { it.recall }.averageOrZero(),
            macroPrecision = perClass.map { it.precision }.averageOrZero(),
            perClass = perClass,
            confusion = matrix.map { (key, count) ->
                ConfusionCell(actual = key.first.name, predicted = key.second, count = count)
            }.sortedWith(compareBy({ it.actual }, { it.predicted }))
        )
    }

    private fun List<Float>.averageOrZero(): Float = if (isEmpty()) 0f else sum() / size

    companion object {
        /** The classifier returned nothing for this window. */
        const val NOT_DETECTED = "NotDetected"
    }
}

@Serializable
data class ClassifierEvaluation(
    val swingCount: Int,
    val correct: Int,
    val notDetected: Int,
    val accuracy: Float,
    val macroRecall: Float,
    val macroPrecision: Float,
    val perClass: List<ClassScore>,
    val confusion: List<ConfusionCell>
) {
    /** Fraction of swings the detector saw at all, regardless of whether it labelled them right. */
    val detectionRate: Float
        get() = if (swingCount == 0) 0f else (swingCount - notDetected).toFloat() / swingCount

    /**
     * Whether this evaluation is worth quoting.
     *
     * A handful of swings from one drill says nothing. The threshold is deliberately low
     * enough to be reachable early and high enough that the number is not noise.
     */
    val isMeaningful: Boolean
        get() = swingCount >= MINIMUM_SWINGS && perClass.size >= 2

    fun summaryLine(): String = if (!isMeaningful) {
        "Not enough labelled data yet ($swingCount swings across ${perClass.size} strokes)."
    } else {
        "${percent(accuracy)}% accuracy, ${percent(macroRecall)}% macro recall " +
            "over $swingCount swings"
    }

    private fun percent(value: Float) = (value * 100).roundToInt()

    companion object {
        const val MINIMUM_SWINGS = 50

        val EMPTY = ClassifierEvaluation(
            swingCount = 0,
            correct = 0,
            notDetected = 0,
            accuracy = 0f,
            macroRecall = 0f,
            macroPrecision = 0f,
            perClass = emptyList(),
            confusion = emptyList()
        )
    }
}

@Serializable
data class ClassScore(
    val label: String,
    val support: Int,
    val truePositives: Int,
    val predictedTotal: Int,
    val recall: Float,
    val precision: Float,
    val notDetected: Int
)

@Serializable
data class ConfusionCell(
    val actual: String,
    val predicted: String,
    val count: Int
)

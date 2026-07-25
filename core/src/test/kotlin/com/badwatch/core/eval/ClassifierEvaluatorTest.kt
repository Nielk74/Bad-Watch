package com.badwatch.core.eval

import com.badwatch.core.classifier.ShotClassifier
import com.badwatch.core.model.LabeledSwing
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.Vector3
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClassifierEvaluatorTest {

    private val evaluator = ClassifierEvaluator(ShotClassifier())

    @Test
    fun scoresSwingsTheClassifierGetsRight() {
        val swings = List(10) { swing(ShotType.Smash, SMASH) }

        val evaluation = evaluator.evaluateSwings(swings)

        assertThat(evaluation.swingCount).isEqualTo(10)
        assertThat(evaluation.correct).isEqualTo(10)
        assertThat(evaluation.accuracy).isEqualTo(1f)
        assertThat(evaluation.perClass.single().recall).isEqualTo(1f)
    }

    @Test
    fun countsUndetectedSwingsRatherThanDroppingThem() {
        // Motion far too gentle for any threshold: the classifier returns nothing.
        val swings = List(6) { swing(ShotType.Drop, IDLE) }

        val evaluation = evaluator.evaluateSwings(swings)

        assertThat(evaluation.notDetected).isEqualTo(6)
        assertThat(evaluation.detectionRate).isEqualTo(0f)
        assertThat(evaluation.accuracy).isEqualTo(0f)
        // The outcome is recorded explicitly, so a detector that simply never fires cannot
        // look good by having nothing counted against it.
        assertThat(evaluation.confusion.map { it.predicted })
            .contains(ClassifierEvaluator.NOT_DETECTED)
    }

    @Test
    fun buildsAConfusionMatrixAcrossClasses() {
        val swings = List(5) { swing(ShotType.Smash, SMASH) } +
            // Labelled as a drop by the player, but physically a smash — the classifier
            // will disagree, and that disagreement is the point of the matrix.
            List(5) { swing(ShotType.Drop, SMASH) }

        val evaluation = evaluator.evaluateSwings(swings)

        val misread = evaluation.confusion
            .single { it.actual == "Drop" && it.predicted == "Smash" }
        assertThat(misread.count).isEqualTo(5)
        assertThat(evaluation.perClass.single { it.label == "Drop" }.recall).isEqualTo(0f)
    }

    @Test
    fun macroAveragesWeightEveryStrokeEqually() {
        // 20 smashes classified correctly, 5 drops never detected. Plain accuracy would
        // read 80% and hide the fact that one stroke is completely invisible.
        val swings = List(20) { swing(ShotType.Smash, SMASH) } +
            List(5) { swing(ShotType.Drop, IDLE) }

        val evaluation = evaluator.evaluateSwings(swings)

        assertThat(evaluation.accuracy).isWithin(0.01f).of(0.8f)
        assertThat(evaluation.macroRecall).isWithin(0.01f).of(0.5f)
    }

    @Test
    fun ignoresSwingsThePlayerDiscarded() {
        val swings = List(5) { swing(ShotType.Smash, SMASH) } +
            List(5) { swing(ShotType.Smash, SMASH, discarded = true) }

        assertThat(evaluator.evaluateSwings(swings.filterNot { it.discarded }).swingCount)
            .isEqualTo(5)
    }

    @Test
    fun refusesToQuoteANumberFromTooLittleData() {
        val evaluation = evaluator.evaluateSwings(List(10) { swing(ShotType.Smash, SMASH) })

        assertThat(evaluation.isMeaningful).isFalse()
        assertThat(evaluation.summaryLine()).contains("Not enough labelled data")
    }

    @Test
    fun quotesANumberOnceThereIsEnoughData() {
        val swings = List(40) { swing(ShotType.Smash, SMASH) } +
            List(40) { swing(ShotType.Clear, CLEAR) }

        val evaluation = evaluator.evaluateSwings(swings)

        assertThat(evaluation.isMeaningful).isTrue()
        assertThat(evaluation.summaryLine()).contains("accuracy")
    }

    @Test
    fun emptyCorpusYieldsEmptyEvaluation() {
        assertThat(evaluator.evaluateSwings(emptyList())).isEqualTo(ClassifierEvaluation.EMPTY)
    }

    private fun swing(
        label: ShotType,
        vectors: List<Vector3>,
        discarded: Boolean = false
    ): LabeledSwing {
        val samples = vectors.mapIndexed { index, gyro ->
            SensorSample(
                timestampMillis = index * 40L,
                gyro = gyro,
                heartRateBpm = 150f
            )
        }
        return LabeledSwing(
            id = "swing-${label.name}-${vectors.hashCode()}-$discarded",
            label = label,
            peakTimestampMillis = 0L,
            peakAngularVelocity = vectors.maxOf { it.magnitude() },
            samples = samples,
            discarded = discarded
        )
    }

    private companion object {
        val SMASH = listOf(
            Vector3(0.2f, 0.4f, -1.2f),
            Vector3(0.4f, 0.6f, -2.8f),
            Vector3(0.5f, 0.7f, -4.5f),
            Vector3(0.6f, 0.9f, -5.4f),
            Vector3(0.8f, 1.1f, -6.8f),
            Vector3(0.5f, 0.6f, -4.0f),
            Vector3(0.3f, 0.5f, -1.5f)
        )

        val CLEAR = listOf(
            Vector3(0.1f, 0.2f, 1.0f),
            Vector3(0.3f, 0.3f, 2.6f),
            Vector3(0.5f, 0.4f, 3.8f),
            Vector3(0.6f, 0.5f, 4.8f),
            Vector3(0.4f, 0.3f, 3.2f),
            Vector3(0.2f, 0.1f, 1.5f)
        )

        val IDLE = List(8) { Vector3(0.05f, 0.03f, 0.02f) }
    }
}

package com.badwatch.server

import com.badwatch.core.eval.ClassifierEvaluator
import java.io.File
import kotlin.math.roundToInt

/**
 * Prints how the shipped rule-based classifier scores against collected labelled swings.
 *
 * `./gradlew :server:evaluateClassifier -PcaptureDir=badwatch-data/captures`
 *
 * This is the number Phase 2 has to beat. Publishing it — even when it is bad, especially
 * when it is bad — is the difference between "uncalibrated" as an admission and as a
 * measurement.
 */
fun main(args: Array<String>) {
    val directory = File(args.firstOrNull() ?: "badwatch-data/captures")
    if (!directory.isDirectory) {
        println("No capture directory at ${directory.absolutePath}.")
        println("Record drills on the watch first, then sync or pull them.")
        return
    }

    val repository = CaptureRepository(directory)
    val captures = repository.all()
    if (captures.isEmpty()) {
        println("No captures in ${directory.absolutePath}.")
        return
    }

    val evaluation = repository.evaluateClassifier()
    val devices = captures.map { it.deviceId }.distinct()

    println()
    println("Rule-based classifier, scored on ${evaluation.swingCount} labelled swings")
    println("from ${devices.size} device(s) across ${captures.size} drills.")
    println()

    if (!evaluation.isMeaningful) {
        println("  ${evaluation.summaryLine()}")
        println()
        println("  Collect at least ${com.badwatch.core.eval.ClassifierEvaluation.MINIMUM_SWINGS}")
        println("  swings across two or more strokes before reading anything into this.")
        return
    }

    println("  Accuracy        ${percent(evaluation.accuracy)}%")
    println("  Macro recall    ${percent(evaluation.macroRecall)}%   (every stroke weighted equally)")
    println("  Macro precision ${percent(evaluation.macroPrecision)}%")
    println("  Detection rate  ${percent(evaluation.detectionRate)}%   (swings the detector saw at all)")
    println()

    println("  Per stroke")
    println("  ${"stroke".padEnd(16)}${"n".padStart(5)}${"recall".padStart(9)}${"precision".padStart(11)}${"missed".padStart(8)}")
    evaluation.perClass.sortedByDescending { it.support }.forEach { score ->
        println(
            "  ${score.label.padEnd(16)}" +
                "${score.support.toString().padStart(5)}" +
                "${(percent(score.recall).toString() + "%").padStart(9)}" +
                "${(percent(score.precision).toString() + "%").padStart(11)}" +
                score.notDetected.toString().padStart(8)
        )
    }
    println()

    val labels = (evaluation.perClass.map { it.label } + ClassifierEvaluator.NOT_DETECTED)
    println("  Confusion (rows = what you hit, columns = what it said)")
    print("  ${"".padEnd(16)}")
    labels.forEach { print(it.take(9).padStart(11)) }
    println()
    evaluation.perClass.forEach { score ->
        print("  ${score.label.padEnd(16)}")
        labels.forEach { predicted ->
            val count = evaluation.confusion
                .firstOrNull { it.actual == score.label && it.predicted == predicted }
                ?.count ?: 0
            print(count.toString().padStart(11))
        }
        println()
    }

    if (devices.size < 2) {
        println()
        println("  All swings come from one device. This measures how the classifier does")
        println("  for one player, not how it generalises.")
    }
    println()
}

private fun percent(value: Float) = (value * 100).roundToInt()

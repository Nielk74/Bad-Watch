package com.badwatch.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Simple 3D vector helper for angular velocity components.
 */
@Serializable
data class Vector3(
    val x: Float,
    val y: Float,
    val z: Float
) {
    fun magnitude(): Float = sqrt(x * x + y * y + z * z)

    fun verticality(): Float {
        val total = abs(x) + abs(y) + abs(z)
        return if (total == 0f) 0f else abs(z) / total
    }

    fun horizontality(): Float {
        val total = abs(x) + abs(y) + abs(z)
        return if (total == 0f) 0f else (abs(x) + abs(y)) / total
    }
}

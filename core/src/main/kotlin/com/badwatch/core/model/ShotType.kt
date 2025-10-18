package com.badwatch.core.model

import kotlinx.serialization.Serializable

/**
 * Supported badminton shot classifications.
 */
@Serializable
enum class ShotType {
    Smash,
    Clear,
    Drop,
    Drive,
    BackhandDrive,
    Unknown
}

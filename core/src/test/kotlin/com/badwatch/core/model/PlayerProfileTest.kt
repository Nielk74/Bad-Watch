package com.badwatch.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerProfileTest {

    @Test
    fun compatibilityNumbersDoNotAuthorizePersonalizedPhysiology() {
        val profile = PlayerProfile()

        assertThat(profile.hasConfiguredRestingHeartRate).isFalse()
        assertThat(profile.hasConfiguredMaxHeartRate).isFalse()
        assertThat(profile.hasConfiguredHeartRateReserve).isFalse()
    }

    @Test
    fun reserveRequiresExplicitProvenanceForBothEndpoints() {
        val maxOnly = PlayerProfile(
            maxHeartRateSource = HeartRateValueSource.AgeEstimated
        )
        val both = maxOnly.copy(
            restingHeartRateSource = HeartRateValueSource.UserEntered
        )

        assertThat(maxOnly.hasConfiguredHeartRateReserve).isFalse()
        assertThat(both.hasConfiguredHeartRateReserve).isTrue()
    }

    @Test
    fun tanakaAdultEstimateIsDeterministic() {
        assertThat(PlayerProfile.maxHeartRateForAge(30)).isEqualTo(187f)
        assertThat(PlayerProfile.maxHeartRateForAge(60)).isEqualTo(166f)
    }

    @Test
    fun restingHeartRateCannotClaimAgeEstimatedProvenance() {
        val failure = runCatching {
            PlayerProfile(restingHeartRateSource = HeartRateValueSource.AgeEstimated)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }
}

package com.badwatch.app.localization

import com.badwatch.app.R
import com.badwatch.core.model.ShotType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelStringResourcesTest {

    @Test
    fun everyAutomaticStrokeFamilyHasADedicatedProvisionalLabel() {
        val automaticLabels = ShotType.entries.map { it.provisionalDisplayNameResource }

        assertThat(automaticLabels).containsExactly(
            R.string.shot_smash_provisional,
            R.string.shot_clear_provisional,
            R.string.shot_drop_provisional,
            R.string.shot_drive_provisional,
            R.string.shot_backhand_drive_provisional,
            R.string.shot_unclassified_provisional
        ).inOrder()

        ShotType.entries.forEach { type ->
            assertThat(type.provisionalDisplayNameResource)
                .isNotEqualTo(type.displayNameResource)
        }
    }
}

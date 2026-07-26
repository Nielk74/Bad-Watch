package com.badwatch.app.localization

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncRejectionLocalizationTest {

    @Test
    fun `server details become stable localizable categories`() {
        assertThat(classifySyncRejection("Unsupported schema version 2"))
            .isEqualTo(SyncRejectionKind.IncompatibleSchema)
        assertThat(classifySyncRejection("Session 's' has divergent diary revision 2"))
            .isEqualTo(SyncRejectionKind.EditConflict)
        assertThat(classifySyncRejection("Session 's' conflicts with immutable recorded evidence"))
            .isEqualTo(SyncRejectionKind.IdentityConflict)
        assertThat(classifySyncRejection("Session 's' ends before it starts"))
            .isEqualTo(SyncRejectionKind.InvalidRecord)
        assertThat(classifySyncRejection("Server rejected this record"))
            .isEqualTo(SyncRejectionKind.Generic)
    }
}

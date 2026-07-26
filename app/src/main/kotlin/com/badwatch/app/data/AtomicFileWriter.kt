package com.badwatch.app.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Writes and fsyncs a sibling temp file before atomically replacing [file]. */
internal fun writeDurableAtomically(file: File, text: String) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    FileOutputStream(temporary).use { output ->
        output.write(text.toByteArray(Charsets.UTF_8))
        output.fd.sync()
    }
    try {
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: AtomicMoveNotSupportedException) {
        // Some OEM filesystems do not advertise atomic moves. The source was still fsynced,
        // and a same-directory replace is the strongest fallback available there.
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }
}

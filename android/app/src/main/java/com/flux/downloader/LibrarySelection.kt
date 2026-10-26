package com.flux.downloader

enum class LibraryStatus(val label: String, val state: String?) {
    All("All states", null), Completed("Completed", "Complete"), Failed("Failed", "Failed"), Cancelled("Cancelled", "Cancelled")
}

data class SelectionSummary(val completed: Int, val knownBytes: Long?, val unknownSizes: Int) {
    val label: String get() = when {
        completed == 0 -> "No completed files selected"
        knownBytes == null -> "Recorded completed size unavailable"
        unknownSizes == completed -> "Recorded completed size unknown · $unknownSizes ${if (unknownSizes == 1) "file" else "files"}"
        else -> "Recorded completed size: ${sizeLabel(knownBytes)}" + if (unknownSizes > 0) " · $unknownSizes ${if (unknownSizes == 1) "size" else "sizes"} unknown" else ""
    }
}

fun selectionSummary(jobs: List<Download>): SelectionSummary {
    val completed = jobs.filter { it.state == "Complete" }
    var bytes: Long? = 0
    completed.filter { it.bytes > 0 }.forEach { job ->
        bytes = bytes?.let { current -> runCatching { Math.addExact(current, job.bytes) }.getOrNull() }
    }
    return SelectionSummary(completed.size, bytes, completed.count { it.bytes <= 0 })
}

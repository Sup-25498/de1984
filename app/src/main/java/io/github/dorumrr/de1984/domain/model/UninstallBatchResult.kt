package io.github.dorumrr.de1984.domain.model

data class UninstallBatchResult(
    val succeeded: List<String>,
    val failed: List<Pair<String, String>>
)


package com.example.audiomemo.features.home.domain.model

/**
 * Classification of one hour-bucket in the Home health stripe
 * (`am-hotfix-home-status-redesign`). Deliberately capped at 4 values — 3 "real" states plus
 * [NO_DATA] for a bucket with no known state at all — per the story's boundary ("classifica cada
 * hora em no máximo 3 estados... hora sem nenhum evento logado — tratar como sem dados/estado
 * neutro, não como erro nem sucesso").
 */
enum class HealthState { RECORDING, PAUSED, ERROR, NO_DATA }

/** One hour-wide bucket of the last-24h health stripe. [hourStart] inclusive, [hourEndExclusive] exclusive. */
data class HealthSegment(
    val hourStart: Long,
    val hourEndExclusive: Long,
    val state: HealthState
)

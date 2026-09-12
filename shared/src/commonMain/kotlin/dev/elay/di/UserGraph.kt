package dev.elay.di

import dev.elay.domain.availability.AvailabilityRepository
import dev.elay.domain.model.UserId
import dev.elay.domain.repository.OutcomeRepository
import dev.elay.domain.repository.PairRepository
import dev.elay.domain.repository.PlannerRepository
import dev.elay.domain.repository.ProposalRepository
import dev.elay.sync.SyncCoordinator

/**
 * Per-account graph (brief §1): the [repository] the UI reads/writes through, plus its
 * [syncCoordinator] (exposed mainly so [UserSessionGraph] can fire the initial replay).
 */
data class UserGraph(
    val userId: UserId,
    val repository: PlannerRepository,
    val syncCoordinator: SyncCoordinator,
    val pairRepository: PairRepository,
    val proposalRepository: ProposalRepository,
    val availabilityRepository: AvailabilityRepository,
    val outcomeRepository: OutcomeRepository,
)

package dev.elay.domain.repository

import dev.elay.domain.model.InviteResult
import dev.elay.domain.model.LeaveResult
import dev.elay.domain.model.PairState
import dev.elay.domain.model.RedeemResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Frozen Stage 1 pairing surface (contracts/stage1-pairing.md; council/stage1-pairing-contract-sol.md
 * §4). Implementations are remote+memory — [dev.elay.data.remote.impl.SupabasePairRepository] is
 * the only implementation; there is no Room cache in Stage 1 (lead amendment 4).
 *
 * [AutoCloseable]: sign-out (or session teardown generally) must tear down the realtime
 * subscription and internal state — never leave a private channel authorized past the session
 * that owns it (ADR-009).
 */
interface PairRepository : AutoCloseable {
    fun observePair(): StateFlow<PairState>

    suspend fun createInvite(
        operationId: String,
        ttlMinutes: Int = 1440,
    ): InviteResult

    suspend fun redeemInvite(
        operationId: String,
        code: String,
    ): RedeemResult

    suspend fun leave(operationId: String): LeaveResult
}

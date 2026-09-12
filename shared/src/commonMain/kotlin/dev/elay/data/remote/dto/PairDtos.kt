package dev.elay.data.remote.dto

import dev.elay.domain.model.PairId
import dev.elay.domain.model.PairMember
import dev.elay.domain.model.PairSnapshot
import dev.elay.domain.model.PairStatus
import dev.elay.domain.model.UserId
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for Stage 1 pairing (contracts/stage1-pairing.md; the incorporated
 * council/stage1-pairing-contract-sol.md §4 "Frozen client surface"). snake_case keys,
 * ISO-8601 UTC instant strings — same house convention as the Phase 1 planner DTOs
 * (contracts/phase1-planner.md §4).
 */

@Serializable
data class PairMemberDto(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("home_tz") val homeTz: String,
    @SerialName("joined_at") val joinedAt: String,
)

fun PairMemberDto.toDomain(): PairMember =
    PairMember(
        userId = UserId(userId),
        displayName = displayName,
        homeTz = homeTz,
        joinedAt = Instant.parse(joinedAt),
    )

/** `rpc_get_pair`'s row shape, and the `pair` field of [PairRpcEnvelopeDto]. */
@Serializable
data class PairSnapshotDto(
    @SerialName("pair_id") val pairId: String,
    val status: String,
    val version: Long,
    @SerialName("channel_topic") val channelTopic: String,
    val members: List<PairMemberDto> = emptyList(),
    @SerialName("active_invite_expires_at") val activeInviteExpiresAt: String? = null,
)

fun PairSnapshotDto.toDomain(): PairSnapshot =
    PairSnapshot(
        id = PairId(pairId),
        status = PairStatus.entries.first { it.wire == status },
        version = version,
        channelTopic = channelTopic,
        members = members.map { it.toDomain() },
        activeInviteExpiresAt = activeInviteExpiresAt?.let(Instant::parse),
    )

@Serializable
data class InviteCodeDto(
    @SerialName("invite_id") val inviteId: String,
    val code: String,
    @SerialName("expires_at") val expiresAt: String,
)

/**
 * Uniform result envelope for the three mutating RPCs (`rpc_create_pair_invite`,
 * `rpc_redeem_pair_invite`, `rpc_leave_pair`) — contract §4. `action` is the cross-RPC replay
 * guard field; irrelevant fields are `null` for a given call (e.g. `invite`/`left_pair_id` are
 * both null on a redeem's `applied` outcome).
 */
@Serializable
data class PairRpcEnvelopeDto(
    val outcome: String,
    val action: String,
    val pair: PairSnapshotDto? = null,
    val invite: InviteCodeDto? = null,
    @SerialName("left_pair_id") val leftPairId: String? = null,
)

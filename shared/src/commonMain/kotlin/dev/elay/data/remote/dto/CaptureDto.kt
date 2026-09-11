package dev.elay.data.remote.dto

import dev.elay.domain.model.Capture
import dev.elay.domain.model.CaptureId
import dev.elay.domain.model.CaptureSource
import dev.elay.domain.model.ParseStatus
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.UserId
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape of a `captures` row (contracts/phase1-planner.md §1, §4).
 * Field order mirrors `contracts/fixtures/capture.json` exactly. Owner-only —
 * no sharing columns.
 */
@Serializable
data class CaptureDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val body: String,
    val source: String,
    @SerialName("ai_parse_status") val aiParseStatus: String,
    @SerialName("captured_at") val capturedAt: String,
    @SerialName("clarified_task_id") val clarifiedTaskId: String? = null,
    val version: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

fun CaptureDto.toDomain(): Capture =
    Capture(
        id = CaptureId(id),
        ownerId = UserId(ownerId),
        body = body,
        source = CaptureSource.entries.first { it.wire == source },
        parseStatus = ParseStatus.entries.first { it.wire == aiParseStatus },
        capturedAt = Instant.parse(capturedAt),
        clarifiedTaskId = clarifiedTaskId?.let(::TaskId),
        version = version,
    )

/**
 * Domain [Capture] has no `created_at`/`updated_at` (frozen model, contracts/phase1-planner.md
 * §5) but the wire row requires both — callers supply them explicitly.
 */
fun Capture.toDto(
    createdAt: Instant,
    updatedAt: Instant,
): CaptureDto =
    CaptureDto(
        id = id.value,
        ownerId = ownerId.value,
        body = body,
        source = source.wire,
        aiParseStatus = parseStatus.wire,
        capturedAt = capturedAt.toString(),
        clarifiedTaskId = clarifiedTaskId?.value,
        version = version,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

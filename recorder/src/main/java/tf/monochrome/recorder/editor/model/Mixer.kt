package tf.monochrome.recorder.editor.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class AudioRole { DIALOGUE, DEVICE, MUSIC, SFX }

@Serializable
data class MixerFx(
    val type: SnapinType,
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val wet: Float = 1f,
    val oversampling: Int = 1,
    val values: Map<Int, Float> = emptyMap(),
)

@Serializable
data class RoleMix(
    val gain: Float = 1f,
    val balance: Float = 0f,
    val muted: Boolean = false,
    val effects: List<MixerFx> = emptyList(),
)

@Serializable
data class Mixer(
    val roles: Map<AudioRole, RoleMix> = emptyMap(),
    val solo: AudioRole? = null,
    val masterGain: Float = 1f,
    val masterBalance: Float = 0f,
    val masterMuted: Boolean = false,
    val masterEffects: List<MixerFx> = emptyList(),
    val fxEnabled: Boolean = true,
) {
    fun isRouted(role: AudioRole): Boolean =
        roles[role]?.muted != true && (solo == null || solo == role)

    fun channel(role: AudioRole?): RoleMix = if (role == null) {
        RoleMix(masterGain, masterBalance, masterMuted, masterEffects)
    } else {
        roles[role] ?: RoleMix()
    }

    fun editChannel(role: AudioRole?, change: (RoleMix) -> RoleMix): Mixer {
        if (role != null) return edit(role, change)
        val channel = change(channel(null))
        return copy(
            masterGain = channel.gain,
            masterBalance = channel.balance,
            masterMuted = channel.muted,
            masterEffects = channel.effects,
        )
    }

    fun edit(role: AudioRole, change: (RoleMix) -> RoleMix): Mixer =
        copy(roles = roles + (role to change(roles[role] ?: RoleMix())))
}

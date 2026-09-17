package ai.ruach.voice

/**
 * Voice-layer failure categories (06_ELEVENLABS_INTEGRATION.md §39 — Voice failure).
 *
 * These are *provider-facing* failures owned by the voice adapter. They are kept
 * distinct from Agent/Tool/Policy/Verification failures so each layer picks its own
 * recovery strategy. A voice failure must never crash or corrupt the agent session,
 * and must never cause a goal to be submitted twice.
 *
 * [userMessage] is user-actionable copy (shown as a toast / status line). It never
 * contains secrets or raw provider payloads.
 */
sealed interface VoiceFailure {
    val userMessage: String

    /** No microphone hardware/service on this device. */
    data object MicUnavailable : VoiceFailure {
        override val userMessage: String = "Voice input is not available on this device"
    }

    /** RECORD_AUDIO not granted. */
    data object MicPermissionDenied : VoiceFailure {
        override val userMessage: String = "Microphone permission is required for voice"
    }

    /** The voice provider is not configured (no API key / store unavailable). */
    data object ProviderNotConfigured : VoiceFailure {
        override val userMessage: String = "Voice is not configured — add an ElevenLabs key in Settings"
    }

    /** Connectivity failure reaching the voice provider. */
    data object Network : VoiceFailure {
        override val userMessage: String = "Voice needs network access"
    }

    /** The voice provider did not answer in time. */
    data object NetworkTimeout : VoiceFailure {
        override val userMessage: String = "The voice service is taking too long — try again"
    }

    /** The voice provider rejected the stored credentials. */
    data object Unauthorized : VoiceFailure {
        override val userMessage: String = "The voice provider rejected the stored key"
    }

    /** The voice provider is unavailable / rate-limited / over quota. */
    data object ProviderUnavailable : VoiceFailure {
        override val userMessage: String = "The voice service is unavailable right now"
    }

    /** The provider returned content that could not be interpreted. */
    data object MalformedProviderResponse : VoiceFailure {
        override val userMessage: String = "The voice service returned an unreadable response"
    }

    /** No speech was captured / transcribed. */
    data object TranscriptEmpty : VoiceFailure {
        override val userMessage: String = "I didn't hear anything — try again"
    }

    /** Speech output could not be produced or played. */
    data object SynthesizeFailed : VoiceFailure {
        override val userMessage: String = "I couldn't speak my response"
    }

    /** The goal gateway rejected the finalized utterance (never re-submitted). */
    data class GoalRejected(val reason: String) : VoiceFailure {
        override val userMessage: String = "Goal rejected: $reason"
    }
}
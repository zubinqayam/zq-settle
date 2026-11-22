package com.zq.commprep.domain

/**
 * Local opening builder (0.2.0-prep2). Deterministic string templates only: no AI service, no network,
 * nothing stored. The same inputs always give the same text, and the user can edit every word afterwards.
 */
enum class ConversationType(val label: String, val situationHint: String, val pointHint: String, val requestHint: String) {
    FEEDBACK("Giving difficult feedback", "What happened (e.g. the last two reports were late)",
        "Why it matters to you", "What you'd like to change"),
    BOUNDARY("Setting a boundary", "The situation (e.g. calls after 9 pm)",
        "What you need", "What you're asking for"),
    ASK_FOR_HELP("Asking for help", "What you're working on or dealing with",
        "Where you're stuck", "The help you'd like"),
    INTERVIEW("Interview or presentation", "The interview or presentation",
        "The main thing you want them to know", "What you'd like to happen next"),
    CUSTOM("Something else", "The situation, in your words",
        "Your main point", "What you'd like to ask for"),
}

data class OpeningInputs(val situation: String = "", val mainPoint: String = "", val request: String = "")

/** The three lines of the takeaway card. Session-only: never written to storage, history or export. */
data class PrepDraft(
    val type: ConversationType,
    val opening: String,
    val request: String,
    val ifDifficult: String,
)

object OpeningBuilder {
    const val MAX_INPUT_CHARS = 300

    /** Trim, collapse whitespace, cap length and drop trailing sentence punctuation. */
    fun clean(s: String): String =
        s.replace(Regex("\\s+"), " ").trim().take(MAX_INPUT_CHARS).trimEnd('.', '!', '?', ',', ';', ':', ' ')

    /** The main point and the request are needed; the situation is optional. */
    fun canBuild(i: OpeningInputs): Boolean = clean(i.mainPoint).isNotEmpty() && clean(i.request).isNotEmpty()

    private fun lowerFirst(s: String) =
        if (s.length > 1 && s[0].isUpperCase() && !s[1].isUpperCase()) s[0].lowercaseChar() + s.substring(1) else s

    fun build(type: ConversationType, i: OpeningInputs): PrepDraft {
        require(canBuild(i)) { "main point and request are required" }
        val sit = clean(i.situation)
        val point = clean(i.mainPoint)
        val req = clean(i.request)
        val about = if (sit.isEmpty()) "" else lowerFirst(sit)
        val opening = when (type) {
            ConversationType.FEEDBACK ->
                (if (about.isEmpty()) "I'd like to talk with you about something. " else "I'd like to talk with you about $about. ") +
                    "$point."
            ConversationType.BOUNDARY ->
                (if (about.isEmpty()) "There's something I need to be clear about. " else "I want to be clear about $about. ") +
                    "$point."
            ConversationType.ASK_FOR_HELP ->
                (if (about.isEmpty()) "I could use your help with something. " else "I'm working on $about and I could use your help. ") +
                    "$point."
            ConversationType.INTERVIEW ->
                (if (about.isEmpty()) "Thank you for your time. " else "Thank you for having me for $about. ") +
                    "The main thing I'd like you to know is: $point."
            ConversationType.CUSTOM ->
                (if (about.isEmpty()) "" else "I'd like to talk about $about. ") + "$point."
        }
        val request = when (type) {
            ConversationType.BOUNDARY -> "What I'm asking for is: $req."
            ConversationType.INTERVIEW -> "Next, I'd like: $req."
            else -> "What I'd like is: $req."
        }
        val ifDifficult = "I hear you. Can we come back to what I'm asking for: $req?"
        return PrepDraft(type, opening, request, ifDifficult)
    }

    /** Plain text for the Copy button: exactly what the card shows, nothing else. */
    fun cardText(d: PrepDraft): String = buildString {
        append("My opening\n").append(d.opening.trim()).append("\n\n")
        append("My request\n").append(d.request.trim()).append("\n\n")
        append("If the conversation becomes difficult…\n").append(d.ifDifficult.trim())
    }
}

/** Camera-off rehearsal gate (0.2.0-prep2): rehearsal may start only once the camera is fully off. */
object RehearsalGate {
    fun canStart(cameraActive: Boolean): Boolean = !cameraActive
    fun cameraText(cameraActive: Boolean): String =
        if (cameraActive) "Turning the camera off… Rehearsal starts once it is off."
        else "Camera off. Nothing is measured or recorded during rehearsal."
}

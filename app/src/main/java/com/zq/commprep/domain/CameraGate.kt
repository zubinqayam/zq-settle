package com.zq.commprep.domain

/**
 * Camera consent rules (0.1.2, updated 0.2.0). Nothing camera-related happens on app launch.
 * Order: consent notice (current version) + 18+ confirmation → user accepts → user taps "Allow camera"
 * → Android permission prompt → SDK may initialise only if permission is granted AND a Presage key is
 * configured in this build. [consent] here is always the *effective* consent (see [ConsentPolicy]).
 */
object CameraGate {
    /**
     * Show the "Allow camera" button only after consent was accepted, while permission is missing,
     * and only when this build can measure at all (keyless builds never ask for the camera).
     */
    fun offerAllowCamera(consent: Consent, permissionGranted: Boolean, keyConfigured: Boolean = true): Boolean =
        consent == Consent.ACCEPTED && !permissionGranted && keyConfigured

    /** The SmartSpectra SDK (camera) may be initialised/started only when all three hold. */
    fun canUseSdk(consent: Consent, permissionGranted: Boolean, keyConfigured: Boolean): Boolean =
        consent == Consent.ACCEPTED && permissionGranted && keyConfigured

    const val NO_KEY_TEXT =
        "Camera check-in isn't available in this build (no Presage key is configured). Every step runs on the timer only."

    /** Status line shown on Home / step screens. Plain words; no SDK jargon. */
    fun statusText(consent: Consent, permissionGranted: Boolean, deniedOnce: Boolean, keyConfigured: Boolean, sensorName: String): String = when {
        !keyConfigured -> NO_KEY_TEXT
        consent != Consent.ACCEPTED -> "Camera check-in off (consent not given). Steps run on the timer only."
        !permissionGranted && deniedOnce -> "Camera not allowed. Steps run on the timer only."
        !permissionGranted -> "Camera not allowed yet. Steps run on the timer only."
        else -> "Camera check-in available: $sensorName"
    }
}

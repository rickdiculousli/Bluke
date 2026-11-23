package dev.arnv.bluke.bluetooth

/** Why the last connection attempt to a host ended without a link. */
enum class ConnectionFailure {
    /** Bonding started and the host (or the user) refused it, or it failed. */
    HOST_REFUSED_PAIRING,

    /** Android would not start bonding with the host. */
    PAIRING_REQUEST_FAILED,

    /** The HID connection was rejected, didn't complete, or Android couldn't start it. */
    CONNECTION_REJECTED,
}

/** The manager's last connection failure, kept until the next attempt starts. */
data class LastConnectionFailure(
    val failure: ConnectionFailure,
    val hostAddress: String,
)

package dk.sdu.cloud

/**
 * Represents a [SecurityPrincipal]'s system-wide role.
 *
 * __This is usually not used for application-specific authorization.__
 *
 * Services are encouraged to implement their own authorization control, potentially
 * from a common library.
 *
 * __DO NOT__ add your own roles here. They go in the services, this is __only__ for a system-wide role.
 */
enum class Role {
    /**
     * The security principal is an unauthenticated guest
     */
    GUEST,

    /**
     * The security principal is a normal end-user.
     *
     * Normal end users can also have "admin-like" privileges in certain parts of the application.
     */
    USER,

    /**
     * The security principal is an administrator of the system.
     *
     * Very few users should have this role.
     */
    ADMIN,

    /**
     * The security principal is a first party, __trusted__, service.
     */
    SERVICE,

    /**
     * The security principal is some third party application.
     *
     * These can be created for OAuth or similar purposes.
     */
    THIRD_PARTY_APP
}

object Roles {
    val AUTHENTICATED = setOf(Role.USER, Role.ADMIN, Role.SERVICE, Role.THIRD_PARTY_APP)
    val END_USER = setOf(Role.USER, Role.ADMIN)
    val PRIVILEDGED = setOf(Role.ADMIN, Role.SERVICE)
    val ADMIN = setOf(Role.ADMIN)
    val THIRD_PARTY_APP = setOf(Role.THIRD_PARTY_APP)
    val PUBLIC = setOf(*Role.values())
}

/**
 * A minimal representation of a security principal.
 *
 * More information can be gathered from an auth service, using the username as a key.
 */
data class SecurityPrincipal(
    /**
     * The unique username of this security principal.
     *
     * This is usually not suitable for display in UIs.
     */
    val username: String,

    /**
     * The role of the security principal
     */
    val role: Role,

    /**
     * The first name of the security principal. Can be empty.
     */
    val firstName: String,

    /**
     * The last name of the security principal. Can be empty.
     */
    val lastName: String
)

/**
 * Represents an access token issued for a security principal.
 */
data class SecurityPrincipalToken(
    val principal: SecurityPrincipal,

    /**
     * Opaque token that identifies the session
     */
    val sessionId: String,

    /**
     * A list of scopes that this principal is currently authorized for.
     */
    val scopes: List<SecurityScope>,

    /**
     * When was this token issued (ms since unix epoch)
     */
    val issuedAt: Long,

    /**
     * When does this token expire (ms since unix epoch)
     */
    val expiresAt: Long
)

enum class AccessRight(val scopeName: String) {
    READ("read"),
    READ_WRITE("write")
}

data class SecurityScope internal constructor(
    val segments: List<String>,
    val access: AccessRight
) {
    init {
        if (segments.isEmpty()) throw IllegalArgumentException("segments cannot be empty")
    }

    fun isCoveredBy(other: SecurityScope): Boolean {
        val accessLevelMatch = other.access == AccessRight.READ_WRITE || access == AccessRight.READ
        if (!accessLevelMatch) return false

        if (other.segments.first() == ALL_SCOPE && segments.first() != SPECIAL_SCOPE) {
            return true
        }

        // We need complete matching otherwise
        if (other.segments.size < segments.size) return false

        for (i in other.segments.indices) {
            val otherSegment = other.segments[i]
            val thisSegment = segments[i]

            if (otherSegment != thisSegment) return false
        }

        return true
    }

    companion object {
        private val segmentRegex = Regex("[a-zA-Z0-9]+")

        const val ALL_SCOPE = "all"
        const val SPECIAL_SCOPE = "special"

        fun parseFromString(value: String): SecurityScope {
            if (value == "api") return SecurityScope(listOf("all"), AccessRight.READ_WRITE)

            val parts = value.split(':')
            if (parts.size != 2) throw IllegalArgumentException("Too many parts")
            val segments = parts.first().split('.')
            val firstInvalidSegment = segments.find { !it.matches(segmentRegex) }
            if (firstInvalidSegment != null) {
                throw IllegalArgumentException("Invalid segment found '$firstInvalidSegment' from '$value'")
            }

            val normalizedAccess = parts.last().toLowerCase()
            val access = AccessRight.values().find { it.scopeName == normalizedAccess }
                    ?: throw IllegalArgumentException("Bad access right in audience")

            return SecurityScope(segments, access)
        }
    }
}
package dk.sdu.cloud.storage.services.ext.irods

import dk.sdu.cloud.storage.api.User

object IRodsUser {
    fun parse(services: IRodsAccountServices, stringRepresentation: String): User {
        val localZone = services.connectionInformation.zone
        val tokens = stringRepresentation.split('#')
        if (tokens.size > 2 || tokens.isEmpty()) throw IllegalArgumentException("Invalid user representation")
        val zone = if (tokens.size == 2) tokens[1] else localZone
        val username = tokens[0]
        return User(stringRepresentation, username, zone)
    }

    fun fromUsernameAndZone(username: String, zone: String) = User("$username#$zone", username, zone)
}
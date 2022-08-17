package dk.sdu.cloud.project.api

import dk.sdu.cloud.*
import dk.sdu.cloud.calls.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

@Serializable
data class UserStatusRequest(
    val username: String? = null
)

@Serializable
data class UserStatusInProject(
    val projectId: String,
    val title: String,
    val whoami: ProjectMember,
    val parent: String? = null
)

@Serializable
data class UserStatusResponse(
    val membership: List<UserStatusInProject>,
    val groups: List<UserGroupSummary>
)

@Serializable
data class SearchRequest(
    val query: String,
    val notInGroup: String? = null,
    override val itemsPerPage: Int? = null,
    override val page: Int? = null,
) : WithPaginationRequest
typealias SearchResponse = Page<ProjectMember>

typealias CountRequest = Unit
typealias CountResponse = Long

@Serializable
data class LookupAdminsRequest(val projectId: String)
@Serializable
data class LookupAdminsResponse(
    val admins: List<ProjectMember>
)

@Serializable
data class LookupAdminsBulkRequest(val projectId: List<String>)

@Serializable
data class Pair<A, B>(val first: A, val second: B)

@Serializable
data class LookupAdminsBulkResponse(
    val admins: List<Pair<String, List<ProjectMember>>>
)

object ProjectMembers : CallDescriptionContainer("project.members") {
    const val baseContext = "/api/projects/membership"

    init {
        description = """
UCloud Projects have one or more members.

This API will likely be combined with one or more related APIs in the project feature.

${ApiConventions.nonConformingApiWarning}
        """.trimIndent()
    }

    /**
     * An endpoint for retrieving the complete project status of a specific user.
     *
     * UCloud users in [Roles.PRIVILEGED] can set [UserStatusRequest.username] otherwise the username of the caller
     * will be used.
     *
     * The returned information will contain a complete status of all groups and project memberships. This endpoint
     * is mostly intended for services to perform permission checks.
     */
    val userStatus = call("userStatus", UserStatusRequest.serializer(), UserStatusResponse.serializer(), CommonErrorMessage.serializer()) {
        auth {
            roles = Roles.AUTHENTICATED
            access = AccessRight.READ
        }

        http {
            method = HttpMethod.Post
            path { using(baseContext) }
            body { bindEntireRequestFromBody() }
        }
    }

    /**
     * Searches in members of a project.
     *
     * If [SearchRequest.notInGroup] is specified then only members which are not in the group specified will be
     * returned. Otherwise all members of the project will be used as the search space.
     *
     * The [SearchRequest.query] will be used to search in the usernames of project members.
     */
    val search = call("search", SearchRequest.serializer(), Page.serializer(ProjectMember.serializer()), CommonErrorMessage.serializer()) {
        auth {
            access = AccessRight.READ
        }

        http {
            method = HttpMethod.Get

            path {
                using(baseContext)
                +"search"
            }

            params {
                +boundTo(SearchRequest::query)
                +boundTo(SearchRequest::itemsPerPage)
                +boundTo(SearchRequest::page)
                +boundTo(SearchRequest::notInGroup)
            }
        }
    }

    /**
     * Returns the number of members in a project.
     *
     * Only project administrators can use this endpoint.
     */
    val count = call("count", CountRequest.serializer(), CountResponse.serializer(), CommonErrorMessage.serializer()) {
        auth {
            access = AccessRight.READ
        }

        http {
            method = HttpMethod.Get

            path {
                using(baseContext)
                +"count"
            }
        }
    }

    /**
     * Returns a complete list of all project administrators in a project.
     *
     * This endpoint can only be used by [Roles.PRIVILEGED]. It is intended for services to consume when they need to
     * communicate with administrators of a project.
     */
    val lookupAdmins = call("lookupAdmins", LookupAdminsRequest.serializer(), LookupAdminsResponse.serializer(), CommonErrorMessage.serializer()) {
        auth {
            access = AccessRight.READ_WRITE
            roles = Roles.PRIVILEGED
        }

        http {
            method = HttpMethod.Get

            path {
                using(baseContext)
                +"lookup-admins"
            }

            params {
                +boundTo(LookupAdminsRequest::projectId)
            }
        }
    }

    val lookupAdminsBulk = call("lookupAdminsBulk", LookupAdminsBulkRequest.serializer(), LookupAdminsBulkResponse.serializer(), CommonErrorMessage.serializer()) {
        auth {
            access = AccessRight.READ_WRITE
            roles = Roles.PRIVILEGED
        }

        http {
            method = HttpMethod.Post

            path {
                using(baseContext)
                +"lookup-admins"
            }

            body { bindEntireRequestFromBody() }
        }
    }
}

package dk.sdu.cloud.project.repository.services

import com.fasterxml.jackson.module.kotlin.readValue
import dk.sdu.cloud.SecurityPrincipal
import dk.sdu.cloud.calls.RPCException
import dk.sdu.cloud.calls.client.AuthenticatedClient
import dk.sdu.cloud.calls.client.call
import dk.sdu.cloud.calls.client.orRethrowAs
import dk.sdu.cloud.calls.client.orThrow
import dk.sdu.cloud.defaultMapper
import dk.sdu.cloud.file.api.*
import dk.sdu.cloud.project.api.*
import dk.sdu.cloud.project.repository.api.ProjectAclEntry
import dk.sdu.cloud.project.repository.api.Repository
import dk.sdu.cloud.service.Loggable
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.HttpStatusCodeContent
import io.ktor.http.isSuccess
import org.slf4j.Logger

class RepositoryService(private val serviceClient: AuthenticatedClient) {
    suspend fun create(principal: SecurityPrincipal, project: String, repository: String) {
        val status = Projects.viewMemberInProject.call(
            ViewMemberInProjectRequest(project, principal.username),
            serviceClient
        ).orRethrowAs { throw RPCException("Unknown project", HttpStatusCode.NotFound) }

        if (!status.member.role.isAdmin()) {
            throw RPCException("Only admins can create new repositories", HttpStatusCode.Forbidden)
        }

        val path = joinPath(
            PROJECT_DIR_PREFIX,
            project,
            repository
        )

        val createDirectoryStatus = FileDescriptions.createDirectory.call(
            CreateDirectoryRequest(
                path,
                principal.username
            ),
            serviceClient
        )

        if (createDirectoryStatus.statusCode != HttpStatusCode.Conflict &&
            !createDirectoryStatus.statusCode.isSuccess()
        ) {
            throw RPCException("Failed to create new repository", HttpStatusCode.InternalServerError)
        }

        MetadataDescriptions.createMetadata.call(
            CreateMetadataRequest(
                listOf(
                    MetadataUpdate(
                        path,
                        METADATA_NAME,
                        null,
                        defaultMapper.writeValueAsString(RepositoryMetadata())
                    )
                )
            ),
            serviceClient
        ).orThrow()
    }

    suspend fun update(
        userClient: AuthenticatedClient,
        project: String,
        oldRepository: String,
        newRepository: String
    ) {
        FileDescriptions.move.call(
            MoveRequest(
                joinPath(PROJECT_DIR_PREFIX, project, oldRepository),
                joinPath(PROJECT_DIR_PREFIX, project, newRepository),
                WriteConflictPolicy.REJECT
            ),
            userClient
        ).orThrow()
    }

    suspend fun delete(
        userClient: AuthenticatedClient,
        project: String,
        repository: String
    ) {
        FileDescriptions.deleteFile.call(
            DeleteFileRequest(joinPath(PROJECT_DIR_PREFIX, project, repository)),
            userClient
        ).orThrow()
    }

    suspend fun listRepositories(
        principal: SecurityPrincipal,
        project: String
    ): List<Repository> {
        Projects.viewMemberInProject.call(
            ViewMemberInProjectRequest(project, principal.username),
            serviceClient
        ).orRethrowAs { throw RPCException("Unknown project", HttpStatusCode.NotFound) }

        // All members can view all projects
        return MetadataDescriptions.findByPrefix
            .call(
                FindByPrefixRequest(
                    joinPath(PROJECT_DIR_PREFIX, project),
                    username = null,
                    type = METADATA_NAME
                ),
                serviceClient
            )
            .orRethrowAs { throw RPCException("Unable to list repositories", HttpStatusCode.InternalServerError) }
            .metadata
            .mapNotNull {
                val name = it.path.normalize().removePrefix(joinPath(PROJECT_DIR_PREFIX, project).normalize() + "/")
                val metadata = runCatching { defaultMapper.readValue<RepositoryMetadata>(it.jsonPayload) }.getOrNull()
                if (metadata != null) {
                    name to metadata
                } else {
                    null
                }
            }
            .map { Repository(it.first) }
    }

    suspend fun updatePermissions(
        userClient: AuthenticatedClient,
        project: String,
        repository: String,
        newAcl: List<ProjectAclEntry>
    ) {
        newAcl.map { it.group }.toSet().forEach { group ->
            val groupExists = ProjectGroups.groupExists.call(
                GroupExistsRequest(project, group),
                serviceClient
            ).orThrow().exists

            if (!groupExists) {
                throw RPCException("Group not found", HttpStatusCode.BadRequest)
            }
        }

        FileDescriptions.updateAcl.call(
            UpdateAclRequest(
                joinPath(PROJECT_DIR_PREFIX, project, repository),
                newAcl.map {
                    ACLEntryRequest(
                        ACLEntity.ProjectAndGroup(project, it.group),
                        it.rights,
                        revoke = false
                    )
                }
            ),
            userClient
        ).orThrow()
    }

    companion object : Loggable {
        override val log: Logger = logger()
        const val PROJECT_DIR_PREFIX = "/projects/"
        const val METADATA_NAME = "project_repository"

        private class RepositoryMetadata
    }
}

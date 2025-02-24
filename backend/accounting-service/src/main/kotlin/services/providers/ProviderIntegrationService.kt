package dk.sdu.cloud.accounting.services.providers

import dk.sdu.cloud.Actor
import dk.sdu.cloud.ActorAndProject
import dk.sdu.cloud.NormalizedPaginationRequestV2
import dk.sdu.cloud.accounting.api.providers.ResourceBrowseRequest
import dk.sdu.cloud.accounting.util.ProviderComms
import dk.sdu.cloud.accounting.util.Providers
import dk.sdu.cloud.accounting.util.invokeCall
import dk.sdu.cloud.auth.api.AuthProviders
import dk.sdu.cloud.auth.api.JwtRefresher
import dk.sdu.cloud.auth.api.RefreshingJWTAuthenticator
import dk.sdu.cloud.calls.HttpStatusCode
import dk.sdu.cloud.calls.RPCException
import dk.sdu.cloud.calls.client.*
import dk.sdu.cloud.provider.api.*
import dk.sdu.cloud.safeUsername
import dk.sdu.cloud.service.PageV2
import dk.sdu.cloud.service.SimpleCache
import dk.sdu.cloud.service.db.async.DBContext
import dk.sdu.cloud.service.db.async.paginateV2
import dk.sdu.cloud.service.db.async.sendPreparedStatement
import dk.sdu.cloud.service.db.async.withSession

class ProviderIntegrationService(
    private val db: DBContext,
    private val providers: ProviderService,
    private val serviceClient: AuthenticatedClient,
    private val devMode: Boolean = false,
) {
    // TODO(Dan): Unify all of this communication stuff. It is getting very confusing.
    private val providerCommunications = Providers(serviceClient)
    private val communicationCache = SimpleCache<String, Communication>(
        maxAge = 1000 * 60 * 60L,
        lookup = { providerResourceId ->
            val spec = providers.retrieve(
                ActorAndProject(Actor.System, null),
                providerResourceId,
                null
            ).specification

            val hostInfo = HostInfo(spec.domain, if (spec.https) "https" else "http", spec.port)
            val auth = RefreshingJWTAuthenticator(
                serviceClient.client,
                JwtRefresher.ProviderOrchestrator(serviceClient, spec.id)
            )

            val httpClient = auth.authenticateClient(OutgoingHttpCall).withFixedHost(hostInfo)
            val integrationProvider = IntegrationProvider(spec.id)

            val manifest = integrationProvider.retrieveManifest.call(Unit, httpClient).orNull()
            val isEnabled = manifest?.enabled ?: false
            if (!isEnabled || manifest == null) return@SimpleCache null

            Communication(integrationProvider, httpClient, spec, manifest)
        }
    )

    private data class Communication(
        val api: IntegrationProvider,
        val client: AuthenticatedClient,
        val spec: ProviderSpecification,
        val manifest: IntegrationProviderRetrieveManifestResponse,
    )

    suspend fun connect(
        actorAndProject: ActorAndProject,
        provider: String,
    ): IntegrationConnectResponse {
        // TODO This would ideally check if the user has been approved
        val (integrationProvider, httpClient, providerSpec) = communicationCache.get(provider) ?:
            throw RPCException("Connection is not supported by this provider", HttpStatusCode.BadRequest)

        val connection = integrationProvider.connect.call(
            IntegrationProviderConnectRequest(actorAndProject.actor.safeUsername()),
            httpClient
        ).orRethrowAs {
            val errorMessage = it.error?.why ?: it.statusCode.description
            throw RPCException("Connection has failed ($errorMessage)", HttpStatusCode.BadGateway)
        }

        if (connection.redirectTo.startsWith("http://") || connection.redirectTo.startsWith("https://")) {
            return IntegrationConnectResponse(connection.redirectTo)
        } else {
            return IntegrationConnectResponse(
                buildString {
                    append("http")
                    if (providerSpec.https) append("s")
                    append("://")
                    if (providerSpec.domain == "integration-module" && devMode) {
                        append("localhost")
                    } else {
                        append(providerSpec.domain)
                    }
                    if (providerSpec.port != null) {
                        append(":")
                        append(providerSpec.port)
                    }
                    append("/")
                    append(connection.redirectTo.removePrefix("/"))
                }
            )
        }
    }

    suspend fun browse(
        actorAndProject: ActorAndProject,
        request: NormalizedPaginationRequestV2
    ): PageV2<IntegrationBrowseResponseItem> {
        return db.paginateV2(
            actorAndProject.actor,
            request,
            create = { session ->
                session
                    .sendPreparedStatement(
                        {
                            setParameter("username", actorAndProject.actor.safeUsername())
                        },
                        """
                            declare c cursor for
                            with
                                connected as (
                                    select provider_id
                                    from provider.connected_with
                                    where
                                        username = :username and
                                        (expires_at is null or expires_at >= now())
                                ),
                                available as(
                                    select p.resource, p.unique_name
                                    from
                                        project.project_members pm join
                                        accounting.wallet_owner wo on
                                            pm.project_id = wo.project_id and
                                            pm.username = :username join
                                        accounting.wallets w on w.owned_by = wo.id join
                                        accounting.product_categories pc on w.category = pc.id join
                                        provider.providers p on pc.provider = p.unique_name
                                    union
                                    select p.resource, p.unique_name
                                    from
                                        accounting.wallet_owner wo join
                                        accounting.wallets w on w.owned_by = wo.id join
                                        accounting.product_categories pc on w.category = pc.id join
                                        provider.providers p on pc.provider = p.unique_name
                                    where
                                        wo.username = :username
                                )
                            select
                                a.resource as provider_id,
                                c.provider_id is not null as is_connected,
                                a.unique_name as provider_name
                            from
                                available a left join
                                connected c on c.provider_id = a.resource
                            order by provider_id
                        """
                    )
            },
            mapper = { _, rows ->
                rows.mapNotNull { row ->
                    val providerTitle = row.getString(2)!!
                    val manifest = communicationCache.get(row.getLong(0)!!.toString())

                    IntegrationBrowseResponseItem(
                        row.getLong(0)!!.toString(),
                        when {
                            providerTitle == "ucloud" -> true // NOTE(Dan): Backwards compatible
                            providerTitle == "aau" -> true // NOTE(Dan): Backwards compatible
                            manifest != null && !manifest.manifest.enabled -> true
                            else -> row.getBoolean(1)!!
                        },
                        providerTitle,
                        manifest?.manifest?.requiresMessageSigning ?: false
                    )
                }
            }
        )
    }

    suspend fun clearConnection(
        actorAndProject: ActorAndProject,
        requestedUser: String?,
        requestedProvider: String?,
    ) {
        val (actor) = actorAndProject

        val actorUsername = actor.safeUsername()
        val actorProviderId =
            if (actorUsername.startsWith(AuthProviders.PROVIDER_PREFIX)) {
                actorUsername.removePrefix(AuthProviders.PROVIDER_PREFIX)
            } else {
                null
            }

        val username = when {
            actor is Actor.System -> requestedUser ?: throw RPCException("Missing username", HttpStatusCode.BadRequest)
            actorProviderId != null -> requestedUser ?: throw RPCException("Missing username", HttpStatusCode.BadRequest)
            else -> actorUsername
        }

        val provider = when {
            actor is Actor.System -> requestedProvider ?: throw RPCException("Missing provider", HttpStatusCode.BadRequest)
            actorProviderId != null -> actorProviderId
            else -> requestedProvider ?: throw RPCException("Missing provider", HttpStatusCode.BadRequest)
        }

        val shouldNotifyProvider = actorProviderId == null && actor !is Actor.System

        db.withSession { session ->
            val success = session
                .sendPreparedStatement(
                    {
                        setParameter("username", username)
                        setParameter("provider_id", provider)
                    },
                    """
                        delete from provider.connected_with cw
                        using provider.providers p
                        where
                            cw.username = :username and
                            p.unique_name = :provider_id and
                            cw.provider_id = p.resource
                    """
                )
                .rowsAffected > 0L

            if (shouldNotifyProvider && success) {
                try {
                    providerCommunications.invokeCall(
                        provider,
                        actorAndProject,
                        { IntegrationProvider(it.provider.id).unlinked },
                        IntegrationProviderUnlinkedRequest(username),
                        actorAndProject.signedIntentFromUser,
                        isUserRequest = true
                    )
                } catch (ex: RPCException) {
                    if (ex.httpStatusCode.value in 500..599) {
                        throw RPCException(
                            "The provider does not appear to be responding at the moment. Try again later.",
                            HttpStatusCode.BadGateway
                        )
                    } else {
                        // This is OK
                    }
                }
            }
        }
    }

    suspend fun approveConnection(
        actor: Actor,
        username: String,
    ) {
        val providerId = actor.safeUsername().removePrefix(AuthProviders.PROVIDER_PREFIX)
        db.withSession { session ->
            val provider = providers.browse(
                ActorAndProject(Actor.System, null),
                ResourceBrowseRequest(ProviderIncludeFlags(filterName = providerId)),
                useProject = false,
                ctx = session
            ).items.singleOrNull() ?: return@withSession

            val comms = communicationCache.get(provider.id) ?: return@withSession
            val expireAfterMs = comms.manifest.expireAfterMs

            session
                .sendPreparedStatement(
                    {
                        setParameter("username", username)
                        setParameter("provider_id", providerId)
                        setParameter("expires_after", expireAfterMs?.let { it / 1000})
                    },
                    """
                        insert into provider.connected_with (username, provider_id, expires_at)
                        select :username, p.resource, now() + (:expires_after::bigint || 's')::interval
                        from provider.providers p
                        where p.unique_name = :provider_id
                        on conflict (username, provider_id) do update set expires_at = excluded.expires_at 
                    """
                )
        }
    }
}

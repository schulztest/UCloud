package dk.sdu.cloud.file.orchestrator.service

import com.github.jasync.sql.db.util.length
import dk.sdu.cloud.Actor
import dk.sdu.cloud.ActorAndProject
import dk.sdu.cloud.FindByStringId
import dk.sdu.cloud.accounting.api.Product
import dk.sdu.cloud.accounting.api.ProductType
import dk.sdu.cloud.accounting.util.*
import dk.sdu.cloud.accounting.util.ProviderSupport
import dk.sdu.cloud.accounting.util.Providers
import dk.sdu.cloud.calls.BulkRequest
import dk.sdu.cloud.calls.bulkRequestOf
import dk.sdu.cloud.calls.client.AuthenticatedClient
import dk.sdu.cloud.defaultMapper
import dk.sdu.cloud.file.orchestrator.api.*
import dk.sdu.cloud.project.api.ProjectRole
import dk.sdu.cloud.provider.api.*
import dk.sdu.cloud.service.db.async.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.serializer
import java.security.PermissionCollection
import java.security.Permissions

typealias FolderSvcSuper = ResourceService<SyncFolder, SyncFolder.Spec, SyncFolder.Update, SyncFolderIncludeFlags,
    SyncFolder.Status, Product.Synchronization, SyncFolderSupport, SimpleProviderCommunication>

class SyncFolderService(
    db: AsyncDBSessionFactory,
    providers: Providers<SimpleProviderCommunication>,
    support: ProviderSupport<SimpleProviderCommunication, Product.Synchronization, SyncFolderSupport>,
    serviceClient: AuthenticatedClient,
    files: FilesService,
    private val fileCollectionService: FileCollectionService,
) : FolderSvcSuper(db, providers, support, serviceClient) {
    override val table = SqlObject.Table("file_orchestrator.sync_folders")
    override val defaultSortColumn = SqlObject.Column(table, "resource")
    override val sortColumns: Map<String, SqlObject.Column> = mapOf("resource" to defaultSortColumn)
    override val serializer = serializer<SyncFolder>()
    override val updateSerializer = serializer<SyncFolder.Update>()
    override val productArea = ProductType.SYNCHRONIZATION

    override fun userApi() = SyncFolders
    override fun controlApi() = SyncFolderControl
    override fun providerApi(comms: ProviderComms) = SyncFolderProvider(comms.provider.id)

    init {
        files.addMoveHandler(::onFilesMoved)
        files.addDeleteHandler(::onFilesDeleted)
        fileCollectionService.addAclUpdateHandler(::onAclUpdated)
        fileCollectionService.addDeleteHandler(::onFileCollectionDeleted)
    }

    private suspend fun onAclUpdated(
        session: AsyncDBConnection,
        batch: BulkRequest<UpdatedAclWithResource<FileCollection>>
    ) {
        val affectedFolders = session.sendPreparedStatement(
            {
                setParameter("parentIds", batch.items.map { "/${it.resource.id}/%" })
            },
            """
                select f.resource, f.path, m.role
                from
                    provider.resource r join
                    file_orchestrator.sync_folders f on f.resource = r.id join
                    accounting.product_categories c on r.product = c.id join
                    project.project_members m on r.created_by = m.username
                where
                    type = 'sync_folder' and
                    f.path like any((select unnest(:parentIds::text[])));
            """
        ).rows.map { row ->
            val folderId = row.getLong("resource") ?: 0
            val folderPath = row.getString("path") !!
            val role = ProjectRole.valueOf(row.getString("role") ?: "USER")
            val resource = batch.items.first {
                it.resource.id == extractPathMetadata(folderPath).collection
            }.added;

            val newSyncType = if (resource.any { it.permissions.contains(Permission.EDIT) } || ProjectRole.ADMINS.contains(role)) {
                SynchronizationType.SEND_RECEIVE
            } else if (resource.any { it.permissions.contains(Permission.READ)}) {
                SynchronizationType.SEND_ONLY
            } else {
                null
            }

            Pair(folderId, newSyncType)
        }

        if (affectedFolders.isEmpty()) {
            return
        }

        session.sendPreparedStatement(
            {
                setParameter("ids", affectedFolders.filter { it.second == null }.map { it.first })
            },
            """
                delete from file_orchestrator.sync_folders
                where resource in (select unnest(:ids::bigint[]))
            """
        )

        session.sendPreparedStatement(
            {
                setParameter("ids", affectedFolders.filter { it.second != null }.map { it.first })
                setParameter("sync_types", affectedFolders.filter { it.second != null }.map { it.second.toString() })
            },
            """
                update file_orchestrator.sync_folders set
                    sync_type = updates.type
                from (select unnest(:ids::bigint[]), unnest(:sync_types::text[])) updates(id, type)
                where resource = updates.id
            """
        )

        proxy.bulkProxy(
            ActorAndProject(Actor.System, null),
            BulkRequest(affectedFolders),
            BulkProxyInstructions.pureProcedure(
                service = this,
                retrieveCall = { providerApi(it).onFilePermissionsUpdated },
                requestToId = { it.first.toString() },
                resourceToRequest = { req, res ->
                    res.copy(status = res.status.copy(syncType = req.second))
                }
            )
        )
    }

    private suspend fun onFilesMoved(batch: List<FilesMoveRequestItem>) {
        removeSyncFolders(batch.map { it.oldId })
    }

    private suspend fun onFilesDeleted(request: List<FindByStringId>) {
        removeSyncFolders(request.map { it.id })
    }

    private suspend fun onFileCollectionDeleted(request: BulkRequest<FindByStringId>) {
        removeSyncFolders(request.items.map { it.id })
    }


    private suspend fun removeSyncFolders(paths: List<String>) {
        db.withSession { session ->
            val affectedFolderIds: List<String> = session.sendPreparedStatement(
                {
                    setParameter("ids", paths)
                    setParameter("parentIds", paths.map { "$it/%" })
                },
                """
                        select f.resource
                        from file_orchestrator.sync_folders f 
                        where 
                            f.path in (select unnest(:ids::text[])) or
                            f.path like any((select unnest(:parentIds::text[])))
                    """
            ).rows.map {
                it.getLong(0).toString()
            }

            val affectedFolders = retrieveBulk(
                ActorAndProject(Actor.System, null),
                affectedFolderIds,
                setOf(Permission.EDIT)
            )

            if (affectedFolders.length > 0) {
                proxy.bulkProxy(
                    ActorAndProject(Actor.System, null),
                    bulkRequestOf(affectedFolders.map { it.id }),
                    BulkProxyInstructions.pureProcedure(
                        service = this,
                        retrieveCall = { providerApi(it).delete },
                        requestToId = { it },
                        resourceToRequest = { req, res -> res },
                        verifyRequest = { _, _, _ -> {} }
                    )
                )

                session.sendPreparedStatement(
                    {
                        setParameter("ids", affectedFolders.map { it.id.toLong() })
                    },
                    """
                        select file_orchestrator.remove_sync_folders(:ids::bigint[])
                    """
                )
            }
        }
    }

    override suspend fun createSpecifications(
        actorAndProject: ActorAndProject,
        idWithSpec: List<Pair<Long, SyncFolder.Spec>>,
        session: AsyncDBConnection,
        allowDuplicates: Boolean
    ) {
        val collectionIds = idWithSpec.map {
            extractPathMetadata(it.second.path).collection
        }.toSet()

        val fileCollections = fileCollectionService.retrieveBulk(
            actorAndProject,
            collectionIds,
            listOf(Permission.READ)
        )

        session
            .sendPreparedStatement(
                {
                    idWithSpec.split {
                        into("ids") { it.first }
                        into("paths") { it.second.path }
                        into("permissions") { (_, spec) ->
                            val permissions = fileCollections.find {
                                it.id == extractPathMetadata(spec.path).collection
                            }!!.permissions!!.myself


                            if (permissions.contains(Permission.EDIT) || permissions.contains(Permission.ADMIN)) {
                                SynchronizationType.SEND_RECEIVE.name
                            } else {
                                SynchronizationType.SEND_ONLY.name
                            }

                        }
                    }
                },
                """
                    insert into file_orchestrator.sync_folders (resource, path, sync_type)
                    select unnest(:ids::bigint[]), unnest(:paths::text[]), unnest(:permissions::text[])
                    on conflict (resource) do nothing
                """
            )
    }

    override suspend fun browseQuery(
        actorAndProject: ActorAndProject,
        flags: SyncFolderIncludeFlags?,
        query: String?
    ): PartialQuery {
        return PartialQuery(
            {
                setParameter("query", query)
                setParameter("filter_path", flags?.filterByPath)
                setParameter("filter_devices", flags?.filterDeviceId)
            },
            """
                select f.resource, f.device_id, f.path, f.sync_type
                from
                    accessible_resources resc join
                    file_orchestrator.sync_folders f on (resc.r).id = resource
                where
                    (:query::text is null or path ilike ('%' || :query || '%')) and
                    (:filter_path::text is null or :filter_path::text = path) and
                    (:filter_devices::text[] is null or
                        array_length(:filter_devices::text[], 1) < 1 or 
                        device_id in (select unnest(:filter_devices::text[]))
                    )
            """
        )
    }

    override suspend fun onUpdate(
        resources: List<SyncFolder>,
        updates: List<ResourceUpdateAndId<SyncFolder.Update>>,
        session: AsyncDBConnection
    ) {
        session.sendPreparedStatement(
            {
                setParameter("ids", updates.map { it.id })
                setParameter("devices", updates.map { it.update.deviceId })
                setParameter("sync_types", updates.map { it.update.syncType })
            },
            """
                update file_orchestrator.sync_folders as f
                set device_id = c.device_id, sync_type = c.sync_type
                from (
                    select unnest(:ids::bigint[]), unnest(:devices::text[]), unnest(:sync_types::text[])
                ) as c(resource, device_id, sync_type)
                where c.resource = f.resource
            """
        )

        super.onUpdate(resources, updates, session)
    }
}
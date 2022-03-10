package dk.sdu.cloud.file.orchestrator.service

import com.github.jasync.sql.db.util.length
import dk.sdu.cloud.Actor
import dk.sdu.cloud.ActorAndProject
import dk.sdu.cloud.FindByStringId
import dk.sdu.cloud.accounting.api.Product
import dk.sdu.cloud.accounting.api.ProductType
import dk.sdu.cloud.accounting.util.*
import dk.sdu.cloud.calls.*
import dk.sdu.cloud.calls.client.AuthenticatedClient
import dk.sdu.cloud.file.orchestrator.api.*
import dk.sdu.cloud.micro.BackgroundScope
import dk.sdu.cloud.provider.api.Permission
import dk.sdu.cloud.provider.api.ResourceUpdateAndId
import dk.sdu.cloud.provider.api.UpdatedAcl
import dk.sdu.cloud.service.DistributedLock
import dk.sdu.cloud.service.DistributedLockFactory
import dk.sdu.cloud.service.Time
import dk.sdu.cloud.service.db.async.AsyncDBConnection
import dk.sdu.cloud.service.db.async.AsyncDBSessionFactory
import dk.sdu.cloud.service.db.async.sendPreparedStatement
import dk.sdu.cloud.service.db.async.withSession
import kotlinx.coroutines.*
import kotlinx.serialization.serializer
import kotlin.random.Random

typealias FolderSvcSuper = ResourceService<SyncFolder, SyncFolder.Spec, SyncFolder.Update, SyncFolderIncludeFlags,
    SyncFolder.Status, Product.Synchronization, SyncFolderSupport, SimpleProviderCommunication>

class SyncFolderService(
    db: AsyncDBSessionFactory,
    providers: Providers<SimpleProviderCommunication>,
    support: ProviderSupport<SimpleProviderCommunication, Product.Synchronization, SyncFolderSupport>,
    serviceClient: AuthenticatedClient,
    files: FilesService,
    private val fileCollectionService: FileCollectionService,
    private val distributedLocks: DistributedLockFactory,
    private val scope: BackgroundScope,
) : FolderSvcSuper(db, providers, support, serviceClient) {
    override val table = SqlObject.Table("file_orchestrator.sync_folders")
    override val defaultSortColumn = SqlObject.Column(table, "resource")
    override val sortColumns: Map<String, SqlObject.Column> = mapOf("resource" to defaultSortColumn)
    override val serializer = serializer<SyncFolder>()
    override val updateSerializer = serializer<SyncFolder.Update>()
    override val productArea = ProductType.SYNCHRONIZATION
    override val personalResource: Boolean = true

    override fun userApi() = SyncFolders
    override fun controlApi() = SyncFolderControl
    override fun providerApi(comms: ProviderComms) = SyncFolderProvider(comms.provider.id)

    init {
        files.addMoveHandler(::onFilesMoved)
        files.addDeleteHandler(::onFilesDeleted)
        files.addTrashHandler(::onFilesTrashed)
        fileCollectionService.addDeleteHandler(::onFileCollectionDeleted)
    }

    private suspend fun onFilesMoved(batch: List<FilesMoveRequestItem>) {
        removeSyncFolders(batch.map { it.oldId })
    }

    private suspend fun onFilesDeleted(request: List<FindByStringId>) {
        removeSyncFolders(request.map { it.id })
    }

    private suspend fun onFilesTrashed(request: List<FindByPath>) {
        removeSyncFolders(request.map { it.id })
    }

    private suspend fun onFileCollectionDeleted(request: BulkRequest<FindByStringId>) {
        removeSyncFolders(request.items.map { "/${it.id}" })
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
                        ('/' || f.collection || f.sub_path) in (select unnest(:ids::text[])) or
                        ('/' || f.collection || f.sub_path) like any((select unnest(:parentIds::text[])))
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
        val collectionIds = idWithSpec.map { extractPathMetadata(it.second.path).collection }.toSet()

        val fileCollections = fileCollectionService.retrieveBulk(
            actorAndProject,
            collectionIds,
            listOf(Permission.READ)
        )

        session
            .sendPreparedStatement(
                {
                    val ids = ArrayList<Long>().also { setParameter("ids", it) }
                    val collections = ArrayList<Long>().also { setParameter("collections", it) }
                    val subPaths = ArrayList<String>().also { setParameter("sub_paths", it) }
                    val permissions = ArrayList<String>().also { setParameter("permissions", it) }

                    for ((id, spec) in idWithSpec) {
                        val collectionId = extractPathMetadata(spec.path).collection.toLongOrNull()
                            ?: throw RPCException.fromStatusCode(HttpStatusCode.Forbidden)

                        val subPath = spec.path.normalize().removePrefix("/${collectionId}")

                        val perms = fileCollections.find { it.id == collectionId.toString() }?.permissions?.myself
                            ?: throw RPCException.fromStatusCode(HttpStatusCode.Forbidden)

                        val myPermission = if (perms.contains(Permission.EDIT) || perms.contains(Permission.ADMIN)) {
                            "EDIT"
                        } else if (perms.contains(Permission.READ)) {
                            "READ"
                        } else {
                            throw RPCException.fromStatusCode(HttpStatusCode.Forbidden)
                        }

                        ids.add(id)
                        subPaths.add(subPath)
                        collections.add(collectionId)
                        permissions.add(myPermission)
                    }
                },
                """
                    insert into file_orchestrator.sync_folders (resource, collection, sub_path, status_permission)
                    select unnest(:ids::bigint[]), unnest(:collections::bigint[]), unnest(:sub_paths::text[]),
                           unnest(:permissions::text[])
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
                setParameter("filter_path", flags?.filterByPath)
            },
            """
                select
                    f.resource, f
                from
                    accessible_resources resc join
                    file_orchestrator.sync_folders f on (resc.r).id = resource
                where
                    (:filter_path::text is null or :filter_path::text = '/' || f.collection || f.sub_path)
            """
        )
    }

    override suspend fun addUpdate(
        actorAndProject: ActorAndProject,
        updates: BulkRequest<ResourceUpdateAndId<SyncFolder.Update>>,
        requireAll: Boolean
    ) {
        db.withSession { session ->
            session.sendPreparedStatement(
                {
                    updates.items.split {
                        into("ids") { it.id.toLong() }
                        into("remote_devices") { it.update.remoteDeviceId }
                        into("permissions") { it.update.permission }
                    }
                },
                """
                    with update_table as (
                        select
                            unnest(:ids::bigint[]) id,
                            unnest(:remote_devices::text[]) new_remote_device,
                            unnest(:permissions::text[]) new_permission
                    )
                    update file_orchestrator.sync_folders f
                    set
                        remote_device_id = coalesce(new_remote_device, remote_device_id),
                        status_permission = coalesce(new_permission, status_permission)
                    from
                        update_table t
                    where
                        t.id = f.resource
                """
            )
        }
    }

    override suspend fun updateAcl(
        actorAndProject: ActorAndProject,
        request: BulkRequest<UpdatedAcl>
    ): BulkResponse<Unit?> {
        throw RPCException("Operation not supported", HttpStatusCode.NotFound)
    }

    fun initialize() {
        scope.launch {
            val lock = distributedLocks.create("app-orchestrator-watcher", duration = 60_000)
            while (isActive) {
                val didAcquire = lock.acquire()
                if (didAcquire) {
                    try {
                        runMonitoringLoop(lock)
                    } catch (ex: Throwable) {
                        log.warn("Caught exception while monitoring jobs")
                        log.warn(ex.stackTraceToString())
                    }
                } else {
                    delay(15000 + Random.nextLong(5000))
                }
            }
        }
    }

    private suspend fun CoroutineScope.runMonitoringLoop(lock: DistributedLock) {
        var nextScan = 0L

        data class MonitoringRow(
            val createdBy: String,
            val path: String,
            val permission: Permission,
            val resource: Long,
            val provider: String,
        )

        while (isActive) {
            val now = Time.now()
            if (now >= nextScan) {
                val rowsByUser = db.withSession { session ->
                    session.sendPreparedStatement(
                        {},
                        """
                            with relevant_resources as (
                                select r.created_by, s.resource, '/' || s.collection || s.sub_path as path,
                                       s.status_permission, r.provider
                                from
                                    file_orchestrator.sync_folders s join
                                    provider.resource r on s.resource = r.id
                                where
                                    now() - s.last_scan >= '15 minutes'::interval
                                limit 500
                            )
                            update file_orchestrator.sync_folders s
                            set last_scan = now()
                            from relevant_resources r
                            where s.resource = r.resource
                            returning r.created_by, r.path, r.status_permission, r.resource, r.provider
                        """
                    ).rows.map { row ->
                        MonitoringRow(
                            row.getString(0)!!, row.getString(1)!!, Permission.valueOf(row.getString(2)!!),
                            row.getLong(3)!!, row.getString(4)!!
                        )
                    }
                }.groupBy { it.createdBy }

                for ((user, rows) in rowsByUser) {
                    val ap = ActorAndProject(Actor.SystemOnBehalfOfUser(user), null)
                    val collections = rows.map { extractPathMetadata(it.path).collection }.toSet()
                    val retrievedCollections = fileCollectionService.retrieveBulk(
                        ap, collections,
                        listOf(Permission.READ), requireAll = false
                    )

                    val missingByProvider = HashMap<String, ArrayList<FindByStringId>>()
                    val updatedByProvider = HashMap<String, ArrayList<SyncFolderPermissionsUpdatedRequestItem>>()

                    for (row in rows) {
                        val coll = extractPathMetadata(row.path).collection
                        val findById = FindByStringId(row.resource.toString())
                        val matching = retrievedCollections.find { it.id == coll }
                        val permissions = matching?.permissions?.myself
                        if (matching == null || permissions == null) {
                            missingByProvider.getOrPut(row.provider) { ArrayList() }.add(findById)
                        } else {
                            val newPermission = when {
                                permissions.contains(Permission.EDIT) || permissions.contains(Permission.ADMIN) ->
                                    Permission.EDIT
                                permissions.contains(Permission.READ) -> Permission.READ
                                else -> null
                            }

                            if (newPermission != row.permission && newPermission != null) {
                                updatedByProvider.getOrPut(row.provider) { ArrayList() }.add(
                                    SyncFolderPermissionsUpdatedRequestItem(row.resource.toString(), newPermission)
                                )
                            } else if (newPermission != row.permission && newPermission == null) {
                                missingByProvider.getOrPut(row.provider) { ArrayList() }.add(findById)
                            }
                        }
                    }

                    for ((provider, missing) in missingByProvider) {
                        coroutineScope {
                            runCatching {
                                delete(ActorAndProject(Actor.System, null), BulkRequest(missing))
                            }
                        }
                    }

                    for ((provider, updated) in updatedByProvider) {
                        val folders = retrieveBulk(
                            ap,
                            updated.map { it.resourceId },
                            listOf(Permission.READ),
                            includeUnconfirmed = false,
                            requireAll = false
                        )

                        if (folders.isEmpty()) continue

                        coroutineScope {
                            runCatching {
                                proxy.pureProxy(
                                    ap,
                                    folders.first().specification.product,
                                    { providerApi(it).onPermissionsUpdated },
                                    BulkRequest(updated)
                                )
                            }.exceptionOrNull()?.printStackTrace()
                        }
                    }
                }

                nextScan = Time.now() + 30_000
            }

            if (!lock.renew(90_000)) {
                log.warn("Lock was lost. We are no longer the master. Did update take too long?")
                break
            }
        }
    }
}

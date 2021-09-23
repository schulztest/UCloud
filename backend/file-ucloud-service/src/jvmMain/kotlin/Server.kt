package dk.sdu.cloud.file.ucloud

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dk.sdu.cloud.auth.api.*
import dk.sdu.cloud.calls.client.OutgoingHttpCall
import dk.sdu.cloud.calls.client.RpcClient
import dk.sdu.cloud.file.ucloud.rpc.FileCollectionsController
import dk.sdu.cloud.file.ucloud.rpc.FilesController
import dk.sdu.cloud.file.ucloud.rpc.SyncController
import dk.sdu.cloud.file.ucloud.rpc.ShareController
import dk.sdu.cloud.file.ucloud.services.*
import dk.sdu.cloud.file.ucloud.services.tasks.*
import dk.sdu.cloud.micro.*
import dk.sdu.cloud.service.*
import dk.sdu.cloud.service.db.async.AsyncDBSessionFactory
import service.TokenValidationChain
import java.io.File
import java.util.*

class Server(
    override val micro: Micro,
    private val configuration: Configuration,
    private val cephConfig: CephConfiguration,
    private val syncConfig: SyncConfiguration,
    private val syncMounterSharedSecret: String?
) : CommonServer {
    override val log = logger()

    @OptIn(ExperimentalStdlibApi::class)
    override fun start() {
        val (refreshToken, validation) =
            if (configuration.providerRefreshToken == null || configuration.ucloudCertificate == null) {
                throw IllegalStateException("Missing configuration at files.ucloud.providerRefreshToken")
            } else {
                Pair(
                    configuration.providerRefreshToken,
                    InternalTokenValidationJWT.withPublicCertificate(configuration.ucloudCertificate)
                )
            }

        val authenticator = RefreshingJWTAuthenticator(micro.client, JwtRefresher.Provider(refreshToken))
        val internalAuthenticator = RefreshingJWTAuthenticator(
            micro.client,
            JwtRefresherSharedSecret(syncMounterSharedSecret ?: "this_will_fail")
        )
        @Suppress("UNCHECKED_CAST")
        micro.providerTokenValidation = TokenValidationChain(
            buildList {
                add(validation as TokenValidation<Any>)
                if (syncMounterSharedSecret != null) {
                    InternalTokenValidationJWT.withSharedSecret(syncMounterSharedSecret)
                } else {
                    log.warn("Missing shared secret for file-ucloud-service and sync-mounter. Sync will not work")
                }
            }
        )
        val authenticatedClient = authenticator.authenticateClient(OutgoingHttpCall)
        val db = AsyncDBSessionFactory(micro)

        val fsRootFile =
            File((cephConfig.cephfsBaseMount ?: "/mnt/cephfs/") + cephConfig.subfolder).takeIf { it.exists() }
                ?: if (micro.developmentModeEnabled) File("./fs") else throw IllegalStateException("No mount found!")

        val pathConverter = PathConverter(InternalFile(fsRootFile.absolutePath), authenticatedClient)
        val nativeFs = NativeFS(pathConverter)
        val distributedStateFactory = RedisDistributedStateFactory(micro)
        val trashService = TrashService(pathConverter)
        val cephStats = CephFsFastDirectoryStats(nativeFs)
        val fileQueries = FileQueries(pathConverter, distributedStateFactory, nativeFs, trashService, cephStats)
        val chunkedUploadService = ChunkedUploadService(db, pathConverter, nativeFs)
        val downloadService = DownloadService(db, pathConverter, nativeFs)
        val memberFiles = MemberFiles(nativeFs, pathConverter, authenticatedClient)
        val distributedLocks = DistributedLockBestEffortFactory(micro)
        val syncthingClient = SyncthingClient(syncConfig, db, authenticatedClient, distributedLocks)
        val syncService =
            SyncService(syncthingClient, db, authenticatedClient, cephStats, pathConverter)

        val shareService = ShareService(nativeFs, pathConverter, authenticatedClient)
        val taskSystem = TaskSystem(db, pathConverter, nativeFs, micro.backgroundScope, authenticatedClient).apply {
            install(CopyTask())
            install(DeleteTask())
            install(MoveTask())
            install(CreateFolderTask())
            install(TrashTask(memberFiles, trashService))
        }
        val fileCollectionService = FileCollectionsService(
            pathConverter,
            db,
            taskSystem,
            nativeFs
        )

        taskSystem.launchScheduler(micro.backgroundScope)

        configureControllers(
            FilesController(fileQueries, taskSystem, chunkedUploadService, downloadService),
            FileCollectionsController(fileCollectionService),
            SyncController(syncService),
            ShareController(shareService)
        )

        startServices()
    }
}

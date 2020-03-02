package dk.sdu.cloud.activity

import dk.sdu.cloud.activity.http.ActivityController
import dk.sdu.cloud.activity.processor.StorageAuditProcessor
import dk.sdu.cloud.activity.processor.StorageEventProcessor
import dk.sdu.cloud.activity.services.ActivityEventElasticDao
import dk.sdu.cloud.activity.services.ActivityService
import dk.sdu.cloud.activity.services.FileLookupService
import dk.sdu.cloud.activity.services.HibernateActivityEventDao
import dk.sdu.cloud.auth.api.authenticator
import dk.sdu.cloud.calls.client.OutgoingHttpCall
import dk.sdu.cloud.micro.Micro
import dk.sdu.cloud.micro.elasticHighLevelClient
import dk.sdu.cloud.micro.eventStreamService
import dk.sdu.cloud.micro.hibernateDatabase
import dk.sdu.cloud.micro.server
import dk.sdu.cloud.service.CommonServer
import dk.sdu.cloud.service.configureControllers
import dk.sdu.cloud.service.startServices
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

class Server(
    override val micro: Micro
) : CommonServer {
    override val log = logger()

    override fun start() {
        val client = micro.authenticator.authenticateClient(OutgoingHttpCall)
        val db = micro.hibernateDatabase
        val elasticClient = micro.elasticHighLevelClient

        log.info("Creating core services")
        val activityEventDao = HibernateActivityEventDao()
        val activityElasticDao = ActivityEventElasticDao(elasticClient)
        val fileLookupService = FileLookupService(client)
        val activityService = ActivityService(activityElasticDao, fileLookupService)
        log.info("Core services constructed")

        if (micro.commandLineArguments.contains("--deleteOldActivity")) {
            val numberOfDays = 180L
            runBlocking {
                activityService.deleteOldActivity(numberOfDays)
            }
            exitProcess(0)
        }

        log.info("Creating stream processors")
        StorageAuditProcessor(micro.eventStreamService, activityService).init()
        StorageEventProcessor(micro.eventStreamService, activityService).init()
        log.info("Stream processors constructed")

        with(micro.server) {
            configureControllers(
                ActivityController(activityService)
            )
        }

        startServices()
    }
}

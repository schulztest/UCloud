package dk.sdu.cloud

import com.fasterxml.jackson.databind.JsonNode
import dk.sdu.cloud.accounting.AccountingService
import dk.sdu.cloud.accounting.api.*
import dk.sdu.cloud.accounting.api.projects.*
import dk.sdu.cloud.accounting.api.providers.ResourceRetrieveRequest
import dk.sdu.cloud.alerting.AlertingService
import dk.sdu.cloud.app.kubernetes.AppKubernetesService
import dk.sdu.cloud.app.orchestrator.AppOrchestratorService
import dk.sdu.cloud.app.store.AppStoreService
import dk.sdu.cloud.app.store.api.AppStore
import dk.sdu.cloud.app.store.api.CreateTagsRequest
import dk.sdu.cloud.app.store.api.ToolStore
import dk.sdu.cloud.audit.ingestion.AuditIngestionService
import dk.sdu.cloud.auth.AuthService
import dk.sdu.cloud.auth.api.*
import dk.sdu.cloud.avatar.AvatarService
import dk.sdu.cloud.calls.bulkRequestOf
import dk.sdu.cloud.calls.client.*
import dk.sdu.cloud.contact.book.ContactBookService
import dk.sdu.cloud.elastic.management.ElasticManagementService
import dk.sdu.cloud.file.orchestrator.FileOrchestratorService
import dk.sdu.cloud.slack.SlackService
import dk.sdu.cloud.file.ucloud.FileUcloudService
import dk.sdu.cloud.mail.MailService
import dk.sdu.cloud.micro.*
import dk.sdu.cloud.news.NewsService
import dk.sdu.cloud.notification.NotificationService
import dk.sdu.cloud.password.reset.PasswordResetService
import dk.sdu.cloud.project.api.CreateProjectRequest
import dk.sdu.cloud.project.api.Projects
import dk.sdu.cloud.provider.api.ProviderIncludeFlags
import dk.sdu.cloud.provider.api.ProviderSpecification
import dk.sdu.cloud.provider.api.Providers
import dk.sdu.cloud.service.Loggable
import dk.sdu.cloud.support.SupportService
import dk.sdu.cloud.task.TaskService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.logging.log4j.Level
import java.io.File
import kotlin.system.exitProcess

object Launcher : Loggable {
    override val log = logger()
}

val services = setOf<Service>(
    AccountingService,
    AppOrchestratorService,
    AppStoreService,
    AuditIngestionService,
    AuthService,
    AvatarService,
    ContactBookService,
    ElasticManagementService,
    MailService,
    NewsService,
    NotificationService,
    PasswordResetService,
    FileOrchestratorService,
    FileUcloudService,
    SupportService,
    AppKubernetesService,
    TaskService,
    AlertingService,
    SlackService
)

enum class LauncherPreset(val flag: String, val serviceFilter: (Service) -> Boolean) {
    Full("full", { true }),

    FullNoProviders("no-providers", { it != AppKubernetesService && it != FileUcloudService }),

    Core("core", { svc ->
        when (svc) {
            AuditIngestionService,
            AuthService,
            AvatarService,
            ContactBookService,
            ElasticManagementService,
            MailService,
            NewsService,
            NotificationService,
            AlertingService,
            PasswordResetService,
            SupportService,
            TaskService -> true

            else -> false
        }
    }),

    AccountingAndProjectManagement("apm", { svc ->
        when (svc) {
            AccountingService -> true
            else -> false
        }
    }),

    Orchestrators("orchestrators", { svc ->
        when (svc) {
            AppOrchestratorService,
            FileOrchestratorService -> true

            else -> false
        }
    }),

    Providers("providers", { svc ->
        when (svc) {
            AppKubernetesService,
            FileUcloudService -> true

            else -> false
        }
    }),
}

suspend fun main(args: Array<String>) {
    val initialConfig = Micro().apply {
        commandLineArguments = args.toList()
        isEmbeddedService = false
        serviceDescription = PlaceholderServiceDescription

        install(ConfigurationFeature)
    }.configuration

    val isInstalling = args.contains("--dev") && runCatching {
        initialConfig.requestChunkAtOrNull<JsonNode>("tokenValidation") == null
    }.getOrElse { true }

    if (isInstalling) {
        runInstaller(initialConfig.configDirs.first())
    }

    if (args.contains("--run-script") && args.contains("api-gen")) {
        generateCode()
        exitProcess(0)
    }

    if (args.contains("--run-script") && args.contains("migrate-db")) {
        val reg = ServiceRegistry(args, PlaceholderServiceDescription)
        reg.rootMicro.install(DatabaseConfigurationFeature)
        reg.rootMicro.install(FlywayFeature)
        reg.rootMicro.databaseConfig.migrateAll()
        exitProcess(0)
    }

    val presetArg = args.getOrNull(0)?.takeIf { !it.startsWith("--") }
    val preset = if (presetArg == null) {
        LauncherPreset.FullNoProviders
    } else {
        LauncherPreset.values().find { it.flag == presetArg }
            ?: error(
                "Unknown preset: $presetArg (available options: ${
                    LauncherPreset.values().joinToString(", ") { it.flag }
                })")
    }

    if (args.contains("--dev") && !isInstalling) {
        val reg = ServiceRegistry(args + "--no-server", PlaceholderServiceDescription)
        reg.rootMicro.install(DatabaseConfigurationFeature)
        reg.rootMicro.install(FlywayFeature)
        reg.rootMicro.databaseConfig.migrateAll()
    }

    val reg = ServiceRegistry(args, PlaceholderServiceDescription)

    services.filter { preset.serviceFilter(it) }.forEach { objectInstance ->
        try {
            Launcher.log.trace("Registering ${objectInstance.javaClass.canonicalName}")
            reg.register(objectInstance)
        } catch (ex: Throwable) {
            Launcher.log.error("Caught error: ${ex.stackTraceToString()}")
        }
    }

    reg.rootMicro.feature(LogFeature).configureLevels(
        mapOf(
            // Don't display same information twice
            "dk.sdu.cloud.calls.client.OutgoingHttpRequestInterceptor" to Level.INFO
        )
    )

    if (isInstalling) {
        GlobalScope.launch {
            while (!ucloudIsReady.get()) {
                delay(100)
            }

            val m = Micro(reg.rootMicro)
            m.install(AuthenticatorFeature)
            val client = m.authenticator.authenticateClient(OutgoingHttpCall)
            UserDescriptions.createNewUser.call(
                listOf(
                    CreateSingleUserRequest(
                        "user",
                        "mypassword",
                        role = Role.ADMIN,
                        email = "user@localhost"
                    )
                ),
                client
            ).orThrow()
        }
    }

    reg.rootMicro.feature(ServerFeature).ktorApplicationEngine!!.application.routing {
        get("/i") {
            call.respondRedirect("http://localhost:9000/app", permanent = false)
        }
    }

    reg.start()
}

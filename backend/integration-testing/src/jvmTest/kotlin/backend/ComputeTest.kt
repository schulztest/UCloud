package dk.sdu.cloud.integration.backend

import dk.sdu.cloud.accounting.api.Product
import dk.sdu.cloud.accounting.api.providers.ResourceRetrieveRequest
import dk.sdu.cloud.app.orchestrator.api.*
import dk.sdu.cloud.app.store.api.AppParameterValue
import dk.sdu.cloud.app.store.api.AppStore
import dk.sdu.cloud.app.store.api.NameAndVersion
import dk.sdu.cloud.app.store.api.ToolStore
import dk.sdu.cloud.calls.bulkRequestOf
import dk.sdu.cloud.calls.client.*
import dk.sdu.cloud.integration.IntegrationTest
import dk.sdu.cloud.integration.UCloudLauncher.serviceClient
import dk.sdu.cloud.service.Time
import io.ktor.http.*
import kotlinx.coroutines.delay

class ComputeTest : IntegrationTest() {
    suspend fun create() {
        ToolStore.create.call(
            Unit,
            serviceClient.withHttpBody(
                """
                    ---
                    tool: v1

                    title: Figlet

                    name: figlet
                    version: 1.0.0

                    container: truek/figlets:1.1.1

                    authors:
                    - Dan Sebastian Thrane <dthrane@imada.sdu.dk>

                    description: Tool for rendering text.

                    defaultTimeAllocation:
                      hours: 0
                      minutes: 1
                      seconds: 0

                    backend: DOCKER
                """.trimIndent(),
                ContentType("text", "yaml")
            )
        ).orThrow()
        AppStore.create.call(
            Unit,
            serviceClient.withHttpBody(
                """
                   ---
                   application: v1

                   title: Figlet
                   name: figlet
                   version: 1.0.0

                   tool:
                     name: figlet
                     version: 1.0.0

                   authors:
                   - Dan Sebastian Thrane <dthrane@imada.sdu.dk>

                   description: >
                     Render some text with Figlet Docker!

                   invocation:
                   - figlet
                   - type: var
                     vars: text
                     
                   parameters:
                     text:
                       title: "Some text to render with figlet"
                       type: text
     
                """.trimIndent(),
                ContentType("text", "yaml")
            )
        ).orThrow()

        AppStore.create.call(
            Unit,
            serviceClient.withHttpBody(
                """
                    ---
                    application: v1
                    
                    title: long running
                    name: long-running
                    version: 1.0.0
                    
                    tool:
                      name: figlet
                      version: 1.0.0
                    
                    authors: ["Dan Sebasti2 Thrane"]
                    
                    description: Runs for a long time
                    
                    # We just count to a really big number
                    invocation:
                    - figlet-count
                    - 1000000000
                """.trimIndent(),
                ContentType("text", "yaml")
            )
        ).orThrow()
    }

    private suspend fun startJob(
        longRunning: Boolean,
        interactivity: InteractiveSessionType?,
        product: Product.Compute,
        rpcClient: AuthenticatedClient,
        waitForState: JobState? = null,
    ): Pair<String, JobState> {
        val id = Jobs.create.call(
            bulkRequestOf(
                JobSpecification(
                    NameAndVersion(name = "figlet", version = "1.0.0"),
                    product.toReference(),
                    parameters = mapOf(
                        "text" to AppParameterValue.Text("Hello World!")
                    )
                )
            ),
            rpcClient
        ).orThrow().responses.first()!!.id

        val deadline = Time.now() + 1000 * 60 * 5L
        var lastKnownState: JobState = JobState.IN_QUEUE
        while (Time.now() < deadline && !lastKnownState.isFinal()) {
            if (waitForState == lastKnownState) break
            lastKnownState = Jobs.retrieve.call(
                ResourceRetrieveRequest(JobIncludeFlags(), id),
                rpcClient
            ).orThrow().status.state

            delay(50)
        }

        return Pair(id, lastKnownState)
    }

    data class TestCase(
        val title: String,
        val initialization: suspend () -> Unit,
        val products: List<Product.Compute>,
        val storage: Product.Storage,
    )

    override fun defineTests() {
        val cases: List<TestCase> = emptyList()

        for (case in cases) {
            for (product in case.products) {
                val titlePrefix = "Compute @ ${case.title} ($product):"
                test<Unit, Unit>("$titlePrefix Batch application") {
                    execute {
                        case.initialization()
                        create()
                        with(initializeResourceTestContext(case.products, emptyList())) {
                            val rpcClient = adminClient.withProject(project)
                            val (collection) = initializeCollection(project, rpcClient, case.storage)

                            val (id, lastKnownState) = startJob(
                                longRunning = false,
                                interactivity = null,
                                product,
                                rpcClient,
                                waitForState = JobState.SUCCESS
                            )

                            if (lastKnownState != JobState.SUCCESS) {
                                throw IllegalStateException("Application did not succeed within deadline: $lastKnownState")
                            }
                        }
                    }

                    case("No input") {
                        input(Unit)
                        check {}
                    }
                }

                test<Unit, Unit>("$titlePrefix Long running with cancel") {
                    execute { }
                    case("No input") {
                        input(Unit)
                        check {}
                    }
                }

                test<Unit, Unit>("$titlePrefix Long running with cancel and follow") {
                    execute { }
                    case("No input") {
                        input(Unit)
                        check {}
                    }
                }

                test<Unit, Unit>("$titlePrefix Batch application with files") {
                    execute { }
                    case("No input") {
                        input(Unit)
                        check {}
                    }
                }

                test<Unit, Unit>("$titlePrefix Batch application with license") {
                    execute { }
                    case("No input") {
                        input(Unit)
                        check {}
                    }
                }

                test<Unit, Unit>("$titlePrefix Batch application with ingress") {
                    execute { }
                    case("No input") {
                        input(Unit)
                        check {}
                    }
                }

                test<Unit, Unit>("$titlePrefix Batch application with ip") {
                    execute { }
                    case("No input") {
                        input(Unit)
                        check {}
                    }
                }

                test<Unit, Unit>("$titlePrefix Create interactive shell") {
                    execute { }
                    case("No input") {
                        input(Unit)
                        check {}
                    }
                }

                test<Unit, Unit>("$titlePrefix Create interactive VNC") {
                    execute { }
                    case("No input") {
                        input(Unit)
                        check {}
                    }
                }

                test<Unit, Unit>("$titlePrefix Create interactive web interface") {
                    execute { }
                    case("No input") {
                        input(Unit)
                        check {}
                    }
                }
            }
        }
    }
}
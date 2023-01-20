package dk.sdu.cloud.faults

import dk.sdu.cloud.calls.CallDescription
import dk.sdu.cloud.calls.bulkRequestOf
import dk.sdu.cloud.calls.client.AuthenticatedClient
import dk.sdu.cloud.calls.client.call
import dk.sdu.cloud.defaultMapper
import dk.sdu.cloud.service.Time
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

object FaultInjectionSystem {
    var enabled = false

    private val allFaults = AtomicReference<List<InjectedFault>>()

    fun injectFaults(faults: List<InjectedFault>) {
        while (true) {
            val current = allFaults.get()
            val new = current + faults
            if (allFaults.compareAndSet(current, new)) break
            Thread.sleep(Random.nextLong(0, 5))
        }
    }

    fun clearFaults() {
        while (true) {
            if (allFaults.compareAndSet(allFaults.get(), emptyList())) break
            Thread.sleep(Random.nextLong(0, 5))
        }
    }

    suspend fun selectFault(
        system: FaultSystem,
        environment: suspend () -> FaultEnvironment,
    ): Fault? {
        if (!enabled) return null

        val currentFaults = allFaults.get()

        val evaluatedEnvironment = environment()
        for (injectedFault in currentFaults) {
            if (injectedFault.fault.system != system) continue

            if (injectedFault.condition.evaluate(evaluatedEnvironment::retrieveProperty)) {
                return injectedFault.fault
            }
        }

        return null
    }
}

suspend inline fun <R> faultInjectionSection(
    system: FaultSystem,
    noinline environment: suspend () -> FaultEnvironment,
    handleFault: (fault: Fault) -> R,
    handler: () -> R
): R {
    val fault = FaultInjectionSystem.selectFault(system, environment) ?: return handler()
    return handleFault(fault)
}

interface FaultEnvironment {
    suspend fun retrieveProperty(property: String): Any?
}

object FaultEnvironmentUtils : FaultEnvironment {
    override suspend fun retrieveProperty(property: String): Any? {
        return when (property) {
            "random100" -> Random.nextLong(0, 100)
            "random1000" -> Random.nextLong(0, 1_000)
            "random10000" -> Random.nextLong(0, 10_000)
            "timestamp" -> Time.now()
            else -> null
        }
    }
}

data class FaultEnvironmentMap(
    private val entries: HashMap<String, Any?>,
    private val delegate: FaultEnvironment? = null
) : FaultEnvironment {
    private val mutex = Mutex()
    override suspend fun retrieveProperty(property: String): Any? =
        mutex.withLock { entries[property] } ?: delegate?.retrieveProperty(property)

    suspend fun setProperty(property: String, value: Any?) {
        mutex.withLock { entries[property] = value }
    }

    suspend fun increment(property: String, delta: Long = 1, initialValue: Long = 0L) {
        mutex.withLock {
            val current = entries[property]
            val valueAsLong = (current as? Int)?.toLong() ?: (current as? Long) ?: initialValue
            entries[property] = valueAsLong + delta
        }
    }

    suspend fun decrement(property: String, delta: Long = 1, initialValue: Long = 0L) {
        mutex.withLock {
            val current = entries[property]
            val valueAsLong = (current as? Int)?.toLong() ?: (current as? Long) ?: initialValue
            entries[property] = valueAsLong - delta
        }
    }

    suspend fun embedJson(prefix: String, json: JsonElement) {
        when (json) {
            is JsonPrimitive -> {
                mutex.withLock {
                    entries[prefix] = json.booleanOrNull ?: json.longOrNull ?: json.doubleOrNull ?: json.content
                }
            }

            JsonNull -> {
                // Do nothing
            }

            is JsonObject -> {
                json.entries.forEach { (key, value) ->
                    embedJson("$prefix/$key", value)
                }
            }

            is JsonArray -> {
                json.forEachIndexed { index, jsonElement ->
                    embedJson("$prefix[$index]", jsonElement)
                }
            }
        }
    }
}

fun faultEnv(
    vararg entries: Pair<String, Any?>,
    delegate: FaultEnvironment = FaultEnvironmentUtils
): FaultEnvironmentMap {
    return FaultEnvironmentMap(hashMapOf(*entries), delegate)
}

fun unhandledFault(fault: Fault): Nothing = error("This fault was not supposed to be handled here: $fault")


val callEnv = faultEnv(
    "total_calls" to 0L,
)

suspend fun <Request : Any, Response : Any> fakeRpcFunction(
    call: CallDescription<Request, *, Response>,
    request: Request,
) {
    faultInjectionSection(
        FaultSystem.Rpc,
        environment = {
            callEnv.increment("total_calls")
            callEnv.increment("calls_to_${call.fullName}")

            faultEnv(delegate = callEnv).apply {
                setProperty("call_name", call.fullName)
                embedJson("request", defaultMapper.encodeToJsonElement(call.requestType, request))
            }
        },

        handleFault = { fault ->
            when (fault) {
                is Fault.Rpc.ConnectionFailure -> TODO()
                is Fault.Rpc.Delay -> TODO()
                is Fault.Rpc.ProxyFailure -> TODO()
                is Fault.Rpc.StaticResponse -> TODO()
                is Fault.Rpc.Tls -> TODO()

                else -> unhandledFault(fault)
            }
        },

        handler = {
            // Do the normal call here
        }
    )
}

suspend fun fakeInjectionUsage(client: AuthenticatedClient) {
    FaultInjections.injectFailure.call(
        bulkRequestOf(
            // Cause a delay for all calls to "foobar.call"
            InjectedFault(
                FaultCondition(
                    StringComparison(
                        "call_name",
                        StringComparison.Op.EQUALS,
                        "foobar.call"
                    )
                ),
                Fault.Rpc.Delay(5000)
            ),

            // Fail the second invocation of files.browse
            InjectedFault(
                FaultCondition(
                    IntComparison(
                        "calls_to_files.browse",
                        IntComparison.Op.EQUALS,
                        2
                    ),
                ),
                Fault.Rpc.StaticResponse(
                    500,
                    emptyList(),
                    "Internal server error",
                    responseTime = 1000
                )
            ),

            // Cause a delay on 10% of calls
            InjectedFault(
                FaultCondition(
                    IntComparison(
                        "random100",
                        IntComparison.Op.LESS_THAN,
                        10
                    )
                ),
                Fault.Rpc.Delay(2000)
            ),

            // Fail all calls to files.browse which are looking in a specific folder
            InjectedFault(
                FaultCondition(
                    StringComparison(
                        "call_name",
                        StringComparison.Op.EQUALS,
                        "files.browse"
                    ),
                    StringComparison(
                        "request/path",
                        StringComparison.Op.EQUALS,
                        "/12341/my folder"
                    )
                ),
                Fault.Rpc.ConnectionFailure()
            ),
        ),
        client
    )
}

package dk.sdu.cloud.faults

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

    fun selectFault(
        system: FaultSystem,
        environment: () -> FaultEnvironment,
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

inline fun <R> faultInjectionSection(
    system: FaultSystem,
    noinline environment: () -> FaultEnvironment,
    handleFault: (fault: Fault) -> R,
    handler: () -> R
): R {
    val fault = FaultInjectionSystem.selectFault(system, environment) ?: return handler()
    return handleFault(fault)
}

interface FaultEnvironment {
    fun retrieveProperty(property: String): Any?
}

data class FaultEnvironmentMap(val entries: Map<String, Any?>) : FaultEnvironment {
    override fun retrieveProperty(property: String): Any? = entries[property]
}

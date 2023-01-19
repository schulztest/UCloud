package dk.sdu.cloud.faults

import dk.sdu.cloud.CommonErrorMessage
import dk.sdu.cloud.Roles
import dk.sdu.cloud.calls.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.math.absoluteValue

@UCloudApiInternal(InternalLevel.BETA)
object FaultInjections : CallDescriptionContainer("fault_injection") {
    const val baseContext = "/api/faultInjection"

    val injectFailure = call("injectFailure", BulkRequest.serializer(InjectedFault.serializer()), Unit.serializer(), CommonErrorMessage.serializer()) {
        httpUpdate(baseContext, "injectFailure", roles = Roles.PUBLIC)
    }

    val resetFailures = call("resetFailures", Unit.serializer(), Unit.serializer(), Unit.serializer()) {
        httpUpdate(baseContext, "resetFailures", roles = Roles.PUBLIC)
    }

    val clearCaches = call("clearCaches", Unit.serializer(), Unit.serializer(), Unit.serializer()) {
        httpUpdate(baseContext, "clearCaches", roles = Roles.PUBLIC)
    }
}

enum class FaultSystem {
    Rpc,
    SqlDatabase,
    DistributedLocks,
    EventStream,
    Kubernetes,
    FileSystem,
    Slurm,
}

@Serializable
@UCloudApiInternal(InternalLevel.BETA)
data class InjectedFault(
    val condition: FaultCondition,
    val fault: Fault,
)

typealias FaultCondition = Conjunction

@Serializable
@UCloudApiInternal(InternalLevel.BETA)
sealed class Term {
    abstract fun evaluate(valueRetriever: (propertyName: String) -> Any?): Boolean
}

@Serializable
@UCloudApiInternal(InternalLevel.BETA)
data class Conjunction(val terms: List<Term>) : Term() {
    override fun evaluate(valueRetriever: (propertyName: String) -> Any?): Boolean {
        return terms.all { it.evaluate(valueRetriever) }
    }
}

@Serializable
@UCloudApiInternal(InternalLevel.BETA)
data class Disjunction(val terms: List<Term>) : Term() {
    override fun evaluate(valueRetriever: (propertyName: String) -> Any?): Boolean {
        return terms.any { it.evaluate(valueRetriever) }
    }
}

@Serializable
@UCloudApiInternal(InternalLevel.BETA)
data class Negate(val term: Term) : Term() {
    override fun evaluate(valueRetriever: (propertyName: String) -> Any?): Boolean {
        return !term.evaluate(valueRetriever)
    }
}

@Serializable
@UCloudApiInternal(InternalLevel.BETA)
sealed class Comparison<V> : Term() {
    abstract val property: String

    override fun evaluate(valueRetriever: (propertyName: String) -> Any?): Boolean {
        val value = valueRetriever(property)
        if (!canUse(value)) return false

        @Suppress("UNCHECKED_CAST")
        return evaluateTerm(value as V)
    }

    abstract fun canUse(value: Any?): Boolean
    abstract fun evaluateTerm(value: V): Boolean
}

@Serializable
@UCloudApiInternal(InternalLevel.BETA)
data class StringComparison(
    override val property: String,
    val op: Op,
    val rhs: String,
) : Comparison<String>() {
    enum class Op {
        EQUALS,
        EQUALS_IGNORE_CASE,

        NOT_EQUALS,
        NOT_EQUALS_IGNORE_CASE,

        STARTS_WITH,
        STARTS_WITH_IGNORE_CASE,

        ENDS_WITH,
        ENDS_WITH_IGNORE_CASE,

        CONTAINS,
        CONTAINS_IGNORE_CASE,

        // In this case RHS is a regex
        MATCHES_REGEX
    }

    override fun canUse(value: Any?): Boolean = value is String

    override fun evaluateTerm(value: String): Boolean {
        return when (op) {
            Op.EQUALS -> value == rhs
            Op.EQUALS_IGNORE_CASE -> value.equals(rhs, ignoreCase = true)
            Op.NOT_EQUALS -> value != rhs
            Op.NOT_EQUALS_IGNORE_CASE -> !value.equals(rhs, ignoreCase = true)
            Op.STARTS_WITH -> value.startsWith(rhs)
            Op.STARTS_WITH_IGNORE_CASE -> value.startsWith(rhs, ignoreCase = true)
            Op.ENDS_WITH -> value.endsWith(rhs)
            Op.ENDS_WITH_IGNORE_CASE -> value.endsWith(rhs, ignoreCase = true)
            Op.CONTAINS -> value.contains(rhs)
            Op.CONTAINS_IGNORE_CASE -> value.contains(rhs, ignoreCase = true)
            Op.MATCHES_REGEX -> value.matches(Regex(rhs))
        }
    }
}

@Serializable
@UCloudApiInternal(InternalLevel.BETA)
data class IntComparison(
    override val property: String,
    val op: Op,
    val rhs: Long
) : Comparison<Long>() {
    enum class Op {
        EQUALS,
        NOT_EQUALS,

        LESS_THAN,
        LESS_THAN_EQUALS,

        GREATER_THAN,
        GREATER_THAN_EQUALS,
    }

    override fun canUse(value: Any?): Boolean = value is Long

    override fun evaluateTerm(value: Long): Boolean {
        return when (op) {
            Op.EQUALS -> value == rhs
            Op.NOT_EQUALS -> value != rhs
            Op.LESS_THAN -> value < rhs
            Op.LESS_THAN_EQUALS -> value <= rhs
            Op.GREATER_THAN -> value > rhs
            Op.GREATER_THAN_EQUALS -> value >= rhs
        }
    }
}

@Serializable
@UCloudApiInternal(InternalLevel.BETA)
data class FloatComparison(
    override val property: String,
    val op: Op,
    val rhs: Double
) : Comparison<Double>() {
    enum class Op {
        EQUALS,
        NOT_EQUALS,

        LESS_THAN,
        LESS_THAN_EQUALS,

        GREATER_THAN,
        GREATER_THAN_EQUALS,
    }

    override fun canUse(value: Any?): Boolean = value is Double

    override fun evaluateTerm(value: Double): Boolean {
        return when (op) {
            Op.EQUALS -> (value - rhs).absoluteValue <= 0.000001
            Op.NOT_EQUALS -> !((value - rhs).absoluteValue <= 0.000001)
            Op.LESS_THAN -> value < rhs
            Op.LESS_THAN_EQUALS -> value <= rhs
            Op.GREATER_THAN -> value > rhs
            Op.GREATER_THAN_EQUALS -> value >= rhs
        }
    }
}

@Serializable
data class BooleanComparison(
    override val property: String,
    val comparisonValue: Boolean
) : Comparison<Boolean>() {
    override fun evaluateTerm(value: Boolean): Boolean {
        return value == comparisonValue
    }

    override fun canUse(value: Any?): Boolean = value is Boolean
}

@Serializable
@UCloudApiInternal(InternalLevel.BETA)
sealed class Fault {
    abstract val system: FaultSystem

    object Rpc {
        @Serializable
        data class Delay(val duration: Long) : Fault() {
            override val system = FaultSystem.Rpc
            init {
                require(duration >= 0)
            }
        }

        @Serializable
        data class StaticResponse(
            val statusCode: Int,
            val headers: List<Header>,
            val payload: String,
            val payloadIsBase64: Boolean = false,
            val responseTime: Long = 10,
        ) : Fault() {
            override val system = FaultSystem.Rpc
            init {
                require(statusCode in 100..599)
                require(responseTime >= 0)
            }
        }

        @Serializable
        data class Header(val name: String, val value: String) {
            init {
                checkSingleLine(::name, name)
                checkSingleLine(::value, value)
            }
        }

        @Serializable
        data class Tls(val type: TlsType) : Fault() {
            override val system = FaultSystem.Rpc
        }

        enum class TlsType {
            EXPIRED_CERT,
            UNTRUSTED_CERT,
            TLS_ERROR
        }

        @Serializable
        data class ConnectionFailure(
            val timeToFailure: Long = 1000,
        ) : Fault() {
            override val system = FaultSystem.Rpc
            init {
                require(timeToFailure >= 0)
            }
        }

        @Serializable
        data class ProxyFailure(
            val timeToFailure: Long = 1000,
            val responseCode: Int
        ) : Fault() {
            override val system = FaultSystem.Rpc
            init {
                require(timeToFailure >= 0)
                require(responseCode in 100..599)
            }
        }
    }

    object SqlDatabase {
        @Serializable
        data class Delay(val duration: Long) : Fault() {
            override val system = FaultSystem.SqlDatabase
        }

        @Serializable
        data class Error(
            val errorType: String,
            val errorMessage: String,
        ) : Fault() {
            override val system = FaultSystem.SqlDatabase
        }

        @Serializable
        data class ConnectionFailure(
            val timeToFailure: Long = 1000,
        ) : Fault() {
            override val system = FaultSystem.SqlDatabase
        }
    }

    object DistributedLocks {
        @Serializable
        data class Delay(val duration: Long) : Fault() {
            override val system = FaultSystem.DistributedLocks
        }

        @Serializable
        data class Error(
            val errorType: String,
            val errorMessage: String,
        ) : Fault() {
            override val system = FaultSystem.DistributedLocks
        }
    }

    object EventStream {
        @Serializable
        data class Delay(val duration: Long) : Fault() {
            override val system = FaultSystem.EventStream
        }

        @Serializable
        data class DoubleReceive(val timeBetweenReceives: Long) : Fault() {
            override val system = FaultSystem.EventStream
        }

        @Serializable
        data class FailureToSend(
            val errorType: String,
            val errorMessage: String,
        ) : Fault() {
            override val system = FaultSystem.EventStream
        }

        @Serializable
        data class FailureToReceive(
            val errorType: String,
            val errorMessage: String,
        ) : Fault() {
            override val system = FaultSystem.EventStream
        }
    }

    object Kubernetes {
        @Serializable
        data class Delay(val duration: Long) : Fault() {
            override val system = FaultSystem.Kubernetes
        }

        @Serializable
        data class Error(
            val errorType: String,
            val errorMessage: String,
        ) : Fault() {
            override val system = FaultSystem.Kubernetes
        }

        @Serializable
        data class ConnectionFailure(
            val timeToFailure: Long = 1000,
        ) : Fault() {
            override val system = FaultSystem.Kubernetes
        }

        @Serializable
        data class ModifyRealResource(
            val updates: List<Update>
        ) : Fault() {
            override val system = FaultSystem.Kubernetes
        }

        @Serializable
        sealed class Update {
            @Serializable
            data class SetField(val path: String, val newValue: String) : Update()
        }
    }

    object FileSystem {
        @Serializable
        data class Delay(val duration: Long) : Fault() {
            override val system = FaultSystem.FileSystem
        }

        @Serializable
        data class FailureToWrite(
            val errorType: String,
            val errorMessage: String,
        ) : Fault() {
            override val system = FaultSystem.FileSystem
        }

        @Serializable
        data class FailureToRead(
            val errorType: String,
            val errorMessage: String,
        ) : Fault() {
            override val system = FaultSystem.FileSystem
        }
    }

    object Slurm {
        @Serializable
        data class Delay(val duration: Long) : Fault() {
            override val system = FaultSystem.Slurm
        }

        @Serializable
        data class Error(
            val errorType: String,
            val errorMessage: String,
        ) : Fault() {
            override val system = FaultSystem.Slurm
        }

        @Serializable
        data class ConnectionFailure(
            val timeToFailure: Long = 1000,
        ) : Fault() {
            override val system = FaultSystem.Slurm
        }
    }
}

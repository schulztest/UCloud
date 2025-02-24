package dk.sdu.cloud.debug

import dk.sdu.cloud.SecurityPrincipal
import dk.sdu.cloud.calls.AttributeKey
import dk.sdu.cloud.calls.CallDescription
import dk.sdu.cloud.calls.client.*
import dk.sdu.cloud.defaultMapper
import dk.sdu.cloud.service.Time
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.random.nextULong

private val uniquePrefix = "${Random.nextULong().toString(16)}-${Random.nextULong().toString(16)}"
private val logIdGenerator = atomicInt(0)

@Serializable
class DebugContext private constructor(val id: String, val parent: String?) {
    val type: String = "job"

    companion object {
        suspend fun create(): DebugContext {
            return DebugContext(uniquePrefix + "-" + logIdGenerator.getAndIncrement(), parentContextId())
        }

        fun createWithParent(parent: String): DebugContext {
            return DebugContext(uniquePrefix + "-" + logIdGenerator.getAndIncrement(), parent)
        }
    }
}

@Serializable
sealed class DebugMessage {
    abstract val context: DebugContext
    abstract val timestamp: Long
    abstract val principal: SecurityPrincipal?
    abstract val importance: MessageImportance
    abstract val messageType: MessageType
    abstract val id: Int

    @SerialName("client_request")
    @Serializable
    data class ClientRequest(
        override val context: DebugContext,
        override val timestamp: Long,
        override val principal: SecurityPrincipal?,
        override val importance: MessageImportance,
        val call: String?,
        val payload: JsonElement?,
        val resolvedHost: String,
    ) : DebugMessage() {
        override val messageType = MessageType.CLIENT
        override val id = idGenerator.getAndIncrement()
    }

    @SerialName("client_response")
    @Serializable
    data class ClientResponse(
        override val context: DebugContext,
        override val timestamp: Long,
        override val principal: SecurityPrincipal?,
        override val importance: MessageImportance,
        val call: String?,
        val payload: JsonElement?,
        val response: JsonElement?,
        val responseCode: Int,
        val responseTime: Long,
    ) : DebugMessage() {
        override val messageType = MessageType.CLIENT
        override val id = idGenerator.getAndIncrement()
    }

    @SerialName("server_request")
    @Serializable
    data class ServerRequest(
        override val context: DebugContext,
        override val timestamp: Long,
        override val principal: SecurityPrincipal?,
        override val importance: MessageImportance,
        val call: String?,
        val payload: JsonElement?,
    ) : DebugMessage() {
        override val messageType = MessageType.SERVER
        override val id = idGenerator.getAndIncrement()
    }

    @SerialName("server_response")
    @Serializable
    data class ServerResponse(
        override val context: DebugContext,
        override val timestamp: Long,
        override val principal: SecurityPrincipal?,
        override val importance: MessageImportance,
        val call: String?,
        val response: JsonElement?,
        val responseCode: Int,
        val responseTime: Long,
    ) : DebugMessage() {
        override val messageType = MessageType.SERVER
        override val id = idGenerator.getAndIncrement()
    }

    @SerialName("database_connection")
    @Serializable
    data class DatabaseConnection(
        override val context: DebugContext,
        val isOpen: Boolean,
        override val timestamp: Long = Time.now(),
        override val principal: SecurityPrincipal? = null,
        override val importance: MessageImportance = MessageImportance.TELL_ME_EVERYTHING,
    ) : DebugMessage() {
        override val messageType = MessageType.DATABASE
        override val id = idGenerator.getAndIncrement()
    }

    enum class DBTransactionEvent {
        OPEN,
        COMMIT,
        ROLLBACK
    }

    @SerialName("database_transaction")
    @Serializable
    data class DatabaseTransaction(
        override val context: DebugContext,
        val event: DBTransactionEvent,
        override val timestamp: Long = Time.now(),
        override val principal: SecurityPrincipal? = null,
        override val importance: MessageImportance = MessageImportance.TELL_ME_EVERYTHING,
    ) : DebugMessage() {
        override val messageType = MessageType.DATABASE
        override val id = idGenerator.getAndIncrement()
    }

    @SerialName("database_query")
    @Serializable
    data class DatabaseQuery(
        override val context: DebugContext,
        val query: String,
        val parameters: JsonObject,
        override val importance: MessageImportance = MessageImportance.IMPLEMENTATION_DETAIL,
        override val timestamp: Long = Time.now(),
        override val principal: SecurityPrincipal? = null,
    ) : DebugMessage() {
        override val messageType = MessageType.DATABASE
        override val id = idGenerator.getAndIncrement()
    }

    @SerialName("database_response")
    @Serializable
    data class DatabaseResponse(
        override val context: DebugContext,
        val responseTime: Long,
        val query: String,
        val parameters: JsonObject,
        override val importance: MessageImportance = MessageImportance.IMPLEMENTATION_DETAIL,
        override val timestamp: Long = Time.now(),
        override val principal: SecurityPrincipal? = null,
    ) : DebugMessage() {
        override val messageType = MessageType.DATABASE
        override val id = idGenerator.getAndIncrement()
    }

    @SerialName("log")
    @Serializable
    data class Log(
        override val context: DebugContext,
        val message: String,
        val extras: JsonObject? = null,
        override val importance: MessageImportance = MessageImportance.IMPLEMENTATION_DETAIL,
        override val timestamp: Long = Time.now(),
        override val principal: SecurityPrincipal? = null,
    ) : DebugMessage() {
        override val messageType = MessageType.LOG
        override val id = idGenerator.getAndIncrement()
    }

    companion object {
        val idGenerator = atomicInt(0)
    }
}

enum class MessageType {
    SERVER,
    DATABASE,
    CLIENT,
    LOG
}

enum class MessageImportance {
    /**
     * You (the developer/operator) only want to see this message if something is badly misbehaving, and you are
     * desperate for clues. It should primarily contain the insignificant details.
     */
    TELL_ME_EVERYTHING,

    /**
     * You (the developer/operator) want to see this message if something is mildly misbehaving. It should contain the
     * most important implementation details.
     */
    IMPLEMENTATION_DETAIL,

    /**
     * Indicates that an ordinary event has occurred. Most RPCs, which aren't chatty, fall into this category.
     */
    THIS_IS_NORMAL,

    /**
     * Indicates that something might be wrong, but not for certain. A developer/operator might want to see this, but
     * it is not critical.
     */
    THIS_IS_ODD,

    /**
     * A clear message that something is wrong. A developer/operator want to see this as soon as possible, but it can
     * probably wait until the next morning.
     */
    THIS_IS_WRONG,

    /**
     * This should never happen. This event needs to be investigated immediately.
     *
     * Only pick this category if you feel comfortable waking up your co-workers in the middle of the night to tell
     * them about this.
     */
    THIS_IS_DANGEROUS
}

class DebugCoroutineContext(
    val context: DebugContext,
) : AbstractCoroutineContextElement(DebugCoroutineContext) {
    companion object Key : CoroutineContext.Key<DebugCoroutineContext>
}

suspend inline fun parentContextId(): String? {
    val ctx = coroutineContext
    return ctx[DebugCoroutineContext]?.context?.id
}

suspend inline fun currentContext(): DebugContext? {
    val ctx = coroutineContext
    return ctx[DebugCoroutineContext]?.context
}

suspend fun DebugSystem.log(message: String, structured: JsonObject?, level: MessageImportance) {
    sendMessage(
        DebugMessage.Log(
            DebugContext.create(),
            message,
            structured,
            level
        )
    )
}

suspend fun DebugSystem.everything(message: String, structured: JsonObject? = null) {
    log(message, structured, MessageImportance.TELL_ME_EVERYTHING)
}

suspend fun DebugSystem.detail(message: String, structured: JsonObject? = null) {
    log(message, structured, MessageImportance.IMPLEMENTATION_DETAIL)
}

suspend fun DebugSystem.normal(message: String, structured: JsonObject? = null) {
    log(message, structured, MessageImportance.THIS_IS_NORMAL)
}

suspend fun DebugSystem.odd(message: String, structured: JsonObject? = null) {
    log(message, structured, MessageImportance.THIS_IS_ODD)
}

suspend fun DebugSystem.wrong(message: String, structured: JsonObject? = null) {
    log(message, structured, MessageImportance.THIS_IS_WRONG)
}

suspend fun DebugSystem.dangerous(message: String, structured: JsonObject? = null) {
    log(message, structured, MessageImportance.THIS_IS_DANGEROUS)
}

suspend fun <R> DebugSystem?.logD(
    message: String,
    serializer: KSerializer<R>,
    structured: R,
    level: MessageImportance,
    context: DebugContext? = null
) {
    if (this == null) return

    val encoded = defaultMapper.encodeToJsonElement(serializer, structured)
    val wrapped = if (encoded !is JsonObject) {
        JsonObject(mapOf("wrapper" to encoded))
    } else {
        encoded
    }

    sendMessage(
        DebugMessage.Log(
            context ?: DebugContext.create(),
            message,
            wrapped,
            level
        )
    )
}

suspend fun <R> DebugSystem?.everythingD(message: String, serializer: KSerializer<R>, structured: R, context: DebugContext? = null) {
    logD(message, serializer, structured, MessageImportance.TELL_ME_EVERYTHING, context)
}

suspend fun <R> DebugSystem?.detailD(message: String, serializer: KSerializer<R>, structured: R, context: DebugContext? = null) {
    logD(message, serializer, structured, MessageImportance.IMPLEMENTATION_DETAIL, context)
}

suspend fun <R> DebugSystem?.normalD(message: String, serializer: KSerializer<R>, structured: R, context: DebugContext? = null) {
    logD(message, serializer, structured, MessageImportance.THIS_IS_NORMAL, context)
}

suspend fun <R> DebugSystem?.oddD(message: String, serializer: KSerializer<R>, structured: R, context: DebugContext? = null) {
    logD(message, serializer, structured, MessageImportance.THIS_IS_ODD, context)
}

suspend fun <R> DebugSystem?.dangerousD(message: String, serializer: KSerializer<R>, structured: R, context: DebugContext? = null) {
    logD(message, serializer, structured, MessageImportance.THIS_IS_DANGEROUS, context)
}

suspend fun <R> DebugSystem?.wrongD(message: String, serializer: KSerializer<R>, structured: R) {
    logD(message, serializer, structured, MessageImportance.THIS_IS_WRONG)
}

class DebugSystemLogContext(
    val name: String,
    val debug: DebugSystem?,
    val debugContext: DebugContext,
) {
    suspend fun logExit(
        message: String,
        data: JsonObject? = null,
        level: MessageImportance = MessageImportance.THIS_IS_NORMAL
    ) {
        if (debug == null) return
        debug.sendMessage(
            DebugMessage.Log(
                debugContext,
                if (message.isBlank()) name else "$name: $message",
                data,
                level
            )
        )
    }
}

suspend fun <R> DebugSystem?.enterContext(
    name: String,
    block: suspend DebugSystemLogContext.() -> R
): R {
    val debug = this

    val debugContext = DebugContext.create()
    val logContext = DebugSystemLogContext(name, debug, debugContext)
    if (debug == null) return block(logContext)

    return withContext(DebugCoroutineContext(debugContext)) {
        debug.sendMessage(
            DebugMessage.Log(
                debugContext,
                "$name: Start",
                null,
                MessageImportance.IMPLEMENTATION_DETAIL
            )
        )
        block(logContext)
    }
}

// TODO(Dan): Remove the indirection, there is no need for this to be an interface. CommonDebugSystem is going to be
//  the only implementation we have.
interface DebugSystem {
    suspend fun <T> transformPayload(serializer: KSerializer<T>, payload: T?): JsonElement {
        return if (payload == null) JsonNull
        else defaultMapper.encodeToJsonElement(serializer, payload)
    }

    suspend fun sendMessage(message: DebugMessage)
}

fun DebugSystem.installCommon(client: RpcClient) {
    val key = AttributeKey<String>("debug-id")
    val payloadKey = AttributeKey<JsonElement>("debug-payload")
    client.attachFilter(object : OutgoingCallFilter.BeforeCall() {
        override fun canUseContext(ctx: OutgoingCall): Boolean = true

        override suspend fun run(context: OutgoingCall, callDescription: CallDescription<*, *, *>, request: Any?) {
            @Suppress("UNCHECKED_CAST") val call = callDescription as CallDescription<Any, Any, Any>
            val debugContext = DebugContext.create()
            val id = debugContext.id
            val debugPayload = transformPayload(call.requestType, request)

            context.attributes[key] = id
            context.attributes[payloadKey] = debugPayload

            sendMessage(
                DebugMessage.ClientRequest(
                    debugContext,
                    Time.now(),
                    null,
                    MessageImportance.IMPLEMENTATION_DETAIL,
                    callDescription.fullName,
                    debugPayload,
                    context.attributes.outgoingTargetHostOrNull.toString(),
                )
            )
        }
    })

    client.attachFilter(object : OutgoingCallFilter.AfterCall() {
        override fun canUseContext(ctx: OutgoingCall): Boolean = true

        override suspend fun run(
            context: OutgoingCall,
            callDescription: CallDescription<*, *, *>,
            response: IngoingCallResponse<*, *>,
            responseTimeMs: Long
        ) {
            val id = context.attributes[key]
            @Suppress("UNCHECKED_CAST") val call = callDescription as CallDescription<Any, Any, Any>
            sendMessage(
                DebugMessage.ClientResponse(
                    DebugContext.createWithParent(id),
                    Time.now(),
                    null,
                    when {
                        responseTimeMs >= 300 || response.statusCode.value in 500..599 ->
                            MessageImportance.THIS_IS_WRONG

                        responseTimeMs >= 150 || response.statusCode.value in 400..499 ->
                            MessageImportance.THIS_IS_ODD

                        else ->
                            MessageImportance.THIS_IS_NORMAL
                    },
                    callDescription.fullName,
                    when (response) {
                        is IngoingCallResponse.Error -> {
                            if (response.error == null) {
                                JsonNull
                            } else {
                                defaultMapper.encodeToJsonElement(call.errorType, response.error)
                            }
                        }
                        is IngoingCallResponse.Ok -> {
                            defaultMapper.encodeToJsonElement(call.successType, response.result)
                        }
                    },
                    context.attributes.getOrNull(payloadKey) ?: JsonNull,
                    response.statusCode.value,
                    responseTimeMs,
                )
            )
        }
    })
}

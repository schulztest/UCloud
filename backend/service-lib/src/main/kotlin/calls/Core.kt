package dk.sdu.cloud.calls

import dk.sdu.cloud.freeze
import dk.sdu.cloud.isFrozen
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlin.reflect.*

data class AttributeKey<V : Any>(val key: String)

class AttributeContainer {
    private val internalContainer = HashMap<AttributeKey<*>, Any>()

    operator fun <V : Any> set(key: AttributeKey<V>, value: V) {
        internalContainer[key] = value
    }

    fun <V : Any> setOrDelete(key: AttributeKey<V>, value: V?) {
        if (value == null) remove(key)
        else set(key, value)
    }

    fun <V : Any> remove(key: AttributeKey<V>) {
        internalContainer.remove(key)
    }

    operator fun <V : Any> get(key: AttributeKey<V>): V {
        val result = internalContainer[key] ?: throw IllegalArgumentException("No such key '${key.key}'!")
        @Suppress("UNCHECKED_CAST")
        return result as V
    }

    fun <V : Any> getOrNull(key: AttributeKey<V>): V? {
        return try {
            get(key)
        } catch (ex: IllegalArgumentException) {
            return null
        }
    }

    override fun toString(): String = internalContainer.toString()
}

class CallDescription<Request : Any, Success : Any, Error : Any> internal constructor(
    val name: String,
    val namespace: String,
    val attributes: AttributeContainer,
    val requestType: KSerializer<Request>,
    val successType: KSerializer<Success>,
    val errorType: KSerializer<Error>,
    val requestClass: KType?,
    val successClass: KType?,
    val errorClass: KType?,
    val containerRef: CallDescriptionContainer,
) {
    val fullName: String get() = "$namespace.$name"

    override fun toString(): String = "CallDescription($fullName)"
}

abstract class CallDescriptionContainer(val namespace: String) {
    val attributes = AttributeContainer()
    private val _callContainer = ArrayList<CallDescription<*, *, *>>()
    private val callContainerLock = Mutex()
    val callContainer: List<CallDescription<*, *, *>>
        get() = _callContainer

    open fun documentation() {
        // Empty by default
    }

    fun <Request : Any, Success : Any, Error : Any> call(
        name: String,
        handler: (CallDescription<Request, Success, Error>.() -> Unit),
        requestType: KSerializer<Request>,
        successType: KSerializer<Success>,
        errorType: KSerializer<Error>,
        requestClass: KType?,
        successClass: KType?,
        errorClass: KType?,
    ): CallDescription<Request, Success, Error> {
        val callDescription = CallDescription(
            name,
            namespace,
            AttributeContainer(),
            requestType,
            successType,
            errorType,
            requestClass,
            successClass,
            errorClass,
            this
        )

        callDescription.handler()

        // NOTE(Dan): Getters will dynamically create new calls. They are not supposed to do this. It really causes
        // problems on the native side where this is not allowed. For this reason, we won't add the description if
        // the container has already been frozen.
        runBlocking {
            callContainerLock.withLock {
                if (!this.isFrozen()) {
                    if (!_callContainer.any { it.fullName == callDescription.fullName }) {
                        _callContainer.add(callDescription)
                    }
                    onBuildHandlers.forEach { it(callDescription) }
                }
            }
        }
        return callDescription
    }

    companion object {
        private val onBuildHandlers = ArrayList<OnCallDescriptionBuildHandler>()

        fun onBuild(handler: OnCallDescriptionBuildHandler) {
            onBuildHandlers.add(handler)
        }
    }
}

inline fun <reified Request : Any, reified Success : Any, reified Error : Any> CallDescriptionContainer.call(
    name: String,
    requestType: KSerializer<Request>,
    successType: KSerializer<Success>,
    errorType: KSerializer<Error>,
    noinline handler: (CallDescription<Request, Success, Error>.() -> Unit),
): CallDescription<Request, Success, Error> {
    return call(
        name,
        handler,
        requestType,
        successType,
        errorType,
        typeOfIfPossible<Request>(),
        typeOfIfPossible<Success>(),
        typeOfIfPossible<Error>(),
    )
}

inline fun <reified T> typeOfIfPossible(): KType? {
    return try {
        typeOf<T>()
    } catch (ex: Throwable) {
        null
    }
}

typealias OnCallDescriptionBuildHandler = (CallDescription<*, *, *>) -> Unit

private val serializerLookupTableKey = AttributeKey<Map<KType?, KSerializer<*>>>("serializer-lookup-table").freeze()
var CallDescriptionContainer.serializerLookupTable: Map<KType?, KSerializer<*>>
    get() = attributes[serializerLookupTableKey]
    set(value) {
        attributes[serializerLookupTableKey] = value
    }

inline fun <reified T> serializerEntry(serializer: KSerializer<T>): Pair<KType?, KSerializer<T>> {
    return Pair(typeOfIfPossible<T>(), serializer)
}

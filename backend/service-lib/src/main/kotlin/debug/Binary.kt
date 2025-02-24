package dk.sdu.cloud.debug

import dk.sdu.cloud.calls.HttpStatusCode
import dk.sdu.cloud.defaultMapper
import dk.sdu.cloud.service.Time
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.RandomAccessFile
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.reflect.KProperty
import kotlin.system.exitProcess
import kotlin.time.measureTime

@Suppress("unused")
abstract class BinaryFrameSchema(parent: BinaryFrameSchema? = null) {
    var size: Int = (parent?.size ?: 0)

    protected fun int1() = BinaryFrameField.Int1(size).also { size += 1 }
    protected fun int2() = BinaryFrameField.Int2(size).also { size += 2 }
    protected fun int4() = BinaryFrameField.Int4(size).also { size += 4 }
    protected fun int8() = BinaryFrameField.Int8(size).also { size += 8 }
    protected fun float4() = BinaryFrameField.Float4(size).also { size += 4 }
    protected fun float8() = BinaryFrameField.Float8(size).also { size += 8 }
    protected fun bool() = BinaryFrameField.Bool(size).also { size += 1 }
    protected fun bytes(size: Short) = BinaryFrameField.Bytes(this.size, size).also { this.size += size.toInt() + 2 }
    protected fun text(maxSize: Int = 128) = BinaryFrameField.Text(this.size, maxSize).also { this.size += maxSize }
    protected inline fun <reified E : Enum<E>> enum() = BinaryFrameField.Enumeration(this.size, enumValues<E>())
        .also { this.size += 1 }
}

abstract class BinaryFrame(val buf: ByteBuffer, var offset: Int = 0) {
    abstract val schema: BinaryFrameSchema
}

sealed class BinaryFrameField(val offset: Int) {
    class Int1(offset: Int) : BinaryFrameField(offset) {
        operator fun getValue(thisRef: BinaryFrame, property: KProperty<*>): Byte {
            return thisRef.buf.get(thisRef.offset + offset)
        }

        operator fun setValue(thisRef: BinaryFrame, property: KProperty<*>, value: Byte) {
            thisRef.buf.put(thisRef.offset + offset, value)
        }
    }

    class Int2(offset: Int) : BinaryFrameField(offset) {
        operator fun getValue(thisRef: BinaryFrame, property: KProperty<*>): Short {
            return thisRef.buf.getShort(thisRef.offset + offset)
        }

        operator fun setValue(thisRef: BinaryFrame, property: KProperty<*>, value: Short) {
            thisRef.buf.putShort(thisRef.offset + offset, value)
        }
    }

    class Int4(offset: Int) : BinaryFrameField(offset) {
        operator fun getValue(thisRef: BinaryFrame, property: KProperty<*>): Int {
            return thisRef.buf.getInt(thisRef.offset + offset)
        }

        operator fun setValue(thisRef: BinaryFrame, property: KProperty<*>, value: Int) {
            thisRef.buf.putInt(thisRef.offset + offset, value)
        }
    }

    class Int8(offset: Int) : BinaryFrameField(offset) {
        operator fun getValue(thisRef: BinaryFrame, property: KProperty<*>): Long {
            return thisRef.buf.getLong(thisRef.offset + offset)
        }

        operator fun setValue(thisRef: BinaryFrame, property: KProperty<*>, value: Long) {
            thisRef.buf.putLong(thisRef.offset + offset, value)
        }
    }

    class Float4(offset: Int) : BinaryFrameField(offset) {
        operator fun getValue(thisRef: BinaryFrame, property: KProperty<*>): Float {
            return thisRef.buf.getFloat(thisRef.offset + offset)
        }

        operator fun setValue(thisRef: BinaryFrame, property: KProperty<*>, value: Float) {
            thisRef.buf.putFloat(thisRef.offset + offset, value)
        }
    }

    class Float8(offset: Int) : BinaryFrameField(offset) {
        operator fun getValue(thisRef: BinaryFrame, property: KProperty<*>): Double {
            return thisRef.buf.getDouble(thisRef.offset + offset)
        }

        operator fun setValue(thisRef: BinaryFrame, property: KProperty<*>, value: Double) {
            thisRef.buf.putDouble(thisRef.offset + offset, value)
        }
    }

    class Bool(offset: Int) : BinaryFrameField(offset) {
        operator fun getValue(thisRef: BinaryFrame, property: KProperty<*>): Boolean {
            return thisRef.buf.get(thisRef.offset + offset) != 0.toByte()
        }

        operator fun setValue(thisRef: BinaryFrame, property: KProperty<*>, value: Boolean) {
            thisRef.buf.put(thisRef.offset + offset, if (value) 1 else 0)
        }
    }

    class Bytes(offset: Int, val size: Short) : BinaryFrameField(offset) {
        operator fun getValue(thisRef: BinaryFrame, property: KProperty<*>): ByteArray {
            val length = thisRef.buf.getShort(thisRef.offset + offset)
            val output = ByteArray(length.toInt())
            thisRef.buf.get(thisRef.offset + offset + 2, output, 0, length.toInt())
            return output
        }

        operator fun setValue(thisRef: BinaryFrame, property: KProperty<*>, value: ByteArray) {
            val length = if (value.size >= size) size else value.size.toShort()
            thisRef.buf.putShort(thisRef.offset + offset, length)
            thisRef.buf.put(thisRef.offset + offset + 2, value, 0, length.toInt())
        }
    }

    class Text(offset: Int, val maxSize: Int = 128) : BinaryFrameField(offset) {
        private val delegate = Bytes(offset, (maxSize - 2).toShort())

        operator fun getValue(thisRef: BinaryFrame, property: KProperty<*>): LargeText {
            return LargeText(delegate.getValue(thisRef, property))
        }

        operator fun setValue(thisRef: BinaryFrame, property: KProperty<*>, value: LargeText) {
            delegate.setValue(thisRef, property, value.value)
        }
    }

    class Enumeration<E : Enum<E>>(offset: Int, val enumeration: Array<E>) : BinaryFrameField(offset) {
        operator fun getValue(thisRef: BinaryFrame, property: KProperty<*>): E {
            val ordinal = thisRef.buf.get(thisRef.offset + offset).toInt() and 0xFF
            return enumeration[ordinal]
        }

        operator fun setValue(thisRef: BinaryFrame, property: KProperty<*>, value: E) {
            thisRef.buf.put(thisRef.offset + offset, value.ordinal.toByte())
        }
    }
}

sealed class BinaryDebugMessage<Self : BinaryDebugMessage<Self>>(
    type: Byte,
    buf: ByteBuffer,
    offset: Int = 0
) : BinaryFrame(buf, offset) {
    var type by Schema.type
    var ctxGeneration by Schema.ctxGeneration
    var ctxParent by Schema.ctxParent
    var ctxId by Schema.ctxId
    var timestamp by Schema.timestamp
    var importance by Schema.importance
    var id by Schema.id

    init {
        if (buf.capacity() != 0 && !buf.isReadOnly) {
            this.type = type
        }
    }

    private var rsv1 by Schema.rsv1
    private var rsv2 by Schema.rsv2
    private var rsv3 by Schema.rsv3

    override fun toString(): String {
        return "(ctxGeneration=$ctxGeneration, ctxParent=$ctxParent, ctxId=$ctxId, timestamp=$timestamp, importance=$importance, id=$id)"
    }

    companion object Schema : BinaryFrameSchema() {
        val type = int1()
        val ctxGeneration = int8()
        val ctxParent = int4()
        val ctxId = int4()
        val timestamp = int8()
        val importance = enum<MessageImportance>()
        val id = int4()

        val rsv1 = int1()
        val rsv2 = int4()
        val rsv3 = int4()
    }

    abstract fun create(buf: ByteBuffer, offset: Int): Self

    class ClientRequest(buf: ByteBuffer, offset: Int = 0) : BinaryDebugMessage<ClientRequest>(1.toByte(), buf, offset) {
        var call by Schema.call
        var payload by Schema.payload

        override val schema = Schema

        override fun create(buf: ByteBuffer, offset: Int): ClientRequest = ClientRequest(buf, offset)

        companion object Schema : BinaryFrameSchema(BinaryDebugMessage) {
            val call = text(64)
            val payload = text(64)
        }
    }

    class ClientResponse(buf: ByteBuffer, offset: Int = 0) :
        BinaryDebugMessage<ClientResponse>(2.toByte(), buf, offset) {
        var responseCode by Schema.responseCode
        var responseTime by Schema.responseTime
        var call by Schema.call
        var response by Schema.response

        override val schema = Schema
        override fun create(buf: ByteBuffer, offset: Int): ClientResponse = ClientResponse(buf, offset)

        companion object Schema : BinaryFrameSchema(BinaryDebugMessage) {
            val responseCode = int1()
            val responseTime = int4()
            val call = text(64)
            val response = text(64)
        }
    }

    class ServerRequest(buf: ByteBuffer, offset: Int = 0) : BinaryDebugMessage<ServerRequest>(3.toByte(), buf, offset) {
        var call by Schema.call
        var payload by Schema.payload

        override val schema = Schema
        override fun create(buf: ByteBuffer, offset: Int): ServerRequest = ServerRequest(buf, offset)

        companion object Schema : BinaryFrameSchema(BinaryDebugMessage) {
            val call = text(64)
            val payload = text(64)
        }
    }

    class ServerResponse(buf: ByteBuffer, offset: Int = 0) :
        BinaryDebugMessage<ServerResponse>(4.toByte(), buf, offset) {
        var responseCode by Schema.responseCode
        var responseTime by Schema.responseTime
        var call by Schema.call
        var response by Schema.response

        override val schema = Schema
        override fun create(buf: ByteBuffer, offset: Int): ServerResponse = ServerResponse(buf, offset)

        companion object Schema : BinaryFrameSchema(BinaryDebugMessage) {
            val responseCode = int1()
            val responseTime = int4()
            val call = text(64)
            val response = text(64)
        }
    }

    class DatabaseConnection(buf: ByteBuffer, offset: Int = 0) :
        BinaryDebugMessage<DatabaseConnection>(5.toByte(), buf, offset) {
        var isOpen by Schema.isOpen

        override val schema = Schema
        override fun create(buf: ByteBuffer, offset: Int): DatabaseConnection = DatabaseConnection(buf, offset)

        companion object Schema : BinaryFrameSchema(BinaryDebugMessage) {
            val isOpen = bool()
        }
    }

    class DatabaseTransaction(buf: ByteBuffer, offset: Int = 0) :
        BinaryDebugMessage<DatabaseTransaction>(6.toByte(), buf, offset) {
        var event by Schema.event

        override val schema = Schema
        override fun create(buf: ByteBuffer, offset: Int): DatabaseTransaction = DatabaseTransaction(buf, offset)

        companion object Schema : BinaryFrameSchema(BinaryDebugMessage) {
            val event = enum<DebugMessage.DBTransactionEvent>()
        }
    }

    class DatabaseQuery(buf: ByteBuffer, offset: Int = 0) : BinaryDebugMessage<DatabaseQuery>(7.toByte(), buf, offset) {
        var parameters by Schema.parameters
        var query by Schema.query

        override val schema = Schema
        override fun create(buf: ByteBuffer, offset: Int): DatabaseQuery = DatabaseQuery(buf, offset)

        companion object Schema : BinaryFrameSchema(BinaryDebugMessage) {
            val parameters = text(64)
            val query = text(128)
        }
    }

    class DatabaseResponse(buf: ByteBuffer, offset: Int = 0) :
        BinaryDebugMessage<DatabaseResponse>(8.toByte(), buf, offset) {
        var responseTime by Schema.responseTime

        override val schema = Schema
        override fun create(buf: ByteBuffer, offset: Int): DatabaseResponse = DatabaseResponse(buf, offset)

        companion object Schema : BinaryFrameSchema(BinaryDebugMessage) {
            val responseTime = int4()
        }
    }

    class Log(buf: ByteBuffer, offset: Int = 0) : BinaryDebugMessage<Log>(9.toByte(), buf, offset) {
        var message by Schema.message
        var extra by Schema.extra

        override val schema = Schema
        override fun create(buf: ByteBuffer, offset: Int): Log = Log(buf, offset)

        override fun toString(): String = "Log($message, $extra, ${super.toString()})"

        companion object Schema : BinaryFrameSchema(BinaryDebugMessage) {
            val message = text(128)
            val extra = text(32)
        }
    }
}

val oomCount = AtomicInteger(0)

class BinaryFrameReader(val file: File) {
    private val logChannel = RandomAccessFile(file, "r").channel
    private val buf = logChannel.map(FileChannel.MapMode.READ_ONLY, 0, logChannel.size())

    private val blobChannel = RandomAccessFile(file.absolutePath.replace(".log", ".blob"), "r").channel
    private val blobBuf = blobChannel.map(FileChannel.MapMode.READ_ONLY, 0, blobChannel.size())

    fun retrieve(idx: Int): BinaryDebugMessage<*>? {
        val offset = idx * FRAME_SIZE
        if (offset >= buf.capacity()) return null
        val type = buf.get(offset)
        return when (type.toInt()) {
            1 -> BinaryDebugMessage.ClientRequest(buf, offset)
            2 -> BinaryDebugMessage.ClientResponse(buf, offset)
            3 -> BinaryDebugMessage.ServerRequest(buf, offset)
            4 -> BinaryDebugMessage.ServerResponse(buf, offset)
            5 -> BinaryDebugMessage.DatabaseConnection(buf, offset)
            6 -> BinaryDebugMessage.DatabaseTransaction(buf, offset)
            7 -> BinaryDebugMessage.DatabaseQuery(buf, offset)
            8 -> BinaryDebugMessage.DatabaseResponse(buf, offset)
            9 -> BinaryDebugMessage.Log(buf, offset)
            else -> null
        }
    }

    fun decodeText(largeText: LargeText): String {
        val decoded = largeText.value.decodeToString()
        if (decoded.startsWith(LargeText.OVERFLOW_PREFIX)) {
            val suffix = decoded.substringAfter(LargeText.OVERFLOW_PREFIX)
            val pos = suffix.toIntOrNull() ?: return "INVALID BLOB DETECTED"
            val size = blobBuf.getInt(pos)
            val buffer = ByteArray(size)
            blobBuf.get(pos + 4, buffer)
            return buffer.decodeToString()
        } else {
            return decoded
        }
    }

    fun close() {
        logChannel.close()
    }
}

const val FRAME_SIZE = 256
const val LOG_FILE_SIZE = 1024 * 1024 * 16L

class BinaryFrameAllocator(
    private val directory: File,
    private val generation: Long,
    val fileIndex: Int
) {
    private val channel = FileChannel.open(
        File(directory, "$generation-$fileIndex.log").toPath(),
        StandardOpenOption.CREATE,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
        StandardOpenOption.SPARSE
    )

    @PublishedApi
    internal val buf = channel.map(FileChannel.MapMode.READ_WRITE, 0, LOG_FILE_SIZE)

    @PublishedApi
    internal var ptr = 0

    private var lastFlushPtr = 0

    val frameIndex: Int
        get() = ptr / FRAME_SIZE

    fun <T : BinaryDebugMessage<T>> allocateOrNull(stub: T): T? {
        if (ptr + FRAME_SIZE >= buf.capacity()) {
            oomCount.incrementAndGet()
            return null
        }

        val result = stub.create(buf, ptr) as T
        ptr += FRAME_SIZE
        return result
    }

    fun flush() {
        if (lastFlushPtr != ptr) buf.force(lastFlushPtr, ptr - lastFlushPtr)
        lastFlushPtr = ptr
    }

    fun isFull(): Boolean {
        return ptr + FRAME_SIZE >= LOG_FILE_SIZE
    }

    fun close() {
        runCatching { channel.close() }
    }

    val clientRequest = BinaryDebugMessage.ClientRequest(ByteBuffer.allocate(0))
    val clientResponse = BinaryDebugMessage.ClientResponse(ByteBuffer.allocate(0))
    val serverRequest = BinaryDebugMessage.ServerRequest(ByteBuffer.allocate(0))
    val serverResponse = BinaryDebugMessage.ServerResponse(ByteBuffer.allocate(0))
    val databaseConnection = BinaryDebugMessage.DatabaseConnection(ByteBuffer.allocate(0))
    val databaseTransaction = BinaryDebugMessage.DatabaseTransaction(ByteBuffer.allocate(0))
    val databaseQuery = BinaryDebugMessage.DatabaseQuery(ByteBuffer.allocate(0))
    val databaseResponse = BinaryDebugMessage.DatabaseResponse(ByteBuffer.allocate(0))
    val log = BinaryDebugMessage.Log(ByteBuffer.allocate(0))

    init {
        listOf(
            clientRequest,
            clientResponse,
            serverRequest,
            serverResponse,
            databaseConnection,
            databaseTransaction,
            databaseQuery,
            databaseResponse,
            log
        ).forEach {
            check(it.schema.size <= FRAME_SIZE) { "${it::class} size exceeds frame (${it.schema.size})" }
        }
    }
}

class ContextDescriptorFile(
    val directory: File,
    val generation: Long,
    val fileIdx: Int,
) {
    private val trackedDescriptors = ArrayList<WeakReference<DebugContextDescriptor>>()

    private val channel = FileChannel.open(
        File(directory, "$generation-$fileIdx.ctx").toPath(),
        StandardOpenOption.CREATE,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
        StandardOpenOption.SPARSE
    )

    @PublishedApi
    internal val buf = channel.map(FileChannel.MapMode.READ_WRITE, 0, 1024 * 1024 * 16)

    private var ptr: Int = 4096

    fun findTrackedContext(context: Int): DebugContextDescriptor? {
        for (ref in trackedDescriptors) {
            val descriptor = ref.get()
            if (descriptor?.id == context) return descriptor
        }
        return null
    }

    fun next(descriptor: DebugContextDescriptor? = null): DebugContextDescriptor? {
        if (descriptor == null) return DebugContextDescriptor(buf, 4096)
        if (descriptor.offset + DebugContextDescriptor.size >= buf.capacity()) return null
        descriptor.offset += DebugContextDescriptor.size
        if (descriptor.id == 0) return null
        return descriptor
    }

    fun findContext(context: Int, after: DebugContextDescriptor? = null): DebugContextDescriptor? {
        var aligned = (max(4096, after?.offset ?: 0) + 1) / DebugContextDescriptor.size
        if (aligned <= (after?.offset ?: 0)) aligned += DebugContextDescriptor.size

        val descriptor = DebugContextDescriptor(buf, aligned)
        while (descriptor.offset + DebugContextDescriptor.size < buf.capacity()) {
            if (descriptor.id == context) return descriptor
            descriptor.offset += DebugContextDescriptor.size
        }
        return null
    }

    fun allocate(): DebugContextDescriptor? {
        if (ptr + DebugContextDescriptor.size >= buf.capacity()) return null
        val descriptor = DebugContextDescriptor(buf, ptr)
        trackedDescriptors.add(WeakReference(descriptor))
        ptr += DebugContextDescriptor.size
        return descriptor
    }

    fun flush() {
        buf.force(0, ptr)
    }

    fun attemptClose(): Boolean {
        for (descriptor in trackedDescriptors) {
            if (descriptor.get()?.isOpen == true) return false
        }
        channel.close()
        return true
    }
}

class BinaryDebugSystem(
    private val directory: String,
    private val serviceName: String,
) {
    private val lock = Mutex()

    private var buffer = BinaryFrameAllocator(File(directory), generation, fileIdAcc.getAndIncrement())
    private var blobs = BlobSystem(File(directory), generation, buffer.fileIndex)

    private val closeSignal = Channel<Unit>(Channel.CONFLATED)

    private val oldContextFiles = ArrayList<ContextDescriptorFile>()
    private val contextFileIdAcc = AtomicInteger(1)
    private val contextIdAcc = AtomicInteger(2)
    private var contextFile = createContextDescriptorFile()

    private fun createContextDescriptorFile(): ContextDescriptorFile =
        ContextDescriptorFile(File(directory), generation, contextFileIdAcc.getAndIncrement())

    fun text(text: String, field: BinaryFrameField.Text): LargeText {
        val encoded = text.encodeToByteArray()
        return if (encoded.size >= field.maxSize) {
            val id = blobs.storeBlob(encoded)
            LargeText((LargeText.OVERFLOW_PREFIX + id).encodeToByteArray())
        } else {
            return LargeText(encoded)
        }
    }

    suspend fun emit(fn: suspend BinaryFrameAllocator.() -> BinaryDebugMessage<*>?) {
        var success = false
        while (coroutineContext.isActive && !success) {
            var requestFlush: Boolean
            lock.withLock {
                val result = fn(buffer)
                success = result != null
                requestFlush = buffer.isFull()
            }
            if (requestFlush) closeSignal.send(Unit)
        }
    }

    private suspend fun allocateContext(): DebugContextDescriptor {
        val currentContext = debugContextOrNull()

        lock.withLock {
            val result = contextFile.allocate()
            if (result != null) {
                val parent = currentContext?.id ?: 1
                val id = contextIdAcc.getAndIncrement()
                result.parent = parent
                result.id = id
                if (parent != 1) {
                    var ctx = contextFile.findTrackedContext(parent)
                    if (ctx == null) {
                        ctx = oldContextFiles.firstNotNullOfOrNull { it.findTrackedContext(parent) }
                    }

                    ctx?.appendChild(id)
                }
                return result
            }

            if (!contextFile.attemptClose()) {
                oldContextFiles.add(contextFile)
            }

            contextFile = createContextDescriptorFile()
        }
        return allocateContext()
    }

    suspend fun <T> useContext(
        type: DebugContextType,
        initialName: String? = null,
        initialImportance: MessageImportance = MessageImportance.IMPLEMENTATION_DETAIL,
        block: suspend () -> T
    ) {
        val descriptor = allocateContext()
        return withContext(BinaryDebugCoroutineContext(descriptor)) {
            try {
                descriptor.type = type
                descriptor.importance = initialImportance
                descriptor.name = when {
                    initialName != null -> initialName
                    type == DebugContextType.BACKGROUND_TASK -> "Task"
                    type == DebugContextType.CLIENT_REQUEST -> "Client request"
                    type == DebugContextType.SERVER_REQUEST -> "Server request"
                    type == DebugContextType.OTHER -> "Other task"
                    else -> "Other task"
                }
                block()
            } finally {
                descriptor.isOpen = false
            }
        }
    }

    fun start(scope: CoroutineScope): Job {
        File(directory, "$generation.service").writeText(buildString {
            appendLine(serviceName)
            appendLine(generation)
        })

        return scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    select {
                        closeSignal.onReceive {
                            lock.withLock {
                                if (!buffer.isFull()) return@withLock
                                blobs.close()
                                buffer.close()

                                buffer = BinaryFrameAllocator(File(directory), generation, fileIdAcc.getAndIncrement())
                                blobs = BlobSystem(File(directory), generation, buffer.fileIndex)
                            }
                        }

                        onTimeout(500) {
                            lock.withLock {
                                buffer.flush()
                                contextFile.flush()

                                val iterator = oldContextFiles.iterator()
                                while (iterator.hasNext()) {
                                    val n = iterator.next()
                                    if (n.attemptClose()) {
                                        iterator.remove()
                                    }
                                }
                            }
                        }
                    }
                }

                lock.withLock {
                    buffer.close()
                }
            } finally {
                runCatching { buffer.close() }
                runCatching { blobs.close() }
            }
        }
    }

    companion object {
        private val fileIdAcc = AtomicInteger(0)

        val generation = Time.now()
        private val idAcc = AtomicInteger(0)
        fun id(): Int {
            return idAcc.getAndIncrement()
        }
    }
}

class BinaryDebugCoroutineContext(
    val descriptorOrNull: DebugContextDescriptor?,
) : AbstractCoroutineContextElement(BinaryDebugCoroutineContext) {
    companion object : CoroutineContext.Key<BinaryDebugCoroutineContext>, BinaryFrameSchema() {
        val root = BinaryDebugCoroutineContext(null)
    }
}

val BinaryDebugCoroutineContext.descriptor: DebugContextDescriptor get() = descriptorOrNull!!
val BinaryDebugCoroutineContext.parent: Int get() = descriptorOrNull?.parent ?: 1
val BinaryDebugCoroutineContext.id: Int get() = descriptorOrNull?.id ?: 1

suspend fun debugContextOrNull(): DebugContextDescriptor? {
    return coroutineContext[BinaryDebugCoroutineContext]?.descriptorOrNull
}

suspend fun debugContext(): DebugContextDescriptor = debugContextOrNull() ?: error("No valid debug context")

enum class DebugContextType {
    CLIENT_REQUEST,
    SERVER_REQUEST,
    DATABASE_TRANSACTION,
    BACKGROUND_TASK,
    OTHER,
}

class DebugContextDescriptor(buf: ByteBuffer, ptr: Int) : BinaryFrame(buf, ptr) {
    var isOpen: Boolean = true

    var parent by Schema.parent
    var id by Schema.id
    var importance by Schema.importance
    var type by Schema.type
    var name: String
        get() = Schema.name.getValue(this, this::name).decodeToString()
        set(value) {
            val shortName = value.take(100)
            Schema.name.setValue(this, this::name, shortName.encodeToByteArray())
        }

    fun setChild(idx: Int, childContext: Int) {
        require(idx in 0..255) { "index out of bounds $idx not in 0..255" }
        val relativeContext = childContext - id
        if (relativeContext >= Short.MAX_VALUE || relativeContext <= 0) return
        buf.putShort(children.offset + idx * 2, relativeContext.toShort())
    }

    fun getChild(idx: Int): Int {
        require(idx in 0..255) { "index out of bounds $idx not in 0..255" }
        return id + buf.getShort(children.offset + idx * 2)
    }

    fun appendChild(child: Int) {
        for (i in 0..255) {
            if (getChild(i) == 0) {
                setChild(i, child)
                break
            }
        }
    }

    override val schema = Schema
    companion object Schema : BinaryFrameSchema() {
        val parent = int4()
        val id = int4()

        val importance = enum<MessageImportance>()
        val type = enum<DebugContextType>()
        val rsv1 = int1()
        val rsv2 = int1()

        val name = bytes(116)
        val children = bytes(256)
    }
}

suspend fun BinaryDebugSystem.clientRequest(
    importance: MessageImportance,

    call: String?,
    payload: JsonElement?,
) {
    val ctx = coroutineContext[BinaryDebugCoroutineContext] ?: BinaryDebugCoroutineContext.root
    val payloadEncoded = if (payload == null) "" else defaultMapper.encodeToString(JsonElement.serializer(), payload)

    emit {
        val message = allocateOrNull(clientRequest) ?: return@emit null
        message.ctxGeneration = BinaryDebugSystem.generation
        message.ctxParent = ctx.parent
        message.ctxId = ctx.id
        message.timestamp = Time.now()
        message.importance = importance
        message.id = BinaryDebugSystem.id()

        message.call = text(call ?: "", BinaryDebugMessage.ClientRequest.call)
        message.payload = text(payloadEncoded, BinaryDebugMessage.ClientRequest.payload)
        message
    }
}

suspend fun BinaryDebugSystem.clientResponse(
    importance: MessageImportance,

    call: String?,
    response: JsonElement?,

    responseCode: HttpStatusCode,
    responseTime: Long,
) {
    val ctx = coroutineContext[BinaryDebugCoroutineContext] ?: BinaryDebugCoroutineContext.root
    val responseEncoded = if (response == null) "" else defaultMapper.encodeToString(JsonElement.serializer(), response)

    emit {
        val message = allocateOrNull(clientResponse) ?: return@emit null
        message.ctxGeneration = BinaryDebugSystem.generation
        message.ctxParent = ctx.parent
        message.ctxId = ctx.id
        message.timestamp = Time.now()
        message.importance = importance
        message.id = BinaryDebugSystem.id()

        message.call = text(call ?: "", BinaryDebugMessage.ClientResponse.call)
        message.response = text(responseEncoded, BinaryDebugMessage.ClientResponse.response)
        message.responseCode = responseCode.value.toByte()
        message.responseTime = responseTime.toInt()
        message
    }
}

suspend fun BinaryDebugSystem.serverRequest(
    importance: MessageImportance,

    call: String?,
    payload: JsonElement?,
) {
    val ctx = coroutineContext[BinaryDebugCoroutineContext] ?: BinaryDebugCoroutineContext.root
    val payloadEncoded = if (payload == null) "" else defaultMapper.encodeToString(JsonElement.serializer(), payload)

    emit {
        val message = allocateOrNull(serverRequest) ?: return@emit null
        message.ctxGeneration = BinaryDebugSystem.generation
        message.ctxParent = ctx.parent
        message.ctxId = ctx.id
        message.timestamp = Time.now()
        message.importance = importance
        message.id = BinaryDebugSystem.id()

        message.call = text(call ?: "", BinaryDebugMessage.ServerRequest.call)
        message.payload = text(payloadEncoded, BinaryDebugMessage.ServerRequest.payload)
        message
    }
}

suspend fun BinaryDebugSystem.serverResponse(
    importance: MessageImportance,

    call: String?,
    response: JsonElement?,

    responseCode: HttpStatusCode,
    responseTime: Long,
) {
    val ctx = coroutineContext[BinaryDebugCoroutineContext] ?: BinaryDebugCoroutineContext.root
    val responseEncoded = if (response == null) "" else defaultMapper.encodeToString(JsonElement.serializer(), response)

    emit {
        val message = allocateOrNull(serverResponse) ?: return@emit null
        message.ctxGeneration = BinaryDebugSystem.generation
        message.ctxParent = ctx.parent
        message.ctxId = ctx.id
        message.timestamp = Time.now()
        message.importance = importance
        message.id = BinaryDebugSystem.id()

        message.call = text(call ?: "", BinaryDebugMessage.ServerResponse.call)
        message.response = text(responseEncoded, BinaryDebugMessage.ServerResponse.response)
        message.responseCode = responseCode.value.toByte()
        message.responseTime = responseTime.toInt()
        message
    }
}

suspend fun BinaryDebugSystem.databaseConnection(
    importance: MessageImportance,

    isOpen: Boolean
) {
    val ctx = coroutineContext[BinaryDebugCoroutineContext] ?: BinaryDebugCoroutineContext.root

    emit {
        val message = allocateOrNull(databaseConnection) ?: return@emit null
        message.ctxGeneration = BinaryDebugSystem.generation
        message.ctxParent = ctx.parent
        message.ctxId = ctx.id
        message.timestamp = Time.now()
        message.importance = importance
        message.id = BinaryDebugSystem.id()

        message.isOpen = isOpen
        message
    }
}

suspend fun BinaryDebugSystem.databaseTransaction(
    importance: MessageImportance,

    event: DebugMessage.DBTransactionEvent
) {
    val ctx = coroutineContext[BinaryDebugCoroutineContext] ?: BinaryDebugCoroutineContext.root

    emit {
        val message = allocateOrNull(databaseTransaction) ?: return@emit null
        message.ctxGeneration = BinaryDebugSystem.generation
        message.ctxParent = ctx.parent
        message.ctxId = ctx.id
        message.timestamp = Time.now()
        message.importance = importance
        message.id = BinaryDebugSystem.id()

        message.event = event
        message
    }
}

suspend fun BinaryDebugSystem.databaseQuery(
    importance: MessageImportance,

    parameters: JsonElement?,
    query: String,
) {
    val ctx = coroutineContext[BinaryDebugCoroutineContext] ?: BinaryDebugCoroutineContext.root
    val parametersEncoded =
        if (parameters == null) "" else defaultMapper.encodeToString(JsonElement.serializer(), parameters)

    emit {
        val message = allocateOrNull(databaseQuery) ?: return@emit null
        message.ctxGeneration = BinaryDebugSystem.generation
        message.ctxParent = ctx.parent
        message.ctxId = ctx.id
        message.timestamp = Time.now()
        message.importance = importance
        message.id = BinaryDebugSystem.id()

        message.parameters = text(parametersEncoded, BinaryDebugMessage.DatabaseQuery.parameters)
        message.query = text(query, BinaryDebugMessage.DatabaseQuery.query)
        message
    }
}

suspend fun BinaryDebugSystem.databaseResponse(
    importance: MessageImportance,

    responseTime: Long
) {
    val ctx = coroutineContext[BinaryDebugCoroutineContext] ?: BinaryDebugCoroutineContext.root

    emit {
        val message = allocateOrNull(databaseResponse) ?: return@emit null
        message.ctxGeneration = BinaryDebugSystem.generation
        message.ctxParent = ctx.parent
        message.ctxId = ctx.id
        message.timestamp = Time.now()
        message.importance = importance
        message.id = BinaryDebugSystem.id()

        message.responseTime = responseTime.toInt()
        message
    }
}

suspend fun BinaryDebugSystem.log(
    importance: MessageImportance,

    log: String,
    extra: JsonElement? = null,
) {
    val ctx = coroutineContext[BinaryDebugCoroutineContext] ?: BinaryDebugCoroutineContext.root
    val extraEncoded = if (extra == null) "" else defaultMapper.encodeToString(JsonElement.serializer(), extra)

    emit {
        val message = allocateOrNull(this.log) ?: return@emit null
        message.ctxGeneration = BinaryDebugSystem.generation
        message.ctxParent = ctx.parent
        message.ctxId = ctx.id
        message.timestamp = Time.now()
        message.importance = importance
        message.id = BinaryDebugSystem.id()

        message.message = text(log, BinaryDebugMessage.Log.message)
        message.extra = text(extraEncoded, BinaryDebugMessage.Log.extra)
        message
    }
}

class BlobSystem(
    private val directory: File,
    private val generation: Long,
    private val fileIndex: Int
) {
    private val channel = FileChannel.open(
        File(directory, "$generation-$fileIndex.blob").toPath(),
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE,
        StandardOpenOption.SPARSE,
    )
    private val buf = channel.map(FileChannel.MapMode.READ_WRITE, 0, 1024 * 1024 * 512)

    fun storeBlob(blob: ByteArray): String {
        if (buf.position() + blob.size + 4 > buf.capacity()) return "invalid"

        val pos = buf.position()
        buf.putInt(blob.size)
        buf.put(blob)
        return pos.toString()
    }

    fun close() {
        channel.truncate(buf.position().toLong())
        channel.close()
    }
}

@Suppress("OPT_IN_USAGE")
fun main() {
    val logFolder = File("/tmp/logs")
    if (false) {
        val reader = BinaryFrameReader(File(logFolder, "1671114806633-0.log"))
        var idx = 0
        while (true) {
            val message = reader.retrieve(idx++) ?: break
            when (message) {
                is BinaryDebugMessage.Log -> {
                    println(reader.decodeText(message.message))
                }

                else -> {}
            }
        }

        val d = ContextDescriptorFile(logFolder, 1671114806633, 1)
        var d2: DebugContextDescriptor? = d.next()
        var iteration = 0
        while (d2 != null && iteration < 100) {
            println(d2.type)
            println(d2.name)
            println(d2.parent)
            println(d2.id)

            d2 = d.next(d2)
            iteration++
        }
        println(idx)

        exitProcess(0)
    }
    runCatching { logFolder.deleteRecursively() }
    logFolder.mkdirs()

    val debug = BinaryDebugSystem(logFolder.absolutePath, "UCloud/Core")
    val j = debug.start(GlobalScope)

    runBlocking {
        val warmup = measureTime {
            (0 until 8).map {
                GlobalScope.launch {
                    debug.useContext(DebugContextType.BACKGROUND_TASK, "Context $it") {
                        repeat(1000) {
                            debug.log(MessageImportance.THIS_IS_NORMAL, "Log $it")
                        }
                    }
                }
            }.joinAll()
        }
        val time = measureTime {
            val long = CharArray(1024) { 'b' }.concatToString()
            (0 until 8).map {
                GlobalScope.launch {
                    repeat(100_000) {
                        debug.log(MessageImportance.THIS_IS_NORMAL, "$long $it")
                    }
                }
            }.joinAll()
        }

        println("All are done! ${oomCount.get()} $warmup $time")
        j.cancelAndJoin()
        println("last sync done")
    }
}

@JvmInline
value class LargeText internal constructor(val value: ByteArray) {
    companion object {
        const val OVERFLOW_PREFIX = "\$\$\$overflow-"
    }
    override fun toString(): String = value.decodeToString()
}

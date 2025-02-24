package dk.sdu.cloud.debugger

import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.date.*
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

data class TrackedService(val title: String, val generation: Long, val lastModified: Long)

val trackedServices = AtomicReference(emptyMap<String, TrackedService>())

val sessions = ArrayList<ClientSession>()
val sessionMutex = Mutex()

fun main(args: Array<String>) {
    val directory = args.getOrNull(0)?.let { File(it) } ?: error("Missing root directory")
    if (args.contains("--producer")) {
        exampleProducer(directory)
        return
    }

    @Suppress("OPT_IN_USAGE") GlobalScope.launch {
        coroutineScope {
            val serviceWatcher = launch(Dispatchers.IO) {
                while (isActive) {
                    val newServices =
                        (directory.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".service") }
                            .mapNotNull { serviceFile ->
                                runCatching {
                                    val lines = serviceFile.readText().lines()
                                    TrackedService(lines[0], lines[1].toLongOrNull() ?: 0, serviceFile.lastModified())
                                }.getOrNull()
                            }.groupBy { it.title }.mapValues { (_, group) ->
                                group.maxByOrNull { it.lastModified }!!
                            }

                    val oldServices = trackedServices.get()

                    // Notify clients of new services
                    val servicesWhichAreNew = newServices.filter { !oldServices.keys.contains(it.key) }
                    if (servicesWhichAreNew.isNotEmpty()) {
                        sessionMutex.withLock {
                            for (session in sessions) {
                                for ((_, service) in servicesWhichAreNew) {
                                    session.acceptService(service.title, service.generation)
                                }
                            }
                        }
                    }

                    trackedServices.set(newServices)
                    delay(50)
                }
            }

            val logWatcher = launch(Dispatchers.IO) {
                val openLogFiles = ArrayList<LogFileReader>()

                while (isActive) {
                    val currentServices = trackedServices.get()

                    // Close log files
                    run {
                        val iterator = openLogFiles.iterator()
                        while (iterator.hasNext()) {
                            val logFile = iterator.next()

                            val shouldClose =
                                // Close if generation is no longer valid
                                currentServices.none { it.value.generation == logFile.generation }
                            // TODO Close files which are no longer actively used

                            if (shouldClose) {
                                println("Closing service")
                                logFile.close()
                                iterator.remove()
                            }
                        }
                    }

                    // Open new log files
                    run {
                        for ((_, service) in currentServices) {
                            var idx = 0
                            while (true) {
                                if (!LogFileReader.exists(directory, service.generation, idx)) {
                                    idx--
                                    break
                                }
                                idx++
                            }

                            if (idx < 0) continue

                            val shouldOpen = openLogFiles.none {
                                it.generation == service.generation && it.idx == idx
                            }

                            if (shouldOpen) {
                                println("Opening $directory $service $idx")
                                val openFile = LogFileReader(directory, service.generation, idx)
                                openFile.seekToEnd()
                                openLogFiles.add(openFile)
                            }
                        }
                    }

                    // Find new messages from all log readers
                    run {
                        for (logFile in openLogFiles) {
                            while (logFile.next()) {
                                val message = logFile.retrieve() ?: break
//                                println(message.toString())
                                sessionMutex.withLock {
                                    for (session in sessions) {
                                        session.acceptLogMessage(message)
                                    }
                                }
                            }
                        }
                    }

                    delay(50)
                }
            }

            val contextWatcher = launch(Dispatchers.IO) {
                val openContextFiles = ArrayList<ContextReader>()

                while (isActive) {
                    val currentServices = trackedServices.get()

                    // Close files
                    run {
                        val iterator = openContextFiles.iterator()
                        while (iterator.hasNext()) {
                            val contextFile = iterator.next()

                            val shouldClose =
                                // Close if generation is no longer valid
                                currentServices.none { it.value.generation == contextFile.generation }
                            // TODO Close files which are no longer actively used
                            // What does this mean? This should probably depend on the ReplayMessage request.

                            if (shouldClose) {
                                println("Closing context")
                                contextFile.close()
                                iterator.remove()
                            }
                        }
                    }

                    // Open new files
                    run {
                        for ((_, service) in currentServices) {
                            var idx = 1
                            while (true) {
                                if (!ContextReader.exists(directory, service.generation, idx)) {
                                    idx--
                                    break
                                }
                                idx++
                            }

                            if (idx < 0) continue

                            val shouldOpen = openContextFiles.none {
                                it.generation == service.generation && it.idx == idx
                            }

                            if (shouldOpen) {
                                println("Opening context $directory $service $idx")
                                val openFile = ContextReader(directory, service.generation, idx)
//                                openFile.seekToEnd()
                                openContextFiles.add(openFile)
                            }
                        }
                    }

                    // Find new messages from all readers
                    run {
                        for (contextFile in openContextFiles) {
                            while (contextFile.next()) {
                                val message = contextFile.retrieve() ?: break
                                println("${message.importance} ${message.name} ${message.id} ${message.parent}")

                                sessionMutex.withLock {
                                    for (session in sessions) {
                                        session.acceptContext(contextFile.generation, message)
                                    }
                                }
                            }
                        }
                    }

                    delay(50)
                }
            }

            val logFlusher = launch(Dispatchers.IO) {
                while (isActive) {
                    sessionMutex.withLock {
                        for (session in sessions) {
                            session.flushServiceMessage()
                            session.flushContextMessage()
                            session.flushLogsMessage()
                        }
                    }
                    delay(100)
                }
            }
        }
    }

    embeddedServer(CIO, port = 5511) {
        install(WebSockets)

        routing {
            webSocket {
                val session = ClientSession(this)
                sessionMutex.withLock {
                    sessions.add(session)
                    println("Adding a client: ${sessions.size}")
                }

                // Send every registered service to the new session
                trackedServices.get().forEach { (_, service) ->
                    session.acceptService(service.title, service.generation)
                }

                try {
                    while (isActive) {
                        val frame = incoming.receiveCatching().getOrNull() ?: break
                        if (frame !is Frame.Text) continue
                        val frameText = frame.readText()

                        val request = runCatching {
                            defaultMapper.decodeFromString(ClientRequest.serializer(), frameText)
                        }.getOrNull() ?: continue

                        when (request) {
                            is ClientRequest.ClearActiveContext -> {
                                session.clearLogMessages()
                                session.clearContextMessages()
                                session.activeContext = arrayListOf(1L)
                                val generation = trackedServices.get().values.find { it.title == session.activeService }?.generation ?: break
                                val startTime = session.toReplayFrom ?: break
                                session.toReplayFrom = null
                                val endTime = getTimeMillis()
                                session.findContexts(startTime, endTime, directory, generation)
                            }

                            is ClientRequest.ReplayMessages -> {
                                // Clear Log and Context Buffer
                                session.clearContextMessages()
                                session.clearLogMessages()

                                session.activeContext = arrayListOf(request.context)

                                session.toReplayFrom = getTimeMillis()
                                val generation = request.generation.toLong()
                                val startTime = request.timestamp
                                val endTime = startTime + 15.minutes.inWholeMilliseconds

                                session.findContexts(startTime, endTime, directory, generation)
                                session.findLogs(startTime, endTime, directory, generation)
                            }

                            is ClientRequest.ActivateService -> {
                                session.activeService = request.service
                            }

                            is ClientRequest.SetSessionState -> {
                                session.filterQuery = request.query
                                session.minimumLevel = request.level ?: MessageImportance.THIS_IS_NORMAL
                                // TODO(Jonas): Filters
                            }
                        }
                    }
                } catch (ex: Throwable) {
                    println(ex.stackTraceToString())
                } finally {
                    sessionMutex.withLock {
                        sessions.remove(session)

                        println("Removing a client: ${sessions.size}")
                    }
                }
            }
        }
    }.start(wait = true)
}

suspend fun ClientSession.findContexts(startTime: Long, endTime: Long, directory: File, generation: Long) {
    var currentFileId = 1 // Note(Jonas): Seems to start at 1?

    outer@ while (ContextReader.exists(directory, generation, currentFileId)) {
        var currentFile = ContextReader(directory, generation, currentFileId++)
        // currentFile.logAllEntries()
        currentFile.seekToEnd()
        var fileEnd = currentFile.retrieve()?.timestamp ?: break@outer
        currentFile.resetCursor()

        // Find the file that contains our first potentially valid context
        while (startTime > fileEnd) {
            if (!ContextReader.exists(directory, generation, currentFileId)) break@outer
            currentFile = ContextReader(directory, generation, currentFileId++)
            currentFile.seekToEnd()
            fileEnd = currentFile.retrieve()?.timestamp ?: break@outer
            currentFile.resetCursor()
        }

        while (currentFile.next()) {
            val currentEntry = currentFile.retrieve() ?: continue
            if (currentEntry.timestamp < startTime) continue // keep looking
            if (currentEntry.timestamp > endTime) break@outer // finished

            if (currentEntry.parent.toLong() in activeContext) {
                activeContext.add(currentEntry.id.toLong())
                acceptContext(generation, currentEntry)
            }

            if (!currentFile.isValid(currentFile.cursor + 1)) {
                if (ContextReader.exists(directory, generation, currentFileId)) {
                    currentFile = ContextReader(directory, generation, currentFileId++)
                }
            }
        }
    }
    println(activeContext.size)
    flushContextMessage()
}

suspend fun ClientSession.findLogs(startTime: Long, endTime: Long, directory: File, generation: Long) {
    var logFileId = 0
    var logs = ArrayList<BinaryDebugMessage<*>>()
    outer@ while (LogFileReader.exists(directory, generation, logFileId)) {
        var logFile = LogFileReader(directory, generation, logFileId)

        logFile.seekToEnd()
        val end = logFile.retrieve()
        var fileEnd = end?.timestamp ?: break@outer
        logFile.resetCursor()
        logFileId += 1
        while (startTime > fileEnd) {
            if (!LogFileReader.exists(directory, generation, logFileId)) {
                break@outer
            }
            logFile = LogFileReader(directory, generation, logFileId)
            logFile.seekToEnd()
            fileEnd = logFile.retrieve()?.timestamp ?: break@outer
            logFile.resetCursor()
            logFileId += 1
        }

        while (logFile.next()) {
            val currentEntry = logFile.retrieve() ?: continue
            if (currentEntry.timestamp < startTime) continue // keep looking
            if (currentEntry.timestamp > endTime) break@outer // finished

            if (currentEntry.ctxId.toLong() in activeContext) {
                acceptLogMessage(currentEntry)
                logs.add(currentEntry)
            }

            if (!logFile.isValid(logFile.cursor + 1)) {
                if (LogFileReader.exists(directory, generation, logFileId)) {
                    logFile = LogFileReader(directory, generation, logFileId++)
                }
            }
        }
    }
    logs
    flushLogsMessage()
}

@Serializable
sealed class ClientRequest {
    @Serializable
    @SerialName("replay_messages")
    data class ReplayMessages(val generation: String, val context: Long, val timestamp: Long) : ClientRequest()

    @Serializable
    @SerialName("activate_service")
    data class ActivateService(val service: String?) : ClientRequest()

    @Serializable
    @SerialName("set_session_state")
    data class SetSessionState(val query: String?, val filters: List<DebugContextType>, val level: MessageImportance?) :
        ClientRequest()

    @Serializable
    @SerialName("clear_active_context")
    object ClearActiveContext : ClientRequest()
}

data class ClientSession(
    // The WebSocket session itself, used for sending communication to the client.
    val session: WebSocketServerSession,

    // State, which is updated by messages from the client.
    var activeContext: ArrayList<Long> = arrayListOf(1L),
    var minimumLevel: MessageImportance = MessageImportance.THIS_IS_NORMAL,
    var filterQuery: String? = null,
    var activeService: String? = null,

    // To denote when the user requested children of a specific debug context.
    // Used to find out which contexts the user may have missed.
    var toReplayFrom: Long? = null,

    val writeMutex: Mutex = Mutex(),

    // Buffers used for various messages. We keep a separate buffer per message type to make it slightly easier to
    // handle concurrency.
    private val newContextWriteBuffer: ByteBuffer = ByteBuffer.allocateDirect(1024 * 512),
    private val newLogsWriteBuffer: ByteBuffer = ByteBuffer.allocateDirect(1024 * 512),
    private val newServiceWriteBuffer: ByteBuffer = ByteBuffer.allocateDirect(1024 * 32),
) {
    init {
        // NOTE(Dan): This will force initialize all buffers with their metadata
        runBlocking {
            clearServiceMessages()
            clearContextMessages()
            clearLogMessages()
        }
    }

    suspend fun flushServiceMessage() {
        writeMutex.withLock {
            newServiceWriteBuffer.flip()
            if (newServiceWriteBuffer.remaining() > 8) {
                session.send(Frame.Binary(true, newServiceWriteBuffer))
            }
            newServiceWriteBuffer.clear()
            newServiceWriteBuffer.putLong(1)
        }
    }

    suspend fun flushContextMessage() {
        writeMutex.withLock {
            newContextWriteBuffer.flip()
            if (newContextWriteBuffer.remaining() > 8) {
                session.send(Frame.Binary(true, newContextWriteBuffer))
            }
            newContextWriteBuffer.clear()
            newContextWriteBuffer.putLong(2)
        }
    }

    suspend fun flushLogsMessage() {
        writeMutex.withLock {
            newLogsWriteBuffer.flip()
            if (newLogsWriteBuffer.remaining() > 8) {
                session.send(Frame.Binary(true, newLogsWriteBuffer))
            }
            newLogsWriteBuffer.clear()
            newLogsWriteBuffer.putLong(3)
        }
    }

    suspend fun clearServiceMessages() {
        writeMutex.withLock {
            newServiceWriteBuffer.clear()
            newServiceWriteBuffer.putLong(1)
        }
    }

    suspend fun clearContextMessages() {
        writeMutex.withLock {
            newContextWriteBuffer.clear()
            newContextWriteBuffer.putLong(2)
        }
    }

    suspend fun clearLogMessages() {
        writeMutex.withLock {
            newLogsWriteBuffer.clear()
            newLogsWriteBuffer.putLong(3)
        }
    }

    suspend fun acceptLogMessage(message: BinaryDebugMessage<*>) {
        val service = activeService ?: return
        if (message.importance.ordinal < minimumLevel.ordinal) return
        val services = trackedServices.get()
        val trackedService = services.values.find { it.title == service }
        if (trackedService == null || trackedService.generation != message.ctxGeneration) return
        if (!activeContext.contains(message.ctxId.toLong())) return

        writeMutex.withLock {
            if (newLogsWriteBuffer.remaining() < FRAME_SIZE) return
            val oldPos = message.buf.position()
            val oldLim = message.buf.limit()

            message.buf.position(message.offset)
            message.buf.limit(message.offset + FRAME_SIZE)

            newLogsWriteBuffer.put(message.buf)

            message.buf.position(oldPos)
            message.buf.limit(oldLim)
        }
    }

    suspend fun acceptContext(generation: Long, context: DebugContextDescriptor) {
        val service = activeService ?: return
        val services = trackedServices.get()
        val trackedService = services.values.find { it.title == service }
        if (trackedService == null || trackedService.generation != generation) return
        if (!activeContext.contains(context.parent.toLong())) return

        writeMutex.withLock {
            if (newContextWriteBuffer.remaining() < DebugContextDescriptor.size) return
            val oldPos = context.buf.position()
            val oldLim = context.buf.limit()

            context.buf.position(context.offset)
            context.buf.limit(context.offset + DebugContextDescriptor.size)

            newContextWriteBuffer.put(context.buf)

            context.buf.position(oldPos)
            context.buf.limit(oldLim)
        }
    }

    suspend fun acceptService(serviceName: String, generation: Long) {
        // NOTE(Jonas): An additional byte (type) is in the buffer ahead of this, making it 264 bytes long in total.
        val MAX_GENERATION_LENGTH = 16
        val MAX_SERVICENAME_LENGTH = 256 - MAX_GENERATION_LENGTH

        writeMutex.withLock {
            val encodedServiceName = serviceName.encodeToByteArray()
            if (encodedServiceName.size >= MAX_SERVICENAME_LENGTH) return
            if (newServiceWriteBuffer.remaining() < MAX_SERVICENAME_LENGTH) return

            val encodedGeneration = generation.toString().encodeToByteArray()
            if (encodedGeneration.size >= MAX_GENERATION_LENGTH) return

            newServiceWriteBuffer.put(encodedServiceName)

            val emptyBytes = ByteArray(MAX_SERVICENAME_LENGTH - encodedServiceName.size)
            newServiceWriteBuffer.put(emptyBytes)

            newServiceWriteBuffer.put(encodedGeneration)
            val emptyGenerationBytes = ByteArray(MAX_GENERATION_LENGTH - encodedGeneration.size)
            newServiceWriteBuffer.put(emptyGenerationBytes)
        }
    }
}

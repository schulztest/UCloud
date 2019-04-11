package dk.sdu.cloud.file.services.linuxfs

import com.sun.jna.LastErrorException
import dk.sdu.cloud.calls.RPCException
import dk.sdu.cloud.file.services.CommandRunner
import dk.sdu.cloud.file.services.StorageUserDao
import dk.sdu.cloud.file.util.FSException
import dk.sdu.cloud.file.util.throwExceptionBasedOnStatus
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import java.lang.Exception
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.NotDirectoryException
import java.nio.file.NotLinkException
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class LinuxFSRunner(
    private val userDao: StorageUserDao<Long>,
    override val user: String
) : CommandRunner {
    private val queue = ArrayBlockingQueue<() -> Any?>(64)
    private var thread: Thread? = null
    private var isRunning: Boolean = false

    private fun init() {
        synchronized(this) {
            if (thread == null) {
                isRunning = true
                thread = Thread(
                    {
                        val cloudUser = runBlocking { userDao.findStorageUser(user) }
                            ?: throw RPCException.fromStatusCode(HttpStatusCode.Forbidden)


                        StandardCLib.setfsgid(cloudUser)
                        StandardCLib.setfsuid(cloudUser)

                        while (isRunning) {
                            val nextJob = queue.poll(1, TimeUnit.SECONDS) ?: continue
                            nextJob()
                        }
                    },
                    THREAD_PREFIX + user + "-" + UUID.randomUUID().toString()
                ).also { it.start() }
            }
        }
    }

    suspend fun <T> submit(job: () -> T): T = suspendCoroutine { cont ->
        init()

        val futureTask: () -> Unit = {
            runBlocking {
                try {
                    runAndRethrowNIOExceptions {
                        cont.resume(job())
                    }
                } catch (ex: Throwable) {
                    cont.resumeWithException(ex)
                }
            }
        }

        queue.put(futureTask)
    }

    fun requireContext() {
        if (!Thread.currentThread().name.startsWith("$THREAD_PREFIX$user-")) {
            throw IllegalStateException("Code is running in an invalid context!")
        }
    }

    override fun close() {
        isRunning = false
    }

    companion object {
        const val THREAD_PREFIX = "linux-fs-thread-"
    }
}

inline fun <T> runAndRethrowNIOExceptions(block: () -> T): T {
    return try {
        block()
    } catch (ex: FileSystemException) {
        when (ex) {
            is DirectoryNotEmptyException -> throw FSException.BadRequest("Directory not empty")

            is FileAlreadyExistsException -> throw FSException.AlreadyExists()

            is NoSuchFileException -> throw FSException.NotFound()

            is NotDirectoryException -> throw FSException.BadRequest("Not a directory")

            is NotLinkException -> throw FSException.BadRequest("Not a link")

            is AccessDeniedException -> throw FSException.PermissionException()

            else -> throw FSException.CriticalException(ex.message ?: "Internal error")
        }
    } catch (ex: NativeException) {
        throwExceptionBasedOnStatus(ex.statusCode)
    } catch (ex: LastErrorException) {
        throwExceptionBasedOnStatus(ex.errorCode)
    }
}

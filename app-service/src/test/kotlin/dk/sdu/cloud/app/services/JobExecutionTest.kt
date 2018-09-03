package dk.sdu.cloud.app.services

import com.auth0.jwt.JWT
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpATTRS
import dk.sdu.cloud.CommonErrorMessage
import dk.sdu.cloud.app.api.*
import dk.sdu.cloud.app.services.ssh.*
import dk.sdu.cloud.auth.api.AuthDescriptions
import dk.sdu.cloud.auth.api.OneTimeAccessToken
import dk.sdu.cloud.auth.api.RefreshingJWTAuthenticatedCloud
import dk.sdu.cloud.auth.api.RefreshingJWTAuthenticator
import dk.sdu.cloud.client.RESTCallDescription
import dk.sdu.cloud.client.RESTResponse
import dk.sdu.cloud.service.MappedEventProducer
import dk.sdu.cloud.service.TokenValidation
import dk.sdu.cloud.service.db.DBSessionFactory
import dk.sdu.cloud.file.api.*
import dk.sdu.cloud.upload.api.MultiPartUploadDescriptions
import io.ktor.client.response.HttpResponse
import io.ktor.http.HttpStatusCode
import io.mockk.*
import io.mockk.impl.annotations.RelaxedMockK
import kotlinx.coroutines.experimental.io.ByteReadChannel
import kotlinx.coroutines.experimental.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.*
import java.net.URI
import java.util.*

class JobExecutionTest {
    private val sshUser = "nobody"
    private val sBatchGenerator = SBatchGenerator()

    @RelaxedMockK
    lateinit var cloud: RefreshingJWTAuthenticatedCloud

    @RelaxedMockK
    lateinit var producer: MappedEventProducer<String, AppEvent>

    @RelaxedMockK
    lateinit var jobsDao: JobDAO<Any>

    @RelaxedMockK
    lateinit var slurmPollAgent: SlurmPollAgent

    @RelaxedMockK
    lateinit var sshPool: SSHConnectionPool

    @RelaxedMockK
    lateinit var sshConnection: SSHConnection

    @RelaxedMockK
    lateinit var db: DBSessionFactory<Any>

    @RelaxedMockK
    lateinit var appDao: ApplicationDAO<Any>

    @RelaxedMockK
    lateinit var toolDao: ToolDAO<Any>

    lateinit var service: JobExecutionService<Any>

    val emitSlot = ArrayList<AppEvent>()


    // ============================================================
    // ====================== Test resources ======================
    // ============================================================

    private val dummyTokenSubject = "test"
    private val dummyToken = JWT.decode(
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                "eyJzdWIiOiJ0ZXN0IiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ." +
                "GxfHPZdY5aBZRt2g-ogPn6LfaG7MnAag-psqzquZKw8"
    )

    private val jobDirectiory = "/scratch/sduescience/p/"
    private val workingDirectory = "/scratch/sduescience/p/files/"

    private val dummyTool = Tool(
        "foo",
        0L,
        0L,
        NormalizedToolDescription(
            info = NameAndVersion("dummy", "1.0.0"),
            container = "dummy.simg",
            defaultNumberOfNodes = 1,
            defaultTasksPerNode = 1,
            defaultMaxTime = SimpleDuration(1, 0, 0),
            requiredModules = emptyList(),
            authors = listOf("Author"),
            title = "Dummy",
            description = "Dummy description",
            backend = ToolBackend.UDOCKER
        )
    )

    private val noParamsApplication = app(
        "noparams",
        invocation = listOf(WordInvocationParameter("noparms")),
        parameters = emptyList()
    )


    private val txtFilesGlob = "*.txt"
    private val singleFileGlob = "b.txt"
    private val directoryGlob = "c/"
    private val filesUnderDirectoryGlob = "d/*"
    private val applicationWithOutputs = app(
        "appwithoutput",
        invocation = emptyList(),
        parameters = emptyList(),
        fileGlobs = listOf(txtFilesGlob, singleFileGlob, directoryGlob, filesUnderDirectoryGlob)
    )

    private fun app(
        name: String,
        invocation: List<InvocationParameter>,
        parameters: List<ApplicationParameter<*>>,
        fileGlobs: List<String> = emptyList()
    ): Application {
        return Application(
            "foo",
            0L,
            0L,
            NormalizedApplicationDescription(
                tool = dummyTool.description.info,
                info = NameAndVersion(name, "1.0.0"),
                authors = listOf("Author"),
                title = name,
                description = name,
                invocation = invocation,
                parameters = parameters,
                outputFileGlobs = fileGlobs
            ),
            dummyTool
        )
    }

    private fun stat(name: String): StorageFile {
        return StorageFile(
            FileType.FILE,
            name,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            dummyTokenSubject,
            10L,
            emptyList(),
            false,
            SensitivityLevel.CONFIDENTIAL,
            false,
            emptySet()
        )
    }

    private fun createTemporaryApplication(application: Application) {
        every {
            with(application.description.info) {
                appDao.findByNameAndVersion(any(), any(), name, version)
            }
        } returns application
    }

    private fun createTemporaryTool(tool: Tool) {
        every {
            with(tool.description.info) {
                toolDao.findByNameAndVersion(any(), any(), name, version)
            }
        } returns tool
    }

    private fun <T> withMockScopes(vararg scopes: MockKUnmockKScope, body: () -> T): T {
        scopes.forEach { it.mock() }
        try {
            return body()
        } finally {
            scopes.reversed().forEach { it.unmock() }
        }
    }

    private fun scpScope() = staticMockk("dk.sdu.cloud.app.services.ssh.SCPKt")
    private fun sftpScope() = staticMockk("dk.sdu.cloud.app.services.ssh.SFTPKt")
    private fun zipScope() = staticMockk("dk.sdu.cloud.app.services.ssh.ZIPKt")
    private fun uploadScope() = objectMockk(MultiPartUploadDescriptions)

    // =========================================
    // TESTS
    // =========================================

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        coEvery { producer.emit(capture(emitSlot)) } just Runs

        every { db.openSession() } returns Any()
        every { db.openTransaction(any()) } just Runs

        every { sshPool.borrowConnection() } answers {
            Pair(0, sshConnection)
        }

        service = JobExecutionService(
            cloud,
            producer,
            sBatchGenerator,
            db,
            jobsDao,
            appDao,
            slurmPollAgent,
            sshPool,
            sshUser
        )

        val tools = listOf(dummyTool)
        tools.forEach { createTemporaryTool(it) }

        val applications = listOf(noParamsApplication, applicationWithOutputs)
        applications.forEach { createTemporaryApplication(it) }
    }

    @Test
    fun testValidationOfSimpleJob() {
        val result =
            runBlocking {
                service.startJob(
                    AppRequest.Start(noParamsApplication.description.info, emptyMap()),
                    dummyToken,
                    cloud
                )
            }
        verifyJobStarted(result, noParamsApplication.description)
    }

    @Test(expected = JobValidationException::class)
    fun testValidationOfMissingOptionalParameter() {
        val application = app(
            "singlearg",
            invocation = listOf(WordInvocationParameter("singlearg"), VariableInvocationParameter(listOf("arg"))),
            parameters = listOf(ApplicationParameter.Text("arg", false))
        )
        createTemporaryApplication(application)

        val result = runBlocking {
            service.startJob(
                AppRequest.Start(application.description.info, emptyMap()),
                dummyToken,
                cloud
            )
        }
        verifyJobStarted(result, application.description)
    }

    @Test
    fun testValidationOfOptionalNoDefault() {
        val application = app(
            "singleoptional",
            invocation = listOf(WordInvocationParameter("eh"), VariableInvocationParameter(listOf("arg"))),
            parameters = listOf(ApplicationParameter.Text("arg", true, defaultValue = null))
        )

        createTemporaryApplication(application)

        val result = runBlocking {
            service.startJob(
                AppRequest.Start(application.description.info, emptyMap()),
                dummyToken,
                cloud
            )
        }
        verifyJobStarted(result, application.description)
    }

    @Test
    fun testValidationOfOptionalWithDefault() {
        val application = app(
            "singleoptionaldefault",
            invocation = listOf(WordInvocationParameter("eh"), VariableInvocationParameter(listOf("arg"))),
            parameters = listOf(ApplicationParameter.Text("arg", true, defaultValue = "foobar"))
        )
        createTemporaryApplication(application)

        val result = runBlocking {
            service.startJob(
                AppRequest.Start(application.description.info, emptyMap()),
                dummyToken,
                cloud
            )
        }
        verifyJobStarted(result, application.description)
    }

    @Test
    fun testMultipleVariables() {
        val application = app(
            "multiple",
            invocation = listOf(WordInvocationParameter("eh"), VariableInvocationParameter(listOf("arg", "arg2"))),
            parameters = listOf(
                ApplicationParameter.Text("arg", true, defaultValue = "foobar"),
                ApplicationParameter.Text("arg2", false, defaultValue = "foobar")
            )
        )
        createTemporaryApplication(application)

        val result = runBlocking {
            service.startJob(
                AppRequest.Start(
                    application.description.info,
                    mapOf(
                        "arg" to "foo",
                        "arg2" to "bar"
                    )
                ),
                dummyToken,
                cloud
            )
        }
        verifyJobStarted(result, application.description)
    }

    @Test(expected = JobValidationException::class)
    fun testMultipleVariablesInvalid() {
        val application = app(
            "multiple",
            invocation = listOf(WordInvocationParameter("eh"), VariableInvocationParameter(listOf("arg", "arg2"))),
            parameters = listOf(
                ApplicationParameter.Text("arg", true, defaultValue = "foobar"),
                ApplicationParameter.Text("arg2", false, defaultValue = "foobar")
            )
        )
        createTemporaryApplication(application)

        val result = runBlocking {
            service.startJob(
                AppRequest.Start(
                    application.description.info,
                    mapOf(
                        "arg" to "foo"
                    )
                ),
                dummyToken,
                cloud
            )
        }
        verifyJobStarted(result, application.description)
    }

    @Test
    fun testFileInputValid() {
        withMockScopes(objectMockk(FileDescriptions)) {
            val path = "/home/foo/Uploads/1.txt"
            coEvery {
                FileDescriptions.stat.call(match { it.path == path }, cloud)
            } answers {
                RESTResponse.Ok(
                    mockk(relaxed = true),
                    stat(path)
                )
            }

            val application = app(
                "files",
                invocation = listOf(VariableInvocationParameter(listOf("myFile"))),
                parameters = listOf(
                    ApplicationParameter.InputFile("myFile", false)
                )
            )
            createTemporaryApplication(application)

            val result = runBlocking {
                service.startJob(
                    AppRequest.Start(
                        application.description.info,
                        mapOf(
                            "myFile" to mapOf("source" to path, "destination" to "1.txt")
                        )
                    ),
                    dummyToken,
                    cloud
                )
            }

            verifyJobStarted(result, application.description)
            val captured = emitSlot.first() as AppEvent.Validated
            val workDir = URI(captured.workingDirectory)

            assertEquals(1, captured.files.size)

            val file = captured.files.first()
            assertEquals(10L, file.stat.size)
            assertEquals(path, file.sourcePath)
            assertEquals(workDir.resolve("1.txt").path, file.destinationPath)
            assertEquals("1.txt", file.destinationFileName)
        }
    }

    @Test(expected = JobValidationException::class)
    fun testFileInputValidationWithMissingFile() {
        withMockScopes(objectMockk(FileDescriptions)) {
            val path = "/home/foo/Uploads/1.txt"
            coEvery {
                FileDescriptions.stat.call(match { it.path == path }, cloud)
            } answers {
                val response: HttpResponse = mockk(relaxed = true)
                every { response.status } returns HttpStatusCode.NotFound
                RESTResponse.Err(
                    response,
                    CommonErrorMessage("Not found")
                )
            }

            val application = app(
                "files",
                invocation = listOf(VariableInvocationParameter(listOf("myFile"))),
                parameters = listOf(
                    ApplicationParameter.InputFile("myFile", false)
                )
            )
            createTemporaryApplication(application)

            runBlocking {
                service.startJob(
                    AppRequest.Start(
                        application.description.info,
                        mapOf(
                            "myFile" to mapOf("source" to path, "destination" to "1.txt")
                        )
                    ),
                    dummyToken,
                    cloud
                )
            }
        }
    }

    @Test
    fun testValidFileInputValidationWithMultipleFiles() {
        withMockScopes(objectMockk(FileDescriptions)) {
            val paths = listOf("/home/foo/Uploads/1.txt", "/home/foo/foo.png")
            paths.forEach { path ->
                coEvery {
                    FileDescriptions.stat.call(match { it.path == path }, cloud)
                } answers {
                    RESTResponse.Ok(
                        mockk(relaxed = true),
                        stat(path)
                    )
                }
            }

            val application = app(
                "files",
                invocation = listOf(VariableInvocationParameter(listOf("myFile"))),
                parameters = listOf(
                    ApplicationParameter.InputFile("myFile", false),
                    ApplicationParameter.InputFile("myFile2", false)
                )
            )
            createTemporaryApplication(application)

            val result = runBlocking {
                service.startJob(
                    AppRequest.Start(
                        application.description.info,
                        mapOf(
                            "myFile" to mapOf("source" to paths[0], "destination" to "1.txt"),
                            "myFile2" to mapOf("source" to paths[1], "destination" to "foo.png")
                        )
                    ),
                    dummyToken,
                    cloud
                )
            }

            verifyJobStarted(result, application.description)
            val captured = emitSlot.first() as AppEvent.Validated
            val workDir = URI(captured.workingDirectory)

            assertEquals(paths.size, captured.files.size)

            paths.forEachIndexed { idx, path ->
                val file = captured.files[idx]
                val name = path.substringAfterLast('/')
                assertEquals(10L, file.stat.size)
                assertEquals(path, file.sourcePath)
                assertEquals(workDir.resolve(name).path, file.destinationPath)
                assertEquals(name, file.destinationFileName)
            }
        }
    }

    @Test(expected = JobValidationException::class)
    fun testInvalidFileInputValidationWithMultipleFiles() {
        withMockScopes(objectMockk(FileDescriptions)) {
            val paths = listOf("/home/foo/Uploads/1.txt", "/home/foo/foo.png")
            paths.forEach { path ->
                coEvery {
                    FileDescriptions.stat.call(match { it.path == path }, cloud)
                } answers {
                    RESTResponse.Ok(
                        mockk(relaxed = true),
                        stat(path)
                    )
                }
            }

            val application = app(
                "files",
                invocation = listOf(VariableInvocationParameter(listOf("myFile"))),
                parameters = listOf(
                    ApplicationParameter.InputFile("myFile", false),
                    ApplicationParameter.InputFile("myFile2", false)
                )
            )
            createTemporaryApplication(application)

            val result = runBlocking {
                service.startJob(
                    AppRequest.Start(
                        application.description.info,
                        mapOf(
                            "myFile" to mapOf("source" to paths[0], "destination" to "1.txt")
                        )
                    ),
                    dummyToken,
                    cloud
                )
            }

            verifyJobStarted(result, application.description)
            val captured = emitSlot.first() as AppEvent.Validated
            val workDir = URI(captured.workingDirectory)

            assertEquals(paths.size, captured.files.size)

            paths.forEachIndexed { idx, path ->
                val file = captured.files[idx]
                val name = path.substringAfterLast('/')
                assertEquals(10L, file.stat.size)
                assertEquals(path, file.sourcePath)
                assertEquals(workDir.resolve(name).path, file.destinationPath)
                assertEquals(name, file.destinationFileName)
            }
        }
    }

    private fun verifyJobStarted(result: String, app: NormalizedApplicationDescription) {
        assertNotEquals("", result)

        verify {
            jobsDao.createJob(
                any(),
                dummyTokenSubject,
                result,
                app.info.name,
                app.info.version
            )
        }

        coVerify {
            producer.emit(
                match {
                    it is AppEvent.Validated &&
                            it.systemId == result &&
                            it.owner == dummyTokenSubject &&
                            it.jwt == dummyToken.token
                }
            )
        }
    }

    @Test
    fun testJobPreparationBadJWT() {
        val event = AppEvent.Validated(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            dummyToken.token,
            dummyTokenSubject,
            noParamsApplication,
            "/scratch/sduescience/p",
            "/scratch/sduescience/p/files",
            emptyList(),
            "job"
        )

        // We don't mock the TokenValidation part, thus the token will not validate (bad signature)
        // We just check that we actually output the correct event
        service.handleAppEvent(event)

        assertTrue(emitSlot.isNotEmpty())
        val outputEvent = emitSlot.first() as AppEvent.Completed
        assertFalse(outputEvent.successful)
    }

    @Test
    fun testJobPreparationNoFiles() {
        val inlineSBatchJob = "job"
        val event = AppEvent.Validated(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            dummyToken.token,
            dummyTokenSubject,
            noParamsApplication,
            "/scratch/sduescience/p",
            "/scratch/sduescience/p/files",
            emptyList(),
            inlineSBatchJob
        )

        val (fileNameSlot, fileContents) = withMockedAuthentication {
            withMockedSCPUpload {
                withMockScopes(sftpScope()) {
                    every { sshConnection.mkdir(any(), any()) } returns 0
                    service.handleAppEvent(event)
                }
            }
        }

        assertTrue(emitSlot.isNotEmpty())
        emitSlot.first() as AppEvent.Prepared

        assertEquals(1, fileNameSlot.size)
        assertEquals(1, fileContents.size)

        assertEquals("job.sh", fileNameSlot.first())
        assertEquals(inlineSBatchJob, String(fileContents.first()))
    }

    @Test
    fun testJobPreparationWithFiles() {
        val application = app(
            "singlefile",
            listOf(VariableInvocationParameter(listOf("myFile"))),
            listOf(ApplicationParameter.InputFile("myFile", false))
        )
        createTemporaryApplication(application)

        val fileName = "file.txt"

        val inlineSBatchJob = "job"
        val workingDirectory = "/scratch/sduescience/p/files"
        val event = AppEvent.Validated(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            dummyToken.token,
            dummyTokenSubject,
            application,
            "/scratch/sduescience/p",
            workingDirectory,
            listOf(
                ValidatedFileForUpload(
                    stat(fileName),
                    fileName,
                    "$workingDirectory/$fileName",
                    fileName,
                    null
                )
            ),
            inlineSBatchJob
        )

        val (fileNameSlot, fileContents) = withJobPrepMock {
            service.handleAppEvent(event)
        }

        assertTrue(emitSlot.isNotEmpty())
        emitSlot.first() as AppEvent.Prepared

        assertEquals(2, fileNameSlot.size)
        assertEquals(2, fileContents.size)

        run {
            // Check job file
            val jobSlot = fileNameSlot.indexOfFirst { it == "job.sh" }
            assertNotEquals(-1, jobSlot)
            assertEquals(inlineSBatchJob, String(fileContents[jobSlot]))
        }

        run {
            // Check input file
            val inputFileSlot = fileNameSlot.indexOfFirst { it == fileName }
            assertNotEquals(-1, inputFileSlot)
            assertEquals(fileName, String(fileContents[inputFileSlot]))
        }
    }

    @Test
    fun testJobPreparationWithFilesWithIRodsFailure() {
        val application = app(
            "singlefile",
            listOf(VariableInvocationParameter(listOf("myFile"))),
            listOf(ApplicationParameter.InputFile("myFile", false))
        )
        createTemporaryApplication(application)

        val fileName = "file.txt"
        val inlineSBatchJob = "job"
        val workingDirectory = "/scratch/sduescience/p/files"
        val event = AppEvent.Validated(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            dummyToken.token,
            dummyTokenSubject,
            application,
            "/scratch/sduescience/p",
            workingDirectory,
            listOf(
                ValidatedFileForUpload(
                    stat(fileName),
                    fileName,
                    "$workingDirectory/$fileName",
                    fileName,
                    null
                )
            ),
            inlineSBatchJob
        )

        withJobPrepMock(downloadFailure = true) {
            service.handleAppEvent(event)
        }

        assertTrue(emitSlot.isNotEmpty())
        val outputEvent = emitSlot.first() as AppEvent.Completed
        assertFalse(outputEvent.successful)
    }

    fun withJobPrepMock(
        sshFailure: Boolean = false,
        scpFailure: Boolean = false,
        downloadFailure: Boolean = false,
        body: () -> Unit
    ): Pair<List<String>, List<ByteArray>> {
        return withMockedAuthentication {
            withMockScopes(objectMockk(AuthDescriptions), sftpScope(), objectMockk(FileDescriptions)) {
                every { sshConnection.mkdir(any(), any()) } returns 0

                coEvery {
                    FileDescriptions.download.call(any(), any())
                } answers {
                    if (downloadFailure) RESTResponse.Err(mockk(relaxed = true))
                    else {
                        val command = call.invocation.args.find { it is DownloadByURI } as DownloadByURI
                        val response: HttpResponse = mockk(relaxed = true)
                        every { response.content } answers {
                            ByteReadChannel(command.path.substringAfterLast('/').toByteArray())
                        }

                        RESTResponse.Ok(response, Unit)
                    }
                }

                coEvery {
                    AuthDescriptions.requestOneTimeTokenWithAudience.call(any(), any())
                } returns RESTResponse.Ok(mockk(), OneTimeAccessToken(dummyToken.token, ""))

                return@withMockScopes withMockedSCPUpload(sshFailure = sshFailure, commandFailure = scpFailure) {
                    body()
                }
            }
        }
    }

    @Test
    fun testJobPreparationWithFilesWithUploadFailure() {
        val application = app(
            "singlefile",
            listOf(VariableInvocationParameter(listOf("myFile"))),
            listOf(ApplicationParameter.InputFile("myFile", false))
        )
        createTemporaryApplication(application)

        val fileName = "file.txt"
        val inlineSBatchJob = "job"
        val workingDirectory = "/scratch/sduescience/p/files"
        val event = AppEvent.Validated(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            dummyToken.token,
            dummyTokenSubject,
            application,
            "/scratch/sduescience/p",
            workingDirectory,
            listOf(
                ValidatedFileForUpload(
                    stat(fileName),
                    fileName,
                    "$workingDirectory/$fileName",
                    fileName,
                    null
                )
            ),
            inlineSBatchJob
        )

        withJobPrepMock(scpFailure = true) {
            service.handleAppEvent(event)
        }

        assertTrue(emitSlot.isNotEmpty())
        val outputEvent = emitSlot.first() as AppEvent.Completed
        assertFalse(outputEvent.successful)
    }

    @Test
    fun testJobPreparationWithSSHFailure() {
        val application = app(
            "singlefile",
            listOf(VariableInvocationParameter(listOf("myFile"))),
            listOf(ApplicationParameter.InputFile("myFile", false))
        )
        createTemporaryApplication(application)

        val fileName = "file.txt"

        val inlineSBatchJob = "job"
        val event = AppEvent.Validated(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            dummyToken.token,
            dummyTokenSubject,
            application,
            jobDirectiory,
            workingDirectory,
            listOf(
                ValidatedFileForUpload(
                    stat(fileName),
                    fileName,
                    "$workingDirectory$fileName",
                    fileName,
                    null
                )
            ),
            inlineSBatchJob
        )

        withJobPrepMock(sshFailure = true) {
            service.handleAppEvent(event)
        }

        assertTrue(emitSlot.isNotEmpty())
        val outputEvent = emitSlot.first() as AppEvent.Completed
        assertFalse(outputEvent.successful)
    }

    private inline fun <T> withMockedAuthentication(body: () -> T): T {
        objectMockk(TokenValidation).use {
            every { TokenValidation.validate(dummyToken.token) } returns dummyToken
            every { TokenValidation.validateOrNull(dummyToken.token) } returns dummyToken
            every { TokenValidation.validate(dummyToken.token, any()) } returns dummyToken
            every { TokenValidation.validateOrNull(dummyToken.token, any()) } returns dummyToken
            return body()
        }
    }

    private inline fun withMockedSCPUpload(
        commandFailure: Boolean = false,
        sshFailure: Boolean = false,
        body: () -> Unit
    ): Pair<List<String>, List<ByteArray>> {
        val names = ArrayList<String>()
        val writers = ArrayList<ByteArray>()

        staticMockk("dk.sdu.cloud.app.services.ssh.SCPKt").use {
            if (!sshFailure) {
                every {
                    sshConnection.scpUpload(
                        any(),
                        capture(names),
                        any(),
                        any(),
                        any()
                    )
                } answers {
                    if (!commandFailure) {
                        val os = ByteArrayOutputStream()

                        @Suppress("UNCHECKED_CAST")
                        val writer = call.invocation.args.last() as (OutputStream) -> Unit
                        writer(os)

                        writers.add(os.toByteArray())

                        0
                    } else {
                        writers.add(ByteArray(0))

                        1
                    }
                }
            } else {
                every { sshConnection.scpUpload(any(), any(), any(), any(), any()) } throws JSchException("Bad!")
            }
            body()
        }
        return Pair(names, writers)
    }

    @Test
    fun testValidJobScheduling() {
        val event = AppEvent.Prepared(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            dummyTokenSubject,
            noParamsApplication,
            "nobody",
            jobDirectiory,
            workingDirectory,
            jobDirectiory + "job.sh"
        )

        val batchJobs = withMockedSBatch(jobId = 123L) {
            service.handleAppEvent(event)
        }

        assertTrue(emitSlot.isNotEmpty())
        val outputEvent = emitSlot.first() as AppEvent.ScheduledAtSlurm
        assertEquals(123L, outputEvent.slurmId)

        assertEquals(1, batchJobs.size)
        assertEquals(event.jobScriptLocation, batchJobs.first())
    }

    @Test
    fun testJobSchedulingWithSlurmFailure() {
        val event = AppEvent.Prepared(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            dummyTokenSubject,
            noParamsApplication,
            "nobody",
            jobDirectiory,
            workingDirectory,
            jobDirectiory + "job.sh"
        )

        withMockedSBatch(commandFailure = true, jobId = 123L) {
            service.handleAppEvent(event)
        }

        assertTrue(emitSlot.isNotEmpty())
        val outputEvent = emitSlot.first() as AppEvent.ExecutionCompleted
        assertFalse(outputEvent.successful)
    }

    @Test
    fun testJobSchedulingWithSSHFailure() {
        val event = AppEvent.Prepared(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            dummyTokenSubject,
            noParamsApplication,
            "nobody",
            jobDirectiory,
            workingDirectory,
            jobDirectiory + "job.sh"
        )

        withMockedSBatch(sshFailure = true, jobId = 123L) {
            service.handleAppEvent(event)
        }

        assertTrue(emitSlot.isNotEmpty())
        val outputEvent = emitSlot.first() as AppEvent.ExecutionCompleted
        assertFalse(outputEvent.successful)
    }

    private inline fun withMockedSBatch(
        commandFailure: Boolean = false,
        sshFailure: Boolean = false,
        jobId: Long,
        body: () -> Unit
    ): List<String> {
        val names = ArrayList<String>()
        staticMockk("dk.sdu.cloud.app.services.ssh.SBatchKt").use {
            if (!sshFailure) {
                every { sshConnection.sbatch(capture(names)) } answers {
                    if (commandFailure) SBatchSubmissionResult(1, "Bad", null)
                    else SBatchSubmissionResult(0, "OK", jobId)
                }
            } else {
                every { sshConnection.sbatch(any()) } throws JSchException("Bad!")
            }
            body()
        }

        return names
    }

    @Test
    fun testScheduledAtSlurm() {
        val slurmId = 123L
        val systemId = UUID.randomUUID().toString()
        val owner = dummyTokenSubject
        val sshUser = dummyTokenSubject

        val event = AppEvent.ScheduledAtSlurm(
            systemId,
            System.currentTimeMillis(),
            owner,
            noParamsApplication,
            sshUser,
            jobDirectiory,
            workingDirectory,
            slurmId
        )

        service.handleAppEvent(event)

        verify {
            slurmPollAgent.startTracking(slurmId)
            jobsDao.updateJobWithSlurmInformation(any(), systemId, sshUser, jobDirectiory, workingDirectory, slurmId)
        }

        assertEquals(0, emitSlot.size)
    }

    private val completedInSlurmEvent = AppEvent.CompletedInSlurm(
        UUID.randomUUID().toString(),
        System.currentTimeMillis(),
        dummyTokenSubject,
        applicationWithOutputs,
        dummyTokenSubject,
        jobDirectiory,
        workingDirectory,
        true,
        123L
    )

    @Test
    fun testShippingResultsWithDirectoryFailure() {
        objectMockk(FileDescriptions).use {
            val directoryCall =
                mockk<RESTCallDescription<CreateDirectoryRequest, LongRunningResponse<Unit>, CommonErrorMessage>>()
            every { FileDescriptions.createDirectory } returns directoryCall

            coEvery { directoryCall.call(any(), any()) } returns RESTResponse.Err(mockk(relaxed = true))

            service.handleAppEvent(completedInSlurmEvent)
            assertEquals(1, emitSlot.size)
            val outputEvent = emitSlot.first() as AppEvent.ExecutionCompleted
            assertFalse(outputEvent.successful)
        }
    }

    @Test
    fun testShippingResultsWithNoOutputFiles() {
        withMockScopes(objectMockk(FileDescriptions), sftpScope()) {
            val directoryCall =
                mockk<RESTCallDescription<CreateDirectoryRequest, LongRunningResponse<Unit>, CommonErrorMessage>>()
            every { FileDescriptions.createDirectory } returns directoryCall
            coEvery { directoryCall.call(any(), any()) } returns RESTResponse.Ok(
                mockk(relaxed = true),
                LongRunningResponse.Result(Unit)
            )

            every { sshConnection.lsWithGlob(any(), any()) } returns emptyList()

            service.handleAppEvent(completedInSlurmEvent)
            assertEquals(1, emitSlot.size)

            val outputEvent = emitSlot.first() as AppEvent.ExecutionCompleted
            assertTrue(outputEvent.successful)

            completedInSlurmEvent.appWithDependencies.description.outputFileGlobs.forEach {
                verify { sshConnection.lsWithGlob(workingDirectory, it) }
            }
        }
    }

    @Test
    fun testShippingResultWithSingleFile() {
        withAllShippingScopes {
            mockCreateDirectoryCall(success = true)
            every { sshConnection.lsWithGlob(any(), any()) } returns emptyList()

            run {
                // Single file present
                every { sshConnection.lsWithGlob(workingDirectory, singleFileGlob) } returns listOf(
                    LSWithGlobResult(workingDirectory + singleFileGlob, 10L)
                )

                mockStatForRemoteFile(workingDirectory + singleFileGlob, 10L, false)
            }

            mockUpload()
            val remoteFiles = mockScpDownloadAndGetFileList()

            // Run tests
            service.handleAppEvent(completedInSlurmEvent)

            // Check results
            assertEquals(1, emitSlot.size)
            val outputEvent = emitSlot.first() as AppEvent.ExecutionCompleted
            assertTrue(outputEvent.successful)


            completedInSlurmEvent.appWithDependencies.description.outputFileGlobs.forEach {
                verify { sshConnection.lsWithGlob(workingDirectory, it) }
            }

            assertEquals(1, remoteFiles.size)
            assertEquals(workingDirectory + singleFileGlob, remoteFiles.first())
        }
    }

    @Test
    fun testShippingResultWithMultipleFiles() {
        withAllShippingScopes {
            mockCreateDirectoryCall(success = true)
            every { sshConnection.lsWithGlob(any(), any()) } returns emptyList()
            val fileNames = listOf("1.txt", "2.txt", "3.txt")

            run {
                every { sshConnection.lsWithGlob(workingDirectory, txtFilesGlob) } returns fileNames.map {
                    LSWithGlobResult(workingDirectory + it, 10L)
                }

                fileNames.forEach {
                    mockStatForRemoteFile(workingDirectory + it, 10L, false)
                }
            }

            mockUpload()
            val remoteFiles = mockScpDownloadAndGetFileList()

            // Run tests
            service.handleAppEvent(completedInSlurmEvent)

            // Check results
            assertEquals(1, emitSlot.size)
            val outputEvent = emitSlot.first() as AppEvent.ExecutionCompleted
            assertTrue(outputEvent.successful)

            completedInSlurmEvent.appWithDependencies.description.outputFileGlobs.forEach {
                verify { sshConnection.lsWithGlob(workingDirectory, it) }
            }

            assertEquals(fileNames.size, remoteFiles.size)
            val expectedRemotes = fileNames.map { workingDirectory + it }.sorted()
            val actualRemote = remoteFiles.sorted()
            assertEquals(expectedRemotes, actualRemote)
        }
    }

    @Test
    fun testShippingResultsWithDirectory() {
        withAllShippingScopes {
            mockCreateDirectoryCall(success = true)
            every { sshConnection.lsWithGlob(any(), any()) } returns emptyList()
            val fileNames = "c/"

            run {
                every { sshConnection.lsWithGlob(workingDirectory, directoryGlob) } returns listOf(fileNames).map {
                    LSWithGlobResult(workingDirectory + it, 10L)
                }

                fileNames.forEach {
                    mockStatForRemoteFile(workingDirectory + it, 10L, true)
                }
            }

            mockUpload()
            val remoteFiles = mockScpDownloadAndGetFileList()
            val (zipOutputs, zipInputs) = mockZipCall(commandFailure = false)
            val expectedZipOutput = workingDirectory + "c.zip"
            mockStatForRemoteFile(expectedZipOutput, 10L, false)

            // Run tests
            service.handleAppEvent(completedInSlurmEvent)

            // Check results
            assertEquals(1, emitSlot.size)
            val outputEvent = emitSlot.first() as AppEvent.ExecutionCompleted
            assertTrue(outputEvent.successful)


            completedInSlurmEvent.appWithDependencies.description.outputFileGlobs.forEach {
                verify { sshConnection.lsWithGlob(workingDirectory, it) }
            }

            assertEquals(1, remoteFiles.size)
            assertEquals(expectedZipOutput, remoteFiles.first())

            verify(exactly = 1) { sshConnection.stat(expectedZipOutput) }
            assertEquals(1, zipOutputs.size)
            assertEquals(expectedZipOutput, zipOutputs.first())
            assertEquals(1, zipInputs.size)
            assertEquals(workingDirectory + fileNames.removeSuffix("/"), zipInputs.first())
        }
    }

    @Test
    fun testShippingResultWithSingleFileUploadCreationFailure() {
        withAllShippingScopes {
            mockCreateDirectoryCall(success = true)
            every { sshConnection.lsWithGlob(any(), any()) } returns emptyList()

            run {
                // Single file present
                every { sshConnection.lsWithGlob(workingDirectory, singleFileGlob) } returns listOf(
                    LSWithGlobResult(workingDirectory + singleFileGlob, 10L)
                )

                mockStatForRemoteFile(workingDirectory + singleFileGlob, 10L, false)
            }

            mockUpload(commandFailure = true)
            mockScpDownloadAndGetFileList()

            // Run tests
            service.handleAppEvent(completedInSlurmEvent)

            val outputEvent = emitSlot.single() as AppEvent.ExecutionCompleted
            assertFalse(outputEvent.successful)
        }
    }

    @Test
    fun testShippingResultWithSingleFileUploadTransferFailure() {
        withAllShippingScopes {
            mockCreateDirectoryCall(success = true)
            every { sshConnection.lsWithGlob(any(), any()) } returns emptyList()

            run {
                // Single file present
                every { sshConnection.lsWithGlob(workingDirectory, singleFileGlob) } returns listOf(
                    LSWithGlobResult(workingDirectory + singleFileGlob, 10L)
                )

                mockStatForRemoteFile(workingDirectory + singleFileGlob, 10L, false)
            }

            mockUpload()
            mockScpDownloadAndGetFileList(commandFailure = true)

            // Run tests
            service.handleAppEvent(completedInSlurmEvent)

            // Check results
            assertEquals(1, emitSlot.size)
            val outputEvent = emitSlot.first() as AppEvent.ExecutionCompleted
            assertFalse(outputEvent.successful)
        }
    }


    private fun withAllShippingScopes(body: () -> Unit) {
        withMockScopes(
            objectMockk(FileDescriptions),

            uploadScope(),

            sftpScope(),
            scpScope(),
            zipScope()
        ) {
            body()
        }
    }

    data class DirectoryZipMock(val outputs: List<String>, val inputs: List<String>)

    private fun mockZipCall(commandFailure: Boolean = false): DirectoryZipMock {
        val outputs = ArrayList<String>()
        val inputs = ArrayList<String>()
        if (!commandFailure) {
            every { sshConnection.createZipFileOfDirectory(capture(outputs), capture(inputs)) } returns 0
        } else {
            every { sshConnection.createZipFileOfDirectory(capture(outputs), capture(inputs)) } returns 1
        }
        return DirectoryZipMock(outputs, inputs)
    }

    private fun mockCreateDirectoryCall(
        success: Boolean
    ): RESTCallDescription<CreateDirectoryRequest, LongRunningResponse<Unit>, CommonErrorMessage> {
        val directoryCall =
            mockk<RESTCallDescription<CreateDirectoryRequest, LongRunningResponse<Unit>, CommonErrorMessage>>()
        every { FileDescriptions.createDirectory } returns directoryCall
        if (success) {
            coEvery { directoryCall.call(any(), any()) } returns RESTResponse.Ok(
                mockk(relaxed = true),
                LongRunningResponse.Result(Unit)
            )
        } else {
            coEvery { directoryCall.call(any(), any()) } returns RESTResponse.Err(mockk(relaxed = true))
        }
        return directoryCall
    }

    private fun mockUpload(commandFailure: Boolean = false) {
        fun answer() {
            if (commandFailure) throw IOException()
        }

        every {
            MultiPartUploadDescriptions.callUpload(any(), any(), any(), any(), any(), any(), any())
        } answers { answer() }

        every {
            MultiPartUploadDescriptions.callUpload(
                any(),
                any<RefreshingJWTAuthenticator>(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } answers { answer() }

        every {
            MultiPartUploadDescriptions.callUpload(any(), any<String>(), any(), any(), any(), any(), any(), any())
        } answers { answer() }
    }

    private fun mockScpDownloadAndGetFileList(commandFailure: Boolean = false): List<String> {
        val remoteFiles = ArrayList<String>()
        if (!commandFailure) {
            every {
                sshConnection.scpDownload(
                    capture(remoteFiles),
                    any()
                )
            } answers {
                @Suppress("UNCHECKED_CAST")
                val reader = call.invocation.args.last() as (InputStream) -> Unit
                reader(ByteArrayInputStream(ByteArray(0)))
                0
            }
        } else {
            every {
                sshConnection.scpDownload(
                    capture(remoteFiles),
                    any()
                )
            } returns 1
        }

        return remoteFiles
    }

    private fun mockStatForRemoteFile(absolutePath: String, size: Long, isDir: Boolean) {
        val returnedFile: SftpATTRS = mockk(relaxed = true)
        every { sshConnection.stat(absolutePath) } returns returnedFile
        every { returnedFile.size } returns size
        every { returnedFile.isDir } returns isDir
    }

    @Test
    fun testCleanup() {
        withMockScopes(sftpScope()) {
            val event = AppEvent.ExecutionCompleted(
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                dummyTokenSubject,
                noParamsApplication,
                "nobody",
                jobDirectiory,
                workingDirectory,
                true,
                "Foo"
            )

            every { sshConnection.rm(any(), any(), any()) } returns 0

            service.handleAppEvent(event)

            assertEquals(1, emitSlot.size)
            val outputEvent = emitSlot.first() as AppEvent.Completed
            assertEquals(event.successful, outputEvent.successful)

            verify { sshConnection.rm(jobDirectiory, true, true) }
        }
    }

    @Test
    fun testCleanupWithDeletionFailure() {
        withMockScopes(sftpScope()) {
            val event = AppEvent.ExecutionCompleted(
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                dummyTokenSubject,
                noParamsApplication,
                "nobody",
                jobDirectiory,
                workingDirectory,
                true,
                "Foo"
            )

            every { sshConnection.rm(any(), any(), any()) } returns 1

            service.handleAppEvent(event)

            assertEquals(1, emitSlot.size)
            val outputEvent = emitSlot.first() as AppEvent.Completed
            assertEquals(event.successful, outputEvent.successful)

            verify { sshConnection.rm(jobDirectiory, true, true) }
        }
    }
}

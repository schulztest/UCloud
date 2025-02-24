package dk.sdu.cloud.integration.backend.accounting

import dk.sdu.cloud.Role
import dk.sdu.cloud.accounting.api.ProductCategoryId
import dk.sdu.cloud.accounting.api.Wallet
import dk.sdu.cloud.accounting.api.WalletBrowseRequest
import dk.sdu.cloud.accounting.api.Wallets
import dk.sdu.cloud.accounting.api.grants.*
import dk.sdu.cloud.accounting.api.projects.*
import dk.sdu.cloud.auth.api.*
import dk.sdu.cloud.base64Encode
import dk.sdu.cloud.calls.HttpStatusCode
import dk.sdu.cloud.calls.RPCException
import dk.sdu.cloud.calls.bulkRequestOf
import dk.sdu.cloud.calls.client.*
import dk.sdu.cloud.grant.api.*
import dk.sdu.cloud.integration.IntegrationTest
import dk.sdu.cloud.integration.UCloudTestCaseBuilder
import dk.sdu.cloud.integration.rpcClient
import dk.sdu.cloud.integration.serviceClient
import dk.sdu.cloud.integration.utils.*
import dk.sdu.cloud.project.api.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrantTest : IntegrationTest() {
    sealed class CommentPoster {
        object User : CommentPoster()
        object Pi : CommentPoster()
        class Admin(val idx: Int) : CommentPoster()
    }

    override fun defineTests() {
        testFilter = {title, subtitle ->
            title == "Grant applications, metadata"
        }
        run {
            class In(
                val userCriterias: List<UserCriteria>,
                val resourceRequests: List<Long>
            )
            class Out(
                val applicationStatus: GrantApplication.State
            )

            test<In, Out>("Auto approval tests") {
                execute {
                    createSampleProducts()
                    val root = initializeRootProject(setOf(UCLOUD_PROVIDER))
                    val applier = createUser("userApply-${UUID.randomUUID()}", email = "mojn@schulz.dk")

                    val createdProject = initializeNormalProject(root)

                    GrantsEnabled.setEnabledStatus.call(
                        bulkRequestOf(
                            SetEnabledStatusRequest(
                                createdProject.projectId,
                                true
                            )
                        ),
                        serviceClient
                    ).orThrow()

                    val uploadSettings =
                        ProjectApplicationSettings(
                            createdProject.projectId,
                            automaticApproval = AutomaticApprovalSettings(
                                input.userCriterias,
                                listOf(
                                    GrantApplication.AllocationRequest(
                                        sampleCompute.category.name,
                                        sampleCompute.category.provider,
                                        createdProject.projectId,
                                        1000,
                                        period = GrantApplication.Period(
                                            null,
                                            null,
                                        )
                                    ),
                                    GrantApplication.AllocationRequest(
                                        sampleStorage.category.name,
                                        sampleStorage.category.provider,
                                        createdProject.projectId,
                                        500,
                                        period = GrantApplication.Period(
                                            null,
                                            null,
                                        )
                                    )
                                )
                            ),
                            allowRequestsFrom = listOf(UserCriteria.Anyone()),
                            excludeRequestsFrom = listOf()
                        )
                    val listOfResourcesRequests = input.resourceRequests.map {
                        GrantApplication.AllocationRequest(
                            sampleCompute.category.name,
                            sampleCompute.category.provider,
                            createdProject.projectId,
                            it,
                            period = GrantApplication.Period(
                                null,
                                null,
                            )
                        )
                    }

                    GrantSettings.uploadRequestSettings.call(
                        bulkRequestOf(
                            uploadSettings
                        ),
                        createdProject.piClient.withProject(createdProject.projectId)
                    ).orThrow()

                    val applicationId = Grants.submitApplication.call(
                        bulkRequestOf(
                            SubmitApplicationRequest(
                                GrantApplication.Document(
                                    GrantApplication.Recipient.NewProject("Say hello to my little friend"),
                                    listOfResourcesRequests,
                                    GrantApplication.Form.PlainText("I would like resources"),
                                    "reference",
                                    "revision Comments",
                                    createdProject.projectId
                                )
                            )
                        ),
                        applier.client
                    ).orThrow()

                    val appStatus = Grants.retrieveApplication.call(
                        RetrieveApplicationRequest(applicationId.responses.first().id),
                        applier.client
                    ).orThrow()

                    Out(appStatus.status.overallState)
                }
                case("auto approve full fail check") {
                    input(
                        In(
                            userCriterias = listOf(
                                UserCriteria.EmailDomain("wrong.dk")
                            ),
                            resourceRequests = listOf(
                                1200
                            )
                        )
                    )
                    check {
                        assertEquals(GrantApplication.State.IN_PROGRESS, output.applicationStatus)
                    }
                }

                case("auto approve full accept check") {
                    input(
                        In(
                            userCriterias = listOf(UserCriteria.EmailDomain("schulz.dk")),
                            resourceRequests = listOf(800)
                        )
                    )
                    check {
                        assertEquals(GrantApplication.State.APPROVED, output.applicationStatus)
                    }
                }

                case("auto approve partial fail check") {
                    input(
                        In(
                            userCriterias = listOf(UserCriteria.EmailDomain("wrong.dk")),
                            resourceRequests = listOf(800)
                        )
                    )
                    check {
                        assertEquals(GrantApplication.State.IN_PROGRESS, output.applicationStatus)
                    }
                }
            }

        }

        run {
            class Comment(val poster: CommentPoster, val commentToPost: String)
            class SimpleResourceRequest(val category: String, val provider: String, val balance: Long)
            class In(
                val outcome: GrantApplication.State?,
                val grantRecipient: GrantApplication.Recipient,
                val resourcesRequested: List<SimpleResourceRequest>,
                val allowList: List<UserCriteria> = listOf(UserCriteria.Anyone()),
                val excludeList: List<UserCriteria> = listOf(),
                val numberOfProjectAdmins: Int = 0,
                val comments: List<Comment> = emptyList(),
                val ifExistingDoCreate: Boolean = true,
                val username: String? = null,
                val userOrganization: String? = "ucloud.dk",
                val userEmail: String = "ucloud@ucloud.dk",
                val initialDocument: String = "This is my document",
                val document: String = initialDocument,
                val changeStatusBy: CommentPoster = CommentPoster.Pi,
            )

            class Out(
                val grantApplication: GrantApplication,
                val projectsOfUser: List<UserProjectSummary>,
                val walletsOfUser: List<Wallet>,
                val childProjectsOfGrantGiver: List<MemberInProject>,
                val walletsOfGrantGiver: List<Wallet>,
            )

            test<In, Out>("Grant applications, expected flow") {
                execute {
                    createSampleProducts()
                    val uniqueAdmin = createUser( role = Role.ADMIN)
                    val root = initializeRootProject(setOf(UCLOUD_PROVIDER), uniqueAdmin.client)
                    val grantAdmins = (0 until input.numberOfProjectAdmins).map {
                        createUser("admin-${UUID.randomUUID()}")
                    }
                    val evilUser = createUser("evil-${UUID.randomUUID()}")
                    println(input.grantRecipient)
                    val normalUser = createUser(
                            username = input.username ?: "user-${testId}",
                            email = input.userEmail,
                            organization = input.userOrganization
                        )

                    val createdProject = initializeNormalProject(root, admin = uniqueAdmin)

                    val walletAllocations = Wallets.browse.call(
                        WalletBrowseRequest(),
                        createdProject.piClient.withProject(createdProject.projectId)
                    ).orThrow()

                    val requestedResources = input.resourcesRequested.map {requested ->
                        val alloc = walletAllocations.items.find { it.paysFor == ProductCategoryId(requested.category, requested.provider) }
                        GrantApplication.AllocationRequest(
                            requested.category,
                            requested.provider,
                            createdProject.projectId,
                            requested.balance,
                            period = GrantApplication.Period(
                                null,
                                null
                            ),
                            sourceAllocation = alloc?.allocations?.singleOrNull()?.id?.toLong()
                        )
                    }

                    if (grantAdmins.isNotEmpty()) {
                        Projects.invite.call(
                            InviteRequest(createdProject.projectId, grantAdmins.map { it.username }.toSet()),
                            createdProject.piClient
                        ).orThrow()

                        for (admin in grantAdmins) {
                            Projects.acceptInvite.call(AcceptInviteRequest(createdProject.projectId), admin.client).orThrow()
                            Projects.changeUserRole.call(
                                ChangeUserRoleRequest(createdProject.projectId, admin.username, ProjectRole.ADMIN),
                                createdProject.piClient
                            ).orThrow()
                        }
                    }

                    var actualRecipient = input.grantRecipient
                    println(actualRecipient)
                    if (input.grantRecipient is GrantApplication.Recipient.ExistingProject && input.ifExistingDoCreate) {
                        val created = Projects.create.call(
                            CreateProjectRequest(
                                "Existing child-${UUID.randomUUID()}",
                                parent = createdProject.projectId,
                                principalInvestigator = normalUser.username
                            ),
                            createdProject.piClient
                        ).orThrow()
                        Projects.invite.call(InviteRequest(created.id, setOf(normalUser.username)), createdProject.piClient)
                            .orThrow()
                        Projects.acceptInvite.call(AcceptInviteRequest(created.id), normalUser.client).orThrow()
                        Projects.transferPiRole.call(
                            TransferPiRoleRequest(normalUser.username),
                            createdProject.piClient.withProject(created.id)
                        ).orThrow()
                        Projects.leaveProject.call(LeaveProjectRequest, createdProject.piClient.withProject(created.id))
                            .orThrow()
                        actualRecipient = GrantApplication.Recipient.ExistingProject(created.id)
                    }

                    GrantsEnabled.setEnabledStatus.call(
                        bulkRequestOf(SetEnabledStatusRequest(createdProject.projectId, true)), serviceClient).orThrow()
                    assertTrue(
                        GrantsEnabled.isEnabled.call(IsEnabledRequest(createdProject.projectId), createdProject.piClient).orThrow().enabled
                    )

                    val initialSettings = GrantSettings.retrieveRequestSettings
                        .call(RetrieveRequestSettingsRequest(createdProject.projectId), createdProject.piClient).orThrow()

                    assertThatInstance(initialSettings, "has no default allow") {
                        it.allowRequestsFrom.isEmpty()
                    }
                    assertThatInstance(initialSettings, "has no default auto-approve") {
                        it.automaticApproval.from.isEmpty() && it.automaticApproval.maxResources.isEmpty()
                    }
                    assertThatInstance(initialSettings, "has no default exclude") {
                        it.excludeRequestsFrom.isEmpty()
                    }

                    val settingsRequest = UploadRequestSettingsRequest(
                        createdProject.projectId,
                        AutomaticApprovalSettings(emptyList(), emptyList()),
                        input.allowList,
                        input.excludeList
                    )

                    GrantSettings.uploadRequestSettings.call(
                        bulkRequestOf(settingsRequest),
                        createdProject.piClient.withProject(createdProject.projectId)
                    ).orThrow()

                    GrantSettings.uploadRequestSettings.call(
                        bulkRequestOf(
                            settingsRequest.copy(allowRequestsFrom = listOf(UserCriteria.WayfOrganization("Evil Corp")))
                        ),
                        evilUser.client.withProject(createdProject.projectId)
                    ).assertUserError()

                    val settingsAfterUpload = GrantSettings.retrieveRequestSettings
                        .call(RetrieveRequestSettingsRequest(createdProject.projectId), createdProject.piClient).orThrow()

                    assertThatInstance(settingsAfterUpload, "has the new allow list") {
                        it.allowRequestsFrom == input.allowList
                    }
                    assertThatInstance(settingsAfterUpload, "has no auto-approve") {
                        it.automaticApproval.from.isEmpty() && it.automaticApproval.maxResources.isEmpty()
                    }
                    assertThatInstance(settingsAfterUpload, "has the new exclude") {
                        it.excludeRequestsFrom == input.excludeList
                    }

                    val projectsToChoseFrom = Grants.browseProjects.call(
                        BrowseProjectsRequest(),
                        normalUser.client
                    ).orThrow()

                    // NOTE(Dan): Don't throw yet since submitApplication might not work
                    val productsToChoose = Grants.browseProducts.call(
                        GrantsBrowseProductsRequest(
                            createdProject.projectId,
                            when (actualRecipient) {
                                is GrantApplication.Recipient.ExistingProject -> GrantApplication.Recipient.EXISTING_PROJECT_TYPE
                                is GrantApplication.Recipient.NewProject -> GrantApplication.Recipient.NEW_PROJECT_TYPE
                                is GrantApplication.Recipient.PersonalWorkspace -> GrantApplication.Recipient.PERSONAL_TYPE
                            },
                            when (actualRecipient) {
                                is GrantApplication.Recipient.ExistingProject -> actualRecipient.id
                                is GrantApplication.Recipient.NewProject -> actualRecipient.title
                                is GrantApplication.Recipient.PersonalWorkspace -> actualRecipient.username
                            }
                        ),
                        normalUser.client
                    )

                    val applicationId = Grants.submitApplication.call(
                        bulkRequestOf(
                            SubmitApplicationRequest(
                                GrantApplication.Document(
                                    actualRecipient,
                                    requestedResources,
                                    GrantApplication.Form.PlainText(input.initialDocument),
                                    null,
                                    "revision",
                                    createdProject.projectId
                                )
                            )
                        ),
                        normalUser.client
                    ).orThrow().responses.first().id

                    // If we manage to submit the application then we must be able to see the project in
                    // `browseProjects`
                    assertThatInstance(projectsToChoseFrom, "has the project") { page ->
                        page.items.any { it.projectId == createdProject.projectId }
                    }

                    // Also check that the products were visible
                    val allProducts = productsToChoose.orThrow().availableProducts.groupBy { it.category }
                    for (request in input.resourcesRequested) {
                        assertThatInstance(allProducts, "has the product for $request") {
                            allProducts[ProductCategoryId(request.category, request.provider)] != null
                        }
                    }

                    // Verify that the application is visible as an ingoing and as an outgoing application
                    assertThatInstance(
                        Grants.browseApplications.call(
                            BrowseApplicationsRequest(
                                includeIngoingApplications = true
                            ),
                            createdProject.piClient.withProject(createdProject.projectId)
                        ).orThrow().items,
                        "has the ingoing application"
                    ) {
                        (it.size == 1) && (it.single().createdBy == normalUser.username) &&
                            (it.single().id == applicationId.toString())
                    }

                    assertThatInstance(
                        Grants.browseApplications.call(
                            BrowseApplicationsRequest(
                                includeOutgoingApplications = true
                            ),
                            normalUser.client.let {
                                val projectIdMaybe = (actualRecipient as? GrantApplication.Recipient.ExistingProject)?.id
                                if (projectIdMaybe != null) it.withProject(projectIdMaybe)
                                else it
                            }
                        ).orThrow().items,
                        "has the outgoing application"
                    ) {
                        (it.size == 1) && (it.single().createdBy == normalUser.username) &&
                            (it.single().id == applicationId.toString())
                    }

                    assertThatInstance(
                        Grants.browseApplications.call(
                            BrowseApplicationsRequest(),
                            evilUser.client.withProject(createdProject.projectId)
                        ).orNull()?.items ?: emptyList(),
                        "is empty or fails"
                    ) { it.isEmpty() }

                    assertThatInstance(
                        Grants.browseApplications.call(
                            BrowseApplicationsRequest(),
                            evilUser.client
                        ).orThrow(),
                        "is empty"
                    ) { it.items.isEmpty() }

                    // Create and delete a single comment (it shouldn't affect the output)
                    GrantComments.createComment.call(
                        bulkRequestOf(
                            CreateCommentRequest(applicationId.toString(), "To be deleted!")
                        ),
                        createdProject.piClient
                    ).orThrow()

                    val commentId = Grants.retrieveApplication.call(RetrieveApplicationRequest(applicationId), createdProject.piClient)
                        .orThrow().status.comments.singleOrNull()?.id ?: error("found no comment")
                    GrantComments.deleteComment.call(bulkRequestOf(DeleteCommentRequest(applicationId.toString(), commentId)), evilUser.client).assertUserError()
                    GrantComments.deleteComment.call(bulkRequestOf(DeleteCommentRequest(applicationId.toString(), commentId)), createdProject.piClient).orThrow()

                    for (comment in input.comments) {
                        GrantComments.createComment.call(
                            bulkRequestOf(
                                CreateCommentRequest(applicationId.toString(), comment.commentToPost)
                            ),
                            when (comment.poster) {
                                is CommentPoster.Admin -> grantAdmins[comment.poster.idx].client
                                CommentPoster.Pi -> createdProject.piClient
                                CommentPoster.User -> normalUser.client
                            }
                        ).orThrow()
                    }

                    GrantComments.createComment.call(
                        bulkRequestOf(
                            CreateCommentRequest(applicationId.toString(), "Should fail")
                        ),
                        evilUser.client
                    ).assertUserError()

                    if (input.initialDocument != input.document) {
                        Grants.editApplication.call(
                            bulkRequestOf(
                                EditApplicationRequest(
                                    applicationId,
                                    GrantApplication.Document(
                                        actualRecipient,
                                        emptyList(),
                                        GrantApplication.Form.PlainText(input.document),
                                    )
                                )
                            ),
                            normalUser.client
                        ).orThrow()
                    }

                    Grants.editApplication.call(
                        bulkRequestOf(
                            EditApplicationRequest(
                                applicationId,
                                GrantApplication.Document(
                                    actualRecipient,
                                    requestedResources,
                                    GrantApplication.Form.PlainText("Totally wrong document which should be updated"),
                                    parentProjectId = createdProject.projectId
                                )
                            )
                        ),
                        createdProject.piClient
                    ).orThrow()

                    Grants.editApplication.call(
                        bulkRequestOf(
                            EditApplicationRequest(
                                applicationId,
                                GrantApplication.Document(
                                    actualRecipient,
                                    requestedResources.map { it.copy(balanceRequested = 1337.DKK) },
                                    GrantApplication.Form.PlainText("Evil document"),
                                    parentProjectId = createdProject.projectId
                                )
                            )
                        ),
                        evilUser.client
                    ).assertUserError()

                    val clientToChange = when (val change = input.changeStatusBy) {
                        is CommentPoster.Admin -> grantAdmins[change.idx].client.withProject(createdProject.projectId)
                        CommentPoster.Pi -> createdProject.piClient.withProject(createdProject.projectId)
                        CommentPoster.User -> normalUser.client
                    }
                    when (input.outcome) {
                        GrantApplication.State.APPROVED -> {
                            Grants.updateApplicationState.call(
                                bulkRequestOf(
                                    UpdateApplicationState(
                                        applicationId,
                                        GrantApplication.State.APPROVED,
                                        false
                                    )
                                ),
                                clientToChange
                            ).orThrow()
                        }
                        GrantApplication.State.REJECTED -> {
                            Grants.updateApplicationState.call(
                                bulkRequestOf(
                                    UpdateApplicationState(
                                        applicationId,
                                        GrantApplication.State.REJECTED,
                                        false
                                    )
                                ),
                                clientToChange
                            ).orThrow()
                        }
                        GrantApplication.State.CLOSED -> {
                            Grants.closeApplication.call(
                                bulkRequestOf(
                                    CloseApplicationRequest(applicationId.toString())
                                ),
                                clientToChange
                            ).orThrow()
                        }
                        else -> {
                            // Do nothing
                        }
                    }

                    Grants.updateApplicationState.call(
                        bulkRequestOf(
                            UpdateApplicationState(applicationId, GrantApplication.State.APPROVED, false)
                        ),
                        evilUser.client
                    ).assertUserError()
                    Grants.updateApplicationState.call(
                        bulkRequestOf(
                            UpdateApplicationState(applicationId, GrantApplication.State.REJECTED, false)
                        ),
                        evilUser.client
                    ).assertUserError()
                    Grants.closeApplication.call(
                        bulkRequestOf(
                            CloseApplicationRequest(applicationId.toString())
                        ),
                        evilUser.client
                    ).assertUserError()

                    val outputApplication =
                        Grants.retrieveApplication.call(RetrieveApplicationRequest(applicationId), normalUser.client).orThrow()

                    Grants.retrieveApplication.call(RetrieveApplicationRequest(applicationId), evilUser.client)
                        .assertUserError()

                    val userProjects = Projects.listProjects.call(ListProjectsRequest(), normalUser.client)
                        .orThrow().items
                    val childProjects = Projects.listSubProjects.call(
                        ListSubProjectsRequest(),
                        createdProject.piClient.withProject(createdProject.projectId)
                    ).orThrow().items

                    val userWallets = when (input.grantRecipient) {
                        is GrantApplication.Recipient.ExistingProject, is GrantApplication.Recipient.NewProject -> {
                            val project = userProjects.singleOrNull()?.projectId
                            if (project == null) emptyList()
                            else Wallets.browse.call(
                                WalletBrowseRequest(),
                                normalUser.client.withProject(project)
                            ).orThrow().items
                        }
                        is GrantApplication.Recipient.PersonalWorkspace -> {
                            Wallets.browse.call(WalletBrowseRequest(), normalUser.client).orThrow().items
                        }
                    }
                    // while (true) {delay(50)}
                    val grantWallets = Wallets.browse.call(
                        WalletBrowseRequest(),
                        createdProject.piClient.withProject(createdProject.projectId)
                    ).orThrow().items

                    Out(
                        outputApplication,
                        userProjects,
                        userWallets,
                        childProjects,
                        grantWallets
                    )
                }

                fun UCloudTestCaseBuilder<In, Out>.checkSuccess() {
                    check {
                        val outputComments = output.grantApplication.status.comments
                        println(outputComments)
                        val inputComments = input.comments
                        inputComments.forEach {
                            println(it.poster)
                            println(it.commentToPost)
                        }
                        assertThatInstance(outputComments, "has the correct size") { it.size == inputComments.size }
                        for ((inputComment, outputComment) in inputComments.zip(outputComments)) {
                            assertThatPropertyEquals(outputComment, { it.comment }, inputComment.commentToPost)
                            assertThatInstance(outputComment, "should match poster") {
                                it.username.startsWith(
                                    when (inputComment.poster) {
                                        is CommentPoster.Admin -> "admin-"
                                        CommentPoster.User -> "user-"
                                        CommentPoster.Pi -> "pi-"
                                    }
                                )
                            }
                        }
                    }

                    check {
                        assertThatPropertyEquals(
                            output.grantApplication,
                            { it.status.overallState },
                            input.outcome ?: GrantApplication.State.IN_PROGRESS,
                            "actual outcome should match expected outcome"
                        )
                    }

                    check {
                        when (val recipient = input.grantRecipient) {
                            is GrantApplication.Recipient.ExistingProject -> {
                                assertThatInstance(output.projectsOfUser) {
                                    if (input.ifExistingDoCreate) {
                                        it.size == 1
                                    } else {
                                        it.isEmpty()
                                    }
                                }
                            }
                            is GrantApplication.Recipient.NewProject -> {
                                assertThatInstance(output.projectsOfUser) {
                                    if (input.outcome == GrantApplication.State.APPROVED) {
                                        it.size == 1 && it.single().title == recipient.title
                                    } else {
                                        it.isEmpty()
                                    }
                                }
                            }
                            is GrantApplication.Recipient.PersonalWorkspace -> {
                                assertThatInstance(output.projectsOfUser) { it.isEmpty() }
                            }
                        }
                    }

                    check {
                        assertThatInstance(output.walletsOfUser, "should have the expected size") {
                            input.outcome != GrantApplication.State.APPROVED || it.size == input.resourcesRequested.size
                        }

                        if (input.outcome == GrantApplication.State.APPROVED) {
                            for (wallet in output.walletsOfUser) {
                                val resolvedRequest = input.resourcesRequested.find {
                                    it.category == wallet.paysFor.name &&
                                        it.provider == wallet.paysFor.provider
                                } ?: throw AssertionError("Received wallet but no such request was made: $wallet")

                                val expectedBalance = resolvedRequest.balance
                                assertThatPropertyEquals(
                                    wallet,
                                    { it.allocations.sumOf { it.balance } },
                                    expectedBalance
                                )
                            }
                        }
                    }

                    check {
                        when (val recipient = input.grantRecipient) {
                            is GrantApplication.Recipient.NewProject, is GrantApplication.Recipient.ExistingProject -> {
                                if (input.outcome == GrantApplication.State.APPROVED) {
                                    assertThatInstance(output.childProjectsOfGrantGiver) { it.size == 1 }
                                }
                            }

                            is GrantApplication.Recipient.PersonalWorkspace -> {
                                assertThatInstance(output.childProjectsOfGrantGiver) { it.size == 0 }
                            }
                        }
                    }
                }

                GrantApplication.State.values().forEach { outcome ->
                    var username = "user-${UUID.randomUUID()}"
                    listOf(
                        GrantApplication.Recipient.NewProject("MyProject-${UUID.randomUUID()}"),
                        GrantApplication.Recipient.ExistingProject("replaced-${UUID.randomUUID()}"),
                        GrantApplication.Recipient.PersonalWorkspace(username)
                    ).forEach { recipient ->
                        listOf(0, 1).forEach { numberOfComments ->
                            (0..2).forEach { numberOfAdmins ->
                                val realrecept = when (recipient) {
                                    is GrantApplication.Recipient.PersonalWorkspace -> {
                                        //has to create a new username for personal workspaces to avoid conflicts
                                        username = "user-${UUID.randomUUID()}"
                                        recipient.copy(username)
                                    }

                                    is GrantApplication.Recipient.NewProject -> {
                                        //has to create a new projectname for new projects to avoid conflicts
                                        recipient.copy("MyProject-${UUID.randomUUID()}")
                                    }

                                    else -> recipient
                                }

                                case(buildString {
                                    append("happy path (")
                                    append("outcome = ")
                                    append(outcome)
                                    append(", recipient = ")
                                    append(recipient)
                                    append(", numberOfAdmins = ")
                                    append(numberOfAdmins)
                                    append(", numberOfComments = ")
                                    append(numberOfComments)
                                    append(")")
                                }) {
                                    input(
                                        In(
                                            outcome,
                                            realrecept,
                                            listOf(
                                                SimpleResourceRequest(
                                                    sampleCompute.category.name,
                                                    sampleCompute.category.provider,
                                                    100.DKK
                                                )
                                            ),
                                            username = if (recipient is GrantApplication.Recipient.PersonalWorkspace) username else null,
                                            numberOfProjectAdmins = numberOfAdmins,
                                            comments = buildList<Comment> {
                                                addAll((0 until numberOfComments).map {
                                                    Comment(CommentPoster.Pi, "This is my comment $it")
                                                })
                                                addAll(
                                                    (0 until numberOfAdmins).flatMap { admin ->
                                                        (0 until numberOfComments).map {
                                                            Comment(
                                                                CommentPoster.Admin(admin),
                                                                "This is my comment $it"
                                                            )
                                                        }
                                                    }
                                                )

                                                addAll((0 until numberOfComments).map {
                                                    Comment(CommentPoster.User, "This is my comment $it")
                                                })
                                            },
                                            changeStatusBy = if (outcome != GrantApplication.State.CLOSED) {
                                                if (numberOfAdmins == 0) {
                                                    CommentPoster.Pi
                                                } else {
                                                    CommentPoster.Admin(numberOfAdmins - 1)
                                                }
                                            } else {
                                                CommentPoster.User
                                            }
                                        )
                                    )

                                    checkSuccess()
                                }
                            }
                        }
                    }
                }

                case("multiple comments and requirements") {
                    val username = "user-${UUID.randomUUID()}"
                    val organization = "ucloud.dk"
                    input(
                        In(
                            GrantApplication.State.APPROVED,
                            GrantApplication.Recipient.PersonalWorkspace(username),
                            listOf(
                                SimpleResourceRequest(
                                    sampleCompute.category.name,
                                    sampleCompute.category.provider,
                                    100.DKK
                                ),
                                SimpleResourceRequest(
                                    sampleStorage.category.name,
                                    sampleStorage.category.provider,
                                    100.DKK
                                )
                            ),
                            comments = buildList<Comment> {
                                addAll((0 until 2).map {
                                    Comment(CommentPoster.Pi, "This is my comment $it")
                                })
                                addAll((0 until 2).map {
                                    Comment(CommentPoster.User, "This is my user comment $it")
                                })
                            },
                            username = username,
                            allowList = listOf(UserCriteria.WayfOrganization(organization)),
                            userOrganization = organization
                        )
                    )
                    check {
                        assertEquals(4, output.grantApplication.status.comments.size)
                        assertEquals(2, output.grantApplication.currentRevision.document.allocationRequests.size)
                    }
                }

                case("organization requirement") {
                    val username = "user-${UUID.randomUUID()}"
                    val organization = "ucloud.dk"
                    input(
                        In(
                            GrantApplication.State.APPROVED,
                            GrantApplication.Recipient.PersonalWorkspace(username),
                            listOf(
                                SimpleResourceRequest(
                                    sampleCompute.category.name,
                                    sampleCompute.category.provider,
                                    100.DKK
                                )
                            ),
                            username = username,
                            allowList = listOf(UserCriteria.WayfOrganization(organization)),
                            userOrganization = organization
                        )
                    )

                    checkSuccess()
                }

                case("organization requirement not met") {
                    val username = "user-${UUID.randomUUID()}"
                    val organization = "ucloud.dk"
                    val otherOrganization = "sdu.dk"
                    input(
                        In(
                            GrantApplication.State.APPROVED,
                            GrantApplication.Recipient.PersonalWorkspace(username),
                            listOf(
                                SimpleResourceRequest(
                                    sampleCompute.category.name,
                                    sampleCompute.category.provider,
                                    100.DKK
                                )
                            ),
                            username = username,
                            allowList = listOf(UserCriteria.WayfOrganization(organization)),
                            userOrganization = otherOrganization
                        )
                    )

                    expectStatusCode(HttpStatusCode.Forbidden)
                }

                case("email requirement") {
                    val username = "user-${UUID.randomUUID()}"
                    val organization = "ucloud.dk"
                    input(
                        In(
                            GrantApplication.State.APPROVED,
                            GrantApplication.Recipient.PersonalWorkspace(username),
                            listOf(
                                SimpleResourceRequest(
                                    sampleCompute.category.name,
                                    sampleCompute.category.provider,
                                    100.DKK
                                )
                            ),
                            username = username,
                            allowList = listOf(UserCriteria.EmailDomain(organization)),
                            userEmail = "user@$organization"
                        )
                    )

                    checkSuccess()
                }

                case("email requirement not met") {
                    val username = "user-${UUID.randomUUID()}"
                    val organization = "ucloud.dk"
                    val otherOrganization = "sdu.dk"
                    input(
                        In(
                            GrantApplication.State.APPROVED,
                            GrantApplication.Recipient.PersonalWorkspace(username),
                            listOf(
                                SimpleResourceRequest(
                                    sampleCompute.category.name,
                                    sampleCompute.category.provider,
                                    100.DKK
                                )
                            ),
                            username = username,
                            allowList = listOf(UserCriteria.EmailDomain(organization)),
                            userEmail = "user@$otherOrganization"
                        )
                    )

                    expectStatusCode(HttpStatusCode.Forbidden)
                }

                case("not excluded") {
                    val username = "user-${UUID.randomUUID()}"
                    val organization = "ucloud.dk"
                    val otherOrganization = "sdu.dk"
                    input(
                        In(
                            GrantApplication.State.APPROVED,
                            GrantApplication.Recipient.PersonalWorkspace(username),
                            listOf(
                                SimpleResourceRequest(
                                    sampleCompute.category.name,
                                    sampleCompute.category.provider,
                                    100.DKK
                                )
                            ),
                            username = username,
                            allowList = listOf(UserCriteria.WayfOrganization(organization)),
                            excludeList = listOf(UserCriteria.EmailDomain(otherOrganization)),
                            userOrganization = organization,
                            userEmail = "user@$organization"
                        )
                    )

                    checkSuccess()
                }

                case("excluded") {
                    val username = "user-${UUID.randomUUID()}"
                    val organization = "ucloud.dk"
                    val otherOrganization = "sdu.dk"
                    input(
                        In(
                            GrantApplication.State.APPROVED,
                            GrantApplication.Recipient.PersonalWorkspace(username),
                            listOf(
                                SimpleResourceRequest(
                                    sampleCompute.category.name,
                                    sampleCompute.category.provider,
                                    100.DKK
                                )
                            ),
                            username = username,
                            allowList = listOf(UserCriteria.WayfOrganization(organization)),
                            excludeList = listOf(UserCriteria.EmailDomain("student.$organization")),
                            userOrganization = organization,
                            userEmail = "user@student.$organization"
                        )
                    )

                    expectStatusCode(HttpStatusCode.Forbidden)
                }
            }
        }
        run {
            class In(
                val description: String,
                val personalTemplate: String,
                val newTemplate: String,
                val existingTemplate: String,
                val useDefaultTemplate: Boolean = false

            )
            class Out(
                val personaltemplate: String,
                val newTemplate: String,
                val exisitingtemplate: String
            )

            test<In, Out>("Grant applications, metadata") {
                execute {
                    createSampleProducts()
                    val evilUser = createUser("evil-${UUID.randomUUID()}")
                    val root = initializeRootProject(setOf(UCLOUD_PROVIDER))
                    val createdProject = initializeNormalProject(root)
                    // NOTE(Dan): This is a valid 1x1 gif
                    val logo = Base64.getDecoder().decode("R0lGODdhAQABAIAAABpkzhpkziwAAAAAAQABAAACAkQBADs=")
                    // NOTE(Dan): The evil logo is red
                    val evilLogo = Base64.getDecoder().decode("R0lGODdhAQABAIABAM4aGgAAACwAAAAAAQABAAACAkQBADs=")

                    GrantsEnabled.setEnabledStatus.call(
                        bulkRequestOf(
                            SetEnabledStatusRequest(createdProject.projectId, true)
                        ), serviceClient).orThrow()
                    GrantDescription.retrieveDescription.call(RetrieveDescriptionRequest(createdProject.projectId), createdProject.piClient).orThrow()
                    GrantDescription.uploadDescription.call(
                        bulkRequestOf(
                            UploadDescriptionRequest(createdProject.projectId, input.description)
                        ),
                        createdProject.piClient
                    ).orThrow()

                    GrantDescription.uploadDescription.call(
                        bulkRequestOf(
                            UploadDescriptionRequest(createdProject.projectId, "Evil!")
                        ),
                        evilUser.client
                    ).assertUserError()

                    val description =
                        GrantDescription.retrieveDescription.call(
                            RetrieveDescriptionRequest(createdProject.projectId),
                            createdProject.piClient
                        ).orThrow()
                    assertEquals(input.description, description.description)

                    ProjectLogo.retrieveLogo.call(
                        RetrieveLogoRequest(createdProject.projectId), createdProject.piClient
                    ).assertUserError()

                    ProjectLogo.uploadLogo.call(
                        UploadLogoRequest(createdProject.projectId),
                        createdProject.piClient.withHttpBody(ContentType.Image.GIF, logo.size.toLong(), ByteReadChannel(logo))
                    ).orThrow()
                    ProjectLogo.uploadLogo.call(
                        UploadLogoRequest(createdProject.projectId),
                        evilUser.client.withHttpBody(
                            ContentType.Image.GIF,
                            evilLogo.size.toLong(),
                            ByteReadChannel(evilLogo)
                        )
                    ).assertUserError()
                    val fetchedLogoBytes = (ProjectLogo.retrieveLogo.call(
                        RetrieveLogoRequest(createdProject.projectId),
                        createdProject.piClient
                    ).ctx as OutgoingHttpCall).response?.readBytes() ?: ByteArray(0)

                    assertEquals(base64Encode(logo), base64Encode(fetchedLogoBytes))

                    if (!input.useDefaultTemplate) {
                        GrantTemplates.uploadTemplates.call(
                            Templates.PlainText(
                                input.personalTemplate,
                                input.newTemplate,
                                input.existingTemplate
                            ),
                            createdProject.piClient.withProject(createdProject.projectId)
                        ).orThrow()
                    }
                    GrantTemplates.uploadTemplates.call(
                        Templates.PlainText("Evil 1", "Evil 2", "Evil 3"),
                        evilUser.client.withProject(createdProject.projectId)
                    ).assertUserError()

                    val fetchedTemplates = GrantTemplates.retrieveTemplates.call(
                        RetrieveTemplatesRequest(createdProject.projectId),
                        createdProject.piClient
                    ).orThrow() as Templates.PlainText
                    Out(
                        fetchedTemplates.personalProject,
                        fetchedTemplates.newProject,
                        fetchedTemplates.existingProject
                    )
                }

                case("Normal data - insert template") {
                    input(In("Some description", "some template", "another template", "more templates"))
                    check {
                        assertEquals(input.newTemplate, output.newTemplate)
                        assertEquals(input.existingTemplate, output.exisitingtemplate)
                        assertEquals(input.personalTemplate, output.personaltemplate)
                    }
                }

                case("Normal data - default templates") {
                    //Does not change the descriptions. Default message is found in Grant/main
                    val defaultMessage = "Please describe the reason for applying for resources"
                    input(In("Some description", "some template", "another template", "more templates", true))
                    check {
                        assertEquals(defaultMessage, output.personaltemplate)
                        assertEquals(defaultMessage, output.newTemplate)
                        assertEquals(defaultMessage, output.exisitingtemplate)
                    }
                }
            }
        }
    }
}

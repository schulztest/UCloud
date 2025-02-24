package dk.sdu.cloud.plugins.compute.ucloud

import dk.sdu.cloud.accounting.api.providers.ResourceChargeCredits
import dk.sdu.cloud.app.orchestrator.api.JobsControl
import dk.sdu.cloud.calls.bulkRequestOf
import dk.sdu.cloud.calls.client.call
import dk.sdu.cloud.calls.client.orThrow
import dk.sdu.cloud.service.Loggable
import dk.sdu.cloud.service.Time
import io.ktor.http.*
import kotlinx.coroutines.launch
import kotlin.math.max

object FeatureAccounting : JobFeature, Loggable {
    override val log = logger()
    const val LAST_PERFORMED_AT_ANNOTATION = "ucloud.dk/lastAccountingTs"
    const val TIME_BETWEEN_ACCOUNTING = 1000L * 60 * 15

    override suspend fun JobManagement.onJobComplete(
        rootJob: Container,
        children: List<Container>
    ) {
        log.trace("Accounting because job has completed!")
        val now = Time.now()
        val lastTs = rootJob.lastAccountingTs ?: rootJob.jobStartedAt ?: run {
            log.warn("Found no last accounting timestamp for job with id ${rootJob.jobId}")
            log.info("Assuming that ${rootJob.jobId} was a very fast job")
            now - 1000L
        }

        account(rootJob, children, lastTs, now)
    }

    override suspend fun JobManagement.onJobStart(rootJob: Container, children: List<Container>) {
        val now = System.currentTimeMillis()
        account(rootJob, children, now, now)
    }

    override suspend fun JobManagement.onJobMonitoring(jobBatch: Collection<Container>) {
        val now = Time.now()
        val jobsById = jobBatch.groupBy { it.jobId }
        for ((jobId, children) in jobsById) {
            val rootJob = children.find { it.rank == 0 } ?: continue
            val lastTs = rootJob.lastAccountingTs ?: rootJob.jobStartedAt
            if (lastTs == null) {
                log.trace("Found no last accounting timestamp for job with name '${jobId}' (Job might not have started yet)")
                continue
            }

            if (now - lastTs < TIME_BETWEEN_ACCOUNTING) continue

            account(rootJob, children, lastTs, now)
        }
    }

    private suspend fun JobManagement.account(rootJob: Container, children: Collection<Container>, lastTs: Long, now: Long) {
        val timespent = now - lastTs
        if (timespent < 0L) {
            log.info("No time spent on ${rootJob.jobId} ($timespent)")
            log.info("No accounting will be performed")
            return
        }

        if (timespent > 0L) {
            val replicas = children.size
            val virtualCpus = run {
                max(1, rootJob.vCpuMillis / 1000)
            }

            k8.scope.launch {
                // NOTE(Dan): Pushing this into a separate coroutine since we have seen quite bad performance from the
                // accounting system. Doing this, we run the risk of things happening in a surprising order. For
                // example, we might perform accounting of job completion and then perform accounting of the job. We
                // choose to accept this risk and not attempt to handle it. Instead, we rely solely on the duplicate
                // transaction detection to save us from charging too much. We live with the risk of charging too
                // little.
                val insufficientFunds = JobsControl.chargeCredits.call(
                    bulkRequestOf(
                        ResourceChargeCredits(
                            rootJob.jobId,
                            rootJob.jobId + "_" + lastTs.toString(),
                            replicas * virtualCpus.toLong(),
                            kotlin.math.ceil(timespent / (1000 * 60.0)).toLong()
                        )
                    ),
                    k8.serviceClient
                ).orThrow().insufficientFunds.isNotEmpty()

                if (insufficientFunds) {
                    children.forEach { it.cancel() }
                }

                try {
                    rootJob.upsertAnnotation(
                        LAST_PERFORMED_AT_ANNOTATION,
                        now.toString()
                    )
                } catch (ex: KubernetesException) {
                    if (ex.statusCode == HttpStatusCode.NotFound) {
                        // Ignored
                    } else {
                        throw ex
                    }
                }
            }
        }
    }

    private val Container.lastAccountingTs: Long?
        get() {
            return annotations[LAST_PERFORMED_AT_ANNOTATION]?.toLongOrNull()
        }
    private val Container.jobStartedAt: Long?
        get() {
            return annotations[FeatureExpiry.JOB_START]?.toLongOrNull()
        }
}

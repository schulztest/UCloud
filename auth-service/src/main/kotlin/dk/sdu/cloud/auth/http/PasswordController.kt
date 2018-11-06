package dk.sdu.cloud.auth.http

import dk.sdu.cloud.auth.api.Person
import dk.sdu.cloud.auth.services.UserDAO
import dk.sdu.cloud.auth.services.UserException
import dk.sdu.cloud.auth.services.checkPassword
import dk.sdu.cloud.auth.util.urlEncoded
import dk.sdu.cloud.service.Controller
import dk.sdu.cloud.service.Loggable
import dk.sdu.cloud.service.db.DBSessionFactory
import dk.sdu.cloud.service.db.withTransaction
import dk.sdu.cloud.service.logEntry
import dk.sdu.cloud.service.stackTraceToString
import io.ktor.application.call
import io.ktor.request.receiveParameters
import io.ktor.response.respondRedirect
import io.ktor.routing.Route
import io.ktor.routing.post
import io.ktor.util.toMap
import org.slf4j.LoggerFactory

class PasswordController<DBSession>(
    private val db: DBSessionFactory<DBSession>,
    private val userDao: UserDAO<DBSession>,
    private val loginResponder: LoginResponder<DBSession>
) : Controller {
    override val baseContext = "/auth"
    override fun configure(routing: Route): Unit = with(routing) {
        post("login") { _ ->
            val params = try {
                call.receiveParameters().toMap()
            } catch (ex: Exception) {
                log.debug(ex.stackTraceToString())
                logEntry(log, additionalParameters = mapOf("message" to "Missing parameters"))
                return@post call.respondRedirect("/auth/login?invalid")
            }

            logEntry(
                log, additionalParameters = mapOf(
                    "username" to params["username"]?.firstOrNull(),
                    "service" to params["service"]?.firstOrNull()
                )
            )

            val username = params["username"]?.firstOrNull()
            val password = params["password"]?.firstOrNull()
            val service = params["service"]?.firstOrNull() ?: return@post run {
                log.info("Missing service")
                call.respondRedirect("/auth/login?invalid")
            }

            if (username == null || password == null) {
                log.info("Missing username or password")
                return@post call.respondRedirect("/auth/login?service=${service.urlEncoded}&invalid")
            }

            val user = db.withTransaction {
                try {
                    userDao.findById(it, username) as? Person.ByPassword ?: throw UserException.NotFound()
                } catch (ex: UserException.NotFound) {
                    log.info("User not found or is not a password authenticated person")
                    loginResponder.handleUnsuccessfulLogin(call, service)
                    return@post
                }
            }

            val validPassword = user.checkPassword(password)
            if (!validPassword) return@post loginResponder.handleUnsuccessfulLogin(call, service)

            loginResponder.handleSuccessfulLogin(call, service, user)
        }
    }

    companion object : Loggable {
        override val log = logger()
    }
}

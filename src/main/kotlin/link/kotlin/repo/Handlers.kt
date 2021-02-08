package link.kotlin.repo

import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.PathHandler
import io.undertow.server.handlers.ResponseCodeHandler
import io.undertow.util.Headers
import io.undertow.util.Methods
import io.undertow.util.StatusCodes
import org.slf4j.LoggerFactory
import java.util.function.Supplier

class DelegatingHandler : HttpHandler {
    @Volatile
    private var handler: HttpHandler = ResponseCodeHandler.HANDLE_404

    fun setHandler(httpHandler: HttpHandler) {
        handler = httpHandler
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        handler.handleRequest(exchange)
    }
}

class HomePageHandler(
    private val configurationService: ConfigurationService
) : HttpHandler {
    override fun handleRequest(exchange: HttpServerExchange) {
        exchange.statusCode = StatusCodes.OK
        exchange.responseSender.send(
            """
            <!doctype html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, user-scalable=no, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0">
                <meta http-equiv="X-UA-Compatible" content="ie=edge">
                <title>repo.kotlin.link</title>
            </head>
            <body>
                <h1>repo.kotlin.link</h1>
                <p><a href="https://github.com/Heapy/repo.kotlin.link">Github</a></p>
                
                <h2>Hosted packages</h2>
                <ul>
                    ${
                configurationService.configuration.map {
                    """<li><strong>${it.key}</strong>: ${it.value}</li>"""
                }.joinToString(separator = "\n")
            }
                </ul>
            </body>
            </html>
        """.trimIndent()
        )
    }
}

class UpdateReposHandler(
    private val updateNotificationService: UpdateNotificationService
) : HttpHandler {
    override fun handleRequest(exchange: HttpServerExchange) {
        if (exchange.requestMethod != Methods.POST) {
            exchange.statusCode = StatusCodes.METHOD_NOT_ALLOWED
            return
        }

        try {
            updateNotificationService.publish()
            exchange.statusCode = StatusCodes.OK
        } catch (e: Exception) {
            LOGGER.error("Update failed", e)
            exchange.statusCode = StatusCodes.INTERNAL_SERVER_ERROR
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(UpdateReposHandler::class.java)
    }
}

class RepoRedirectHandler(
    private val repoUrl: String
) : HttpHandler {
    override fun handleRequest(exchange: HttpServerExchange) {
        val location = repoUrl + exchange.requestPath

        LOGGER.info("Request for [{}] redirected to [{}]", exchange.requestPath, location)

        exchange.responseHeaders.add(Headers.LOCATION, location)
        exchange.statusCode = StatusCodes.FOUND
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(RepoRedirectHandler::class.java)
    }
}

class RootHandlerProvider(
    private val configurationService: ConfigurationService,
    private val homePageHandler: HttpHandler,
    private val updateReposHandler: HttpHandler
) : Supplier<HttpHandler> {
    override fun get(): HttpHandler {
        return PathHandler(10000)
            .addExactPath("/", homePageHandler)
            .addExactPath("/update", updateReposHandler)
            .also { handler ->
                configurationService.configuration.forEach {
                    val prefix = it.key.split(".").joinToString(prefix = "/", separator = "/", postfix = "/")
                    handler.addPrefixPath(prefix, RepoRedirectHandler(it.value))
                }
            }
            .addPrefixPath("/", NotFoundHandler())
    }
}

class NotFoundHandler : HttpHandler {
    override fun handleRequest(exchange: HttpServerExchange) {
        LOGGER.info("Unknown path [${exchange.requestPath}]")
        exchange.statusCode = StatusCodes.NOT_FOUND
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(NotFoundHandler::class.java)
    }
}

class UpdateNotificationService {
    @Volatile
    private var listener: () -> Unit = {}

    fun subscribe(listener: () -> Unit) {
        this.listener = listener
    }

    fun publish() {
        listener()
    }
}

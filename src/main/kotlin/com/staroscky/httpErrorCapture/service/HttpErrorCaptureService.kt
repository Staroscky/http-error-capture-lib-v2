package com.staroscky.httpErrorCapture.service

import com.staroscky.httpErrorCapture.config.HttpErrorCaptureProperties
import com.staroscky.httpErrorCapture.model.HttpErrorEvent
import com.staroscky.httpErrorCapture.publisher.ErrorEventPublisher
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.PrintWriter
import java.io.StringWriter
import java.util.*

@Service
class HttpErrorCaptureService(
    private val publisher: ErrorEventPublisher,
    private val properties: HttpErrorCaptureProperties,
    @Value("\${spring.application.name:unknown}") private val applicationName: String
) {

    private val logger = LoggerFactory.getLogger(HttpErrorCaptureService::class.java)
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun captureAsync(
        request: HttpServletRequest,
        response: HttpServletResponse,
        requestBody: String? = null,
        responseBody: String? = null,
        exception: Exception? = null,
        duration: Long? = null
    ) {
        if (!properties.enabled || !shouldCapture(request, response)) {
            return
        }

        // Executa de forma completamente assíncrona para não impactar a aplicação
        coroutineScope.launch {
            try {
                val event = buildEvent(request, response, requestBody, responseBody, exception, duration)
                publisher.publishAsync(event)
                logger.debug("HTTP error event captured and sent: {}", event.id)
            } catch (e: Exception) {
                logger.error("Failed to capture HTTP error event", e)
                // Não propaga a exceção para não afetar o fluxo principal
            }
        }
    }

    fun captureBatchAsync(events: List<HttpErrorEvent>) {
        if (!properties.enabled || events.isEmpty()) {
            return
        }

        coroutineScope.launch {
            try {
                publisher.publishBatchAsync(events)
                logger.debug("Batch of {} HTTP error events sent", events.size)
            } catch (e: Exception) {
                logger.error("Failed to capture batch HTTP error events", e)
            }
        }
    }

    private fun shouldCapture(request: HttpServletRequest, response: HttpServletResponse): Boolean {
        val statusCode = response.status
        val path = request.requestURI

        // Verifica se o status code deve ser capturado
        if (!properties.capture.statusCodes.contains(statusCode)) {
            return false
        }

        // Verifica se o path não está na lista de exclusões
        if (properties.capture.excludePaths.any { excludePath ->
                path.startsWith(excludePath, ignoreCase = true)
            }) {
            return false
        }

        return true
    }

    private fun buildEvent(
        request: HttpServletRequest,
        response: HttpServletResponse,
        requestBody: String?,
        responseBody: String?,
        exception: Exception?,
        duration: Long?
    ): HttpErrorEvent {
        return HttpErrorEvent.builder()
            .id(UUID.randomUUID().toString())
            .applicationName(applicationName)
            .method(request.method)
            .path(request.requestURI)
            .statusCode(response.status)
            .userAgent(request.getHeader("User-Agent"))
            .remoteAddress(getClientIpAddress(request))
            .requestHeaders(extractHeaders(request))
            .responseHeaders(extractResponseHeaders(response))
            .requestBody(truncateBody(requestBody))
            .responseBody(truncateBody(responseBody))
            .duration(duration)
            .errorMessage(exception?.message)
            .stackTrace(exception?.let { getStackTrace(it) })
            .additionalData(buildAdditionalData(request, exception))
            .build()
    }

    private fun extractHeaders(request: HttpServletRequest): Map<String, String> {
        if (!properties.capture.includeHeaders) {
            return emptyMap()
        }

        return request.headerNames.asSequence()
            .associateWith { headerName ->
                request.getHeaders(headerName).asSequence().joinToString(", ")
            }
            .filterKeys { !isSensitiveHeader(it) }
    }

    private fun extractResponseHeaders(response: HttpServletResponse): Map<String, String> {
        if (!properties.capture.includeHeaders) {
            return emptyMap()
        }

        return response.headerNames.associateWith { headerName ->
            response.getHeaders(headerName).joinToString(", ")
        }.filterKeys { !isSensitiveHeader(it) }
    }

    private fun isSensitiveHeader(headerName: String): Boolean {
        val sensitiveHeaders = setOf(
            "authorization", "cookie", "set-cookie", 
            "x-api-key", "x-auth-token", "proxy-authorization"
        )
        return sensitiveHeaders.contains(headerName.lowercase())
    }

    private fun getClientIpAddress(request: HttpServletRequest): String? {
        val xForwardedFor = request.getHeader("X-Forwarded-For")
        if (!xForwardedFor.isNullOrBlank()) {
            return xForwardedFor.split(",")[0].trim()
        }

        val xRealIp = request.getHeader("X-Real-IP")
        if (!xRealIp.isNullOrBlank()) {
            return xRealIp
        }

        return request.remoteAddr
    }

    private fun truncateBody(body: String?): String? {
        if (body == null || !properties.capture.includeRequestBody) {
            return null
        }

        return if (body.length > properties.capture.maxBodySize) {
            body.substring(0, properties.capture.maxBodySize) + "... [TRUNCATED]"
        } else {
            body
        }
    }

    private fun getStackTrace(exception: Exception): String {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        exception.printStackTrace(printWriter)
        return stringWriter.toString()
    }

    private fun buildAdditionalData(request: HttpServletRequest, exception: Exception?): Map<String, Any> {
        val data = mutableMapOf<String, Any>()
        
        data["queryString"] = request.queryString ?: ""
        data["contentType"] = request.contentType ?: ""
        data["contentLength"] = request.contentLength
        data["protocol"] = request.protocol
        data["scheme"] = request.scheme
        data["serverName"] = request.serverName
        data["serverPort"] = request.serverPort
        
        exception?.let {
            data["exceptionClass"] = it::class.java.simpleName
        }

        return data
    }

    fun shutdown() {
        coroutineScope.cancel()
    }
}
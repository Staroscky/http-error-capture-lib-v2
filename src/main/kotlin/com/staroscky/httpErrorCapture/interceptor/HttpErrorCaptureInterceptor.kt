package com.staroscky.httpErrorCapture.interceptor

import com.staroscky.httpErrorCapture.service.HttpErrorCaptureService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper
import java.nio.charset.StandardCharsets

@Component
class HttpErrorCaptureInterceptor(
    private val captureService: HttpErrorCaptureService
) : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(HttpErrorCaptureInterceptor::class.java)

    companion object {
        private const val START_TIME_ATTRIBUTE = "http-error-capture.start-time"
    }

    override fun preHandle(
        request: HttpServletRequest, 
        response: HttpServletResponse, 
        handler: Any
    ): Boolean {
        request.setAttribute(START_TIME_ATTRIBUTE, System.currentTimeMillis())
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        try {
            val startTime = request.getAttribute(START_TIME_ATTRIBUTE) as? Long
            val duration = startTime?.let { System.currentTimeMillis() - it }

            val requestBody = extractRequestBody(request)
            val responseBody = extractResponseBody(response)

            captureService.captureAsync(
                request = request,
                response = response,
                requestBody = requestBody,
                responseBody = responseBody,
                exception = ex,
                duration = duration
            )
        } catch (e: Exception) {
            logger.error("Error in HTTP error capture interceptor", e)
            // Não propaga exceção para não afetar o fluxo da aplicação
        }
    }

    private fun extractRequestBody(request: HttpServletRequest): String? {
        return try {
            when (request) {
                is ContentCachingRequestWrapper -> {
                    val content = request.contentAsByteArray
                    if (content.isNotEmpty()) {
                        String(content, StandardCharsets.UTF_8)
                    } else null
                }
                else -> null // Para requests não wrappados, não conseguimos capturar o body
            }
        } catch (e: Exception) {
            logger.debug("Failed to extract request body", e)
            null
        }
    }

    private fun extractResponseBody(response: HttpServletResponse): String? {
        return try {
            when (response) {
                is ContentCachingResponseWrapper -> {
                    val content = response.contentAsByteArray
                    if (content.isNotEmpty()) {
                        String(content, StandardCharsets.UTF_8)
                    } else null
                }
                else -> null // Para responses não wrappados, não conseguimos capturar o body
            }
        } catch (e: Exception) {
            logger.debug("Failed to extract response body", e)
            null
        }
    }
}
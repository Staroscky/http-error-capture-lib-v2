package com.staroscky.httpErrorCapture.model

import com.fasterxml.jackson.annotation.JsonFormat
import java.time.LocalDateTime

data class HttpErrorEvent(
    val id: String,
    val applicationName: String,
    val method: String,
    val path: String,
    val statusCode: Int,
    val userAgent: String? = null,
    val remoteAddress: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val requestBody: String? = null,
    val responseBody: String? = null,
    val duration: Long? = null,
    val errorMessage: String? = null,
    val stackTrace: String? = null,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val additionalData: Map<String, Any> = emptyMap()
) {
    companion object {
        fun builder() = Builder()
    }

    class Builder {
        private var id: String = ""
        private var applicationName: String = ""
        private var method: String = ""
        private var path: String = ""
        private var statusCode: Int = 0
        private var userAgent: String? = null
        private var remoteAddress: String? = null
        private var requestHeaders: Map<String, String> = emptyMap()
        private var responseHeaders: Map<String, String> = emptyMap()
        private var requestBody: String? = null
        private var responseBody: String? = null
        private var duration: Long? = null
        private var errorMessage: String? = null
        private var stackTrace: String? = null
        private var timestamp: LocalDateTime = LocalDateTime.now()
        private var additionalData: Map<String, Any> = emptyMap()

        fun id(id: String) = apply { this.id = id }
        fun applicationName(applicationName: String) = apply { this.applicationName = applicationName }
        fun method(method: String) = apply { this.method = method }
        fun path(path: String) = apply { this.path = path }
        fun statusCode(statusCode: Int) = apply { this.statusCode = statusCode }
        fun userAgent(userAgent: String?) = apply { this.userAgent = userAgent }
        fun remoteAddress(remoteAddress: String?) = apply { this.remoteAddress = remoteAddress }
        fun requestHeaders(requestHeaders: Map<String, String>) = apply { this.requestHeaders = requestHeaders }
        fun responseHeaders(responseHeaders: Map<String, String>) = apply { this.responseHeaders = responseHeaders }
        fun requestBody(requestBody: String?) = apply { this.requestBody = requestBody }
        fun responseBody(responseBody: String?) = apply { this.responseBody = responseBody }
        fun duration(duration: Long?) = apply { this.duration = duration }
        fun errorMessage(errorMessage: String?) = apply { this.errorMessage = errorMessage }
        fun stackTrace(stackTrace: String?) = apply { this.stackTrace = stackTrace }
        fun timestamp(timestamp: LocalDateTime) = apply { this.timestamp = timestamp }
        fun additionalData(additionalData: Map<String, Any>) = apply { this.additionalData = additionalData }

        fun build() = HttpErrorEvent(
            id = id,
            applicationName = applicationName,
            method = method,
            path = path,
            statusCode = statusCode,
            userAgent = userAgent,
            remoteAddress = remoteAddress,
            requestHeaders = requestHeaders,
            responseHeaders = responseHeaders,
            requestBody = requestBody,
            responseBody = responseBody,
            duration = duration,
            errorMessage = errorMessage,
            stackTrace = stackTrace,
            timestamp = timestamp,
            additionalData = additionalData
        )
    }
}
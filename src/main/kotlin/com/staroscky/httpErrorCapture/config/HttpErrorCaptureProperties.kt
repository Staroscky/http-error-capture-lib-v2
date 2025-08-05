package com.staroscky.httpErrorCapture.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding

@ConfigurationProperties(prefix = "http-error-capture")
data class HttpErrorCaptureProperties @ConstructorBinding constructor(
    val enabled: Boolean = true,
    val async: AsyncProperties = AsyncProperties(),
    val sqs: SqsProperties = SqsProperties(),
    val capture: CaptureProperties = CaptureProperties()
) {
    data class AsyncProperties(
        val corePoolSize: Int = 2,
        val maxPoolSize: Int = 10,
        val queueCapacity: Int = 100,
        val threadNamePrefix: String = "http-error-"
    )

    data class SqsProperties(
        val queueUrl: String = "",
        val region: String = "us-east-1",
        val batchSize: Int = 10,
        val visibilityTimeoutSeconds: Int = 30,
        val messageRetentionPeriod: Int = 1209600, // 14 days
        val enabled: Boolean = true
    )

    data class CaptureProperties(
        val statusCodes: List<Int> = listOf(400, 401, 403, 404, 500, 502, 503, 504),
        val includeRequestBody: Boolean = true,
        val includeResponseBody: Boolean = true,
        val includeHeaders: Boolean = true,
        val maxBodySize: Int = 10240, // 10KB
        val excludePaths: List<String> = listOf("/health", "/actuator", "/metrics")
    )
}


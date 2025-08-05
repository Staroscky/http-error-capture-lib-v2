package com.staroscky.httpErrorCapture.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.staroscky.httpErrorCapture.filter.ContentCachingFilter
import com.staroscky.httpErrorCapture.interceptor.HttpErrorCaptureInterceptor
import com.staroscky.httpErrorCapture.publisher.ErrorEventPublisher
import com.staroscky.httpErrorCapture.publisher.impl.SqsErrorEventPublisher
import com.staroscky.httpErrorCapture.service.HttpErrorCaptureService
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import java.util.concurrent.Executor

@Configuration
@EnableAsync
@EnableConfigurationProperties(HttpErrorCaptureProperties::class)
@ComponentScan(basePackages = ["com.staroscky.httperror"])
@ConditionalOnProperty(
    name = ["http-error-capture.enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class HttpErrorCaptureAutoConfiguration(
    private val properties: HttpErrorCaptureProperties
) {

    @Bean
    @ConditionalOnMissingBean
    fun sqsAsyncClient(): SqsAsyncClient {
        return SqsAsyncClient.builder()
            .region(Region.of(properties.sqs.region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build()
    }

    @Bean
    @ConditionalOnMissingBean
    fun errorEventPublisher(
        sqsClient: SqsAsyncClient,
        objectMapper: ObjectMapper
    ): ErrorEventPublisher {
        return SqsErrorEventPublisher(sqsClient, objectMapper, properties)
    }

    @Bean("httpErrorCaptureTaskExecutor")
    fun taskExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = properties.async.corePoolSize
        executor.maxPoolSize = properties.async.maxPoolSize
        executor.queueCapacity = properties.async.queueCapacity
        executor.setThreadNamePrefix(properties.async.threadNamePrefix)
        executor.initialize()
        return executor
    }

    @Bean
    @ConditionalOnMissingBean
    fun contentCachingFilter(): ContentCachingFilter {
        return ContentCachingFilter()
    }

    @Bean
    @ConditionalOnMissingBean
    fun httpErrorCaptureService(
        publisher: ErrorEventPublisher
    ): HttpErrorCaptureService {
        return HttpErrorCaptureService(publisher, properties, "")
    }

    @Bean
    @ConditionalOnMissingBean
    fun httpErrorCaptureInterceptor(
        captureService: HttpErrorCaptureService
    ): HttpErrorCaptureInterceptor {
        return HttpErrorCaptureInterceptor(captureService)
    }
}

/**
 * Configuração separada para WebMvc para evitar problemas de injeção circular
 */
@Configuration
@ConditionalOnProperty(
    name = ["http-error-capture.enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class HttpErrorCaptureWebMvcConfiguration(
    private val httpErrorCaptureInterceptor: HttpErrorCaptureInterceptor
) : org.springframework.web.servlet.config.annotation.WebMvcConfigurer {

    override fun addInterceptors(registry: org.springframework.web.servlet.config.annotation.InterceptorRegistry) {
        registry.addInterceptor(httpErrorCaptureInterceptor)
    }
}
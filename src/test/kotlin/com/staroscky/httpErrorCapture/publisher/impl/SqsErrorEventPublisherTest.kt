package com.staroscky.httpErrorCapture.publisher.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.staroscky.httpErrorCapture.config.HttpErrorCaptureProperties
import com.staroscky.httpErrorCapture.model.HttpErrorEvent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.*
import java.util.*
import java.util.concurrent.CompletableFuture

@ExtendWith(MockitoExtension::class)
class SqsErrorEventPublisherTest {

    @Mock
    private lateinit var sqsClient: SqsAsyncClient

    private lateinit var objectMapper: ObjectMapper
    private lateinit var properties: HttpErrorCaptureProperties
    private lateinit var publisher: SqsErrorEventPublisher

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        properties = HttpErrorCaptureProperties(
            sqs = HttpErrorCaptureProperties.SqsProperties(
                queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue",
                enabled = true,
                batchSize = 10
            )
        )
        publisher = SqsErrorEventPublisher(sqsClient, objectMapper, properties)
    }

    @Test
    fun `should publish single event successfully`() = runTest {
        // Given
        val event = HttpErrorEvent.builder()
            .id(UUID.randomUUID().toString())
            .applicationName("test-app")
            .method("GET")
            .path("/api/test")
            .statusCode(404)
            .build()

        val sendMessageResponse = SendMessageResponse.builder()
            .messageId("message-123")
            .build()

        whenever(sqsClient.sendMessage(any<SendMessageRequest>()))
            .thenReturn(CompletableFuture.completedFuture(sendMessageResponse))

        // When
        publisher.publishAsync(event)

        // Then
        verify(sqsClient).sendMessage(argThat<SendMessageRequest> { request ->
            request.queueUrl() == properties.sqs.queueUrl &&
            request.messageGroupId() == "test-app" &&
            request.messageBody().contains("\"statusCode\":404")
        })
    }

    @Test
    fun `should not publish when SQS is disabled`() = runTest {
        // Given
        val disabledProperties = properties.copy(
            sqs = properties.sqs.copy(enabled = false)
        )
        val disabledPublisher = SqsErrorEventPublisher(sqsClient, objectMapper, disabledProperties)
        
        val event = HttpErrorEvent.builder()
            .id(UUID.randomUUID().toString())
            .applicationName("test-app")
            .method("GET")
            .path("/api/test")
            .statusCode(404)
            .build()

        // When
        disabledPublisher.publishAsync(event)

        // Then
        verifyNoInteractions(sqsClient)
    }

    @Test
    fun `should publish batch events successfully`() = runTest {
        // Given
        val events = listOf(
            HttpErrorEvent.builder()
                .id(UUID.randomUUID().toString())
                .applicationName("test-app")
                .method("GET")
                .path("/api/test1")
                .statusCode(404)
                .build(),
            HttpErrorEvent.builder()
                .id(UUID.randomUUID().toString())
                .applicationName("test-app")
                .method("POST")
                .path("/api/test2")
                .statusCode(500)
                .build()
        )

        val batchResponse = SendMessageBatchResponse.builder()
            .successful(
                SendMessageBatchResultEntry.builder()
                    .id("0")
                    .messageId("msg-1")
                    .build(),
                SendMessageBatchResultEntry.builder()
                    .id("1")
                    .messageId("msg-2")
                    .build()
            )
            .build()

        whenever(sqsClient.sendMessageBatch(any<SendMessageBatchRequest>()))
            .thenReturn(CompletableFuture.completedFuture(batchResponse))

        // When
        publisher.publishBatchAsync(events)

        // Then
        verify(sqsClient).sendMessageBatch(argThat<SendMessageBatchRequest> { request ->
            request.queueUrl() == properties.sqs.queueUrl &&
            request.entries().size == 2
        })
    }

    @Test
    fun `should handle batch with some failures`() = runTest {
        // Given
        val events = listOf(
            HttpErrorEvent.builder()
                .id(UUID.randomUUID().toString())
                .applicationName("test-app")
                .method("GET")
                .path("/api/test1")
                .statusCode(404)
                .build(),
            HttpErrorEvent.builder()
                .id(UUID.randomUUID().toString())
                .applicationName("test-app")
                .method("POST")
                .path("/api/test2")
                .statusCode(500)
                .build()
        )

        val batchResponse = SendMessageBatchResponse.builder()
            .successful(
                SendMessageBatchResultEntry.builder()
                    .id("0")
                    .messageId("msg-1")
                    .build()
            )
            .failed(
                BatchResultErrorEntry.builder()
                    .id("1")
                    .code("InternalError")
                    .message("Internal service error")
                    .build()
            )
            .build()

        whenever(sqsClient.sendMessageBatch(any<SendMessageBatchRequest>()))
            .thenReturn(CompletableFuture.completedFuture(batchResponse))

        // When
        publisher.publishBatchAsync(events)

        // Then
        verify(sqsClient).sendMessageBatch(any<SendMessageBatchRequest>())
        // Verifica que não lança exceção mesmo com falhas parciais
    }

    @Test
    fun `should handle SQS client exceptions gracefully`() = runTest {
        // Given
        val event = HttpErrorEvent.builder()
            .id(UUID.randomUUID().toString())
            .applicationName("test-app")
            .method("GET")
            .path("/api/test")
            .statusCode(404)
            .build()

        whenever(sqsClient.sendMessage(any<SendMessageRequest>()))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("SQS Error")))

        // When & Then
        // Não deve lançar exceção
        publisher.publishAsync(event)

        verify(sqsClient).sendMessage(any<SendMessageRequest>())
    }

    @Test
    fun `should check health correctly`() = runTest {
        // Given
        val attributesResponse = GetQueueAttributesResponse.builder()
            .attributes(mapOf(QueueAttributeName.QUEUE_ARN to "arn:aws:sqs:us-east-1:123456789012:test-queue"))
            .build()

        whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
            .thenReturn(CompletableFuture.completedFuture(attributesResponse))

        // When
        val isHealthy = publisher.isHealthy()

        // Then
        assert(isHealthy)
        verify(sqsClient).getQueueAttributes(argThat<GetQueueAttributesRequest> { request ->
            request.queueUrl() == properties.sqs.queueUrl
        })
    }

    @Test
    fun `should return false when health check fails`() = runTest {
        // Given
        whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("Queue not found")))

        // When
        val isHealthy = publisher.isHealthy()

        // Then
        assert(!isHealthy)
    }

    @Test
    fun `should split large batches correctly`() = runTest {
        // Given
        val largeEventList = (1..25).map { i ->
            HttpErrorEvent.builder()
                .id(UUID.randomUUID().toString())
                .applicationName("test-app")
                .method("GET")
                .path("/api/test$i")
                .statusCode(404)
                .build()
        }

        val batchResponse = SendMessageBatchResponse.builder()
            .successful(emptyList())
            .build()

        whenever(sqsClient.sendMessageBatch(any<SendMessageBatchRequest>()))
            .thenReturn(CompletableFuture.completedFuture(batchResponse))

        // When
        publisher.publishBatchAsync(largeEventList)

        // Then
        // Deve fazer 3 chamadas: 10 + 10 + 5 eventos
        verify(sqsClient, times(3)).sendMessageBatch(any<SendMessageBatchRequest>())
    }
}
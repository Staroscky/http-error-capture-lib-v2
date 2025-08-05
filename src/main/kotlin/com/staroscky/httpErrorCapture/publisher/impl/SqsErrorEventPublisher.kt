package com.staroscky.httpErrorCapture.publisher.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.staroscky.httpErrorCapture.config.HttpErrorCaptureProperties
import com.staroscky.httpErrorCapture.model.HttpErrorEvent
import com.staroscky.httpErrorCapture.publisher.ErrorEventPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.*
import java.util.*

@Component
class SqsErrorEventPublisher(
    private val sqsClient: SqsAsyncClient,
    private val objectMapper: ObjectMapper,
    private val properties: HttpErrorCaptureProperties
) : ErrorEventPublisher {

    private val logger = LoggerFactory.getLogger(SqsErrorEventPublisher::class.java)

    override suspend fun publishAsync(event: HttpErrorEvent) {
        if (!properties.sqs.enabled) {
            logger.debug("SQS publishing is disabled")
            return
        }

        try {
            withContext(Dispatchers.IO) {
                val messageBody = objectMapper.writeValueAsString(event)
                
                val sendMessageRequest = SendMessageRequest.builder()
                    .queueUrl(properties.sqs.queueUrl)
                    .messageBody(messageBody)
                    .messageAttributes(buildMessageAttributes(event))
                    .messageGroupId(event.applicationName) // Para FIFO queues
                    .messageDeduplicationId(UUID.randomUUID().toString())
                    .build()

                val result = sqsClient.sendMessage(sendMessageRequest).get()
                logger.debug("Message sent to SQS. MessageId: {}", result.messageId())
            }
        } catch (e: Exception) {
            logger.error("Failed to send message to SQS for event: ${event.id}", e)
            // Não lança exceção para não impactar a aplicação principal
        }
    }

    override suspend fun publishBatchAsync(events: List<HttpErrorEvent>) {
        if (!properties.sqs.enabled || events.isEmpty()) {
            logger.debug("SQS publishing is disabled or no events to send")
            return
        }

        try {
            withContext(Dispatchers.IO) {
                // Divide em batches de acordo com a configuração
                events.chunked(properties.sqs.batchSize).map { batch ->
                    async {
                        sendBatch(batch)
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            logger.error("Failed to send batch messages to SQS", e)
        }
    }

    private suspend fun sendBatch(events: List<HttpErrorEvent>) {
        try {
            val entries = events.mapIndexed { index, event ->
                val messageBody = objectMapper.writeValueAsString(event)
                
                SendMessageBatchRequestEntry.builder()
                    .id(index.toString())
                    .messageBody(messageBody)
                    .messageAttributes(buildMessageAttributes(event))
                    .messageGroupId(event.applicationName)
                    .messageDeduplicationId(UUID.randomUUID().toString())
                    .build()
            }

            val batchRequest = SendMessageBatchRequest.builder()
                .queueUrl(properties.sqs.queueUrl)
                .entries(entries)
                .build()

            val result = sqsClient.sendMessageBatch(batchRequest).get()
            
            if (result.hasFailed()) {
                logger.warn("Some messages failed to send. Failed count: {}", result.failed().size)
                result.failed().forEach { failure ->
                    logger.warn("Failed message - Id: {}, Code: {}, Message: {}", 
                        failure.id(), failure.code(), failure.message())
                }
            }
            
            logger.debug("Batch sent to SQS. Successful: {}, Failed: {}", 
                result.successful().size, result.failed().size)
                
        } catch (e: Exception) {
            logger.error("Failed to send batch to SQS", e)
        }
    }

    override suspend fun isHealthy(): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val request = GetQueueAttributesRequest.builder()
                    .queueUrl(properties.sqs.queueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)
                    .build()
                
                sqsClient.getQueueAttributes(request).get()
                true
            }
        } catch (e: Exception) {
            logger.warn("SQS health check failed", e)
            false
        }
    }

    private fun buildMessageAttributes(event: HttpErrorEvent): Map<String, MessageAttributeValue> {
        return mapOf(
            "ApplicationName" to MessageAttributeValue.builder()
                .stringValue(event.applicationName)
                .dataType("String")
                .build(),
            "StatusCode" to MessageAttributeValue.builder()
                .stringValue(event.statusCode.toString())
                .dataType("Number")
                .build(),
            "Method" to MessageAttributeValue.builder()
                .stringValue(event.method)
                .dataType("String")
                .build(),
            "Timestamp" to MessageAttributeValue.builder()
                .stringValue(event.timestamp.toString())
                .dataType("String")
                .build()
        )
    }
}
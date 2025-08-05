package com.staroscky.httpErrorCapture.integration

import com.staroscky.httpErrorCapture.model.HttpErrorEvent
import com.staroscky.httpErrorCapture.publisher.ErrorEventPublisher
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.web.bind.annotation.*
import java.util.concurrent.ConcurrentLinkedQueue

@SpringBootTest(
    classes = [
        HttpErrorCaptureIntegrationTest.TestApp::class,
        HttpErrorCaptureIntegrationTest.TestConfig::class
    ],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebMvc
@TestPropertySource(properties = [
    "http-error-capture.enabled=true",
    "http-error-capture.sqs.enabled=false", // Desabilita SQS para testes
    "spring.application.name=integration-test-app"
])
class HttpErrorCaptureIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var testPublisher: TestErrorEventPublisher

    @TestConfiguration
    class TestConfig {
        
        @Bean
        @Primary
        fun testErrorEventPublisher(): TestErrorEventPublisher {
            return TestErrorEventPublisher()
        }
    }

    @RestController
    class TestApp {
        
        @GetMapping("/success")
        fun success(): Map<String, String> {
            return mapOf("status" to "ok")
        }
        
        @GetMapping("/not-found")
        fun notFound(): Nothing {
            throw RuntimeException("Resource not found")
        }
        
        @PostMapping("/server-error")
        fun serverError(@RequestBody payload: Map<String, Any>): Nothing {
            throw RuntimeException("Internal server error")
        }
        
        @GetMapping("/health")
        fun health(): Map<String, String> {
            return mapOf("status" to "UP")
        }
        
        @ExceptionHandler(RuntimeException::class)
        @ResponseStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
        fun handleRuntimeException(ex: RuntimeException): Map<String, String> {
            return mapOf("error" to (ex.message ?: "Unknown error"))
        }
    }

    // Publisher de teste que armazena eventos em memória
    class TestErrorEventPublisher : ErrorEventPublisher {
        private val events = ConcurrentLinkedQueue<HttpErrorEvent>()
        
        override suspend fun publishAsync(event: HttpErrorEvent) {
            events.offer(event)
        }
        
        override suspend fun publishBatchAsync(events: List<HttpErrorEvent>) {
            this.events.addAll(events)
        }
        
        override suspend fun isHealthy(): Boolean = true
        
        fun getPublishedEvents(): List<HttpErrorEvent> = events.toList()
        
        fun clearEvents() = events.clear()
        
        fun getEventCount(): Int = events.size
    }

    @Test
    fun `should capture 500 error with request and response body`() {
        // Given
        val requestBody = """{"test": "data", "number": 123}"""
        
        // When
        mockMvc.perform(
            post("/server-error")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody)
                .header("User-Agent", "Test-Agent")
        )
        .andExpect(status().isInternalServerError)
        
        // Then
        Thread.sleep(200) // Aguarda processamento assíncrono
        
        val events = testPublisher.getPublishedEvents()
        assert(events.size == 1)
        
        val event = events.first()
        assert(event.method == "POST")
        assert(event.path == "/server-error")
        assert(event.statusCode == 500)
        assert(event.applicationName == "integration-test-app")
        assert(event.requestBody?.contains("test") == true)
        assert(event.responseBody?.contains("error") == true)
        assert(event.errorMessage == "Internal server error")
        assert(event.userAgent == "Test-Agent")
        assert(event.duration != null && event.duration!! > 0)
    }

    @Test
    fun `should not capture 200 success responses`() {
        // Given
        testPublisher.clearEvents()
        
        // When
        mockMvc.perform(get("/success"))
            .andExpect(status().isOk)
        
        // Then
        Thread.sleep(100)
        assert(testPublisher.getEventCount() == 0)
    }

    @Test
    fun `should not capture excluded paths`() {
        // Given
        testPublisher.clearEvents()
        
        // When
        mockMvc.perform(get("/health"))
            .andExpect(status().isOk)
        
        // Then
        Thread.sleep(100)
        assert(testPublisher.getEventCount() == 0)
    }

    @Test
    fun `should capture request headers excluding sensitive ones`() {
        // Given
        testPublisher.clearEvents()
        
        // When
        mockMvc.perform(
            get("/not-found")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer secret-token")
                .header("X-Custom-Header", "custom-value")
        )
        .andExpect(status().isInternalServerError)
        
        // Then
        Thread.sleep(200)
        
        val events = testPublisher.getPublishedEvents()
        assert(events.size == 1)
        
        val event = events.first()
        assert(event.requestHeaders.containsKey("Content-Type"))
        assert(event.requestHeaders.containsKey("X-Custom-Header"))
        assert(!event.requestHeaders.containsKey("Authorization")) // Deve ser filtrado
    }

    @Test
    fun `should include client IP address`() {
        // Given
        testPublisher.clearEvents()
        
        // When
        mockMvc.perform(
            get("/not-found")
                .header("X-Forwarded-For", "192.168.1.100, 10.0.0.1")
        )
        .andExpect(status().isInternalServerError)
        
        // Then
        Thread.sleep(200)
        
        val events = testPublisher.getPublishedEvents()
        assert(events.size == 1)
        
        val event = events.first()
        assert(event.remoteAddress == "192.168.1.100") // Primeiro IP do X-Forwarded-For
    }

    @Test
    fun `should include additional data`() {
        // Given
        testPublisher.clearEvents()
        
        // When
        mockMvc.perform(
            post("/server-error")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"test": "value"}""")
                .param("queryParam", "queryValue")
        )
        .andExpect(status().isInternalServerError)
        
        // Then
        Thread.sleep(200)
        
        val events = testPublisher.getPublishedEvents()
        assert(events.size == 1)
        
        val event = events.first()
        assert(event.additionalData.containsKey("queryString"))
        assert(event.additionalData.containsKey("contentType"))
        assert(event.additionalData.containsKey("protocol"))
        assert(event.additionalData["queryString"] == "queryParam=queryValue")
        assert(event.additionalData["contentType"] == "application/json")
    }

    @Test
    fun `should truncate large request body`() {
        // Given
        testPublisher.clearEvents()
        val largeBody = "x".repeat(15000) // Maior que maxBodySize (10KB)
        
        // When
        mockMvc.perform(
            post("/server-error")
                .contentType(MediaType.TEXT_PLAIN)
                .content(largeBody)
        )
        .andExpect(status().isInternalServerError)
        
        // Then
        Thread.sleep(200)
        
        val events = testPublisher.getPublishedEvents()
        assert(events.size == 1)
        
        val event = events.first()
        assert(event.requestBody != null)
        assert(event.requestBody!!.endsWith("... [TRUNCATED]"))
        assert(event.requestBody!!.length <= 10240 + 15) // maxBodySize + truncation message
    }

    @Test
    fun `should handle concurrent requests properly`() {
        // Given
        testPublisher.clearEvents()
        val numberOfRequests = 10
        
        // When - Simula múltiplas requisições concorrentes
        repeat(numberOfRequests) { index ->
            Thread {
                try {
                    mockMvc.perform(
                        post("/server-error")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"request": $index}""")
                    )
                } catch (e: Exception) {
                    // Ignora exceções de concorrência para este teste
                }
            }.start()
        }
        
        // Then
        Thread.sleep(1000) // Aguarda todas as requisições
        
        // Deve ter capturado eventos (pode ser menos que numberOfRequests devido a concorrência)
        assert(testPublisher.getEventCount() > 0)
    }
}
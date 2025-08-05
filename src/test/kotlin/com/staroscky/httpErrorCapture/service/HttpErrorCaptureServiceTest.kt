package com.staroscky.httpErrorCapture.service

import com.staroscky.httpErrorCapture.config.HttpErrorCaptureProperties
import com.staroscky.httpErrorCapture.model.HttpErrorEvent
import com.staroscky.httpErrorCapture.publisher.ErrorEventPublisher
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.*

@ExtendWith(MockitoExtension::class)
class HttpErrorCaptureServiceTest {

    @Mock
    private lateinit var publisher: ErrorEventPublisher

    @Mock
    private lateinit var request: HttpServletRequest

    @Mock
    private lateinit var response: HttpServletResponse

    private lateinit var properties: HttpErrorCaptureProperties
    private lateinit var service: HttpErrorCaptureService

    @BeforeEach
    fun setUp() {
        properties = HttpErrorCaptureProperties(
            enabled = true,
            capture = HttpErrorCaptureProperties.CaptureProperties(
                statusCodes = listOf(400, 500),
                excludePaths = listOf("/health"),
                includeRequestBody = true,
                includeResponseBody = true,
                includeHeaders = true,
                maxBodySize = 1000
            )
        )
        
        service = HttpErrorCaptureService(publisher, properties, "test-app")
    }

    @Test
    fun `should capture error when status code matches configuration`() = runTest {
        // Given
        whenever(request.method).thenReturn("POST")
        whenever(request.requestURI).thenReturn("/api/users")
        whenever(request.getHeader("User-Agent")).thenReturn("Test-Agent")
        whenever(request.remoteAddr).thenReturn("127.0.0.1")
        whenever(request.headerNames).thenReturn(Collections.enumeration(listOf("Content-Type")))
        whenever(request.getHeaders("Content-Type")).thenReturn(Collections.enumeration(listOf("application/json")))
        whenever(response.status).thenReturn(500)
        whenever(response.headerNames).thenReturn(listOf("Content-Type"))
        whenever(response.getHeaders("Content-Type")).thenReturn(listOf("application/json"))

        // When
        service.captureAsync(
            request = request,
            response = response,
            requestBody = """{"name": "test"}""",
            responseBody = """{"error": "Internal error"}""",
            exception = RuntimeException("Test error"),
            duration = 1500L
        )

        // Then
        // Como é assíncrono, precisamos dar um tempo para a execução
        Thread.sleep(100)
        
        verify(publisher, timeout(1000)).publishAsync(argThat { event ->
            event.applicationName == "test-app" &&
            event.method == "POST" &&
            event.path == "/api/users" &&
            event.statusCode == 500 &&
            event.requestBody == """{"name": "test"}""" &&
            event.responseBody == """{"error": "Internal error"}""" &&
            event.errorMessage == "Test error" &&
            event.duration == 1500L
        })
    }

    @Test
    fun `should not capture when status code does not match configuration`() = runTest {
        // Given
        whenever(request.requestURI).thenReturn("/api/users")
        whenever(response.status).thenReturn(200)

        // When
        service.captureAsync(request, response)

        // Then
        Thread.sleep(100)
        verifyNoInteractions(publisher)
    }

    @Test
    fun `should not capture when path is excluded`() = runTest {
        // Given
        whenever(request.requestURI).thenReturn("/health")
        whenever(response.status).thenReturn(500)

        // When
        service.captureAsync(request, response)

        // Then
        Thread.sleep(100)
        verifyNoInteractions(publisher)
    }

    @Test
    fun `should not capture when disabled`() = runTest {
        // Given
        val disabledProperties = properties.copy(enabled = false)
        val disabledService = HttpErrorCaptureService(publisher, disabledProperties, "test-app")
        
        whenever(request.requestURI).thenReturn("/api/users")
        whenever(response.status).thenReturn(500)

        // When
        disabledService.captureAsync(request, response)

        // Then
        Thread.sleep(100)
        verifyNoInteractions(publisher)
    }

    @Test
    fun `should truncate request body when exceeds max size`() = runTest {
        // Given
        val longBody = "a".repeat(2000)
        whenever(request.method).thenReturn("POST")
        whenever(request.requestURI).thenReturn("/api/users")
        whenever(request.headerNames).thenReturn(Collections.enumeration(emptyList()))
        whenever(response.status).thenReturn(500)
        whenever(response.headerNames).thenReturn(emptyList())

        // When
        service.captureAsync(
            request = request,
            response = response,
            requestBody = longBody
        )

        // Then
        Thread.sleep(100)
        verify(publisher, timeout(1000)).publishAsync(argThat { event ->
            event.requestBody != null &&
            event.requestBody!!.endsWith("... [TRUNCATED]") &&
            event.requestBody!!.length <= properties.capture.maxBodySize + 15 // +15 for truncation message
        })
    }

    @Test
    fun `should filter sensitive headers`() = runTest {
        // Given
        whenever(request.method).thenReturn("POST")
        whenever(request.requestURI).thenReturn("/api/users")
        whenever(request.headerNames).thenReturn(
            Collections.enumeration(listOf("Authorization", "Content-Type", "X-API-Key"))
        )
        whenever(request.getHeaders("Authorization")).thenReturn(
            Collections.enumeration(listOf("Bearer token123"))
        )
        whenever(request.getHeaders("Content-Type")).thenReturn(
            Collections.enumeration(listOf("application/json"))
        )
        whenever(request.getHeaders("X-API-Key")).thenReturn(
            Collections.enumeration(listOf("secret-key"))
        )
        whenever(response.status).thenReturn(500)
        whenever(response.headerNames).thenReturn(emptyList())

        // When
        service.captureAsync(request, response)

        // Then
        Thread.sleep(100)
        verify(publisher, timeout(1000)).publishAsync(argThat { event ->
            event.requestHeaders.containsKey("Content-Type") &&
            !event.requestHeaders.containsKey("Authorization") &&
            !event.requestHeaders.containsKey("X-API-Key")
        })
    }

    @Test
    fun `should capture batch events`() = runTest {
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

        // When
        service.captureBatchAsync(events)

        // Then
        Thread.sleep(100)
        verify(publisher, timeout(1000)).publishBatchAsync(eq(events))
    }
}
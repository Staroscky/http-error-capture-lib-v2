package com.staroscky.httpErrorCapture.filter

import jakarta.servlet.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper

/**
 * Filtro que wrappea as requisições e respostas para permitir 
 * a leitura múltipla do conteúdo do body
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ContentCachingFilter : Filter {

    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain
    ) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse

        val wrappedRequest = ContentCachingRequestWrapper(httpRequest)
        val wrappedResponse = ContentCachingResponseWrapper(httpResponse)

        try {
            chain.doFilter(wrappedRequest, wrappedResponse)
        } finally {
            // É importante copiar o conteúdo de volta para a response original
            wrappedResponse.copyBodyToResponse()
        }
    }
}
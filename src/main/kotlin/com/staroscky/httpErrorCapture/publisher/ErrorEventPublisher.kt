package com.staroscky.httpErrorCapture.publisher

import com.staroscky.httpErrorCapture.model.HttpErrorEvent

/**
 * Interface para publicação assíncrona de eventos de erro HTTP
 */
interface ErrorEventPublisher {
    
    /**
     * Publica um evento de erro de forma assíncrona
     * @param event Evento de erro HTTP a ser publicado
     */
    suspend fun publishAsync(event: HttpErrorEvent)
    
    /**
     * Publica múltiplos eventos de erro de forma assíncrona em lote
     * @param events Lista de eventos de erro HTTP
     */
    suspend fun publishBatchAsync(events: List<HttpErrorEvent>)
    
    /**
     * Verifica se o publisher está saudável e operacional
     * @return true se o publisher está funcionando corretamente
     */
    suspend fun isHealthy(): Boolean
}
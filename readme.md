# HTTP Error Capture Library

Uma biblioteca Kotlin/Spring Boot para captura assíncrona de erros HTTP com publicação no Amazon SQS.

## 🚀 Características

- **Assíncrono**: Não impacta a performance da aplicação principal
- **Configurável**: Ampla gama de opções de configuração
- **Publisher SQS**: Implementação padrão para Amazon SQS com suporte a batch
- **Extensível**: Interface de publisher permite implementações customizadas
- **Seguro**: Filtra automaticamente headers sensíveis
- **Testável**: Totalmente testado com testes unitários e de integração

## 📦 Instalação

### Maven

Adicione a dependência ao seu `pom.xml`:

```xml
<dependency>
    <groupId>com.staroscky</groupId>
    <artifactId>http-error-capture</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle

```kotlin
implementation("com.staroscky:http-error-capture:1.0.0")
```

## ⚙️ Configuração

### Configuração Básica

Adicione ao seu `application.yml`:

```yaml
http-error-capture:
  enabled: true
  
  sqs:
    queue-url: "https://sqs.us-east-1.amazonaws.com/123456789012/http-errors-queue"
    region: "us-east-1"
    enabled: true
  
  capture:
    status-codes: [400, 401, 403, 404, 500, 502, 503, 504]
    exclude-paths:
      - "/health"
      - "/actuator"
```

### Configuração Completa

```yaml
http-error-capture:
  enabled: true
  
  # Configurações assíncronas
  async:
    core-pool-size: 2
    max-pool-size: 10
    queue-capacity: 100
    thread-name-prefix: "http-error-"
  
  # Configurações do SQS
  sqs:
    queue-url: "https://sqs.us-east-1.amazonaws.com/123456789012/http-errors-queue"
    region: "us-east-1"
    batch-size: 10
    visibility-timeout-seconds: 30
    message-retention-period: 1209600 # 14 days
    enabled: true
  
  # Configurações de captura
  capture:
    status-codes: [400, 401, 403, 404, 500, 502, 503, 504]
    include-request-body: true
    include-response-body: true
    include-headers: true
    max-body-size: 10240 # 10KB
    exclude-paths:
      - "/health"
      - "/actuator"
      - "/metrics"
      - "/favicon.ico"
```

### Configuração AWS

Certifique-se de que as credenciais AWS estejam configuradas:

```bash
# Variáveis de ambiente
export AWS_ACCESS_KEY_ID=your-access-key
export AWS_SECRET_ACCESS_KEY=your-secret-key
export AWS_REGION=us-east-1

# Ou usando AWS CLI
aws configure
```

## 🔧 Uso

### Uso Automático

A biblioteca se integra automaticamente com sua aplicação Spring Boot através de auto-configuração. Todos os erros HTTP serão capturados automaticamente baseado na configuração.

### Uso Programático

```kotlin
@Autowired
private lateinit var captureService: HttpErrorCaptureService

// Captura manual de um erro
captureService.captureAsync(
    request = request,
    response = response,
    requestBody = "request body",
    responseBody = "error response", 
    exception = exception,
    duration = 1500L
)

// Captura em lote
val events = listOf(/* eventos */)
captureService.captureBatchAsync(events)
```

### Publisher Customizado

Implemente sua própria lógica de publicação:

```kotlin
@Component
@Primary
class CustomErrorEventPublisher : ErrorEventPublisher {
    
    override suspend fun publishAsync(event: HttpErrorEvent) {
        // Sua implementação customizada
        // Ex: Kafka, RabbitMQ, Database, etc.
    }
    
    override suspend fun publishBatchAsync(events: List<HttpErrorEvent>) {
        // Implementação para lote
    }
    
    override suspend fun isHealthy(): Boolean {
        // Health check customizado
        return true
    }
}
```

## 📊 Estrutura do Evento

Os eventos capturados contém as seguintes informações:

```kotlin
data class HttpErrorEvent(
    val id: String,                           // UUID único do evento
    val applicationName: String,              // Nome da aplicação
    val method: String,                       // HTTP method (GET, POST, etc.)
    val path: String,                         // Path da requisição
    val statusCode: Int,                      // Status code HTTP
    val userAgent: String?,                   // User-Agent da requisição
    val remoteAddress: String?,               // IP do cliente
    val requestHeaders: Map<String, String>,  // Headers da requisição (filtrados)
    val responseHeaders: Map<String, String>, // Headers da resposta
    val requestBody: String?,                 // Body da requisição (truncado se necessário)
    val responseBody: String?,                // Body da resposta (truncado se necessário)
    val duration: Long?,                      // Duração da requisição em ms
    val errorMessage: String?,                // Mensagem da exceção
    val stackTrace: String?,                  // Stack trace da exceção
    val timestamp: LocalDateTime,             // Timestamp do evento
    val additionalData: Map<String, Any>      // Dados adicionais (query params, etc.)
)
```

## 🛡️ Segurança

### Headers Sensíveis

Os seguintes headers são automaticamente filtrados por segurança:

- `authorization`
- `cookie`
- `set-cookie`
- `x-api-key`
- `x-auth-token`
- `proxy-authorization`

### Truncamento de Body

Bodies de requisição/resposta maiores que `max-body-size` são automaticamente truncados para evitar payloads muito grandes.

## 🔍 Monitoramento

### Health Check

Verifique a saúde do publisher:

```kotlin
@Autowired
private lateinit var publisher: ErrorEventPublisher

// Verifica se o publisher está saudável
val isHealthy = publisher.isHealthy()
```

### Métricas

A biblioteca não impacta a aplicação principal, executando completamente de forma assíncrona com:

- Thread pool dedicado
- Tratamento de exceções que não afeta o fluxo principal
- Configuração de timeout e retry automáticos

## 🧪 Testes

### Executar Testes

```bash
mvn test
```

### Testes de Integração

Os testes de integração usam TestContainers para testar contra uma instância real do LocalStack (simulando AWS):

```bash
mvn verify
```

### Desabilitar para Testes

```yaml
# application-test.yml
http-error-capture:
  enabled: false
```

## 📝 Configurações Importantes

### Performance

- `async.core-pool-size`: Número de threads base (padrão: 2)
- `async.max-pool-size`: Número máximo de threads (padrão: 10)
- `async.queue-capacity`: Capacidade da fila de tasks (padrão: 100)

### SQS

- `sqs.batch-size`: Número de mensagens por batch (padrão: 10)
- `sqs.visibility-timeout-seconds`: Timeout de visibilidade (padrão: 30)
- `sqs.message-retention-period`: Período de retenção em segundos (padrão: 14 dias)

### Captura

- `capture.max-body-size`: Tamanho máximo do body em bytes (padrão: 10KB)
- `capture.exclude-paths`: Paths que não devem ser capturados
- `capture.status-codes`: Status codes que devem ser capturados

## 🤝 Contribuição

1. Fork o projeto
2. Crie uma branch para sua feature (`git checkout -b feature/AmazingFeature`)
3. Commit suas mudanças (`git commit -m 'Add some AmazingFeature'`)
4. Push para a branch (`git push origin feature/AmazingFeature`)
5. Abra um Pull Request

## 📄 Licença

Este projeto está licenciado sob a Licença MIT - veja o arquivo [LICENSE](LICENSE) para detalhes.

## 🆘 Suporte

Para suporte, abra uma issue no repositório ou entre em contato com a equipe de desenvolvimento.

## 📋 Roadmap

- [ ] Suporte a outros message brokers (Kafka, RabbitMQ)
- [ ] Métricas com Micrometer
- [ ] Dashboard de monitoramento
- [ ] Filtros customizáveis de dados sensíveis
- [ ] Suporte a diferentes formatos de serialização
- [ ] Compressão de payloads grandes
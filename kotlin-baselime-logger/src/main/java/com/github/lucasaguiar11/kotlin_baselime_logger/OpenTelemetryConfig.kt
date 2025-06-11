import com.github.lucasaguiar11.kotlin_baselime_logger.Logger
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.LoggerProvider
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes
import java.util.concurrent.TimeUnit

object OpenTelemetryConfig {
    private var openTelemetry: OpenTelemetry? = null
    private var loggerProvider: LoggerProvider? = null
    private var isDebug = false
    private var isInitialized = false
    private var defaultData: Map<String, Any>? = null


    fun configure(
        endpoint: String,
        serviceName: String = "kotlin-otel-logger",
        serviceVersion: String = "1.0.0",
        headers: Map<String, String> = emptyMap(),
        batchTimeoutSeconds: Long = 5L,
        maxBatchSize: Int = 256, // Reduzido para melhor compatibilidade
        maxQueueSize: Int = 1024, // Reduzido para melhor compatibilidade
        isDebug: Boolean = false,
        defaultData: Map<String, Any>? = null,

        ): Boolean {
        if (isInitialized) {
            if (isDebug) println("OpenTelemetry already initialized")
            return true
        }

        this.isDebug = isDebug

        return try {
            if (isDebug) println("Initializing OpenTelemetry...")

            this.defaultData = defaultData ?: emptyMap()

            // Cria resource de forma mais simples
            val resourceBuilder = Resource.builder()
            resourceBuilder.put(ResourceAttributes.SERVICE_NAME, serviceName)
            resourceBuilder.put(ResourceAttributes.SERVICE_VERSION, serviceVersion)
            val resource = resourceBuilder.build()

            if (isDebug) println("Resource created for service: $serviceName")

            // Cria exporter com configuração mínima
            val exporterBuilder = OtlpGrpcLogRecordExporter.builder()
                .setEndpoint(endpoint)
                .setCompression("gzip") // Enable compression
                .setTimeout(5, TimeUnit.SECONDS) // Timeout para conexões

            // Adiciona headers de forma segura
            headers.forEach { (key, value) ->
                try {
                    exporterBuilder.addHeader(key, value)
                    if (isDebug) println("Added header: $key")
                } catch (e: Exception) {
                    if (isDebug) println("Failed to add header $key: ${e.message}")
                }
            }

            val otlpExporter = exporterBuilder.build()
            if (isDebug) println("OTLP exporter created")

            // Cria processor com configurações conservadoras
            val batchProcessor = BatchLogRecordProcessor.builder(otlpExporter)
                .setScheduleDelay(batchTimeoutSeconds, TimeUnit.SECONDS)
                .setMaxExportBatchSize(maxBatchSize)
                .setMaxQueueSize(maxQueueSize)
                .build()

            if (isDebug) println("Batch processor created")

            // Cria logger provider
            val sdkLoggerProvider = SdkLoggerProvider.builder()
                .setResource(resource)
                .addLogRecordProcessor(batchProcessor)
                .build()

            if (isDebug) println("Logger provider created")

            // Cria OpenTelemetry SDK
            openTelemetry = OpenTelemetrySdk.builder()
                .setLoggerProvider(sdkLoggerProvider)
                .build()

            loggerProvider = openTelemetry?.logsBridge
            isInitialized = true
            Logger.initialize()


            if (isDebug) {
                println("✅ OpenTelemetry initialized successfully")
                println("   Service: $serviceName")
                println("   Endpoint: $endpoint")
                println("   Headers: ${headers.size}")
            }

            true
        } catch (e: Exception) {
            if (isDebug) {
                println("❌ Failed to initialize OpenTelemetry: ${e.message}")
                e.printStackTrace()
            }
            isInitialized = false
            false
        }
    }

    fun getLoggerProvider(): LoggerProvider? = loggerProvider

    fun isInitialized(): Boolean = isInitialized

    fun isDebugEnabled(): Boolean = isDebug

    fun shutdown() {
        if (!isInitialized) return

        try {
            (openTelemetry as? OpenTelemetrySdk)?.shutdown()?.join(3, TimeUnit.SECONDS)
            if (isDebug) {
                println("OpenTelemetry shutdown completed")
            }
        } catch (e: Exception) {
            if (isDebug) {
                println("Error during shutdown: ${e.message}")
            }
        } finally {
            isInitialized = false
        }
    }

    fun getDefaultData(): Map<String, Any>? {
        return defaultData
    }
}
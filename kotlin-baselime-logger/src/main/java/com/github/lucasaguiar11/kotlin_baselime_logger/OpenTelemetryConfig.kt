import com.github.lucasaguiar11.kotlin_baselime_logger.Logger
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.LoggerProvider
import io.opentelemetry.api.trace.TracerProvider
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

object OpenTelemetryConfig {
    private var openTelemetry: OpenTelemetry? = null
    private var loggerProvider: LoggerProvider? = null
    private var tracerProvider: TracerProvider? = null
    private var isDebug = false
    private var isInitialized = false
    private var defaultData: Map<String, Any>? = null


    fun configure(
        endpoint: String,
        serviceName: String = "kotlin-otel-logger",
        serviceVersion: String = "1.0.0",
        environment: String = "production",
        headers: Map<String, String> = emptyMap(),
        batchTimeoutSeconds: Long = 5L,
        maxBatchSize: Int = 256,
        maxQueueSize: Int = 1024,
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

            val resourceBuilder = Resource.builder()
            resourceBuilder.put(ResourceAttributes.SERVICE_NAME, serviceName)
            resourceBuilder.put(ResourceAttributes.SERVICE_VERSION, serviceVersion)
            resourceBuilder.put(ResourceAttributes.DEPLOYMENT_ENVIRONMENT, environment)

            val resource = resourceBuilder.build()

            if (isDebug) println("Resource created for service: $serviceName")

            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            val trustManager = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                override fun checkClientTrusted(
                    certs: Array<X509Certificate>,
                    authType: String
                ) {
                }

                override fun checkServerTrusted(
                    certs: Array<X509Certificate>,
                    authType: String
                ) {
                }
            }
            // Inicializando o SSLContext antes de usá-lo
            sslContext.init(null, arrayOf(trustManager), null)

            // Cria exporter com configuração mínima
            val exporterBuilder = OtlpGrpcLogRecordExporter.builder()
                .setEndpoint(endpoint)
                .setCompression("gzip") // Enable compression
                .setTimeout(5, TimeUnit.SECONDS) // Timeout para conexões

            // Configura SSL para ignorar certificados inválidos
            exporterBuilder.setSslContext(sslContext, trustManager)
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

            // Cria span exporter com mesmas configurações
            val spanExporterBuilder = OtlpGrpcSpanExporter.builder()
                .setEndpoint(endpoint)
                .setCompression("gzip")
                .setTimeout(5, TimeUnit.SECONDS)

            // Configura SSL para spans
            spanExporterBuilder.setSslContext(sslContext, trustManager)
            headers.forEach { (key, value) ->
                try {
                    spanExporterBuilder.addHeader(key, value)
                    if (isDebug) println("Added span header: $key")
                } catch (e: Exception) {
                    if (isDebug) println("Failed to add span header $key: ${e.message}")
                }
            }

            val spanExporter = spanExporterBuilder.build()
            if (isDebug) println("OTLP span exporter created")

            // Cria span processor
            val spanProcessor = BatchSpanProcessor.builder(spanExporter)
                .setScheduleDelay(batchTimeoutSeconds, TimeUnit.SECONDS)
                .setMaxExportBatchSize(maxBatchSize)
                .setMaxQueueSize(maxQueueSize)
                .build()

            if (isDebug) println("Span processor created")

            // Cria tracer provider
            val sdkTracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(spanProcessor)
                .build()

            if (isDebug) println("Tracer provider created")

            // Cria OpenTelemetry SDK
            openTelemetry = OpenTelemetrySdk.builder()
                .setLoggerProvider(sdkLoggerProvider)
                .setTracerProvider(sdkTracerProvider)
                .build()

            loggerProvider = openTelemetry?.logsBridge
            tracerProvider = openTelemetry?.tracerProvider
            isInitialized = true


            if (isDebug) {
                println("✅ OpenTelemetry initialized successfully")
                println("   Service: $serviceName")
                println("   Endpoint: $endpoint")
                println("   Headers: ${headers.size}")
                println("   Logs + Traces enabled")
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

    fun getTracerProvider(): TracerProvider? = tracerProvider

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
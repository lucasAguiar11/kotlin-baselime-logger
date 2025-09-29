package com.github.lucasaguiar11.kotlin_baselime_logger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.lucasaguiar11.kotlin_baselime_logger.ui.theme.KotlinbaselimeloggerTheme
import java.lang.Thread.sleep
import java.util.UUID

data class User(val name: String, val age: Int)

class MainActivity : ComponentActivity() {

    fun runExampleLogger() {
        val user = User("Lucas", 30)

        Logger.i("MainActivity", "onCreate user", obj = user)

        Logger.i(
            "MainActivity",
            "onCreate",
            mapOf("timestamp" to System.currentTimeMillis().toString())
        )

        try {
            throw Exception("This is a test exception")
        } catch (e: Exception) {
            Logger.e(
                "MainActivity",
                "onCreate with exception",
                throwable = e
            )
        }

        Logger.d("MainActivity", "onCreate", mapOf("duration" to 1000))
        Logger.w("MainActivity", "onCreate", mapOf("duration" to "2000"))


        for (i in 1..50) {
            Logger.i("MainActivity - LOOP", "i => $i", mapOf("iteration" to i.toString()))
            Thread.sleep(10)
        }

        val requestId = UUID.randomUUID().toString()
        Logger.i(
            "TestRequestID", "Request XYZ", mapOf("teste" to "123"), requestId = requestId
        )
        Logger.i(
            "TestRequestID", "Request XYZ 1", mapOf("teste" to "123"), requestId = requestId
        )
        Logger.i(
            "TestRequestID", "Request XYZ 2", mapOf("teste" to "123") // ignore
        )

        Logger.i(
            "TestRequestID", "Request XYZ 3", mapOf("teste" to "123"), requestId = requestId
        )

    }

    fun runExampleTracer() {

        // Exemplo com start/stop e eventos

        val span = Logger.startSpan(
            "user_login", mapOf(
                "user.id" to "123",
                "login.method" to "email"
            )
        )

        // Adiciona eventos durante a operação
        Logger.addSpanEvent(span, "validation_started")
        Logger.addSpanEvent(span, "checking_credentials")

        // Finaliza com sucesso
        Logger.endSpan(
            span, mapOf(
                "result" to "success",
            ), SpanStatus.OK
        )

        // Exemplo com wrapper
        // Operação é automaticamente trackeada
        Logger.traceOperation("fetch_user", mapOf("user.id" to "123")) { spanContext ->
            // Pode adicionar eventos dentro da operação
            Logger.addSpanEvent(spanContext, "querying_database")

            // Sua lógica aqui
            sleep(1000) // Simula operação demorada
            Logger.addSpanEvent(spanContext, "processing_data")
        }

        // Span parent
        val parentSpan = Logger.startSpan("process_order", mapOf("order.id" to "456"))

        // Span filho
        val childSpan = Logger.startSpan(
            "validate_payment",
            mapOf("payment.method" to "credit_card"),
            parentContext = parentSpan
        )

        Logger.endSpan(childSpan, null, SpanStatus.OK)
        Logger.endSpan(parentSpan, null, SpanStatus.OK)


        val span2 = Logger.startSpan("risky_operation")

        try {
            throw Exception("Something went wrong!")
        } catch (e: Exception) {
            Logger.endSpan(
                span2, mapOf(
                    "error.type" to e.javaClass.simpleName,
                    "error.message" to e.message!!
                ), SpanStatus.ERROR
            )
        }


    }

    private fun runTransactionFlowExample() {
        Logger.createTrace("transaction.flow") {

            attributes = mapOf(
                "transaction.id" to "TXN-789-ABC",
                "transaction.amount" to 150.00,
                "customer.id" to "CUST-456"
            )

            val sdkWindow = span("sdk.transaction.window") {
                attributes = mapOf(
                    "sdk.name" to "payment-sdk",
                    "sdk.version" to "1.2.3"
                )
                status = SpanStatus.OK
                // duration será calculada automaticamente pelos filhos (3.5s)
            }

            sdkWindow.childSpan("sdk.transaction.init") {
                duration = 200 // 200ms
                attributes = mapOf(
                    "init.type" to "handshake",
                    "sdk.endpoint" to "https://sdk.payment.com/init",
                    "auth.method" to "api_key"
                )
                status = SpanStatus.OK
            }

            sdkWindow.childSpan("sdk.transaction.authorize") {
                duration = 2800 // 2.8 segundos
                attributes = mapOf(
                    "authorize.method" to "POST",
                    "authorize.endpoint" to "/v1/transactions/authorize",
                    "card.type" to "credit",
                    "card.last4" to "1234",
                    "merchant.id" to "MERCH-456"
                )
                status = SpanStatus.OK
            }

            // ✅ Confirm - filho do SDK Window
            sdkWindow.childSpan("sdk.transaction.confirm") {
                duration = 500 // 500ms
                attributes = mapOf(
                    "confirm.method" to "polling",
                    "confirm.attempts" to 3,
                    "confirm.interval_ms" to 150,
                    "final.status" to "approved",
                    "authorization.code" to "AUTH123456"
                )
                status = SpanStatus.OK
            }

            // Resultado:
            // transaction.flow: 3.5s (duração do sdk.window)
            // └── sdk.transaction.window: 3.5s (200ms + 2800ms + 500ms)
            //     ├── sdk.transaction.init: 200ms
            //     ├── sdk.transaction.authorize: 2800ms
            //     └── sdk.transaction.confirm: 500ms
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        // Inicializa OpenTelemetry
        OpenTelemetryConfig.configure(
            endpoint = "http://srv574063.hstgr.cloud:4517",
            serviceName = "kotlin-otel-logger-sample",
            serviceVersion = BuildConfig.VERSION_NAME,
            isDebug = true,
        )

        // Inicializa Logger com persistência
        Logger.initialize(this)


        runExampleLogger()
        runExampleTracer()
        runTransactionFlowExample()

        super.onCreate(savedInstanceState)
        setContent {
            KotlinbaselimeloggerTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column {
                        Greeting("Android")
                        Button(
                            onClick = {
                                Logger.i(
                                    "MainActivity", "Button clicked!"
                                )
                            },
                            modifier = Modifier
                                .width(150.dp)
                                .height(50.dp)
                        ) {
                            Text("Log Message")
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Graceful shutdown para garantir que logs pendentes sejam enviados
        Logger.shutdown()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    KotlinbaselimeloggerTheme {
        Greeting("Android")
    }
}
package com.github.lucasaguiar11.kotlin_baselime_logger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.github.lucasaguiar11.kotlin_baselime_logger.LoggerUtil.toMap
import com.github.lucasaguiar11.kotlin_baselime_logger.ui.theme.KotlinbaselimeloggerTheme
import java.util.UUID

data class User(val name: String, val age: Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {

        OpenTelemetryConfig.configure(
            endpoint = "http://srv574063.hstgr.cloud:4517",
            serviceName = "kotlin-otel-logger-sample",
            serviceVersion = BuildConfig.VERSION_NAME,
            isDebug = true,
        )

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


        super.onCreate(savedInstanceState)
        setContent {
            KotlinbaselimeloggerTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Greeting("Android")
                }
            }
        }
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
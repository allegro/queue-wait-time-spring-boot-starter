package pl.allegro.tech.queuewaittime.webclient

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.http.client.autoconfigure.reactive.ClientHttpConnectorBuilderCustomizer
import org.springframework.boot.http.client.reactive.ReactorClientHttpConnectorBuilder
import org.springframework.boot.webclient.WebClientCustomizer
import org.springframework.web.reactive.function.client.WebClient
import java.util.concurrent.TimeUnit

internal const val WEB_CLIENT_START_TIME_CONTEXT_KEY = "pl.allegro.tech.queuewaittime.webclient.startTime"

internal class WebClientQueueWaitTimeCustomizer : WebClientCustomizer {
    override fun customize(webClientBuilder: WebClient.Builder) {
        webClientBuilder.filter { request, next ->
            next.exchange(request)
                .contextWrite { it.put(WEB_CLIENT_START_TIME_CONTEXT_KEY, System.nanoTime()) }
        }
    }
}

internal class ReactorClientHttpConnectorQueueWaitTimeCustomizer(
    private val meterRegistry: MeterRegistry,
) : ClientHttpConnectorBuilderCustomizer<ReactorClientHttpConnectorBuilder> {
    override fun customize(builder: ReactorClientHttpConnectorBuilder): ReactorClientHttpConnectorBuilder =
        builder.withHttpClientCustomizer { httpClient ->
            httpClient.doOnRequest { request, _ ->
                request.currentContextView().getOrEmpty<Long>(WEB_CLIENT_START_TIME_CONTEXT_KEY)
                    .ifPresent { startTime ->
                        meterRegistry.timer("webclient_queue_wait_time")
                            .record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS)
                    }
            }
        }
}

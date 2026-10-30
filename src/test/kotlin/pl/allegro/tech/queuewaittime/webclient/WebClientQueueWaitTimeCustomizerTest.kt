package pl.allegro.tech.queuewaittime.webclient

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.netty.http.client.HttpClient
import reactor.netty.http.server.HttpServer
import reactor.netty.resources.ConnectionProvider
import reactor.netty.resources.LoopResources
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class WebClientQueueWaitTimeCustomizerTest {
    private val registry = SimpleMeterRegistry()
    private val resources = LoopResources.create("webclient-customizer-test", 1, true)
    private val connections = ConnectionProvider.builder("webclient-customizer-test")
        .maxConnections(1)
        .build()
    private val connectorBuilder = ClientHttpConnectorBuilder.reactor()
        .withHttpClientFactory { HttpClient.create(connections).runOn(resources, false) }

    @AfterEach
    fun closeResources() {
        try {
            connections.disposeLater().block(Duration.ofSeconds(5))
            resources.disposeLater(Duration.ZERO, Duration.ofSeconds(5)).block()
        } finally {
            registry.close()
        }
    }

    @Test
    fun recordsRequestsWithoutADemoHeader() {
        val server = HttpServer.create().host("127.0.0.1").port(0)
            .handle { _, response -> response.sendString(Mono.just("ok")) }
            .bindNow(Duration.ofSeconds(5))
        try {
            val builder = WebClient.builder().clientConnector(
                ReactorClientHttpConnectorQueueWaitTimeCustomizer(registry).customize(connectorBuilder).build()
            )
            WebClientQueueWaitTimeCustomizer().customize(builder)

            val body = builder.build().get().uri("http://127.0.0.1:${server.port()}")
                .retrieve().bodyToMono(String::class.java).block(Duration.ofSeconds(5))

            assertThat(body).isEqualTo("ok")
            val timer = registry.find("webclient_queue_wait_time").timer()
            assertThat(timer).isNotNull
            assertThat(timer!!.count()).isEqualTo(1)
            assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isPositive()
            assertThat(registry.find("custom.web.client.queue_wait_time").timers()).isEmpty()
        } finally {
            server.disposeNow()
        }
    }

    @Test
    fun startsTimingAtEachSubscription() {
        val startTimes = mutableListOf<Long>()
        val builder = WebClient.builder().exchangeFunction {
            Mono.deferContextual { context ->
                assertThat(context.get<String>("existing")).isEqualTo("preserved")
                startTimes.add(context.getOrDefault(WEB_CLIENT_START_TIME_CONTEXT_KEY, 0L) ?: 0L)
                Mono.just(ClientResponse.create(HttpStatus.OK).build())
            }
        }
        WebClientQueueWaitTimeCustomizer().customize(builder)
        val request = builder.build().get().uri("http://localhost/unused")
            .retrieve().toBodilessEntity()
            .contextWrite { it.put("existing", "preserved") }

        val beforeFirstSubscription = System.nanoTime()
        request.block(Duration.ofSeconds(5))
        val beforeSecondSubscription = System.nanoTime()
        request.block(Duration.ofSeconds(5))

        assertThat(startTimes).hasSize(2)
        assertThat(startTimes[0]).isGreaterThanOrEqualTo(beforeFirstSubscription)
        assertThat(startTimes[1]).isGreaterThanOrEqualTo(beforeSecondSubscription)
        assertThat(startTimes[1]).isGreaterThan(startTimes[0])
    }

    @Test
    fun skipsRequestsWithoutAStartTime() {
        val server = HttpServer.create().host("127.0.0.1").port(0)
            .handle { _, response -> response.sendString(Mono.just("ok")) }
            .bindNow(Duration.ofSeconds(5))
        try {
            val client = WebClient.builder().clientConnector(
                ReactorClientHttpConnectorQueueWaitTimeCustomizer(registry).customize(connectorBuilder).build()
            ).build()

            assertThat(client.get().uri("http://127.0.0.1:${server.port()}")
                .retrieve().bodyToMono(String::class.java).block(Duration.ofSeconds(5)))
                .isEqualTo("ok")
            assertThat(registry.find("webclient_queue_wait_time").timers().sumOf { it.count() }).isZero()
        } finally {
            server.disposeNow()
        }
    }

    @Test
    fun preservesExistingCustomizations() {
        val callbacks = AtomicInteger()
        val server = HttpServer.create().host("127.0.0.1").port(0)
            .handle { request, response ->
                response.sendString(Mono.just(
                    "${request.requestHeaders().get("X-existing")}:${request.requestHeaders().get("X-filter")}"
                ))
            }
            .bindNow(Duration.ofSeconds(5))
        try {
            val customizedConnector = connectorBuilder.withHttpClientCustomizer { client ->
                client.doOnRequest { _, _ -> callbacks.incrementAndGet() }
            }
            val builder = WebClient.builder()
                .defaultHeader("X-existing", "kept")
                .filter { request, next ->
                    next.exchange(ClientRequest.from(request).header("X-filter", "also-kept").build())
                }
                .clientConnector(
                    ReactorClientHttpConnectorQueueWaitTimeCustomizer(registry).customize(customizedConnector).build()
                )
            WebClientQueueWaitTimeCustomizer().customize(builder)

            val body = builder.build().get().uri("http://127.0.0.1:${server.port()}")
                .retrieve().bodyToMono(String::class.java).block(Duration.ofSeconds(5))

            assertThat(body).isEqualTo("kept:also-kept")
            assertThat(callbacks.get()).isEqualTo(1)
            assertThat(registry.get("webclient_queue_wait_time").timer().count()).isEqualTo(1)
            assertThat(connections.isDisposed).isFalse()
            assertThat(resources.isDisposed).isFalse()
        } finally {
            server.disposeNow()
        }
    }

    @Test
    fun includesPendingConnectionAcquisition() {
        val firstReceived = CountDownLatch(1)
        val releaseFirst = Sinks.one<String>()
        val server = HttpServer.create().host("127.0.0.1").port(0)
            .handle { request, response ->
                if (request.uri() == "/first") {
                    firstReceived.countDown()
                    response.sendString(releaseFirst.asMono())
                } else {
                    response.sendString(Mono.just("second"))
                }
            }
            .bindNow(Duration.ofSeconds(5))
        try {
            val builder = WebClient.builder().baseUrl("http://127.0.0.1:${server.port()}").clientConnector(
                ReactorClientHttpConnectorQueueWaitTimeCustomizer(registry).customize(connectorBuilder).build()
            )
            WebClientQueueWaitTimeCustomizer().customize(builder)
            val client = builder.build()
            val first = client.get().uri("/first").retrieve().bodyToMono(String::class.java).toFuture()
            try {
                assertThat(firstReceived.await(5, TimeUnit.SECONDS)).isTrue()
                val timer = registry.find("webclient_queue_wait_time").timer()
                assertThat(timer).isNotNull
                assertThat(timer!!.count()).isEqualTo(1)
                assertThat(first.isDone).isFalse()

                val second = client.get().uri("/second").retrieve().bodyToMono(String::class.java).toFuture()
                try {
                    Thread.sleep(100)
                    assertThat(second.isDone).isFalse()
                    assertThat(timer.count()).isEqualTo(1)

                    releaseFirst.tryEmitValue("first")

                    assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first")
                    assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second")
                    assertThat(timer.count()).isEqualTo(2)
                    assertThat(timer.max(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(50.0)
                } finally {
                    second.cancel(true)
                }
            } finally {
                releaseFirst.tryEmitValue("first")
                first.cancel(true)
            }
        } finally {
            server.disposeNow()
        }
    }
}

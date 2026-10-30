package pl.allegro.tech.queuewaittime.webclient

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.http.client.autoconfigure.reactive.ClientHttpConnectorBuilderCustomizer
import org.springframework.boot.http.client.reactive.ReactorClientHttpConnectorBuilder
import org.springframework.boot.webclient.WebClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

@AutoConfiguration(
    afterName = ["org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"],
)
@ConditionalOnClass(
    WebClient::class,
    WebClientCustomizer::class,
    ClientHttpConnectorBuilderCustomizer::class,
    ReactorClientHttpConnectorBuilder::class,
    HttpClient::class,
    MeterRegistry::class,
)
@ConditionalOnBean(MeterRegistry::class)
class WebClientQueueWaitTimeAutoConfiguration {
    @Bean
    fun webClientQueueWaitTimeCustomizer(): WebClientCustomizer = WebClientQueueWaitTimeCustomizer()

    @Bean
    fun reactorClientHttpConnectorQueueWaitTimeCustomizer(
        meterRegistry: ObjectProvider<MeterRegistry>,
    ): ClientHttpConnectorBuilderCustomizer<ReactorClientHttpConnectorBuilder> =
        ReactorClientHttpConnectorQueueWaitTimeCustomizer(meterRegistry.getObject())
}

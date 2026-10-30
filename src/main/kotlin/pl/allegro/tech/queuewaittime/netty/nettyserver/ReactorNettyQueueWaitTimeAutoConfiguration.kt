package pl.allegro.tech.queuewaittime.netty.nettyserver

import io.micrometer.core.instrument.MeterRegistry
import io.netty.util.concurrent.EventExecutor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.context.annotation.Bean
import pl.allegro.tech.queuewaittime.netty.EventLoopLagProperties
import reactor.netty.http.server.HttpServer

@AutoConfiguration(
    afterName = ["org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"],
)
@ConditionalOnClass(HttpServer::class, EventExecutor::class, MeterRegistry::class, NettyServerCustomizer::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnBean(MeterRegistry::class)
@ConditionalOnProperty(
    prefix = "allegro-tech.event-loop-lag",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(EventLoopLagProperties::class)
class ReactorNettyQueueWaitTimeAutoConfiguration {
    @Bean
    fun reactorNettyServerEventLoopLagCustomizer(
        meterRegistry: ObjectProvider<MeterRegistry>,
        properties: EventLoopLagProperties,
    ): NettyServerCustomizer = ReactorNettyServerEventLoopLagCustomizer(meterRegistry.getObject(), properties)
}

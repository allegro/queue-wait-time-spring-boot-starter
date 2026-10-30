package pl.allegro.tech.queuewaittime.netty.mongoclient

import com.mongodb.MongoClientSettings
import com.mongodb.reactivestreams.client.MongoClient
import io.micrometer.core.instrument.MeterRegistry
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer
import org.springframework.boot.mongodb.autoconfigure.MongoReactiveAutoConfiguration
import org.springframework.context.annotation.Bean
import pl.allegro.tech.queuewaittime.netty.EventLoopLagProperties
import reactor.core.publisher.Flux

@AutoConfiguration(
    before = [MongoReactiveAutoConfiguration::class],
    afterName = ["org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"],
)
@ConditionalOnClass(
    value = [
        MongoClient::class,
        MongoClientSettingsBuilderCustomizer::class,
        Flux::class,
        MeterRegistry::class,
        SocketChannel::class,
        MultiThreadIoEventLoopGroup::class,
        NioIoHandler::class,
    ],
    name = ["io.netty.handler.ssl.SslContext"],
)
@ConditionalOnBean(MeterRegistry::class)
@ConditionalOnMissingBean(MongoClientSettings::class)
@ConditionalOnProperty(
    prefix = "allegro-tech.event-loop-lag",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(EventLoopLagProperties::class)
class ReactiveMongoQueueWaitTimeAutoConfiguration {
    @Bean
    fun mongoEventLoopLagCustomizer(
        meterRegistry: ObjectProvider<MeterRegistry>,
        properties: EventLoopLagProperties,
    ): MongoClientSettingsBuilderCustomizer = MongoEventLoopLagCustomizer(meterRegistry.getObject(), properties)
}

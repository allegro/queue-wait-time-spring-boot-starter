package pl.allegro.tech.queuewaittime.tomcat

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.thread.Threading
import org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean

@AutoConfiguration(
    afterName = ["org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"],
)
@ConditionalOnClass(ConfigurableTomcatWebServerFactory::class, MeterRegistry::class)
@ConditionalOnWebApplication
@ConditionalOnBean(MeterRegistry::class)
@ConditionalOnProperty(
    prefix = "allegro-tech.tomcat.queue-wait-time",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(TomcatServerProperties::class)
class TomcatQueueWaitTimeAutoConfiguration {
    @Bean
    @ConditionalOnThreading(Threading.PLATFORM)
    fun tomcatPlatformThreadsWebServerCustomizer(
        properties: TomcatServerProperties,
        meterRegistry: ObjectProvider<MeterRegistry>,
    ): WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> =
        TomcatPlatformThreadsWebServerCustomizer(properties, meterRegistry.getObject())

    @Bean
    @ConditionalOnThreading(Threading.VIRTUAL)
    fun tomcatVirtualThreadsWebServerCustomizer(
        meterRegistry: ObjectProvider<MeterRegistry>,
    ): WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> =
        TomcatVirtualThreadsWebServerCustomizer(meterRegistry.getObject())
}

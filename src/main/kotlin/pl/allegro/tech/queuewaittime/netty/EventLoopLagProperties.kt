package pl.allegro.tech.queuewaittime.netty

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "allegro-tech.event-loop-lag")
class EventLoopLagProperties {
    var enabled: Boolean = true
    var interval: Duration = Duration.ofMillis(10)
}

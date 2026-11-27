# Appendix

## Metrics

| Metric                      | Meter type | Integration                              | Tags                                                   | Measurement                                                                                                                                                    |
|-----------------------------|------------|------------------------------------------|--------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `executor.queue.wait.time`  | Timer      | Tomcat platform threads                  | `name=tomcat`                                          | Time between task submission and the start of execution.                                                                                                       |
| `executor.queue.wait.time`  | Timer      | Tomcat virtual threads                   | `name=tomcat-virtual-threads`                          | Initial task-start delay; later mounting and unmounting are not measured.                                                                                      |
| `webclient.queue.wait.time` | Timer      | WebClient with Reactor Netty             | No integration-specific tags                           | Time from exchange subscription until Reactor Netty is about to send the request. May include scheduling delays, connection acquisition, and connection setup. |
| `scheduler.sample`          | Counter    | Netty, virtual threads, reactive MongoDB | `executor`, `type`, `status` (`healthy` / `unhealthy`) | Healthy: increments by `1` when `diff ≤ 2 × interval`. Unhealthy: increments by `floor(diff / interval)` when `diff > 2 × interval`.                           |
| `scheduler.lag`             | Timer      | Netty, virtual threads, reactive MongoDB | `executor`, `type`, `status=unhealthy`                 | Records `diff - 2 × interval` for unhealthy samples only.                                                                                                      |

For scheduler metrics, `executor` is the event-loop thread name or virtual-thread
scheduler name. The `type` tag is `server`, `mongo`, or `VirtualThreadDefaultScheduler`.

When metric renaming is disabled, the Tomcat queue wait time timer retains
Micrometer's original name, `executor.idle`. Tags and measurements are unchanged.

## Properties

| Property                                                                | Default | Description                                                                                                  |
|-------------------------------------------------------------------------|---------|--------------------------------------------------------------------------------------------------------------|
| `allegro-tech.tomcat.queue-wait-time.enabled`                           | `true`  | Controls Tomcat queue wait time monitoring for platform and virtual threads.                                 |
| `allegro-tech.webclient.queue-wait-time.enabled`                        | `true`  | Controls WebClient queue wait time monitoring.                                                               |
| `allegro-tech.scheduler-lag.enabled`                                    | `true`  | Controls scheduler lag monitoring for Netty, virtual threads, and reactive MongoDB.                          |
| `allegro-tech.scheduler-lag.interval`                                   | `10ms`  | Sampling interval used to classify samples and calculate scheduler lag.                                      |
| `allegro-tech.rename-executor-idle-to-executor-queue-wait-time.enabled` | `true`  | Renames `executor.idle` to `executor.queue.wait.time`; applies to matching metrics, not only Tomcat metrics. |

# Config Schema Reference

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `version` | string |  | `` | Config version identifier |
| `scheduler` | object |  |  | Unified scheduler configuration |
| `links` | list[object] |  |  | Link (connection pool) configurations |
| `inputs` | object |  |  | Input source configurations |
| `queues` | object |  |  | Queue system configuration |
| `outputs` | object |  |  | Output sink configurations |
| `rules` | list[object] |  |  | Message forwarding rules |
| `deadLetter` | object |  |  | Dead-letter handling for messages that exhausted all retries |
| `quickSettings` | object |  |  | Quick-settings tile configuration |

## `scheduler`

Unified scheduler configuration

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `tickInterval` | duration |  | `40s` | Scheduler tick interval (minimum 20s enforced at runtime) |
| `chargingTickInterval` | duration |  |  | Tick interval when charging (defaults to tickInterval if omitted) |
| `wakeLockTimeout` | duration |  | `1h` | Wake lock maximum hold duration |
| `wifiLockTimeout` | duration |  | `1h` | WiFi lock maximum hold duration |

## `links`

Link (connection pool) configurations

- **Type**: list[object]


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `id` | string | ✓ |  | Unique link identifier referenced by inputs and outputs |
| `dsn` | string |  |  | Connection string: protocol://[user:pass@]host:port[?params]. Protocol determines link type: mqtt[s]:// \| ws[s]:// \| tcp[s]:// \| http[s]:// |
| `clientId` | string |  |  | Client identifier (MQTT) |
| `host` | string |  |  | Host override when not using dsn |
| `port` | int |  |  | Port override when not using dsn _(1–65535)_ |
| `reconnect` | object |  |  | Reconnection policy |
| `tls` | object |  |  | TLS configuration |
| `when` | string |  |  | Enable condition (e.g. network=wifi,ssid=MyWiFi) |
| `deny` | string |  |  | Disable condition (e.g. network=mobile) |

### `reconnect`

Reconnection policy

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `enabled` | boolean |  | `true` |  |
| `interval` | duration |  | `10s` | Initial reconnect interval |
| `maxInterval` | duration |  | `60s` | Maximum reconnect interval |

### `tls`

TLS configuration

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `ca` | string |  |  | CA certificate path |
| `cert` | string |  |  | Client certificate path |
| `key` | string |  |  | Client private key path |
| `insecure` | boolean |  | `false` | Skip TLS certificate verification |

## `inputs`

Input source configurations

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `http` | list[object] |  |  | HTTP server input sources |
| `link` | list[object] |  |  | Link-based input sources (MQTT subscriber, WebSocket, TCP) |

### `http`

HTTP server input sources

- **Type**: list[object]


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `name` | string | ✓ |  | Unique input name referenced by rules |
| `dsn` | string | ✓ |  | HTTP listen DSN: http://[user:pass@]host:port[?method=GET&token=xxx] |
| `paths` | list[string] |  |  | URL path filters (empty = all paths) |
| `linkId` | string |  |  | Link to also publish received messages to |
| `when` | string |  |  | Enable condition |
| `deny` | string |  |  | Disable condition |

### `link`

Link-based input sources (MQTT subscriber, WebSocket, TCP)

- **Type**: list[object]


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `name` | string | ✓ |  | Unique input name referenced by rules |
| `linkId` | list[string] | ✓ |  | Link ID(s) to subscribe from (string or list of strings) |
| `role` | enum |  | `consumer` | Link role: consumer (subscribe) or producer (publish) `consumer` / `producer` |
| `topic` | string |  |  | Topic to subscribe (MQTT) |
| `topics` | list[string] |  |  | Multiple topics to subscribe |
| `excludeTopics` | list[string] |  |  | Topics to exclude |
| `qos` | int |  |  | MQTT QoS level _(0–2)_ |
| `replay` | object |  |  | Message replay configuration |
| `when` | string |  |  | Enable condition |
| `deny` | string |  |  | Disable condition |

#### `replay`

Message replay configuration

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `enabled` | boolean |  | `false` |  |
| `provider` | enum |  | `gotifyApi` | Replay data provider `gotifyApi` |
| `messageIdPath` | string |  | `id` | JSON path to the message ID field |
| `pageSize` | int |  | `50` | Messages per page when fetching |
| `maxPages` | int |  | `20` | Maximum pages to fetch |
| `maxMessages` | int |  | `500` | Maximum total messages to replay |
| `persistState` | boolean |  | `true` | Persist last-seen message ID across restarts |
| `baseUrl` | string |  |  | Provider base URL |
| `token` | string |  |  | Provider authentication token |
| `applicationId` | int |  |  | Provider application ID filter |

## `queues`

Queue system configuration

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `memory` | map[object] |  |  |  |
| `sqlite` | map[object] |  |  |  |

### `memory`

- **Type**: map[object]


*每个命名实例的属性如下：*

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `capacity` | int |  | `1000` | Maximum queue capacity |
| `workers` | int |  | `1` | Number of consumer coroutines |
| `overflow` | enum |  | `dropOldest` | Overflow strategy when queue is full `dropOldest` / `dropNew` / `block` |

### `sqlite`

- **Type**: map[object]


*每个命名实例的属性如下：*

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `path` | string | ✓ |  | Database path. Protocols: data:// \| sdcard:// \| file:// \| cache:// |
| `batchSize` | int |  | `20` | Messages to dequeue per tick |
| `retryInterval` | duration |  | `5s` | Minimum interval between retry attempts |
| `maxRetry` | int |  | `10` | Maximum delivery attempts before dead-lettering |
| `backoff` | object |  |  | Retry backoff configuration |
| `cleanup` | object |  |  | Completed-message cleanup policy |

#### `backoff`

Retry backoff configuration

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `type` | enum |  | `exponential` | Backoff calculation strategy `exponential` / `linear` |
| `initial` | duration |  | `2s` | Initial backoff duration |
| `max` | duration |  | `5m` | Maximum backoff duration |

#### `cleanup`

Completed-message cleanup policy

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `maxAge` | duration |  | `7d` | Retain completed messages for this duration |

## `outputs`

Output sink configurations

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `http` | list[object] |  |  | HTTP output sinks |
| `link` | list[object] |  |  | Link output sinks (MQTT publish, WebSocket send, TCP send) |
| `internal` | list[object] |  |  | Internal output sinks (clipboard, file, broadcast, notify) |

### `http`

HTTP output sinks

- **Type**: list[object]


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `name` | string | ✓ |  | Unique output name referenced by rules |
| `url` | string | ✓ |  | Target HTTP URL |
| `method` | string |  | `POST` | HTTP method |
| `headers` | any |  |  | Additional HTTP request headers; values support template variables |
| `body` | string |  |  | Request body template (defaults to message data) |
| `timeout` | duration |  | `5s` | Request timeout |
| `retry` | object |  |  | Retry policy on transient failure |
| `onFailureQueue` | object |  |  | Queue to enqueue message after all retries are exhausted (for async retry) |
| `queue` | object |  |  | Queue reference for async delivery |
| `format` | any |  |  | Output format: string template or list of format steps |

#### `retry`

Retry policy on transient failure

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `maxAttempts` | int |  | `1` | Maximum delivery attempts |
| `interval` | duration |  | `1s` | Interval between retry attempts |

#### `onFailureQueue`

Queue to enqueue message after all retries are exhausted (for async retry)

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `name` | string | ✓ |  | Queue name as defined in queues section |
| `delay` | duration |  | `0s` | Enqueue delay before the message becomes eligible for retry |

#### `queue`

Queue reference for async delivery

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `name` | string | ✓ |  | Queue name as defined in queues section |
| `delay` | duration |  | `0s` | Enqueue delay before the message is first processed |

### `link`

Link output sinks (MQTT publish, WebSocket send, TCP send)

- **Type**: list[object]


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `name` | string | ✓ |  | Unique output name referenced by rules |
| `linkId` | list[string] | ✓ |  | Target link ID(s) — single string or list for fan-out to multiple links |
| `role` | enum |  | `producer` | Link role `consumer` / `producer` |
| `topic` | string |  |  | Target topic (MQTT) |
| `qos` | int |  |  | MQTT QoS level _(0–2)_ |
| `retain` | boolean |  | `false` | MQTT retain flag |
| `retry` | object |  |  |  |
| `onFailureQueue` | object |  |  | Queue to enqueue message after all retries are exhausted (for async retry) |
| `queue` | object |  |  |  |
| `when` | string |  |  | Enable condition |
| `deny` | string |  |  | Disable condition |
| `format` | any |  |  | Output format: string template or list of format steps |

#### `retry`

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `maxAttempts` | int |  | `1` |  |
| `interval` | duration |  | `1s` |  |

#### `onFailureQueue`

Queue to enqueue message after all retries are exhausted (for async retry)

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `name` | string | ✓ |  |  |
| `delay` | duration |  | `0s` | Enqueue delay before the message becomes eligible for retry |

#### `queue`

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `name` | string | ✓ |  |  |
| `delay` | duration |  | `0s` | Enqueue delay before the message is first processed |

### `internal`

Internal output sinks (clipboard, file, broadcast, notify)

- **Type**: list[object]


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `name` | string | ✓ |  | Unique output name referenced by rules |
| `type` | enum | ✓ |  | Internal output type `clipboard` / `file` / `broadcast` / `notify` / `clipboardHistory` |
| `basePath` | string |  |  | Base path for file output |
| `fileName` | string |  |  | File name template for file output |
| `channel` | string |  |  | Notification channel ID |
| `queue` | object |  |  |  |
| `format` | any |  |  | Output format: string template or list of format steps |

#### `queue`

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `name` | string | ✓ |  |  |
| `delay` | duration |  | `0s` | Enqueue delay before the message is first processed |

## `rules`

Message forwarding rules

- **Type**: list[object]


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `name` | string | ✓ |  | Unique rule name |
| `from` | list[string] | ✓ |  | Source input name(s): single string or list of strings |
| `pipeline` | list[object] |  |  | Processing pipeline steps |
| `onError` | list[object] |  |  | Pipeline executed on error |
| `when` | string |  |  | Enable condition |
| `deny` | string |  |  | Disable condition |

### `pipeline`

Processing pipeline steps

- **Type**: list[object]


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `transform` | object |  |  | Data transformation to apply |
| `to` | list[string] |  |  | Output names to forward to after this step |

#### `transform`

Data transformation to apply

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `decode` | string |  |  | Decode pipeline, e.g. 'base64Decode', 'jsonDecode', 'gzDecode' |
| `detect` | string |  |  | Type detection: 'image', 'json', 'text' |
| `enrich` | string |  |  | Enrichment spec, e.g. 'gotifyIcon:linkId' |
| `filter` | string |  |  | Filter expression, e.g. 'data.type == "alert"' |
| `extract` | string |  |  | GJSON path or '$raw', e.g. 'data.temperature' |
| `format` | string |  |  | Template string, e.g. '{data.title}: {data.message}' |
| `formatSteps` | list[string] |  |  | Structured format steps (mutually exclusive with format) |
| `breakOnReject` | boolean |  | `false` | Abort pipeline when filter rejects |

### `onError`

Pipeline executed on error

- **Type**: list[object]


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `transform` | object |  |  |  |
| `to` | list[string] |  |  |  |

## `deadLetter`

Dead-letter handling for messages that exhausted all retries

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `enabled` | boolean |  | `false` |  |
| `maxRetry` | int |  | `10` | Maximum retry attempts in dead-letter processing |
| `action` | list[object] |  |  | Dead-letter processing pipeline |

### `action`

Dead-letter processing pipeline

- **Type**: list[object]


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `transform` | object |  |  |  |
| `to` | list[string] |  |  |  |

## `quickSettings`

Quick-settings tile configuration

- **Type**: object


| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| `inputMethodSwitcher` | boolean |  | `true` | Show input method switcher tile |

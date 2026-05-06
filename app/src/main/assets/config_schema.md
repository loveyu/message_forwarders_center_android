# Config Schema Reference

## `version`

Config version identifier

- **Type**: string
- **Default**: ``

## `scheduler`

Unified scheduler configuration

- **Type**: object

### `tickInterval`

Scheduler tick interval (minimum 20s enforced at runtime)

- **Type**: duration
- **Default**: `40s`

### `chargingTickInterval`

Tick interval when charging (defaults to tickInterval if omitted)

- **Type**: duration

### `wakeLockTimeout`

Wake lock maximum hold duration

- **Type**: duration
- **Default**: `1h`

### `wifiLockTimeout`

WiFi lock maximum hold duration

- **Type**: duration
- **Default**: `1h`

## `links`

Link (connection pool) configurations

- **Type**: list of object

**Item fields:**

### `id`

Unique link identifier referenced by inputs and outputs

- **Type**: string
- **Required**: yes

### `dsn`

Connection string: protocol://[user:pass@]host:port[?params]. Protocol determines link type: mqtt[s]:// | ws[s]:// | tcp[s]:// | http[s]://

- **Type**: string

### `clientId`

Client identifier (MQTT)

- **Type**: string

### `host`

Host override when not using dsn

- **Type**: string

### `port`

Port override when not using dsn

- **Type**: int
- **Range**: 1 – 65535

### `reconnect`

Reconnection policy

- **Type**: object

#### `enabled`

- **Type**: boolean
- **Default**: `true`

#### `interval`

Initial reconnect interval

- **Type**: duration
- **Default**: `10s`

#### `maxInterval`

Maximum reconnect interval

- **Type**: duration
- **Default**: `60s`

### `tls`

TLS configuration

- **Type**: object

#### `ca`

CA certificate path

- **Type**: string

#### `cert`

Client certificate path

- **Type**: string

#### `key`

Client private key path

- **Type**: string

#### `insecure`

Skip TLS certificate verification

- **Type**: boolean
- **Default**: `false`

### `when`

Enable condition (e.g. network=wifi,ssid=MyWiFi)

- **Type**: string

### `deny`

Disable condition (e.g. network=mobile)

- **Type**: string

## `inputs`

Input source configurations

- **Type**: object

### `http`

HTTP server input sources

- **Type**: list of object

**Item fields:**

#### `name`

Unique input name referenced by rules

- **Type**: string
- **Required**: yes

#### `dsn`

HTTP listen DSN: http://[user:pass@]host:port[?method=GET&token=xxx]

- **Type**: string
- **Required**: yes

#### `paths`

URL path filters (empty = all paths)

- **Type**: list of string

#### `linkId`

Link to also publish received messages to

- **Type**: string

#### `when`

Enable condition

- **Type**: string

#### `deny`

Disable condition

- **Type**: string

### `link`

Link-based input sources (MQTT subscriber, WebSocket, TCP)

- **Type**: list of object

**Item fields:**

#### `name`

Unique input name referenced by rules

- **Type**: string
- **Required**: yes

#### `linkId`

Link ID(s) to subscribe from (string or list of strings)

- **Type**: any
- **Required**: yes

#### `role`

Link role: consumer (subscribe) or producer (publish)

- **Type**: enum
- **Default**: `consumer`
- **Values**: `consumer`, `producer`

#### `topic`

Topic to subscribe (MQTT)

- **Type**: string

#### `topics`

Multiple topics to subscribe

- **Type**: list of string

#### `excludeTopics`

Topics to exclude

- **Type**: list of string

#### `qos`

MQTT QoS level

- **Type**: int
- **Range**: 0 – 2

#### `replay`

Message replay configuration

- **Type**: object

##### `enabled`

- **Type**: boolean
- **Default**: `false`

##### `provider`

Replay data provider

- **Type**: enum
- **Default**: `gotifyApi`
- **Values**: `gotifyApi`

##### `messageIdPath`

JSON path to the message ID field

- **Type**: string
- **Default**: `id`

##### `pageSize`

Messages per page when fetching

- **Type**: int
- **Default**: `50`

##### `maxPages`

Maximum pages to fetch

- **Type**: int
- **Default**: `20`

##### `maxMessages`

Maximum total messages to replay

- **Type**: int
- **Default**: `500`

##### `persistState`

Persist last-seen message ID across restarts

- **Type**: boolean
- **Default**: `true`

##### `baseUrl`

Provider base URL

- **Type**: string

##### `token`

Provider authentication token

- **Type**: string

##### `applicationId`

Provider application ID filter

- **Type**: int

#### `when`

Enable condition

- **Type**: string

#### `deny`

Disable condition

- **Type**: string

### `failQueue`

Fail-queue input sources (re-inject failed messages)

- **Type**: list of object

**Item fields:**

#### `name`

Unique input name referenced by rules

- **Type**: string
- **Required**: yes

#### `idTypes`

Message type filter (empty = all)

- **Type**: list of string

#### `queues`

Queue references to read from, e.g. 'sqlite:myQueue', 'memory:myQueue'

- **Type**: list of string

#### `batchSize`

Maximum messages to process per tick

- **Type**: int
- **Default**: `20`

## `queues`

Queue system configuration

- **Type**: object

### `memory`

- **Type**: map of object

**Value fields:**

#### `capacity`

Maximum queue capacity

- **Type**: int
- **Default**: `1000`

#### `workers`

Number of consumer coroutines

- **Type**: int
- **Default**: `1`

#### `overflow`

Overflow strategy when queue is full

- **Type**: enum
- **Default**: `dropOldest`
- **Values**: `dropOldest`, `dropNew`, `block`

### `sqlite`

- **Type**: map of object

**Value fields:**

#### `path`

Database path. Protocols: data:// | sdcard:// | file:// | cache://

- **Type**: string
- **Required**: yes

#### `batchSize`

Messages to dequeue per tick

- **Type**: int
- **Default**: `20`

#### `retryInterval`

Minimum interval between retry attempts

- **Type**: duration
- **Default**: `5s`

#### `maxRetry`

Maximum delivery attempts before dead-lettering

- **Type**: int
- **Default**: `10`

#### `backoff`

Retry backoff configuration

- **Type**: object

##### `type`

Backoff calculation strategy

- **Type**: enum
- **Default**: `exponential`
- **Values**: `exponential`, `linear`

##### `initial`

Initial backoff duration

- **Type**: duration
- **Default**: `2s`

##### `max`

Maximum backoff duration

- **Type**: duration
- **Default**: `5m`

#### `cleanup`

Completed-message cleanup policy

- **Type**: object

##### `maxAge`

Retain completed messages for this duration

- **Type**: duration
- **Default**: `7d`

## `outputs`

Output sink configurations

- **Type**: object

### `http`

HTTP output sinks

- **Type**: list of object

**Item fields:**

#### `name`

Unique output name referenced by rules

- **Type**: string
- **Required**: yes

#### `url`

Target HTTP URL

- **Type**: string
- **Required**: yes

#### `method`

HTTP method

- **Type**: string
- **Default**: `POST`

#### `headers`

Additional HTTP request headers; values support template variables

- **Type**: any

#### `body`

Request body template (defaults to message data)

- **Type**: string

#### `timeout`

Request timeout

- **Type**: duration
- **Default**: `5s`

#### `retry`

Retry policy on transient failure

- **Type**: object

##### `maxAttempts`

Maximum delivery attempts

- **Type**: int
- **Default**: `1`

##### `interval`

Interval between retry attempts

- **Type**: duration
- **Default**: `1s`

#### `onFailure`

Action after all retries are exhausted

- **Type**: object

##### `action`

Failure action: 'discard' or a queue name

- **Type**: string
- **Default**: `discard`

##### `idType`

Message type tag for fail-queue filtering

- **Type**: string

##### `delay`

Delay before the message is re-injected from the fail queue

- **Type**: duration
- **Default**: `60s`

#### `queue`

Queue reference for async delivery

- **Type**: object

##### `name`

Queue name as defined in queues section

- **Type**: string
- **Required**: yes

##### `delay`

Enqueue delay in milliseconds

- **Type**: long
- **Default**: `0`

#### `format`

Output format: string template or list of format steps

- **Type**: any

### `link`

Link output sinks (MQTT publish, WebSocket send, TCP send)

- **Type**: list of object

**Item fields:**

#### `name`

Unique output name referenced by rules

- **Type**: string
- **Required**: yes

#### `linkId`

Target link ID

- **Type**: string
- **Required**: yes

#### `role`

Link role

- **Type**: enum
- **Default**: `producer`
- **Values**: `consumer`, `producer`

#### `topic`

Target topic (MQTT)

- **Type**: string

#### `qos`

MQTT QoS level

- **Type**: int
- **Range**: 0 – 2

#### `retain`

MQTT retain flag

- **Type**: boolean
- **Default**: `false`

#### `retry`

- **Type**: object

##### `maxAttempts`

- **Type**: int
- **Default**: `1`

##### `interval`

- **Type**: duration
- **Default**: `1s`

#### `onFailure`

Action after all retries are exhausted

- **Type**: object

##### `action`

Failure action: 'discard' or a queue name

- **Type**: string
- **Default**: `discard`

##### `idType`

Message type tag for fail-queue filtering

- **Type**: string

##### `delay`

Delay before the message is re-injected from the fail queue

- **Type**: duration
- **Default**: `60s`

#### `queue`

- **Type**: object

##### `name`

- **Type**: string
- **Required**: yes

##### `delay`

- **Type**: long
- **Default**: `0`

#### `when`

Enable condition

- **Type**: string

#### `deny`

Disable condition

- **Type**: string

#### `format`

Output format: string template or list of format steps

- **Type**: any

### `internal`

Internal output sinks (clipboard, file, broadcast, notify)

- **Type**: list of object

**Item fields:**

#### `name`

Unique output name referenced by rules

- **Type**: string
- **Required**: yes

#### `type`

Internal output type

- **Type**: enum
- **Required**: yes
- **Values**: `clipboard`, `file`, `broadcast`, `notify`, `clipboardHistory`

#### `basePath`

Base path for file output

- **Type**: string

#### `fileName`

File name template for file output

- **Type**: string

#### `channel`

Notification channel ID

- **Type**: string

#### `queue`

- **Type**: object

##### `name`

- **Type**: string
- **Required**: yes

##### `delay`

- **Type**: long
- **Default**: `0`

#### `format`

Output format: string template or list of format steps

- **Type**: any

## `rules`

Message forwarding rules

- **Type**: list of object

**Item fields:**

### `name`

Unique rule name

- **Type**: string
- **Required**: yes

### `from`

Source input name (or use froms for multiple)

- **Type**: string
- **Required**: yes

### `froms`

Multiple source input names

- **Type**: list of string

### `pipeline`

Processing pipeline steps

- **Type**: list of object

**Item fields:**

#### `transform`

Data transformation to apply

- **Type**: object

##### `decode`

Decode pipeline, e.g. 'base64Decode', 'jsonDecode', 'gzDecode'

- **Type**: string

##### `detect`

Type detection: 'image', 'json', 'text'

- **Type**: string

##### `enrich`

Enrichment spec, e.g. 'gotifyIcon:linkId'

- **Type**: string

##### `filter`

Filter expression, e.g. 'data.type == "alert"'

- **Type**: string

##### `extract`

GJSON path or '$raw', e.g. 'data.temperature'

- **Type**: string

##### `format`

Template string, e.g. '{data.title}: {data.message}'

- **Type**: string

##### `formatSteps`

Structured format steps (mutually exclusive with format)

- **Type**: list of string

##### `breakOnReject`

Abort pipeline when filter rejects

- **Type**: boolean
- **Default**: `false`

#### `to`

Output names to forward to after this step

- **Type**: list of string

### `onError`

Pipeline executed on error

- **Type**: list of object

**Item fields:**

#### `transform`

- **Type**: object

#### `to`

- **Type**: list of string

### `when`

Enable condition

- **Type**: string

### `deny`

Disable condition

- **Type**: string

## `deadLetter`

Dead-letter handling for messages that exhausted all retries

- **Type**: object

### `enabled`

- **Type**: boolean
- **Default**: `false`

### `maxRetry`

Maximum retry attempts in dead-letter processing

- **Type**: int
- **Default**: `10`

### `action`

Dead-letter processing pipeline

- **Type**: list of object

**Item fields:**

#### `transform`

- **Type**: object

#### `to`

- **Type**: list of string

## `quickSettings`

Quick-settings tile configuration

- **Type**: object

### `inputMethodSwitcher`

Show input method switcher tile

- **Type**: boolean
- **Default**: `true`

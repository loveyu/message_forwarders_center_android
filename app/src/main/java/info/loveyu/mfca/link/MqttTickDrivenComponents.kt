package info.loveyu.mfca.link

import org.eclipse.paho.client.mqttv3.internal.ClientComms
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttPingSender
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/** Tick-driven MQTT async client that delegates keep-alive to a tick-driven ping sender */
internal class TickDrivenMqttAsyncClient(
    serverUri: String,
    clientId: String,
    persistence: MemoryPersistence,
    private val tickPingSender: TickDrivenMqttPingSender,
) : MqttAsyncClient(serverUri, clientId, persistence, tickPingSender) {
    fun onTick(now: Long) {
        tickPingSender.onTick(now)
    }

    fun getDelayUntilNextCheck(now: Long): Long? = tickPingSender.getDelayUntilNextCheck(now)

    fun applyKeepAliveSeconds(seconds: Int) {
        comms.getClientState().setKeepAliveInterval(seconds * 1000L)
        tickPingSender.forceCheckSoon()
    }

    fun triggerImmediateProbe() {
        tickPingSender.forceCheckSoon()
        onTick(System.currentTimeMillis())
    }
}

/** Tick-driven MQTT ping sender that replaces the default scheduler thread */
internal class TickDrivenMqttPingSender : MqttPingSender {
    private var comms: ClientComms? = null

    @Volatile
    private var started = false

    @Volatile
    private var nextCheckAtMs: Long? = null

    override fun init(comms: ClientComms) {
        this.comms = comms
    }

    override fun start() {
        started = true
        schedule(comms?.keepAlive ?: 0L)
    }

    override fun stop() {
        started = false
        nextCheckAtMs = null
    }

    override fun schedule(delayInMilliseconds: Long) {
        if (!started) return
        nextCheckAtMs = System.currentTimeMillis() + delayInMilliseconds.coerceAtLeast(1L)
    }

    fun forceCheckSoon() {
        if (!started) return
        nextCheckAtMs = System.currentTimeMillis()
    }

    fun onTick(now: Long) {
        val dueAt = nextCheckAtMs ?: return
        if (!started || now < dueAt) return
        comms?.checkForActivity()
    }

    fun getDelayUntilNextCheck(now: Long): Long? {
        val dueAt = nextCheckAtMs ?: return null
        return (dueAt - now).coerceAtLeast(0L)
    }
}

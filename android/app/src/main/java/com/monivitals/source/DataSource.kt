//
//  DataSource.kt
//  MoniVitals (Android)
//
//  The single interface the app talks to for live measurement. The camera source implements
//  it; every screen works through it. It publishes camera pulse samples, phone motion, and
//  connection lifecycle (so the UI can show a warm "getting ready" / "needs camera" state).
//
//  Event delivery uses SharedFlow; handlers push high-rate samples into non-reactive ring
//  buffers rather than reactive state.
//

package com.monivitals.source

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Connection / capture lifecycle. */
enum class ConnectionState {
    DISCONNECTED,
    UNSUPPORTED, // no usable camera on the hardware (e.g. an emulator)
    SCANNING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

/** Optional detail on a connection-state change (a friendly reason line). */
data class ConnectionDetail(val error: String? = null)

private fun <T> streamFlow(): MutableSharedFlow<T> =
    MutableSharedFlow(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)

/**
 * The event hub a [DataSource] publishes on. The ViewModel collects these; emission uses
 * `tryEmit` from any thread (camera analysis thread, sensor thread), matching the buffered,
 * non-suspending semantics.
 */
class DataSourceEvents {
    val ppg = streamFlow<PpgPacket>()
    val motion = streamFlow<MotionSample>()
    /** 0..100 fingertip signal quality, published from the capture layer. */
    val contact = streamFlow<Int>()
    val connection = streamFlow<Pair<ConnectionState, ConnectionDetail?>>()
}

/** A live measurement source. */
interface DataSource {
    val state: ConnectionState
    val events: DataSourceEvents

    fun connect()
    fun disconnect()
    fun start()
    fun stop()
}

/** Shared base providing the event hub + connection-state plumbing. */
abstract class BaseDataSource : DataSource {
    override val events = DataSourceEvents()

    private var stateValue: ConnectionState = ConnectionState.DISCONNECTED

    override val state: ConnectionState get() = stateValue

    protected fun setState(state: ConnectionState, detail: ConnectionDetail? = null) {
        stateValue = state
        events.connection.tryEmit(state to detail)
    }

    override fun connect() {}
    override fun disconnect() {}
    override fun start() {}
    override fun stop() {}
}

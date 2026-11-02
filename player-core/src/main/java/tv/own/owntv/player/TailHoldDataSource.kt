package tv.own.owntv.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * One live HTTP read that can be told to stop: from the moment [cut] is set its next [read] closes the
 * connection and reports end-of-input. ExoPlayer treats that as the end of the item, so everything already
 * buffered keeps playing while the connection (and the provider's stream slot) is gone.
 *
 * Used for the "buffered tail" channel change (see `LivePreviewEngine.zapTailHold`): the old channel plays out
 * its buffer while the next one connects, with only ever one connection open at a time.
 */
internal class TailCutDataSource(private val inner: DataSource, val token: Int) : DataSource {
    @Volatile var cut = false

    override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (cut) throw IOException("stream was cut for a channel change")
        return inner.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (cut) {
            runCatching { inner.close() }
            return C.RESULT_END_OF_INPUT
        }
        return inner.read(buffer, offset, length)
    }

    override fun getUri() = inner.uri

    override fun getResponseHeaders() = inner.responseHeaders

    override fun close() = inner.close()
}

/** Hands out [TailCutDataSource]s stamped with the tune's token, and cuts every older one on request. */
internal class TailCutDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val currentToken: () -> Int,
) : DataSource.Factory {
    private val sources = ArrayList<WeakReference<TailCutDataSource>>()

    override fun createDataSource(): DataSource =
        TailCutDataSource(upstream.createDataSource(), currentToken()).also { ds ->
            synchronized(sources) {
                sources.removeAll { it.get() == null }
                sources.add(WeakReference(ds))
            }
        }

    /** Every read opened before [token] ends at its next read. */
    fun cutOlderThan(token: Int) {
        synchronized(sources) { sources.forEach { ref -> ref.get()?.takeIf { it.token < token }?.cut = true } }
    }
}

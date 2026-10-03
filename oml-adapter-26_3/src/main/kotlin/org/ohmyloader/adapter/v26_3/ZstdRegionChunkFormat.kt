package org.ohmyloader.adapter.v26_3

import org.ohmyloader.api.OmlLog
import net.minecraft.world.level.chunk.storage.RegionFileVersion
import org.ohmyloader.core.compression.OmlNativeZstd
import org.ohmyloader.core.compression.OmlZstdInputStream
import org.ohmyloader.core.compression.OmlZstdOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Proxy

/**
 * Zstd (ID **127**) region-file chunk compression for Minecraft 26.3, built on our own **oml-native** C library (see [OmlNativeZstd]). In 26.3, ID 127 is `VERSION_CUSTOM`, a *sentinel* whose wrappers throw — not a working codec. We construct a fresh `RegionFileVersion(127, "zstd", …)` via reflection and push it through vanilla's own private `register`, replacing that entry: vanilla ids 1/2/3/4 and the sentinel field stay untouched, so old worlds keep reading through the original wrappers unchanged.
 * Write path: every new `RegionFile` picks its codec via `getSelected()` and stamps the chunk header's 5th byte with the same version's id, so rewriting `getSelected()` switches format and on-disk algorithm byte together, atomically. Read path: `createChunkInputStream` dispatches on the on-disk byte via `fromId` and never consults `getSelected()`, so a chunk written by our instance reads back plain (no name prefix), exactly like a vanilla chunk.
 * Minting a version needs the private constructor, private `register` and the package-private `StreamWrapper` interface — none reachable from `org.ohmyloader.*` — hence the `java.lang.reflect.Proxy` wrappers (invoked once per chunk, negligible next to compression).
 * Side effect of the "zstd" name: `region-file-compression=zstd` in server.properties becomes a valid spelling of the same codec; the `getSelected` rewrite overrides the *default* regardless — the product decision is "new chunks are Zstd", not "Zstd is opt-in".
 */
object ZstdRegionChunkFormat {

    /** The modding-standard extension algorithm id for Zstd inside the Anvil chunk header. */
    const val VERSION_ID: Int = 127

    /**
     * Compression level 3 — zstd's own default: the balanced point between throughput and ratio.
     * Higher levels buy a few percent of ratio for a multiple of CPU per chunk save, which shows
     * up directly as autosave latency spikes on the server thread. Internal so the codec
     * round-trip test exercises the exact level the registered version uses.
     */
    internal const val LEVEL: Int = 3

    /** The internal name of `RegionFileVersion$StreamWrapper` — package-private, resolved reflectively. */
    private const val STREAM_WRAPPER_CLASS =
        $$"net.minecraft.world.level.chunk.storage.RegionFileVersion$StreamWrapper"

    /** The registered Zstd version; null until registration succeeded (or forever on failure — the game then keeps vanilla defaults). */
    @Volatile
    private var registered: RegionFileVersion? = null

    /**
     * Called at the tail of `RegionFileVersion.<clinit>` (injected by both the client and the
     * server transformer — region storage is shared bootstrap code). Every vanilla instance is
     * registered and `selected`/`DEFAULT` already point at `VERSION_DEFLATE` here, so this runs
     * after all state it must not disturb exists.
     *
     * Never throws: a failure inside a `<clinit>` would kill the class (and with it the game's
     * whole storage layer) with `ExceptionInInitializerError`. Registration failing degrades to
     * vanilla deflate and the error is logged loudly instead.
     */
    @JvmStatic
    fun onRegionFileVersionInitialized() {
        if (registered != null) return
        try {
            val wrapperInterface = Class.forName(STREAM_WRAPPER_CLASS)
            val compressing: Any = Proxy.newProxyInstance(
                RegionFileVersion::class.java.classLoader,
                arrayOf(wrapperInterface),
            ) { _, method, args ->
                when (method.name) {
                    "wrap" -> OmlZstdOutputStream(args[0] as OutputStream, LEVEL)
                    "toString" -> "ZstdRegionChunkFormat@output(level=$LEVEL)"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> args[0] === this
                    else -> throw IllegalStateException("unexpected StreamWrapper method: $method")
                }
            }
            val decompressing: Any = Proxy.newProxyInstance(
                RegionFileVersion::class.java.classLoader,
                arrayOf(wrapperInterface),
            ) { _, method, args ->
                when (method.name) {
                    "wrap" -> OmlZstdInputStream(args[0] as InputStream)
                    "toString" -> "ZstdRegionChunkFormat@input"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> args[0] === this
                    else -> throw IllegalStateException("unexpected StreamWrapper method: $method")
                }
            }

            val constructor = RegionFileVersion::class.java.getDeclaredConstructor(
                Integer.TYPE,
                String::class.java,
                wrapperInterface,
                wrapperInterface,
            )
            constructor.isAccessible = true
            // Parameter order matters and is invisible after erasure — both parameters are the same
            // raw `StreamWrapper` type: (decompressing<InputStream>, compressing<OutputStream>).
            // Swapped, the first chunk save dies with `ClassCastException: ChunkBuffer cannot be
            // cast to InputStream` (the write path would find the decompressing wrapper). Verified
            // against the class's generic signature in the 26.3 jar.
            val version = constructor.newInstance(VERSION_ID, "zstd", decompressing, compressing)

            val register = RegionFileVersion::class.java.getDeclaredMethod(
                "register",
                RegionFileVersion::class.java,
            )
            register.isAccessible = true
            registered = register.invoke(null, version) as RegionFileVersion
            OmlLog.info(
                "OMLZstd",
                "RegionFileVersion: registered Zstd (id $VERSION_ID, level $LEVEL, " +
                    "oml-native) — new region-file chunks will be written as Zstd; " +
                    "vanilla ids 1/2/3/4 keep reading unchanged"
            )
        } catch (t: Throwable) {
            registered = null
            OmlLog.error(
                "OMLZstd",
                "Zstd region-file registration failed — falling back to vanilla " +
                    "deflate (old worlds are unaffected, new chunks will not be Zstd)",
                t
            )
        }
    }

    /**
     * `getSelected()` return-value rewrite: the version every *new* `RegionFile` writes with.
     * Falls back to the vanilla value when Zstd registration failed, so a broken zstd native
     * library costs compression, not the game.
     */
    @JvmStatic
    fun onSelectedVersion(vanilla: RegionFileVersion): RegionFileVersion =
        registered ?: vanilla
}

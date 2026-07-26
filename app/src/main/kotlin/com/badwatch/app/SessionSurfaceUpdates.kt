package com.badwatch.app

/**
 * Notifies the two system-owned session summaries independently.
 *
 * A watch face or Tile host can be absent, restarting, or temporarily reject an update. These
 * are cache-invalidation hints, never part of the durable session transaction, so neither
 * requester may prevent the other from running or make a successful store mutation fail.
 */
internal fun requestSessionSurfaceUpdates(
    complication: () -> Unit,
    tile: () -> Unit
) {
    runCatching(complication)
    runCatching(tile)
}

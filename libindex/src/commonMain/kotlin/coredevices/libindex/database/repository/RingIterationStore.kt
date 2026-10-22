package coredevices.libindex.database.repository

/** The only transfer-table call libindex makes while pairing or adopting a ring. */
interface RingIterationStore {
    suspend fun markTransfersAsPreviousIndexIteration()
}

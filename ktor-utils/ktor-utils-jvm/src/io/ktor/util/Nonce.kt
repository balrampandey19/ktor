package io.ktor.util

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import java.security.*

private const val SECURE_RESEED_PERIOD = 30_000

private const val NONCE_SIZE_IN_BYTES = 8

private const val SECURE_NONCE_COUNT = 8

private const val INSECURE_NONCE_COUNT_FACTOR = 4

internal val seedChannel: Channel<String> = Channel(1024)

private val nonceGeneratorJob =
    GlobalScope.launch(context = Dispatchers.IO + NonCancellable, start = CoroutineStart.LAZY) {
        val seedChannel = seedChannel
        var lastReseed = 0L
        val previousRoundNonceList = ArrayList<String>()
        val secureInstance = SecureRandom.getInstanceStrong()
        val weakRandom = SecureRandom.getInstance("SHA1PRNG")

        val secureBytes = ByteArray(SECURE_NONCE_COUNT * NONCE_SIZE_IN_BYTES)
        val weakBytes = ByteArray(secureBytes.size * INSECURE_NONCE_COUNT_FACTOR)

        weakRandom.setSeed(secureInstance.generateSeed(secureBytes.size))

        try {
            while (true) {
                // fill both
                secureInstance.nextBytes(secureBytes)
                weakRandom.nextBytes(weakBytes)

                // mix secure and weak
                for (i in 0 until secureBytes.size) {
                    weakBytes[i * INSECURE_NONCE_COUNT_FACTOR] = secureBytes[i]
                }

                // reseed weak bytes
                // if too much time then reseed completely
                // otherwise simply reseed with mixed
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastReseed > SECURE_RESEED_PERIOD) {
                    secureInstance.setSeed(lastReseed - currentTime)
                    weakRandom.setSeed(secureInstance.generateSeed(secureBytes.size))
                    lastReseed = currentTime
                } else {
                    weakRandom.setSeed(secureBytes)
                }

                // concat entries with entries from the previous round
                // and shuffle with weak random (reseeded)
                val randomNonceList = (hex(weakBytes).chunked(16) + previousRoundNonceList).shuffled(weakRandom)

                // send first part to the channel
                for (index in 0 until randomNonceList.size / 2) {
                    seedChannel.send(randomNonceList[index])
                }

                // stash the second part for the next round
                previousRoundNonceList.clear()
                for (index in randomNonceList.size / 2 until randomNonceList.size) {
                    previousRoundNonceList.add(randomNonceList[index])
                }
            }
        } catch (t: Throwable) {
            seedChannel.close(t)
        } finally {
            seedChannel.close()
        }
    }

internal fun ensureNonceGeneratorRunning() {
    nonceGeneratorJob.start()
}

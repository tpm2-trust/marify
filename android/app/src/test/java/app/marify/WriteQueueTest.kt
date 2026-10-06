@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.marify

import app.marify.ble.LinkException
import app.marify.ble.WriteQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WriteQueueTest {

    @Test fun oneWriteAtATimeInOrder() = runTest {
        val submitted = mutableListOf<Int>()
        lateinit var q: WriteQueue
        q = WriteQueue({ submitted.add(it[0].toInt()); WriteQueue.SUCCESS })
        val jobs = (0 until 5).map { i -> launch { q.write(byteArrayOf(i.toByte())) } }
        runCurrent()
        // Only the first write is in flight until the stack acknowledges it.
        assertEquals(listOf(0), submitted)
        for (n in 1..5) {
            q.onWriteComplete(WriteQueue.GATT_SUCCESS)
            runCurrent()
            assertEquals((0 until minOf(n + 1, 5)).toList(), submitted)
        }
        jobs.forEach { assertTrue(it.isCompleted) }
    }

    @Test fun busyIsRetriedWithBackoff() = runTest {
        var calls = 0
        val q = WriteQueue({ if (++calls < 4) WriteQueue.BUSY else WriteQueue.SUCCESS })
        val w = async { q.write(byteArrayOf(1)) }
        advanceTimeBy(1_000)  // well below the acknowledgement timeout
        runCurrent()
        assertEquals(4, calls)
        q.onWriteComplete(WriteQueue.GATT_SUCCESS)
        w.await()
    }

    @Test fun persistentBusyFails() = runTest {
        val q = WriteQueue({ WriteQueue.BUSY }, maxAttempts = 3)
        try {
            q.write(byteArrayOf(1))
            fail("expected LinkException")
        } catch (_: LinkException) {
        }
    }

    @Test fun otherErrorFailsImmediately() = runTest {
        var calls = 0
        val q = WriteQueue({ calls++; 3 })
        try {
            q.write(byteArrayOf(1)); fail()
        } catch (_: LinkException) {
        }
        assertEquals(1, calls)
    }

    @Test fun gattFailureStatusFails() = runTest {
        val q = WriteQueue({ WriteQueue.SUCCESS })
        val w = async { runCatching { q.write(byteArrayOf(1)) } }
        advanceUntilIdle()
        q.onWriteComplete(133)
        assertTrue(w.await().exceptionOrNull() is LinkException)
    }

    @Test fun missingAcknowledgementTimesOut() = runTest {
        val q = WriteQueue({ WriteQueue.SUCCESS })
        val w = async { runCatching { q.write(byteArrayOf(1)) } }
        advanceUntilIdle()
        assertTrue(w.await().exceptionOrNull() is LinkException)
    }

    @Test fun cancelFailsWriteInFlight() = runTest {
        val q = WriteQueue({ WriteQueue.SUCCESS })
        val w = async { runCatching { q.write(byteArrayOf(1)) } }
        advanceUntilIdle()
        q.cancel()
        assertTrue(w.await().exceptionOrNull() is LinkException)
    }
}

package com.zq.commprep

import com.zq.commprep.sensors.ProcessSingleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SensorSingletonTest {
    @Test fun factory_runs_once_however_often_asked() {
        val holder = ProcessSingleton { Any() }
        val first = holder.get()
        repeat(5) { assertSame(first, holder.get()) }   // e.g. activity re-created five times
        assertEquals(1, holder.created)
    }

    @Test fun concurrent_callers_get_one_instance() {
        val holder = ProcessSingleton { Thread.sleep(20); Any() }
        val results = java.util.Collections.synchronizedList(mutableListOf<Any>())
        val threads = (1..8).map { Thread { results += holder.get() } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(1, results.toSet().size)
        assertEquals(1, holder.created)
    }
}

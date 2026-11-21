package org.jarvis.ans100.alpha.data

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100BenchmarkTraceStoreTest {
    @Test fun storesOnlyHashedAdapterTraceAndBoundsHistory() {
        val root = createTempDirectory(prefix = "ans100-trace-test").toFile()
        try {
            val store = ANS100BenchmarkTraceStore(root)
            val trace = """{"schema":"ANS100_BENCHMARK_TRACE_V1","input_sha256":"${"a".repeat(64)}","input_char_count":12}"""
            repeat(ANS100BenchmarkTraceStore.MAX_RECORDS + 3) { assertTrue(store.append(trace)) }
            assertEquals(ANS100BenchmarkTraceStore.MAX_RECORDS, store.recordCount())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsRawMessageAndUnknownSchema() {
        val root = createTempDirectory(prefix = "ans100-trace-test").toFile()
        try {
            val store = ANS100BenchmarkTraceStore(root)
            val hash = "a".repeat(64)
            assertFalse(store.append("""{"schema":"ANS100_BENCHMARK_TRACE_V1","input_sha256":"$hash","input_text":"private"}"""))
            assertFalse(store.append("""{"schema":"OTHER","input_sha256":"$hash"}"""))
            assertEquals(0, store.recordCount())
        } finally {
            root.deleteRecursively()
        }
    }
}

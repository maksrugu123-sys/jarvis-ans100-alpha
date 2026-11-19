package org.jarvis.ans100.alpha.data

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES31
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental OpenGL ES 3.1 compute backend for the floating-point LIF-DT
 * candidate calculation. CPU continues to own timestamps, integer metadata,
 * event scheduling and SQLite transactions.
 *
 * The backend is deliberately fail-closed: unsupported devices, shader errors,
 * GL errors, non-finite output and numerically ambiguous threshold decisions
 * raise AndroidNeuronNetworkException. It never silently substitutes CPU work.
 *
 * A backend instance is confined to the thread that first calls processBatch().
 */
class Gles31AndroidNeuronComputeBackend(context: Context) :
    AndroidNeuronComputeBackend, Closeable {

    override val backendName: String = "gles31-compute-fp32"

    private val appContext = context.applicationContext
    private var ownerThreadId: Long? = null
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var inputBuffer = 0
    private var outputBuffer = 0
    private val closed = AtomicBoolean(false)

    @Synchronized
    override fun processBatch(
        inputs: List<AndroidNeuronKernel.Input>,
    ): List<AndroidNeuronKernel.Result> {
        check(!closed.get()) { "GPU compute backend is closed" }
        if (inputs.isEmpty()) return emptyList()
        bindToCallingThread()
        ensureInitialized()
        return dispatch(inputs)
    }

    private fun bindToCallingThread() {
        val current = Thread.currentThread().id
        val previous = ownerThreadId
        if (previous == null) ownerThreadId = current
        else if (previous != current) {
            throw AndroidNeuronNetworkException("GLES compute backend must be used from its initializing thread")
        }
    }

    private fun ensureInitialized() {
        if (program != 0) return
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) fail("EGL display is unavailable")
            val versions = IntArray(2)
            if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) {
                fail("EGL initialization failed")
            }
            val configAttrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, count, 0) ||
                count[0] == 0 || configs[0] == null
            ) fail("OpenGL ES 3.x EGL config is unavailable")
            val contextAttrs = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttrs, 0,
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) fail("OpenGL ES 3 context creation failed")
            val surfaceAttrs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttrs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) fail("EGL pbuffer creation failed")
            if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
                fail("EGL context activation failed")
            }
            val version = GLES31.glGetString(GLES31.GL_VERSION).orEmpty()
            if (!version.contains("OpenGL ES 3.1") && !version.contains("OpenGL ES 3.2")) {
                fail("GPU does not expose OpenGL ES 3.1 compute: $version")
            }
            program = buildProgram()
            val ids = IntArray(2)
            GLES31.glGenBuffers(2, ids, 0)
            inputBuffer = ids[0]
            outputBuffer = ids[1]
            checkGl("GPU buffer creation")
            qualifyBackend()
        } catch (error: AndroidNeuronNetworkException) {
            releaseGl()
            throw error
        } catch (error: Throwable) {
            releaseGl()
            throw AndroidNeuronNetworkException(
                "GLES 3.1 compute initialization failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    /**
     * A short CPU-reference qualification runs before the adapter accepts real
     * work. Spike decisions and integer metadata must match; FP32 state values
     * must remain within the documented tolerance.
     */
    private fun qualifyBackend() {
        val fixtures = listOf(
            AndroidNeuronKernel.Input(
                AndroidNeuronState(neuronId = 101, potential = 0.25, adaptation = 0.2),
                10_000_000L, 0.2,
            ),
            AndroidNeuronKernel.Input(
                AndroidNeuronState(neuronId = 102, potential = 0.2, adaptation = 0.1),
                5_000_000L, 1.2,
            ),
            AndroidNeuronKernel.Input(
                AndroidNeuronState(
                    neuronId = 103, potential = 0.4, adaptation = 0.3,
                    refractoryUntilNs = 10_000_000L,
                ),
                9_000_000L, 1.4,
            ),
            AndroidNeuronKernel.Input(
                AndroidNeuronState(neuronId = 104, potential = -0.5, adaptation = 0.8),
                100_000_000L, 0.25,
            ),
            // Represents the CPU-aggregated result of simultaneous same-time inputs.
            AndroidNeuronKernel.Input(
                AndroidNeuronState(neuronId = 105, potential = 0.1, adaptation = 0.05),
                20_000_000L, 0.72,
            ),
        )
        val gpuResults = dispatch(fixtures)
        val cpuResults = CpuAndroidNeuronComputeBackend.processBatch(fixtures)
        for (index in fixtures.indices) {
            val actual = gpuResults[index]
            val expected = cpuResults[index]
            if (actual.spiked != expected.spiked ||
                actual.state.neuronId != expected.state.neuronId ||
                actual.state.lastUpdateNs != expected.state.lastUpdateNs ||
                actual.state.refractoryUntilNs != expected.state.refractoryUntilNs ||
                actual.state.spikeSequence != expected.state.spikeSequence ||
                kotlin.math.abs(actual.state.potential - expected.state.potential) > STATE_TOLERANCE ||
                kotlin.math.abs(actual.state.adaptation - expected.state.adaptation) > STATE_TOLERANCE
            ) {
                fail("GPU compute failed CPU-reference qualification fixture ${index + 1}")
            }
        }
    }

    private fun dispatch(inputs: List<AndroidNeuronKernel.Input>): List<AndroidNeuronKernel.Result> {
        val inputBytes = ByteBuffer.allocateDirect(inputs.size * INPUT_FLOATS * 4)
            .order(ByteOrder.nativeOrder())
        val inputFloats = inputBytes.asFloatBuffer()
        for (item in inputs) {
            val state = item.state
            if (item.timestampNs < state.lastUpdateNs) {
                throw AndroidNeuronNetworkException("neuron event time moved backwards")
            }
            if (!item.input.isFinite()) {
                throw AndroidNeuronNetworkException("neuron input must be finite")
            }
            val elapsed = item.timestampNs - state.lastUpdateNs
            val values = floatArrayOf(
                state.potential.toFloat(),
                state.adaptation.toFloat(),
                item.input.toFloat(),
                elapsed.toFloat(),
                if (item.timestampNs >= state.refractoryUntilNs) 1f else 0f,
                0f, 0f, 0f,
            )
            if (values.any { !it.isFinite() }) {
                throw AndroidNeuronNetworkException("GPU input exceeds FP32 range")
            }
            inputFloats.put(values)
        }
        inputBytes.position(0)

        val outputBytes = inputs.size * OUTPUT_FLOATS * 4
        val output = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
        GLES31.glUseProgram(program)
        val countLocation = GLES31.glGetUniformLocation(program, "uCount")
        if (countLocation < 0) fail("GPU batch-size uniform is missing")
        GLES31.glUniform1i(countLocation, inputs.size)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, inputBuffer)
        GLES31.glBufferData(
            GLES31.GL_SHADER_STORAGE_BUFFER, inputBytes.capacity(), inputBytes, GLES31.GL_DYNAMIC_DRAW,
        )
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, inputBuffer)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, outputBuffer)
        GLES31.glBufferData(
            GLES31.GL_SHADER_STORAGE_BUFFER, outputBytes, output, GLES31.GL_DYNAMIC_READ,
        )
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, outputBuffer)
        GLES31.glDispatchCompute((inputs.size + LOCAL_SIZE - 1) / LOCAL_SIZE, 1, 1)
        GLES31.glMemoryBarrier(
            GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_BUFFER_UPDATE_BARRIER_BIT,
        )
        checkGl("GPU compute dispatch")

        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, outputBuffer)
        val mapped = GLES31.glMapBufferRange(
            GLES31.GL_SHADER_STORAGE_BUFFER, 0, outputBytes,
            GLES31.GL_MAP_READ_BIT,
        ) as? ByteBuffer ?: fail("GPU result buffer could not be mapped")
        val resultFloats = mapped.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val results = ArrayList<AndroidNeuronKernel.Result>(inputs.size)
        for (index in inputs.indices) {
            val potential = resultFloats.get(index * OUTPUT_FLOATS)
            val adaptation = resultFloats.get(index * OUTPUT_FLOATS + 1)
            val spiked = resultFloats.get(index * OUTPUT_FLOATS + 2) > 0.5f
            val thresholdMargin = resultFloats.get(index * OUTPUT_FLOATS + 3)
            val item = inputs[index]
            if (!potential.isFinite() || !adaptation.isFinite() ||
                potential !in -2f..2f || adaptation !in 0f..1f
            ) {
                GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
                fail("GPU returned an invalid neuron state")
            }
            if (!thresholdMargin.isFinite() ||
                kotlin.math.abs(thresholdMargin.toDouble()) <= THRESHOLD_GUARD
            ) {
                GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
                fail("GPU threshold decision is numerically ambiguous; refusing FP32 result")
            }
            val refractoryOpen = item.timestampNs >= item.state.refractoryUntilNs
            if (spiked != (refractoryOpen && thresholdMargin > 0f)) {
                GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
                fail("GPU spike decision is inconsistent with its threshold margin")
            }
            val old = item.state
            val next: AndroidNeuronState
            if (spiked) {
                if (old.spikeSequence == Long.MAX_VALUE ||
                    item.timestampNs > Long.MAX_VALUE - REFRACTORY_NS
                ) {
                    GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
                    fail("neuron spike counter or refractory clock would overflow")
                }
                next = old.copy(
                    potential = 0.0,
                    adaptation = (adaptation.toDouble() + ADAPTATION_PER_SPIKE).coerceIn(0.0, 1.0),
                    lastUpdateNs = item.timestampNs,
                    refractoryUntilNs = item.timestampNs + REFRACTORY_NS,
                    spikeSequence = old.spikeSequence + 1L,
                )
            } else {
                next = old.copy(
                    potential = potential.toDouble(),
                    adaptation = adaptation.toDouble(),
                    lastUpdateNs = item.timestampNs,
                )
            }
            val result = AndroidNeuronKernel.Result(next, spiked)
            AndroidNeuronKernel.validateCandidate(item, result)
            results += result
        }
        if (!GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)) {
            fail("GPU result buffer became invalid after mapping")
        }
        checkGl("GPU result readback")
        return results
    }

    private fun buildProgram(): Int {
        val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        GLES31.glShaderSource(shader, COMPUTE_SHADER)
        GLES31.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES31.glGetShaderInfoLog(shader)
            GLES31.glDeleteShader(shader)
            fail("GPU compute shader compilation failed: $log")
        }
        val linkedProgram = GLES31.glCreateProgram()
        GLES31.glAttachShader(linkedProgram, shader)
        GLES31.glLinkProgram(linkedProgram)
        GLES31.glDeleteShader(shader)
        val linked = IntArray(1)
        GLES31.glGetProgramiv(linkedProgram, GLES31.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES31.glGetProgramInfoLog(linkedProgram)
            GLES31.glDeleteProgram(linkedProgram)
            fail("GPU compute program link failed: $log")
        }
        return linkedProgram
    }

    private fun checkGl(stage: String) {
        val error = GLES31.glGetError()
        if (error != GLES31.GL_NO_ERROR) {
            fail("$stage failed with GL error 0x${error.toString(16)}")
        }
    }

    private fun fail(message: String): Nothing = throw AndroidNeuronNetworkException(message)

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) releaseGl()
    }

    private fun releaseGl() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (eglContext != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
            }
            if (program != 0) GLES31.glDeleteProgram(program)
            val ids = intArrayOf(inputBuffer, outputBuffer)
            if (inputBuffer != 0 || outputBuffer != 0) GLES31.glDeleteBuffers(2, ids, 0)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
        }
        program = 0
        inputBuffer = 0
        outputBuffer = 0
        surface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    companion object {
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        private const val LOCAL_SIZE = 64
        private const val INPUT_FLOATS = 8
        private const val OUTPUT_FLOATS = 4
        private const val REFRACTORY_NS = 2_000_000L
        private const val ADAPTATION_PER_SPIKE = 0.15
        // Conservative fail-closed zone around the FP32 threshold.
        private const val THRESHOLD_GUARD = 0.0002
        private const val STATE_TOLERANCE = 0.00005

        private const val COMPUTE_SHADER = """
            #version 310 es
            layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;
            layout(std430, binding = 0) readonly buffer InputBuffer { vec4 inputData[]; };
            layout(std430, binding = 1) writeonly buffer OutputBuffer { vec4 outputData[]; };
            uniform int uCount;
            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= uint(uCount)) return;
                vec4 stateInput = inputData[i * 2u];
                vec4 flags = inputData[i * 2u + 1u];
                float p = stateInput.x;
                float a = stateInput.y;
                float x = stateInput.z;
                float dt = stateInput.w;
                float decayedP = p * exp(-dt / 20000000.0);
                float decayedA = a * exp(-dt / 100000000.0);
                float nextP = clamp(decayedP + x, -2.0, 2.0);
                float threshold = clamp(1.0 + 0.5 * decayedA, 1.0, 2.0);
                bool spike = flags.x > 0.5 && nextP >= threshold;
                if (spike) {
                    outputData[i] = vec4(0.0, clamp(decayedA + 0.15, 0.0, 1.0), 1.0, nextP - threshold);
                } else {
                    outputData[i] = vec4(nextP, clamp(decayedA, 0.0, 1.0), 0.0, nextP - threshold);
                }
            }
        """
    }
}

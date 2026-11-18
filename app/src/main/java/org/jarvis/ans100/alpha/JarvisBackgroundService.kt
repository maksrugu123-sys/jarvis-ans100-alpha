package org.jarvis.ans100.alpha

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Debug
import android.os.Process
import org.jarvis.ans100.alpha.data.ANS100StandaloneBenchmarkRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground process owner for user-requested local work.
 *
 * Structured ANS-100 training runs in this service-owned executor and runtime,
 * not in the Activity or ViewModel. A durable in-flight marker prevents an
 * uncertain task from being replayed after process death.
 */
class JarvisBackgroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val running = AtomicBoolean(false)
    @Volatile private var worker = Executors.newSingleThreadExecutor()
    @Volatile private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, false).apply()
                stopRequested = true
                if (running.get()) {
                    checkpointPreferences().edit()
                        .putString(KEY_STATE, STATE_INTERRUPTED)
                        .putString(KEY_ERROR, "Остановка запрошена пользователем.")
                        .commit()
                }
                worker.shutdownNow()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_STRUCTURED_TRAINING -> {
                stopRequested = false
                executeTraining(resetBeforeRun = true)
            }
            ACTION_RESUME -> recoverTraining()
            null -> recoverTraining()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (running.get()) {
            checkpointPreferences().edit()
                .putString(KEY_STATE, STATE_INTERRUPTED)
                .putString(KEY_ERROR, "Фоновый процесс был остановлен до фиксации текущего задания.")
                .commit()
        }
        worker.shutdownNow()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun recoverTraining() {
        val prefs = checkpointPreferences()
        val state = prefs.getString(KEY_STATE, STATE_IDLE) ?: STATE_IDLE
        if (state != STATE_PENDING && state != STATE_RUNNING) return
        val checkpoint = runCatching {
            BackgroundTrainingCheckpoint(
                state = state,
                totalTasks = prefs.getInt(KEY_TOTAL, 0),
                completedTasks = prefs.getInt(KEY_COMPLETED, 0),
                inFlightIndex = prefs.getInt(KEY_IN_FLIGHT, -1),
            )
        }.getOrElse {
            markFailure("Контрольная точка повреждена: ${it.message}")
            return
        }
        when (checkpoint.recoveryDecision()) {
            BackgroundTrainingCheckpoint.RecoveryDecision.START_FRESH ->
                executeTraining(resetBeforeRun = true)
            BackgroundTrainingCheckpoint.RecoveryDecision.RESUME ->
                executeTraining(resetBeforeRun = false)
            BackgroundTrainingCheckpoint.RecoveryDecision.INTERRUPTED -> {
                prefs.edit()
                    .putString(KEY_STATE, STATE_INTERRUPTED)
                    .putString(KEY_ERROR, "Процесс завершился внутри задания ${checkpoint.inFlightIndex + 1}. Автоповтор запрещён, чтобы не исказить обучение. Начни новый запуск.")
                    .commit()
                updateNotification("Обучение прервано · требуется новый запуск")
                releaseWakeLock()
            }
            BackgroundTrainingCheckpoint.RecoveryDecision.COMPLETE -> {
                prefs.edit().putString(KEY_STATE, STATE_COMPLETED).commit()
                updateNotification("Фоновое обучение завершено")
                releaseWakeLock()
            }
            BackgroundTrainingCheckpoint.RecoveryDecision.NONE -> Unit
        }
    }

    private fun executeTraining(resetBeforeRun: Boolean) {
        if (!running.compareAndSet(false, true)) return
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
        acquireWakeLock()
        worker.execute {
            var runtime: ANS100StandaloneBenchmarkRuntime? = null
            try {
                val rows = loadDevelopmentRows()
                val prefs = checkpointPreferences()
                if (resetBeforeRun) {
                    updateNotification("Подготовка изолированной памяти ANS-100…")
                    val activeRuntime = ANS100StandaloneBenchmarkRuntime(applicationContext)
                    runtime = activeRuntime
                    activeRuntime.resetStructuredLearnedState()
                    check(prefs.edit()
                        .putInt(KEY_TOTAL, rows.size)
                        .putInt(KEY_COMPLETED, 0)
                        .putInt(KEY_IN_FLIGHT, -1)
                        .putString(KEY_STATE, STATE_RUNNING)
                        .putString(KEY_ERROR, "")
                        .commit()) { "Не удалось сохранить начало фонового эксперимента." }
                } else {
                    val activeRuntime = ANS100StandaloneBenchmarkRuntime(applicationContext)
                    runtime = activeRuntime
                    check(prefs.getInt(KEY_TOTAL, -1) == rows.size) {
                        "Размер сохранённого development-набора изменился; автоматическое продолжение запрещено."
                    }
                }

                var completed = prefs.getInt(KEY_COMPLETED, 0)
                for (index in completed until rows.size) {
                    check(!stopRequested && !Thread.currentThread().isInterrupted) {
                        "Фоновое обучение остановлено."
                    }
                    benchmarkSafetyStopReason()?.let { error("BENCHMARK_SAFETY_STOP: $it") }

                    // Commit before entering the non-transactional multi-timestamp
                    // neural pass. If the process dies here, this task is never replayed.
                    check(prefs.edit()
                        .putInt(KEY_IN_FLIGHT, index)
                        .putString(KEY_STATE, STATE_RUNNING)
                        .commit()) { "Не удалось зафиксировать начало задания ${index + 1}." }

                    val row = rows[index]
                    val activeRuntime = runtime ?: error("ANS-100 runtime is unavailable.")
                    val result = activeRuntime.runStructuredTask(
                        taskId = row.taskId,
                        inputText = row.inputText,
                        trainingLabel = row.trainingLabel,
                    )
                    check(result.completed) { "${row.taskId}: нейронная очередь не завершилась." }
                    check(result.trainingApplied) { "${row.taskId}: не применено обновление обучающих синапсов." }
                    check(!stopRequested && !Thread.currentThread().isInterrupted) {
                        "Остановка получена после вычисления задания; его статус требует проверки."
                    }

                    completed = index + 1
                    check(prefs.edit()
                        .putInt(KEY_COMPLETED, completed)
                        .putInt(KEY_IN_FLIGHT, -1)
                        .putString(KEY_STATE, STATE_RUNNING)
                        .putString(KEY_ERROR, "")
                        .commit()) { "Не удалось сохранить результат задания ${index + 1}." }
                    updateNotification("Обучение ANS-100: $completed / ${rows.size}")
                    if (completed < rows.size && completed % TRAINING_BATCH_SIZE == 0) {
                        updateNotification("Пауза для охлаждения · продолжение после 10 секунд")
                        Thread.sleep(TRAINING_COOLDOWN_MS)
                    }
                }

                prefs.edit()
                    .putInt(KEY_COMPLETED, rows.size)
                    .putInt(KEY_IN_FLIGHT, -1)
                    .putString(KEY_STATE, STATE_COMPLETED)
                    .putString(KEY_ERROR, "")
                    .commit()
                updateNotification("Обучение ANS-100 завершено · ${rows.size} заданий")
                releaseWakeLock()
            } catch (error: Exception) {
                val message = error.message ?: "Неизвестная ошибка фонового обучения."
                val interrupted = stopRequested || Thread.currentThread().isInterrupted ||
                    message.contains("остановлено", ignoreCase = true)
                checkpointPreferences().edit()
                    .putString(KEY_STATE, if (interrupted) STATE_INTERRUPTED else STATE_FAILED)
                    .putString(KEY_ERROR, message.take(500))
                    .commit()
                updateNotification(if (interrupted) "Обучение остановлено" else "Ошибка обучения ANS-100")
                releaseWakeLock()
            } finally {
                runCatching { runtime?.close() }
                running.set(false)
            }
        }
    }

    private fun loadDevelopmentRows(): List<ANS100BenchmarkDevelopmentExample> {
        val raw = getSharedPreferences(BENCHMARK_INPUT_PREFERENCES, MODE_PRIVATE)
            .getString(BENCHMARK_DEVELOPMENT_ROWS_KEY, null)
            ?: error("Сначала загрузи development JSONL в приложении.")
        val json = JSONArray(raw)
        require(json.length() in 1..MAX_TRAINING_ROWS) { "Development-набор должен содержать 1–$MAX_TRAINING_ROWS заданий." }
        val rows = List(json.length()) { index ->
            val row = json.getJSONObject(index)
            ANS100BenchmarkDevelopmentExample(
                taskId = row.getString("task_id"),
                inputText = row.getString("input_text"),
                trainingLabel = row.getString("training_label").uppercase(),
            )
        }
        require(rows.map { it.taskId }.distinct().size == rows.size) { "В development-наборе есть повторяющиеся task_id." }
        require(rows.all {
            it.taskId.startsWith("development:") &&
                it.inputText.isNotBlank() &&
                it.inputText.length <= 2_048 &&
                it.trainingLabel in ALLOWED_LABELS
        }) { "Development-набор содержит недопустимые задания или метки." }
        return rows
    }

    private fun benchmarkSafetyStopReason(): String? {
        val pssMb = Debug.getPss().toLong() / 1024L
        if (pssMb >= MAX_PROCESS_PSS_MB) return "PSS процесса достиг 1800 МиБ."
        if (Build.VERSION.SDK_INT >= 29) {
            val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (power != null && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                return "Температурное состояние Android достигло опасного уровня."
            }
        }
        return null
    }

    private fun checkpointPreferences() =
        getSharedPreferences(CHECKPOINT_PREFERENCES, MODE_PRIVATE)

    private fun markFailure(message: String) {
        checkpointPreferences().edit()
            .putString(KEY_STATE, STATE_FAILED)
            .putString(KEY_ERROR, message.take(500))
            .commit()
        updateNotification("Ошибка контрольной точки")
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:BackgroundRuntime").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS · фоновая работа",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Показывает состояние фоновых экспериментов JARVIS"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String = notificationMessage()): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, JarvisBackgroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_jarvis_notification)
            .setContentTitle("JARVIS · фоновая работа")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(0, "Остановить", stopIntent).build())
            .build()
    }

    private fun notificationMessage(): String {
        val prefs = checkpointPreferences()
        val state = prefs.getString(KEY_STATE, STATE_IDLE)
        val completed = prefs.getInt(KEY_COMPLETED, 0)
        val total = prefs.getInt(KEY_TOTAL, 0)
        return when (state) {
            STATE_PENDING -> "Подготовка фонового обучения ANS-100"
            STATE_RUNNING -> "Обучение ANS-100: $completed / $total"
            STATE_COMPLETED -> "Структурированное обучение завершено"
            STATE_INTERRUPTED -> "Обучение прервано · требуется новый запуск"
            STATE_FAILED -> "Ошибка: ${prefs.getString(KEY_ERROR, "неизвестно")}"
            else -> "Локальный ИИ-ассистент активен"
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    companion object {
        const val PREFERENCES = "jarvis_ui_preferences_v1"
        const val KEY_ENABLED = "background_service_enabled"
        private const val CHANNEL_ID = "jarvis_background"
        private const val NOTIFICATION_ID = 4100
        private const val ACTION_STOP = "org.jarvis.ans100.alpha.action.STOP_BACKGROUND"
        private const val ACTION_START_STRUCTURED_TRAINING = "org.jarvis.ans100.alpha.action.START_STRUCTURED_TRAINING"
        private const val ACTION_RESUME = "org.jarvis.ans100.alpha.action.RESUME_BACKGROUND"
        private const val BENCHMARK_INPUT_PREFERENCES = "jarvis_benchmark_checkpoint_v1"
        private const val BENCHMARK_DEVELOPMENT_ROWS_KEY = "development_rows"
        private const val CHECKPOINT_PREFERENCES = "jarvis_background_training_v1"
        private const val KEY_STATE = "state"
        private const val KEY_TOTAL = "total_tasks"
        private const val KEY_COMPLETED = "completed_tasks"
        private const val KEY_IN_FLIGHT = "in_flight_index"
        private const val KEY_ERROR = "error"
        private const val STATE_IDLE = "idle"
        private const val STATE_PENDING = "pending"
        private const val STATE_RUNNING = "running"
        private const val STATE_COMPLETED = "completed"
        private const val STATE_INTERRUPTED = "interrupted"
        private const val STATE_FAILED = "failed"
        private const val MAX_TRAINING_ROWS = 1_000
        private const val MAX_PROCESS_PSS_MB = 1_800L
        private const val TRAINING_BATCH_SIZE = 8
        private const val TRAINING_COOLDOWN_MS = 10_000L
        private val ALLOWED_LABELS = setOf("YES", "NO", "UNKNOWN", "CONFLICT")

        fun startStructuredTraining(context: Context): Boolean {
            val input = context.getSharedPreferences(BENCHMARK_INPUT_PREFERENCES, Context.MODE_PRIVATE)
                .getString(BENCHMARK_DEVELOPMENT_ROWS_KEY, null) ?: return false
            if (runCatching { JSONArray(input).length() }.getOrDefault(0) !in 1..MAX_TRAINING_ROWS) return false
            val prefs = context.getSharedPreferences(CHECKPOINT_PREFERENCES, Context.MODE_PRIVATE)
            val oldState = prefs.getString(KEY_STATE, STATE_IDLE)
            if (oldState == STATE_PENDING || oldState == STATE_RUNNING) return false
            val rows = JSONArray(input)
            if (!prefs.edit()
                    .putString(KEY_STATE, STATE_PENDING)
                    .putInt(KEY_TOTAL, rows.length())
                    .putInt(KEY_COMPLETED, 0)
                    .putInt(KEY_IN_FLIGHT, -1)
                    .putString(KEY_ERROR, "")
                    .commit()
            ) return false
            return try {
                context.startForegroundService(
                    Intent(context, JarvisBackgroundService::class.java).setAction(ACTION_START_STRUCTURED_TRAINING),
                )
                true
            } catch (error: Exception) {
                prefs.edit()
                    .putString(KEY_STATE, STATE_FAILED)
                    .putString(KEY_ERROR, error.message ?: "Не удалось запустить foreground service.")
                    .commit()
                throw error
            }
        }

        fun resumeIfNeeded(context: Context) {
            val prefs = context.getSharedPreferences(CHECKPOINT_PREFERENCES, Context.MODE_PRIVATE)
            val state = prefs.getString(KEY_STATE, STATE_IDLE)
            if (state == STATE_PENDING || state == STATE_RUNNING) {
                runCatching {
                    context.startForegroundService(
                        Intent(context, JarvisBackgroundService::class.java).setAction(ACTION_RESUME),
                    )
                }
            }
        }

        fun isStructuredTrainingActive(context: Context): Boolean {
            val state = context.getSharedPreferences(CHECKPOINT_PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_STATE, STATE_IDLE)
            return state == STATE_PENDING || state == STATE_RUNNING
        }

        /** Restore only explicitly enabled background presence or a saved user-requested run. */
        fun restoreAfterBoot(context: Context) {
            val presenceEnabled = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
            val state = context.getSharedPreferences(CHECKPOINT_PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_STATE, STATE_IDLE)
            if (presenceEnabled || state == STATE_PENDING || state == STATE_RUNNING) {
                runCatching {
                    context.startForegroundService(
                        Intent(context, JarvisBackgroundService::class.java).setAction(ACTION_RESUME),
                    )
                }
            }
        }
    }
}

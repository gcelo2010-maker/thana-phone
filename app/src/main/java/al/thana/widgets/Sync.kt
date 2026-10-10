package al.thana.widgets

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val r = withContext(Dispatchers.IO) { Repo.fetch(applicationContext) }
        Widgets.updateAll(applicationContext)
        return if (r == Repo.Fetch.NETWORK) Result.retry() else Result.success()
    }
}

object Sync {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Android lejon minimumi 15 min; ekzekutimi mund të vonohet nga Doze / kufizimet e baterisë. */
    fun schedule(c: Context) {
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(
            "thana-sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(net).build(),
        )
    }

    fun now(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork(
            "thana-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SyncWorker>().build(),
        )
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Sync.now(context)
    }
}

object Widgets {
    suspend fun updateAll(c: Context) {
        SalesWidget().updateAll(c)
        HealthWidget().updateAll(c)
        DebtWidget().updateAll(c)
        ApprovalsWidget().updateAll(c)
        AlertsWidget().updateAll(c)
        TrendWidget().updateAll(c)
        AskWidget().updateAll(c)
    }
}

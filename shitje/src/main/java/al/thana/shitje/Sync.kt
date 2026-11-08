package al.thana.shitje

import android.content.Context
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
        val r = withContext(Dispatchers.IO) { Panel.fetch(applicationContext) }
        PanelWidgets.updateAll(applicationContext)
        return if (r == Panel.State.NETWORK) Result.retry() else Result.success()
    }
}

object Sync {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    /** Android lejon minimumi 15 min. */
    fun schedule(c: Context) = WorkManager.getInstance(c).enqueueUniquePeriodicWork("shitje-sync", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(net).build())
    fun now(c: Context) = WorkManager.getInstance(c).enqueueUniqueWork("shitje-now", ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net).build())
}

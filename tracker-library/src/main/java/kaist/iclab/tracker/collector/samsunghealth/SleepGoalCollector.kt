package kaist.iclab.tracker.collector.samsunghealth

import android.content.Context
import android.util.Log
import kaist.iclab.tracker.collector.core.AbstractCollector
import kaist.iclab.tracker.collector.core.Availability
import kaist.iclab.tracker.collector.core.CollectorConfig
import kaist.iclab.tracker.collector.core.CollectorState
import kaist.iclab.tracker.collector.core.DataEntity
import kaist.iclab.tracker.data.core.SingletonStorageInterface
import kaist.iclab.tracker.permission.PermissionManagerInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.Thread.sleep
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.LocalDateFilter
import com.samsung.android.sdk.health.data.request.Ordering
import java.time.Instant
import java.time.ZoneId

//At now, just a copy of SampleCollector with name modifications
//Need to be implemented.

class SleepGoalCollector(
    val context: Context,
    permissionManager: PermissionManagerInterface,
    configStorage: SingletonStorageInterface<Config>,
    stateStorage: SingletonStorageInterface<CollectorState>,
) : AbstractCollector<SleepGoalCollector.Config, SleepGoalCollector.Entity>(permissionManager
    , configStorage, stateStorage) {

    companion object{
        val defaultConfig = Config(
            TimeUnit.SECONDS.toMillis(60)
        )
        //TODO : Set this value properly.
        val defaultBedTimeGoal:Long = 0
        val defaultWakeUpTimeGoal:Long = 0
    }
    override val _defaultConfig = SleepGoalCollector.defaultConfig

    override val permissions = listOfNotNull<String>(
        //TODO : Permission
    ).toTypedArray()
    override val foregroundServiceTypes: Array<Int> = listOfNotNull<Int>().toTypedArray()

    data class Config(
        val interval: Long,
    ) : CollectorConfig {
        override fun copy(property: String, setValue:String):Config {
            return when(property){
                "interval" -> this.copy(interval = setValue.toLong())
                else -> error("Unknown property $property")
            }
        }
    }

    override fun getConfigClass(): KClass<out CollectorConfig> {
        return Config::class
    }

    override fun getEntityClass(): KClass<out DataEntity> {
        return Entity::class
    }

    private var job: Job? = null

    suspend fun readGoal(store: HealthDataStore, latestGoalSetTime:Long):Entity?{
        val timeFilter = LocalDateFilter.since(
            Instant.ofEpochMilli(
                if(latestGoalSetTime != -1L) latestGoalSetTime else 0
            )
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        )
        val breq = DataType.SleepGoalType
            .LAST_BED_TIME.requestBuilder
            .setOrdering(Ordering.DESC)
            .setLocalDateFilter(timeFilter).build()
        val bresList = store.aggregateData(breq).dataList

        val wreq = DataType.SleepGoalType
            .LAST_WAKE_UP_TIME.requestBuilder
            .setOrdering(Ordering.DESC)
            .setLocalDateFilter(timeFilter).build()
        val wresList = store.aggregateData(wreq).dataList

        val bgoal:Long? = if(bresList.isNotEmpty()) bresList.first().value?.toSecondOfDay()?.toLong() else null
        val wgoal:Long? = if(wresList.isNotEmpty()) wresList.first().value?.toSecondOfDay()?.toLong() else null

        if(bgoal != null && wgoal != null){
            return Entity(
                latestGoalSetTime,
                if(bgoal == SleepGoalCollector.defaultBedTimeGoal) -1 else bgoal,
                if(wgoal == SleepGoalCollector.defaultWakeUpTimeGoal) -1 else wgoal,
                latestGoalSetTime
            )
        }
        return null
    }
    override fun start() {
        super.start()
        job = CoroutineScope(Dispatchers.IO).launch {
            //TODO: Should get latest goal's setTime
            //TODO: store should be passed from outside, not getting here.
            val store = HealthDataService.getStore(context)
            val latestGoalSetTime:Long = -1;
            while(isActive){
                val timestamp = System.currentTimeMillis()
                Log.d("TAG", "SleepGoalCollector: $timestamp")

                val readEntity = readGoal(store, latestGoalSetTime)
                if(readEntity != null){
                    listener?.invoke(
                        readEntity
                    )
                }

                sleep(configFlow.value.interval)
            }
        }

    }

    override fun stop() {
        Log.d(NAME, "STOP()")
        job?.cancel()
        job = null
        super.stop()
    }

    override fun isAvailable() = Availability(true)

    data class Entity(
        override val received: Long,
        val bedTime:Long,
        val wakeUpTime:Long,
        val goalSetTime: Long,
    ) : DataEntity(received)
}
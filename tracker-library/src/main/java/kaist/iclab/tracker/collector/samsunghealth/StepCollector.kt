package kaist.iclab.tracker.collector.samsunghealth

import android.Manifest
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
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.ZoneId


//At now, just a copy of SampleCollector with name modifications
//Need to be implemented.

class StepCollector(
    val context: Context,
    permissionManager: PermissionManagerInterface,
    configStorage: SingletonStorageInterface<Config>,
    stateStorage: SingletonStorageInterface<CollectorState>,
) : AbstractCollector<StepCollector.Config, StepCollector.Entity>(permissionManager
    , configStorage, stateStorage) {

    companion object{
        val defaultConfig = Config(
            TimeUnit.SECONDS.toMillis(60)
        )
    }
    override val _defaultConfig = StepCollector.defaultConfig

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
    fun getTimeFilter():LocalTimeFilter{
        val now = LocalDateTime.now()
        val sTime = now.minusMinutes((now.minute % 10).toLong())
        val eTime = sTime.plusMinutes(10)

        return LocalTimeFilter.of(sTime, eTime)
    }
    suspend fun readGoal(store: HealthDataStore):Entity?{
        //지금 시간이 포함되어 있는 Timeslot의 Steps 정보를 읽어온다.
        val timeFilter = getTimeFilter()
        val req = DataType.StepsType
            .TOTAL
            .requestBuilder
            .setLocalTimeFilter(timeFilter)
            .setOrdering(Ordering.DESC)
            .build()

        val resList = store.aggregateData(req).dataList
        if(resList.isNotEmpty()){
            val step:Long? = resList.first().value?.toLong()
            if(step != null){
                return Entity(
                    0,
                    if (step == StepGoalCollector.defaultGoal) -1 else step,
                    timeFilter.startTime!!.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    timeFilter.endTime!!.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                )
            }
        }
        return null
    }
    override fun start() {
        super.start()
        job = CoroutineScope(Dispatchers.IO).launch {
            //TODO: store should be passed from outside, not getting here.
            val store = HealthDataService.getStore(context)
            val latestGoalSetTime:Long = -1;
            while(isActive){
                val timestamp = System.currentTimeMillis()
                Log.d("TAG", "StepGoalCollector: $timestamp")

                //TODO : Insert only if this entity's timeslot is new. Else, do update instead of insertion.
                val readEntity = readGoal(store)
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
        val steps: Long,
        val startTime: Long,
        val endTime: Long,
    ) : DataEntity(received)
}
package org.dnsbunker.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class ProtectionStatus { OFF, STARTING, ACTIVE, WIFI_EXEMPT, DEGRADED, ERROR }
data class RuntimeSnapshot(
    val status: ProtectionStatus = ProtectionStatus.OFF,
    val transport: String = "—",
    val detail: String = "",
    val queries: Long = 0,
    val blocked: Long = 0,
    val blockedPro:Long=0,
    val blockedTif:Long=0,
    val blockedOther:Long=0,
    val failures: Long = 0,
    val lastLatencyMillis: Long? = null,
    val latencyTotalMillis: Long = 0,
    val warmSamples:Long=0,
    val warmTotalMillis:Long=0,
    val lastWarmMillis:Long?=null,
    val lastSetupMillis:Long?=null,
    val reconnects: Long = 0,
    val echStatus: String = "",
    val padding: Boolean = true,
    val androidValidated: Boolean = false,
    val interceptPublicDns: Boolean = false,
    val batteryExempt: Boolean = false,
    val privateDnsStrict: Boolean = false,
    val captivePortal: Boolean = false,
    val underlayValidated: Boolean = false,
    val underlayAvailable: Boolean = false,
    val protocolHealth: List<ProtocolHealth> = emptyList(),
    val recentActivity: List<Long> = List(24) { 0L },
    val recentBlocked: List<Long> = List(24) { 0L },
    val startedAtMillis: Long? = null,
    val currentWifi: String? = null,
    val lockdown: Boolean = false,
    val wifiLocationReady: Boolean = false,
    val backgroundLocation: Boolean = true,
    val updateState: UpdateState = UpdateState.IDLE,
    val updateVersion: String? = null,
)
object AppState {
    val snapshot = MutableStateFlow(RuntimeSnapshot())
    private val timeLock=Any()
    private var bucket = System.nanoTime()/300_000_000_000L
    fun update(block: (RuntimeSnapshot)->RuntimeSnapshot) = snapshot.update(block)
    fun recordQuery(elapsedMs: Long,isBlocked: Boolean,category:DnsWire.BlockCategory=if(isBlocked)DnsWire.BlockCategory.OTHER else DnsWire.BlockCategory.NONE,timing:QueryTiming=QueryTiming(false,0,elapsedMs)) = synchronized(timeLock) {
        val now=System.nanoTime()/300_000_000_000L
        val shift=(now-bucket).coerceIn(0,24).toInt();bucket=now
        update { old ->
            val activity=(old.recentActivity.drop(shift)+List(shift){0L}).toMutableList()
            val blocked=(old.recentBlocked.drop(shift)+List(shift){0L}).toMutableList()
            activity[23]++;if(isBlocked)blocked[23]++
            old.copy(queries=old.queries+1,blocked=old.blocked+if(isBlocked)1 else 0,
                blockedPro=old.blockedPro+if(category==DnsWire.BlockCategory.PRO)1 else 0,
                blockedTif=old.blockedTif+if(category==DnsWire.BlockCategory.TIF)1 else 0,
                blockedOther=old.blockedOther+if(category==DnsWire.BlockCategory.OTHER)1 else 0,
                lastLatencyMillis=elapsedMs,latencyTotalMillis=old.latencyTotalMillis+elapsedMs,
                warmSamples=old.warmSamples+if(timing.cold)0 else 1,
                warmTotalMillis=old.warmTotalMillis+if(timing.cold)0 else timing.elapsedMillis,
                lastWarmMillis=if(timing.cold)old.lastWarmMillis else timing.elapsedMillis,
                lastSetupMillis=if(timing.cold)timing.setupMillis else old.lastSetupMillis,
                recentActivity=activity,recentBlocked=blocked)
        }
    }
    fun advanceActivity() = synchronized(timeLock) {
        val now=System.nanoTime()/300_000_000_000L
        val shift=(now-bucket).coerceIn(0,24).toInt()
        if(shift>0) {bucket=now;update {it.copy(recentActivity=it.recentActivity.drop(shift)+List(shift){0L},recentBlocked=it.recentBlocked.drop(shift)+List(shift){0L})}}
    }
    fun recordFailure(reason:String) = update { it.copy(failures=it.failures+1,detail=reason) }
    fun resetStatistics() = synchronized(timeLock) {
        bucket=System.nanoTime()/300_000_000_000L
        update { it.copy(queries=0,blocked=0,blockedPro=0,blockedTif=0,blockedOther=0,failures=0,lastLatencyMillis=null,latencyTotalMillis=0,warmSamples=0,warmTotalMillis=0,lastWarmMillis=null,lastSetupMillis=null,
            recentActivity=List(24){0L},recentBlocked=List(24){0L}) }
    }
}

package org.coepi.android.repo

import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.schedulers.Schedulers.io
import io.reactivex.subjects.PublishSubject
import org.coepi.android.api.CENApi
import org.coepi.android.api.request.ApiParamsCenReport
import org.coepi.android.api.request.toApiParamsCenReport
import org.coepi.android.api.toCenReport
import org.coepi.android.cen.CenKey
import org.coepi.android.cen.RealmCenDao
import org.coepi.android.cen.RealmCenKeyDao
import org.coepi.android.cen.ReceivedCen
import org.coepi.android.cen.ReceivedCenReport
import org.coepi.android.cen.SymptomReport
import org.coepi.android.cen.toCenKey
import org.coepi.android.common.Result
import org.coepi.android.common.Result.Failure
import org.coepi.android.common.Result.Success
import org.coepi.android.common.doIfSuccess
import org.coepi.android.common.flatMap
import org.coepi.android.common.group
import org.coepi.android.common.map
import org.coepi.android.domain.CenMatcher
import org.coepi.android.extensions.coEpiTimestamp
import org.coepi.android.extensions.rx.toObservable
import org.coepi.android.extensions.toResult
import org.coepi.android.system.log.LogTag.CEN_MATCHING
import org.coepi.android.system.log.LogTag.NET
import org.coepi.android.system.log.log
import org.coepi.android.system.rx.OperationState.Progress
import org.coepi.android.system.rx.OperationStateNotifier
import org.coepi.android.system.rx.VoidOperationState
import java.lang.System.currentTimeMillis
import java.util.Date

interface CoEpiRepo {

    // State of send report operation
    val sendReportState: Observable<VoidOperationState>

    // Store CEN from other device
    fun storeObservedCen(cen: ReceivedCen)

    // Send symptoms report
    fun sendReport(report: SymptomReport)

    fun reports(): Result<List<ReceivedCenReport>, Throwable>
}

class CoepiRepoImpl(
    private val cenMatcher: CenMatcher,
    private val api: CENApi,
    private val cenDao: RealmCenDao,
    private val cenKeyDao: RealmCenKeyDao
) : CoEpiRepo {

    private var matchingStartTime: Long? = null

    // last time (unix timestamp) the CENKeys were requested. TODO use it. From preferences.
    private var lastCENKeysCheck: Long = 0L

    private val disposables = CompositeDisposable()

    private val postSymptomsTrigger: PublishSubject<SymptomReport> = PublishSubject.create()
    override val sendReportState: PublishSubject<VoidOperationState> = PublishSubject.create()

    init {
        disposables += postSymptomsTrigger.doOnNext {
            sendReportState.onNext(Progress)
        }
        .flatMap { report -> postReport(report).toObservable(Unit).materialize() }
        .subscribe(OperationStateNotifier(sendReportState))
    }

    override fun sendReport(report: SymptomReport) {
        postSymptomsTrigger.onNext(report)
    }

    override fun reports(): Result<List<ReceivedCenReport>, Throwable> {
        val keysResult = api.cenkeysCheck(0).execute()
            .toResult().map { keyStrings ->
                keyStrings.map {
                    CenKey(it, Date().coEpiTimestamp())
                }
            }

        keysResult.doIfSuccess { keys ->
            log.i("Retrieved ${keys.size} keys. Start matching...", CEN_MATCHING)
            val keyStrings = keys.map { it.key }
            log.v("$keyStrings", CEN_MATCHING)
        }

        matchingStartTime = currentTimeMillis()

        val matchedKeysResult: Result<List<CenKey>, Throwable> =
            keysResult.map { filterMatchingKeys(it) }

        matchingStartTime?.let {
            val time = (currentTimeMillis() - it) / 1000
            log.i("Took ${time}s to match keys", CEN_MATCHING)
        }
        matchingStartTime = null

        matchedKeysResult.doIfSuccess {
            if (it.isNotEmpty()) {
                log.i("Matches found: $it", CEN_MATCHING)
            } else {
                log.i("No matches found", CEN_MATCHING)
            }
        }

        return matchedKeysResult.flatMap { reportsFor(it) }
    }

    private fun reportsFor(keys: List<CenKey>): Result<List<ReceivedCenReport>, Throwable> {

        // Retrieve reports for keys, group in successful / failed calls
        val (successful, failed) = keys.map { key ->
            api.getCenReports(key.key).execute().toResult()
        }.group()

        // Log failed calls
        failed.forEach {
            log.e("Error fetching reports: $it")
        }

        // If we only got failure results, return a failure, otherwise return success
        // and ignore failures (logged before)
        // TODO review / maybe refine this error handling
        return if (successful.isEmpty() && failed.isNotEmpty()) {
            Failure(Throwable("Couldn't fetch any reports"))
        } else {
            Success(successful.flatten().map { ReceivedCenReport(it.toCenReport()) })
        }
    }

    private fun filterMatchingKeys(keys: List<CenKey>): List<CenKey> =
        keys.distinct().mapNotNull { key ->
            //.distinct():same key may arrive more than once, due to multiple reporting
            if (cenMatcher.hasMatches(key, Date().coEpiTimestamp())) {
                key
            } else {
                null
            }
        }

    private fun postReport(report: SymptomReport): Completable {
        val params: ApiParamsCenReport? =
            cenKeyDao.lastCENKeys(3).takeIf { it.isNotEmpty() }?.let { keys ->
                report.toApiParamsCenReport(keys.map { it.toCenKey() })
            }
        return if (params != null) {
            log.i("Sending CEN report to API: $params", NET)
            api.postCENReport(params).subscribeOn(io())
        } else {
            log.e("Can't send report. No CEN keys.", NET)
            Completable.complete()
        }
    }

    override fun storeObservedCen(cen: ReceivedCen) {
        if (cenDao.insert(cen)) {
            log.v("Inserted an observed CEN: $cen")
        }
    }
}

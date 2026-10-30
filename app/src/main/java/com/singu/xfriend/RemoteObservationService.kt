package com.singu.xfriend

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*

/** User-started observation only: network loss never submits or replays a task. */
class RemoteObservationService : Service() {
    private val work=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val jobs=java.util.concurrent.ConcurrentHashMap<String,Job>()
    override fun onCreate() {
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("remote-tasks","电脑任务进度",NotificationManager.IMPORTANCE_DEFAULT))
        startForeground(63, notification("正在观察电脑任务","返回应用可查看进度与审批"))
    }
    private fun notification(title:String,text:String):Notification {
        val open=packageManager.getLaunchIntentForPackage(packageName)
        val builder=Notification.Builder(this,"remote-tasks").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(text).setVisibility(Notification.VISIBILITY_PRIVATE)
        if(open!=null) builder.setContentIntent(PendingIntent.getActivity(this,63,open,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        return builder.build()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val accountScope=intent?.getStringExtra("scope")
        if(accountScope.isNullOrBlank()){stopSelfResult(startId);return START_NOT_STICKY}
        if(jobs[accountScope]?.isActive==true)return START_NOT_STICKY
        val job=work.launch(start=CoroutineStart.LAZY) {
            val vault=FriendRemoteVault(applicationContext,accountScope)
            val client=FriendRemoteClient()
            var failures=0
            try {
                while(isActive) {
                    val saved=vault.read()
                    val session=saved.session ?: break
                    val active=saved.observableRuns()
                    if(active.isEmpty())break
                    for(run in active) {
                        try {
                            val next=friendRemoteSnapshot(run,client.snapshot(session,run.runId)).copy(lastSyncedAt=System.currentTimeMillis())
                            if(!vault.updateObserved(next,session))break
                            failures=0
                            if(next.state!=run.state && (next.terminal || next.state=="awaiting_approval")) {
                                getSystemService(NotificationManager::class.java).notify(run.runId.hashCode(),notification(if(next.state=="awaiting_approval")"电脑任务等待确认" else "电脑任务已有结果","返回 Xfriend 查看详情"))
                            }
                        }catch(e:CancellationException){throw e}catch(_:Exception){failures++}
                    }
                    if(failures>=12)break
                    delay(if(failures>0)15000 else 5000)
                }
            }finally{jobs.remove(accountScope,currentCoroutineContext().job);if(jobs.isEmpty())stopSelf()}
        }
        jobs[accountScope]=job;FriendRemoteVault.registerObservation(accountScope,job);job.start()
        return START_NOT_STICKY
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){work.cancel();super.onDestroy()}
    companion object {
        fun watch(context:Context,scope:String){context.startForegroundService(Intent(context,RemoteObservationService::class.java).putExtra("scope",scope))}
    }
}

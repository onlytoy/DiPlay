package local.airuize.receiver

/** Main-thread bounded trial sequence. Each completed/interrupted trial is saved before teardown. */
class UsbBlackScreenSuite(
    private val later:(Long,()->Unit)->Unit,
    private val now:()->Long,
    private val usbStopped:()->Boolean,
    private val stop:((()->Unit)->Unit),
    private val start:(VideoTrial)->Unit,
    private val capture:(VideoTrial,Boolean,String)->String,
    private val save:(String,String)->Unit,
    private val progress:(String,Boolean)->Unit,
    private val complete:(String)->Unit,
) {
    var running=false; private set
    private var generation=0
    private var index=0
    private var trialStarted=false
    private var runId=""
    private val results=StringBuilder()
    fun begin() {
        if(running) return
        running=true;index=0;results.setLength(0);runId=System.currentTimeMillis().toString()
        generation++;next(generation)
    }
    private fun next(expected:Int) {
        if(!running || expected!=generation) return
        if(index>=BlackScreenDiagnostics.trials.size) { finish("全部方案完成");return }
        trialStarted=false
        val trial=BlackScreenDiagnostics.trials[index]
        progress("${index+1}/4 ${trial.title}：释放旧连接…",true)
        stop {} // Do not let a missing completion callback disable the stop deadline.
        val deadline=now()+15000
        fun waitForStop() {
                if(!running || expected!=generation) return
                if(!usbStopped()) {
                    if(now()>=deadline) {
                        results.append(safeCapture(trial,false,"USB停止超时")).append('\n')
                        finish("旧USB服务未释放，停止后续测试");return
                    }
                    later(250) { waitForStop() };return
                }
                BlackScreenDiagnostics.current=trial
                trialStarted=true
                progress("${index+1}/4 ${trial.title}：手机保持解锁，等待${if(index==0)90 else 60}秒",true)
                try { start(trial) } catch(error:Exception) { endTrial(false,"启动异常 ${error.javaClass.simpleName}");return }
                later(if(index==0)90000 else 60000) { if(running && expected==generation) endTrial(false,"观察时间结束") }
            }
        waitForStop()
    }
    fun seen() { if(running && trialStarted) endTrial(true,"用户确认看到画面") }
    private fun endTrial(visible:Boolean,reason:String) {
        if(!running || !trialStarted) return
        trialStarted=false
        val trial=BlackScreenDiagnostics.trials[index]
        val text="USB黑屏批量诊断 run=$runId trial=${trial.id}\n$reason\n"+safeCapture(trial,visible,reason)
        results.append(trial.title).append("：").append(reason).append('\n')
        save(text,"USB黑屏 $runId ${trial.id}")
        index++;generation++;next(generation)
    }
    fun cancel() {
        if(!running) return
        if(trialStarted) {
            val trial=BlackScreenDiagnostics.trials[index]
            save("USB黑屏批量诊断 run=$runId trial=${trial.id} INTERRUPTED\n"+safeCapture(trial,false,"用户或页面离开中止"),"USB黑屏 $runId ${trial.id} 中止")
        }
        finish("测试中止，已保留当前结果")
    }
    private fun safeCapture(trial:VideoTrial,visible:Boolean,reason:String):String=try {
        capture(trial,visible,reason)
    } catch(error:Exception) { "采集部分失败=${error.javaClass.simpleName}；未认定画面成功；reason=$reason" }
    private fun finish(reason:String) {
        generation++;running=false;trialStarted=false
        BlackScreenDiagnostics.current=null
        save("USB黑屏批量诊断汇总 run=$runId\n$reason\n$results\n缓冲释放计数不证明画面实际可见；请以用户确认及每轮详细报告为准。","USB黑屏 $runId 汇总")
        stop {}
        progress(reason,false);complete(reason)
    }
}

package local.airuize.receiver

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothClass
import android.media.*
import android.os.Bundle
import android.os.Environment
import android.view.*
import android.widget.*
import com.shilapi.xcertplay.airplay.*
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ReceiverActivity : Activity() {
    private lateinit var homeUi: ReceiverHomeUi
    private lateinit var quickActions: LinearLayout
    private var awaitingProjection = false
    private val usbDetached:()->Unit = {
        runOnUiThread {
            if(!destroyed && !blackScreenSuite.running && ::homeUi.isInitialized && controller?.activeSession==null) {
                touchQueue.clear()
                sessionMicrophoneEnabled=false
                LegacyWiredService.setVideoSurface(null)
                video.visibility=View.GONE
                videoBox.visibility=View.GONE
                showHome()
                log("USB连接结束：投屏Surface已隐藏，触摸队列已清理，返回DiPlay首页")
            }
        }
    }
    private var bluetoothEventsRegistered=false
    private val bluetoothEvents=object:android.content.BroadcastReceiver() {
        override fun onReceive(context:android.content.Context,intent:android.content.Intent) {
            log("系统蓝牙事件=${intent.action?.substringAfterLast('.')}; state=${intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,intent.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE,-1))}; previous=${intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE,-1)}；ACL为设备级链路，不等同于DiPlay RFCOMM或音频配置；未记录设备地址")
        }
    }
    private var speedProbe: LegacyVehicleSpeed? = null
    private lateinit var status: TextView
    private lateinit var microphone: CheckBox
    private lateinit var vehicleGps: CheckBox
    private lateinit var video: LegacyVideoTarget
    private lateinit var videoBox: FrameLayout
    private lateinit var controls: LinearLayout
    private var displayWidth = 1024
    private var displayHeight = 600
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var testSink: LegacyMediaSink? = null
    private val sink: LegacyMediaSink? get() = testSink ?: sessionService?.sink
    private val controller: LegacyWirelessController? get() = sessionService?.controller
    private var sessionService: LegacySessionService? = null
    private var bound = false
    private val serviceConnection = object: android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
            sessionService = (binder as LegacySessionService.LocalBinder).service
            sessionService?.observer = ::log
            sessionService?.frameObserver = { runOnUiThread { if(!destroyed) { LegacyWiredService.instance?.projectionDisplay?.let(::setProjectionSize); sessionService?.setSurface(video.targetSurface); log("视频恢复确认：当前Surface已输出首帧；投屏页可见=${homeUi.view.visibility!=View.VISIBLE}"); if(awaitingProjection && homeUi.view.visibility==View.VISIBLE) showProjection(); awaitingProjection=false } } }
            sessionService?.setSurface(video.targetSurface)
            val active=controller?.activeSession != null || LegacyWiredService.instance?.activeSession != null
            if(intent.action==SteeringVoiceService.SIRI_ACTION || intent.action==SteeringVoiceService.VOICE_ACTION) {
                if(active) handleVoiceKey(if(intent.action==SteeringVoiceService.SIRI_ACTION) VoiceKeyPolicy.Action.SIRI else VoiceKeyPolicy.Action.OPEN)
                else showHome()
            } else if(active) {
                LegacyWiredService.instance?.projectionDisplay?.let(::setProjectionSize)
                showProjection()
            } else if(ConnectionStartPolicy.onOpen(getSharedPreferences("receiver-ui",MODE_PRIVATE).getBoolean("autoOpenConnect",false),active,sessionService?.hasConnectionRequest==true)) autoConnect()
            refreshHomeStatus()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName) { sessionService=null }
    }
    @Volatile private var destroyed = false
    private val selfTesting = AtomicBoolean(false)
    private val toneTesting = AtomicBoolean(false)
    private var transitioning = false
    private val siriControl = SiriRequestSerialiser()
    private val siriRequestNumber = AtomicInteger()
    @Volatile private var sessionMicrophoneEnabled = false
    private val report = StringBuilder("DiPlay Legacy 0.2.36-api17.7-public; Android SDK17; experimental\n")
    private val reportStarted = android.os.SystemClock.elapsedRealtime()
    private val diagnosticHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingBatch=false
    private lateinit var trialPanel:LinearLayout
    private lateinit var trialLabel:TextView
    private val blackScreenSuite by lazy { UsbBlackScreenSuite(
        { delay,action -> diagnosticHandler.postDelayed({ if(!destroyed) action() },delay);Unit },
        { android.os.SystemClock.elapsedRealtime() },{ LegacyWiredService.isTransportStopped() && !transitioning },
        { after -> stopReceiver(after) },
        { trial -> video.select(trial.output);startUsb() },
        { trial,visible,reason ->
            val counts=LegacyWiredService.instance?.videoCounts() ?: longArrayOf(0,0,0)
            "方案=${trial.id} decoder=${trial.decoder} output=${video.mode} requested=${trial.width}x${trial.height} fps=${trial.fps}\n"+
                "判定="+BlackScreenDiagnostics.diagnosis(counts[0],counts[1],counts[2],visible)+"\nreason=$reason\n"+
                (LegacyWiredService.instance?.videoEvidence() ?: "视频接收器尚未创建")+"\n"+reportContent()
        },::saveDiagnosticReport,
        { text,running -> if(!destroyed && ::trialPanel.isInitialized) {trialLabel.text=text;trialPanel.visibility=if(running)View.VISIBLE else View.GONE} },
        { reason -> if(!destroyed) {video.select(ReceiverSettings.read(this).videoOutput);showHome();log(reason)} }
    ) }
    private lateinit var gpsProbe: LegacyGpsProbe
    private val touchSent = java.util.concurrent.atomic.AtomicLong()
    private val touchFailed = java.util.concurrent.atomic.AtomicLong()
    private val diagnosticTick = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (controller != null || !LegacyWiredService.isTransportStopped()) diagnosticSummary()
            diagnosticHandler.postDelayed(this, 15000)
        }
    }
    private fun diagnosticSummary() {
        VoiceKeyJournal.drain().forEach(::log)
        log(SystemServiceDiagnostics.snapshot(this))
        log("显示状态：页面=${homeUi.page}；首页可见=${homeUi.view.visibility==View.VISIBLE}；视频可见=${video.isShown}；View=${video.width}x${video.height}；Surface=${surfaceWidth}x${surfaceHeight}；Surface有效=${(video.targetSurface!=null)}；目标=${displayWidth}x${displayHeight}；等待首帧=$awaitingProjection")
        log("麦克风配置：RECORD_AUDIO权限=${microphonePermissionGranted()}；本会话启用=$sessionMicrophoneEnabled；API18在安装时授权；无运行时权限弹窗")
        sessionMicrophoneEnabled=sessionService?.microphoneEnabled ?: sessionMicrophoneEnabled
        controller?.let { log(it.diagnosticSummary()) }
        sink?.let { log(it.diagnosticSummary()) }
        log("触摸统计（应用累计）：事件通道写入成功=${touchSent.get()}；失败=${touchFailed.get()}（不证明手机已执行）")
        try {
            val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            log("系统音频状态：mode=${audio.mode}；音乐音量=${audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}；通话音量=${audio.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)}；通知音量=${audio.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION)}；麦克风静音=${audio.isMicrophoneMute}；扬声器=${audio.isSpeakerphoneOn}；蓝牙SCO=${audio.isBluetoothScoOn}（仅读取，不修改选路）")
        } catch (error: Exception) { log("系统音频状态读取失败：${error.javaClass.simpleName}") }
    }
    private val touchQueue = ArrayBlockingQueue<Pair<AirPlaySession, List<AirPlayContact>>>(64)
    private var touchThread: Thread? = null
    private fun log(text: String) {
        synchronized(report) {
            report.append('+').append(android.os.SystemClock.elapsedRealtime() - reportStarted).append("ms ").append(text).append('\n')
            if (report.length > 60000) report.delete(0, report.length - 48000)
        }
        if (!RuntimeLogPolicy.updateUi(text)) return
        if (!destroyed) runOnUiThread { if (!destroyed) { status.text = text; refreshHomeStatus() } }
    }
    private val strings by lazy { LegacyStrings(this) }
    private fun tr(zh:String,en:String,ar:String):String = strings.translate(zh,en,ar)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val preferences = getSharedPreferences("receiver-ui", MODE_PRIVATE)
        if(ReceiverSettings.WIRED_ONLY) preferences.edit().putBoolean("autoOpenConnect",false).putBoolean("bootConnect",false).putBoolean("sessionWanted",false).commit()
        val display = LegacyWiredService.instance?.projectionDisplay?.takeIf {!LegacyWiredService.isTransportStopped()} ?: ReceiverSettings.read(this).display()
        displayWidth=display.widthPixels; displayHeight=display.heightPixels
        val layout = FrameLayout(this).apply { layoutDirection = if(strings.language()=="ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR }
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xee202020.toInt()) }
        var toolbar = LinearLayout(this)
        var buttonCount = 0
        controls.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        fun button(label: String, action: () -> Unit) {
            if (buttonCount > 0 && buttonCount % 4 == 0) {
                toolbar = LinearLayout(this)
                controls.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            }
            toolbar.addView(Button(this).apply { text = label; textSize = 14f; setOnClickListener { action() } }, LinearLayout.LayoutParams(0, -2, 1f))
            buttonCount++
        }
        button("屏幕检测／分辨率") { showDisplaySettings() }
        button(tr("停止", "Stop", "إيقاف")) { if(blackScreenSuite.running) blackScreenSuite.cancel() else stopReceiver() }
        button("Siri") { requestSiri("菜单") }
        button(tr("设置", "Settings", "الإعدادات")) { showHome(); homeUi.render("settings") }
        button(tr("返回车机", "Head unit", "السيارة")) { startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)) }
        button("USB CarPlay") { startUsb() }
        button(tr("音视频自检", "Media test", "اختبار الوسائط")) { selfTest() }
        button(tr("日志", "Log", "السجل")) { showReport() }
        button(tr("导出", "Export", "تصدير")) { exportReport() }
        button("黑屏批量排查") { confirmBlackScreenSuite() }
        button("选择画面方案") { chooseVideoTrial() }
        button(tr("诊断", "Diagnostics", "التشخيص")) { showDiagnostics() }
        microphone = CheckBox(this).apply {
            text = tr("麦克风 PCM（重新连接生效）", "Microphone PCM (reconnect)", "الميكروفون (أعد الاتصال)"); textSize = 12f
            isChecked = preferences.getBoolean("microphonePcm", true)
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit().putBoolean("microphonePcm", enabled).commit()
                log("麦克风设置已保存：$enabled；重新连接生效")
            }
        }
        val inputOptions = LinearLayout(this)
        inputOptions.addView(microphone)
        vehicleGps = CheckBox(this).apply {
            text = tr("车载GPS（重新连接生效）", "Vehicle GPS (reconnect)", "نظام GPS للسيارة"); textSize = 12f
            isChecked = preferences.getBoolean("vehicleGps", false)
            setOnCheckedChangeListener { _, enabled -> preferences.edit().putBoolean("vehicleGps", enabled).commit() }
        }
        inputOptions.addView(vehicleGps)
        controls.addView(inputOptions)
        val steering = CheckBox(this).apply {
            text = tr("方向盘语音键（后台监听）", "Steering voice button (background)", "زر الصوت على المقود"); textSize = 12f
            isChecked = preferences.getBoolean("steeringVoice", false)
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit().putBoolean("steeringVoice", enabled).commit()
                updateSteeringService(enabled)
            }
        }
        controls.addView(steering)
        status = TextView(this).apply { text = strings.text("connecting_to_your_iphone"); textSize = 14f; setTextColor(android.graphics.Color.WHITE) }
        controls.addView(status)
        videoBox = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        video = LegacyVideoTarget(this) { reason -> if(!destroyed) attachVideoSurface(true,reason) }
        video.select(ReceiverSettings.read(this).videoOutput)
        videoBox.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        videoBox.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val availableWidth = right - left; val availableHeight = bottom - top
            if (availableWidth > 0 && availableHeight > 0) {
                val width = minOf(availableWidth, availableHeight * displayWidth / displayHeight)
                val height = width * displayHeight / displayWidth
                if (video.layoutParams.width != width || video.layoutParams.height != height)
                    video.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
            }
        }
        video.setOnTouchListener { view, event ->
            val session = controller?.activeSession ?: LegacyWiredService.instance?.activeSession
            if (session != null) {
                val action = event.actionMasked
                val contacts = (0 until minOf(event.pointerCount, 2)).map { index ->
                    AirPlayContact(event.getPointerId(index), (event.getX(index) / view.width).toDouble().coerceIn(0.0, 1.0),
                        (event.getY(index) / view.height).toDouble().coerceIn(0.0, 1.0),
                        action != MotionEvent.ACTION_CANCEL && !(index == event.actionIndex && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)))
                }
                // Preserve down/up transitions. Saturation forces a release instead of a stuck contact.
                if (!touchQueue.offer(session to contacts)) { touchQueue.clear(); touchQueue.offer(session to emptyList()) }
            }
            true
        }
        layout.addView(videoBox, FrameLayout.LayoutParams(-1, -1))
        layout.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        quickActions = LinearLayout(this)
        val menu = Button(this).apply {
            text = tr("菜单", "Menu", "القائمة") + " v0.2.36-api17.7-public"; textSize = 13f; setPadding(0, 0, 0, 0)
            setOnClickListener { controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        val actionWidth = (100 * resources.displayMetrics.density + 0.5f).toInt()
        val actionHeight = (48 * resources.displayMetrics.density + 0.5f).toInt()
        quickActions.addView(menu, LinearLayout.LayoutParams(actionWidth, actionHeight))
        quickActions.addView(Button(this).apply {
            text = strings.text("diagnostics"); textSize = 14f; setPadding(0, 0, 0, 0)
            setOnClickListener { showDiagnostics() }
        }, LinearLayout.LayoutParams(actionWidth, actionHeight))
        val menuParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT)
        quickActions.addView(Button(this).apply {
            text=tr("首页", "Home", "الرئيسية"); setOnClickListener { if(blackScreenSuite.running) blackScreenSuite.cancel() else showHome() }
        }, LinearLayout.LayoutParams(actionWidth, actionHeight))
        layout.addView(quickActions, menuParams)
        homeUi=ReceiverHomeUi(this, ::tr,
            { if(controller?.activeSession!=null || LegacyWiredService.instance?.activeSession!=null) showProjection() else choosePhone() },
            ::startUsb, ::choosePhone, { stopReceiver { showHome() } }, ::showProjection,
            { ReceiverSettingsUi(this, ::log).show() },
            { homeUi.view.visibility=View.GONE; quickActions.visibility=View.VISIBLE; controls.visibility=View.VISIBLE },
            { startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)) },
            { box -> ReceiverSettingsPage(this,::log,::choosePhone,::startUsb,::exportReport,::showReport,
                { homeUi.view.visibility=View.GONE; quickActions.visibility=View.VISIBLE; controls.visibility=View.VISIBLE },
                ::selfTest,::chooseAudioChannel,::testAudioChannel,::applySavedSettings,
                ::confirmBlackScreenSuite,::chooseVideoTrial,::showDisplaySettings).render(box) })
        layout.addView(homeUi.view, FrameLayout.LayoutParams(-1,-1))
        trialPanel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(0xee202020.toInt());visibility=View.GONE }
        trialLabel=TextView(this).apply {textSize=15f;setTextColor(android.graphics.Color.WHITE)}
        trialPanel.addView(trialLabel)
        val trialButtons=LinearLayout(this)
        trialButtons.addView(Button(this).apply {text="这轮有画面，继续下一轮";setOnClickListener {blackScreenSuite.seen()}},LinearLayout.LayoutParams(0,-2,1f))
        trialButtons.addView(Button(this).apply {text="停止排查并保存";setOnClickListener {blackScreenSuite.cancel()}},LinearLayout.LayoutParams(0,-2,1f))
        trialPanel.addView(trialButtons);layout.addView(trialPanel,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        setContentView(layout); showHome()
        LegacyWiredService.connectionEndedObserver=usbDetached
        touchThread = Thread({
            while (!destroyed) {
                try {
                    val job = touchQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (job.first.sendTouch(job.second)) touchSent.incrementAndGet() else touchFailed.incrementAndGet()
                }
                catch (_: InterruptedException) { return@Thread } catch (_: Exception) {}
            }
        }, "legacy-touch").apply { isDaemon = true; start() }
        gpsProbe = LegacyGpsProbe(this, ::log)
        log("功能基准：DiPlay f2d06951 + 选择性移植Legacy v0.2.94；Android4.2原车USB视频/声音仍需验证，其他车辆结果不作为本车通过证明")
        diagnosticHandler.postDelayed(diagnosticTick, 15000)
        log("麦克风设置=${microphone.isChecked}；RECORD_AUDIO权限=${microphonePermissionGranted()}；Android4.3安装时授权；实际收音等待手机协商")
        updateSteeringService(preferences.getBoolean("steeringVoice", false))
        val serviceIntent = android.content.Intent(this, LegacySessionService::class.java)
        startService(serviceIntent)
        bound=bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        val filter=android.content.IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
        }
        registerReceiver(bluetoothEvents,filter); bluetoothEventsRegistered=true
        log(DeviceVideoEvidence.snapshot(this))
        log("启动策略：进入首页默认不建立蓝牙连接；自动连接=${preferences.getBoolean("autoOpenConnect",false)}；开机连接=${ReceiverSettings.read(this).bootConnect}")
    }
    private fun refreshHomeStatus() {
        if(!::homeUi.isInitialized) return
        homeUi.status.text=when {
            controller?.activeSession!=null || LegacyWiredService.instance?.activeSession!=null -> tr("已连接，可返回 CarPlay", "Connected — return to CarPlay", "متصل")
            sessionService?.hasConnectionRequest==true || awaitingProjection -> tr("正在连接…", "Connecting…", "جار الاتصال")
            else -> tr("请选择无线或 USB 连接", "Choose wireless or USB", "اختر الاتصال اللاسلكي أو USB")
        }
    }
    private fun showHome() {
        awaitingProjection=false; homeUi.render(); homeUi.view.visibility=View.VISIBLE
        controls.visibility=View.GONE; quickActions.visibility=View.GONE; refreshHomeStatus()
    }
    private fun showProjection() {
        awaitingProjection=false; homeUi.view.visibility=View.GONE
        controls.visibility=View.GONE; quickActions.visibility=View.VISIBLE
        videoBox.visibility=View.VISIBLE; video.visibility=View.VISIBLE; hideSystemBars()
        // Show the rendering target before waiting for decoded video. Layout/window changes
        // may replace the native Surface while Android keeps its Java wrapper.
        video.post {
            if(!destroyed && homeUi.view.visibility!=View.VISIBLE) attachVideoSurface(true,"进入或返回投屏页")
        }
    }
    override fun onBackPressed() {
        if(blackScreenSuite.running) {blackScreenSuite.cancel();return}
        if(homeUi.view.visibility!=View.VISIBLE || homeUi.page!="home") showHome()
        else super.onBackPressed()
    }
    private fun updateSteeringService(enabled: Boolean) {
        getSharedPreferences("receiver-ui", MODE_PRIVATE).edit().putBoolean("steeringVoice", enabled).commit()
        try {
            val service = android.content.Intent(this, SteeringVoiceService::class.java)
            if (enabled) { startService(service); log("方向盘后台监听已请求；可取消勾选关闭") }
            else { stopService(service); log("方向盘后台监听已关闭") }
        } catch (error: Exception) { log("方向盘后台监听异常：${error.javaClass.simpleName}") }
    }
    private fun requestSiri(source: String) {
        val active = controller?.activeSession ?: LegacyWiredService.instance?.activeSession
        if (active == null) { log("Siri[$source]：没有激活的CarPlay会话"); return }
        val number = siriRequestNumber.incrementAndGet()
        val queued = siriControl.submit(
            { !destroyed && (controller?.activeSession ?: LegacyWiredService.instance?.activeSession) === active },
            { active.invokeSiri() },
            { failure ->
                log("Siri[$source]#$number：直接调用上游invokeSiri；异常=${failure ?: "无"}；本会话麦克风启用=$sessionMicrophoneEnabled（具体写入见上游请求日志；不证明识别或执行）")
                diagnosticHandler.postDelayed({
                    if (!destroyed && (controller?.activeSession ?: LegacyWiredService.instance?.activeSession) === active) {
                        log("Siri#$number 请求后5秒的收音状态（不记录语音内容）")
                        diagnosticSummary()
                    }
                }, 5000)
            },
            { log("Siri#$number 已忽略：会话已结束或更换") }
        )
        if (!queued) log("Siri#$number 控制通道已关闭")
    }

    private var windowLongSent=false
    private fun handleVoiceKey(action:VoiceKeyPolicy.Action) {
        VoiceKeyJournal.drain().forEach(::log)
        val active=controller?.activeSession ?: LegacyWiredService.instance?.activeSession
        log("Steering voice action=$action; active=${active!=null}")
        if(active!=null) {
            showProjection()
            requestSiri("方向盘语音键点击")
        } else if(controller==null && !transitioning) showHome()
        else log("CarPlay connecting; Siri waits for an active session")
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); setIntent(intent)
        when(intent.action) {
            android.content.Intent.ACTION_MAIN -> if(controller?.activeSession!=null || LegacyWiredService.instance?.activeSession!=null) { showProjection() }
            SteeringVoiceService.VOICE_ACTION -> handleVoiceKey(VoiceKeyPolicy.Action.OPEN)
            SteeringVoiceService.SIRI_ACTION -> handleVoiceKey(VoiceKeyPolicy.Action.SIRI)
        }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(android.os.SystemClock.elapsedRealtime()<VoiceKeyJournal.captureUntil)
            log("Window key code=${event.keyCode} action=${event.action} repeat=${event.repeatCount} heldMs=${(event.eventTime-event.downTime).coerceAtLeast(0)}")
        if(!VoiceKeyPolicy.isWindowVoice(event.keyCode)) return super.dispatchKeyEvent(event)
        val held=(event.eventTime-event.downTime).coerceAtLeast(0)
        val threshold=ViewConfiguration.getLongPressTimeout().toLong()
        val action=VoiceKeyPolicy.windowAction(event.keyCode,held,threshold)!!
        if(event.action==KeyEvent.ACTION_DOWN) {
            if(event.repeatCount==0) windowLongSent=false
            if(!windowLongSent && (event.isLongPress || action==VoiceKeyPolicy.Action.SIRI)) {
                windowLongSent=true
                if(VoiceKeyJournal.gate.accept(android.os.SystemClock.elapsedRealtime(),VoiceKeyPolicy.Action.SIRI)) handleVoiceKey(VoiceKeyPolicy.Action.SIRI)
            }
        }
        if(event.action==KeyEvent.ACTION_UP && !event.isCanceled && !windowLongSent) {
            log("Window voice classified=$action heldMs=$held thresholdMs=$threshold")
            if(VoiceKeyJournal.gate.accept(android.os.SystemClock.elapsedRealtime(),action)) handleVoiceKey(action)
        }
        return true
    }
    private fun showDiagnostics() {
        val items = arrayOf("功能状态汇总", "车辆GPS检测（60秒）", "导航测试标记", "音频/触摸/Siri结果标记", "声音通道短音测试（先停止连接）", "与原项目功能核对", "方向盘按键检测（15秒）", "CAN车速与原始挡位检测（60秒）", "配置已核对挡位编号")
        AlertDialog.Builder(this).setTitle("功能诊断 · v0.2.36-api17.7-public")
            .setItems(items) { _, index -> when (index) {
                0 -> diagnosticSummary()
                1 -> gpsProbe.start()
                2 -> markNavigation()
                3 -> markResults()
                4 -> chooseAudioChannel()
                5 -> showFeatureParity()
                7 -> {
                    speedProbe?.stop()
                    val probe=LegacyVehicleSpeed(this,"诊断",::log); speedProbe=probe; probe.start()
                    log("CAN车速检测60秒：记录有效计数、读取异常及原始挡位；不记录坐标；结束后导出日志")
                    diagnosticHandler.postDelayed({ probe.stop(); if(speedProbe===probe) speedProbe=null; if(!destroyed) log("CAN车速检测结束，请导出日志") },60000)
                }
                8 -> configureVehicleGears()
                6 -> {
                    VoiceKeyJournal.captureUntil = android.os.SystemClock.elapsedRealtime() + 15000
                    controls.visibility = View.GONE
                    log("方向盘按键检测已开始：15秒内按语音按钮短按/长按；只记录键值，不记录输入文字")
                    diagnosticHandler.postDelayed({ if (!destroyed) { VoiceKeyJournal.drain().forEach(::log); log("方向盘按键检测结束，请导出日志") } }, 15000)
                }
            } }.setNegativeButton("取消", null).show()
    }
    private fun configureVehicleGears() {
        val prefs=getSharedPreferences("receiver-ui",0)
        val input=EditText(this).apply { setText(prefs.getString("vehicleGearMap","")); hint="P,R,N,D"; inputType=android.text.InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("输入已实测的P、R、N、D编号")
            .setMessage("按顺序输入四个不同编号，以逗号分隔。留空禁用独立PASCD；无映射时仍可在GPS定位中提供CAN车速。保存后重连。")
            .setView(input).setPositiveButton("保存") { _,_ ->
                val value=input.text.toString().trim()
                if(value.isEmpty() || VehicleSpeedPolicy.gearMap(value).isNotEmpty()) {
                    prefs.edit().putString("vehicleGearMap",value).commit(); log("车辆挡位映射保存；启用=${value.isNotEmpty()}；下次连接生效")
                } else log("挡位映射无效，未保存：需要四个不同的0–255编号")
            }.setNegativeButton("取消",null).show()
    }
    private fun showFeatureParity() {
        try {
            val contents = assets.open("feature-parity.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val text = TextView(this).apply { this.text = contents; textSize = 16f; setPadding(20, 16, 20, 16) }
            val scroll = ScrollView(this).apply { addView(text) }
            AlertDialog.Builder(this).setTitle("功能核对 · v0.2.36-api17.7-public")
                .setView(scroll).setPositiveButton("关闭", null).show()
            log("查看功能核对：这是版本能力说明，不是当前会话测试结果")
        } catch (error: Exception) { log("功能核对读取失败：${error.javaClass.simpleName}") }
    }
    private fun markNavigation() {
        val labels = arrayOf("车机停留图标桌面", "准备第一次手机开始导航", "准备在车机打开百度", "已打开车机百度并同步", "百度打开后返回图标桌面", "准备第二次手机开始导航", "导航测试结束")
        AlertDialog.Builder(this).setTitle("记录当前测试步骤（不会启动导航）")
            .setItems(labels) { _, index -> log("用户测试标记：${labels[index]}"); controls.visibility = View.GONE }
            .setNegativeButton("取消", null).show()
    }
    private fun markResults() {
        val labels = arrayOf("音乐有声音", "音乐无声音", "导航播报有声音", "导航播报无声音", "只有音乐有声", "只有播报有声", "声音来自车机扬声器", "声音来自手机", "短音测试听到", "短音测试没听到", "触摸有响应", "触摸无响应", "Siri有响应", "Siri无响应", "通话双向正常", "通话听不到或对方听不到", "画面流畅", "画面卡顿", "打开软件自动连接成功", "打开软件自动连接失败")
        AlertDialog.Builder(this).setTitle("记录人工观察（不会调整音频或车辆设置）")
            .setItems(labels) { _, index -> log("用户结果标记：${labels[index]}"); diagnosticSummary(); controls.visibility = View.GONE }
            .setNegativeButton("取消", null).show()
    }
    private fun chooseAudioChannel() {
        if (controller != null || transitioning || selfTesting.get()) { log("请先停止CarPlay连接并等待释放，再测试声音通道"); return }
        val names = arrayOf("音乐通道 STREAM_MUSIC", "通话通道 STREAM_VOICE_CALL", "通知通道 STREAM_NOTIFICATION")
        val streams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_NOTIFICATION)
        AlertDialog.Builder(this).setTitle("播放半秒短音，保持当前音量和选路")
            .setItems(names) { _, index -> testAudioChannel(streams[index]) }
            .setNegativeButton("取消", null).show()
    }
    private fun testAudioChannel(stream: Int) {
        if (controller != null || !LegacyWiredService.isTransportStopped() || transitioning || selfTesting.get()) {
            Toast.makeText(this,tr("请先停止连接，再试听声音通道","Stop the connection before previewing audio","أوقف الاتصال قبل اختبار الصوت"),Toast.LENGTH_SHORT).show(); return
        }
        if (!toneTesting.compareAndSet(false, true)) return
        diagnosticSummary()
        Thread({
            var output: AudioTrack? = null
            try {
                val rate = 44100
                val minimum = AudioTrack.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                val track = AudioTrack(stream, rate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 8192), AudioTrack.MODE_STREAM)
                output = track; check(track.state == AudioTrack.STATE_INITIALIZED)
                val pcm = ByteArray(rate)
                for (sample in 0 until rate / 2) {
                    val value = (Math.sin(sample * 2.0 * Math.PI * 660.0 / rate) * 1200).toInt()
                    pcm[sample * 2] = value.toByte(); pcm[sample * 2 + 1] = (value shr 8).toByte()
                }
                val manager = getSystemService(AUDIO_SERVICE) as AudioManager
                log("短音测试开始：${AudioDiagnostics.androidStreamName(stream)}($stream)；音量=${manager.getStreamVolume(stream)}/${manager.getStreamMaxVolume(stream)}；44100Hz/单声道/半秒；未修改音量、mode或SCO")
                track.play()
                var bytes = 0
                while (!destroyed && bytes < pcm.size) {
                    val count = track.write(pcm, bytes, minOf(4096, pcm.size - bytes)); check(count > 0); bytes += count
                }
                Thread.sleep(600)
                log("短音测试结果：${AudioDiagnostics.androidStreamName(stream)}($stream)；写入字节=$bytes；请标记听到/没听到及声音来源")
            } catch (error: Exception) { log("短音测试失败：${AudioDiagnostics.androidStreamName(stream)}；${error.javaClass.simpleName}") }
            finally { try { output?.stop() } catch (_: Exception) {}; try { output?.release() } catch (_: Exception) {}; toneTesting.set(false) }
        }, "legacy-channel-test").apply { isDaemon = true; start() }
    }
    private fun autoConnect() {
        if (destroyed || controller != null || sessionService?.hasConnectionRequest == true || !LegacyWiredService.isTransportStopped() || selfTesting.get()) return
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) { log("自动连接需要先开启蓝牙"); return }
        val phones = adapter.bondedDevices.toList()
        val saved = getSharedPreferences("receiver-ui", MODE_PRIVATE).getString("phoneAddress", null)
        val remembered = phones.firstOrNull { it.address == saved }
        val candidates = phones.filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
        val phone = remembered ?: candidates.singleOrNull() ?: phones.singleOrNull()
        if (phone != null) { log("自动连接已保存或唯一的手机"); startPhone(phone) }
        else if (phones.isNotEmpty()) { log("首次使用请选择 iPhone，以后打开应用将自动连接"); choosePhone() }
        else log("请先在车机上配对 iPhone")
    }
    private fun microphonePermissionGranted(): Boolean = packageManager.checkPermission(
        android.Manifest.permission.RECORD_AUDIO, packageName) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun startPhone(phone: BluetoothDevice) {
        if (toneTesting.get()) { log("请等待声音通道短音结束再连接"); return }
        getSharedPreferences("receiver-ui", MODE_PRIVATE).edit().putString("phoneAddress", phone.address).commit()
        val service = sessionService ?: run { log("Session service starting"); return }
        showProjection()
        awaitingProjection=true
        log("显示准备：无线连接开始前已显示投屏页；不等待首帧才隐藏首页")
        LegacyWiredService.requestStop {
            if(!destroyed) {
                service.connect(phone)
                sessionMicrophoneEnabled = microphone.isChecked && microphonePermissionGranted()
                log("Session start requested; options apply on reconnect")
            }
        }
    }
    private fun startUsb() {
        if(transitioning) { log("USB正在释放，请等待停止完成后再连接");return }
        if(selfTesting.get()) {log("请等待音视频自检完成");return}
        val saved=ReceiverSettings.read(this)
        setProjectionSize(saved.display())
        video.select(saved.videoOutput)
        showProjection() // USB uses its own service; retain the surface while it negotiates.
        LegacyWiredService.setVideoSurface(video.targetSurface)
        val permission = android.net.VpnService.prepare(this)
        if(permission != null) startActivityForResult(permission, 43)
        else LegacyWiredService.requestStart(this)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==44) {
            if(pendingBatch && resultCode==RESULT_OK) {pendingBatch=false;blackScreenSuite.begin()}
            else {pendingBatch=false;log("批量排查未开始：未允许系统VPN")}
        }
        if(requestCode==43 && resultCode==RESULT_OK) LegacyWiredService.requestStart(this)
    }
    private fun choosePhone() {
        if (selfTesting.get()) { log("请等待自检完成"); return }
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            check(adapter != null && adapter.isEnabled) { "请先开启蓝牙并配对 iPhone" }
            val phones = adapter.bondedDevices.toList()
            check(phones.isNotEmpty()) { "没有已配对设备" }
            AlertDialog.Builder(this).setTitle("选择已配对的 iPhone（手机保持解锁）")
                .setItems(phones.map { it.name ?: "蓝牙设备" }.toTypedArray()) { _, index ->
                    startPhone(phones[index])
                }.setNegativeButton("取消", null).show()
        } catch (e: Exception) { log(e.message ?: e.javaClass.simpleName) }
    }
    private var stopRequestNumber=0
    private fun stopReceiver(afterStopped: (() -> Unit)? = null) {
        touchQueue.clear(); transitioning=true
        val request=++stopRequestNumber
        var remaining=2
        fun stopped() {
            remaining--
            if(remaining==0 && !destroyed && request==stopRequestNumber) {
                transitioning=false
                log("停止完成：USB/VPN 与无线会话已释放，可重新连接")
                afterStopped?.invoke()
            }
        }
        LegacyWiredService.requestStop { stopped() }
        testSink?.close(); testSink=null
        val service=sessionService
        if(service!=null) service.disconnect { stopped() } else stopped()
        diagnosticHandler.postDelayed({
            if(!destroyed && request==stopRequestNumber && transitioning) {
                log("USB停止仍在等待，暂不启动新连接；请导出本地日志定位释放步骤")
            }
        },15000)
    }
    private fun attachVideoSurface(force:Boolean,reason:String) {
        val target=video.targetSurface
        surfaceWidth=video.width;surfaceHeight=video.height
        sessionService?.setSurface(target,force); testSink?.setSurface(target,force)
        LegacyWiredService.setVideoSurface(target,force)
        log("视频显示挂接：原因=$reason；Surface有效=${target!=null}；强制恢复=$force；会话激活=${controller?.activeSession!=null || LegacyWiredService.instance?.activeSession!=null}")
    }
    override fun onResume() {
        super.onResume()
        if(::video.isInitialized) {
            if(controller?.activeSession!=null || LegacyWiredService.instance?.activeSession!=null) showProjection()
            else attachVideoSurface(true,"应用回到前台")
        }
    }
    private fun hideSystemBars() {
        if(!ReceiverSettings.read(this).fullScreen) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_VISIBLE; return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // v0.2.94: volume bars/dialogs must not detach a still-valid video target.
        if(::video.isInitialized) log("视频窗口焦点=$hasFocus；保留有效显示目标=${video.targetSurface!=null}；mode=${video.mode}")
        if (hasFocus && ::controls.isInitialized && controls.visibility != View.VISIBLE) hideSystemBars()
    }
    private fun selfTest() {
        if(blackScreenSuite.running || controller!=null || !LegacyWiredService.isTransportStopped() || transitioning) {
            Toast.makeText(this,"请先停止连接并等待释放，再运行音视频自检",Toast.LENGTH_LONG).show();return
        }
        video.select(ReceiverSettings.read(this).videoOutput)
        showProjection()
        video.post {waitForSelfTestSurface(android.os.SystemClock.elapsedRealtime()+3000)}
    }
    private fun waitForSelfTestSurface(deadline:Long) {
        if(destroyed || selfTesting.get()) return
        if(video.targetSurface!=null) { runSelfTest();return }
        if(android.os.SystemClock.elapsedRealtime()>=deadline) {log("自检未开始：显示目标3秒内未就绪，mode=${video.mode}");return}
        diagnosticHandler.postDelayed({waitForSelfTestSurface(deadline)},100)
    }
    private fun runSelfTest() {
        if (toneTesting.get()) { log("请等待声音通道短音结束再自检"); return }
        if (transitioning) { log("请等待旧连接释放完成"); return }
        if (controller != null) { log("请先停止连接，再运行自检"); return }
        if (!(video.targetSurface!=null) || !selfTesting.compareAndSet(false, true)) return
        val decoded = AtomicInteger()
        val selfSettings=ReceiverSettings.read(this)
        val display=selfSettings.display()
        setProjectionSize(display)
        val media = LegacyMediaSink(::log, { decoded.incrementAndGet() },display.widthPixels,display.heightPixels,selfSettings,this)
        testSink = media; media.setSurface(video.targetSurface)
        Thread({
            val extractor = MediaExtractor()
            try {
                log("检查加密和上游认证素材")
                val directory = File(filesDir, "offline-mfi").apply { mkdirs() }
                listOf("identity.pk8", "certificate.p7b").forEach { name -> assets.open("offline-mfi/$name").use { input -> File(directory, name).outputStream().use { input.copyTo(it) } } }
                val auth = com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient.load(directory)
                check(auth.signChallenge(ByteArray(32) { it.toByte() }).size == 64)
                val key = ByteArray(32) { (it + 1).toByte() }; val nonce = ByteArray(12); val plaintext = "API18 crypto test".toByteArray()
                val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, ByteArray(0))
                check(AirPlayCrypto.chachaOpen(key, nonce, sealed, ByteArray(0)).contentEquals(plaintext))
                log("本地证书/签名/ChaCha 自检通过（未验证 iPhone 信任）")
                val fd = assets.openFd("h264-test.mp4")
                try { extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) } finally { fd.close() }
                val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "video/avc" }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                fun raw(name: String): ByteArray {
                    val buffer = format.getByteBuffer(name).duplicate(); val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                    val offset = if (bytes.size > 4 && bytes.take(4) == listOf<Byte>(0, 0, 0, 1)) 4 else if (bytes.size > 3 && bytes.take(3) == listOf<Byte>(0, 0, 1)) 3 else 0
                    return bytes.copyOfRange(offset, bytes.size)
                }
                val sps = raw("csd-0"); val pps = raw("csd-1")
                val avcc = byteArrayOf(1, sps[1], sps[2], sps[3], 0xff.toByte(), 0xe1.toByte(), (sps.size shr 8).toByte(), sps.size.toByte()) + sps +
                    byteArrayOf(1, (pps.size shr 8).toByte(), pps.size.toByte()) + pps
                media.onVideoCodec(110, VideoCodec.H264); media.onVideoConfig(110, avcc)
                val buffer = ByteBuffer.allocate(1024 * 1024)
                var count = 0
                while (!destroyed) {
                    buffer.clear(); val size = extractor.readSampleData(buffer, 0); if (size < 0) break
                    buffer.position(0); val bytes = ByteArray(size); buffer.get(bytes)
                    media.onVideoFrame(110, bytes); extractor.advance(); count++; Thread.sleep(34)
                }
                Thread.sleep(300)
                log("视频自检送入 $count 帧，已释放显示缓冲 ${decoded.get()} 个；请确认彩色画面")
                val id = AudioStreamId(100, "media")
                val audioFormat = com.shilapi.xcertplay.airplay.AudioFormat(AudioCodecKind.LPCM, 44100, 2, 100)
                media.onAudioStarted(id, audioFormat, 0)
                for (packet in 0 until 50) {
                    if (destroyed) break
                    val bytes = ByteArray(12 + 441 * 4)
                    for (sample in 0 until 441) {
                        val value = (Math.sin((packet * 441 + sample) * 2.0 * Math.PI * 440.0 / 44100) * 1800).toInt()
                        for (channel in 0..1) { val offset = 12 + sample * 4 + channel * 2; bytes[offset] = (value shr 8).toByte(); bytes[offset + 1] = value.toByte() }
                    }
                    media.onAudioRtp(id, audioFormat, bytes, packet * 441); Thread.sleep(10)
                }
                Thread.sleep(300); media.onAudioStopped(id)
                val aac = MediaExtractor()
                try {
                    val audioFd = assets.openFd("aac-test.m4a")
                    try { aac.setDataSource(audioFd.fileDescriptor, audioFd.startOffset, audioFd.length) } finally { audioFd.close() }
                    val audioTrack = (0 until aac.trackCount).first { aac.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
                    aac.selectTrack(audioTrack)
                    val aacId = AudioStreamId(102, "media")
                    val aacFormat = com.shilapi.xcertplay.airplay.AudioFormat(AudioCodecKind.AAC_LC, 48000, 2, 102)
                    media.onAudioStarted(aacId, aacFormat, 0)
                    var aacPackets = 0
                    while (!destroyed) {
                        buffer.clear(); val size = aac.readSampleData(buffer, 0); if (size < 0) break
                        val payload = ByteArray(12 + size); buffer.position(0); buffer.get(payload, 12, size)
                        val sample = (aac.sampleTime * 48000L / 1_000_000L).toInt()
                        media.onAudioRtp(aacId, aacFormat, payload, sample)
                        aac.advance(); aacPackets++; Thread.sleep(22)
                    }
                    Thread.sleep(300); media.onAudioStopped(aacId)
                    log("AAC 自检送入 $aacPackets 个音频包，请确认第二段较高提示音")
                } finally { aac.release() }
                log("自检数据发送结束，请确认彩色画面和一秒提示音；这不代表 CarPlay 已连接")
            } catch (e: Exception) { log("自检失败：${e.javaClass.simpleName}；${e.message?.take(120)}") }
            finally {
                extractor.release();media.close();if(testSink===media)testSink=null;selfTesting.set(false)
                saveDiagnosticReport("音视频本地自检（不代表CarPlay成功）\n"+media.diagnosticSummary()+"\n"+reportContent(),"音视频自检，等待用户确认")
                runOnUiThread { if(!destroyed) AlertDialog.Builder(this).setTitle("自检时看到彩色画面了吗？")
                    .setItems(arrayOf("看到了彩色画面","仍然黑屏","没有注意，结果未知")) { _,index ->
                        val result=listOf("用户确认本地彩色画面可见","用户确认本地自检黑屏","本地自检可见性未知")[index]
                        log(result);saveDiagnosticReport(result+"\n"+reportContent(),"自检画面确认")
                    }.setNegativeButton("稍后确认",null).show() }
            }
        }, "legacy-self-test").apply { isDaemon = true; start() }
    }
    private fun showReport() {
        val content = reportContent()
        val text = TextView(this).apply { this.text = content; setPadding(12, 12, 12, 12); setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this).setTitle("接收端日志").setView(scroll).setPositiveButton("关闭", null).show()
    }
    private fun setProjectionSize(display:com.shilapi.xcertplay.airplay.AirPlayDisplayConfig) {
        displayWidth=display.widthPixels;displayHeight=display.heightPixels
        if(::videoBox.isInitialized) videoBox.requestLayout()
    }
    private fun showDisplaySettings() {
        ProjectionDisplaySettingsUi(this,::log,::applySavedSettings).show()
    }
    private fun applySavedSettings() {
        if(blackScreenSuite.running || selfTesting.get() || toneTesting.get()) {
            Toast.makeText(this,"请先停止排查或等待自检结束，再应用显示设置",Toast.LENGTH_LONG).show();return
        }
        updateSteeringService(getSharedPreferences("receiver-ui",MODE_PRIVATE).getBoolean("steeringVoice",false))
        hideSystemBars()
        log("用户应用已保存设置：将停止旧会话并重新连接USB")
        stopReceiver {
            diagnosticHandler.postDelayed({ if(!destroyed) startUsb() },1500)
        }
    }
    private fun reportContent():String = DeviceVideoEvidence.snapshot(this)+"\n"+LegacyScreenCrypto.summary()+
        "\n"+synchronized(report) { report.toString() }+"\nBackground session:\n"+(sessionService?.report() ?: "")+
        "\nWired session:\n"+(LegacyWiredService.instance?.report() ?: ("Previous USB evidence:\n"+TransportEvidence.readPrevious(File(filesDir,"usb-transport-evidence.txt"))))
    private fun saveDiagnosticReport(text:String,description:String) {
        try {
            val safe=DiagnosticRedactor.clean(text)
            val archive=File(filesDir,"diagnostic-reports").apply { mkdirs() }
            val file=File(archive,"${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.txt")
            file.writeText(safe,Charsets.UTF_8)
            archive.listFiles()?.filter { it.name.endsWith(".txt") }?.sortedByDescending { it.name }?.drop(20)?.forEach { it.delete() }
            log("本地报告已保存：$description")
        } catch(error:Exception) { log("本地报告保存异常：${error.javaClass.simpleName}；${error.message?.take(100)}") }
    }
    private fun confirmBlackScreenSuite() {
        if(blackScreenSuite.running) { Toast.makeText(this,"正在排查，可点上方停止",Toast.LENGTH_SHORT).show();return }
        if(selfTesting.get() || toneTesting.get()) { log("请等待自检或短音测试结束");return }
        AlertDialog.Builder(this).setTitle("USB黑屏批量排查（停车后进行）")
            .setMessage("依次尝试4种视频方案，约5分钟，每轮会断开并重新连接。手机保持解锁，按提示允许USB、VPN和信任。看到画面请点上方确认。每轮在应用内保存脱敏报告。完成后使用“导出”保存到车机存储。完成或停止后恢复原设置。")
            .setPositiveButton("开始并保存到本地") { _,_->
                val permission=android.net.VpnService.prepare(this)
                if(permission!=null) { pendingBatch=true;startActivityForResult(permission,44) } else blackScreenSuite.begin()
            }.setNegativeButton("取消",null).show()
    }
    private fun chooseVideoTrial() {
        if(blackScreenSuite.running) { log("请先停止批量排查");return }
        AlertDialog.Builder(this).setTitle("选择画面方案（应用并重新连接）")
            .setItems(BlackScreenDiagnostics.trials.map { it.title }.toTypedArray()) { _,index ->
                val trial=BlackScreenDiagnostics.trials[index]
                stopReceiver {
                    getSharedPreferences("receiver-ui",0).edit().putInt("width",trial.width).putInt("height",trial.height)
                        .putInt("fps",trial.fps).putInt("scale",10).putBoolean("hevc",false)
                        .putString("decoderPolicy",trial.decoder).putString("videoOutput",trial.output).commit()
                    log("用户选择画面方案=${trial.id}；设置已保存，将重新连接USB")
                    diagnosticHandler.postDelayed({ if(!destroyed) startUsb() },1500)
                }
            }.setNegativeButton("取消",null).show()
    }
    private fun exportReport() {
        log(SystemServiceDiagnostics.snapshot(this))
        try {
            val folder=getSharedPreferences("receiver-ui",MODE_PRIVATE).getString("reportDirectory","DiPlayLegacy") ?: "DiPlayLegacy"
            require(ReportFolderPolicy.valid(folder))
            val root=Environment.getExternalStorageDirectory().canonicalFile
            val directory = File(root, folder).canonicalFile
            require(directory.path.startsWith(root.path+File.separator))
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create report folder" }
            val file = File(directory, "receiver-${System.currentTimeMillis()}.txt")
            file.writeText(DiagnosticRedactor.clean(reportContent()), Charsets.UTF_8)
            var archived=0
            File(filesDir,"diagnostic-reports").listFiles()?.filter { it.name.endsWith(".txt") }?.forEach { saved ->
                File(directory,"diagnostic-${saved.name}").writeText(DiagnosticRedactor.clean(saved.readText(Charsets.UTF_8)),Charsets.UTF_8)
                archived++
            }
            log("日志已保存：${file.absolutePath}；已同时导出 $archived 份本地诊断报告")
        } catch (e: Exception) { log("保存失败：${e.javaClass.simpleName}") }
    }
    override fun onStop() {
        super.onStop()
        if(!ReceiverSettings.read(this).background && !isChangingConfigurations) {
            sessionService?.disconnect()
            LegacyWiredService.requestStop()
        }
    }
    override fun onDestroy() {
        pendingBatch=false
        if(blackScreenSuite.running) blackScreenSuite.cancel()
        if(LegacyWiredService.connectionEndedObserver===usbDetached) LegacyWiredService.connectionEndedObserver=null
        if(bluetoothEventsRegistered) { unregisterReceiver(bluetoothEvents); bluetoothEventsRegistered=false }
        sessionService?.observer=null; sessionService?.frameObserver=null; sessionService?.setSurface(null); testSink?.setSurface(null); LegacyWiredService.setVideoSurface(null)
        if(!ReceiverSettings.read(this).background) { sessionService?.disconnect(); LegacyWiredService.requestStop() }
        if(bound) unbindService(serviceConnection)
        testSink?.close()
        speedProbe?.stop(); speedProbe=null; if (::gpsProbe.isInitialized) gpsProbe.close(); diagnosticHandler.removeCallbacks(diagnosticTick); destroyed = true; siriControl.close(); touchThread?.interrupt(); super.onDestroy() }
}

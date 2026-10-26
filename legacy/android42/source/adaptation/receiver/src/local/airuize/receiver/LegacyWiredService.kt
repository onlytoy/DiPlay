package local.airuize.receiver

import android.app.Notification
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.*
import com.shilapi.xcertplay.network.Ipv6NcmBridge
import java.io.Closeable
import java.io.File
import java.net.*
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Upstream USBMUX -> Lockdown -> carkit iAP2, with NCM -> IPv6 VPN AirPlay. */
class LegacyWiredService : VpnService() {
    @Volatile var activeSession:AirPlaySession?=null; private set
    @Volatile var projectionDisplay:AirPlayDisplayConfig?=null; private set
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor { Thread(it,"legacy-wired-lifecycle").apply { isDaemon=true } }
    private val running=AtomicBoolean(false)
    private val generation=java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var resources=WiredResourceScope(::log)
    @Volatile private var stopping=false
    @Volatile private var idle=true
    private var destroyed=false
    private val stoppedCallbacks=ArrayList<()->Unit>()
    private lateinit var usb:UsbManager
    @Volatile private var media:LegacyMediaSink?=null
    @Volatile private var surface:Surface?=null
    private var selectedName:String?=null
    private var detaching=false
    private var pendingPermission:String?=null
    private var denied=false
    private var transitionDevice:String?=null
    private var transitionTime=0L
    private val nextFrame=AtomicBoolean(true)
    private val logHistory=StringBuilder("USB CarPlay 0.2.36-api17.7-public\n")
    private val evidence=TransportEvidence()
    private var previousEvidence=""
    private val evidenceDropped=java.util.concurrent.atomic.AtomicLong()
    private var lastEvidenceWrite=0L // Accessed only by the evidence worker.
    private val evidenceWriter=java.util.concurrent.ThreadPoolExecutor(1,1,0L,java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(256),
        java.util.concurrent.ThreadFactory { Thread(it,"legacy-usb-evidence").apply { isDaemon=true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
    private var evidenceFile:File?=null
    fun report():String = synchronized(logHistory) { logHistory.toString() }+
        resources.summary()+"\nUSB lifecycle: running=${running.get()} stopping=$stopping idle=$idle\n"+
        "\nRetained USB structural/audio evidence (current service):\n"+evidence.snapshot()+
        "\nPrevious service USB evidence (may be a different session):\n"+previousEvidence
    fun videoEvidence():String=media?.diagnosticSummary() ?: "视频接收器尚未创建"
    fun videoCounts():LongArray=media?.diagnostics?.let { longArrayOf(it.videoReceived.get(),it.decoderStarts.get(),it.videoRendered.get()) } ?: longArrayOf(0,0,0)
    private fun retain(message:String) {
        if(!TransportEvidence.relevant(message)) return
        // Real-time callers only enqueue. Sanitizing while receiving UDP/USB delayed audio.
        val timestamp=android.os.SystemClock.elapsedRealtime()
        val bounded=if(message.length<=12000) message else "Diagnostic message omitted: over size limit"
        try { evidenceWriter.execute {
            evidence.append("t=${timestamp}ms $bounded")
            val dropped=evidenceDropped.getAndSet(0)
            if(dropped>0) evidence.append("Diagnostic queue overflow: omitted=$dropped")
            val target=evidenceFile ?: return@execute
            val now=android.os.SystemClock.elapsedRealtime()
            val urgent=bounded.contains("failure",true) || bounded.contains("ended",true) || bounded.contains("closed",true)
            if(lastEvidenceWrite!=0L && now-lastEvidenceWrite<5000 && !urgent) return@execute
            try {
                // One writer; atomic replacement keeps the last complete snapshot on restart.
                val temporary=File(target.parentFile,target.name+".tmp")
                temporary.writeText(evidence.snapshot(),Charsets.UTF_8)
                if(!temporary.renameTo(target)) temporary.delete()
                lastEvidenceWrite=now
            } catch(_:Exception) { /* Export still includes in-memory evidence if storage is unavailable. */ }
        } } catch(_:java.util.concurrent.RejectedExecutionException) { evidenceDropped.incrementAndGet() }
    }
    private fun log(message:String) { retain(message); synchronized(logHistory) { logHistory.append(message).append('\n'); if(logHistory.length>60000) logHistory.delete(0,12000) }; LegacySessionService.instance?.log(message) }
    fun setSurface(value:Surface?,force:Boolean=false) { if((surface !== value || force) && value != null) nextFrame.set(true); surface=value; media?.setSurface(value,force) }
    private fun <T:Closeable> own(value:T, expected:Int=generation.get(), name:String=value.javaClass.simpleName, abort:Boolean=false):T {
        if(!running.get() || expected!=generation.get()) { value.close();error("USB stopped") }
        return resources.own(value,name,abort)
    }
    private val poll=object:Runnable { override fun run() {
        if(!running.get()) return
        if(!stopping && selectedName==null && !denied) discover()
        media?.let { log(it.diagnosticSummary()) }
        log(VideoDiagnosticGate.summary())
        log(AudioDiagnosticDispatcher.summary())
        main.postDelayed(this,5000)
    } }
    private val receiver=object:BroadcastReceiver() { override fun onReceive(context:Context,intent:Intent) {
        val device=intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        if(device.vendorId!=0x05ac) return
        when(intent.action) {
            permissionAction -> { pendingPermission=null
                if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false) && usb.hasPermission(device)) queue(device)
                else { denied=true; log("USB permission denied; reconnect cable or select USB to retry") }
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> if(!stopping && selectedName==null && !denied) discover()
            UsbManager.ACTION_USB_DEVICE_DETACHED -> if(device.deviceName==selectedName && !detaching) {
                log("USB detached")
                endTransport("USB detached")
            }
        }
    } }
    private val permissionAction get()="$packageName.USB_PERMISSION"
    override fun onCreate() {
        super.onCreate()
        UsbReceiveDiagnostics.depth=UsbReceiveDiagnostics.sanitize(getSharedPreferences("receiver-ui",0).getInt("usbInRequests",8))
        VideoDiagnosticGate.reset()
        evidenceFile=File(filesDir,"usb-transport-evidence.txt")
        previousEvidence=TransportEvidence.readPrevious(evidenceFile!!)
        evidence.append("DiPlay 0.2.36-api17.7-public USB evidence; elapsed timestamps; structural metadata only")
        instance=this; surface=videoSurface; usb=getSystemService(USB_SERVICE) as UsbManager
        registerReceiver(receiver,IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) })
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.getIntExtra("USB_COMMAND",-1)!=commandNumber.get()) {
            log("USB start cancelled: a newer stop/start command superseded this request")
            return START_NOT_STICKY
        }
        if(stopping || retiring?.let { !it.idle }==true) {
            log("USB start refused: previous shutdown is still pending; "+resources.summary())
            return START_NOT_STICKY
        }
        if(resources.closeFailed) { log("USB start refused: "+resources.summary());return START_NOT_STICKY }
        idle=false
        if(!running.compareAndSet(false,true)) { denied=false; discover(); return START_NOT_STICKY }
        if(prepare(this)!=null) { log("USB requires system VPN consent"); running.set(false);idle=true;stopSelf(); return START_NOT_STICKY }
        LegacySessionService.instance?.disconnect()
        val open=PendingIntent.getActivity(this,0,Intent(this,ReceiverActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(44,Notification.Builder(this).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("DiPlay USB CarPlay").setContentText("USB / NCM session").setContentIntent(open).setOngoing(true).build())
        log("Firmware path: USB Host / descriptor configuration / NCM NTB16 / IPv6 TUN")
        main.post(poll); return START_NOT_STICKY
    }
    private fun discover() {
        if(!running.get() || stopping) return
        val devices=usb.deviceList.values.filter { it.vendorId==0x05ac }
        log("USB enumeration: hostFeature=${packageManager.hasSystemFeature("android.hardware.usb.host")} total=${usb.deviceList.size} apple=${devices.size}")
        val device=devices.firstOrNull() ?: return
        if(!usb.hasPermission(device)) {
            if(pendingPermission==device.deviceName) return
            pendingPermission=device.deviceName
            usb.requestPermission(device,PendingIntent.getBroadcast(this,0,Intent(permissionAction).setPackage(packageName),PendingIntent.FLAG_UPDATE_CURRENT))
            log("USB permission requested"); return
        }
        queue(device)
    }
    private fun queue(device:UsbDevice) {
        if(!running.get() || stopping || selectedName!=null) return
        if(resources.closeFailed) { log("USB reconnect blocked: "+resources.summary());return }
        selectedName=device.deviceName
        resources=WiredResourceScope(::log)
        val scope=resources
        val sessionSettings=ReceiverSettings.read(this)
        projectionDisplay=sessionSettings.display()
        log("USB selected projection: ${projectionDisplay?.widthPixels}x${projectionDisplay?.heightPixels} fps=${projectionDisplay?.fps}; saved parameters frozen for this connection")
        val id=generation.incrementAndGet()
        worker.execute {
            if(id!=generation.get() || !running.get()) return@execute
            try { startStack(device,id,scope,sessionSettings) }
            catch(error:Exception) {
                if(id==generation.get() && running.get()) { log("USB stage failed: ${error.javaClass.simpleName} reason=${error.message?.take(200)}"); FailureDiagnostics.lines("USB",error).forEach(::log) }
                cleanup(scope); if(id==generation.get()) selectedName=null
            }
        }
    }
    private fun startStack(device:UsbDevice,id:Int,scope:WiredResourceScope,settings:ReceiverSettings) {
        val first=usb.openDevice(device) ?: error("USB open failed")
        // Own the USBMUX descriptor before any configuration, claim or protocol handshake can block.
        val descriptorClosed=AtomicBoolean(false)
        val releaseDescriptor={ if(descriptorClosed.compareAndSet(false,true)) first.close();Unit }
        scope.own(Closeable { releaseDescriptor() },"USBMUX descriptor",true)
        try {
        val power=getSystemService(POWER_SERVICE) as android.os.PowerManager
        val wake=power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"DiPlay:USB").apply { setReferenceCounted(false); acquire() }
        own(Closeable { if(wake.isHeld) wake.release() },id,"wake lock")
            val descriptors=LegacyUsbPlatform.configurations(first)
            val configuration=IphoneCarPlayConfiguration.find(descriptors)
            log("USB descriptors configurations=${descriptors.map { it.id }} CarPlay=${configuration?.id}")
            if(configuration==null) {
                val now=android.os.SystemClock.elapsedRealtime()
                if(transitionDevice==device.deviceName && now-transitionTime<30000) { log("USB waiting for CarPlay re-enumeration"); return }
                transitionDevice=device.deviceName; transitionTime=now
                val response=ByteArray(1)
                val result=first.controlTransfer(0xc0,0x52,0,4,response,1,1000)
                log("USB Apple vendor transition request result=$result; waiting for re-enumeration")
                check(result==response.size) { "USB vendor transition transferred $result of ${response.size} bytes" }
                return
            }
            if(!LegacyUsbPlatform.selectConfiguration(first,configuration.id,::log)) {
                // Match upstream: a reported configuration failure is logged, but
                // interface claim remains the decisive host-side availability test.
                log("USB configuration selection reported failure; attempting USBMUX claim as upstream")
            }
            val muxInterface=IphoneCarPlayConfiguration.usbMuxInterface(configuration) ?: error("USBMUX interface absent")
            val endpoints=IphoneCarPlayConfiguration.usbMuxEndpoints(muxInterface) ?: error("USBMUX endpoints absent")
            log("USBMUX claim: config=${configuration.id} interface=${muxInterface.id} alternate=${LegacyUsbPlatform.alternate(muxInterface)} endpoints=${muxInterface.endpointCount}")
            check(first.claimInterface(muxInterface,true)) { "USBMUX claim failed (config=${configuration.id}, interface=${muxInterface.id}); possible kernel or OEM interface ownership" }
            log("USB configuration=${configuration.id}; USBMUX interface=${muxInterface.id}; bulk endpoints selected")
            val pipe=own(Iap2UsbSession(first,endpoints.first,endpoints.second,releaseDescriptor),id,"USBMUX pipe",true)
            val ncmConnection=usb.openDevice(device) ?: error("NCM open failed")
            val ncmLock=Any()
            var ncmBridge:NcmUsbBridge?=null
            var ncmClosed=false
            own(Closeable { synchronized(ncmLock) {
                ncmClosed=true
                // UsbRequest cancellation/release must finish before freeing its native device.
                val bridge=ncmBridge
                if(bridge==null) ncmConnection.close() else bridge.close()
            } },id,"NCM transport (cancel requests then close descriptor)",true)
            UsbReceiveDiagnostics.depth=UsbReceiveDiagnostics.sanitize(getSharedPreferences("receiver-ui",0).getInt("usbInRequests",8))
            val function=NcmFunctionDiscovery.find(configuration)
            if(function==null) error("CDC NCM function absent")
            log("NCM requested: control=${function.control.id}/${LegacyUsbPlatform.alternate(function.control)} data=${function.data.id}/${LegacyUsbPlatform.alternate(function.data)}")
            log(UsbLinkSpeed.read(device.deviceName,device.vendorId,device.productId))
            val ncm=synchronized(ncmLock) {
                check(!ncmClosed) { "NCM stopped before open" }
                NcmUsbBridge.open(ncmConnection,function,::log).also { ncmBridge=it }
            }
            log("NCM interfaces claimed; data alternate setting selected")
            val mux=own(Iap2UsbMuxHost.open(pipe),id,"USBMUX host",true); log("USBMUX ready")
            val prefs=getSharedPreferences("receiver-usb",MODE_PRIVATE)
            val hostId=prefs.getString("hostId",null) ?: UUID.randomUUID().toString().toUpperCase(Locale.US).also { prefs.edit().putString("hostId",it).commit() }
            val buid=prefs.getString("buid",null) ?: UUID.randomUUID().toString().toUpperCase(Locale.US).also { prefs.edit().putString("buid",it).commit() }
            log("Lockdown TLS backend: BC lightweight TLS1.2; dedicated USB pairing identity; system TLS unchanged")
            log("Lockdown pairing: unlock iPhone and confirm Trust if prompted")
            val records=LegacyPairRecords(this)
            fun pair():LockdownPairRecord = LockdownPairingClient(mux).pair("DiPlay Legacy",hostId,buid,120000) { !running.get() || id!=generation.get() }.pairRecord.also { records.save(it) }
            val saved=records.load()
            val pairRecord=saved ?: pair()
            val carkit=own(try { LockdownCarKitClient(mux).open(pairRecord,"DiPlay Legacy") } catch(error:IphoneUsbException) {
                // Upstream retries only explicit saved-record rejection, never generic TLS/I/O failure.
                val rejected=listOf("InvalidHostID", "InvalidPairRecord", "InvalidPairingRecord", "MissingPairRecord").any { error.message?.contains(it)==true }
                if(saved==null || !rejected) throw error
                records.clear(); log("Saved USB pair record rejected; pairing again")
                LockdownCarKitClient(mux).open(pair(),"DiPlay Legacy")
            },id,"Lockdown carkit"); log("Lockdown carkit ready")
            val iap=own(Iap2Session.open(carkit),id,"iAP2 session"); log("Wired iAP2 link ready")
            val display=settings.display(); nextFrame.set(true)
            val hostMac=ncm.hostMac ?: byteArrayOf(2,0,0,0,0,43)
            val literal="fe80::2"
            val tun=own(Builder().addAddress(literal,64).addRoute("fe80::",64).setSession("DiPlay USB").setMtu(1500).establish() ?: error("VPN TUN rejected"),id,"VPN TUN",true)
            val directAudio=own(DirectAudioRouter.open(InetAddress.getByName(literal),getSharedPreferences("receiver-ui",0).getBoolean("usbDirectAudio",true)))
            log("USB audio ingress mode=${if(directAudio.enabled)"direct-with-socket-fallback" else "socket"}; authentication unchanged")
            own(KernelAudioDiagnostics(android.os.Process.myUid(),::log))
            own(Ipv6NcmBridge(ncm,tun,hostMac, { error -> log("NCM IPv6 failure=${error.javaClass.simpleName} reason=${error.message?.take(200)}"); FailureDiagnostics.lines("NCM",error).forEach(::log); main.post { if(id==generation.get()) endTransport("NCM failure") } }, ::log,directAudio)).start()
            log("NCM IPv6 TUN established (SDK17 nonblocking mode)")
            val identity=LegacyWirelessController.loadIdentity(this)
            val authDir=File(filesDir,"offline-mfi").apply { mkdirs() }
            for(name in listOf("identity.pk8","certificate.p7b")) assets.open("offline-mfi/$name").use { input -> File(authDir,name).outputStream().use { input.copyTo(it) } }
            val mfi=LocalMfiAuthenticationClient.load(authDir)
            val deviceId=hostMac.joinToString(":") { "%02X".format(it.toInt() and 255) }
            val config=AirPlayConfig("DiPlay Legacy",deviceId,android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.address ?: deviceId,"950.7.1",display,
                rightHandDrive=settings.rightHandDrive, hevc=settings.hevc&&CodecCapabilities.hevc(), opus=settings.opus&&CodecCapabilities.opus(),microphone=settings.microphone,icons=LegacyOem.icons(),oemLabel="DiPlay",manufacturer="DiPlay Legacy",model="Android42-USB")
            val bindAddress=LegacyBindAddress.wired(literal,{ !running.get() || id!=generation.get() },::log)
            val sink=own(LegacyMediaSink(::log,{ if(nextFrame.compareAndSet(true,false)) { log("USB first video frame rendered"); LegacySessionService.instance?.frameObserver?.invoke() } },display.widthPixels,display.heightPixels,settings,this,bindAddress)); media=sink; sink.setSurface(surface)
            val engine=CarPlayMediaEngine(sink,microphoneEnabled=settings.microphone)
            val pairPrefs=getSharedPreferences("receiver-pairings",MODE_PRIVATE)
            val pairings=PairingStore { id,key -> pairPrefs.edit().putString(id,android.util.Base64.encodeToString(key,2)).commit() }
            pairPrefs.all.forEach { (id,value)-> if(value is String) runCatching { pairings.save(id,android.util.Base64.decode(value,2)) } }
            val server=ServerSocket()
            own(Closeable { server.close() },id,"AirPlay listener",true) // Own before bind: failure must release the socket too.
            server.reuseAddress=true
            server.bind(InetSocketAddress(bindAddress,config.port))
            log("USB AirPlay listener ready: scope=${bindAddress.scopeId} port=${server.localPort}")
            val acceptor=Thread({ try { while(running.get() && id==generation.get()) {
                val socket=server.accept(); socket.tcpNoDelay=true
                own(Closeable { socket.close() },id,"AirPlay socket",true)
                own(AirPlaySession(socket,config,identity,pairings,mfi,object:AirPlaySessionListener {
                    override fun onSessionActive(session:AirPlaySession) { if(id==generation.get()) { activeSession=session; log("USB AirPlay active") } }
                    override fun onSessionEnded(session:AirPlaySession) {
                        val wasActive=activeSession===session
                        if(wasActive) activeSession=null
                        log("USB AirPlay ended")
                        if(wasActive) main.post { if(id==generation.get() && activeSession==null && !detaching) connectionEndedObserver?.invoke() }
                    }
                    override fun onDebugLog(message:String) { AirPlayDiagnostics.summary(message)?.let(::log) }
                    override fun onHostUiRequested(session:AirPlaySession) { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    override fun onControlFailure(stage:String,error:Throwable) { log("USB AirPlay failure stage=$stage class=${error.javaClass.simpleName}") }
                },engine),id).start()
            } } catch(error:Exception) { if(running.get()) log("USB AirPlay listener ended ${error.javaClass.simpleName}") } },"legacy-wired-airplay").apply { isDaemon=true }
            own(Closeable { if(Thread.currentThread()!==acceptor) acceptor.join() },id,"AirPlay accept worker")
            acceptor.start()
            val location=if(settings.gps) own(LegacyVehicleLocationProvider(this,"USB",::log)) else null
            val identification=Iap2IdentificationConfig("DiPlay Legacy","Android42-USB","DiPlay Legacy",identity.pairingId,"0.2.36-api17.7-public","iMX6", carPlayUsbInterfaceNumber=muxInterface.id).copy(locationInformationEnabled=settings.gps,vehicleSpeedEnabled=settings.gps && VehicleSpeedPolicy.gearMap(getSharedPreferences("receiver-ui",0).getString("vehicleGearMap", "") ?: "").isNotEmpty())
            val endpoint=Iap2WiredCarPlayEndpoint(listOf(literal),config.port,identity.publicKeyHex,config.sourceVersion,deviceId)
            log("Wired iAP2 authentication / StartCarPlaySession")
            Iap2WiredControlClient(iap,Iap2MfiAuthenticationClient(mfi)).run(identification,endpoint,availableCurrentMilliAmps=500,
                timeoutMillis=Iap2WiredControlClient.NO_TIMEOUT_MILLIS,locationProvider=location,onProgress={ log(FailureDiagnostics.progress(it)) })
        } finally {
            cleanup(scope); if(id==generation.get()) selectedName=null
        }
    }
    /** Stop explicitly: Android may keep a VpnService bound after stopService(). */
    private fun shutdown(reason:String,keepListening:Boolean=false,after:(()->Unit)?=null) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { shutdown(reason,keepListening,after) };return }
        after?.let { stoppedCallbacks.add(it) }
        if(stopping) {
            if(!keepListening) running.set(false)
            return
        }
        if(idle) { deliverStopped();return }
        stopping=true;detaching=true
        if(!keepListening) running.set(false)
        generation.incrementAndGet();main.removeCallbacks(poll)
        val scope=resources
        media?.let { log("USB final media: "+it.diagnosticSummary()) }
        activeSession=null
        log("USB stop requested: $reason; explicitly releasing TUN and USB")
        val watchdog=object:Runnable { override fun run() {
            if(!stopping || resources!==scope) return
            log(scope.summary()+"; waiting for USB lifecycle worker")
            main.postDelayed(this,5000)
        } }
        main.postDelayed(watchdog,5000)
        Thread({
            cleanup(scope)
            // This marker can only run after startStack and any late constructor have returned.
            worker.execute {
                main.post {
                    if(!scope.released) {
                        log("USB stop incomplete: resource close failed; reconnect is blocked; "+scope.summary())
                        return@post
                    }
                    main.removeCallbacks(watchdog)
                    selectedName=null;pendingPermission=null;denied=false
                    media=null;activeSession=null;stopping=false;detaching=false
                    idle=!running.get()
                    log("USB stop complete: descriptors released and lifecycle worker drained")
                    if(idle) stopForeground(true)
                    if(destroyed) {
                        worker.shutdown();evidenceWriter.shutdown()
                        if(instance===this) instance=null
                        if(retiring===this) retiring=null
                    } else if(running.get()) main.postDelayed(poll,500)
                    deliverStopped()
                }
            }
        },"legacy-usb-cleanup").apply { isDaemon=true;start() }
    }
    private fun deliverStopped() {
        val callbacks=stoppedCallbacks.toList();stoppedCallbacks.clear()
        callbacks.forEach { runCatching { it() } }
    }
    private fun endTransport(reason:String) {
        if(detaching || !running.get()) return
        log("USB connection ended: $reason; returning to application UI")
        shutdown(reason,keepListening=true)
        connectionEndedObserver?.invoke()
    }
    private fun cleanup(scope:WiredResourceScope) { scope.close() }
    override fun onRevoke() { log("VPN permission revoked");shutdown("VPN revoked");super.onRevoke() }
    override fun onDestroy() {
        destroyed=true;retiring=this
        unregisterReceiver(receiver)
        if(idle) {
            worker.shutdown();evidenceWriter.shutdown()
            if(instance===this) instance=null
            if(retiring===this) retiring=null
        } else shutdown("service destroyed")
        super.onDestroy()
    }
    companion object {
        @Volatile var instance:LegacyWiredService?=null
        @Volatile private var retiring:LegacyWiredService?=null
        fun isTransportStopped():Boolean = (instance?.idle!=false) && (retiring?.idle!=false)
        private val commandNumber=java.util.concurrent.atomic.AtomicInteger()
        fun requestStart(context:Context) {
            val command=commandNumber.incrementAndGet()
            context.startService(Intent(context,LegacyWiredService::class.java).putExtra("USB_COMMAND",command))
        }
        fun requestStop(after:(()->Unit)?=null) {
            commandNumber.incrementAndGet() // Cancels a start intent whose service has not been created yet.
            val service=instance ?: retiring
            if(service==null) after?.invoke() else service.shutdown("user/application stop",after=after)
        }
        @Volatile var connectionEndedObserver:(()->Unit)?=null
        @Volatile private var videoSurface:Surface?=null
        fun setVideoSurface(value:Surface?,force:Boolean=false) { videoSurface=value; instance?.setSurface(value,force) }
    }
}

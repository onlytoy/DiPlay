package local.airuize.receiver

import android.app.Activity
import android.app.AlertDialog
import android.widget.*

/** Saved choices and running protocol parameters stay separate until explicit USB reconnection. */
class ProjectionDisplaySettingsUi(private val activity:Activity,private val log:(String)->Unit,
    private val reconnect:()->Unit) {
    private val p=activity.getSharedPreferences("receiver-ui",0)
    private val strings=LegacyStrings(activity)
    private fun tr(zh:String,en:String)=strings.translate(zh,en,en)
    private lateinit var summary:TextView
    private lateinit var resolution:Button
    private lateinit var rate:Button
    private lateinit var density:Button
    private val screen=HeadUnitDisplayInfo.capture(activity)
    private fun saved()=ReceiverSettings.read(activity)
    private fun save(value:ReceiverSettings):Boolean = try {DisplayPreferences.save(p,value);true} catch(error:Exception) {
        Toast.makeText(activity,tr("显示设置保存失败，请检查存储空间","Could not save display settings; check storage"),Toast.LENGTH_LONG).show()
        log("显示设置保存失败：${error.javaClass.simpleName}");false
    }
    private fun refresh() {
        val s=saved();val d=s.display()
        summary.text=tr("系统报告的屏幕","System-reported screen")+"：${screen.width}×${screen.height}\n"+
            tr("应用可用区域","Available app area")+"：${screen.availableWidth}×${screen.availableHeight}\n"+
            tr("已保存投屏参数（下次连接）","Saved projection (next connection)")+"：${d.widthPixels}×${d.heightPixels} / ${d.fps}fps\n"+
            "${s.videoOutput} / ${s.decoderPolicy}；DPI=${screen.densityDpi}"
        LegacyWiredService.instance?.projectionDisplay?.takeIf {!LegacyWiredService.isTransportStopped()}?.let {
            summary.append("\n"+tr("本轮连接参数","This connection")+"：${it.widthPixels}×${it.heightPixels} / ${it.fps}fps")
        }
        resolution.text=tr("投屏分辨率","Projection resolution")+" · ${s.width}×${s.height}"
        rate.text=tr("帧率","Frame rate")+" · ${s.fps}fps"
        density.text=tr("布局参考尺寸（实验性）","Layout reference size (experimental)")+" · ${s.physicalMm}mm"
    }
    private fun button(box:LinearLayout,title:String,action:()->Unit):Button=Button(activity).apply {
        text=title;setAllCaps(false);setOnClickListener {action()};box.addView(this,LinearLayout.LayoutParams(-1,-2))
    }
    fun show() {
        if(BlackScreenDiagnostics.current!=null) {
            Toast.makeText(activity,tr("请先停止四轮排查，再修改日常显示设置","Stop the comparison before changing saved display settings"),Toast.LENGTH_LONG).show();return
        }
        val box=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(20,12,20,12)}
        summary=TextView(activity).apply {textSize=17f;setPadding(0,0,0,12);box.addView(this)}
        box.addView(TextView(activity).apply {text=tr("选择后保存在车机，覆盖升级和下次启动都会保留；重新连接后生效，当前会话保持原参数。检测值来自系统，不保证等同于液晶面板原生规格。","Choices persist across app restarts and updates. Reconnect to apply; the current session retains its parameters. Screen dimensions are reported by the firmware.")})
        resolution=button(box,"") {chooseResolution()}
        rate=button(box,"") {chooseRate()}
        density=button(box,"") {chooseDensity()}
        box.addView(TextView(activity).apply {text=tr("可先选择屏幕匹配方案；15fps可用于低负载对照。更高像素不保证图标一定变小，CarPlay布局还由 iPhone 和参考尺寸决定。","Try the detected screen size. 15fps offers a lower frame-rate comparison. Pixel resolution alone does not determine icon size; iPhone also uses the reported physical size.");setPadding(0,12,0,12)})
        button(box,tr("恢复 A 方案 · 800×480 / 30fps","Restore A · 800×480 / 30fps")) {
            val restored=ProjectionDisplayPolicy.restore(saved())
            if(save(restored)) {
                log("显示设置已保存：恢复A，800×480/30fps/Surface；重新连接生效")
                refresh()
            }
        }
        refresh()
        AlertDialog.Builder(activity).setTitle(tr("屏幕检测与投屏大小","Screen detection and projection size"))
            .setView(ScrollView(activity).apply {addView(box)})
            .setPositiveButton(tr("应用并 USB 重连","Apply and reconnect USB")) {_,_->reconnect()}
            .setNegativeButton(tr("保存，稍后重连","Saved; reconnect later"),null).show()
    }
    private fun chooseResolution() {
        val s=saved();val current=ProjectionResolution(s.width,s.height)
        val options=ProjectionDisplayPolicy.options(screen.width,screen.height,current)
        val matching=ProjectionDisplayPolicy.matchingScreen(screen.width,screen.height)
        val labels=options.map {value -> value.label+when {
            value==matching->tr(" · 屏幕匹配"," · screen match")
            value==ProjectionDisplayPolicy.fallback->tr(" · 已验证 A 尺寸"," · verified A dimensions")
            value.width>screen.width || value.height>screen.height->tr("（实验性）"," (experimental)")
            else->""
        }}
        var selected=options.indexOf(current)
        AlertDialog.Builder(activity).setTitle(tr("投屏分辨率","Projection resolution"))
            .setSingleChoiceItems(labels.toTypedArray(),selected) {_,index->selected=index}
            .setPositiveButton(tr("保存，下次连接生效","Save for next connection")) {_,_->
                if(selected>=0) {
                    val changed=options[selected].applyTo(saved())
                    if(save(changed)) {
                        log("显示分辨率已保存：${changed.width}×${changed.height}，像素比例100%；重新连接生效")
                        refresh()
                    }
                }
            }.setNegativeButton(tr("取消","Cancel"),null).show()
    }
    private fun chooseRate() {
        val options=(listOf(15,30,60)+saved().fps).distinct().sorted()
        AlertDialog.Builder(activity).setTitle(tr("帧率（重新连接生效）","Frame rate (reconnect required)"))
            .setSingleChoiceItems(options.map {"$it fps"}.toTypedArray(),options.indexOf(saved().fps)) {dialog,index->
                if(save(saved().copy(fps=options[index]))) {
                    dialog.dismiss();refresh();log("显示帧率已保存：${options[index]}fps；重新连接生效")
                }
            }.setNegativeButton(tr("取消","Cancel"),null).show()
    }
    private fun chooseDensity() {
        val options=(listOf(150,200,250,300)+saved().physicalMm).distinct().sorted()
        val box=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(16,8,16,8)}
        box.addView(TextView(activity).apply {text=tr("这是向 iPhone 报告的屏幕参考宽度，较大的值可尝试显示更多内容。实际布局由 iPhone 决定，需要重新连接观察；不会改车机系统字体。","This reports a reference screen width to iPhone. A larger value can be tried for more content; actual layout is decided by iPhone. Reconnect to compare.")})
        val choices=RadioGroup(activity)
        options.forEachIndexed {index,value->choices.addView(RadioButton(activity).apply {id=index+100;text="$value mm";isChecked=value==saved().physicalMm&&!saved().physicalHeight})}
        box.addView(choices)
        AlertDialog.Builder(activity).setTitle(tr("布局参考尺寸（实验性）","Layout reference size (experimental)"))
            .setView(ScrollView(activity).apply {addView(box)})
            .setPositiveButton(tr("保存，下次连接生效","Save for next connection")) {_,_->
                val index=choices.checkedRadioButtonId-100
                if(index in options.indices) {
                    if(save(saved().copy(physicalMm=options[index],physicalHeight=false))) {
                        refresh();log("CarPlay布局参考宽度已保存：${options[index]}mm；重新连接生效，实际布局待观察")
                    }
                }
            }.setNegativeButton(tr("取消","Cancel"),null).show()
    }
}

/** Same persistence used by the advanced settings and the USB service. */
object DisplayPreferences {
    fun save(p:android.content.SharedPreferences,s:ReceiverSettings) {
        check(p.edit().putInt("width",s.width).putInt("height",s.height).putInt("scale",s.scale).putInt("fps",s.fps)
            .putInt("physicalMm",s.physicalMm).putBoolean("physicalHeight",s.physicalHeight)
            .putString("videoOutput",s.videoOutput).putString("decoderPolicy",s.decoderPolicy).putBoolean("hevc",s.hevc).commit()) {"Display settings save failed"}
    }
}

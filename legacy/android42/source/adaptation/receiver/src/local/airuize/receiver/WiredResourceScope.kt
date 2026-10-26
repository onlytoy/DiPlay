package local.airuize.receiver

import java.io.Closeable
import java.util.concurrent.CountDownLatch

/** One session owns one scope. Completion means close calls returned, not merely list.clear(). */
class WiredResourceScope(private val report:(String)->Unit = {}) : Closeable {
    private data class Entry(val name:String,val value:Closeable,val abort:Boolean)
    private val lock=Any()
    private val entries=ArrayList<Entry>()
    private val pending=LinkedHashSet<String>()
    private val failed=ArrayList<String>()
    private val done=CountDownLatch(1)
    private var nextId=0
    private var closing=false
    private var owner:Thread?=null
    val closeFailed:Boolean get()=synchronized(lock) { failed.isNotEmpty() }
    val released:Boolean get()=synchronized(lock) { done.count==0L && pending.isEmpty() && failed.isEmpty() }
    fun summary():String=synchronized(lock) {
        "USB cleanup: closing=$closing completed=${done.count==0L} released=$released pending=${pending.joinToString(",")} failed=${failed.joinToString(",")}"
    }
    fun <T:Closeable> own(value:T,name:String,abort:Boolean=false):T {
        val entry:Entry
        val accepted=synchronized(lock) {
            entry=Entry("$name#${++nextId}",value,abort)
            if(closing) { pending.add(entry.name);false } else { entries.add(entry);true }
        }
        if(!accepted) {
            // A constructor can finish after stop was requested. Never publish that resource.
            // Retain close failures here too; the lifecycle worker barrier waits for this call.
            closeEntry(entry)
            error("USB session stopped during $name creation")
        }
        return value
    }
    override fun close() {
        val snapshot=synchronized(lock) {
            if(closing) {
                // Reentrant close must not wait on itself.
                if(owner===Thread.currentThread()) return
                null
            } else {
                closing=true;owner=Thread.currentThread()
                entries.toList().asReversed().also { list ->
                    entries.clear();pending.addAll(list.map { it.name })
                }
            }
        }
        if(snapshot==null) { await(done);return }
        try {
            // Closing TUN/USB and aborting USBMUX wakes blocked readers/writers before TLS/FIN.
            // Independent aborts also let VPN release even if an OEM USB close itself stalls.
            val aborts=snapshot.filter { it.abort }.map { entry ->
                Thread({ closeEntry(entry) },"legacy-usb-abort").apply { isDaemon=true;start() }
            }
            for(thread in aborts) {
                var interrupted=false
                while(thread.isAlive) try { thread.join() } catch(_:InterruptedException) { interrupted=true }
                if(interrupted) Thread.currentThread().interrupt()
            }
            snapshot.filterNot { it.abort }.forEach(::closeEntry)
        } finally { done.countDown();safeReport(summary()) }
    }
    private fun closeEntry(entry:Entry) {
        val started=System.nanoTime()
        safeReport("USB cleanup begin: ${entry.name}")
        try { entry.value.close() }
        catch(error:Exception) {
            synchronized(lock) { failed.add(entry.name) }
            safeReport("USB cleanup failure: ${entry.name} ${error.javaClass.simpleName}")
        } finally {
            synchronized(lock) { pending.remove(entry.name) }
            safeReport("USB cleanup ended: ${entry.name} elapsedMs=${(System.nanoTime()-started)/1000000}")
        }
    }
    private fun safeReport(message:String) { runCatching { report(message) } }
    private fun await(latch:CountDownLatch) {
        var interrupted=false
        while(latch.count!=0L) try { latch.await() } catch(_:InterruptedException) { interrupted=true }
        if(interrupted) Thread.currentThread().interrupt()
    }
}

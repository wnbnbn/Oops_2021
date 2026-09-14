import com.localfeed.app.core.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock

fun main() {
    check(InboxWork.begin()); check(!InboxWork.begin())
    try { error("test") } catch(_:IllegalStateException) {} finally { InboxWork.end() }
    check(InboxWork.begin()); InboxWork.end()
    val queue=InboxBatchQueue<Int>()
    repeat(40) { queue.offer("$it",it) }; queue.offer("0",99)
    check(queue.drain(16).first()==99)
    check(queue.drain(16).size==16); check(queue.drain(16).size==8); check(queue.isEmpty())
    val producers=(0..3).map { Thread { repeat(200) { queue.offer("$it",it) } }.apply { start() } }
    producers.forEach { it.join(2000); check(!it.isAlive) }
    val values=mutableListOf<Int>()
    while(!queue.isEmpty()) values+=queue.drain(16)
    check(values.size==200 && values.toSet().size==200)
    val inside=AtomicInteger(); val operations=AtomicInteger()
    val writers=(0..3).map { Thread {
        repeat(100) { InboxWork.storageLock.withLock {
            check(inside.incrementAndGet()==1); operations.incrementAndGet()
            check(inside.decrementAndGet()==0)
        } }
    }.apply { start() } }
    writers.forEach { it.join(2000); check(!it.isAlive) }
    check(operations.get()==400)
    // A waiting index task is admitted between consecutive transfers by the fair shared lock.
    val order=mutableListOf<String>(); val ready=CountDownLatch(1)
    val lock=InboxWork.storageLock
    lock.lock()
    val index=Thread { ready.countDown(); lock.withLock { order+="index" } }
    val transfer=Thread { lock.withLock { order+="transfer" } }
    try {
        index.start(); check(ready.await(2,java.util.concurrent.TimeUnit.SECONDS))
        val deadline=System.nanoTime()+2_000_000_000L
        while(!lock.hasQueuedThread(index) && System.nanoTime()<deadline) Thread.yield()
        check(lock.hasQueuedThread(index)); transfer.start()
    } finally { lock.unlock() }
    index.join(2000); transfer.join(2000)
    check(!index.isAlive && !transfer.isAlive)
    check(order==listOf("index","transfer"))
    println("Inbox scheduling, coalesced publication, single-flight and fair storage lock: PASS")
}

package nodescala

import nodescala.NodeScala._
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import scala.async.Async.async
import scala.collection._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

@RunWith(classOf[JUnitRunner])
class NodeScalaSuite extends FunSuite {

  test("A Future should always be completed") {
    val always = Future.always(517)

    assert(Await.result(always, 0 nanos) == 517)
  }
  test("A Future should never be completed") {
    val never = Future.never[Int]

    try {
      Await.result(never, 300 millis)
      assert(false)
    } catch {
      case t: TimeoutException => // ok!
    }
  }
  test("Future.all preserves order") {
    val ans = (1 to 10).toList
    val res = ans.map(i => Future.always(i))

    assert(Await.result(Future.all(res), 300 millis) == ans)
  }
  test("Future.any returns first completed") {
    val ans = (1 to 5).toList
    val res = Future.never :: ans.map(i => Future.always(i))

    assert(ans.contains(Await.result(Future.any(res), 300 millis)))
  }

  test("Future.any fails if any error") {
    val res = Future {
      throw new Exception
    } :: Future.never[Int] :: Nil

    intercept[Exception] {
      Await.result(Future.any(res), 300 millis)
    }
  }

  test("Future.delay waits correct amount of time") {
    val delay = (500 millis)
    val start = System.currentTimeMillis
    Await.result(Future.delay(delay), delay + (100 millis))
    val dt = System.currentTimeMillis - start
    assert(dt >= delay.toMillis)
  }

  test("Future.delay combined delays") {
    val delay = (500 millis)
    val combined = for {
      f1 <- Future.delay(delay)
      f2 <- Future.delay(delay)
    } yield ()

    val start = System.currentTimeMillis
    Await.result(combined, (2 * delay) + (100 millis))
    val dt = System.currentTimeMillis - start
    assert(dt >= (2 * delay).toMillis)
  }

  test("Future.now available result") {
    val f = Future.always(0)
    assert(f.now == 0)
  }

  test("Future.now throws NoSuchElementException when result is unavailable") {
    val f = Future.never[Int]
    intercept[NoSuchElementException] {
      f.now
    }
  }

  test("Future.continueWith returns cont's value") {
    val f1 = Future.always(1)
    val f2 = (f: Future[Int]) => f.value.get.map(_ + 1).getOrElse(throw new Error)

    val r = Await.result(f1.continueWith(f2), 10 millis)
    assert(r == 2)
  }

  test("Future.continueWith should wait for the first future to complete") {
    val delay = Future.never
    val always = (f: Future[Nothing]) => 1

    intercept[TimeoutException] {
      Await.result(delay.continueWith(always), 10 millis)
      assert(false)
    }
  }

  test("Future.continue returns cont's value") {
    val f1 = Future.always(1)
    val f2 = (f: Try[Int]) => f.map(_ + 1).getOrElse(throw new Error)

    val r = Await.result(f1.continue(f2), 10 millis)
    assert(r == 2)
  }

  test("Future.continue should wait for the first future to complete") {
    val delay = Future.never
    val always = (f: Try[Nothing]) => 1

    intercept[TimeoutException] {
      Await.result(delay.continue(always), 10 millis)
      assert(false)
    }
  }

  test("Future.run completes when not cancelled") {
    val p = Promise[String]()
    Future.run() { ct =>
      async {
        if (ct.nonCancelled) {
          p.success("done")
        }
      }
    }

    val r = Await.result(p.future, 1 millis)
    assert(r == "done")
  }

  test("Future.run should allow cancellation") {
    val p = Promise[String]()

    val token = Future.run() { ct =>
      async {
        while (ct.nonCancelled) {
        }
        p.success("cancelled")
      }
    }
    token.unsubscribe()
    assert(Await.result(p.future, 1 milli) == "cancelled")
  }

  test("CancellationTokenSource should allow stopping the computation") {
    val cts = CancellationTokenSource()
    val ct = cts.cancellationToken
    val p = Promise[String]()

    async {
      while (ct.nonCancelled) {
        // do work
      }

      p.success("done")
    }

    cts.unsubscribe()
    assert(Await.result(p.future, 1 second) == "done")
  }

  class DummyExchange(val request: Request) extends Exchange {
    val loaded = Promise[String]()
    @volatile var response = ""

    def write(s: String) {
      response += s
    }

    def close() {
      loaded.success(response)
    }
  }

  class DummyListener(val port: Int, val relativePath: String) extends NodeScala.Listener {
    self =>

    var handler: Exchange => Unit = null
    @volatile private var started = false

    def createContext(h: Exchange => Unit) = this.synchronized {
      assert(started, "is server started?")
      handler = h
    }

    def removeContext() = this.synchronized {
      assert(started, "is server started?")
      handler = null
    }

    def start() = self.synchronized {
      started = true
      new Subscription {
        def unsubscribe() = self.synchronized {
          started = false
        }
      }
    }

    def emit(req: Request) = {
      val exchange = new DummyExchange(req)
      if (handler != null) handler(exchange)
      exchange
    }
  }

  class DummyServer(val port: Int) extends NodeScala {
    self =>
    val listeners = mutable.Map[String, DummyListener]()

    def createListener(relativePath: String) = {
      val l = new DummyListener(port, relativePath)
      listeners(relativePath) = l
      l
    }

    def emit(relativePath: String, req: Request) = this.synchronized {
      val l = listeners(relativePath)
      l.emit(req)
    }
  }

  test("Server should serve requests") {
    val dummy = new DummyServer(8191)
    val dummySubscription = dummy.start("/testDir") {
      request => for (kv <- request.iterator) yield (kv + "\n").toString
    }

    // wait until server is really installed
    Thread.sleep(500)

    def test(req: Request) {
      val webpage = dummy.emit("/testDir", req)
      val content = Await.result(webpage.loaded.future, 1 second)
      val expected = (for (kv <- req.iterator) yield (kv + "\n").toString).mkString
      assert(content == expected, s"'$content' vs. '$expected'")
    }

    test(immutable.Map("StrangeRequest" -> List("Does it work?")))
    test(immutable.Map("StrangeRequest" -> List("It works!")))
    test(immutable.Map("WorksForThree" -> List("Always works. Trust me.")))

    dummySubscription.unsubscribe()
  }

}





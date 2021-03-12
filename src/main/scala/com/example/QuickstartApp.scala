package com.example

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route

import scala.util.Failure
import scala.util.Success
import akka.stream.scaladsl.Flow
import java.util.UUID
import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Sink
import scala.concurrent.Promise
import java.util.Observable
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import akka.stream.scaladsl.Keep
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import scala.concurrent.ExecutionContext

//#main-class
object QuickstartApp {
  //#start-http-server
  private def startHttpServer(
      routes: Route
  )(implicit system: ActorSystem[_]): Unit = {
    // Akka HTTP still needs a classic ActorSystem to start
    import system.executionContext

    val futureBinding = Http().newServerAt("localhost", 8080).bind(routes)
    futureBinding.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info(
          "Server online at http://{}:{}/",
          address.getHostString,
          address.getPort
        )
      case Failure(ex) =>
        system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }
  }

  val counter: Flow[Unit, Int, NotUsed] =
    Flow[Unit]
      .scan(0) { case (count, ()) => count + 1 }

  def sinkLatest[T](implicit ec: ExecutionContext): Sink[T, () => Future[T]] = {
    Sink
      .lazySink { () =>
        val latestValue = new AtomicReference[T]()
        Sink
          .foreach { x: T =>
            latestValue.set(x)
          }
          .mapMaterializedValue(_ => () => latestValue.get())
      }
      .mapMaterializedValue(futGet => () => futGet.map(_()))
  }

  //#start-http-server
  def main(args: Array[String]): Unit = {
    //#server-bootstrapping

    val rootBehavior = Behaviors.setup[Nothing] { context =>
      implicit val system: ActorSystem[_] = context.system
      import system.executionContext

      val countSample = new AtomicReference(0)

      val publisher = new Publisher[Int]() {
        override def subscribe(s: Subscriber[_ >: Int <: Object]): Unit = ???
      }
      val (queue, sampleCounter) = Source
        .queue(10)
        .via(counter)
        .toMat(sinkLatest)(Keep.both)
        .run()

      def incrementCounter(): Unit = queue.offer(())

      val userRegistryActor = context.spawn(UserRegistry(), "UserRegistryActor")
      context.watch(userRegistryActor)

      val routes = new UserRoutes(
        userRegistryActor,
        incrementCounter,
        sampleCounter
      )
      startHttpServer(routes.userRoutes)

      Behaviors.empty
    }
    val system = ActorSystem[Nothing](rootBehavior, "HelloAkkaHttpServer")
    //#server-bootstrapping
  }
}
//#main-class

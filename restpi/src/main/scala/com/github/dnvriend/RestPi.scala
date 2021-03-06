/*
 * Copyright 2016 Dennis Vriend
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.dnvriend

import akka.actor.ActorSystem
import akka.event.{ Logging, LoggingAdapter }
import akka.http.scaladsl._
import akka.http.scaladsl.common.{ EntityStreamingSupport, JsonEntityStreamingSupport }
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.{ Directives, Route }
import akka.stream.scaladsl.{ Flow, Source }
import akka.stream.{ ActorMaterializer, Materializer }
import akka.util.ByteString
import com.github.dnvriend.spark.CalculatePi
import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession
import spray.json.DefaultJsonProtocol

import scala.concurrent.{ ExecutionContext, Future }

object RestPi extends App with Directives with SprayJsonSupport with DefaultJsonProtocol {
  implicit val system: ActorSystem = ActorSystem()
  implicit val mat: Materializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val log: LoggingAdapter = Logging(system, this.getClass)

  val spark = SparkSession.builder()
    .config("spark.sql.warehouse.dir", "file:/tmp/spark-warehouse")
    .config("spark.scheduler.mode", "FAIR")
    .config("spark.sql.crossJoin.enabled", "true")
    .master("local") // use as many threads as cores
    .appName("RestPi") // The appName parameter is a name for your application to show on the cluster UI.
    .getOrCreate()

  final case class Pi(pi: Double)

  implicit val piJsonFormat = jsonFormat1(Pi)
  val start = ByteString.empty
  val sep = ByteString("\n")
  val end = ByteString.empty
  implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()
    .withFramingRenderer(Flow[ByteString].intersperse(start, sep, end))
    .withParallelMarshalling(parallelism = 8, unordered = true)

  def sparkContext: SparkContext = spark.newSession().sparkContext

  def calculatePi(num: Long = 1000000, slices: Int = 2): Future[Double] =
    Future(CalculatePi(sparkContext, num, slices)).map(count => slices.toDouble * count / (num - 1))

  val route: Route =
    pathEndOrSingleSlash {
      complete(calculatePi().map(Pi))
    } ~ path("pi" / LongNumber / IntNumber) { (num, slices) =>
      complete(calculatePi(num, slices).map(Pi))
    } ~ path("stream" / "pi" / LongNumber) { num =>
      complete(Source.fromFuture(calculatePi()).map(Pi)
        .flatMapConcat(Source.repeat).take(num))
    }

  Http().bindAndHandle(route, "0.0.0.0", 8008)

  sys.addShutdownHook {
    spark.stop()
    system.terminate()
  }
}

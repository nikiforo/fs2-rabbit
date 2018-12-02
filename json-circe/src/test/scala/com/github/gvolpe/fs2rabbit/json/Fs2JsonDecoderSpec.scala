/*
 * Copyright 2017-2019 Fs2 Rabbit
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

package com.github.gvolpe.fs2rabbit.json

import cats.effect.IO
import cats.syntax.functor._
import com.github.gvolpe.fs2rabbit.model.{AmqpEnvelope, AmqpProperties, DeliveryTag, ExchangeName, RoutingKey}
import fs2._
import io.circe._
import org.scalatest.prop.PropertyChecks
import org.scalatest.{FlatSpecLike, Matchers}

import scala.concurrent.duration._

class Fs2JsonDecoderSpec extends Fs2JsonDecoderFixture with FlatSpecLike with Matchers {

  import io.circe.generic.auto._

  forAll(examples) { (description, json, decoder, expected) =>
    it should description in {
      val envelope = AmqpEnvelope(new DeliveryTag(1),
                                  json,
                                  AmqpProperties.empty,
                                  ExchangeName("test"),
                                  RoutingKey("test.route"),
                                  false)

      val test = for {
        parsed         <- Stream(envelope).covary[IO] through decoder
        (validated, _) = parsed
      } yield {
        validated should be(expected)
      }

      test.compile.drain.unsafeRunTimed(2.seconds)
    }
  }

  it should "fail decoding the wrong class" in {
    val json = """ { "two": "the two" } """
    val envelope = AmqpEnvelope(new DeliveryTag(1),
                                json,
                                AmqpProperties.empty,
                                ExchangeName("test"),
                                RoutingKey("test.route"),
                                false)

    val test = for {
      parsed         <- Stream(envelope).covary[IO] through fs2JsonDecoder.jsonDecode[Person]
      (validated, _) = parsed
    } yield {
      validated shouldBe a[Left[_, Person]]
    }

    test.compile.drain.unsafeRunTimed(2.seconds)
  }

}

object Message {
  sealed trait Message               extends Product with Serializable
  final case class One(one: String)  extends Message
  final case class Two(two: String)  extends Message
  final case class Three(three: Int) extends Message
}

trait Fs2JsonDecoderFixture extends PropertyChecks {

  import io.circe.generic.auto._
  import Message._

  val fs2JsonDecoder = new Fs2JsonDecoder[IO]
  import fs2JsonDecoder.jsonDecode

  case class Address(number: Int, streetName: String)
  case class Person(name: String, address: Address)

  implicit def msgDecoder[A >: Message]: Decoder[A] =
    List[Decoder[A]](
      Decoder[One].widen,
      Decoder[Two].widen,
      Decoder[Three].widen
    ).reduceLeft(_ or _)

  val simpleJson: String =
    """
      |{
      |  "number": 212,
      |  "streetName": "Baker St"
      |}
    """.stripMargin

  val nestedJson: String =
    """
      |{
      |  "name": "Sherlock",
      |  "address": {
      |    "number": 212,
      |    "streetName": "Baker St"
      |  }
      |}
    """.stripMargin

  val examples = Table(
    ("description", "json", "clazz", "expected"),
    ("decode a simple case class", simpleJson, jsonDecode[Address], Right(Address(212, "Baker St"))),
    ("decode a nested case class", nestedJson, jsonDecode[Person], Right(Person("Sherlock", Address(212, "Baker St")))),
    ("decode an adt 1", """ { "one": "the one" } """, jsonDecode[Message], Right(One("the one"))),
    ("decode an adt 2", """ { "two": "the two" } """, jsonDecode[Message], Right(Two("the two")))
  )

}

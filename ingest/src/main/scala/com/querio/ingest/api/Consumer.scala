/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.querio.ingest.api

import blueeyes.json.JPath
import blueeyes.json.JsonAST._
import blueeyes.json.Printer

import blueeyes.json.xschema.{ ValidatedExtraction, Extractor, Decomposer }
import blueeyes.json.xschema.Extractor._
import blueeyes.json.xschema.DefaultSerialization._
import blueeyes.json.xschema.Extractor._

import scalaz._
import Scalaz._
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CharsetEncoder
import blueeyes.json.JsonParser

import org.scalacheck.Gen._

import com.querio.ingest.util.ArbitraryIngestMessage

trait IngestMessageReceivers {
  def find(address: MailboxAddress): List[IngestMessageReceiver]
}

trait IngestMessageReceiver {
  def get(): IngestMessage 
}

object TestIngestMessageRecievers extends IngestMessageReceivers with ArbitraryIngestMessage {
  def find(address: MailboxAddress) = List(new IngestMessageReceiver() {
    def get() = genRandomEventMessage.sample.get
  })
}

case class MailboxAddress(id: Long)

abstract class IngestMessage

class IngestMessageSerialization {
  implicit val IngestMessageDecomposer: Decomposer[IngestMessage] = new Decomposer[IngestMessage] {
    override def decompose(ingestMessage: IngestMessage): JValue = ingestMessage match {
      case sm @ SyncMessage(_, _, _)  => SyncMessage.SyncMessageDecomposer.apply(sm)
      case em @ EventMessage(_, _, _) => EventMessage.EventMessageDecomposer.apply(em)
    }
  }
}

object IngestMessage extends IngestMessageSerialization


case class SyncMessage(producerId: Int, syncId: Int, eventIds: List[Int])  extends IngestMessage

class SyncMessages(producerId: Int, initialId: Int = 1) {
  
  private val nextId = new AtomicInteger(initialId)
  
  val start: SyncMessage = SyncMessage(producerId, 0, List.empty)
  def next(eventIds: List[Int]): SyncMessage = SyncMessage(producerId, nextId.getAndIncrement(), eventIds)
  def stop(eventIds: List[Int] = List.empty) = SyncMessage(producerId, Int.MaxValue, eventIds)
}

object SyncMessage extends SyncMessageSerialization {
  val start = SyncMessage(_: Int, 0, List.empty)
  def finish = SyncMessage(_: Int, Int.MaxValue, _: List[Int])
}

trait SyncMessageSerialization {
  implicit val SyncMessageDecomposer: Decomposer[SyncMessage] = new Decomposer[SyncMessage] {
    override def decompose(eventMessage: SyncMessage): JValue = JObject(
      List(
        JField("producerId", eventMessage.producerId.serialize),
        JField("syncId", eventMessage.syncId.serialize),
        JField("eventIds", eventMessage.eventIds.serialize)))
  }

  implicit val SyncMessageExtractor: Extractor[SyncMessage] = new Extractor[SyncMessage] with ValidatedExtraction[SyncMessage] {
    override def validated(obj: JValue): Validation[Error, SyncMessage] =
      ((obj \ "producerId").validated[Int] |@|
        (obj \ "syncId").validated[Int] |@|
        (obj \ "eventIds").validated[List[Int]]).apply(SyncMessage(_, _, _))
  }  
}


case class Event(path: String, token: String, content: JValue)

class EventSerialization {
  implicit val EventDecomposer: Decomposer[Event] = new Decomposer[Event] {
    override def decompose(event: Event): JValue = JObject(
      List(
        JField("path", event.path.serialize),
        JField("token", event.token.serialize),
        JField("content", event.content.serialize)))
  }

  implicit val EventExtractor: Extractor[Event] = new Extractor[Event] with ValidatedExtraction[Event] {
    override def validated(obj: JValue): Validation[Error, Event] =
      ((obj \ "path").validated[String] |@|
        (obj \ "token").validated[String] |@|
        (obj \ "content").validated[JValue]).apply(Event(_, _, _))
  }  
}

object Event extends EventSerialization


case class EventMessage(producerId: Int, eventId: Int, event: Event) extends IngestMessage

trait EventMessageSerialization {
  implicit val EventMessageDecomposer: Decomposer[EventMessage] = new Decomposer[EventMessage] {
    override def decompose(eventMessage: EventMessage): JValue = JObject(
      List(
        JField("producerId", eventMessage.producerId.serialize),
        JField("eventId", eventMessage.eventId.serialize),
        JField("event", eventMessage.event.serialize)))
  }

  implicit val EventMessageExtractor: Extractor[EventMessage] = new Extractor[EventMessage] with ValidatedExtraction[EventMessage] {
    override def validated(obj: JValue): Validation[Error, EventMessage] =
      ((obj \ "producerId").validated[Int] |@|
        (obj \ "eventId").validated[Int] |@|
        (obj \ "event").validated[Event]).apply(EventMessage(_, _, _))
  }
}

object EventMessage extends EventMessageSerialization


object IngestMessageSerialization {
  
  private val stopByte: Byte = 0x00
  private val jsonEventFlag: Byte = 0x01
  private val jsonSyncFlag: Byte = 0x02
  private val magicByte: Byte = -123
  
  def write(buffer: ByteBuffer, msg: IngestMessage) {
    (msg match {
      case SyncMessage(_, _, _)     => writeSync _
      case EventMessage(_, _, _)    => writeEvent _
    })(msg)(buffer)
  }
  
  def writeSync(msg: IngestMessage): ByteBuffer => ByteBuffer = (writeHeader(_: ByteBuffer, jsonSyncFlag)) andThen 
                                                                (writeMessage(_: ByteBuffer, msg))

  def writeEvent(msg: IngestMessage): ByteBuffer => ByteBuffer = (writeHeader(_: ByteBuffer, jsonEventFlag)) andThen 
                                                                 (writeMessage(_: ByteBuffer, msg))
  
  def writeHeader(buffer: ByteBuffer, encodingFlag: Byte): ByteBuffer = {
    buffer.put(magicByte)
    buffer.put(encodingFlag)
    buffer.put(stopByte)
    buffer
  }

  val charset = Charset.forName("UTF-8")
  
  def writeMessage(buffer: ByteBuffer, msg: IngestMessage): ByteBuffer = {
    val msgBuffer = charset.encode(Printer.compact(Printer.render(msg.serialize)))
    buffer.put(msgBuffer)
    buffer
  }
  
  def readMessage(buffer: ByteBuffer): Validation[String, IngestMessage] = {
    val magic = buffer.get()
    if(magic != magicByte) {
      failure("Invaild message bad magic byte.")
    } else {
      val msgType = buffer.get()
      val stop    = buffer.get()
      
      (stop, msgType) match {
        case (stop, flag) if stop == stopByte && flag == jsonEventFlag => parseEvent(buffer)
        case (stop, flag) if stop == stopByte && flag == jsonSyncFlag  => parseSync(buffer)
        case _                                                         => failure("Unrecognized message type")
      }
    }
  }
  
  def parseSync = (parseJValue _) andThen (jvalueToSync _)
  
  def parseEvent = (parseJValue _) andThen (jvalueToEvent _)
  
  def jvalueToSync(jvalue: JValue): Validation[String, IngestMessage] = {
    jvalue.validated[SyncMessage] match {
      case Failure(e)  => Failure(e.message)
      case Success(sm) => Success(sm)
    }
  }
  
  def jvalueToEvent(jvalue: JValue): Validation[String, IngestMessage] = {
    jvalue.validated[EventMessage] match {
      case Failure(e)  => Failure(e.message)
      case Success(em) => Success(em)
    }
  }
  
  def parseJValue(buffer: ByteBuffer): JValue = {
    val decoder = charset.newDecoder()
    val charBuffer = decoder.decode(buffer)
    JsonParser.parse(charBuffer.toString())
  }
}

package org.ccfea.tickdata.storage.hbase

import org.apache.hadoop.hbase.client.Put
import collection.JavaConversions._
import org.ccfea.tickdata.event.{EventType, Event}

/**
 * Store events in Apache HBase.
 *
 * (c) Steve Phelps 2013
 */
trait HBaseInserter extends HBaseEventConverter {

  val fields: List[String] = List("eventType", "marketSegmentCode", "currencyCode", "marketMechanismType",
    "aggregateSize", "tradeDirection", "orderCode", "tradeSize", "broadcastUpdateAction", "marketSectorCode",
    "marketMechanismGroup", "price", "singleFillInd", "matchingOrderCode", "resultingTradeCode", "tradeCode",
    "tradeTimeInd", "convertedPriceInd" )

  def store(field: String, data: Array[Byte])(implicit put: Put, timeStamp: Long) {
    put.add(dataFamily, toBytes(field), timeStamp, data)
  }

  def store(field: String, data: AnyRef)(implicit put: Put, timeStamp: Long) {
    data match {
      case Some(data) =>
        store(field, toBytes(data))
      case None =>
      // no need to store
      case data =>
        store(field, toBytes(data))
    }
  }

  implicit def convert(event: Event): Put = {
    implicit val timeStamp = event.timeStamp
    implicit val put: Put = new Put(getKey(event))
    def getField(f: String): AnyRef = classOf[Event].getMethod(f).invoke(event)
    for(field <- fields) store(field, getField(field))
    put
  }

  def insertData(parsedEvents: Seq[Event]): Int = {

    eventsTable.put(
      for(event <- parsedEvents) yield convert(event)
    )

    parsedEvents.length
  }
}
package org.ccfea.tickdata

import net.sourceforge.jabm.SimulationTime

import net.sourceforge.jasa.agent.SimpleTradingAgent

import collection.JavaConversions._

import net.sourceforge.jasa.market.Order
import net.sourceforge.jasa.market.FourHeapOrderBook

import grizzled.slf4j.Logger
import java.util.GregorianCalendar

/**
 * The state of the market at a single point in time.
 *
 * (c) Steve Phelps 2013
 */
class MarketState {

  /**
   * The current state of the book.
   */
  val book = new FourHeapOrderBook()

  /**
   * Lookup table mapping order-codes to Orders.
   */
  val orderMap = collection.mutable.Map[String, Order]()

  /**
   * The time of the most recent event.
   */
  var time: Option[SimulationTime] = None

  /**
   * The most recent transaction price.
   */
  var lastTransactionPrice: Option[Double] = None

  val logger = Logger(classOf[MarketState])

  /**
   * Update the state in response to a new incoming event.
   * @param ev  The new event
   */
  def processEvent(ev: Event) = {

    logger.debug("Processing event " + ev)

    assert(ev.timeStamp >= (time match { case None => 0; case Some(t) => t.getTicks}))


    val newTime = new SimulationTime(ev.timeStamp)

    this.time match {
      case Some(t) => if (getDay(newTime) != getDay(t)) processNewDay()
      case None =>
    }

    this.time = Some(newTime)

    ev match {

        /********************************************************************
         *        Logic for order submitted events                          *
         ********************************************************************/
      case Event(id, EventType.OrderSubmitted,
                  messageSequenceNumber, timeStamp, tiCode, marketSegmentCode, currencyCode,
                  Some(marketMechanismType), Some(aggregateSize), Some(tradeDirection), Some(orderCode),
                  None,
                  Some(broadcastUpdateAction), Some(marketSectorCode), Some(marketMechanismGroup), Some(price),
                    Some(singleFillInd),
                  None, None, None, None, None)

      => processOrderSubmission(orderCode, price, aggregateSize, tradeDirection, marketMechanismType)


        /********************************************************************
         *        Logic for order deleted (and related) events              *
         ********************************************************************/
       case Event(id, EventType.OrderDeleted | EventType.OrderExpired | EventType.TransactionLimit,
                  messageSequenceNumber, timeStamp,
                  tiCode, marketSegmentCode, marketMechanismType, currencyCode, aggregateSize, tradeDirection,
                  Some(orderCode),
                  tradeSize, broadcastUpdateAction, marketSectorCode, marketMechanismGroup, price, singleFillInd,
                  None, None, None, None, None)

      => processOrderRemoval(orderCode)


        /********************************************************************
         *        Logic for order filled events                             *
         ********************************************************************/
       case Event(id, EventType.OrderFilled,
                  messageSequenceNumber, timeStamp, tiCode,
                  marketSegmentCode, currencyCode, marketMechanismType, aggregateSize, tradeDirection,
                  Some(orderCode),
                  tradeSize, broadcastUpdateAction, marketSectorCode, marketMechanismGroup, price, singleFillInd,
                  matchingOrderCode, resultingTradeCode,
                  None, None, None)

      => processOrderFilled(orderCode)

        /********************************************************************
         *        Logic for order matched events
         ********************************************************************/
       case Event(id, EventType.OrderMatched,
                  messageSequenceNumber, timeStamp, tiCode,
                  marketSegmentCode, currencyCode, marketMechanismType, aggregateSize, tradeDirection,
                  Some(orderCode),
                  tradeSize, broadcastUpdateAction, marketSectorCode, marketMechanismGroup, price, singleFillInd,
                  matchingOrderCode, resultingTradeCode,
                  None, None, None)

      => processOrderMatched(orderCode)

      /********************************************************************
        *        Logic for transaction events                            *
        ********************************************************************/
      case Event(id, EventType.Transaction,
                messageSequenceNumber, timeStamp,
                tiCode, marketSegmentCode, currencyCode,
                None, None, None, None,
                Some(tradeSize), Some(broadcastUpdateAction),
                None, None, Some(tradePrice), None,
                None, None,
                tradeCode, Some(tradeTimeInd), Some(convertedPriceInd))

      => processTransaction(tradePrice)


      case _ => logger.warn("Do not know how to process " + ev)
    }
  }

  def processLimitOrder(order: Order) = {
    if (order.isAsk) book.insertUnmatchedAsk(order) else book.insertUnmatchedBid(order)
//    book add order
  }

  def processMarketOrder(order: Order) = {
  }

  def printState = {
    book.printState()
  }

  def midPrice: Option[Double] = {

    val quote: (Option[Order], Option[Order]) =
      (if (book.getHighestUnmatchedBid == null) None else Some(book.getHighestUnmatchedBid),
       if (book.getHighestMatchedAsk==null)     None else Some(book.getHighestMatchedAsk))

    quote match {
      case (None,      None)      => None
      case (Some(bid), None)      => Some(bid.getPrice)
      case (None,      Some(ask)) => Some(ask.getPrice)
      case (Some(bid), Some(ask)) => Some((bid.getPrice + ask.getPrice) / 2)
    }
  }

//  def price(level: Int, orders: Seq[Order]): Option[Double] = {
//    if (level < orders.length) {
//      Some(orders.sorted.get(level).getPrice)
//    } else {
//      None
//    }
//  }

  //TODO this results in a sort
//  def bidPrice(level: Int) = price(level, book.getUnmatchedBids)
//  def askPrice(level: Int) = price(level, book.getUnmatchedAsks)

  def processTransaction(price: BigDecimal) = {
    this.lastTransactionPrice = Some(price.toDouble)
  }

  def processOrderRemoval(orderCode: String) = {
      if (orderMap.contains(orderCode)) {
        val order = orderMap(orderCode)
        book.remove(order)
      } else {
        logger.warn("Unknown order code when removing order: " + orderCode)
      }
    }

    def processOrderFilled(orderCode: String) = {
        if (orderMap.contains(orderCode)) {
          val order = orderMap(orderCode)
          logger.debug("Removing order " + orderCode + " from book: " + order)
          book.remove(order)
        } else {
          logger.warn("Unknown order code when order filled: " + orderCode)
        }
    }

    def processOrderMatched(orderCode: String) = {
        if (orderMap.contains(orderCode)) {
          val order = orderMap(orderCode)
          logger.debug("partially filled order " + order)
        }  else {
          logger.debug("unknown order code " + orderCode)
        }
      }

    def processOrderSubmission(orderCode: String, price: BigDecimal, aggregateSize: Long, tradeDirection: TradeDirection.Value,
                                  marketMechanismType: String) = {
      val order = new Order()
      order.setPrice(price.toDouble)
      order.setQuantity(aggregateSize.toInt)
      order.setAgent(new SimpleTradingAgent())
      order.setIsBid(tradeDirection == TradeDirection.Buy)
      order.setTimeStamp(time.get)
      if (orderMap.contains(orderCode)) {
        logger.warn("Submission using existing order code: " + orderCode)
      }
      orderMap(orderCode) = order
      if (marketMechanismType equals "LO") {
        processLimitOrder(order)
      } else {
        processMarketOrder(order)
      }
  }

  def processNewDay() = {
    //TODO
    //book.clear()
  }

  def getDay(t: SimulationTime) = {
    val cal = new GregorianCalendar()
    cal.setTime(new java.util.Date(t.getTicks))
    cal.get(java.util.Calendar.DAY_OF_MONTH)
  }

}

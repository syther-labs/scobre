package org.ccfea.tickdata

import java.{lang, util}

import scala.collection.mutable

import grizzled.slf4j.Logger
import org.apache.thrift.server.TThreadPoolServer
import org.apache.thrift.transport.TServerSocket
import org.ccfea.tickdata.collector.MultivariateTimeSeriesCollector
import org.ccfea.tickdata.conf.{BuildInfo, ServerConf}

import org.ccfea.tickdata.event.OrderReplayEvent
import org.ccfea.tickdata.simulator.{ClearingMarketState, MarketState}
import org.ccfea.tickdata.storage.hbase.HBaseRetriever
import org.ccfea.tickdata.storage.shuffled.RandomPermutation
import org.ccfea.tickdata.storage.thrift.MultivariateThriftCollator
import org.ccfea.tickdata.thrift.OrderReplay

import collection.JavaConversions._

/**
 * A server that provides order-replay simulation results over the network.
 * It uses Apache Thrift so that clients can easily be written in other languages.
 *
 * (C) Steve Phelps 2014
 */
object OrderReplayService extends ReplayApplication {

  val shufflers = mutable.Map[String, RandomPermutation]()

  val logger = Logger("org.ccfea.tickdata.OrderReplayService")

  /**
   *    Use reflection to find the method to retrieve the  data for each variable (a function of MarketState).
   *
   * @param variables  The variables to collect from the simulation.
   * @return            a map of variables and methods, i.e. the collectors for the simulation.
   */
  def collectors(variables: java.util.List[String]) = {
    def variableToMethod(variable: String): MarketState => Option[AnyVal] =
      classOf[MarketState].getMethod(variable) invoke _
    for (variable <- variables) yield (variable, variableToMethod(variable))
  }

  def getShuffledData(assetId: String, source: Iterable[OrderReplayEvent],
                          proportionShuffling: Double, windowSize: Int): RandomPermutation = {
    if (shufflers.contains(assetId)) {
      val shuffler = shufflers(assetId)
      shuffler.proportion = proportionShuffling
      shuffler.windowSize = windowSize
      shuffler
    } else {
      val hbaseSource = new HBaseRetriever(selectedAsset = assetId)
      val shuffler = new RandomPermutation(hbaseSource, proportionShuffling, windowSize)
      shufflers(assetId) = shuffler
      shuffler
    }
  }

  def main(args: Array[String]): Unit = {

    val conf = new ServerConf(args)
    val port: Int = conf.port()

    val marketState = if (conf.explicitClearing()) new ClearingMarketState() else new MarketState()

    class Replayer(val eventSource: Iterable[OrderReplayEvent],
                   val dataCollectors: Map[String, MarketState => Option[AnyVal]],
                    val marketState: MarketState = marketState)
      extends MultivariateTimeSeriesCollector with MultivariateThriftCollator

    val processor = new org.ccfea.tickdata.thrift.OrderReplay.Processor(new OrderReplay.Iface {

      override def replay(assetId: String, variables: java.util.List[String],
                            startDate: String, endDate: String): java.util.List[java.util.Map[String,java.lang.Double]] = {

        logger.info("Using data for " + assetId + " between " + startDate + " and " + endDate)

        logger.info("Starting simulation... ")

        val hbaseSource: Iterable[OrderReplayEvent] =
          new HBaseRetriever(selectedAsset = assetId, startDate = parseDate(Some(startDate)),
                                                        endDate = parseDate(Some(endDate)))

        val replayer =
          new Replayer(eventSource = hbaseSource, dataCollectors = Map() ++ collectors(variables))
        replayer.run()

        logger.info("done.")

        replayer.result
      }

      override def shuffledReplay(assetId: String, variables: util.List[String],
                                    proportionShuffling: Double, windowSize: Int): util.List[util.Map[String, lang.Double]] = {

        logger.info("Shuffled replay for " + assetId + " with windowSize " + windowSize + " and percentage " + proportionShuffling)
        logger.info("Starting simulation... ")

        val source = new HBaseRetriever(selectedAsset = assetId)
        val shuffledData = getShuffledData(assetId, source, proportionShuffling, windowSize)

        val replayer =
          new Replayer(eventSource = shuffledData, dataCollectors = Map() ++ collectors(variables))
        replayer.run()

        logger.info("done.")

        replayer.result
      }
    })

    val serverTransport = new TServerSocket(port)
    val server = new TThreadPoolServer(new TThreadPoolServer.Args(serverTransport).processor(processor))

    logger.info("CCFEA order-replay server version " + BuildInfo.version)
    logger.info("Server running on port " + port + "... ")
    server.serve()
    logger.info("Server terminated.")
  }

}

package com.xsn.explorer.services.synchronizer

import com.alexitc.playsonify.core.FutureApplicationResult
import com.alexitc.playsonify.core.FutureOr.Implicits.FutureOps
import com.xsn.explorer.config.ExplorerConfig
import com.xsn.explorer.data.async.BlockFutureDataHandler
import com.xsn.explorer.errors.BlockNotFoundError
import com.xsn.explorer.models._
import com.xsn.explorer.models.transformers.toPersistedBlock
import com.xsn.explorer.models.values._
import com.xsn.explorer.services.{BlockService, TransactionCollectorService, XSNService}
import com.xsn.explorer.util.Extensions.FutureOrExt
import javax.inject.Inject

import org.scalactic.{Bad, Good}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

private[synchronizer] class LedgerSynchronizationOps @Inject() (
    explorerConfig: ExplorerConfig,
    blockDataHandler: BlockFutureDataHandler,
    xsnService: XSNService,
    blockService: BlockService,
    transactionCollectorService: TransactionCollectorService
)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def getLatestLedgerBlock: FutureApplicationResult[Option[persisted.Block]] = {
    blockDataHandler
      .getLatestBlock()
      .toFutureOr
      .map(Option.apply)
      .recoverFrom(BlockNotFoundError)(None)
      .toFuture
  }

  def getRPCBlock(
      blockhash: Blockhash
  ): FutureApplicationResult[rpc.Block.Canonical] = {
    val result = for {
      rpcBlock <- xsnService.getBlock(blockhash).toFutureOr
    } yield {
      if (
        explorerConfig.liteVersionConfig.enabled &&
        rpcBlock.height.int < explorerConfig.liteVersionConfig.syncTransactionsFromBlock
      ) {

        // lite version, ignore transactions
        rpcBlock.copy(transactions = List.empty)
      } else {
        rpcBlock
      }
    }

    result.toFuture
  }

  def getFullRPCBlock(
      blockhash: Blockhash
  ): FutureApplicationResult[rpc.Block.HasTransactions[rpc.TransactionVIN]] = {
  

    import io.scalaland.chimney.dsl._

    val partial = for {
      // we need to get the canonical block in order to evalu
      rpcBlock <- xsnService.getBlock(blockhash).toFutureOr
    } yield {
      if (
        explorerConfig.liteVersionConfig.enabled &&
        rpcBlock.height.int < explorerConfig.liteVersionConfig.syncTransactionsFromBlock
      ) {

        // lite version, ignore transactions
        val liteBlock = rpcBlock
          .into[rpc.Block.HasTransactions[rpc.TransactionVIN]]
          .withFieldConst(_.transactions, List.empty)
          .transform
        Future.successful(Good(liteBlock))
      } else {
        xsnService.getFullBlock(blockhash)
      }
    }

    val result = partial.flatMap(_.toFutureOr).toFuture



    result
  }

  def getBlockData(
      rpcBlock: rpc.Block[_]
  ): FutureApplicationResult[BlockData] = {
 

    val result = for {
      extractionMethod <- blockService.extractionMethod(rpcBlock).toFutureOr
      rewards <- getBlockRewards(rpcBlock, extractionMethod).toFutureOr
      data <- transactionCollectorService.collect(rpcBlock).toFutureOr
      (transactions, contracts, filterFactory) = data
      filteredTransactions = transactions.filter(_.blockhash == rpcBlock.hash)
    } yield {
      if (transactions.size != filteredTransactions.size) {
        // see https://github.com/bitpay/insight-api/issues/42
        logger.warn(
          s"The block = ${rpcBlock.hash} has phantom ${transactions.size - filteredTransactions.size} transactions, they are being discarded"
        )
      }
      val block = toPersistedBlock(rpcBlock, extractionMethod).withTransactions(
        filteredTransactions
      )
      (block, contracts, filterFactory, rewards)
    }

  

    result.toFuture
  }

  private def getBlockRewards(
      rpcBlock: rpc.Block[_],
      extractionMethod: BlockExtractionMethod
  ): FutureApplicationResult[Option[BlockRewards]] = {
    blockService.getBlockRewards(rpcBlock, extractionMethod).map {
      case Good(reward) => Good(Some(reward))
      case Bad(_) => Good(None)
    }
  }
}

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
package com.precog.shard
package jdbm3

import com.precog.common.security._
import com.precog.common.security.service._
import com.precog.common.accounts._
import com.precog.common.jobs._
import com.precog.common.client.BaseClient._

import blueeyes.BlueEyesServer
import blueeyes.bkka._
import blueeyes.util.Clock

import akka.dispatch.Future

import org.streum.configrity.Configuration

import scalaz._

object JDBMShardServer extends BlueEyesServer with AsyncShardService with AkkaDefaults {
  import WebJobManager._
  val clock = Clock.System

  val executionContext = defaultFutureDispatch
  implicit val M: Monad[Future] = new FutureMonad(executionContext)

  def APIKeyFinder(config: Configuration): APIKeyFinder[Future] = WebAPIKeyFinder(config)
  def AccountFinder(config: Configuration): AccountFinder[Future] = WebAccountFinder(config)
  def JobManager(config: Configuration): JobManager[Future] = WebJobManager(config).withM[Future]
  def QueryExecutorFactory(config: Configuration, accessControl: AccessControl[Future], accountFinder: AccountFinder[Future], jobManager: JobManager[Future]) = 
    JDBMQueryExecutorFactory(config, accessControl, accountFinder, jobManager)
}
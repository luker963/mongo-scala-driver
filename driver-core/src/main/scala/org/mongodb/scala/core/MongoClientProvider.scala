/**
 * Copyright (c) 2014 MongoDB, Inc.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 * For questions and comments about this product, please see the project page at:
 *
 * https://github.com/mongodb/mongo-scala-driver
 */
package org.mongodb.scala.core

import java.io.Closeable
import java.util.concurrent.TimeUnit

import org.mongodb.{MongoAsyncCursor, MongoFuture, ReadPreference}
import org.mongodb.binding.{AsyncClusterBinding, AsyncReadBinding, AsyncReadWriteBinding, AsyncWriteBinding, ReferenceCounted}
import org.mongodb.connection.Cluster
import org.mongodb.operation.{AsyncReadOperation, AsyncWriteOperation, QueryOperation}

import org.mongodb.scala.core.admin.MongoClientAdminProvider

/**
 * The MongoClientProvider trait providing the core of a MongoClient implementation.
 *
 * To use the trait it requires a concrete implementation of [RequiredTypesAndTransformersProvider] to define the types the
 * concrete implementation uses. The current supported implementations are native Scala `Futures` and RxScala
 * `Observables`.
 *
 * The core api remains the same between the implementations only the resulting types change based on the
 * [RequiredTypesAndTransformersProvider] implementation. To do this the concrete implementation of this trait requires the following
 * methods to be implemented:
 *
 * {{{
 *    case class MongoClient(options: MongoClientOptions, cluster: Cluster)
 *        extends MongoClientProvider with RequiredTypesAndTransformers
 *
 *         val admin: MongoClientAdminProvider
 *
 *         protected def databaseProvider(databaseName: String, databaseOptions: MongoDatabaseOptions): Database
 *
 * }}}
 *
 */
trait MongoClientProvider extends Closeable {

  this: RequiredTypesAndTransformersProvider =>

  /**
   * A concrete implementation of [[org.mongodb.scala.core.admin.MongoClientAdminProvider MongoClientAdminProvider]]
   *
   * @note Each MongoClient implementation must provide this.
   */
  val admin: MongoClientAdminProvider

  /**
   * The MongoClientOptions used with MongoClient.
   * These form the basis of the default options for the database and collection.
   *
   * @note Its expected that the MongoClient implementation is a case class and this is one of the constructor params.
   *       The default MongoClientOptions is created automatically by the [[MongoClientCompanion]] helper.
   */
  val options: MongoClientOptions

  /**
   * The Cluster.
   *
   * Used in conjunction with the binding for an operation to target the query at the correct server.
   *
   * @note Its expected that the MongoClient implementation is a case class and this is one of the constructor params.
   *       The default Cluster is created automatically by the [[MongoClientCompanion]] helper.
   */
  val cluster: Cluster

  /**
   * Helper to get a database
   *
   * @param databaseName the name of the database
   * @return MongoDatabase
   */
  def apply(databaseName: String) = database(databaseName)

  /**
   * Helper to get a database
   *
   * @param databaseName the name of the database
   * @param databaseOptions the options to use with the database
   * @return MongoDatabase
   */
  def apply(databaseName: String, databaseOptions: MongoDatabaseOptions) = database(databaseName, databaseOptions)

  /**
   * An explicit helper to get a database
   *
   * @param databaseName the name of the database
   * @return MongoDatabase
   */
  def database(databaseName: String): Database = database(databaseName, MongoDatabaseOptions(options))

  /**
   * an explicit helper to get a database
   *
   * @param databaseName the name of the database
   * @param databaseOptions the options to use with the database
   * @return MongoDatabase
   */
  def database(databaseName: String, databaseOptions: MongoDatabaseOptions): Database =
    databaseProvider(databaseName, databaseOptions)

  /**
   * Close the MongoClient and its connections
   */
  def close() {
    cluster.close()
  }

  /**
   * A concrete implementation of a [[MongoDatabaseProvider]]
   *
   * @note Each MongoClient implementation must provide this.
   *
   * @return Database
   */
  protected def databaseProvider(databaseName: String, databaseOptions: MongoDatabaseOptions): Database

  /**
   * Executes a AsyncWriteOperation
   *
   * @param writeOperation the write operation to execute asynchronously
   * @tparam T the type of result eg CommandResult, Document etc..
   * @return ResultType[T]
   */
  private[scala] def executeAsync[T](writeOperation: AsyncWriteOperation[T]): ResultType[T] = {
    val binding = this.writeBinding
    mongoFutureConverter[T](writeOperation.executeAsync(binding), binding)
  }

  /**
   * Executes a QueryOperation
   *
   * @param queryOperation the query operation to execute asynchronously
   * @param readPreference the read preference
   * @tparam T the type of result eg Document
   * @return CursorType[T]
   */
  private[scala] def executeAsync[T](queryOperation: QueryOperation[T], readPreference: ReadPreference): CursorType[T] = {
    val binding = readBinding(readPreference)
    mongoCursorConverter[T](queryOperation.executeAsync(binding), binding)
  }

  /**
   * Executes a QueryOperation and transforms the results
   *
   * @param queryOperation the query operation to execute asynchronously
   * @param readPreference the read preference
   * @param transformer the transformation
   * @tparam T The original Type
   * @tparam R The resulting Type
   * @return The transformed results
   */
  private[scala] def executeAsync[T, R](queryOperation: QueryOperation[T], readPreference: ReadPreference,
                                        transformer: MongoFuture[MongoAsyncCursor[T]] => MongoFuture[R]): ResultType[R] = {
    val binding = readBinding(readPreference)
    mongoFutureConverter[R](transformer(queryOperation.executeAsync(binding)), binding)
  }

  /**
   * Executes a AsyncReadOperation
   *
   * @param readOperation the query operation to execute asynchronously
   * @param readPreference the read preference
   * @tparam T the type of result eg Document
   * @return ResultType[T]
   */
  private[scala] def executeAsync[T](readOperation: AsyncReadOperation[T], readPreference: ReadPreference): ResultType[T] = {
    val binding = readBinding(readPreference)
    mongoFutureConverter[T](readOperation.executeAsync(binding), binding)
  }

  /**
   * Executes a AsyncReadOperation
   *
   * @param readOperation the query operation to execute asynchronously
   * @param readPreference the read preference
   * @tparam T The original Type
   * @tparam R The resulting Type
   * @param transformer the transformation
   * @return The transformed results
   */
  private[scala] def executeAsync[T, R](readOperation: AsyncReadOperation[T], readPreference: ReadPreference,
                                        transformer: MongoFuture[T] => MongoFuture[R]): ResultType[R] = {
    val binding = readBinding(readPreference)
    mongoFutureConverter[R](transformer(readOperation.executeAsync(binding)), binding)
  }

  /**
   * Returns a AsyncClusterBinding with a primary ReadPreference
   *
   * @return AsyncClusterBinding
   */
  private[scala] def writeBinding: AsyncWriteBinding = {
    readWriteBinding(ReadPreference.primary)
  }

  /**
   * Returns a AsyncReadBinding for the operation with the given ReadPreference
   *
   * @param readPreference the `ReadPreference` to be used with this operation
   * @return AsyncReadBinding
   */
  private[scala] def readBinding(readPreference: ReadPreference): AsyncReadBinding = {
    readWriteBinding(readPreference)
  }

  /**
   * Returns a AsyncClusterBinding for the operation with the given ReadPreference
   *
   * @param readPreference the `ReadPreference` to be used with this operation
   * @return AsyncReadBinding
   */
  private[scala] def readWriteBinding(readPreference: ReadPreference): AsyncReadWriteBinding = {
    new AsyncClusterBinding(cluster, readPreference, options.maxWaitTime, TimeUnit.MILLISECONDS)
  }
}

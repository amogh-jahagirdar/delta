/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta

import java.io.{File, IOException}
import java.net.ServerSocket

import org.scalatest.concurrent.Eventually
import org.scalatest.time.SpanSugar._

import org.apache.spark.SparkContext
import org.apache.spark.sql.{QueryTest, Row, SparkSession}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog.{CatalogStorageFormat, CatalogTable, CatalogTableType}
import org.apache.spark.sql.delta.actions.{Metadata, RemoveFile}
import org.apache.spark.sql.delta.implicits._
import org.apache.spark.sql.delta.icebergShaded.IcebergTransactionUtils
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType}
import org.apache.spark.util.Utils
import shadedForDelta.org.apache.iceberg.{Table, TableProperties}

/**
 * This test suite relies on an external Hive metastore (HMS) instance to run.
 *
 * A standalone HMS can be created using the following docker command.
 *  ************************************************************
 *  docker run -d -p 9083:9083 --env SERVICE_NAME=metastore \
 *  --name metastore-standalone apache/hive:4.0.0-beta-1
 *  ************************************************************
 *  The URL of this standalone HMS is thrift://localhost:9083
 *
 *  By default this hms will use `/opt/hive/data/warehouse` as warehouse path.
 *  Please make sure this path exists prior to running the suite.
 */
class ConvertToIcebergSuite extends QueryTest with Eventually {

  private var _sparkSession: SparkSession = null
  private var _sparkSessionWithDelta: SparkSession = null
  private var _sparkSessionWithIceberg: SparkSession = null

  private val PORT = 9083
  private val WAREHOUSE_PATH = "/opt/hive/data/warehouse/"

  private val testTableName: String = "deltatable"
  private var testTablePath: String = s"$WAREHOUSE_PATH$testTableName"

  override def spark: SparkSession = _sparkSession

  override def beforeAll(): Unit = {
    super.beforeAll()
    if (hmsReady(PORT)) {
      _sparkSessionWithDelta = createSparkSessionWithDelta()
      _sparkSessionWithIceberg = createSparkSessionWithIceberg()
      require(!_sparkSessionWithDelta.eq(_sparkSessionWithIceberg), "separate sessions expected")
    }
  }

  override def afterEach(): Unit = {
    super.afterEach()
    if (hmsReady(PORT)) {
      _sparkSessionWithDelta.sql(s"DROP TABLE IF EXISTS $testTableName")
    }
    Utils.deleteRecursively(new File(testTablePath))
  }

  override def afterAll(): Unit = {
    super.afterAll()
    SparkContext.getActive.foreach(_.stop())
  }

  test("enforceSupportInCatalog") {
    var testTable = new CatalogTable(
      TableIdentifier("table"),
      CatalogTableType.EXTERNAL,
      CatalogStorageFormat(None, None, None, None, compressed = false, Map.empty),
      new StructType(Array(StructField("col1", IntegerType), StructField("col2", StringType))))
    var testMetadata = Metadata()

    assert(UniversalFormat.enforceSupportInCatalog(testTable, testMetadata).isEmpty)

    testTable = testTable.copy(properties = Map("table_type" -> "iceberg"))
    var resultTable = UniversalFormat.enforceSupportInCatalog(testTable, testMetadata)
    assert(resultTable.nonEmpty)
    assert(!resultTable.get.properties.contains("table_type"))

    testMetadata = testMetadata.copy(
      configuration = Map("delta.universalFormat.enabledFormats" -> "iceberg"))
    assert(UniversalFormat.enforceSupportInCatalog(testTable, testMetadata).isEmpty)

    testTable = testTable.copy(properties = Map.empty)
    resultTable = UniversalFormat.enforceSupportInCatalog(testTable, testMetadata)
    assert(resultTable.nonEmpty)
    assert(resultTable.get.properties("table_type") == "iceberg")
  }

  test("basic test - managed table created with SQL") {
    if (hmsReady(PORT)) {
      runDeltaSql(
        s"""CREATE TABLE `${testTableName}` (col1 INT) USING DELTA
           |TBLPROPERTIES (
           |  'delta.columnMapping.mode' = 'name',
           |  'delta.universalFormat.enabledFormats' = 'iceberg',
           |  'delta.enableIcebergCompatV2' = 'true'
           |)""".stripMargin)
      runDeltaSql(s"INSERT INTO `$testTableName` VALUES (123)")
      verifyReadWithIceberg(testTableName, Seq(Row(123)))
    }
  }

  test("basic test - catalog table created with DataFrame") {
    if (hmsReady(PORT)) {
      withDeltaSparkSession { deltaSpark =>
        withDefaultTablePropsInSQLConf {
          deltaSpark.range(10).write.format("delta")
            .option("path", testTablePath)
            .option("delta.enableIcebergCompatV2", "true")
            .saveAsTable(testTableName)
        }
      }
      withDeltaSparkSession { deltaSpark =>
        deltaSpark.range(10, 20, 1)
          .write.format("delta").mode("append")
          .option("path", testTablePath)
          .option("delta.enableIcebergCompatV2", "true")
          .saveAsTable(testTableName)
      }
      verifyReadWithIceberg(testTableName, 0 to 19 map (Row(_)))
    }
  }

  test("Expire Snapshots") {
    if (hmsReady(PORT)) {
      runDeltaSql(
        s"""CREATE TABLE `${testTableName}` (col1 INT) USING DELTA
           |TBLPROPERTIES (
           |  'delta.columnMapping.mode' = 'name',
           |  'delta.universalFormat.enabledFormats' = 'iceberg',
           |  'delta.enableIcebergCompatV2' = 'true'
           |
           |)""".stripMargin)

      val icebergTable = loadIcebergTable()
      icebergTable.updateProperties().set(TableProperties.MAX_SNAPSHOT_AGE_MS, "1").commit()

      for (i <- 0 to 7) {
        runDeltaSql(s"INSERT INTO ${testTableName} VALUES (${i})",
          DeltaSQLConf.DELTA_UNIFORM_ICEBERG_SYNC_CONVERT_ENABLED.key -> "true")
      }

      // Sleep past snapshot retention duration
      Thread.sleep(5)
      withIcebergSparkSession { icebergSpark => {
        icebergSpark.sql(s"REFRESH TABLE $testTableName")
        val manifestListsBeforeExpiration = manifestListLocations(icebergSpark)
        assert(manifestListsBeforeExpiration.length == 8)
        val manifestsBeforeExpiration = manifestLocations(icebergSpark)

        // Trigger snapshot expiration
        runDeltaSql(s"OPTIMIZE ${testTableName}")
        icebergSpark.sql(s"REFRESH TABLE $testTableName")

        // Manifest lists from earlier snapshots should be cleaned up
        val manifestListsAfterExpiration = manifestListLocations(icebergSpark)
        assert(manifestListsAfterExpiration.length == 1)
        assertAllFilesDeleted(icebergTable, manifestListsBeforeExpiration)

        // Unreachable manifests should be cleaned up
        val manifestsAfterExpiration = manifestLocations(icebergSpark)

        val unreachableManifests = manifestsBeforeExpiration.diff(manifestsAfterExpiration)
        assertAllFilesDeleted(icebergTable, unreachableManifests)
      }}

      withDeltaSparkSession(deltaSparkSession => {
        val deltaLog = DeltaLog.forTable(deltaSparkSession, testTablePath)
        val logicallyRemovedDataFiles = deltaLog.getChanges(9).toArray.head._2.collect {
          case removeFile: RemoveFile => removeFile.absolutePath(deltaLog)
        }

        // The data files must not be cleaned up
        withIcebergSparkSession(_ => {
          val table = loadIcebergTable()
          logicallyRemovedDataFiles.foreach(
            file => assert(table.io().newInputFile(file.toString).exists()))
        })
      })
    }
  }

  test("Expire Snapshots doesn't cleanup in case the data/metadata locations are the same") {
    if (hmsReady(PORT)) {
      runDeltaSql(
        s"""CREATE TABLE `${testTableName}` (col1 INT) USING DELTA
           |TBLPROPERTIES (
           |  'delta.columnMapping.mode' = 'name',
           |  'delta.universalFormat.enabledFormats' = 'iceberg',
           |  'delta.enableIcebergCompatV2' = 'true'
           |
           |)""".stripMargin)

      val icebergTable = loadIcebergTable()
      icebergTable.updateProperties()
        .set(TableProperties.MAX_SNAPSHOT_AGE_MS, "1")
        .set(TableProperties.WRITE_METADATA_LOCATION, icebergTable.location())
        .commit()

      for (i <- 0 to 7) {
        runDeltaSql(s"INSERT INTO ${testTableName} VALUES (${i})",
          DeltaSQLConf.DELTA_UNIFORM_ICEBERG_SYNC_CONVERT_ENABLED.key -> "true")
      }

      // Sleep past snapshot retention duration
      Thread.sleep(5)

      withIcebergSparkSession { icebergSpark => {
        icebergSpark.sql(s"REFRESH TABLE $testTableName")
        val manifestListsBeforeExpiration = manifestListLocations(icebergSpark)
        assert(manifestListsBeforeExpiration.length == 8)

        // Trigger snapshot expiration
        runDeltaSql(s"OPTIMIZE ${testTableName}")
        icebergSpark.sql(s"REFRESH TABLE $testTableName")

        // Manifest lists from earlier snapshots should not be cleaned up
        val manifestListsAfterExpiration = manifestListLocations(icebergSpark)
        manifestListsAfterExpiration.foreach(
          file => assert(icebergTable.io().newInputFile(file).exists()))
      }
    }
  }
}

  private def manifestListLocations(icebergSparkSession: SparkSession): Array[String] = {
    icebergSparkSession
      .sql(s"SELECT * FROM default.${testTableName}.snapshots")
      .select("manifest_list")
      .as[String]
      .collect()
  }

  private def manifestLocations(icebergSparkSession: SparkSession): Array[String] = {
    icebergSparkSession
      .sql(s"SELECT * FROM default.${testTableName}.manifests")
      .select("path")
      .as[String]
      .collect()
  }

  private def loadIcebergTable(): shadedForDelta.org.apache.iceberg.Table = {
    withDeltaSparkSession { deltaSpark => {
      val log = DeltaLog.forTable(deltaSpark, testTablePath)
      val hiveCatalog = IcebergTransactionUtils.createHiveCatalog(
        log.newDeltaHadoopConf()
      )
      val table = hiveCatalog.loadTable(
        shadedForDelta.org.apache.iceberg.catalog.TableIdentifier
          .of("default", testTableName)
      )
      table
    }}
  }

  private def assertAllFilesDeleted(icebergTable: Table, files: Array[String]): Unit = {
    files.foreach(file => assert(!icebergTable.io().newInputFile(file).exists()))
  }

  def runDeltaSql(sqlStr: String, conf: (String, String)*): Unit = {
    withDeltaSparkSession { deltaSpark =>
      conf.foreach(c => deltaSpark.conf.set(c._1, c._2))
      deltaSpark.sql(sqlStr)
    }
  }

  def verifyReadWithIceberg(tableName: String, expectedAnswer: Seq[Row]): Unit = {
    withIcebergSparkSession { icebergSparkSession =>
      eventually(timeout(10.seconds)) {
        icebergSparkSession.sql(s"REFRESH TABLE ${tableName}")
        val icebergDf = icebergSparkSession.read.format("iceberg").load(tableName)
        checkAnswer(icebergDf, expectedAnswer)
      }
    }
  }


  def withDefaultTablePropsInSQLConf(f: => Unit): Unit = {
    withSQLConf(
      DeltaConfigs.COLUMN_MAPPING_MODE.defaultTablePropertyKey -> "name",
      DeltaConfigs.UNIVERSAL_FORMAT_ENABLED_FORMATS.defaultTablePropertyKey -> "iceberg"
    ) { f }
  }

  def withDeltaSparkSession[T](f: SparkSession => T): T = {
    withSparkSession(_sparkSessionWithDelta, f)
  }

  def withIcebergSparkSession[T](f: SparkSession => T): T = {
    withSparkSession(_sparkSessionWithIceberg, f)
  }

  def withSparkSession[T](sessionToUse: SparkSession, f: SparkSession => T): T = {
    try {
      SparkSession.setDefaultSession(sessionToUse)
      SparkSession.setActiveSession(sessionToUse)
      _sparkSession = sessionToUse
      f(sessionToUse)
    } finally {
      SparkSession.clearActiveSession()
      SparkSession.clearDefaultSession()
      _sparkSession = null
    }
  }

  protected def createSparkSessionWithDelta(): SparkSession = {
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val sparkSession = SparkSession.builder()
      .master("local[*]")
      .appName("DeltaSession")
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      .config("hive.metastore.uris", s"thrift://localhost:$PORT")
      .config("spark.sql.catalogImplementation", "hive")
      .getOrCreate()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    sparkSession
  }

  protected def createSparkSessionWithIceberg(): SparkSession = {
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val sparkSession = SparkSession.builder()
      .master("local[*]")
      .appName("IcebergSession")
      .config("spark.sql.extensions",
        "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
      .config("spark.sql.catalog.spark_catalog", "org.apache.iceberg.spark.SparkSessionCatalog")
      .config("hive.metastore.uris", s"thrift://localhost:$PORT")
      .config("spark.sql.catalogImplementation", "hive")
      .getOrCreate()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    sparkSession
  }

  def hmsReady(port: Int): Boolean = {
    var ss: ServerSocket = null
    try {
      ss = new ServerSocket(port)
      ss.setReuseAddress(true)
      logWarning("No HMS detected, test suite will not run")
      return false
    } catch {
      case e: IOException =>
    } finally {
      if (ss != null) {
        try ss.close()
        catch {
          case e: IOException =>
        }
      }
    }
    true
  }
}

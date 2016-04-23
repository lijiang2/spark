/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.command

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Dataset, Row, SQLContext}
import org.apache.spark.sql.catalyst.{CatalystTypeConverters, InternalRow, TableIdentifier}
import org.apache.spark.sql.catalyst.errors.TreeNodeException
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference}
import org.apache.spark.sql.catalyst.plans.logical
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.debug._
import org.apache.spark.sql.types._

/**
 * A logical command that is executed for its side-effects.  `RunnableCommand`s are
 * wrapped in `ExecutedCommand` during execution.
 */
private[sql] trait RunnableCommand extends LogicalPlan with logical.Command {
  override def output: Seq[Attribute] = Seq.empty
  override def children: Seq[LogicalPlan] = Seq.empty
  def run(sqlContext: SQLContext): Seq[Row]
}

/**
 * A physical operator that executes the run method of a `RunnableCommand` and
 * saves the result to prevent multiple executions.
 */
private[sql] case class ExecutedCommandExec(cmd: RunnableCommand) extends SparkPlan {
  /**
   * A concrete command should override this lazy field to wrap up any side effects caused by the
   * command or any other computation that should be evaluated exactly once. The value of this field
   * can be used as the contents of the corresponding RDD generated from the physical plan of this
   * command.
   *
   * The `execute()` method of all the physical command classes should reference `sideEffectResult`
   * so that the command can be executed eagerly right after the command query is created.
   */
  protected[sql] lazy val sideEffectResult: Seq[InternalRow] = {
    val converter = CatalystTypeConverters.createToCatalystConverter(schema)
    cmd.run(sqlContext).map(converter(_).asInstanceOf[InternalRow])
  }

  override def output: Seq[Attribute] = cmd.output

  override def children: Seq[SparkPlan] = Nil

  override def executeCollect(): Array[InternalRow] = sideEffectResult.toArray

  override def executeTake(limit: Int): Array[InternalRow] = sideEffectResult.take(limit).toArray

  protected override def doExecute(): RDD[InternalRow] = {
    sqlContext.sparkContext.parallelize(sideEffectResult, 1)
  }

  override def argString: String = cmd.toString
}


/**
 * An explain command for users to see how a command will be executed.
 *
 * Note that this command takes in a logical plan, runs the optimizer on the logical plan
 * (but do NOT actually execute it).
 */
case class ExplainCommand(
    logicalPlan: LogicalPlan,
    override val output: Seq[Attribute] =
      Seq(AttributeReference("plan", StringType, nullable = true)()),
    extended: Boolean = false,
    codegen: Boolean = false)
  extends RunnableCommand {

  // Run through the optimizer to generate the physical plan.
  override def run(sqlContext: SQLContext): Seq[Row] = try {
    // TODO in Hive, the "extended" ExplainCommand prints the AST as well, and detailed properties.
    val queryExecution = sqlContext.executePlan(logicalPlan)
    val outputString =
      if (codegen) {
        codegenString(queryExecution.executedPlan)
      } else if (extended) {
        queryExecution.toString
      } else {
        queryExecution.simpleString
      }
    Seq(Row(outputString))
  } catch { case cause: TreeNodeException[_] =>
    ("Error occurred during query planning: \n" + cause.getMessage).split("\n").map(Row(_))
  }
}


case class CacheTableCommand(
    tableName: String,
    plan: Option[LogicalPlan],
    isLazy: Boolean)
  extends RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    plan.foreach { logicalPlan =>
      sqlContext.registerDataFrameAsTable(Dataset.ofRows(sqlContext, logicalPlan), tableName)
    }
    sqlContext.cacheTable(tableName)

    if (!isLazy) {
      // Performs eager caching
      sqlContext.table(tableName).count()
    }

    Seq.empty[Row]
  }

  override def output: Seq[Attribute] = Seq.empty
}


case class UncacheTableCommand(tableName: String) extends RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    sqlContext.table(tableName).unpersist(blocking = false)
    Seq.empty[Row]
  }

  override def output: Seq[Attribute] = Seq.empty
}

/**
 * Clear all cached data from the in-memory cache.
 */
case object ClearCacheCommand extends RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    sqlContext.clearCache()
    Seq.empty[Row]
  }

  override def output: Seq[Attribute] = Seq.empty
}


/**
 * A command for users to get tables in the given database.
 * If a databaseName is not given, the current database will be used.
 * The syntax of using this command in SQL is:
 * {{{
 *   SHOW TABLES [(IN|FROM) database_name] [[LIKE] 'identifier_with_wildcards'];
 * }}}
 */
case class ShowTablesCommand(
    databaseName: Option[String],
    tableIdentifierPattern: Option[String]) extends RunnableCommand {

  // The result of SHOW TABLES has two columns, tableName and isTemporary.
  override val output: Seq[Attribute] = {
    AttributeReference("tableName", StringType, nullable = false)() ::
      AttributeReference("isTemporary", BooleanType, nullable = false)() :: Nil
  }

  override def run(sqlContext: SQLContext): Seq[Row] = {
    // Since we need to return a Seq of rows, we will call getTables directly
    // instead of calling tables in sqlContext.
    val catalog = sqlContext.sessionState.catalog
    val db = databaseName.getOrElse(catalog.getCurrentDatabase)
    val tables =
      tableIdentifierPattern.map(catalog.listTables(db, _)).getOrElse(catalog.listTables(db))
    tables.map { t =>
      val isTemp = t.database.isEmpty
      Row(t.table, isTemp)
    }
  }
}

/**
 * A command for users to list the databases/schemas.
 * If a databasePattern is supplied then the databases that only matches the
 * pattern would be listed.
 * The syntax of using this command in SQL is:
 * {{{
 *   SHOW (DATABASES|SCHEMAS) [LIKE 'identifier_with_wildcards'];
 * }}}
 */
case class ShowDatabasesCommand(databasePattern: Option[String]) extends RunnableCommand {

  // The result of SHOW DATABASES has one column called 'result'
  override val output: Seq[Attribute] = {
    AttributeReference("result", StringType, nullable = false)() :: Nil
  }

  override def run(sqlContext: SQLContext): Seq[Row] = {
    val catalog = sqlContext.sessionState.catalog
    val databases =
      databasePattern.map(catalog.listDatabases(_)).getOrElse(catalog.listDatabases())
    databases.map { d => Row(d) }
  }
}

/**
 * A command for users to list the properties for a table If propertyKey is specified, the value
 * for the propertyKey is returned. If propertyKey is not specified, all the keys and their
 * corresponding values are returned.
 * The syntax of using this command in SQL is:
 * {{{
 *   SHOW TBLPROPERTIES table_name[('propertyKey')];
 * }}}
 */
case class ShowTablePropertiesCommand(
    table: TableIdentifier,
    propertyKey: Option[String]) extends RunnableCommand {

  override val output: Seq[Attribute] = {
    val schema = AttributeReference("value", StringType, nullable = false)() :: Nil
    propertyKey match {
      case None => AttributeReference("key", StringType, nullable = false)() :: schema
      case _ => schema
    }
  }

  override def run(sqlContext: SQLContext): Seq[Row] = {
    val catalog = sqlContext.sessionState.catalog

    if (catalog.isTemporaryTable(table)) {
      Seq.empty[Row]
    } else {
      val catalogTable = sqlContext.sessionState.catalog.getTableMetadata(table)

      propertyKey match {
        case Some(p) =>
          val propValue = catalogTable
            .properties
            .getOrElse(p, s"Table ${catalogTable.qualifiedName} does not have property: $p")
          Seq(Row(propValue))
        case None =>
          catalogTable.properties.map(p => Row(p._1, p._2)).toSeq
      }
    }
  }
}

/**
 * A command for users to list all of the registered functions.
 * The syntax of using this command in SQL is:
 * {{{
 *    SHOW FUNCTIONS [LIKE pattern]
 * }}}
 * For the pattern, '*' matches any sequence of characters (including no characters) and
 * '|' is for alternation.
 * For example, "show functions like 'yea*|windo*'" will return "window" and "year".
 *
 * TODO currently we are simply ignore the db
 */
case class ShowFunctions(db: Option[String], pattern: Option[String]) extends RunnableCommand {
  override val output: Seq[Attribute] = {
    val schema = StructType(
      StructField("function", StringType, nullable = false) :: Nil)

    schema.toAttributes
  }

  override def run(sqlContext: SQLContext): Seq[Row] = {
    val dbName = db.getOrElse(sqlContext.sessionState.catalog.getCurrentDatabase)
    // If pattern is not specified, we use '*', which is used to
    // match any sequence of characters (including no characters).
    val functionNames =
      sqlContext.sessionState.catalog
        .listFunctions(dbName, pattern.getOrElse("*"))
        .map(_.unquotedString)
    // The session catalog caches some persistent functions in the FunctionRegistry
    // so there can be duplicates.
    functionNames.distinct.sorted.map(Row(_))
  }
}

/**
 * A command for users to get the usage of a registered function.
 * The syntax of using this command in SQL is
 * {{{
 *   DESCRIBE FUNCTION [EXTENDED] upper;
 * }}}
 */
case class DescribeFunction(
    functionName: String,
    isExtended: Boolean) extends RunnableCommand {

  override val output: Seq[Attribute] = {
    val schema = StructType(
      StructField("function_desc", StringType, nullable = false) :: Nil)

    schema.toAttributes
  }

  private def replaceFunctionName(usage: String, functionName: String): String = {
    if (usage == null) {
      "To be added."
    } else {
      usage.replaceAll("_FUNC_", functionName)
    }
  }

  override def run(sqlContext: SQLContext): Seq[Row] = {
    // Hard code "<>", "!=", "between", and "case" for now as there is no corresponding functions.
    functionName.toLowerCase match {
      case "<>" =>
        Row(s"Function: $functionName") ::
        Row(s"Usage: a <> b - Returns TRUE if a is not equal to b") :: Nil
      case "!=" =>
        Row(s"Function: $functionName") ::
        Row(s"Usage: a != b - Returns TRUE if a is not equal to b") :: Nil
      case "between" =>
        Row(s"Function: between") ::
        Row(s"Usage: a [NOT] BETWEEN b AND c - " +
          s"evaluate if a is [not] in between b and c") :: Nil
      case "case" =>
        Row(s"Function: case") ::
        Row(s"Usage: CASE a WHEN b THEN c [WHEN d THEN e]* [ELSE f] END - " +
          s"When a = b, returns c; when a = d, return e; else return f") :: Nil
      case _ => sqlContext.sessionState.functionRegistry.lookupFunction(functionName) match {
        case Some(info) =>
          val result =
            Row(s"Function: ${info.getName}") ::
            Row(s"Class: ${info.getClassName}") ::
            Row(s"Usage: ${replaceFunctionName(info.getUsage(), info.getName)}") :: Nil

          if (isExtended) {
            result :+
              Row(s"Extended Usage:\n${replaceFunctionName(info.getExtended, info.getName)}")
          } else {
            result
          }

        case None => Seq(Row(s"Function: $functionName not found."))
      }
    }
  }
}

case class SetDatabaseCommand(databaseName: String) extends RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    sqlContext.sessionState.catalog.setCurrentDatabase(databaseName)
    Seq.empty[Row]
  }

  override val output: Seq[Attribute] = Seq.empty
}

package se.uu.it.easymr

import org.apache.commons.io.FilenameUtils
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.NullWritable
import org.apache.hadoop.mapred.lib.MultipleTextOutputFormat
import org.apache.log4j.Logger
import org.apache.spark.HashPartitioner
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD.rddToPairRDDFunctions

import scopt.OptionParser
import org.apache.hadoop.io.LongWritable
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapred.lib.NLineInputFormat

private[easymr] class RDDMultipleTextOutputFormat extends MultipleTextOutputFormat[Any, Any] {
  override def generateActualKey(key: Any, value: Any): Any =
    NullWritable.get()
  override def generateFileNameForKeyValue(
    key: Any,
    value: Any,
    name: String): String =
    key.asInstanceOf[String]
}

case class EasyMapParams(
  imageName: String = null,
  command: String = null,
  inputPath: String = null,
  outputPath: String = null,
  wholeFiles: Boolean = false,
  local: Boolean = false,
  linesPerRecord: Int = 1)

object EasyMapCLI {

  @transient lazy val log = Logger.getLogger(getClass.getName)

  def run(params: EasyMapParams) = {
    
    //Start Spark context
    val sc = EasyContext.create(
      appName = s"Map: ${params.command}",
      params.linesPerRecord,
      params.local)

    //Read input data
    val data = readInputData(
      sc,
      params.inputPath,
      params.outputPath,
      params.wholeFiles)

    //Map data
    val result = EasyMapReduce.mapWholeFiles(
      data,
      params.imageName,
      params.command)

    //Save results
    if (params.wholeFiles) { //Save with multiple output
      //Count files to output
      val it = FileSystem
        .get(sc.hadoopConfiguration)
        .listFiles(new Path(params.inputPath), false)
      var numFiles = 0 // Can't go functional on this :-(
      while (it.hasNext()) {
        it.next
        numFiles += 1
      }
      //Save on separated files
      result.partitionBy(new HashPartitioner(numFiles))
        .saveAsHadoopFile(params.outputPath,
          classOf[String],
          classOf[String],
          classOf[RDDMultipleTextOutputFormat])
    } else { //Save as single file
      result.map(_._2) //remove index
        .saveAsTextFile(params.outputPath)
    }

    //Stop Spark context
    sc.stop

  }

  private def readInputData(
    sc: SparkContext,
    inputPath: String,
    outputPath: String,
    wholeFiles: Boolean) = {
    val defaultParallelism =
      sc.getConf.get("spark.default.parallelism", "0").toInt
    if (wholeFiles) {
      //Get output extension
      val outExt = FilenameUtils.getExtension(outputPath)
      //Load files
      val rdd = if (defaultParallelism > 0) {
        sc.wholeTextFiles(inputPath, defaultParallelism)
      } else {
        sc.wholeTextFiles(inputPath)
      }
      rdd.map {
        case (filename, content) =>
          //Trim extension and path
          val noExt = FilenameUtils.removeExtension(filename)
          val trimmedName = FilenameUtils.getBaseName(noExt)
          //Set trimmed name and index, with output extension
          if (outExt != null && outExt.length > 0) {
            (s"${trimmedName}.${outExt}", content)
          } else {
            (trimmedName, content)
          }
      }
    } else {
      val rdd = if (defaultParallelism > 0) {
        sc.hadoopFile[LongWritable, Text, NLineInputFormat](inputPath, defaultParallelism)
      } else {
        sc.hadoopFile[LongWritable, Text, NLineInputFormat](inputPath)
      }
      rdd.map{ case(lw,txt) => (inputPath, txt.toString) }
    }
  }

  def main(args: Array[String]) {

    val defaultParams = EasyMapParams()

    val parser = new OptionParser[EasyMapParams]("Easy Map") {
      head("EasyMap: it maps a distributed dataset using a command form a Docker container.")
      opt[String]("imageName")
        .required
        .text("Docker image name.")
        .action((x, c) => c.copy(imageName = x))
      opt[String]("command")
        .required
        .text("command to run inside the Docker container, " +
          "e.g. 'rev /input > /output | tr -d \"\\n\"'.")
        .action((x, c) => c.copy(command = x))
      opt[Unit]("wholeFiles")
        .text("if set, multiple input files will be loaded from an input directory. The command will " +
          "executed in parallel, on the whole files. In contrast, when this is not set " +
          "the file/files in input is/are splitted line by line, and the command is executed in parallel " +
          "on each line of the file.")
        .action((_, c) => c.copy(wholeFiles = true))
      opt[Int]("linesPerRecord")
        .text(s"Lines to parse in each record (default: 1).")
        .action((x, c) => c.copy(linesPerRecord = x))
      opt[Unit]("local")
        .text("set to run in local mode (useful for testing purpose).")
        .action((_, c) => c.copy(local = true))
      arg[String]("inputPath")
        .required
        .text("dataset input path. Must be a directory if wholeFiles is set.")
        .action((x, c) => c.copy(inputPath = x))
      arg[String]("outputPath")
        .required
        .text("results output path.")
        .action((x, c) => c.copy(outputPath = x))
    }

    parser.parse(args, defaultParams).map { params =>
      run(params)
    } getOrElse {
      sys.exit(1)
    }

  }

}
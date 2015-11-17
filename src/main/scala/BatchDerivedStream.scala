package telemetry

import awscala.s3._
import com.github.nscala_time.time.Imports._
import com.typesafe.config._
import heka.{HekaFrame, Message}
import java.io.File
import java.util.UUID
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.joda.time.Days
import org.json4s._
import org.json4s.native.JsonMethods._
import scala.collection.JavaConverters._
import scala.io.Source
import telemetry.parquet.ParquetFile
import telemetry.streams.{ExecutiveStream, E10sExperiment}

case class ObjectSummary(key: String, size: Long)

abstract class BatchDerivedStream extends java.io.Serializable{
  @transient private implicit val s3 = S3()
  private val conf = ConfigFactory.load()
  private val parquetBucket = conf.getString("app.parquetBucket")
  private val metadataBucket = Bucket("net-mozaws-prod-us-west-2-pipeline-metadata")
  private val clsName = uncamelize(this.getClass.getSimpleName.replace("$", ""))  // Use classname as stream prefix on S3

  private val metaPrefix = {
    val Some(sourcesObj) = metadataBucket.get(s"sources.json")
    val sources = parse(Source.fromInputStream(sourcesObj.getObjectContent()).getLines().mkString("\n"))
    val JString(metaPrefix) = sources \\ streamName \\ "metadata_prefix"
    metaPrefix
  }

  private val partitioning = {
    val Some(schemaObj) = metadataBucket.get(s"$metaPrefix/schema.json")
    val schema = Source.fromInputStream(schemaObj.getObjectContent()).getLines().mkString("\n")
    Partitioning(schema)
  }

  private def uploadLocalFileToS3(fileName: String, prefix: String) {
    implicit val s3 = S3()
    val uuid = UUID.randomUUID.toString
    val key = s"$prefix/$uuid"
    val file = new File(fileName)
    println(s"Uploading Parquet file to $parquetBucket/$key")
    s3.putObject(parquetBucket, key, file)
  }

  private def uncamelize(name: String) = {
    val pattern = java.util.regex.Pattern.compile("(^[^A-Z]+|[A-Z][^A-Z]+)")
    val matcher = pattern.matcher(name);
    val output = new StringBuilder

    while (matcher.find()) {
      if (output.length > 0)
        output.append("-");
      output.append(matcher.group().toLowerCase);
    }

    output.toString()
  }

  private def hasNotBeenProcessed(prefix: String): Boolean = {
    implicit val s3 = S3()
    val bucket = Bucket(parquetBucket)
    val partitionedPrefix = partitioning.partitionPrefix(prefix)
    if (!s3.objectSummaries(bucket, s"$clsName/$partitionedPrefix").isEmpty) {
      println(s"Warning: can't process $prefix as data already exists!")
      false
    } else true
  }

  protected def buildSchema: Schema
  protected def buildRecord(message: Message, schema: Schema): Option[GenericRecord]
  protected def streamName: String
  protected def filterPrefix: String = ""
  protected def prefixGroup(key: String): String = {
    val Some(m) = "(.+)/.+".r.findFirstMatchIn(key)
    m.group(1)
  }

  protected def transform(bucket: Bucket, keys: Iterator[ObjectSummary], prefix: String) {
    implicit val s3 = S3()
    val schema = buildSchema
    val records = for {
      key <- keys
      hekaFile = bucket
      .getObject(key.key)
      .getOrElse(throw new Exception("File missing on S3"))
      message <- HekaFrame.parse(hekaFile.getObjectContent(), hekaFile.getKey())
      record <- buildRecord(message, schema)
    } yield record

    val partitionedPrefix = partitioning.partitionPrefix(prefix)
    while(!records.isEmpty) {
      val localFile = ParquetFile.serialize(records, schema, chunked=true)
      uploadLocalFileToS3(localFile, s"$clsName/$partitionedPrefix")
      new File(localFile).delete()
    }
  }
}

object BatchDerivedStream {
  type OptionMap = Map[Symbol, String]

  private val metadataBucket = Bucket("net-mozaws-prod-us-west-2-pipeline-metadata")
  private implicit val s3 = S3()

  private def parseOptions(args: Array[String]): OptionMap = {
    def nextOption(map : OptionMap, list: List[String]) : OptionMap = {
      def isSwitch(s : String) = (s(0) == '-')
      list match {
        case Nil => map
        case "--from-date" :: value :: tail =>
          nextOption(map ++ Map('fromDate -> value), tail)
        case "--to-date" :: value :: tail =>
          nextOption(map ++ Map('toDate -> value), tail)
        case string :: opt2 :: tail if isSwitch(opt2) =>
          nextOption(map ++ Map('stream -> string), list.tail)
        case string :: Nil =>  nextOption(map ++ Map('stream -> string), list.tail)
        case option :: tail => Map()
      }
    }

    nextOption(Map(), args.toList)
  }

  private def S3Prefix(logical: String): String = {
    val Some(sourcesObj) = metadataBucket.get(s"sources.json")
    val sources = parse(Source.fromInputStream(sourcesObj.getObjectContent()).getLines().mkString("\n"))
    val JString(prefix) = sources \\ logical \\ "prefix"
    prefix
  }

  private def groupBySize(keys: Iterator[ObjectSummary]): List[List[ObjectSummary]] = {
    val threshold = 1L << 31
    keys.foldRight((0L, List[List[ObjectSummary]]()))(
      (x, acc) => {
        acc match {
          case (size, head :: tail) if size + x.size < threshold =>
            (size + x.size, (x :: head) :: tail)
          case (size, res) if size + x.size < threshold =>
            (size + x.size, List(x) :: res)
          case (_, res) =>
            (x.size, List(x) :: res)
        }
      })._2
  }

  def convert(stream: String, from: String, to: String) {
    val converter = stream match {
      case "ExecutiveStream" => ExecutiveStream
      case "E10sExperiment" => E10sExperiment("e10s-enabled-aurora-20151020@experiments.mozilla.org",
                                              "telemetry/4/saved_session/Firefox/aurora/43.0a2/")
      case _ => throw new Exception("Stream does not exist!")
    }

    val formatter = DateTimeFormat.forPattern("yyyyMMdd")
    val fromDate = DateTime.parse(from, formatter)
    val toDate = DateTime.parse(to, formatter)
    val daysCount = Days.daysBetween(fromDate, toDate).getDays()
    val bucket = Bucket("net-mozaws-prod-us-west-2-pipeline-data")
    val prefix = S3Prefix(converter.streamName)
    val filterPrefix = converter.filterPrefix

    val conf = new SparkConf().setAppName("Parquet Converter").setMaster("local[*]")
    val sc = new SparkContext(conf)

    val tasks = sc.parallelize(0 until daysCount + 1)
      .map(fromDate.plusDays(_).toString("yyyyMMdd"))
      .flatMap(date => {
                 s3.objectSummaries(bucket, s"$prefix/$date/$filterPrefix")
                   .map(summary => ObjectSummary(summary.getKey(), summary.getSize()))})
      .groupBy(summary => converter.prefixGroup(summary.key))
      .flatMap(x => groupBySize(x._2.toIterator).toIterator.zip(Iterator.continually{x._1}))
      .filter(x => converter.hasNotBeenProcessed(x._2))

    tasks
      .repartition(tasks.count().toInt)
      .foreach(x => converter.transform(bucket, x._1.toIterator, x._2))
  }

  def main(args: Array[String]) {
    val usage = "converter --from-date YYYYMMDD --to-date YYYYMMDD stream_name"
    val options = parseOptions(args)

    if (!List('fromDate, 'toDate, 'stream).forall(options.contains)) {
      println(usage)
      return
    }

    convert(options('stream), options('fromDate), options('toDate))
  }
}
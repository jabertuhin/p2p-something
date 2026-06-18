import com.typesafe.scalalogging.StrictLogging
import org.rogach.scallop.{ScallopConf, ScallopOption, Subcommand, intConverter, stringConverter}

import java.nio.file.Paths

class ReceiveConf extends Subcommand("receive") {
  val dir: ScallopOption[String] = opt[String](required = true)
  val port: ScallopOption[Int] = opt[Int](required = true)
}

class SendConf extends Subcommand("send") {
  val dir: ScallopOption[String] = opt[String](required = true)
  val host: ScallopOption[String] = opt[String](required = true)
  val port: ScallopOption[Int] = opt[Int](required = true)
}

class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  val receive = new ReceiveConf
  addSubcommand(receive)

  val send = new SendConf
  addSubcommand(send)

  verify()
}

object Main extends StrictLogging{
  def main(args: Array[String]): Unit = {
    val conf = new Conf(args)

    conf.subcommand match {
      case Some(conf.receive) =>
        val dirA = conf.receive.dir()
        Receiver.run(dirA, port = conf.receive.port())
        logger.info("receive")
      case Some(conf.send) => println("send")
      case _ => println("no subcommand")
    }
  }
}
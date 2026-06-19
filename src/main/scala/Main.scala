import com.typesafe.scalalogging.StrictLogging
import org.rogach.scallop._


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
        logger.info("Starting receiver....")
        Receiver.run(dirA, port = conf.receive.port())
      case Some(conf.send) =>
        val dirB = conf.receive.dir()
        logger.info("Starting sender....")
        Sender.run(dirB,  host = conf.send.host(), port = conf.receive.port())
      case _ => println("no subcommand")
    }
  }
}
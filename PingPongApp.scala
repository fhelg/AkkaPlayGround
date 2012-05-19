import akka.actor._
import akka.event.Logging
import akka.util.Duration
import akka.util.duration._
import akka.routing.RoundRobinRouter
import akka.routing.FromConfig
import com.typesafe.config.ConfigFactory

object PingPongApp extends App {

  sealed trait Message
  case class Ping extends Message
  case class Pong extends Message
  case class Stop(duration: Duration) extends Message

  class PongActor extends Actor {  
    def receive = {
      case Ping =>
        println("ping received in actor %s - from actor %s".format(self.path.name, sender.path.name))
        sender ! Pong
    }

    override def postStop() = {
      println("PongActor is stopping")
    }
  }

  class PingActor() extends Actor {
    val pongService = context.actorFor("/user/pongService")
    val masterService = context.actorFor("/user/services")
    def receive = {
      case Ping =>
        println("ping in actor %s".format(self.path.name))
        pongService ! Ping

      case Pong =>
        println("pong in actor %s".format(self.path.name))
        masterService ! Pong
    }

    override def postStop() = {
      println("PingActor is stopping")
    }
  }

  class Master(nbMsg: Int) extends Actor {
    //.withRouter(FromConfig()) 
    val pongActor = context.system.actorOf(Props[PongActor].withRouter(FromConfig()), name = "pongService")
    //.withRouter(FromConfig())
    val pingActor = context.system.actorOf(Props[PingActor].withRouter(FromConfig()), name = "pingService")
    val start: Long = System.currentTimeMillis

    var nbOfMsg: Int = _
    var nbOfResult: Int = _

    def receive = {
      case Ping =>
        while (nbOfMsg < nbMsg) {
          nbOfMsg += 1
          pingActor ! Ping
        }

      case Pong =>
        nbOfResult += 1
        if (nbOfResult == nbMsg) {
          println("all pong msg have been received")
          self ! Stop(duration = (System.currentTimeMillis - start).millis)
        }

      case Stop(duration) =>
        // ne garanti pas que les messages pending seront traité...
        //On préférera plutôt l'utilisation de message (qui rentrera dans la mailbox)
        //context.stop(sender)
        pongActor ! PoisonPill
        pingActor ! PoisonPill
        if (nbOfMsg == nbMsg) {
          //should always be the case
          val listener = context.actorFor("/user/listener")
          listener ! Stop(duration)
          //context.stop(self)
          self ! PoisonPill
        } else
          throw new RuntimeException("unexpected error....")
    }
  }

  class Listener extends Actor {
    def receive = {
      case Stop(duration) =>
        println("* stopping PingPongSystem [via Listener] - \t%s".format(duration))
        context.system.shutdown()
    }
  }

  //
  def main(nbMsg: Int) {
    println("*** starting PingPongSystem ***")
    //
    val system = ActorSystem("PingPongSystem", ConfigFactory.load.getConfig("master"))

    val listener = system.actorOf(Props[Listener], name = "listener")
    val master = system.actorOf(Props(new Master(nbMsg)), name = "services")

    master ! Ping
  }

  main(10)

}

package domain
import test.IzanamiSpec
import zio._

trait Context extends AuthInfoModule

trait AuthInfoModule {
  def user: User
}

trait User {
  def user[R]: RIO[R, String]
  def withUser[R, E, A](user: String)(zio: ZIO[R, E, A]): ZIO[R, E, A]
}

object User {
  def makeModule(user: String): UIO[AuthInfoModule] =
    FiberRef.make(user).map { ref =>
      new AuthInfoModule {
        val user = new User {
          def user[R]: RIO[R, String]                                          = ref.get
          def withUser[R, E, A](user: String)(zio: ZIO[R, E, A]): ZIO[R, E, A] = ref.locally(user)(zio)
        }
      }
    }

  def withUser[R <: AuthInfoModule, E, A](user: String)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.accessM[R](_.user.withUser(user)(zio))
}

class ZioTest extends IzanamiSpec {
  "Zio test" must {
    "work" in {
      //val userValue: RIO[Context, String] = ZIO.accessM[Context](_.user.user)
      //val res = for {
      //  runtime <- ZIO.runtime[Any]
      //  user    = runtime.unsafeRun(User.withUser("test")(userValue))
      //  _       = println(s"Coucou : $user")
      //} yield ()
//
      //val runtime = new DefaultRuntime {}
      //println("runing test")
      //runtime.unsafeRun(res)
    }
  }
}

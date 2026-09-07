package fr.maif.izanami.services

import fr.maif.izanami.models.ReadPersonnalAccessToken
import fr.maif.izanami.datastores.{PersonnalAccessTokenDatastore}
import fr.maif.izanami.datastores.PersonnalAccessTokenDatastore.{TokenCheckSuccess, TokenCheckFailure}
import scala.concurrent.Future
import java.util.Base64
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.errors.{BadFormatPersonalAccessToken, InvalidpersonalAccessToken}
import fr.maif.izanami.utils.Done
import scala.concurrent.ExecutionContext
import scala.util.Try


class AuthService(personalAccessTokenDatastore: PersonnalAccessTokenDatastore)(implicit val ec: ExecutionContext) {
  def extractAndCheckPersonnalAccessToken(
      headerValue: String,
      checker: ReadPersonnalAccessToken => Boolean
  ): FutureEither[(String, ReadPersonnalAccessToken)] = {
    val splittedValue = headerValue.split(("Basic "));
    for(  
      _ <- if(splittedValue.length == 2) FutureEither.failure(BadFormatPersonalAccessToken) else FutureEither.success(Done.done());
      tokenValue = splittedValue(1);
      decoded <- FutureEither.from(Try { Base64.getDecoder.decode(tokenValue.getBytes) }, BadFormatPersonalAccessToken);
      decodedString = new String(decoded);
      splittedValue = decodedString.split(":", 2);
      _ <- if(splittedValue.length == 2) FutureEither.failure(BadFormatPersonalAccessToken) else FutureEither.success(Done.done());
      username = splittedValue(0);
      token = splittedValue(1);
      tokenCheckResult = FutureEither.success(personalAccessTokenDatastore.checkAccessToken(username=username, token = token, checker = checker));
      res <- tokenCheckResult.flatMap {
        case TokenCheckSuccess(token) => FutureEither.success((username, token))
        case TokenCheckFailure        => FutureEither.failure(InvalidpersonalAccessToken)
      }
    ) yield res
  }
}
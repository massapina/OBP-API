package code.api

import code.token.Tokens
import code.api.util.APIUtil.OAuth.{Consumer, Token}
import code.consumer.Consumers
import code.model.TokenType._
import code.model.dataAccess.ResourceUser
import code.model.{User, Consumer => OBPConsumer, Token => OBPToken}
import net.liftweb.common.Full
import net.liftweb.util.Helpers._
import net.liftweb.util.Props
import net.liftweb.util.TimeHelpers.TimeSpan

trait DefaultUsers {

  //create the application
  lazy val testConsumer = Consumers.consumers.vend.createConsumer(Some(randomString(40).toLowerCase), Some(randomString(40).toLowerCase), Some(true), Some("test application"), None, None, None, None, None) match {
    case Full(c) => c
  }

  val defaultProvider = Props.get("hostname","")

  lazy val consumer = new Consumer (testConsumer.key.get,testConsumer.secret.get)

  // create the access token
  val expiration = Props.getInt("token_expiration_weeks", 4)
  lazy val tokenDuration = weeks(expiration)

  lazy val authuser1: ResourceUser = User.createResourceUser(defaultProvider, None, None, None, None) match {
    case Full(au) => au
  }

  lazy val testToken = Tokens.tokens.vend.createToken(Access,
                                                      Some(testConsumer.id.get),
                                                      Some(authuser1.id.get),
                                                      Some(randomString(40).toLowerCase),
                                                      Some(randomString(40).toLowerCase),
                                                      Some(tokenDuration),
                                                      Some({(now : TimeSpan) + tokenDuration}),
                                                      Some(now),
                                                      None
                                                    ) match {
    case Full(t) => t
  }

  lazy val token = new Token(testToken.key.get, testToken.secret.get)

  // create a user for test purposes
  lazy val authuser2 = User.createResourceUser(defaultProvider, None, None, None, None) match {
    case Full(au2) => au2
  }

  //we create an access token for the other user
  lazy val testToken2 = Tokens.tokens.vend.createToken(Access,
                                                      Some(testConsumer.id.get),
                                                      Some(authuser2.id.get),
                                                      Some(randomString(40).toLowerCase),
                                                      Some(randomString(40).toLowerCase),
                                                      Some(tokenDuration),
                                                      Some({(now : TimeSpan) + tokenDuration}),
                                                      Some(now),
                                                      None
                                                    ) match {
    case Full(t) => t
  }

  lazy val token2 = new Token(testToken2.key.get, testToken2.secret.get)

  // create a user for test purposes
  lazy val authuser3 = User.createResourceUser(defaultProvider, None, None, None, None) match {
    case Full(au3) => au3
  }

  //we create an access token for the other user
  lazy val testToken3 = Tokens.tokens.vend.createToken(Access,
                                                      Some(testConsumer.id.get),
                                                      Some(authuser3.id.get),
                                                      Some(randomString(40).toLowerCase),
                                                      Some(randomString(40).toLowerCase),
                                                      Some(tokenDuration),
                                                      Some({(now : TimeSpan) + tokenDuration}),
                                                      Some(now),
                                                      None
                                                    ) match {
    case Full(t) => t
  }

  lazy val token3 = new Token(testToken3.key.get, testToken3.secret.get)

  lazy val user1 = Some((consumer, token))
  lazy val user2 = Some((consumer, token2))
  lazy val user3 = Some((consumer, token3))


}

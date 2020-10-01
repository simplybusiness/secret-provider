/*
 *
 *  * Copyright 2017-2020 Lenses.io Ltd
 *
 */

package io.lenses.connect.secrets.providers

import java.nio.file.FileSystems
import java.time.OffsetDateTime
import java.util.Calendar

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials, DefaultAWSCredentialsProviderChain}
import com.amazonaws.services.secretsmanager.model.{DescribeSecretRequest, GetSecretValueRequest}
import com.amazonaws.services.secretsmanager.{AWSSecretsManager, AWSSecretsManagerClientBuilder}
import com.fasterxml.jackson.databind.ObjectMapper
import com.typesafe.scalalogging.StrictLogging
import io.lenses.connect.secrets.config.AWSProviderSettings
import io.lenses.connect.secrets.connect.{AuthMode, decodeKey, getFileName}
import org.apache.kafka.connect.errors.ConnectException

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

trait AWSHelper extends StrictLogging {
  private val separator: String = FileSystems.getDefault.getSeparator

  // initialize the AWS client based on the auth mode
  def createClient(settings: AWSProviderSettings): AWSSecretsManager = {

    logger.info(
      s"Initializing client with mode [${settings.authMode}]"
    )

    val credentialProvider = settings.authMode match {
      case AuthMode.CREDENTIALS =>
        new BasicAWSCredentials(settings.accessKey, settings.secretKey.value())
      case _ =>
        new DefaultAWSCredentialsProviderChain().getCredentials
    }

    val credentials = new AWSStaticCredentialsProvider(credentialProvider)

    AWSSecretsManagerClientBuilder
      .standard()
      .withCredentials(credentials)
      .withRegion(settings.region)
      .build()

  }

  // determine the ttl for the secret
  private def getTTL(
      client: AWSSecretsManager,
      secretId: String
  ): Option[OffsetDateTime] = {

    // describe to get the ttl
    val descRequest: DescribeSecretRequest =
      new DescribeSecretRequest().withSecretId(secretId)

    Try(client.describeSecret(descRequest)) match {
      case Success(d) =>
        if (d.getRotationEnabled) {
          val lastRotation = d.getLastRotatedDate
          val nextRotationInDays =
            d.getRotationRules.getAutomaticallyAfterDays
          val cal = Calendar.getInstance()
          //set to last rotation date
          cal.setTime(lastRotation)
          //increment
          cal.add(Calendar.DAY_OF_MONTH, nextRotationInDays.toInt)
          Some(
            OffsetDateTime.ofInstant(cal.toInstant, cal.getTimeZone.toZoneId))

        } else None

      case Failure(exception) =>
        throw new ConnectException(
          s"Failed to describe secret [$secretId]",
          exception
        )
    }
  }

  // get the key value and ttl in the specified secret
  def getSecretValue(
      client: AWSSecretsManager,
      rootDir: String,
      secretId: String,
      key: String
  ): (String, Option[OffsetDateTime]) = {

    // get the secret
    Try(
      client.getSecretValue(new GetSecretValueRequest().withSecretId(secretId))
    ) match {
      case Success(secret) =>
        val value =
          new ObjectMapper()
            .readValue(
              secret.getSecretString,
              classOf[java.util.HashMap[String, String]]
            )
            .asScala
            .getOrElse(
              key,
              throw new ConnectException(
                s"Failed to look up key [$key] in secret [${secret.getName}]. key not found"
              )
            )

        // decode the value
        (
          decodeKey(
            key = key,
            value = value,
            fileName = getFileName(rootDir, secretId, key.toLowerCase, separator)
          ),
          //Used to be `getTTL(client, secretId)` but we don't need this as our secrets don't expire. Calling getTTL
          // was failing as it calls DescribeSecret in the AWS SDK and the role we use to get secrets doesn't have the
          // DescribeSecret permission.
          None
        )

      case Failure(exception) =>
        throw new ConnectException(
          s"Failed to look up key [$key] in secret [$secretId}]",
          exception
        )
    }
  }
}

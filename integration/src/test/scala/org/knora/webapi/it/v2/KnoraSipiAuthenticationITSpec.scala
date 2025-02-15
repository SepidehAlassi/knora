/*
 * Copyright © 2021 - 2023 Swiss National Data and Service Center for the Humanities and/or DaSCH Service Platform contributors.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.knora.webapi.it.v2

import org.apache.pekko

import java.nio.file.Files
import java.nio.file.Paths
import scala.concurrent.Await
import scala.concurrent.duration._

import org.knora.webapi._
import org.knora.webapi.messages.store.triplestoremessages.RdfDataObject
import org.knora.webapi.messages.store.triplestoremessages.TriplestoreJsonProtocol
import org.knora.webapi.messages.v2.routing.authenticationmessages._
import org.knora.webapi.routing.Authenticator
import org.knora.webapi.routing.UnsafeZioRun
import org.knora.webapi.sharedtestdata.SharedTestDataADM

import pekko.http.scaladsl.model._
import pekko.http.scaladsl.model.headers.BasicHttpCredentials
import pekko.http.scaladsl.unmarshalling.Unmarshal

/**
 * Tests interaction between Knora and Sipi using Knora API v2.
 */
class KnoraSipiAuthenticationITSpec
    extends ITKnoraLiveSpec
    with AuthenticationV2JsonProtocol
    with TriplestoreJsonProtocol {

  private val anythingUserEmail = SharedTestDataADM.anythingAdminUser.email
  private val password          = SharedTestDataADM.testPass

  private val marblesOriginalFilename = "marbles.tif"
  private val pathToMarbles           = Paths.get("..", s"test_data/test_route/images/$marblesOriginalFilename")

  override lazy val rdfDataObjects: List[RdfDataObject] = List(
    RdfDataObject(
      path = "test_data/project_data/anything-data.ttl",
      name = "http://www.knora.org/data/0001/anything"
    )
  )

  "The Knora/Sipi authentication" should {
    lazy val loginToken: String = {
      val params =
        s"""
           |{
           |    "email": "$anythingUserEmail",
           |    "password": "$password"
           |}
              """.stripMargin
      val request                = Post(baseApiUrl + s"/v2/authentication", HttpEntity(ContentTypes.`application/json`, params))
      val response: HttpResponse = singleAwaitingRequest(request)
      assert(response.status == StatusCodes.OK)
      Await.result(Unmarshal(response.entity).to[LoginResponse], 1.seconds).token
    }

    "log in as a Knora user" in {
      loginToken.nonEmpty should be(true)
    }

    "successfully get an image with provided credentials inside cookie" in {

      // using cookie to authenticate when accessing sipi (test for cookie parsing in sipi)
      val KnoraAuthenticationCookieName = UnsafeZioRun.runOrThrow(Authenticator.calculateCookieName())
      val cookieHeader                  = headers.Cookie(KnoraAuthenticationCookieName, loginToken)

      // Request the permanently stored image from Sipi.
      val sipiGetImageRequest =
        Get(s"$baseInternalSipiUrl/0001/B1D0OkEgfFp-Cew2Seur7Wi.jp2/full/max/0/default.jpg") ~> addHeader(cookieHeader)
      val response = singleAwaitingRequest(sipiGetImageRequest)
      assert(response.status === StatusCodes.OK)
    }

    "accept a token in Sipi that has been signed by Knora" in {

      // The image to be uploaded.
      assert(Files.exists(pathToMarbles), s"File $pathToMarbles does not exist")

      // A multipart/form-data request containing the image.
      val sipiFormData = Multipart.FormData(
        Multipart.FormData.BodyPart(
          "file",
          HttpEntity.fromPath(MediaTypes.`image/tiff`, pathToMarbles),
          Map("filename" -> pathToMarbles.getFileName.toString)
        )
      )

      // Send a POST request to Sipi, asking it to convert the image to JPEG 2000 and store it in a temporary file.
      val sipiRequest  = Post(s"$baseInternalSipiUrl/upload?token=$loginToken", sipiFormData)
      val sipiResponse = singleAwaitingRequest(sipiRequest)
      assert(sipiResponse.status == StatusCodes.OK)
    }

    "not accept a token in Sipi that hasn't been signed by Knora" in {
      val invalidToken = "a_invalid_token"

      // The image to be uploaded.
      assert(Files.exists(pathToMarbles), s"File $pathToMarbles does not exist")

      // A multipart/form-data request containing the image.
      val sipiFormData = Multipart.FormData(
        Multipart.FormData.BodyPart(
          "file",
          HttpEntity.fromPath(MediaTypes.`image/tiff`, pathToMarbles),
          Map("filename" -> pathToMarbles.getFileName.toString)
        )
      )

      // Send a POST request to Sipi, asking it to convert the image to JPEG 2000 and store it in a temporary file.
      val sipiRequest  = Post(s"$baseInternalSipiUrl/upload?token=$invalidToken", sipiFormData)
      val sipiResponse = singleAwaitingRequest(sipiRequest)
      assert(sipiResponse.status == StatusCodes.Unauthorized)
    }

    "accept a request with valid credentials to clean_temp_dir route which requires basic auth" in {
      // set the environment variables
      val username = "clean_tmp_dir_user"
      val password = "clean_tmp_dir_pw"

      val request =
        Get(s"$baseInternalSipiUrl/clean_temp_dir") ~> addCredentials(BasicHttpCredentials(username, password))

      val response: HttpResponse = singleAwaitingRequest(request)
      assert(response.status == StatusCodes.OK)
    }

    "not accept a request with invalid credentials to clean_temp_dir route which requires basic auth" in {
      val username = "username"
      val password = "password"

      val request =
        Get(s"$baseInternalSipiUrl/clean_temp_dir") ~> addCredentials(BasicHttpCredentials(username, password))

      val response: HttpResponse = singleAwaitingRequest(request)
      assert(response.status == StatusCodes.Unauthorized)
    }
  }
}

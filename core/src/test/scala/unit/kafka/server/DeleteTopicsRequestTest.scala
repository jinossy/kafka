/**
  * Licensed to the Apache Software Foundation (ASF) under one or more
  * contributor license agreements.  See the NOTICE file distributed with
  * this work for additional information regarding copyright ownership.
  * The ASF licenses this file to You under the Apache License, Version 2.0
  * (the "License"); you may not use this file except in compliance with
  * the License.  You may obtain a copy of the License at
  *
  *    http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package kafka.server

import kafka.network.SocketServer
import kafka.utils._
import org.apache.kafka.common.protocol.{ApiKeys, Errors}
import org.apache.kafka.common.requests.{DeleteTopicsRequest, DeleteTopicsResponse, MetadataRequest, MetadataResponse}
import org.junit.Assert._
import org.junit.Test

import scala.collection.JavaConverters._

class DeleteTopicsRequestTest extends BaseRequestTest {

  @Test
  def testValidDeleteTopicRequests() {
    val timeout = 10000
    // Single topic
    TestUtils.createTopic(zkUtils, "topic-1", 1, 1, servers)
    validateValidDeleteTopicRequests(new DeleteTopicsRequest(Set("topic-1").asJava, timeout))
    // Multi topic
    TestUtils.createTopic(zkUtils, "topic-3", 5, 2, servers)
    TestUtils.createTopic(zkUtils, "topic-4", 1, 2, servers)
    validateValidDeleteTopicRequests(new DeleteTopicsRequest(Set("topic-3", "topic-4").asJava, timeout))
  }

  private def validateValidDeleteTopicRequests(request: DeleteTopicsRequest): Unit = {
    val response = sendDeleteTopicsRequest(request, 0)

    val error = response.errors.values.asScala.find(_ != Errors.NONE)
    assertTrue(s"There should be no errors, found ${response.errors.asScala}", error.isEmpty)

    request.topics.asScala.foreach { topic =>
      validateTopicIsDeleted(topic)
    }
  }

  @Test
  def testErrorDeleteTopicRequests() {
    val timeout = 30000
    val timeoutTopic = "invalid-timeout"

    // Basic
    validateErrorDeleteTopicRequests(new DeleteTopicsRequest(Set("invalid-topic").asJava, timeout),
      Map("invalid-topic" -> Errors.INVALID_TOPIC_EXCEPTION))

    // Partial
    TestUtils.createTopic(zkUtils, "partial-topic-1", 1, 1, servers)
    validateErrorDeleteTopicRequests(new DeleteTopicsRequest(Set(
      "partial-topic-1",
      "partial-invalid-topic").asJava, timeout),
      Map(
        "partial-topic-1" -> Errors.NONE,
        "partial-invalid-topic" -> Errors.INVALID_TOPIC_EXCEPTION
      )
    )

    // Timeout
    TestUtils.createTopic(zkUtils, timeoutTopic, 5, 2, servers)
    validateErrorDeleteTopicRequests(new DeleteTopicsRequest(Set(timeoutTopic).asJava, 1),
      Map(timeoutTopic -> Errors.REQUEST_TIMED_OUT))
    // The topic should still get deleted eventually
    TestUtils.waitUntilTrue(() => !servers.head.metadataCache.contains(timeoutTopic), s"Topic $timeoutTopic is never deleted")
    validateTopicIsDeleted(timeoutTopic)
  }

  private def validateErrorDeleteTopicRequests(request: DeleteTopicsRequest, expectedResponse: Map[String, Errors]): Unit = {
    val response = sendDeleteTopicsRequest(request, 0)
    val errors = response.errors.asScala
    assertEquals("The response size should match", expectedResponse.size, response.errors.size)

    expectedResponse.foreach { case (topic, expectedError) =>
      assertEquals("The response error should match", expectedResponse(topic), errors(topic))
      // If no error validate the topic was deleted
      if (expectedError == Errors.NONE) {
        validateTopicIsDeleted(topic)
      }
    }
  }

  @Test
  def testNotController() {
    val request = new DeleteTopicsRequest(Set("not-controller").asJava, 1000)
    val response = sendDeleteTopicsRequest(request, 0, notControllerSocketServer)

    val error = response.errors.asScala.head._2
    assertEquals("Expected controller error when routed incorrectly",  Errors.NOT_CONTROLLER, error)
  }

  private def validateTopicIsDeleted(topic: String): Unit = {
    val metadata = sendMetadataRequest(new MetadataRequest(List(topic).asJava)).topicMetadata.asScala
    TestUtils.waitUntilTrue (() => !metadata.exists(p => p.topic.equals(topic) && p.error() == Errors.NONE),
      s"The topic $topic should not exist")
  }

  private def sendDeleteTopicsRequest(request: DeleteTopicsRequest, version: Short, socketServer: SocketServer = controllerSocketServer): DeleteTopicsResponse = {
    val response = send(request, ApiKeys.DELETE_TOPICS, Some(version), socketServer)
    DeleteTopicsResponse.parse(response, version)
  }

  private def sendMetadataRequest(request: MetadataRequest): MetadataResponse = {
    val response = send(request, ApiKeys.METADATA)
    MetadataResponse.parse(response)
  }
}

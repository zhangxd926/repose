/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package org.openrepose.commons.utils.servlet.http

import java.util
import javax.servlet.http.HttpServletRequest

import org.apache.http.client.utils.DateUtils

import scala.collection.JavaConverters._
import scala.collection.immutable.{TreeMap, TreeSet}

/**
 * Created with IntelliJ IDEA.
 * User: adrian
 * Date: 5/27/15
 * Time: 10:25 AM
 */
class HttpServletRequestWrapper(originalRequest: HttpServletRequest)
  extends javax.servlet.http.HttpServletRequestWrapper(originalRequest)
  with HeaderInteractor {

  private var headerMap: Map[String, List[String]] = new TreeMap[String, List[String]]()(CaseInsensitiveStringOrdering)
  private var removedHeaders: Set[String] = new TreeSet[String]()(CaseInsensitiveStringOrdering)

  object CaseInsensitiveStringOrdering extends Ordering[String] {
    override def compare(x: String, y: String): Int = x compareToIgnoreCase y
  }

  def getHeaderNamesSet: Set[String] = {
    super.getHeaderNames.asScala.toSet.filterNot(removedHeaders.contains) ++ headerMap.keySet
  }

  override def getHeaderNames: util.Enumeration[String] = getHeaderNamesSet.toIterator.asJavaEnumeration

  override def getHeaderNamesList: util.List[String] = getHeaderNamesSet.toList.asJava

  override def getIntHeader(headerName: String): Int = Option(getHeader(headerName)).getOrElse("-1").toInt

  override def getHeaders(headerName: String): util.Enumeration[String] = getHeadersScalaList(headerName).toIterator.asJavaEnumeration

  override def getDateHeader(headerName: String): Long = {
    Option(getHeader(headerName)) match {
      case Some(headerValue) =>
        Option(DateUtils.parseDate(headerValue)) match {
          case Some(parsedDate) => parsedDate.getTime
          case None => throw new IllegalArgumentException("Header value could not be converted to a date")
        }
      case None => -1
    }
  }

  override def getHeader(headerName: String): String = getHeadersScalaList(headerName).headOption.orNull

  def getHeadersScalaList(headerName: String): List[String] = {
    if (removedHeaders.contains(headerName)) {
      List[String]()
    }
    else {
      headerMap.getOrElse(headerName, super.getHeaders(headerName).asScala.toList)
    }
  }

  override def getHeadersList(headerName: String): util.List[String] = getHeadersScalaList(headerName).asJava

  override def addHeader(headerName: String, headerValue: String): Unit = {
    val existingHeaders: List[String] = getHeadersScalaList(headerName) //this has to be done before we remove from the list,
                                                                        // because getting this list is partially based on the contents of the removed list
    if (removedHeaders.contains(headerName)) {
      removedHeaders = removedHeaders.filterNot(_.equalsIgnoreCase(headerName))
    }
    headerMap = headerMap + (headerName -> (existingHeaders :+ headerValue))
  }

  override def addHeader(headerName: String, headerValue: String, quality: Double): Unit = addHeader(headerName, headerValue + ";q=" + quality)

  override def appendHeader(headerName: String, headerValue: String): Unit = {
    val existingHeaders: List[String] = getHeadersScalaList(headerName)
    existingHeaders.headOption match {
      case (Some(value)) => {
        val newHeadValue: String = value + "," + headerValue
        headerMap = headerMap + (headerName -> (newHeadValue +: existingHeaders.tail))
      }
      case (None) => addHeader(headerName, headerValue)
    }
  }

  override def appendHeader(headerName: String, headerValue: String, quality: Double): Unit = appendHeader(headerName, headerValue + ";q=" + quality)

  override def removeHeader(headerName: String): Unit = {
    removedHeaders = removedHeaders + headerName
    headerMap = headerMap.filterKeys(!_.equalsIgnoreCase(headerName))
  }

  case class HeaderWithParameters(value: String, parameters: Map[String, String])

  case class HeaderWithQuality(value: String, quality: Double)

  def getValueWithQuality(headerValues: List[HeaderWithParameters]): List[HeaderWithQuality] = {
    headerValues.map { header =>
      HeaderWithQuality(header.value, Option(header.parameters.getOrElse("q", "1.0")).map(_.toDouble).getOrElse(0.0))
    }
  }

  def filterToQualityParameters(headerValues: List[HeaderWithParameters]): List[HeaderWithParameters] = {
    headerValues.map { header =>
      HeaderWithParameters(header.value, header.parameters.filterKeys(_.equals("q")))
    }
  }

  def breakoutHeaderParameters(headerValues: List[String]): List[HeaderWithParameters] = {
    headerValues.map { headerValue =>
      val splitValues: Array[String] = headerValue.split(";")
      val parametersList = splitValues.tail.map { parameterString =>
        val parameterParts: Array[String] = parameterString.split("=", 2)
        if (parameterParts.length == 2) {
          (parameterParts(0), parameterParts(1))
        }
        else {
          (parameterParts(0), "")
        }
      }
      HeaderWithParameters(splitValues.head, parametersList.toMap)
    }
  }

  def getPreferredHeader(headerValues: List[String]): String = {
    getValueWithQuality(filterToQualityParameters(breakoutHeaderParameters(headerValues))).sortWith(_.quality > _.quality).headOption.map(_.value).orNull
  }

  override def getPreferredHeader(headerName: String): String = getPreferredHeader(getHeadersScalaList(headerName))

  override def getPreferredSplittableHeader(headerName: String): String = getPreferredHeader(getSplittableHeader(headerName).asScala.toList)

  override def replaceHeader(headerName: String, headerValue: String): Unit = {
    headerMap = headerMap + (headerName -> List(headerValue))
    removedHeaders = removedHeaders.filterNot(_.equalsIgnoreCase(headerName))
  }

  override def replaceHeader(headerName: String, headerValue: String, quality: Double): Unit = replaceHeader(headerName, headerValue + ";q=" + quality)

  override def getSplittableHeader(headerName: String): util.List[String] = getHeadersScalaList(headerName).foldLeft(List[String]())((list, s) => list ++ s.split(",")).asJava
}

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

import java.io.{ByteArrayInputStream, InputStream}
import javax.servlet.ServletOutputStream

class ReadOnlyServletOutputStream(servletOutputStream: ServletOutputStream)
  extends ExtendedServletOutputStream with ByteArrayOutputStreamTrait {

  override def write(b: Int): Unit = {
    super.write(b)
    servletOutputStream.write(b)
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    super.write(b, off, len)
    servletOutputStream.write(b, off, len)
  }

  override def getOutputStreamAsInputStream: InputStream = new ByteArrayInputStream(super.toByteArray)

  override def setOutput(in: InputStream): Unit =
    throw new IllegalStateException("method should not be called if the ResponseMode is not set to MUTABLE")

  override def commit: Unit =
    throw new IllegalStateException("method should not be called if the ResponseMode is not set to MUTABLE")
}
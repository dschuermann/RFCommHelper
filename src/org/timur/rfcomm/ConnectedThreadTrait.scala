/*
 * This file is part of RFComm and AnyMime, a program to help you swap
 * files wirelessly between mobile devices.
 *
 * Copyright (C) 2012 Timur Mehrvarz, timur.mehrvarz(a)gmail(.)com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.timur.rfcomm

import java.io.InputStream
import java.io.OutputStream

trait ConnectedThreadTrait extends Thread {

  def init(mmInStream:InputStream, mmOutStream:OutputStream, 
           localDeviceAddr:String, localDeviceName:String, 
           remoteDeviceAddr:String, remoteDeviceName:String, 
           socketCloseFkt:() => Unit)

  def doFirstActor()

  def cancel()

  def isRunning() :Boolean

  def disconnectBackupConnection()

  def updateStreams(setMmInStream:InputStream, setMmOutStream:OutputStream)
}


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

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder


trait RFServiceTrait extends android.app.Service {

  var context:Context = null                        // set by activity onServiceConnected(), needed for Toast, runOnUiThread, etc

  var activityMsgHandler:Handler = null             // set by activity onServiceConnected()

  var rfCommHelper:RFCommHelper = null              // set by activity onServiceConnected() after new RFCommHelper()

  var connectedThread:ConnectedThreadTrait = null   // created by RFCommHelperService in connectedBt() or in connectedWiFi()

  def setContextMsgHandler(setContext:Context,setActivityMsgHandler:Handler)
  def setRfCommHelper(setRfCommHelper:RFCommHelper)
  def getRfCommHelper() :RFCommHelper

  def createConnectedThread()

  def connectViaBackupHost()

  def stopActiveConnection()
}


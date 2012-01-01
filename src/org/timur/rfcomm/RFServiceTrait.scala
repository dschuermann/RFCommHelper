package org.timur.rfcomm

import android.content.Context
import android.os.Handler

trait RFServiceTrait extends android.app.Service {

  var context:Context = null                        // must be set by activity onServiceConnected()

  var activityMsgHandler:Handler = null             // must be set by activity onServiceConnected()

  var rfCommHelper:RFCommHelper = null              // must be set by activity onServiceConnected() after new RFCommHelper()

  var connectedThread:ConnectedThreadTrait = null   // will be created by RFCommHelperService in connectedBt() or connectedWiFi

  def createConnectedThread()
}


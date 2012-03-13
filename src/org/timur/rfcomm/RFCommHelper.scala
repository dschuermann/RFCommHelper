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

import java.net.Socket
import java.net.InetSocketAddress
import java.net.InetAddress
import java.io.PrintStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.ArrayList
import java.util.Date

import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.ServiceConnection
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.DialogInterface
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.AlertDialog.Builder
import android.os.IBinder
import android.os.Environment
import android.os.Handler
import android.os.SystemClock
import android.os.Bundle
import android.widget.Toast
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.CompoundButton.OnCheckedChangeListener
import android.media.MediaPlayer
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams

import android.nfc.NfcAdapter
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.net.wifi.p2p.WifiP2pManager.ChannelListener
import android.net.wifi.p2p.WifiP2pManager.PeerListListener
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.NetworkInfo

// app-specific code that needs to stay in memory when the activity goes into background
// so that filetransfer can continue while the app is in background (and the activity might have been removed from memory)

object RFCommHelper {
  val MSG_SERVICE_FAILED = 50
  val MSG_SERVICE_INITIALIZED = 51
  val MSG_RADIO_AVAILABLE = 52

  // allow access from Java, see: http://stackoverflow.com/questions/8434013/can-i-access-a-scala-objects-val-without-parentheses-from-java
  @scala.reflect.BeanProperty val RADIO_BT:Int = 1
  @scala.reflect.BeanProperty val RADIO_BT_INSECURE:Int = 2
  @scala.reflect.BeanProperty val RADIO_P2PWIFI:Int = 4
  @scala.reflect.BeanProperty val RADIO_NFC:Int = 8
  
  def getBtPairedDevices(activity:Activity):java.util.ArrayList[String] = {
    val pairedDevicesArrayListOfStrings = new java.util.ArrayList[String]()
    val mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter
    if(mBluetoothAdapter!=null) {
      val pairedDevicesSet = mBluetoothAdapter.getBondedDevices
      if(pairedDevicesSet.size>0) {
		    // Create an ArrayAdapter that will make the Strings above appear in the ListView
        val pairedDevicesArrayListOfBluetoothDevices = new ArrayList[BluetoothDevice](pairedDevicesSet)
        if(pairedDevicesArrayListOfBluetoothDevices==null) {
          //Log.e(TAG, "getPairedDevices pairedDevicesArrayListOfBluetoothDevices==null")
          AndrTools.runOnUiThread(activity) { () =>
            Toast.makeText(activity, "Could not get pairedDevicesArrayListOfBluetoothDevices", Toast.LENGTH_LONG).show
          }
        } else {
          val iterator = pairedDevicesArrayListOfBluetoothDevices.iterator 
          while(iterator.hasNext) {
            val bluetoothDevice = iterator.next
            if(bluetoothDevice!=null) {
              //if(D) Log.i(TAG, "updateDevicesView ADD paired="+bluetoothDevice.getName)
              if(bluetoothDevice.getName!=null && bluetoothDevice.getName.size>0) {
                pairedDevicesArrayListOfStrings.add(bluetoothDevice.getName+"\n"+bluetoothDevice.getAddress+" bt paired")
              }
            }
          }
        }
      }
    }

    return pairedDevicesArrayListOfStrings
  }
}

class RFCommHelper(activity:Activity, 
                   msgFromServiceHandler:android.os.Handler, 
                   prefsPrivate:SharedPreferences, 
                   prefsSharedP2pBt:SharedPreferences,
                   prefsSharedP2pWifi:SharedPreferences,
                   appService:RFServiceTrait,
                   activityRuntimeClass:java.lang.Class[Activity],
                   mediaConfirmSound:MediaPlayer,
                   mediaFailSound:MediaPlayer,
                   radioTypeWanted:Int,  // all radio's meaningfull to the app (vs. rfCommService.desiredXXXXX = radios that are desired by the user)
                   radioDialogAllowed:Boolean,
                   acceptThreadSecureName:String, acceptThreadSecureUuid:String,
                   acceptThreadInsecureName:String, acceptThreadInsecureUuid:String,
                   ipPort:Int, 
                   appName:String) {

  var rfCommService:RFCommHelperService = null
  var connectAttemptFromNfc = false
  var mBluetoothAdapter:BluetoothAdapter = null

  private val TAG = "RFCommHelper"
  private val D = true
  private val REQUEST_ENABLE_BT = 101
  private val prefsPrivateEditor = prefsPrivate.edit

  private var activityDestroyed = false
  private var radioDialogPossibleAndNotYetShown = false
  private var wifiDirectBroadcastReceiver:BroadcastReceiver = null
  private var autoEnabledBt = false
  private var retryChannel = false
  @volatile private var activityResumed = false

  private val intentFilter = new IntentFilter()
  if(android.os.Build.VERSION.SDK_INT>=14) {
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
  }

  if(D) Log.i(TAG, "constructor startService('RFCommHelperService')")
  val serviceIntent = new Intent(activity, classOf[RFCommHelperService])
  //startService(serviceIntent)   // call this only, to keep service active after onDestroy()/unbindService()

  var rfCommServiceConnection = new ServiceConnection { 
    def onServiceDisconnected(className:ComponentName) { 
      if(D) Log.i(TAG, "constructor rfCommServiceConnection onServiceDisconnected set rfCommService=null")
      rfCommService = null
      msgFromServiceHandler.obtainMessage(RFCommHelper.MSG_SERVICE_FAILED, -1, -1, null).sendToTarget
    } 
    def onServiceConnected(className:ComponentName, rawBinder:IBinder) { 
      if(D) Log.i(TAG, "constructor rfCommServiceConnection onServiceConnected localBinder.getService ...")
      rfCommService = rawBinder.asInstanceOf[RFCommHelperService#LocalBinder].getService
      if(rfCommService==null) {
        Log.e(TAG, "constructor rfCommServiceConnection onServiceConnected no interface to service, rfCommService==null")
        val errMsg = "Error: failed to get service interface from binder"
        if(msgFromServiceHandler!=null)
          msgFromServiceHandler.obtainMessage(RFCommHelperService.ALERT_MESSAGE, -1, -1, errMsg).sendToTarget
        else
          AndrTools.runOnUiThread(activity) { () =>
            Toast.makeText(activity, errMsg, Toast.LENGTH_LONG).show    // todo: create more 'human' text
          }
        return
      }

      // got connected to rfCommService

      if(D) Log.i(TAG, "constructor rfCommServiceConnection onServiceConnected got rfCommService object, activity="+activity+" activityResumed="+activityResumed)
      rfCommService.activity = activity
      rfCommService.activityRuntimeClass = activityRuntimeClass   // needed for nfcPendingIntent only
      rfCommService.appService = appService                       // createConnectedThread, connectedThread.start, etc.
      rfCommService.activityMsgHandler = msgFromServiceHandler
      rfCommService.prefsSharedP2pBt = prefsSharedP2pBt
      rfCommService.prefsSharedP2pBtEditor = prefsSharedP2pBt.edit
      rfCommService.prefsSharedP2pWifi = prefsSharedP2pWifi
      rfCommService.prefsSharedP2pWifiEditor = prefsSharedP2pWifi.edit
      rfCommService.acceptThreadSecureName = acceptThreadSecureName
      rfCommService.acceptThreadSecureUuid = acceptThreadSecureUuid
      rfCommService.acceptThreadInsecureName = acceptThreadInsecureName
      rfCommService.acceptThreadInsecureUuid = acceptThreadInsecureUuid
      rfCommService.ipPort = ipPort
      rfCommService.appName = appName
      rfCommService.activityResumed = activityResumed

      if(D) Log.i(TAG, "constructor rfCommServiceConnection onServiceConnected prefsPrivate="+prefsPrivate+" radioTypeWanted & RFCommHelper.RADIO_BT="+(radioTypeWanted & RFCommHelper.RADIO_BT))
      if(prefsPrivate!=null) {
        // note: the desiredRADIO settings are off by default
        // if the parent app requests any of these settings via radioTypeWanted, the prefsPrivate are read
        // if there was no persistent setting yet, the default is then true
      
        if((radioTypeWanted & RFCommHelper.RADIO_BT)!=0) {
          rfCommService.desiredBluetooth = prefsPrivate.getBoolean("radioBluetooth", true)
          if(D) Log.i(TAG, "constructor rfCommServiceConnection onServiceConnected rfCommService.desiredBluetooth="+rfCommService.desiredBluetooth)
        }

        if((radioTypeWanted & RFCommHelper.RADIO_P2PWIFI)!=0)
          rfCommService.desiredWifiDirect = prefsPrivate.getBoolean("radioWifiDirect", true)

        if((radioTypeWanted & RFCommHelper.RADIO_NFC)!=0)
          rfCommService.desiredNfc = prefsPrivate.getBoolean("radioNfc", true)         

        rfCommService.pairedBtOnly = prefsPrivate.getBoolean("pairedBtOnly", false)
      }

      // tell app: rfCommService is initialized
      msgFromServiceHandler.obtainMessage(RFCommHelper.MSG_SERVICE_INITIALIZED, -1, -1, null).sendToTarget

      // we need to call our onResumeAction method ourselfs now, because the original 1st onResume was not able to call us, because this service was not yet loaded then
      if(D) Log.i(TAG, "constructor rfCommServiceConnection onServiceConnected activityResumed="+rfCommService.activityResumed+" -> onResume")
      new Thread() {
        override def run() {
          onResumeAction(false)  // this will run radioSelect and start the AcceptThread(s)

          // do we have a startup nfc-event?
          if(activity!=null && activity.getIntent!=null && activity.getIntent.getAction==android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED) {
            if(D) Log.i(TAG, "startup NDEF_DISCOVERED -> onNewIntent()")
            onNewIntent(activity.getIntent)
          }
        }
      }.start                        
    } 
  } 
  if(rfCommServiceConnection!=null) {
    if(D) Log.i(TAG, "constructor bindService ...")
    activity.bindService(serviceIntent, rfCommServiceConnection, Context.BIND_AUTO_CREATE)
    if(D) Log.i(TAG, "constructor bindService done")
    radioDialogPossibleAndNotYetShown = true // will be evaluated in onResume
  } else {
    Log.e(TAG, "constructor bindService failed")
    msgFromServiceHandler.obtainMessage(RFCommHelper.MSG_SERVICE_FAILED, -1, -1, null).sendToTarget
  }

  def setActivityResumed(state:Boolean) {
    activityResumed = state
  }

  def stopActiveConnection() {
    if(rfCommService!=null)
      rfCommService.stopActiveConnection
  }

  def state() :Int = {
    if(rfCommService!=null)
      return rfCommService.state
    return -1
  }
  
  def getWifiP2pManager() :WifiP2pManager = {
    //return wifiP2pManager
    if(rfCommService!=null)
      return rfCommService.wifiP2pManager
    return null
  }

  def getRFCommService() :RFCommHelperService = {
    return rfCommService
  }

  private def initBt() {
    if(D) Log.i(TAG, "initBt rfCommService="+rfCommService)
    if(rfCommService!=null) {
      if(D) Log.i(TAG, "initBt rfCommService.desiredBluetooth="+rfCommService.desiredBluetooth+" mBluetoothAdapter="+mBluetoothAdapter)
      if(rfCommService.desiredBluetooth && mBluetoothAdapter!=null && mBluetoothAdapter.isEnabled) {
        // stop any old bluetooth accept threads
        if(D) Log.i(TAG, "initBt stop any old bluetooth accept threads -> rfCommService.stopAcceptThreads")
        rfCommService.stopAcceptThreads

        // start bluetooth accept thread
        if(D) Log.i(TAG, "initBt rfCommService.start pairedBtOnly="+rfCommService.pairedBtOnly+" ...")
        rfCommService.startBtAcceptThreads // -> bt (new AcceptThread()).start -> run()
      }

      // display state of bt
      msgFromServiceHandler.obtainMessage(RFCommHelperService.UI_UPDATE, -1, -1).sendToTarget
    }
  }

  def storeRadioSelection(selectedBt:Boolean, selectedWifi:Boolean, selectedNfc:Boolean, pairedBtOnly:Boolean) {
    if(D) Log.i(TAG, "storeRadioSelection prefsPrivateEditor="+prefsPrivateEditor+" selectedBt="+selectedBt+
                     " selectedWifi="+selectedWifi+" selectedNfc="+selectedNfc+" pairedBtOnly="+pairedBtOnly)
    if(prefsPrivateEditor!=null) {
      prefsPrivateEditor.putBoolean("radioBluetooth",selectedBt)
      prefsPrivateEditor.putBoolean("radioWifiDirect",selectedWifi)
      prefsPrivateEditor.putBoolean("radioNfc",selectedNfc)
      prefsPrivateEditor.putBoolean("pairedBtOnly",pairedBtOnly)
      prefsPrivateEditor.commit
      if(D) Log.i(TAG, "storeRadioSelection commited")
    }
  }
  
  // dynamically created dialog box (not inflated from xml)
  def radioDialog(backKeyIsExit:Boolean=false) {
    // user did not yet see the dialog to turn all wanted radio on, show it 
    if(D) Log.i(TAG, "radioDialog resumed="+rfCommService.activityResumed)
    if(activityDestroyed) {
      if(D) Log.i(TAG, "radioDialog aborted because: activityDestroyed="+activityDestroyed)
      return
    }

    val radioSelectDialogBuilder = new AlertDialog.Builder(activity)
    radioSelectDialogBuilder.setTitle("Radio selection")

    val radioSelectDialogLayout = new LinearLayout(activity)
    radioSelectDialogLayout.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT))
    radioSelectDialogLayout.setPadding(40, 40, 40, 40)
    radioSelectDialogLayout.setOrientation(LinearLayout.VERTICAL)

    if(D) Log.i(TAG, "radioDialog rfCommService.desiredBluetooth="+rfCommService.desiredBluetooth)

    val radioBluetoothCheckbox = new CheckBox(activity)
    if((radioTypeWanted&RFCommHelper.RADIO_BT)!=0) {
      radioBluetoothCheckbox.setText("Bluetooth not available")
      radioBluetoothCheckbox.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,19.0f)
      if(mBluetoothAdapter==null)
        radioBluetoothCheckbox.setEnabled(false)    // disable if bt-hardware is not available
      else {
        radioBluetoothCheckbox.setText("Bluetooth (off)")
        if(mBluetoothAdapter.isEnabled)
          radioBluetoothCheckbox.setText("Bluetooth")
        radioBluetoothCheckbox.setChecked(rfCommService.desiredBluetooth)
      }
      radioSelectDialogLayout.addView(radioBluetoothCheckbox)
    }

    val radioPairlessBtCheckbox = new CheckBox(activity)
    radioPairlessBtCheckbox.setText("Pairless Bluetooth")
    radioPairlessBtCheckbox.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,19.0f)
    if(android.os.Build.VERSION.SDK_INT<14) {
      radioPairlessBtCheckbox.setEnabled(false)
      radioPairlessBtCheckbox.setChecked(false)
    } else {
      radioPairlessBtCheckbox.setEnabled(radioBluetoothCheckbox.isChecked)
      radioPairlessBtCheckbox.setChecked(!rfCommService.pairedBtOnly)
      
      // disable radioPairlessBtCheckbox if radioBluetoothCheckbox is disabled
      radioBluetoothCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
        override def onCheckedChanged(buttonView:CompoundButton, isChecked:Boolean) {
          radioPairlessBtCheckbox.setEnabled(isChecked)
        }
      })
    }
    radioSelectDialogLayout.addView(radioPairlessBtCheckbox)


    val radioWifiDirectCheckbox = new CheckBox(activity)
    if((radioTypeWanted&RFCommHelper.RADIO_P2PWIFI)!=0) {
      radioWifiDirectCheckbox.setText("WiFi Direct not available")
      radioWifiDirectCheckbox.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,19.0f)
      if(rfCommService.wifiP2pManager==null || android.os.Build.VERSION.SDK_INT<14)
        radioWifiDirectCheckbox.setEnabled(false)   // disable if wifip2p-hardware is not available
      else {
        radioWifiDirectCheckbox.setText("WiFi Direct (off)")
        if(rfCommService.isWifiP2pEnabled)
          radioWifiDirectCheckbox.setText("WiFi Direct")
        radioWifiDirectCheckbox.setChecked(rfCommService.desiredWifiDirect)
      }
      radioSelectDialogLayout.addView(radioWifiDirectCheckbox)
    }

    val radioNfcCheckbox = new CheckBox(activity)
    if((radioTypeWanted&RFCommHelper.RADIO_NFC)!=0) {
      radioNfcCheckbox.setText("NFC not available")
      radioNfcCheckbox.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,19.0f)
      if(rfCommService.mNfcAdapter==null || android.os.Build.VERSION.SDK_INT<10)
        radioNfcCheckbox.setEnabled(false)          // disable if nfc-hardware is not available
      else {
        radioNfcCheckbox.setText("NFC (off)")
        if(rfCommService.mNfcAdapter.isEnabled)
          radioNfcCheckbox.setText("NFC")
        radioNfcCheckbox.setChecked(rfCommService.desiredNfc)
      }
      radioSelectDialogLayout.addView(radioNfcCheckbox)
    }

    radioSelectDialogBuilder.setView(radioSelectDialogLayout)

    val backKeyLabel = if(backKeyIsExit) "Exit" else "Close"
    radioSelectDialogBuilder.setNegativeButton(backKeyLabel, new DialogInterface.OnClickListener() {
      def onClick(dialogInterface:DialogInterface, m:Int) {
        // persist desired-flags
        storeRadioSelection(radioBluetoothCheckbox.isChecked,radioWifiDirectCheckbox.isChecked,radioNfcCheckbox.isChecked,!radioPairlessBtCheckbox.isChecked)
        if(backKeyIsExit)
          activity.finish
      }
    })

    radioSelectDialogBuilder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
      def onClick(dialogInterface:DialogInterface, m:Int) {
        // this is just to create the OK button
        // evaluation is found below under setOnShowListener()/onClick()
      }
    })

    if(backKeyIsExit)
      radioSelectDialogBuilder.setOnKeyListener(new DialogInterface.OnKeyListener() {
        override def onKey(dialogInterface:DialogInterface, keyCode:Int, keyEvent:KeyEvent) :Boolean = {
          if(keyCode == KeyEvent.KEYCODE_BACK && keyEvent.getAction()==KeyEvent.ACTION_UP /*&& !keyEvent.isCanceled()*/) {
            if(D) Log.i(TAG, "radioDialog onKeyDown KEYCODE_BACK backKeyIsExit="+backKeyIsExit)
            storeRadioSelection(radioBluetoothCheckbox.isChecked,radioWifiDirectCheckbox.isChecked,radioNfcCheckbox.isChecked,!radioPairlessBtCheckbox.isChecked)
            if(backKeyIsExit)
              activity.finish
            return true
          }
          return false
        }                   
      })

    def radioDialogConfirm() {
      // evaluate checkboxes and set desired booleans
      rfCommService.desiredBluetooth = radioBluetoothCheckbox.isChecked
      rfCommService.desiredWifiDirect = radioWifiDirectCheckbox.isChecked
      rfCommService.desiredNfc = radioNfcCheckbox.isChecked
      rfCommService.pairedBtOnly = !radioPairlessBtCheckbox.isChecked
      if(D) Log.i(TAG, "radioSelectDialog radioDialogConfirm desiredBluetooth="+rfCommService.desiredBluetooth+" desiredWifiDirect="+rfCommService.desiredWifiDirect+" desiredNfc="+rfCommService.desiredNfc+" ################")

      // persist desired-flags
      storeRadioSelection(rfCommService.desiredBluetooth,rfCommService.desiredWifiDirect,rfCommService.desiredNfc, rfCommService.pairedBtOnly)

      switchOnDesiredRadios
      initBt  // startBtAcceptThreads, if desiredBluetooth && mBluetoothAdapter.isEnabled

      // initialize nfc (initialize nfc for wifi will come through WiFiDirectBroadcastReceiver WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
      if(rfCommService.desiredNfc && rfCommService.mNfcAdapter!=null && rfCommService.mNfcAdapter.isEnabled) {
        if(D) Log.i(TAG, "radioDialog after radioSelectDialog -> nfcServiceSetup")
        rfCommService.nfcServiceSetup
        
      } else {
        // disable nfc
        if(rfCommService.mNfcAdapter!=null && rfCommService.mNfcAdapter.isEnabled) {
          if(rfCommService.activityResumed) {
            try {
              rfCommService.mNfcAdapter.disableForegroundDispatch(activity)
            } catch {
              case npex:java.lang.NullPointerException =>
                Log.e(TAG, "radioDialog disableForegroundDispatch NullPointerException")
            }
          }
          rfCommService.mNfcAdapter.setNdefPushMessage(null, activity)
        }
      }

      radioDialogPossibleAndNotYetShown = false  // no need to show radioDialog anymore on successive onResume's
      
      // this is the point in time when the app can make use of the radios
      if(D) Log.i(TAG, "radioDialog finished - send MSG_RADIO_AVAILABLE")
      msgFromServiceHandler.obtainMessage(RFCommHelper.MSG_RADIO_AVAILABLE, -1, -1, null).sendToTarget     
      // todo: this arrives before wifi-direct was (manually) switched on
      // todo: this arrives before bluetooth was (automatically) switched on
    }

    if(radioDialogAllowed) {
      if(D) Log.i(TAG, "radioSelectDialog radioDialogAllowed==true")
      AndrTools.runOnUiThread(activity) { () =>
        val radioSelectDialog = radioSelectDialogBuilder.create
        var alertReady = false
        radioSelectDialog.setOnShowListener(new DialogInterface.OnShowListener() {
          override def onShow(dialogInterface:DialogInterface) {
            if(alertReady==false) {
              val button = radioSelectDialog.getButton(DialogInterface.BUTTON_POSITIVE)
              button.setOnClickListener(new View.OnClickListener() {
                override def onClick(view:View) {
                  dialogInterface.cancel
                  radioDialogConfirm
                }
              })
              alertReady = true
            }
          }
        })
        radioSelectDialog.show
      }

    } else {
      if(D) Log.i(TAG, "radioSelectDialog radioDialogAllowed==false")
      // (virtually) check-on all desired radios
      rfCommService.desiredBluetooth = (radioTypeWanted & RFCommHelper.RADIO_BT)!=0
      rfCommService.desiredWifiDirect = (radioTypeWanted & RFCommHelper.RADIO_P2PWIFI)!=0
      rfCommService.desiredNfc = (radioTypeWanted & RFCommHelper.RADIO_NFC)!=0
      rfCommService.pairedBtOnly = false  // todo: hardcoded pairedBtOnly=false if radioDialogAllowed=false ?

      radioDialogConfirm
    }
  }

/*
  // strange p2pWifi error callback
  def wifiP2pChannelListener = new WifiP2pManager.ChannelListener() {
    def onChannelDisconnected() {
      if(rfCommService.wifiP2pManager!=null && !retryChannel) {
        if(D) Log.i(TAG, "onChannelDisconnected Channel lost - re-init... ##################")
        Toast.makeText(activity, "Channel lost - re-init...", Toast.LENGTH_SHORT).show
        retryChannel = true
        rfCommService.p2pChannel = rfCommService.wifiP2pManager.initialize(activity, activity.getMainLooper, null)
      } else {
        if(D) Log.i(TAG, "onChannelDisconnected WiFi Direct channel is probably lost premanently. ##################")
        Toast.makeText(activity, "Severe! WiFi Direct channel is probably lost premanently.", Toast.LENGTH_LONG).show
      }
    }
  }
*/

  def onResumeAction(checkResumed:Boolean) {
    if(D) Log.i(TAG, "onResumeAction activityResumed="+rfCommService.activityResumed+" checkResumed="+checkResumed)

    if((radioTypeWanted&RFCommHelper.RADIO_NFC)!=0 && rfCommService.mNfcAdapter==null) {
      // find out if nfc hardware is supported (not necessarily on)
      if(android.os.Build.VERSION.SDK_INT>=10 && rfCommService.mNfcAdapter==null) {
        try {
          rfCommService.mNfcAdapter = NfcAdapter.getDefaultAdapter(activity)
          if(D) Log.i(TAG, "onResumeAction mNfcAdapter="+rfCommService.mNfcAdapter)
          // continue to setup nfc in nfcServiceSetup()
        } catch {
          case ncdferr:java.lang.NoClassDefFoundError =>
            Log.e(TAG, "onResumeAction NfcAdapter.getDefaultAdapter(activity) failed "+ncdferr)
        }
      }
      if(rfCommService.mNfcAdapter!=null) {
        if(D) Log.i(TAG, "onResumeAction nfc supported")
      } else {
        if(D) Log.i(TAG, "onResumeAction nfc not supported")
      }
    }

    if((radioTypeWanted&RFCommHelper.RADIO_BT)!=0 && mBluetoothAdapter==null) {
      // find out if bt-hardware is supported (not necessarily on)
      if(mBluetoothAdapter==null) {
        // get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter
        // If the adapter is null, then Bluetooth is not supported (mBluetoothAdapter must not be null, even if turned off)
      }
      if(mBluetoothAdapter!=null) {
        if(D) Log.i(TAG, "onResumeAction bt supported")
      } else {
        Log.e(TAG, "onResumeAction bt not supported")
      }
    }

    if((radioTypeWanted&RFCommHelper.RADIO_P2PWIFI)!=0 && rfCommService.wifiP2pManager==null) {
      // we need to initialze wifiP2pManager and wifiDirectBroadcastReceiver, in order to find out if 1. wifi-direct is supported 2. wifi-direct is enabled
      // wifiP2pManager will fail if the API is not supported, wifiDirectBroadcastReceiver will tell us if the hardware is enabled
      if(android.os.Build.VERSION.SDK_INT>=14 && rfCommService.wifiP2pManager==null) {
        rfCommService.wifiP2pManager = activity.getSystemService(Context.WIFI_P2P_SERVICE).asInstanceOf[WifiP2pManager]
        if(rfCommService.wifiP2pManager!=null) {
          // register p2pChannel and wifiDirectBroadcastReceiver
          // note: this will result in a call to setIsWifiP2pEnabled(), so we know wether p2pWifi is already activated!
          if(D) Log.i(TAG, "onResumeAction wifiP2p is supported, initialze p2pChannel and register wifiDirectBroadcastReceiver")
          try {
            // next step requires android.permission.CHANGE_WIFI_STATE
            rfCommService.p2pChannel = rfCommService.wifiP2pManager.initialize(activity, activity.getMainLooper, /*wifiP2pChannelListener*/ null)
            wifiDirectBroadcastReceiver = rfCommService.newWiFiDirectBroadcastReceiver()
            activity.registerReceiver(wifiDirectBroadcastReceiver, intentFilter)

            rfCommService.wifiP2pManager.discoverPeers(rfCommService.p2pChannel, new WifiP2pManager.ActionListener() {
              override def onFailure(reasonCode:Int) {
                val reasonString = if(reasonCode==0) "Error" else if(reasonCode==1) "P2P_UNSUPPORTED" else if(reasonCode==2) "Busy" else "unknown"
                if(D) Log.i(TAG, "onResumeAction wifiP2pManager.discoverPeers failed reasonCode="+reasonCode+" "+reasonString)
              }
              override def onSuccess() {
                //if(D) Log.i(TAG, "onResumeAction wifiP2pManager.discoverPeers onSuccess")
              }
            })
          } catch {
            case secex:java.lang.SecurityException =>
              if(D) Log.i(TAG, "onResumeAction SecurityException on wifiP2pManager.initialize")
          }
        }
      }
      if(rfCommService.wifiP2pManager==null) {
        if(D) Log.i(TAG, "onResumeAction wifiP2p not supported")
      }
    }

    // next we will start one or two AcceptThreads, either through radioDialog() or via initBtWithNfc()
    // at this point we need the UUID's for the mBluetoothAdapter.listenUsingxxxxx calls
    if(D) Log.i(TAG, "onResumeAction radioDialogPossibleAndNotYetShown="+radioDialogPossibleAndNotYetShown)
    if(radioDialogPossibleAndNotYetShown) {
      // if all desired radio is already on, we don't need to show the radio dialog
      if((radioTypeWanted&RFCommHelper.RADIO_P2PWIFI)!=0 && rfCommService.wifiP2pManager!=null && !rfCommService.isWifiP2pEnabled) {
        // the app want's to use p2pWifi, if it is supported by this device
        // however, we need to wait a little for wifiDirectBroadcastReceiver to call our setIsWifiP2pEnabled() method, so we know if isWifiP2pEnabled is true
        // if isWifiP2pEnabled is true, we might not need to show the radio-select dialog
        if(D) Log.i(TAG, "onResumeAction little sleep to find out about the state of isWifiP2pEnabled="+rfCommService.isWifiP2pEnabled)
        try { Thread.sleep(400) } catch { case ex:Exception => }
        if(D) Log.i(TAG, "onResumeAction little sleep to find out about the state of isWifiP2pEnabled="+rfCommService.isWifiP2pEnabled+" DONE ##############")
        // check activityResumed state after sleep
      }

      if(checkResumed && rfCommService.activityResumed==false) {
        if(D) Log.i(TAG, "onResumeAction rfCommService.activityResumed==false abort")
        return
      }

      var radioDialogNeeded = false
      if((radioTypeWanted&RFCommHelper.RADIO_BT)!=0 && mBluetoothAdapter!=null && !mBluetoothAdapter.isEnabled)
        radioDialogNeeded = true
      if((radioTypeWanted&RFCommHelper.RADIO_P2PWIFI)!=0 && rfCommService.wifiP2pManager!=null && !rfCommService.isWifiP2pEnabled)
        radioDialogNeeded = true
      if((radioTypeWanted&RFCommHelper.RADIO_NFC)!=0 && rfCommService.mNfcAdapter!=null && !rfCommService.mNfcAdapter.isEnabled)
        radioDialogNeeded = true
      if(D) Log.i(TAG, "onResumeAction radioTypeWanted="+radioTypeWanted+" radioDialogNeeded="+radioDialogNeeded)
      if(radioDialogNeeded) {
        // show the radio dialog
        if(D) Log.i(TAG, "onResumeAction new thread -> radioDialog")
        new Thread() {
          override def run() {
            radioDialog(true) // will turn radioDialogPossibleAndNotYetShown off 
          }
        }.start

      } else {
        radioDialogPossibleAndNotYetShown = false
        initBt  // start bt-accept-thread and init-nfc

        // initialize nfc (initialize nfc for wifi will come through WiFiDirectBroadcastReceiver WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        if(rfCommService.desiredNfc && rfCommService.mNfcAdapter!=null && rfCommService.mNfcAdapter.isEnabled) {
          if(D) Log.i(TAG, "onResumeAction -> nfcServiceSetup")
          rfCommService.nfcServiceSetup
        } else {
          // disable nfc
          if(rfCommService.mNfcAdapter!=null && rfCommService.mNfcAdapter.isEnabled) {
            if(D) Log.i(TAG, "onResumeAction disable nfc - activityResumed="+rfCommService.activityResumed)
            if(rfCommService.activityResumed) {
              try {
                rfCommService.mNfcAdapter.disableForegroundDispatch(activity)
              } catch {
                case npex:java.lang.NullPointerException =>
                  Log.e(TAG, "onResumeAction disable nfc - NullPointerException")
              }
            }
            rfCommService.mNfcAdapter.setNdefPushMessage(null, activity)
          }
        }

        // this is the point in time when the app can make use of the radios
        if(D) Log.i(TAG, "not radioDialogNeeded finished - send MSG_RADIO_AVAILABLE")
        msgFromServiceHandler.obtainMessage(RFCommHelper.MSG_RADIO_AVAILABLE, -1, -1, null).sendToTarget
      }

    } else {
      new Thread() {
        override def run() {
          // delay this, so that user can still exit app if wanted
          try { Thread.sleep(600) } catch { case ex:Exception => }
          if(!activityDestroyed) {
            // todo tmtmtm: maybe better popup the radio-dialog here again?
            switchOnDesiredRadios
          }
        }
      }.start
    }

    // check activityResumed again
    if(checkResumed && rfCommService.activityResumed==false) {
      if(D) Log.i(TAG, "onResumeAction before nfc rfCommService.activityResumed==false abort")
      return
    }

    if(rfCommService.desiredNfc && rfCommService.mNfcAdapter!=null && rfCommService.mNfcAdapter.isEnabled) {
      if(rfCommService.nfcPendingIntent==null) {
        Log.e(TAG, "onResumeAction rfCommService.nfcPendingIntent==null cannot do enableForegroundDispatch")

      } else {
        if(D) Log.i(TAG, "onResumeAction rfCommService.mNfcAdapter.enableForegroundDispatch ...")
        AndrTools.runOnUiThread(activity) { () =>
          // enableForegroundDispatch() must be called from the main thread, and only when the activity is in the foreground (resumed). 
          // Also, activities must call disableForegroundDispatch(Activity) before the completion of their onPause() 
          // callback to disable foreground dispatch after it has been enabled. 
          try {
            rfCommService.mNfcAdapter.enableForegroundDispatch(activity, rfCommService.nfcPendingIntent, rfCommService.nfcFilters, rfCommService.nfcTechLists)
            if(D) Log.i(TAG, "onResumeAction enableForegroundDispatch done")
          } catch {
            case npex:java.lang.NullPointerException =>
              Log.e(TAG, "onResumeAction rfCommService.mNfcAdapter.enableForegroundDispatch NullPointerException")
          }
        }
      }
      if(rfCommService.nfcForegroundPushMessage!=null) {
        if(D) Log.i(TAG, "onResumeAction setNdefPushMessage ...")
        // todo: next statement results in "java.lang.IllegalStateException: Can not perform this action after onSaveInstanceState"
        // if started via adb on sleeping device
        rfCommService.mNfcAdapter.setNdefPushMessage(rfCommService.nfcForegroundPushMessage, activity)
        //if(D) Log.i(TAG, "onResumeAction setNdefPushMessage done")
      }
    }
  }

  def onResume() {
    if(D) Log.i(TAG, "onResume mNfcAdapter="+rfCommService.mNfcAdapter+" wifiP2pManager="+rfCommService.wifiP2pManager+" isWifiP2pEnabled="+rfCommService.isWifiP2pEnabled)
    if(rfCommService==null) {
      activityResumed = true  // only so we can report the current resumed state to rfCommService when it has initialized
      if(D) Log.i(TAG, "onResume cannot compete due to rfCommService==null")
      return
    }

    rfCommService.activityResumed = true
    onResumeAction(true)
  }


  def onPause() {
    if(D) Log.i(TAG, "onPause...")
    // todo tmtmtm: if this is just a "small onPause" (triggered by nfc-system-animation)
    //              rfcommservice will NOT be able to answer an incoming bt-connect-request   

    if(rfCommService==null) {
      activityResumed = false  // only so we can report the current resumed state to rfCommService when it has initialized
      return
    }

    rfCommService.activityResumed = false
    if(rfCommService.mNfcAdapter!=null && rfCommService.mNfcAdapter.isEnabled) {
      try {
        rfCommService.mNfcAdapter.disableForegroundDispatch(activity)
      } catch {
        case npex:java.lang.NullPointerException =>
          Log.e(TAG, "onPause  disableForegroundDispatch NullPointerException")
      }
      rfCommService.mNfcAdapter.setNdefPushMessage(null, activity)
      if(D) Log.i(TAG, "onPause disable ForegroundDispatch + NdefPushMessage done")
    }
  }

  def onDestroy() {
    if(D) Log.i(TAG, "onDestroy shutdown everything ...")
    if(rfCommService!=null) {
      if(rfCommService.appService!=null) {
        if(D) Log.i(TAG, "onDestroy appService.stopActiveConnection ...")
        rfCommService.appService.stopActiveConnection
      }

      if(D) Log.i(TAG, "onDestroy rfCommService.stopAcceptThreads ...")
      rfCommService.stopAcceptThreads

      if(D) Log.i(TAG, "onDestroy addAllDevicesUnregister ...")
      addAllDevicesUnregister
      rfCommService.activity = null

    } else {
      Log.e(TAG, "onDestroy rfCommService=null cannot call stopActiveConnection/stopAcceptThreads/addAllDevicesUnregister")
    }
    
    if(autoEnabledBt && mBluetoothAdapter!=null && mBluetoothAdapter.isEnabled) {
      if(D) Log.i(TAG, "onDestroy auto-disable bt")
      mBluetoothAdapter.disable
    }

    if(rfCommServiceConnection!=null) {
      activity.unbindService(rfCommServiceConnection)
      // note: our service will exit here, since we DID NOT use startService in front of bindService - this is our intent!
      if(D) Log.i(TAG, "onDestroy unbindService done")
      rfCommServiceConnection=null
    }

    if(rfCommService.wifiP2pManager!=null && rfCommService.p2pChannel!=null) {
      if(D) Log.i(TAG, "onDestroy wifiP2pManager.removeGroup")
      // we don't care for the removeGroup() result, it's all over
      rfCommService.wifiP2pManager.removeGroup(rfCommService.p2pChannel, null)

      if(wifiDirectBroadcastReceiver!=null) {
        if(D) Log.i(TAG, "onDestroy unregisterReceiver(wifiDirectBroadcastReceiver)")
        activity.unregisterReceiver(wifiDirectBroadcastReceiver)
        wifiDirectBroadcastReceiver = null
      }
      rfCommService.p2pConnected = false  // maybe not necessary
    }

    activityDestroyed=true
  }

  def onNewIntent(intent:Intent) :Boolean = {
    // all sort of intents may arrive here... for instance ACTION_NDEF_DISCOVERED
    //if(D) Log.i(TAG, "onNewIntent intent="+intent+" intent.getAction="+intent.getAction+" mNfcAdapter="+rfCommService.mNfcAdapter)
    // we are interested in nfc-intents (ACTION_NDEF_DISCOVERED)
    if(android.os.Build.VERSION.SDK_INT>=10 && rfCommService.mNfcAdapter!=null && intent.getAction==NfcAdapter.ACTION_NDEF_DISCOVERED) {
      val ncfActionString = NfcHelper.checkForNdefAction(activity, intent)
      // this is a nfc-intent, ncfActionString may look something like this: "bt=xxyyzzxxyyzz|p2pWifi=xx:yy:zz:xx:yy:zz"
      if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED NfcEnabled="+rfCommService.mNfcAdapter.isEnabled+" ncfActionString=["+ncfActionString+"] desiredWifiDirect="+rfCommService.desiredWifiDirect+" desiredBluetooth="+rfCommService.desiredBluetooth)
      if(rfCommService.mNfcAdapter.isEnabled && ncfActionString!=null) {

        val idxAppname = ncfActionString.indexOf("app=")
        if(idxAppname<0) {
          if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED ncfActionString no 'app=' found in received ncfActionString")
          if(mediaFailSound!=null)
            new Thread() {
              override def run() {
                // play negative sound after a short amount
                try { Thread.sleep(1400) } catch { case ex:Exception => }
                mediaFailSound.start
              }
            }.start                        
          return true
        }

        var otherAppName = ncfActionString.substring(idxAppname+4)
        if(otherAppName==null) {
          if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED ncfActionString no 'app=xxx' found in received ncfActionString")
          if(mediaFailSound!=null)
            new Thread() {
              override def run() {
                // play negative sound after a short amount
                try { Thread.sleep(1400) } catch { case ex:Exception => }
                mediaFailSound.start
              }
            }.start                        
          return true
        }

        val idxPipe = otherAppName.indexOf("|")
        if(idxPipe>=0)
          otherAppName = otherAppName.substring(0,idxPipe)
        if(otherAppName!=appName) {
          if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED ncfActionString otherAppName="+otherAppName+" != "+appName)
          if(mediaFailSound!=null)
            new Thread() {
              override def run() {
                // play negative sound after a short amount
                try { Thread.sleep(1400) } catch { case ex:Exception => }
                mediaFailSound.start
              }
            }.start                        
          return true
        }

        val idxP2p = ncfActionString.indexOf("p2pWifi=")
        val idxBt = ncfActionString.indexOf("bt=")

        // todo tmtmtm: at this point, "ip=" is only for testing
        //              disadvantage: if communicating over accesspoint-ip, we cannot use the accesspoint-ip as a backup
        //              we use this here, as a substitude for wifiDirect, as long it is not stable
        //              this can be disabled by outremarking the code below [adding "ip=xx.xx.xx.xx"] in RFCommService

        val idxIp = ncfActionString.indexOf("ip=")
        if(idxIp>=0 && rfCommService.getWifiIpAddr!=null) {
          // new: evaluate "ip=..." for access-point ip-addr
          var ipAddr = ncfActionString.substring(idxIp+3)
          val idxPipe = ipAddr.indexOf("|")
          if(idxPipe>=0) 
            ipAddr = ipAddr.substring(0,idxPipe)
          if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED ipAddr="+ipAddr)
          // play audio notification (as earliest possible feedback for nfc activity)
          if(mediaConfirmSound!=null)
            mediaConfirmSound.start
          new Thread() {
            override def run() {
              // a little pause to get over nfc tata and hopefully be resumed
              try { Thread.sleep(700) } catch { case ex:Exception => }
              rfCommService.connectIp(ipAddr, "ip-target")
            }
          }.start                        

        } else if(rfCommService.wifiP2pManager!=null && rfCommService.desiredWifiDirect && idxP2p>=0) {
          // evaluate "p2pWifi=..." for WiFi-Direct mac-addr
          var p2pWifiAddr = ncfActionString.substring(idxP2p+8)
          val idxPipe = p2pWifiAddr.indexOf("|")
          if(idxPipe>=0) 
            p2pWifiAddr = p2pWifiAddr.substring(0,idxPipe)
          if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED p2pWifiAddr="+p2pWifiAddr)
          // play audio notification (as earliest possible feedback for nfc activity)
          if(mediaConfirmSound!=null)
            mediaConfirmSound.start
          if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED wait to get into onResume state...")
          new Thread() {
            override def run() {
              // a little pause to get over nfc tata and hopefully be resumed
              try { Thread.sleep(700) } catch { case ex:Exception => }    // wait to get into onResume state after NDEF_DISCOVERED
              if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED rfCommService.connectWifi() ...")
              rfCommService.connectWifi(p2pWifiAddr, "nfc-target", true)
            }
          }.start                        

        } else if(mBluetoothAdapter!=null && rfCommService.desiredBluetooth && idxBt>=0) {
          // evaluate "bt=..." for bluetooth mac-addr
          var btAddr = ncfActionString.substring(idxBt+3)
          val idxPipe = btAddr.indexOf("|")
          if(idxPipe>=0) 
            btAddr = btAddr.substring(0,idxPipe)
          if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED btAddr="+btAddr+" rfCommService="+rfCommService)
          // play audio notification (as earliest possible feedback for nfc activity)
          if(mediaConfirmSound!=null)
            mediaConfirmSound.start
          if(rfCommService!=null) {
            if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED rfCommService!=null activityResumed="+rfCommService.activityResumed)
            val remoteBluetoothDevice = mBluetoothAdapter.getRemoteDevice(btAddr)
            if(remoteBluetoothDevice!=null) {
              if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED remoteBluetoothDevice!=null")
              // todo: this condition should NOT be used if this app was started by nfc
              //       maybe this condition should go completely
/*
              if(mBluetoothAdapter.getAddress > remoteBluetoothDevice.getAddress) {
                // our local btAddr is > than the remote btAddr: we become the actor and we will bt-connect
*/
                // our activity may still be in onPause mode due to NFC activity: sleep a bit before 
                if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED connecting ...")
                connectAttemptFromNfc=true    // parent app on connect fail will ask user "fall back to OPP?" only if connect was NOT initiated by nfc
                rfCommService.connectBt(remoteBluetoothDevice, true, false)
                // connectBt() will send CONNECTION_START to the activity, which will draw the connect-progress animation
/*
              } else {
                // our local btAddr is < than the remote btAddr: we just wait for a bt-connect request
                if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED passively waiting for incoming bt-connect request... mSecureAcceptThread="+rfCommService.mSecureAcceptThread)
                // show "connecting progress" animation
                // todo: what if noone connects? can this animation be aborted, does it timeout?

                //rfCommService.state = RFCommHelperService.STATE_CONNECTING    // tmtmtm?
                //if(msgFromServiceHandler!=null)
                //  msgFromServiceHandler.obtainMessage(RFCommHelperService.CONNECTING, -1, -1, remoteBluetoothDevice.getName+" "+remoteBluetoothDevice.getAddress).sendToTarget
              }
*/
            }
          }
        }
        return true
      }
    }
    return false
  }

  def onActivityResult(requestCode:Int, resultCode:Int, intent:Intent) :Boolean = {
    if(D) Log.i(TAG, "onActivityResult resultCode="+resultCode+" requestCode="+requestCode)
    requestCode match {
      case REQUEST_ENABLE_BT =>
        if(D) Log.i(TAG, "onActivityResult REQUEST_ENABLE_BT")
        // When the request to enable Bluetooth returns
        if (resultCode == Activity.RESULT_OK) {
          // Bluetooth is now enabled, so set up a chat session
          if(mBluetoothAdapter==null)
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter
          if(mBluetoothAdapter!=null) {
            if(D) Log.i(TAG, "onActivityResult REQUEST_ENABLE_BT -> nfcServiceSetup")
            rfCommService.nfcServiceSetup // update our ndef push message to include our btAddr
          }

        } else {
          // User did not enable Bluetooth or an error occured
          val errMsg = "Bluetooth was not enabled"
          if(D) Log.i(TAG, "onActivityResult "+errMsg)
          if(msgFromServiceHandler!=null)
            msgFromServiceHandler.obtainMessage(RFCommHelperService.ALERT_MESSAGE, -1, -1, errMsg).sendToTarget
          else
            Toast.makeText(activity, errMsg, Toast.LENGTH_SHORT).show
          activity.finish
        }
        return true

      case _ =>
        return false
    }
  }

  def switchOnDesiredRadios() {
    if(rfCommService.desiredNfc && android.os.Build.VERSION.SDK_INT>=10 && rfCommService.mNfcAdapter!=null && !rfCommService.mNfcAdapter.isEnabled) {
      // let user enable nfc
      if(D) Log.i(TAG, "switchOnDesiredRadios !rfCommService.mNfcAdapter.isEnabled: ask user to enable nfc")
      AndrTools.runOnUiThread(activity) { () =>
        Toast.makeText(activity, "Please enable 'NFC', then go back...", Toast.LENGTH_SHORT).show
      }
      activity.startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
      // todo: onexit: offer to disable NFC

    } else if(rfCommService.desiredWifiDirect && android.os.Build.VERSION.SDK_INT>=14 && rfCommService.wifiP2pManager!=null && !rfCommService.isWifiP2pEnabled) {
      // let user enable wifip2p
      if(D) Log.i(TAG, "switchOnDesiredRadios isWifiP2pEnabled="+rfCommService.isWifiP2pEnabled+": ask user to enable p2p")
      AndrTools.runOnUiThread(activity) { () =>
        Toast.makeText(activity, "Please enable 'WiFi direct', then go back...", Toast.LENGTH_SHORT).show
      }

      activity.startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
      // note: once wifi-direct will be switched on (manually by the user), we will receive setIsWifiP2pEnabled(true)
      //       -> which will trigger discoverPeers()
      //       -> which will trigger a p2p connect request to the ipAddr given by nfc-dispatch
      // todo: onexit: offer to disable wifi-direct

    } else if(rfCommService.desiredBluetooth && mBluetoothAdapter!=null && !mBluetoothAdapter.isEnabled) {
/*
      // let user enable bluetooth
      if(D) Log.i(TAG, "switchOnDesiredRadios ask user to switch on bluetooth...")
      val enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
      activity.startActivityForResult(enableIntent, REQUEST_ENABLE_BT)
      // -> onActivityResult/REQUEST_ENABLE_BT -> if(resultCode == Activity.RESULT_OK) nfcServiceSetup()
      // todo: onexit: offer to disable BT
*/
      // programatically enable bluetooth
      if(D) Log.i(TAG, "switchOnDesiredRadios auto-enable bt")
      mBluetoothAdapter.enable // this may take some time
      // todo tmtmtm: really need a busy bee ("switching on bluetooth...")
      /*AndrTools.runOnUiThread(activity) { () =>
        Toast.makeText(activity, "Switching on Bluetooth", Toast.LENGTH_SHORT).show
      }*/
      var waitMS = 8000
      while(!mBluetoothAdapter.isEnabled && waitMS>0) {
        if(D) Log.i(TAG, "switchOnDesiredRadios mBluetoothAdapter currently off, waiting...")
        try { Thread.sleep(250) } catch { case ex:Exception => }
        waitMS -= 250
      }
      if(D) Log.i(TAG, "switchOnDesiredRadios bluetooth is now switched on ###############")
      autoEnabledBt = true // so we don't forget to switch it off on exit
      // display new state of bt
      msgFromServiceHandler.obtainMessage(RFCommHelperService.UI_UPDATE, -1, -1).sendToTarget
    }
  }

  def isNfcEnabled() :Boolean = {
    if(android.os.Build.VERSION.SDK_INT>=10 && rfCommService.mNfcAdapter!=null && rfCommService.mNfcAdapter.isEnabled && rfCommService.desiredNfc)
      return true
    return false
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////
  private var pairedDevicesShadowHashMap:scala.collection.mutable.HashMap[String,String] = null
  private var btBroadcastReceiver:BroadcastReceiver = null
  private var arrayAdapter:ArrayAdapter[String] = null

  def addAllDevices(setArrayAdapter:ArrayAdapter[String], mediaMiniAlert:MediaPlayer, 
                    showStored:Boolean=true, showPaired:Boolean=true, showDiscovered:Boolean=true) {
    arrayAdapter = setArrayAdapter
    // now fill our listView with all possible (paired/stored/discovered) devices of the requested device types
    // we use pairedDevicesShadowHashMap[addr,name] as a shadow-HashMap containing all listed devices, so we can prevent double-entries in the visible arrayAdapter
    pairedDevicesShadowHashMap = new scala.collection.mutable.HashMap[String,String]()
    if(D) Log.i(TAG, "addAllDevices fill listView with all devices, arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size)

    if(showStored) {
      // 1. add all prev connected and stored bt devices
      //if(rfCommService.desiredBluetooth) {
        // the only reason I disable this condition is so that I can switch off bt + wifi direct and still be able to use these entries to connect via default ip connection
        if(D) Log.i(TAG, "addAllDevices read prefsSharedP2pBt...")
        val p2pBtMap = prefsSharedP2pBt.getAll   // :map[String, ?]
        val p2pBtKeySet = p2pBtMap.keySet
        val p2pBtIterator = p2pBtKeySet.iterator
        while(p2pBtIterator.hasNext) {
          val addr = p2pBtIterator.next
          val name = prefsSharedP2pBt.getString(addr,null)
          //if(D) Log.i(TAG, "addAllDevices read prefsSharedP2pBt "+addr+" = "+name)
          if(name!=null && name.length>0) {
            if(pairedDevicesShadowHashMap.getOrElse(addr,null)==null) {
              pairedDevicesShadowHashMap += addr -> name
              arrayAdapter.add(name+"\n"+addr+" bt stored")
              if(D) Log.i(TAG, "addAllDevices prefsSharedP2pBt name=["+name+"] addr="+addr+" arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size)
            }
          }
        }
      //}

      // 2. add all prev connected and stored wifi devices
      if(rfCommService.desiredWifiDirect) {
        if(D) Log.i(TAG, "addAllDevices read prefsSharedP2pWifi...")
        val p2pWifiMap = prefsSharedP2pWifi.getAll   // :map[String, ?]
        val p2pWifiKeySet = p2pWifiMap.keySet
        val p2pWifiIterator = p2pWifiKeySet.iterator
        while(p2pWifiIterator.hasNext) {
          val addr = p2pWifiIterator.next
          val name = prefsSharedP2pWifi.getString(addr,null)
          //if(D) Log.i(TAG, "addAllDevices read prefsSharedP2pWifi "+addr+" = "+name)
          if(name!=null && name.length>0) {
            if(pairedDevicesShadowHashMap.getOrElse(addr,null)==null) {
              pairedDevicesShadowHashMap += addr -> name
              arrayAdapter.add(name+"\n"+addr+" wifi stored")
              if(D) Log.i(TAG, "addAllDevices prefsSharedP2pWifi name=["+name+"] addr="+addr+" arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size)
            }
          }
        }
      }
    }

    // 3. get list of paired bt devices from rfCommHelper
    if(showPaired) {
      if(rfCommService.desiredBluetooth) {
        if(D) Log.i(TAG, "addAllDevices get list of paired bt devices")
        val pairedDevicesArrayListOfStrings = RFCommHelper.getBtPairedDevices(activity)  // java.util.ArrayList[String], "name/naddr"
        if(pairedDevicesArrayListOfStrings!=null) {
          if(D) Log.i(TAG, "addAllDevices add BtPairedDevices count="+pairedDevicesArrayListOfStrings.size+" arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size)
          if(pairedDevicesArrayListOfStrings.size>0) {
            for(i <- 0 until pairedDevicesArrayListOfStrings.size)
              addDevice(pairedDevicesArrayListOfStrings.get(i))
          }
        }
      }
    }

    // 4. start handler for all newly discovered bt devices
    if(showDiscovered && rfCommService.desiredBluetooth) {
      if(mBluetoothAdapter==null)
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter
      if(mBluetoothAdapter!=null) {
        if(D) Log.i(TAG, "addAllDevices start handler for all newly discovered bt devices")
        btBroadcastReceiver = new BroadcastReceiver() {
          override def onReceive(context:Context, intent:Intent) {
            val actionString = intent.getAction
            if(BluetoothDevice.ACTION_FOUND==actionString) {
              val bluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE).asInstanceOf[BluetoothDevice]
              if(bluetoothDevice!=null) {
                if(bluetoothDevice.getName!=null && bluetoothDevice.getName.length>0) {
                  if(pairedDevicesShadowHashMap.getOrElse(bluetoothDevice.getAddress,null)==null) {
                    pairedDevicesShadowHashMap += bluetoothDevice.getAddress -> bluetoothDevice.getName
                    if(D) Log.i(TAG, "addAllDevices pairedDevicesShadowHashMap ["+pairedDevicesShadowHashMap.getOrElse(bluetoothDevice.getAddress,null)+"]")
                    if(mediaMiniAlert!=null)
                      mediaMiniAlert.start
                  }
                  arrayAdapter.add(bluetoothDevice.getName+"\n"+bluetoothDevice.getAddress+" bt discovered")
                  arrayAdapter.notifyDataSetChanged
                  if(D) Log.i(TAG, "addAllDevices btBroadcastReceiver BluetoothDevice.ACTION_FOUND name=["+bluetoothDevice.getName+"] addr="+bluetoothDevice.getAddress+
                                   " arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size)
                }
              }
            }
            else if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED==actionString) {
              //if(D) Log.i(TAG,"addAllDevices btBroadcastReceiver ACTION_DISCOVERY_FINISHED arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size+" ############")
              if(btBroadcastReceiver!=null)
                mBluetoothAdapter.startDiscovery
            }
          }
        }

        activity.registerReceiver(btBroadcastReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND))
        activity.registerReceiver(btBroadcastReceiver, new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))
        mBluetoothAdapter.startDiscovery
      }
    }

    // 5. start handler for all newly discovered p2pWifi devices
    if(showDiscovered && rfCommService.desiredWifiDirect) {
      if(rfCommService.wifiP2pManager!=null) {
        if(D) Log.i(TAG, "addAllDevices start handler for all newly discovered p2pWifi devices")
        rfCommService.p2pWifiDiscoveredCallbackFkt = { wifiP2pDevice =>
          if(wifiP2pDevice != null) {
            if(pairedDevicesShadowHashMap.getOrElse(wifiP2pDevice.deviceAddress,null)==null) {
              pairedDevicesShadowHashMap += wifiP2pDevice.deviceAddress -> wifiP2pDevice.deviceName
              if(mediaMiniAlert!=null)
                mediaMiniAlert.start
            }

            arrayAdapter.add(wifiP2pDevice.deviceName+"\n"+wifiP2pDevice.deviceAddress+" wifi discovered")
            arrayAdapter.notifyDataSetChanged
            if(D) Log.i(TAG, "addAllDevices add wifiP2p device deviceName="+wifiP2pDevice.deviceName+" deviceAddress="+wifiP2pDevice.deviceAddress+
                            " status="+wifiP2pDevice.status)
          }
        }

        rfCommService.wifiP2pManager.discoverPeers(rfCommService.p2pChannel, new WifiP2pManager.ActionListener() {
          override def onFailure(reasonCode:Int) {
            val reasonString = if(reasonCode==0) "Error" else if(reasonCode==1) "P2P_UNSUPPORTED" else if(reasonCode==2) "Busy" else "unknown"
            if(D) Log.i(TAG, "addAllDevices wifiP2pManager.discoverPeers failed reasonCode="+reasonCode+" "+reasonString)
          }
          override def onSuccess() {
            //if(D) Log.i(TAG, "addAllDevices wifiP2pManager.discoverPeers onSuccess")
          }
        })
      }
    }
  }

  def addDevice(device:String) {
    val idxCR = device.indexOf("\n")
    val btName = device.substring(0,idxCR)
    val idxBlank = device.substring(idxCR+1).indexOf(" ")
    val btAddr = if(idxBlank>=0) device.substring(idxCR+1,idxCR+1+idxBlank) else device.substring(idxCR+1)
    if(pairedDevicesShadowHashMap!=null && pairedDevicesShadowHashMap.getOrElse(btAddr,null)==null) {
      if(D) Log.i(TAG, "addDevice BtPairedDevices btAddr="+btAddr+" btName="+btName)
      pairedDevicesShadowHashMap += btAddr -> btName
      arrayAdapter.add(device)
      //if(D) Log.i(TAG, "addDevice arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size+" ############")
    }
  }

  def addAllDevicesUnregister() {
    if(D) Log.i(TAG, "addAllDevicesUnregister")
    if(rfCommService!=null) {
      // not interested anymore in wifi device discovery
      if(rfCommService.p2pWifiDiscoveredCallbackFkt!=null) {
        if(D) Log.i(TAG, "addAllDevicesUnregister rfCommService.callbackFkt=null")
        rfCommService.p2pWifiDiscoveredCallbackFkt = null
      }
      // not interested anymore in bt device discovery
      if(btBroadcastReceiver!=null) {
        if(mBluetoothAdapter==null)
          mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter
        if(mBluetoothAdapter!=null) {
          if(D) Log.i(TAG, "addAllDevicesUnregister mBluetoothAdapter.cancelDiscovery")
          mBluetoothAdapter.cancelDiscovery
        }
        if(D) Log.i(TAG, "addAllDevicesUnregister activity.unregisterReceiver")
        if(activity!=null)
          activity.unregisterReceiver(btBroadcastReceiver)
        btBroadcastReceiver=null
      }
    }
  }
}


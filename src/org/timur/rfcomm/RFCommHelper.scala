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
import java.net.ServerSocket
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

import scala.collection.mutable // using mutable.HashMap

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
  val RADIO_BT:Int = 1
  val RADIO_BT_INSECURE:Int = 2
  val RADIO_P2PWIFI:Int = 4
  val RADIO_NFC:Int = 8
}

class RFCommHelper(activity:Activity, 
                   msgFromServiceHandler:android.os.Handler, 
                   prefsPrivate:SharedPreferences, 
                   prefsSharedP2pBt:SharedPreferences, 
                   prefsSharedP2pWifi:SharedPreferences,
                   allOK:() => Unit, 
                   allFailed:() => Unit, 
                   appService:RFServiceTrait,
                   activityRuntimeClass:java.lang.Class[Activity],
                   audioConfirmSound:MediaPlayer,
                   radioTypeWanted:Int,
                   acceptThreadSecureName:String, acceptThreadSecureUuid:String,
                   acceptThreadInsecureName:String, acceptThreadInsecureUuid:String,
                   ipPort:Int) {

  var rfCommService:RFCommHelperService = null
  var connectAttemptFromNfc = false
  var wifiP2pManager:WifiP2pManager = null
  var mBluetoothAdapter:BluetoothAdapter = null

  private val TAG = "RFCommHelper"
  private val D = true
  private val REQUEST_ENABLE_BT = 101
  private val prefsPrivateEditor = prefsPrivate.edit

  private var activityDestroyed = false
  private var radioDialogPossibleAndNotYetShown = false
  private var wifiDirectBroadcastReceiver:BroadcastReceiver = null

  private val intentFilter = new IntentFilter()
  if(android.os.Build.VERSION.SDK_INT>=14) {
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
  }

  if(D) Log.i(TAG, "constructor startService('RFCommHelperService') ...")
  val serviceIntent = new Intent(activity, classOf[RFCommHelperService])
  //startService(serviceIntent)   // call this only, to keep service active after onDestroy()/unbindService()

  var rfCommServiceConnection = new ServiceConnection { 
    def onServiceDisconnected(className:ComponentName) { 
      if(D) Log.i(TAG, "constructor rfCommServiceConnection onServiceDisconnected set rfCommService=null")
      rfCommService = null
      allFailed()
    } 
    def onServiceConnected(className:ComponentName, rawBinder:IBinder) { 
      if(D) Log.i(TAG, "constructor rfCommServiceConnection onServiceConnected localBinder.getService ...")
      rfCommService = rawBinder.asInstanceOf[RFCommHelperService#LocalBinder].getService
      if(rfCommService==null) {
        Log.e(TAG, "constructor rfCommServiceConnection onServiceConnected no interface to service, rfCommService==null")
        val errMsg = "Error - failed to get service interface from binder"
        if(msgFromServiceHandler!=null)
          msgFromServiceHandler.obtainMessage(RFCommHelperService.ALERT_MESSAGE, -1, -1, errMsg).sendToTarget
        else
          AndrTools.runOnUiThread(activity) { () =>
            Toast.makeText(activity, errMsg, Toast.LENGTH_LONG).show    // todo: create more 'human' text
          }
        return
      }

      // we got connected to rfCommService

      if(D) Log.i(TAG, "constructor rfCommServiceConnection onServiceConnected got rfCommService object, activity="+activity)
      rfCommService.activity = activity
      rfCommService.activityRuntimeClass = activityRuntimeClass
      rfCommService.activityMsgHandler = msgFromServiceHandler
      rfCommService.appService = appService
      rfCommService.prefsSharedP2pBt = prefsSharedP2pBt
      rfCommService.prefsSharedP2pBtEditor = prefsSharedP2pBt.edit
      rfCommService.prefsSharedP2pWifi = prefsSharedP2pWifi
      rfCommService.prefsSharedP2pWifiEditor = prefsSharedP2pWifi.edit
      rfCommService.acceptThreadSecureName = acceptThreadSecureName
      rfCommService.acceptThreadSecureUuid = acceptThreadSecureUuid
      rfCommService.acceptThreadInsecureName = acceptThreadInsecureName
      rfCommService.acceptThreadInsecureUuid = acceptThreadInsecureUuid
      rfCommService.ipPort = ipPort

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

      // rfCommService is initialized
      if(D) Log.i(TAG, "constructor rfCommServiceConnection onServiceConnected activityResumed="+rfCommService.activityResumed+" -> onResume")
      //if(rfCommService.activityResumed) {
        // todo: but what if not?
        new Thread() {
          override def run() {
            onResume  // this will run radioSelect and start the AcceptThread(s)
          }
        }.start                        
      //}
      if(D) Log.i(TAG, "constructor rfCommServiceConnection onServiceConnected -> allOK")
      allOK()
    } 
  } 
  if(rfCommServiceConnection!=null) {
    if(D) Log.i(TAG, "constructor bindService ...")
    activity.bindService(serviceIntent, rfCommServiceConnection, Context.BIND_AUTO_CREATE)
    if(D) Log.i(TAG, "constructor bindService done")
    radioDialogPossibleAndNotYetShown = true // will be evaluated in onResume
  } else {
    Log.e(TAG, "constructor bindService failed")
    allFailed()
  }

  private def initBtNfc() {
    // start bluetooth accept thread
    if(mBluetoothAdapter!=null && mBluetoothAdapter.isEnabled && rfCommService!=null) {
      if(rfCommService.state == RFCommHelperService.STATE_NONE) {
        if(D) Log.i(TAG, "initBtNfc rfCommService.start pairedBtOnly="+rfCommService.pairedBtOnly+" ...")
        rfCommService.start() // -> bt (new AcceptThread()).start -> run()
      }

      msgFromServiceHandler.obtainMessage(RFCommHelperService.UI_UPDATE, -1, -1).sendToTarget
                        // todo: how do we display the use of dual-radio (p2pwifi+bt) ?
    }

    // initialize nfc
    if(rfCommService.mNfcAdapter!=null && rfCommService.mNfcAdapter.isEnabled) {
      if(D) Log.i(TAG, "initBtNfc -> nfcServiceSetup")
      rfCommService.nfcServiceSetup
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
  def radioDialog(backKeyIsExit:Boolean) {
    if(D) Log.i(TAG, "radioDialog()")
    if(activityDestroyed) {
      if(D) Log.i(TAG, "radioDialog aborted because: activityDestroyed="+activityDestroyed)
      return
    }

    // user did not yet see the dialog, offer the dialog to turn all wanted radio on
    val radioSelectDialogBuilder = new AlertDialog.Builder(activity)
    radioSelectDialogBuilder.setTitle("Radio selection")
    // todo: use a nice fancy "radio wave" background ?

    val radioSelectDialogLayout = new LinearLayout(activity)
    radioSelectDialogLayout.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT))
    radioSelectDialogLayout.setPadding(40, 40, 40, 40)
    radioSelectDialogLayout.setOrientation(LinearLayout.VERTICAL)


    if(D) Log.i(TAG, "radioDialog() rfCommService.desiredBluetooth="+rfCommService.desiredBluetooth)

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

    val radioPairedBtOnlyCheckbox = new CheckBox(activity)
    radioPairedBtOnlyCheckbox.setText("Paired Bluetooth only")
    radioPairedBtOnlyCheckbox.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,19.0f)
    if(android.os.Build.VERSION.SDK_INT<10) {
      radioPairedBtOnlyCheckbox.setEnabled(false)
      radioPairedBtOnlyCheckbox.setChecked(true)
    } else {
      radioPairedBtOnlyCheckbox.setEnabled(true)
      radioPairedBtOnlyCheckbox.setChecked(rfCommService.pairedBtOnly)
    }
    radioSelectDialogLayout.addView(radioPairedBtOnlyCheckbox)
    
    val radioWifiDirectCheckbox = new CheckBox(activity)
    if((radioTypeWanted&RFCommHelper.RADIO_P2PWIFI)!=0) {
      radioWifiDirectCheckbox.setText("WiFi Direct not available")
      radioWifiDirectCheckbox.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,19.0f)
      if(wifiP2pManager==null || android.os.Build.VERSION.SDK_INT<14)
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
        storeRadioSelection(radioBluetoothCheckbox.isChecked,radioWifiDirectCheckbox.isChecked,radioNfcCheckbox.isChecked,radioPairedBtOnlyCheckbox.isChecked)
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
            storeRadioSelection(radioBluetoothCheckbox.isChecked,radioWifiDirectCheckbox.isChecked,radioNfcCheckbox.isChecked,radioPairedBtOnlyCheckbox.isChecked)
            if(backKeyIsExit)
              activity.finish
            return true
          }
          return false
        }                   
      })

    AndrTools.runOnUiThread(activity) { () =>
      val radioSelectDialog = radioSelectDialogBuilder.create
      var alertReady = false
      radioSelectDialog.setOnShowListener(new DialogInterface.OnShowListener() {
        override def onShow(dialogInterface:DialogInterface) {
          if(alertReady==false) {
            val button = radioSelectDialog.getButton(DialogInterface.BUTTON_POSITIVE)
            button.setOnClickListener(new View.OnClickListener() {
              override def onClick(view:View) {
                // evaluate checkboxes and set desired booleans
                rfCommService.desiredBluetooth = radioBluetoothCheckbox.isChecked
                rfCommService.desiredWifiDirect = radioWifiDirectCheckbox.isChecked
                rfCommService.desiredNfc = radioNfcCheckbox.isChecked
                rfCommService.pairedBtOnly = radioPairedBtOnlyCheckbox.isChecked
                if(D) Log.i(TAG, "radioSelectDialog onClick desiredBluetooth="+rfCommService.desiredBluetooth+" desiredWifiDirect="+rfCommService.desiredWifiDirect+" desiredNfc="+rfCommService.desiredNfc)
                if(rfCommService.desiredBluetooth==false && rfCommService.desiredWifiDirect==false) {
                  // we need at least 1 type of transport-radio
                  if(msgFromServiceHandler!=null)
                    msgFromServiceHandler.obtainMessage(RFCommHelperService.ALERT_MESSAGE, -1, -1, "No radio enabled for transport").sendToTarget
                  else
                    AndrTools.runOnUiThread(activity) { () =>
                      Toast.makeText(activity, "No radio enabled for transport", Toast.LENGTH_SHORT).show
                    }
                  // we let the dialog stay open

                } else {
                  dialogInterface.cancel

                  // persist desired-flags
                  storeRadioSelection(rfCommService.desiredBluetooth,rfCommService.desiredWifiDirect,rfCommService.desiredNfc, rfCommService.pairedBtOnly)
                  initBtNfc()  // start bt-accept-thread and init-nfc
                  switchOnDesiredRadios  // open wireless settings and let user enable radio-hw
                  radioDialogPossibleAndNotYetShown = false  // radioDialog will not again be shown on successive onResume's
                }
              }
            })
            alertReady = true
          }
        }
      })
      radioSelectDialog.show
    }
  }

  def onResume() {
    if(D) Log.i(TAG, "onResume mNfcAdapter="+rfCommService.mNfcAdapter+" wifiP2pManager="+wifiP2pManager+" isWifiP2pEnabled="+rfCommService.isWifiP2pEnabled)
    if((radioTypeWanted&RFCommHelper.RADIO_NFC)!=0 && rfCommService.mNfcAdapter==null) {
      // find out if nfc hardware is supported (not necessarily on)
      if(android.os.Build.VERSION.SDK_INT>=10 && rfCommService.mNfcAdapter==null) {
        try {
          rfCommService.mNfcAdapter = NfcAdapter.getDefaultAdapter(activity)
          if(D) Log.i(TAG, "onResume mNfcAdapter="+rfCommService.mNfcAdapter)
          // continue to setup nfc in nfcServiceSetup()
        } catch {
          case ncdferr:java.lang.NoClassDefFoundError =>
            Log.e(TAG, "onResume NfcAdapter.getDefaultAdapter(this) failed "+ncdferr)
        }
      }
      if(rfCommService.mNfcAdapter!=null) {
        if(D) Log.i(TAG, "onResume nfc supported")
      } else {
        if(D) Log.i(TAG, "onResume nfc not supported")
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
        if(D) Log.i(TAG, "onResume bt supported")
      } else {
        Log.e(TAG, "onResume bt not supported")
      }
    }

    if((radioTypeWanted&RFCommHelper.RADIO_P2PWIFI)!=0 && wifiP2pManager==null) {
      // find out if wifi-direct is supported, if so initialze wifiP2pManager
      if(android.os.Build.VERSION.SDK_INT>=14 && wifiP2pManager==null) {
        wifiP2pManager = activity.getSystemService(Context.WIFI_P2P_SERVICE).asInstanceOf[WifiP2pManager]
        if(wifiP2pManager!=null) {
          // register p2pChannel and wifiDirectBroadcastReceiver
          // note: this will result in a call to setIsWifiP2pEnabled(), so we know wether p2pWifi is already activated!
          if(D) Log.i(TAG, "onResume wifiP2p is supported, initialze p2pChannel and register wifiDirectBroadcastReceiver")
          rfCommService.p2pChannel = wifiP2pManager.initialize(activity, activity.getMainLooper, null)
          wifiDirectBroadcastReceiver = rfCommService.newWiFiDirectBroadcastReceiver(wifiP2pManager)
          activity.registerReceiver(wifiDirectBroadcastReceiver, intentFilter)
        }
      }
      if(wifiP2pManager==null) {
        if(D) Log.i(TAG, "onResume wifiP2p not supported")
      }
    }

    // set activityResumed if possible
    if(rfCommService!=null) {
      rfCommService.activityResumed = true
      if(D) Log.i(TAG, "onResume set rfCommService.activityResumed=true")
    } else {
      Log.e(TAG, "onResume rfCommService==null, activityResumed not set ##################")
    }

    // next we will start one or two AcceptThreads, either through radioDialog() or via initBtNfc()
    // at this point we need the UUID's for the mBluetoothAdapter.listenUsingxxxxx calls
    if(radioDialogPossibleAndNotYetShown) {
      // if all desired radio is already on, we don't need to show the radio dialog
      if((radioTypeWanted&RFCommHelper.RADIO_P2PWIFI)!=0 && wifiP2pManager!=null && !rfCommService.isWifiP2pEnabled) {
        // the app want's to use p2pWifi, if it is supported by this device
        // however, we need to wait a little for wifiDirectBroadcastReceiver to call our setIsWifiP2pEnabled() method, so we know if isWifiP2pEnabled is true
        // if isWifiP2pEnabled is true, we might not need to show the radio-select dialog
        if(D) Log.i(TAG, "onResume little sleep to find out about the state of isWifiP2pEnabled="+rfCommService.isWifiP2pEnabled)
        try { Thread.sleep(300) } catch { case ex:Exception => }
        if(D) Log.i(TAG, "onResume little sleep to find out about the state of isWifiP2pEnabled="+rfCommService.isWifiP2pEnabled+" DONE ##############")
      }

      var radioDialogNeeded = false
      if((radioTypeWanted&RFCommHelper.RADIO_BT)!=0 && mBluetoothAdapter!=null && !mBluetoothAdapter.isEnabled)
        radioDialogNeeded = true
      if((radioTypeWanted&RFCommHelper.RADIO_P2PWIFI)!=0 && wifiP2pManager!=null && !rfCommService.isWifiP2pEnabled)
        radioDialogNeeded = true
      if((radioTypeWanted&RFCommHelper.RADIO_NFC)!=0 && rfCommService.mNfcAdapter!=null && !rfCommService.mNfcAdapter.isEnabled)
        radioDialogNeeded = true
      if(radioDialogNeeded) {
        // show the radio dialog
        new Thread() {
          override def run() {
            if(D) Log.i(TAG, "onResume new thread -> radioDialog")
            radioDialog(true) // will turn radioDialogPossibleAndNotYetShown off 
          }
        }.start

      } else {
        radioDialogPossibleAndNotYetShown = false
        initBtNfc()  // start bt-accept-thread and init-nfc
      }

    } else {
      new Thread() {
        override def run() {
          // delay this, so that user can still exit app if wanted
          try { Thread.sleep(600) } catch { case ex:Exception => }
          if(!activityDestroyed) {
            // todo: maybe better popup the radio dialog here?
            switchOnDesiredRadios
          }
        }
      }.start
    }

    if(rfCommService.mNfcAdapter!=null && rfCommService.mNfcAdapter.isEnabled) {
      if(rfCommService.nfcPendingIntent!=null) {
        AndrTools.runOnUiThread(activity) { () =>
          // enableForegroundDispatch() must be called from the main thread, and only when the activity is in the foreground (resumed). 
          // Also, activities must call disableForegroundDispatch(Activity) before the completion of their onPause() 
          // callback to disable foreground dispatch after it has been enabled. 
          rfCommService.mNfcAdapter.enableForegroundDispatch(activity, rfCommService.nfcPendingIntent, rfCommService.nfcFilters, rfCommService.nfcTechLists)
          //if(D) Log.i(TAG, "onResume nfc enableForegroundDispatch done")
        }
      }
      if(rfCommService.nfcForegroundPushMessage!=null) {
        rfCommService.mNfcAdapter.setNdefPushMessage(rfCommService.nfcForegroundPushMessage, activity)
        //if(D) Log.i(TAG, "onResume nfc setNdefPushMessage done")
      }
    }
  }

  def onPause() {
    if(D) Log.i(TAG, "onPause...")
    // todo tmtmtm: if this is just a "small onPause" (triggered by nfc-system-animation)
    //              rfcommservice will NOT be able to answer an incoming bt-connect-request   
    if(rfCommService!=null) {
      rfCommService.activityResumed = false
      if(rfCommService.mNfcAdapter!=null && rfCommService.mNfcAdapter.isEnabled) {
        rfCommService.mNfcAdapter.disableForegroundDispatch(activity)
        rfCommService.mNfcAdapter.setNdefPushMessage(null, activity)
        if(D) Log.i(TAG, "onPause setNdefPushMessage null done")
      }
    } else {
      Log.e(TAG, "onPause rfCommService==null, activityResumed not cleared")
    }
  }

  def onDestroy() {
    if(D) Log.i(TAG, "onDestroy shutdown everything ...")
    if(rfCommService!=null) {
      rfCommService.stopActiveConnection
      rfCommService.stopAcceptThreads
      rfCommService.activity = null
    } else {
      Log.e(TAG, "onDestroy rfCommService=null cannot call stopActiveConnection")
    }

    if(rfCommServiceConnection!=null) {
      activity.unbindService(rfCommServiceConnection)
      // note: our service will exit here, since we DID NOT use startService in front of bindService - this is our intent!
      if(D) Log.i(TAG, "onDestroy unbindService done")
      rfCommServiceConnection=null
    }

    if(wifiDirectBroadcastReceiver!=null) {
      if(D) Log.i(TAG, "onDestroy unregisterReceiver(wifiDirectBroadcastReceiver)")
      activity.unregisterReceiver(wifiDirectBroadcastReceiver)
    }

    if(wifiP2pManager!=null && rfCommService.p2pChannel!=null) {
      if(D) Log.i(TAG, "onDestroy wifiP2pManager.removeGroup")
      wifiP2pManager.removeGroup(rfCommService.p2pChannel, new ActionListener() {
        override def onSuccess() {
          if(D) Log.i(TAG, "onDestroy wifiP2pManager.removeGroup() success")
          // wifiDirectBroadcastReceiver will notify us
        }

        override def onFailure(reason:Int) {
          if(D) Log.i(TAG, "onDestroy wifiP2pManager.removeGroup() failed reason="+reason)
          // reason ERROR=0, P2P_UNSUPPORTED=1, BUSY=2
        }
      })
      rfCommService.p2pConnected = false  // maybe not necessary
      //p2pChannel = null
      //wifiP2pManager = null
    }

    activityDestroyed=true
  }

  def getBtPairedDevices():java.util.ArrayList[String] = {
    val pairedDevicesArrayListOfStrings = new java.util.ArrayList[String]()
    if(mBluetoothAdapter!=null) {
      val pairedDevicesSet = mBluetoothAdapter.getBondedDevices
      if(pairedDevicesSet.size>0) {
		    // Create an ArrayAdapter that will make the Strings above appear in the ListView
        val pairedDevicesArrayListOfBluetoothDevices = new ArrayList[BluetoothDevice](pairedDevicesSet)
        if(pairedDevicesArrayListOfBluetoothDevices==null) {
          Log.e(TAG, "getPairedDevices pairedDevicesArrayListOfBluetoothDevices==null")
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

  def onNewIntent(intent:Intent) :Boolean = {
    // all sort of intents may arrive here... for instance ACTION_NDEF_DISCOVERED
    //if(D) Log.i(TAG, "onNewIntent intent="+intent+" intent.getAction="+intent.getAction+" mNfcAdapter="+rfCommService.mNfcAdapter)
    // we are interested in nfc-intents (ACTION_NDEF_DISCOVERED)
    if(android.os.Build.VERSION.SDK_INT>=10 && rfCommService.mNfcAdapter!=null && intent.getAction==NfcAdapter.ACTION_NDEF_DISCOVERED) {
      val ncfActionString = NfcHelper.checkForNdefAction(activity, intent)
      if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED NfcEnabled="+rfCommService.mNfcAdapter.isEnabled+" ncfActionString=["+ncfActionString+"] desiredWifiDirect="+rfCommService.desiredWifiDirect+" desiredBluetooth="+rfCommService.desiredBluetooth)
      if(rfCommService.mNfcAdapter.isEnabled && ncfActionString!=null) {
        // this is a nfc-intent, ncfActionString may look something like this: "bt=xxyyzzxxyyzz|p2pWifi=xx:yy:zz:xx:yy:zz"
        val idxP2p = ncfActionString.indexOf("p2pWifi=")
        val idxBt = ncfActionString.indexOf("bt=")

        // todo tmtmtm: at this point, "ip=" is only for testing
        //              disadvantage: if SocketClient communicates over accesspoint-ip, it cannot use the accesspoint-ip as a fallback
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
          if(audioConfirmSound!=null)
            audioConfirmSound.start
          rfCommService.connectIp(ipAddr, "ip-target")

        } else if(wifiP2pManager!=null && rfCommService.desiredWifiDirect && idxP2p>=0) {
          // evaluate "p2pWifi=..." for WiFi-Direct mac-addr
          var p2pWifiAddr = ncfActionString.substring(idxP2p+8)
          val idxPipe = p2pWifiAddr.indexOf("|")
          if(idxPipe>=0) 
            p2pWifiAddr = p2pWifiAddr.substring(0,idxPipe)
          if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED p2pWifiAddr="+p2pWifiAddr)

          // play audio notification (as earliest possible feedback for nfc activity)
          if(audioConfirmSound!=null)
            audioConfirmSound.start

          rfCommService.connectWifi(wifiP2pManager, p2pWifiAddr, "nfc-target", true)

        } else if(mBluetoothAdapter!=null && rfCommService.desiredBluetooth && idxBt>=0) {
          // evaluate "bt=..." for bluetooth mac-addr
          var btAddr = ncfActionString.substring(idxBt+3)
          val idxPipe = btAddr.indexOf("|")
          if(idxPipe>=0) 
            btAddr = btAddr.substring(0,idxPipe)
          if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED btAddr="+btAddr+" rfCommService="+rfCommService)

          // play audio notification (as earliest possible feedback for nfc activity)
          if(audioConfirmSound!=null)
            audioConfirmSound.start

          if(rfCommService!=null) {
            if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED rfCommService!=null activityResumed="+rfCommService.activityResumed)

            def remoteBluetoothDevice = mBluetoothAdapter.getRemoteDevice(btAddr)
            if(remoteBluetoothDevice!=null) {
              //val sendFilesCount = if(selectedFileStringsArrayList!=null) selectedFileStringsArrayList.size else 0
              //if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED sendFilesCount="+sendFilesCount+" ...")
              if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED remoteBluetoothDevice!=null")

              if(mBluetoothAdapter.getAddress > remoteBluetoothDevice.getAddress) {
                // our local btAddr is > than the remote btAddr: we become the actor and we will bt-connect
                // our activity may still be in onPause mode due to NFC activity: sleep a bit before 
                if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED connecting ...")

                connectAttemptFromNfc=true    // parent app on connect fail will ask user "fall back to OPP?" only if connect was NOT initiated by nfc
                rfCommService.connectBt(remoteBluetoothDevice)
                // connectBt() will send CONNECTION_START to the activity, which will draw the connect-progress animation

              } else {
                // our local btAddr is < than the remote btAddr: we just wait for a bt-connect request
                if(D) Log.i(TAG, "onNewIntent NDEF_DISCOVERED passively waiting for incoming bt-connect request... mSecureAcceptThread="+rfCommService.mSecureAcceptThread)

                // show "connecting progress" animation
                // todo: what if noone connects? can this aniation be aborted, does it timeout?
                rfCommService.state = RFCommHelperService.STATE_CONNECTING    // tmtmtm?
                if(msgFromServiceHandler!=null)
                  msgFromServiceHandler.obtainMessage(RFCommHelperService.CONNECTING, -1, -1, remoteBluetoothDevice.getName+" "+remoteBluetoothDevice.getAddress).sendToTarget
              }
            }
          }
        }

      }
      return true
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

    } else if(rfCommService.desiredWifiDirect && android.os.Build.VERSION.SDK_INT>=14 && wifiP2pManager!=null && !rfCommService.isWifiP2pEnabled) {
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
      // let user enable bluetooth
      val enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
      activity.startActivityForResult(enableIntent, REQUEST_ENABLE_BT)
      // -> onActivityResult/REQUEST_ENABLE_BT -> if(resultCode == Activity.RESULT_OK) nfcServiceSetup()
      // todo: onexit: offer to disable BT
    }
  }

  def isNfcEnabled() :Boolean = {
    if(android.os.Build.VERSION.SDK_INT>=10 && rfCommService.mNfcAdapter!=null && rfCommService.mNfcAdapter.isEnabled)
      return true
    return false
  }


  ////////////////////////////////////////////////////////////////////////////////////////////////

  private var pairedDevicesShadowHashMap:mutable.HashMap[String,String] = null
  private var btBroadcastReceiver:BroadcastReceiver = null
  private var arrayAdapter:ArrayAdapter[String] = null

  // todo: must render 2nd line of listview entry (deviceAddr + comment) using smaller font
  // todo: make it, so that wifiName is the same as btName

  def addAllDevices(setArrayAdapter:ArrayAdapter[String], audioMiniAlert:MediaPlayer) {
    arrayAdapter = setArrayAdapter
    // now fill our listView with all possible (paired/stored/discovered) devices of the requested device types
    // we use pairedDevicesShadowHashMap[addr,name] as a shadow-HashMap containing all listed devices, so we can prevent double-entries in the visible arrayAdapter
    pairedDevicesShadowHashMap = new mutable.HashMap[String,String]()
    if(D) Log.i(TAG, "addAllDevices fill listView with all devices, arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size)

    if(rfCommService.desiredBluetooth) {
      // 1. add all prev connected bt devices
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
    }

    if(rfCommService.desiredWifiDirect) {
      // 2. add all prev connected wifi devices
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

    if(rfCommService.desiredBluetooth) {
      // 3. get list of paired bt devices from rfCommHelper
      val pairedDevicesArrayListOfStrings = getBtPairedDevices  // java.util.ArrayList[String], "name/naddr"
      if(pairedDevicesArrayListOfStrings!=null) {
        if(D) Log.i(TAG, "addAllDevices add BtPairedDevices count="+pairedDevicesArrayListOfStrings.size+" arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size)
        if(pairedDevicesArrayListOfStrings.size>0) {
          for(i <- 0 until pairedDevicesArrayListOfStrings.size)
            addDevice(pairedDevicesArrayListOfStrings.get(i))
        }
      }

      // 4. start handler for all newly discovered bt devices
      if(mBluetoothAdapter!=null) {
        btBroadcastReceiver = new BroadcastReceiver() {
          override def onReceive(context:Context, intent:Intent) {
            val actionString = intent.getAction
            if(BluetoothDevice.ACTION_FOUND==actionString) {
              val bluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE).asInstanceOf[BluetoothDevice]
              if(bluetoothDevice!=null) {
                if(bluetoothDevice.getName!=null && bluetoothDevice.getName.length>0) {
                  if(pairedDevicesShadowHashMap.getOrElse(bluetoothDevice.getAddress,null)==null) {
                    pairedDevicesShadowHashMap += bluetoothDevice.getAddress -> bluetoothDevice.getName
                    arrayAdapter.add(bluetoothDevice.getName+"\n"+bluetoothDevice.getAddress+" bt discovered")
                    arrayAdapter.notifyDataSetChanged
                    if(D) Log.i(TAG, "addAllDevices btBroadcastReceiver BluetoothDevice.ACTION_FOUND name=["+bluetoothDevice.getName+"] addr="+bluetoothDevice.getAddress+
                                     " arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size)
                    if(audioMiniAlert!=null)
                      audioMiniAlert.start
                  }
                  // else todo: replace "bt paired" with "bt paired discovered"
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

        //activity.registerReceiver(btBroadcastReceiver, new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED))
        activity.registerReceiver(btBroadcastReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND))
        activity.registerReceiver(btBroadcastReceiver, new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))

        mBluetoothAdapter.startDiscovery
      }
    }

    if(rfCommService.desiredWifiDirect) {
      // 5. start handler for freshly discovered p2pWifi devices
      if(wifiP2pManager!=null) {
        rfCommService.p2pWifiDiscoveredCallbackFkt = { wifiP2pDevice =>
          if(wifiP2pDevice != null) {
            if(pairedDevicesShadowHashMap.getOrElse(wifiP2pDevice.deviceAddress,null)==null) {
              if(D) Log.i(TAG, "addAllDevices add wifiP2p device deviceName="+wifiP2pDevice.deviceName+" deviceAddress="+wifiP2pDevice.deviceAddress+
                              " status="+wifiP2pDevice.status /*+" "+(wifiP2pDevice.deviceAddress==rfCommService.p2pRemoteAddressToConnect)*/)
              pairedDevicesShadowHashMap += wifiP2pDevice.deviceAddress -> wifiP2pDevice.deviceName
              arrayAdapter.add(wifiP2pDevice.deviceName+"\n"+wifiP2pDevice.deviceAddress+" wifi discovered")
              arrayAdapter.notifyDataSetChanged
              if(D) Log.i(TAG, "addAllDevices add p2pWifi, arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size+" ############")
              if(audioMiniAlert!=null)
                audioMiniAlert.start
            }
          }
        }

        wifiP2pManager.discoverPeers(rfCommService.p2pChannel, new WifiP2pManager.ActionListener() {
          override def onFailure(reasonCode:Int) {
            if(D) Log.i(TAG, "addAllDevices wifiP2pManager.discoverPeers failed reasonCode="+reasonCode)
            // reason ERROR=0, P2P_UNSUPPORTED=1, BUSY=2
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
    if(pairedDevicesShadowHashMap.getOrElse(btAddr,null)==null) {
      if(D) Log.i(TAG, "addDevice BtPairedDevices btAddr="+btAddr+" btName="+btName)
      pairedDevicesShadowHashMap += btAddr -> btName
      arrayAdapter.add(device)
      //if(D) Log.i(TAG, "addDevice arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size+" ############")
    }
  }

  def addAllDevicesUnregister() {
    //if(D) Log.i(TAG, "addAllDevicesUnregister")
    if(rfCommService!=null) {
      // not interested anymore in wifi device discovery
      if(rfCommService.p2pWifiDiscoveredCallbackFkt!=null) {
        if(D) Log.i(TAG, "addAllDevicesUnregister rfCommService.callbackFkt=null")
        rfCommService.p2pWifiDiscoveredCallbackFkt = null
      }
      // not interested anymore in bt device discovery
      if(btBroadcastReceiver!=null) {
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


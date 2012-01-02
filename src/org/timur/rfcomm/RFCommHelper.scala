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

import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentFilter.MalformedMimeTypeException
import android.content.SharedPreferences
import android.content.ServiceConnection
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.DialogInterface
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.AlertDialog.Builder
import android.app.PendingIntent
import android.os.IBinder
import android.os.Environment
import android.os.Handler
import android.os.SystemClock
import android.os.Bundle
import android.widget.Toast
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.media.MediaPlayer
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams

import android.nfc.NfcAdapter
import android.nfc.tech.NfcF
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import java.nio.charset.Charset
import java.util.Locale

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

class RFCommHelper(activity:Activity, msgFromServiceHandler:android.os.Handler, 
                   prefSettings:SharedPreferences = null, prefSettingsEditor:SharedPreferences.Editor = null,
                   allOK:() => Unit, allFailed:() => Unit, 
                   appService:RFServiceTrait,
                   val activityRuntimeClass:java.lang.Class[Activity],
                   audioConfirmSound:MediaPlayer,
                   radioTypeWanted:Int) {

  private val TAG = "RFCommHelper"
  private val D = true

  private val REQUEST_ENABLE_BT = 1
  private val REQUEST_SELECT_PAIRED_DEVICE_AND_CONNECT_TO = 2

  private var activityDestroyed = false
  private var activityResumed = false

  private var isWifiP2pEnabled = false    // if false in onResume, we will offer ACTION_WIRELESS_SETTINGS 
  private var mNfcAdapter:NfcAdapter = null
  private var nfcPendingIntent:PendingIntent = null
  private var nfcFilters:Array[IntentFilter] = null
  private var nfcTechLists:Array[Array[String]] = null
  private var nfcForegroundPushMessage:NdefMessage = null
  private var mBluetoothAdapter:BluetoothAdapter = null
  private var radioTypeSelected = false
  private var desiredBluetooth = false
  private var desiredWifiDirect = false
  private var desiredNfc = false
  private var radioDialogPossibleAndNotYetShown = false
  private var wifiP2pManager:WifiP2pManager = null
  private var wifiDirectBroadcastReceiver:BroadcastReceiver = null

  var rfCommService:RFCommHelperService = null    // activity calls stopActiveConnection -> mConnectThread.cancel
  var p2pConnected = false    // set and cleared in WiFiDirectBroadcastReceiver
  var p2pChannel:Channel = null
  var localP2pWifiAddr:String = null   // set and used in WiFiDirectBroadcastReceiver
  var p2pRemoteAddressToConnect:String = null   // needed to carry the target ip-p2p-addr from ACTION_NDEF_DISCOVERED/discoverPeers() to WIFI_P2P_PEERS_CHANGED_ACTION/wifiP2pManager.connect()
  var discoveringPeersInProgress = false  // so we do not call discoverPeers() again while it is active still
  var connectAttemptFromNfc = false
  var initiatedConnectionByThisDevice = false

  private val intentFilter = new IntentFilter()
  if(android.os.Build.VERSION.SDK_INT>=14) {
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
  }

  if(prefSettings!=null) {
    if((radioTypeWanted & RFCommHelper.RADIO_BT)!=0)
      desiredBluetooth = prefSettings.getBoolean("radioBluetooth", true)
    if((radioTypeWanted & RFCommHelper.RADIO_P2PWIFI)!=0)
      desiredWifiDirect = prefSettings.getBoolean("radioWifiDirect", true)
    if((radioTypeWanted & RFCommHelper.RADIO_NFC)!=0)
      desiredNfc = prefSettings.getBoolean("radioNfc", true)
  }


  if(D) Log.i(TAG, "onCreate startService('RFCommHelperService') ...")
  val serviceIntent = new Intent(activity, classOf[RFCommHelperService])
  //startService(serviceIntent)   // call this only, to keep service active after onDestroy()/unbindService()

  var rfCommServiceConnection = new ServiceConnection { 
    def onServiceDisconnected(className:ComponentName) { 
      if(D) Log.i(TAG, "onCreate onServiceDisconnected set rfCommService=null")
      rfCommService = null
      allFailed()
    } 
    def onServiceConnected(className:ComponentName, rawBinder:IBinder) { 
      if(D) Log.i(TAG, "onCreate onServiceConnected localBinder.getService ...")
      rfCommService = rawBinder.asInstanceOf[RFCommHelperService#LocalBinder].getService
      if(rfCommService==null) {
        Log.e(TAG, "onCreate onServiceConnected no interface to service, rfCommService==null")
        val errMsg = "Error - failed to get service interface from binder"
        if(msgFromServiceHandler!=null)
          msgFromServiceHandler.obtainMessage(RFCommHelperService.ALERT_MESSAGE, -1, -1, errMsg).sendToTarget
        else
          AndrTools.runOnUiThread(activity) { () =>
            Toast.makeText(activity, errMsg, Toast.LENGTH_LONG).show    // todo: create more 'human' text
          }
        return
      }
      if(D) Log.i(TAG, "onCreate onServiceConnected got rfCommService object")
      rfCommService.activity = activity
      rfCommService.activityMsgHandler = msgFromServiceHandler
      rfCommService.appService = appService
      // everything is OK!
      allOK()
    } 
  } 
  if(rfCommServiceConnection!=null) {
    if(D) Log.i(TAG, "onCreate bindService ...")
    activity.bindService(serviceIntent, rfCommServiceConnection, Context.BIND_AUTO_CREATE)
    if(D) Log.i(TAG, "onCreate bindService done")
    radioDialogPossibleAndNotYetShown = true // will be evaluated in onResume
  } else {
    Log.e(TAG, "onCreate bindService failed")
    allFailed()
  }


  // called by wifiDirectBroadcastReceiver #(1)
  def setIsWifiP2pEnabled(setIsWifiP2pEnabled:Boolean) {
    Log.i(TAG, "setIsWifiP2pEnabled="+setIsWifiP2pEnabled)
    if(isWifiP2pEnabled != setIsWifiP2pEnabled) {
      isWifiP2pEnabled = setIsWifiP2pEnabled
      if(!isWifiP2pEnabled)
        p2pConnected = false
    }    
  }

  def initBtNfc() {
    // start bluetooth accept thread
    if(mBluetoothAdapter!=null && mBluetoothAdapter.isEnabled && rfCommService!=null) {
      if(rfCommService.state == RFCommHelperService.STATE_NONE) {
        var acceptOnlySecureConnectRequests = true
        // todo: need secure/insecure decission based on handed over app setting
        //if(prefSettings!=null)
        //  acceptOnlySecureConnectRequests = prefSettings.getBoolean("acceptOnlySecureConnectRequests",true)
        if(D) Log.i(TAG, "initBtNfc rfCommService.start acceptOnlySecureConnectReq="+acceptOnlySecureConnectRequests+" ...")
        rfCommService.start(acceptOnlySecureConnectRequests) // -> bt (new AcceptThread()).start -> run()
      }

      msgFromServiceHandler.obtainMessage(RFCommHelperService.UI_UPDATE, -1, -1).sendToTarget
                        // todo: how do we display the use of dual-radio (p2pwifi+bt) ?
    }

    // initialize nfc
    if(mNfcAdapter!=null && mNfcAdapter.isEnabled) {
      if(D) Log.i(TAG, "initBtNfc -> nfcServiceSetup")
      nfcServiceSetup
    }
  }

  def storeRadioSelection(selectedBt:Boolean, selectedWifi:Boolean, selectedNfc:Boolean) {
      if(prefSettingsEditor!=null) {
        if((radioTypeWanted&RFCommHelper.RADIO_BT)!=0)
          prefSettingsEditor.putBoolean("radioBluetooth",selectedBt)
        if((radioTypeWanted&RFCommHelper.RADIO_P2PWIFI)!=0)
          prefSettingsEditor.putBoolean("radioWifiDirect",selectedWifi)
        if((radioTypeWanted&RFCommHelper.RADIO_NFC)!=0)
          prefSettingsEditor.putBoolean("radioNfc",selectedNfc)
        prefSettingsEditor.commit
      }
  }
  
  // dynamically create a dialog box (not inflated from xml)
  def radioDialog(backKeyIsExit:Boolean) {
    if(D) Log.i(TAG, "radioDialog radioTypeSelected="+radioTypeSelected)
    if(activityDestroyed) {
      if(D) Log.i(TAG, "radioDialog aborted because: activityDestroyed="+activityDestroyed)
      return
    }

    if(!radioTypeSelected) {
      // user did not yet see the dialog, offer the dialog to turn all wanted radio on
      val radioSelectDialogBuilder = new AlertDialog.Builder(activity)
      radioSelectDialogBuilder.setTitle("Radio selection")
      // todo: use a nice fancy "radio wave" background ?

      val radioSelectDialogLayout = new LinearLayout(activity)
      radioSelectDialogLayout.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT))
      radioSelectDialogLayout.setPadding(40, 40, 40, 40)
      radioSelectDialogLayout.setOrientation(LinearLayout.VERTICAL)

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
          radioBluetoothCheckbox.setChecked(desiredBluetooth)
        }
        radioSelectDialogLayout.addView(radioBluetoothCheckbox)
      }
      
      val radioWifiDirectCheckbox = new CheckBox(activity)
      if((radioTypeWanted&RFCommHelper.RADIO_P2PWIFI)!=0) {
/*
        // the app want's to use p2pWifi, if it is supported on this device
        // however, we need to wait a little for wifiDirectBroadcastReceiver to call our setIsWifiP2pEnabled() method
        // so we know if isWifiP2pEnabled is true
        // note: we can sleep here, since we are runing in a separate thread
        if(D) Log.i(TAG, "radioDialog little sleep to find out about the state of isWifiP2pEnabled="+isWifiP2pEnabled)
        try { Thread.sleep(500) } catch { case ex:Exception => }
        if(D) Log.i(TAG, "radioDialog little sleep to find out about the state of isWifiP2pEnabled="+isWifiP2pEnabled+" DONE ##############")
*/        
        radioWifiDirectCheckbox.setText("WiFi Direct not available")
        radioWifiDirectCheckbox.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,19.0f)
        if(wifiP2pManager==null || android.os.Build.VERSION.SDK_INT<14)
          radioWifiDirectCheckbox.setEnabled(false)   // disable if wifip2p-hardware is not available
        else {
          radioWifiDirectCheckbox.setText("WiFi Direct (off)")
          if(isWifiP2pEnabled)    // todo tmtmtm: on start this is NOT set true, even though p2pWifi IS enabled
            radioWifiDirectCheckbox.setText("WiFi Direct")
          radioWifiDirectCheckbox.setChecked(desiredWifiDirect)
        }
        radioSelectDialogLayout.addView(radioWifiDirectCheckbox)
      }

      val radioNfcCheckbox = new CheckBox(activity)
      if((radioTypeWanted&RFCommHelper.RADIO_NFC)!=0) {
        radioNfcCheckbox.setText("NFC not available")
        radioNfcCheckbox.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,19.0f)
        if(mNfcAdapter==null || android.os.Build.VERSION.SDK_INT<10)
          radioNfcCheckbox.setEnabled(false)          // disable if nfc-hardware is not available
        else {
          radioNfcCheckbox.setText("NFC (off)")
          if(mNfcAdapter.isEnabled)
            radioNfcCheckbox.setText("NFC")
          radioNfcCheckbox.setChecked(desiredNfc)
        }
        radioSelectDialogLayout.addView(radioNfcCheckbox)
      }

      radioSelectDialogBuilder.setView(radioSelectDialogLayout)

      val backKeyLabel = if(backKeyIsExit) "Exit" else "Close"
      radioSelectDialogBuilder.setNegativeButton(backKeyLabel, new DialogInterface.OnClickListener() {
        def onClick(dialogInterface:DialogInterface, m:Int) {
          // persist desired-flags
          storeRadioSelection(radioBluetoothCheckbox.isChecked,radioWifiDirectCheckbox.isChecked,radioNfcCheckbox.isChecked)
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
              storeRadioSelection(radioBluetoothCheckbox.isChecked,radioWifiDirectCheckbox.isChecked,radioNfcCheckbox.isChecked)
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
                  desiredBluetooth = radioBluetoothCheckbox.isChecked
                  desiredWifiDirect = radioWifiDirectCheckbox.isChecked
                  desiredNfc = radioNfcCheckbox.isChecked
                  if(D) Log.i(TAG, "radioSelectDialog onClick desiredBluetooth="+desiredBluetooth+" desiredWifiDirect="+desiredWifiDirect+" desiredNfc="+desiredNfc)
                  if(desiredBluetooth==false && desiredWifiDirect==false) {
                    // we need at least 1 type of transport-radio
                    if(msgFromServiceHandler!=null)
                      msgFromServiceHandler.obtainMessage(RFCommHelperService.ALERT_MESSAGE, -1, -1, "No radio enabled for transport").sendToTarget
                    else
                      AndrTools.runOnUiThread(activity) { () =>
                        Toast.makeText(activity, "No radio enabled for transport", Toast.LENGTH_SHORT).show
                      }
                    // we let the dialog stay open

                  } else {
                    radioTypeSelected = true
                    dialogInterface.cancel

                    // persist desired-flags
                    storeRadioSelection(desiredBluetooth,desiredWifiDirect,desiredNfc)
                    initBtNfc  // start bt-accept-thread and init-nfc
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
  }

  def onResume() {
    if(D) Log.i(TAG, "onResume mNfcAdapter="+mNfcAdapter+" wifiP2pManager="+wifiP2pManager+" isWifiP2pEnabled="+isWifiP2pEnabled)

    if((radioTypeWanted&RFCommHelper.RADIO_NFC)!=0) {
      // find out if nfc hardware is supported (not necessarily on)
      if(android.os.Build.VERSION.SDK_INT>=10 && mNfcAdapter==null) {
        try {
          mNfcAdapter = NfcAdapter.getDefaultAdapter(activity)
          if(D) Log.i(TAG, "onResume mNfcAdapter="+mNfcAdapter)
          // continue to setup nfc in nfcServiceSetup()
        } catch {
          case ncdferr:java.lang.NoClassDefFoundError =>
            Log.e(TAG, "onResume NfcAdapter.getDefaultAdapter(this) failed "+ncdferr)
        }
      }
      if(mNfcAdapter!=null) {
        if(D) Log.i(TAG, "onResume nfc supported")
      } else {
        if(D) Log.i(TAG, "onResume nfc not supported")
      }
    }

    if((radioTypeWanted&RFCommHelper.RADIO_BT)!=0) {
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

    if((radioTypeWanted&RFCommHelper.RADIO_P2PWIFI)!=0) {
      // find out if wifi-direct is supported, if so initialze wifiP2pManager
      if(android.os.Build.VERSION.SDK_INT>=14 && wifiP2pManager==null) {
        wifiP2pManager = activity.getSystemService(Context.WIFI_P2P_SERVICE).asInstanceOf[WifiP2pManager]
        if(wifiP2pManager!=null) {
          // register p2pChannel and wifiDirectBroadcastReceiver
          // note: this will result in a call to setIsWifiP2pEnabled(), so we know wether p2pWifi is already activated!
          if(D) Log.i(TAG, "onResume wifiP2p is supported, initialze p2pChannel and register wifiDirectBroadcastReceiver")
          p2pChannel = wifiP2pManager.initialize(activity, activity.getMainLooper, null)
          wifiDirectBroadcastReceiver = rfCommService.newWiFiDirectBroadcastReceiver(wifiP2pManager, this, rfCommService)
          activity.registerReceiver(wifiDirectBroadcastReceiver, intentFilter)
        }
      }
      if(wifiP2pManager==null) {
        if(D) Log.i(TAG, "onResume wifiP2p not supported")
      }
    }

    if(radioDialogPossibleAndNotYetShown) {
      // if all desired radio is already on, we don't need to show the radio dialog

      if((radioTypeWanted&RFCommHelper.RADIO_P2PWIFI)!=0 && wifiP2pManager!=null && !isWifiP2pEnabled) {
        // the app want's to use p2pWifi, if it is supported by this device
        // however, we need to wait a little for wifiDirectBroadcastReceiver to call our setIsWifiP2pEnabled() method, so we know if isWifiP2pEnabled is true
        // if isWifiP2pEnabled is true, we might not need to show the radio-select dialog
        if(D) Log.i(TAG, "onResume little sleep to find out about the state of isWifiP2pEnabled="+isWifiP2pEnabled)
        try { Thread.sleep(300) } catch { case ex:Exception => }
        if(D) Log.i(TAG, "onResume little sleep to find out about the state of isWifiP2pEnabled="+isWifiP2pEnabled+" DONE ##############")
      }

      var radioDialogNeeded = false
      if((radioTypeWanted&RFCommHelper.RADIO_BT)!=0 && mBluetoothAdapter!=null && !mBluetoothAdapter.isEnabled)
        radioDialogNeeded = true
      if((radioTypeWanted&RFCommHelper.RADIO_P2PWIFI)!=0 && wifiP2pManager!=null && !isWifiP2pEnabled)
        radioDialogNeeded = true
      if((radioTypeWanted&RFCommHelper.RADIO_NFC)!=0 && mNfcAdapter!=null && !mNfcAdapter.isEnabled)
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
        initBtNfc  // start bt-accept-thread and init-nfc

        // prepare desired-switches for use with ACTION_NDEF_DISCOVERED
        if((radioTypeWanted & RFCommHelper.RADIO_BT)!=0)
          desiredBluetooth = true
        if((radioTypeWanted & RFCommHelper.RADIO_P2PWIFI)!=0)
          desiredWifiDirect = true
        if((radioTypeWanted & RFCommHelper.RADIO_NFC)!=0)
          desiredNfc = true
      }
    } else {
      new Thread() {
        override def run() {
          // delay this, so that user can still exit app if wanted
          try { Thread.sleep(600) } catch { case ex:Exception => }
          if(!activityDestroyed)
            switchOnDesiredRadios
        }
      }.start
    }

    if(mNfcAdapter!=null && mNfcAdapter.isEnabled) {
      if(nfcPendingIntent!=null) {
        // This method must be called from the main thread, and only when the activity is in the foreground (resumed). 
        // Also, activities must call disableForegroundDispatch(Activity) before the completion of their onPause() 
        // callback to disable foreground dispatch after it has been enabled. 
        AndrTools.runOnUiThread(activity) { () =>
          mNfcAdapter.enableForegroundDispatch(activity, nfcPendingIntent, nfcFilters, nfcTechLists)
          if(D) Log.i(TAG, "onResume nfc enableForegroundDispatch done")
        }
      }
      if(nfcForegroundPushMessage!=null) {
        //mNfcAdapter.enableForegroundNdefPush(activity, nfcForegroundPushMessage)
        //if(D) Log.i(TAG, "onResume enableForegroundNdefPush done")
        mNfcAdapter.setNdefPushMessage(nfcForegroundPushMessage, activity)
        if(D) Log.i(TAG, "onResume setNdefPushMessage done")
      }
    }

    // set acceptAndConnect if possible / update mainViewUpdate if necessary
    if(rfCommService!=null) {
      rfCommService.acceptAndConnect = true
      // RFCommService will otherwise not answer incoming connect requests
      if(D) Log.i(TAG, "onResume set rfCommService.acceptAndConnect="+rfCommService.acceptAndConnect)

      // no! this undo's any visual activity (for instance the connect-progress animation)
      //if(rfCommService.state!=RFCommHelperService.STATE_CONNECTED)    // ???
      //  msgFromServiceHandler.obtainMessage(RFCommHelperService.UI_UPDATE, -1, -1).sendToTarget
    } else {
      Log.i(TAG, "onResume rfCommService==null, acceptAndConnect not set")
    }

    activityResumed = true
  }

  def onPause() {
    activityResumed = false
    if(D) Log.i(TAG, "onPause...")

/*
    new Thread() {
      override def run() {
        try { Thread.sleep(500) } catch { case ex:Exception => }
        if(activityResumed) {
          if(D) Log.i(TAG, "onPause delayed activityResumed already set !!!!!!!!!!!!!!")

        } else {
          if(D) Log.i(TAG, "onPause delayed activityResumed not set !!!!!!!!!!!!!!")
*/
          AndrTools.runOnUiThread(activity) { () =>
            if(mNfcAdapter!=null && mNfcAdapter.isEnabled) {
              mNfcAdapter.disableForegroundDispatch(activity)
              if(nfcForegroundPushMessage!=null) {
                //mNfcAdapter.disableForegroundNdefPush(activity)
                //if(D) Log.i(TAG, "onPause disableForegroundNdefPush done")
                mNfcAdapter.setNdefPushMessage(null, activity)
                if(D) Log.i(TAG, "onPause setNdefPushMessage null done")
              }
            }

            if(rfCommService!=null) {
              rfCommService.acceptAndConnect = false
              Log.i(TAG, "onPause rfCommService.acceptAndConnect cleared")
              // todo tmtmtm: if this is just a "small onPause" (triggered by nfc-system-animation)
              //              rfcommservice will NOT be able to answer an incoming bt-connect-request   
            } else {
              Log.i(TAG, "onPause rfCommService==null, acceptAndConnect not cleared")
            }
          }
/*
        }
      }
    }.start
*/
  }

  def onDestroy() {
    if(rfCommService!=null) {
      rfCommService.stopActiveConnection
      rfCommService.stopAcceptThread
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

    if(wifiP2pManager!=null && p2pChannel!=null) {
      if(D) Log.i(TAG, "onDestroy wifiP2pManager.removeGroup")
      wifiP2pManager.removeGroup(p2pChannel, new ActionListener() {
        override def onSuccess() {
          if(D) Log.i(TAG, "onDestroy wifiP2pManager.removeGroup() success")
          // wifiDirectBroadcastReceiver will notify us
        }

        override def onFailure(reason:Int) {
          if(D) Log.i(TAG, "onDestroy wifiP2pManager.removeGroup() failed reason="+reason)
          // reason ERROR=0, P2P_UNSUPPORTED=1, BUSY=2
        }
      })
      p2pConnected = false  // maybe not necessary
      //p2pChannel = null
      //wifiP2pManager = null
    }

    activityDestroyed=true
  }

  def onNewIntent(intent:Intent) :Boolean = {
    // all sort of intents may arrive here... for instance ACTION_NDEF_DISCOVERED
    if(D) Log.i(TAG, "onNewIntent intent="+intent+" intent.getAction="+intent.getAction+" mNfcAdapter="+mNfcAdapter)

    // we are interested in nfc-intents (ACTION_NDEF_DISCOVERED)
    if(android.os.Build.VERSION.SDK_INT>=10 && mNfcAdapter!=null && intent.getAction==NfcAdapter.ACTION_NDEF_DISCOVERED) {
      val ncfActionString = NfcHelper.checkForNdefAction(activity, intent)
      if(D) Log.i(TAG, "onNewIntent NfcEnabled="+mNfcAdapter.isEnabled+" ncfActionString="+ncfActionString+" desiredWifiDirect="+desiredWifiDirect+" desiredBluetooth="+desiredBluetooth)
      if(mNfcAdapter.isEnabled && ncfActionString!=null) {
        // this is a nfc-intent, ncfActionString may look something like this: "bt=xxyyzzxxyyzz|p2pWifi=xx:yy:zz:xx:yy:zz"
        val idxP2p = ncfActionString.indexOf("p2pWifi=")
        val idxBt = ncfActionString.indexOf("bt=")
        //if(D) Log.i(TAG, "onNewIntent idxP2p="+idxP2p+" idxBt="+idxBt+" mBluetoothAdapter="+mBluetoothAdapter)

        if(wifiP2pManager!=null && desiredWifiDirect && idxP2p>=0) {
          var p2pWifiAddr = ncfActionString.substring(idxP2p+8)
          val idxPipe = p2pWifiAddr.indexOf("|")
          if(idxPipe>=0) 
            p2pWifiAddr = p2pWifiAddr.substring(0,idxPipe)
          if(D) Log.i(TAG, "onNewIntent p2pWifiAddr="+p2pWifiAddr)

          // play audio notification (as earliest possible feedback for nfc activity)
          if(audioConfirmSound!=null)
            audioConfirmSound.start

          p2pRemoteAddressToConnect = p2pWifiAddr

          if(discoveringPeersInProgress) {
            if(D) Log.i(TAG, "onNewIntent discoveringPeersInProgress: do not call discoverPeers() again")

          } else {
            if(D) Log.i(TAG, "onNewIntent wifiP2pManager.discoverPeers()")
            wifiP2pManager.discoverPeers(p2pChannel, new WifiP2pManager.ActionListener() {
              // note: discovered peers arrive via wifiDirectBroadcastReceiver WIFI_P2P_PEERS_CHANGED_ACTION
              //       a call to manager.requestPeers() will hand over a PeerListListener with onPeersAvailable() which contains a WifiP2pDeviceList
              //       WifiP2pDeviceList.getDeviceList(), a list of WifiP2pDevice objects, each containg deviceAddress, deviceName, primaryDeviceType, etc.
              
              // note: initiated discovery requests stay active until the device starts connecting to a peer or forms a p2p group
              
              // todo: p2pWifi problem: sometimes we get neither onSuccess nor onFailure
              //       and the cause does not seem to be the other device (problem stays after other devices was rebooted)
              //       just restarting the app (on GN) solves the problem - this is an app issue!

              override def onSuccess() {
                discoveringPeersInProgress = true
                if(D) Log.i(TAG, "onNewIntent discoverPeers() success")
              }

              override def onFailure(reasonCode:Int) {
                if(D) Log.i(TAG, "onNewIntent discoverPeers() fail reasonCode="+reasonCode)
                // reason ERROR=0, P2P_UNSUPPORTED=1, BUSY=2
                // note: we do get 2
                if(reasonCode!=2)
                  discoveringPeersInProgress = false
              }
            })
            if(D) Log.i(TAG, "onNewIntent wifiP2pManager.discoverPeers() done")
          }

        } else if(mBluetoothAdapter!=null && desiredBluetooth && idxBt>=0) {
          var btAddr = ncfActionString.substring(idxBt+3)
          val idxPipe = btAddr.indexOf("|")
          if(idxPipe>=0) 
            btAddr = btAddr.substring(0,idxPipe)
          if(D) Log.i(TAG, "onNewIntent btAddr="+btAddr+" rfCommService="+rfCommService)

          // play audio notification (as earliest possible feedback for nfc activity)
          if(audioConfirmSound!=null)
            audioConfirmSound.start

          if(rfCommService!=null) {
            if(D) Log.i(TAG, "onNewIntent NdefAction rfCommService!=null activityResumed="+activityResumed)
            if(activityResumed)   // tmtmtm ???
              rfCommService.acceptAndConnect = true

            def remoteBluetoothDevice = mBluetoothAdapter.getRemoteDevice(btAddr)
            if(remoteBluetoothDevice!=null) {
              //val sendFilesCount = if(selectedFileStringsArrayList!=null) selectedFileStringsArrayList.size else 0
              //if(D) Log.i(TAG, "onNewIntent NdefAction sendFilesCount="+sendFilesCount+" ...")
              if(D) Log.i(TAG, "onNewIntent NdefAction remoteBluetoothDevice!=null")

              if(mBluetoothAdapter.getAddress > remoteBluetoothDevice.getAddress) {
                // our local btAddr is > than the remote btAddr: we become the actor and we will bt-connect
                // our activity may still be in onPause mode due to NFC activity: sleep a bit before 
                if(D) Log.i(TAG, "onNewIntent NdefAction connecting ...")

                connectAttemptFromNfc=true
                rfCommService.connectBt(remoteBluetoothDevice)
                // connectBt() will send CONNECTION_START to the activity, which will draw the connect-progress animation

              } else {
                // our local btAddr is < than the remote btAddr: we just wait for a bt-connect request
                if(D) Log.i(TAG, "onNewIntent passively waiting for incoming connect request... mSecureAcceptThread="+rfCommService.mSecureAcceptThread)

                // show "connecting progress" animation
                // todo: what if noone connects? can this aniation be aborted, does it timeout?
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
            nfcServiceSetup // update our ndef push message to include our btAddr
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

      case REQUEST_SELECT_PAIRED_DEVICE_AND_CONNECT_TO =>
        if(D) Log.i(TAG, "onActivityResult REQUEST_SELECT_PAIRED_DEVICE_AND_CONNECT_TO")
        if(resultCode!=Activity.RESULT_OK) {
          Log.e(TAG, "REQUEST_SELECT_PAIRED_DEVICE_AND_CONNECT_TO resultCode!=Activity.RESULT_OK ="+resultCode)
        } else
        if(intent==null) {
          Log.e(TAG, "REQUEST_SELECT_PAIRED_DEVICE_AND_CONNECT_TO intent==null")
        } else {
          val bundle = intent.getExtras()
          if(bundle==null) {
            Log.e(TAG, "REQUEST_SELECT_PAIRED_DEVICE_AND_CONNECT_TO intent.getExtras==null")
          } else {
            val btDevice = bundle.getString("btdevice")
            if(D) Log.i(TAG, "REQUEST_SELECT_PAIRED_DEVICE_AND_CONNECT_TO btDevice="+btDevice)
            if(btDevice==null) {
              Log.e(TAG, "REQUEST_SELECT_PAIRED_DEVICE_AND_CONNECT_TO btDevice==null")
            } else {
              // user has selected one paired device to manually connect to
              val idxCR = btDevice.indexOf("\n")
              if(idxCR<1) {
                Log.e(TAG, "REQUEST_SELECT_PAIRED_DEVICE_AND_CONNECT_TO idxCR<1")
              } else {
                val btAddr = btDevice.substring(idxCR+1)
                val btName = btDevice.substring(0,idxCR)
                if(D) Log.i(TAG, "REQUEST_SELECT_PAIRED_DEVICE_AND_CONNECT_TO btName="+btDevice+" btAddr="+btAddr)
            		Toast.makeText(activity, "Bt connecting to "+btName, Toast.LENGTH_SHORT).show
               
                // connect to btAddr
                val remoteBluetoothDevice = BluetoothAdapter.getDefaultAdapter.getRemoteDevice(btAddr)
                if(remoteBluetoothDevice==null) {
                  Log.e(TAG, "REQUEST_SELECT_PAIRED_DEVICE_AND_CONNECT_TO remoteBluetoothDevice==null")
                } else {
                  //val sendFilesCount = if(selectedFileStringsArrayList!=null) selectedFileStringsArrayList.size else 0
                  //if(D) Log.i(TAG, "REQUEST_SELECT_PAIRED_DEVICE_AND_CONNECT_TO rfCommService.connectBt() sendFilesCount="+sendFilesCount+" ...")
                  if(D) Log.i(TAG, "REQUEST_SELECT_PAIRED_DEVICE_AND_CONNECT_TO rfCommService.connectBt() ...")
                  initiatedConnectionByThisDevice = true
                  connectAttemptFromNfc=false
                  rfCommService.connectBt(remoteBluetoothDevice)
                }
              }
            }
          }
        }
        return true

      case _ =>
        return false
    }
  }

  def nfcServiceSetup() {
    // this is called by radioDialog/onOK, by wifiDirectBroadcastReceiver:WIFI_P2P_THIS_DEVICE_CHANGED_ACTION and by onActivityResult:REQUEST_ENABLE_BT

    if(D) Log.i(TAG, "nfcServiceSetup mNfcAdapter="+mNfcAdapter+" ...")

    // setup NFC (only for Android 2.3.3+ and only if NFC hardware is available)
    if(android.os.Build.VERSION.SDK_INT>=10 && mNfcAdapter!=null && mNfcAdapter.isEnabled) {
      if(nfcPendingIntent==null) {
        // Create a generic PendingIntent that will be delivered to this activity 
        // The NFC stack will fill in the intent with the details of the discovered tag 
        // before delivering to this activity.
        nfcPendingIntent = PendingIntent.getActivity(activity, 0,
                new Intent(activity, activityRuntimeClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0)

        // setup an intent filter for all MIME based dispatches
        val ndef = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        try {
          if(D) Log.i(TAG, "nfcServiceSetup ndef.addDataType...")
          ndef.addDataType("*/*")   // or "text/plain"
          if(D) Log.i(TAG, "nfcServiceSetup ndef.addDataType done")
        } catch {
          case e: MalformedMimeTypeException =>
            Log.e(TAG, "nfcServiceSetup ndef.addDataType MalformedMimeTypeException")
            throw new RuntimeException("fail", e)
        }
        nfcFilters = Array(ndef)

        // Setup a tech list for all NfcF tags
        if(D) Log.i(TAG, "nfcServiceSetup setup a tech list for all NfcF tags...")
        nfcTechLists = Array(Array(classOf[NfcF].getName))
      }
      if(D) Log.i(TAG, "nfcServiceSetup enable nfc dispatch mNfcAdapter="+mNfcAdapter+" activity="+activity+" nfcPendingIntent="+nfcPendingIntent+" nfcFilters="+nfcFilters+" nfcTechLists="+nfcTechLists+" ...")

      if(activityResumed) {
        // This method must be called from the main thread, and only when the activity is in the foreground (resumed). 
        // Also, activities must call disableForegroundDispatch(Activity) before the completion of their onPause() callback 
        mNfcAdapter.enableForegroundDispatch(activity, nfcPendingIntent, nfcFilters, nfcTechLists)
        if(D) Log.i(TAG, "nfcServiceSetup enableForegroundDispatch done")
      } else {
        if(D) Log.i(TAG, "nfcServiceSetup enableForegroundDispatch delayed until activity is resumed")
      }

      // embed our btAddress + localP2pWifiAddr in a new NdefMessage to be used via enableForegroundNdefPush
      var nfcString = ""
      val btAddress = mBluetoothAdapter.getAddress
      if(desiredBluetooth && btAddress!=null)
        nfcString += "bt="+btAddress
      if(desiredWifiDirect && localP2pWifiAddr!=null) {
        if(nfcString.length>0)
          nfcString += "|"
        nfcString += "p2pWifi="+localP2pWifiAddr
      }

      if(nfcString.length==0) {
        // this should never happen, right?
        if(D) Log.i(TAG, "nfcServiceSetup nfcString empty")
        nfcForegroundPushMessage=null
        if(activityResumed) {
          //mNfcAdapter.disableForegroundNdefPush(activity)
          mNfcAdapter.setNdefPushMessage(null, activity)
        }

      } else {        
        nfcForegroundPushMessage = new NdefMessage(Array(NfcHelper.newTextRecord(nfcString, Locale.ENGLISH, true)))
        if(nfcForegroundPushMessage!=null) {
          if(activityResumed) {
            //mNfcAdapter.enableForegroundNdefPush(activity, nfcForegroundPushMessage)
            //if(D) Log.i(TAG, "nfcServiceSetup enable nfc ForegroundNdefPush nfcString=["+nfcString+"] done")
            mNfcAdapter.setNdefPushMessage(nfcForegroundPushMessage, activity)
            if(D) Log.i(TAG, "setNdefPushMessage enable nfc ForegroundNdefPush nfcString=["+nfcString+"] done")

          } else {
            if(D) Log.i(TAG, "nfcServiceSetup enable nfc ForegroundNdefPush nfcString=["+nfcString+"] delayed until activity is resumed")
          }
        }
      }

    } else {
      Log.e(TAG, "nfcServiceSetup NFC NOT set up mNfcAdapter="+mNfcAdapter)
    }
  }

  def switchOnDesiredRadios() {
    if(desiredNfc && android.os.Build.VERSION.SDK_INT>=10 && mNfcAdapter!=null && !mNfcAdapter.isEnabled) {
      // let user enable nfc
      if(D) Log.i(TAG, "msgFromServiceHandler switchOnDesiredRadios !mNfcAdapter.isEnabled: ask user to enable nfc")
      AndrTools.runOnUiThread(activity) { () =>
        Toast.makeText(activity, "Please enable 'NFC', then go back...", Toast.LENGTH_SHORT).show
      }
      activity.startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
      // todo: onexit: offer to disable NFC

    } else if(desiredWifiDirect && android.os.Build.VERSION.SDK_INT>=14 && wifiP2pManager!=null && !isWifiP2pEnabled) {
      // let user enable wifip2p
      if(D) Log.i(TAG, "msgFromServiceHandler switchOnDesiredRadios isWifiP2pEnabled="+isWifiP2pEnabled+": ask user to enable p2p")
      AndrTools.runOnUiThread(activity) { () =>
        Toast.makeText(activity, "Please enable 'WiFi direct', then go back...", Toast.LENGTH_SHORT).show
      }

      activity.startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
      // note: once wifi-direct will be switched on (manually by the user), we will receive setIsWifiP2pEnabled(true)
      //       -> which will trigger discoverPeers()
      //       -> which will trigger a p2p connect request to the ipAddr given by nfc-dispatch
      // todo: onexit: offer to disable wifi-direct

    } else if(desiredBluetooth && mBluetoothAdapter!=null && !mBluetoothAdapter.isEnabled) {
      // let user enable bluetooth
      val enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
      activity.startActivityForResult(enableIntent, REQUEST_ENABLE_BT)
      // -> onActivityResult/REQUEST_ENABLE_BT -> if(resultCode == Activity.RESULT_OK) nfcServiceSetup()
      // todo: onexit: offer to disable BT
    }
  }

  def isNfcEnabled() :Boolean = {
    if(android.os.Build.VERSION.SDK_INT>=10 && mNfcAdapter!=null && mNfcAdapter.isEnabled)
      return true
    return false
  }

  def offerBtConnect() {
/* todo: re-enable this
         - implement SelectPairedDevicePopupActivity as dialog
         - show paired devices (and unpaird devices, in case of support for insecure-BT)
         - show p2pWifi devices
    if(D) Log.i(TAG, "onClick buttonManualConnect new Intent(context, classOf[SelectPairedDevicePopupActivity])")
    val intent = new Intent(activity, classOf[SelectPairedDevicePopupActivity])
    if(D) Log.i(TAG, "onClick buttonManualConnect startActivityForResult")
    activity.startActivityForResult(intent, REQUEST_SELECT_PAIRED_DEVICE_AND_CONNECT_TO) // -> SelectPairedDevicePopupActivity -> onActivityResult()
*/
  }
}


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

import scala.collection.mutable // for instance: mutable.HashMap

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

class RFCommHelper(activity:Activity, msgFromServiceHandler:android.os.Handler, 
                   prefSettings:SharedPreferences = null, prefSettingsEditor:SharedPreferences.Editor = null,
                   allOK:() => Unit, allFailed:() => Unit, 
                   appService:RFServiceTrait,
                   activityRuntimeClass:java.lang.Class[Activity],
                   audioConfirmSound:MediaPlayer,
                   radioTypeWanted:Int) {

  private val TAG = "RFCommHelper"
  private val D = true

  private val REQUEST_ENABLE_BT = 101

  var rfCommService:RFCommHelperService = null    // activity calls stopActiveConnection -> mConnectThread.cancel

  var connectAttemptFromNfc = false
  var wifiP2pManager:WifiP2pManager = null

  private var activityDestroyed = false

  var mBluetoothAdapter:BluetoothAdapter = null
  private var radioDialogPossibleAndNotYetShown = false
  private var wifiDirectBroadcastReceiver:BroadcastReceiver = null

  private val intentFilter = new IntentFilter()
  if(android.os.Build.VERSION.SDK_INT>=14) {
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
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

      // we got connected to rfCommService

      if(D) Log.i(TAG, "onCreate onServiceConnected got rfCommService object")
      rfCommService.activity = activity
      rfCommService.activityRuntimeClass = activityRuntimeClass
      rfCommService.activityMsgHandler = msgFromServiceHandler
      rfCommService.appService = appService

      if(prefSettings!=null) {
        if((radioTypeWanted & RFCommHelper.RADIO_BT)!=0)
          rfCommService.desiredBluetooth = prefSettings.getBoolean("radioBluetooth", true)
        if((radioTypeWanted & RFCommHelper.RADIO_P2PWIFI)!=0)
          rfCommService.desiredWifiDirect = prefSettings.getBoolean("radioWifiDirect", true)
        if((radioTypeWanted & RFCommHelper.RADIO_NFC)!=0)
          rfCommService.desiredNfc = prefSettings.getBoolean("radioNfc", true)
      }

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

  def initBtNfc() {
    // start bluetooth accept thread
    if(mBluetoothAdapter!=null && mBluetoothAdapter.isEnabled && rfCommService!=null) {
      if(rfCommService.state == RFCommHelperService.STATE_NONE) {
        if(D) Log.i(TAG, "initBtNfc rfCommService.start acceptOnlySecureConnectReq="+rfCommService.pairedBtOnly+" ...")
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
                rfCommService.desiredBluetooth = radioBluetoothCheckbox.isChecked
                rfCommService.desiredWifiDirect = radioWifiDirectCheckbox.isChecked
                rfCommService.desiredNfc = radioNfcCheckbox.isChecked
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
                  storeRadioSelection(rfCommService.desiredBluetooth,rfCommService.desiredWifiDirect,rfCommService.desiredNfc)
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
        initBtNfc  // start bt-accept-thread and init-nfc
        // prepare desired-switches for use with ACTION_NDEF_DISCOVERED
        if((radioTypeWanted & RFCommHelper.RADIO_BT)!=0)
          rfCommService.desiredBluetooth = true
        if((radioTypeWanted & RFCommHelper.RADIO_P2PWIFI)!=0)
          rfCommService.desiredWifiDirect = true
        if((radioTypeWanted & RFCommHelper.RADIO_NFC)!=0)
          rfCommService.desiredNfc = true
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

    if(rfCommService.mNfcAdapter!=null && rfCommService.mNfcAdapter.isEnabled) {
      if(rfCommService.nfcPendingIntent!=null) {
        // This method must be called from the main thread, and only when the activity is in the foreground (resumed). 
        // Also, activities must call disableForegroundDispatch(Activity) before the completion of their onPause() 
        // callback to disable foreground dispatch after it has been enabled. 
        AndrTools.runOnUiThread(activity) { () =>
          rfCommService.mNfcAdapter.enableForegroundDispatch(activity, rfCommService.nfcPendingIntent, rfCommService.nfcFilters, rfCommService.nfcTechLists)
          //if(D) Log.i(TAG, "onResume nfc enableForegroundDispatch done")
        }
      }
      if(rfCommService.nfcForegroundPushMessage!=null) {
        rfCommService.mNfcAdapter.setNdefPushMessage(rfCommService.nfcForegroundPushMessage, activity)
        //if(D) Log.i(TAG, "onResume nfc setNdefPushMessage done")
      }
    }

    // set acceptAndConnect if possible / update mainViewUpdate if necessary
    if(rfCommService!=null) {
      if(rfCommService.acceptAndConnect==false) {
        rfCommService.acceptAndConnect = true
        // RFCommService will otherwise not answer incoming connect requests
        if(D) Log.i(TAG, "onResume set rfCommService.acceptAndConnect="+rfCommService.acceptAndConnect)

        // no! this undo's any visual activity (for instance the connect-progress animation)
        //if(rfCommService.state!=RFCommHelperService.STATE_CONNECTED)    // ???
        //  msgFromServiceHandler.obtainMessage(RFCommHelperService.UI_UPDATE, -1, -1).sendToTarget
      }
    } else {
      Log.i(TAG, "onResume rfCommService==null, acceptAndConnect not set")
    }

    rfCommService.activityResumed = true
  }

  def onPause() {
    if(rfCommService!=null)
      rfCommService.activityResumed = false
    if(D) Log.i(TAG, "onPause...")
    AndrTools.runOnUiThread(activity) { () =>
      if(rfCommService!=null) {
        if(rfCommService.mNfcAdapter!=null && rfCommService.mNfcAdapter.isEnabled) {
          rfCommService.mNfcAdapter.disableForegroundDispatch(activity)
          rfCommService.mNfcAdapter.setNdefPushMessage(null, activity)
          if(D) Log.i(TAG, "onPause setNdefPushMessage null done")
        }
        rfCommService.acceptAndConnect = false
        Log.i(TAG, "onPause rfCommService.acceptAndConnect cleared")
        // todo tmtmtm: if this is just a "small onPause" (triggered by nfc-system-animation)
        //              rfcommservice will NOT be able to answer an incoming bt-connect-request   
      } else {
        Log.i(TAG, "onPause rfCommService==null, acceptAndConnect not cleared")
      }
    }
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

/*
  def getP2pWifiDevices():java.util.ArrayList[String] = {
    val pairedDevicesArrayListOfStrings = new java.util.ArrayList[String]()
    return pairedDevicesArrayListOfStrings
  }
*/

  def onNewIntent(intent:Intent) :Boolean = {
    // all sort of intents may arrive here... for instance ACTION_NDEF_DISCOVERED
    if(D) Log.i(TAG, "onNewIntent intent="+intent+" intent.getAction="+intent.getAction+" mNfcAdapter="+rfCommService.mNfcAdapter)
    // we are interested in nfc-intents (ACTION_NDEF_DISCOVERED)
    if(android.os.Build.VERSION.SDK_INT>=10 && rfCommService.mNfcAdapter!=null && intent.getAction==NfcAdapter.ACTION_NDEF_DISCOVERED) {
      val ncfActionString = NfcHelper.checkForNdefAction(activity, intent)
      if(D) Log.i(TAG, "onNewIntent NfcEnabled="+rfCommService.mNfcAdapter.isEnabled+" ncfActionString="+ncfActionString+" desiredWifiDirect="+rfCommService.desiredWifiDirect+" desiredBluetooth="+rfCommService.desiredBluetooth)
      if(rfCommService.mNfcAdapter.isEnabled && ncfActionString!=null) {
        // this is a nfc-intent, ncfActionString may look something like this: "bt=xxyyzzxxyyzz|p2pWifi=xx:yy:zz:xx:yy:zz"
        val idxP2p = ncfActionString.indexOf("p2pWifi=")
        val idxBt = ncfActionString.indexOf("bt=")
        //if(D) Log.i(TAG, "onNewIntent idxP2p="+idxP2p+" idxBt="+idxBt+" mBluetoothAdapter="+mBluetoothAdapter)

        if(wifiP2pManager!=null && rfCommService.desiredWifiDirect && idxP2p>=0) {
          var p2pWifiAddr = ncfActionString.substring(idxP2p+8)
          val idxPipe = p2pWifiAddr.indexOf("|")
          if(idxPipe>=0) 
            p2pWifiAddr = p2pWifiAddr.substring(0,idxPipe)
          if(D) Log.i(TAG, "onNewIntent p2pWifiAddr="+p2pWifiAddr)

          // play audio notification (as earliest possible feedback for nfc activity)
          if(audioConfirmSound!=null)
            audioConfirmSound.start

          rfCommService.connectWifi(wifiP2pManager, p2pWifiAddr, "nfc-target")

        } else if(mBluetoothAdapter!=null && rfCommService.desiredBluetooth && idxBt>=0) {
          var btAddr = ncfActionString.substring(idxBt+3)
          val idxPipe = btAddr.indexOf("|")
          if(idxPipe>=0) 
            btAddr = btAddr.substring(0,idxPipe)
          if(D) Log.i(TAG, "onNewIntent btAddr="+btAddr+" rfCommService="+rfCommService)

          // play audio notification (as earliest possible feedback for nfc activity)
          if(audioConfirmSound!=null)
            audioConfirmSound.start

          if(rfCommService!=null) {
            if(D) Log.i(TAG, "onNewIntent NdefAction rfCommService!=null activityResumed="+rfCommService.activityResumed)
            if(rfCommService.activityResumed)   // todo tmtmtm ???
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

                //connectAttemptFromNfc=true    // todo ???
                rfCommService.connectBt(remoteBluetoothDevice)
                // connectBt() will send CONNECTION_START to the activity, which will draw the connect-progress animation

              } else {
                // our local btAddr is < than the remote btAddr: we just wait for a bt-connect request
                if(D) Log.i(TAG, "onNewIntent passively waiting for incoming connect request... mSecureAcceptThread="+rfCommService.mSecureAcceptThread)

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
      if(D) Log.i(TAG, "msgFromServiceHandler switchOnDesiredRadios !rfCommService.mNfcAdapter.isEnabled: ask user to enable nfc")
      AndrTools.runOnUiThread(activity) { () =>
        Toast.makeText(activity, "Please enable 'NFC', then go back...", Toast.LENGTH_SHORT).show
      }
      activity.startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
      // todo: onexit: offer to disable NFC

    } else if(rfCommService.desiredWifiDirect && android.os.Build.VERSION.SDK_INT>=14 && wifiP2pManager!=null && !rfCommService.isWifiP2pEnabled) {
      // let user enable wifip2p
      if(D) Log.i(TAG, "msgFromServiceHandler switchOnDesiredRadios isWifiP2pEnabled="+rfCommService.isWifiP2pEnabled+": ask user to enable p2p")
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




  private var pairedDevicesShadowHashMap:mutable.HashMap[String,String] = null
  private var btBroadcastReceiver:BroadcastReceiver = null
  private var arrayAdapter:ArrayAdapter[String] = null

  // todo: must render 2nd line of listview entry (deviceAddr + comment) much smaller
  // todo: would be nice if we could make it, so that btName and wifiName are the same

  def addAllDevices(setArrayAdapter:ArrayAdapter[String]) {
    arrayAdapter = setArrayAdapter
    // now fill our listView with all possible (paired/stored/discovered) devices of the requested device types
    // we use pairedDevicesShadowHashMap[addr,name] as a shadow-HashMap containing all listed devices, so we can prevent double-entries in the visible arrayAdapter
    pairedDevicesShadowHashMap = new mutable.HashMap[String,String]()
    if(D) Log.i(TAG, "fill listView with all devices, arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size+" ####################################")

    if(rfCommService.desiredBluetooth) {
      // 1. get list of paired bt devices from rfCommHelper
      val pairedDevicesArrayListOfStrings = getBtPairedDevices  // java.util.ArrayList[String], "name/naddr"
      if(pairedDevicesArrayListOfStrings!=null) {
        if(D) Log.i(TAG, "add BtPairedDevices count="+pairedDevicesArrayListOfStrings.size+" arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size)
        if(pairedDevicesArrayListOfStrings.size>0)
          for(i <- 0 until pairedDevicesArrayListOfStrings.size)
            addDevice(pairedDevicesArrayListOfStrings.get(i))
      }

      // todo: 2. get list of stored (previously connected) bt devices

      // 3. start handler for all newly discovered bt devices
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
                    if(D) Log.i(TAG, "btBroadcastReceiver BluetoothDevice.ACTION_FOUND name=["+bluetoothDevice.getName+"] addr="+bluetoothDevice.getAddress+
                                     " arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size)
                  }
                  // else todo: replace "bt paired" with "bt paired discovered"
                }
              }
            }
            else if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED==actionString) {
              //if(D) Log.i(TAG,"btBroadcastReceiver ACTION_DISCOVERY_FINISHED arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size+" ############")
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
      // todo: 4. get list of previously connected p2pWifi devices

      // 5. start handler for freshly discovered p2pWifi devices
      if(wifiP2pManager!=null) {
        rfCommService.p2pWifiDiscoveredCallbackFkt = { wifiP2pDevice =>
          if(wifiP2pDevice != null) {
            if(pairedDevicesShadowHashMap.getOrElse(wifiP2pDevice.deviceAddress,null)==null) {
              if(D) Log.i(TAG, "add wifiP2p device deviceName="+wifiP2pDevice.deviceName+" deviceAddress="+wifiP2pDevice.deviceAddress+
                              " status="+wifiP2pDevice.status+" "+(wifiP2pDevice.deviceAddress==rfCommService.p2pRemoteAddressToConnect))
              pairedDevicesShadowHashMap += wifiP2pDevice.deviceAddress -> wifiP2pDevice.deviceName
              arrayAdapter.add(wifiP2pDevice.deviceName+"\n"+wifiP2pDevice.deviceAddress+" wifi discovered")
              if(D) Log.i(TAG, "add p2pWifi, arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size+" ############")
            }
          }
        }

        wifiP2pManager.discoverPeers(rfCommService.p2pChannel, new WifiP2pManager.ActionListener() {
          override def onFailure(reasonCode:Int) {
            if(D) Log.i(TAG, "wifiP2pManager.discoverPeers failed reasonCode="+reasonCode)
            // reason ERROR=0, P2P_UNSUPPORTED=1, BUSY=2
          }
          override def onSuccess() {
            //if(D) Log.i(TAG, "wifiP2pManager.discoverPeers onSuccess")
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
      if(D) Log.i(TAG, "add BtPairedDevices btAddr="+btAddr+" btName="+btName)
      pairedDevicesShadowHashMap += btAddr -> btName
      arrayAdapter.add(device)
      //if(D) Log.i(TAG, "add device, arrayAdapter.getCount="+arrayAdapter.getCount+" "+pairedDevicesShadowHashMap.size+" ############")
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


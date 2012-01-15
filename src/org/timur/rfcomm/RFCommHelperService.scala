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

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.FileWriter
import java.util.UUID
import java.util.LinkedList
import java.util.ArrayList
import java.util.Calendar
import java.net.Socket
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.InetAddress

import android.app.Activity
import android.app.ActivityManager
import android.app.PendingIntent
import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentFilter.MalformedMimeTypeException
import android.content.BroadcastReceiver
import android.content.SharedPreferences
import android.os.IBinder
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.SystemClock
import android.os.Environment
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.media.MediaPlayer
import android.provider.Settings
import android.widget.Toast

import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager.PeerListListener
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.WifiManager
import android.net.NetworkInfo

import android.nfc.NfcAdapter
import android.nfc.NdefMessage
import android.nfc.tech.NfcF
import java.util.Locale

object RFCommHelperService {
  @scala.reflect.BeanProperty val STATE_NONE = 0        // doing nothing
  @scala.reflect.BeanProperty val STATE_LISTEN = 1      // not yet connected but listening for incoming connections
  @scala.reflect.BeanProperty val STATE_CONNECTING = 2  // connected to at least one remote device
  @scala.reflect.BeanProperty val STATE_CONNECTED = 3   // connected to at least one remote device

  // Message types sent from RFCommHelperService to the activity handler
  @scala.reflect.BeanProperty val MESSAGE_STATE_CHANGE = 1
  @scala.reflect.BeanProperty val MESSAGE_USERHINT1 = 2
  @scala.reflect.BeanProperty val MESSAGE_USERHINT2 = 3
  @scala.reflect.BeanProperty val MESSAGE_DEVICE_NAME = 4
  @scala.reflect.BeanProperty val DEVICE_DISCONNECT = 7
  @scala.reflect.BeanProperty val CONNECTION_FAILED = 8
  @scala.reflect.BeanProperty val CONNECTION_START = 9
  @scala.reflect.BeanProperty val MESSAGE_REDRAW_DEVICEVIEW = 10
  @scala.reflect.BeanProperty val MESSAGE_DELIVER_PROGRESS = 11
  @scala.reflect.BeanProperty val MESSAGE_YOURTURN = 12
  @scala.reflect.BeanProperty val MESSAGE_RECEIVED_FILE = 13
  @scala.reflect.BeanProperty val UI_UPDATE = 14
  @scala.reflect.BeanProperty val ALERT_MESSAGE = 15
  @scala.reflect.BeanProperty val CONNECTING = 16

  // Key names received from RFCommHelperService to the activity handler
  val DEVICE_NAME = "device_name"
  val DEVICE_ADDR = "device_addr"
  val SOCKET_TYPE = "socket_type"
  val DELIVER_ID = "deliver_id"
  val DELIVER_PROGRESS = "deliver_progress"
  val DELIVER_BYTES = "deliver_bytes"
  val DELIVER_TYPE = "deliver_type"
  val DELIVER_FILENAME = "deliver_filename"
  val DELIVER_URI = "deliver_uri"
} 

class RFCommHelperService extends android.app.Service {
  // public objects
  @volatile var activityResumed = false   // set by RFCommHelper
  @volatile var state = RFCommHelperService.STATE_NONE  // retrieved by activity
  @volatile var p2pWifiDiscoveredCallbackFkt:(WifiP2pDevice) => Unit = null
  var activity:Activity = null            // set by activity on new ServiceConnection()
  var activityMsgHandler:Handler = null   // set by activity on new ServiceConnection()
  var appService:RFServiceTrait = null
  var connectedRadio:Int = 0
  val wifiP2pDeviceArrayList = new ArrayList[WifiP2pDevice]()
  var discoveringPeersInProgress = false  // so we do not call discoverPeers() again while it is active still
  var isWifiP2pEnabled = false            // if false in onResume, we will offer ACTION_WIRELESS_SETTINGS 
  var p2pConnected = false                // set and cleared in WiFiDirectBroadcastReceiver
  var wifiP2pManager:WifiP2pManager = null
  var p2pChannel:Channel = null
  var localP2pWifiAddr:String = null      // set and used in WiFiDirectBroadcastReceiver
  var mNfcAdapter:NfcAdapter = null
  var nfcPendingIntent:PendingIntent = null
  var nfcFilters:Array[IntentFilter] = null
  var nfcTechLists:Array[Array[String]] = null
  var ipPort = 8954
  var appName:String = null
  var activityRuntimeClass:java.lang.Class[Activity] = null  // needed for nfcPendingIntent only
  var nfcForegroundPushMessage:NdefMessage = null
  var desiredBluetooth = false
  var pairedBtOnly = false // may only be false for sdk>=10 (2.3.3+)
  var desiredWifiDirect = false
  var desiredNfc = false
  var prefsSharedP2pBt:SharedPreferences = null
  var prefsSharedP2pBtEditor:SharedPreferences.Editor = null
  var prefsSharedP2pWifi:SharedPreferences = null
  var prefsSharedP2pWifiEditor:SharedPreferences.Editor = null
  var acceptThreadSecureName:String = null
  var acceptThreadSecureUuid:String = null
  var mSecureAcceptThread:AcceptThread = null
  var acceptThreadInsecureName:String = null
  var acceptThreadInsecureUuid:String = null
  var mInsecureAcceptThread:AcceptThread = null

  // private objects
  private val TAG = "RFCommHelperService"
  private val D = true
  @volatile private var mConnectThread:ConnectThreadBt = null
  private val mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter
  private var myBtName = if(mBluetoothAdapter!=null) mBluetoothAdapter.getName else null
  private var myBtAddr = if(mBluetoothAdapter!=null) mBluetoothAdapter.getAddress else null  

  if(D) Log.i(TAG, "constructor myBtName="+myBtName+" myBtAddr="+myBtAddr+" mBluetoothAdapter="+mBluetoothAdapter)
  private var blobTaskId = 0

  class LocalBinder extends android.os.Binder {
    def getService = RFCommHelperService.this
  }
  private val localBinder = new LocalBinder
  override def onBind(intent:Intent) :IBinder = localBinder 

  override def onCreate() {
    //if(D) Log.i(TAG, "onCreate")
    // note: our service is started via bindService() from RFCommHelper constructor (from activity onCreate())
    //       but it is not yet clear wether "bt new AcceptThread" is needed
    //       when it is clear this is wanted, activity will call start() from onResume()
  }

  // called by Activity onResume() 
  // but only while state == STATE_NONE
  // this is why we quickly switch state to STATE_LISTEN
  def start() = synchronized {
    if(D) Log.i(TAG, "start! android.os.Build.VERSION.SDK_INT="+android.os.Build.VERSION.SDK_INT+" pairedBtOnly="+pairedBtOnly+" activityResumed="+activityResumed)
    setState(RFCommHelperService.STATE_LISTEN)   // this will send MESSAGE_STATE_CHANGE

    // in case bt was turned on _after_ app start
    if(myBtAddr==null) {
      myBtAddr = mBluetoothAdapter.getAddress
      if(myBtAddr==null)
        myBtAddr = "unknown"  // tmtmtm
    }
    if(myBtName==null) {
      myBtName = mBluetoothAdapter.getName
      if(myBtName==null)
        myBtName = "unknown"  // tmtmtm
    }
    if(D) Log.i(TAG, "start myBtName="+myBtName+" myBtAddr="+myBtAddr+" mBluetoothAdapter="+mBluetoothAdapter+" pairedBtOnly="+pairedBtOnly)

    // start thread to listen on BluetoothServerSocket
    if(mSecureAcceptThread == null) {
      if(D) Log.i(TAG, "start new AcceptThread for secure")
      mSecureAcceptThread = new AcceptThread(true)
      if(mSecureAcceptThread != null) 
        mSecureAcceptThread.start
    }

    if(android.os.Build.VERSION.SDK_INT>=10 && !pairedBtOnly) {
      if(mInsecureAcceptThread == null) {
        if(D) Log.i(TAG, "start new AcceptThread for insecure (running on 2.3.3+)")
        mInsecureAcceptThread = new AcceptThread(false)
        if(mInsecureAcceptThread != null)
          mInsecureAcceptThread.start
      }
    }

    if(D) Log.i(TAG, "start: done")
  }

  // called by onDestroy() + by activity (on MESSAGE_YOURTURN)
  def stopActiveConnection() = synchronized {
    if(D) Log.i(TAG, "stopActiveConnection mConnectThread="+mConnectThread)
    if(mConnectThread != null) {
      // disconnect in case we were the connect initiator
      mConnectThread.cancel
      mConnectThread = null
    }
    if(appService!=null && appService.connectedThread!=null) {
      // disconnect in case we were the connect responder
      if(D) Log.i(TAG, "stopActiveConnection connectedThread="+appService.connectedThread)
      appService.connectedThread.cancel
      appService.connectedThread = null
    }
    setState(RFCommHelperService.STATE_LISTEN)   // will send MESSAGE_STATE_CHANGE to activity
    if(D) Log.i(TAG, "stopActiveConnection done")
  }

  def stopAcceptThreads() = synchronized {
    if(mSecureAcceptThread != null) {
      mSecureAcceptThread.cancel
      mSecureAcceptThread = null
    }
    if(!pairedBtOnly && mInsecureAcceptThread!=null) {
      mInsecureAcceptThread.cancel
      mInsecureAcceptThread = null
    }
  }

  // called by the activity: options menu "connect" -> onActivityResult() -> connectDevice()
  // called by the activity: as a result of NfcAdapter.ACTION_NDEF_DISCOVERED
  def connectBt(newRemoteDevice:BluetoothDevice, reportConnectState:Boolean=true, onstartEnableBackupConnection:Boolean=false) :Unit = synchronized {
    if(newRemoteDevice==null) {
      Log.e(TAG, "connect() newRemoteDevice==null, give up")
      return
    }

    connectedRadio = 1 // bt
    state = RFCommHelperService.STATE_CONNECTING
    if(D) Log.i(TAG, "connectBt remoteAddr="+newRemoteDevice.getAddress+" name=["+newRemoteDevice.getName+"] pairedBtOnly="+pairedBtOnly)

    // store target deviceAddr and deviceName to "org.timur.p2pDevices" preferences
    if(newRemoteDevice.getName!=null && newRemoteDevice.getName.length>0) {
      if(prefsSharedP2pBtEditor!=null) {
        // todo tmtmtm: ONLY if newRemoteDevice.getName in preferences is empty
        prefsSharedP2pBtEditor.putString(newRemoteDevice.getAddress,newRemoteDevice.getName)
        prefsSharedP2pBtEditor.commit
      }
    }

    if(reportConnectState && activityMsgHandler!=null) {
      val msg = activityMsgHandler.obtainMessage(RFCommHelperService.CONNECTION_START)
      val bundle = new Bundle
      bundle.putString(RFCommHelperService.DEVICE_ADDR, newRemoteDevice.getAddress)
      bundle.putString(RFCommHelperService.DEVICE_NAME, newRemoteDevice.getName)
      msg.setData(bundle)
      activityMsgHandler.sendMessage(msg)
    }
    
    // Start the thread to connect with the given device
    mConnectThread = new ConnectThreadBt(newRemoteDevice, reportConnectState, onstartEnableBackupConnection)
    mConnectThread.start
  }

  def connectWifi(p2pWifiAddr:String, p2pWifiName:String, 
                  onlyIfLocalAddrBiggerThatRemote:Boolean, reportConnectState:Boolean=true,
                  onstartEnableBackupConnection:Boolean=false) :Unit = synchronized {
    // todo: onstartEnableBackupConnection not yet being evaluated
    
    if(!RFCommHelper.WIFI_DIRECT_SUPPORTED)
      return

    //p2pChannel = wifiP2pManager.initialize(activity, activity.getMainLooper, null)

    connectedRadio = 2 // wifi
    state = RFCommHelperService.STATE_CONNECTING

    // store target deviceAddr and deviceName to "org.timur.p2pDevices" preferences
    if(p2pWifiAddr!=null && p2pWifiName!=null && p2pWifiName.length>0) {
      if(prefsSharedP2pWifiEditor!=null) {
        // but don't overwrite if one entry with p2pWifiAddr (and a name?) exists     
        val existingName = prefsSharedP2pWifi.getString(p2pWifiAddr,null)
        if(existingName==null || existingName.length==0 || existingName=="nfc-target") {
          prefsSharedP2pWifiEditor.putString(p2pWifiAddr,p2pWifiName)
          prefsSharedP2pWifiEditor.commit
        }
      }
    }

    if(onlyIfLocalAddrBiggerThatRemote && localP2pWifiAddr<p2pWifiAddr) {
      // this is to prevent nfc-initiated concurrent connect requests 
      if(D) Log.i(TAG, "connectWifi local="+localP2pWifiAddr+" < remote="+p2pWifiAddr+" - stay passive - let other device connect() ############")

    } else {
      if(D) Log.i(TAG, "connectWifi active connect() local="+localP2pWifiAddr+" > remote="+p2pWifiAddr+" ############")
      val wifiP2pConfig = new WifiP2pConfig()
      wifiP2pConfig.groupOwnerIntent = -1
      wifiP2pConfig.wps.setup = WpsInfo.PBC
      wifiP2pConfig.deviceAddress = p2pWifiAddr
      wifiP2pManager.connect(p2pChannel, wifiP2pConfig, new ActionListener() {
        // note: may result in "E/wpa_supplicant(): Failed to create interface p2p-wlan0-5: -12 (Out of memory)"
        //       in which case onSuccess() is often still be called     
        override def onSuccess() {
          if(D) Log.i(TAG, "connectWifi wifiP2pManager.connect() success ####")
          // we expect WIFI_P2P_CONNECTION_CHANGED_ACTION in WiFiDirectBroadcastReceiver to notify us
          // todo: however sometimes this does NOT happen
          
          // let's render the connect-progress animation (like we do in connectBt)
          connectedRadio = 2 // wifi    // todo: duplicate - likely not necessary
          if(activityMsgHandler!=null) {
            val msg = activityMsgHandler.obtainMessage(RFCommHelperService.CONNECTION_START)
            val bundle = new Bundle
            bundle.putString(RFCommHelperService.DEVICE_ADDR, wifiP2pConfig.deviceAddress)
            bundle.putString(RFCommHelperService.DEVICE_NAME, p2pWifiName)
            msg.setData(bundle)
            activityMsgHandler.sendMessage(msg)
          }
        }

        override def onFailure(reason:Int) {
          val errMsg = "wifiP2pManager.connect() failed reason="+reason
          Log.e(TAG, "connectWifi fail "+errMsg+" ##################")
          // reason ERROR=0, P2P_UNSUPPORTED=1, BUSY=2
          if(activityMsgHandler!=null)
            activityMsgHandler.obtainMessage(RFCommHelperService.ALERT_MESSAGE, -1, -1, errMsg).sendToTarget
        }
      })
      if(D) Log.i(TAG, "connectWifi wifiP2pManager.connect() done")
    }
  }

  def getWifiIpAddr() :String = {
    if(activity==null) {
      Log.e(TAG, "getWifiIpAddr() activity==null")
      return null
    }
    val wifiManager = activity.getSystemService(Context.WIFI_SERVICE).asInstanceOf[WifiManager]
    if(wifiManager==null) {
      Log.e(TAG, "getWifiIpAddr() wifiManager==null")
      return null
    }
    val wifiInfo = wifiManager.getConnectionInfo
    if(wifiInfo==null) {
      Log.e(TAG, "getWifiIpAddr() wifiInfo==null")
      return null
    }
    val ipAddrInt = wifiInfo.getIpAddress
    if(ipAddrInt==0) {
      if(D) Log.i(TAG, "getWifiIpAddr() ipAddrInt==0 - we got no std wifi addr")
      return null
    }
    val ipAddrString = "%d.%d.%d.%d".format((ipAddrInt & 0xff),
                                            (ipAddrInt >> 8 & 0xff),
                                            (ipAddrInt >> 16 & 0xff),
                                            (ipAddrInt >> 24 & 0xff))
    if(D) Log.i(TAG, "getWifiIpAddr() return ipAddrString="+ipAddrString)
    return ipAddrString
  }

  def connectIp(targetIpAddr:String, ipName:String, reportConnectState:Boolean=true) :Unit = synchronized {
    connectedRadio = 3 // access-point ip
    state = RFCommHelperService.STATE_CONNECTING

    val localAddr = getWifiIpAddr
    if(localAddr==null) {
      Log.e(TAG, "connectIp() localAddr==null abort")
      return
    }
    
    if(D) Log.i(TAG, "connectIp() localAddr="+localAddr+" to targetIpAddr="+targetIpAddr)
    if(localAddr < targetIpAddr)
      ipClientConnectorThread(true, null, null)  // socketserver
    else
      ipClientConnectorThread(false, java.net.InetAddress.getByName(targetIpAddr), null)  // client socket
  }

  class AcceptThread(pairedBt:Boolean=true) extends Thread {
    private var mmServerSocket:BluetoothServerSocket = null
    mmServerSocket = null
    try {
      if(pairedBt) {
        mmServerSocket = mBluetoothAdapter.listenUsingRfcommWithServiceRecord(acceptThreadSecureName, UUID.fromString(acceptThreadSecureUuid))
      } else {
        try {
          mmServerSocket = mBluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(acceptThreadInsecureName, UUID.fromString(acceptThreadInsecureUuid))
        } catch {
          case nsmerr: java.lang.NoSuchMethodError =>
            // this should really not happen, because we run the insecure method only if os >= 2.3.3
            Log.e(TAG, "AcceptThread pairedBt="+pairedBt+" listenUsingInsecureRfcommWithServiceRecord failed", nsmerr)
        }
      }
    } catch {
      case e: IOException =>
        Log.e(TAG, "AcceptThread pairedBt="+pairedBt+" listen() failed", e)
    }

    override def run() {
      if(mmServerSocket==null) {
        Log.e(TAG, "AcceptThread pairedBt="+pairedBt+" run mmServerSocket==null")
        return
      }

      if(D) Log.i(TAG, "AcceptThread pairedBt="+pairedBt+" run mmServerSocket="+mmServerSocket)
      setName("AcceptThread"+pairedBtOnly)
      var btSocket:BluetoothSocket = null

      // Listen to the server socket if we're not connected
      while(mmServerSocket!=null) {
        if(D) Log.i(TAG, "AcceptThread pairedBt="+pairedBt+" run loop mmServerSocket="+mmServerSocket)
        try {
          synchronized {
            btSocket = null
            if(mmServerSocket!=null) {
              // This is a blocking call and will only return on a successful connection or an exception
              btSocket = mmServerSocket.accept
              if(D) Log.i(TAG, "AcceptThread pairedBt="+pairedBt+" run loop after accept, btSocket="+btSocket)
            }
          }
        } catch {
          case ioex: IOException =>
            // log exception only if not stopped
            if(state != RFCommHelperService.STATE_NONE)
              Log.e(TAG, "AcceptThread pairedBt="+pairedBt+" run state="+state+" ioex="+ioex)
        }

        if(D) Log.i(TAG, "AcceptThread pairedBt="+pairedBt+" btSocket="+btSocket+" activityResumed="+activityResumed)
        if(btSocket==null) {
          Log.e(TAG, "AcceptThread pairedBt="+pairedBt+" btSocket==null")
        } else {
          // store the deviceAddr and deviceName of the calling bt device
          if(prefsSharedP2pBtEditor!=null) {
            val btDevice = btSocket.getRemoteDevice
            if(btDevice.getName!=null && btDevice.getName.length>0) {
              prefsSharedP2pBtEditor.putString(btDevice.getAddress,btDevice.getName)
              prefsSharedP2pBtEditor.commit
            }
          }

          // a bt connection is now technically possible and can be accepted
          // note: this is where we can decide to activityResumed (or not)
          if(!activityResumed) {
            // our activity is currently paused
            if(D) Log.i(TAG, "AcceptThread pairedBt="+pairedBt+" Denying incoming connect request, activityResumed="+activityResumed+" activity="+activity+" ###########################")
            // hangup
            btSocket.close

            if(activity!=null) {
              AndrTools.runOnUiThread(activity) { () =>
                Toast.makeText(activity, "Run Anymime in foreground to accept BT connections.", Toast.LENGTH_LONG).show
              }
            }

          } else {
            // activity is not paused
            if(D) Log.i(TAG, "AcceptThread pairedBt="+pairedBt+" -> connectedBt()")
            RFCommHelperService.this synchronized {
              connectedBt(btSocket, btSocket.getRemoteDevice)
            }
          }
        }
        
        // prevent tight loop
        try { Thread.sleep(100); } catch { case ex:Exception => }
      }

      // mmServerSocket was set to null
      if(D) Log.i(TAG, "AcceptThread pairedBt="+pairedBt+" end pairedBtOnly="+pairedBtOnly)
    }

    def cancel() { 
      if(D) Log.i(TAG, "AcceptThread pairedBt="+pairedBt+" cancel() mmServerSocket="+mmServerSocket)
      if(mmServerSocket!=null) {
        try {
          setState(RFCommHelperService.STATE_NONE)   // so that run() will NOT log an error; will send MESSAGE_STATE_CHANGE
          mmServerSocket.close
          mmServerSocket=null
        } catch {
          case ex: IOException =>
            Log.e(TAG, "AcceptThread pairedBt="+pairedBt+" cancel() mmServerSocket="+mmServerSocket+" ex=",ex)
        }
      }
    }
  }

  private def setState(setState:Int) = synchronized {
    if(setState != state) {
      if(D) Log.i(TAG, "setState() "+state+" -> "+setState)
      state = setState
      // send modified state to the activity Handler
      if(activityMsgHandler!=null) {
        activityMsgHandler.obtainMessage(RFCommHelperService.MESSAGE_STATE_CHANGE, state, -1).sendToTarget
      } else {
        Log.e(TAG, "setState() failed to set "+setState+" because activityMsgHandler not set")
      }
    }
  }

  private class ConnectThreadBt(remoteDevice:BluetoothDevice, reportConnectState:Boolean=true, onstartEnableBackupConnection:Boolean=false) extends Thread {
    private var mmSocket:BluetoothSocket = null

    if(desiredBluetooth) {
      // Get a BluetoothSocket for a connection with the given BluetoothDevice
      try {
        if(pairedBtOnly)
          mmSocket = remoteDevice.createRfcommSocketToServiceRecord(UUID.fromString(acceptThreadSecureUuid))   // requires pairing
        else
          mmSocket = remoteDevice.createInsecureRfcommSocketToServiceRecord(UUID.fromString(acceptThreadInsecureUuid))   // does not require pairing
      } catch {
        case e:IOException =>
          Log.e(TAG, "ConnectThreadBt Socket pairedBtOnly="+pairedBtOnly+" create() failed", e)
      }
    }

    override def run() {
      if(D) Log.i(TAG, "ConnectThreadBt run desiredBluetooth="+desiredBluetooth+" pairedBtOnly="+pairedBtOnly+" mmSocket="+mmSocket)
      setName("ConnectThreadBt"+pairedBtOnly)

      if(desiredBluetooth && mmSocket!=null) {
        // Always cancel discovery because it will slow down a connection
        mBluetoothAdapter.cancelDiscovery

        try {
          // This is a blocking call and will only return on a successful connection or an exception
          if(D) Log.i(TAG, "ConnectThreadBt run mmSocket.connect()")
          mmSocket.connect
          // todo: "java.io.IOException: Service discovery failed" on bt connect!!!
          //       solution: unpair opposit device
        } catch {
          case ex:IOException =>
            if(!pairedBtOnly) {
              if(D) Log.i(TAG, "ConnectThreadBt run ignore failed insecure connect ...")
              // ignore exception, try again to connect, but this time secure/paired
              try {
                mmSocket.close
              } catch {
                case ex:Exception =>
                  // ignore
              }

              try {
                mmSocket = remoteDevice.createRfcommSocketToServiceRecord(UUID.fromString(acceptThreadSecureUuid))   // requires pairing
                if(D) Log.i(TAG, "ConnectThreadBt run 2nd attempt secure connect ...")
                mmSocket.connect
              } catch {
                case e: IOException =>
                  mmSocket = null
                  Log.e(TAG, "ConnectThreadBt run unable to connect() 2nd attempt pairedBtOnly="+pairedBtOnly,ex)
                  if(!onstartEnableBackupConnection) {
                    if(reportConnectState) {
                      val msg = activityMsgHandler.obtainMessage(RFCommHelperService.CONNECTION_FAILED)
                      val bundle = new Bundle
                      bundle.putString(RFCommHelperService.DEVICE_ADDR, remoteDevice.getAddress)
                      bundle.putString(RFCommHelperService.DEVICE_NAME, remoteDevice.getName)
                      msg.setData(bundle)
                      activityMsgHandler.sendMessage(msg)
                    }
                    cancel
                    return
                  }
              }
            } else {
              Log.e(TAG, "ConnectThreadBt run unable to connect() pairedBtOnly="+pairedBtOnly+" IOException",ex)
              mmSocket = null
              if(!onstartEnableBackupConnection) {
                if(reportConnectState) {
                  val msg = activityMsgHandler.obtainMessage(RFCommHelperService.CONNECTION_FAILED)
                  val bundle = new Bundle
                  bundle.putString(RFCommHelperService.DEVICE_ADDR, remoteDevice.getAddress)
                  bundle.putString(RFCommHelperService.DEVICE_NAME, remoteDevice.getName)
                  msg.setData(bundle)
                  activityMsgHandler.sendMessage(msg)
                }
                cancel
                return
              }
            }
        }
      }

      // Start the connected thread
      if(D) Log.i(TAG, "ConnectThreadBt -> connectedBt(mmSocket="+mmSocket+")")
      connectedBt(mmSocket, remoteDevice)
    }

    def cancel() {  // called by stopActiveConnection()
      if(D) Log.i(TAG, "ConnectThreadBt cancel() pairedBtOnly="+pairedBtOnly+" mmSocket="+mmSocket)
      if(mmSocket!=null) {
        try {
          mmSocket.close
        } catch {
          case e: IOException =>
            Log.e(TAG, "ConnectThreadBt cancel() socket.close() failed for pairedBtOnly="+pairedBtOnly, e)
        }
      }
      setState(RFCommHelperService.STATE_LISTEN)   // will send MESSAGE_STATE_CHANGE to activity
    }
  }

  // called by: AcceptThread() -> socket = mmServerSocket.accept()
  // called by: ConnectThreadBt() / activity options menu (or NFC touch) -> connect() -> ConnectThreadBt()
  // called by: ConnectPopupActivity
  def connectedBt(socket:BluetoothSocket, remoteDevice:BluetoothDevice) :Unit = synchronized {
    // in case of nfc triggered connect: for the device with the bigger btAddr, this is the 1st indication of the connect
    if(D) Log.i(TAG, "connectedBt, socket="+socket+" remoteDevice="+remoteDevice+" pairedBtOnly="+pairedBtOnly)
    if(/*socket==null ||*/ remoteDevice==null) 
      return
      
    connectedRadio = 1 // bt
    state = RFCommHelperService.STATE_CONNECTING

    val remoteBtAddrString = remoteDevice.getAddress
    var remoteBtNameString = remoteDevice.getName
    // convert spaces to underlines in btNameString (some android activities, for instance the browser, dont like encoded spaces =%20 in file pathes)
    remoteBtNameString = remoteBtNameString.replaceAll(" ","_")

    //if(D) Log.i(TAG, "connectedBt, Start ConnectedThread to manage the connection")
    try {
      // Get the BluetoothSocket input and output streams
      val mmInStream = if(socket!=null) socket.getInputStream else null
      val mmOutStream = if(socket!=null) socket.getOutputStream else null

/*
      if(appService.connectedThread!=null && appService.connectedThread.isRunning) {
        // re-connect a bt connection after it was disconnected
        // todo: but only if a BackupConnection is in use     
        appService.connectedThread.updateStreams(mmInStream, mmOutStream)
        if(D) Log.i(TAG, "connectedBt -> disconnectBackupConnection")
        appService.connectedThread.disconnectBackupConnection

      } else {
*/
        // start the thread to handle the streams
        appService.createConnectedThread
        appService.connectedThread.init(mmInStream, mmOutStream, myBtAddr, myBtName, remoteBtAddrString, remoteBtNameString, () => { 
          if(D) Log.i(TAG, "connectedBt disconnecting from "+remoteBtNameString+" "+remoteBtAddrString+" ...")

          // disconnect the bt-socket
          if(socket!=null) {
            socket.close
            //socket=null
          }

          // tell the activity that the connection was lost
          val msg = activityMsgHandler.obtainMessage(RFCommHelperService.DEVICE_DISCONNECT)
          val bundle = new Bundle
          bundle.putString(RFCommHelperService.DEVICE_ADDR, remoteBtAddrString)
          bundle.putString(RFCommHelperService.DEVICE_NAME, remoteBtNameString)
          msg.setData(bundle)
          activityMsgHandler.sendMessage(msg)

          setState(RFCommHelperService.STATE_LISTEN)   // will send MESSAGE_STATE_CHANGE to activity
          // todo: why? DEVICE_DISCONNECT + MESSAGE_STATE_CHANGE
          
          if(D) Log.i(TAG, "connectedBt post-ConnectedThread processing done")
        })

        if(D) Log.i(TAG, "connectedBt -> start thread")
        setState(RFCommHelperService.STATE_CONNECTED)
        appService.connectedThread.start // -> run() will immediately connect to SocketProxy
        appService.connectedThread.doFirstActor

        // Send the name of the connected device back to the UI Activity
        // note: the main activity may not be active at this moment (but for instance the ConnectPopupActivity)
        if(activityMsgHandler!=null) {
          val msg = activityMsgHandler.obtainMessage(RFCommHelperService.MESSAGE_DEVICE_NAME)
          val bundle = new Bundle
          bundle.putString(RFCommHelperService.DEVICE_NAME, remoteBtNameString)
          bundle.putString(RFCommHelperService.DEVICE_ADDR, remoteBtAddrString)
          bundle.putBoolean(RFCommHelperService.SOCKET_TYPE, pairedBtOnly)
          msg.setData(bundle)
          activityMsgHandler.sendMessage(msg)
        }
/*
      }
*/
      //if(D) Log.i(TAG, "connectedBt done")

    } catch {
      case e: IOException =>
        Log.e(TAG, "connectedBt ConnectedThread start temp sockets not created", e)
    }
  }

  def connectedWifi(socket:java.net.Socket, actor:Boolean, p2pCloseFkt:() => Unit) :Unit = synchronized {
    if(D) Log.i(TAG, "connectedWifi actor="+actor)
    if(!RFCommHelper.WIFI_DIRECT_SUPPORTED)
      return

    if(socket!=null) {
      connectedRadio = 2 // wifi

      val remoteSocketAddr = socket.getRemoteSocketAddress.asInstanceOf[InetSocketAddress]
      val remoteWifiAddrString = remoteSocketAddr.getAddress.getHostAddress
      val remoteWifiNameString = remoteSocketAddr.getHostName   // todo: this is not a deviceName, but an ip4-addr
      if(D) Log.i(TAG, "connectedWifi remoteWifiAddrString="+remoteWifiAddrString+" remoteWifiNameString="+remoteWifiNameString)

      // convert spaces to underlines in device name (some android activities, for instance the browser, dont like encoded spaces =%20 in file pathes)
      val myRemoteWifiNameString = remoteWifiNameString.replaceAll(" ","_")
      val localSocketAddr = socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress]
      val localWifiAddrString = localSocketAddr.getAddress.getHostAddress
      val localWifiNameString = localSocketAddr.getHostName

      val mmInStream = socket.getInputStream
      if(mmInStream!=null) {
        val mmOutStream = socket.getOutputStream
        if(mmOutStream!=null) {
/*
          if(appService.connectedThread!=null && appService.connectedThread.isRunning) {
            // re-connect a bt connection after it was disconnected
            // todo: but only if a BackupConnection is in use     
            appService.connectedThread.updateStreams(mmInStream, mmOutStream)
            if(D) Log.i(TAG, "connectedWifi -> disconnectBackupConnection")
            appService.connectedThread.disconnectBackupConnection

          } else {
*/
            appService.createConnectedThread
            appService.connectedThread.init(mmInStream, mmOutStream, localWifiAddrString, localWifiNameString, remoteWifiAddrString, myRemoteWifiNameString, () => { 
              if(D) Log.i(TAG, "connectedWifi post-ConnectedThread processing remoteWifiAddrString="+remoteWifiAddrString+" myRemoteWifiNameString="+myRemoteWifiNameString)

              // tell the activity that the connection was lost
              val msg = activityMsgHandler.obtainMessage(RFCommHelperService.DEVICE_DISCONNECT)
              val bundle = new Bundle
              bundle.putString(RFCommHelperService.DEVICE_ADDR, remoteWifiAddrString)
              bundle.putString(RFCommHelperService.DEVICE_NAME, myRemoteWifiNameString)
              msg.setData(bundle)
              activityMsgHandler.sendMessage(msg)

              System.gc    
              p2pCloseFkt() // will close the socket
              setState(RFCommHelperService.STATE_LISTEN)   // will send MESSAGE_STATE_CHANGE to activity
              if(D) Log.i(TAG, "connectedWifi post-ConnectedThread processing done")
            })

            if(D) Log.i(TAG, "connectedWifi -> start thread")
            setState(RFCommHelperService.STATE_CONNECTED)
            appService.connectedThread.start // run() will immediately connect to SocketProxy
            appService.connectedThread.doFirstActor

            // Send the name of the connected device back to the UI Activity
            // note: the main activity may not be active at this moment (but for instance the ConnectPopupActivity)
            if(activityMsgHandler!=null) {
              val msg = activityMsgHandler.obtainMessage(RFCommHelperService.MESSAGE_DEVICE_NAME)
              val bundle = new Bundle
              bundle.putString(RFCommHelperService.DEVICE_NAME, myRemoteWifiNameString)
              bundle.putString(RFCommHelperService.DEVICE_ADDR, remoteWifiAddrString)
              bundle.putBoolean(RFCommHelperService.SOCKET_TYPE, false) // not supported for wifi
              msg.setData(bundle)
              activityMsgHandler.sendMessage(msg)
            }
/*
          }
*/
        }
      }
    }

    if(D) Log.i(TAG, "connectedWifi done")
  }

  def nfcServiceSetup() {
    // this is called by radioDialog/OK, by wifiDirectBroadcastReceiver:WIFI_P2P_THIS_DEVICE_CHANGED_ACTION and by onActivityResult:REQUEST_ENABLE_BT

    if(D) Log.i(TAG, "nfcServiceSetup mNfcAdapter="+mNfcAdapter+" activityResumed="+activityResumed)

    // setup NFC (only for Android 2.3.3+ and only if NFC hardware is available)
    if(android.os.Build.VERSION.SDK_INT>=10 && mNfcAdapter!=null && mNfcAdapter.isEnabled) {
      if(nfcPendingIntent!=null) {
        if(D) Log.i(TAG, "nfcServiceSetup nfcPendingIntent was already set: no enableForegroundDispatch")
      } else {
        if(activity==null || activityRuntimeClass==null) {
          Log.e(TAG, "nfcServiceSetup cannot create nfcPendingIntent activity="+activity+" activityRuntimeClass="+activityRuntimeClass)

        } else if(!activityResumed) {
          Log.e(TAG, "nfcServiceSetup cannot call enableForegroundDispatch while activity is not resumed ############")

        } else {
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

          if(D) Log.i(TAG, "nfcServiceSetup enable nfc dispatch mNfcAdapter="+mNfcAdapter+" activity="+activity+" nfcPendingIntent="+nfcPendingIntent+" nfcFilters="+nfcFilters+" nfcTechLists="+nfcTechLists+" ...")

          // This method must be called from the main thread, and only when the activity is in the foreground (resumed). 
          // Also, activities must call disableForegroundDispatch(Activity) before the completion of their onPause() callback 

          AndrTools.runOnUiThread(activity) { () =>
            mNfcAdapter.enableForegroundDispatch(activity, nfcPendingIntent, nfcFilters, nfcTechLists)
            if(D) Log.i(TAG, "nfcServiceSetup enableForegroundDispatch done")
          }
        }
      }

      // embed our btAddress + localP2pWifiAddr in a new NdefMessage to be used via enableForegroundNdefPush
      var nfcString = "app="+appName
      val btAddress = mBluetoothAdapter.getAddress
      if(desiredBluetooth && btAddress!=null) {
        if(nfcString.length>0)
          nfcString += "|"
        nfcString += "bt="+btAddress
      }
      if(RFCommHelper.WIFI_DIRECT_SUPPORTED && desiredWifiDirect && localP2pWifiAddr!=null) {
        if(nfcString.length>0)
          nfcString += "|"
        nfcString += "p2pWifi="+localP2pWifiAddr
      }
      // adding "ip=xx.xx.xx.xx" in case device is connected to wifi-ap (usually this is not the case, so myWifiIpAddr==null is OK)
      val myWifiIpAddr = getWifiIpAddr
      if(myWifiIpAddr!=null) {
        if(nfcString.length>0)
          nfcString += "|"
        nfcString += "ip="+myWifiIpAddr
      }

      if(nfcString.length==0) {
        // this should never happen, right?
        if(D) Log.i(TAG, "nfcServiceSetup nfcString empty")
        nfcForegroundPushMessage=null
        if(activityResumed)
          mNfcAdapter.setNdefPushMessage(null, activity)

      } else {        
        nfcForegroundPushMessage = new NdefMessage(Array(NfcHelper.newTextRecord(nfcString, Locale.ENGLISH, true)))
        if(nfcForegroundPushMessage!=null) {
          if(activityResumed) {
            mNfcAdapter.setNdefPushMessage(nfcForegroundPushMessage, activity)
            if(D) Log.i(TAG, "setNdefPushMessage enable nfc ForegroundNdefPush nfcString=["+nfcString+"] done")

          } else {
            if(D) Log.i(TAG, "nfcServiceSetup enable nfc ForegroundNdefPush nfcString=["+nfcString+"] - DELAYED UNTIL activity is resumed ##########")
          }
        }
      }

    } else {
      Log.e(TAG, "nfcServiceSetup NFC NOT set up mNfcAdapter="+mNfcAdapter)
    }
  }

  // wifiP2pInfo.isGroupOwner, wifiP2pInfo.groupOwnerAddress
  def ipClientConnectorThread(isHost:Boolean, inetAddressTarget:java.net.InetAddress, closeDownP2p:() => Unit) = {
    if(D) Log.i(TAG, "ipClientConnectorThread isHost="+isHost+" inetAddressTarget="+inetAddressTarget)

    // start socket communication
    new Thread() {
      override def run() {
        var serverSocket:ServerSocket = null
        var socket:Socket = null

        def closeDownSocket() {
          // this will be called (by both sides) when the thread is finished
          if(D) Log.d(TAG, "ipClientConnectorThread closeDownSocket p2pConnected="+p2pConnected+" p2pChannel="+p2pChannel)

          // todo: why sleep (so long)?
          try { Thread.sleep(1200) } catch { case ex:Exception => }

          if(socket!=null) {
            socket.close
            socket=null
          }
          if(serverSocket!=null) {
            serverSocket.close
            serverSocket=null
          }
          if(p2pConnected) {
            if(D) Log.d(TAG, "ipClientConnectorThread closeDownSocket -> closeDownP2p="+closeDownP2p)
            if(closeDownP2p!=null)
              closeDownP2p
            p2pConnected = false  // probably not required, because WIFI_P2P_CONNECTION_CHANGED_ACTION will be called again with networkInfo.isConnected=false
          }
        }

        val port = ipPort //8954  // our personal rfcomm ip-port
        if(isHost) {
          // which device becomes the isGroupOwner is random, but it will be the device we run our serversocket on...
          // by convention, we make the GroupOwner (using the serverSocket) also the filetransfer-non-actor
          // start server socket
          if(D) Log.d(TAG, "ipClientConnectorThread server: new ServerSocket("+port+")")
          try {
            serverSocket = new ServerSocket(port)
            if(D) Log.d(TAG, "ipClientConnectorThread serverSocket opened")
            socket = serverSocket.accept
            if(socket!=null) {
              connectedWifi(socket, actor=false, closeDownSocket)
            }
          } catch {
            case ioException:IOException =>
              Log.e(TAG, "ipClientConnectorThread serverSocket failed to connect ex="+ioException.getMessage)
              closeDownSocket
          }

        } else {
          // which device becomes the Group client is random, but this is the device we run our client socket on...
          // by convention, we make the Group client (using the client socket) also the filetransfer-actor (will start the delivery)
          // because we are NOT the groupOwner, the groupOwnerAddress is the address of the OTHER device

          val SOCKET_TIMEOUT = 5000
          // we wait up to 5000 ms for the connection...
          val host = inetAddressTarget.getHostAddress
          if(D) Log.d(TAG, "ipClientConnectorThread client: connect to host="+host+" port="+port)
          socket = new Socket()
          try {
            socket.bind(null)
            socket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT)
            // if we don't get connected, an ioexception is thrown, otherwise we continue here by connecting to the other peer
            connectedWifi(socket, actor=true, closeDownSocket)
          } catch {
            case ioException:IOException =>
              Log.e(TAG, "ipClientConnectorThread client socket failed to connect ex="+ioException.getMessage+" ########")
              closeDownSocket
          }
        }
      }
    }.start
  }


  def newWiFiDirectBroadcastReceiver() :WiFiDirectBroadcastReceiver = {
    if(!RFCommHelper.WIFI_DIRECT_SUPPORTED)
      return null
    return new WiFiDirectBroadcastReceiver()
  }

  class WiFiDirectBroadcastReceiver() extends BroadcastReceiver {
    private val TAG = "RFCommHelperService WiFiDirectBroadcastReceiver"
    private val D = true

    override def onReceive(activity:Context, intent:Intent) {
      val action = intent.getAction

      if(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION == action) {                              // p2pWifi has been enabled (or disabled)

        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
        if(D) Log.i(TAG, "WIFI_P2P_STATE_CHANGED_ACTION state="+state+" ####")
        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
          // Wifi Direct mode is enabled
          //if(D) Log.i(TAG, "WifiP2pEnabled true ####")
          isWifiP2pEnabled=true

        } else {
          //if(D) Log.i(TAG, "WifiP2pEnabled false ####")
          p2pConnected = false
          isWifiP2pEnabled=false
        }

      } else if(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION == action) {                 // our device now has a p2pWifi mac-addr (or has lost it)
        val wifiP2pDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE).asInstanceOf[WifiP2pDevice]
        if(localP2pWifiAddr==null || localP2pWifiAddr!=wifiP2pDevice.deviceAddress) {
          // we now know our p2p mac address, we now can do nfcServiceSetup
          if(D) Log.i(TAG, "THIS_DEVICE_CHANGED_ACTION OUR deviceName="+wifiP2pDevice.deviceName+" deviceAddress="+wifiP2pDevice.deviceAddress) //+" info="+wifiP2pDevice.toString)
          localP2pWifiAddr = wifiP2pDevice.deviceAddress
          if(D) Log.i(TAG, "WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> nfcServiceSetup")
          nfcServiceSetup
        }

      } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION == action) {                      // there is an update for the discovered p2pWifi devices
        if(D) Log.i(TAG, "PEERS_CHANGED_ACTION number of p2p peers changed ####")
        discoveringPeersInProgress = false

        if(wifiP2pManager==null) {
          if(D) Log.i(TAG, "PEERS_CHANGED_ACTION wifiP2pManager==null ####")

        } else {
          //if(D) Log.i(TAG, "PEERS_CHANGED_ACTION requestPeers() ####")
          wifiP2pManager.requestPeers(p2pChannel, new PeerListListener() {
            override def onPeersAvailable(wifiP2pDeviceList:WifiP2pDeviceList) {
              // wifiP2pDeviceList.getDeviceList() is a list of WifiP2pDevice objects, each containg deviceAddress, deviceName, primaryDeviceType, etc.
              //if(D) Log.i(TAG, "onPeersAvailable wifiP2pDeviceList="+wifiP2pDeviceList)
              if(wifiP2pDeviceList!=null) {
                if(D) Log.i(TAG, "onPeersAvailable wifiP2pDeviceList.getDeviceList.size="+wifiP2pDeviceList.getDeviceList.size)
              }
              wifiP2pDeviceArrayList.clear
              wifiP2pDeviceArrayList.addAll(wifiP2pDeviceList.getDeviceList.asInstanceOf[java.util.Collection[WifiP2pDevice]])
              val wifiP2pDeviceListCount = wifiP2pDeviceArrayList.size
              if(D) Log.i(TAG, "onPeersAvailable wifiP2pDeviceListCount="+wifiP2pDeviceListCount)

              if(wifiP2pDeviceListCount>0) {
                // list all peers
                for(i <- 0 until wifiP2pDeviceListCount) {
                  val wifiP2pDevice = wifiP2pDeviceArrayList.get(i)
                  if(wifiP2pDevice != null) {
                    val statusString = if(wifiP2pDevice.status==0) "connected" 
                                  else if(wifiP2pDevice.status==1) "invited" 
                                  else if(wifiP2pDevice.status==2) "failed" 
                                  else if(wifiP2pDevice.status==3) "available" 
                                  else "unknown="+wifiP2pDevice.status
                    if(D) Log.i(TAG, "device "+i+" deviceName="+wifiP2pDevice.deviceName+" deviceAddress="+wifiP2pDevice.deviceAddress+" status="+statusString+" ####")
                    // status: connected=0, invited=1, failed=2, available=3
                   
                    if(p2pWifiDiscoveredCallbackFkt!=null)
                      p2pWifiDiscoveredCallbackFkt(wifiP2pDevice)
                  }
                }
              }
            }
          })
        }

      } else if(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION==action) {                        // we got p2pWifi client/client connected or disconnected
        // as a result of us (or some other device) calling wifiP2pManager.connect() 

        val networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO).asInstanceOf[NetworkInfo]
        if(D) Log.i(TAG, "CONNECTION_CHANGED_ACTION new p2p-connect-state="+networkInfo.isConnected+" getSubtypeName="+networkInfo.getSubtypeName)

        if(wifiP2pManager==null) {
          if(D) Log.i(TAG, "CONNECTION_CHANGED_ACTION wifiP2pManager==null ###########")
          // need wifiP2pManager to call requestConnectionInfo() to get groupOwnerAddress and isGroupOwner
          return
        }
        
        if(networkInfo.isConnected && p2pConnected) {
          if(D) Log.i(TAG, "CONNECTION_CHANGED_ACTION we are already connected - strange... ignore ###########")
          // todo: new p2p-connect, but we were connected already (probably this is how we set up a group of 3 or more clients?)
          return
        }

        if(!networkInfo.isConnected) {
          if(!p2pConnected) {
            if(D) Log.i(TAG, "CONNECTION_CHANGED_ACTION we are now disconnected, we were disconnect already")
            return
          }
          // we thought we are connected, but now we have been disconnected
          if(D) Log.i(TAG, "CONNECTION_CHANGED_ACTION we are now disconnected, set p2pConnected=false")
          p2pConnected = false
          stopActiveConnection
          return

        } else {
          // we got connected with another device, request connection info to get 
          // the groupOwnerAddress and find out if our device isGroupOwner (-> ServerSocket.accept) or not (-> socket.connect)
          p2pConnected = true
          if(D) Log.i(TAG, "CONNECTION_CHANGED_ACTION we are now p2pWifi connected with the other device")
          wifiP2pManager.requestConnectionInfo(p2pChannel, new WifiP2pManager.ConnectionInfoListener() {
            override def onConnectionInfoAvailable(wifiP2pInfo:WifiP2pInfo) {
              if(D) Log.i(TAG, "CONNECTION_CHANGED_ACTION onConnectionInfoAvailable groupOwnerAddress="+wifiP2pInfo.groupOwnerAddress+" isGroupOwner="+wifiP2pInfo.isGroupOwner+" ###############")

              def closeDownP2p() {
                if(D) Log.d(TAG, "closeDownP2p p2pConnected="+p2pConnected)
                if(p2pConnected) {
                  if(D) Log.d(TAG, "closeDownP2p wifiP2pManager.removeGroup() (this is how we disconnect from p2pWifi) SKIP ##################")
/*
                  wifiP2pManager.removeGroup(p2pChannel, new ActionListener() {
                    override def onSuccess() {
                      if(D) Log.i(TAG, "closeDownP2p wifiP2pManager.removeGroup() success")
                      // wifiDirectBroadcastReceiver will notify us
                    }

                    override def onFailure(reason:Int) {
                      if(D) Log.i(TAG, "closeDownP2p wifiP2pManager.removeGroup() failed reason="+reason+" ############")
                      // reason ERROR=0, P2P_UNSUPPORTED=1, BUSY=2
                      // note: it seems to be 'normal' for one of the two devices to receive reason=2 on disconenct
                    }
                  })
*/
                  p2pConnected = false  // probably not required, because WIFI_P2P_CONNECTION_CHANGED_ACTION will be called again with networkInfo.isConnected=false
                }
              }

              // now start a ServerSocket thread (if isGroupOwner) or a client socket thread (if not isGroupOwner)
              // then call connectedWifi(), which will open input- and output streams and start appService.connectedThread.start
              ipClientConnectorThread(wifiP2pInfo.isGroupOwner, wifiP2pInfo.groupOwnerAddress, closeDownP2p)
            }
          })
        }
      }
    }
  }
}


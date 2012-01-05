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
import android.net.NetworkInfo

import android.nfc.NfcAdapter
import android.nfc.NdefMessage
import android.nfc.tech.NfcF
import java.util.Locale

object RFCommHelperService {
  val STATE_NONE = 0        // doing nothing
  val STATE_LISTEN = 1      // not yet connected but listening for incoming connections
  val STATE_CONNECTING = 2  // connected to at least one remote device
  val STATE_CONNECTED = 3   // connected to at least one remote device

  // Message types sent from RFCommHelperService to the activity handler
  val MESSAGE_STATE_CHANGE = 1
  val MESSAGE_USERHINT1 = 2
  val MESSAGE_USERHINT2 = 3
  val MESSAGE_DEVICE_NAME = 4
  val DEVICE_DISCONNECT = 7
  val CONNECTION_FAILED = 8
  val CONNECTION_START = 9
  val MESSAGE_REDRAW_DEVICEVIEW = 10
  val MESSAGE_DELIVER_PROGRESS = 11
  val MESSAGE_YOURTURN = 12
  val MESSAGE_RECEIVED_FILE = 13
  val UI_UPDATE = 14
  val ALERT_MESSAGE = 15
  val CONNECTING = 16

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
  var activity:Activity = null            // set by activity on new ServiceConnection()
  var activityMsgHandler:Handler = null   // set by activity on new ServiceConnection()
  var appService:RFServiceTrait = null
  @volatile var acceptAndConnect = true   // set by activity on onPause/onResume: false = activity is sleeping, don't accept incoming connect requests
  @volatile var state = RFCommHelperService.STATE_NONE  // retrieved by activity
  @volatile var p2pWifiDiscoveredCallbackFkt:(WifiP2pDevice) => Unit = null
  var connectedRadio:Int = 0
  val wifiP2pDeviceArrayList = new ArrayList[WifiP2pDevice]()
  var discoveringPeersInProgress = false  // so we do not call discoverPeers() again while it is active still
  var isWifiP2pEnabled = false            // if false in onResume, we will offer ACTION_WIRELESS_SETTINGS 
  var p2pConnected = false                // set and cleared in WiFiDirectBroadcastReceiver
  var p2pChannel:Channel = null
  var localP2pWifiAddr:String = null      // set and used in WiFiDirectBroadcastReceiver
  var mNfcAdapter:NfcAdapter = null
  var nfcPendingIntent:PendingIntent = null
  var nfcFilters:Array[IntentFilter] = null
  var nfcTechLists:Array[Array[String]] = null
  var activityResumed = false
  var activityRuntimeClass:java.lang.Class[Activity] = null
  var nfcForegroundPushMessage:NdefMessage = null
  var desiredBluetooth = false
  var pairedBtOnly = false // may only be false for sdk>=10 (2.3.3+)
  var desiredWifiDirect = false
  var desiredNfc = false
  @volatile var mSecureAcceptThread:AcceptThread = null
  @volatile var mInsecureAcceptThread:AcceptThread = null
  var p2pRemoteAddressToConnect:String = null   // needed to carry the target ip-p2p-addr from ACTION_NDEF_DISCOVERED/discoverPeers() to WIFI_P2P_PEERS_CHANGED_ACTION/wifiP2pManager.connect()

  // private objects
  private val TAG = "RFCommHelperService"
  private val D = true
  private val NAME_SECURE = "AnyMime"
  private val MY_UUID_SECURE   = UUID.fromString("fa87c0d0-afac-11de-9991-0800200c9a66")
  private val NAME_INSECURE = "AnyMimeInsecure"
  private val MY_UUID_INSECURE = UUID.fromString("8ce255c0-200a-11e0-9992-0800200c9a66")
  private var p2pRemoteNameToConnect:String = null      // used for information purposes only
  private val mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter
  private var myBtName = if(mBluetoothAdapter!=null) mBluetoothAdapter.getName else null
  private var myBtAddr = if(mBluetoothAdapter!=null) mBluetoothAdapter.getAddress else null
  @volatile private var mConnectThread:ConnectThread = null

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
    if(D) Log.i(TAG, "start: android.os.Build.VERSION.SDK_INT="+android.os.Build.VERSION.SDK_INT+" pairedBtOnly="+pairedBtOnly)
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
    if(D) Log.i(TAG, "stopActiveConnection mConnectThread="+mConnectThread+" mSecureAcceptThread="+mSecureAcceptThread+" ##########")
    if(mConnectThread != null) {
      mConnectThread.cancel
      mConnectThread = null
    }
    System.gc
    setState(RFCommHelperService.STATE_LISTEN)   // will send MESSAGE_STATE_CHANGE to activity
  }

  def stopAcceptThread() = synchronized {
    if(D) Log.i(TAG, "stopAcceptThread mSecureAcceptThread="+mSecureAcceptThread)
    if(mSecureAcceptThread != null) {
      mSecureAcceptThread.cancel
      mSecureAcceptThread = null
    }
  }

  // called by the activity: options menu "connect" -> onActivityResult() -> connectDevice()
  // called by the activity: as a result of NfcAdapter.ACTION_NDEF_DISCOVERED
  def connectBt(newRemoteDevice:BluetoothDevice, reportConnectState:Boolean=true) :Unit = synchronized {
    if(newRemoteDevice==null) {
      Log.e(TAG, "connect() newRemoteDevice==null, give up")
      return
    }

    connectedRadio = 1 // bt
    state = RFCommHelperService.STATE_CONNECTING    // tmtmtm?
    if(D) Log.i(TAG, "connect() remoteAddr="+newRemoteDevice.getAddress()+" name="+newRemoteDevice.getName+" pairedBtOnly="+pairedBtOnly)

    if(reportConnectState && activityMsgHandler!=null) {
      val msg = activityMsgHandler.obtainMessage(RFCommHelperService.CONNECTION_START)
      val bundle = new Bundle
      bundle.putString(RFCommHelperService.DEVICE_ADDR, newRemoteDevice.getAddress)
      bundle.putString(RFCommHelperService.DEVICE_NAME, newRemoteDevice.getName)
      msg.setData(bundle)
      activityMsgHandler.sendMessage(msg)
    }
    
    // Start the thread to connect with the given device
    mConnectThread = new ConnectThread(newRemoteDevice, reportConnectState)
    mConnectThread.start
  }

  def connectWifi(wifiP2pManager:WifiP2pManager, p2pWifiAddr:String, p2pWifiName:String, reportConnectState:Boolean=true) :Unit = synchronized {
    p2pRemoteAddressToConnect = p2pWifiAddr
    p2pRemoteNameToConnect = p2pWifiName
    connectedRadio = 2 // wifi
    state = RFCommHelperService.STATE_CONNECTING    // tmtmtm?

    if(discoveringPeersInProgress) {
      if(D) Log.i(TAG, "connectWifi discoveringPeersInProgress: do not call discoverPeers() again")

    } else {
      if(D) Log.i(TAG, "connectWifi wifiP2pManager.discoverPeers()")
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
          if(D) Log.i(TAG, "connectWifi discoverPeers() onSuccess")
        }

        override def onFailure(reasonCode:Int) {
          // reasonCode: ERROR=0, P2P_UNSUPPORTED=1, BUSY=2
          if(D) Log.i(TAG, "connectWifi discoverPeers() onFailure reasonCode="+reasonCode)
          // note: sometimes we receive "onFailure reasonCode=0" and still see followup "WIFI_P2P_PEERS_CHANGED_ACTION number of p2p peers changed"
          if(reasonCode!=2)
            discoveringPeersInProgress = false
        }
      })
      if(D) Log.i(TAG, "connectWifi wifiP2pManager.discoverPeers() done")
    }
  }

  // called by: AcceptThread() -> socket = mmServerSocket.accept()
  // called by: ConnectThread() / activity options menu (or NFC touch) -> connect() -> ConnectThread()
  // called by: ConnectPopupActivity
  def connectedBt(socket:BluetoothSocket, remoteDevice:BluetoothDevice, pairedBtOnly:Boolean) :Unit = synchronized {
    // in case of nfc triggered connect: for the device with the bigger btAddr, this is the 1st indication of the connect
    if(D) Log.i(TAG, "connectedBt, socket="+socket+" remoteDevice="+remoteDevice+" pairedBtOnly="+pairedBtOnly)
    if(socket==null || remoteDevice==null) 
      return
      
    connectedRadio = 1 // bt
    state = RFCommHelperService.STATE_CONNECTING    // tmtmtm?

    val remoteBtAddrString = remoteDevice.getAddress
    var remoteBtNameString = remoteDevice.getName
    // convert spaces to underlines in btNameString (some android activities, for instance the browser, dont like encoded spaces =%20 in file pathes)
    remoteBtNameString = remoteBtNameString.replaceAll(" ","_")

    //if(D) Log.i(TAG, "connectedBt, Start ConnectedThread to manage the connection")
    try {
      // Get the BluetoothSocket input and output streams
      val mmInStream = socket.getInputStream
      val mmOutStream = socket.getOutputStream

      if(appService.connectedThread!=null && appService.connectedThread.isRunning) {
        // re-connect a bt connection after it was disconnected
        // todo: but only if a BackupConnection is in use     
        appService.connectedThread.updateStreams(mmInStream, mmOutStream)
        if(D) Log.i(TAG, "connectedBt -> disconnectBackupConnection")
        appService.connectedThread.disconnectBackupConnection

      } else {
        // start the thread to handle the streams
        appService.createConnectedThread
        appService.connectedThread.init(mmInStream, mmOutStream, pairedBtOnly, myBtAddr, myBtName, remoteBtAddrString, remoteBtNameString, () => { 
          if(D) Log.i(TAG, "connectedBt disconnecting from "+remoteBtAddrString+" ...")

          // tell the activity that the connection was lost
          val msg = activityMsgHandler.obtainMessage(RFCommHelperService.DEVICE_DISCONNECT)
          val bundle = new Bundle
          bundle.putString(RFCommHelperService.DEVICE_ADDR, remoteBtAddrString)
          bundle.putString(RFCommHelperService.DEVICE_NAME, remoteBtNameString)
          msg.setData(bundle)
          activityMsgHandler.sendMessage(msg)

          socket.close
          //socket=null
          if(D) Log.i(TAG, "connectedBt post-ConnectedThread processing done")
        })

        if(D) Log.i(TAG, "connectedBt -> start thread")
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
        setState(RFCommHelperService.STATE_CONNECTED)
      }

      //if(D) Log.i(TAG, "connectedBt done")

    } catch {
      case e: IOException =>
        Log.e(TAG, "connectedBt ConnectedThread start temp sockets not created", e)
    }
  }

  def connectedWifi(socket:java.net.Socket, actor:Boolean, p2pCloseFkt:() => Unit) :Unit = synchronized {
    if(D) Log.i(TAG, "connectedWifi() actor="+actor)
    if(socket!=null) {
      connectedRadio = 2 // wifi

      val remoteSocketAddr = socket.getRemoteSocketAddress.asInstanceOf[InetSocketAddress]
      val remoteWifiAddrString = remoteSocketAddr.getAddress.getHostAddress
      val remoteWifiNameString = remoteSocketAddr.getHostName
      // convert spaces to underlines in device name (some android activities, for instance the browser, dont like encoded spaces =%20 in file pathes)
      val myRemoteWifiNameString = remoteWifiNameString.replaceAll(" ","_")
      val localSocketAddr = socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress]
      val localWifiAddrString = localSocketAddr.getAddress.getHostAddress
      val localWifiNameString = localSocketAddr.getHostName

      val mmInStream = socket.getInputStream
      if(mmInStream!=null) {
        val mmOutStream = socket.getOutputStream
        if(mmOutStream!=null) {
          if(activityMsgHandler!=null) {
            val msg = activityMsgHandler.obtainMessage(RFCommHelperService.MESSAGE_DEVICE_NAME)
            val bundle = new Bundle
            bundle.putString(RFCommHelperService.DEVICE_NAME, myRemoteWifiNameString)
            bundle.putString(RFCommHelperService.DEVICE_ADDR, remoteWifiAddrString)
            bundle.putString(RFCommHelperService.SOCKET_TYPE, "wifi")
            msg.setData(bundle)
            activityMsgHandler.sendMessage(msg)
          }

          if(appService.connectedThread!=null && appService.connectedThread.isRunning) {
            // re-connect a bt connection after it was disconnected
            // todo: but only if a BackupConnection is in use     
            appService.connectedThread.updateStreams(mmInStream, mmOutStream)
            if(D) Log.i(TAG, "connectedWifi -> disconnectBackupConnection")
            appService.connectedThread.disconnectBackupConnection

          } else {
            appService.createConnectedThread
            appService.connectedThread.init(mmInStream, mmOutStream, false, localWifiAddrString, localWifiNameString, remoteWifiAddrString, myRemoteWifiNameString, () => { 
              if(D) Log.i(TAG, "connectedWifi post-ConnectedThread processing...")

              //if(D) Log.i(TAG, "connectionLost addrString="+addrString+" nameString="+nameString)
              // tell the activity that the connection was lost
              val msg = activityMsgHandler.obtainMessage(RFCommHelperService.DEVICE_DISCONNECT)
              val bundle = new Bundle
              bundle.putString(RFCommHelperService.DEVICE_ADDR, remoteWifiAddrString)
              bundle.putString(RFCommHelperService.DEVICE_NAME, myRemoteWifiNameString)
              msg.setData(bundle)
              activityMsgHandler.sendMessage(msg)

              System.gc    
              p2pCloseFkt() // will close the socket
              if(D) Log.i(TAG, "connectedWifi post-ConnectedThread processing done")
            })

            appService.connectedThread.start // run() will immediately connect to SocketProxy
            appService.connectedThread.doFirstActor

            // Send the name of the connected device back to the UI Activity
            // note: the main activity may not be active at this moment (but for instance the ConnectPopupActivity)
            if(activityMsgHandler!=null) {
              val msg = activityMsgHandler.obtainMessage(RFCommHelperService.MESSAGE_DEVICE_NAME)
              val bundle = new Bundle
              bundle.putString(RFCommHelperService.DEVICE_NAME, remoteWifiNameString)
              bundle.putString(RFCommHelperService.DEVICE_ADDR, remoteWifiAddrString)
              bundle.putBoolean(RFCommHelperService.SOCKET_TYPE, false) // not supported for wifi
              msg.setData(bundle)
              activityMsgHandler.sendMessage(msg)
            }
            setState(RFCommHelperService.STATE_CONNECTED)
          }
        }
      }
    }

    if(D) Log.i(TAG, "connectedWifi done")
  }

  class AcceptThread(pairedBt:Boolean=true) extends Thread {
    if(D) Log.i(TAG, "AcceptThread")
    private var mSocketType: String = if(pairedBt) "Secure" else "Insecure"
    private var mmServerSocket:BluetoothServerSocket = null
    mmServerSocket = null
    try {
      if(pairedBt) {
        mmServerSocket = mBluetoothAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE, MY_UUID_SECURE)
      } else {
        try {
          mmServerSocket = mBluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(NAME_INSECURE, MY_UUID_INSECURE)
        } catch {
          case nsmerr: java.lang.NoSuchMethodError =>
            // this should really not happen, because we run the insecure method only if os >= 2.3.3
            Log.e(TAG, "listenUsingInsecureRfcommWithServiceRecord failed ", nsmerr)
        }
      }
    } catch {
      case e: IOException =>
        Log.e(TAG, "AcceptThread pairedBtOnly="+pairedBtOnly+" listen() failed", e)
    }

    override def run() {
      if(mmServerSocket==null)
        return

      if(D) Log.i(TAG, "AcceptThread run pairedBtOnly="+pairedBtOnly+" mmServerSocket="+mmServerSocket+" ################")
      setName("AcceptThread"+pairedBtOnly)
      var socket:BluetoothSocket = null

      // Listen to the server socket if we're not connected
      while(mmServerSocket!=null) {
        if(D) Log.i(TAG, "AcceptThread run loop pairedBtOnly="+pairedBtOnly+" mmServerSocket="+mmServerSocket+" ################")
        try {
          synchronized {
            socket = null
            if(mmServerSocket!=null) {
              // This is a blocking call and will only return on a successful connection or an exception
              socket = mmServerSocket.accept
              if(D) Log.i(TAG, "AcceptThread run loop after accept, socket="+socket+" ################")
            }
          }
        } catch {
          case ioex: IOException =>
            // log exception only if not stopped
            if(state != RFCommHelperService.STATE_NONE)
              Log.e(TAG, "AcceptThread run pairedBtOnly="+pairedBtOnly+" state="+state+" ioex="+ioex)
        }

        if(D) Log.i(TAG, "AcceptThread socket="+socket+" acceptAndConnect="+acceptAndConnect)
        if(socket!=null) {
          // a bt connection is technically possible and can be accepted
          // note: this is where we can decide to acceptAndConnect (or not)
          if(!acceptAndConnect) {
            if(D) Log.i(TAG, "AcceptThread - denying incoming connect request, acceptAndConnect="+acceptAndConnect+" activity="+activity)
            // hangup
            socket.close

            if(activity!=null) {
              activity.runOnUiThread(new Runnable() {
                override def run() { 
                  // we want to show our appname, this toast will appear if Anymime is running in background
                  Toast.makeText(activity, "Run Anymime in foreground to accept BT connections.", Toast.LENGTH_LONG).show
                }
              })
            } else {
              // ...
            }

            try { Thread.sleep(100); } catch { case ex:Exception => }
            if(D) Log.i(TAG, "AcceptThread - after denying +100 ms acceptAndConnect="+acceptAndConnect)
            try { Thread.sleep(100); } catch { case ex:Exception => }
            if(D) Log.i(TAG, "AcceptThread - after denying +200 ms acceptAndConnect="+acceptAndConnect)
            try { Thread.sleep(300); } catch { case ex:Exception => }
            if(D) Log.i(TAG, "AcceptThread - after denying +500 ms acceptAndConnect="+acceptAndConnect)
            try { Thread.sleep(300); } catch { case ex:Exception => }
            if(D) Log.i(TAG, "AcceptThread - after denying +800 ms acceptAndConnect="+acceptAndConnect)

          } else {
            // activity is not paused
            RFCommHelperService.this synchronized {
              connectedBt(socket, socket.getRemoteDevice, pairedBtOnly)
            }
          }
        }
        
        // prevent tight loop
        try { Thread.sleep(100); } catch { case ex:Exception => }
      }
      if(D) Log.i(TAG, "AcceptThread end pairedBtOnly="+pairedBtOnly)
    }

    def cancel() { // called by stopActiveConnection()
      if(D) Log.i(TAG, "AcceptThread cancel() pairedBtOnly="+pairedBtOnly+" mmServerSocket="+mmServerSocket)
      if(mmServerSocket!=null) {
        try {
          setState(RFCommHelperService.STATE_NONE)   // so that run() will NOT log an error; will send MESSAGE_STATE_CHANGE
          mmServerSocket.close
          mmServerSocket=null
        } catch {
          case ex: IOException =>
            Log.e(TAG, "cancel() mmServerSocket="+mmServerSocket+" ex=",ex)
        }
      }
    }
  }

  // private stuff

  private def setState(setState:Int) = synchronized {
    if(setState != state) {
      if(D) Log.i(TAG, "setState() "+state+" -> "+setState)
      state = setState
      // send modified state to the activity Handler
      if(activityMsgHandler!=null) {
        activityMsgHandler.obtainMessage(RFCommHelperService.MESSAGE_STATE_CHANGE, state, -1).sendToTarget
      } else {
        Log.e(TAG, "setState() activityMsgHandler not set")
      }
    }
  }

  private class ConnectThread(remoteDevice:BluetoothDevice, reportConnectState:Boolean=true) extends Thread {
    private var mmSocket:BluetoothSocket = null

    // Get a BluetoothSocket for a connection with the given BluetoothDevice
    try {
      if(pairedBtOnly)
        mmSocket = remoteDevice.createRfcommSocketToServiceRecord(MY_UUID_SECURE)   // requires pairing
      else
        mmSocket = remoteDevice.createInsecureRfcommSocketToServiceRecord(MY_UUID_INSECURE)   // does not require pairing
    } catch {
      case e: IOException =>
        Log.e(TAG, "ConnectThread Socket pairedBtOnly="+pairedBtOnly+" create() failed", e)
    }

    override def run() {
      if(D) Log.i(TAG, "ConnectThread run pairedBtOnly="+pairedBtOnly)
      setName("ConnectThread"+pairedBtOnly)

      // Always cancel discovery because it will slow down a connection
      mBluetoothAdapter.cancelDiscovery

      try {
        // This is a blocking call and will only return on a successful connection or an exception
        if(D) Log.i(TAG, "ConnectThread run mmSocket.connect()")
        mmSocket.connect
      } catch {
        case ex:IOException =>
          if(!pairedBtOnly) {
            if(D) Log.i(TAG, "ConnectThread run ignore failed insecure connect ...")
            // ignore exception, try again to connect, but this time secure/paired
            try {
              mmSocket.close
            } catch {
              case ex:Exception =>
                // ignore
            }

            try {
              mmSocket = remoteDevice.createRfcommSocketToServiceRecord(MY_UUID_SECURE)   // requires pairing
              if(D) Log.i(TAG, "ConnectThread run 2nd attempt secure connect ...")
              mmSocket.connect
            } catch {
              case e: IOException =>
                Log.e(TAG, "ConnectThread run unable to connect() 2nd attempt pairedBtOnly="+pairedBtOnly+" IOException",ex)
                if(reportConnectState) {
                  val msg = activityMsgHandler.obtainMessage(RFCommHelperService.CONNECTION_FAILED)
                  val bundle = new Bundle
                  bundle.putString(RFCommHelperService.DEVICE_ADDR, remoteDevice.getAddress)
                  bundle.putString(RFCommHelperService.DEVICE_NAME, remoteDevice.getName)
                  msg.setData(bundle)
                  activityMsgHandler.sendMessage(msg)
                }
                // Close the socket
                try {
                  mmSocket.close
                } catch {
                  case ex:Exception =>
                    // ignore
                }
                return
            }
          } else {
            Log.e(TAG, "ConnectThread run unable to connect() pairedBtOnly="+pairedBtOnly+" IOException",ex)
            if(reportConnectState) {
              val msg = activityMsgHandler.obtainMessage(RFCommHelperService.CONNECTION_FAILED)
              val bundle = new Bundle
              bundle.putString(RFCommHelperService.DEVICE_ADDR, remoteDevice.getAddress)
              bundle.putString(RFCommHelperService.DEVICE_NAME, remoteDevice.getName)
              msg.setData(bundle)
              activityMsgHandler.sendMessage(msg)
            }
            // Close the socket
            try {
              mmSocket.close
            } catch {
              case ex:Exception =>
                // ignore
            }
            return
          }
      }

      // Start the connected thread
      connectedBt(mmSocket, remoteDevice, pairedBtOnly)
    }

    def cancel() {
      if(D) Log.i(TAG, "ConnectThread cancel() pairedBtOnly="+pairedBtOnly+" mmSocket="+mmSocket)
      if(mmSocket!=null) {
        try {
          mmSocket.close
        } catch {
          case e: IOException =>
            Log.e(TAG, "ConnectThread cancel() socket.close() failed for pairedBtOnly="+pairedBtOnly, e)
        }
      }
    }
  }

  def nfcServiceSetup() {
    // this is called by radioDialog/OK, by wifiDirectBroadcastReceiver:WIFI_P2P_THIS_DEVICE_CHANGED_ACTION and by onActivityResult:REQUEST_ENABLE_BT

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
        if(activityResumed)
          mNfcAdapter.setNdefPushMessage(null, activity)

      } else {        
        nfcForegroundPushMessage = new NdefMessage(Array(NfcHelper.newTextRecord(nfcString, Locale.ENGLISH, true)))
        if(nfcForegroundPushMessage!=null) {
          if(activityResumed) {
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

  def newWiFiDirectBroadcastReceiver(wifiP2pManager:WifiP2pManager) :WiFiDirectBroadcastReceiver = {
    return new WiFiDirectBroadcastReceiver(wifiP2pManager)
  }

  class WiFiDirectBroadcastReceiver(wifiP2pManager:WifiP2pManager) extends BroadcastReceiver {
    private val TAG = "WiFiDirectBroadcastReceiver"
    private val D = true

    override def onReceive(activity:Context, intent:Intent) {
      val action = intent.getAction

      if(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION == action) {
        // p2pWifi functionality has now been enabled (or disabled)

        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
        if(D) Log.i(TAG, "WIFI_P2P_STATE_CHANGED_ACTION state="+state+" ####")
        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
          // Wifi Direct mode is enabled
          //if(D) Log.i(TAG, "WifiP2pEnabled true ####")
          isWifiP2pEnabled=true

        } else {
          //if(D) Log.i(TAG, "WifiP2pEnabled false ####")
          p2pRemoteAddressToConnect = null
          p2pConnected = false
          isWifiP2pEnabled=false
        }

      } else if(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION == action) {
        // this device has now a p2pWifi-connection (or it has lost it)
        // we get our own dynamic p2p-mac-addr

        val wifiP2pDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE).asInstanceOf[WifiP2pDevice]
        if(localP2pWifiAddr==null || localP2pWifiAddr!=wifiP2pDevice.deviceAddress) {
          // we now know our p2p mac address, we now can do nfcServiceSetup
          if(D) Log.i(TAG, "WIFI_P2P_THIS_DEVICE_CHANGED_ACTION OUR deviceName="+wifiP2pDevice.deviceName+" deviceAddress="+wifiP2pDevice.deviceAddress) //+" info="+wifiP2pDevice.toString)
          localP2pWifiAddr = wifiP2pDevice.deviceAddress
          nfcServiceSetup
        }

      } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION == action) {
        // an update to the situation with visible p2pWifi devices
        // we can (must?) use this to to connect to one of the visible devices

        if(D) Log.i(TAG, "WIFI_P2P_PEERS_CHANGED_ACTION number of p2p peers changed ####")
        discoveringPeersInProgress = false

        if(wifiP2pManager==null) {
          if(D) Log.i(TAG, "WIFI_P2P_PEERS_CHANGED_ACTION wifiP2pManager==null ####")

        } else {
          //if(D) Log.i(TAG, "WIFI_P2P_PEERS_CHANGED_ACTION requestPeers() ####")
          wifiP2pManager.requestPeers(p2pChannel, new PeerListListener() {
            override def onPeersAvailable(wifiP2pDeviceList:WifiP2pDeviceList) {
              // wifiP2pDeviceList.getDeviceList() is a list of WifiP2pDevice objects, each containg deviceAddress, deviceName, primaryDeviceType, etc.
              wifiP2pDeviceArrayList.addAll(wifiP2pDeviceList.getDeviceList.asInstanceOf[java.util.Collection[WifiP2pDevice]])
              val wifiP2pDeviceListCount = wifiP2pDeviceArrayList.size
              if(D) Log.i(TAG, "onPeersAvailable wifiP2pDeviceListCount="+wifiP2pDeviceListCount+" trying to connect to="+p2pRemoteAddressToConnect+" ####")
              if(wifiP2pDeviceListCount>0) {
                // list all peers
                for(i <- 0 until wifiP2pDeviceListCount) {
                  val wifiP2pDevice = wifiP2pDeviceArrayList.get(i)
                  if(wifiP2pDevice != null) {
                    //if(D) Log.i(TAG, "device "+i+" deviceName="+wifiP2pDevice.deviceName+" deviceAddress="+wifiP2pDevice.deviceAddress+" status="+wifiP2pDevice.status+" "+(wifiP2pDevice.deviceAddress==p2pRemoteAddressToConnect)+" ####")
                    // status: connected=0, invited=1, failed=2, available=3
                    
                    if(p2pWifiDiscoveredCallbackFkt!=null)
                      p2pWifiDiscoveredCallbackFkt(wifiP2pDevice)

                    if(p2pRemoteAddressToConnect!=null && wifiP2pDevice.deviceAddress==p2pRemoteAddressToConnect) {
                      if(localP2pWifiAddr < p2pRemoteAddressToConnect) {
                        if(D) Log.i(TAG, "onPeersAvailable - local="+localP2pWifiAddr+" < remote="+p2pRemoteAddressToConnect+" - stay passive - let other device connect() ########################")
                        p2pRemoteAddressToConnect = null

                      } else {
                        if(D) Log.i(TAG, "onPeersAvailable active connect() local="+localP2pWifiAddr+" > remote="+p2pRemoteAddressToConnect+" ########################")
                        val wifiP2pConfig = new WifiP2pConfig()
                        wifiP2pConfig.groupOwnerIntent = -1
                        wifiP2pConfig.wps.setup = WpsInfo.PBC
                        wifiP2pConfig.deviceAddress = p2pRemoteAddressToConnect
                        p2pRemoteAddressToConnect = null
                        wifiP2pManager.connect(p2pChannel, wifiP2pConfig, new ActionListener() {
                          // note: may result in "E/wpa_supplicant(): Failed to create interface p2p-wlan0-5: -12 (Out of memory)"
                          //       in which case onSuccess() is often still be called
                        
                          override def onSuccess() {
                            if(D) Log.i(TAG, "wifiP2pManager.connect() success ####")
                            // we expect WIFI_P2P_CONNECTION_CHANGED_ACTION in WiFiDirectBroadcastReceiver to notify us
                            // todo: however sometimes this does NOT happen
                            
                            // let's render the connect-progress animation (like we do in connectBt)
                            connectedRadio = 2 // wifi
                            val msg = activityMsgHandler.obtainMessage(RFCommHelperService.CONNECTION_START)
                            val bundle = new Bundle
                            bundle.putString(RFCommHelperService.DEVICE_ADDR, wifiP2pConfig.deviceAddress)
                            bundle.putString(RFCommHelperService.DEVICE_NAME, p2pRemoteNameToConnect)
                            msg.setData(bundle)
                            activityMsgHandler.sendMessage(msg)
                          }

                          override def onFailure(reason:Int) {
                            val errMsg = "wifiP2pManager.connect() failed reason="+reason
                            Log.e(TAG, errMsg+" ##################")
                            // reason ERROR=0, P2P_UNSUPPORTED=1, BUSY=2
                            if(activityMsgHandler!=null)
                              activityMsgHandler.obtainMessage(RFCommHelperService.ALERT_MESSAGE, -1, -1, errMsg).sendToTarget
                          }
                        })
                        if(D) Log.i(TAG, "wifiP2pManager.connect() done")
                      }
                    }

                  }
                }
              }
            }
          })
        }

      } else if(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION==action) {
        // we got p2pWifi connected (or disconnected)
        // as a result of us (or some other device) calling wifiP2pManager.connect() 
        // (sometimes failes to be called)

        val networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO).asInstanceOf[NetworkInfo]
        if(D) Log.i(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION new p2p-connect-state="+networkInfo.isConnected+" getSubtypeName="+networkInfo.getSubtypeName+" ###################")

        if(wifiP2pManager==null) {
          if(D) Log.i(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION wifiP2pManager==null ###################")
          return
        }
        
        if(networkInfo.isConnected && p2pConnected) {
          if(D) Log.i(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION we are already connected - strange... ignore ###################")
          // todo: new p2p-connect, but we were connected already (maybe this is how we set up a group of 3 or more clients?)
          return
        }

        if(!networkInfo.isConnected) {
          p2pRemoteAddressToConnect = null

          if(!p2pConnected) {
            if(D) Log.i(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION we are now disconnected, we were disconnect already")
            return
          }
          // we think we are connected, but now we are being disconnected
          if(D) Log.i(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION we are now disconnected, set p2pConnected=false")
          p2pConnected = false
          return

        } else {
          // we got connected with another device, request connection info to find group owner IP
          p2pConnected = true
          if(D) Log.i(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION we are now p2pWifi connected with the other device")
          wifiP2pManager.requestConnectionInfo(p2pChannel, new WifiP2pManager.ConnectionInfoListener() {
            override def onConnectionInfoAvailable(wifiP2pInfo:WifiP2pInfo) {
              if(D) Log.i(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION onConnectionInfoAvailable groupOwnerAddress="+wifiP2pInfo.groupOwnerAddress+" isGroupOwner="+wifiP2pInfo.isGroupOwner+" ###############")

              // start socket communication
              new Thread() {
                override def run() {
                  var serverSocket:ServerSocket = null
                  var socket:Socket = null

                  def closeDownP2p() {
                    // this will be called (by both sides) when the thread is finished
                    Log.d(TAG, "closeDownP2p p2pConnected="+p2pConnected+" p2pChannel="+p2pChannel)
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
                      Log.d(TAG, "closeDownP2p wifiP2pManager.removeGroup() (this is how we disconnect from p2pWifi)")
                      wifiP2pManager.removeGroup(p2pChannel, new ActionListener() {
                        override def onSuccess() {
                          if(D) Log.i(TAG, "closeDownP2p wifiP2pManager.removeGroup() success ####")
                          // wifiDirectBroadcastReceiver will notify us
                        }

                        override def onFailure(reason:Int) {
                          if(D) Log.i(TAG, "closeDownP2p wifiP2pManager.removeGroup() failed reason="+reason)
                          // reason ERROR=0, P2P_UNSUPPORTED=1, BUSY=2
                          // note: it seems to be 'normal' for one of the two devices to receive reason=2 on disconenct
                        }
                      })

                      p2pConnected = false  // probably not required, because WIFI_P2P_CONNECTION_CHANGED_ACTION will be called again with networkInfo.isConnected=false
                      p2pRemoteAddressToConnect = null
                    }
                  }

                  val port = 8954
                  if(wifiP2pInfo.isGroupOwner) {
                    // which device becomes the isGroupOwner is random, but it will be the device we run our serversocket on...
                    // by convention, we make the GroupOwner (using the serverSocket) also the filetransfer-non-actor
                    // start server socket
                    //Log.d(TAG, "Server: new ServerSocket("+port+")")
                    try {
                      serverSocket = new ServerSocket(port)
                      Log.d(TAG, "serverSocket opened")
                      socket = serverSocket.accept
                      if(socket!=null) {
                        connectedWifi(socket, actor=false, closeDownP2p)
                      }
                    } catch {
                      case ioException:IOException =>
                        Log.e(TAG, "serverSocket failed to connect ex="+ioException.getMessage)
                        closeDownP2p
                    }

                  } else {
                    // which device becomes the Group client is random, but this is the device we run our client socket on...
                    // by convention, we make the Group client (using the client socket) also the filetransfer-actor (will start the delivery)
                    // because we are NOT the groupOwner, the groupOwnerAddress is the address of the OTHER device
                    val SOCKET_TIMEOUT = 5000
                    val host = wifiP2pInfo.groupOwnerAddress
                    socket = new Socket()
                    try {
                      //Log.d(TAG, "client socket opened")
                      socket.bind(null)
                      socket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT)
                      // we wait up to 5000 ms for the connection... if we don't get connected, an ioexception is thrown                  
                      // otherwise we continue here by connecting to the other peer
                      connectedWifi(socket, actor=true, closeDownP2p)
                    } catch {
                      case ioException:IOException =>
                        Log.e(TAG, "client socket failed to connect ex="+ioException.getMessage+" ########")
                        closeDownP2p
                    }
                  }
                }
              }.start
            }
          })
        }
      }
    }
  }
}


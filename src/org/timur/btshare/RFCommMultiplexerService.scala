/*
 * Copyright (C) 2011 Timur Mehrvarz
 * Portions: Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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
 */

/*
 * RFCommMultiplexerService is a generic Android service used by developers 
 * to create ad-hoc wireless networks based on Bluetooth technology, 
 * RFCommMultiplexer simplifies the creation of multi-device apps. 
 * Spontaneous networks are created by simply connecting devices to each other. 
 * A device only needs to connect to one other device, and immediately it 
 * becomes part of a larger network. Applications connected in this way can 
 * engage in coordinated interaction such as multi-player gaming.
 * No access points infrastructure is needed. 
 * RFCommMultiplexer is written in Scala 2.8.x.
 */

package org.timur.btshare

import scala.collection.mutable.HashMap
import scala.collection.mutable.ListBuffer

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.LinkedList
import java.util.ArrayList

import android.app.ActivityManager
import android.util.Log
import android.content.Context
import android.content.Intent
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

import com.google.protobuf.CodedOutputStream
import com.google.protobuf.CodedInputStream

object RFCommMultiplexerService {
  val LOGLINES = 50        // todo: a little arbitrary number

  val STATE_NONE = 0       // doing nothing
  val STATE_LISTEN = 1     // not yet connected but listening for incoming connections
  val STATE_CONNECTED = 3  // connected to at least one remote device

  // Message types sent from RFCommMultiplexerService to the activity handler
  val MESSAGE_STATE_CHANGE = 1
  val MESSAGE_READ = 2
  val MESSAGE_WRITE = 3
  val MESSAGE_DEVICE_NAME = 4
  val MESSAGE_TOAST = 5
  val RECEIVED_PONG = 6
  val DEVICE_DISCONNECT = 7
  val CONNECTION_FAILED = 8
  val CONNECTION_START = 9
  val MESSAGE_REDRAW_DEVICEVIEW = 10
  val MESSAGE_DELIVER_PROGRESS = 11

  // Key names received from RFCommMultiplexerService to the activity handler
  val DEVICE_NAME = "device_name"
  val DEVICE_ADDR = "device_addr"
  val SOCKET_TYPE = "socket_type"
  val TOAST = "toast"
  val DELIVER_ID = "deliver_id"
  val DELIVER_PROGRESS = "deliver_progress"
} 

class QueueMessage(createTimeMs:Long=0l, deviceAddr:String=null, deviceName:String=null, strmsg:String=null) {
  def createTimeMs() :Long = { return createTimeMs }
  def deviceAddr() :String = { return deviceAddr }
  def deviceName() :String = { return deviceName }
  def strmsg() :String = { return strmsg }
}

class IndirectDeviceObject(deviceName:String, lastTimeSeen:Long) {
  def deviceName() :String = { return deviceName }
  def lastTimeSeen() :Long = { return lastTimeSeen }
}

class RFCommMultiplexerService extends android.app.Service {
  private val TAG = "RFCommMultiplexerService"
  private val D = true

  // Name and unique UUID for the SDP record when creating server socket
  private val NAME_SECURE = "BluetoothChatSecure"
  private val MY_UUID_SECURE   = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")

  private val NAME_INSECURE = "BluetoothChatInsecure"
  private val MY_UUID_INSECURE = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

  // Member fields for the local bluetooth adapter
  protected val mAdapter = BluetoothAdapter.getDefaultAdapter()
  protected var myBtName = mAdapter.getName()
  protected var myBtAddr = mAdapter.getAddress()
  
  protected var context:Context = null
  protected var activityMsgHandler:Handler = null

  protected val queueMessageLinkedList = new LinkedList[QueueMessage]()

//  private val sendQueue = new scala.collection.mutable.Queue[Any]


  def getAllMsgsNewerThan(lastMsgTimeMillis:Long) :ArrayList[QueueMessage] = {
    val retList = new ArrayList[QueueMessage]()
    queueMessageLinkedList synchronized {
      val listIterator = queueMessageLinkedList.listIterator(0) 
      while(listIterator.hasNext()) {
        val msg = listIterator.next()
        if(msg.createTimeMs()>lastMsgTimeMillis)
          retList.add(msg)
      }
    }
    return retList
  }

  def setContext(context:Context) {
    this.context = context
  }

  def setActivityMsgHandler(activityMsgHandler:Handler) {
    // android.os.Handler defined in the activity
    this.activityMsgHandler = activityMsgHandler
  }

  @volatile protected var sendMsgCounter:Long = 0

  @volatile private var mSecureAcceptThread: AcceptThread = null
  @volatile private var mInsecureAcceptThread: AcceptThread = null
  @volatile protected var mConnectThread: ConnectThread = null
  @volatile protected var mConnectedThread: ConnectedThread = null
  @volatile protected var mState = RFCommMultiplexerService.STATE_NONE

  // directlyConnectedDevicesMap contains all directly connected devices mapped to their connectedThread objects
  val directlyConnectedDevicesMap = new HashMap[BluetoothDevice,ConnectedThread]

  // indirectlyConnectedDevicesMap contains all indirectly connected devices mapped to their connectedThread objects
  val indirectlyConnectedDevicesMap = new HashMap[String,IndirectDeviceObject] // btAddr,IndirectDeviceObject

  // sendMsgCounterMap keeps track of the msg-counters for all known devices
  // this makes it possible to ignore messages that are received multiple times
  val sendMsgCounterMap = new HashMap[String,Long] // BluetoothDeviceAddrString,counter

  class LocalBinder extends android.os.Binder {
    def getService = RFCommMultiplexerService.this
  }

  val localBinder = new LocalBinder
  override def onBind(intent:Intent) :IBinder = localBinder 

  def processBtMessage(cmd:String, arg1:String, fromAddr:String, btMessage:BtShare.Message)(readCodedInputStream:() => Array[Byte]): Boolean = {
    // this method should be overloaded to implement app specific protocol extensions
    Log.d(TAG, "processBtMessage() empty function to be overridden!!!!! SHOULD NEVER BE SHOWN!!!! -------------------------------")
    return false // return false if msg was not processed
  }

  def getState() :Int = synchronized {
    return mState
  }

  def isDirectlyConnectedDevices(newRemoteDeviceAddr: String) :Boolean  = synchronized {
    directlyConnectedDevicesMap.foreach { case (remoteDevice, connectedThread) => 
      if(connectedThread!=null && remoteDevice!=null)
        if(newRemoteDeviceAddr.equals(remoteDevice.getAddress()))
          return true
    }
    return false
  }

  def getAllConnecedDevicesMap() : HashMap[String,Long] = { // btAddrString, sendMsgCounterInt
    //Log.d(TAG, "getAllConnecedDevicesMap()")
    return sendMsgCounterMap
  }

  // called by Activity onResume() 
  // but only while getState() == STATE_NONE
  // this is why we quickly switch state to STATE_LISTEN
  def start() = synchronized {
    if(D) Log.i(TAG, "start: android.os.Build.VERSION.SDK_INT="+android.os.Build.VERSION.SDK_INT)

    setState(RFCommMultiplexerService.STATE_LISTEN)   // will send MESSAGE_STATE_CHANGE

    // in case bt was turned on after app start
    if(myBtName==null)
      myBtName = mAdapter.getName()
    if(myBtAddr==null)
      myBtAddr = mAdapter.getAddress()

    // Start the thread to listen on a BluetoothServerSocket
    if(mSecureAcceptThread == null) {
      if(D) Log.i(TAG, "start new AcceptThread for secure")
      mSecureAcceptThread = new AcceptThread(true)
      if(mSecureAcceptThread != null) 
        mSecureAcceptThread.start()
    }

    if(android.os.Build.VERSION.SDK_INT>=10) {
      // start insecure socket only on 2.3.3+
      if(mInsecureAcceptThread == null) {
        if(D) Log.i(TAG, "start new AcceptThread for insecure (running on 2.3.3+)")
        mInsecureAcceptThread = new AcceptThread(false)
        if(mInsecureAcceptThread != null)
          mInsecureAcceptThread.start()
      }
    }
    if(D) Log.i(TAG, "start: done")
  }

  @volatile var connectingCount = 0

  // called by the activity: options menu "connect" -> onActivityResult() -> connectDevice()
  // called by the activity: as a result of NfcAdapter.ACTION_NDEF_DISCOVERED
  def connect(newRemoteDevice:BluetoothDevice, secure:Boolean, reportConnectState:Boolean=true) :Unit = synchronized {
    if(newRemoteDevice==null) {
      Log.e(TAG, "connect() newRemoteDevice==null, give up")
      return
    }

    if(D) Log.i(TAG, "connect() remoteAddr="+newRemoteDevice.getAddress()+" name="+newRemoteDevice.getName()+" secure="+secure)

    // if newRemoteDevice is already listed in directlyConnectedDevicesMap, then we do nothing
    if(isDirectlyConnectedDevices(newRemoteDevice.getAddress())) {
      if(D) Log.i(TAG, "connect() newRemoteDevice is already directly connected, give up")
      return
    }

    connectingCount+=1

    if(reportConnectState) {
      val msg = activityMsgHandler.obtainMessage(RFCommMultiplexerService.CONNECTION_START)
      val bundle = new Bundle()
      bundle.putString(RFCommMultiplexerService.DEVICE_ADDR, newRemoteDevice.getAddress())
      bundle.putString(RFCommMultiplexerService.DEVICE_NAME, newRemoteDevice.getName())
      msg.setData(bundle)
      activityMsgHandler.sendMessage(msg)
    }
    
    // Start the thread to connect with the given device
    mConnectThread = new ConnectThread(newRemoteDevice, secure, reportConnectState)
    mConnectThread.start()
  }

  // called by onDestroy()
  // note: on the other hand ... when the activity ends, we want the service to continue
  //       maybe make this available via "options menue / more" + "kill all"
  def stop() = synchronized {
    if(D) Log.i(TAG, "stop")

    setState(RFCommMultiplexerService.STATE_NONE)   // will send MESSAGE_STATE_CHANGE

    if(mConnectThread != null) {
      mConnectThread.cancel()
      mConnectThread = null
    }

    if(mConnectedThread != null) {
      mConnectedThread.cancel()
      mConnectedThread = null
    }

    if(mSecureAcceptThread != null) {
      mSecureAcceptThread.cancel()
      mSecureAcceptThread = null
    }

    if(mInsecureAcceptThread != null) {
      mInsecureAcceptThread.cancel()
      mInsecureAcceptThread = null
    }
  }

  def send(message: String, toAddr:String) {
    send(null, message, toAddr)
  }

  def send(cmd:String, message:String, toAddr:String) = synchronized {
    // the idea with synchronized is that no other send() shall take over (will interrupt) an ongoing send()
    var thisSendMsgCounter:Long = 0
    synchronized { 
      val nowMs = SystemClock.uptimeMillis
      if(sendMsgCounter>=nowMs) 
        sendMsgCounter+=1
      else
        sendMsgCounter=nowMs

      thisSendMsgCounter = sendMsgCounter
    }
    val myCmd = if(cmd==null) cmd else "strmsg"
    if(D) Log.i(TAG, "send myCmd="+myCmd+" message="+message+" toAddr="+toAddr+" sendMsgCounter="+thisSendMsgCounter+" directlyConnectedDevicesMap.size="+directlyConnectedDevicesMap.size)
    directlyConnectedDevicesMap.foreach { case (remoteDevice, connectedThread) => 
      //if(D) Log.i(TAG, "send myCmd="+myCmd+" message="+message+" toAddr='"+toAddr+"' remoteDevice="+remoteDevice+" connectedThread="+connectedThread)
      if(connectedThread!=null) {
        //if(D) Log.i(TAG, "send myCmd="+myCmd+" message="+message+" toAddr='"+toAddr+"' remoteDevice='"+remoteDevice.getAddress()+"'")
        connectedThread.writeCmdMsg(myCmd,message,toAddr,thisSendMsgCounter)
      }
    }

    if(cmd==null || cmd.equals("strmsg")) {
      // Share the sent message back to the UI Activity
      //if(D) Log.i(TAG, "send 'Share the sent message back to the UI Activity'")
      activityMsgHandler.obtainMessage(RFCommMultiplexerService.MESSAGE_WRITE, -1, -1, message).sendToTarget()
    }

    if(D) Log.i(TAG, "send myCmd="+myCmd+" DONE")
  }

  def ping(toAddr:String) {
    if(D) Log.i(TAG, "ping toAddr="+toAddr+" directlyConnectedDevicesMap.size="+directlyConnectedDevicesMap.size)
    val nowMs = SystemClock.uptimeMillis()
    var thisSendMsgCounter:Long = 0
    synchronized { 
      //sendMsgCounter+=1
      val nowMs = SystemClock.uptimeMillis()
      if(sendMsgCounter>=nowMs) 
        sendMsgCounter+=1
      else
        sendMsgCounter=nowMs
      thisSendMsgCounter = sendMsgCounter
    }
    directlyConnectedDevicesMap.foreach { case (remoteDevice, connectedThread) => 
      if(connectedThread!=null)
        connectedThread.writeCmdMsg("ping",""+nowMs,toAddr,thisSendMsgCounter)
    }
  }

  def sendData(size:Int, data:Array[Byte], toAddr:String) {
    if(D) Log.i(TAG, "sendData size="+size+" toAddr="+toAddr)
    directlyConnectedDevicesMap.foreach { case (remoteDevice, connectedThread) => 
      if(D) Log.i(TAG, "sendData remoteDevice.getAddress()="+remoteDevice.getAddress()+" connectedThread="+connectedThread)
      if(connectedThread!=null)
        try {
          if(D) Log.i(TAG, "sendData remoteDevice.getAddress()="+remoteDevice.getAddress()+" connectedThread="+connectedThread)   
          connectedThread.writeData(size, data)
        } catch {
          case e: IOException =>
            Log.e(TAG, "sendData exception during write", e)
        }
    }
  }

  def sendToast(string:String) {
    val msg = activityMsgHandler.obtainMessage(RFCommMultiplexerService.MESSAGE_TOAST)
    val bundle = new Bundle()
    bundle.putString(RFCommMultiplexerService.TOAST, string)
    msg.setData(bundle)
    activityMsgHandler.sendMessage(msg)
  }

  // private methods

  protected def setState(state: Int) = synchronized {
    if(D) Log.i(TAG, "setState() " + mState + " -> " + state)
    mState = state
    // Give the new state to the Handler so the UI Activity can update
    activityMsgHandler.obtainMessage(RFCommMultiplexerService.MESSAGE_STATE_CHANGE, state, -1).sendToTarget()
  }
  
  def checkQueueMaxSize() {
    while(queueMessageLinkedList.size()>RFCommMultiplexerService.LOGLINES)
      queueMessageLinkedList.removeFirst()
  }

  // todo: not sure who calls this
  def disconnect(socket: BluetoothSocket) = synchronized {
    if(socket!=null) {
      try {
        socket.close()
      } catch {
        case ex: IOException =>
          Log.e(TAG, "disconnect() socket="+socket+" ex=",ex)
      }
    }
  }

  // called by: AcceptThread() -> socket = mmServerSocket.accept()
  // called by: ConnectThread() / activity options menu (or NFC touch) -> connect() -> ConnectThread()
  // called by: ConnectPopupActivity
  def connected(socket: BluetoothSocket, remoteDevice: BluetoothDevice, socketType: String) :Unit = synchronized {
    if(D) Log.i(TAG, "connected, sockettype="+socketType+" remoteDevice="+remoteDevice)
    if(remoteDevice==null) return

    val btAddrString = remoteDevice.getAddress()
    val btNameString = remoteDevice.getName()

    // reset sendMsgCounter for this remote device
    sendMsgCounterMap.put(btAddrString, 0)
    
    // Start the thread to manage the connection and perform transmissions
    if(D) Log.i(TAG, "connected, Start ConnectedThread to manage the connection")
    mConnectedThread = new ConnectedThread(socket, socketType)
    mConnectedThread.start()

    // add remoteDevice to list of connected devices
    directlyConnectedDevicesMap += remoteDevice -> mConnectedThread

/*
    if(directlyConnectedDevicesMap.size==1) {
      // ONLY IF THIS IS OUR 1ST CONNECT
      // todo: broadcast local and remote address as "just connected"
    }
*/
    // Send the name of the connected device back to the UI Activity
    // note: the main activity may not be active at this moment (but for instance the ConnectPopupActivity)
    val msg = activityMsgHandler.obtainMessage(RFCommMultiplexerService.MESSAGE_DEVICE_NAME)
    val bundle = new Bundle()
    bundle.putString(RFCommMultiplexerService.DEVICE_NAME, btNameString)
    bundle.putString(RFCommMultiplexerService.DEVICE_ADDR, btAddrString)
    bundle.putString(RFCommMultiplexerService.SOCKET_TYPE, socketType)
    msg.setData(bundle)
    activityMsgHandler.sendMessage(msg)

    // send to activity: MESSAGE_STATE_CHANGE
    setState(RFCommMultiplexerService.STATE_CONNECTED)    

    // send to activity: "[connected]" text message
    queueMessageLinkedList synchronized {
      queueMessageLinkedList.add(new QueueMessage(System.currentTimeMillis(), btAddrString, btNameString, "[connected]"))
      checkQueueMaxSize()
    }

    if(D) Log.i(TAG, "connected done, directlyConnectedDevicesMap.size="+directlyConnectedDevicesMap.size)
  }

  // called by ConnectedThread() IOException on send()
  private def connectionLost(socket: BluetoothSocket) {
    if(D) Log.i(TAG, "connectionLost() socket="+socket)

    // Send a failure toast back to the Activity
    if(socket!=null) {
      if(D) Log.i(TAG, "connectionLost socket!=null ...")
      val remoteDevice = socket.getRemoteDevice()
      if(D) Log.i(TAG, "connectionLost remoteDevice="+remoteDevice)

      if(remoteDevice!=null) {
        val btAddrString = remoteDevice.getAddress()
        val btNameString = remoteDevice.getName()
        directlyConnectedDevicesMap -= remoteDevice
        if(D) Log.i(TAG, "connectionLost btAddrString="+btAddrString+" btNameString="+btNameString)

        // put a disconnect-entry into msg-log 
        queueMessageLinkedList synchronized {
          queueMessageLinkedList.add(new QueueMessage(System.currentTimeMillis(), btAddrString, btNameString, "[disconnected]"))
          checkQueueMaxSize()
        }

        // tell the activity that the connection was lost
        val msg = activityMsgHandler.obtainMessage(RFCommMultiplexerService.DEVICE_DISCONNECT)
        val bundle = new Bundle()
        bundle.putString(RFCommMultiplexerService.DEVICE_ADDR, btAddrString)
        bundle.putString(RFCommMultiplexerService.DEVICE_NAME, btNameString)
        msg.setData(bundle)
        activityMsgHandler.sendMessage(msg)

        // remove disconnected device from sendMsgCounterMap
        sendMsgCounterMap.remove(btAddrString)

        if(D) Log.i(TAG, "connectionLost directlyConnectedDevicesMap.size="+directlyConnectedDevicesMap.size+" #############################")
        if(directlyConnectedDevicesMap.size>0) {
          // bt-broadcast "disconnect", indication that [btAddrString] _MAYBE_ lost
          //       BUT THE LOST DEVICE MAY STILL BE CONNECTED THROUGH ANOTHER DEVICE
          //       if the disconnected device itself receives this bt-broadcast, it can broadcast a PONG msg to everyone, indicating it is alive

          var thisSendMsgCounter:Long = 0
          synchronized { 
            sendMsgCounter+=1
            thisSendMsgCounter = sendMsgCounter
          }
          directlyConnectedDevicesMap.foreach { case (remoteDevice, connectedThread) => 
            if(connectedThread!=null) {
              if(D) Log.i(TAG, "connectionLost btAddrString="+btAddrString+" bt-forward to "+remoteDevice.getAddress()+" ############################")
              connectedThread.writeCmdMsg("disconnect",btAddrString,null,thisSendMsgCounter)
            }
          }
        }

        //if(D) Log.i(TAG, "ConnectedThread run: strmsg added queueMessageLinkedList.size()="+queueMessageLinkedList.size())
      }
    }

    if(directlyConnectedDevicesMap.size>0) {
      //sendToast(btNameString+" connection was lost")
    } else { 
      //sendToast(btNameString+" connection was lost - now fully disconnected")
      setState(RFCommMultiplexerService.STATE_LISTEN)   // will send MESSAGE_STATE_CHANGE
    }
  }

  protected class AcceptThread(secure: Boolean) extends Thread {
    if(D) Log.i(TAG, "AcceptThread")
    // The local server socket
    private var mSocketType: String = if(secure) "Secure" else "Insecure"
    private var mmServerSocket: BluetoothServerSocket = null

    openListenSocket()

    def openListenSocket() {
      mmServerSocket = null
      // Create a new listening server socket
      try {
        if(secure) {
          mmServerSocket = mAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE, MY_UUID_SECURE)
        } else {
          try {
            mmServerSocket = mAdapter.listenUsingInsecureRfcommWithServiceRecord(NAME_INSECURE, MY_UUID_INSECURE)
          } catch {
            case nsmerr: java.lang.NoSuchMethodError =>
              // this should reall not happen, because we run the insecure method only if os >= 2.3.3
              Log.e(TAG, "listenUsingInsecureRfcommWithServiceRecord failed ", nsmerr)
          }
        }
      } catch {
        case e: IOException =>
          Log.e(TAG, "Socket Type: " + mSocketType + " listen() failed", e)
      }
    }

    override def run() {
      if(mmServerSocket==null)
        return

      setName("AcceptThread" + mSocketType)
      var socket:BluetoothSocket = null

      // Listen to the server socket if we're not connected
      while(mmServerSocket!=null) {
        if(D) Log.i(TAG, "AcceptThread run Socket Type: " + mSocketType)
        try {
          // This is a blocking call and will only return on a
          // successful connection or an exception
          synchronized {
            socket = null
            if(mmServerSocket!=null)
              socket = mmServerSocket.accept()
          }
        } catch {
          case e: IOException =>
            // log exception only if not stopped
            if(mState != RFCommMultiplexerService.STATE_NONE)
              Log.e(TAG, "Socket Type: " + mSocketType + " accept() failed", e)
        }

        if(socket != null) {
          // If a connection was accepted
          RFCommMultiplexerService.this synchronized {
            // this will start the connected thread, add remoteDevice to directlyConnectedDevicesMap
            // and send MESSAGE_DEVICE_NAME to the activity so that mConnectedDeviceAddr can be added to prefKnownDevicesEditor 
            connected(socket, socket.getRemoteDevice(), mSocketType)
          }
        }
        
        // prevent tight loop
        try { Thread.sleep(200); } catch { case ex:Exception => }
      }
      if(D) Log.i(TAG, "END mAcceptThread, socket Type: " + mSocketType)
    }

    def cancel() { // called by disconnect() or stop()
      if(D) Log.i(TAG, "Socket Type " + mSocketType + " cancel()")
      if(mmServerSocket!=null) {
        try {
          mmServerSocket.close()
          mmServerSocket=null
        } catch {
          case ex: IOException =>
            Log.e(TAG, "cancel() mmServerSocket="+mmServerSocket+" ex=",ex)
        }
      }
    }
  }

  protected class ConnectThread(remoteDevice: BluetoothDevice, secure: Boolean, reportConnectState:Boolean=true) extends Thread {
    private val mSocketType = if(secure) "Secure" else "Insecure"
    private var mmSocket: BluetoothSocket = null

    // Get a BluetoothSocket for a connection with the given BluetoothDevice
    try {
      if(secure) {
        mmSocket = remoteDevice.createRfcommSocketToServiceRecord(MY_UUID_SECURE)
      } else {
        mmSocket = remoteDevice.createInsecureRfcommSocketToServiceRecord(MY_UUID_INSECURE)
      }
    } catch {
      case e: IOException =>
        Log.e(TAG, "Socket Type: " + mSocketType + "create() failed", e)
    }

    override def run() {
      if(D) Log.i(TAG, "ConnectThread() run SocketType="+mSocketType)
      setName("ConnectThread" + mSocketType)

      // Always cancel discovery because it will slow down a connection
      mAdapter.cancelDiscovery()

      // Make a connection to the BluetoothSocket
      try {
        // This is a blocking call and will only return on a
        // successful connection or an exception
        mmSocket.connect()
      } catch {
        case e: IOException =>
          // Close the socket
          try {
            mmSocket.close()
          } catch {
            case e2: IOException =>
              Log.e(TAG, "unable to close() " + mSocketType + " socket during connection failure", e2)
          } finally {
            connectingCount-=1
            if(reportConnectState) {
              // need to tell the activity that the connection has failed - and that the connect-animation/busy-image can be disabled/made invisible
              val msg = activityMsgHandler.obtainMessage(RFCommMultiplexerService.CONNECTION_FAILED)
              val bundle = new Bundle()
              bundle.putString(RFCommMultiplexerService.DEVICE_ADDR, remoteDevice.getAddress())
              bundle.putString(RFCommMultiplexerService.DEVICE_NAME, remoteDevice.getName())
              msg.setData(bundle)
              activityMsgHandler.sendMessage(msg)
            }
          }
          return
      }

      // Reset the ConnectThread because we're done
      RFCommMultiplexerService.this synchronized {
        mConnectThread = null
      }

      // Start the connected thread
      connected(mmSocket, remoteDevice, mSocketType)
      connectingCount-=1
    }

    def cancel() {
      try {
        mmSocket.close()
      } catch {
        case e: IOException =>
          Log.e(TAG, "close() of connect " + mSocketType + " socket failed", e)
      }
    }
  }

  class ConnectedThread(socket:BluetoothSocket, socketType:String) extends Thread {
    if(D) Log.i(TAG, "ConnectedThread start " + socketType)
    private var mmInStream: InputStream = null
    private var codedInputStream: CodedInputStream = null
    private var mmOutStream: OutputStream = null
    //private var codedOutputStream: CodedOutputStream = null
    private var mConnectedSendThread: ConnectedSendThread = null
    private val sendQueue = new scala.collection.mutable.Queue[Any]

    var connectedBluetoothDevice:BluetoothDevice = null
    var connectedBtAddr:String = null
    var connectedBtName:String = null
    @volatile var running = false     // set true by run(), set false by cancel()

    if(socket!=null) {
      connectedBluetoothDevice = socket.getRemoteDevice()
      if(connectedBluetoothDevice!=null) {
        connectedBtAddr = connectedBluetoothDevice.getAddress()
        connectedBtName = connectedBluetoothDevice.getName()
      }

      try {
        // Get the BluetoothSocket input and output streams
        mmInStream = socket.getInputStream()
        codedInputStream = CodedInputStream.newInstance(mmInStream)
        mmOutStream = socket.getOutputStream()
        //codedOutputStream =  CodedOutputStream.newInstance(mmOutStream)
        // todo: start fifo queue delivery via codedOutputStream
        mConnectedSendThread = new ConnectedSendThread(sendQueue,CodedOutputStream.newInstance(mmOutStream),socket)
        mConnectedSendThread.start()

      } catch {
        case e: IOException =>
          Log.e(TAG, "ConnectedThread start temp sockets not created", e)
      }
    }

    private def splitString(line:String, delim:List[String]) :List[String] = delim match {
      case head :: tail => 
        val listBuffer = new ListBuffer[String]
        //if(D) Log.i(TAG, "ConnectedThread run: splitString line="+line)
        for(addr <- line.split(head).toList) {
          listBuffer += addr
          //if(D) Log.i(TAG, "ConnectedThread run: splitString addr="+addr+" listBuffer.size="+listBuffer.size)
        }
        //if(D) Log.i(TAG, "ConnectedThread run: splitString listBuffer.size="+listBuffer.size)
        return listBuffer.toList
      case Nil => 
        return List(line.trim)
    }

    private def processReceivedRawData(rawdata:Array[Byte]) :Unit = synchronized {
      val btMessage = BtShare.Message.parseFrom(rawdata)
      val cmd = btMessage.getCommand
      val toAddr = btMessage.getToAddr
      val fromAddr = btMessage.getFromAddr
      val fromName = btMessage.getFromName
      val receivedSendMsgCounter = btMessage.getArgCount
      val lastSendMsgCounter = sendMsgCounterMap get fromAddr

      // ignore double delivery
      if(lastSendMsgCounter!=None && receivedSendMsgCounter <= lastSendMsgCounter.get) {
        if(D) Log.i(TAG, "ConnectedThread processReceivedRawData ignore msg cmd="+cmd+" counter="+receivedSendMsgCounter+" <= "+lastSendMsgCounter.get+" fromName="+fromName+" fromAddr="+fromAddr+" double delivery")
        return
      }

      sendMsgCounterMap.put(fromAddr, receivedSendMsgCounter)
      
      // todo: would be good to store a timestamp as "lastDataTime" from fromAddr

      // if fromAddr not listed in directlyConnectedDevicesMap, then add it to indirectlyConnectedDevicesMap
      if(!isDirectlyConnectedDevices(fromAddr)) {
        val previouslyFound = indirectlyConnectedDevicesMap get fromAddr
        indirectlyConnectedDevicesMap += fromAddr -> new IndirectDeviceObject(fromName, System.currentTimeMillis())  // todo: missing info: connected via btAddr
        if(D) Log.i(TAG, "ConnectedThread processReceivedRawData: added indirectlyConnectedDevice fromName="+fromName+" fromAddr="+fromAddr)

        previouslyFound match {
          case None => 
            // trigger a redraw, for when activity is currently in deviceView
            activityMsgHandler.obtainMessage(RFCommMultiplexerService.MESSAGE_REDRAW_DEVICEVIEW, -1, -1, null).sendToTarget()
          case Some(previouslyFoundDevice) => 
        }

        sendMsgCounterMap.put(fromAddr, 0)
      }

      
      // toAddr may be null (data is for everyone) or it can be a comma separated list
      var dataForMe = true
      var numberOfToAddr = 0
      if(toAddr!=null && toAddr.length>0) {
        dataForMe = false
        // check if myBtAddr is part of targetList
        val targetList = splitString(toAddr,List(","))
        //if(D) Log.i(TAG, "ConnectedThread processReceivedRawData: targetList.size="+targetList.size)
        //targetList.foreach(addr => if(D) Log.i(TAG, "ConnectedThread run: foreach "+addr+" contained="+myBtAddr.contains(addr)) )
        targetList.foreach(addr => if(myBtAddr.contains(addr)) {
          dataForMe = true
          numberOfToAddr = targetList.size
        })
      }

      val arg1 = btMessage.getArg1
      val toName = btMessage.getToName

      if(dataForMe && numberOfToAddr==1) {
        // ONLY for me: don't forward
        //if(D) Log.i(TAG, "ConnectedThread run - only for me, don't forward")
      } else {
        // NOT "only for me": forward obtained message to all devices in connectedDevicesSet except to fromAddr
        // so that ALL data is ALWAYS received IDENTICALLY by ALL clients 
        directlyConnectedDevicesMap.foreach { case (remoteDevice, connectedThread) => 
          if(!remoteDevice.getAddress().equals(connectedBtAddr)) {    // prevent sending broadcasted data back to where it came from
            if(D) Log.i(TAG, "ConnectedThread forward cmd="+cmd+" from="+fromAddr+" to="+remoteDevice.getAddress())
            connectedThread.writeBtShareMessage(btMessage)
          }
        }
      }

      if(!dataForMe) {
        // NOT for me: don't process
        if(D) Log.i(TAG, "ConnectedThread processReceivedRawData: NOT for me="+myBtAddr+", don't process - toAddr="+toAddr)

      } else {
        // for me OR for all: do process
        if(D) Log.i(TAG, "ConnectedThread processReceivedRawData: read1 cmd="+cmd+" fromName="+fromName+" fromAddr="+fromAddr+" toAddr="+toAddr+" receivedSendMsgCounter="+receivedSendMsgCounter)

        // plug-in app-specific behaviour
        if(!processBtMessage(cmd, arg1, fromAddr, btMessage){
          () =>
          // this closure is used as readCodedInputStream() from within subclassed clients
          if(D) Log.i(TAG, "ConnectedThread processReceivedRawData closure processBtMessage ...")
          var size = codedInputStream.readInt32 // may block
          if(D) Log.i(TAG, "ConnectedThread processReceivedRawData closure codedInputStream first size="+size)
          var rawdata:Array[Byte] = null
          if(size>0 /*&& running*/) {      // todo: must implement running-check
            if(D) Log.i(TAG, "ConnectedThread processReceivedRawData closure wait for "+size+" bytes data ...")
            rawdata = codedInputStream.readRawBytes(size)     // may be aborted by call to cancel
          }          

          // forward data, if "NOT only for me", to other directly connected devices
          if(dataForMe && numberOfToAddr==1) {
            // ONLY for me: don't forward
            //if(D) Log.i(TAG, "ConnectedThread run - only for me, don't forward")
          } else {
            // NOT "only for me": forward obtained message to all devices in connectedDevicesSet except to fromAddr
            // so that ALL data is ALWAYS received IDENTICALLY by ALL clients 
            directlyConnectedDevicesMap.foreach { case (remoteDevice, connectedThread) => 
              if(!remoteDevice.getAddress().equals(connectedBtAddr)) {    // prevent sending broadcasted data back to where it came from
                if(D) Log.i(TAG, "ConnectedThread processReceivedRawData closure forward cmd="+cmd+" from="+fromAddr+" to="+remoteDevice.getAddress())
                connectedThread.writeData(size, rawdata)
              }
            }
          }

          if(size>0 /*&& running*/) {      // todo: must implement running-check
            if(D) Log.i(TAG, "ConnectedThread processReceivedRawData closure return rawdata="+rawdata)
            rawdata

          } else {
            if(D) Log.i(TAG, "ConnectedThread processReceivedRawData closure return null")
            null
          }
        }) {
          // basic behaviour: ping, pong + strmsg
          if(D) Log.i(TAG, "ConnectedThread run: basic behaviour arg1="+arg1+" toName="+toName)

          if(cmd.equals("ping")) {

            var thisSendMsgCounter:Long = 0
            synchronized { 
              //sendMsgCounter+=1
              val nowMs = SystemClock.uptimeMillis()
              if(sendMsgCounter>=nowMs) 
                sendMsgCounter+=1
              else
                sendMsgCounter=nowMs
              thisSendMsgCounter = sendMsgCounter
            }
            writeCmdMsg("pong", btMessage.getArg1, fromAddr, thisSendMsgCounter)

          } else if(cmd.equals("pong")) {
            val sendMs = new java.lang.Integer(arg1).intValue()
            val nowMs = SystemClock.uptimeMillis()
            val diffMs = nowMs - sendMs
            activityMsgHandler.obtainMessage(RFCommMultiplexerService.RECEIVED_PONG, -1, -1, fromAddr+","+diffMs).sendToTarget()

          } else if(cmd.equals("disconnect")) {
            // note: this can be wrong info, if the "disconencted" device is still connected via another device
            val disconnectDeviceAddr = arg1  // btAddr of device that disconnected
            if(D) Log.i(TAG, "ConnectedThread run: disconnect disconnectDeviceAddr="+disconnectDeviceAddr+" ############################")
            if(disconnectDeviceAddr.equals(myBtAddr)) {
              // another device has bt-broadcasted that this device has disconnected, but we are still alive
              // bt-broadcast a "pong" so that everyone know we are still 
              if(D) Log.i(TAG, "ConnectedThread run: disconnect disconnectDeviceAddr="+disconnectDeviceAddr+" THIS IS ME, tell otheres I'm still here ############################")
              var thisSendMsgCounter:Long = 0
              synchronized { 
                sendMsgCounter+=1
                thisSendMsgCounter = sendMsgCounter
              }
              writeCmdMsg("pong", null, null, thisSendMsgCounter)

            } else {
              // remove disconnectDeviceAddr from our indirectlyConnectedDevicesMap
              if(D) Log.i(TAG, "ConnectedThread run: disconnect remove disconnectDeviceAddr="+disconnectDeviceAddr+" from our indirectlyConnectedDevicesMap ############################")
              val found = indirectlyConnectedDevicesMap get disconnectDeviceAddr
              found match {
                case None => 
                case Some(foundDevice) => 
                  indirectlyConnectedDevicesMap -= disconnectDeviceAddr
                  // tell the activity, in case it sits in deviceView
                  activityMsgHandler.obtainMessage(RFCommMultiplexerService.MESSAGE_REDRAW_DEVICEVIEW, -1, -1, null).sendToTarget()
              }
              sendMsgCounterMap.put(disconnectDeviceAddr, 0)
            } 

          } else if(cmd.equals("strmsg")) {
            // todo: classcast exception if something is fishy with arg1 ?
            if(D) Log.i(TAG, "ConnectedThread run: strmsg arg1="+arg1+" toName="+toName)
            //val strmsg = fromName+": "+arg1
            //activityMsgHandler.obtainMessage(RFCommMultiplexerService.MESSAGE_READ, -1, -1, strmsg).sendToTarget()
            // issue fixed: when the device sleeps (or when the activity is unloaded, say, while in the background), MESSAGE_READ WILL NOT ARRIVE
            // so we queue strmsg's and use obtainMessage().sendToTarget() only to notify the activity

            queueMessageLinkedList synchronized {
              queueMessageLinkedList.add(new QueueMessage(System.currentTimeMillis(), fromAddr, fromName, arg1))
              checkQueueMaxSize()
            }
            if(D) Log.i(TAG, "ConnectedThread run cmd=strmsg added queueMessageLinkedList.size()="+queueMessageLinkedList.size())

            // the activity will fetch queued msgs immediately, or whenever it is started or wakes up from sleep
            activityMsgHandler.obtainMessage(RFCommMultiplexerService.MESSAGE_READ, -1, -1, null).sendToTarget()

          } else {
            if(D) Log.i(TAG, "ConnectedThread run - received unknown cmd="+cmd)
            // todo: must make it possible for the activity to make user aware of this "unknown type" message
          }
        }
      }
    }

    override def run() {
      if(D) Log.i(TAG, "ConnectedThread run " + socketType)
      try {
        // while connected, keep listening to the InputStream
        running = true
        while(running) {
          //if(D) Log.i(TAG, "ConnectedThread run " + socketType+" read size...")
          val size = codedInputStream.readInt32() // may block a long while
          if(D) Log.i(TAG, "ConnectedThread run " + socketType+" read size="+size+" socket="+socket+" running="+running)
          if(running && size>0) {
            val rawdata = codedInputStream.readRawBytes(size) // since we know the size of data to expect, this should not block
            if(running)
              processReceivedRawData(rawdata)
          }
        }
      } catch {
        case e: IOException =>
          if(D) Log.i(TAG, "ConnectedThread run disconnected ("+connectedBtAddr+" "+connectedBtName+") "+e)
          connectionLost(socket)
      }
      if(D) Log.i(TAG, "ConnectedThread run " + socketType+ " DONE")
    }

    def writeBtShareMessage(btMessage:BtShare.Message) :Unit = {
      if(D) Log.i(TAG, "ConnectedThread writeBtShareMessage btMessage="+btMessage)
      if(btMessage==null) return

      // todo: fifo queue btMessage - and actually send it from somewhere else
      sendQueue += btMessage
    }

    /**
     * Write a command with an arg to the connected OutStream.
     * @param message  The string to write
     */
    def writeCmdMsg(cmd:String, message:String, toAddr:String, sendMsgCounter:Long) = synchronized {
      if(D) Log.i(TAG, "writeCmdMsg cmd="+cmd+" message="+message+" socket="+socket) //+" toAddr="+toAddr+" myBtName="+myBtName+" myBtAddr="+myBtAddr)
      val btBuilder = BtShare.Message.newBuilder()
                                     .setArgCount(sendMsgCounter)
                                     .setFromName(myBtName)
                                     .setFromAddr(myBtAddr)
      if(message!=null)
        btBuilder.setArg1(message)     

      if(cmd!=null)
        btBuilder.setCommand(cmd)
      else
        btBuilder.setCommand("strmsg")

      if(toAddr!=null)
        btBuilder.setToAddr(toAddr)

      writeBtShareMessage(btBuilder.build())
    }

    def writeData(size:Int, data:Array[Byte]) {
      if(D) Log.i(TAG, "ConnectedThread writeData size="+size)
      // queue some part of the Array
      // take care of "out of memory" issues
      var sendData = new Array[Byte](size)
      if(size>0) {
        while(sendData==null) {
          try {
            sendData = new Array[Byte](size)
          } catch {
            case e: java.lang.OutOfMemoryError =>
              if(D) Log.i(TAG, "ConnectedThread writeData OutOfMemoryError - force System.gc() ######################################################")
              System.gc()
              try { Thread.sleep(2000); } catch { case ex:Exception => }
              System.gc()
              if(D) Log.i(TAG, "ConnectedThread writeData OutOfMemoryError - continue ######################################################")
          }
        }
        Array.copy(data,0,sendData,0,size)
      }
      sendQueue += sendData
    }

    def cancel() {
      // called by disconnect() and stop()
      if(mmInStream != null) {
        try {mmInStream.close() } catch { case e: Exception => }
        mmInStream = null
      }

      if(mmOutStream != null) {
        try {mmOutStream.close() } catch { case e: Exception => }
        mmOutStream = null
      }

      codedInputStream = null
      mConnectedSendThread.halt()

      if(socket != null) {
        try { socket.close() } catch { case e: Exception => Log.e(TAG, "cancel() close() of connect socket failed", e) }
      }
      running = false
    }
  }


  class ConnectedSendThread(sendQueue:scala.collection.mutable.Queue[Any], var codedOutputStream:CodedOutputStream, socket:BluetoothSocket) extends Thread {
    if(D) Log.i(TAG, "ConnectedSendThread start")
    var running = false
    var totalSend = 0
    var blobId:Long = 0
    var contentLength:Long = 0
    var progressLastStep:Long = 0
    
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE).asInstanceOf[ActivityManager]
    val memoryInfo = new ActivityManager.MemoryInfo()

    override def run() {
      if(D) Log.i(TAG, "ConnectedSendThread run ")
      try {
        while(codedOutputStream!=null && sendQueue!=null) {
          if(sendQueue.size>0) {
            val obj = sendQueue.dequeue
            if(obj.isInstanceOf[BtShare.Message]) {
              if(D) Log.i(TAG, "ConnectedSendThread run BtShare.Message")
              val btShareMessage = obj.asInstanceOf[BtShare.Message]
              writeBtShareMessage(btShareMessage)
              // a new blob delivery is starting...
              totalSend = 0
              blobId = btShareMessage.getId
              contentLength = btShareMessage.getDataLength
              progressLastStep = 0
            } else {
              val data = obj.asInstanceOf[Array[Byte]]

              activityManager.getMemoryInfo(memoryInfo)
              if(D) Log.i(TAG, "ConnectedSendThread run size="+data.size+" totalSend="+totalSend+" progressLastStep+contentLength/5="+(progressLastStep+contentLength/5)+" contentLength="+contentLength +" ######################")

              writeData(data.size, data)
              // a new blob delivery is in progress... (if data.size==0 then this is the end of this blob delivery)
              totalSend += data.size
              
              // todo: if data.size == 0, message back "blobId finished" to activity
              // todo: else if contentLength > 0, message back "percentage progress" to activity
              if(data.size == 0) {
                val msg = activityMsgHandler.obtainMessage(RFCommMultiplexerService.MESSAGE_DELIVER_PROGRESS)
                val bundle = new Bundle()
                bundle.putLong(RFCommMultiplexerService.DELIVER_ID, blobId)
                bundle.putInt(RFCommMultiplexerService.DELIVER_PROGRESS, 100)
                msg.setData(bundle)
                activityMsgHandler.sendMessage(msg)
              } 

              else
              if(contentLength>0 && totalSend>progressLastStep+contentLength/20) {
                progressLastStep += contentLength/20  // 5% steps seems ideal for progress bar

                val msg = activityMsgHandler.obtainMessage(RFCommMultiplexerService.MESSAGE_DELIVER_PROGRESS)
                val bundle = new Bundle()
                bundle.putLong(RFCommMultiplexerService.DELIVER_ID, blobId)
                bundle.putInt(RFCommMultiplexerService.DELIVER_PROGRESS, (progressLastStep/(contentLength/100)).asInstanceOf[Int] )
                msg.setData(bundle)
                activityMsgHandler.sendMessage(msg)
              }
            }
          } else {
            try { Thread.sleep(200); } catch { case ex:Exception => }
          }
        }
      } catch {
        case e: IOException =>
          if(D) Log.i(TAG, "ConnectedSendThread socket="+socket+" run ex="+e)
          connectionLost(socket)
      }
      if(D) Log.i(TAG, "ConnectedSendThread run DONE ###################################################")
    }

    def halt() {
      codedOutputStream=null
    }

    private def writeBtShareMessage(btMessage:BtShare.Message) :Unit = synchronized {
      if(D) Log.i(TAG, "ConnectedSendThread writeBtShareMessage btMessage="+btMessage+" socket="+socket)
      if(btMessage==null) return

      try {
        val size = btMessage.getSerializedSize
        //if(D) Log.i(TAG, "ConnectedSendThread writeBtShareMessage size="+size)
        if(size>0) {
          if(codedOutputStream!=null)
            if(codedOutputStream!=null)
              codedOutputStream.writeInt32NoTag(size)
            if(codedOutputStream!=null)
              btMessage.writeTo(codedOutputStream)
            if(codedOutputStream!=null)
              codedOutputStream.flush()
          if(D) Log.i(TAG, "ConnectedSendThread writeBtShareMessage flushed size="+size+" codedOutputStream="+codedOutputStream)
        }
      } catch {
        case ex: IOException =>
          Log.e(TAG, "ConnectedSendThread writeBtShareMessage socket="+socket+" ioexception", ex)
          sendToast("ConnectedSendThread write exception "+ex.getMessage())
          // we receive: "java.io.IOException: Connection reset by peer"
          // or:         "java.io.IOException: Transport endpoint is not connected"
          halt()
          connectionLost(socket)
      }
    }

    private def writeData(size:Int, data:Array[Byte]) = synchronized {
      //if(D) Log.i(TAG, "ConnectedSendThread writeData size="+size+" socket="+socket)

      try {
        codedOutputStream.writeInt32NoTag(size)
        if(size>0)
          codedOutputStream.writeRawBytes(data,0,size)
        codedOutputStream.flush()
      } catch {
        case e: IOException =>
          halt()
          connectionLost(socket)
          Log.e(TAG, "ConnectedSendThread writeData socket="+socket+" exception=", e)
          sendToast("ConnectedSendThread writeData "+e.getMessage())
      }
    }
  }
}


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

// RFCommMultiplexerService is an Android app service written in Scala 2.8.x

package org.timur.btshare

import scala.collection.mutable.HashMap

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.LinkedList
import java.util.ArrayList

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
  val STATE_NONE = 0       // doing nothing
  val STATE_LISTEN = 1     // not yet connected but listening for incoming connections
  val STATE_CONNECTED = 3  // connected to at least one remote device

  // Message types sent from the RFCommMultiplexerService Handler
  val MESSAGE_STATE_CHANGE = 1
  val MESSAGE_READ = 2
  val MESSAGE_WRITE = 3
  val MESSAGE_DEVICE_NAME = 4
  val MESSAGE_TOAST = 5
  val RECEIVED_PONG = 6
  val DEVICE_DISCONNECT = 7
  val CONNECTION_FAILED = 8
  val CONNECTION_START = 9

  // Key names received from the RFCommMultiplexerService Handler
  val DEVICE_NAME = "device_name"
  val DEVICE_ADDR = "device_addr"
  val SOCKET_TYPE = "socket_type"
  val TOAST = "toast"
} 

class QueueMessage(createTimeMs:Long=0l, deviceAddr:String=null, deviceName:String=null, strmsg:String=null) {
  def createTimeMs() :Long = { return createTimeMs }
  def deviceAddr() :String = { return deviceAddr }
  def deviceName() :String = { return deviceName }
  def strmsg() :String = { return strmsg }
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
  private val mAdapter = BluetoothAdapter.getDefaultAdapter()
  protected val myBtName = mAdapter.getName()
  protected val myBtAddr = mAdapter.getAddress()
  
  protected var context:Context = null
  protected var activityMsgHandler:Handler = null

  private val queueMessageLinkedList = new LinkedList[QueueMessage]()

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
    // a simple android.os.Handler, defined in the activity
    this.activityMsgHandler = activityMsgHandler
  }

  @volatile protected var sendMsgCounter = 0

  @volatile private var mSecureAcceptThread: AcceptThread = null
  @volatile private var mInsecureAcceptThread: AcceptThread = null
  @volatile private var mConnectThread: ConnectThread = null
  @volatile private var mConnectedThread: ConnectedThread = null
  @volatile private var mState = RFCommMultiplexerService.STATE_NONE

  // connectedDevicesMap contains all directly connected devices mapped to their connectedThread objects
  val connectedDevicesMap = new HashMap[BluetoothDevice,ConnectedThread]

  // sendMsgCounterMap keeps track of the msg-counters for all known devices
  // this makes it possible to ignore messages that are received multiple times
  val sendMsgCounterMap = new HashMap[String,Int] // BluetoothDeviceAddrString,counter

  class LocalBinder extends android.os.Binder {
    def getService = RFCommMultiplexerService.this
  }

  val localBinder = new LocalBinder
  override def onBind(intent:Intent) :IBinder = localBinder 

  def processBtMessage(cmd:String, arg1:String, fromAddr:String, btMessage:BtShare.Message, codedInputStream:CodedInputStream): Boolean = {
    // this method may be overloaded to implement app specific protocol extensions
    return false // return false if msg was not processed
  }

  def getState() :Int = synchronized {
    return mState
  }

  def getConnectedDevicesMap() :HashMap[BluetoothDevice,ConnectedThread] = synchronized {
    return connectedDevicesMap
  }

  def isConnectedDevices(newRemoteDeviceAddr: String) :Boolean  = synchronized {
    connectedDevicesMap.foreach { case (remoteDevice, connectedThread) => 
      if(connectedThread!=null)
        if(newRemoteDeviceAddr.equals(remoteDevice.getAddress()))
          return true
    }
    return false
  }

  // called by Activity onResume() 
  // but only while getState() == STATE_NONE
  // this is why we quickly switch state to STATE_LISTEN
  def start() = synchronized {
    if(D) Log.i(TAG, "start: android.os.Build.VERSION.SDK_INT="+android.os.Build.VERSION.SDK_INT)

    setState(RFCommMultiplexerService.STATE_LISTEN)   // will send MESSAGE_STATE_CHANGE

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

  // called by the activity: options menu "connect" -> onActivityResult() -> connectDevice()
  // called by the activity: as a result of NfcAdapter.ACTION_NDEF_DISCOVERED
  def connect(newRemoteDevice:BluetoothDevice, secure:Boolean, complainFail:Boolean=true) :Unit = synchronized {
    if(newRemoteDevice==null) {
      if(D) Log.i(TAG, "connect() newRemoteDevice==null, give up")
      return
    }

    if(D) Log.i(TAG, "connect to: "+newRemoteDevice.getAddress()+" name="+newRemoteDevice.getName())

    // if newRemoteDevice is already listed in connectedDevicesMap, then we do nothing
    if(isConnectedDevices(newRemoteDevice.getAddress())) {
      if(D) Log.i(TAG, "connect() newRemoteDevice is already directly connected, give up")
      return
    }

    if(complainFail) {
      // todo: "complainFail" is actually used as "report_connect/disconnect-actions_via_activityMsgHandler"
      val msg = activityMsgHandler.obtainMessage(RFCommMultiplexerService.CONNECTION_START)
      val bundle = new Bundle()
      bundle.putString(RFCommMultiplexerService.DEVICE_ADDR, newRemoteDevice.getAddress())
      bundle.putString(RFCommMultiplexerService.DEVICE_NAME, newRemoteDevice.getName())
      msg.setData(bundle)
      activityMsgHandler.sendMessage(msg)
    }
    
    // Start the thread to connect with the given device
    mConnectThread = new ConnectThread(newRemoteDevice, secure, complainFail)
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

  def send(cmd:String, message:String, toAddr:String) = synchronized {  // the idea with synchronized is that no other send() shall take over (will interrupt) an ongoing send()
    var thisSendMsgCounter = 0
    synchronized { 
      sendMsgCounter+=1
      thisSendMsgCounter = sendMsgCounter
    }
    val myCmd = if(cmd==null) cmd else "strmsg"
    if(D) Log.i(TAG, "send myCmd="+myCmd+" message="+message+" toAddr="+toAddr+" sendMsgCounter="+thisSendMsgCounter)
    connectedDevicesMap.foreach { case (remoteDevice, connectedThread) => 
      if(connectedThread!=null) {
        //if(D) Log.i(TAG, "send2 myCmd="+myCmd+" message="+message+" toAddr='"+toAddr+"' remoteDevice='"+remoteDevice.getAddress()+"'")
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
    if(D) Log.i(TAG, "ping toAddr="+toAddr)
    val nowMs = SystemClock.uptimeMillis()
    var thisSendMsgCounter = 0
    synchronized { 
      sendMsgCounter+=1
      thisSendMsgCounter = sendMsgCounter
    }
    connectedDevicesMap.foreach { case (remoteDevice, connectedThread) => 
      if(connectedThread!=null)
        connectedThread.writeCmdMsg("ping",""+nowMs,toAddr,thisSendMsgCounter)
    }
  }

  def sendData(size:Int, data: Array[Byte], toAddr:String) {
    //if(D) Log.i(TAG, "sendData size="+size+" toAddr="+toAddr)
    connectedDevicesMap.foreach { case (remoteDevice, connectedThread) => 
      if(connectedThread!=null)
        try {
          connectedThread.writeData(size, data)
        } catch {
          case e: IOException =>
            Log.e(TAG, "sendData exception during write", e)
            //sendToast("write exception "+e.getMessage())
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

  private def setState(state: Int) = synchronized {
    if(D) Log.i(TAG, "setState() " + mState + " -> " + state)
    mState = state
    // Give the new state to the Handler so the UI Activity can update
    activityMsgHandler.obtainMessage(RFCommMultiplexerService.MESSAGE_STATE_CHANGE, state, -1).sendToTarget()
  }
  
  private def checkQueueMaxSize() {
    while(queueMessageLinkedList.size()>30)       // todo: this is a bit arbitrary
      queueMessageLinkedList.removeFirst()
  }

  // called by: AcceptThread() -> socket = mmServerSocket.accept()
  // called by: activity options menu / NFC -> connect() -> ConnectThread()
  private def connected(socket: BluetoothSocket, remoteDevice: BluetoothDevice, socketType: String) = synchronized {
    if(D) Log.i(TAG, "connected, sockettype="+socketType+" remoteDevice="+remoteDevice)

    if(remoteDevice!=null)
    {
      // Start the thread to manage the connection and perform transmissions
      if(D) Log.i(TAG, "connected, Start ConnectedThread to manage the connection")
      mConnectedThread = new ConnectedThread(socket, socketType)
      mConnectedThread.start()

      val btAddrString = remoteDevice.getAddress()
      val btNameString = remoteDevice.getName()

      // add remoteDevice to list of connected devices
      connectedDevicesMap += remoteDevice -> mConnectedThread

      // reset sendMsgCounter
      sendMsgCounterMap.put(btAddrString, 0)

      queueMessageLinkedList synchronized {
        queueMessageLinkedList.add(new QueueMessage(System.currentTimeMillis(), btAddrString, btNameString, "[connected]"))
        checkQueueMaxSize()
      }

      // Send the name of the connected device back to the UI Activity
      val msg = activityMsgHandler.obtainMessage(RFCommMultiplexerService.MESSAGE_DEVICE_NAME)
      val bundle = new Bundle()
      bundle.putString(RFCommMultiplexerService.DEVICE_NAME, btNameString)
      bundle.putString(RFCommMultiplexerService.DEVICE_ADDR, btAddrString)
      bundle.putString(RFCommMultiplexerService.SOCKET_TYPE, socketType)
      msg.setData(bundle)
      activityMsgHandler.sendMessage(msg)

      setState(RFCommMultiplexerService.STATE_CONNECTED)    // will send MESSAGE_STATE_CHANGE
    }
    //if(D) Log.i(TAG, "connected, done")
  }

  // called by ConnectedThread() IOException on send()
  private def connectionLost(socket: BluetoothSocket) {
    if(D) Log.i(TAG, "connectionLost socket="+socket)

    // Send a failure toast back to the Activity
    var remoteDevice:BluetoothDevice = null
    //var btNameString:String = null
    if(socket!=null) {
      remoteDevice = socket.getRemoteDevice()
      if(remoteDevice!=null) {
        val btAddrString = remoteDevice.getAddress()
        val btNameString = remoteDevice.getName()
        connectedDevicesMap -= remoteDevice

        queueMessageLinkedList synchronized {
          queueMessageLinkedList.add(new QueueMessage(System.currentTimeMillis(), btAddrString, btNameString, "[disconnected]"))
          checkQueueMaxSize()
        }

        sendMsgCounterMap.remove(btAddrString)

        // tell the activity that the connection was lost
        val msg = activityMsgHandler.obtainMessage(RFCommMultiplexerService.DEVICE_DISCONNECT)
        val bundle = new Bundle()
        bundle.putString(RFCommMultiplexerService.DEVICE_ADDR, btAddrString)
        bundle.putString(RFCommMultiplexerService.DEVICE_NAME, btNameString)
        msg.setData(bundle)
        activityMsgHandler.sendMessage(msg)

        if(D) Log.i(TAG, "ConnectedThread run: strmsg added queueMessageLinkedList.size()="+queueMessageLinkedList.size())
      }
    }

    if(connectedDevicesMap.size>0) {
      //sendToast(btNameString+" connection was lost")
    } else { 
      //sendToast(btNameString+" connection was lost - now fully disconnected")
      setState(RFCommMultiplexerService.STATE_LISTEN)   // will send MESSAGE_STATE_CHANGE
    }
  }

  private class AcceptThread(secure: Boolean) extends Thread {
    if(D) Log.i(TAG, "AcceptThread")
    // The local server socket
    private var mSocketType: String = if(secure) "Secure" else "Insecure"
    private var mmServerSocket: BluetoothServerSocket = null
    openListenSocket()

    def openListenSocket() {
      mmServerSocket = null
      // Create a new listening server socket
      try {
        if (secure) {
          mmServerSocket = mAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE, MY_UUID_SECURE)
        } else {
          try {
            mmServerSocket = mAdapter.listenUsingInsecureRfcommWithServiceRecord(NAME_INSECURE, MY_UUID_INSECURE)
          } catch {
            case nsmerr: java.lang.NoSuchMethodError =>
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

        // If a connection was accepted
        if(socket != null) {
          RFCommMultiplexerService.this synchronized {
            // Start the connected thread
            connected(socket, socket.getRemoteDevice(), mSocketType)

            // open listen socket for next connection
            //if(mmServerSocket!=null) // might be nulled by cancel()
            //  openListenSocket()
          }
        }
        
        // prevent tight loop
        try { Thread.sleep(200); } catch { case ex:Exception => }
      }
      if(D) Log.i(TAG, "END mAcceptThread, socket Type: " + mSocketType)
    }

    def cancel() { // called by disconnect() or stop()
      if(D) Log.i(TAG, "Socket Type " + mSocketType + " cancel ")
      if(mmServerSocket!=null) {
        try {
          mmServerSocket.close()
          mmServerSocket=null
        } catch {
          case e: IOException =>
            Log.e(TAG, "Socket Type " + mSocketType + " close() of server failed", e)
        }
      }
    }
  }

  private class ConnectThread(remoteDevice: BluetoothDevice, secure: Boolean, complainFail:Boolean=true) extends Thread {
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
      //if(D) Log.i(TAG, "BEGIN mConnectThread SocketType:" + mSocketType)
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
          }
          if(complainFail) {
            // need to tell the activity that the connection has failed - and that the connect-animation/busy-image can be disabled/made invisible
            val msg = activityMsgHandler.obtainMessage(RFCommMultiplexerService.CONNECTION_FAILED)
            val bundle = new Bundle()
            bundle.putString(RFCommMultiplexerService.DEVICE_ADDR, remoteDevice.getAddress())
            bundle.putString(RFCommMultiplexerService.DEVICE_NAME, remoteDevice.getName())
            msg.setData(bundle)
            activityMsgHandler.sendMessage(msg)
          }
          return
      }

      // Reset the ConnectThread because we're done
      RFCommMultiplexerService.this synchronized {
        mConnectThread = null
      }

      // Start the connected thread
      connected(mmSocket, remoteDevice, mSocketType)
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

  class ConnectedThread(socket: BluetoothSocket, socketType: String) extends Thread {
    if(D) Log.i(TAG, "ConnectedThread start " + socketType)
    private var mmInStream: InputStream = null
    private var codedInputStream: CodedInputStream = null
    private var mmOutStream: OutputStream = null
    private var codedOutputStream: CodedOutputStream = null

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
        codedOutputStream =  CodedOutputStream.newInstance(mmOutStream)
      } catch {
        case e: IOException =>
          Log.e(TAG, "ConnectedThread start temp sockets not created", e)
      }
    }

    private def processReceivedRawData(rawdata:Array[Byte]) :Unit = synchronized {
      val btMessage = BtShare.Message.parseFrom(rawdata)
      val cmd = btMessage.getCommand()
      val toAddr = btMessage.getToAddr()
      val fromAddr = btMessage.getFromAddr()
      val fromName = btMessage.getFromName()
      val receivedSendMsgCounter = btMessage.getArgCount()
      val lastSendMsgCounter = sendMsgCounterMap get fromAddr

      if(lastSendMsgCounter!=None && receivedSendMsgCounter <= lastSendMsgCounter.get) {
        if(D) Log.i(TAG, "ConnectedThread run ignore msg cmd="+cmd+" counter="+receivedSendMsgCounter+" <= "+lastSendMsgCounter.get+" fromName="+fromName+" fromAddr="+fromAddr+" double delivery")
        return
      }

      sendMsgCounterMap.put(fromAddr, receivedSendMsgCounter)
      
      if(toAddr!=null && toAddr.length>0 && !toAddr.equals(myBtAddr)) {
        // NOT for me: don't process
        if(D) Log.i(TAG, "ConnectedThread run: not for me, don't process - toAddr="+toAddr)

      } else {
        // for me OR for all: do process
        if(D) Log.i(TAG, "ConnectedThread run: read1 cmd="+cmd+" fromName="+fromName+" fromAddr="+fromAddr+" toAddr="+toAddr+" receivedSendMsgCounter="+receivedSendMsgCounter)

        val toName = btMessage.getToName()
        val arg1 = btMessage.getArg1()  // the text message
        //if(D) Log.i(TAG, "ConnectedThread run: read arg1="+arg1+" toName="+toName)

        // plug-in app-specific behaviour
        if(!processBtMessage(cmd, arg1, fromAddr, btMessage, codedInputStream)) {

          // basic behaviour: ping, pong + strmsg
          if(D) Log.i(TAG, "ConnectedThread run: basic behaviour arg1="+arg1+" toName="+toName)

          if(cmd.equals("ping")) {
            var thisSendMsgCounter = 0
            synchronized { 
              sendMsgCounter+=1
              thisSendMsgCounter = sendMsgCounter
            }
            writeCmdMsg("pong", btMessage.getArg1(), fromAddr, thisSendMsgCounter)

          } else if(cmd.equals("pong")) {
            val sendMs = new java.lang.Integer(arg1).intValue()
            val nowMs = SystemClock.uptimeMillis()
            val diffMs = nowMs - sendMs
            activityMsgHandler.obtainMessage(RFCommMultiplexerService.RECEIVED_PONG, -1, -1, fromAddr+","+diffMs).sendToTarget()

          } else if(cmd.equals("strmsg")) {
            // todo: classcast exception if somethngs fishy with arg1 ?
            if(D) Log.i(TAG, "ConnectedThread run: strmsg arg1="+arg1+" toName="+toName)
            //val strmsg = fromName+": "+arg1
            //activityMsgHandler.obtainMessage(RFCommMultiplexerService.MESSAGE_READ, -1, -1, strmsg).sendToTarget()
            // issue fixed: when the device sleeps (or when the activity is unloaded, say, while in the background), MESSAGE_READ WILL NOT ARRIVE
            // so we queue strmsg's and use obtainMessage().sendToTarget() only to notify the activity

            val msg = new QueueMessage(System.currentTimeMillis(), fromAddr, fromName, arg1)
            queueMessageLinkedList synchronized {
              queueMessageLinkedList.add(msg)
              checkQueueMaxSize()
            }
            if(D) Log.i(TAG, "ConnectedThread run: strmsg added queueMessageLinkedList.size()="+queueMessageLinkedList.size())

            // the activity will fetch queued msgs immediately, or whenever it is started or wakes up from sleep
            activityMsgHandler.obtainMessage(RFCommMultiplexerService.MESSAGE_READ, -1, -1, null).sendToTarget()

          } else {
            if(D) Log.i(TAG, "ConnectedThread run - received unknown cmd="+cmd)
            // todo: must forward "unknown type" message to activity
          }
        }
      }

      if(toAddr!=null && toAddr.length>0 && toAddr.equals(myBtAddr)) {
        // ONLY for me: don't forward
        //if(D) Log.i(TAG, "ConnectedThread run - only for me, don't forward")
      } else {
        // NOT only for me: forward obtained message to all devices in connectedDevicesSet except to fromAddr
        // so that ALL data is ALWAYS received IDENTICALLY by ALL clients 
        connectedDevicesMap.foreach { case (remoteDevice, connectedThread) => 
          if(!remoteDevice.getAddress().equals(fromAddr) && 
             !remoteDevice.getAddress().equals(connectedBtAddr)) {    // todo: explain ???
            if(D) Log.i(TAG, "ConnectedThread forward "+cmd+" from="+fromAddr+" to="+remoteDevice.getAddress())
            connectedThread.writeBtShareMessage(btMessage)
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
          if(running) {
            //if(D) Log.i(TAG, "ConnectedThread run " + socketType+" read size="+size+" socket="+socket)
            if(size>0) {
              val rawdata = codedInputStream.readRawBytes(size) // may block, but only very short
              if(running)
                processReceivedRawData(rawdata)
            }
          }
        }
      } catch {
        case e: IOException =>
          Log.e(TAG, "ConnectedThread run disconnected ("+connectedBtAddr+" "+connectedBtName+") "+e)
          connectionLost(socket)
      }
      if(D) Log.i(TAG, "ConnectedThread run " + socketType+ " DONE")
    }

    def writeBtShareMessage(btMessage: BtShare.Message) {
      if(btMessage!=null) {
        try {
          val size = btMessage.getSerializedSize()
          if(size>0) {
            if(codedOutputStream!=null)
              codedOutputStream synchronized {
                if(codedOutputStream!=null)
                  codedOutputStream.writeInt32NoTag(size)
                if(codedOutputStream!=null)
                  btMessage.writeTo(codedOutputStream)
                if(codedOutputStream!=null)
                  codedOutputStream.flush()
              }
            //if(D) Log.i(TAG, "writeBtShareMessage flushed size="+size)
          }
        } catch {
          case e: IOException =>
            Log.e(TAG, "writeBtShareMessage exception=", e)
            sendToast("write exception "+e.getMessage())      // ???
        }
      }
    }

    /**
     * Write a command with an arg to the connected OutStream.
     * @param message  The string to write
     */
    def writeCmdMsg(cmd:String, message:String, toAddr:String, sendMsgCounter:Int) = synchronized {
      //if(D) Log.i(TAG, "writeCmdMsg cmd="+cmd+" message="+message+" toAddr="+toAddr+" myBtName="+myBtName+" myBtAddr="+myBtAddr)
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

      try {
        writeBtShareMessage(btBuilder.build())
      } catch {
        case e: IOException =>
          Log.e(TAG, "writeCmdMsg exception=", e)
          sendToast("write exception "+e.getMessage())      // ???
      }
    }

    def writeData(size:Int, data: Array[Byte]) {
      try {
        codedOutputStream synchronized {
          codedOutputStream.writeInt32NoTag(size)
          codedOutputStream.writeRawBytes(data)
          codedOutputStream.flush()
        }
      } catch {
        case e: IOException =>
          Log.e(TAG, "writeData exception=", e)
          sendToast("writeData "+e.getMessage())
      }
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
      codedOutputStream = null

      if(socket != null) {
        try { socket.close() } catch { case e: Exception => Log.e(TAG, "cancel() close() of connect socket failed", e) }
      }
      running = false
    }
  }
}



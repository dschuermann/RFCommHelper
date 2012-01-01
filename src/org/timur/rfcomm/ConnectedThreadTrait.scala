package org.timur.rfcomm

import java.io.InputStream
import java.io.OutputStream

trait ConnectedThreadTrait extends Thread {

  def init(mmInStream:InputStream, mmOutStream:OutputStream, socketType:String, 
           localDeviceAddr:String, localDeviceName:String, 
           remoteDeviceAddr:String, remoteDeviceName:String, 
           socketCloseFkt:() => Unit)

  def doFirstActor()

  def cancel()

  def isRunning() :Boolean

  def disconnectBackupConnection()

  def updateStreams(setMmInStream:InputStream, setMmOutStream:OutputStream)
}


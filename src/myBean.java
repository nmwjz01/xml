
package com.PowerService.PowerCabinet;

import java.io.*;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Date;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


import com.PowerService.Bean.BeanDeviceInfo;
import com.PowerService.Bean.BeanSensorAlarm;
import com.PowerService.Bean.BeanSensorInfo;
import com.PowerService.DB.DBErrorCode;
import com.PowerService.PowerReport.ReportThread;
import com.PowerService.PowerSerial.SerialError;
import com.PowerService.Proc.ConfigFile;
import com.PowerService.Proc.SensorAlarm.SensorAlarmAdd;
import com.PowerService.Proc.SensorInfo.SensorInfoAdd;
import com.PowerService.Proc.SensorInfo.SensorInfoList;
import com.PowerService.Proc.SensorInfo.SensorInfoMod;


import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

public class CabinetObj
{
	class ModBusMsg
	{
		//定义24个字节的Buff，用于收发数据
		//public byte buffModBusSend[] = { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };
		//public byte buffModBusRead[] = { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };

		public byte buffModBusSend[] = new byte[64];
		public byte buffModBusRead[] = new byte[5000];    //最大空间，用于接收热成像图像

		public int iLength;
	}
	private Socket socketClient        = null;
	private BeanDeviceInfo oDeviceInfo = null;

	//数据库会话对象
	private static SensorInfoList sensorInfoList = new SensorInfoList();
	private static SensorInfoMod  sensorInfoMod  = new SensorInfoMod();
	private static SensorInfoAdd  sensorInfoAdd  = new SensorInfoAdd();
	private static SensorAlarmAdd sensorAlarmAdd = new SensorAlarmAdd();

    private static Logger oLogger = LogManager.getLogger( "PowerService" );

    private int iStateConn = BeanDeviceInfo.Device_State_UnConnect;   //0:表示连接正常；非0表示各种连接失败

    //每次ModBus读取的序列号
    private short iSerial = 1;
    
	//设置设备信息
	public void setDevice( BeanDeviceInfo oDevice )
	{
		//设置设备数据
		oDeviceInfo = oDevice;
	}
	public long GetDeviceID()
	{
		return oDeviceInfo.getID();
	}

    //初始化连接所有MQTT服务器
    public int initDevice()
    {
        try
        {
        	//连接到DB
        	int iResult = sensorInfoList.initDB();
        	oLogger.info( "IN init(). call dataInfoList.initDB() . iResult=" + iResult);

        	iResult = sensorInfoMod.initDB();
        	oLogger.info( "IN init(). call sensorInfoMod.initDB() . iResult=" + iResult);

        	iResult = sensorInfoAdd.initDB();
        	oLogger.info( "IN init(). call sensorInfoAdd.initDB() . iResult=" + iResult);

        	iResult = sensorAlarmAdd.initDB();
        	oLogger.info( "IN init(). call sensorAlarmAdd.initDB() . iResult=" + iResult);
        }
        catch( Exception e )
        {
			oLogger.error( "IN init(). catch error:" );
			e.printStackTrace();
			oLogger.info( "Exit init()." );
			return SerialError.SerialError_Catch;
        }

        String strIP = oDeviceInfo.getIP();
		int strPort  = oDeviceInfo.getPort();

		//连接到环网柜设备
		int iResult = tcpOpen( strIP, strPort, (int)oDeviceInfo.getID() );
		if( 0 != iResult )
		{
			//设置状态为未连接状态
			iStateConn = BeanDeviceInfo.Device_State_UnConnect;

			oLogger.info( "connect to " + strIP + ":" + strPort + " is error" );

			return -1;
		}
		else
		{
			//设置状态为连接状态
			iStateConn = BeanDeviceInfo.Device_State_Connect;

			oLogger.info( "connect to " + strIP + ":" + strPort + " is ok" );

    		return 0;
		}
    }

    //连接设备
    public int tcpOpen( String strIP, int iPort, int iDeviceID )
    {
        try
        {
        	//连接设备
        	socketClient = new Socket(strIP, iPort);
            return 0;
        }
        catch(Exception e)
        {
        	oLogger.info("msg "+ e.getMessage());
        	oLogger.info("loc "+ e.getLocalizedMessage());
        	oLogger.info("cause "+e.getCause());
        	oLogger.info("excep "+e);
            e.printStackTrace();

            return -1;
        }
    }
    //关闭
    public int tcpClose()
    {
    	if( null == socketClient )
    		return -1;

		try
		{
			socketClient.close();
			return 0;
		}
		catch (Exception e)
		{
			//
			e.printStackTrace();
			return -2;
		}
    }
	public int GetConnectState()
	{
		return iStateConn;
	}


    //处理消息
	public void procModBus()
	{
		//1、构造ModBus消息
		//2、发送ModBus消息
		//3、接收ModBus消息
		//4、解析和存储ModBus消息

		ArrayList<BeanSensorInfo> lstBeanSensorInfo = new ArrayList<BeanSensorInfo>();

		//1.1、读取对应设备的所有开关状态传感器列表
		int iResult = getSensorList((int)oDeviceInfo.getID(), BeanSensorInfo.Sensor_Type_Switch, lstBeanSensorInfo);
		if( 0 != iResult )
			oLogger.info("Call getSensorList() for Switch. iResult=" + iResult);
		//1.2、离散寄存器--读取开关状态
		iResult = procSwitch((int)oDeviceInfo.getID(), lstBeanSensorInfo);
		oLogger.info("Call procSwitch(" + oDeviceInfo.getID() + ") for Switch. iResult=" + iResult);
		//1.3、处理完成后，清空List，准备下次使用
		lstBeanSensorInfo.clear();

		//2.1、读取对应设备的所有"线路停电状态"传感器列表
		iResult = getSensorList((int)oDeviceInfo.getID(), BeanSensorInfo.Sensor_Type_LineState, lstBeanSensorInfo);
		if( 0 != iResult )
			oLogger.info("Call getSensorList() for LineState. iResult=" + iResult);
		//2.2、离散寄存器--读取线路停电状态
		iResult = procLineState((int)oDeviceInfo.getID(), lstBeanSensorInfo);
		oLogger.info("Call procLineState(" + oDeviceInfo.getID() + ") for LineState. iResult=" + iResult);
		//2.3、处理完成后，清空List，准备下次使用
		lstBeanSensorInfo.clear();

		//3.1、读取对应设备的所有"烟感传感器状态"传感器列表
		iResult = getSensorList((int)oDeviceInfo.getID(), BeanSensorInfo.Sensor_Type_Smoke, lstBeanSensorInfo);
		if( 0 != iResult )
			oLogger.info("Call getSensorList() for Smoke. iResult=" + iResult);
		//3。2、离散寄存器--烟感传感器
		iResult = procSmokeState((int)oDeviceInfo.getID(), lstBeanSensorInfo);
		oLogger.info("Call procSmokeState(" + oDeviceInfo.getID() + ") for Smoke. iResult=" + iResult);
		//3.3、处理完成后，清空List，准备下次使用
		lstBeanSensorInfo.clear();

		//4.1、读取对应设备的所有"水浸传感器状态"传感器列表
		iResult = getSensorList((int)oDeviceInfo.getID(), BeanSensorInfo.Sensor_Type_Water, lstBeanSensorInfo);
		if( 0 != iResult )
			oLogger.info("Call getSensorList() for Water. iResult=" + iResult);
		//4.2、离散寄存器--水浸传感器
		iResult = procWaterState((int)oDeviceInfo.getID(), lstBeanSensorInfo);
		oLogger.info("Call procWaterState(" + oDeviceInfo.getID() + ") for Water. iResult=" + iResult);
		//4.3、处理完成后，清空List，准备下次使用
		lstBeanSensorInfo.clear();

		//5.1、读取对应设备的所有"门磁传感器状态"传感器列表
		iResult = getSensorList((int)oDeviceInfo.getID(), BeanSensorInfo.Sensor_Type_Door, lstBeanSensorInfo);
		if( 0 != iResult )
			oLogger.info("Call getSensorList() for Door. iResult=" + iResult);
		//5.2、离散寄存器--门磁传感器
		iResult = procDoorState((int)oDeviceInfo.getID(), lstBeanSensorInfo);
		oLogger.info("Call procDoorState(" + oDeviceInfo.getID() + ") for Door. iResult=" + iResult);
		//5.3、处理完成后，清空List，准备下次使用
		lstBeanSensorInfo.clear();

		//==================上面是离散寄存器处理（已完成），下面是保持寄存器===================//
		//6.1、读取对应设备的所有"无源无线测温传感器"传感器列表
		iResult = getSensorList((int)oDeviceInfo.getID(), BeanSensorInfo.Sensor_Type_Temperature, lstBeanSensorInfo);
		if( 0 != iResult )
			oLogger.info("Call getSensorList() for Temperature. iResult=" + iResult);
		//6.2、保持寄存器--无源无线测温传感器
		iResult = procTepmertureData((int)oDeviceInfo.getID(), lstBeanSensorInfo);
		oLogger.info("Call procTepmertureData(" + oDeviceInfo.getID() + ") for Temperature. iResult=" + iResult);
		//6.3、处理完成后，清空List，准备下次使用
		lstBeanSensorInfo.clear();

		//7.1、读取对应设备的所有"母线/支线的电气量数据--电流"传感器列表
		iResult = getSensorList((int)oDeviceInfo.getID(), BeanSensorInfo.Sensor_Type_LineData_Electric, lstBeanSensorInfo);
		if( 0 != iResult )
			oLogger.info("Call getSensorList() for LineDataElectric. iResult=" + iResult);
		//7.2、保持寄存器--线路电器数据--电流
		iResult = procLineDataElectric((int)oDeviceInfo.getID(), lstBeanSensorInfo);
		oLogger.info("Call procLineDataElectric(" + oDeviceInfo.getID() + ") for LineDataElectric. iResult=" + iResult);
		//7.3、处理完成后，清空List，准备下次使用
		lstBeanSensorInfo.clear();

		//8.1、读取对应设备的所有"母线/支线的电气量数据--电压"传感器列表
		iResult = getSensorList((int)oDeviceInfo.getID(), BeanSensorInfo.Sensor_Type_LineData_Voltage, lstBeanSensorInfo);
		if( 0 != iResult )
			oLogger.info("Call getSensorList() for LineDataVoltage. iResult=" + iResult);
		//8.2、保持寄存器--线路电器数据--电压
		iResult = procLineDataVoltage((int)oDeviceInfo.getID(), lstBeanSensorInfo);
		oLogger.info("Call procLineDataVoltage(" + oDeviceInfo.getID() + ") for LineDataVoltage. iResult=" + iResult);
		//8.3、处理完成后，清空List，准备下次使用
		lstBeanSensorInfo.clear();

		//9.1、读取对应设备的所有"温湿度传感器"传感器列表
		iResult = getSensorList((int)oDeviceInfo.getID(), BeanSensorInfo.Sensor_Type_Temperature2, lstBeanSensorInfo);
		if( 0 != iResult )
			oLogger.info("Call getSensorList() for Temperature2. iResult=" + iResult);
		//9.1、保持寄存器--温湿度数据
		iResult = procTepmertureData2((int)oDeviceInfo.getID(), lstBeanSensorInfo);
		oLogger.info("Call procTepmertureData2(" + oDeviceInfo.getID() + ") for Temperature2. iResult=" + iResult);
		//9.3、处理完成后，清空List，准备下次使用
		lstBeanSensorInfo.clear();

		//10.1、读取对应设备的所有"局放传感器"传感器列表
		iResult = getSensorList((int)oDeviceInfo.getID(), BeanSensorInfo.Sensor_Type_Scale, lstBeanSensorInfo);
		if( 0 != iResult )
			oLogger.info("Call getSensorList() for LocalScale. iResult=" + iResult);
		//10.2、保持寄存器--局放传感器
		iResult = procLocalScaleData((int)oDeviceInfo.getID(), lstBeanSensorInfo);
		oLogger.info("Call procLocalScaleData(" + oDeviceInfo.getID() + ") for LocalScale. iResult=" + iResult);
		//10.3、处理完成后，清空List，准备下次使用
		lstBeanSensorInfo.clear();

		//11.1、读取对应设备的所有"热成像传感器"传感器列表
		iResult = getSensorList((int)oDeviceInfo.getID(), BeanSensorInfo.Sensor_Type_HotPic, lstBeanSensorInfo);
		if( 0 != iResult )
			oLogger.info("Call getSensorList() for HotPic. iResult=" + iResult);
		//11.2、保持寄存器--热成像传感器
		iResult = procHotPic((int)oDeviceInfo.getID(), lstBeanSensorInfo);
		oLogger.info("Call procHotPic(" + oDeviceInfo.getID() + ") for HotPic. iResult=" + iResult);
		//11.3、处理完成后，清空List，准备下次使用
		lstBeanSensorInfo.clear();
	}

	//1、离散寄存器--读取开关状态
	private int procSwitch( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo)
	{
    	oLogger.info("Enter procSwitch().lstBeanSensorInfo.size()=" + lstBeanSensorInfo.size());

        try
        {
        	//0、定义发送数据和接收数据
        	ModBusMsg modBusMsg = new ModBusMsg();

        	//1、构造发送数据
        	modBusMsg.buffModBusSend[ 0] = (byte) (iSerial/256);
        	modBusMsg.buffModBusSend[ 1] = (byte) (iSerial%256);    //序列号

        	modBusMsg.buffModBusSend[ 2] = 0;
        	modBusMsg.buffModBusSend[ 3] = 0;    //协议标识，00表示为ModBus-TCP

        	modBusMsg.buffModBusSend[ 4] = 0;
        	modBusMsg.buffModBusSend[ 5] = 6;    //数据长度

        	modBusMsg.buffModBusSend[ 6] = 1;    //单元标识符，设备地址（当前没有实际意义，固定取1）

        	modBusMsg.buffModBusSend[ 7] = 2;    //功能码

        	modBusMsg.buffModBusSend[ 8] = 0x10;
        	modBusMsg.buffModBusSend[ 9] = 0x01;    //寄存器开始地址

        	modBusMsg.buffModBusSend[10] = 0x00;
        	modBusMsg.buffModBusSend[11] = 0x30;    //一次将48路全部取回集中处理  //标配16路，最大48
        	modBusMsg.iLength = 12;

        	//2、发送ModBus数据
        	int iResult = modBusSend(modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on sending modbus" );
        		return iResult;
        	}

        	//3、读取ModBus数据,返回11个字节  //返回举例：00 01 00 00 00 06 01 02 02 a1 c4
         	modBusMsg.iLength = 15;
        	iResult = modBusRecv(modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on getting modbus. iResult=" + iResult );
        		return -1;
        	}

			//序列号++
			if(iSerial>=65535) iSerial = 1; else iSerial ++;

        	//4、存储DB ---将数据写入库
        	iResult  = setDBSwitch(iDeviceID, lstBeanSensorInfo, modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on call setDBSwitch" );
        		return iResult;
        	}
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return -1;
        }
		
    	return 0;
	}

	//2、离散寄存器--读取线路停电状态
	private int procLineState( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo )
	{
        try
        {
        	oLogger.info("Enter procLineState().");

        	//0、定义发送数据和接收数据
        	ModBusMsg modBusMsg = new ModBusMsg();

        	//1、构造发送数据
        	modBusMsg.buffModBusSend[ 0] = (byte) (iSerial/256);
        	modBusMsg.buffModBusSend[ 1] = (byte) (iSerial%256);    //序列号

        	modBusMsg.buffModBusSend[ 2] = 0;
        	modBusMsg.buffModBusSend[ 3] = 0;    //协议标识，00表示为ModBus-TCP

        	modBusMsg.buffModBusSend[ 4] = 0;
        	modBusMsg.buffModBusSend[ 5] = 6;    //数据长度

        	modBusMsg.buffModBusSend[ 6] = 1;    //单元标识符，设备地址（设备Num or 传感器Num）

        	modBusMsg.buffModBusSend[ 7] = 2;    //功能码

        	modBusMsg.buffModBusSend[ 8] = 0x10;
        	modBusMsg.buffModBusSend[ 9] = 0x31;    //寄存器开始地址

        	modBusMsg.buffModBusSend[10] = 0x00;
        	modBusMsg.buffModBusSend[11] = 0x10;    //最大16个

        	modBusMsg.iLength = 12;

        	//2、发送ModBus数据
        	int iResult = modBusSend(modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on sending modbus" );
        		return iResult;
        	}

        	//3、读取ModBus数据,11个字节  //返回举例：00 01 00 00 00 06 01 02 02 a1 f0
        	modBusMsg.iLength = 11;
        	iResult = modBusRecv(modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on getting modbus. iResult=" + iResult );
        		return -1;
        	}

			//序列号++
			if(iSerial>=65535) iSerial = 1; else iSerial ++;

        	//4、存储DB
        	iResult  = setDBLineState(iDeviceID, lstBeanSensorInfo, modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on call setDBLineState" );
        		return iResult;
        	}

        	return 0;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return -1;
        }
	}

	//3、离散寄存器--烟感传感器
	private int procSmokeState( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo )
	{
        try
        {
        	oLogger.info("Enter procSmokeState().");

        	//0、定义发送数据和接收数据
        	ModBusMsg modBusMsg = new ModBusMsg();

        	//1、构造发送数据
        	modBusMsg.buffModBusSend[ 0] = (byte) (iSerial/256);
        	modBusMsg.buffModBusSend[ 1] = (byte) (iSerial%256);    //序列号

        	modBusMsg.buffModBusSend[ 2] = 0;
        	modBusMsg.buffModBusSend[ 3] = 0;    //协议标识，00表示为ModBus-TCP

        	modBusMsg.buffModBusSend[ 4] = 0;
        	modBusMsg.buffModBusSend[ 5] = 6;    //数据长度

        	modBusMsg.buffModBusSend[ 6] = 1;    //单元标识符，设备地址（设备Num or 传感器Num）

        	modBusMsg.buffModBusSend[ 7] = 2;    //功能码

        	modBusMsg.buffModBusSend[ 8] = 0x10;
        	modBusMsg.buffModBusSend[ 9] = 0x41;    //寄存器开始地址

        	modBusMsg.buffModBusSend[10] = 0x00;
        	modBusMsg.buffModBusSend[11] = 0x10;    //读取16个
        	
        	modBusMsg.iLength = 12;

        	//2、发送ModBus数据
        	int iResult = modBusSend(modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on sending modbus" );
        		return iResult;
        	}

        	//3、读取ModBus数据,11个字节  //返回举例：00 01 00 00 00 06 01 02 02 a1 f0
        	modBusMsg.iLength = 11;
        	iResult = modBusRecv(modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on getting modbus. iResult=" + iResult );
        		return -1;
        	}

        	//序列号++
			if(iSerial>=65535) iSerial = 1; else iSerial ++;

        	//4、存储DB
        	iResult  = setDBSmoke(iDeviceID, lstBeanSensorInfo, modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on call setDBSmoke" );
        		return iResult;
        	}

        	return 0;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return -1;
        }
	}

	//4、离散寄存器--水浸传感器
	private int procWaterState( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo )
	{
        try
        {
        	oLogger.info("Enter procWaterState().");

        	//0、定义发送数据和接收数据
        	ModBusMsg modBusMsg = new ModBusMsg();

        	//1、构造发送数据
        	modBusMsg.buffModBusSend[ 0] = (byte) (iSerial/256);
        	modBusMsg.buffModBusSend[ 1] = (byte) (iSerial%256);    //序列号

        	modBusMsg.buffModBusSend[ 2] = 0;
        	modBusMsg.buffModBusSend[ 3] = 0;    //协议标识，00表示为ModBus-TCP

        	modBusMsg.buffModBusSend[ 4] = 0;
        	modBusMsg.buffModBusSend[ 5] = 6;    //数据长度

        	modBusMsg.buffModBusSend[ 6] = 1;    //单元标识符，设备地址（设备Num or 传感器Num）

        	modBusMsg.buffModBusSend[ 7] = 2;    //功能码

        	modBusMsg.buffModBusSend[ 8] = 0x10;
        	modBusMsg.buffModBusSend[ 9] = 0x51;    //寄存器开始地址

        	modBusMsg.buffModBusSend[10] = 0x00;
        	modBusMsg.buffModBusSend[11] = 0x10;    //读取16个

        	modBusMsg.iLength = 12;

        	//2、发送ModBus数据
        	int iResult = modBusSend(modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on sending modbus" );
        		return iResult;
        	}

        	//3、读取ModBus数据,11个字节  //返回举例：00 01 00 00 00 06 01 02 02 a1 f0
        	modBusMsg.iLength = 11;
        	iResult = modBusRecv(modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on getting modbus. iResult=" + iResult );
        		return -1;
        	}

        	//序列号++
			if(iSerial>=65535) iSerial = 1; else iSerial ++;

        	//4、存储DB
        	iResult  = setDBWater(iDeviceID, lstBeanSensorInfo, modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on call setDBWater" );
        		return iResult;
        	}

        	return 0;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return -1;
        }
	}

	//5、离散寄存器--门磁传感器
	private int procDoorState( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo )
	{
        try
        {
        	oLogger.info("Enter procDoorState().");

        	//0、定义发送数据和接收数据
        	ModBusMsg modBusMsg = new ModBusMsg();

        	//1、构造发送数据
        	modBusMsg.buffModBusSend[ 0] = (byte) (iSerial/256);
        	modBusMsg.buffModBusSend[ 1] = (byte) (iSerial%256);    //序列号

        	modBusMsg.buffModBusSend[ 2] = 0;
        	modBusMsg.buffModBusSend[ 3] = 0;    //协议标识，00表示为ModBus-TCP

        	modBusMsg.buffModBusSend[ 4] = 0;
        	modBusMsg.buffModBusSend[ 5] = 6;    //数据长度

        	modBusMsg.buffModBusSend[ 6] = 1;    //单元标识符，设备地址（设备Num or 传感器Num）

        	modBusMsg.buffModBusSend[ 7] = 2;    //功能码

        	modBusMsg.buffModBusSend[ 8] = 0x10;
        	modBusMsg.buffModBusSend[ 9] = 0x61;    //寄存器开始地址

        	modBusMsg.buffModBusSend[10] = 0x00;
        	modBusMsg.buffModBusSend[11] = 0x10;    //读取16个

        	modBusMsg.iLength = 12;

        	//2、发送ModBus数据
        	int iResult = modBusSend(modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on sending modbus" );
        		return iResult;
        	}

        	//3、读取ModBus数据,11个字节  //返回举例：00 01 00 00 00 06 01 02 02 a1 f0
        	modBusMsg.iLength = 11;
        	iResult = modBusRecv(modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on getting modbus. iResult=" + iResult );
        		return -1;
        	}

        	//序列号++
			if(iSerial>=65535) iSerial = 1; else iSerial ++;

        	//4、存储DB
        	iResult  = setDBDoor(iDeviceID, lstBeanSensorInfo, modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on call setDBDoor" );
        		return iResult;
        	}

        	return 0;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return -1;
        }
	}

	//====================上面是离散寄存器处理（已完成），下面是保持寄存器=====================//

	//6、保持寄存器--无源无线测温传感器
	private int procTepmertureData( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo )
	{
    	oLogger.info("Enter procTepmertureData().");
        try
        {
        	//0、定义发送数据和接收数据
        	ModBusMsg modBusMsg = new ModBusMsg();

        	//1、构造发送数据
        	modBusMsg.buffModBusSend[ 0] = (byte) (iSerial/256);
        	modBusMsg.buffModBusSend[ 1] = (byte) (iSerial%256);    //序列号

        	modBusMsg.buffModBusSend[ 2] = 0;
        	modBusMsg.buffModBusSend[ 3] = 0;    //协议标识，00表示为ModBus-TCP

        	modBusMsg.buffModBusSend[ 4] = 0;
        	modBusMsg.buffModBusSend[ 5] = 6;    //数据长度

        	modBusMsg.buffModBusSend[ 6] = 1;    //单元标识符，设备地址（设备Num or 传感器Num）

        	modBusMsg.buffModBusSend[ 7] = 3;    //功能码

        	modBusMsg.buffModBusSend[ 8] = 0x00;
        	modBusMsg.buffModBusSend[ 9] = 0x41;    //寄存器开始地址

        	modBusMsg.buffModBusSend[10] = 0x00;
        	modBusMsg.buffModBusSend[11] = 48;    //24个传感器，每个传感器2个寄存器

        	modBusMsg.iLength = 12;

        	//2、发送ModBus数据
        	int iResult = modBusSend(modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on sending modbus" );
        		return iResult;
        	}

        	//3、读取ModBus数据,返回字节数据: lstBeanSensorInfo.size() * 2 * 2;
        	modBusMsg.iLength = 105;
        	iResult = modBusRecv(modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on getting modbus. iResult=" + iResult );
        		return -1;
        	}

        	//序列号++
    		if(iSerial>=65535) iSerial = 1; else iSerial ++;

        	//4、存储DB
        	iResult  = setDBTepmertureData(iDeviceID, lstBeanSensorInfo, modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on call setDBTepmertureData" );
        		return iResult;
        	}

        	return 0;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return -1;
        }

	}

	//7、保持寄存器--读取线路电流
	private int procLineDataElectric( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo )
	{
    	oLogger.info("Enter procLineDataElectric().");

		//得到路数
		for( int i=0; i<lstBeanSensorInfo.size(); i++ )
		{
			BeanSensorInfo oSensorInfo = lstBeanSensorInfo.get(i);

			//对电流路的校验
			if( ( !oSensorInfo.getChannelNum().equals( "1" ) ) && ( !oSensorInfo.getChannelNum().equals( "2" ) ) &&
				( !oSensorInfo.getChannelNum().equals( "3" ) ) && ( !oSensorInfo.getChannelNum().equals( "4" ) ) &&
				( !oSensorInfo.getChannelNum().equals( "5" ) ) && ( !oSensorInfo.getChannelNum().equals( "6" ) ) &&
				( !oSensorInfo.getChannelNum().equals( "7" ) ) && ( !oSensorInfo.getChannelNum().equals( "8" ) ) )
			{
				oLogger.error( "In procLineDataElectric. The ChannelNum is error. The ChannelNum=" + oSensorInfo.getChannelNum() + ". The ChannelNum should be in [1,2,3,4,5,6,7,8].");
				continue;
			}
	        try
	        {
	        	//0、定义发送数据和接收数据
	        	ModBusMsg modBusMsg = new ModBusMsg();

	        	//1、构造发送数据
	        	modBusMsg.buffModBusSend[ 0] = (byte) (iSerial/256);
	        	modBusMsg.buffModBusSend[ 1] = (byte) (iSerial%256);    //序列号

	        	modBusMsg.buffModBusSend[ 2] = 0;
	        	modBusMsg.buffModBusSend[ 3] = 0;    //协议标识，00表示为ModBus-TCP

	        	modBusMsg.buffModBusSend[ 4] = 0;
	        	modBusMsg.buffModBusSend[ 5] = 6;    //数据长度

	        	modBusMsg.buffModBusSend[ 6] = 1;    //单元标识符，设备地址（设备Num or 传感器Num）

	        	modBusMsg.buffModBusSend[ 7] = 3;    //功能码

	        	//第一路电流的起始地址
	        	if(oSensorInfo.getChannelNum().equals( "1" ))
	        	{
		        	modBusMsg.buffModBusSend[ 8] = 0x00;
		        	modBusMsg.buffModBusSend[ 9] = 0x07;    //寄存器开始地址
	        	}
	        	else if(oSensorInfo.getChannelNum().equals( "2" ))
	        	{
		        	modBusMsg.buffModBusSend[ 8] = 0x00;
		        	modBusMsg.buffModBusSend[ 9] = 0x0b;    //寄存器开始地址
	        	}
	        	else
	        	{
	        		//得到电路路
	        		int iNum = Integer.parseInt(oSensorInfo.getChannelNum());
		        	modBusMsg.buffModBusSend[ 8] = 0x00;
		        	modBusMsg.buffModBusSend[ 9] = (byte) (0x0f + 4 * ( iNum - 3 ));    //寄存器开始地址
	        	}

	        	modBusMsg.buffModBusSend[10] = 0x00;
	        	modBusMsg.buffModBusSend[11] = 0x04;    //读取4个寄存器，4相电流

	        	modBusMsg.iLength = 12;

	        	//2、发送ModBus数据
	        	int iResult = modBusSend(modBusMsg);
	        	if( 0 != iResult )
	        	{
	        		oLogger.error( "Found error on sending modbus" );
	        		return iResult;
	        	}

	        	//3、读取ModBus数据,17个字节  //返回举例：00 01 00 00 00 06 01 03 08 a1 a2 b1 b2 c1 c2 d1 d2
	        	modBusMsg.iLength = 17;
	        	iResult = modBusRecv(modBusMsg);
	        	if( 0 != iResult )
	        	{
	        		oLogger.error( "Found error on getting modbus. iResult=" + iResult );
	        		return -1;
	        	}
	    		//序列号++
	    		if(iSerial>=65535) iSerial = 1; else iSerial ++;

	        	//4、存储DB
	        	iResult  = setDBLineDataElectric(iDeviceID, oSensorInfo, modBusMsg);
	        	if( 0 != iResult )
	        	{
	        		oLogger.error( "Found error on call setDBLineDataElectric" );
	        		return iResult;
	        	}
	        }
	        catch (Exception e)
	        {
	            e.printStackTrace();
	            continue;
	        }
		}
    	return 0;
	}

	//8、保持寄存器--读取线路电压
	private int procLineDataVoltage( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo )
	{
    	oLogger.info("Enter procLineDataVoltage().");

		//得到路数
		for( int i=0; i<lstBeanSensorInfo.size(); i++ )
		{
			BeanSensorInfo oSensorInfo = lstBeanSensorInfo.get(i);

			//对电流路的校验
			if( ( !oSensorInfo.getChannelNum().equals( "1" ) ) && ( !oSensorInfo.getChannelNum().equals( "2" ) ) )
			{
				oLogger.error( "In procLineDataVoltage. The ChannelNum is error. The ChannelNum=" + oSensorInfo.getChannelNum() + ". The ChannelNum should be in [1,2].");
				continue;
			}
	        try
	        {
	        	//0、定义发送数据和接收数据
	        	ModBusMsg modBusMsg = new ModBusMsg();

	        	//1、构造发送数据
	        	modBusMsg.buffModBusSend[ 0] = (byte) (iSerial/256);
	        	modBusMsg.buffModBusSend[ 1] = (byte) (iSerial%256);    //序列号

	        	modBusMsg.buffModBusSend[ 2] = 0;
	        	modBusMsg.buffModBusSend[ 3] = 0;    //协议标识，00表示为ModBus-TCP

	        	modBusMsg.buffModBusSend[ 4] = 0;
	        	modBusMsg.buffModBusSend[ 5] = 6;    //数据长度

	        	modBusMsg.buffModBusSend[ 6] = 1;    //单元标识符，设备地址（设备Num or 传感器Num）

	        	modBusMsg.buffModBusSend[ 7] = 3;    //功能码

	        	//第一路电流的起始地址
	        	if(oSensorInfo.getChannelNum().equals( "1" ))
	        	{
		        	modBusMsg.buffModBusSend[ 8] = 0x00;
		        	modBusMsg.buffModBusSend[ 9] = 0x07;    //寄存器开始地址
	        	}
	        	else if(oSensorInfo.getChannelNum().equals( "2" ))
	        	{
		        	modBusMsg.buffModBusSend[ 8] = 0x00;
		        	modBusMsg.buffModBusSend[ 9] = 0x0b;    //寄存器开始地址
	        	}

	        	modBusMsg.buffModBusSend[10] = 0x00;
	        	modBusMsg.buffModBusSend[11] = 0x03;    //读取3个寄存器，3相电压

	        	modBusMsg.iLength = 12;

	        	//2、发送ModBus数据
	        	int iResult = modBusSend(modBusMsg);
	        	if( 0 != iResult )
	        	{
	        		oLogger.error( "Found error on sending modbus" );
	        		return iResult;
	        	}

	        	//3、读取ModBus数据,15个字节  //返回举例：00 01 00 00 00 06 01 03 08 a1 a2 b1 b2 c1 c2
	        	modBusMsg.iLength = 15;
	        	iResult = modBusRecv(modBusMsg);
	        	if( 0 != iResult )
	        	{
	        		oLogger.error( "Found error on getting modbus. iResult=" + iResult );
	        		return -1;
	        	}

	    		//序列号++
	    		if(iSerial>=65535) iSerial = 1; else iSerial ++;

	        	//4、存储DB
	        	iResult  = setDBLineDataVoltage(iDeviceID, oSensorInfo, modBusMsg);
	        	if( 0 != iResult )
	        	{
	        		oLogger.error( "Found error on call setDBLineDataVoltage" );
	        		return iResult;
	        	}
	        }
	        catch (Exception e)
	        {
	            e.printStackTrace();
	            continue;
	        }
		}
    	return 0;
	}

	//9、保持寄存器--温湿度传感器
	private int procTepmertureData2( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo )
	{
    	oLogger.info("Enter procTepmertureData2().");

        try
        {
        	//0、定义发送数据和接收数据
        	ModBusMsg modBusMsg = new ModBusMsg();

        	//1、构造发送数据
        	modBusMsg.buffModBusSend[ 0] = (byte) (iSerial/256);
        	modBusMsg.buffModBusSend[ 1] = (byte) (iSerial%256);    //序列号

        	modBusMsg.buffModBusSend[ 2] = 0;
        	modBusMsg.buffModBusSend[ 3] = 0;    //协议标识，00表示为ModBus-TCP

        	modBusMsg.buffModBusSend[ 4] = 0;
        	modBusMsg.buffModBusSend[ 5] = 6;    //数据长度

        	modBusMsg.buffModBusSend[ 6] = 1;    //单元标识符，设备地址（设备Num or 传感器Num）

        	modBusMsg.buffModBusSend[ 7] = 3;    //功能码

        	modBusMsg.buffModBusSend[ 8] = 0x00;
        	modBusMsg.buffModBusSend[ 9] = 0x31;    //寄存器开始地址

        	modBusMsg.buffModBusSend[10] = 0x00;
        	modBusMsg.buffModBusSend[11] = (byte) (lstBeanSensorInfo.size() * 3);    //每个传感器3个地址

        	modBusMsg.iLength = 12;

        	//2、发送ModBus数据
        	int iResult = modBusSend(modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on sending modbus" );
        		return iResult;
        	}

        	//3、读取ModBus数据,X个字节  X = 9 + lstBeanSensorInfo.size() * 3 * 2    //每个传感器3个地址,每个数据2个字节
        	modBusMsg.iLength = 9 + lstBeanSensorInfo.size() * 3 * 2;
        	iResult = modBusRecv(modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on getting modbus. iResult=" + iResult );
        		return -1;
        	}

        	//序列号++
    		if(iSerial>=65535) iSerial = 1; else iSerial ++;

        	//4、存储DB
        	iResult  = setDBTepmertureData2(iDeviceID, lstBeanSensorInfo, modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on call setDBTepmertureData2" );
        		return iResult;
        	}

        	return 0;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return -1;
        }

	}

	//10、保持寄存器--局放传感器
	private int procLocalScaleData( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo )
	{
    	oLogger.info("Enter procLocalScaleData().");

        try
        {
        	//0、定义发送数据和接收数据
        	ModBusMsg modBusMsg = new ModBusMsg();

        	//1、构造发送数据
        	modBusMsg.buffModBusSend[ 0] = (byte) (iSerial/256);
        	modBusMsg.buffModBusSend[ 1] = (byte) (iSerial%256);    //序列号

        	modBusMsg.buffModBusSend[ 2] = 0;
        	modBusMsg.buffModBusSend[ 3] = 0;    //协议标识，00表示为ModBus-TCP

        	modBusMsg.buffModBusSend[ 4] = 0;
        	modBusMsg.buffModBusSend[ 5] = 6;    //数据长度

        	modBusMsg.buffModBusSend[ 6] = 1;    //单元标识符，设备地址（设备Num or 传感器Num）

        	modBusMsg.buffModBusSend[ 7] = 3;    //功能码

        	modBusMsg.buffModBusSend[ 8] = 0x00;
        	modBusMsg.buffModBusSend[ 9] = 0x71;    //寄存器开始地址

        	modBusMsg.buffModBusSend[10] = 0x00;
        	modBusMsg.buffModBusSend[11] = (byte) (lstBeanSensorInfo.size() * 4);    //读取80个 -- 全部80个局放传感器

        	modBusMsg.iLength = 12;

        	//2、发送ModBus数据
        	int iResult = modBusSend(modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on sending modbus" );
        		return iResult;
        	}

        	//3、读取ModBus数据
        	modBusMsg.iLength = lstBeanSensorInfo.size() * 4 * 2;
        	iResult = modBusRecv(modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on getting modbus. iResult=" + iResult );
        		return -1;
        	}

        	//序列号++
    		if(iSerial>=65535) iSerial = 1; else iSerial ++;

        	//4、存储DB
        	iResult  = setDBLocalScale(iDeviceID, lstBeanSensorInfo, modBusMsg);
        	if( 0 != iResult )
        	{
        		oLogger.error( "Found error on call setDBLocalScale" );
        		return iResult;
        	}

        	return 0;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return -1;
        }

	}
	
	//11、保持寄存器--热成像传感器
	private int procHotPic( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo )
	{
    	oLogger.info("Enter procHotPic().");

		//得到路数
		for( int i=0; i<lstBeanSensorInfo.size(); i++ )
		{
			BeanSensorInfo oSensorInfo = lstBeanSensorInfo.get(i);

			//获取位置号和ID号
			String strChannelNum = oSensorInfo.getChannelNum();
			String strEmitterID  = oSensorInfo.getEmitterID();

			int iChannelNum = Integer.parseInt(strChannelNum);
			int iEmitterID  = Integer.parseInt(strEmitterID);

			//=====================设置位置号和ID号====================//
	        try
	        {
	        	//0、定义发送数据和接收数据
	        	ModBusMsg modBusMsg = new ModBusMsg();

	        	//1、构造发送数据
	        	modBusMsg.buffModBusSend[ 0] = (byte) (iSerial/256);
	        	modBusMsg.buffModBusSend[ 1] = (byte) (iSerial%256);    //序列号

	        	modBusMsg.buffModBusSend[ 2] = 0;
	        	modBusMsg.buffModBusSend[ 3] = 0;    //协议标识，00表示为ModBus-TCP

	        	modBusMsg.buffModBusSend[ 4] = 0;
	        	modBusMsg.buffModBusSend[ 5] = 8;    //数据长度

	        	modBusMsg.buffModBusSend[ 6] = 1;    //单元标识符，设备地址（设备Num or 传感器Num）

	        	modBusMsg.buffModBusSend[ 7] = 10;    //功能码

	        	modBusMsg.buffModBusSend[ 8] = (byte) 0xba;
	        	modBusMsg.buffModBusSend[ 9] = (byte) 0xbc;    //寄存器开始地址

	        	modBusMsg.buffModBusSend[10] = (byte) (iChannelNum / 256);
	        	modBusMsg.buffModBusSend[11] = (byte) (iChannelNum % 256);    //位置号

	        	modBusMsg.buffModBusSend[12] = (byte) (iEmitterID / 256);
	        	modBusMsg.buffModBusSend[13] = (byte) (iEmitterID % 256);    //ID号

	        	modBusMsg.iLength = 14;

	        	//2、发送ModBus数据
	        	int iResult = modBusSend(modBusMsg);
	        	if( 0 != iResult )
	        	{
	        		oLogger.error( "Found error on sending modbus" );
	        		continue;
	        	}

	        	//3、读取ModBus数据,12个字节
	        	modBusMsg.iLength = 12;
	        	iResult = modBusRecv(modBusMsg);
	        	if( 0 != iResult )
	        	{
	        		oLogger.error( "Found error on getting modbus. iResult=" + iResult );
	        		continue;
	        	}

	    		//序列号++
	    		if(iSerial>=65535) iSerial = 1; else iSerial ++;
	        }
	        catch (Exception e)
	        {
	            e.printStackTrace();
	            continue;
	        }

			//=====================读取矩阵数据====================//
	        try
	        {
	        	//0、定义发送数据和接收数据
	        	ModBusMsg modBusMsg = new ModBusMsg();

	        	//1、构造发送数据
	        	modBusMsg.buffModBusSend[ 0] = (byte) (iSerial/256);
	        	modBusMsg.buffModBusSend[ 1] = (byte) (iSerial%256);    //序列号

	        	modBusMsg.buffModBusSend[ 2] = 0;
	        	modBusMsg.buffModBusSend[ 3] = 0;    //协议标识，00表示为ModBus-TCP

	        	modBusMsg.buffModBusSend[ 4] = 0;
	        	modBusMsg.buffModBusSend[ 5] = 6;    //数据长度

	        	modBusMsg.buffModBusSend[ 6] = 1;    //单元标识符，设备地址（设备Num or 传感器Num）

	        	modBusMsg.buffModBusSend[ 7] = 3;    //功能码

	        	modBusMsg.buffModBusSend[ 8] = 0x10;
	        	modBusMsg.buffModBusSend[ 9] = 0x06;    //寄存器开始地址

	        	modBusMsg.buffModBusSend[10] = (byte) (4960 / 256);
	        	modBusMsg.buffModBusSend[11] = (byte) (4960 % 256);    //读取长度

	        	modBusMsg.iLength = 12;

	        	//2、发送ModBus数据
	        	int iResult = modBusSend(modBusMsg);
	        	if( 0 != iResult )
	        	{
	        		oLogger.error( "Found error on sending modbus" );
	        		continue;
	        	}

	        	//3、读取ModBus数据, 4096*2 + 8个字节
	        	modBusMsg.iLength = 4096*2 + 8;
	        	iResult = modBusRecv(modBusMsg);
	        	if( 0 != iResult )
	        	{
	        		oLogger.error( "Found error on getting modbus. iResult=" + iResult );
	        		continue;
	        	}

	        	//这里需要存储热成像图片
	        	iResult = saveHotPic(iDeviceID, oSensorInfo, modBusMsg);
	        	if( 0 != iResult )
	        	{
	        		oLogger.error( "CALL saveHotPic() for saving the hotpic. iResult=" + iResult );
	        		continue;
	        	}

	    		//序列号++
	    		if(iSerial>=65535) iSerial = 1; else iSerial ++;
	        }
	        catch (Exception e)
	        {
	            e.printStackTrace();
	            continue;
	        }
		}

		return 0;
	}

	//====================下面是ModBus数据接收和发送部分======================//
	//发送数据
	private int modBusSend(ModBusMsg modBusMsg)
	{
        try
        {
	        //1、从Socket通信管道中得到一个字节输出流，负责发送数据
	        OutputStream outputStream = socketClient.getOutputStream();
	
	        //2、把低级的字节流包装成打印流
	        outputStream.write(modBusMsg.buffModBusSend, 0, modBusMsg.iLength);
	        outputStream.flush();

	        return 0;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return -1;
        }
	}
	//接受数据
	private int modBusRecv(ModBusMsg modBusMsg)
	{
		try
		{
			InputStream is = socketClient.getInputStream();

			int index = 0;
			while( true )
			{
				if( modBusMsg.iLength <= index )
					break;
				index = index + is.read(modBusMsg.buffModBusRead, index, modBusMsg.iLength - index);
			}
			
			//返回读取长度
			modBusMsg.iLength = index;
			return 0;
		}
		catch (IOException e)
		{
			e.printStackTrace();
			return 0;
		}
	}
	//====================上面是ModBus数据接收和发送部分======================//

	//获取对应类型的所有Sensor信息
	private int getSensorList(int iDeviceID, int iSensorType, ArrayList<BeanSensorInfo> lstBeanSensorInfo)
	{

		BeanSensorInfo oBeanSensorInfo = new BeanSensorInfo();
		oBeanSensorInfo.setDeviceID(iDeviceID);
		oBeanSensorInfo.setType(iSensorType);

		//读取所有符合条件的Sensor，存放入List
		int iResult = sensorInfoMod.getDB().List(oBeanSensorInfo, 0, 100, 1, 2, lstBeanSensorInfo);
		if(DBErrorCode.DB_Success != iResult)
		{
			oLogger.error( "Get the sensor( iDeviceID=" + iDeviceID + " is error. The type is Switch.)" );
			return iResult;	
		}
		if( 1 > lstBeanSensorInfo.size() )
		{
			oLogger.warn( "no sensor( iDeviceID=" + iDeviceID + ". The type is Switch.) was found" );
		}

		return 0;
	}

	//====================下面是解析ModBus协议和处理ModBus数据部分======================//
	//1、更新开关量的实时数值
	private int setDBSwitch( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo, ModBusMsg modBusMsg )
	{
		int cData0 = (int) (0x00ff & modBusMsg.buffModBusRead[ 9 + 0 ]);
		int cData1 = (int) (0x00ff & modBusMsg.buffModBusRead[ 9 + 1 ]);

		int cData2 = (int) (0x00ff & modBusMsg.buffModBusRead[ 9 + 2 ]);
		int cData3 = (int) (0x00ff & modBusMsg.buffModBusRead[ 9 + 3 ]);

		int cData4 = (int) (0x00ff & modBusMsg.buffModBusRead[ 9 + 4 ]);
		int cData5 = (int) (0x00ff & modBusMsg.buffModBusRead[ 9 + 5 ]);

		//当前开关变量为16个标配，最大48路
		if(lstBeanSensorInfo.size()>48)
			oLogger.warn( "In setDBSwitch. lstBeanSensorInfo.size()=" + lstBeanSensorInfo.size() );

		for( int i=0; i<lstBeanSensorInfo.size(); i++ )
		{
			BeanSensorInfo oSensorInfo = lstBeanSensorInfo.get(i);
			int iChannelNum = Integer.parseInt(oSensorInfo.getChannelNum());
			if(iChannelNum<=8)
			{
				int iData = 1 & ( cData0 >> ( iChannelNum - 1 ) );
				oSensorInfo.setPhaseA( iData );
			}
			else if(iChannelNum<=16)
			{
				int iData = 1 & ( cData1 >> ( iChannelNum - 8 - 1 ) );
				oSensorInfo.setPhaseA( iData );
			}
			else if(iChannelNum<=24)
			{
				int iData = 1 & ( cData2 >> ( iChannelNum - 16 - 1 ) );
				oSensorInfo.setPhaseA( iData );
			}
			else if(iChannelNum<=32)
			{
				int iData = 1 & ( cData3 >> ( iChannelNum - 24 - 1 ) );
				oSensorInfo.setPhaseA( iData );
			}
			else if(iChannelNum<=40)
			{
				int iData = 1 & ( cData4 >> ( iChannelNum - 32 - 1 ) );
				oSensorInfo.setPhaseA( iData );
			}
			else if(iChannelNum<=48)
			{
				int iData = 1 & ( cData5 >> ( iChannelNum - 40 - 1 ) );
				oSensorInfo.setPhaseA( iData );
			}

			//将数据写入数据库
			int iResult = sensorInfoMod.getDB().Mod(oSensorInfo);
			if(DBErrorCode.DB_Success != iResult)
			{
				oLogger.error( "update the data of the sensor( iDeviceID=" + iDeviceID + ". The type is Switch.) was error. iResult=" + iResult );
				//continue;	
			}
		}
		return 0;
	}
	//2、更新线路状态（停电状态）的实时数值
	private int setDBLineState( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo, ModBusMsg modBusMsg )
	{
		int cData0 = (int) (0x00ff & modBusMsg.buffModBusRead[ 9 + 0 ]);
		int cData1 = (int) (0x00ff & modBusMsg.buffModBusRead[ 9 + 1 ]);
		int iData =  0;

		//当前线路状态（停电状态）最大16个
		if(lstBeanSensorInfo.size()>16)
			oLogger.warn( "In setDBLineState. lstBeanSensorInfo.size()=" + lstBeanSensorInfo.size() );

		//循环处理每个传感器的数据
		for( int i=0; i<lstBeanSensorInfo.size(); i++ )
		{
			BeanSensorInfo oSensorInfo = lstBeanSensorInfo.get(i);
			int iChannelNum = Integer.parseInt(oSensorInfo.getChannelNum());
			if(iChannelNum<=8)
			{
				iData = 1 & ( cData0 >> ( iChannelNum - 1 ) );
			}
			else
			{
				iData = 1 & ( cData1 >> ( iChannelNum - 8 - 1 ) );
			}

			//解析ModBus数据到Sensor对象
			oSensorInfo.setPhaseA( iData );
	
			//将数据写入数据库
			int iResult = sensorInfoMod.getDB().Mod(oSensorInfo);
			if(DBErrorCode.DB_Success != iResult)
			{
				oLogger.error( "update the data of the sensor( iDeviceID=" + iDeviceID + ". The type is LineState.) was error. iResult=" + iResult );
			}
		}
		return 0;
	}
	//3、更新烟雾传感器的实时数据
	private int setDBSmoke( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo, ModBusMsg modBusMsg )
	{
		int cData0 = (int) (0x00ff & modBusMsg.buffModBusRead[ 9 + 0 ]);
		int cData1 = (int) (0x00ff & modBusMsg.buffModBusRead[ 9 + 1 ]);
		int iData =  0;

		//当前烟雾传感器最大16个
		if(lstBeanSensorInfo.size()>16)
			oLogger.warn( "In setDBSmoke. lstBeanSensorInfo.size()=" + lstBeanSensorInfo.size() );

		//循环处理每个传感器的数据
		for( int i=0; i<lstBeanSensorInfo.size(); i++ )
		{
			BeanSensorInfo oSensorInfo = lstBeanSensorInfo.get(i);
			int iChannelNum = Integer.parseInt(oSensorInfo.getChannelNum());
			if(iChannelNum<=8)
			{
				iData = 1 & ( cData0 >> ( iChannelNum - 1 ) );
			}
			else
			{
				iData = 1 & ( cData1 >> ( iChannelNum - 8 - 1 ) );
			}

			//解析ModBus数据到Sensor对象
			oSensorInfo.setPhaseA( iData );

			//将数据写入数据库
			int iResult = sensorInfoMod.getDB().Mod(oSensorInfo);
			if(DBErrorCode.DB_Success != iResult)
			{
				oLogger.error( "update the data of the sensor( iDeviceID=" + iDeviceID + ". The type is SmokeState.) was error. iResult=" + iResult );
			}
		}

		return 0;
	}
	//4、更新水浸传感器的实时数值
	private int setDBWater( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo, ModBusMsg modBusMsg )
	{
		int cData0 = (int) (0x00ff & modBusMsg.buffModBusRead[ 9 + 0 ]);
		int cData1 = (int) (0x00ff & modBusMsg.buffModBusRead[ 9 + 1 ]);
		int iData =  0;

		//当前水浸传感器最大16个
		if(lstBeanSensorInfo.size()>16)
			oLogger.warn( "In setDBWater. lstBeanSensorInfo.size()=" + lstBeanSensorInfo.size() );

		//循环处理每个传感器的数据
		for( int i=0; i<lstBeanSensorInfo.size(); i++ )
		{
			BeanSensorInfo oSensorInfo = lstBeanSensorInfo.get(i);

			int iChannelNum = Integer.parseInt(oSensorInfo.getChannelNum());
			if(iChannelNum<=8)
			{
				iData = 1 & ( cData0 >> ( iChannelNum - 1 ) );
			}
			else
			{
				iData = 1 & ( cData1 >> ( iChannelNum - 8 - 1 ) );
			}

			//解析ModBus数据到Sensor对象
			oSensorInfo.setPhaseA( iData );
	
			//将数据写入数据库
			int iResult = sensorInfoMod.getDB().Mod(oSensorInfo);
			if(DBErrorCode.DB_Success != iResult)
			{
				oLogger.error( "update the data of the sensor( iDeviceID=" + iDeviceID + ". The type is WaterState.) was error. iResult=" + iResult );
			}
		}
		return 0;
	}
	//5、更新门磁传感器的实时数值
	private int setDBDoor( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo, ModBusMsg modBusMsg )
	{
		int cData0 = (int) (0x00ff & modBusMsg.buffModBusRead[ 9 + 0 ]);
		int cData1 = (int) (0x00ff & modBusMsg.buffModBusRead[ 9 + 1 ]);
		int iData =  0;

		//当前门磁传感器最大16个
		if(lstBeanSensorInfo.size()>16)
			oLogger.warn( "In setDBDoor. lstBeanSensorInfo.size()=" + lstBeanSensorInfo.size() );

		//循环处理每个传感器的数据
		for( int i=0; i<lstBeanSensorInfo.size(); i++ )
		{
			BeanSensorInfo oSensorInfo = lstBeanSensorInfo.get(i);
			
			int iChannelNum = Integer.parseInt(oSensorInfo.getChannelNum());
			if(iChannelNum<=8)
			{
				iData = 1 & ( cData0 >> ( iChannelNum - 1 ) );
			}
			else
			{
				iData = 1 & ( cData1 >> ( iChannelNum - 8 - 1 ) );
			}
			//解析ModBus数据到Sensor对象
			oSensorInfo.setPhaseA( iData );
	
			//将数据写入数据库
			int iResult = sensorInfoMod.getDB().Mod(oSensorInfo);
			if(DBErrorCode.DB_Success != iResult)
			{
				oLogger.error( "update the data of the sensor( iDeviceID=" + iDeviceID + ". The type is DoorState.) was error. iResult=" + iResult );
				continue;
			}
		}
		return 0;
	}

	//====================上面是离散寄存器处理（已完成），下面是保持寄存器=====================//
	//6、更新无源无线测温传感器的实时数值
	private int setDBTepmertureData( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo, ModBusMsg modBusMsg )
	{
		//无源无线测温传感器最大24个
		if(lstBeanSensorInfo.size()>24)
			oLogger.warn( "In setDBTepmertureData. lstBeanSensorInfo.size()=" + lstBeanSensorInfo.size() );

		//循环处理每个传感器的数据
		for( int i=0; i<lstBeanSensorInfo.size(); i++ )
		{
			BeanSensorInfo oSensorInfo = lstBeanSensorInfo.get(i);
			int iChannelNum = Integer.parseInt( oSensorInfo.getChannelNum() );
			if( iChannelNum > 24 )
				continue;
			
			//得到级别
			int iLevel0 = (int) (0x00ff & modBusMsg.buffModBusRead[ 4*(iChannelNum-1) + 9 + 0 ]);
			int iLevel1 = (int) (0x00ff & modBusMsg.buffModBusRead[ 4*(iChannelNum-1) + 9 + 1 ]);
			int iLevel  = (int) (iLevel0 * 256 + iLevel1);

			//得到温度数据
			int iData0  = (int) (0x00ff & modBusMsg.buffModBusRead[ 4*(iChannelNum-1) + 9 + 2 ]);
			int iData1  = (int) (0x00ff & modBusMsg.buffModBusRead[ 4*(iChannelNum-1) + 9 + 3 ]);
			int iData   = (int) (iData0 * 256 + iData1);

			//解析ModBus数据到Sensor对象
			oSensorInfo.setPhaseA( iData );
			//将数据写入数据库
			int iResult = sensorInfoMod.getDB().Mod(oSensorInfo);
			if(DBErrorCode.DB_Success != iResult)
			{
				oLogger.error( "update the data of the sensor( iDeviceID=" + iDeviceID + ". The type is TepmertureData.) was error. iResult=" + iResult );
				continue;	
			}

			//告警等级转化
			//     我方:异常=2；严重=4.
			//     长川:异常=1；严重=2
			if( 1 == iLevel )
				iLevel = 2;
			if( 2 == iLevel )
				iLevel = 4;
			//没有告警就返回吧
			if( 0 == iLevel )
				continue;

			//写入告警DB
			Date oTime = new Date();
			@SuppressWarnings("deprecation")
			String strTime = "" + ( oTime.getYear() + 1900 ) + "-" + ( oTime.getMonth() + 1 ) + "-" + oTime.getDate() + " " + oTime.getHours() + ":" + oTime.getMinutes() + ":" + oTime.getSeconds();

			//构造告警对象
			BeanSensorAlarm oInfo = new BeanSensorAlarm();
			oInfo.setSensorID(oSensorInfo.getID());
			oInfo.setType(BeanSensorAlarm.Alarm_Type_Temperture);
			oInfo.setValue(iData);
			oInfo.setLevel(iLevel);
			oInfo.setTimeGet(strTime);
			//记录告警
			iResult = sensorAlarmAdd.getDB().Add(oInfo);
			if(DBErrorCode.DB_Success != iResult)
			{
				oLogger.error( "update the alarm data of the sensor( iDeviceID=" + iDeviceID + ". The type is TepmertureData.) was error. iResult=" + iResult );
			}
		}

		return 0;
	}
	//7、更新母线/支线的电气量数据（电流数据）的实时数值
	private int setDBLineDataElectric( int iDeviceID, BeanSensorInfo oSensorInfo, ModBusMsg modBusMsg )
	{
		short Ia = modBusMsg.buffModBusRead[0];
		short Ib = modBusMsg.buffModBusRead[2];
		short Ic = modBusMsg.buffModBusRead[4];
		short I0 = modBusMsg.buffModBusRead[6];  //I0电流后续支持

		//解析ModBus数据到Sensor对象
		oSensorInfo.setPhaseA( Ia );
		oSensorInfo.setPhaseB( Ib );
		oSensorInfo.setPhaseC( Ic );

		//将数据写入数据库
		int iResult = sensorInfoMod.getDB().Mod(oSensorInfo);
		if(DBErrorCode.DB_Success != iResult)
		{
			oLogger.error( "update the data of the sensor( iDeviceID=" + iDeviceID + ". The type is Electric.) was error. iResult=" + iResult );
		}

		return 0;
	}
	//8、更新母线/支线的电气量数据（电压数据）的实时数值
	private int setDBLineDataVoltage( int iDeviceID, BeanSensorInfo oSensorInfo, ModBusMsg modBusMsg )
	{
		short Va = modBusMsg.buffModBusRead[0];
		short Vb = modBusMsg.buffModBusRead[2];
		short Vc = modBusMsg.buffModBusRead[4];

		//解析ModBus数据到Sensor对象
		oSensorInfo.setPhaseA( Va );
		oSensorInfo.setPhaseB( Vb );
		oSensorInfo.setPhaseC( Vc );

		//将数据写入数据库
		int iResult = sensorInfoMod.getDB().Mod(oSensorInfo);
		if(DBErrorCode.DB_Success != iResult)
		{
			oLogger.error( "update the data of the sensor( iDeviceID=" + iDeviceID + ". The type is Voltage.) was error. iResult=" + iResult );
		}

		return 0;
	}
	//9、更新温湿度传感器的实时数值
	private int setDBTepmertureData2( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo, ModBusMsg modBusMsg )
	{
		//无源无线测温传感器预留5个
		if(lstBeanSensorInfo.size()>5)
			oLogger.warn( "In setDBTepmertureData2. lstBeanSensorInfo.size()=" + lstBeanSensorInfo.size() );

		//循环处理每个传感器的数据
		for( int i=0; i<lstBeanSensorInfo.size(); i++ )
		{
			//得到状态
			short iLevel0 = (short) (0x00ff & modBusMsg.buffModBusRead[ 6*i + 0 ]);
			short iLevel1 = (short) (0x00ff & modBusMsg.buffModBusRead[ 6*i + 1 ]);
			short iLevel  = (short) (iLevel0 * 256 + iLevel1);

			//得到温度数据
			short iDataTemp0 = (short) (0x00ff & modBusMsg.buffModBusRead[ 6*i + 2 ]);
			short iDataTemp1 = (short) (0x00ff & modBusMsg.buffModBusRead[ 6*i + 3 ]);
			short iDataTemp  = (short) (iDataTemp0 * 256 + iDataTemp1);

			//得到湿度数据
			short iDataWet0  = (short) (0x00ff & modBusMsg.buffModBusRead[ 6*i + 4 ]);
			short iDataWet1  = (short) (0x00ff & modBusMsg.buffModBusRead[ 6*i + 5 ]);
			short iDataWet   = (short) (iDataWet0 * 256 + iDataWet1);

			BeanSensorInfo oSensorInfo = lstBeanSensorInfo.get(i);
			//解析ModBus数据到Sensor对象
			oSensorInfo.setPhaseA( iDataTemp );
			oSensorInfo.setPhaseB( iDataWet  );

			//将数据写入数据库
			int iResult = sensorInfoMod.getDB().Mod(oSensorInfo);
			if(DBErrorCode.DB_Success != iResult)
			{
				oLogger.error( "update the data of the sensor( iDeviceID=" + iDeviceID + ". The type is TepmertureData2.) was error. iResult=" + iResult );
				continue;	
			}

			//告警等级转化
			//     我方:异常=2；严重=4.
			//     长川:异常=1；严重=2
			if( 1 == iLevel )
				iLevel = 2;
			if( 2 == iLevel )
				iLevel = 4;
			//没有告警就返回吧
			if( 0 == iLevel )
				continue;

			//写入告警DB
			Date oTime = new Date();
			@SuppressWarnings("deprecation")
			String strTime = "" + ( oTime.getYear() + 1900 ) + "-" + ( oTime.getMonth() + 1 ) + "-" + oTime.getDate() + " " + oTime.getHours() + ":" + oTime.getMinutes() + ":" + oTime.getSeconds();

			int iData = ( iDataTemp << 16 ) | iDataWet;
			//构造告警对象
			BeanSensorAlarm oInfo = new BeanSensorAlarm();
			oInfo.setSensorID(oSensorInfo.getID());
			oInfo.setType(BeanSensorAlarm.Alarm_Type_Temperture);
			oInfo.setValue(iData);
			oInfo.setLevel(iLevel);
			oInfo.setTimeGet(strTime);
			//记录告警
			iResult = sensorAlarmAdd.getDB().Add(oInfo);
			if(DBErrorCode.DB_Success != iResult)
			{
				oLogger.error( "update the alarm data of the sensor( iDeviceID=" + iDeviceID + ". The type is TepmertureData.) was error. iResult=" + iResult );
				continue;	
			}
		}

		return 0;
	}
	//10、更新局放传感器的实时数值
	private int setDBLocalScale( int iDeviceID, ArrayList<BeanSensorInfo> lstBeanSensorInfo, ModBusMsg modBusMsg )
	{
		//无源无线测温传感器预留20个
		if(lstBeanSensorInfo.size()>20)
			oLogger.warn( "In setDBLocalScale. lstBeanSensorInfo.size()=" + lstBeanSensorInfo.size() );

		//循环处理每个传感器的数据
		for( int i=0; i<lstBeanSensorInfo.size(); i++ )
		{
			//得到状态
			short iLevel0 = (short) (0x00ff & modBusMsg.buffModBusRead[ 8*i + 0 ]);
			short iLevel1 = (short) (0x00ff & modBusMsg.buffModBusRead[ 8*i + 1 ]);
			short iLevel  = (short) (iLevel0 * 256 + iLevel1);

			//放电幅值
			short iAmplitude0 = (short) (0x00ff & modBusMsg.buffModBusRead[ 8*i + 2 ]);
			short iAmplitude1 = (short) (0x00ff & modBusMsg.buffModBusRead[ 8*i + 3 ]);
			short iAmplitude  = (short) (iAmplitude0 * 256 + iAmplitude1);

			//放电频次
			short iFreq0   = (short) (0x00ff & modBusMsg.buffModBusRead[ 8*i + 4 ]);
			short iFreq1   = (short) (0x00ff & modBusMsg.buffModBusRead[ 8*i + 5 ]);
			short iFreq    = (short) (iFreq0 * 256 + iFreq1);

			//放电总能量
			short iEnergy0 = (short) (0x00ff & modBusMsg.buffModBusRead[ 8*i + 6 ]);
			short iEnergy1 = (short) (0x00ff & modBusMsg.buffModBusRead[ 8*i + 7 ]);
			short iEnergy  = (short) (iEnergy0 * 256 + iEnergy1);

			BeanSensorInfo oSensorInfo = lstBeanSensorInfo.get(i);
			//解析ModBus数据到Sensor对象
			oSensorInfo.setPhaseA( iAmplitude );
			oSensorInfo.setPhaseB( iFreq      );
			oSensorInfo.setPhaseC( iEnergy    );

			//将数据写入数据库
			int iResult = sensorInfoMod.getDB().Mod(oSensorInfo);
			if(DBErrorCode.DB_Success != iResult)
			{
				oLogger.error( "update the data of the sensor( iDeviceID=" + iDeviceID + ". The type is LocalScale.) was error. iResult=" + iResult );
				continue;	
			}

			//告警等级转化
			//     我方:异常=2；严重=4.
			//     长川:异常=1；严重=2
			if( 1 == iLevel )
				iLevel = 2;
			if( 2 == iLevel )
				iLevel = 4;
			//没有告警就返回吧
			if( 0 == iLevel )
				continue;

			//写入告警DB
			Date oTime = new Date();
			@SuppressWarnings("deprecation")
			String strTime = "" + ( oTime.getYear() + 1900 ) + "-" + ( oTime.getMonth() + 1 ) + "-" + oTime.getDate() + " " + oTime.getHours() + ":" + oTime.getMinutes() + ":" + oTime.getSeconds();

			String strMemo = "Amplitude=" + iAmplitude + ". Frequency=" + iFreq + ". Energy=" + iEnergy;
			//构造告警对象
			BeanSensorAlarm oInfo = new BeanSensorAlarm();
			oInfo.setSensorID(oSensorInfo.getID());
			oInfo.setType(BeanSensorAlarm.Alarm_Type_Temperture);
			oInfo.setMemo(strMemo);
			oInfo.setLevel(iLevel);
			oInfo.setTimeGet(strTime);
			//记录告警
			iResult = sensorAlarmAdd.getDB().Add(oInfo);
			if(DBErrorCode.DB_Success != iResult)
			{
				oLogger.error( "update the alarm data of the sensor( iDeviceID=" + iDeviceID + ". The type is LocalScale.) was error. iResult=" + iResult );
				continue;	
			}
		}

		return 0;
	}

	//11、记录人成像传感器实时数据
	private int saveHotPic( int iDeviceID, BeanSensorInfo oSensorInfo, ModBusMsg modBusMsg )
	{
		//TODO:这里要补全功能

		return 0;
	}

}


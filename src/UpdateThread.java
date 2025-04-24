package com.PowerService.PowerUpdate;

import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.PowerService.Bean.BeanUpdateTask;

public class UpdateThread extends Thread
{
	private UpdateApplication updateApp = new UpdateApplication();
	private boolean running  = false;

	private static Logger oLogger  = LogManager.getLogger( "PowerService" );
	
	private static ArrayList<BeanUpdateTask> lstTask = new ArrayList<BeanUpdateTask>();
	private static ReentrantLock oLockTask = new ReentrantLock();

	//设置设备信息
	public static void addTask( BeanUpdateTask oBeanTask )
	{
		oLockTask.lock();
		lstTask.add(oBeanTask);
		oLockTask.unlock();
	}

	//返回线程运行状态
	public boolean isRunning()
	{
		return running;
	}

    @Override
    public void run()
    {
    	//Thread.currentThread().setName("");
    	if(running)
    	{
    		oLogger.info( "UpdateThread is running, needn't restart it." );
    		return;
    	}

    	running = true;
    	Thread.currentThread().setPriority(8);
    	oLogger.info( "UpdateThread is starting. " );

    	while( true == running )
    	{
            //这里运行线程需要执行的任务
        	try
        	{
        		if( lstTask.size() > 0 )
        		{
        			oLockTask.lock();
        			BeanUpdateTask oTask = lstTask.get( 0 );
        			lstTask.clear();
        			oLockTask.unlock();
            		//线程里面处理,升级任务
            		updateApp.proc( oTask );
        		}

        		//打印日志
        		oLogger.info( "UpdateThread is running.");

        		//等待一下再执行
        		Thread.sleep( 1000 );   //1秒一次，实际运行过程中200毫秒1次
            }
        	catch(Exception e)
        	{
        		oLogger.error( "Catch Exception. e=" + e.getMessage() );
        		break;
        	}
    	}
    }

	public void shutdown()
	{
		oLogger.info( "UpdateThread is shutdown." );
		running = false;
		interrupt();
	}
}


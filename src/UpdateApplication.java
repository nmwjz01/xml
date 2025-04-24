package com.PowerService.PowerUpdate;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.PowerService.Bean.BeanUpdateTask;

public class UpdateApplication
{
	private static Logger oLogger = LogManager.getLogger( "PowerService" );

	//升级的目标文件，这个文件为下载临时存放
	private static String strPath = "/usr/local/tomcat/webapp/";
	private static String strFile = "Update.zip";

	//重启命令
	private static String strRestart = "/usr/local/tomcat/bin/restart.sh"; 

    public int proc(BeanUpdateTask oTask)
    {
    	String strURL      = oTask.getURL();    //下载URL
    	String strMD5      = oTask.getMD5();    //升级文件的hash值

    	try
    	{
    		//将JSON中的目标文件下载到本地
        	URL url = new URL( strURL );
        	URLConnection connection = url.openConnection();
        	InputStream inputStream = connection.getInputStream();
        	//将目标文件下载到本地
        	FileOutputStream fileOutputStream = new FileOutputStream( strPath + strFile );
        	byte[] buffer = new byte[4096];
        	int len;
        	while ((len = inputStream.read(buffer)) > 0)
        	{
        	    fileOutputStream.write(buffer, 0, len);
        	}

        	inputStream.close();
        	fileOutputStream.close();
    	}
        catch( Exception e )
    	{
        	e.printStackTrace();
    	}

    	//下面进行升级操作
    	try
    	{
    		//1、计算下载文件的MD5值，同时进行验证
    		String strMD5Local = md5( strPath + strFile );
	    	//这里验证MD5值是否正确
	    	if( !strMD5.equals( strMD5Local ) )
	    	{
	    		oLogger.error( "IN proc().The Md5 isn't match. strMD5=" + strMD5 + ". strMD5Local=" + strMD5Local );
	    		return -1;
	    	}

	    	//2、备份原有的war文件
	    	fileCopy( strPath + "PowerService.war", strPath + "PowerService_bak.zip" );

	    	//3、copy下载文件覆盖原有的war文件
	    	fileCopy( strPath + strFile, strPath + "PowerService.war" );

	    	//4、删除目录。/PowerService
	        File folder = new File( strPath + "PowerService/" );
	        deleteFolder(folder);

	    	//5、重启web，升级完成
	        restart( strRestart );
    	}
        catch( Exception e )
    	{
        	e.printStackTrace();
    	}

    	return 0;
    }

    //计算一个文件的md5值
    private String md5( String strFileName )
    {
    	BigInteger bi = null;
    	try
    	{
    		byte[] buffer = new byte[1024 * 1024 *100];
            int len = 0;
    		MessageDigest md = MessageDigest.getInstance("MD5");
    		File f = new File(strFileName);
            FileInputStream fis = new FileInputStream(f);
            while ((len = fis.read(buffer)) != -1)
            {
                md.update(buffer, 0, len);
            }
            fis.close();
            byte[] b = md.digest();
            bi = new BigInteger(1, b);
        }
    	catch (NoSuchAlgorithmException e)
    	{
            e.printStackTrace();
        }
    	catch (IOException e)
    	{
            e.printStackTrace();
        }
        return bi.toString(16);
    }

    //copy文件
    private int fileCopy( String strFileSrc, String strFileDst )
    {
        Path sourcePath      = Paths.get( strFileSrc );
        Path destinationPath = Paths.get( strFileDst );

        //Path sourcePath      = Paths.get("sourceFile.txt"     );
        //Path destinationPath = Paths.get("destinationFile.txt");
 
        try
        {
            Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("文件备份成功！");
            return 0;
        }
        catch (IOException e)
        {
            System.err.println("复制文件时发生错误：" + e.getMessage());
            return -1;
        }
    }

    //删除目录和文件
    private void deleteFolder(File folder)
    {
        if (folder.isDirectory())
        {
            File[] files = folder.listFiles();
            if (files != null)
            {
                for (File file : files)
                {
                    deleteFolder(file);
                }
            }
        }
        folder.delete();
    }

    //重启应用
    private void restart(String command)
    {
        StringBuilder sb = new StringBuilder();
        try
        {
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = null;
            while ((line = reader.readLine()) != null)
            {
                sb.append(line);
            }
            process.getOutputStream().close();
            reader.close();
            process.destroy();
        }
        catch (Exception e)
        {
        	oLogger.error("执行外部命令错误，命令行:" + command, e);
        }
        oLogger.info( sb.toString() );
    }
}

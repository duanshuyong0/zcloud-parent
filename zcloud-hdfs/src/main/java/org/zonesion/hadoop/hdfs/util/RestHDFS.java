package org.zonesion.hadoop.hdfs.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xml.sax.SAXException;
import org.zonesion.hadoop.base.bean.Gate;
import org.zonesion.hadoop.base.bean.HistoryURL;
import org.zonesion.hadoop.base.bean.Sensor;
import org.zonesion.hadoop.hdfs.util.HDFSUtil;
import org.zonesion.hadoop.base.util.Constants;
import org.zonesion.hadoop.base.util.LogListener;
import org.zonesion.hadoop.base.util.PropertiesUtil;
import org.zonesion.hadoop.base.util.Rest;
import org.zonesion.hadoop.base.util.RestListener;
import org.zonesion.hadoop.base.util.XmlService;

//数据存储与访问有2种方式：1）访问数据库；2）访问xml文件；
public class RestHDFS extends RestListener
{
	private HttpURLConnection connection = null;
	private Properties properties;
	private Logger logger;
	private LogListener logListener;
	private HDFSUtil hdfsUtil;
	
	public RestHDFS(String hdfsName) throws IOException {
		super();
		hdfsUtil = new HDFSUtil(hdfsName);
		//从类路径下加载配置文件
		PropertyConfigurator.configure(this.getClass().getClassLoader().getResourceAsStream("log4j/log4j.properties"));
		logger =  Logger.getLogger(RestHDFS.class);
		//在家目录生成临时文件
		File file = new File(Constants.MKDIRPATH);
		if(!file.exists()) file.mkdirs();
		file = new File(Constants.HDFS_PATH);
		if(!file.exists()) file.createNewFile();
		logger.info(Constants.HDFS_PATH);
		InputStream input = new FileInputStream(file);
		properties = PropertiesUtil.loadFromInputStream(input);
	}

	//解析JSON格式字符串	
	//加上synchronized
	public synchronized  String  analysis(HistoryURL historyURL) throws Exception{
		String result = Rest.doRest(connection,historyURL);
		JSONObject resultObj = new JSONObject(result);
		JSONArray  datapoints = resultObj.getJSONArray("datapoints");
		int serverSize = datapoints.length();
		if(serverSize >= 1){
			String startAt = datapoints.getJSONObject(0).getString("at");//并读取第一个时间点
			String endAt = datapoints.getJSONObject(serverSize-1).getString("at");//并读取最后一个时间点
			logger.info("startAt:"+startAt+";"+"endAt:"+endAt+";"+"size:"+serverSize);
			//创建文件夹
			if(!hdfsUtil.check("zcloud")){//HDFS创建zcloud
				hdfsUtil.mkdir("zcloud");//HDFS上的路径：/user/hadoop/zcloud/
			}
			String hdfsUserID = "zcloud"+File.separator+historyURL.getId();
			if(!hdfsUtil.check(hdfsUserID)){//HDFS创建
				hdfsUtil.mkdir(hdfsUserID);//HDFS上的路径：/user/hadoop/zcloud/1155223593
			}
			String hdfsChannal = hdfsUserID+File.separator+historyURL.getChannal().replace(":", "_");//HDFS上的路径：/user/hadoop/zcloud/1155223593/00_11_22/
			if(!hdfsUtil.check(hdfsChannal)){//HDFS创建
				 hdfsUtil.mkdir(hdfsChannal);
			}
			//文件名以startAt命令
			File hdfsFile = new File(hdfsChannal+startAt.replace(":", "-"));//  /user/hadoop/zcloud/1155223593/channalid/startat.txt
			String localSize = properties.getProperty(historyURL.getId()+";"+historyURL.getChannal()+";"+startAt, "2000");
			if(serverSize == 2000){//判断其为完整的文件的条件
				if(hdfsUtil.check(hdfsFile.getPath()) && Integer.valueOf(localSize).equals(2000)){
					//如果文件在HDFS上存在，且服务器段的分片数据为2000个，且本地分片的大小为2000个
					logger.info("完整文件已经保存到HDFS");
				}else{
					uploadHDFS(startAt.replace(":", "-"),result,hdfsChannal);
				}
				//记录最后一个分片的开始时间点,用于指定开始时间
				properties.setProperty(historyURL.getId()+";"+historyURL.getChannal(), startAt);
				if(!Integer.valueOf(localSize).equals(2000)) 
					properties.remove(historyURL.getId()+";"+historyURL.getChannal()+";"+startAt);
				return endAt;
			}else{//最后一片段文件
				uploadHDFS(startAt.replace(":", "-"),result,hdfsChannal);
				//记录最后一个分片的开始时间点,用于指定开始时间
				properties.setProperty(historyURL.getId()+";"+historyURL.getChannal(), startAt);
				//记录最后一个分片的本地文件大小
				properties.setProperty(historyURL.getId()+";"+historyURL.getChannal()+";"+startAt, String.valueOf(serverSize));
				return "";
			}
		}
		return "";
	}
	
	public void uploadHDFS(String filename,String result,String hdfsPath) throws Exception{
		File file = new File(filename);//
		OutputStream outputStream = new FileOutputStream(file);
		OutputStreamWriter writer = new OutputStreamWriter(outputStream,"GBk");
		writer.write(result);//保存读取到的数据到文件
		writer.close();
		if(logListener != null) logListener.log("成功上传："+file.getName());
		logger.info("成功上传："+file.getName()+"到"+hdfsPath);
		hdfsUtil.put(file.getPath(), hdfsPath);//上传到HDFS上
	}

	//查询
	public  class QueryRunnable implements Runnable{
		
		HistoryURL historyURL;
		CountDownLatch downLatch;
		Exchanger<Integer> exchanger;   
		
		public QueryRunnable(CountDownLatch downLatch,Exchanger<Integer> exchanger,HistoryURL historyURL) {
			super();
			this.downLatch = downLatch;
			this.historyURL = historyURL;
			this.exchanger = exchanger;
		}

		public void run() {
			String endAt = "";
			try {
				endAt = analysis(historyURL);//第一次读取
			} catch (Exception e1) {
				e1.printStackTrace();
			}
			 while(true){//什么时候退出
				 try {
						 if(!endAt.equals("")){
							 historyURL.setStarttime(endAt);
							 endAt = analysis(historyURL);//第归读取
						 }else{
							 break;
						 }
					} catch (Exception e) {
						e.printStackTrace();
						break;//当网络不可达时，报异常跳出循环
					}
			 }
			 if(logListener != null) logListener.log(historyURL.getChannal()+"上传结束！");
			 logger.info(historyURL.getChannal()+"获取历史数据结束！");
			 try {
					exchanger.exchange(1);//与主线程交换信息
				} catch (InterruptedException e) {
					e.printStackTrace();
					logger.error(e);
				}   
			 this.downLatch.countDown();//计数减少1
		}
		
	}
	
	public class FinnishJobRunable implements Runnable{
		CountDownLatch downLatch;
		XmlService xmlService;
		List<Gate> gateList;
		public  FinnishJobRunable(CountDownLatch downLatch,XmlService xmlService,List<Gate> gateList){
			this.downLatch = downLatch;
			this.xmlService = xmlService;
			this.gateList = gateList;
		}
		public void run() {
			if(logListener != null) logListener.log("等待读取历史数据线程开始执行......");
			logger.info("FinnishJobRunable在等待所有的读历史数据线程执行完毕！");
			try {
				this.downLatch.await();
				if(logListener != null) logListener.log("任务执行完毕！");
				logger.info("FinnishJobRunable释放连接资源！");
//				connection.disconnect();
				close();
			} catch (InterruptedException e) {
				e.printStackTrace();
				logger.error(e);
			} 
		}
	}
		
	public void executeJob(String filePath) throws SAXException, IOException, InterruptedException{
		ExecutorService service = Executors.newCachedThreadPool(); //创建一个线程池
		Exchanger<Integer> exchanger = new Exchanger<Integer>();//子线程与主线程交换数据
		XmlService xmlService = XmlService.getInstance();
		InputStream inputStream;
			//读配置文件
			inputStream = new FileInputStream(new File(filePath));
			List<Gate> gateList = xmlService.readXML(inputStream);
			int sum = 0;
			for(Gate gate:gateList){//获取任务个数
				sum+= gate.getSensors().size();
			}
			CountDownLatch downLatch = new CountDownLatch(sum);
			for(Gate gate:gateList){
				for(Sensor sensor:gate.getSensors()){
					//读取最新的更新记录
					String starttime = properties.getProperty(gate.getUserid()+";"+sensor.getChannal());
					HistoryURL historyURL = new HistoryURL(gate.getServerAddr(), gate.getUserid(),gate.getUserkey(), sensor.getChannal(),starttime);
					QueryRunnable queryRunnable = new QueryRunnable(downLatch, exchanger,historyURL);//多线程执行--是否有公共资源
					 service.execute(queryRunnable);//为线程池添加任务
				}
			}
			 FinnishJobRunable writeToXMLRunable = new FinnishJobRunable(downLatch,xmlService,gateList);
			 service.execute(writeToXMLRunable);//为线程池添加任务
			Integer totalResult = Integer.valueOf(0);
			 for(int i=0;i<sum;i++){
				 //当主线程调用Exchange对象的exchange()方法后，他会陷入阻塞状态
				 //直到queryRunnable线程也调用了exchange()方法，然后以线程安全的方式交换数据，之后主线程继续运行
				 Integer partialResult = exchanger.exchange(Integer.valueOf(0));
				 if (partialResult != 0) {
						totalResult = totalResult + partialResult;
						System.out.println(String.format("Progress: %s/%s",totalResult, sum));
						//更新进度条
						logListener.progress(totalResult, sum);
					}
			 }
			 service.shutdown();
	}
	
	public void registerLogListener(LogListener logListener) {
		this.logListener = logListener;
	}

	public static void main(String[] args) {
		try {
			RestHDFS rest = new RestHDFS("hdfs://192.168.1.30:9000");
			rest.executeJob("sensors.xml");
		} catch (SAXException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	public void close() {
		// TODO Auto-generated method stub
		OutputStream out = null;
		try{
			hdfsUtil.destroy();
			//String path = this.getClass().getResource(PATH).getPath();
			out = new FileOutputStream(new File(Constants.HDFS_PATH));
			properties.store(out, "update");
		}catch (FileNotFoundException e) {
			e.printStackTrace();
			logger.error(e);
		} catch (IOException e) {
			e.printStackTrace();
			logger.error(e);
		} finally{
			if(out !=null)
				try {
					out.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					logger.error(e);
				}
		}
	}	
}

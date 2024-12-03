/********************************************************************************/
/*										*/
/*		BrepairFaitManager.java 					*/
/*										*/
/*	Use FAIT to get better fault localization data if available		*/
/*										*/
/********************************************************************************/
/*	Copyright 2011 Brown University -- Steven P. Reiss		      */
/*********************************************************************************
 *  Copyright 2011, Brown University, Providence, RI.				 *
 *										 *
 *			  All Rights Reserved					 *
 *										 *
 * This program and the accompanying materials are made available under the	 *
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, *
 * and is available at								 *
 *	http://www.eclipse.org/legal/epl-v10.html				 *
 *										 *
 ********************************************************************************/

/* SVN: $Id$ */



package edu.brown.cs.seedebb.brepair;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Element;

import edu.brown.cs.bubbles.batt.BattConstants.BattTest;
import edu.brown.cs.bubbles.board.BoardLog;
import edu.brown.cs.bubbles.board.BoardProperties;
import edu.brown.cs.bubbles.board.BoardSetup;
import edu.brown.cs.ivy.exec.IvyExec;
import edu.brown.cs.ivy.exec.IvyExecQuery;
import edu.brown.cs.ivy.file.IvyFile;
import edu.brown.cs.ivy.file.IvyFormat;
import edu.brown.cs.ivy.mint.MintArguments;
import edu.brown.cs.ivy.mint.MintConstants;
import edu.brown.cs.ivy.mint.MintControl;
import edu.brown.cs.ivy.mint.MintDefaultReply;
import edu.brown.cs.ivy.mint.MintHandler;
import edu.brown.cs.ivy.mint.MintMessage;
import edu.brown.cs.ivy.mint.MintReply;
import edu.brown.cs.ivy.mint.MintConstants.CommandArgs;
import edu.brown.cs.ivy.xml.IvyXml;
import edu.brown.cs.ivy.xml.IvyXmlWriter;

class BrepairFaitManager implements BrepairConstants
{


/********************************************************************************/
/*										*/
/*	Private Storage 							*/
/*										*/
/********************************************************************************/

enum AnalysisState {
   NONE, PENDING, READY
};


private BattTest	test_case;
private Set<File>	using_files;
private Boolean 	is_running;
private boolean 	is_started;
private String		fait_session;
private Element 	last_analysis;
private AnalysisState	analysis_state;

private static final Pattern STACK_PATTERN =
   Pattern.compile("at ([a-zA-z0-9_.$]+)\\(([A-Za-z0-9_.$]+)\\:([0-9]+)\\)");

private static AtomicInteger id_counter = new AtomicInteger((int) (Math.random()*256000.0));


/********************************************************************************/
/*										*/
/*	Constructors								*/
/*										*/
/********************************************************************************/

BrepairFaitManager(BattTest bt,Set<File> files)
{
   test_case = bt;
   using_files = files;
   is_running = null;
   is_started = false;
   last_analysis = null;
   analysis_state = AnalysisState.NONE;
   fait_session = "BREPAIR_" + IvyExecQuery.getProcessId() + "_" + id_counter.incrementAndGet();
}



/********************************************************************************/
/*										*/
/*	Get FAIT result 							*/
/*										*/
/********************************************************************************/

BrepairDataFlow getFaitResult(BrepairCountData bcd)
{
   if (is_running == null) {
      setup();
      if (!waitForSetup()) return null;
    }
   if (!is_running) return null;

   Element query = getBackSliceStart();
   if (query == null) return null;
   
   CommandArgs args = new CommandArgs("FILE",IvyXml.getAttrString(query,"FILE"),
         "LINE",IvyXml.getAttrInt(query,"LINE"),
         "METHOD",IvyXml.getAttrString(query,"METHOD"),
         "QTYPE","EXPRESSION");
   String cnts = IvyXml.convertXmlToString(IvyXml.getChild(query,"EXPR")); 
   MintDefaultReply mdr = new MintDefaultReply();
   sendFaitMessage(fait_session,"FLOWQUERY",args,cnts,mdr);
   Element e = mdr.waitForXml();
   if (e == null) return null;
   
   BoardLog.logD("BREPAIR","Result of query: " + IvyXml.convertXmlToString(e));
   
   Element qr = IvyXml.getChild(e,"QUERY");
   Element graph = IvyXml.getChild(qr,"GRAPH");
   for (Element node : IvyXml.children(graph,"NODE")) {
      String file = IvyXml.getAttrString(node,"FILE");
      String mthd = IvyXml.getAttrString(node,"METHOD");
      String sgn = IvyXml.getAttrString(node,"SIGNATURE");
      if (sgn != null) {
         int idx = sgn.lastIndexOf(")");
         sgn = sgn.substring(0,idx+1);
         String sgn1 = IvyFormat.formatTypeName(sgn);
         mthd = mthd + sgn1;
       }
      Element pt = IvyXml.getChild(node,"POINT");
      int line = IvyXml.getAttrInt(pt,"LINE");
      double pr = IvyXml.getAttrDouble(node,"PRIORITY",0);
      if (pr == 0) continue;
//    BoardLog.logD("BREPAIR","ADD FLOW PRIORITY " + file + " " + mthd + " " +
//          line + " " + pr);
      bcd.addFlowPriority(file,mthd,line,pr);
    }
 
   
   // analyze the result to return prioritized set of lines

   return null;
}




public synchronized void setup()
{
   FaitStarter fs = new FaitStarter();
   fs.start();
}


private boolean waitForSetup()
{
   synchronized (this) {
      while (is_running == null) {
	 try {
	    wait(10000);
	  }
	 catch (InterruptedException e) { }
       }
    }

   BoardLog.logD("BREPAIR","Wait for setup " + is_running);

   if (!is_running) return false;

   BoardSetup bs = BoardSetup.getSetup();
   MintControl mc = bs.getMintControl();
   mc.register("<FAITEXEC TYPE='_VAR_0' />",new UpdateHandler());

   Element r = sendFaitMessage("BEGIN",null,null);
   if (!IvyXml.isElement(r,"RESULT")) {
      is_running = false;
      return false;
    }
   Element sess = IvyXml.getChild(r,"SESSION");
   String sid = IvyXml.getAttrString(sess,"ID");
   if (sid != null) fait_session = sid;

   if (using_files != null && !using_files.isEmpty()) {
      IvyXmlWriter xw = new IvyXmlWriter();
      for (File f : using_files) {
	 xw.begin("FILE");
	 xw.field("NAME",f.getAbsolutePath());
	 xw.end("FILE");
       }
      String files = xw.toString();
      xw.close();
      Element r1 = sendFaitMessage("ADDFILE",null,files);
      if (!IvyXml.isElement(r1,"RESULT")) {
	 is_running = false;
	 return false;
       }
    }

   BoardProperties props = BoardProperties.getProperties("Brepair");
   int nth = props.getInt("Brepair.fait.threads",4);  
   CommandArgs args = new CommandArgs("REPORT","SOURCE",
	 "THREADS",nth);
   analysis_state = AnalysisState.PENDING;
   Element r2 = sendFaitMessage("ANALYZE",args,null);
   if (!IvyXml.isElement(r2,"RESULT")) {
      analysis_state = AnalysisState.NONE;
      is_running = false;
      return false;
    }

   Element anal = waitForAnalysis();
   BoardLog.logD("BREPAIR","Fait analysis: " + anal);

   return true;
}



private class FaitStarter extends Thread {

   FaitStarter() {
      super("BrepairFaitStarterThread");
    }


   @Override public void run() {
       startFait();
    }

}	// end of inner class FaitStarter


private void startFait()
{
   synchronized (this) {
      if (is_started) return;
      is_started = true;
    }

   BoardLog.logD("BREPAIR","STARTING FAIT");

   BoardSetup bs = BoardSetup.getSetup();
   MintControl mc = bs.getMintControl();
   File wd = new File(bs.getDefaultWorkspace());
   File logf = new File(wd,"fait.log");

   List<String> args = new ArrayList<>();
   args.add(IvyExecQuery.getJavaPath());

   BoardProperties bp = BoardProperties.getProperties("Brepair");
   String dbgargs = bp.getProperty("Brepair.fait.jvm.args");
   if (dbgargs != null) {
      StringTokenizer tok = new StringTokenizer(dbgargs);
      while (tok.hasMoreTokens()) {
	 args.add(tok.nextToken());
       }
    }

   BoardSetup setup = BoardSetup.getSetup();

   File f1 = setup.getRootDirectory();
   File f2 = new File(f1,"dropins");
   File faitjar = new File(f2,"fait.jar");
   File fjar = IvyFile.getJarFile(BrepairFactory.class);
   if (fjar == null || fjar.getName().endsWith(".class")) {
      File f3 = new File("/Users/spr/Eclipse/fait/fait/bin");
      if (!f3.exists()) f3 = new File("/pro/fait/java");
      if (!f3.exists()) f3 = new File("/research/people/spr/fait/java");
      if (f3.exists()) faitjar = f3;
    }
   if (!faitjar.exists()) {
      BoardLog.logD("BREPAIR","Can't find fait jar file " + faitjar);
      noteRunning(false);
      return;
    }

   args.add("-cp");
   String xcp = bp.getProperty("Brepair.fait.class.path");
   if (xcp == null) {
      xcp = System.getProperty("java.class.path");
      String ycp = bp.getProperty("Brepair.fait.add.path");
      if (ycp != null) xcp = ycp + File.pathSeparator + xcp;
    }
   else {
      StringBuffer buf = new StringBuffer();
      StringTokenizer tok = new StringTokenizer(xcp,":;");
      while (tok.hasMoreTokens()) {
	 String elt = tok.nextToken();
	 if (!elt.startsWith("/") &&  !elt.startsWith("\\")) {
	    if (elt.equals("eclipsejar")) {
	       elt = setup.getEclipsePath();
	     }
	    else if (elt.equals("fait.jar") && faitjar != null) {
	       elt = faitjar.getPath();
	     }
	    else {
	       elt = setup.getLibraryPath(elt);
	     }
	  }
	 if (buf.length() > 0) buf.append(File.pathSeparator);
	 buf.append(elt);
       }
      xcp = buf.toString();
    }

   args.add(xcp);
   args.add("edu.brown.cs.fait.iface.FaitMain");
   args.add("-m");
   args.add(mc.getMintName());
   args.add("-L");
   args.add(logf.getPath());
   if (bp.getBoolean("Brepair.fait.debug")) {
      args.add("-D");
      if (bp.getBoolean("Brepair.fait.trace")) args.add("-T");
    }

   IvyExec exec = null;
   for (int i = 0; i < 100; ++i) {
      MintDefaultReply rply = new MintDefaultReply();
      sendFaitMessage("*","PING",null,null,rply);
      String rslt = rply.waitForString(1000);
      BoardLog.logD("BREPAIR","TRY STARTING FAIT " + i + " " + rslt);
      if (rslt != null) {
	 noteRunning(true);
	 return;
       }
      if (i == 0) {
	 try {
	    exec = new IvyExec(args,null,IvyExec.ERROR_OUTPUT);     // make IGNORE_OUTPUT to clean up otuput
	    BoardLog.logD("BREPAIR","Run " + exec.getCommand());
	  }
	 catch (IOException e) {
	    break;
	  }
       }
      else {
	 try {
	    if (exec != null) {
	       int sts = exec.exitValue();
	       BoardLog.logD("BREPAIR","Fait server disappeared with status " + sts);
	       break;
	     }
	  }
	 catch (IllegalThreadStateException e) { }
       }

      try {
	 Thread.sleep(2000);
       }
      catch (InterruptedException e) { }
    }
   noteRunning(false);
}


private synchronized void noteRunning(boolean fg)
{
   BoardLog.logD("BREPAIR","NOTE RUNNING " + fg);
   is_running = fg;
   notifyAll();
}



/********************************************************************************/
/*										*/
/*	Find starting point for FAIT						*/
/*										*/
/********************************************************************************/

private Element getBackSliceStart()
{
   String stack = test_case.getFailTrace();
   BoardLog.logD("BREPAIR","GET BACKSLICE from: " + stack);
   if (stack == null) return null;
   StringTokenizer tok = new StringTokenizer(stack,"\n\t");
   if (!tok.hasMoreTokens()) {
      return null;
    }
   String exc = tok.nextToken();
   BoardLog.logD("BREPAIR","Exception is " + exc);

   IvyXmlWriter xw = new IvyXmlWriter();
   xw.begin("STACK");
   xw.textElement("EXCEPTION",exc);

   int ct = 0;
   while (tok.hasMoreTokens()) {
      String ln = tok.nextToken().trim();
      Matcher m = STACK_PATTERN.matcher(ln);
      BoardLog.logD("BREPAIR","MATCH " + ln + " " + m.matches() + " " + STACK_PATTERN);
      if (m.matches()) {
	 String mthd = m.group(1);
	 String file = m.group(2);
	 int line = Integer.parseInt(m.group(3));
	 System.err.println("FOUND " + mthd + " " + file + " " + line);
	 xw.begin("FRAME");
	 xw.field("METHOD",mthd);
	 xw.field("FILE",file);
	 xw.field("LINE",line);
	 xw.end("FRAME");
	 ++ct;
       }
    }
   xw.end("STACK");
   String query = xw.toString();
   xw.close();

   if (ct == 0) return null;

   // ask fait to find starting point
   MintDefaultReply mdr = new MintDefaultReply();
   sendFaitMessage(fait_session,"STACKSTART",null,query,mdr);
   Element e = mdr.waitForXml();
   if (e == null) return null;
   Element q = IvyXml.getChild(e,"QUERY");
   
   return q;
}



/********************************************************************************/
/*										*/
/*	Send messages to FAIT							*/
/*										*/
/********************************************************************************/

private Element sendFaitMessage(String cmd,CommandArgs args,String cnts)
{
   MintDefaultReply mdr = new MintDefaultReply();
   sendFaitMessage(fait_session,cmd,args,cnts,mdr);
   Element rslt = mdr.waitForXml();
   BoardLog.logD("BREPAIR","REPLY from FAIT: " + IvyXml.convertXmlToString(rslt));

   return rslt;
}



private void sendFaitMessage(String id,String cmd,CommandArgs args,String cnts,MintReply rply)
{
   BoardSetup bs = BoardSetup.getSetup();
   MintControl mc = bs.getMintControl();
   IvyXmlWriter xw = new IvyXmlWriter();
   xw.begin("FAIT");
   xw.field("DO",cmd);
   if (id != null) xw.field("SID",id);
   if (args != null) {
      for (Map.Entry<String,Object> ent : args.entrySet()) {
	 if (ent.getValue() == null) continue;
	 xw.field(ent.getKey(),ent.getValue().toString());
       }
    }
   if (cnts != null) xw.xmlText(cnts);
   xw.end("FAIT");
   String msg = xw.toString();
   xw.close();

   BoardLog.logD("BREPAIR","SEND to FAIT: " + msg);

   if (rply == null) {
      mc.send(msg,rply,MintConstants.MINT_MSG_NO_REPLY);
    }
   else {
      mc.send(msg,rply,MintConstants.MINT_MSG_FIRST_NON_NULL);
    }
}

/********************************************************************************/
/*										*/
/*	Handle replies from fait						*/
/*										*/
/********************************************************************************/


private final class UpdateHandler implements MintHandler {

   @Override public void receive(MintMessage msg,MintArguments args) {
      BoardLog.logD("BREPAIR","Fait message: " + msg.getText());
      String type = args.getArgument(0);
      Element xml = msg.getXml();
      String id = IvyXml.getAttrString(xml,"ID");
      if (fait_session == null || !fait_session.equals(id)) return;
      String rslt = null;
      try {
	 switch (type) {
	    case "ANALYSIS" :
	       handleAnalysis(xml);
	       break;
	    default :
	       BoardLog.logE("BREPAIR","Unknown command " + type + " from Fait");
	       break;
	    case "ERROR" :
	       throw new Error("Fait error: " + msg.getText());
	  }
       }
      catch (Throwable e) {
	 BoardLog.logE("BREPAIR","Error processing command",e);
       }
      msg.replyTo(rslt);
    }

}	// end of inner class UpdateHandler



synchronized void handleAnalysis(Element xml)
{
   BoardLog.logD("BREPAIR","Analysis received: " + IvyXml.convertXmlToString(last_analysis));

   boolean started = IvyXml.getAttrBool(xml,"STARTED");
   boolean aborted = IvyXml.getAttrBool(xml,"ABORTED");

   if (started || aborted) {
      analysis_state = AnalysisState.PENDING;
      last_analysis = null;
    }
   else {
      analysis_state = AnalysisState.READY;
      last_analysis = xml;
    }

   notifyAll();
}


synchronized Element waitForAnalysis()
{
   for ( ; ; ) {
      if (is_running == null || !is_running) return null;

      switch (analysis_state) {
	 case NONE :
	    return null;
	 case PENDING :
	    break;
	 case READY :
	    return last_analysis;
       }
      try {
	 wait(10000);
       }
      catch (InterruptedException e) { }
    }
}


}	// end of class BrepairFaitManager




/* end of BrepairFaitManager.java */


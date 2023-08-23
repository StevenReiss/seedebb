/********************************************************************************/
/*										*/
/*		BicexEvaluationAnnot.java					*/
/*										*/
/*	Handle annotations for current evaluation viewer			*/
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



package edu.brown.cs.seedebb.bicex;

import edu.brown.cs.bubbles.bale.BaleConstants.BaleAnnotation;
import edu.brown.cs.bubbles.bale.BaleConstants.BaleFileOverview;
import edu.brown.cs.bubbles.bale.BaleFactory;
import edu.brown.cs.bubbles.board.BoardColors;
import edu.brown.cs.bubbles.board.BoardImage;
import edu.brown.cs.bubbles.board.BoardLog;
import edu.brown.cs.bubbles.board.BoardMetrics;
import edu.brown.cs.bubbles.buda.BudaBubble;
import edu.brown.cs.bubbles.buda.BudaBubbleArea;
import edu.brown.cs.bubbles.buda.BudaConstants;
import edu.brown.cs.bubbles.buda.BudaRoot;
import edu.brown.cs.ivy.swing.SwingGridPanel;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.text.BadLocationException;
import javax.swing.text.Position;

import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class BicexEvaluationAnnot extends BicexPanel implements BicexConstants
{


/********************************************************************************/
/*										*/
/*	Private Storage 							*/
/*										*/
/********************************************************************************/

private EvalAnnot	current_annotation;
private int		current_line;


/********************************************************************************/
/*										*/
/*	Constructors								*/
/*										*/
/********************************************************************************/

BicexEvaluationAnnot(BicexEvaluationViewer ev)
{
   super(ev);

   current_annotation = null;
   current_line = 0;
}



/********************************************************************************/
/*										*/
/*	 Panel methods								*/
/*										*/
/********************************************************************************/

@Override protected JComponent setupPanel()	{ return null; }

@Override void update()
{
   current_line = 0;
   checkAnnotation();
}


@Override void updateTime()
{
   checkAnnotation();
}


@Override void removePanel()
{
   removeAnnotation();
}



/********************************************************************************/
/*										*/
/*	Annotation method							*/
/*										*/
/********************************************************************************/

private void checkAnnotation()
{
   if (getContext() == null) {
      BoardLog.logD("BICEX","No context for annotations");
      return;
    }
   long when = getExecution().getCurrentTime();
   Integer ilno = getContext().getLineAtTime(when);
   BoardLog.logD("BICEX","LINE annotation at " + ilno + " " + getContext().getFileName());

   if (ilno == null || ilno == 0) {
      removeAnnotation();
    }
   else if (ilno == current_line) ;
   else {
      removeAnnotation();
      EvalAnnot ea = new EvalAnnot(ilno,when);
      synchronized (this) {
	 current_line = ilno;
	 current_annotation = ea;
	 BaleFactory.getFactory().addAnnotation(current_annotation);
       }
    }
}


private synchronized void removeAnnotation()
{
   if (current_annotation != null) {
      BaleFactory.getFactory().removeAnnotation(current_annotation);
      current_annotation = null;
      current_line = 0;
    }
}



/********************************************************************************/
/*										*/
/*	Annotation for current line						*/
/*										*/
/********************************************************************************/

private class EvalAnnot implements BaleAnnotation {

   private BicexEvaluationContext eval_context;
   private BaleFileOverview for_document;
   private Position execute_pos;
   private Color annot_color;
   private File for_file;
   private long exec_time;

   EvalAnnot(int lno,long when) {
      eval_context = getContext();
      for_file = new File(eval_context.getFileName());
      execute_pos = null;
      exec_time = when;

      for_document = BaleFactory.getFactory().getFileOverview(null,for_file,false);
      int off = for_document.findLineOffset(lno);
      execute_pos = null;
      try {
	 execute_pos = for_document.createPosition(off);
       }
      catch (BadLocationException e) {
	 BoardLog.logE("BICEX","Bad execution position",e);
       }

      annot_color = BoardColors.getColor(BICEX_EXECUTE_ANNOT_COLOR_PROP);
    }

   @Override public int getDocumentOffset()	{ return execute_pos.getOffset(); }
   @Override public File getFile()		{ return for_file; }

   @Override public Icon getIcon(BudaBubble b) {
      return BoardImage.getIcon("seedexec");
    }

   @Override public String getToolTip() {
      if (execute_pos == null) return null;
      return "Continuous execution at " + current_line;
    }

   @Override public Color getLineColor(BudaBubble bbl) {
      return annot_color;
    }

   @Override public Color getBackgroundColor()			{ return null; }

   @Override public boolean getForceVisible(BudaBubble bb) {
      BudaBubbleArea bba = BudaRoot.findBudaBubbleArea(bb);
      BudaBubbleArea bba1 = BudaRoot.findBudaBubbleArea(eval_viewer);
      if (bba != bba1) return false;
      return false;			// don't force this line to be visible
    }

   @Override public int getPriority()				{ return 20; }

   @Override public void addPopupButtons(Component c,JPopupMenu m) {
      Map<String,BicexValue> values = eval_context.getValues();
      BicexValue lnv = values.get("*LINE*");
      long prev = eval_context.getStartTime();
      long next = 0;
      for (Integer t0 : lnv.getTimeChanges()) {
	 if (t0 > exec_time) {
	    next = t0;
	    break;
	  }
	 prev = t0;
       }

      Map<String,BicexValue> whys = new LinkedHashMap<>();

      if (next == 0 && exec_time == eval_context.getEndTime()) {
	 next = exec_time;
       }

      if (prev <= 0 || next <= 0) return;
      for (Map.Entry<String,BicexValue> ent : values.entrySet()) {
	 String var = ent.getKey();
	 BicexValue bv = ent.getValue();
	 if (bv.hasChildren(next)) continue;
	 if (var.equals("*LINE*")) continue;
	 if (var.equals("*RETURNS*")) {
	    if (eval_context.getEndTime() == exec_time) whys.put(var,bv);
	    continue;
	  }
	 List<Integer> tc = bv.getTimeChanges();
	 for (Integer t0 : tc) {
	    if (t0 != 0 && t0 >= prev && t0 <= next) {
	       whys.put(var,bv);
	       break;
	     }
	    else if (t0 > next) break;
	  }
       }
      BoardLog.logD("BICEX","Found " + whys);
      for (Map.Entry<String,BicexValue> ent : whys.entrySet()) {
	 m.add(new WhyAction(ent.getKey(),ent.getValue(),exec_time));
       }
    }

}	// end of inner class EvalAnnot



/********************************************************************************/
/*										*/
/*	Variable History (why) action						*/
/*										*/
/********************************************************************************/

private class WhyAction extends AbstractAction {

   private String var_name;
   private transient BicexValue orig_value;
   private long exec_time;
   private transient BicexPanel history_panel;
   private static final long serialVersionUID = 1;

   WhyAction(String var,BicexValue val,long when) {
      super((var.equals("*RETURNS*") ? "WHY return " : "WHY is " + var) + " = " + val.getStringValue(when));
      var_name = var;
      orig_value = val;
      exec_time = when;
      history_panel = null;
      putValue(Action.NAME,getLabel());
    }

   @Override public void actionPerformed(ActionEvent evt) {
      BicexVarHistory hist = new BicexVarHistory(eval_viewer,orig_value,var_name,true);
      hist.process();
      history_panel = hist.getPanel();
      JComponent graph = history_panel.getComponent();
      SwingGridPanel pnl = new SwingGridPanel();
      pnl.beginLayout();
      pnl.addBannerLabel(getLabel());
      pnl.addLabellessRawComponent("GRAPH",graph);
      WhyBubble bbl = new WhyBubble(history_panel,pnl);
      BoardMetrics.noteCommand("BICEX","WhyQuery");
      BudaBubbleArea bba = BudaRoot.findBudaBubbleArea(eval_viewer);
      bba.addBubble(bbl,eval_viewer,null,
	    BudaConstants.PLACEMENT_LOGICAL|BudaConstants.PLACEMENT_NEW|
	    BudaConstants.PLACEMENT_MOVETO|BudaConstants.PLACEMENT_USER);
    }

   private String getLabel() {
      if (var_name.equals("*RETURNS*")) {
	 return "WHY RETURN " + orig_value.getStringValue(exec_time);
       }
      else {
	 return "WHY IS " + var_name + " = " + orig_value.getStringValue(exec_time);
       }
    }

}	// end of inner class WhyAction


private class WhyBubble extends BudaBubble {

   private transient BicexPanel history_panel;
   private static final long serialVersionUID = 1;

   WhyBubble(BicexPanel hpnl,JComponent cmp) {
      history_panel = hpnl;
      setContentPane(cmp);
    }

   @Override public void handlePopupMenu(MouseEvent evt) {
      JPopupMenu menu = new JPopupMenu();
      history_panel.handlePopupMenu(menu,evt);
      menu.add(this.getFloatBubbleAction());
      menu.show(this,evt.getX(),evt.getY());
    }

}	// end of inner class WhyBubble

}	// end of class BicexEvaluationAnnot




/* end of BicexEvaluationAnnot.java */


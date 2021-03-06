/* 
 * Copyright (c) 2002, Cameron Zemek
 * 
 * This file is part of JSpread.
 * 
 * JSpread is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * JSpread is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.capsicumcorp.swing.spreadsheet;

import java.util.*;

import net.sf.jeppers.expression.*;
import net.sf.jeppers.expression.function.*;
import net.sf.jeppers.grid.*;

/**
 * Workbook is responsible for updating formulas.
 * 
 * @version 1.0
 * 
 * @author  <a href="mailto:grom@capsicumcorp.com">Cameron Zemek</a>
 */
public class Workbook implements GridModelListener {
    // Map of cell to collection of referencing cells 
	protected Map referenceMap = new HashMap();
	
	// Map of references to formula and formula object
	protected Map formulaMap = new HashMap();
	
	// Map of worksheet names to GridModel
	protected Map worksheets = new HashMap();
	
	// Parsers expressions
	protected ExpressionCompiler compiler;
	
	// Set of dirty cells
	private transient Set dirtyCells;
    
    // Set of cells we have visted. Used to detect circular references
    private transient Set visitCells = new HashSet();

    // Don't listen to grid changes when true
	protected boolean block = false;

	/** Creates a new instance of Workbook */
	public Workbook() {
		compiler = new ExpressionCompiler();
		compiler.setFunction("if", new If());
	}

	/**
	 *  Add tableModel to worbook
	 */
	public void addWorksheet(String sheetName, GridModel gridModel) {
		gridModel.addGridModelListener(this);
		worksheets.put(sheetName, gridModel);
	} //end addWorksheet

	/**
	 *  Remove tableModel from workbook
	 */
	public void removeWorksheet(String sheetName) {
		GridModel gridModel = (GridModel) worksheets.get(sheetName);
		gridModel.removeGridModelListener(this);
		worksheets.remove(sheetName);
	} //end removeWorksheeet        
	
	private void markReferencesAsDirty(CellReference ref) throws CircularReferenceException {
		Collection refCells = (Collection) referenceMap.get(ref);
		if (refCells == null) {
			return;
		}
        if (visitCells.contains(ref)) {
            throw new CircularReferenceException();
        } else {
            visitCells.add(ref);
        }
		Iterator it = refCells.iterator();
		while (it.hasNext()) {
			FormulaInfo formula = (FormulaInfo) it.next();
			CellReference formulaRef = formula.getReference();
			if (! dirtyCells.contains(formulaRef)) {
				dirtyCells.add(formulaRef);			
				markReferencesAsDirty(formulaRef);
			}
		}        
	}
    
    private void error(CellReference ref) {
        block = true;
        dirtyCells.clear();
        markError(ref);
        block = false;
    }
    
    private void markError(CellReference ref) {
        Collection refCells = (Collection) referenceMap.get(ref);
        if (refCells == null) {
            return;
        }
        Iterator it = refCells.iterator();
        while (it.hasNext()) {
            FormulaInfo formula = (FormulaInfo) it.next();
            CellReference formulaRef = formula.getReference();
            if (! dirtyCells.contains(formulaRef)) {
                dirtyCells.add(formulaRef);                
                Expression error = new Expression(formula.formulaExpression.getExpression(), new Token[0], null, null, null) {
                    public Object getCachedResult() {
                        return "#ERROR#";
                    }
                };
                formulaRef.model.setValueAt(error, formulaRef.row, formulaRef.column);
                markError(formulaRef);
            }
        }         
    }

	/**
	 *  Listens to worksheets for changes
	 */
	public void gridChanged(GridModelEvent evt) {
		if (block) {
			/* Ignore change if it was caused by formula.update() */
			return;
		}

        GridModel source = (GridModel) evt.getSource();
        int firstRow = evt.getFirstRow();
        int lastRow = evt.getLastRow();
        int firstColumn = evt.getFirstColumn();
        int lastColumn = evt.getLastColumn();
        
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                Object value = source.getValueAt(row, column);
                CellReference ck = new CellReference(row, column, source);

                // Delete existing formula
                FormulaInfo formula = (FormulaInfo) formulaMap.get(ck);
                if (formula != null) {                  
                    formula.delete();
                }
                
                // Add new formula              
                if (value != null && value.toString().startsWith("=")) {    
                    // Convert formula into expression
                    Expression exp =
                        compiler.compile(value.toString().substring(1));

                    formula = new FormulaInfo(ck, exp);
                    formulaMap.put(ck, formula);
                    formula.update();
                } //end if
                
                // Update cells which reference the changed cell                
                // 1. Mark the cells as dirty                
                dirtyCells = new HashSet();
                try {
                    markReferencesAsDirty(ck);
                } catch (CircularReferenceException e) {                    
                    block = true;
                    Expression error = new Expression(value.toString().substring(1), new Token[0], null, null, null) {
                        public Object getCachedResult() {
                            return "#ERROR#";
                        }
                    };
                    source.setValueAt(error, row, column);
                    
                    // Propagate error to the cells that reference this one
                    error(ck);
                    
                    block = false;
                    continue;
                }
                // 2. Update the dirty cells
                while(dirtyCells.size() > 0) {                  
                    Iterator it = dirtyCells.iterator();
                    CellReference ref = (CellReference) it.next();
                    formula = (FormulaInfo) formulaMap.get(ref);
                    formula.update();
                }
            } //end for(column)
        } //end for(row)
        visitCells.clear();

        dirtyCells = null;  
	} //end gridChanged

	public void updateAll() {
		// Initialise formulas
		Iterator it = formulaMap.values().iterator();
		while (it.hasNext()) {
			FormulaInfo formula = (FormulaInfo) it.next();
			formula.update();
		}
	}
	
	/**
	 * Cell reference
	 */
	static protected class CellReference {
		private int row;
		private int column;
		private GridModel model;
		
		public CellReference(int row, int column, GridModel model) {
			this.row = row;
			this.column = column;
			this.model = model;
		}
		
		public int hashCode() {
			/* (row << 16) moves the row into the upper 
			 * 16 bits of the 32 bit hashCode
			 *  
			 * (column & 0xFFFF) truncates the column into 
			 * the lower 16 bits of the 32 bit hashCode
			 */			
			return (row << 16) + (column & 0xFFFF);
		}
		
		public boolean equals(Object obj) {
			if (!(obj instanceof CellReference)) {
				return false;
			}
			
			CellReference ck = (CellReference) obj;
			return (row == ck.row 
					&& column == ck.column
					&& model == ck.model);
		}
	}

	/**
	 *  FormulaInfo information
	 */
	protected class FormulaInfo {
		protected CellReference cellRef;
		protected Expression formulaExpression;
		// Map of variable names to cell references
		protected Map varRefMap = new HashMap();

		public FormulaInfo(
			CellReference cellReference,
			Expression exp) {
			this.cellRef = cellReference;
			this.formulaExpression = exp;

			// build cell reference table
			Iterator i = exp.getReferences().iterator();
			while (i.hasNext()) {
				Object variableKey = i.next();
				String variableName = variableKey.toString();

				GridModel refModel = null;
				int index = 0; //parse position

				// Parse sheet name
				int endSheetNameIndex = variableName.indexOf("!");
				if (endSheetNameIndex == -1) { //no sheet name
					refModel = cellRef.model;
				} else {
					String sheetName =
						variableName.substring(0, endSheetNameIndex).toUpperCase();
					refModel = (GridModel) worksheets.get(sheetName);
				} //end if				
				index = endSheetNameIndex + 1; //consume '!' character

				// Parse column
				char colChar = 'A';
				StringBuffer colBuff = new StringBuffer(variableName.length());
				do {
					colChar = variableName.charAt(index);
					if (Character.isLetter(colChar)) {
						colBuff.append(colChar);
						index++;
					} //end if                    
				} while (Character.isLetter(colChar));

				String colName = colBuff.toString();
				int lastPos = colName.length() - 1; // last index position in colName
				int column = -1; // we subtract 1 from final result
				for (int strIndex = lastPos;
					strIndex >= 0;
					strIndex--) {
					column += ((colName.charAt(strIndex) - 'A') + 1)
						* (int) Math.pow(26, lastPos - strIndex);
				}

				int row = Integer.parseInt(variableName.substring(index));
				
				CellReference reference = new CellReference(row, column, refModel);
				varRefMap.put(variableName, reference);
				
				// Listen to changes on the reference cell
				Collection refCells = (Collection) referenceMap.get(reference);
				if (refCells == null) {
					refCells = new HashSet();
					referenceMap.put(reference, refCells);
				}
				refCells.add(this);
			} //end while
		} //end constructor
		
		public void delete() {
			// Stop listening to changes on the reference cells
			Iterator it = varRefMap.values().iterator();
			while (it.hasNext()) {
				CellReference ref = (CellReference) it.next();
				Collection refCells = (Collection) referenceMap.get(ref);
				refCells.remove(this);								
			}
			formulaMap.remove(this);
		}

		public CellReference getReference() {
			return cellRef;
		}

		/**
		 *  Update the formula
		 */
		public void update() {			
			// Update variable values
			Iterator i = formulaExpression.getReferences().iterator();
			while (i.hasNext()) {
				Object variableKey = i.next();
				String variableName = variableKey.toString();

				CellReference reference = (CellReference) varRefMap.get(variableName);
				int row = reference.row;
				int column = reference.column;
				GridModel model = (GridModel) reference.model;
				
				// If reference cell needs updating
				if (dirtyCells != null && dirtyCells.contains(reference)) {
					FormulaInfo formula = (FormulaInfo) formulaMap.get(reference);
					formula.update();
				}

				Object variableValue = model.getValueAt(row, column);
				if (variableValue instanceof Expression) {
					variableValue = ((Expression) variableValue).evaluate();
				}

				if (variableValue == null) {
					variableValue = "";
				}
				compiler.setVariable(variableName, variableValue);
			} //end while			
			
			block = true;
			formulaExpression.evaluate();
			cellRef.model.setValueAt(
				formulaExpression,
				cellRef.row,
				cellRef.column);
			block = false;
			
			if (dirtyCells != null) {
				dirtyCells.remove(cellRef);
			}
		} //end update
	} //end inner class FormulaInfo
} //end Workbook

package org.faaguilar.webui.apps.form;

import static org.adempiere.webui.ClientInfo.MEDIUM_WIDTH;
import static org.adempiere.webui.ClientInfo.SMALL_WIDTH;
import static org.adempiere.webui.ClientInfo.maxWidth;

import java.sql.Timestamp;
import java.util.Enumeration;
import java.util.Properties;
import java.util.Vector;
import java.util.logging.Level;

import org.adempiere.webui.ClientInfo;
import org.adempiere.webui.LayoutUtils;
import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Checkbox;
import org.adempiere.webui.component.DocumentLink;
import org.adempiere.webui.component.Grid;
import org.adempiere.webui.component.GridFactory;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.ListModelTable;
import org.adempiere.webui.component.Panel;
import org.adempiere.webui.component.Row;
import org.adempiere.webui.component.Rows;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.component.WListbox;
import org.adempiere.webui.editor.WDateEditor;
import org.adempiere.webui.editor.WSearchEditor;
import org.adempiere.webui.editor.WTableDirEditor;
import org.adempiere.webui.event.ValueChangeEvent;
import org.adempiere.webui.event.ValueChangeListener;
import org.adempiere.webui.event.WTableModelEvent;
import org.adempiere.webui.event.WTableModelListener;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.panel.CustomForm;
import org.adempiere.webui.panel.IFormController;
import org.adempiere.webui.util.ZKUpdateUtil;
import org.adempiere.webui.window.Dialog;
import org.compiere.model.MAllocationHdr;
import org.compiere.model.MInvoice;
import org.compiere.model.MLookup;
import org.compiere.model.MLookupFactory;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.compiere.util.Trx;
import org.compiere.util.TrxRunnable;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zul.Borderlayout;
import org.zkoss.zul.Center;
import org.zkoss.zul.Hbox;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.North;
import org.zkoss.zul.South;

/**
 * Ventana ZK para asignación con soporte a dos BPs.
 * - BP1 => Pagos
 * - BP2 => Facturas
 *
 * Basada en WAllocation10 pero agregando bpartnerSearch2 y la carga con Allocation_MultiBP.
 */
public class WAllocation extends Allocation
	implements IFormController, EventListener<Event>, WTableModelListener, ValueChangeListener
{
	private CustomForm form = new CustomForm();

	// Elementos UI
	private Borderlayout mainLayout = new Borderlayout();
	private Panel parameterPanel = new Panel();
	private Grid parameterLayout = GridFactory.newGridLayout();
	
	private Label bpartnerLabel = new Label();
	private WSearchEditor bpartnerSearch = null;   // BP1

	private Label bpartnerLabel2 = new Label();
	private WSearchEditor bpartnerSearch2 = null;  // BP2

	private Label currencyLabel = new Label();
	private WTableDirEditor currencyPick = null;
	private Checkbox multiCurrency = new Checkbox();
	private Label chargeLabel = new Label();
	private Label dateLabel = new Label();
	private WDateEditor dateField = new WDateEditor();
	private Checkbox autoWriteOff = new Checkbox();
	private Label organizationLabel = new Label();
	private WTableDirEditor organizationPick;

	private Borderlayout infoPanel = new Borderlayout();
	private Panel paymentPanel = new Panel();
	private Panel invoicePanel = new Panel();

	private Borderlayout invoiceLayout = new Borderlayout();
	private Label invoiceLabel = new Label();
	private WListbox invoiceTable = new WListbox();
	private Label invoiceInfo = new Label();

	private Borderlayout paymentLayout = new Borderlayout();
	private Label paymentLabel = new Label();
	private WListbox paymentTable = new WListbox();
	private Label paymentInfo = new Label();

	private Panel allocationPanel = new Panel();
	private Grid allocationLayout = GridFactory.newGridLayout();
	private Label differenceLabel = new Label();
	private Textbox differenceField = new Textbox();
	private Button allocateButton = new Button();
	private Button refreshButton = new Button();
	private WTableDirEditor chargePick = null;
	private Label DocTypeLabel = new Label();
	private WTableDirEditor DocTypePick = null;
	private Label allocCurrencyLabel = new Label();

	private Hlayout statusBar = new Hlayout();

	/** Número de columnas en parameterLayout */
	private int noOfColumn;

	public WAllocation() {
		try {
			Properties ctx = Env.getCtx();
			int C_Invoice_ID = getC_InvoiceIdFromContext(ctx);
			super.dynInit(); 
			dynInit();
			zkInit();
			calculate();
			if (C_Invoice_ID > 0) {
				MInvoice invoice = MInvoice.get(C_Invoice_ID);
				bpartnerSearch.setValue(invoice.getC_BPartner_ID());
				bpartnerSearch2.setValue(invoice.getC_BPartner_ID());
				m_C_BPartner2_ID = invoice.getC_BPartner_ID();
				m_C_BPartner_ID = invoice.getC_BPartner_ID();
				loadBPartner();
			}
			
		} catch(Exception e) {
			log.log(Level.SEVERE, "", e);
		}

		if (ClientInfo.isMobile()) {
			ClientInfo.onClientInfo(form, this::onClientInfo);
		}
	}

	/**
	 * Inicializa componentes UI (combos, search, etc.).
	 */
	public void dynInit() throws Exception {
		//  Moneda
		int AD_Column_ID = org.compiere.model.SystemIDs.COLUMN_C_INVOICE_C_CURRENCY_ID; 
		MLookup lookupCur = MLookupFactory.get(Env.getCtx(), form.getWindowNo(), 0, AD_Column_ID, DisplayType.TableDir);
		currencyPick = new WTableDirEditor("C_Currency_ID", true, false, true, lookupCur);
		currencyPick.setValue(getC_Currency_ID());
		currencyPick.addValueChangeListener(this);

		// Organización
		AD_Column_ID = org.compiere.model.SystemIDs.COLUMN_C_PERIOD_AD_ORG_ID; 
		MLookup lookupOrg = MLookupFactory.get(Env.getCtx(), form.getWindowNo(), 0, AD_Column_ID, DisplayType.TableDir);
		organizationPick = new WTableDirEditor("AD_Org_ID", true, false, true, lookupOrg);
		organizationPick.setValue(Env.getAD_Org_ID(Env.getCtx()));
		organizationPick.addValueChangeListener(this);

		// BPartner 1 (pagos)
		AD_Column_ID = org.compiere.model.SystemIDs.COLUMN_C_INVOICE_C_BPARTNER_ID;
		MLookup lookupBP = MLookupFactory.get(Env.getCtx(), form.getWindowNo(), 0, AD_Column_ID, DisplayType.Search);
		bpartnerSearch = new WSearchEditor("C_BPartner_ID", true, false, true, lookupBP);
		bpartnerSearch.addValueChangeListener(this);

		// BPartner 2 (facturas)
		AD_Column_ID = org.compiere.model.SystemIDs.COLUMN_C_INVOICE_C_BPARTNER_ID;
		MLookup lookupBP2 = MLookupFactory.get(Env.getCtx(), form.getWindowNo(), 0, AD_Column_ID, DisplayType.Search);
		bpartnerSearch2 = new WSearchEditor("C_BPartner2_ID", true, false, true, lookupBP2);
		bpartnerSearch2.addValueChangeListener(this);

		// Campo status bar
		statusBar.appendChild(new Label(Msg.getMsg(Env.getCtx(), "AllocateStatus")));
		ZKUpdateUtil.setVflex(statusBar, "min");

		// Fecha
		dateField.setValue(Env.getContextAsDate(Env.getCtx(), Env.DATE));
		dateField.addValueChangeListener(this);

		// Charge
		AD_Column_ID = 61804; // C_AllocationLine.C_Charge_ID
		MLookup lookupCharge = MLookupFactory.get(Env.getCtx(), form.getWindowNo(), 0, AD_Column_ID, DisplayType.TableDir);
		chargePick = new WTableDirEditor("C_Charge_ID", false, false, true, lookupCharge);
		chargePick.setValue(getC_Charge_ID());
		chargePick.addValueChangeListener(this);

		// DocType
		AD_Column_ID = 212213; 
		MLookup lookupDocType = MLookupFactory.get(Env.getCtx(), form.getWindowNo(), 0, AD_Column_ID, DisplayType.TableDir);
		DocTypePick = new WTableDirEditor("C_DocType_ID", false, false, true, lookupDocType);
		DocTypePick.setValue(getC_DocType_ID());
		DocTypePick.addValueChangeListener(this);
	}

	private void zkInit() throws Exception {
		form.appendChild(mainLayout);
		ZKUpdateUtil.setWidth(mainLayout, "100%");
		ZKUpdateUtil.setHeight(mainLayout, "100%");
		mainLayout.setStyle("min-height: 600px");

		dateLabel.setText(Msg.getMsg(Env.getCtx(), "Date"));
		autoWriteOff.setSelected(false);
		autoWriteOff.setText(Msg.getMsg(Env.getCtx(), "AutoWriteOff", true));

		parameterPanel.appendChild(parameterLayout);
		allocationPanel.appendChild(allocationLayout);

		bpartnerLabel.setText(Msg.translate(Env.getCtx(), "C_BPartner_ID") + " (Pagos)");
		bpartnerLabel2.setText(Msg.translate(Env.getCtx(), "C_BPartner_ID") + " 2 (Facturas)");

		paymentLabel.setText(" " + Msg.translate(Env.getCtx(), "C_Payment_ID"));
		invoiceLabel.setText(" " + Msg.translate(Env.getCtx(), "C_Invoice_ID"));

		paymentPanel.appendChild(paymentLayout);
		invoicePanel.appendChild(invoiceLayout);
		invoiceInfo.setText(".");
		paymentInfo.setText(".");
		chargeLabel.setText(" " + Msg.translate(Env.getCtx(), "C_Charge_ID"));
		DocTypeLabel.setText(" " + Msg.translate(Env.getCtx(), "C_DocType_ID"));	
		differenceLabel.setText(Msg.getMsg(Env.getCtx(), "Difference"));
		differenceField.setValue("0");
		differenceField.setReadonly(true);

		allocateButton.setLabel(Msg.getMsg(Env.getCtx(), "Process"));
		allocateButton.addActionListener(this);
		refreshButton.setLabel(Msg.getMsg(Env.getCtx(), "Refresh"));
		refreshButton.addActionListener(this);

		currencyLabel.setText(Msg.translate(Env.getCtx(), "C_Currency_ID"));
		multiCurrency.setText(Msg.getMsg(Env.getCtx(), "MultiCurrency"));
		multiCurrency.addActionListener(this);
		allocCurrencyLabel.setText(".");
		organizationLabel.setText(Msg.translate(Env.getCtx(), "AD_Org_ID"));

		// Panel superior
		North north = new North();
		north.setBorder("none");
		north.setSplittable(true);
		north.setCollapsible(true);
		mainLayout.appendChild(north);
		north.appendChild(parameterPanel);

		layoutParameterAndSummary();

		// Panel de pagos
		paymentPanel.appendChild(paymentLayout);
		ZKUpdateUtil.setWidth(paymentPanel, "100%");
		ZKUpdateUtil.setHeight(paymentPanel, "100%");
		ZKUpdateUtil.setVflex(paymentPanel, "1");
		ZKUpdateUtil.setVflex(paymentLayout, "1");

		// Panel de facturas
		invoicePanel.appendChild(invoiceLayout);
		ZKUpdateUtil.setWidth(invoicePanel, "100%");
		ZKUpdateUtil.setHeight(invoicePanel, "100%");
		ZKUpdateUtil.setVflex(invoicePanel, "1");
		ZKUpdateUtil.setVflex(invoiceLayout, "1");

		// Payment layout north
		north = new North();
		north.setBorder("none");
		paymentLayout.appendChild(north);
		north.appendChild(paymentLabel);

		South south = new South();
		south.setBorder("none");
		paymentLayout.appendChild(south);
		south.appendChild(paymentInfo.rightAlign());
		Center center = new Center();
		paymentLayout.appendChild(center);
		center.appendChild(paymentTable);
		ZKUpdateUtil.setWidth(paymentTable, "100%");
		ZKUpdateUtil.setVflex(paymentTable, "1");

		// Invoice layout
		north = new North();
		north.setBorder("none");
		invoiceLayout.appendChild(north);
		north.appendChild(invoiceLabel);
		south = new South();
		south.setBorder("none");
		invoiceLayout.appendChild(south);
		south.appendChild(invoiceInfo.rightAlign());
		center = new Center();
		invoiceLayout.appendChild(center);
		center.appendChild(invoiceTable);
		ZKUpdateUtil.setWidth(invoiceTable, "100%");
		ZKUpdateUtil.setVflex(invoiceTable, "1");

		// Centro principal: panel con pagos arriba y facturas abajo
		center = new Center();
		mainLayout.appendChild(center);
		center.appendChild(infoPanel);
		infoPanel.setStyle("border: none");
		ZKUpdateUtil.setWidth(infoPanel, "100%");
		ZKUpdateUtil.setVflex(infoPanel, "1");

		north = new North();
		north.setBorder("none");
		infoPanel.appendChild(north);
		north.appendChild(paymentPanel);
		north.setAutoscroll(true);
		north.setSplittable(true);
		north.setSize("50%");
		north.setCollapsible(true);

		center = new Center();
		center.setBorder("none");
		infoPanel.appendChild(center);
		center.appendChild(invoicePanel);
		center.setAutoscroll(true);
		infoPanel.setStyle("min-height: 300px;");

		// Sur: panel de allocation
//		south = new South();
//		south.setBorder("none");
//		mainLayout.appendChild(south);
//		south.appendChild(allocationPanel);
//		allocationPanel.appendChild(allocationLayout);
//		allocationPanel.appendChild(statusBar);
		
		
		// footer/allocations layout
		south = new South();
		south.setBorder("none");
		mainLayout.appendChild(south);
		south.appendChild(allocationPanel);
		allocationPanel.appendChild(allocationLayout);
		allocationPanel.appendChild(statusBar);
		ZKUpdateUtil.setWidth(allocationLayout, "100%");
		ZKUpdateUtil.setHflex(allocationPanel, "1");
		ZKUpdateUtil.setVflex(allocationPanel, "min");
		ZKUpdateUtil.setVflex(allocationLayout, "min");
		ZKUpdateUtil.setVflex(statusBar, "min");
		ZKUpdateUtil.setVflex(south, "min");
		
		
	}

	private void layoutParameterAndSummary() {
		setupParameterColumns();
		Rows rows = parameterLayout.newRows();
		Row row = rows.newRow();

		// Fila 1: BP1 + Fecha
		row.appendCellChild(bpartnerLabel.rightAlign());
		ZKUpdateUtil.setHflex(bpartnerSearch.getComponent(), "true");
		row.appendCellChild(bpartnerSearch.getComponent(),1);
		row.appendChild(dateLabel.rightAlign());
		row.appendChild(dateField.getComponent());

		// Fila 2: BP2 + Organización
		row = rows.newRow();
		row.appendCellChild(bpartnerLabel2.rightAlign());
		ZKUpdateUtil.setHflex(bpartnerSearch2.getComponent(), "true");
		row.appendCellChild(bpartnerSearch2.getComponent(), 1);

		row.appendCellChild(organizationLabel.rightAlign());
		ZKUpdateUtil.setHflex(organizationPick.getComponent(), "true");
		row.appendCellChild(organizationPick.getComponent(), 1);

		// Fila 3: Moneda + checkbox multicurrency + AutoWriteOff
		row = rows.newRow();
		row.appendCellChild(currencyLabel.rightAlign(),1);
		ZKUpdateUtil.setHflex(currencyPick.getComponent(), "true");
		row.appendCellChild(currencyPick.getComponent(),1);

		Hbox cbox = new Hbox();
		cbox.setWidth("100%");
		if (noOfColumn == 6) {
			cbox.setPack("center");
		} else {
			cbox.setPack("end");
		}
		cbox.appendChild(multiCurrency);
		cbox.appendChild(autoWriteOff);
		row.appendCellChild(cbox, 2);

		if (noOfColumn < 6) {
			LayoutUtils.compactTo(parameterLayout, noOfColumn);
		} else {
			LayoutUtils.expandTo(parameterLayout, noOfColumn, true);
		}

		// Footer
		Rows rowsAlloc = allocationLayout.newRows();
		row = rowsAlloc.newRow();

		Hlayout diffLayout = new Hlayout();
		diffLayout.appendChild(differenceLabel.rightAlign());
		diffLayout.appendChild(allocCurrencyLabel.rightAlign());
		row.appendCellChild(diffLayout);

		ZKUpdateUtil.setHflex(differenceField, "true");
		row.appendCellChild(differenceField);
		row.appendCellChild(chargeLabel.rightAlign());
		ZKUpdateUtil.setHflex(chargePick.getComponent(), "true");
		row.appendCellChild(chargePick.getComponent());
		row.appendCellChild(DocTypeLabel.rightAlign());
		ZKUpdateUtil.setHflex(DocTypePick.getComponent(), "true");
		row.appendCellChild(DocTypePick.getComponent());

		row = rowsAlloc.newRow();
		Hbox box = new Hbox();
		box.setWidth("100%");
		box.setPack("end");
		box.appendChild(allocateButton);
		box.appendChild(refreshButton);
		row.appendCellChild(box, 2);
	}

	private void setupParameterColumns() {
		noOfColumn = 6;
	}

	/**
	 * Evento al cambiar checkbox o dar click a botones "Process" o "Refresh".
	 */
	@Override
	public void onEvent(Event e) {
		if (log.isLoggable(Level.CONFIG)) {
			log.config("Evento=" + e.getTarget());
		}
		if (e.getTarget().equals(multiCurrency)) {
			loadBPartner(); // recargar con multi-moneda on/off
		} else if (e.getTarget().equals(allocateButton)) {
			allocateButton.setEnabled(false);
			MAllocationHdr allocation = saveAllocationData();
			loadBPartner();
			allocateButton.setEnabled(true);
			if (allocation != null) {
//				Dialog.info(form.getWindowNo(), Msg.getMsg(Env.getCtx(), "AllocationCreated") 
//					+ " " + allocation.getDocumentNo());
				
				DocumentLink link = new DocumentLink(Msg.getElement(Env.getCtx(), MAllocationHdr.COLUMNNAME_C_AllocationHdr_ID) + ": " + allocation.getDocumentNo(), allocation.get_Table_ID(), allocation.get_ID());				
				statusBar.appendChild(link);
				
			}
		} else if (e.getTarget().equals(refreshButton)) {
			loadBPartner();
		}
	}

	@Override
	public void tableChanged(WTableModelEvent event) {
		boolean isUpdate = (event.getType() == WTableModelEvent.CONTENTS_CHANGED);
		if (!isUpdate) {
			calculate();
			return;
		}

		int row = event.getFirstRow();
		int col = event.getColumn();
		if (row < 0) {
			return;
		}

		boolean isInvoice = (event.getModel().equals(invoiceTable.getModel()));
		boolean isAutoWO = autoWriteOff.isSelected();

		String msg = writeOff(row, col, isInvoice, paymentTable, invoiceTable, isAutoWO);

		// Actualiza la fila
		ListModelTable model = (ListModelTable) event.getModel();
		model.updateComponent(row);

		if (msg != null && msg.length() > 0) {
			Dialog.warn(form.getWindowNo(), "AllocationWriteOffWarn");
		}
		calculate();
	}

	@Override
	public void valueChange(ValueChangeEvent e) {
		String name = e.getPropertyName();
		Object value = e.getNewValue();
		if (log.isLoggable(Level.CONFIG)) {
			log.config(name + " = " + value);
		}
		if (value == null && (!"C_Charge_ID".equals(name) && !"C_DocType_ID".equals(name))) {
			return;
		}

		if (name.equals("AD_Org_ID")) {
			setAD_Org_ID((int) value);
			loadBPartner();
		} else if (name.equals("C_Charge_ID")) {
			setC_Charge_ID(value!=null ? (Integer)value : 0);
			setAllocateButton();
		} else if (name.equals("C_DocType_ID")) {
			setC_DocType_ID(value!=null ? (Integer)value : 0);
		} else if (name.equals("C_BPartner_ID")) {
			bpartnerSearch.setValue(value);
			setC_BPartner_ID(value != null ? (Integer)value : 0);
			loadBPartner();
		} else if (name.equals("C_BPartner2_ID")) {
			bpartnerSearch2.setValue(value);
			setC_BPartner2_ID(value != null ? (Integer)value : 0);
			loadBPartner();
		} else if (name.equals("C_Currency_ID")) {
			setC_Currency_ID((int) value);
			loadBPartner();
		} else if (name.equals("Date") && multiCurrency.isSelected()) {
			loadBPartner();
		}
	}

	private void setAllocateButton() {
		allocateButton.setEnabled(isOkToAllocate());
		if (getTotalDifference().signum() == 0) {
			chargePick.setValue(null);
			setC_Charge_ID(0);
		}
	}

	/**
	 * Carga la info de pagos (BP1) e facturas (BP2) usando los métodos
	 * de Allocation10_MultiBP.
	 */
	private void loadBPartner() {
		checkBPartner(); // Esto invoca checkBPartner1 + checkBPartner2

		// Pagos del BP1
		Vector<Vector<Object>> data = getPaymentData(multiCurrency.isSelected(), dateField.getValue(), null);
		Vector<String> columnNames = getPaymentColumnNames(multiCurrency.isSelected());
		paymentTable.clear();
		paymentTable.getModel().removeTableModelListener(this);
		ListModelTable modelP = new ListModelTable(data);
		modelP.addTableModelListener(this);
		paymentTable.setData(modelP, columnNames);
		setPaymentColumnClass(paymentTable, multiCurrency.isSelected());

		// Facturas del BP2
		data = getInvoiceData(multiCurrency.isSelected(), (Timestamp) dateField.getValue(), null);
		columnNames = getInvoiceColumnNames(multiCurrency.isSelected());
		invoiceTable.clear();
		invoiceTable.getModel().removeTableModelListener(this);
		ListModelTable modelI = new ListModelTable(data);
		modelI.addTableModelListener(this);
		invoiceTable.setData(modelI, columnNames);
		setInvoiceColumnClass(invoiceTable, multiCurrency.isSelected());

		calculate();
		statusBar.getChildren().clear();
	}

	private MAllocationHdr saveAllocationData() {
		if (getAD_Org_ID() > 0) {
			Env.setContext(Env.getCtx(), form.getWindowNo(), "AD_Org_ID", getAD_Org_ID());
		} else {
			Env.setContext(Env.getCtx(), form.getWindowNo(), "AD_Org_ID", "");
		}
		final MAllocationHdr[] allocation = new MAllocationHdr[1];
		try {
			Trx.run(new TrxRunnable() {
				@Override
				public void run(String trxName) {
					allocation[0] = saveData(form.getWindowNo(),
						(Timestamp) dateField.getValue(),
						paymentTable,
						invoiceTable,
						trxName);
				}
			});
		} catch (Exception ex) {
			Dialog.error(form.getWindowNo(), "Error", ex.getLocalizedMessage());
			return null;
		}
		return allocation[0];
	}


	public void calculate() {
		calculate(paymentTable, invoiceTable, multiCurrency.isSelected());

		paymentInfo.setText(getPaymentInfoText());
		invoiceInfo.setText(getInvoiceInfoText());
		differenceField.setText(format.format(getTotalDifference()));

		if (allocDate != null) {
			if (!allocDate.equals(dateField.getValue())) {
				dateField.setValue(allocDate);
			}
		}
		allocCurrencyLabel.setText(currencyPick.getDisplay());
		setAllocateButton();
	}

	/**
	 * Handle onClientInfo event from browser.
	 */
	protected void onClientInfo()
	{
		if (ClientInfo.isMobile() && form.getPage() != null) 
		{
			if (noOfColumn > 0 && parameterLayout.getRows() != null)
			{
				int t = 6;
				if (maxWidth(MEDIUM_WIDTH-1))
				{
					if (maxWidth(SMALL_WIDTH-1))
						t = 2;
					else
						t = 4;
				}
				if (t != noOfColumn)
				{
					parameterLayout.getRows().detach();
					if (parameterLayout.getColumns() != null)
						parameterLayout.getColumns().detach();
					if (mainLayout.getSouth() != null)
						mainLayout.getSouth().detach();
					if (allocationLayout.getRows() != null)
						allocationLayout.getRows().detach();
					layoutParameterAndSummary();
					form.invalidate();
				}
			}
		}
	}
	
	public static int getC_InvoiceIdFromContext(Properties ctx) {
	    // Opción A: Buscar la clave específica que termina con |C_Invoice_ID
	    Enumeration<?> keys = ctx.propertyNames();
	    while (keys.hasMoreElements()) {
	        String key = (String) keys.nextElement();
	        if (key.endsWith("|C_Invoice_ID")) {
	            String value = ctx.getProperty(key);
	            if (value != null && !value.isEmpty()) {
	                return Integer.parseInt(value);
	            }
	        }
	    }
	    
	    // Opción B: Buscar el patrón {WindowNo}|{TabNo}|C_Invoice_ID
	    // Como el WindowNo es variable, iteramos sobre posibles valores
	    for (int wNo = 0; wNo <= 10; wNo++) {
	        for (int tNo = 0; tNo <= 5; tNo++) {
	            String key = wNo + "|" + tNo + "|C_Invoice_ID";
	            String value = ctx.getProperty(key);
	            if (value != null && !value.isEmpty()) {
	                return Integer.parseInt(value);
	            }
	        }
	    }
	    
	    return -1; // No encontrado
	}

	@Override
	public ADForm getForm() {
		return form;
	}

}
